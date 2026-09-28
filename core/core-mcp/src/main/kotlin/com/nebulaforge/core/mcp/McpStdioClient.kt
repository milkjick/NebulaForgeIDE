package com.nebulaforge.core.mcp

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 远端客户端统一接口。
 *
 * 让 HTTP 与 stdio 两条传输走同一套上层逻辑：`McpHost` 只管 initialize / tools/list / call，
 * 不必区分传输方式，探测状态机、快照、关闭流程都不用改。
 */
interface McpRemoteClient {
    fun initialize(): JSONObject
    fun listTools(): JSONObject
    fun call(method: String, params: JSONObject? = null): JSONObject
    fun close()
}

/**
 * 长驻进程句柄（stdin/stdout 全双工），由 app 层按设备通道提供。
 */
interface McpStdioProcess {
    /** 写入端（发请求）。 */
    val stdin: OutputStream
    /** 读取端（收响应）。 */
    val stdout: InputStream
    /** 可选错误流，用于失败时给出可读原因。 */
    val stderr: InputStream?
    fun kill()
}

/** 按当前设备通道打开长驻进程：Shizuku（shell，无 Root）→ Root → 应用内 Runtime。 */
fun interface McpStdioProcessFactory {
    fun open(command: String, workdir: String?): McpStdioProcess?
}

/**
 * MCP **stdio 传输**：JSON-RPC 2.0，一行一条消息（换行分帧）。
 *
 * 为什么需要它：本地 MCP 服务器（node/python 脚本）通常只提供 stdio；而 Shizuku/Root 通道能开
 * 长驻进程（全双工管道），于是「guest 管道的 stdio MCP」在无 Root 设备上一样能跑起来。
 *
 * 实现取舍：
 *  - 读线程 + 阻塞队列：`BufferedReader.ready()` 在 socket 上不可靠，轮询会漏数据，所以用独立
 *    线程持续 `readLine()` 入队，主线程 `poll(timeout)`——超时与进程退出都能被明确区分。
 *  - 非 JSON 行直接跳过：很多 MCP 服务器会把日志打到 stdout，不该因此判定协议失败。
 *  - stderr 保留最后 2KB：失败时把「进程为什么死」写进异常，而不是只报一句超时。
 */
class McpStdioClient(
    private val command: String,
    private val workdir: String? = null,
    private val factory: McpStdioProcessFactory,
    private val timeoutMs: Long = 60_000
) : McpRemoteClient {

    private val seq = AtomicLong(0)
    private val inbox = LinkedBlockingQueue<String>()
    private val stderrTail = StringBuilder()

    @Volatile
    private var process: McpStdioProcess? = null

    @Volatile
    private var readerThread: Thread? = null

    @Volatile
    private var dead = false

    private val lock = Any()

    private fun ensureStarted(): McpStdioProcess {
        process?.let { if (!dead) return it }
        val opened = factory.open(command, workdir)
            ?: error("无法启动 stdio 进程：$command（请确认终端通道可用、命令存在且可执行）")
        val reader = BufferedReader(InputStreamReader(opened.stdout, Charsets.UTF_8))
        readerThread = Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    inbox.put(line)
                }
            } catch (_: Throwable) {
                // 读失败按「进程结束」处理，由等待方给出可读原因。
            } finally {
                dead = true
                inbox.offer(EOF)
            }
        }.apply { isDaemon = true; name = "mcp-stdio-reader"; start() }

        opened.stderr?.let { err ->
            Thread {
                runCatching {
                    InputStreamReader(err, Charsets.UTF_8).buffered().forEachLine { line ->
                        synchronized(stderrTail) {
                            stderrTail.append(line).append('\n')
                            if (stderrTail.length > 2000) stderrTail.delete(0, stderrTail.length - 2000)
                        }
                    }
                }
            }.apply { isDaemon = true; name = "mcp-stdio-stderr"; start() }
        }
        process = opened
        dead = false
        return opened
    }

    override fun initialize(): JSONObject {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("clientInfo", JSONObject().put("name", "NebulaForge").put("version", "1"))
        val result = call("initialize", params)
        // 握手完成的标志性通知（无 id，不需要回复）；失败不影响已拿到的 initialize 结果。
        runCatching { sendNotification("notifications/initialized", JSONObject()) }
        return result
    }

    override fun listTools(): JSONObject = call("tools/list", JSONObject())

    override fun call(method: String, params: JSONObject?): JSONObject {
        val proc = ensureStarted()
        val id = seq.incrementAndGet()
        val payload = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method)
        if (params != null) payload.put("params", params)
        synchronized(lock) {
            val out = proc.stdin
            out.write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) error("$method 超时（${timeoutMs}ms）${stderrHint()}")
                val line = inbox.poll(remaining, TimeUnit.MILLISECONDS)
                if (line == null) error("$method 超时（${timeoutMs}ms）${stderrHint()}")
                if (line == EOF) error("$method 失败：stdio 进程已退出${stderrHint()}")
                if (line.isBlank()) continue
                val obj = runCatching { JSONObject(line.trim()) }.getOrNull() ?: continue
                // 通知（无 id）与别人的响应都跳过。
                if (!obj.has("id") || obj.optLong("id", -1L) != id) continue
                obj.optJSONObject("error")?.let { err ->
                    error("$method 返回错误：${err.optString("message").takeIf { m -> m.isNotBlank() } ?: err.toString()}")
                }
                return obj.optJSONObject("result") ?: JSONObject()
            }
        }
    }

    private fun sendNotification(method: String, params: JSONObject) {
        val proc = ensureStarted()
        val line = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params).toString()
        synchronized(lock) {
            proc.stdin.write((line + "\n").toByteArray(Charsets.UTF_8))
            proc.stdin.flush()
        }
    }

    override fun close() {
        dead = true
        runCatching { process?.kill() }
        process = null
        readerThread?.interrupt()
        readerThread = null
    }

    /** 供界面显示：这条 stdio 服务器到底在跑什么命令。 */
    fun describe(): String = "stdio · $command"

    private fun stderrHint(): String = synchronized(stderrTail) {
        val lines = stderrTail.toString().trim().lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) "" else "；stderr：" + lines.takeLast(3).joinToString(" | ").take(300)
    }

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        private const val EOF = "\u0000__MCP_EOF__"
    }
}
