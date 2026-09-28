package com.nebulaforge.core.aiprovider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 单个 AI 服务端点配置。
 *
 * 最初只够描述「官方 OpenAI / Anthropic / Gemini」，导致小众第三方 API 与**中转/聚合网关**
 * 几乎必然连不上：端点路径写死、鉴权头写死、无法附加渠道标记头、流式必带 stream_options。
 * 下面这些字段就是为这类场景补齐的兼容开关（都有默认值，历史调用点零改动）。
 */
data class ProviderConfig(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val enabled: Boolean = true,
    /** 额外请求头：中转常用非标准鉴权头（`api-key`）或渠道标记（`X-Channel`/`User-Agent`）。 */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** 鉴权头名称；为空用协议默认（OpenAI 兼容=Authorization，Anthropic=x-api-key）。 */
    val authHeader: String = "",
    /** 鉴权值前缀； 用协议默认（OpenAI 兼容="Bearer "，Anthropic/Gemini=""）。 */
    val authPrefix: String? = null,
    /** 额外查询参数：少数网关用 `?key=`/`?api-version=` 传参。 */
    val extraQuery: Map<String, String> = emptyMap(),
    /** 对话端点路径覆盖（相对 baseUrl，如 `/v1/openai/chat/completions`）；为空按协议默认拼接。 */
    val chatPath: String = "",
    /** 流式是否发送 `stream_options.include_usage`：严格网关会因未知字段直接 400。 */
    val sendStreamOptions: Boolean = true,
    /** 自定义余额接口路径（相对 baseUrl 或完整 URL）；中转/自建网关可据此查余额。 */
    val balancePath: String = ""
)
/**
 * 图片片段（多模态输入的唯一载体）。
 *
 * 用 `data:` URL 直传 base64：三大协议都支持内联图片，不需要额外的文件服务器；
 * 体积由调用方在上传前压缩控制（见 app 侧 AttachmentPreparer）。
 */
data class ChatImage(val mimeType: String, val base64: String) {
    val dataUrl: String get() = "data:$mimeType;base64,$base64"
}

data class ChatMessage(
    val role: String,
    val content: String,
    /** 随消息一起发送的图片（识图能力）。非空时 content 会按各协议的多模态格式组装。 */
    val images: List<ChatImage> = emptyList()
) {
    val hasImages: Boolean get() = images.isNotEmpty()
}

/**
 * 单次请求的生成参数。
 *
 * [maxTokens] 必须是**模型允许的上限**：过去这里被写死成 2048，长回答会在生成到一半时被服务端
 * 硬截断（finish_reason=length），用户看到的现象就是「对话总是被截断」。
 */
data class ChatOptions(
    val maxTokens: Int = 8192,
    val temperature: Double = 0.6,
    val stream: Boolean = true
)

data class ChatUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/**
 * 一次生成的完整结果。
 *
 * [finishReason] 为 `length` / `max_tokens` 时表示**因 token 上限被截断**，
 * UI 必须据此提供「继续」，而不是把半截答案当完整答案展示。
 */
data class ChatResult(
    val text: String,
    val reasoning: String = "",
    val finishReason: String = "",
    val usage: ChatUsage = ChatUsage()
) {
    val truncatedByLength: Boolean
        get() {
            // OpenAI: length / max_tokens；Anthropic: max_tokens；Gemini: MAX_TOKENS（归一化后统一判断）。
            val normalized = finishReason.uppercase().replace("_", "").replace("-", "")
            return normalized == "LENGTH" || normalized == "MAXTOKENS"
        }
}

interface AiProvider {
    val id: String

    suspend fun chat(config: ProviderConfig, messages: List<ChatMessage>): String

    suspend fun test(config: ProviderConfig): Result<String>

    /** 带生成参数与用量统计的调用；默认回退到简单 chat，保证既有实现零改动可用。 */
    suspend fun chat(config: ProviderConfig, messages: List<ChatMessage>, options: ChatOptions): ChatResult =
        ChatResult(text = chat(config, messages))

    /**
     * 流式调用：每个增量通过 [onDelta] **立即**回调（UI 因此可以逐字显示，而不是等整段生成完），
     * 返回值仍是完整结果（含结束原因与用量）。
     * 默认实现回退为一次性 chat，不支持 SSE 的协议不会因此报错。
     */
    suspend fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        options: ChatOptions,
        onDelta: (String) -> Unit
    ): ChatResult {
        val result = chat(config, messages, options)
        if (result.text.isNotEmpty()) onDelta(result.text)
        return result
    }
}

internal fun JSONObject?.toUsage(): ChatUsage = this?.let {
    ChatUsage(it.optInt("prompt_tokens"), it.optInt("completion_tokens"))
} ?: ChatUsage()

internal fun HttpURLConnection.readOrFail(): String {
    val code = responseCode
    val text = (if (code in 200..299) inputStream else errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code !in 200..299) error("AI HTTP $code: ${text.take(800)}")
    return text
}

/** 非 2xx 响应；单独建模以便区分「可自动降级重试的协议不兼容」与真正的网络错误。 */
internal class HttpStatusException(val status: Int, message: String) : RuntimeException(message)

private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

/** 把额外查询参数拼到 URL 末尾（不与已有 query 冲突）。 */
internal fun String.withExtraQuery(extra: Map<String, String>): String {
    val pairs = extra.entries.filter { it.key.isNotBlank() }
    if (pairs.isEmpty()) return this
    val separator = if (contains('?')) "&" else "?"
    return this + separator + pairs.joinToString("&") { "${urlEncode(it.key.trim())}=${urlEncode(it.value)}" }
}

/**
 * 计算最终对话端点：支持「路径覆盖 / 完整 URL 覆盖 / 默认后缀拼接 / 额外查询参数」四种情况。
 * 这样用户既可以填 `https://relay.com`（自动补 /chat/completions），也可以直接粘贴完整 URL。
 */
internal fun resolveChatUrl(baseUrl: String, defaultSuffix: String, overridePath: String, extraQuery: Map<String, String>): String {
    val base = baseUrl.trim().trimEnd('/')
    val path = overridePath.trim()
    val url = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.isNotEmpty() -> base + if (path.startsWith("/")) path else "/$path"
        defaultSuffix.isEmpty() -> base
        base.isEmpty() -> defaultSuffix
        base.endsWith(defaultSuffix) -> base
        else -> base + defaultSuffix
    }
    return url.withExtraQuery(extraQuery)
}

/**
 * 推算 OpenAI 兼容协议的默认对话路径后缀。
 *
 * 中转/自建网关（one-api、new-api、各类聚合站）几乎都要求 `/v1/chat/completions`，
 * 而用户往往只填 `https://relay.example.com`。旧实现直接拼 `/chat/completions`，
 * 结果固定 404，表现为「小众第三方 API / 中转死活连不上」。
 * 规则：base 只有 host（无路径）→ 补 `/v1/chat/completions`；
 *      已有版本段（/v1、/v2、/v1beta…）→ 只补 `/chat/completions`；
 *      其它自定义路径 → 保持 `/chat/completions`，由 404 回退兜底。
 */
internal fun defaultChatSuffix(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isEmpty()) return "/v1/chat/completions"
    val path = runCatching { URL(base).path.orEmpty() }.getOrDefault("")
    val hasPath = path.split('/').any { it.isNotBlank() }
    // 已有路径（含 /v1、/v2、/compatible-mode/v1 等）说明用户已经写全了前缀，只补 /chat/completions；
    // 光秃秃的域名才需要补 /v1，否则会拼成 /v1/v1/chat/completions 这种必然 404 的地址。
    return if (hasPath) "/chat/completions" else "/v1/chat/completions"
}

/** 注入自定义请求头（鉴权头由各协议另行处理）。 */
internal fun HttpURLConnection.applyExtraHeaders(config: ProviderConfig) {
    config.extraHeaders.forEach { (key, value) -> if (key.isNotBlank()) setRequestProperty(key.trim(), value) }
}

/**
 * 注入鉴权：优先用配置里的自定义头名/前缀，否则回退到协议默认。
 * 允许把前缀设为空串（去 Bearer）或换头名（api-key / x-api-key），覆盖中转网关的常见变体。
 */
internal fun HttpURLConnection.applyAuth(config: ProviderConfig, defaultHeader: String, defaultPrefix: String) {
    val header = config.authHeader.trim().ifBlank { defaultHeader }
    val prefix = config.authPrefix ?: defaultPrefix
    if (header.isNotEmpty() && config.apiKey.isNotBlank()) setRequestProperty(header, prefix + config.apiKey)
    applyExtraHeaders(config)
}

/** OpenAI 兼容（/chat/completions）：支持真流式 SSE、usage 回传与 finish_reason。 */
class OpenAiCompatibleProvider : AiProvider {
    override val id = "openai-compatible"

    private fun endpoint(c: ProviderConfig): String =
        resolveChatUrl(c.baseUrl, defaultChatSuffix(c.baseUrl), c.chatPath, c.extraQuery)

    /**
     * 候选端点：用户显式覆盖 [ProviderConfig.chatPath] 时只认它（不猜）；
     * 否则在 `/v1/chat/completions` 与 `/chat/completions` 之间给出两种可能，
     * 由请求方在 404 时自动回退，兼容各种中转/自建网关的路径习惯。
     */
    private fun candidateEndpoints(c: ProviderConfig): List<String> {
        val primary = endpoint(c)
        if (c.chatPath.isNotBlank()) return listOf(primary)
        val alt = when {
            primary.contains("/v1/chat/completions") -> primary.replaceFirst("/v1/chat/completions", "/chat/completions")
            primary.endsWith("/chat/completions") -> primary.replaceFirst("/chat/completions", "/v1/chat/completions")
            else -> null
        }
        return if (alt != null && alt != primary) listOf(primary, alt) else listOf(primary)
    }

    /**
     * 组装单条消息。有图片时 content 必须是「片段数组」：[{type:text}, {type:image_url}]；
     * 纯文本时保持字符串，兼容所有只认字符串的第三方兼容网关。
     */
    private fun messageJson(message: ChatMessage): JSONObject =
        if (!message.hasImages) {
            JSONObject().put("role", message.role).put("content", message.content)
        } else {
            JSONObject().put("role", message.role).put("content", JSONArray().apply {
                if (message.content.isNotBlank()) put(JSONObject().put("type", "text").put("text", message.content))
                message.images.forEach { image ->
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image.dataUrl)))
                }
            })
        }

    private fun body(c: ProviderConfig, m: List<ChatMessage>, o: ChatOptions, stream: Boolean): String =
        JSONObject()
            .put("model", c.model)
            .put("messages", JSONArray().apply { m.forEach { put(messageJson(it)) } })
            .put("max_tokens", o.maxTokens)
            .put("temperature", o.temperature)
            .put("stream", stream)
            .apply { if (stream && c.sendStreamOptions) put("stream_options", JSONObject().put("include_usage", true)) }
            .toString()

    override suspend fun chat(config: ProviderConfig, messages: List<ChatMessage>): String =
        chat(config, messages, ChatOptions(stream = false)).text

    override suspend fun chat(config: ProviderConfig, messages: List<ChatMessage>, options: ChatOptions): ChatResult =
        withContext(Dispatchers.IO) {
            require(config.enabled && config.apiKey.isNotBlank() && config.model.isNotBlank())
            val urls = candidateEndpoints(config)
            var last: Throwable? = null
            for (url in urls) {
                try {
                    return@withContext performChat(config, url, messages, options)
                } catch (e: HttpStatusException) {
                    // 404 绝大多数是「网关路径前缀猜错了」（例如到底要不要 /v1）：
                    // 用户没显式覆盖路径时自动换一种再试，省掉反复改配置。
                    if (e.status == 404 && url != urls.last()) last = e else throw e
                }
            }
            throw last ?: IllegalStateException("AI 端点不可用")
        }

    /** 单次非流式请求（[url] 已由 [candidateEndpoints] 解析完毕）。 */
    private fun performChat(config: ProviderConfig, url: String, messages: List<ChatMessage>, options: ChatOptions): ChatResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 300_000
        conn.doOutput = true
        conn.applyAuth(config, "Authorization", "Bearer ")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body(config, messages, options, stream = false).toByteArray()) }
        // 非 2xx 统一抛 HttpStatusException，让上层能按状态码（404 路径猜测、400 参数不兼容）自动降级重试。
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw HttpStatusException(code, "AI HTTP $code: ${text.take(800)}")
        val json = JSONObject(text)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        return ChatResult(
            text = message?.optString("content").orEmpty(),
            reasoning = message?.optString("reasoning_content").orEmpty(),
            finishReason = choice?.optString("finish_reason").orEmpty(),
            usage = json.optJSONObject("usage").toUsage()
        )
    }

    override suspend fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        options: ChatOptions,
        onDelta: (String) -> Unit
    ): ChatResult = withContext(Dispatchers.IO) {
        require(config.enabled && config.apiKey.isNotBlank() && config.model.isNotBlank())
        try {
            streamOnce(config, messages, options, onDelta)
        } catch (e: HttpStatusException) {
            // 严格网关 / 自建中转常因不识别 `stream_options` 直接 400。这里自动摘掉该字段重试一次，
            // 让「自定义端点连不上」不再需要用户自己去猜是哪个参数被拒。
            if (config.sendStreamOptions && e.status == 400) {
                streamOnce(config.copy(sendStreamOptions = false), messages, options, onDelta)
            } else {
                throw IllegalStateException(e.message)
            }
        }
    }

    /**
     * 单次流式请求；非 2xx 抛 [HttpStatusException]（此时尚未回调任何增量，重试安全）。
     * 404 会在候选端点间自动重试一次（路径前缀猜错），其余错误原样上抛。
     */
    private fun streamOnce(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        options: ChatOptions,
        onDelta: (String) -> Unit
    ): ChatResult {
        val urls = candidateEndpoints(config)
        var last: HttpStatusException? = null
        for (url in urls) {
            try {
                return performStream(config, url, messages, options, onDelta)
            } catch (e: HttpStatusException) {
                if (e.status == 404 && url != urls.last()) last = e else throw e
            }
        }
        throw last ?: IllegalStateException("AI 端点不可用")
    }

    /** 单次流式请求实现（[url] 已解析）。 */
    private fun performStream(
        config: ProviderConfig,
        url: String,
        messages: List<ChatMessage>,
        options: ChatOptions,
        onDelta: (String) -> Unit
    ): ChatResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        // 流式下 readTimeout 是「两个 chunk 之间」的间隔容忍：长思考/长代码时给足，否则会中途断流。
        conn.readTimeout = 300_000
        conn.doOutput = true
        conn.applyAuth(config, "Authorization", "Bearer ")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.outputStream.use { it.write(body(config, messages, options, stream = true).toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw HttpStatusException(code, "AI HTTP $code: ${err.take(800)}")
        }
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var finish = ""
        var usage = ChatUsage()
        conn.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                // SSE 帧：只认 data: 行，忽略 event:/id:/注释心跳与空行。
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                json.optJSONObject("usage")?.let { usage = it.toUsage() }
                val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: continue
                choice.optString("finish_reason")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let { finish = it }
                val delta = choice.optJSONObject("delta") ?: continue
                // 推理模型（deepseek-reasoner 等）把思考过程放在 reasoning_content，单独收集后折叠展示。
                delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { reasoning.append(it) }
                val chunk = delta.optString("content")
                if (chunk.isNotEmpty()) {
                    text.append(chunk)
                    onDelta(chunk)
                }
            }
        }
        return ChatResult(text.toString(), reasoning.toString(), finish, usage)
    }

    override suspend fun test(config: ProviderConfig): Result<String> =
        runCatching { chat(config, listOf(ChatMessage("user", "回复 OK"))).take(200) }
}
