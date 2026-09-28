package com.nebulaforge.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 模型请求的一次工具调用。 */
data class AiToolCall(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val arguments: JSONObject,
    val rawJson: String
)

/** 一次工具调用的执行结果（会被回灌给模型，也会渲染成界面上的工具卡片）。 */
data class AiToolResult(
    val callId: String,
    val name: String,
    val ok: Boolean,
    val exitCode: Int = 0,
    val output: String,
    /** 该结果是否改变了「工作台数据」（妙招/技能/记忆/经验），UI 需要刷新面板。 */
    val stateChanged: Boolean = false
)

/**
 * 工具调用协议。
 *
 * 为什么用「围栏代码块 + JSON」而不上原生 function-calling：
 *  1. 用户会接各种 OpenAI 兼容端点（自建网关、国产模型、本地 llama.cpp），原生 tool-call
 *     字段支持参差不齐，围栏 JSON 是**所有模型都能稳定产出**的形态；
 *  2. 出错可降级：解析失败时原文仍能展示给用户看，而不是静默丢失一次调用；
 *  3. 兼容历史：逆向工作台里已经在用这套 ```tool 写法。
 *
 * 兼容三种常见形态：
 *  - `{"name":"read_file","arguments":{...}}`（我们的规范写法）
 *  - `{"tool":"read_file","args":{...}}`
 *  - `{"function":{"name":"read_file","arguments":"{...}"}}`（OpenAI 风格，arguments 是字符串）
 */
object AiToolProtocol {

    /** 允许作为工具块的语言标记：避免把普通 ```kotlin 代码块误当成工具调用。 */
    private val TOOL_LANGS = setOf("tool", "tool_call", "tool_calls", "toolcall", "tool-use", "nebula-tool", "tools")

    /**
     * 「宽松」语言标记（含无标记的裸围栏）。
     *
     * 真机根因：我们的规范是 ```tool，但**走 AI 聚合网关（网页 AI）时**模型非常爱写 ```json ——
     * 它们按「你要给我 JSON」理解，而不是按语言标记理解（连网关自己的续写提示也写的是「JSON 工具协议」）。
     * 旧实现只认上面那几个标记，于是网关模型输出的工具调用**一个都不会被执行**：
     * 回答很漂亮、就是不动手读文件/跑命令，用户看到的就是「AI 网关无法调用工作台（工具）」。
     *
     * 这里放宽判定，但**必须**同时满足「name 命中真实工具名」（见 [KNOWN_TOOLS]）才算工具调用，
     * 所以普通示例 JSON（如 {"name":"张三","skill":"Kotlin"}）不会被误执行。
     */
    private val LOOSE_LANGS = setOf("json", "json5", "")

    /** 真实工具名集合：宽松模式下的准入判据（静态基线；MCP 等动态工具由 [parse] 的 extraKnown 补充）。 */
    private val KNOWN_TOOLS: Set<String> =
        com.nebulaforge.core.agent.AgentToolCatalog.all.map { it.name }.toSet()

    /**
     * 工具名容错表：网关/网页 AI 常把工具名写成自然语言（"bash"、"read"、"search"）或驼峰/连字符形式。
     * 只做**无歧义**映射，且映射目标必须在本次真实可用集合里才生效（见 [resolveName]）。
     */
    private val ALIASES: Map<String, String> = mapOf(
        "read" to "read_file", "cat" to "read_file", "open_file" to "read_file", "view_file" to "read_file",
        "ls" to "list_files", "dir" to "list_files", "list_dir" to "list_files", "list_directory" to "list_files",
        "grep" to "grep_project", "search" to "grep_project", "search_code" to "grep_project",
        "find_in_files" to "grep_project", "ripgrep" to "grep_project",
        "write" to "write_file", "save_file" to "write_file", "create_file" to "write_file",
        "overwrite_file" to "write_file", "write_text_file" to "write_file",
        // 代码编辑 / 文件管理（本轮补齐的工具，此前 AI 一律被引导去 write_file 整份重写）
        "edit" to "edit_file", "editfile" to "edit_file", "str_replace" to "edit_file",
        "strreplace" to "edit_file", "replace_in_file" to "edit_file", "modify_file" to "edit_file",
        "patch" to "edit_file", "apply_patch" to "edit_file",
        "search_replace" to "search_replace", "bulk_replace" to "search_replace",
        "batch_replace" to "search_replace", "refactor" to "search_replace",
        "read_many" to "read_files", "multi_read" to "read_files", "batch_read" to "read_files",
        "read_files" to "read_files",
        "delete" to "delete_path", "remove" to "delete_path", "remove_file" to "delete_path",
        "rm_file" to "delete_path", "delete_dir" to "delete_path",
        "move" to "move_file", "rename" to "move_file", "mv" to "move_file", "rename_file" to "move_file",
        // Git / 网络 / 协作
        "git_status" to "git", "git_diff" to "git", "git_commit" to "git", "git_log" to "git", "vcs" to "git",
        "http" to "http_request", "http_get" to "http_request", "http_post" to "http_request",
        "request" to "http_request", "api_call" to "http_request",
        "ask" to "ask_user", "question" to "ask_user", "confirm" to "ask_user",
        "skills" to "list_skills", "list_tricks" to "list_skills", "show_skills" to "list_skills",
        "remove_skill" to "delete_skill", "delete_trick" to "delete_skill", "remove_trick" to "delete_skill",
        // 逆向（本轮新接入 AI 工具台）
        "reverse_apk" to "apk_reverse", "decompile_apk" to "apk_reverse", "unpack_apk" to "apk_reverse",
        "apktool" to "apk_reverse",
        "reverse_web" to "web_reverse", "traffic" to "web_reverse", "network_records" to "web_reverse",
        "reverse_api" to "api_reverse", "endpoints" to "api_reverse", "mitm" to "api_reverse",
        "proxy" to "api_reverse", "openapi" to "api_reverse",
        "ca_cert" to "ca_certificate", "ca_certificate" to "ca_certificate", "certificate" to "ca_certificate",
        "install_ca" to "ca_certificate", "trust_ca" to "ca_certificate",
        "bash" to "run_command", "shell" to "run_command", "exec" to "run_command", "terminal" to "run_command",
        "execute_command" to "run_command", "run" to "run_command", "sh" to "run_command",
        "websearch" to "web_search", "search_web" to "web_search", "google" to "web_search",
        "fetch" to "fetch_page", "fetch_url" to "fetch_page", "open_url" to "fetch_page", "read_url" to "fetch_page",
        "browse" to "fetch_page", "curl" to "fetch_page",
        "plan" to "propose_plan", "todo" to "propose_plan",
        "remember_fact" to "remember", "memory" to "remember",
        "logcat" to "capture_logcat", "read_logs" to "capture_logcat",
        "screenshot" to "read_screen", "observe_screen" to "read_screen",
        "pack" to "pack_files", "zip_project" to "pack_files"
    )

    /** 工具名归一化：小写 + 去掉连字符/空格/点，便于「read-file / ReadFile / read file」统一比对。 */
    private fun normalizeName(name: String): String =
        name.trim().lowercase().replace('-', '_').replace(' ', '_').replace('.', '_')

    /** 真实名 ↔ 归一化名 双向查找表（准入与纠错共用一张表，保证两处判定一致）。 */
    private fun canonicalOf(extraKnown: Set<String>): Map<String, String> {
        val map = HashMap<String, String>()
        (KNOWN_TOOLS + extraKnown).forEach { name ->
            map[name] = name
            map[normalizeName(name)] = name
        }
        return map
    }

    /**
     * 把模型写的名字解析成真实工具名。
     * [canonical] 里同时放了「真实名」和「归一化名」两种键，所以三种写错方式都能救回来：
     * 大小写/连字符差异（read-file）、驼峰（readFile）、自然语言别名（bash → run_command）。
     */
    private fun resolveName(raw: String, canonical: Map<String, String>): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        canonical[trimmed]?.let { return it }
        val flat = normalizeName(trimmed)
        canonical[flat]?.let { return it }
        ALIASES[flat]?.let { alias -> canonical[alias]?.let { return it } }
        // 驼峰：readFile → read_file（仅当归一化后在下划线形式里存在时生效）
        val snake = trimmed.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
        canonical[normalizeName(snake)]?.let { return it }
        return null
    }

    private val FENCE = Regex("(?s)```([A-Za-z_\\-]*)[ \\t]*\\r?\\n(.*?)```")

    /**
     * 解析消息里的全部工具调用（按出现顺序）。
     *
     * [extraKnown]：本次额外可执行的工具名（例如 MCP Server 现暴露的工具）——
     * 让「宽松准入」也能跟上**动态**能力，而不是只认静态目录。
     */
    fun parse(text: String, extraKnown: Set<String> = emptySet()): List<AiToolCall> {
        if (text.isBlank()) return emptyList()
        val canonical = canonicalOf(extraKnown)
        val calls = mutableListOf<AiToolCall>()
        if (text.contains("```")) {
            FENCE.findAll(text).forEach { match ->
                val lang = match.groupValues[1].lowercase()
                val strict = lang in TOOL_LANGS
                // 规范标记（```tool）直接收；```json / 裸围栏等宽松标记要求 name 能解析成真实工具名，
                // 避免把回答里的示例 JSON 当成调用去执行（见 LOOSE_LANGS / resolveName）。
                if (!strict && lang !in LOOSE_LANGS) return@forEach
                val body = match.groupValues[2]
                splitJsonObjects(body).forEach { json ->
                    val call = parseOne(json) ?: return@forEach
                    val resolved = resolveName(call.name, canonical)
                    // 规范标记下即使名字不认识也照收：执行器会把「未知工具 + 可用清单」回灌，
                    // 模型下一轮就能自我纠正，比静默丢弃更利于收敛。
                    if (resolved != null) calls.add(call.copy(name = resolved))
                    else if (strict) calls.add(call)
                }
            }
        }
        if (calls.isEmpty()) calls += parseBareJson(text, canonical)
        return calls
    }

    /**
     * 裸 JSON 兜底：模型（尤其网页 AI）常把工具调用**不加围栏**直接写出来。
     * 只在「整段文本就是一个 JSON 对象/数组」且「带参数键」时才认定，避免把说明里的片段当调用执行。
     */
    private fun parseBareJson(text: String, canonical: Map<String, String>): List<AiToolCall> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return emptyList()
        val out = mutableListOf<AiToolCall>()
        splitJsonObjects(trimmed).forEach { json ->
            if (!json.contains("\"arguments\"") && !json.contains("\"args\"") && !json.contains("\"input\"")) return@forEach
            val call = parseOne(json) ?: return@forEach
            val resolved = resolveName(call.name, canonical) ?: return@forEach
            out.add(call.copy(name = resolved))
        }
        return out
    }

    /** 界面展示用：去掉工具块，避免用户看到一大坨 JSON；若去掉后为空则给一句占位。 */
    fun stripBlocks(text: String, extraKnown: Set<String> = emptySet()): String {
        if (!text.contains("```")) return text
        val canonical = canonicalOf(extraKnown)
        val stripped = FENCE.replace(text) { match ->
            val lang = match.groupValues[1].lowercase()
            // ```json 等宽松围栏：只有内容确实是**可执行的**工具调用才剥掉，
            // 否则（真的只是示例/数据 JSON）原样保留给用户看。
            val looseToolCall = lang in LOOSE_LANGS && splitJsonObjects(match.groupValues[2]).any { json ->
                val call = parseOne(json) ?: return@any false
                resolveName(call.name, canonical) != null
            }
            if (lang in TOOL_LANGS || looseToolCall) "" else match.value
        }.trim()
        return stripped
    }

    /** 把工具结果渲染成回灌给模型的文本块。 */
    fun renderResults(results: List<AiToolResult>, perResultLimit: Int = 6000, totalLimit: Int = 24_000): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder("【工具执行结果】\n")
        results.forEachIndexed { index, result ->
            sb.append(index + 1).append(") ").append(result.name)
                .append(if (result.ok) " (成功" else " (失败")
                .append(", exit=").append(result.exitCode).append(")\n")
            val body = if (result.output.length <= perResultLimit) result.output
            else result.output.take(perResultLimit) + "\n…（结果过长已截断，共 ${result.output.length} 字符）"
            sb.append(body).append('\n')
        }
        val text = sb.toString()
        return if (text.length <= totalLimit) text
        else text.take(totalLimit) + "\n…（本次工具结果总量过大已截断，请按需分批读取）"
    }

    private fun parseOne(json: String): AiToolCall? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        // 形态 3：OpenAI function 包装
        val function = obj.optJSONObject("function")
        val name = sequenceOf(
            obj.optString("name"),
            obj.optString("tool"),
            obj.optString("tool_name"),
            function?.optString("name").orEmpty()
        ).firstOrNull { it.isNotBlank() } ?: return null

        val args = obj.optJSONObject("arguments")
            ?: obj.optJSONObject("args")
            ?: obj.optJSONObject("input")
            ?: obj.optJSONObject("parameters")
            ?: run {
                val raw = obj.opt("arguments")
                // OpenAI 风格：arguments 是 JSON 字符串
                if (raw is String && raw.isNotBlank()) runCatching { JSONObject(raw) }.getOrNull() else null
            }
            ?: JSONObject()
        return AiToolCall(name = name.trim(), arguments = args, rawJson = json)
    }

    /**
     * 从一段文本里切出所有**顶层** JSON 对象（支持一个代码块里放多个调用）。
     * 手写扫描而不是正则，是因为参数里常常含有 `{` `}`（例如 content 写代码）。
     */
    private fun splitJsonObjects(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        body.forEachIndexed { index, ch ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                return@forEachIndexed
            }
            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) depth--
                    if (depth == 0 && start >= 0) {
                        out.add(body.substring(start, index + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }

    /** 计划卡片用：把 propose_plan 的参数渲染成「N 步」文本。 */
    fun renderPlan(summary: String, steps: JSONArray?): String = buildString {
        append(summary.ifBlank { "执行计划" })
        if (steps == null || steps.length() == 0) return@buildString
        append("（共 ").append(steps.length()).append(" 步）\n")
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            append(i + 1).append(". ").append(step.optString("title").ifBlank { "步骤 ${i + 1}" })
            val action = step.optString("action")
            if (action.isNotBlank()) append(" [").append(action.uppercase()).append(']')
            val description = step.optString("description")
            if (description.isNotBlank()) append("：").append(description)
            append('\n')
        }
    }

    /**
     * 计划闸门用：把 propose_plan 的 steps 数组压成「一行一步」的标题列表。
     *
     * 兼容三种写法（网关模型都写过）：
     *  - `[{"title":"改 A","action":"BUILD"}]`；
     *  - `[{"desc":"跑命令"}]`（有的模型把 title 写成 desc）；
     *  - `["改 A","跑 B"]`（纯字符串数组）。
     */
    fun planStepTitles(steps: org.json.JSONArray?): List<String> {
        if (steps == null || steps.length() == 0) return emptyList()
        val objects = buildList {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                val title = step.optString("title")
                    .ifBlank { step.optString("name").ifBlank { step.optString("desc").ifBlank { "步骤 ${i + 1}" } } }
                val action = step.optString("action")
                val description = step.optString("description")
                add(buildString {
                    append(title)
                    if (action.isNotBlank()) append(" [").append(action.uppercase()).append(']')
                    if (description.isNotBlank() && description != title) append("：").append(description.take(120))
                })
            }
        }
        if (objects.isNotEmpty()) return objects
        return buildList {
            for (i in 0 until steps.length()) {
                steps.optString(i).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
    }


    /**
     * `steps` 被写成多行字符串时的解析（工具参数声明允许数组，但模型两种都会写）。
     * 依次支持 "1. 改 A" / "- 改 A" / "改 A" 三种行格式。
     */
    fun planStepTitlesFromText(text: String): List<String> =
        text.replace("\r\n", "\n").split('\n')
            .map { it.trim() }
            .mapNotNull { line ->
                line.removePrefix("-").removePrefix("*").trim()
                    .replace(Regex("^\\d+[.、)]\\s*"), "").trim()
                    .takeIf { it.isNotEmpty() }
            }

}
