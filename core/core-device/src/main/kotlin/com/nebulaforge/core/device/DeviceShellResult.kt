package com.nebulaforge.core.device

/**
 * 设备命令执行结果（提权通道统一返回类型）。
 *
 * [level] 标明这条命令**实际**由哪个通道执行，UI 与 AI 都必须看到真实执行身份，
 * 避免"以为在 root 下跑、其实还在应用沙箱里"这类静默降级。
 */
data class DeviceShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val level: DeviceChannel,
    val durationMs: Long,
    val timedOut: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** 供 AI 阅读的紧凑摘要（限制长度，避免把上下文塞满）。 */
    fun toAiText(maxChars: Int = 8000): String = buildString {
        append("[终端执行] 通道=").append(level.label)
        append(" 退出码=").append(if (timedOut) "超时(${durationMs}ms)" else exitCode.toString())
        append(" 耗时=").append(durationMs).append("ms\n")
        append("$ ").append(command).append('\n')
        if (stdout.isNotBlank()) append(trim(stdout, maxChars)) else append("(标准输出为空)\n")
        if (stderr.isNotBlank()) {
            append("\n[stderr]\n").append(trim(stderr, maxChars / 2))
        }
    }

    private fun trim(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "\n…（输出超过 $limit 字符已截断）\n"
}

/** 设备命令执行通道，按权限从强到弱排列。 */
enum class DeviceChannel(val label: String, val description: String) {
    AUTO("自动选择", "按 Root → Shizuku → 内嵌 bootstrap 的顺序选择当前可用通道"),
    ROOT("Root", "su 直接以 uid=0 执行，权限最高"),
    SHIZUKU("Shizuku", "通过 Shizuku 以 shell(uid=2000) 身份执行，需用户授权"),
    EMBEDDED("内嵌 bootstrap", "在应用内置的 Termux 用户态里执行（无设备特权，但无需 Root/Shizuku 即可用）"),
    NONE("无", "没有任何可用通道")
}

internal fun errText(e: Throwable): String = e.message ?: e.javaClass.simpleName
