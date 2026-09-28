package com.nebulaforge.core.mcp

import org.json.JSONArray
import org.json.JSONObject

/** MCP JSON-RPC 2.0 协议模型与序列化工具。 */
data class McpRequest(val id: String, val method: String, val params: JSONObject? = null) {
    fun toJson(): JSONObject = JSONObject().apply { put("jsonrpc", "2.0"); put("id", id); put("method", method); if (params != null) put("params", params) }
}
data class McpError(val code: Int, val message: String, val data: JSONObject? = null)
data class McpResponse(val id: String?, val result: JSONObject? = null, val error: McpError? = null)
data class McpToolDefinition(val name: String, val description: String, val inputSchema: JSONObject)

data class McpToolCall(val name: String, val arguments: JSONObject)

object McpJsonRpc {
    fun parseRequest(json: String): McpRequest {
        val o = JSONObject(json)
        require(o.optString("jsonrpc") == "2.0") { "仅支持 JSON-RPC 2.0" }
        return McpRequest(o.optString("id"), o.getString("method"), o.optJSONObject("params"))
    }
    fun response(id: String?, result: JSONObject): String = JSONObject().apply { put("jsonrpc", "2.0"); if (id != null) put("id", id); put("result", result) }.toString()
    fun error(id: String?, code: Int, message: String): String = JSONObject().apply { put("jsonrpc", "2.0"); if (id != null) put("id", id); put("error", JSONObject().put("code", code).put("message", message)) }.toString()
    fun toolsResult(tools: List<McpToolDefinition>): JSONObject = JSONObject().put("tools", JSONArray().apply { tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.inputSchema)) } })
}
