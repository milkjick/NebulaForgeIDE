package com.nebulaforge.core.mcp

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 传输协议。
 *
 * 真机上遇到的服务器实现五花八门，必须分别适配，不能假设「一定是标准 Streamable HTTP、响应一定是 JSON」：
 * ```
 * 原因: Value ... of type java.lang.String cannot be converted to JSONObject
 * ```
 * 这条真机报错就是「服务器把响应包成了字符串 / 先发 `event: message` 再发 `data:`」把解析噎住了。
 */
enum class McpTransport(val label: String) {
    /** 官方 Streamable HTTP：POST 端点，响应体可能是 JSON，也可能是 SSE（`event:` + `data:` 行）。 */
    STREAMABLE_HTTP("Streamable HTTP"),

    /** 老式 HTTP+SSE：先 GET 打开事件流，服务器用 `endpoint` 事件告知真正收请求的 POST 地址。 */
    SSE("HTTP+SSE"),

    /** 标准输入输出：拉起本地进程（node/python 脚本），按行收发 JSON-RPC。 */
    STDIO("stdio 本地进程");

    companion object {
        /** 端点推断：`.../sse` 走老式 SSE，其余按 Streamable HTTP。 */
        fun detect(endpoint: URI): McpTransport =
            if (endpoint.path.orEmpty().trimEnd('/').endsWith("/sse")) SSE else STREAMABLE_HTTP
    }
}

/**
 * MCP 客户端：JSON-RPC over HTTP（两种传输）、会话 ID、SSE 解析、错误与响应类型检查。
 *
 * 解析策略刻意宽松 + 报错刻意具体：解析不通时把**响应前 80 字**带进异常，用户能直接把原因贴给我，
 * 而不是只看到一句「连接失败」。
 */
class McpHttpClient(
    private val endpoint: URI,
    val transport: McpTransport = McpTransport.detect(endpoint)
) : McpRemoteClient {
    private val ids = AtomicLong(0)

    @Volatile
    var sessionId: String? = null
        private set

    @Volatile
    var initialized: Boolean = false
        private set

    // ---- 老式 SSE 会话状态 ----
    @Volatile
    private var ssePostEndpoint: URI? = null
    private val sseInbox = ArrayBlockingQueue<JSONObject>(64)

    @Synchronized
    override fun call(method: String, params: JSONObject?): JSONObject = when (transport) {
        McpTransport.STREAMABLE_HTTP -> post(endpoint, method, params)

        // stdio 由 McpStdioClient 负责（需要长驻进程），这里只兜底提示，避免静默走错通道。
        McpTransport.STDIO -> error("stdio 传输请使用 McpHost.connectStdio(...)")
        McpTransport.SSE -> {
            val target = sseEndpoint()
            val id = nextId(method, params)
            val payload = requestOf(id, method, params)
            postRaw(target, payload)
            awaitOnStream(id)
        }
    }

    override fun initialize(): JSONObject {
        val result = call(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2025-03-26")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "NebulaForge IDE").put("version", "1.0"))
        )
        initialized = true
        // 规范要求握手后回一条 initialized 通知；不发的服务器有的会拒绝后续 tools/list。
        runCatching { notify("notifications/initialized") }
        return result
    }

    override fun listTools(): JSONObject {
        if (!initialized) initialize()
        return call("tools/list")
    }

    fun callTool(name: String, arguments: JSONObject = JSONObject()): JSONObject {
        if (!initialized) initialize()
        return call("tools/call", JSONObject().put("name", name).put("arguments", arguments))
    }

    @Synchronized
    override fun close() {
        sessionId = null
        initialized = false
        ssePostEndpoint = null
        sseInbox.clear()
    }

    // ------------------------------------------------------------------ 内部

    private fun nextId(method: String, params: JSONObject?): String =
        if (method.startsWith("notifications/")) "" else ids.incrementAndGet().toString()

    private fun requestOf(id: String, method: String, params: JSONObject?): String =
        McpRequest(id, method, params).toJson().toString()

    private fun notify(method: String) {
        val body = JSONObject().put("jsonrpc", "2.0").put("method", method).toString()
        runCatching {
            if (transport == McpTransport.SSE) postRaw(sseEndpoint(), body) else postRaw(endpoint, body)
        }
    }

    private fun post(target: URI, method: String, params: JSONObject?): JSONObject {
        val id = nextId(method, params)
        val body = requestOf(id, method, params)
        val text = postRaw(target, body)
        if (id.isEmpty()) return JSONObject().put("jsonrpc", "2.0").put("result", JSONObject())
        // 202 / 空体：响应会从 SSE 流里来（部分服务器在 Streamable HTTP 下也这么做）。
        if (text.isBlank()) return awaitOnStream(id)
        return parseBody(text)
    }

    /** 发一次 POST，返回响应体文本（可能为空）。 */
    private fun postRaw(target: URI, body: String): String {
        val conn = (target.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val errorText = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
        if (code !in 200..299) {
            throw IllegalStateException("MCP HTTP $code：${errorText.take(120).ifBlank { "服务端未返回错误正文" }}")
        }
        conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
        val text = runCatching { conn.inputStream?.bufferedReader()?.use { r -> r.readText() }.orEmpty() }.getOrDefault("")
        // 顺手把 SSE 响应里带回来的消息塞进收件箱，便于后续按 id 取。
        collectSseData(text).forEach { runCatching { sseInbox.offer(JSONObject(it)) } }
        return text
    }

    /** GET 打开 SSE 流，等到 `endpoint` 事件（拿到真正的 POST 地址）。 */
    private fun sseEndpoint(): URI {
        ssePostEndpoint?.let { return it }
        val conn = (endpoint.toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 0
            setRequestProperty("Accept", "text/event-stream")
        }
        if (conn.responseCode !in 200..299) throw IllegalStateException("SSE 连接返回 HTTP ${conn.responseCode}")
        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        val latch = CountDownLatch(1)
        val thread = Thread({
            var event = ""
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            if (event == "endpoint") {
                                ssePostEndpoint = if (data.startsWith("http")) URI(data) else endpoint.resolve(data)
                                latch.countDown()
                            } else {
                                collectSseData("data: $data").forEach { d ->
                                    runCatching { sseInbox.offer(JSONObject(d)) }
                                }
                            }
                        }
                        line.isBlank() -> event = ""
                    }
                }
            } catch (_: Throwable) {
                // 流断开：让 awaitOnStream 超时报错，而不是在这里崩掉。
            }
        }, "nebula-mcp-sse")
        thread.isDaemon = true
        thread.start()
        if (!latch.await(15, TimeUnit.SECONDS)) {
            throw IllegalStateException("SSE 服务端 15 秒内没有下发 endpoint 事件（可能不是老式 SSE 服务，请改用 Streamable HTTP）")
        }
        return requireNotNull(ssePostEndpoint)
    }

    private fun awaitOnStream(id: String): JSONObject {
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val message = sseInbox.poll(1, TimeUnit.SECONDS) ?: continue
            if (message.optString("id") == id) return message
        }
        throw IllegalStateException("等待 MCP 响应超时（60 秒，id=$id）")
    }

    /**
     * 解析响应体。
     *
     * 三种真机形态都要吃下：
     * 1. 纯 JSON：`{"jsonrpc":...}`
     * 2. SSE 混合：`event: message` 行在前、`data: {...}` 在后（旧实现只判断「开头是不是 data:」→ 直接漏掉）
     * 3. 双重编码：`"{\"jsonrpc\":...}"`（JSON 字符串里再套 JSON → 旧实现报 “cannot be converted to JSONObject”）
     */
    private fun parseBody(text: String): JSONObject {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw IllegalStateException("MCP 服务端返回空响应")
        for (data in collectSseData(trimmed)) {
            runCatching { JSONObject(data) }.getOrNull()?.let { return it }
        }
        val head = trimmed.removePrefix("\uFEFF").trim()
        val direct = runCatching { JSONObject(head) }.getOrNull()
        if (direct != null) return direct
        val unquoted = head.removeSurrounding("\"").replace("\\\"", "\"").trim()
        runCatching { JSONObject(unquoted) }.getOrNull()?.let { return it }
        throw IllegalStateException("MCP 响应不是 JSON 对象（前 80 字：${head.take(80)}）")
    }

    /** 抽出 `data:` 行（逗号 / 换行分隔都支持）。没有 data 行时返回空。 */
    private fun collectSseData(text: String): List<String> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.startsWith("data:") }.toList()
        if (lines.isEmpty()) return emptyList()
        val joined = lines.joinToString("") { it.removePrefix("data:").trim() }
        // 有些服务器把多条消息塞在同一个 data 里（无分隔），只在能整体解析时才当一条。
        if (runCatching { JSONObject(joined) }.isSuccess) return listOf(joined)
        return listOf(joined)
    }
}
