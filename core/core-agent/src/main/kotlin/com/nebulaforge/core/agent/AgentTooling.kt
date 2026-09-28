package com.nebulaforge.core.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 工具协议（与具体模型厂商无关）。
 *
 * 设计取舍：
 *  1. **不用厂商原生 function-calling**：DeepSeek/Anthropic/Gemini 的 schema 差异大，且 OpenAI 兼容
 *     网关普遍不完好支持；改用「文本协议 + 严格解析」，任何模型都能用，也更易调试。
 *  2. 调用格式固定为围栏代码块，避免和普通代码块混淆：
 *
 *     ```tool
 *     {"name":"read_file","arguments":{"path":"app/src/main/AndroidManifest.xml"}}
 *     ```
 *
 *  3. 解析失败**不抛异常**：模型偶尔会写坏 JSON，此时把错误文本回灌给模型让它自我纠正，
 *     比整个任务失败更划算。
 */
data class AgentToolParam(val name: String, val type: String, val description: String, val required: Boolean = false)

/** 工具声明：既用于渲染提示词里的可用工具清单，也用于 UI 展示。 */
data class AgentToolSpec(
    val name: String,
    val title: String,
    val description: String,
    val params: List<AgentToolParam> = emptyList()
) {
    fun signature(): String = buildString {
        append(name).append('(')
        append(params.joinToString(", ") { "${it.name}:${it.type}${if (it.required) "" else "?"}" })
        append(')')
    }

    fun promptLine(): String = "- ${signature()}：$description"

    fun exampleJson(): String {
        val args = params.filter { it.required }.joinToString(",") { "\"${it.name}\":\"…\"" }
        return "{\"name\":\"$name\",\"arguments\":{$args}}"
    }
}

/** 一次工具调用请求。 */
data class AgentToolCall(val name: String, val arguments: JSONObject = JSONObject(), val raw: String = "") {
    fun stringArg(key: String): String = arguments.optString(key).trim()

    fun intArg(key: String, default: Int): Int = if (arguments.has(key)) arguments.optInt(key, default) else default

    /** 兼容 `"tools":"a,b"` 与 `"tools":["a","b"]` 两种写法。 */
    fun listArg(key: String): List<String> {
        arguments.optJSONArray(key)?.let { a -> return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }
        return arguments.optString(key).split(',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** 一次工具执行结果。[output] 会直接进入模型上下文，因此失败时必须写明原因而不是空串。 */
data class AgentToolResult(
    val tool: String,
    val ok: Boolean,
    val output: String,
    val display: String = "",
    val exitCode: Int? = null
) {
    /** 回灌给模型的文本：明确标注工具名与成功/失败，避免模型臆造结果。 */
    fun toModelText(maxChars: Int = 12_000): String = buildString {
        append("【工具结果】").append(tool).append(if (ok) " 成功" else " 失败")
        display.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        append('\n')
        append(output.take(maxChars))
        if (output.length > maxChars) append("\n…（输出已截断，共 ${output.length} 字符）")
    }

    companion object {
        fun fail(tool: String, reason: String) = AgentToolResult(tool, false, reason, "参数/环境错误")
    }
}

/** 工具执行宿主：由 app 层实现（能拿到项目路径、终端、网络、妙招/技能/记忆存储）。 */
interface AgentToolHost {
    val specs: List<AgentToolSpec>
    suspend fun execute(call: AgentToolCall): AgentToolResult
}

/** 工具清单 + 文本协议解析。 */
object AgentToolCatalog {

    const val READ_FILE = "read_file"
    const val LIST_FILES = "list_files"
    const val GREP_PROJECT = "grep_project"
    const val WEB_SEARCH = "web_search"
    const val FETCH_PAGE = "fetch_page"
    const val REMEMBER = "remember"
    const val CREATE_TRICK = "create_trick"
    const val CREATE_SKILL = "create_skill"
    const val RUN_SKILL = "run_skill"
    const val RUN_COMMAND = "run_command"
    const val WRITE_FILE = "write_file"
    const val PROPOSE_PLAN = "propose_plan"
    const val CAPTURE_LOGCAT = "capture_logcat"
    const val READ_SCREEN = "read_screen"
    const val DEVICE_FILES = "device_files"
    const val PACK_FILES = "pack_files"
    const val GENERATE_VIDEO = "generate_video"

    /** 文生图：按描述生成图片（在线图片端点优先，未配置则本地兜底海报）。 */
    const val GENERATE_IMAGE = "generate_image"

    /** 网页 AI 桥接：把问题转给已接入的网页 AI（ChatGPT/Claude/Gemini/DeepSeek/豆包/通义…）。 */
    const val ASK_WEB_AI = "ask_web_ai"

    /** 装环境/依赖：缺什么自己装，不用回头问用户。 */
    const val INSTALL_ENV = "install_env"

    /** 调用星弦 IDE 自身已有能力（导航 / 装包 / 日志 / 识屏 / 文件 / 打包）。 */
    const val APP_CONTROL = "app_control"

    /**
     * 编译/打包当前项目（**走项目真实工具链**，不是在设备 shell 里手写命令）。
     *
     * 为什么必须有这个工具：AI 的 `run_command` 走的是**设备 shell 通道**
     * （Shizuku / 内嵌 toybox），那个通道里没有 JDK、Gradle、Android SDK、Flutter、Node ——
     * 工具链只在 IDE 的「用户态环境」(proot) 里。于是用户让 AI「编译一下」「打个包」时，
     * 模型只能写 `gradle assembleDebug`，而这条命令在设备 shell 里必然 `command not found`，
     * 表现为「AI 工作台无法调用编译环境」。这个工具把 IDE 构建中心的真实入口
     * （UnifiedRunController：Android=Gradle、Flutter=flutter build、Web=npm run build、
     * 其余技术栈=各自 BuildSystem 的首个任务）暴露给 AI。
     */
    const val BUILD_PROJECT = "build_project"

    /** 构建并运行/安装到设备（Android 会构建→安装→拉起；其余技术栈走各自的运行配置）。 */
    const val RUN_APP = "run_app"

    /** 构建环境自检：查 JDK / Gradle / Android SDK / Flutter / Node 与 shell 通道是否就绪。 */
    const val TOOLCHAIN_STATUS = "toolchain_status"

    /** 新建项目（复用「新建项目向导」的模板脚手架，建完自动切换工作区）。 */
    const val CREATE_PROJECT = "create_project"
    const val EDIT_FILE = "edit_file"
    const val SEARCH_REPLACE = "search_replace"
    const val READ_FILES = "read_files"
    const val DELETE_PATH = "delete_path"
    const val MOVE_FILE = "move_file"
    const val GIT = "git"
    const val HTTP_REQUEST = "http_request"
    const val ASK_USER = "ask_user"
    const val LIST_SKILLS = "list_skills"
    const val DELETE_SKILL = "delete_skill"
    const val APK_REVERSE = "apk_reverse"
    const val WEB_REVERSE = "web_reverse"
    const val API_REVERSE = "api_reverse"
    const val CA_CERTIFICATE = "ca_certificate"

    /**
     * 手机后台软件：列后台软件与状态（前台/可见/服务/缓存/常驻）、查前台应用，
     * 以及按包名**持续监听**目标软件（启动/转后台/被杀/崩溃 + 目标日志）。
     */
    const val DEVICE_APPS = "device_apps"

    /**
     * 调用 MCP 工具（IDE 内置的项目 MCP Server + 用户接入的远端 MCP Server）。
     *
     * 为什么要有这个工具：MCP 工具是**动态**的（数量、名字随 Server 变化），没法写进静态目录常量，
     * 于是以前 MCP 能力对 AI 是完全不可见的 —— 用户在「MCP 工具」面板里看到 Server 就绪，
     * 但 AI 网关/任务台一次也调不到，这就是「连上工作台却用不了完整功能」的根因之一。
     * 现在统一走一个透传入口：`{"name":"mcp_call","arguments":{"server":"...","tool":"...","arguments":{...}}}`。
     */
    const val MCP_CALL = "mcp_call"

    /**
     * 受授权、可审计地调用宿主能力（插件 / AI / 外部网关共用同一条通道）。
     *
     * 为什么不能让 AI 自己摸内部对象：插件命令、文件读写、终端执行、模型网关调用过去各自找入口，
     * 于是「谁在什么时候用 AI 改了哪个文件」无从追溯，也给不了用户一次明确的授权机会。
     * 这个工具把 Agent 接到 [com.nebulaforge.app.plugins.HostCapabilityBridge] 上：
     *  - `action=list`   看能力目录（含参数契约）与当前授权状态；
     *  - `action=invoke` 真正调用（写入/执行/模型调用会弹窗请用户授权，答「始终允许」后落盘记住）；
     *  - `action=audit`  读最近审计（谁调的、准没准、成没成、参数摘要）。
     */
    const val HOST_CAPABILITY = "host_capability"

    /** 只读工具：任何模式下都允许。 */
    val READ_ONLY: Set<String> = setOf(READ_FILE, LIST_FILES, GREP_PROJECT)

    /** 联网工具：需要用户开启「联网」开关。 */
    val ONLINE: Set<String> = setOf(WEB_SEARCH, FETCH_PAGE)

    /** 写类工具：需要用户开启「允许写文件」。 */
    val WRITING: Set<String> = setOf(WRITE_FILE, CREATE_TRICK, CREATE_SKILL)

    /** 执行类工具：需要用户开启「允许执行命令」。 */
    val EXECUTING: Set<String> = setOf(RUN_COMMAND, RUN_SKILL)

    /**
     * 开发辅助（软件开发时「看」和「动手」的能力）：抓实时日志 / 识屏看状况 / 操作用户手机文件 / 打包到手机。
     * 全部经设备命令通道执行（无 Root 时走 Shizuku，否则走内嵌用户态），写类操作逐条弹审批。
     */
    val DEVTOOLS: Set<String> = setOf(
        CAPTURE_LOGCAT, READ_SCREEN, DEVICE_FILES, PACK_FILES, GENERATE_VIDEO, DEVICE_APPS
    )

    /**
     * 自主补全（**默认可用**的「自己想办法」能力）：缺技能自建、缺环境自装、要动 IDE 自身。
     * 用户要求「AI 自己把活干完」，所以这些不受任何开关门槛限制。
     */
    val AUTONOMY: Set<String> = setOf(
        INSTALL_ENV, APP_CONTROL, CREATE_SKILL, CREATE_TRICK, REMEMBER, PACK_FILES, CAPTURE_LOGCAT,
        GENERATE_VIDEO, GENERATE_IMAGE, ASK_WEB_AI, MCP_CALL
    )

    /**
     * 构建能力（**默认可用**）：编译打包 / 运行安装 / 工具链自检 / 新建项目。
     *
     * 默认可见不等于默认放行 —— 与 DEVTOOLS 同策略：这四类操作真正执行前都要过
     * [com.nebulaforge.core.agent] 审批（TaskApprovalBroker），所以少一层开关不会削弱安全性，
     * 但能让「AI 自己把项目编译出来」这件事**不需要用户先去配置任何东西**。
     */
    val BUILDING: Set<String> = setOf(BUILD_PROJECT, RUN_APP, TOOLCHAIN_STATUS, CREATE_PROJECT)

    /**
     * 宿主能力网关（**默认可用**）：列能力 / 调用 / 读审计。
     *
     * 默认可见是安全的 —— 真正落地的每一步都要过 [HostCapabilityBridge] 的授权判定，
     * 写入、执行、模型调用**默认都要用户当场点头**，且无一例外写审计。
     */
    val CAPABILITY: Set<String> = setOf(HOST_CAPABILITY)

    /**
     * MCP 能力（默认可用）：MCP Server 暴露的工具名是动态的，只有拿到实时目录才有意义，
     * 所以它不受「联网 / 执行命令 / 写文件」开关限制 —— 真正的门槛在 Server 自身的工具实现里。
     */
    val MCP: Set<String> = setOf(MCP_CALL)

    /**
     * 代码编辑能力（**默认可用**）：精确替换 / 跨文件批量替换 / 批量读 / 删除 / 移动重命名。
     *
     * 补齐的是历史短板：以前 AI 只能 write_file 整份覆盖，改一个大文件要回传全文，
     * 既慢又容易截断；也不能删文件、不能重命名、不能跨文件重构。
     * 写类操作与 write_file 同一条安全通道（TaskApprovalBroker，默认自动放行、可关）。
     */
    val CODING: Set<String> = setOf(EDIT_FILE, SEARCH_REPLACE, READ_FILES, DELETE_PATH, MOVE_FILE)

    /** 版本控制（**默认可用**）：git 状态/差异/提交/分支，在项目目录里执行。 */
    val VCS: Set<String> = setOf(GIT)

    /** 网络请求（**默认可用**）：任意 HTTP 调用，用于调 API、验证接口、取 JSON 数据。 */
    val NET: Set<String> = setOf(HTTP_REQUEST)

    /**
     * 逆向能力（**默认可用**）：APK / Web / API 三类逆向。
     *
     * 以前逆向只存在于「逆向工作台」界面和计划执行器的固定步骤里，AI 工作台调不到。
     * 现在把它作为工具交给 AI：能自己看清单件、反编译、查 smali、抓接口、起 MITM 抓真机流量。
     */
    val REVERSE: Set<String> = setOf(APK_REVERSE, WEB_REVERSE, API_REVERSE, CA_CERTIFICATE)

    /** 协作与技能管理（**默认可用**）：向用户提问（挂起等待）、列出/删除技能与妙招。 */
    val COLLAB: Set<String> = setOf(ASK_USER, LIST_SKILLS, DELETE_SKILL)

    val all: List<AgentToolSpec> = listOf(
        AgentToolSpec(
            READ_FILE, "读取文件",
            "读取项目内（或绝对路径）的文本文件内容，自动跳过二进制并限制行数。",
            listOf(
                AgentToolParam("path", "string", "文件路径，相对路径按项目根解析", true),
                AgentToolParam("start_line", "number", "起始行（1 起）"),
                AgentToolParam("max_lines", "number", "最多读取行数，默认 400")
            )
        ),
        AgentToolSpec(
            LIST_FILES, "列目录",
            "列出目录条目（目录在前，附体积），用于快速了解项目结构。",
            listOf(
                AgentToolParam("path", "string", "目录路径，缺省为项目根"),
                AgentToolParam("recursive", "boolean", "是否递归（最多 3 层）")
            )
        ),
        AgentToolSpec(
            GREP_PROJECT, "检索代码",
            "在项目内按关键字/正则检索文本，返回 文件:行号:内容，用于定位实现。",
            listOf(
                AgentToolParam("pattern", "string", "关键字或正则", true),
                AgentToolParam("path", "string", "限定目录/文件，缺省项目根"),
                AgentToolParam("max_results", "number", "最多命中数，默认 60")
            )
        ),
        AgentToolSpec(
            WEB_SEARCH, "联网搜索",
            "联网检索最新资料，返回标题/URL/摘要与来源可信度，用于时效性问题。",
            listOf(
                AgentToolParam("query", "string", "检索词", true),
                AgentToolParam("max_results", "number", "结果数，默认 6")
            )
        ),
        AgentToolSpec(
            FETCH_PAGE, "读取网页",
            "抓取指定 URL 的正文并摘取与问题相关的片段（用于核实事实）。",
            listOf(AgentToolParam("url", "string", "网页地址", true))
        ),
        AgentToolSpec(
            REMEMBER, "写入记忆",
            "把本轮得到的关键事实（kind=memory）或可复用解法（kind=experience）沉淀到项目记忆/经验库，后续任务会自动召回。",
            listOf(
                AgentToolParam("kind", "string", "memory 或 experience", true),
                AgentToolParam("key", "string", "标题/键，简短可检索", true),
                AgentToolParam("content", "string", "内容：事实结论或问题→解法→结果", true)
            )
        ),
        AgentToolSpec(
            CREATE_TRICK, "创建妙招",
            "把一套可复用的提示词套路存成「妙招」，用户可一键启用/编辑，后续对话自动生效。",
            listOf(
                AgentToolParam("name", "string", "妙招名", true),
                AgentToolParam("summary", "string", "一句话说明用途"),
                AgentToolParam("prompt", "string", "追加到系统提示词的指令正文", true),
                AgentToolParam("tools", "string", "建议启用的工具名，逗号分隔")
            )
        ),
        AgentToolSpec(
            CREATE_SKILL, "创建技能",
            "创建一个可执行「技能」：写入说明正文与入口脚本（/bin/sh），之后可用 run_skill 运行。",
            listOf(
                AgentToolParam("name", "string", "技能名", true),
                AgentToolParam("description", "string", "用途说明", true),
                AgentToolParam("body", "string", "技能正文：输入/输出/步骤说明"),
                AgentToolParam("entry", "string", "入口脚本文件名，如 run.sh"),
                AgentToolParam("script", "string", "入口脚本内容（/bin/sh，接收参数 $1…）"),
                AgentToolParam("tools", "string", "建议工具名，逗号分隔")
            )
        ),
        AgentToolSpec(
            RUN_SKILL, "运行技能",
            "运行已创建技能的入口脚本（需要用户开启「允许执行命令」）。",
            listOf(
                AgentToolParam("id", "string", "技能 id 或名称", true),
                AgentToolParam("args", "string", "传给脚本的参数")
            )
        ),
        AgentToolSpec(
            RUN_COMMAND, "执行命令",
            "执行一条 shell 命令并返回输出（需要用户开启「允许执行命令」；破坏性命令会被拒绝）。" +
                "**注意通道**：默认 channel=device 走设备 shell（Shizuku / 内嵌 toybox），那里没有 JDK / Gradle / " +
                "Android SDK / Flutter / Node；要在带构建工具链的环境里跑命令（如 gradle --version、flutter doctor），" +
                "必须显式传 channel=\"host\"。单纯编译/打包项目优先用 build_project。",
            listOf(
                AgentToolParam("command", "string", "完整命令", true),
                AgentToolParam("channel", "string", "执行通道：device（默认，设备 shell）或 host（项目工具链用户态，带 JDK/Gradle/SDK/Flutter/Node）"),
                AgentToolParam("timeout_sec", "number", "超时秒数，默认 60，上限 600")
            )
        ),
        AgentToolSpec(
            INSTALL_ENV, "装环境/依赖",
            "把缺失的环境或依赖直接装上（自动挑选 apt / yum / dnf / pkg / pip / npm），并回报是否装成功。" +
                "缺什么自己装，不要回头问用户怎么配置。",
            listOf(
                AgentToolParam("name", "string", "要安装的软件或依赖名，如 ffmpeg / pillow / requests", true),
                AgentToolParam("manager", "string", "可选：强制指定包管理器（apt/yum/dnf/pkg/pip/npm）")
            )
        ),
        AgentToolSpec(
            APP_CONTROL, "调用 App 已有能力",
            "直接调用星弦 IDE 自身能力：list=列出全部能力；open=打开功能页（arg=路由，" +
                "如 ai/ai_plan/ai_settings/ai_learning/mcp/build_center/problems/plugins/new_project）；" +
                "install=安装 APK（arg=APK 路径）；logcat/screen/files/pack=取设备信息与产物。",
            listOf(
                AgentToolParam("action", "string", "list / open / install / logcat / screen / files / pack", true),
                AgentToolParam("arg", "string", "open 时为路由名；install 时为 APK 路径")
            )
        ),
        AgentToolSpec(
            WRITE_FILE, "写入文件",
            "创建/覆盖项目文件（需要用户开启「允许写文件」并逐条确认 Diff）。路径相对项目根解析，禁止越出项目目录。",
            listOf(
                AgentToolParam("path", "string", "目标路径", true),
                AgentToolParam("content", "string", "完整文件内容", true)
            )
        ),
        AgentToolSpec(
            ASK_WEB_AI, "问网页 AI",
            "把问题交给已接入的网页 AI 并取回它的回答（AI 聚合网关）。用户在工作台「网桥」面板里接入并登录过的站才能用；" +
                "不填 name（或写「首选」）就用用户设的**首选站点**，没设首选时按「首选→上次成功→其它」自动挑。" +
                "适合：换一个模型交叉验证、用某站特有的能力、对比不同 AI 的结论。",
            listOf(
                AgentToolParam("prompt", "string", "要问的内容（可包含必要上下文）", true),
                AgentToolParam("name", "string", "指定站点（名字或 id）；留空或写「首选」= 用用户设的首选站点"),
                AgentToolParam("timeout_seconds", "number", "等待回答的秒数，默认 120")
            )
        ),
        AgentToolSpec(
            GENERATE_IMAGE, "生成图片",
            "按文字描述生成一张图片并保存到手机。配好在线图片服务（OpenAI 兼容 /images/generations）时出真实画面；" +
                "没配或调用失败会本地兜底生成「描述海报」，并在结果里如实说明是兜底产物。",
            listOf(
                AgentToolParam("prompt", "string", "画面描述（越具体越好：主体/风格/构图/颜色/光照）", true),
                AgentToolParam("size", "string", "尺寸，默认 1024x1024"),
                AgentToolParam("output", "string", "输出路径，默认 <项目>/nebula-media/image-<时间戳>.png")
            )
        ),
        AgentToolSpec(
            GENERATE_VIDEO, "生成视频",
            "生成短视频（MP4）并保存到手机：给 text=文生视频（按行切字幕卡），给 images=图生视频（运镜+交叉淡入淡出）。" +
                "配置了在线视频服务时优先走在线（可用 provider 指定用哪个服务商），失败或未配置自动回落本地系统硬编。",
            listOf(
                AgentToolParam("text", "string", "文案，每行一张字幕卡"),
                AgentToolParam("images", "string", "图片路径，逗号分隔（图生视频）"),
                AgentToolParam("provider", "string", "指定在线服务商名字；不填用当前选中的"),
                AgentToolParam("output", "string", "输出路径，默认 <项目>/nebula-media/video-<时间戳>.mp4"),
                AgentToolParam("seconds_per_image", "number", "每张停留秒数，默认 3"),
                AgentToolParam("width", "number", "宽度，默认 1280"),
                AgentToolParam("height", "number", "高度，默认 720"),
                AgentToolParam("fps", "number", "帧率，默认 24"),
                AgentToolParam("title", "string", "角标文字（可选）")
            )
        ),
        AgentToolSpec(
            CAPTURE_LOGCAT, "抓取实时日志",
            "抓 logcat：默认取最近 N 行快照，seconds>0 时实时抓取该时长；可按包名（自动解析 pid）、TAG、级别、关键字过滤，" +
                "并同时落盘到应用私有目录。用于查「App 跑起来报什么错、崩在哪、现在在刷什么日志」。",
            listOf(
                AgentToolParam("package", "string", "包名，如 com.nebulaforge.app；给了就只看该进程"),
                AgentToolParam("tag", "string", "TAG 包含过滤"),
                AgentToolParam("level", "string", "最低级别 V/D/I/W/E，默认 V"),
                AgentToolParam("filter", "string", "关键字包含过滤"),
                AgentToolParam("lines", "number", "最多返回行数，默认 200，上限 3000"),
                AgentToolParam("seconds", "number", "实时抓取秒数；0=只取快照")
            )
        ),
        AgentToolSpec(
            READ_SCREEN, "识屏看软件状况",
            "读取屏幕：截图存到手机（默认 Download/nebula），用 uiautomator 导出界面文本要素与可点控件 id，" +
                "并附带前台窗口/顶层 Activity/目标应用进程状态（adj、是否被冻结）。用于判断「现在卡在哪一屏、界面有什么」。",
            listOf(
                AgentToolParam("ui", "boolean", "是否导出界面元素，默认 true"),
                AgentToolParam("package", "string", "顺带查该应用的进程状态"),
                AgentToolParam("save_to", "string", "截图目录，默认 /sdcard/Download/nebula")
            )
        ),
        AgentToolSpec(
            DEVICE_FILES, "操作用户手机文件",
            "对手机文件做实际操作：list/read/write/copy/move/mkdir/delete/stat/search。" +
                "允许范围：/sdcard（含 /storage/emulated/0）与应用私有目录；写类操作需用户确认。",
            listOf(
                AgentToolParam("op", "string", "list|read|write|copy|move|mkdir|delete|stat|search", true),
                AgentToolParam("path", "string", "目标路径", true),
                AgentToolParam("dest", "string", "copy/move 目标路径"),
                AgentToolParam("content", "string", "write 的文本内容"),
                AgentToolParam("pattern", "string", "search 的文件名模式，如 *.apk"),
                AgentToolParam("max_bytes", "number", "read 最大字节，默认 65536")
            )
        ),
        AgentToolSpec(
            PACK_FILES, "打包到手机本地",
            "把源码或产物打包压缩到手机（默认 /sdcard/Download/nebula/*.zip），返回体积与 sha256。" +
                "kind=source 排除 build/.git/.dart_tool，kind=build 只打包产物，kind=all 全量。",
            listOf(
                AgentToolParam("src", "string", "源目录，默认当前工程"),
                AgentToolParam("dest", "string", "输出文件路径（.zip），给了就用它"),
                AgentToolParam("kind", "string", "source|build|all，默认 source"),
                AgentToolParam("exclude", "string", "额外排除模式，逗号分隔")
            )
        ),
        AgentToolSpec(
            PROPOSE_PLAN, "拟定计划",
            "动手前把任务拆成有序步骤，**登记为工作台可见的执行计划**（任务面板实时显示进度）。" +
                "调用后即可开始执行；写文件/执行命令的每一步仍会按用户设置请求确认。",
            listOf(
                AgentToolParam(
                    "steps", "array",
                    "步骤数组，每项 {title, description, action}；action 可选 BUILD / EXECUTE_COMMAND / ANALYZE_PROJECT / VERIFY_RESULT 等",
                    true
                )
            )
        ),
        AgentToolSpec(
            HOST_CAPABILITY, "调用宿主能力（受授权+可审计）",
            "调用星弦 IDE 自身能力网关：list=列能力目录与授权状态；invoke=调用某项能力" +
                "（capability 写能力 id，arguments 传参数对象；写入/执行/模型调用会请用户授权）；audit=读最近审计。" +
                "插件能做的一切（读写工作区文件、列目录、跑终端命令、调模型网关、通知）都在这条通道上，" +
                "所以用它改代码/跑命令是**可追溯**的。",
            listOf(
                AgentToolParam("action", "string", "list / invoke / audit", true),
                AgentToolParam("capability", "string", "invoke 时的能力 id，如 workspace.writeFile、terminal.run、model.chat"),
                AgentToolParam("arguments", "object", "invoke 时的参数对象，按能力参数契约填"),
                AgentToolParam("limit", "number", "audit 时返回条数，默认 20")
            )
        ),
        AgentToolSpec(
            MCP_CALL, "调用 MCP 工具",
            "调用 MCP Server 暴露的工具（IDE 内置的项目 MCP Server，或用户接入的远端 MCP Server）。" +
                "server 填系统提示词里「MCP 目录」列出的 server id，tool 填该 server 的工具名，" +
                "arguments 按该工具的 inputSchema 传参。",
            listOf(
                AgentToolParam("server", "string", "MCP Server id（见提示词中的 MCP 目录）", true),
                AgentToolParam("tool", "string", "该 Server 下的工具名", true),
                AgentToolParam("arguments", "object", "工具参数对象，按该工具 inputSchema 填")
            )
        ),
        AgentToolSpec(
            BUILD_PROJECT, "编译打包项目",
            "用项目自己的构建工具链编译/打包当前项目（Android=Gradle、Flutter=flutter build、Node=npm run build、" +
                "其余技术栈=各自构建系统的任务）。**要编译、要出 APK、要「打包一下」时用这个工具**。" +
                "task 写自然说法即可（apk / debug / release / aab / clean），工具会按当前技术栈自动归一化成合法命令" +
                "（例如 Flutter 项目的 task=apk 会执行 `flutter build apk`，Gradle 项目的 task=release 会执行 " +
                "`assembleRelease`）—— **不要再因为担心命令写错而绕去 run_command 手写**。" +
                "返回构建结论、关键日志与产物路径。",
            listOf(
                AgentToolParam("task", "string", "构建类型/任务：留空=默认构建（Android debug 包）。" +
                    "**平台无关写法会被自动归一化**：apk/debug/build → 出 debug APK；release → release 包；" +
                    "aab/appbundle → Flutter appbundle；clean → 清理；也可直接填真实任务名" +
                    "（assembleDebug / assembleRelease / flutter build apk --debug / npm run build）。"),
                AgentToolParam("module", "string", "Gradle 模块，如 :app；留空构建整个项目"),
                AgentToolParam("timeout_seconds", "number", "超时秒数，默认 900，上限 3600"),
                AgentToolParam("clean_first", "boolean", "是否先执行 clean，默认 false")
            )
        ),
        AgentToolSpec(
            RUN_APP, "构建并运行到设备",
            "构建并运行/安装当前项目到设备：Android 走「构建 → 安装 → 拉起应用」，" +
                "Flutter/Web 等走各自的运行配置。用于「跑起来看看」「装到手机上」这类要求。",
            listOf(
                AgentToolParam("timeout_seconds", "number", "超时秒数，默认 900，上限 3600")
            )
        ),
        AgentToolSpec(
            TOOLCHAIN_STATUS, "查构建环境",
            "自检构建环境：JDK / Gradle / Android SDK / Flutter / Node / go 等是否可用，shell 通道是什么。" +
                "编译报「找不到命令」「JDK 未安装」时先用它确认环境，再决定是补环境（install_env）还是找用户确认。",
            listOf(
                AgentToolParam("verbose", "boolean", "是否附带关键环境变量（JAVA_HOME/ANDROID_HOME/PATH），默认 false")
            )
        ),
        AgentToolSpec(
            CREATE_PROJECT, "新建项目",
            "用内置模板新建一个项目（等同用户在「新建项目向导」里选模板创建），建完自动切换当前工作区，" +
                "之后可以直接 write_file 写代码、build_project 编译。可用模板见 toolchain_status 或工具返回的清单。",
            listOf(
                AgentToolParam("name", "string", "项目名（同时作为目录名）", true),
                AgentToolParam("template", "string", "模板 id，如 android-empty-compose / flutter-empty / go-gin；留空用 android-empty"),
                AgentToolParam("package_name", "string", "包名/命名空间（Android/Kotlin 类模板需要），如 com.example.demo")
            )
        ),
        AgentToolSpec(
            EDIT_FILE, "精确改文件",
            "在文件里做**精确替换**：只传要改的片段（old_string → new_string），不必回传整份文件。" +
                "改大文件、改几行逻辑时**优先用它**，比 write_file 更快也更不容易出错。" +
                "old_string 必须在文件里唯一出现（否则报错，可改大上下文或 replace_all=true 全替换）。" +
                "写入前会走与 write_file 相同的 Diff 确认。",
            listOf(
                AgentToolParam("path", "string", "文件路径，相对路径按项目根解析", true),
                AgentToolParam("old_string", "string", "要被替换掉的原片段（含必要上下文以保证唯一）", true),
                AgentToolParam("new_string", "string", "替换成的新内容；传空字符串=删除该片段", true),
                AgentToolParam("replace_all", "boolean", "是否替换全部出现，默认 false（要求唯一）")
            )
        ),
        AgentToolSpec(
            SEARCH_REPLACE, "批量替换",
            "在整个项目里按关键字或正则批量替换（改包名、统一 API 名、跨文件重构）。" +
                "先用 dry_run=true 预览命中文件与条数，确认后再正式替换。",
            listOf(
                AgentToolParam("find", "string", "要查找的内容（关键字或正则）", true),
                AgentToolParam("replace", "string", "替换成什么（传空=删除）", true),
                AgentToolParam("path", "string", "限定目录/文件，缺省项目根"),
                AgentToolParam("regex", "boolean", "find 是否按正则解释，默认 false"),
                AgentToolParam("dry_run", "boolean", "只预览不写盘，默认 true"),
                AgentToolParam("max_files", "number", "最多改多少个文件，默认 40")
            )
        ),
        AgentToolSpec(
            READ_FILES, "批量读文件",
            "一次读取多个文件（比逐个 read_file 省好几轮）。paths 支持逗号分隔的路径，或 glob（如 **/*.kt、app/**/*.xml）。",
            listOf(
                AgentToolParam("paths", "string", "路径列表（逗号/换行分隔）或 glob 模式", true),
                AgentToolParam("max_lines_per_file", "number", "每个文件最多读多少行，默认 300")
            )
        ),
        AgentToolSpec(
            DELETE_PATH, "删除文件/目录",
            "删除项目内文件或目录（目录要 recursive=true）。执行前需要确认。",
            listOf(
                AgentToolParam("path", "string", "要删除的路径（项目内）", true),
                AgentToolParam("recursive", "boolean", "目录是否递归删除，默认 false")
            )
        ),
        AgentToolSpec(
            MOVE_FILE, "移动/重命名",
            "移动或重命名文件/目录（等价 mv），也可用于在 /sdcard 与应用私有目录之间搬文件。",
            listOf(
                AgentToolParam("source", "string", "源路径", true),
                AgentToolParam("destination", "string", "目标路径（目录会自动拼接文件名）", true)
            )
        ),
        AgentToolSpec(
            GIT, "Git 操作",
            "对当前项目执行 git：op=status/diff/log/add/commit/checkout/branch/stash/init/remote/pull/push，" +
                "args 传附加参数（如 commit 的 \"-m 修复登录\"、log 的 \"-n 20\"）。" +
                "未初始化仓库时可先 op=init。返回原始输出，便于 AI 判断与下一步动作。",
            listOf(
                AgentToolParam("op", "string", "子命令，如 status/diff/log/add/commit/checkout/branch/init", true),
                AgentToolParam("args", "string", "附加参数，如 \"-m 提交说明\"")
            )
        ),
        AgentToolSpec(
            HTTP_REQUEST, "发 HTTP 请求",
            "发一条真实 HTTP 请求（调 API、验证接口、抓 JSON）：支持自定义方法、请求头、请求体，" +
                "返回状态码、响应头与正文（超长自动截断）。用于接口联调与数据获取。",
            listOf(
                AgentToolParam("url", "string", "完整 URL", true),
                AgentToolParam("method", "string", "GET/POST/PUT/PATCH/DELETE，默认 GET"),
                AgentToolParam("headers", "string", "请求头，形如 \"Content-Type: application/json; Authorization: Bearer ...\"（分号或换行分隔）"),
                AgentToolParam("body", "string", "请求体（POST/PUT/PATCH 用）")
            )
        ),
        AgentToolSpec(
            ASK_USER, "问用户",
            "向用户提问并**挂起等待回答**，拿到回答后继续干活。给了 options 用户可以直接点选。" +
                "只在确实缺关键信息（目标平台、包名、方案取舍、要改哪个文件）时用；不要用它确认自己能判断的小事。",
            listOf(
                AgentToolParam("question", "string", "要问的问题", true),
                AgentToolParam("options", "string", "可选项，逗号分隔（可留空，用户自由输入）")
            )
        ),
        AgentToolSpec(
            LIST_SKILLS, "列出技能/妙招",
            "列出已有技能与妙招（id/名称/用途/建议工具）。动手前先看有没有可复用的，避免重复造。",
            listOf(AgentToolParam("kind", "string", "skill | trick | all，默认 all"))
        ),
        AgentToolSpec(
            DELETE_SKILL, "删除技能/妙招",
            "删除已有技能或妙招（kind=skill|trick，id 或名称）。",
            listOf(
                AgentToolParam("kind", "string", "skill 或 trick", true),
                AgentToolParam("id", "string", "技能/妙招的 id 或名称", true)
            )
        ),
        AgentToolSpec(
            APK_REVERSE, "APK 逆向",
            "APK 逆向分析（自研引擎，无需联网）。action：" +
                "inspect=读结构（包名/版本/权限/组件/DEX/签名证书/内置 URL）；" +
                "unpack=反编译资源与 smali 并生成 Java 源码（产物在当前项目的 .nebula-reverse 工作区，首次较慢）；" +
                "files=列出工作区里可直接修改的文件；read=读取其中某个文件；write=修改某个文件（走 Diff 确认）；" +
                "rebuild=回编并签名出新的 APK；evidence=返回最近一次分析与抓包证据摘要；" +
                "toolchain=自检逆向工具链（JDK/apktool/JADX/apksigner 是否就绪、缺什么）。" +
                "path 传 APK 路径或反编译工作区目录。",
            listOf(
                AgentToolParam("action", "string", "inspect | unpack | files | read | write | rebuild | evidence | toolchain", true),
                AgentToolParam("path", "string", "APK 路径或工作区目录（留空用最近一次分析的 APK）"),
                AgentToolParam("file", "string", "read/write 时的工作区文件路径"),
                AgentToolParam("content", "string", "write 的新内容"),
                AgentToolParam("out", "string", "rebuild 输出 APK 路径（留空自动命名）")
            )
        ),
        AgentToolSpec(
            WEB_REVERSE, "Web 逆向",
            "Web 逆向：查看内置浏览器捕获的网络记录（文档 / 接口 / SSE / WebSocket / 静态资源），" +
                "可让 AI 分析页面结构、接口与参数。" +
                "action=records（列最近记录）/analyze（AI 分析）/to_api（把抓到的接口并入 API 端点库，供导出 OpenAPI）/clear（清空）。",
            listOf(
                AgentToolParam("action", "string", "records | analyze | to_api | clear", true),
                AgentToolParam("url", "string", "按 URL 关键字过滤（可选）"),
                AgentToolParam("limit", "number", "最多返回多少条记录，默认 40")
            )
        ),
        AgentToolSpec(
            API_REVERSE, "API 逆向",
            "API 逆向：管理抓到的真实接口。action=" +
                "endpoints（列接口：方法/URL/参数/出现次数）；analyze（AI 归纳接口与调用关系）；" +
                "openapi（导出 OpenAPI 描述，可复制给其它工具）；" +
                "proxy_start / proxy_stop / proxy_status（启停内置 MITM 代理，用于让真机 App 走本机抓包）；clear（清空）。",
            listOf(
                AgentToolParam("action", "string", "endpoints | analyze | openapi | proxy_start | proxy_stop | proxy_status | clear", true),
                AgentToolParam("filter", "string", "按 URL 关键字过滤（可选）"),
                AgentToolParam("port", "number", "proxy_start 的端口，默认用既有配置")
            )
        ),
        AgentToolSpec(
            CA_CERTIFICATE, "本机 CA 证书",
            "本机 MITM CA 证书管理 —— 抓 HTTPS 的前置条件。" +
                "Android 7+ 起「用户证书」默认不被任何 App（含 WebView）信任，只有写入**系统**证书库才通吃，" +
                "所以「代理已启动却抓不到 HTTPS」几乎都是这一步没做。" +
                "action=status（查证书状态/文件路径/subject_hash）；export（导出到公共目录）；" +
                "install_system（写入系统证书库，需 Root 或已授权的 Shizuku）；" +
                "install_user（打开系统「安装证书」界面，仅本应用生效）。",
            listOf(
                AgentToolParam("action", "string", "status | export | install_system | install_user", true)
            )
        ),
        AgentToolSpec(
            DEVICE_APPS, "后台软件与监听",
            "看手机后台软件与状态，并可盯住目标软件（开发调试的核心能力 —— 自家 App 有没有被杀、转后台、" +
                "闪退，一眼可见）。action=" +
                "running（列正在运行的软件：前台/可见/服务/缓存/常驻 + 前台应用；include_system=true 连系统应用一起）；" +
                "foreground（只看当前前台应用）；" +
                "watch（按 package 开始持续监听：状态变化、被系统杀掉、崩溃与目标 logcat 都会记成事件）；" +
                "status（监听状态 + 当前状态）；events（最近状态变化事件）；logs（目标增量日志）；" +
                "stop（停止监听）；installed（列已安装应用，用于挑监听目标）。" +
                "只读部分不需要「允许执行命令」以外的任何授权；watch 只是后台轮询，可用 stop 随时结束。",
            listOf(
                AgentToolParam("action", "string", "running | foreground | watch | status | events | logs | stop | installed", true),
                AgentToolParam("package", "string", "watch 的目标包名（如 com.example.app）"),
                AgentToolParam("include_system", "boolean", "running 时是否连系统应用一起返回，默认 false"),
                AgentToolParam("filter", "string", "installed 时按包名/应用名关键字过滤"),
                AgentToolParam("limit", "number", "最多返回条目/事件数，默认 60")
            )
        )
    )

    fun spec(name: String): AgentToolSpec? = all.firstOrNull { it.name == name }

    fun specsFor(allowed: Set<String>): List<AgentToolSpec> = all.filter { it.name in allowed }

    /** 渲染给模型的工具清单（只列本次真正允许的工具，避免模型调用不存在的工具）。 */
    fun renderForPrompt(allowed: Set<String>): String = specsFor(allowed).joinToString("\n") { it.promptLine() }

    private val FENCE = Regex("```(?:tool|tool_call|tool-call)\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val XML = Regex("<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call>", RegexOption.IGNORE_CASE)

    /** 从模型输出里解析工具调用（支持一次输出多个调用、JSON 数组或单个对象）。 */
    fun parse(text: String): List<AgentToolCall> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<AgentToolCall>()
        FENCE.findAll(text).forEach { out += decodePayload(it.groupValues[1]) }
        XML.findAll(text).forEach { out += decodePayload(it.groupValues[1]) }
        return out
    }

    /** 去掉工具块后的正文（给用户看的文本里不该出现协议块）。 */
    fun stripToolBlocks(text: String): String =
        text.replace(FENCE, "").replace(XML, "").replace(Regex("\n{3,}"), "\n\n").trim()

    private fun decodePayload(raw: String): List<AgentToolCall> {
        val trimmed = raw.trim().removePrefix("json").trim()
        if (trimmed.isEmpty()) return emptyList()
        return runCatching {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull { decodeCall(arr.optJSONObject(it)) }
            } else {
                listOfNotNull(decodeCall(JSONObject(trimmed)))
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeCall(obj: JSONObject?): AgentToolCall? {
        obj ?: return null
        val name = sequenceOf(obj.optString("name"), obj.optString("tool"), obj.optString("action"))
            .map { it.trim() }.firstOrNull { it.isNotBlank() && spec(it) != null } ?: return null
        val args = obj.optJSONObject("arguments")
            ?: obj.optJSONObject("args")
            ?: obj.optJSONObject("parameters")
            ?: JSONObject()
        return AgentToolCall(name, args, obj.toString())
    }
}
