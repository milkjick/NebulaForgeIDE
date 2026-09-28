package com.nebulaforge.core.plugin

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 插件权限沙箱（开发方案第 15.4 节）。
 *
 * 方案要求两点，这里全部落地，且都是**运行时**强制，不是仅安装时提示：
 * 1. 安装时展示 `plugin.xml` 的 `<permissions>` 清单，用户确认后才允许安装
 *    —— 由 [PluginPermissionPolicy.validate] + [PluginRuntime.load] 的 `grantedPermissions` 承担。
 * 2. 运行时每次插件调用受限 API 前，[PluginContext] 做权限检查，未授权直接抛 [SecurityException]，
 *    **不静默失败**。
 */

/** 插件受限能力类型。与 plugin.xml 中 `<permission type="..."/>` 的 type 取值对应。 */
enum class PluginCapability(val id: String) {
    FILE_SYSTEM("filesystem"),
    NETWORK("network"),
    SHELL("shell"),
    PROJECT_ACCESS("project");

    companion object {
        fun fromId(id: String): PluginCapability? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** 插件调用未授权能力时抛出。显式继承 [SecurityException]，上层可统一按安全异常处理。 */
class PluginPermissionDeniedException(pluginId: String, capability: String, scope: String? = null) :
    SecurityException(
        "插件 $pluginId 未获得权限：$capability" + (scope?.let { ":$it" } ?: "") +
            "（请卸载后重新安装并在权限清单中勾选该项）"
    )

/**
 * 单个插件的权限判定器。
 *
 * 权限键格式与 [PluginPermissionPolicy] 一致：`type` 或 `type:scope`；
 * 同时支持通配授予 `type:*`。
 */
class PluginSandbox(
    val pluginId: String,
    grantedPermissions: Set<String>
) {
    private val granted: Set<String> = grantedPermissions.toSet()

    val permissions: Set<String> get() = granted

    fun has(capability: PluginCapability, scope: String? = null): Boolean =
        isGranted(capability.id, scope)

    fun has(capability: String, scope: String? = null): Boolean = isGranted(capability, scope)

    /** 未授权直接抛 [PluginPermissionDeniedException]，绝不静默失败 */
    fun require(capability: PluginCapability, scope: String? = null) {
        if (!has(capability, scope)) throw PluginPermissionDeniedException(pluginId, capability.id, scope)
    }

    fun require(capability: String, scope: String? = null) {
        if (!has(capability, scope)) throw PluginPermissionDeniedException(pluginId, capability, scope)
    }

    private fun isGranted(type: String, scope: String?): Boolean {
        if (granted.isEmpty()) return false
        val exact = type + (scope?.let { ":$it" } ?: "")
        if (exact in granted) return true
        if ("$type:*" in granted) return true
        // 无 scope 声明时，任一带 scope 的同类授权均视为可用（插件声明比宿主请求更宽）
        if (scope == null && granted.any { it == type || it.startsWith("$type:") }) return true
        return false
    }
}

/**
 * 提供给插件的受限访问入口。所有受限 API 都在执行前做权限检查。
 *
 * [projectRoot] 为当前项目根目录；文件系统访问会被限制在该目录内（防路径穿越）。
 */
class PluginContext(
    val pluginId: String,
    val sandbox: PluginSandbox,
    val projectRoot: File?
) {
    /** 读取项目内文件；需要 `filesystem:read` 权限 */
    fun readText(relativePath: String): String {
        sandbox.require(PluginCapability.FILE_SYSTEM, "read")
        return resolve(relativePath).readText()
    }

    /** 写入项目内文件；需要 `filesystem:write` 权限 */
    fun writeText(relativePath: String, content: String) {
        sandbox.require(PluginCapability.FILE_SYSTEM, "write")
        val target = resolve(relativePath)
        target.parentFile?.mkdirs()
        target.writeText(content)
    }

    /** 列出项目内目录；需要 `filesystem:read` 权限 */
    fun listFiles(relativePath: String = "."): List<String> {
        sandbox.require(PluginCapability.FILE_SYSTEM, "read")
        val dir = resolve(relativePath)
        return dir.listFiles()?.map { it.name } ?: emptyList()
    }

    /** 发起网络连接；需要 `network` 权限。返回已打开的连接，不做任何自动收尾。 */
    fun openConnection(url: String): HttpURLConnection {
        sandbox.require(PluginCapability.NETWORK)
        return (URL(url).openConnection() as HttpURLConnection)
    }

    /** 构造一条 shell 命令描述；需要 `shell` 权限。实际执行由宿主注入的执行器完成。 */
    fun shellCommand(command: String): String {
        sandbox.require(PluginCapability.SHELL)
        return command
    }

    /** 访问当前项目根目录；需要 `project` 权限 */
    fun requireProjectRoot(): File {
        sandbox.require(PluginCapability.PROJECT_ACCESS)
        return projectRoot ?: error("插件 $pluginId 请求了项目访问，但当前没有打开的项目")
    }

    private fun resolve(relativePath: String): File {
        val root = requireProjectRoot()
        val rootCanonical = root.canonicalFile
        val candidate = File(rootCanonical, relativePath).canonicalFile
        require(
            candidate.path == rootCanonical.path ||
                candidate.path.startsWith(rootCanonical.path + File.separator)
        ) { "插件 $pluginId 尝试访问项目外路径：$relativePath" }
        return candidate
    }
}
