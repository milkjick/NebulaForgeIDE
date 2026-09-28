package com.nebulaforge.core.aiprovider

/** 多协议 AI Provider 注册表；上层只依赖 AiProvider，不再绑定单一厂商协议。 */
class ProviderRegistry {
    private val providers = linkedMapOf<String, AiProvider>()

    init {
        register(OpenAiCompatibleProvider())
        register(AnthropicProvider())
        register(GeminiProvider())
    }

    fun register(provider: AiProvider) { providers[provider.id] = provider }
    fun get(id: String): AiProvider? = providers[id]
    fun all(): List<AiProvider> = providers.values.toList()
}

class AnthropicProvider : AiProvider {
    override val id = "anthropic"

    override suspend fun chat(config: ProviderConfig, messages: List<ChatMessage>): String =
        chat(config, messages, ChatOptions()).text

    override suspend fun chat(config: ProviderConfig, messages: List<ChatMessage>, options: ChatOptions): ChatResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(config.enabled && config.apiKey.isNotBlank() && config.model.isNotBlank())
            // 支持中转：可覆盖完整 URL / 相对路径，并可附加额外查询参数。
            val url = resolveChatUrl(
                config.baseUrl.ifBlank { "https://api.anthropic.com/v1/messages" },
                "",
                config.chatPath,
                config.extraQuery
            )
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            // 长答案（代码解释、方案文档）生成时间可能数分钟，读超时不能太短。
            conn.readTimeout = 300_000
            conn.doOutput = true
            conn.applyAuth(config, "x-api-key", "")
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            val body = org.json.JSONObject()
                .put("model", config.model).put("max_tokens", options.maxTokens)
                .put("temperature", options.temperature)
                .put("messages", org.json.JSONArray().apply {
                    messages.forEach { put(anthropicMessage(it)) }
                })
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = org.json.JSONObject(conn.readOrFail())
            ChatResult(
                text = json.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty(),
                finishReason = json.optString("stop_reason"),
                usage = ChatUsage(
                    json.optJSONObject("usage")?.optInt("input_tokens") ?: 0,
                    json.optJSONObject("usage")?.optInt("output_tokens") ?: 0
                )
            )
        }

    override suspend fun test(config: ProviderConfig): Result<String> =
        runCatching { chat(config, listOf(ChatMessage("user", "回复 OK"))).take(200) }

    /**
     * Anthropic 消息格式：content 为 block 数组，图片用 source.base64 内联。
     * 注意 Anthropic 只接受 user 角色的图片块，这里对 assistant 角色只发文本，避免 400。
     */
    private fun anthropicMessage(message: ChatMessage): org.json.JSONObject {
        if (!message.hasImages || message.role == "assistant") {
            return org.json.JSONObject().put("role", if (message.role == "assistant") "assistant" else "user").put("content", message.content)
        }
        val blocks = org.json.JSONArray()
        if (message.content.isNotBlank()) blocks.put(org.json.JSONObject().put("type", "text").put("text", message.content))
        message.images.forEach { image ->
            blocks.put(
                org.json.JSONObject().put("type", "image").put(
                    "source",
                    org.json.JSONObject().put("type", "base64").put("media_type", image.mimeType).put("data", image.base64)
                )
            )
        }
        return org.json.JSONObject().put("role", "user").put("content", blocks)
    }
}

class GeminiProvider : AiProvider {
    override val id = "gemini"

    override suspend fun chat(config: ProviderConfig, messages: List<ChatMessage>): String =
        chat(config, messages, ChatOptions()).text

    override suspend fun chat(config: ProviderConfig, messages: List<ChatMessage>, options: ChatOptions): ChatResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(config.enabled && config.apiKey.isNotBlank() && config.model.isNotBlank())
            val base = config.baseUrl.trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
            // 鉴权默认走 `?key=`；用户自定义鉴权头（如 x-goog-api-key）时改走请求头。
            val url = buildString {
                append(base).append("/models/").append(config.model).append(":generateContent")
                if (config.authHeader.isBlank()) append("?key=").append(java.net.URLEncoder.encode(config.apiKey, "UTF-8"))
            }.withExtraQuery(config.extraQuery)
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"; conn.connectTimeout = 15_000; conn.readTimeout = 300_000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (config.authHeader.isNotBlank()) {
                conn.setRequestProperty(config.authHeader.trim(), (config.authPrefix ?: "") + config.apiKey)
            }
            conn.applyExtraHeaders(config)
            val body = org.json.JSONObject()
                .put("contents", org.json.JSONArray().apply {
                    messages.forEach { put(geminiMessage(it)) }
                })
                .put("generationConfig", org.json.JSONObject()
                    .put("maxOutputTokens", options.maxTokens)
                    .put("temperature", options.temperature))
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val json = org.json.JSONObject(conn.readOrFail())
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ChatResult(
                text = candidate?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty(),
                finishReason = candidate?.optString("finishReason").orEmpty(),
                usage = ChatUsage(
                    json.optJSONObject("usageMetadata")?.optInt("promptTokenCount") ?: 0,
                    json.optJSONObject("usageMetadata")?.optInt("candidatesTokenCount") ?: 0
                )
            )
        }

    override suspend fun test(config: ProviderConfig): Result<String> =
        runCatching { chat(config, listOf(ChatMessage("user", "回复 OK"))).take(200) }

    /** Gemini 消息格式：parts 数组，图片走 inline_data（mime_type + base64）。 */
    private fun geminiMessage(message: ChatMessage): org.json.JSONObject {
        val parts = org.json.JSONArray()
        if (message.content.isNotBlank()) parts.put(org.json.JSONObject().put("text", message.content))
        message.images.forEach { image ->
            parts.put(
                org.json.JSONObject().put(
                    "inline_data",
                    org.json.JSONObject().put("mime_type", image.mimeType).put("data", image.base64)
                )
            )
        }
        if (parts.length() == 0) parts.put(org.json.JSONObject().put("text", ""))
        return org.json.JSONObject().put("role", if (message.role == "assistant") "model" else "user").put("parts", parts)
    }
}
