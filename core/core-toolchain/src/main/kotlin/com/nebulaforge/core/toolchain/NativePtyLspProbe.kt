package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * LSP launcher 的"真实执行"探测。
 *
 * 为什么不用 ProcessBuilder：Android 上 app 私有目录内的 ELF 会被 noexec/SELinux 拒绝，
 * 而 LSP 二进制的 DT_RUNPATH 又硬编码了 Termux 前缀，必须配合 PREFIX/LD_LIBRARY_PATH 才能启动。
 * 只有 NativePty（与终端、构建、bootstrap 自检同一条 Way-B 用户态）能同时满足这两点，
 * 所以这里的"自检通过"必须是"真的把它 exec 起来并跑完一条命令"，而不是"文件存在且可执行位已置"。
 */
internal object NativePtyLspProbe {
    data class Result(val ok: Boolean, val detail: String)

    fun run(context: Context, launcher: File): Result {
        val shell = runCatching { Environment.resolveShell(context) }.getOrNull()
        if (shell == null || !File(shell).isFile) {
            return Result(false, "内嵌 shell 不可用，无法执行 launcher 自检")
        }
        val prefix = File(Environment.usrRoot(context))
        val lines = mutableListOf<String>()
        var exitCode = -1
        var failure: String? = null
        val marker = "NEBULA_LSP_PROBE_OK"
        var sawMarker = false
        try {
            val env = Environment.buildPrefixEnv(context, prefix).toMutableMap()
            env["PATH"] = listOfNotNull(File(prefix, "bin").absolutePath, env["PATH"]).joinToString(":")
            val home = Environment.ensureHome(context)
            val quoted = "'" + launcher.absolutePath.replace("'", "'\\''") + "'"
            runBlocking {
                withTimeoutOrNull(20_000L) {
                    TermuxCommandExecutor(shell, guestAware = true)
                        .execute("printf '$marker\\n'; $quoted --version 2>&1 | head -3", home, env)
                        .collect { event ->
                            when (event) {
                                is TermuxCommandExecutor.Event.Line -> {
                                    if (lines.size < 8) lines += event.text.trim()
                                    if (event.text.contains(marker)) sawMarker = true
                                }
                                is TermuxCommandExecutor.Event.Finished -> exitCode = event.exitCode
                            }
                        }
                }
            }
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message?.take(200)}"
        }
        val detail = buildString {
            append("pty=").append(if (sawMarker) "ok" else "fail")
            append(" exit=").append(exitCode)
            failure?.let { append(" 异常=").append(it) }
            val clean = lines.filter { it.isNotBlank() }
            if (clean.isNotEmpty()) append(" 输出=").append(clean.joinToString(" | ").take(300))
        }
        return Result(sawMarker, detail)
    }
}
