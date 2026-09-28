package com.nebulaforge.app.reverse.api

import org.json.JSONObject

/** 第 9.3 节向内部 MCP 注册的逆向工具定义与调用适配器。 */
class ReverseApiMcpContributor(private val registry: ApiReverseRegistry) {
    fun definitions(): List<Pair<String, JSONObject>> = listOf(
        "capture_traffic" to JSONObject().put("type", "object").put("properties", JSONObject().put("running", JSONObject().put("type", "boolean"))),
        "list_captured_endpoints" to JSONObject().put("type", "object").put("properties", JSONObject()),
        "export_openapi_schema" to JSONObject().put("type", "object").put("properties", JSONObject()),
        "clear_captured_endpoints" to JSONObject().put("type", "object").put("properties", JSONObject()),
        "list_captured_mcp_tools" to JSONObject().put("type", "object").put("properties", JSONObject()),
        "generate_curl" to JSONObject().put("type", "object").put("properties", JSONObject().put("method", JSONObject().put("type", "string")).put("url", JSONObject().put("type", "string")))
    )

    fun call(name: String, arguments: JSONObject): JSONObject = when (name) {
        "capture_traffic" -> JSONObject().put("success", true).put("message", "代理状态由 API 逆向页面控制").put("endpointCount", registry.all().size)
        "list_captured_endpoints" -> JSONObject().put("endpoints", registry.all().map { endpoint ->
            JSONObject().put("method", endpoint.method).put("url", endpoint.normalizedUrl).put("status", endpoint.status).put("mimeType", endpoint.mimeType ?: JSONObject.NULL)
        })
        "export_openapi_schema" -> JSONObject().put("openapi", registry.toOpenApi())
        "clear_captured_endpoints" -> { registry.clear(); JSONObject().put("success", true) }
        "list_captured_mcp_tools" -> JSONObject().put("tools", registry.mcpTools())
        "generate_curl" -> {
            val method = arguments.optString("method").uppercase(); val url = arguments.optString("url")
            val endpoint = registry.all().firstOrNull { it.method.uppercase() == method && it.normalizedUrl == url } ?: error("未找到已捕获端点")
            JSONObject().put("curl", registry.toCurl(endpoint))
        }
        else -> error("未知逆向 MCP 工具：$name")
    }
}
