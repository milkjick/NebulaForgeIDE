package com.nebulaforge.core.device

import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Root（su）通道。
 *
 * 设备已 Root 且授权了 su 时，优先于 Shizuku 使用：身份是真正的 uid=0，
 * 可以执行 Shizuku 做不到的操作（如挂载、写 /system）。检测结果会缓存，
 * 避免每次执行命令都 fork 一次 su 探测。
 */
object RootAccess {

    @Volatile
    private var cachedAvailability: Boolean? = null

    /** 已缓存的可用性（未探测过为 null）。 */
    val cached: Boolean? get() = cachedAvailability

    /** 探测 su 是否可用（`su -c id` 返回 uid=0）。结果缓存。 */
    fun isAvailable(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) cachedAvailability?.let { return it }
        val ok = runCatching {
            val r = runProcess(listOf("su", "-c", "id"), 8000)
            r.exitCode == 0 && r.stdout.contains("uid=0")
        }.getOrDefault(false)
        cachedAvailability = ok
        return ok
    }

    fun exec(command: String, timeoutMs: Long = 30_000): DeviceShellResult {
        val started = System.currentTimeMillis()
        val r = runProcess(listOf("su", "-c", command), timeoutMs)
        return DeviceShellResult(
            command = command,
            exitCode = r.exitCode,
            stdout = r.stdout,
            stderr = r.stderr,
            level = DeviceChannel.ROOT,
            durationMs = System.currentTimeMillis() - started,
            timedOut = r.timedOut
        )
    }

    private data class ProcResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean)

    private fun runProcess(argv: List<String>, timeoutMs: Long): ProcResult {
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        var stdout = ""
        var stderr = ""
        val outThread = thread(name = "root-stdout") { stdout = readAll(process.inputStream) }
        val errThread = thread(name = "root-stderr") { stderr = readAll(process.errorStream) }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        outThread.join(2000)
        errThread.join(2000)
        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ProcResult(
            exitCode = code,
            stdout = stdout,
            stderr = if (finished) stderr else (stderr + "\n命令超时（${timeoutMs}ms），已终止").trim(),
            timedOut = !finished
        )
    }

    private fun readAll(stream: InputStream): String = runCatching {
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrElse { errText(it) }
}
