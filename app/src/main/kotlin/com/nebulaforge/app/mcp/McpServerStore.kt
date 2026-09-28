package com.nebulaforge.app.mcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 外部 MCP 服务器配置。
 *
 * 持久化在应用私有 SharedPreferences 中，仅保存连接元数据（名称/URL/开关），
 * 不保存任何凭据：需要鉴权的服务器请在 URL 查询参数或后续的 header 字段中自行配置，
 * 且本类不会把 URL 之外的任何内容写入日志。
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    /** 完整端点，例如 http://127.0.0.1:8787/mcp 或 https://host/mcp；stdio 时留空。 */
    val url: String,
    val enabled: Boolean = true,
    val autoConnect: Boolean = true,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * 传输方式：`http`（Streamable HTTP / HTTP+SSE，自动识别）或 `stdio`（本地进程，按行 JSON-RPC）。
     *
     * 存字符串而非枚举：配置是持久化的，字符串对旧数据更宽容（未知值按 http 处理）。
     */
    val transport: String = TRANSPORT_HTTP,
    /** stdio 专用：启动命令，交给 `sh -c` 执行（例如 `node /sdcard/mcp/server.js`）。 */
    val command: String = "",
    /** stdio 专用：工作目录，留空则用进程默认目录。 */
    val workdir: String = ""
) {
    /** 明文 HTTP 端点：可用但需要在界面上明确提示风险。 */
    val isCleartext: Boolean get() = !isStdio && url.trim().startsWith("http://", ignoreCase = true)

    /** 本地 stdio 进程传输。 */
    val isStdio: Boolean get() = transport.equals(TRANSPORT_STDIO, ignoreCase = true)

    /** 界面用的一行摘要。 */
    val endpointSummary: String get() = if (isStdio) "stdio · ${command.ifBlank { "（未填命令）" }}" else url

    companion object {
        const val TRANSPORT_HTTP = "http"
        const val TRANSPORT_STDIO = "stdio"
    }
}

/**
 * 外部 MCP 服务器的增删改查存储。
 * McpHost 只负责运行时连接，本类负责“管理”语义的持久化，二者职责分离。
 */
class McpServerStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<McpServerConfig> = runCatching {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { add(decode(it)) }
            }
        }
    }.getOrDefault(emptyList())

    fun save(servers: List<McpServerConfig>) {
        val array = JSONArray()
        servers.forEach { array.put(encode(it)) }
        prefs.edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    fun upsert(server: McpServerConfig): List<McpServerConfig> {
        val next = load().toMutableList()
        val index = next.indexOfFirst { it.id == server.id }
        if (index >= 0) next[index] = server else next += server
        save(next)
        return next
    }

    fun remove(id: String): List<McpServerConfig> =
        load().filterNot { it.id == id }.also(::save)

    private fun encode(server: McpServerConfig) = JSONObject().apply {
        put("id", server.id)
        put("name", server.name)
        put("url", server.url)
        put("enabled", server.enabled)
        put("autoConnect", server.autoConnect)
        put("note", server.note)
        put("createdAt", server.createdAt)
        put("transport", server.transport)
        put("command", server.command)
        put("workdir", server.workdir)
    }

    private fun decode(json: JSONObject) = McpServerConfig(
        id = json.optString("id").ifBlank { newId() },
        name = json.optString("name").ifBlank { "MCP Server" },
        url = json.optString("url"),
        enabled = json.optBoolean("enabled", true),
        autoConnect = json.optBoolean("autoConnect", true),
        note = json.optString("note"),
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
        transport = json.optString("transport").ifBlank { McpServerConfig.TRANSPORT_HTTP },
        command = json.optString("command"),
        workdir = json.optString("workdir")
    )

    companion object {
        private const val PREFS = "nebulaforge.mcp.servers"
        private const val KEY_SERVERS = "servers_json"

        fun newId(): String = "mcp-" + UUID.randomUUID().toString().replace("-", "").take(8)

        /** stdio 启动命令校验：非空即可（具体解释交给 shell，报错会带 stderr 回显）。 */
        fun validateCommand(raw: String): String? =
            if (raw.trim().isBlank()) "请填写启动命令（例如 node server.js）" else null

        /** URL 合法性校验（只接受 HTTP/HTTPS，且必须带主机名）。 */
        fun validateUrl(raw: String): String? {
            val url = raw.trim()
            if (url.isBlank()) return "请填写服务器 URL"
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                return "URL 必须以 http:// 或 https:// 开头"
            }
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
            if (host.isBlank()) return "URL 缺少主机名"
            return null
        }
    }
}
