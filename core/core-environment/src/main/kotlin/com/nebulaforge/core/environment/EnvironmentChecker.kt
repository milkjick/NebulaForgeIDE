package com.nebulaforge.core.environment

import android.content.Context
import com.nebulaforge.core.exec.TermuxCommandExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

enum class CheckStep {
    BOOTSTRAP_INSTALLED,
    JDK_17,
    ANDROID_SDK_BASIC,
    ADB_EXECUTABLE,
    NETWORK
}

data class CheckResult(
    val step: CheckStep,
    val passed: Boolean,
    val detail: String = "",
    val remedy: Remedy?
)

sealed class Remedy {
    data class ManualAction(val instructionKey: String) : Remedy()
    data class AutoFixable(val command: String) : Remedy()
}

/**
 * Scheme B 环境检测必须验证“真的能执行”，不能把文件存在等同于工具链可用。
 */
class EnvironmentChecker(private val context: Context) {
    suspend fun runAllChecks(): List<CheckResult> = withContext(Dispatchers.IO) {
        listOf(
            checkBootstrapInstalled(),
            checkJdk17(),
            checkAndroidSdkBasic(),
            checkAdb()
        )
    }

    /**
     * Scheme B：用户态是否可用必须靠「真的能执行」判定，标记文件只代表曾经解压过。
     *
     * 早期实现仅凭 Environment.isBootstrapInstalled（标记文件存在）就判定通过，掩盖了
     * 「SYMLINKS.txt 相对目标解析错误 → usr/bin/sh 悬空 → 无法执行」这类故障：
     * 设置页显示"已就绪"，但终端、构建、LSP 全线失败，用户无从下手。
     */
    private fun checkBootstrapInstalled(): CheckResult {
        if (!Environment.isBootstrapInstalled(context)) {
            return CheckResult(
                CheckStep.BOOTSTRAP_INSTALLED, false, "内置用户态尚未初始化",
                Remedy.AutoFixable("bootstrap_install")
            )
        }
        val runtime = BootstrapRuntime(context)
        return if (runtime.verifyRuntime()) {
            CheckResult(
                CheckStep.BOOTSTRAP_INSTALLED, true,
                "内置用户态自检通过（${runtime.releaseVersion()}，sh 可真实执行）", null
            )
        } else {
            CheckResult(
                CheckStep.BOOTSTRAP_INSTALLED, false, runtime.diagnoseRuntime(),
                Remedy.AutoFixable("bootstrap_install")
            )
        }
    }

    private suspend fun checkJdk17(): CheckResult {
        if (!Environment.isBootstrapInstalled(context)) {
            return CheckResult(CheckStep.JDK_17, false, "bootstrap required", Remedy.AutoFixable("bootstrap_install"))
        }
        val home = Environment.resolveJdkHome(context, 17)
        if (home == null) {
            return CheckResult(CheckStep.JDK_17, false, "JDK 17 directory not found", Remedy.AutoFixable("pkg install -y openjdk-17"))
        }
        val java = File(home, "bin/java")
        val result = execute(java, "-version")
        return CheckResult(
            CheckStep.JDK_17,
            result.first == 0,
            result.second.ifBlank { "java executable test failed" },
            if (result.first == 0) null else Remedy.AutoFixable("pkg install -y openjdk-17")
        )
    }

    private fun checkAndroidSdkBasic(): CheckResult {
        val ready = Environment.isAndroidSdkReady(context)
        return CheckResult(
            CheckStep.ANDROID_SDK_BASIC,
            ready,
            if (ready) "platform-tools + build-tools detected" else "platform-tools/build-tools incomplete",
            if (ready) null else Remedy.ManualAction("sdkmanager_install_platform_tools_build_tools")
        )
    }

    private suspend fun checkAdb(): CheckResult {
        val adb = Environment.resolveAdb(context)
        if (adb == null) {
            return CheckResult(CheckStep.ADB_EXECUTABLE, false, "adb binary unavailable", Remedy.ManualAction("sdkmanager_platform_tools"))
        }
        val result = execute(adb, "version")
        return CheckResult(
            CheckStep.ADB_EXECUTABLE,
            result.first == 0,
            result.second.ifBlank { "adb 探测无输出（exit=${result.first}）" },
            if (result.first == 0) null else Remedy.ManualAction("sdkmanager_platform_tools")
        )
    }

    /**
     * Scheme B 的执行判定必须经内置用户态（proot 前缀对齐）。
     *
     * 内置用户态里的 ELF（java、adb、aapt2…）的 RPATH 指向 guest 前缀，宿主机直接 exec 会报
     * `CANNOT LINK EXECUTABLE ... library "libz.so.1" not found`：工具明明是好的，设置页却被判成
     * 失败（历史上 JDK17 的「不可执行」就是这么来的）。这里与 ToolchainManager 的探测保持一致。
     */
    private suspend fun execute(file: File, vararg args: String): Pair<Int, String> {
        val env = Environment.buildTerminalEnv(context)
        val inner = (listOf(file.absolutePath) + args).joinToString(" ") { shellQuote(it) }
        val executor = TermuxCommandExecutor(Environment.resolveShell(context), guestAware = true)
        val output = StringBuilder()
        var exitCode = -1
        return try {
            withTimeout(GUEST_EXEC_TIMEOUT_MS) {
                executor.execute(inner, File(Environment.homeRoot(context)), env).collect { event ->
                    when (event) {
                        is TermuxCommandExecutor.Event.Line -> {
                            output.append(event.text).append('\n')
                            if (output.length > 8192) output.delete(0, output.length - 8192)
                        }
                        is TermuxCommandExecutor.Event.Finished -> exitCode = event.exitCode
                    }
                }
            }
            exitCode to meaningful(output.toString())
        } catch (t: Throwable) {
            -1 to (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 去噪后的详情：proot/linker/bashrc 的启动噪声不是失败原因，混进来会把用户带偏。 */
    private fun meaningful(output: String): String {
        val lines = output.lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !isProbeNoise(it) }
            .take(4)
            .joinToString("\n")
        if (lines.isNotBlank()) return lines
        return output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

    private fun isProbeNoise(line: String): Boolean =
        line.contains("linker:") || line.contains("ld.config.txt") || line.contains("bash.bashrc") ||
            line.startsWith("proot warning") || line.startsWith("WARNING:")

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private companion object {
        /** 冷启动 proot + java 的探测留足余量（首次执行 JVM 会解压/校验大量 class）。 */
        const val GUEST_EXEC_TIMEOUT_MS = 30_000L
    }
}
