package com.nebulaforge.core.device

/**
 * AI 终端命令安全策略。
 *
 * 设计原则：提权通道等于把 shell/root 交出去，因此**只做两件事**——
 *  1. 对不可逆的破坏性命令直接拒绝（[Verdict.DENY]）；
 *  2. 对高风险但可能是正常需求的命令打标记（[Verdict.WARN]），由 UI 展示给用户确认。
 * 策略不试图"猜意图"，只做可解释的字面匹配，避免误伤正常构建/调试命令。
 */
object CommandPolicy {

    enum class Verdict { ALLOW, WARN, DENY }

    data class Decision(val verdict: Verdict, val reason: String? = null) {
        val allowed: Boolean get() = verdict != Verdict.DENY
    }

    private val DENY_PATTERNS = listOf(
        "mkfs" to "格式化文件系统不可逆",
        "wipefs" to "擦除文件系统签名不可逆",
        "dd of=/dev/block" to "直接写块设备会破坏分区",
        "dd if=/dev/zero of=/dev/block" to "写零到块设备会破坏分区",
        "fastboot flash" to "刷写设备分区需要专用工具与确认流程",
        "rm -rf / " to "递归删除根目录",
        "rm -rf /" to "递归删除根目录",
        "reboot recovery" to "重启到 recovery 会打断当前会话"
    )

    private val WARN_PATTERNS = listOf(
        "rm -rf" to "递归强制删除，请确认路径",
        "pm uninstall" to "卸载应用会清除其数据",
        "pm clear" to "清除应用数据不可恢复",
        "settings put" to "修改系统设置会影响整机行为",
        "svc " to "直接控制系统服务开关",
        "chmod 777 /" to "放宽根目录权限存在安全风险",
        "kill -9" to "强杀进程可能丢失未保存数据",
        "reboot" to "重启设备会中断当前工作",
        "shutdown" to "关机/重启设备会中断当前工作"
    )

    fun evaluate(command: String): Decision {
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        DENY_PATTERNS.firstOrNull { normalized.contains(it.first) }?.let { return Decision(Verdict.DENY, it.second) }
        WARN_PATTERNS.firstOrNull { normalized.contains(it.first) }?.let { return Decision(Verdict.WARN, it.second) }
        return Decision(Verdict.ALLOW)
    }
}

/**
 * AI 终端桥：把设备提权通道包装成"AI 可直接调用"的一个方法。
 *
 * 三重闸门：
 *  1. 用户在设置里显式开启「允许 AI 使用终端通道」（默认关闭）；
 *  2. [CommandPolicy] 拒绝破坏性命令；
 *  3. 每次执行写入审计（命令、通道、退出码、结果摘要），可在设置页查看。
 */
class AiTerminalBridge(private val context: android.content.Context) {

    private val prefs = DeviceAccessPrefs(context)

    fun isEnabled(): Boolean = prefs.isAiTerminalEnabled()

    fun setEnabled(enabled: Boolean) = prefs.setAiTerminalEnabled(enabled)

    fun audit(): List<AiTerminalAuditEntry> = prefs.audit()

    fun clearAudit() = prefs.clearAudit()

    /** 当前将使用的通道与身份，用于 UI 提示与计划批准页展示。 */
    fun describeChannel(): String = "${DeviceShellGate.resolve(context).label} · ${DeviceShellGate.identity(context)}"

    /** 预览某条命令的策略判定，供计划批准页提前提示风险。 */
    fun preview(command: String): String {
        val decision = CommandPolicy.evaluate(command)
        return when (decision.verdict) {
            CommandPolicy.Verdict.ALLOW -> "通道：${describeChannel()}\n命令：$command"
            CommandPolicy.Verdict.WARN -> "通道：${describeChannel()}\n命令：$command\n⚠ 风险提示：${decision.reason}"
            CommandPolicy.Verdict.DENY -> "该命令被安全策略拒绝：${decision.reason}\n命令：$command"
        }
    }

    /**
     * 供 AI 调用：执行一条命令并返回可读文本结果。
     *
     * 任何闸门不通过都返回**说明性文本**而不是抛异常，因为返回文本会直接进入 AI 上下文，
     * 让模型知道"没执行成功"以及原因，而不是臆造结果。
     */
    fun execForAi(command: String, timeoutMs: Long = 60_000): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return "命令为空，未执行。"
        if (!isEnabled()) {
            return "AI 终端通道未开启：请在「设置 → 设备权限（Shizuku / Root）」中打开「允许 AI 使用终端通道」后重试。"
        }
        val decision = CommandPolicy.evaluate(trimmed)
        if (decision.verdict == CommandPolicy.Verdict.DENY) {
            val text = "命令被安全策略拒绝（${decision.reason}），未执行：$trimmed"
            prefs.appendAudit(AiTerminalAuditEntry(System.currentTimeMillis(), "policy", trimmed, -1, "被策略拒绝：${decision.reason}"))
            return text
        }
        val result = DeviceShellGate.exec(context, trimmed, timeoutMs = timeoutMs)
        val warning = if (decision.verdict == CommandPolicy.Verdict.WARN) "⚠ 风险提示：${decision.reason}\n" else ""
        prefs.appendAudit(
            AiTerminalAuditEntry(
                at = System.currentTimeMillis(),
                channel = result.level.label,
                command = trimmed,
                exitCode = if (result.timedOut) -1 else result.exitCode,
                summary = buildString {
                    append(if (result.ok) "成功" else if (result.timedOut) "超时" else "失败(exit=${result.exitCode})")
                    if (result.stderr.isNotBlank()) append(" · ").append(result.stderr.lineSequence().first().take(120))
                }
            )
        )
        return warning + result.toAiText()
    }
}
