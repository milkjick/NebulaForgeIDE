package com.nebulaforge.core.session

import com.nebulaforge.core.exec.TermuxCommandExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.io.File

/**
 * ADB's track-devices is an event stream. It is used after a real run starts so
 * device connect/disconnect/authorization changes reach the same IdeSessionBus
 * without a timer-based device poll loop.
 */
class RunDeviceEventMonitor(private val executor: TermuxCommandExecutor) {
    data class Event(val serial: String, val state: RunDeviceStatus)

    fun events(workingDir: File, env: Map<String, String>): Flow<Event> =
        executor.execute("adb track-devices", workingDir, env).mapNotNull { item ->
            when (item) {
                is TermuxCommandExecutor.Event.Line -> parse(item.text)
                is TermuxCommandExecutor.Event.Finished -> null
            }
        }

    private fun parse(line: String): Event? {
        val value = line.trim()
        if (value.isBlank() || value.startsWith("* daemon") || value.startsWith("List of devices")) return null
        val p = value.split(Regex("\\s+"))
        if (p.size < 2) return null
        val state = when (p[1].lowercase()) {
            "device" -> RunDeviceStatus.ONLINE
            "offline" -> RunDeviceStatus.OFFLINE
            "unauthorized" -> RunDeviceStatus.UNAUTHORIZED
            "bootloader" -> RunDeviceStatus.BOOTLOADER
            else -> RunDeviceStatus.UNKNOWN
        }
        return Event(p[0], state)
    }
}
