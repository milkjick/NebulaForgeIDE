package com.nebulaforge.app.ai

import android.content.Context
import com.nebulaforge.core.agent.AgentCapabilities
import com.nebulaforge.core.agent.AgentSkill
import com.nebulaforge.core.agent.AgentToolCatalog
import com.nebulaforge.core.agent.AgentTrick
import com.nebulaforge.core.agent.AiAttachmentTextExtractor
import android.util.Base64 as AndroidBase64
import org.json.JSONObject
import java.io.File

/**
 * 工具执行器需要的外部能力。由 `NebulaForgeApplication` 实现，避免本类反向依赖整个 Application。
 */
interface TaskToolRuntime {
    val toolContext: Context
    val capabilities: AgentCapabilities
    val approvalBroker: TaskApprovalBroker
    fun currentProjectPath(): String?

    /**
     * 宿主能力目录与当前授权状态（受授权网关的「能调什么」视图）。
     *
     * 与 [invokeHostCapability] 配套：模型先看一眼目录再决定调哪条，避免瞎试
     * （瞎试的代价是每次都会弹一次授权窗，用户会烦）。
     */
    fun hostCapabilityCatalog(): String

    /**
     * 经「受授权、可审计」的能力网关调用宿主功能（caller 固定为 `agent`）。
     *
     * 与工具直接摸内部对象的区别：写入 / 执行 / 模型调用会**当场请用户授权**，
     * 且无论放行、拒绝还是失败都会留一条审计 —— 这样「AI 动了什么」可追溯。
     */
    suspend fun invokeHostCapability(capability: String, arguments: JSONObject): String

    /** 最近能力调用审计文本（谁调的、准没准、成没成、参数摘要）。 */
    fun hostCapabilityAudit(limit: Int): String
    /** 切换当前项目（AI 的 create_project 建完项目后要把工作区切过去）。 */
    fun selectProject(path: String)
    /** 在设备/内嵌用户态执行命令，返回可读文本（内部已做安全策略与审计）。 */
    fun execOnDevice(command: String, timeoutMs: Long): String

    /**
     * 编译/打包项目（走项目真实构建工具链，而不是设备 shell）。
     *
     * 实现方（Application）接到 [com.nebulaforge.core.session.UnifiedRunController]：
     * Android=Gradle 的 buildOnly，Flutter/Web/其它栈=各自构建命令 + 用户态终端执行器。
     *
     * @param task 构建任务或运行配置 id（留空=默认构建）
     * @param module Gradle 模块（如 :app），可空
     * @return 可读文本（成功/失败结论 + 关键日志 + 产物路径）
     */
    suspend fun buildProject(projectPath: String, task: String?, module: String?, cleanFirst: Boolean, timeoutMs: Long): String

    /** 构建并运行/安装到设备，返回过程摘要。 */
    suspend fun runProject(projectPath: String, timeoutMs: Long): String

    /** 构建环境自检：JDK / Gradle / Android SDK / Flutter / Node 与 shell 通道是否就绪。 */
    fun toolchainStatus(verbose: Boolean): String

    /** 用内置模板新建项目（复用新建项目向导的脚手架），返回摘要文本（含新项目路径）。 */
    suspend fun createProject(name: String, template: String?, packageName: String?): String

    /**
     * 在「项目工具链用户态」执行命令。
     *
     * 与 [execOnDevice] 的区别（这是「AI 用不了编译环境」的根因，务必分清）：
     *  - [execOnDevice]：设备 shell（Shizuku / 内嵌 toybox），**没有** JDK / Gradle / SDK / Flutter / Node；
     *  - 本方法：proot 用户态 + [com.nebulaforge.core.environment.Environment] 注入的完整工具链环境。
     */
    suspend fun execInToolchain(command: String, cwd: String?, timeoutMs: Long): String

    /**
     * 本轮任务是否已经立过计划（用户要求：AI 动手前必须先根据用户要求制定计划）。
     *
     * 由 app 层查 [com.nebulaforge.app.NebulaForgeApplication] 的 agentPlanState 得出。
     * 返回 false 时，写类/执行类工具会被 [AiTaskToolHost] 直接拒绝，并提示模型先调用 propose_plan。
     */
    fun hasActivePlan(): Boolean

    /**
     * 登记一份**由模型主动提出**的计划（`propose_plan` 工具的落地点）。
     *
     * 关键修复（2.12.80）：以前 `propose_plan` 只把计划渲染成文本回给模型，**从不写进计划状态**，
     * 于是闸门 [hasActivePlan] 永远为 false —— 模型越是乖乖立计划，越是被反复拒绝，
     * 最终表现就是「AI 立了计划也执行不下去，只能停下来反问用户」。
     *
     * @return 登记后的步骤数；0 表示没解析出步骤（闸门会退回自动兜底）
     */
    fun registerPlan(summary: String, steps: List<String>): Int

    /**
     * 闸门兜底：模型忘记立计划时，工作台按**本次实际操作**自动登记一份计划再放行。
     *
     * 设计取舍：宁可「计划由工作台代写」，也不要「把 AI 的任务拦死」。
     * 同一个自动计划内的后续操作会**追加**步骤，任务面板便随 AI 动手实时生长。
     *
     * @return true = 已登记，调用方应继续执行
     */
    fun ensureAutoPlan(operation: String): Boolean

    /** 向用户提问并**挂起等待回答**（返回用户答复文本）。 */
    suspend fun askUser(question: String, options: List<String>): String

    /** 发一条真实 HTTP 请求，返回「状态码 + 响应头 + 正文」的可读文本。 */
    suspend fun httpRequest(method: String, url: String, headers: String?, body: String?): String

    /** 列出技能与妙招（kind=skill|trick|all）。 */
    fun describeSkills(kind: String): String

    /** 删除技能或妙招，返回结果说明。 */
    fun removeSkill(kind: String, id: String): String

    /**
     * APK 逆向。
     *
     * @param action inspect / unpack / files / read / write / rebuild / evidence
     * @param path APK 或反编译工作区目录（留空=最近一次分析的 APK）
     * @param file read/write 时的工作区文件
     * @param content write 的新内容
     * @param out rebuild 的输出 APK 路径
     */
    suspend fun reverseApk(action: String, path: String?, file: String?, content: String?, out: String?): String

    /** Web 逆向（action=records/analyze/to_api/clear）。 */
    suspend fun reverseWeb(action: String, url: String?, limit: Int): String

    /** API 逆向（action=endpoints/analyze/openapi/proxy_start/proxy_stop/proxy_status/clear）。 */
    suspend fun reverseApi(action: String, filter: String?, port: Int?): String

    /** 本机 MITM CA 证书（action=status/export/install_system/install_user）。 */
    suspend fun caCertificate(action: String): String
    /** 工具改变了妙招/技能/记忆/经验后通知界面刷新。 */
    fun onToolStateChanged()

    /**
     * MCP 宿主（IDE 内置项目服务 + 用户接入的远端服务）。
     *
     * 接进这里是为了让「MCP 工具」成为 AI 的一条**真实可达**的能力通道：
     * 以前 MCP 只挂在界面面板上，模型既不知道有哪些 Server，也没有任何工具能调它，
     * 于是「AI 网关连上了工作台却用不了完整功能」。默认 null 表示宿主未就绪（早期初始化阶段）。
     */
    val mcpToolHost: com.nebulaforge.core.mcp.McpHost?
}

/**
 * AI 工作台的工具执行主机。
 *
 * 分工原则：**能本地做的绝不上模型**。文件读取、目录列举、grep 全在设备上完成，
 * 只把「需要判断」的部分交给模型；执行类与写入类操作一律先过 [TaskApprovalBroker]。
 */
class AiTaskToolHost(private val runtime: TaskToolRuntime) {

    private val capabilities: AgentCapabilities get() = runtime.capabilities

    /** 明显破坏性的命令直接拒（策略层已有通用规则，这里是最底线兜底）。 */
    private val forbidden = listOf(
        Regex("(?i)\\bmkfs(\\.|\\s)"),
        Regex("(?i)\\breboot\\b|\\bshutdown\\b"),
        Regex("(?i)\\brm\\s+-[a-z]*r[a-z]*f?\\s+/(\\s|$)"),
        Regex("(?i)\\bdd\\s+if=.+of=/dev/"),
        Regex("(?i)\\bflash_erase\\b|\\bformat\\b\\s+/dev")
    )

    /**
     * 哪些工具必须**先有计划**才能执行。
     *
     * 原则：会改变项目/设备/外部状态的一律先立计划；纯只读（读文件、检索、列清单、逆向 inspect）
     * 放行 —— 否则「先看一眼代码」也要走一遍计划流程，反而拖慢任务。
     */
    private fun requiresPlan(call: AiToolCall): Boolean = when (call.name) {
        AgentToolCatalog.WRITE_FILE, AgentToolCatalog.EDIT_FILE, AgentToolCatalog.SEARCH_REPLACE,
        AgentToolCatalog.DELETE_PATH, AgentToolCatalog.MOVE_FILE, AgentToolCatalog.RUN_COMMAND,
        AgentToolCatalog.RUN_SKILL, AgentToolCatalog.BUILD_PROJECT, AgentToolCatalog.RUN_APP,
        AgentToolCatalog.INSTALL_ENV, AgentToolCatalog.CREATE_PROJECT, AgentToolCatalog.CREATE_SKILL,
        AgentToolCatalog.PACK_FILES, AgentToolCatalog.GIT, AgentToolCatalog.HTTP_REQUEST -> true

        AgentToolCatalog.APK_REVERSE -> call.arguments.optString("action").lowercase() in setOf("write", "rebuild", "unpack")
        AgentToolCatalog.API_REVERSE -> call.arguments.optString("action").lowercase() == "proxy_start"
        AgentToolCatalog.CA_CERTIFICATE -> call.arguments.optString("action").lowercase().startsWith("install")
        else -> false
    }

    suspend fun execute(call: AiToolCall, toolset: Set<String>): AiToolResult {
        if (call.name !in toolset) {
            // 网关模型经常**直接写 MCP 工具名**（提示词的 MCP 目录里就是这么列的），
            // 这类名字不在静态 toolset 里，但确实可执行：先当作 MCP 调用试一次，
            // 真不是 MCP 工具再按「未启用」如实回报（不把可执行调用误判成越权）。
            val mcpHit = runtime.mcpToolHost?.let { host ->
                host.serverIds().filter { id ->
                    serverTools(host, id).any { it.name.equals(call.name, ignoreCase = true) }
                }
            }.orEmpty()
            if (mcpHit.size == 1) return mcpDirect(call, mcpHit.first())
            if (mcpHit.size > 1) {
                return fail(call, "工具 ${call.name} 在多个 MCP Server 中都存在（${mcpHit.joinToString("、")}），" +
                    "请改用 mcp_call 并显式指定 server。")
            }
            return fail(call, "工具 ${call.name} 在本次任务中未启用。请提示用户在输入框上方开启对应开关" +
                "（联网 / 允许执行命令 / 允许写文件）后重试。")
        }
        // 硬规则：动手前必须先立计划（用户明确要求「AI 完成任务前必须先制定计划」）。
        // 提示词里已经写了这条约定，这里是代码级兜底 —— 光靠提示词模型会忘。
        var autoPlanned = false
        if (requiresPlan(call) && !runtime.hasActivePlan()) {
            // 顺序很关键：先让工作台按本次操作**自动补一份计划**，而不是直接把调用拒掉。
            // 实测（网关模型）看到「⛔ 未先制定计划」时，多数会停下来反问用户，而不是自己再调一次 propose_plan，
            // 于是任务就卡死在「AI 不愿意立计划」这个假象上。
            autoPlanned = runCatching { runtime.ensureAutoPlan(describeForPlan(call)) }.getOrDefault(false)
            if (!autoPlanned) {
                return fail(
                    call,
                    "⛔ 未先制定计划：按工作台约定，改动项目或设备的操作必须先制定分步计划，" +
                        "系统才允许执行 ${call.name}。请先调用 propose_plan（步骤写清楚：改哪个文件、跑什么命令、" +
                        "怎么验证），然后继续本轮操作。只读工具（read_file / list_files / grep_project 等）不受此限制。"
                )
            }
        }
        val outcome = try {
            when (call.name) {
                AgentToolCatalog.READ_FILE -> readFile(call)
                AgentToolCatalog.LIST_FILES -> listFiles(call)
                AgentToolCatalog.GREP_PROJECT -> grepProject(call)
                AgentToolCatalog.WEB_SEARCH -> webSearch(call)
                AgentToolCatalog.FETCH_PAGE -> fetchPage(call)
                AgentToolCatalog.RUN_COMMAND -> runCommand(call)
                AgentToolCatalog.INSTALL_ENV -> installEnv(call)
                AgentToolCatalog.APP_CONTROL -> appControl(call)
                AgentToolCatalog.GENERATE_IMAGE -> generateImage(call)
                AgentToolCatalog.ASK_WEB_AI -> askWebAi(call)
                AgentToolCatalog.RUN_SKILL -> runSkill(call)
                AgentToolCatalog.WRITE_FILE -> writeFile(call)
                AgentToolCatalog.REMEMBER -> remember(call)
                AgentToolCatalog.CREATE_TRICK -> createTrick(call)
                AgentToolCatalog.CREATE_SKILL -> createSkill(call)
                AgentToolCatalog.PROPOSE_PLAN -> proposePlan(call)
                AgentToolCatalog.CAPTURE_LOGCAT -> captureLogcat(call)
                AgentToolCatalog.READ_SCREEN -> readScreen(call)
                AgentToolCatalog.DEVICE_FILES -> deviceFiles(call)
                AgentToolCatalog.PACK_FILES -> packFiles(call)
                AgentToolCatalog.GENERATE_VIDEO -> generateVideo(call)
                AgentToolCatalog.MCP_CALL -> mcpCall(call)
                AgentToolCatalog.BUILD_PROJECT -> buildProject(call)
                AgentToolCatalog.RUN_APP -> runApp(call)
                AgentToolCatalog.TOOLCHAIN_STATUS -> toolchainStatus(call)
                AgentToolCatalog.CREATE_PROJECT -> createProject(call)
                AgentToolCatalog.EDIT_FILE -> editFile(call)
                AgentToolCatalog.SEARCH_REPLACE -> searchReplace(call)
                AgentToolCatalog.READ_FILES -> readFiles(call)
                AgentToolCatalog.DELETE_PATH -> deletePath(call)
                AgentToolCatalog.MOVE_FILE -> moveFile(call)
                AgentToolCatalog.GIT -> gitOp(call)
                AgentToolCatalog.HTTP_REQUEST -> httpRequest(call)
                AgentToolCatalog.ASK_USER -> askUser(call)
                AgentToolCatalog.LIST_SKILLS -> listSkills(call)
                AgentToolCatalog.DELETE_SKILL -> deleteSkill(call)
                AgentToolCatalog.APK_REVERSE -> apkReverse(call)
                AgentToolCatalog.WEB_REVERSE -> webReverse(call)
                AgentToolCatalog.API_REVERSE -> apiReverse(call)
                AgentToolCatalog.CA_CERTIFICATE -> caCertificate(call)
                AgentToolCatalog.DEVICE_APPS -> deviceApps(call)
                AgentToolCatalog.HOST_CAPABILITY -> hostCapability(call)
                else -> fail(call, "未知工具：${call.name}。当前可用：" + AgentToolCatalog.all.joinToString("、") { it.name })
            }
        } catch (t: Throwable) {
            fail(call, "工具 ${call.name} 执行异常：${t.message ?: t.javaClass.simpleName}")
        }
        if (!autoPlanned) return outcome
        // 让模型和用户都清楚：这份计划是工作台代写的，随时可以用 propose_plan 覆盖成更细的步骤。
        return outcome.copy(
            stateChanged = true,
            output = "（本轮尚未立计划，工作台已按本次操作自动登记执行计划；如需更细的步骤请调用 propose_plan 覆盖。）\n" + outcome.output
        )
    }

    /**
     * 自动兜底计划里显示的「本次操作」描述。
     *
     * 目标不是给模型看，而是给**任务面板**看：让用户一眼知道 AI 正在动哪个文件、跑什么命令，
     * 所以这里优先取参数里最有信息量的字段，而不是工具名。
     */
    private fun describeForPlan(call: AiToolCall): String {
        val a = call.arguments
        val detail = when (call.name) {
            AgentToolCatalog.WRITE_FILE, AgentToolCatalog.EDIT_FILE -> a.optString("path")
            AgentToolCatalog.SEARCH_REPLACE ->
                "把「${a.optString("find").ifBlank { a.optString("old_string") }}」替换为「${a.optString("replace").ifBlank { a.optString("new_string") }}」"
            AgentToolCatalog.READ_FILES -> a.optString("paths")
            AgentToolCatalog.DELETE_PATH -> "删除 ${a.optString("path")}"
            AgentToolCatalog.MOVE_FILE ->
                "${a.optString("from").ifBlank { a.optString("source") }} → ${a.optString("to").ifBlank { a.optString("destination") }}"
            AgentToolCatalog.RUN_COMMAND -> a.optString("command").ifBlank { a.optString("cmd") }
            AgentToolCatalog.BUILD_PROJECT -> "构建 ${a.optString("task").ifBlank { "默认任务" }}"
            AgentToolCatalog.RUN_APP -> "运行应用"
            AgentToolCatalog.INSTALL_ENV -> "安装依赖 ${a.optString("package")}"
            AgentToolCatalog.CREATE_PROJECT -> "新建项目 ${a.optString("name")}"
            AgentToolCatalog.CREATE_SKILL -> "创建技能 ${a.optString("name")}"
            AgentToolCatalog.PACK_FILES -> "打包文件"
            AgentToolCatalog.GIT -> "git ${a.optString("action")}"
            AgentToolCatalog.HTTP_REQUEST -> "${a.optString("method", "GET")} ${a.optString("url")}"
            AgentToolCatalog.APK_REVERSE -> "APK 逆向 ${a.optString("action")}"
            AgentToolCatalog.API_REVERSE -> "API 逆向 ${a.optString("action")}"
            AgentToolCatalog.CA_CERTIFICATE -> "安装本机 MITM CA 证书（${a.optString("action")}）"
            else -> ""
        }
        val label = toolDisplayName(call.name)
        return if (detail.isBlank()) label else "$label：$detail"
    }

    /** 工具的中文短名（任务面板展示用）。 */
    private fun toolDisplayName(name: String): String = when (name) {
        AgentToolCatalog.WRITE_FILE -> "写入文件"
        AgentToolCatalog.EDIT_FILE -> "修改文件"
        AgentToolCatalog.SEARCH_REPLACE -> "批量替换"
        AgentToolCatalog.READ_FILES -> "批量读取"
        AgentToolCatalog.DELETE_PATH -> "删除路径"
        AgentToolCatalog.MOVE_FILE -> "移动/重命名"
        AgentToolCatalog.RUN_COMMAND -> "执行命令"
        AgentToolCatalog.BUILD_PROJECT -> "构建项目"
        AgentToolCatalog.RUN_APP -> "运行项目"
        AgentToolCatalog.INSTALL_ENV -> "安装依赖"
        AgentToolCatalog.CREATE_PROJECT -> "新建项目"
        AgentToolCatalog.CREATE_SKILL -> "创建技能"
        AgentToolCatalog.PACK_FILES -> "打包文件"
        AgentToolCatalog.GIT -> "版本控制"
        AgentToolCatalog.HTTP_REQUEST -> "网络请求"
        AgentToolCatalog.APK_REVERSE -> "APK 逆向"
        AgentToolCatalog.WEB_REVERSE -> "Web 逆向"
        AgentToolCatalog.API_REVERSE -> "API 逆向"
        AgentToolCatalog.CA_CERTIFICATE -> "CA 证书"
        AgentToolCatalog.HOST_CAPABILITY -> "调用宿主能力"
        else -> name
    }

    // ------------------------------------------------------ 宿主能力网关（受授权 + 可审计）

    /**
     * `host_capability`：把 Agent 接到宿主能力网关上。
     *
     * 存在的理由：插件能做的一切（读写工作区文件、列目录、跑终端命令、调模型网关、通知用户）
     * 都走 [com.nebulaforge.app.plugins.HostCapabilityBridge]，如果 AI 绕过它自己摸内部对象，
     * 用户就既看不到授权提示、也查不到审计。这里强制走同一条门。
     */
    private suspend fun hostCapability(call: AiToolCall): AiToolResult {
        when (val action = call.arguments.optString("action", "list").lowercase()) {
            "list", "ls", "catalog" -> {
                val catalog = runCatching { runtime.hostCapabilityCatalog() }
                    .getOrElse { "读取能力目录失败：${it.message ?: it.javaClass.simpleName}" }
                return AiToolResult(
                    call.id, call.name, true,
                    output = "宿主能力目录（写入/执行/模型调用会在调用时请你授权）：\n$catalog"
                )
            }

            "audit", "log" -> {
                val limit = call.arguments.optInt("limit", 20).coerceIn(1, 200)
                val text = runCatching { runtime.hostCapabilityAudit(limit) }
                    .getOrElse { "读取审计失败：${it.message ?: it.javaClass.simpleName}" }
                return AiToolResult(call.id, call.name, true, output = "最近 $limit 条能力调用审计：\n$text")
            }

            "invoke", "call" -> {
                val capability = call.arguments.optString("capability")
                if (capability.isBlank()) return fail(call, "invoke 需要参数 capability（可先 action=list 查看能力 id）")
                val args = call.arguments.optJSONObject("arguments") ?: JSONObject()
                val text = runCatching { runtime.invokeHostCapability(capability, args) }
                    .getOrElse { "调用 $capability 异常：${it.message ?: it.javaClass.simpleName}" }
                val ok = !text.startsWith("失败") && !text.startsWith("已拒绝") && !text.startsWith("调用")
                return AiToolResult(
                    callId = call.id, name = call.name, ok = ok,
                    output = "[$capability] $text"
                )
            }

            else -> return fail(call, "未知 action：$action（可用：list / invoke / audit）")
        }
    }

    // ---------------------------------------------------------------- 只读

    private fun readFile(call: AiToolCall): AiToolResult {
        val path = call.arguments.optString("path")
        if (path.isBlank()) return fail(call, "缺少参数 path")
        val file = resolve(path) ?: return fail(call, "无法解析路径：$path（未打开项目且不是绝对路径）")
        if (!file.exists()) return fail(call, "文件不存在：${file.path}")
        if (file.isDirectory) return fail(call, "${file.path} 是目录，请改用 list_files")
        if (isBinary(file)) return fail(call, "${file.name} 看起来是二进制文件（${file.length()} 字节）。请改用 run_command 分析，例如 `strings|head`、`unzip -l`、`aapt2 dump badging`。")

        val startLine = call.arguments.optInt("start_line", 1).coerceAtLeast(1)
        val maxLines = call.arguments.optInt("max_lines", 400).coerceIn(1, 2000)
        val all = file.readLines()
        val from = (startLine - 1).coerceAtMost(all.size)
        val to = (from + maxLines).coerceAtMost(all.size)
        val numbered = (from until to).joinToString("\n") { index ->
            "${index + 1}\t${all[index]}"
        }
        val header = "${relative(file)} （第 ${from + 1}-$to 行 / 共 ${all.size} 行）"
        return AiToolResult(
            callId = call.id, name = call.name, ok = true,
            output = if (numbered.isBlank()) "$header\n(空文件或行号超出范围)" else "$header\n$numbered"
        )
    }

    private fun listFiles(call: AiToolCall): AiToolResult {
        val rawPath = call.arguments.optString("path").ifBlank { "." }
        val max = call.arguments.optInt("max", 200).coerceIn(10, 800)
        val dir = resolve(rawPath) ?: return fail(call, "无法解析路径：$rawPath（未打开项目且不是绝对路径）")
        if (!dir.isDirectory) return fail(call, "不是目录：${dir.path}")

        val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        if (entries.isEmpty()) return AiToolResult(call.id, call.name, true, output = "${dir.path}\n(空目录)")
        val shown = entries.take(max)
        val body = shown.joinToString("\n") { child ->
            if (child.isDirectory) "  ${child.name}/"
            else "  ${child.name}  ${human(child.length())}"
        }
        val tail = if (entries.size > shown.size) "\n…（共 ${entries.size} 项，已显示前 ${shown.size} 项）" else ""
        return AiToolResult(call.id, call.name, true, output = "${relative(dir)}（${dir.path}）\n$body$tail")
    }

    private fun grepProject(call: AiToolCall): AiToolResult {
        val pattern = call.arguments.optString("pattern")
        if (pattern.isBlank()) return fail(call, "缺少参数 pattern")
        val root = runtime.currentProjectPath()?.let { File(it) } ?: return fail(call, "未打开项目，grep_project 不可用")
        val relativeDir = call.arguments.optString("path").takeIf { it.isNotBlank() }
        val base = relativeDir?.let { File(root, it).takeIf { f -> f.isDirectory } } ?: root
        val max = call.arguments.optInt("max", 80).coerceIn(5, 400)
        val caseSensitive = call.arguments.optBoolean("case_sensitive", false)
        val regex = runCatching { Regex(pattern, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrNull()

        val hits = mutableListOf<String>()
        var scanned = 0
        base.walkTopDown()
            .onEnter { child -> !excluded(child) }
            .filter { it.isFile && it.length() in 1..(2L * 1024 * 1024) && !excluded(it) && !isBinary(it) }
            .forEach { file ->
                if (hits.size >= max) return@forEach
                scanned++
                val lines = runCatching { file.readLines() }.getOrElse { return@forEach }
                lines.forEachIndexed { index, line ->
                    if (hits.size >= max) return@forEach
                    val matched = if (regex != null) regex.containsMatchIn(line)
                    else line.contains(pattern, ignoreCase = !caseSensitive)
                    if (matched) hits.add("${relative(file)}:${index + 1}: ${line.trim().take(240)}")
                }
            }
        return if (hits.isEmpty()) AiToolResult(
            call.id, call.name, true,
            output = "未匹配到「$pattern」：扫描 $scanned 个文本文件（已跳过 build/.gradle/二进制）。可换关键词或指定 path。"
        ) else AiToolResult(
            call.id, call.name, true,
            output = "匹配「$pattern」共 ${hits.size} 处（扫描 $scanned 个文件）:\n" + hits.joinToString("\n")
        )
    }

    // ---------------------------------------------------------------- 联网

    private suspend fun webSearch(call: AiToolCall): AiToolResult {
        val query = call.arguments.optString("query").ifBlank { call.arguments.optString("q") }
        if (query.isBlank()) return fail(call, "缺少参数 query")
        val limit = call.arguments.optInt("limit", 6).coerceIn(1, 15)
        val results = runCatching { capabilities.web.search(query, limit) }.getOrElse {
            return fail(call, "联网检索失败：${it.message ?: it.javaClass.simpleName}（请检查网络，或在设置里关闭代理）")
        }
        if (results.isEmpty()) return AiToolResult(
            call.id, call.name, true,
            output = "检索「$query」没有返回结果。不要编造来源，可换关键词或提示用户手动提供链接。"
        )
        val text = results.joinToString("\n\n") { r ->
            val score = capabilities.credibility(r.url)
            "· ${r.title}\n  ${r.url}\n  ${r.snippet}\n  来源可信度：${"%.2f".format(score)}（${if (score >= 0.7) "官方/文档站，可采信" else "需交叉验证"}）"
        }
        return AiToolResult(call.id, call.name, true, output = "检索「$query」结果（${results.size} 条）：\n$text")
    }

    private suspend fun fetchPage(call: AiToolCall): AiToolResult {
        val url = call.arguments.optString("url")
        if (url.isBlank()) return fail(call, "缺少参数 url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return fail(call, "url 必须以 http(s):// 开头")
        val maxChars = call.arguments.optInt("max_chars", 8000).coerceIn(500, 40_000)
        val evidence = runCatching { capabilities.web.fetchEvidence(url, maxChars) }.getOrElse {
            return fail(call, "抓取失败：${it.message ?: it.javaClass.simpleName}")
        }
        val score = capabilities.credibility(evidence.url)
        return AiToolResult(
            call.id, call.name, true,
            output = "标题：${evidence.title}\nURL：${evidence.url}\n可信度：${"%.2f".format(score)}\n正文：\n${evidence.text}"
        )
    }

    // ---------------------------------------------------------------- 执行

    private suspend fun runCommand(call: AiToolCall): AiToolResult {
        val command = call.arguments.optString("command").ifBlank { call.arguments.optString("cmd") }
        if (command.isBlank()) return fail(call, "缺少参数 command")
        forbidden.firstOrNull { it.containsMatchIn(command) }?.let {
            return fail(call, "命令被工作台安全底线拒绝（匹配 ${it.pattern}）：$command")
        }
        // 参数名兼容：工具说明里是 timeout_sec（秒），历史上实现只读 timeout_ms（毫秒）——
        // 导致 AI 按说明传参时超时设置完全失效，这里两个都认。
        val timeout = if (call.arguments.has("timeout_sec")) {
            (call.arguments.optDouble("timeout_sec", 60.0) * 1000).toLong().coerceIn(1_000, 600_000)
        } else {
            call.arguments.optLong("timeout_ms", 60_000).coerceIn(1_000, 600_000)
        }
        // channel=host/toolchain：走**项目工具链用户态**（proot + JDK/Gradle/AndroidSDK/Flutter/Node）。
        // 真机根因：默认通道是设备 shell（Shizuku/toybox），里面没有构建工具链，
        // AI 写 gradle/flutter 必然 command not found —— 于是「AI 用不了编译环境」。
        val hostChannel = call.arguments.optString("channel").trim().lowercase()
            .let { it == "host" || it == "toolchain" || it == "userland" || it == "proot" }
        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.COMMAND,
                title = if (hostChannel) "AI 请求在项目工具链环境中执行命令" else "AI 请求执行命令",
                detail = if (hostChannel) "【项目工具链用户态】\n$command" else command,
                command = command
            )
        )
        if (!allowed) {
            return fail(call, "用户拒绝执行该命令（或已停止任务）。请不要重试同一条命令，改为给出命令说明让用户手动执行。")
        }
        val text = if (hostChannel) runtime.execInToolchain(command, runtime.currentProjectPath(), timeout)
        else runtime.execOnDevice(command, timeout)
        val exit = Regex("退出码=(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (text.contains("超时")) -1 else 0
        return AiToolResult(call.id, call.name, exit == 0, exitCode = exit, output = text)
    }

    // ---------------------------------------------------------------- 构建（编译 / 运行 / 环境 / 建项目）

    /**
     * 编译打包：**走项目真实构建工具链**，而不是让模型在设备 shell 里手写 gradle。
     *
     * 这是「AI 工作台无法调用编译环境」的修复重点：构建中心一直能编译（运行配置 + UnifiedRunController），
     * 但那套能力只在界面上有按钮，AI 手上没有任何对应工具，于是只能写 `gradle assembleDebug`，
     * 在设备 shell 里必然找不到命令。
     */
    private suspend fun buildProject(call: AiToolCall): AiToolResult {
        val root = runtime.currentProjectPath()
            ?: return fail(call, "当前没有打开的项目。先用 create_project 新建，或让用户在「工作区」里选一个项目。")
        val task = call.arguments.optString("task").trim().takeIf { it.isNotBlank() }
        val module = call.arguments.optString("module").trim().takeIf { it.isNotBlank() }
        val cleanFirst = call.arguments.optBoolean("clean_first", false)
        val timeout = (call.arguments.optDouble("timeout_seconds", 1800.0) * 1000).toLong().coerceIn(30_000, 3_600_000)
        val label = buildString {
            append("[build_project] ").append(File(root).name)
            append(" · task=").append(task ?: "默认构建")
            if (module != null) append(" · module=").append(module)
            if (cleanFirst) append(" · 先 clean")
        }
        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.COMMAND,
                title = "AI 请求编译项目：${File(root).name}",
                detail = label + "\n（走项目构建工具链，首次构建可能需要数分钟）",
                command = "build_project"
            )
        )
        if (!allowed) return fail(call, "用户拒绝了本次构建。请不要重复请求，改为说明需要执行的构建命令。")
        // ★ 取消必须向上传播：runCatching 会把 CancellationException 一并吞掉，
        //   结果是「用户点了停止 / 工具超时」但底层 gradle 仍在设备上跑（真机复现）。
        val text = try {
            runtime.buildProject(root, task, module, cleanFirst, timeout)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            "✗ 构建调用异常：" + (t.message ?: t.javaClass.simpleName)
        }
        return AiToolResult(call.id, call.name, !text.startsWith("✗"), output = label + "\n" + text)
    }

    /** 构建并运行/安装到设备（Android：构建 → 安装 → 拉起）。 */
    private suspend fun runApp(call: AiToolCall): AiToolResult {
        val root = runtime.currentProjectPath()
            ?: return fail(call, "当前没有打开的项目。")
        val timeout = (call.arguments.optDouble("timeout_seconds", 1800.0) * 1000).toLong().coerceIn(30_000, 3_600_000)
        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.COMMAND,
                title = "AI 请求构建并运行：${File(root).name}",
                detail = "会构建、安装到设备并启动应用",
                command = "run_app"
            )
        )
        if (!allowed) return fail(call, "用户拒绝了本次运行。")
        val text = try {
            runtime.runProject(root, timeout)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            "✗ 运行调用异常：" + (t.message ?: t.javaClass.simpleName)
        }
        return AiToolResult(call.id, call.name, !text.startsWith("✗"), output = text)
    }

    /** 构建环境自检：编译失败时先看环境，别急着改代码。 */
    private fun toolchainStatus(call: AiToolCall): AiToolResult {
        val verbose = call.arguments.optBoolean("verbose", false)
        val text = runCatching { runtime.toolchainStatus(verbose) }
            .getOrElse { "✗ 自检异常：" + (it.message ?: it.javaClass.simpleName) }
        return AiToolResult(call.id, call.name, !text.startsWith("✗"), output = text)
    }

    /** 新建项目：复用「新建项目向导」的模板脚手架，建完切换工作区。 */
    private suspend fun createProject(call: AiToolCall): AiToolResult {
        val name = call.arguments.optString("name").trim()
        if (name.isBlank()) return fail(call, "缺少参数 name（项目名）")
        val template = call.arguments.optString("template").trim().takeIf { it.isNotBlank() }
        val packageName = call.arguments.optString("package_name").trim().takeIf { it.isNotBlank() }
        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.COMMAND,
                title = "AI 请求新建项目：$name",
                detail = "模板=" + (template ?: "android-empty") + (packageName?.let { " · 包名=$it" } ?: ""),
                command = "create_project"
            )
        )
        if (!allowed) return fail(call, "用户拒绝了新建项目。")
        val text = runCatching { runtime.createProject(name, template, packageName) }
            .getOrElse { "✗ 新建项目异常：" + (it.message ?: it.javaClass.simpleName) }
        runtime.onToolStateChanged()
        return AiToolResult(call.id, call.name, !text.startsWith("✗"), output = text, stateChanged = true)
    }

    /**
     * 装环境：用户要求「缺环境自己补」。按可用包管理器直接装，最后做存在性校验并把结论回报，
     * 让 AI 知道装好了没，而不是每缺一次就停下来问人。
     */
    private suspend fun installEnv(call: AiToolCall): AiToolResult {
        val name = call.arguments.optString("name").trim()
        if (name.isBlank()) return fail(call, "缺少参数 name（要安装的软件/依赖名）")
        val forced = call.arguments.optString("manager").trim().lowercase()
        val script = "NAME=" + shq(name) + "\n" +
            "MGR=" + shq(forced) + "\n" +
            "run_install() {\n" +
            "  if [ -z \"\$MGR\" ]; then\n" +
            "    if command -v apt-get >/dev/null 2>&1; then MGR=apt\n" +
            "    elif command -v dnf >/dev/null 2>&1; then MGR=dnf\n" +
            "    elif command -v yum >/dev/null 2>&1; then MGR=yum\n" +
            "    elif command -v pkg >/dev/null 2>&1; then MGR=pkg\n" +
            "    fi\n" +
            "  fi\n" +
            "  echo \"[install_env] manager=\${MGR:-none} pkg=\$NAME\"\n" +
            "  case \"\$MGR\" in\n" +
            "    apt) apt-get install -y \"\$NAME\" ;;\n" +
            "    dnf) dnf install -y \"\$NAME\" ;;\n" +
            "    yum) yum install -y \"\$NAME\" ;;\n" +
            "    pkg) pkg install -y \"\$NAME\" ;;\n" +
            "    pip) pip3 install \"\$NAME\" ;;\n" +
            "    npm) npm install -g \"\$NAME\" ;;\n" +
            "    *) if command -v pip3 >/dev/null 2>&1; then pip3 install \"\$NAME\";\n" +
            "       elif command -v npm >/dev/null 2>&1; then npm install -g \"\$NAME\";\n" +
            "       else echo 'no package manager available'; exit 3; fi ;;\n" +
            "  esac 2>&1 | tail -25\n" +
            "}\n" +
            "run_install\n" +
            "if command -v \"\$NAME\" >/dev/null 2>&1; then echo \"[install_env] ok -> \$(command -v \"\$NAME\")\"; " +
            "elif pip3 show \"\$NAME\" >/dev/null 2>&1; then echo \"[install_env] ok (python 包 \$NAME)\"; " +
            "else echo \"[install_env] 未确认\"; fi"
        val text = runtime.execOnDevice(script, 600_000)
        val ok = !text.contains("no package manager available") && !text.contains("未确认")
        return AiToolResult(call.id, call.name, ok, output = "[install_env] " + name + "\n" + text)
    }

    /** 调用 App 自身能力：把「IDE 能做的事」变成 AI 能直接发起的动作。 */
    private suspend fun appControl(call: AiToolCall): AiToolResult {
        val action = call.arguments.optString("action").trim().lowercase()
        val arg = call.arguments.optString("arg").trim()
        return when (action) {
            "", "list" -> AiToolResult(
                call.id, call.name, true,
                output = "【星弦 IDE 可用能力】\n" + listOf(
                    "页面：ai（AI 工作台 / 任务模式）/ ai_plan（AI 工作计划与审查）/ ai_settings（AI 设置）/ " +
                        "ai_learning（智能学习中心）/ mcp / build_center / problems / run_center / database / plugins / ai_build_fix / new_project",
                    "装包：action=install, arg=/sdcard/xxx.apk",
                    "设备：action=logcat | screen | files | pack",
                    "代码：read_file / list_files / grep_project / write_file",
                    "执行：run_command / run_skill / install_env",
                    "内容：generate_video / remember / create_trick / create_skill"
                ).joinToString("\n")
            )
            "open" -> {
                if (arg.isBlank()) return fail(call, "open 需要 arg=路由名（如 mcp / build_center）")
                val text = runtime.execOnDevice(
                    "am start -n com.nebulaforge.app/.MainActivity -e route " + shq(arg) + " 2>&1 | tail -5",
                    30_000
                )
                AiToolResult(call.id, call.name, true, output = "已请求打开：" + arg + "\n" + text)
            }
            "install" -> {
                if (arg.isBlank()) return fail(call, "install 需要 arg=APK 绝对路径")
                val text = runtime.execOnDevice("pm install -r -d " + shq(arg) + " 2>&1 | tail -10", 300_000)
                AiToolResult(call.id, call.name, text.contains("Success"), output = text)
            }
            "logcat" -> captureLogcat(call)
            "screen" -> readScreen(call)
            "files" -> deviceFiles(call)
            "pack" -> packFiles(call)
            else -> fail(call, "不支持的 action：" + action + "（可用 list/open/install/logcat/screen/files/pack）")
        }
    }

    private suspend fun runSkill(call: AiToolCall): AiToolResult {
        val idOrName = call.arguments.optString("id").ifBlank { call.arguments.optString("name") }
        if (idOrName.isBlank()) return fail(call, "缺少参数 id 或 name")
        val skill: AgentSkill = capabilities.skills.get(idOrName)
            ?: return fail(call, "技能不存在：$idOrName。可用技能：" + capabilities.skills.list().joinToString("、") { it.name })
        val entry = capabilities.skills.entryFile(skill.id)
            ?: return fail(call, "技能「${skill.name}」没有入口脚本（可参考技能说明手动执行）")
        val args = call.arguments.optString("args")
        val command = "sh \"${entry.absolutePath}\"" + if (args.isBlank()) "" else " $args"
        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.RUN_SKILL,
                title = "AI 请求运行技能：${skill.name}", detail = command, command = command
            )
        )
        if (!allowed) return fail(call, "用户拒绝运行技能「${skill.name}」")
        val text = runtime.execOnDevice(command, 120_000)
        capabilities.skills.markRun(skill.id)
        runtime.onToolStateChanged()
        val exit = Regex("退出码=(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return AiToolResult(call.id, call.name, exit == 0, exitCode = exit, output = text)
    }

    /** 网页 AI 桥接：把提示词发给已接入的网页 AI（登录态复用持久 WebView），返回它的回答。 */
    private suspend fun askWebAi(call: AiToolCall): AiToolResult {
        val prompt = call.arguments.optString("prompt")
            .ifBlank { call.arguments.optString("text") }.trim()
        if (prompt.isEmpty()) return fail(call, "缺少 prompt：要问网页 AI 什么？")
        val who = call.arguments.optString("name").trim()
        val ai = com.nebulaforge.app.ai.WebAiBridge.resolve(runtime.toolContext, who)
        if (ai == null) {
            return AiToolResult(
                call.id, call.name, false,
                output = "还没有可用的网页 AI。请让用户打开 AI 工作台 →「网桥」面板，" +
                    "点「一键添加常用预设」添加并在内置浏览器里登录一次；之后我就能直接调用它们。"
            )
        }
        val timeout = (call.arguments.optDouble("timeout_seconds", 120.0) * 1000).toLong().coerceIn(20_000, 300_000)
        val answer = com.nebulaforge.app.ai.WebAiBridge.ask(runtime.toolContext, ai, prompt, timeout)
        return AiToolResult(call.id, call.name, !answer.startsWith("✗"), output = answer)
    }

    /** 文生图：在线图片服务优先，失败或未配置回落本地兜底；产物落在 <项目>/nebula-media。 */
    private suspend fun generateImage(call: AiToolCall): AiToolResult {
        val prompt = call.arguments.optString("prompt")
            .ifBlank { call.arguments.optString("text") }.trim()
        if (prompt.isEmpty()) return fail(call, "缺少 prompt：请描述想生成的画面")
        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.COMMAND,
                title = "AI 请求生成图片",
                detail = "描述：" + prompt.take(120) + "\n输出到手机（默认在工程的 nebula-media 目录）",
                command = "generate_image"
            )
        )
        if (!allowed) return fail(call, "用户拒绝了图片生成")
        val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.nebulaforge.app.media.ImageGenerationService.generate(
                    context = runtime.toolContext,
                    projectRoot = runtime.currentProjectPath(),
                    arguments = call.arguments
                )
            }.getOrElse { "生成图片失败：" + (it.message ?: it::class.simpleName.orEmpty()) }
        }
        return AiToolResult(call.id, call.name, !msg.startsWith("生成图片失败"), output = msg)
    }

    /** 生成视频：在线服务商优先，失败或未配置回落本地硬编。 */
    private suspend fun generateVideo(call: AiToolCall): AiToolResult {
        val text = call.arguments.optString("text").trim()
        val images = call.arguments.optString("images").trim()
        if (text.isEmpty() && images.isEmpty()) return fail(call, "需要 text（文生视频）或 images（图生视频）至少一个")
        val provider = call.arguments.optString("provider").trim()
        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.COMMAND,
                title = "AI 请求生成视频" + if (provider.isBlank()) "" else "（服务商：$provider）",
                detail = (if (text.isNotEmpty()) "文案：" + text.take(80) else "图片：" + images.take(120)) +
                    "\n输出到手机（默认在工程的 nebula-media 目录）",
                command = "generate_video"
            )
        )
        if (!allowed) return fail(call, "用户拒绝了视频生成")
        val out = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.nebulaforge.app.media.VideoGenerationService.compose(
                    context = runtime.toolContext,
                    projectRoot = runtime.currentProjectPath(),
                    arguments = call.arguments
                ) { _, _ -> }
            }.getOrElse { "生成失败：${it.message ?: it.javaClass.simpleName}" }
        }
        val ok = out.contains("已生成")
        return AiToolResult(call.id, call.name, ok = ok, exitCode = if (ok) 0 else 1, output = out)
    }

    // ------------------------------------------------- 开发辅助：日志 / 识屏 / 文件 / 打包

    /** shell 单引号安全引用。 */
    // ------------------------------------------------- 增量工具（委托给 AiExtraTools）

    /** 代码编辑 / 文件管理 / Git / HTTP / 协作 / 逆向的实现体。 */
    private val extraTools by lazy { AiExtraTools(runtime) }

    private suspend fun editFile(call: AiToolCall) = extraTools.editFile(call)
    private suspend fun searchReplace(call: AiToolCall) = extraTools.searchReplace(call)
    private fun readFiles(call: AiToolCall) = extraTools.readFiles(call)
    private suspend fun deletePath(call: AiToolCall) = extraTools.deletePath(call)
    private suspend fun moveFile(call: AiToolCall) = extraTools.moveFile(call)
    private suspend fun gitOp(call: AiToolCall) = extraTools.gitOp(call)
    private suspend fun httpRequest(call: AiToolCall) = extraTools.httpRequest(call)
    private suspend fun askUser(call: AiToolCall) = extraTools.askUser(call)
    private fun listSkills(call: AiToolCall) = extraTools.listSkills(call)
    private fun deleteSkill(call: AiToolCall) = extraTools.deleteSkill(call)
    private suspend fun apkReverse(call: AiToolCall) = extraTools.apkReverse(call)
    private suspend fun webReverse(call: AiToolCall) = extraTools.webReverse(call)
    private suspend fun apiReverse(call: AiToolCall) = extraTools.apiReverse(call)
    private suspend fun caCertificate(call: AiToolCall) = extraTools.caCertificate(call)

    private fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 允许被 AI 直接操作的位置（其余一律拒绝，避免误伤系统目录）。 */
    private fun guardRoot(path: String): String? {
        val raw = path.trim()
        if (raw.isEmpty()) return null
        val abs = if (raw.startsWith("/")) raw else File(projectRootOrNull() ?: File("."), raw).absolutePath
        val inFiles = abs.startsWith(runtime.toolContext.filesDir.absolutePath)
        val inSd = abs == "/sdcard" || abs.startsWith("/sdcard/") || abs.startsWith("/storage/emulated/0")
        return if (inSd || inFiles) abs else null
    }

    private fun projectRootOrNull(): File? =
        runtime.currentProjectPath()?.let { runCatching { File(it) }.getOrNull() }

    /** 抓 logcat（快照或实时），并按包名/TAG/级别/关键字过滤。 */
    private suspend fun captureLogcat(call: AiToolCall): AiToolResult {
        val seconds = call.arguments.optInt("seconds", 0).coerceIn(0, 180)
        val maxLines = call.arguments.optInt("lines", 200).coerceIn(10, 3000)
        val pkg = call.arguments.optString("package").trim()
        val tag = call.arguments.optString("tag").trim()
        val filter = call.arguments.optString("filter").trim()
        val level = call.arguments.optString("level").trim().uppercase().let {
            if (it in setOf("V", "D", "I", "W", "E", "F", "S")) it else "V"
        }
        val cmd = buildString {
            append("if command -v logcat >/dev/null 2>&1; then LC=logcat; else LC=/system/bin/logcat; fi; PID=''; ")
            if (pkg.isNotEmpty()) {
                append("PID=`pidof ").append(shq(pkg)).append(" 2>/dev/null || pgrep -f ").append(shq(pkg)).append(" | head -1`; ")
            }
            append("if [ -n \"\$PID\" ]; then ARGS=\"--pid=\$PID\"; else ARGS=''; fi; ")
            if (seconds > 0) {
                append("timeout ").append(seconds).append(" \$LC -v time ").append(shq("*:$level"))
                    .append(" \$ARGS 2>/dev/null | tail -n ").append(maxLines)
            } else {
                append("\$LC -d -v time -t ").append(maxLines).append(" ").append(shq("*:$level")).append(" \$ARGS 2>/dev/null")
            }
        }
        val raw = runtime.execOnDevice(cmd, if (seconds > 0) (seconds + 25) * 1000L else 45_000L)
        var text = raw
        if (tag.isNotEmpty()) text = text.lines().filter { it.contains(tag) }.joinToString("\n")
        if (filter.isNotEmpty()) text = text.lines().filter { it.contains(filter, ignoreCase = true) }.joinToString("\n")
        val kept = text.lines().filter { it.isNotBlank() }.takeLast(maxLines)
        val dir = File(runtime.toolContext.filesDir, "ai-logs").apply { mkdirs() }
        val outFile = File(dir, "logcat-" + System.currentTimeMillis() + ".log")
        runCatching { outFile.writeText(kept.joinToString("\n")) }
        if (kept.isEmpty()) {
            return AiToolResult(
                call.id, call.name, ok = true, exitCode = 0,
                output = "没有匹配到日志（package=$pkg tag=$tag level=$level filter=$filter）。原始返回片段：\n" + raw.take(700)
            )
        }
        val head = if (seconds > 0) "实时抓取 ${seconds}s，命中 ${kept.size} 行" else "快照命中 ${kept.size} 行"
        return AiToolResult(
            call.id, call.name, ok = true, exitCode = 0,
            output = "$head（已落盘：${outFile.absolutePath}）\n" + kept.joinToString("\n")
        )
    }

    /** 识屏：截图 + 界面要素 + 前台窗口/进程状态。 */
    private suspend fun readScreen(call: AiToolCall): AiToolResult {
        val wantUi = call.arguments.optBoolean("ui", true)
        val pkg = call.arguments.optString("package").trim()
        val saveTo = call.arguments.optString("save_to").trim().ifBlank { "/sdcard/Download/nebula" }
        val shot = saveTo + "/screen-" + System.currentTimeMillis() + ".png"
        val cmd = buildString {
            append("mkdir -p ").append(shq(saveTo)).append(" 2>/dev/null; ")
            append("if mkdir -p /data/local/tmp 2>/dev/null && touch /data/local/tmp/.nbw 2>/dev/null; then UI=/data/local/tmp/nb-ui.xml; ")
            append("else UI=\"\${HOME:-/tmp}/.nebulaforge/nb-ui.xml\"; mkdir -p \"\$(dirname \"\$UI\")\" 2>/dev/null; fi; ")
            append("if command -v screencap >/dev/null 2>&1; then SC=screencap; else SC=/system/bin/screencap; fi; ")
            append("\$SC -p ").append(shq(shot)).append(" 2>/dev/null && echo \"SHOT=$shot\" || echo SHOT_FAIL; ")
            append("echo '--- 前台窗口 ---'; dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -4; ")
            append("echo '--- 顶层 Activity ---'; dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity' | head -3; ")
            if (pkg.isNotEmpty()) {
                append("echo '--- 目标应用进程状态 ---'; ")
                append("dumpsys activity processes 2>/dev/null | grep -A6 ").append(shq(pkg)).append(" | grep -E 'adj=|isFrozen|curProcState|pid=' | head -8; ")
                append("echo '--- 是否在跑 ---'; pidof ").append(shq(pkg)).append(" 2>/dev/null || echo '(未运行)'; ")
            }
            if (wantUi) {
                append("echo '--- 界面文本要素 ---'; ")
                append("uiautomator dump \"\$UI\" >/dev/null 2>&1; ")
                append("grep -o 'text=\"[^\"]\\{1,80\\}\"' \"\$UI\" 2>/dev/null | sed 's/^text=//' | sort -u | head -60; ")
                append("echo '--- 可交互控件 id ---'; ")
                append("grep -o 'resource-id=\"[^\"]\\{1,120\\}\"' \"\$UI\" 2>/dev/null | sed 's/^resource-id=//' | sort -u | head -40; ")
            }
        }
        val text = runtime.execOnDevice(cmd, 90_000L)
        val okShot = text.contains("SHOT=") && !text.contains("SHOT_FAIL")
        val tip = if (okShot) "截图已保存：$shot（可用 pack_files 打包或让用户直接查看）"
        else "截图失败（可开启「允许执行命令」或改用识屏文本要素）"
        return AiToolResult(call.id, call.name, ok = true, exitCode = 0, output = tip + "\n" + text)
    }

    /** 手机文件操作（写类先审批）。 */
    private suspend fun deviceFiles(call: AiToolCall): AiToolResult {
        val op = call.arguments.optString("op").trim().lowercase().ifBlank { "list" }
        val pathRaw = call.arguments.optString("path").trim()
        val path = guardRoot(pathRaw)
            ?: return fail(call, "路径不在允许范围内（只允许 /sdcard、/storage/emulated/0、应用私有目录）：$pathRaw")
        val destRaw = call.arguments.optString("dest").trim()
        val pattern = call.arguments.optString("pattern").trim().ifBlank { "*" }
        val maxBytes = call.arguments.optLong("max_bytes", 65_536L).coerceIn(1_024L, 4_000_000L)
        if (op in setOf("write", "copy", "move", "mkdir", "delete")) {
            val allowed = runtime.approvalBroker.awaitApproval(
                TaskApprovalRequest(
                    id = call.id, callId = call.id, kind = TaskApprovalKind.COMMAND,
                    title = "AI 请求操作手机文件：$op",
                    detail = path + if (destRaw.isEmpty()) "" else " → $destRaw",
                    command = "$op $path $destRaw"
                )
            )
            if (!allowed) return fail(call, "用户拒绝了文件操作（$op $path）。请不要重复发起，改为说明建议让用户手动处理。")
        }
        val dest = if (destRaw.isEmpty()) "" else (guardRoot(destRaw) ?: return fail(call, "目标路径越界：$destRaw"))
        val cmd = when (op) {
            "list" -> "ls -la " + shq(path)
            "stat" -> "ls -ld " + shq(path) + "; du -sh " + shq(path) + " 2>/dev/null; stat -c '%s bytes mode=%a mtime=%y' " + shq(path) + " 2>/dev/null"
            "read" -> "head -c $maxBytes " + shq(path) + " | tr -d '\\000'"
            "write" -> {
                val content = call.arguments.optString("content")
                if (content.length > 400_000) return fail(call, "内容过大（${content.length} 字符），请分批写入")
                val b64 = AndroidBase64.encodeToString(content.toByteArray(), AndroidBase64.NO_WRAP)
                "(printf '%s' " + shq(b64) + " | base64 -d > " + shq(path) + ") 2>/dev/null || " +
                    "(printf '%s' " + shq(b64) + " | base64 --decode > " + shq(path) + "); wc -c < " + shq(path)
            }
            "mkdir" -> "mkdir -p " + shq(path) + " && echo MKDIR_OK"
            "copy" -> if (dest.isEmpty()) return fail(call, "copy 需要 dest") else "cp -f " + shq(path) + " " + shq(dest) + " && ls -l " + shq(dest)
            "move" -> if (dest.isEmpty()) return fail(call, "move 需要 dest") else "mv -f " + shq(path) + " " + shq(dest) + " && ls -l " + shq(dest)
            "delete" -> "rm -rf " + shq(path) + " && echo DELETED"
            "search" -> "find " + shq(path) + " -name " + shq(pattern) + " 2>/dev/null | head -100"
            else -> return fail(call, "未知 op：$op（可用 list/read/write/copy/move/mkdir/delete/stat/search）")
        }
        val text = runtime.execOnDevice(cmd, 60_000L)
        return AiToolResult(call.id, call.name, ok = !text.contains("退出码=1"), exitCode = 0, output = text)
    }

    /**
     * 手机后台软件：列正在运行的软件与状态 / 查前台应用 / 按包名持续监听 / 看监听事件与日志 / 列已安装应用。
     *
     * 与 [readScreen] 的分工：识屏是「看一眼此刻屏幕上有什么」，本工具是**进程级**
     * 的「谁在前台、谁退到后台、谁被系统缓存待回收、目标软件有没有被杀」——排查
     * 「App 一退后台就被杀 / 闪退 / 定时任务没跑」这类问题靠的是后者。
     */
    private fun deviceApps(call: AiToolCall): AiToolResult {
        val action = call.arguments.optString("action").trim().lowercase().ifBlank { "running" }
        val limit = call.arguments.optInt("limit", 60).coerceIn(5, 300)
        val exec: (String, Long) -> String = { cmd, ms -> runtime.execOnDevice(cmd, ms) }
        val ctx = runtime.toolContext
        return when (action) {
            "running", "list", "snapshot" -> {
                val includeSystem = call.arguments.optBoolean("include_system", false)
                val snap = com.nebulaforge.app.device.AppWatchCenter.scan(
                    exec = exec,
                    includeSystem = includeSystem,
                    installed = appInstalledPackages()
                )
                if (snap.processes.isEmpty()) {
                    return fail(
                        call,
                        "读不到进程信息。常见原因：设置里未打开「允许 AI 使用终端通道」（走 Shizuku 才能读 dumpsys）。" +
                            "原始返回：\n" + snap.source.take(400)
                    )
                }
                AiToolResult(
                    call.id, call.name, ok = true, exitCode = 0,
                    output = com.nebulaforge.app.device.AppWatchCenter.render(snap, limit)
                )
            }
            "foreground", "focus" -> {
                val snap = com.nebulaforge.app.device.AppWatchCenter.scan(exec = exec, installed = appInstalledPackages())
                val fg = snap.foregroundPackage
                    ?: return fail(call, "读不到前台应用（原始返回：${snap.source.take(200)}）")
                val app = snap.apps.firstOrNull { it.packageName == fg }
                AiToolResult(
                    call.id, call.name, ok = true, exitCode = 0,
                    output = "当前前台应用：" + (app?.line() ?: fg)
                )
            }
            "watch", "start" -> {
                val pkg = call.arguments.optString("package").trim().ifBlank {
                    com.nebulaforge.app.device.AppWatchCenter.targetFor(ctx, runtime.currentProjectPath())
                }
                if (pkg.isBlank()) {
                    return fail(call, "watch 需要 package（目标包名）。可先调 device_apps action=installed 找到它。")
                }
                val msg = com.nebulaforge.app.device.AppWatchCenter.start(
                    ctx, exec, pkg, runtime.currentProjectPath()
                )
                AiToolResult(call.id, call.name, ok = true, exitCode = 0, output = msg)
            }
            "status" -> AiToolResult(
                call.id, call.name, ok = true, exitCode = 0,
                output = com.nebulaforge.app.device.AppWatchCenter.statusText()
            )
            "events" -> AiToolResult(
                call.id, call.name, ok = true, exitCode = 0,
                output = com.nebulaforge.app.device.AppWatchCenter.eventsText(limit)
            )
            "logs" -> AiToolResult(
                call.id, call.name, ok = true, exitCode = 0,
                output = com.nebulaforge.app.device.AppWatchCenter.logsText(limit)
            )
            "stop" -> AiToolResult(
                call.id, call.name, ok = true, exitCode = 0,
                output = com.nebulaforge.app.device.AppWatchCenter.stop()
            )
            "installed" -> {
                val needle = call.arguments.optString("filter").trim().lowercase()
                val pm = ctx.packageManager
                val rows = runCatching {
                    pm.getInstalledApplications(0).mapNotNull { info ->
                        val pkg = info.packageName
                        val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(pkg)
                        if (needle.isEmpty() || pkg.lowercase().contains(needle) || label.lowercase().contains(needle)) "$label（$pkg）" else null
                    }.sorted()
                }.getOrElse { return fail(call, "读取已安装应用失败：${it.message}") }
                AiToolResult(
                    call.id, call.name, ok = true, exitCode = 0,
                    output = "已安装应用 ${rows.size} 个：\n" + rows.take(limit).joinToString("\n").ifBlank { "（无匹配）" }
                )
            }
            else -> fail(call, "未知 action：$action（可用 running/foreground/watch/status/events/logs/stop/installed）")
        }
    }

    /** 已安装包名集合（兜底通道 `ps` 用它过滤掉 media.extractor 这类原生进程）。 */
    private fun appInstalledPackages(): Set<String> = runCatching {
        runtime.toolContext.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** 打包源码/产物到手机本地。 */
    private suspend fun packFiles(call: AiToolCall): AiToolResult {
        val srcRaw = call.arguments.optString("src").trim().ifBlank { projectRootOrNull()?.absolutePath ?: "" }
        if (srcRaw.isBlank()) return fail(call, "没有可打包的目录（未打开工程时请显式给 src）")
        val src = guardRoot(srcRaw) ?: return fail(call, "源目录不在允许范围内：$srcRaw")
        val kind = call.arguments.optString("kind").trim().lowercase().ifBlank { "source" }
        val name = File(src).name.ifBlank { "nebula" }
        val stamp = System.currentTimeMillis()
        val destRaw = call.arguments.optString("dest").trim()
        var dest = if (destRaw.isNotEmpty()) (guardRoot(destRaw) ?: return fail(call, "输出路径越界：$destRaw"))
        else "/sdcard/Download/nebula/$name-$kind-$stamp.zip"
        if (!dest.endsWith(".zip") && !dest.endsWith(".tar.gz")) dest += ".zip"
        val destTar = if (dest.endsWith(".zip")) dest.removeSuffix(".zip") + ".tar.gz" else dest

        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.COMMAND,
                title = "AI 请求打包到手机（$kind）", detail = "$src → $dest", command = "pack $src $dest"
            )
        )
        if (!allowed) return fail(call, "用户拒绝了打包操作")

        val zipEx = when (kind) {
            "source" -> "-x '*/build/*' -x '*/.git/*' -x '*/.dart_tool/*' -x '*/.idea/*' -x '*/node_modules/*'"
            "build" -> "-x '*/.git/*' -x '*/.dart_tool/*'"
            else -> ""
        }
        val tarEx = when (kind) {
            "source" -> "--exclude=./build --exclude=./.git --exclude=./.dart_tool"
            "build" -> "--exclude=./.git --exclude=./.dart_tool"
            else -> ""
        }
        val parent = File(dest).parent ?: "/sdcard/Download/nebula"
        val cmd = buildString {
            append("mkdir -p ").append(shq(parent)).append(" 2>/dev/null; OUT=''; ")
            append("if command -v zip >/dev/null 2>&1; then ")
            append("( cd ").append(shq(src)).append(" && zip -qr ").append(shq(dest)).append(" . $zipEx ) >/dev/null 2>&1 && OUT=").append(shq(dest)).append("; fi; ")
            append("if [ -z \"\$OUT\" ] && command -v tar >/dev/null 2>&1; then ")
            append("( cd ").append(shq(src)).append(" && tar -czf ").append(shq(destTar)).append(" . ").append(tarEx).append(" ) >/dev/null 2>&1 && OUT=").append(shq(destTar)).append("; fi; ")
            append("if [ -z \"\$OUT\" ]; then echo NO_PACKER; exit 3; fi; ")
            append("echo \"OUT=\$OUT\"; ls -l \"\$OUT\" 2>/dev/null; ")
            append("echo -n 'sha256='; (sha256sum \"\$OUT\" 2>/dev/null || toybox sha256sum \"\$OUT\" 2>/dev/null || md5sum \"\$OUT\" 2>/dev/null) | awk '{print \$1}'; ")
            append("echo -n 'bytes='; wc -c < \"\$OUT\" 2>/dev/null")
        }
        val text = runtime.execOnDevice(cmd, 300_000L)
        if (text.contains("NO_PACKER")) return fail(call, "设备上没有 zip/tar 可用，无法打包")
        return AiToolResult(call.id, call.name, ok = true, exitCode = 0, output = "打包完成（kind=$kind，源：$src）\n$text")
    }

    // ---------------------------------------------------------------- 写入

    private suspend fun writeFile(call: AiToolCall): AiToolResult {
        val rawPath = call.arguments.optString("path")
        if (rawPath.isBlank()) return fail(call, "缺少参数 path")
        val content = call.arguments.optString("content")
        val root = runtime.currentProjectPath()?.let { File(it) }
            ?: return fail(call, "未打开项目，write_file 不可用")
        val file = if (File(rawPath).isAbsolute) File(rawPath) else File(root, rawPath)
        // 只允许写项目内文件：避免 AI 顺手改到应用私有目录或系统路径。
        val normalizedRoot = root.canonicalPath
        val normalizedTarget = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            return fail(call, "只允许写入当前项目目录内的文件：$rawPath")
        }
        val original = if (file.isFile) runCatching { file.readText() }.getOrNull() else null
        val patch = AiPendingPatch(toolCallId = call.id, path = file.absolutePath, content = content, original = original)

        val allowed = runtime.approvalBroker.awaitApproval(
            TaskApprovalRequest(
                id = call.id, callId = call.id, kind = TaskApprovalKind.WRITE_FILE,
                title = if (patch.exists) "AI 请求修改文件" else "AI 请求新建文件",
                detail = patch.summary, patch = patch
            )
        )
        if (!allowed) return fail(call, "用户拒绝了该文件改动（未写入磁盘）：${patch.summary}。请按用户意见调整方案或先说明理由。")

        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
        }.getOrElse { return fail(call, "写入失败：${it.message ?: it.javaClass.simpleName}") }
        val verified = runCatching { file.readText() == content }.getOrDefault(false)
        return AiToolResult(
            call.id, call.name, true,
            output = buildString {
                append(if (patch.exists) "已应用修改：" else "已创建文件：").append(relative(file))
                append("\n").append(patch.summary)
                append("\n大小=").append(human(file.length()))
                if (!verified) append("\n⚠ 回读校验不一致，请人工确认")
            }
        )
    }

    // ---------------------------------------------------------------- 记忆 / 妙招 / 技能

    private fun remember(call: AiToolCall): AiToolResult {
        val project = runtime.currentProjectPath() ?: return fail(call, "未打开项目，记忆只能写入项目维度")
        val key = call.arguments.optString("key").ifBlank { call.arguments.optString("title") }
        val content = call.arguments.optString("content").ifBlank { call.arguments.optString("value") }
        if (key.isBlank() || content.isBlank()) return fail(call, "需要 key 与 content 两个参数")
        capabilities.upsertMemory(project, key, content, source = "agent")
        runtime.onToolStateChanged()
        return AiToolResult(call.id, call.name, true, stateChanged = true, output = "已写入项目记忆：$key\n（后续轮次会自动召回）")
    }

    private fun createTrick(call: AiToolCall): AiToolResult {
        val name = call.arguments.optString("name")
        if (name.isBlank()) return fail(call, "缺少参数 name")
        val summary = call.arguments.optString("summary")
        val patch = call.arguments.optString("prompt_patch").ifBlank { call.arguments.optString("promptPatch") }
        if (patch.isBlank()) return fail(call, "缺少参数 prompt_patch（妙招的核心是提示词补丁）")
        val tools = call.arguments.optJSONArray("tools")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        val trick: AgentTrick = capabilities.tricks.upsert(
            AgentTrick(name = name, summary = summary, promptPatch = patch, tools = tools, author = "agent", enabled = true)
        )
        runtime.onToolStateChanged()
        return AiToolResult(
            call.id, call.name, true, stateChanged = true,
            output = "妙招「${trick.name}」已创建并启用（id=${trick.id}）。下一轮起会作为提示词补丁生效。"
        )
    }

    private fun createSkill(call: AiToolCall): AiToolResult {
        val name = call.arguments.optString("name")
        if (name.isBlank()) return fail(call, "缺少参数 name")
        val description = call.arguments.optString("description")
        val body = call.arguments.optString("body")
        val entry = call.arguments.optString("entry")
        val script = call.arguments.optString("script")
        val tools = call.arguments.optJSONArray("tools")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        if (body.isBlank() && script.isBlank()) return fail(call, "技能需要 body（说明）或 script（入口脚本）")
        val skill = capabilities.skills.create(
            name = name, description = description, body = body, entry = entry, script = script,
            tools = tools, author = "agent"
        )
        runtime.onToolStateChanged()
        val entryNote = if (skill.hasEntry) "，入口=${skill.entry}，可用 run_skill 调用" else "，无入口脚本"
        return AiToolResult(
            call.id, call.name, true, stateChanged = true,
            output = "技能「${skill.name}」已创建（id=${skill.id}$entryNote）。"
        )
    }

    private fun proposePlan(call: AiToolCall): AiToolResult {
        val summary = call.arguments.optString("summary").ifBlank { call.arguments.optString("title") }
        val steps = call.arguments.optJSONArray("steps")
        val text = AiToolProtocol.renderPlan(summary, steps)
        // ★ 关键修复（2.12.80）：以前这里**只**把计划渲染成文本返回，从不写进计划状态，
        //   于是闸门 hasActivePlan() 永远为 false —— 模型再乖也每次被拒，最终「立了计划也执行不了」。
        // 两种写法都要接：数组（steps:[{title…}]）与多行字符串（steps:"1. 改 A\n2. 跑 B"）。
        val titles = AiToolProtocol.planStepTitles(steps).ifEmpty {
            AiToolProtocol.planStepTitlesFromText(call.arguments.optString("steps"))
        }
        val registered = runCatching { runtime.registerPlan(summary, titles) }.getOrDefault(0)
        if (registered <= 0) {
            return fail(
                call,
                "计划未登记：steps 里没解析出可用步骤。请用 steps 数组重新提交，每步给 title（必填）、" +
                    "description（写清做什么），action 可选（BUILD / EXECUTE_COMMAND / ANALYZE_PROJECT / " +
                    "VERIFY_RESULT / SEARCH_WEB 等）。"
            )
        }
        return AiToolResult(
            callId = call.id,
            name = call.name,
            ok = true,
            output = "计划已登记（$registered 步），现在开始执行：\n$text\n" +
                "补充：只读工具可直接调用；写文件/执行命令的每一步仍会按你的设置请求确认。",
            stateChanged = true
        )
    }

    // ---------------------------------------------------------------- MCP

    /**
     * 调用 MCP 工具（IDE 内置项目 MCP Server + 用户接入的远端 MCP Server）。
     *
     * 参数容错：模型常把 server/tool 写错大小写、或直接塞 `server.tool`，
     * 这里做归一化匹配；对不上时不只回「失败」，而是把**真实可用清单**回灌，
     * 让它在下一轮自己纠正（网关模型比原生 function-calling 更容易写错字段名）。
     */
    private suspend fun mcpCall(call: AiToolCall): AiToolResult {
        val host = runtime.mcpToolHost
            ?: return fail(call, "MCP 宿主尚未就绪（应用仍在初始化），请稍后重试。")
        val args = call.arguments
        var server = args.optString("server")
            .ifBlank { args.optString("server_id").ifBlank { args.optString("id") } }.trim()
        var tool = args.optString("tool")
            .ifBlank { args.optString("tool_name").ifBlank { args.optString("name") } }.trim()
        // 模型很爱把 "server.tool" 整个塞进 server 字段。
        if (server.contains('.') && tool.isBlank()) {
            val index = server.indexOf('.')
            tool = server.substring(index + 1)
            server = server.substring(0, index)
        }
        if (server.isBlank() && tool.isNotBlank()) {
            // 只给了工具名：在全部 Server 里找唯一匹配，找不到就让模型自己选。
            val hit = host.serverIds().filter { id ->
                serverTools(host, id).any { it.name.equals(tool, ignoreCase = true) }
            }
            if (hit.size == 1) server = hit.first()
        }
        val ids = host.serverIds()
        if (ids.isEmpty()) return fail(call, "当前没有已接入的 MCP Server。可在「MCP 工具」面板接入后重试。")
        val resolvedServer = ids.firstOrNull { it.equals(server, ignoreCase = true) }
            ?: ids.firstOrNull { it.substringAfterLast(':').equals(server, ignoreCase = true) }
            ?: return fail(call, "未知 MCP Server「$server」。可用 Server：${ids.joinToString("、")}")

        val defined = serverTools(host, resolvedServer)
        val resolvedTool = defined.firstOrNull { it.name.equals(tool, ignoreCase = true) }
            ?: defined.firstOrNull { it.name.substringAfterLast('/').equals(tool, ignoreCase = true) }
            ?: if (defined.isEmpty() && tool.isNotBlank()) null // 未探测到清单：直接按名字试
            else return fail(
                call,
                "MCP Server「$resolvedServer」下没有工具「$tool」。该 Server 的工具：" +
                    defined.joinToString("、") { it.name }.ifBlank { "（清单为空，未探测成功）" }
            )
        val toolName = resolvedTool?.name ?: tool
        if (toolName.isBlank()) return fail(call, "缺少参数 tool（该 Server 的工具：${defined.joinToString("、") { it.name }}）")

        val payload = runCatching {
            args.optJSONObject("arguments") ?: args.optJSONObject("args")
            ?: (args.optString("arguments").takeIf { it.isNotBlank() }?.let { JSONObject(it) })
            ?: JSONObject()
        }.getOrElse { return fail(call, "arguments 不是合法 JSON 对象：${it.message}") }

        val internal = host.snapshotOf(resolvedServer)?.internal == true
        val resultJson = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (internal) host.callInternalTool(resolvedServer, toolName, payload)
                else host.remoteCall(
                    resolvedServer, "tools/call",
                    JSONObject().put("name", toolName).put("arguments", payload)
                )
            }
        } catch (t: Throwable) {
            return fail(call, "MCP 调用失败（$resolvedServer / $toolName）：${t.message ?: t.javaClass.simpleName}")
        }

        val isError = resultJson.optBoolean("isError") ||
            resultJson.optJSONObject("result")?.optBoolean("isError") == true
        val text = extractMcpText(resultJson).take(20000)
        return AiToolResult(
            call.id, call.name, !isError,
            output = "MCP $resolvedServer / $toolName：\n$text"
        )
    }

    /** 直名 MCP 调用：模型直接写 MCP 工具名（不是 mcp_call）时的落地路径。 */
    private suspend fun mcpDirect(call: AiToolCall, serverId: String): AiToolResult {
        val args = JSONObject()
            .put("server", serverId)
            .put("tool", call.name)
            .put("arguments", call.arguments)
        return mcpCall(AiToolCall(call.id, AgentToolCatalog.MCP_CALL, args, call.rawJson))
    }

    private fun serverTools(
        host: com.nebulaforge.core.mcp.McpHost,
        id: String
    ): List<com.nebulaforge.core.mcp.McpToolDefinition> =
        if (host.snapshotOf(id)?.internal == true) host.internalTools(id)
        else host.remoteToolDefinitions(id)
    /** MCP 结果体：优先取 content[].text（模型看着最省 token），取不到再退回原始 JSON。 */
    private fun extractMcpText(json: JSONObject): String {
        val body = json.optJSONObject("result") ?: json
        val content = body.optJSONArray("content")
        if (content != null && content.length() > 0) {
            val parts = (0 until content.length()).mapNotNull { index ->
                val item = content.optJSONObject(index) ?: return@mapNotNull null
                item.optString("text").ifBlank { item.optString("data") }.ifBlank { null }
            }
            if (parts.isNotEmpty()) return parts.joinToString("\n")
        }
        val structured = body.optJSONObject("structuredContent")
        if (structured != null) return structured.toString(2)
        return json.toString(2)
    }

    // ---------------------------------------------------------------- 工具函数

    private fun fail(call: AiToolCall, message: String) =
        AiToolResult(callId = call.id, name = call.name, ok = false, exitCode = 1, output = "✗ $message")

    private fun resolve(path: String): File? {
        val file = File(path)
        if (file.isAbsolute) return file
        val root = runtime.currentProjectPath() ?: return null
        return File(root, path)
    }

    private fun relative(file: File): String {
        val root = runtime.currentProjectPath() ?: return file.absolutePath
        return runCatching { file.absolutePath.removePrefix(File(root).absolutePath + "/") }.getOrDefault(file.name)
    }

    private fun excluded(file: File): Boolean {
        val name = file.name
        if (file.isDirectory) return name in setOf("build", ".gradle", ".git", ".kotlin", ".cxx", ".idea", "node_modules", "dist", "vendor")
        return name.endsWith(".apk") || name.endsWith(".jar") || name.endsWith(".aar") ||
            name.endsWith(".dex") || name.endsWith(".so") || name.endsWith(".class")
    }

    /** 前 8KB 出现 NUL 视为二进制（对 UTF-8 文本误判概率极低）。 */
    private fun isBinary(file: File): Boolean {
        if (file.length() == 0L) return false
        val ext = file.extension.lowercase()
        if (ext in AiAttachmentTextExtractor.TEXT_EXT) return false
        return runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                val read = input.read(buffer)
                read > 0 && (0 until read).any { buffer[it] == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 供 UI 一键把选中的文件塞进输入框（本质是构造只读工具调用）。 */
    fun describeArguments(json: JSONObject): String = json.toString()
}
