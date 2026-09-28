package com.nebulaforge.app.mcp

import android.content.Context
import com.nebulaforge.core.device.ShizukuAccess
import com.nebulaforge.core.environment.EmbeddedShellRunner
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.mcp.McpStdioProcess
import com.nebulaforge.core.mcp.McpStdioProcessFactory
import java.io.File

/**
 * MCP stdio 的**进程通道**实现。
 *
 * 两条真实通道：
 *  1. **内嵌用户态**（应用自带 Termux 式环境）：有 python/node/bash，是本地 MCP 服务器的常规宿主；
 *  2. **Shizuku**（无 Root 设备上即 shell 身份）：跑 `pm/am/dumpsys` 这类**设备**命令时必需。
 *
 * 选择规则（不要求用户理解通道差异）：
 *  - 命令首词是设备工具（pm/am/dumpsys…）→ 先 Shizuku，失败再内嵌；
 *  - 其余（python/node/脚本）→ 先内嵌，失败再 Shizuku。
 *
 * 为什么放 app 层：core 只认 [McpStdioProcessFactory] 这个口子，不该知道 Shizuku / 内嵌用户态的细节。
 */
object McpStdioProcessBridge {

    /** 设备侧命令（需要 shell 身份，内嵌用户态里没有）。 */
    private val DEVICE_TOOLS = setOf(
        "pm", "am", "cmd", "dumpsys", "settings", "getprop", "setprop", "screencap",
        "input", "svc", "monkey", "wm", "logcat", "content", "pidof", "ps", "top", "ifconfig"
    )

    @Volatile
    private var appContext: Context? = null

    /**
     * 由 Application 注入 Context：内嵌用户态需要它解析 shell 路径与环境变量。
     *
     * 注意：**不能**在这里取 `context.applicationContext` —— 该调用若发生在 `attachBaseContext`
     * 之前（Application 的 init 块就是这种情况），`ContextWrapper.mBase` 仍为 null，会直接 NPE 崩启动。
     * Application 本身即应用级 Context，够用且没有生命周期问题。
     */
    fun install(context: Context) {
        appContext = context
    }

    val factory: McpStdioProcessFactory = McpStdioProcessFactory { command, workdir ->
        if (looksLikeDeviceCommand(command)) {
            openViaShizuku(command, workdir) ?: openEmbedded(command, workdir)
        } else {
            openEmbedded(command, workdir) ?: openViaShizuku(command, workdir)
        }
    }

    /** 界面提示：这条命令默认会落在哪条通道。 */
    fun channelHint(): String {
        val ctx = appContext
        val embeddedReady = ctx != null && runCatching { EmbeddedShellRunner(ctx).isReady() }.getOrDefault(false)
        return when {
            embeddedReady -> "内嵌用户态（已就绪）"
            ShizukuAccess.isAuthorized() -> "Shizuku(shell)"
            else -> "无可用通道（需要内嵌用户态或 Shizuku）"
        }
    }

    private fun looksLikeDeviceCommand(command: String): Boolean =
        command.trim().substringBefore(' ').substringAfterLast('/') in DEVICE_TOOLS

    /** 内嵌用户态：与 [EmbeddedShellRunner] 完全一致的启动方式（登录式 bash + 终端环境变量）。 */
    private fun openEmbedded(command: String, workdir: String?): McpStdioProcess? {
        val ctx = appContext ?: return null
        return runCatching {
            if (!EmbeddedShellRunner(ctx).isReady()) return@runCatching null
            val shell = Environment.resolveShell(ctx)
            val builder = ProcessBuilder(
                listOf(shell) + com.nebulaforge.core.pty.PtyShellArgs.bashLongOptions(shell) + listOf("-lc", command)
            )
            builder.directory(
                workdir?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isDirectory }
                    ?: Environment.ensureHome(ctx)
            )
            builder.environment().putAll(Environment.buildTerminalEnv(ctx))
            adapter(builder.start())
        }.getOrNull()
    }

    /** Shizuku：长驻进程（全双工管道），无 Root 设备即 shell 身份。 */
    private fun openViaShizuku(command: String, workdir: String?): McpStdioProcess? =
        ShizukuAccess.openProcess(command, workdir)?.let { handle ->
            object : McpStdioProcess {
                override val stdin = handle.output
                override val stdout = handle.input
                override val stderr = handle.error
                override fun kill() {
                    handle.kill()
                }
            }
        }

    private fun adapter(proc: java.lang.Process) = object : McpStdioProcess {
        override val stdin = proc.outputStream
        override val stdout = proc.inputStream
        override val stderr = proc.errorStream
        override fun kill() {
            runCatching { proc.destroy() }
        }
    }
}
