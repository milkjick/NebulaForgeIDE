package com.nebulaforge.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 消息角色。TOOL 是工具/命令执行卡片（AI 工作台里可折叠展示输出行数）。 */
enum class AiMessageRole { USER, ASSISTANT, TOOL }

/** 任务模式当前处于哪个阶段（用于顶部状态行与「停止」按钮的可用性）。 */
enum class AiTaskPhase { IDLE, THINKING, TOOL, WRITING, DONE, ERROR }

/**
 * 单条会话消息。
 *
 * 与旧结构（只有 fromUser + text）相比补齐了「工作台」必需的信息：
 * 思考过程（[reasoning]）、结束原因（[finishReason]，用于判断**是否被 token 上限截断**）、
 * 用量（[tokens]）、流式状态（[streaming]）、工具卡片（[toolName]/[toolOutput]）。
 */
data class AiChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: AiMessageRole = AiMessageRole.ASSISTANT,
    val text: String = "",
    /** 推理模型的思考过程（deepseek-reasoner 等通过 reasoning_content 返回），默认折叠展示。 */
    val reasoning: String = "",
    /** 工具卡片：工具名（如 shizuku_exec / gradlew）。 */
    val toolName: String? = null,
    /** 工具卡片：输出正文，UI 默认折叠并显示行数。 */
    val toolOutput: String = "",
    /** 工具卡片：真实退出码（失败时红色标记）。 */
    val toolExitCode: Int? = null,
    /**
     * 工具卡片：**开始执行**的墙钟时间（0 = 未知，例如旧数据）。
     *
     * 以前这里只有 [createdAt]，构建/下载这类长任务跑起来界面上只有一句「执行中…」，
     * 用户完全判断不出「已经在跑 5 秒」还是「卡死了 5 分钟」，也没法拿用时跟上次对比。
     * 有了开始/结束时间戳，卡片就能显示「⏱ 42.3s」并在运行中实时走表。
     */
    val toolStartedAt: Long = 0L,
    /** 工具卡片：**执行结束**的墙钟时间（0 = 还在跑或未知）。 */
    val toolFinishedAt: Long = 0L,
    val tokens: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val finishReason: String = "",
    val streaming: Boolean = false,
    val error: String? = null,
    val model: String = "",
    /** 用户消息携带的附件（助手消息恒为空）。图片的 base64 不持久化，见 [com.nebulaforge.core.agent.AiAttachment]。 */
    val attachments: List<com.nebulaforge.core.agent.AiAttachment> = emptyList(),
    /**
     * 钉住：长按菜单「钉住」。被钉住的消息**永远进入上下文**（即使已滑出预算窗口），
     * 用于把「必须一直记住的约束」（不许删代码、必须用某 API 等）固定住。
     */
    val pinned: Boolean = false,
    /**
     * 从上下文移除／保留显示，不发给大模型：本条不再拼进提示词（界面仍然显示）。
     * 长对话里塞了半截实验、闲聊、错误方向时用它止损，而不是把消息删掉（删了就查不到）。
     */
    val excludedFromContext: Boolean = false,
    /** 「从上下文移除」的弱化显示：整条折叠成一行，避免视觉噪音；「保留显示」则为 false。 */
    val contextCollapsed: Boolean = false
) {
    val fromUser: Boolean get() = role == AiMessageRole.USER

    /** 是否因 max_tokens 用尽被截断：为 true 时 UI 必须提示并可「继续」。 */
    val truncated: Boolean
        get() {
            val f = finishReason.uppercase().replace("_", "").replace("-", "")
            return f == "LENGTH" || f == "MAXTOKENS"
        }

    val toolLineCount: Int get() = if (toolOutput.isBlank()) 0 else toolOutput.count { it == '\n' } + 1

    /** 工具卡片用时：`now = 0` 时用 [toolFinishedAt] 计算（已结束），否则按传入的当前时间走表。 */
    fun toolElapsedMs(now: Long = 0L): Long {
        if (toolStartedAt <= 0L) return 0L
        val end = when {
            toolFinishedAt >= toolStartedAt -> toolFinishedAt
            streaming && now > 0L -> now
            else -> 0L
        }
        return if (end <= 0L) 0L else (end - toolStartedAt).coerceAtLeast(0L)
    }

    /** 是否还有用时信息可展示（旧消息没有时间戳 → 不显示，避免显示假数据）。 */
    val hasToolTiming: Boolean get() = toolStartedAt > 0L

    /** 工具卡片状态文案：执行中 / 成功 / 失败（退出码 + 用时）。 */
    val toolStatusLabel: String
        get() = when {
            streaming -> "执行中"
            error != null || (toolExitCode != null && toolExitCode != 0) -> "失败"
            else -> "成功"
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("role", role.name)
        .put("text", text)
        .put("reasoning", reasoning)
        .put("toolName", toolName ?: JSONObject.NULL)
        .put("toolOutput", toolOutput)
        .put("toolExitCode", toolExitCode ?: JSONObject.NULL)
        .put("toolStartedAt", toolStartedAt)
        .put("toolFinishedAt", toolFinishedAt)
        .put("tokens", tokens)
        .put("createdAt", createdAt)
        .put("finishReason", finishReason)
        .put("error", error ?: JSONObject.NULL)
        .put("model", model)
        .put("attachments", JSONArray().apply {
            attachments.forEach { put(it.toJson()) }
        })
        .put("pinned", pinned)
        .put("excludedFromContext", excludedFromContext)
        .put("contextCollapsed", contextCollapsed)

    companion object {
        fun fromJson(o: JSONObject): AiChatMessage = AiChatMessage(
            id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            role = runCatching { AiMessageRole.valueOf(o.optString("role", "ASSISTANT")) }.getOrDefault(AiMessageRole.ASSISTANT),
            text = o.optString("text"),
            reasoning = o.optString("reasoning"),
            toolName = o.optString("toolName").takeIf { it.isNotBlank() && it != "null" },
            toolOutput = o.optString("toolOutput"),
            toolExitCode = if (o.isNull("toolExitCode")) null else o.optInt("toolExitCode"),
            // 旧会话没有这两个字段 → 默认 0（界面据此判断「无用时信息」，不显示假数据）。
            toolStartedAt = o.optLong("toolStartedAt", 0L),
            toolFinishedAt = o.optLong("toolFinishedAt", 0L),
            tokens = o.optInt("tokens"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            finishReason = o.optString("finishReason"),
            // 载入时绝不复原 streaming：进程重启后不可能还有流在跑，否则会永远转圈。
            streaming = false,
            error = o.optString("error").takeIf { it.isNotBlank() && it != "null" },
            model = o.optString("model"),
            attachments = buildList {
                val arr = o.optJSONArray("attachments") ?: return@buildList
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(com.nebulaforge.core.agent.AiAttachment.fromJson(item))
                }
            },
            pinned = o.optBoolean("pinned", false),
            excludedFromContext = o.optBoolean("excludedFromContext", false),
            contextCollapsed = o.optBoolean("contextCollapsed", false)
        )

        fun listToJson(list: List<AiChatMessage>): JSONArray = JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(array: JSONArray?): List<AiChatMessage> = buildList {
            if (array != null) for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
        }
    }
}

/** 会话索引项：列表只加载元信息，不用把全部消息读进内存。 */
data class AiSessionMeta(
    val id: String,
    val title: String,
    val messageCount: Int,
    val tokens: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("messageCount", messageCount)
        .put("tokens", tokens).put("createdAt", createdAt).put("updatedAt", updatedAt).put("model", model)

    companion object {
        fun fromJson(o: JSONObject) = AiSessionMeta(
            id = o.optString("id"),
            title = o.optString("title"),
            messageCount = o.optInt("messageCount"),
            tokens = o.optInt("tokens"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
            model = o.optString("model")
        )
    }
}

/**
 * AI 工作台状态。
 *
 * [contextTokens]/[contextLimit] 对应界面上「38032/128000 (29%)」那一行：
 * 用真实 usage（服务端返回）优先，缺失时用估算值，让用户知道离上下文上限还有多远。
 */
data class AiChatUiState(
    val sessions: List<AiSessionMeta> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<AiChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val contextTokens: Int = 0,
    val contextLimit: Int = 128000,
    val lastPromptTokens: Int = 0,
    val lastCompletionTokens: Int = 0,
    val maxTokens: Int = 8192,
    val modelLabel: String = "",
    val providerLabel: String = "",
    /** 任务模式阶段（任务工作台只有任务模式，不再有聊天模式开关）。 */
    val phase: AiTaskPhase = AiTaskPhase.IDLE,
    /**
     * 本轮任务的开始时间（0 = 没有正在跑/刚跑过的任务）。
     *
     * 顶栏原来只有一句「正在调用工具…」，构建一跑几分钟，用户看不出已经跑了多久、
     * 也判断不了「是在慢还是在卡」。这里记下开始时间，顶栏就能实时显示 `用时 01:23`。
     */
    val taskStartedAt: Long = 0L,
    /** 本轮任务的结束时间（0 = 还在跑或没有任务）。 */
    val taskFinishedAt: Long = 0L,
    /** 本轮已提交、尚未发送的附件。 */
    val pendingAttachments: List<com.nebulaforge.core.agent.AiAttachment> = emptyList(),
    /** 允许联网检索（真实 web_search / fetch_page）。 */
    val online: Boolean = true,
    /** 允许 AI 在设备终端执行命令。 */
    val allowCommand: Boolean = true,
    /** 允许 AI 提出写文件改动（执行前仍需逐条确认 Diff）。 */
    val allowWrite: Boolean = true,
    /** 本次任务已完成的工具轮数（用于「已调用 N 个工具」提示）。 */
    val toolRounds: Int = 0,
    /** 妙招（可一键启停，启用后作为提示词补丁）。 */
    val tricks: List<com.nebulaforge.core.agent.AgentTrick> = emptyList(),
    /** 技能（含内置与用户/AI 自建）。 */
    val skills: List<com.nebulaforge.core.agent.AgentSkill> = emptyList(),
    /** 当前项目记忆（面板展示 + 可删除）。 */
    val memories: List<com.nebulaforge.core.agent.ProjectMemoryEntry> = emptyList(),
    /** 当前项目经验库。 */
    val experiences: List<com.nebulaforge.core.agent.AgentExperience> = emptyList(),
    /** 待用户确认的写文件改动（Diff 审查），非空时界面必须弹出确认。 */
    val pendingPatch: AiPendingPatch? = null
) {
    /** 上下文占用百分比（0..100）。 */
    val contextPercent: Int
        get() = if (contextLimit <= 0) 0 else ((contextTokens.toDouble() / contextLimit) * 100).toInt().coerceIn(0, 100)

    /** 已归档/已总结的消息数（助手回复条数），对应 DeepSeek 那种「N 条 / 已总结 M」的展示。 */
    val summarizedCount: Int get() = messages.count { it.role == AiMessageRole.ASSISTANT && it.text.isNotBlank() }

    /** 顶部状态行文案。 */
    val phaseLabel: String
        get() = when (phase) {
            AiTaskPhase.IDLE -> if (messages.isEmpty()) "待命" else "已完成"
            AiTaskPhase.THINKING -> "思考中…"
            AiTaskPhase.TOOL -> "正在调用工具…"
            AiTaskPhase.WRITING -> "生成中…"
            AiTaskPhase.DONE -> "已完成"
            AiTaskPhase.ERROR -> message.ifBlank { "出错了" }
        }

    val canStop: Boolean get() = busy || streaming

    /** 任务是否正在跑（用真实阶段判断，不看 loading 标志）。 */
    val taskActive: Boolean
        get() = phase == AiTaskPhase.THINKING || phase == AiTaskPhase.TOOL || phase == AiTaskPhase.WRITING

    /** 本轮任务已用时（毫秒）。`now` 由界面每 500ms 传进来驱动走表；任务结束时固定为总用时。 */
    fun taskElapsedMs(now: Long = 0L): Long {
        if (taskStartedAt <= 0L) return 0L
        val end = when {
            taskFinishedAt >= taskStartedAt -> taskFinishedAt
            taskActive && now > 0L -> now
            else -> 0L
        }
        return if (end <= 0L) 0L else (end - taskStartedAt).coerceAtLeast(0L)
    }
}

/** 粗略 token 估算：CJK 约 1 字 1 token，其余字符约 4 字符 1 token（用于无 usage 时的占比显示与裁剪）。 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    for (ch in text) if (ch.code in 0x2E80..0x9FFF || ch.code in 0x3000..0x303F || ch.code in 0xFF00..0xFFEF) cjk++
    val other = text.length - cjk
    return cjk + (other / 4) + 1
}
