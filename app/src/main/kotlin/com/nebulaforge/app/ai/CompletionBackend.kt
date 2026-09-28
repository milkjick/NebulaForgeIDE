package com.nebulaforge.app.ai

import android.content.Context
import com.nebulaforge.core.aiprovider.ChatImage
import com.nebulaforge.core.aiprovider.ChatOptions
import com.nebulaforge.core.aiprovider.ChatResult
import com.nebulaforge.core.aiprovider.ChatUsage

/**
 * 一次补全的「后端」抽象。
 *
 * 为什么需要它（真机根因）：AI 任务台（工作台 · 任务模式）过去**只认「在线 API 服务商」**——
 * `launchCompletion()` 开头就是 `if (!settings.isUsable()) { 报错「AI Provider 未启用」; return }`，
 * 而 `isUsable()` 要求「已启用 + 有 API Key + 有模型名」。于是用户明明在「网桥」面板里接好了
 * 网页 AI（AI 聚合网关：ChatGPT / Claude / DeepSeek …，状态显示「已接入」），一进任务台还是被
 * 拦在门外 —— 用户看到的就是「AI 网关无法调用 AI 任务台」。
 *
 * 修法：把「怎么拿到一步补全」抽成这个接口，任务台不再关心后端是哪种，统一走同一套
 * 「补全 → 解析工具调用 → 执行 → 回灌历史」的循环。于是网关（[WebBridgeCompletionBackend]）
 * 和 API（[ApiCompletionBackend]）都能把任务干完，工具集与审批逻辑完全复用。
 */
interface CompletionBackend {

    /** 稳定标识（日志/去重用）。 */
    val id: String

    /** 界面上显示的「协议 · 模型」标签。 */
    val label: String

    /** 供给端面板显示的配置名（如「默认配置」/「AI 聚合网关」）。 */
    val providerLabel: String

    /** 单次生成上限（token）。 */
    val maxTokens: Int

    /** 上下文窗口（token），仅用于用量条显示。 */
    val contextWindow: Int

    /** 是否支持多模态图片输入。 */
    val supportsImages: Boolean get() = true

    /**
     * 执行一次补全。
     *
     * @param history 扁平化的对话历史（role 为 `user` / `assistant`）。
     * @param images  本轮附带的图片；[supportsImages] 为 false 时调用方传空。
     * @param onDelta 增量正文回调（网页网关无 token 级增量，会一次性交出整段回答）。
     * @param onStatus 过程文案（「打开 ChatGPT…」「回答生成中…」），任务台直接显示给用户。
     */
    suspend fun complete(
        system: String,
        history: List<Pair<String, String>>,
        images: List<ChatImage>,
        options: ChatOptions,
        onDelta: (String) -> Unit,
        onStatus: (String) -> Unit = {}
    ): ChatResult

    /** 释放后端持有的资源（无状态后端可不实现）。 */
    fun close() {}
}

/** OpenAI 兼容（以及各家协议）在线 API 后端：原有一等公民，行为保持不变。 */
class ApiCompletionBackend(
    settings: com.nebulaforge.core.agent.AiProviderSettings
) : CompletionBackend {

    private val settings = settings
    private val client = com.nebulaforge.core.agent.OpenAiCompatibleCompletionClient(settings)

    override val id: String = "api:${settings.protocol.id}"
    override val label: String = "${settings.protocol.label} · ${settings.model}"
    override val providerLabel: String = settings.displayName()
    override val maxTokens: Int = settings.maxTokens
    override val contextWindow: Int = settings.contextWindow

    override suspend fun complete(
        system: String,
        history: List<Pair<String, String>>,
        images: List<ChatImage>,
        options: ChatOptions,
        onDelta: (String) -> Unit,
        onStatus: (String) -> Unit
    ): ChatResult = if (images.isNotEmpty()) {
        client.streamMultimodal(system, history, images, options, onDelta)
    } else {
        client.streamCompletion(system, history, options, onDelta)
    }
}

/**
 * AI 聚合网关后端：把「网页 AI」（[WebAiBridge]）当成任务台的推理后端。
 *
 * 关键差异与处理：
 * 1. 网页只有一个输入框，没有 system/多轮角色 → 把 system 提示 + 历史**压平成一段文本**；
 *    历史只保留最近若干轮并逐轮截断，避免长提示被站点拒绝或提交过慢。
 * 2. 网页没有 token 级流式输出（只能轮询正文增量）→ [onDelta] 一次性交出整段回答；
 *    过程用 [onStatus] 汇报，用户不会看到「卡住不动」。
 * 3. 站点的回答前缀 `[站名]` 会在界面上保留（用户能知道这次是谁答的）。
 * 4. 轮询式调度：[targets] 里多个站点会被轮流使用，避免总压在一个站上被限流。
 */
class WebBridgeCompletionBackend(
    private val context: Context,
    private val targets: List<WebAiBridge.WebAi>,
    override val maxTokens: Int = 8192,
    override val contextWindow: Int = 32_000,
    // 单站最长等待。真机教训（2.12.68）：45s 对「写一个完整应用」这种长回答**太短** ——
    // 站点还在流式出字时就被超时掐断，上层拿到的还是半截话（症状同样是「不调工具、任务没做完」）。
    // 现在改成 90s，且 collectReply 会在页面上「站点还在出字」（有停止生成按钮）时一直等到写完；
    // 未登录/地区限制的站点仍会快速失败换站，不会把时间全花在坏站上。
    // 单站预算。真机取证：90s 对「深度思考型」站点不够（DeepSeek 收到长提示词后
    // 前 75s 一个字符都没吐，被我们的无出字窗口误杀）；健康的站点仍然在 20s 内返回，
    // 真死的站点由 gateway 的「未落地/未登录」快速闸门提前换站，所以放宽到 180s 的代价很小。
    private val timeoutMs: Long = 180_000
) : CompletionBackend {

    init {
        require(targets.isNotEmpty()) { "聚合网关后端至少要有一个已接入的网页 AI" }
    }

    /**
     * 当前站点。默认取「首选 → 上次成功 → 其它」里的第一个（就是任务台顶栏显示的那个名字）。
     * 旧实现直接取 `targets.first()`（列表第一个，真机上常是 Claude）→ 界面上显示的站点与实际
     * 出话的站点不一致，用户会觉得「每次调用的 AI 都不一样」。现在与 [WebAiBridge.orderedForTask] 对齐。
     */
    private var lastUsed: WebAiBridge.WebAi =
        runCatching { WebAiBridge.orderedForTask(context) }.getOrDefault(emptyList())
            .firstOrNull { o -> targets.any { it.id == o.id } }
            ?: targets.first()

    override val id: String = "bridge"
    override val providerLabel: String get() = "AI 聚合网关（${lastUsed.label}）"
    override val label: String get() = "聚合网关 · ${lastUsed.label}"
    override val supportsImages: Boolean = false

    override suspend fun complete(
        system: String,
        history: List<Pair<String, String>>,
        images: List<ChatImage>,
        options: ChatOptions,
        onDelta: (String) -> Unit,
        onStatus: (String) -> Unit
    ): ChatResult {
        val prompt = buildPrompt(system, history, images.isNotEmpty())
        // 提速①：站点那边的会话还在时只补最新一轮（几百字），不再把 system + 全历史重贴一遍。
        val delta = buildDeltaPrompt(history)
        // 提速②：**粘性站点**。以前是每轮 `cursor++` 轮换，站点一换就要重新打开页面
        // （重新挂载 SPA、重新走登录态、站点还会把整段历史当新对话重读）——每换一次多几秒到几十秒。
        // 现在固定用当前站点，只有它真的不可用（找不到输入框/空壳页/抓不到正文）时才切下一个。
        // 选站顺序：**首选 → 上次成功 → 其它 → 暂时不可用**（用户偏好优先，见 WebAiBridge.orderedForTask）。
        // 真机教训：用户明明登录好的是 DeepSeek，网关却先去调 Claude —— 而 Claude 在中国大陆是
        // 地区限制页（claude.com/app-unavailable-in-region）：页面看着有输入框，填得进去却永远发不出去，
        // 用户看到的就是「消息无法发送」。现在①用户设了「首选」就优先它；②被判定不可用的站沉到最后。
        val rank = runCatching { WebAiBridge.orderedForTask(context) }
            .getOrDefault(emptyList())
            .withIndex().associate { (i, a) -> a.id to i }
        val order = targets.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
        val errors = mutableListOf<Pair<String, String>>()
        for ((i, ai) in order.withIndex()) {
            if (i >= MAX_ATTEMPTS) break
            onStatus("交给 ${ai.label} …")
            val text = WebAiBridge.ask(
                context = context,
                ai = ai,
                prompt = prompt,
                timeoutMs = timeoutMs,
                onProgress = { onStatus(it) },
                deltaPrompt = delta
            ).trim()

            if (text.startsWith("✗")) {
                // 网页侧的失败（地区限制 / 未登录 / 找不到输入框 / 空壳页）必须让用户看到原文，
                // 否则又会变成「点一下没反应」。每个站的原因都收集起来，最后一起报出去。
                errors += ai.label to text.removePrefix("✗").trim()
                val next = order.getOrNull(i + 1)
                if (next != null && i + 1 < MAX_ATTEMPTS) {
                    onStatus("${ai.label} 不可用，改用 ${next.label} …")
                    continue
                }
                break
            }

            // ★ 最终的闸门（真机症状：任务台收到「整段预设指令 + 站点首页文字」，模型因此没有任何
            //   工具调用、任务完不成）：网页输出必须过准入检查。不合格就**换下一个站点重试**，
            //   绝不把这段废话当模型回答交给上层。
            val reject = WebAiBridge.replyRejectReason(text)
            if (reject != null) {
                errors += ai.label to reject
                android.util.Log.w("NbWebAi", "站点输出被闸门拦下（${ai.label}）：$reject")
                val next = order.getOrNull(i + 1)
                if (next != null && i + 1 < MAX_ATTEMPTS) {
                    onStatus("${ai.label}：$reject → 改用 ${next.label} …")
                    continue
                }
                // 没有下一个站点可换：这一轮就是失败，break 交给下面的统一报错
                // （绝不能把回显/首页文字当回答返回给上层）。
                break
            }

            lastUsed = ai
            runCatching { WebAiBridge.setLastOk(context, ai.id) }
            val answer = text
            onStatus("已取回 ${answer.length} 字回答")
            onDelta(answer)
            // 网页不返回 usage：用字符数粗估，保证界面用量条不是一直为 0。
            val completionTokens = (answer.length / 2).coerceAtLeast(1)
            val promptTokens = (prompt.length / 2).coerceAtLeast(1)
            return ChatResult(
                text = answer,
                reasoning = "",
                finishReason = "stop",
                usage = ChatUsage(promptTokens = promptTokens, completionTokens = completionTokens)
            )
        }
        // ★ 文案必须**短**：这段文本会被塞进任务台的 `state.message`（渲染在消息列表之外），
        //   真机上是「一排十几行的报错把输入框顶出屏幕 → 用户回不到 AI 工作台」的元凶。
        //   每个站点的完整原因仍写在消息气泡里（message.error），这里只留一句摘要。
        val detail = errors.joinToString("；") { (l, r) -> "「$l」${r.lineSequence().first().take(90)}" }
        throw IllegalStateException(
            "聚合网关 ${errors.size} 个站点都没成功（$detail）。" +
                "建议：打开「网桥」面板登录一个站点（或点「设首选」），然后重试。"
        )
    }

    /** 增量提示：站点那边的会话还在时，只补最新的这一轮（用户消息/工具结果）。
     *  第一次仍走 [buildPrompt]（system + 全历史），之后每轮只发几百字，站点响应明显更快。
     *  历史不是以「用户回合」结尾（例如上游刚注入 system 提醒）时返回 null → 老实走全量。 */
    private fun buildDeltaPrompt(history: List<Pair<String, String>>): String? {
        val last = history.lastOrNull() ?: return null
        if (last.first != "user") return null
        return "（接着上一轮继续，直接给出下一步；需要工具时**必须**输出一个 ```tool 围栏代码块" +
            "（形如 ```tool{\"name\":\"工具名\",\"arguments\":{...}}```），不要写成普通说明文字或普通 ```json 代码块，不要复述历史。）\n" +
            last.second.take(MAX_TURN_CHARS)
    }


    /** 把 system + 历史压平成一段网页可提交的提示；历史做「保留最近 N 轮 + 单轮截断」控制长度。 */
    private fun buildPrompt(system: String, history: List<Pair<String, String>>, hasImages: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("你是跑在 NebulaForgeIDE 工作台里的编码 Agent，请严格按下面的工作区约定来回答。")
        sb.appendLine()
        sb.appendLine("===== 工作区约定与工具协议 =====")
        sb.appendLine(system)
        sb.appendLine()
        sb.appendLine("===== 对话历史 =====")
        val tail = history.takeLast(HISTORY_TURNS)
        tail.forEach { (role, content) ->
            val who = if (role == "assistant") "助手" else "用户"
            sb.appendLine("【$who】")
            sb.appendLine(content.take(MAX_TURN_CHARS))
            sb.appendLine()
        }
        if (hasImages) {
            sb.appendLine("（用户还附带了图片，但当前是网页网关后端，无法上传图片，请按上面的文字描述作答。）")
            sb.appendLine()
        }
        sb.appendLine("请直接给出下一步（必要的文字说明 + 需要工具时**必须**输出 ```tool 围栏代码块，形如 ```tool{\"name\":\"工具名\",\"arguments\":{...}}```；不要写成普通说明文字，也不要用 ```json 代码块）），不要复述本提示。")
        return sb.toString().take(MAX_PROMPT_CHARS)
    }

    private companion object {
        /** 只回灌最近这么多轮，控制网页提交长度。 */
        const val HISTORY_TURNS = 8

        /** 单轮消息最多贴这么多字符。 */
        const val MAX_TURN_CHARS = 4_000

        /** 整段提示上限（网页输入框对超长文本很敏感）。 */
        const val MAX_PROMPT_CHARS = 24_000

        /** 一次补全最多尝试几个站点（粘性站点失败后才顺延，避免把时间全花在换站上）。 */
        /** 一次任务最多尝试几个站点（用错误信息里的「试过 N 个站点」能看出实际试了几个）。 */
        const val MAX_ATTEMPTS = 3
    }
}

/** 后端选择结果。 */
sealed interface BackendChoice {
    data class Ready(val backend: CompletionBackend) : BackendChoice

    /** 没有任何可用后端：附上给用户看的解决办法。 */
    data class Unavailable(val message: String) : BackendChoice
}

/**
 * 按「API 优先、聚合网关兜底」的顺序选后端。
 *
 * 以前这里不存在：任务台直接 `if (!settings.isUsable()) 报错返回`，把「只接了网页 AI」的用户
 * 完全挡在门外。现在只要有一个可用后端就能开工。
 */
fun resolveCompletionBackend(
    context: Context,
    settings: com.nebulaforge.core.agent.AiProviderSettings
): BackendChoice {
    if (settings.isUsable()) return BackendChoice.Ready(ApiCompletionBackend(settings))

    val web = runCatching { WebAiBridge.all(context).filter { it.usable } }.getOrDefault(emptyList())
    if (web.isNotEmpty()) {
        return BackendChoice.Ready(WebBridgeCompletionBackend(context, web, maxTokens = settings.maxTokens))
    }

    val message = if (!settings.enabled) {
        "还不能开工：请先「启用在线 API 服务商」并填好 Base URL 与 API Key；" +
            "或者到 AI 工作台 →「网桥」面板接入一个网页 AI（聚合网关）后重试。"
    } else {
        "还不能开工：在线 API 配置不完整（检查 API Key 与模型名）；" +
            "或者到 AI 工作台 →「网桥」面板接入一个网页 AI（聚合网关）后重试。"
    }
    return BackendChoice.Unavailable(message)
}

/**
 * 把「AI 聚合网关」后端适配成 core 的 [com.nebulaforge.core.agent.AiCompletionClient]。
 *
 * 为什么需要（真机根因）：计划器（建立执行计划）、网页事实核验、逆向证据分析、修改方案
 * 这几处只认「一步补全」（system + user → 文本），过去一律 new 一个
 * [com.nebulaforge.core.agent.OpenAiCompatibleCompletionClient] 并 `check(apiKey.isNotBlank())`。
 * 于是**只接了网页 AI、没配 API Key** 的用户一用到这些功能就报「AI Provider 未启用」，
 * 而任务面板同时却显示「可用（走 AI 聚合网关：网页 AI）」——用户看到的就是
 * 「AI 网桥/网关无法调用工作台」。这里把网关后端包成同一个接口，调用方零改动。
 */
class BridgeCompletionClient(private val backend: CompletionBackend) :
    com.nebulaforge.core.agent.AiCompletionClient {

    override suspend fun complete(systemPrompt: String, userPrompt: String): String =
        backend.complete(
            system = systemPrompt,
            history = listOf("user" to userPrompt),
            images = emptyList(),
            options = ChatOptions(maxTokens = backend.maxTokens, stream = false),
            onDelta = {}
        ).text
}

/**
 * 「一步补全」类模块（计划器 / 事实核验 / 逆向分析 / 修改方案）选客户端的统一入口。
 *
 * 选法与任务台完全一致：**在线 API 优先，「AI 聚合网关」（网页 AI）兜底**。
 * 两者都没有时抛错，消息直接复用 [BackendChoice.Unavailable] 的文案（告诉用户去哪配）。
 */
fun resolveAgentCompletionClient(
    context: Context,
    settings: com.nebulaforge.core.agent.AiProviderSettings
): com.nebulaforge.core.agent.AiCompletionClient =
    when (val choice = resolveCompletionBackend(context, settings)) {
        is BackendChoice.Ready ->
            // API 可用时保持原行为（同一条在线链路，便于排查）；否则改走网关。
            if (settings.isUsable()) com.nebulaforge.core.agent.OpenAiCompatibleCompletionClient(settings)
            else BridgeCompletionClient(choice.backend)
        is BackendChoice.Unavailable -> throw IllegalStateException(choice.message)
    }
