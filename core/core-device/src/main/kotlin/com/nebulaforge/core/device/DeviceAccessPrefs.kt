package com.nebulaforge.core.device

import android.content.Context

/**
 * 提权通道偏好与审计存储。
 *
 * 关键约束：**是否允许 AI 使用设备级终端** 必须由用户在设置里显式打开，
 * 默认关闭；每次 AI 命令都留审计记录，便于事后追查。
 */
class DeviceAccessPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("device_access", Context.MODE_PRIVATE)

    /** 用户偏好的通道（AUTO 表示按 Root → Shizuku → 内嵌 bootstrap 自动选择）。 */
    fun preferredChannel(): DeviceChannel =
        runCatching { DeviceChannel.valueOf(prefs.getString(KEY_CHANNEL, DeviceChannel.AUTO.name) ?: DeviceChannel.AUTO.name) }
            .getOrDefault(DeviceChannel.AUTO)

    fun setPreferredChannel(channel: DeviceChannel) {
        prefs.edit().putString(KEY_CHANNEL, channel.name).apply()
    }

    /** 是否允许 AI 使用终端通道执行命令（默认关闭）。 */
    fun isAiTerminalEnabled(): Boolean = prefs.getBoolean(KEY_AI_TERMINAL, false)

    fun setAiTerminalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_TERMINAL, enabled).apply()
    }

    /** 追加一条 AI 命令审计（最多保留 [MAX_AUDIT] 条，新的在前）。 */
    fun appendAudit(entry: AiTerminalAuditEntry) {
        val existing = rawAudit().toMutableList()
        existing.add(0, entry)
        while (existing.size > MAX_AUDIT) existing.removeAt(existing.size - 1)
        prefs.edit().putString(KEY_AUDIT, existing.joinToString("\n") { it.encode() }).apply()
    }

    fun audit(): List<AiTerminalAuditEntry> = rawAudit()

    fun clearAudit() {
        prefs.edit().remove(KEY_AUDIT).apply()
    }

    private fun rawAudit(): List<AiTerminalAuditEntry> =
        prefs.getString(KEY_AUDIT, "").orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { AiTerminalAuditEntry.decode(it) }
            .toList()

    private companion object {
        const val KEY_CHANNEL = "preferred_channel"
        const val KEY_AI_TERMINAL = "ai_terminal_enabled"
        const val KEY_AUDIT = "ai_terminal_audit"
        const val MAX_AUDIT = 50
    }
}

/** 一条 AI 命令审计记录。 */
data class AiTerminalAuditEntry(
    val at: Long,
    val channel: String,
    val command: String,
    val exitCode: Int,
    val summary: String
) {
    fun encode(): String = listOf(at.toString(), channel, command.replace('\n', '⏎'), exitCode.toString(), summary.replace('\n', '⏎')).joinToString("\u0001")

    companion object {
        fun decode(raw: String): AiTerminalAuditEntry? {
            val parts = raw.split('\u0001')
            if (parts.size < 5) return null
            return AiTerminalAuditEntry(
                at = parts[0].toLongOrNull() ?: 0L,
                channel = parts[1],
                command = parts[2].replace('⏎', '\n'),
                exitCode = parts[3].toIntOrNull() ?: -1,
                summary = parts[4].replace('⏎', '\n')
            )
        }
    }
}
