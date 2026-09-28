package com.nebulaforge.core.plugin

import android.content.Context

/**
 * 插件「启用状态 + 已授予权限」的持久化。
 *
 * ## 为什么必须持久化
 * 插件装完当场加载只解决「这次会话能用」。真实使用中用户会重启 IDE、被系统杀后台后重新进入，
 * 若不恢复，已安装插件就退化成「躺在磁盘上的一个安装包」——用户感知正是
 * 「装了却用不了，好像只是下载了个安装包」。
 * 权限勾选同理：内存里的 `mutableStateMapOf` 不跨进程存活，恢复加载时拿不到权限就直接加载失败。
 *
 * 存储内容只有插件 id 与权限键（如 `network`、`filesystem:read`），不含任何敏感信息。
 */
class PluginActivationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 该插件上次是否处于「已启用」状态。 */
    fun isEnabled(pluginId: String): Boolean = prefs.getBoolean(keyEnabled(pluginId), false)

    /** 记录启用/停用（停用后下次启动不会自动加载）。 */
    fun setEnabled(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyEnabled(pluginId), enabled).apply()
    }

    /** 该插件已授予的权限键集合（`type` 或 `type:scope`，与 plugin.xml 的 permission 对应）。 */
    fun granted(pluginId: String): Set<String> =
        prefs.getString(keyGrants(pluginId), null)
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

    fun setGranted(pluginId: String, granted: Set<String>) {
        prefs.edit().putString(keyGrants(pluginId), granted.sorted().joinToString(",")).apply()
    }

    /**
     * 卸载插件时清除记录。
     * 否则同一个 id 将来被重新安装（可能是不同来源、不同权限的包）会继承旧授权，属于权限泄漏。
     */
    fun forget(pluginId: String) {
        prefs.edit().remove(keyEnabled(pluginId)).remove(keyGrants(pluginId)).apply()
    }

    private fun keyEnabled(id: String) = "enabled.$id"
    private fun keyGrants(id: String) = "grants.$id"

    private companion object {
        const val PREFS = "nebulaforge.plugins"
    }
}
