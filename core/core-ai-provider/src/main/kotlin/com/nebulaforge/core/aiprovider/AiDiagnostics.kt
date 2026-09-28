package com.nebulaforge.core.aiprovider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * AI 服务可用性诊断：余额查询 + 测速。
 *
 * 设计原则（避免伪造数据）：
 *  - 余额接口**不是** OpenAI 兼容协议的一部分，各家自建端点、返回体各不相同，
 *    甚至同一家不同账户类型都可能没有。因此只在**已知服务商**上按公开协议查询；
 *    未识别的端点一律如实返回「服务商未提供余额接口」，绝不臆造数字。
 *  - 测速是通用的：任何能发起一次对话请求的服务都能测出「连通性 + 首字节耗时 + 完整响应耗时」。
 */
data class BalanceReport(
    /** 是否真的拿到了余额（true 才能显示数字；false 时 [display] 是说明文案）。 */
    val supported: Boolean,
    /** 可直接展示的一行文案。 */
    val display: String,
    /** 服务商原始信息的简短摘要（排查用，不含 Key）。 */
    val detail: String = ""
)

/**
 * 测速结果。
 *
 * 三个指标含义明确区分，避免用「总耗时」糊弄首字延迟：
 *  - [firstByteMs]：从请求发出到收到**第一个**增量/首包的时间（含建连，也就是 TTFB）；
 *  - [totalMs]：从请求发出到**完整响应**结束的总时间；
 *  - 两者之差即「生成阶段」耗时，能区分「网络慢」还是「模型思考慢」。
 */
data class SpeedReport(
    val ok: Boolean,
    val firstByteMs: Long,
    val totalMs: Long,
    val error: String = ""
) {
    val summary: String
        get() = if (ok) "首字节 ${firstByteMs}ms · 完整 ${totalMs}ms" else "失败：$error"
}

object AiDiagnostics {

    /** 余额查询的最小超时：余额接口都很轻量，慢于 8s 基本是网络问题。 */
    private const val BALANCE_TIMEOUT_MS = 8_000

    /**
     * 查询余额。
     *
     * 已支持的公开余额端点（按 Base URL 的 host 识别，路径与鉴权头按各家文档）：
     *  - DeepSeek      : GET {origin}/user/balance            → balance_infos[0].total_balance
     *  - Moonshot/Kimi : GET {origin}/v1/users/me/balance     → data.available_balance
     *  - OpenRouter    : GET {origin}/api/v1/credits          → data.total_credits - data.total_usage
     *  - SiliconFlow   : GET {origin}/v1/user/info            → data.balance
     *  - OpenAI 官方    : GET {origin}/v1/dashboard/billing/credit_grants → total_available（多数 Key 已无权限）
     */
    suspend fun balance(config: ProviderConfig): BalanceReport = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext BalanceReport(false, "未填写 API Key，无法查询余额")
        val origin = originOf(config.baseUrl)
            ?: return@withContext BalanceReport(false, "服务地址无效，无法查询余额")
        // 自定义余额端点优先：中转/自建网关常自带 /api/user/self 之类接口，用户填了就直接用。
        val customPath = config.balancePath.trim()
        if (customPath.isNotEmpty()) {
            val url = if (customPath.startsWith("http")) customPath
            else origin.base + (if (customPath.startsWith("/")) customPath else "/$customPath")
            return@withContext runCatching {
                val text = httpGetConfig(url, config)
                val json = runCatching { JSONObject(text) }.getOrNull()
                val value = json?.let { findBalanceValue(it) }
                if (value == null) BalanceReport(false, "未返回可识别余额字段", text.take(200))
                else BalanceReport(true, "余额 $value", "自定义余额接口：$customPath")
            }.getOrElse { err ->
                BalanceReport(false, "余额查询失败：${friendly(err)}", "自定义余额接口：$customPath")
            }
        }
        val spec = balanceSpecFor(origin.host)
            ?: return@withContext BalanceReport(
                false,
                "该服务商未提供通用余额接口",
                "端点 ${origin.host} 无公开余额 API：可正常对话，但无法显示余额"
            )
        runCatching {
            val text = httpGet("${origin.base}${spec.path}", config.apiKey, spec.authHeader, spec.authPrefix)
            val json = JSONObject(text)
            val value = spec.extract(json)
            if (value == null) {
                BalanceReport(false, "余额接口未返回可识别字段", text.take(200))
            } else {
                BalanceReport(true, "余额 ${spec.currency} $value", spec.label)
            }
        }.getOrElse { err ->
            BalanceReport(
                false,
                "余额查询失败：${friendly(err)}",
                "该服务商可能未开放余额接口（对话功能不受影响）"
            )
        }
    }

    /**
     * 通用测速：真实发起一次最小对话请求，测「首字节」与「完整响应」。
     *
     * 复用 [AiProvider.streamChat]：支持 SSE 的协议（OpenAI 兼容）能测出真实首字节；
     * 不支持流式的协议会退化为一次性返回，此时首字节≈完整耗时（如实反映，不伪造）。
     */
    suspend fun speedTest(config: ProviderConfig): SpeedReport = withContext(Dispatchers.IO) {
        val provider = ProviderRegistry().get(config.id) ?: OpenAiCompatibleProvider()
        val probe = config.copy(enabled = true)
        val started = System.currentTimeMillis()
        var firstByte = -1L
        val outcome = runCatching {
            provider.streamChat(
                probe,
                listOf(ChatMessage("user", "ping")),
                ChatOptions(maxTokens = 16, temperature = 0.0, stream = true),
                onDelta = { if (firstByte < 0L) firstByte = System.currentTimeMillis() - started }
            )
        }
        val total = System.currentTimeMillis() - started
        outcome.fold(
            onSuccess = {
                SpeedReport(
                    ok = true,
                    firstByteMs = if (firstByte >= 0L) firstByte else total,
                    totalMs = total
                )
            },
            onFailure = { err ->
                SpeedReport(false, firstByteMs = firstByte.coerceAtLeast(0L), totalMs = total, error = friendly(err))
            }
        )
    }

    /**
     * 从服务商的模型目录读取可用模型。
     *
     * 模型列表不是所有厂商都实现，但 OpenAI 兼容网关和 Gemini 都有稳定的
     * GET /models 约定；响应只返回模型 id，不把原始响应带回 UI，避免意外展示敏感字段。
     */
    suspend fun discoverModels(config: ProviderConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) return@withContext Result.failure(IllegalArgumentException("未填写 API Key"))
        val base = config.baseUrl.trim().trimEnd('/')
        val url = when {
            config.id == "gemini" -> resolveModelsUrl(base, "/models", config.extraQuery)
            else -> resolveModelsUrl(base, "/models", config.extraQuery)
        }
        runCatching {
            val text = httpGetConfig(url, config)
            val json = JSONObject(text)
            val values = mutableListOf<String>()
            val data = json.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) values += id
                }
            }
            val models = json.optJSONArray("models")
            if (models != null) {
                for (i in 0 until models.length()) {
                    val item = models.optJSONObject(i)
                    val raw = item?.optString("name").orEmpty().ifBlank { item?.optString("id").orEmpty() }
                    val id = raw.removePrefix("models/")
                    if (id.isNotBlank()) values += id
                }
            }
            values.distinct().sorted().take(300).also {
                if (it.isEmpty()) error("模型接口未返回可识别的模型")
            }
        }
    }

    private fun resolveModelsUrl(baseUrl: String, suffix: String, query: Map<String, String>): String {
        val url = when {
            baseUrl.endsWith("/v1") || baseUrl.endsWith("/v1beta") -> baseUrl + suffix
            baseUrl.endsWith(suffix) -> baseUrl
            else -> baseUrl + suffix
        }
        return url.withExtraQuery(query)
    }

    // ==== 内部实现 ============================================================

    private data class Origin(val base: String, val host: String)

    private data class BalanceSpec(
        val path: String,
        val authHeader: String,
        val authPrefix: String,
        val currency: String,
        val label: String,
        val extract: (JSONObject) -> String?
    )

    private fun originOf(baseUrl: String): Origin? = runCatching {
        val u = URL(baseUrl.trim().ifBlank { return@runCatching null as Origin? })
        val port = if (u.port > 0) ":${u.port}" else ""
        Origin("${u.protocol}://${u.host}$port", u.host.lowercase())
    }.getOrNull()

    private fun balanceSpecFor(host: String): BalanceSpec? = when {
        host.contains("deepseek.com") -> BalanceSpec(
            path = "/user/balance",
            authHeader = "Authorization", authPrefix = "Bearer ",
            currency = "CNY", label = "DeepSeek 账户余额",
            extract = { json ->
                json.optJSONArray("balance_infos")?.optJSONObject(0)?.optString("total_balance")
                    ?.takeIf { it.isNotBlank() && it != "" }
            }
        )

        host.contains("moonshot.cn") -> BalanceSpec(
            path = "/v1/users/me/balance",
            authHeader = "Authorization", authPrefix = "Bearer ",
            currency = "CNY", label = "Moonshot 账户余额",
            extract = { json ->
                json.optJSONObject("data")?.optDouble("available_balance")
                    ?.takeIf { !it.isNaN() }?.toString()
            }
        )

        host.contains("openrouter.ai") -> BalanceSpec(
            path = "/api/v1/credits",
            authHeader = "Authorization", authPrefix = "Bearer ",
            currency = "USD", label = "OpenRouter 可用额度",
            extract = { json ->
                val d = json.optJSONObject("data") ?: return@BalanceSpec null
                val total = d.optDouble("total_credits", Double.NaN)
                val used = d.optDouble("total_usage", 0.0)
                if (total.isNaN()) null else "%.2f".format(total - used)
            }
        )

        host.contains("siliconflow") -> BalanceSpec(
            path = "/v1/user/info",
            authHeader = "Authorization", authPrefix = "Bearer ",
            currency = "CNY", label = "SiliconFlow 账户余额",
            extract = { json ->
                json.optJSONObject("data")?.optDouble("balance")
                    ?.takeIf { !it.isNaN() }?.toString()
            }
        )

        host == "api.openai.com" -> BalanceSpec(
            path = "/v1/dashboard/billing/credit_grants",
            authHeader = "Authorization", authPrefix = "Bearer ",
            currency = "USD", label = "OpenAI 授信余额",
            extract = { json ->
                val v = json.optDouble("total_available", Double.NaN)
                if (v.isNaN()) null else "%.2f".format(v)
            }
        )

        else -> null
    }

    /** 可能的余额字段名，按优先级排序；只在响应体里如实查找，找不到就返回 null（不臆造）。 */
    private val balanceKeys = listOf(
        "total_balance", "available_balance", "balance", "remain_balance", "remaining_balance",
        "remaining", "remain", "quota", "credits", "available", "credit", "amount", "points"
    )

    /** 在任意层级响应体里查找第一个「看起来是余额」的数值字段。 */
    private fun findBalanceValue(obj: JSONObject, depth: Int = 0): String? {
        if (depth > 4) return null
        for (key in balanceKeys) {
            if (!obj.has(key)) continue
            val num = when (val raw = obj.opt(key)) {
                is Number -> raw.toDouble()
                is String -> raw.toDoubleOrNull()
                else -> null
            }
            if (num != null && !num.isNaN()) {
                return if (num == num.toLong().toDouble()) num.toLong().toString() else "%.2f".format(num)
            }
        }
        for (key in obj.keys()) {
            val child = obj.optJSONObject(key) ?: continue
            findBalanceValue(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** 带完整自定义鉴权/请求头的 GET（供自定义余额端点使用）。 */
    private fun httpGetConfig(url: String, config: ProviderConfig): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = BALANCE_TIMEOUT_MS
        conn.readTimeout = BALANCE_TIMEOUT_MS
        conn.applyAuth(config, "Authorization", "Bearer ")
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code ${body.take(160)}")
        return body
    }

    private fun httpGet(url: String, apiKey: String, header: String, prefix: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = BALANCE_TIMEOUT_MS
        conn.readTimeout = BALANCE_TIMEOUT_MS
        conn.setRequestProperty(header, prefix + apiKey)
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error("HTTP $code ${body.take(160)}")
        return body
    }

    /** 把异常转成对用户友好、且**绝不包含 Key** 的短文案。 */
    private fun friendly(err: Throwable): String {
        val raw = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
        return raw.replace(Regex("sk-[A-Za-z0-9_\\-]{6,}"), "sk-***").take(160)
    }
}
