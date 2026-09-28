package com.nebulaforge.core.session

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.exec.TermuxCommandExecutor
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Real device discovery through the embedded runtime; no ProcessBuilder. */
data class RunDevice(val id: String, val label: String, val type: String)

class RunDeviceCatalog(private val context: Context) {
    private val cache = ConcurrentHashMap<String, List<RunDevice>>()
    private val capabilityCache = ConcurrentHashMap<String, List<RunDeviceCapability>>()

    suspend fun discover(typeId: String): List<RunDevice> = discoverCapabilities(typeId).map { RunDevice(it.id, it.label, it.type) }

    suspend fun discoverCapabilities(typeId: String): List<RunDeviceCapability> {
        val result = when (typeId) {
            "android" -> discoverAndroid()
            "flutter" -> discoverFlutter()
            // 独立语言工程 / 后端 / Web / C++：直接跑在内置运行时（proot guest）里，不需要 adb 设备。
            // 给一个「本机」能力项，运行面板才不会显示「可用设备 0 个」而让用户以为不能运行。
            else -> listOf(localCapability())
        }
        capabilityCache[typeId] = result
        cache[typeId] = result.map { RunDevice(it.id, it.label, it.type) }
        return result
    }

    /** 内置运行时的「本机」能力项：代表 guest 里的 python / node / clang / cmake 等本机执行环境。 */
    private fun localCapability(): RunDeviceCapability = RunDeviceCapability(
        id = LOCAL_DEVICE_ID,
        label = "本机（内置运行时）",
        type = "local",
        status = RunDeviceStatus.ONLINE,
        platform = "android",
        architecture = System.getProperty("os.arch"),
        supportsDebug = true,
        supportsProfile = true,
        supportsRelease = true,
        rawDetails = mapOf("runtime" to "embedded proot guest")
    )

    fun cached(typeId: String): List<RunDevice> = cache[typeId].orEmpty()
    fun cachedCapabilities(typeId: String): List<RunDeviceCapability> = capabilityCache[typeId].orEmpty()

    private suspend fun execute(command: String, env: Map<String, String>): Pair<List<String>, Int> {
        val lines = mutableListOf<String>()
        var code = 1
        TermuxCommandExecutor(Environment.resolveShell(context)).execute(command, Environment.ensureHome(context), env).collect {
            when (it) {
                is TermuxCommandExecutor.Event.Line -> lines += it.text
                is TermuxCommandExecutor.Event.Finished -> code = it.exitCode
            }
        }
        return lines to code
    }

    private suspend fun discoverAndroid(): List<RunDeviceCapability> {
        val (lines, code) = execute("adb devices -l", Environment.buildSdkEnv(context))
        if (code != 0) return emptyList()
        return parseAdb(lines).map { base ->
            if (base.status != RunDeviceStatus.ONLINE) base
            else {
                val details = queryAndroidProperties(base.id)
                base.copy(
                    platform = "android",
                    architecture = details["ro.product.cpu.abi"],
                    apiLevel = details["ro.build.version.sdk"]?.toIntOrNull(),
                    rawDetails = details + base.rawDetails,
                    supportsDebug = true,
                    supportsProfile = true,
                    supportsRelease = true
                )
            }
        }
    }

    private suspend fun queryAndroidProperties(id: String): Map<String, String> {
        val (lines, code) = execute(
            "adb -s ${shellQuote(id)} shell getprop ro.product.cpu.abi; adb -s ${shellQuote(id)} shell getprop ro.build.version.sdk; adb -s ${shellQuote(id)} shell getprop ro.product.manufacturer; adb -s ${shellQuote(id)} shell getprop ro.product.model",
            Environment.buildSdkEnv(context)
        )
        if (code != 0) return emptyMap()
        val keys = listOf("ro.product.cpu.abi", "ro.build.version.sdk", "ro.product.manufacturer", "ro.product.model")
        return keys.mapIndexedNotNull { i, k -> lines.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap()
    }

    private suspend fun discoverFlutter(): List<RunDeviceCapability> {
        val (lines, code) = execute("flutter devices --machine", Environment.buildFlutterEnv(context))
        if (code != 0) return emptyList()
        return parseFlutter(lines.joinToString("\n"))
    }

    internal fun parseAdb(lines: List<String>): List<RunDeviceCapability> = lines
        .dropWhile { !it.trim().startsWith("List of devices") }.drop(1)
        .mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 2) return@mapNotNull null
            val status = when (p[1].lowercase()) {
                "device" -> RunDeviceStatus.ONLINE
                "offline" -> RunDeviceStatus.OFFLINE
                "unauthorized" -> RunDeviceStatus.UNAUTHORIZED
                "bootloader" -> RunDeviceStatus.BOOTLOADER
                else -> RunDeviceStatus.UNKNOWN
            }
            val fields = p.drop(2).mapNotNull { token -> token.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val label = fields["model"]?.replace('_', ' ') ?: p[0]
            RunDeviceCapability(p[0], label, "android", status, platform = "android", rawDetails = fields)
        }

    internal fun parseFlutter(raw: String): List<RunDeviceCapability> = runCatching {
        val array = JSONArray(raw.trim())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = o.optString("name").ifBlank { id }
                val platform = o.optString("targetPlatform").ifBlank { o.optString("platform") }
                val isWeb = platform.contains("web", ignoreCase = true) || o.optString("category").equals("web", true)
                val supported = !o.has("isSupported") || o.optBoolean("isSupported", false)
                val status = if (supported) RunDeviceStatus.ONLINE else RunDeviceStatus.UNKNOWN
                add(RunDeviceCapability(
                    id = id, label = name, type = "flutter", status = status, platform = platform.ifBlank { null },
                    architecture = o.optString("platformType").ifBlank { null },
                    supportsDebug = supported, supportsProfile = supported && !isWeb, supportsRelease = supported && !isWeb,
                    supportsWeb = isWeb, rawDetails = buildMap { put("category", o.optString("category")); put("sdk", o.optString("sdk")) }
                ))
            }
        }
    }.getOrDefault(emptyList()).distinctBy { it.id }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        /** 「本机（内置运行时）」设备 id：非 adb 类型（独立语言工程 / 后端 / Web / C++）使用。 */
        const val LOCAL_DEVICE_ID: String = "local"
    }
}
