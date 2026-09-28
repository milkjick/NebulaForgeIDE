package com.nebulaforge.core.device

import android.content.Context

/**
 * 设备命令统一入口：AI、工具窗口、诊断都从这里取通道，避免各处自行判断权限。
 *
 * 通道选择规则（[DeviceChannel.AUTO]）：
 *  1. Root 可用 → Root（uid=0）
 *  2. Shizuku 已授权 → Shizuku（shell uid=2000）
 *  3. [embeddedRunner] 已注入 → 内嵌 bootstrap（无设备特权，但一定能用）
 *  4. 都没有 → [DeviceChannel.NONE]
 *
 * 内嵌 bootstrap 通道由 app 注入：直接复用内嵌 Termux 用户态的 `bash -lc`（Termux 二进制本身
 * 经由 PREFIX 与 LD_LIBRARY_PATH 自举，不再需要额外的 proot 包装），
 * 这样 AI 即使在未 Root、未装 Shizuku 的设备上也有一个可用的命令执行面。
 */
object DeviceShellGate {

    /** 内嵌用户态回退执行器：返回必须带上 [DeviceChannel.EMBEDDED]，便于来源追溯。 */
    var embeddedRunner: ((String, Long) -> DeviceShellResult)? = null

    /** 已注入的内嵌用户态执行器是否可用。 */
    val hasEmbeddedShell: Boolean get() = embeddedRunner != null

    fun installEmbeddedRunner(runner: (String, Long) -> DeviceShellResult) {
        embeddedRunner = runner
    }

    /** 解析实际生效通道（永不返回 AUTO）。 */
    fun resolve(context: Context?, preferred: DeviceChannel? = null): DeviceChannel {
        val want = preferred ?: context?.let { DeviceAccessPrefs(it).preferredChannel() } ?: DeviceChannel.AUTO
        return when (want) {
            DeviceChannel.ROOT -> if (RootAccess.isAvailable()) DeviceChannel.ROOT else autoResolve()
            DeviceChannel.SHIZUKU -> if (ShizukuAccess.isAuthorized()) DeviceChannel.SHIZUKU else autoResolve()
            DeviceChannel.EMBEDDED -> if (hasEmbeddedShell) DeviceChannel.EMBEDDED else autoResolve()
            DeviceChannel.NONE -> DeviceChannel.NONE
            DeviceChannel.AUTO -> autoResolve()
        }
    }

    /** 自动选择：按权限从强到弱，保证"有一档能用就用一档"。 */
    private fun autoResolve(): DeviceChannel = when {
        RootAccess.isAvailable() -> DeviceChannel.ROOT
        ShizukuAccess.isAuthorized() -> DeviceChannel.SHIZUKU
        hasEmbeddedShell -> DeviceChannel.EMBEDDED
        else -> DeviceChannel.NONE
    }

    /** 当前身份描述，用于 UI 与 AI 上下文展示。 */
    fun identity(context: Context?): String = when (resolve(context)) {
        DeviceChannel.ROOT -> "Root（uid=0）"
        DeviceChannel.SHIZUKU -> "Shizuku（${ShizukuAccess.execIdentity() ?: "shell"}）"
        DeviceChannel.EMBEDDED -> "内嵌 bootstrap（应用内 bootstrap，无设备特权）"
        else -> "无可用通道"
    }

    /**
     * 执行命令。
     *
     * @param channel 期望通道；默认取用户偏好。若该通道不可用会返回带失败信息的
     *   [DeviceShellResult]，**不会**静默改走别的通道（避免提权语义被悄悄改变）。
     */
    fun exec(
        context: Context?,
        command: String,
        channel: DeviceChannel? = null,
        timeoutMs: Long = 30_000
    ): DeviceShellResult {
        val requested = channel ?: context?.let { DeviceAccessPrefs(it).preferredChannel() } ?: DeviceChannel.AUTO
        val started = System.currentTimeMillis()
        val target = if (requested == DeviceChannel.AUTO) autoResolve() else requested
        return when (target) {
            DeviceChannel.ROOT -> if (RootAccess.isAvailable()) RootAccess.exec(command, timeoutMs)
            else fail(command, DeviceChannel.ROOT, started, "Root 通道不可用（su 未授权或设备未 Root）")

            DeviceChannel.SHIZUKU -> if (ShizukuAccess.isAuthorized()) ShizukuAccess.exec(command, timeoutMs)
            else fail(command, DeviceChannel.SHIZUKU, started, "Shizuku 通道不可用：${ShizukuAccess.status(context).label}")

            DeviceChannel.EMBEDDED -> embeddedRunner?.invoke(command, timeoutMs)
                ?: fail(command, DeviceChannel.EMBEDDED, started, "内嵌 bootstrap 通道未初始化（内嵌 bootstrap 尚未就绪）")

            DeviceChannel.AUTO, DeviceChannel.NONE -> fail(
                command, DeviceChannel.NONE, started,
                "没有可用的命令通道：请授权 Shizuku、授予 Root，或先完成内嵌 bootstrap 解压"
            )
        }
    }

    private fun fail(command: String, level: DeviceChannel, startedAt: Long, reason: String): DeviceShellResult =
        DeviceShellResult(
            command = command,
            exitCode = -1,
            stdout = "",
            stderr = reason,
            level = level,
            durationMs = System.currentTimeMillis() - startedAt
        )

    /** 多行状态摘要（设备与权限诊断、AI 上下文都能直接用）。 */
    fun statusSummary(context: Context?): String {
        val root = RootAccess.cached ?: RootAccess.isAvailable()
        val shizuku = ShizukuAccess.status(context)
        return buildString {
            append("执行通道：").append(identity(context)).append('\n')
            append("· Root：").append(if (root) "可用（uid=0）" else "不可用").append('\n')
            append("· Shizuku：").append(shizuku.label)
            if (shizuku == ShizukuStatus.READY) ShizukuAccess.execIdentity()?.let { append("（").append(it).append("）") }
            append('\n')
            append("· 内嵌 bootstrap：").append(if (hasEmbeddedShell) "已就绪" else "未初始化")
        }
    }
}
