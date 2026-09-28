package com.nebulaforge.app.reverse.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** API 端点仓库：模板化去重、请求体摘要、OpenAPI 3.0 与 MCP 工具定义。 */
class ApiReverseRegistry {
    data class Endpoint(
        val method: String,
        val url: String,
        val requestHeaders: Map<String, String>,
        val requestBody: String?,
        val responseHeaders: Map<String, String>,
        val status: Int,
        val mimeType: String?,
        val responsePreview: String?
    ) {
        val normalizedUrl: String get() = runCatching {
            val u = URI(url)
            val path = normalizePath(u.path.ifBlank { "/" })
            buildString { append(u.scheme).append("://").append(u.authority).append(path) }
        }.getOrDefault(url)

        companion object {
            fun normalizePath(path: String): String {
                val segments = path.split('/').map { segment ->
                    when {
                        segment.matches(Regex("\\d+")) -> "{id}"
                        segment.matches(Regex("[0-9a-fA-F]{16,}")) -> "{id}"
                        segment.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}")) -> "{id}"
                        else -> segment
                    }
                }
                return segments.joinToString("/").replace("//", "/").ifBlank { "/" }
            }
        }
        val key: String get() = method.uppercase() + " " + normalizedUrl
    }

    private val endpoints = ConcurrentHashMap<String, Endpoint>()

    fun record(endpoint: Endpoint) {
        endpoints.merge(endpoint.key, endpoint) { old, fresh ->
            old.copy(
                requestHeaders = if (old.requestHeaders.isEmpty()) fresh.requestHeaders else old.requestHeaders,
                requestBody = old.requestBody ?: fresh.requestBody,
                responseHeaders = if (old.responseHeaders.isEmpty()) fresh.responseHeaders else old.responseHeaders,
                status = fresh.status,
                mimeType = fresh.mimeType ?: old.mimeType,
                responsePreview = fresh.responsePreview ?: old.responsePreview
            )
        }
    }

    fun all(): List<Endpoint> = endpoints.values.sortedWith(compareBy({ it.normalizedUrl }, { it.method }))
    fun clear() = endpoints.clear()

    /** 生成只读重放模板，不在 IDE 内自动发送请求。 */
    fun toCurl(endpoint: Endpoint): String = buildString {
        append("curl -i -X ").append(endpoint.method.uppercase()).append(" '").append(shellQuote(endpoint.url)).append("'")
        endpoint.requestHeaders.entries.filterNot { it.key.equals("Content-Length", true) || it.key.equals("Host", true) }.take(80).forEach { (k, v) ->
            append(" -H '").append(shellQuote("$k: $v")).append("'")
        }
        endpoint.requestBody?.takeIf { it.isNotBlank() }?.let { append(" --data-raw '").append(shellQuote(it)).append("'") }
    }

    private fun shellQuote(value: String): String = value.replace("'", "'\"'\"'")

    fun toOpenApi(): String {
        val grouped = LinkedHashMap<String, MutableList<Endpoint>>()
        all().forEach { e ->
            val uri = runCatching { URI(e.normalizedUrl) }.getOrNull() ?: return@forEach
            grouped.getOrPut(uri.path.ifBlank { "/" }) { mutableListOf() }.add(e)
        }
        val origins = all().mapNotNull { runCatching { URI(it.normalizedUrl).let { u -> "${u.scheme}://${u.authority}" } }.getOrNull() }.distinct()
        val root = JSONObject().apply {
            put("openapi", "3.0.3")
            put("info", JSONObject().put("title", "NebulaForge API Capture").put("version", "1.0.0"))
            put("servers", JSONArray(origins.map { JSONObject().put("url", it) }))
            put("paths", JSONObject().apply {
                grouped.forEach { (path, eps) ->
                    put(path, JSONObject().apply {
                        eps.forEach { e -> put(e.method.lowercase(), operation(e)) }
                    })
                }
            })
        }
        return root.toString(2)
    }

    fun mcpTools(): JSONArray = JSONArray().apply {
        all().forEach { e ->
            put(JSONObject().apply {
                put("name", "api_${e.method.lowercase()}_${Integer.toHexString(e.key.hashCode())}")
                put("description", "捕获的 API 端点：${e.method} ${e.normalizedUrl}")
                put("inputSchema", inputSchema(e))
                put("endpoint", e.normalizedUrl)
                put("method", e.method)
            })
        }
    }

    private fun operation(e: Endpoint): JSONObject = JSONObject().apply {
        put("operationId", operationId(e))
        put("responses", JSONObject().put(e.status.toString(), JSONObject().put("description", "Captured response")))
        val params = JSONArray()
        pathParameters(e.normalizedUrl).forEach { name ->
            params.put(JSONObject().put("name", name).put("in", "path").put("required", true).put("schema", JSONObject().put("type", "string")))
        }
        if (params.length() > 0) put("parameters", params)
        e.requestBody?.let { body ->
            if (looksJson(body)) put("requestBody", JSONObject().put("content", JSONObject().put("application/json", JSONObject().put("schema", inferJsonSchema(body)))))
        }
    }

    private fun inputSchema(e: Endpoint): JSONObject {
        val properties = JSONObject()
        pathParameters(e.normalizedUrl).forEach { properties.put(it, JSONObject().put("type", "string")) }
        if (e.requestBody != null) properties.put("body", JSONObject().put("type", "string").put("description", "原始请求体（可选）"))
        return JSONObject().put("type", "object").put("properties", properties)
    }

    private fun pathParameters(path: String): List<String> = Regex("\\{([^}]+)}").findAll(path).map { it.groupValues[1] }.distinct().toList()


    private fun operationId(e: Endpoint): String = "${e.method.lowercase()}_${Integer.toHexString(e.key.hashCode())}"
    private fun looksJson(body: String): Boolean = body.trimStart().startsWith("{") || body.trimStart().startsWith("[")
    private fun inferJsonSchema(body: String): JSONObject = runCatching {
        val value = org.json.JSONTokener(body).nextValue()
        when (value) {
            is JSONObject -> JSONObject().put("type", "object").put("additionalProperties", true)
            is JSONArray -> JSONObject().put("type", "array").put("items", JSONObject().put("type", "object"))
            else -> JSONObject().put("type", "string")
        }
    }.getOrElse { JSONObject().put("type", "string") }
}
