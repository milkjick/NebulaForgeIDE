package com.nebulaforge.core.mcp

import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * MCP 服务器**真实可达状态**。
 *
 * 之前 `McpServerSnapshot.connected` 只要「注册过」就恒为 `true`，于是界面永远显示「已连接」，
 * 用户说的「嗅探不到外部服务器状态」就是这么来的：远端挂了、URL 写错、进程没起，界面看起来都是好的。
 * 现在状态由一次真实的 `initialize` + `tools/list` 往返决定。
 */
enum class McpServerState {
    /** 进程内注册的内部 Server（随工作区生命周期存在，无需网络探测）。 */
    INTERNAL,

    /** 已注册但尚未探测（刚添加、刚打开界面）。 */
    IDLE,

    /** 正在探测。 */
    PROBING,

    /** 探测成功：握手 + 工具列表都拿到了。 */
    READY,

    /** 探测失败：网络不通、HTTP 非 2xx、协议不匹配等，原因见 [McpServerSnapshot.detail]。 */
    ERROR
}

/** 界面直接用的一份快照：状态 + 工具数 + 失败原因 + 探测时间。 */
data class McpServerSnapshot(
    val id: String,
    val internal: Boolean,
    val connected: Boolean,
    val toolCount: Int,
    val state: McpServerState = if (internal) McpServerState.INTERNAL else McpServerState.IDLE,
    val detail: String? = null,
    val probedAt: Long = 0L,
    /** 实际使用的传输协议（Streamable HTTP / HTTP+SSE），便于界面显示与排查。 */
    val transport: McpTransport? = null
)

/** MCP Host：统一管理内部 Server 与外部 Streamable HTTP Client。 */
class McpHost {
    private val _snapshots = MutableStateFlow<List<McpServerSnapshot>>(emptyList())
    val snapshots: StateFlow<List<McpServerSnapshot>> = _snapshots.asStateFlow()
    private val internal = ConcurrentHashMap<String, InternalMcpServer>()
    /**
     * 远端客户端：HTTP 与 stdio 都实现 [McpRemoteClient]，因此探测状态机、快照、关闭流程
     * 完全不必区分传输方式（新增传输时上层零改动）。
     */
    private val remote = ConcurrentHashMap<String, McpRemoteClient>()

    /** 状态机与探测结果缓存（key = server id）。 */
    private val states = ConcurrentHashMap<String, McpServerState>()
    private val details = ConcurrentHashMap<String, String>()
    private val probedAt = ConcurrentHashMap<String, Long>()

    /**
     * 探测得到的工具定义缓存。
     *
     * 工具列表只能靠一次真实往返拿到，**不能在 `publish()` 里顺手去抓**（那会让每次状态刷新都发网络请求，
     * 在 UI 线程上就是卡顿 + 假连接）。这里只读缓存，缓存由 [probe] / [listRemoteTools] 填充。
     */
    private val tools = ConcurrentHashMap<String, List<McpToolDefinition>>()

    /** 每个远端服务器实际用的传输协议。 */
    private val transports = ConcurrentHashMap<String, McpTransport>()

    @Synchronized
    fun registerInternal(id: String, server: InternalMcpServer) {
        require(id.isNotBlank())
        internal[id] = server
        states[id] = McpServerState.INTERNAL
        publish()
    }

    @Synchronized
    fun connectRemote(
        id: String,
        endpoint: URI,
        transport: McpTransport = McpTransport.detect(endpoint)
    ): McpHttpClient {
        require(id.isNotBlank())
        remote[id]?.close()
        // 传输协议自动识别（`/sse` → 老式 HTTP+SSE，其余 → Streamable HTTP），也允许调用方显式指定。
        val client = McpHttpClient(endpoint, transport)
        transports[id] = transport
        remote[id] = client
        // 注册 ≠ 连通：先记 IDLE，等一次真实探测再给结论。
        states[id] = McpServerState.IDLE
        details.remove(id)
        tools.remove(id)
        publish()
        return client
    }

    /**
     * 接入一个 **stdio** MCP 服务器：由 [factory] 按设备通道拉起长驻进程（无 Root 时走 Shizuku shell）。
     *
     * 进程怎么起取决于设备能力，所以由 app 注入 factory，core 层不关心 Shizuku/Root/应用内细节。
     */
    @Synchronized
    fun connectStdio(
        id: String,
        command: String,
        workdir: String? = null,
        factory: McpStdioProcessFactory
    ): McpStdioClient {
        require(id.isNotBlank())
        require(command.isNotBlank()) { "stdio 服务器必须提供启动命令" }
        remote[id]?.close()
        val client = McpStdioClient(command, workdir, factory)
        transports[id] = McpTransport.STDIO
        remote[id] = client
        states[id] = McpServerState.IDLE
        details.remove(id)
        tools.remove(id)
        publish()
        return client
    }

    fun initializeRemote(id: String): JSONObject = remote[id]?.initialize() ?: error("未知 MCP Server：$id")

    fun listRemoteTools(id: String): JSONObject =
        (remote[id] ?: error("未知 MCP Server：$id")).listTools().also { result ->
            tools[id] = runCatching { parseTools(result) }.getOrDefault(emptyList())
            states[id] = McpServerState.READY
            details.remove(id)
            probedAt[id] = System.currentTimeMillis()
            publish()
        }

    @Synchronized
    fun remove(id: String) {
        internal.remove(id)
        remote.remove(id)?.close()
        states.remove(id)
        details.remove(id)
        probedAt.remove(id)
        tools.remove(id)
        transports.remove(id)
        publish()
    }

    @Synchronized
    fun closeAll() {
        remote.values.forEach { it.close() }
        remote.clear()
        internal.clear()
        states.clear()
        details.clear()
        probedAt.clear()
        tools.clear()
        transports.clear()
        publish()
    }

    fun internalServers(): List<String> = internal.keys.toList().sorted()

    fun internalTools(id: String): List<McpToolDefinition> = internal[id]?.listTools().orEmpty()

    /** 远端工具定义（读缓存，不发网络请求）。缓存为空时请先 [probe]。 */
    fun remoteToolDefinitions(id: String): List<McpToolDefinition> = tools[id].orEmpty()

    fun serverIds(): List<String> = (internal.keys + remote.keys).toSet().sorted()

    fun remoteServers(): List<String> = remote.keys.toList().sorted()

    fun stateOf(id: String): McpServerState = states[id] ?: McpServerState.IDLE

    fun detailOf(id: String): String? = details[id]

    fun snapshotOf(id: String): McpServerSnapshot = snapshots.value.firstOrNull { it.id == id }
        ?: McpServerSnapshot(id, internal.containsKey(id), false, 0, stateOf(id), detailOf(id), probedAt[id] ?: 0L)

    /** 让界面先把球转起来（探测前调用）。 */
    @Synchronized
    fun markProbing(id: String) {
        if (!remote.containsKey(id)) return
        states[id] = McpServerState.PROBING
        publish()
    }

    /**
     * 一次真实探测：`initialize` 握手 + `tools/list`。
     *
     * 在 IO 线程执行（HTTP 客户端是阻塞的），异常一律收敛成 [McpServerState.ERROR] + 原因字符串，
     * 绝不抛给 UI —— 状态界面本身不该因为服务器挂了而崩。
     */
    suspend fun probe(id: String): McpServerSnapshot {
        if (!remote.containsKey(id)) return snapshotOf(id)
        markProbing(id)
        val failure = withContext(Dispatchers.IO) {
            runCatching {
                val client = requireNotNull(remote[id])
                client.initialize()
                val result = client.listTools()
                tools[id] = parseTools(result)
                states[id] = McpServerState.READY
                details.remove(id)
                null
            }.getOrElse { it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.simpleName }
        }
        if (failure != null) {
            states[id] = McpServerState.ERROR
            details[id] = failure
            tools.remove(id)
            // 探测失败就断开，避免后续调用撞上同一个坏会话。
            runCatching { remote[id]?.close() }
        }
        probedAt[id] = System.currentTimeMillis()
        publish()
        return snapshotOf(id)
    }

    /** 逐个探测（串行，避免真机上一次开太多连接）。 */
    suspend fun probeAll(ids: List<String>): List<McpServerSnapshot> = ids.map { probe(it) }

    suspend fun handleInternal(id: String, requestJson: String): String =
        internal[id]?.handle(McpJsonRpc.parseRequest(requestJson)) ?: McpJsonRpc.error(null, -32004, "未知 MCP Server：$id")

    suspend fun callInternalTool(id: String, name: String, args: JSONObject = JSONObject()): JSONObject =
        internal[id]?.call(name, args) ?: error("未知 MCP Server：$id")

    fun remoteCall(id: String, method: String, params: JSONObject? = null): JSONObject =
        remote[id]?.call(method, params) ?: error("未知 MCP Server：$id")

    @Synchronized
    private fun publish() {
        _snapshots.value = (internal.keys.map { id ->
            McpServerSnapshot(
                id = id,
                internal = true,
                connected = true,
                toolCount = internal[id]?.listTools()?.size ?: 0,
                state = McpServerState.INTERNAL,
                detail = null,
                probedAt = 0L
            )
        } + remote.keys.map { id ->
            val state = states[id] ?: McpServerState.IDLE
            McpServerSnapshot(
                id = id,
                internal = false,
                connected = state == McpServerState.READY,
                toolCount = tools[id]?.size ?: 0,
                state = state,
                detail = details[id],
                probedAt = probedAt[id] ?: 0L,
                transport = transports[id]
            )
        }).sortedBy { it.id }
    }

    private fun parseTools(result: JSONObject): List<McpToolDefinition> {
        val out = mutableListOf<McpToolDefinition>()
        val tools = result.optJSONObject("result")?.optJSONArray("tools") ?: result.optJSONArray("tools") ?: return out
        for (i in 0 until tools.length()) {
            val o = tools.optJSONObject(i) ?: continue
            out += McpToolDefinition(o.optString("name"), o.optString("description"), o.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"))
        }
        return out
    }
}
