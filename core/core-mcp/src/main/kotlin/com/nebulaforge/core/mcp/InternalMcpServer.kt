package com.nebulaforge.core.mcp

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** IDE 内部 MCP Server。所有文件工具均限制在项目根目录内，避免路径穿越。 */
class InternalMcpServer(
    private val projectRoot: File,
    private val buildHandler: suspend (String) -> JSONObject = { task -> JSONObject().put("success", false).put("task", task).put("message", "未接入构建控制器") },
    private val extraTools: Map<String, Pair<McpToolDefinition, suspend (JSONObject) -> JSONObject>> = emptyMap()
) {
    private val handlers = ConcurrentHashMap<String, suspend (JSONObject) -> JSONObject>()
    private val definitions = ConcurrentHashMap<String, McpToolDefinition>()

    init {
        register("run_build", "执行当前项目的构建任务", schema("task")) { args -> buildHandler(args.optString("task", "assembleDebug")) }
        extraTools.forEach { (name, pair) -> register(name, pair.first.description, pair.first.inputSchema, pair.second) }
        register("read_file", "读取项目内指定路径的文件内容", schema("path")) { args ->
            val file = safeFile(args.getString("path"));
            require(file.isFile) { "文件不存在" }
            JSONObject().put("path", relative(file)).put("content", file.readText()).put("size", file.length())
        }
        register("write_file", "写入项目内指定路径的文件内容", schema("path", "content")) { args ->
            val file = safeFile(args.getString("path")); file.parentFile?.mkdirs(); file.writeText(args.getString("content"));
            JSONObject().put("path", relative(file)).put("size", file.length())
        }
        register("list_files", "列出项目内指定目录的文件", schema("path")) { args ->
            val dir = safeFile(args.optString("path", ".")); require(dir.isDirectory) { "目录不存在" }
            JSONObject().put("path", relative(dir)).put("files", org.json.JSONArray().apply { dir.listFiles()?.sortedBy { it.name }?.forEach { put(JSONObject().put("name", it.name).put("directory", it.isDirectory).put("size", if (it.isFile) it.length() else 0)) } })
        }
    }

    fun register(name: String, description: String, inputSchema: JSONObject, handler: suspend (JSONObject) -> JSONObject) {
        require(name.matches(Regex("[a-zA-Z0-9_.-]+"))) { "非法工具名" }
        definitions[name] = McpToolDefinition(name, description, inputSchema); handlers[name] = handler
    }
    fun listTools(): List<McpToolDefinition> = definitions.values.sortedBy { it.name }
    suspend fun call(name: String, args: JSONObject): JSONObject = handlers[name]?.invoke(args) ?: error("未知 MCP 工具：$name")

    suspend fun handle(request: McpRequest): String = try {
        when (request.method) {
            "initialize" -> McpJsonRpc.response(request.id, JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject().put("tools", JSONObject())).put("serverInfo", JSONObject().put("name", "NebulaForge Internal MCP").put("version", "1.0")))
            "tools/list" -> McpJsonRpc.response(request.id, McpJsonRpc.toolsResult(listTools()))
            "tools/call" -> {
                val p = request.params ?: error("缺少 params"); val result = call(p.getString("name"), p.optJSONObject("arguments") ?: JSONObject())
                McpJsonRpc.response(request.id, JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", result.toString()))).put("structuredContent", result).put("isError", false))
            }
            "ping" -> McpJsonRpc.response(request.id, JSONObject())
            else -> McpJsonRpc.error(request.id, -32601, "不支持的方法：${request.method}")
        }
    } catch (t: Throwable) { McpJsonRpc.error(request.id, -32000, t.message ?: t.javaClass.simpleName) }

    private fun safeFile(path: String): File {
        val root = projectRoot.canonicalFile; val file = File(root, path).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "路径超出项目目录" }; return file
    }
    private fun relative(file: File): String = file.canonicalFile.relativeTo(projectRoot.canonicalFile).path.replace(File.separatorChar, '/')
    private fun schema(vararg required: String): JSONObject = JSONObject().put("type", "object").put("properties", JSONObject().apply { required.forEach { put(it, JSONObject().put("type", "string")) } }).put("required", org.json.JSONArray().apply { required.forEach(::put) })
}
