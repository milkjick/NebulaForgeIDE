package com.nebulaforge.core.agent

import android.content.Context
import java.io.File

/**
 * AI 工作台的「能力中心」：把本项目已有的可复用能力（联网检索、项目记忆、经验库、
 * 失败模式、学习记录、妙招、技能）聚合成一个门面，供 app 层编排器按需取用。
 *
 * 存在的理由：任务模式要用到的东西分散在 6 个 store/service 里，如果让编排器自己逐个 new，
 * 既难测试也容易漏掉（例如忘了召回记忆就等于没有记忆）。这里统一暴露：
 *  - [toolset]：本次允许哪些工具（受用户开关约束）；
 *  - [buildSystemPrompt]：把工具清单/技能/记忆/妙招拼成系统提示词；
 *  - [rememberMemory]/[rememberExperience]/[recordFeedback]：把结果沉淀回记忆与经验库。
 */
class AgentCapabilities(context: Context) {

    private val appContext = context.applicationContext

    val web = AgentWebSearchService()
    val tricks = AgentTrickStore(appContext)
    val skills = AgentSkillStore(appContext)
    val memories = ProjectMemoryStore(appContext)
    val experiences = AgentExperienceStore(appContext)
    val failurePatterns = FailurePatternStore(appContext)
    val conversations = AgentConversationStore(appContext)
    val learning = AgentLearningService(memories, experiences, appContext)
    val finalF = AgentFinalFService(appContext)

    /** 网页来源可信度评分（0..1），用于让模型知道「这条来源有多可信」。 */
    fun credibility(url: String): Double = runCatching { finalF.sourceCredibility.score(url).score }.getOrDefault(0.5)

    /**
     * 本次任务允许的工具集。
     *
     * 权限模型（与 UI 上的开关一一对应，不做任何"暗地里提权"）：
     *  - 只读工具 + 计划/记忆/妙招/技能创建：始终允许（不触碰项目文件，也不执行外部命令）；
     *  - 联网工具：需用户开启「联网」；
     *  - run_command / run_skill：需用户开启「允许执行命令」；
     *  - write_file：需用户开启「允许写文件」，且执行时还要逐条 Diff 确认。
     */
    fun toolset(online: Boolean, allowCommand: Boolean, allowWrite: Boolean): Set<String> = buildSet {
        add(AgentToolCatalog.READ_FILE)
        add(AgentToolCatalog.LIST_FILES)
        add(AgentToolCatalog.GREP_PROJECT)
        add(AgentToolCatalog.PROPOSE_PLAN)
        add(AgentToolCatalog.REMEMBER)
        add(AgentToolCatalog.CREATE_TRICK)
        add(AgentToolCatalog.CREATE_SKILL)
        if (online) { add(AgentToolCatalog.WEB_SEARCH); add(AgentToolCatalog.FETCH_PAGE) }
        // 开发/媒体能力（抓实时日志 / 识屏看状况 / 操作手机文件 / 打包到手机 / 生成视频）**默认就给 AI**：
        // 用户要的是「AI 自己会查、会动手」，而不是先让他去手动开面板、填配置。
        // 默认可见不等于默认放行：写文件、跑命令、打包这些每一次调用都要过审批（TaskApprovalBroker），
        // 破坏性命令另有安全策略兜底，所以这里少一层开关不会削弱安全性。
        addAll(AgentToolCatalog.DEVTOOLS)
        // 自主补全（缺技能/缺环境自己搞定 + 调 App 自身能力）同样**默认给 AI**，用户不需要配置任何东西。
        addAll(AgentToolCatalog.AUTONOMY)
        // 构建能力（编译打包 / 运行安装 / 环境自检 / 新建项目）同样**默认给 AI**：
        // 用户的诉求就是「让 AI 把项目编译出来」，不该先让他去开开关；真正执行前仍有逐条审批兜底。
        addAll(AgentToolCatalog.BUILDING)
        // 宿主能力网关（列能力/调用/读审计）：默认给 AI。理由是授权判定在网关内部逐次发生，
        // 所以「默认可见」不等于「默认可改」——用户仍然会看到每一次写入/执行的授权与审计。
        addAll(AgentToolCatalog.CAPABILITY)
        // 代码编辑（精确替换 / 批量替换 / 批量读 / 删除 / 移动重命名）、Git、HTTP 请求、逆向工程、
        // 协作（问用户 / 技能管理）也**默认给 AI**：这些都是「干活的家伙」——
        // 缺精确替换就只能整份重写文件，缺删除/移动就没法重构，缺逆向就只能让用户自己去面板里点。
        // 写类操作与 write_file 走同一条审批通道（默认自动放行，用户可随时关）。
        addAll(AgentToolCatalog.CODING)
        addAll(AgentToolCatalog.VCS)
        addAll(AgentToolCatalog.NET)
        addAll(AgentToolCatalog.REVERSE)
        addAll(AgentToolCatalog.COLLAB)
        if (allowCommand) {
            add(AgentToolCatalog.RUN_COMMAND); add(AgentToolCatalog.RUN_SKILL)
        }
        if (allowWrite) add(AgentToolCatalog.WRITE_FILE)
    }

    /** 系统提示词（任务模式专用）。 */
    fun buildSystemPrompt(
        projectPath: String?,
        toolset: Set<String>,
        attachments: List<AiAttachment>,
        online: Boolean,
        allowCommand: Boolean,
        allowWrite: Boolean,
        memoryContext: String = "",
        /** MCP 实时目录（由 app 层从 McpHost 现查现拼，Server/工具都是动态的）。 */
        mcpCatalog: String = ""
    ): String = buildString {
        append("你是 NebulaForge IDE 内置「AI 工作台」的任务执行助手，运行在用户的 Android 手机上。\n")
        append("你可以读项目代码、执行设备终端命令、联网检索、并把结论沉淀进项目记忆/经验库。\n\n")

        append("【工作方式】\n")
        // 真机反馈的核心痛点：模型爱「只出方案不动手」。任务模式下把「动手」写成第一规则。
        append("0. **先动手，别只出方案**：用户交代的任务要真正执行到底 —— 该调工具就立刻调" +
            "（可以连续多轮：调用 → 看结果 → 再调用 → 最后收尾）；绝不要用一句「你可以这样做…」" +
            "把活儿推回给用户，也不要只描述计划就结束。只有真的缺权限/缺关键信息、或需要用户拍板时才停下来问。\n")
        append("   同时**严格按用户的原话与目标执行**：不要擅自缩小、替换或扩大任务范围；" +
            "用户指定的语言、工具、文件、格式一律照做；用户说「继续」就接着上一轮往下做。\n")
        // 用户明确要求：AI 在动手完成任务前必须先制定计划。这里写成硬约定，
        // 工具宿主（AiTaskToolHost）另有代码级闸门兜底 —— 没计划就直接改文件/跑命令会被拒绝。
        append("0a. **动手前先立计划（硬规则）**：任何会改动项目或设备的动作 —— write_file / edit_file / " +
            "search_replace / delete_path / move_file / run_command / run_skill / build_project / run_app / " +
            "install_env / create_project / apk_reverse 的写类 action / api_reverse 的 proxy_start —— " +
            "**执行前必须先调用 propose_plan** 写出本轮分步计划（写清改哪个文件、跑什么命令、怎么验证）；" +
            "没有计划就直接调这些工具时，工作台会**自动补一份计划并继续执行**（不会把任务卡住），" +
            "但自动补的步骤很粗，仍请你优先自己立。只读工具（read_file / list_files / " +
            "grep_project / read_files / 各类 inspect）无需计划，可直接用。\n")
        append("1. 需要了解项目事实时**先调用工具**，不要凭猜测描述代码；\n")
        append("2. 每给出关键结论，都要对应到工具返回的真实结果；工具失败时如实说明原因；\n")
        append("3. 收尾用三段式：**结论** / **改动或命令** / **验证方式**。\n\n")

        append("【当前环境】\n")
        append("- 项目路径：").append(projectPath ?: "（未打开项目）").append('\n')
        append("- 联网检索：").append(if (online) "已开启" else "未开启（需要最新信息请提示用户开启）").append('\n')
        append("- 执行命令：").append(if (allowCommand) "已开启" else "未开启（需要运行命令时请提示用户开启「允许执行命令」）").append('\n')
        append("- 写项目文件：").append(if (allowWrite) "已开启（每次写入仍需用户确认 Diff）" else "未开启（需要改代码时请提示用户开启「允许写文件」）").append('\n')
        append('\n')

        append("【工具调用协议】\n")
        append("需要工具时，输出一个围栏代码块（同一条消息里可以给出多个调用）：\n")
        append("```tool\n{\"name\":\"工具名\",\"arguments\":{\"...\":\"...\"}}\n```\n")
        append("给出调用后**停止输出**，等待工具结果回灌，再继续推理；绝不要自己编造工具输出。\n")
        append("围栏标记写成 ```tool 最稳妥；写成 ```json 或直接给裸 JSON 对象也会被识别，" +
            "但工具名必须是下面清单里的真实名字（不要自造、不要写中文名）。\n")
        append("可用工具：\n").append(AgentToolCatalog.renderForPrompt(toolset)).append('\n')
        // 全自动约定：用户已明确要求「AI 自己执行所有操作和命令，不要问」——把这条写进系统提示，
        // 让模型**直接动手**（缺技能就建、缺环境就装、要设备信息就抓），而不是停下来教用户去配置。
        if (toolset.contains(AgentToolCatalog.INSTALL_ENV) || toolset.contains(AgentToolCatalog.APP_CONTROL)) {
            append("全自动约定（重要）：缺技能用 create_skill 自己建；缺环境/缺命令用 install_env 自己装；" +
                "要看运行状况用 capture_logcat / read_screen；要动手机文件用 device_files / pack_files；" +
                "要开某个功能页或装包用 app_control。**直接执行**，不要回一句「请你去设置里配置」。\n")
        }
        if (toolset.contains(AgentToolCatalog.BUILD_PROJECT)) {
            append(
                "构建约定（重要）：要编译、要出安装包、要「打包/跑一下」时 —— " +
                    "用 build_project 编译（它走项目真实构建工具链：Gradle / flutter build / npm run build），" +
                    "用 run_app 构建并安装运行，环境可疑先用 toolchain_status 自检。" +
                    "**不要在 run_command 里手写 gradle / flutter / npm 编译命令**：" +
                    "run_command 走的是设备 shell 通道（没有 JDK 与构建工具链），手写必然报 command not found。\n"
            )
        }
        if (toolset.contains(AgentToolCatalog.WRITE_FILE)) {
            append("写文件示例：\n```tool\n{\"name\":\"write_file\",\"arguments\":{\"path\":\"app/src/main/.../Foo.kt\",\"content\":\"完整文件内容\"}}\n```\n")
        }
        append('\n')

        // MCP 目录：Server 与工具都是动态的（内置项目服务 + 用户接入的远端服务），
        // 只有把「当前真实可用的 server id / 工具名」写进提示词，模型才可能调用成功。
        if (toolset.contains(AgentToolCatalog.MCP_CALL)) {
            if (mcpCatalog.isNotBlank()) {
                append("【MCP 目录（mcp_call 可直接调用，server 用这里的 id）】\n")
                append(mcpCatalog).append('\n')
                append("用法：\n```tool\n{\"name\":\"mcp_call\",\"arguments\":{\"server\":\"<id>\",\"tool\":\"<工具名>\",\"arguments\":{}}}\n```\n\n")
            } else {
                append("【MCP 目录】当前没有就绪的 MCP Server（可在「MCP 工具」面板接入）。" +
                    "需要 MCP 能力时提示用户去面板连接，不要假装调用成功。\n\n")
            }
        }

        val skillText = skills.renderForPrompt()
        append("【可用技能】\n").append(skillText).append("\n\n")

        val enabledTricks = tricks.enabled()
        if (enabledTricks.isNotEmpty()) {
            append("【已启用妙招（用户设定的偏好，优先遵守）】\n")
            enabledTricks.forEach { trick ->
                append("· ").append(trick.name).append("：").append(trick.promptPatch.replace("\n", " ")).append('\n')
            }
            append('\n')
        }

        if (memoryContext.isNotBlank()) {
            append("【项目记忆与经验（自动召回，可直接引用；与代码冲突时以代码为准）】\n")
            append(memoryContext).append("\n\n")
        }

        if (attachments.isNotEmpty()) {
            append("【本轮附件】\n")
            attachments.forEach { a ->
                append("· ").append(a.name).append("（").append(a.kind.name).append("，").append(a.sizeLabel).append("）")
                if (a.path.isNotBlank()) append(" 路径=").append(a.path)
                append('\n')
            }
            append("图片请看图作答；文本附件已内联在提问里；二进制/压缩包请按需用命令分析其路径。\n")
            // 视图能力：只要本轮带图（电路图 / 单片机引脚图 / 时序图 / 实物照片…），
            // 一律按工程图纸的读法给出**结构化**结论，而不是泛泛描述。
            if (attachments.any { it.isImage }) {
                append(
                    "\n【图纸/图片解读规范（本轮带图，必须遵守）】\n" +
                        "- 先判类型并说出判据：电路原理图 / PCB 布局 / 单片机引脚图 / 封装图 / 时序图 / 波形图 / 元器件照片 / 界面截图。\n" +
                        "- 电路原理图：分节输出 ①电源与地 ②主要元件（位号+型号+作用）③网络连接关系（谁连谁、走哪条网络）" +
                        "④关键参数（电压/电流/阻容/晶振/分压比）⑤设计疑点与风险；能列表格就列 Markdown 表格。\n" +
                        "- 单片机引脚图/封装图：输出表格「引脚号 | 名称 | 复用功能 | 方向 | 在本电路中的连接 | 注意事项」，" +
                        "并单列电源/地/复位/晶振/Boot/下载 等特殊脚。\n" +
                        "- 时序图/波形图：给出周期、占空、建立/保持时间、相位关系，并推断总线协议（I2C/SPI/UART/单总线）及判据。\n" +
                        "- 看不清必须直说：小字、位号、网络名标为「不可确认」，并给出**具体**补拍/放大建议（放大哪块区域、想看什么），禁止猜测。\n" +
                        "- 结论后必须给「可执行下一步」（要测哪个点、要补哪个参数、要改哪根走线）。\n\n"
                )
            }
        }

        append("【边界】\n")
        append("- 被拒绝的授权（命令/写文件）必须如实告知用户，不得假装成功；\n")
        append("- 不得编造文件路径、命令输出或联网来源；\n")
        append("- 用中文回答；代码与命令保持原样。")
    }

    /**
     * 项目上下文：记忆 + 经验 + 词法知识 + 失败模式（全部本地离线，不联网）。
     * 取失败模式是为了让模型**不要重复踩同一个坑**。
     */
    fun buildProjectContext(projectPath: String?, query: String): String {
        if (projectPath.isNullOrBlank() || !File(projectPath).isDirectory) return ""
        val memory = runCatching { memories.recall(projectPath, query, 8) }.getOrDefault(emptyList())
        val experience = runCatching { experiences.recall(projectPath, query, 6) }.getOrDefault(emptyList())
        val failures = runCatching { failurePatterns.recall(projectPath, query, 4) }.getOrDefault(emptyList())
        if (memory.isEmpty() && experience.isEmpty() && failures.isEmpty()) return ""
        return buildString {
            memory.forEach { append("[记忆] ").append(it.key).append("：").append(it.content.take(400)).append('\n') }
            experience.forEach {
                append("[经验] ").append(it.title).append("：问题=").append(it.problem.take(200))
                    .append("；解法=").append(it.solution.take(400))
                    .append(if (it.verified) "（已验证）" else "（未验证）").append('\n')
            }
            failures.forEach {
                append("[失败模式] ").append(it.title).append("：症状=").append(it.symptoms.take(160))
                    .append("；根因=").append(it.rootCause.take(200))
                    .append("；修复=").append(it.remediation.take(300)).append('\n')
            }
        }.trim()
    }

    fun upsertMemory(projectPath: String, key: String, content: String, source: String = "agent"): ProjectMemoryEntry {
        val entry = ProjectMemoryEntry(projectPath = projectPath, category = "agent", key = key.take(120), content = content, source = source)
        memories.upsert(entry)
        return entry
    }

    fun addExperience(
        projectPath: String,
        title: String,
        content: String,
        verified: Boolean = false,
        tags: List<String> = emptyList(),
        source: String = "agent"
    ): AgentExperience {
        val experience = AgentExperience(
            projectPath = projectPath,
            title = title.take(120),
            problem = content.take(1200),
            solution = content.take(3000),
            outcome = if (verified) "由 AI 在任务中沉淀" else "未验证，待复核",
            tags = tags.take(12),
            verified = verified,
            source = source
        )
        experiences.add(experience)
        return experience
    }

    /** 用户对某条回答的评价（👍/👎）→ 学习记录，供后续召回排序使用。 */
    fun recordFeedback(projectPath: String?, request: String, answer: String, positive: Boolean) {
        if (projectPath.isNullOrBlank()) return
        runCatching { learning.recordFeedback(projectPath, request, answer, positive, "workbench-feedback") }
    }

    fun stats(projectPath: String?): LearningStats? =
        projectPath?.takeIf { File(it).isDirectory }?.let { runCatching { learning.learningStats(it) }.getOrNull() }
}
