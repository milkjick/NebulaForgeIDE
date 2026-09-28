package com.nebulaforge.core.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * AI 服务商协议。决定请求体结构与鉴权头（见 core-ai-provider 中各 Provider 实现）。
 * 需要新增服务商协议时只在此处扩展，并保证 ProviderRegistry 中存在同 id 的实现。
 */
enum class AiProtocol(val id: String, val label: String, val defaultBaseUrl: String, val defaultModel: String) {
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI 兼容", "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1/messages", "claude-3-5-sonnet-latest"),
    GEMINI("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-flash");

    companion object {
        fun fromId(id: String?): AiProtocol = entries.firstOrNull { it.id == id } ?: OPENAI_COMPATIBLE
    }
}

/**
 * 单套 AI 服务配置（profile）。
 *
 * 2.0 变更：从"全局唯一配置"升级为"多套配置 + 当前生效"。
 * 新增 [id]（稳定标识，切换/编辑/删除都以它为准）、[name]（用户可读名称，如"DeepSeek 主力"）、
 * [protocol]（协议类型，不再只支持 OpenAI 兼容）。
 *
 * 兼容性：新增字段都有默认值，历史调用点的 `copy(...)` 写法不受影响；
 * 同时 [AiProviderSettingsStore.load] 仍返回"当前生效配置"，
 * 因此所有既有 Agent/逆向/向量检索代码无需改动即可跟随当前选择。
 */
data class AiProviderSettings(
    val id: String = "default",
    val name: String = "默认配置",
    val protocol: AiProtocol = AiProtocol.OPENAI_COMPATIBLE,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val embeddingModel: String = "text-embedding-3-small",
    val enabled: Boolean = false,
    val autoFixOnBuildFailure: Boolean = false,
    /** 单次回复的最大 token：过去服务端参数被写死 2048，长回答会在中途被硬截断。 */
    val maxTokens: Int = 8192,
    /** 采样温度：写代码偏低（0.6），纯文本问答可适当调高。 */
    val temperature: Double = 0.6,
    /** 模型的上下文窗口（token），用于界面显示「已用 / 上限（占比）」；不同模型差异极大，允许自定义。 */
    val contextWindow: Int = 128000,
    /**
     * 额外请求头：小众第三方 API 与**中转/聚合网关**常要求非标准鉴权头
     * （`api-key` / `x-api-key`）或渠道标记（`X-Channel`、`User-Agent`）。
     */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** 自定义鉴权头名称；空表示用协议默认（OpenAI 兼容=Authorization，Anthropic=x-api-key）。 */
    val authHeader: String = "",
    /** 自定义鉴权值前缀； 表示用协议默认（可显式设为空串以去掉 `Bearer `）。 */
    val authPrefix: String? = null,
    /** 额外查询参数：少数网关用 `?key=` / `?api-version=` 传递凭据或版本。 */
    val extraQuery: Map<String, String> = emptyMap(),
    /** 对话端点路径覆盖（相对 [baseUrl] 或完整 URL）；空表示按协议默认拼接。 */
    val chatPath: String = "",
    /** 流式请求是否发送 `stream_options.include_usage`；严格网关会因未知字段直接 400。 */
    val sendStreamOptions: Boolean = true,
    /** 自定义余额接口路径（相对 baseUrl 或完整 URL）；中转/自建网关常自带宽余额查询。 */
    val balancePath: String = ""
) {
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')

    /** 界面上展示的名称：未命名时退回协议名，避免出现空白条目。 */
    fun displayName(): String = name.trim().ifBlank { protocol.label }

    /** 是否具备发起对话调用的最小条件（供 UI 判断"当前 AI 是否可用"）。 */
    fun isUsable(): Boolean = enabled && apiKey.isNotBlank() && model.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("protocol", protocol.id)
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("embeddingModel", embeddingModel)
        .put("enabled", enabled)
        .put("autoFix", autoFixOnBuildFailure)
        .put("maxTokens", maxTokens)
        .put("temperature", temperature)
        .put("contextWindow", contextWindow)
        .put("extraHeaders", extraHeaders.toJsonObject())
        .put("authHeader", authHeader)
        .apply { if (authPrefix != null) put("authPrefix", authPrefix) }
        .put("extraQuery", extraQuery.toJsonObject())
        .put("chatPath", chatPath)
        .put("sendStreamOptions", sendStreamOptions)
        .put("balancePath", balancePath)

    companion object {
        fun fromJson(o: JSONObject): AiProviderSettings {
            val protocol = AiProtocol.fromId(o.optString("protocol").takeIf { it.isNotBlank() })
            return AiProviderSettings(
                id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = o.optString("name").ifBlank { protocol.label },
                protocol = protocol,
                baseUrl = o.optString("baseUrl").ifBlank { protocol.defaultBaseUrl },
                apiKey = o.optString("apiKey"),
                model = o.optString("model").ifBlank { protocol.defaultModel },
                embeddingModel = o.optString("embeddingModel").ifBlank { "text-embedding-3-small" },
                enabled = o.optBoolean("enabled", false),
                autoFixOnBuildFailure = o.optBoolean("autoFix", false),
                maxTokens = o.optInt("maxTokens", 8192).coerceIn(256, 200_000),
                temperature = o.optDouble("temperature", 0.6).let { if (it.isNaN()) 0.6 else it }.coerceIn(0.0, 2.0),
                contextWindow = o.optInt("contextWindow", 128000).coerceIn(2048, 2_000_000),
                extraHeaders = o.optJSONObject("extraHeaders").toStringMap(),
                authHeader = o.optString("authHeader"),
                authPrefix = if (o.has("authPrefix") && !o.isNull("authPrefix")) o.optString("authPrefix") else null,
                extraQuery = o.optJSONObject("extraQuery").toStringMap(),
                chatPath = o.optString("chatPath"),
                sendStreamOptions = o.optBoolean("sendStreamOptions", true),
                balancePath = o.optString("balancePath")
            )
        }
    }
}

/**
 * AI 多配置持久化。
 *
 * 存储策略（应用私有 SharedPreferences，Key 不写日志、不写项目文件）：
 *   - `profiles`        ：配置列表 JSON 数组（每次整体重写，条目数是个位数，无需增量）
 *   - `active_profile_id`：当前生效配置 id
 *   - 旧扁平键（base_url/api_key/model/...）：继续镜像"当前生效配置"，
 *     供尚未迁移的历史代码与灰度版本读取，保证升级不丢配置（迁移见 [migrateIfNeeded]）。
 */
class AiProviderSettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun profiles(): List<AiProviderSettings> {
        migrateIfNeeded()
        return readProfiles()
    }

    fun activeId(): String {
        migrateIfNeeded()
        val list = readProfiles()
        val saved = prefs.getString(KEY_ACTIVE_ID, null)
        if (saved != null && list.any { it.id == saved }) return saved
        return list.firstOrNull()?.id.orEmpty()
    }

    /** 当前生效配置；没有配置时返回不可用的内存默认值，不写回存储，保证删除最后一套配置真正生效。 */
    fun load(): AiProviderSettings {
        migrateIfNeeded()
        val list = readProfiles()
        if (list.isEmpty()) return AiProviderSettings(enabled = false)
        val active = activeId()
        return list.firstOrNull { it.id == active } ?: list.first()
    }

    /** 保存（按 id upsert）并写入旧扁平键镜像；不改变当前生效项。 */
    fun save(settings: AiProviderSettings) {
        migrateIfNeeded()
        val normalized = settings.normalized()
        val list = readProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == normalized.id }
        if (idx >= 0) list[idx] = normalized else list.add(normalized)
        writeProfiles(list)
        if (activeId() == normalized.id || readProfiles().size == 1) setActive(normalized.id)
        mirrorLegacy(load())
    }

    /** 新增一套配置（自动生成 id），返回新建对象，便于 UI 立刻进入编辑。 */
    fun create(name: String, protocol: AiProtocol): AiProviderSettings {
        migrateIfNeeded()
        val created = AiProviderSettings(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "配置 ${readProfiles().size + 1}" },
            protocol = protocol,
            baseUrl = protocol.defaultBaseUrl,
            model = protocol.defaultModel,
            embeddingModel = if (protocol == AiProtocol.OPENAI_COMPATIBLE) "text-embedding-3-small" else ""
        )
        save(created)
        return created
    }

    /** 复制一套配置（便于在同一服务商下维护两套 key/模型）。 */
    fun duplicate(id: String): AiProviderSettings? {
        migrateIfNeeded()
        val src = readProfiles().firstOrNull { it.id == id } ?: return null
        val copy = src.copy(id = UUID.randomUUID().toString(), name = "${src.displayName()} 副本")
        save(copy)
        return copy
    }

    /** 删除配置；若删除的是当前生效项，则自动切换到列表中第一项。返回删除后仍在生效的配置 id。 */
    fun delete(id: String): String {
        migrateIfNeeded()
        val list = readProfiles().filterNot { it.id == id }.toMutableList()
        val previous = prefs.getString(KEY_ACTIVE_ID, null).orEmpty()
        writeProfiles(list)
        val next = previous.takeIf { it.isNotBlank() && list.any { p -> p.id == it } }
            ?: list.firstOrNull()?.id.orEmpty()
        prefs.edit()
            .putString(KEY_ACTIVE_ID, next)
            .putString("base_url", list.firstOrNull()?.baseUrl.orEmpty())
            .putString("api_key", list.firstOrNull()?.apiKey.orEmpty())
            .putString("model", list.firstOrNull()?.model.orEmpty())
            .putString("embedding_model", list.firstOrNull()?.embeddingModel.orEmpty())
            .putBoolean("enabled", list.firstOrNull()?.enabled ?: false)
            .apply()
        return next
    }

    /** 切换当前生效配置（全局即时生效：所有 Agent 都通过 load() 取当前项）。 */
    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        mirrorLegacy(load())
    }

    private fun AiProviderSettings.normalized(): AiProviderSettings = copy(
        name = displayName(),
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        embeddingModel = embeddingModel.trim(),
        authHeader = authHeader.trim(),
        chatPath = chatPath.trim(),
        balancePath = balancePath.trim(),
        // 丢掉空键名/空键值，避免把坏配置写成实际请求头。
        extraHeaders = extraHeaders.filterKeys { it.isNotBlank() }.mapValues { it.value.trim() },
        extraQuery = extraQuery.filterKeys { it.isNotBlank() }.mapValues { it.value.trim() }
    )

    private fun defaultProfile(): AiProviderSettings = AiProviderSettings(
        id = UUID.randomUUID().toString(),
        name = "默认配置",
        protocol = AiProtocol.OPENAI_COMPATIBLE,
        baseUrl = prefs.getString("base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
        apiKey = prefs.getString("api_key", "") ?: "",
        model = prefs.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini",
        embeddingModel = prefs.getString("embedding_model", "text-embedding-3-small") ?: "text-embedding-3-small",
        enabled = prefs.getBoolean("enabled", false),
        autoFixOnBuildFailure = prefs.getBoolean("auto_fix", false)
    )

    /** 首次升级到多配置：把历史扁平键迁成"默认配置"一条，避免用户重填 Key。 */
    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_PROFILES)) return
        val seeded = defaultProfile()
        writeProfiles(listOf(seeded))
        prefs.edit().putString(KEY_ACTIVE_ID, seeded.id).apply()
        mirrorLegacy(seeded)
    }

    private fun readProfiles(): List<AiProviderSettings> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { AiProviderSettings.fromJson(it) } }
        }.getOrDefault(emptyList())
    }

    private fun writeProfiles(list: List<AiProviderSettings>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    /** 把当前生效配置镜像回旧扁平键，兼容尚未迁移的历史读取路径。 */
    private fun mirrorLegacy(settings: AiProviderSettings) {
        prefs.edit()
            .putString("base_url", settings.baseUrl.trim())
            .putString("api_key", settings.apiKey)
            .putString("model", settings.model.trim())
            .putString("embedding_model", settings.embeddingModel.trim())
            .putBoolean("enabled", settings.enabled)
            .putBoolean("auto_fix", settings.autoFixOnBuildFailure)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "ai_provider"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_ID = "active_profile_id"
    }
}

/**
 * 当前生效 AI 配置的进程内单一真源。
 *
 * 为什么需要它：SharedPreferences 只能持久化，无法主动驱动 Compose 重组；
 * 而"任务面板随时切换并显示当前 AI"要求切换后各处立刻一致。
 * 因此所有写操作（新增/编辑/删除/切换）都通过 [publish] 通知，界面统一 collect [current]。
 */
object ActiveAiProfile {
    private val _current = MutableStateFlow(AiProviderSettings())
    val current: StateFlow<AiProviderSettings> = _current.asStateFlow()

    /** 从持久层重新加载当前生效配置（进入相关界面时调用）。 */
    fun refresh(context: Context) {
        _current.value = AiProviderSettingsStore(context).load()
    }

    /** 切换生效配置：先落盘，再广播。 */
    fun switchTo(context: Context, id: String) {
        val store = AiProviderSettingsStore(context)
        store.setActive(id)
        _current.value = store.load()
    }

    /** 保存编辑结果并广播（编辑的正好是当前生效项时，界面需要立刻更新）。 */
    fun publish(context: Context, settings: AiProviderSettings) {
        val store = AiProviderSettingsStore(context)
        store.save(settings)
        _current.value = store.load()
    }

    /** 删除配置后同步进程内状态；没有配置时保持不可用空状态，不自动补回默认项。 */
    fun remove(context: Context, id: String) {
        val store = AiProviderSettingsStore(context)
        val next = store.delete(id)
        _current.value = store.profiles().firstOrNull { it.id == next }
            ?: AiProviderSettings(enabled = false)
    }

    /** 测试/预览场景直接注入，不落盘。 */
    fun override(value: AiProviderSettings) {
        _current.value = value
    }
}

/**
 * 解析 `Key: Value`（也接受 `Key=Value`）多行文本为映射表。
 * 供 AI 配置界面把「自定义请求头 / 额外查询参数」两个多行输入框转成结构化配置。
 * 空行与 `#` 开头的行会被忽略，便于用户写注释。
 */
fun parseKeyValueLines(text: String): Map<String, String> = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("#") }
    .mapNotNull { line ->
        val idx = line.indexOf(':').takeIf { it > 0 } ?: line.indexOf('=').takeIf { it > 0 }
        idx?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() }
    }
    .filter { it.first.isNotEmpty() }
    .toMap()

/** 把映射表渲染回多行文本（与 [parseKeyValueLines] 互逆，便于界面回填）。 */
fun formatKeyValueLines(map: Map<String, String>): String =
    map.entries.joinToString("\n") { "${it.key}: ${it.value}" }

private fun Map<String, String>.toJsonObject(): JSONObject = JSONObject().apply {
    forEach { (key, value) -> put(key, value) }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    val obj = this ?: return emptyMap()
    return obj.keys().asSequence().associateWith { obj.optString(it) }
}

/** 将持久化 AI 设置转换为统一 ProviderConfig；协议 id 决定 ProviderRegistry 选中的实现。 */
fun AiProviderSettings.toProviderConfig(): com.nebulaforge.core.aiprovider.ProviderConfig =
    com.nebulaforge.core.aiprovider.ProviderConfig(
        id = protocol.id,
        baseUrl = normalizedBaseUrl(),
        apiKey = apiKey,
        model = model,
        enabled = enabled,
        extraHeaders = extraHeaders,
        authHeader = authHeader,
        authPrefix = authPrefix,
        extraQuery = extraQuery,
        chatPath = chatPath,
        sendStreamOptions = sendStreamOptions,
        balancePath = balancePath
    )

/**
 * AI Provider 统一适配层。HTTP 协议实现集中在 core-ai-provider，
 * core-agent 只负责把 IDE 持久化配置转换为统一 ProviderConfig。
 *
 * 协议选择：按 [AiProviderSettings.protocol] 从 [com.nebulaforge.core.aiprovider.ProviderRegistry]
 * 取实现（OpenAI 兼容 / Anthropic / Gemini），未注册时回退 OpenAI 兼容实现。
 */
class OpenAiCompatibleCompletionClient(
    private val settings: AiProviderSettings,
    private val provider: com.nebulaforge.core.aiprovider.AiProvider = com.nebulaforge.core.aiprovider.ProviderRegistry()
        .get(settings.protocol.id)
        ?: com.nebulaforge.core.aiprovider.OpenAiCompatibleProvider()
) : AiCompletionClient {
    override suspend fun complete(systemPrompt: String, userPrompt: String): String {
        val config = settings.toProviderConfig()
        return provider.chat(
            config,
            listOf(
                com.nebulaforge.core.aiprovider.ChatMessage("system", systemPrompt),
                com.nebulaforge.core.aiprovider.ChatMessage("user", userPrompt)
            )
        )
    }

    /**
     * 真流式多轮补全：把 system + 完整历史（含助手自己的回复）交给 Provider，
     * 逐增量回调 UI，并把 finish_reason / usage 原样返回。
     */
    override suspend fun streamCompletion(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        options: com.nebulaforge.core.aiprovider.ChatOptions,
        onDelta: (String) -> Unit
    ): com.nebulaforge.core.aiprovider.ChatResult {
        val config = settings.toProviderConfig()
        val messages = buildList {
            add(com.nebulaforge.core.aiprovider.ChatMessage("system", systemPrompt))
            history.forEach { (role, content) ->
                add(com.nebulaforge.core.aiprovider.ChatMessage(if (role == "assistant") "assistant" else "user", content))
            }
        }
        // 单次生成上限以「本次选项」为准，未显式给出时用配置里的 maxTokens。
        val effective = if (options.maxTokens > 0) options else options.copy(maxTokens = settings.maxTokens)
        return provider.streamChat(config, messages, effective, onDelta)
    }

    /**
     * 带图片（多模态）的流式补全。
     *
     * 图片只挂在**最后一条 user 消息**上：三大协议都要求图片与对应的提问处于同一轮，
     * 挂到历史轮次上不仅浪费 token，还可能被部分网关拒绝。
     */
    suspend fun streamMultimodal(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        images: List<com.nebulaforge.core.aiprovider.ChatImage>,
        options: com.nebulaforge.core.aiprovider.ChatOptions,
        onDelta: (String) -> Unit
    ): com.nebulaforge.core.aiprovider.ChatResult {
        val config = settings.toProviderConfig()
        val lastUserIndex = history.indexOfLast { it.first != "assistant" }
        val messages = buildList {
            add(com.nebulaforge.core.aiprovider.ChatMessage("system", systemPrompt))
            history.forEachIndexed { index, (role, content) ->
                val isAssistant = role == "assistant"
                add(
                    com.nebulaforge.core.aiprovider.ChatMessage(
                        role = if (isAssistant) "assistant" else "user",
                        content = content,
                        images = if (index == lastUserIndex) images else emptyList()
                    )
                )
            }
        }
        val effective = if (options.maxTokens > 0) options else options.copy(maxTokens = settings.maxTokens)
        return provider.streamChat(config, messages, effective, onDelta)
    }
}
