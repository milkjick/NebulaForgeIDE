package com.nebulaforge.core.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Final F：离线向量能力。
 * 若用户把兼容的本地 token-vector 模型放入 models/local-embedding.json，则优先使用模型；
 * 模型不存在时使用确定性的离线子词编码器，保证 RAG 在断网状态仍可工作，并明确标记为 fallback。
 */
class LocalEmbeddingModel(private val context: Context) {
    data class Status(val available: Boolean, val mode: String, val dimension: Int, val modelPath: String?)
    private data class Model(val dimension: Int, val vectors: Map<String, FloatArray>)
    private val modelFile = File(context.filesDir, "nebulaforge-agent/models/local-embedding.json")
    private val model: Model? by lazy { loadModel() }
    private val dimension = model?.dimension ?: 384

    fun status(): Status = if (model != null) Status(true, "本地 Token-Vector 模型", dimension, modelFile.absolutePath)
    else Status(true, "离线子词向量编码器（无模型依赖）", dimension, null)

    fun encode(text: String): FloatArray {
        val m = model
        if (m != null) {
            val out = FloatArray(m.dimension)
            tokenize(text).forEach { token ->
                m.vectors[token]?.let { v -> for (i in out.indices) out[i] += v.getOrElse(i) { 0f } }
            }
            normalize(out)
            return out
        }
        return fallbackEncode(text)
    }

    private fun fallbackEncode(text: String): FloatArray {
        val out = FloatArray(dimension)
        val normalized = text.lowercase(Locale.ROOT).trim()
        val chars = normalized.toCharArray()
        for (n in 2..5) {
            for (i in 0..(chars.size - n).coerceAtLeast(-1)) {
                val gram = String(chars, i, n)
                val h = stableHash(gram)
                val index = (h and Int.MAX_VALUE) % dimension
                val sign = if ((h ushr 31) and 1 == 0) 1f else -1f
                out[index] += sign * (1f / n)
            }
        }
        normalize(out)
        return out
    }

    private fun tokenize(s: String): List<String> = s.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}_+#.-]+"))
        .filter { it.length >= 2 }.distinct().take(256)

    private fun stableHash(s: String): Int = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .take(4).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }

    private fun normalize(v: FloatArray) {
        var norm = 0.0
        for (x in v) norm += x * x
        val d = sqrt(norm)
        if (d > 0) for (i in v.indices) v[i] = (v[i] / d).toFloat()
    }

    private fun loadModel(): Model? = runCatching {
        if (!modelFile.isFile) return@runCatching null
        val root = JSONObject(modelFile.readText())
        val dim = root.optInt("dimension", 0)
        if (dim !in 32..4096) return@runCatching null
        val obj = root.optJSONObject("vectors") ?: return@runCatching null
        val map = mutableMapOf<String, FloatArray>()
        obj.keys().forEach { token ->
            val a = obj.optJSONArray(token) ?: return@forEach
            if (a.length() == dim) map[token] = FloatArray(dim) { i -> a.optDouble(i).toFloat() }
        }
        if (map.isEmpty()) null else Model(dim, map)
    }.getOrNull()
}

data class LearningEvaluation(
    val score: Double,
    val label: String,
    val reasons: List<String>
)

class AgentLearningEvaluator {
    fun evaluate(plan: AgentPlan): LearningEvaluation {
        val total = plan.steps.size.coerceAtLeast(1)
        val completed = plan.steps.count { it.status == AgentPlanStepStatus.COMPLETED }
        val failed = plan.steps.count { it.status == AgentPlanStepStatus.FAILED }
        val verified = plan.steps.count { it.action == AgentPlanAction.VERIFY_RESULT && it.status == AgentPlanStepStatus.COMPLETED }
        var score = completed.toDouble() / total
        if (plan.status == AgentPlanStatus.COMPLETED) score += .25
        if (failed > 0) score -= failed.toDouble() / total * .35
        if (verified > 0) score += .15
        score = score.coerceIn(0.0, 1.0)
        val label = when { score >= .85 -> "高质量经验"; score >= .6 -> "可复用经验"; else -> "待改进经验" }
        val reasons = buildList {
            add("完成 ${completed}/${total} 步")
            if (plan.status == AgentPlanStatus.COMPLETED) add("计划完整结束")
            if (failed > 0) add("存在 $failed 个失败步骤")
            if (verified > 0) add("包含验证步骤")
        }
        return LearningEvaluation(score, label, reasons)
    }
}

data class AgentConversationTurn(val role: String, val content: String, val timestamp: Long = System.currentTimeMillis())

class AgentConversationStore(private val context: Context) {
    private fun file(projectPath: String) = File(projectPath, ".nebulaforge/agent-memory/conversation.json")
    fun load(projectPath: String): List<AgentConversationTurn> = runCatching {
        val f = file(projectPath); if (!f.isFile) return@runCatching emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { AgentConversationTurn(it.optString("role"), it.optString("content"), it.optLong("timestamp")) } }
    }.getOrDefault(emptyList())
    @Synchronized fun append(projectPath: String, role: String, content: String) {
        val list = (load(projectPath) + AgentConversationTurn(role, content.take(12000))).takeLast(1000)
        val f = file(projectPath); f.parentFile?.mkdirs()
        f.writeText(JSONArray().apply { list.forEach { put(JSONObject().put("role", it.role).put("content", it.content).put("timestamp", it.timestamp)) } }.toString(2))
    }
    fun context(projectPath: String, limit: Int = 12): String = load(projectPath).takeLast(limit).joinToString("\n") { "[${it.role}] ${it.content}" }
}

data class FailureAttribution(val category: String, val rootCause: String, val confidence: Double, val evidence: String)

class FailureAttributor {
    fun attribute(text: String): FailureAttribution {
        val s = text.lowercase(Locale.ROOT)
        return when {
            "sdk.dir" in s || "sdk location" in s -> FailureAttribution("Android SDK", "SDK 路径不存在或未配置", .95, "命中 sdk.dir / SDK location")
            "java_home" in s || "java is not set" in s -> FailureAttribution("JDK", "JAVA_HOME 未配置或 JDK 不可执行", .95, "命中 JAVA_HOME / java")
            "wrapper" in s && ("not found" in s || "download" in s) -> FailureAttribution("Gradle Wrapper", "Wrapper 不可用或 Gradle 分发包无法获取", .9, "命中 Wrapper / download")
            "unknownhostexception" in s || "unable to resolve host" in s || "dns" in s -> FailureAttribution("网络", "DNS 或网络解析失败", .97, "命中网络解析异常")
            "permission denied" in s -> FailureAttribution("文件权限", "工具或运行时缺少执行/读写权限", .93, "命中 Permission denied")
            "compile" in s && ("kotlin" in s || "unresolved reference" in s) -> FailureAttribution("Kotlin 编译", "源码引用、依赖或 Kotlin 编译错误", .78, "命中 Kotlin compile / unresolved reference")
            "adb" in s && ("offline" in s || "device" in s) -> FailureAttribution("ADB 设备", "设备未在线或 ADB 通道异常", .9, "命中 ADB device/offline")
            else -> FailureAttribution("未知", "需要进一步分析日志与上下文", .35, "没有命中已知失败模式")
        }
    }
}

data class WebSourceScore(val host: String, val score: Double, val tier: String, val reasons: List<String>)

class WebSourceCredibility {
    fun score(url: String): WebSourceScore {
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.") }.getOrDefault("")
        val official = listOf("developer.android.com", "kotlinlang.org", "docs.gradle.org", "docs.flutter.dev", "docs.python.org", "developer.apple.com", "developer.mozilla.org", "docs.github.com")
        val government = host.endsWith(".gov") || host.endsWith(".gc.ca")
        val academic = host.endsWith(".edu") || host.endsWith(".ac.uk")
        return when {
            host in official -> WebSourceScore(host, .95, "官方文档", listOf("官方开发者文档域名"))
            government -> WebSourceScore(host, .93, "政府/公共机构", listOf("政府或公共机构域名"))
            academic -> WebSourceScore(host, .9, "学术机构", listOf("学术机构域名"))
            host.endsWith("github.com") -> WebSourceScore(host, .82, "代码托管", listOf("GitHub 代码托管平台"))
            host.isNotBlank() -> WebSourceScore(host, .55, "普通网站", listOf("未识别为高可信官方来源"))
            else -> WebSourceScore("未知", .2, "未知", listOf("URL 无法解析主机名"))
        }
    }
}

data class Citation(val id: Int, val title: String, val url: String, val sourceScore: WebSourceScore)

class RagCitationFormatter {
    fun citations(results: List<WebSearchResult>): List<Citation> = results.mapIndexed { i, r -> Citation(i + 1, r.title, r.url, WebSourceCredibility().score(r.url)) }
    fun append(text: String, citations: List<Citation>): String = if (citations.isEmpty()) text else text + "\n\n来源引用：\n" + citations.joinToString("\n") { "[${it.id}] ${it.title} — ${it.url}（可信度 ${"%.2f".format(it.sourceScore.score)}，${it.sourceScore.tier}）" }
}

class AgentFinalFService(private val context: Context) {
    val localEmbedding = LocalEmbeddingModel(context)
    val evaluator = AgentLearningEvaluator()
    val conversations = AgentConversationStore(context)
    val failureAttributor = FailureAttributor()
    val sourceCredibility = WebSourceCredibility()
    val citations = RagCitationFormatter()
}
