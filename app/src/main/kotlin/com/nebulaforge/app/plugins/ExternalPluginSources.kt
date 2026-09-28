package com.nebulaforge.app.plugins

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 外部真实生态源。MCP 服务与 Nebula 插件分开标记，避免安装不兼容的 IntelliJ 插件。 */
object ExternalPluginSources {
    const val MCP_REGISTRY = "https://registry.modelcontextprotocol.io/v0.1/servers"
    const val JETBRAINS_MARKETPLACE = "https://plugins.jetbrains.com"

    data class ExternalItem(val id: String, val name: String, val description: String?, val sourceUrl: String, val remoteUrl: String?, val installable: Boolean, val kind: String)

    fun loadMcpServers(limit: Int = 50): List<ExternalItem> {
        val c = URL(MCP_REGISTRY).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000; c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/json"); c.connect()
        require(c.responseCode in 200..299) { "MCP Registry 请求失败：HTTP ${c.responseCode}" }
        val root = c.inputStream.use { JSONObject(it.bufferedReader().readText()) }
        val arr = root.optJSONArray("servers") ?: JSONArray()
        return buildList {
            for (i in 0 until minOf(arr.length(), limit)) {
                val item = arr.optJSONObject(i) ?: continue
                val server = item.optJSONObject("server") ?: item
                val id = server.optString("name"); if (id.isBlank()) continue
                val remotes = server.optJSONArray("remotes")
                val remoteUrl = remotes?.let { a -> (0 until a.length()).asSequence().mapNotNull { a.optJSONObject(it)?.optString("url") }.firstOrNull { it.startsWith("https://") } }
                add(ExternalItem(id, server.optString("title", id), server.optString("description").ifBlank { null }, server.optString("repository", MCP_REGISTRY), remoteUrl, remoteUrl != null, "MCP"))
            }
        }
    }
}
