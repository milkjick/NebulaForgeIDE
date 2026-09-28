package com.nebulaforge.core.environment

import android.content.Context
import com.nebulaforge.core.pty.PtyShellArgs
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 在内嵌 Termux 用户态里执行一次性命令（非交互）。
 *
 * 用途：AI 终端通道在设备没有 Root / 未授权 Shizuku 时的回退执行面，
 * 以及"设备诊断 / 工具窗口"里需要跑一条命令拿结果（不需要 tty）的场景。
 * 交互式会话仍走 core-pty 的 PTY。
 *
 * 注意：这里只继承 [Environment.buildTerminalEnv] 给出的用户态环境，
 * 不做任何提权；shell 身份就是本应用进程的 uid。
 */
class EmbeddedShellRunner(private val context: Context) {

    data class Outcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val durationMs: Long
    )

    /** 内嵌用户态是否已安装并可执行。 */
    fun isReady(): Boolean = Environment.isBootstrapInstalled(context) &&
        File(Environment.resolveShell(context)).let { it.isFile && it.canExecute() }

    /** 尚未安装时给出可执行的引导动作（供 UI 展示）。 */
    fun notReadyReason(): String =
        if (!Environment.isBootstrapInstalled(context)) "内嵌用户态尚未解压完成，请先到「设置 → 内置用户态」完成初始化"
        else "内嵌用户态 shell 不可执行：${Environment.resolveShell(context)}"

    fun run(command: String, timeoutMs: Long = 30_000, workingDir: File? = null): Outcome {
        val started = System.currentTimeMillis()
        if (!isReady()) {
            return Outcome(-1, "", notReadyReason(), false, System.currentTimeMillis() - started)
        }
        val shell = Environment.resolveShell(context)
        // 长选项必须排在 `-lc` 之前（实测 `-i -c ... --norc` 会让 bash 直接报 `invalid option` 退出）。
        // 登录式 bash 会 source 前缀 `etc/profile`，宿主命名空间下该路径不可达（EACCES）→
        // 每次都往 stderr 吐一行 "Permission denied" 噪声，这里显式关掉。
        val builder = ProcessBuilder(listOf(shell) + PtyShellArgs.bashLongOptions(shell) + listOf("-lc", command))
        builder.directory(workingDir?.takeIf { it.isDirectory } ?: Environment.ensureHome(context))
        val env = builder.environment()
        env.putAll(Environment.buildTerminalEnv(context))

        val process = builder.start()
        var stdout = ""
        var stderr = ""
        val outThread = thread(name = "embedded-stdout") { stdout = readAll(process.inputStream) }
        val errThread = thread(name = "embedded-stderr") { stderr = readAll(process.errorStream) }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        outThread.join(2000)
        errThread.join(2000)
        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return Outcome(
            exitCode = code,
            stdout = stdout,
            stderr = if (finished) stderr else (stderr + "\n命令超时（${timeoutMs}ms），已终止").trim(),
            timedOut = !finished,
            durationMs = System.currentTimeMillis() - started
        )
    }

    private fun readAll(stream: InputStream): String = runCatching {
        stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrElse { it.message ?: it.javaClass.simpleName }
}
