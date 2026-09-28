package com.nebulaforge.core.session

/** Runtime-derived device state. ONLINE is only reported when the underlying tool reports it. */
enum class RunDeviceStatus { ONLINE, OFFLINE, UNAUTHORIZED, BOOTLOADER, UNKNOWN, DISCONNECTED }

data class RunDeviceCapability(
    val id: String,
    val label: String,
    val type: String,
    val status: RunDeviceStatus,
    val platform: String? = null,
    val architecture: String? = null,
    val apiLevel: Int? = null,
    val supportsDebug: Boolean = false,
    val supportsProfile: Boolean = false,
    val supportsRelease: Boolean = false,
    val supportsWeb: Boolean = false,
    val rawDetails: Map<String, String> = emptyMap(),
    val discoveredAt: Long = System.currentTimeMillis()
)

data class DeviceCompatibility(val device: RunDeviceCapability, val compatible: Boolean, val reason: String? = null)

object RunConfigurationDeviceFilter {
    fun filter(typeId: String, mode: String, requestedId: String?, devices: List<RunDeviceCapability>): List<DeviceCompatibility> =
        devices.map { d ->
            val reason = when {
                requestedId?.isNotBlank() == true && requestedId != d.id -> "不是当前配置指定设备"
                d.status != RunDeviceStatus.ONLINE -> "设备状态：${d.status.name.lowercase()}"
                typeId == "android" && d.type == "android" -> null
                typeId == "flutter" && d.type == "flutter" && mode == "web" && d.supportsWeb -> null
                typeId == "flutter" && d.type == "flutter" && mode != "web" && !d.supportsWeb -> null
                typeId == "flutter" && d.type == "flutter" -> "当前运行模式与设备平台不匹配"
                // 独立语言工程 / 后端 / Web / C++ 跑在内置运行时里，与 adb 设备无关：
                // 「本机」能力项对这些类型恒可用。缺这一条时它们的设备列表恒为「0 个可用」。
                typeId != "android" && typeId != "flutter" && d.type == "local" -> null
                else -> "不支持当前项目类型"
            }
            DeviceCompatibility(d, reason == null, reason)
        }

    fun compatible(typeId: String, mode: String, requestedId: String?, devices: List<RunDeviceCapability>): List<RunDeviceCapability> =
        filter(typeId, mode, requestedId, devices).filter { it.compatible }.map { it.device }
}
