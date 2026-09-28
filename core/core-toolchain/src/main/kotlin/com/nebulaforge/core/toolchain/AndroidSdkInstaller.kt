package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.environment.TermuxGuest
import com.nebulaforge.core.exec.TermuxCommandExecutor
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Real Android SDK installer using the same Way-B embedded PTY execution path as Terminal/Build/LSP.
 * A directory is never considered installed until sdkmanager exits successfully and every requested
 * component passes a post-install probe.
 */
class AndroidSdkInstaller(private val context: Context, private val taskCenter: ToolchainTaskCenter) {
    private val app = context.applicationContext

    fun installProfile(packages: List<String>): String =
        installPackages("Android SDK 基础组件", packages)

    fun installPackages(title: String, packages: List<String>): String {
        require(packages.isNotEmpty()) { "SDK 包列表不能为空" }
        return taskCenter.enqueueCustom(title) { report ->
            val sdkmanager = Environment.findSdkExecutable(app, "sdkmanager")
                ?: error("sdkmanager 不可执行。请先在「Android SDK CLI」一项安装 Android Command-line Tools")
            // 空间预检：SDK 组件常见 1GB 以上，空间不足时 sdkmanager 只给笼统错误。
            val sdkAnchor = File(Environment.androidSdkRoot(app)).also { it.mkdirs() }
            val freeMb = sdkAnchor.usableSpace / (1024L * 1024L)
            check(freeMb >= 1500) {
                "安装 Android SDK 组件至少需要 1500MB 可用空间，当前只剩 ${freeMb}MB；请清理存储后重试"
            }
            report(5, "sdkmanager: ${sdkmanager.absolutePath}")
            val quoted = packages.joinToString(" ") { shellQuote(it) }
            val sdkRoot = shellQuote(Environment.androidSdkRoot(app))
            // sdkmanager 直接接收 package id，不存在 `--install` 参数（写成 --install 会被当成包名而失败）。
            val inner = "yes | " + shellQuote(sdkmanager.absolutePath) + " --sdk_root=" + sdkRoot +
                " --licenses >/dev/null 2>&1 || true; " +
                shellQuote(sdkmanager.absolutePath) + " --sdk_root=" + sdkRoot + " " + quoted
            val env = Environment.buildSdkEnv(app)
            // sdkmanager 是 Java 程序 + shell 包装脚本：必须进入 proot guest（前缀对齐）执行，
            // 否则会撞上 Termux shebang / bash.bashrc 的 Permission denied。
            val cmd = TermuxGuest.guestCommandLine(app, inner, env)
            val executor = TermuxCommandExecutor(Environment.resolveShell(app))
            var exitCode = -1
            val tail = StringBuilder()
            withTimeout(20L * 60L * 1000L) {
                executor.execute(cmd, File(Environment.androidSdkRoot(app)), env).collect { event ->
                    when (event) {
                        is TermuxCommandExecutor.Event.Line -> {
                            // 只保留尾部：sdkmanager 输出很长，但失败原因一定在最后几行。
                            tail.append(event.text).append('\n')
                            if (tail.length > 6000) tail.delete(0, tail.length - 6000)
                            report(parsePercent(event.text), event.text)
                        }
                        is TermuxCommandExecutor.Event.Finished -> exitCode = event.exitCode
                    }
                }
            }
            if (exitCode != 0) error("sdkmanager 退出码 $exitCode：${diagnoseFailure(tail.toString())}\n${tail.takeLast(1500)}")
            report(95, "执行真实组件探测")
            val missing = packages.filterNot { packageReady(it) }
            if (missing.isNotEmpty()) error("安装后仍缺少：${missing.joinToString()}")
            report(100, "SDK 组件安装并验证成功")
        }
    }

    private fun packageReady(pkg: String): Boolean = when {
        pkg == AndroidSdkPackages.PLATFORM_TOOLS -> Environment.findSdkExecutable(app, "adb") != null
        pkg == AndroidSdkPackages.CMDLINE_TOOLS -> Environment.findSdkExecutable(app, "sdkmanager") != null
        pkg.startsWith("build-tools;") -> {
            val version = pkg.substringAfter(';')
            val dir = File(Environment.androidSdkRoot(app), "build-tools/$version")
            dir.isDirectory && File(dir, "aapt2").isFile && File(dir, "source.properties").isFile
        }
        pkg.startsWith("platforms;") -> File(Environment.androidSdkRoot(app), pkg.replace(';', '/')).isDirectory
        pkg.startsWith("ndk;") -> File(Environment.androidSdkRoot(app), pkg.replace(';', '/')).isDirectory
        else -> false
    }

    /** 把 sdkmanager 的原始输出翻译成用户能执行的一句话（网络/空间/许可/权限）。 */
    private fun diagnoseFailure(output: String): String = when {
        output.contains("No space left") -> "存储空间不足，请清理后重试"
        output.contains("Could not resolve host") || output.contains("Connection refused") ||
            output.contains("timed out") || output.contains("UnknownHost") ->
            "无法访问 Google 下载源：请确认网络可访问 dl.google.com；受限网络可改用镜像源后重试"
        output.contains("install properties file") -> "SDK 目录不可写：请检查 ${Environment.androidSdkRoot(app)} 的权限"
        output.contains("licence", ignoreCase = true) || output.contains("license", ignoreCase = true) ->
            "许可未接受：安装流程已自动尝试接受，若持续失败请检查 SDK 目录权限"
        output.contains("Java") && output.contains("not found") -> "缺少 JDK：请先安装 JDK 17 工具链"
        else -> "详见下方原始输出（可复制上报）"
    }

    private fun parsePercent(line: String): Int =
        Regex("(\\d{1,3})%").find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 99) ?: 20

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
