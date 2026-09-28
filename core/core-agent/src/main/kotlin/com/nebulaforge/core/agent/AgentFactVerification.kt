package com.nebulaforge.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.exp

/** 网页事实核验结果：要求保留证据 URL，不把单一搜索摘要直接当作事实。 */
data class WebFactCheck(
    val claim: String,
    val verdict: String,
    val confidence: Double,
    val supportingSources: List<String>,
    val conflictingSources: List<String>,
    val rationale: String,
    val checkedAt: Long = System.currentTimeMillis()
)

class AgentWebFactVerifier(private val client: AiCompletionClient) {
    suspend fun verify(claim: String, evidence: List<WebEvidence>): WebFactCheck = withContext(Dispatchers.IO) {
        require(claim.isNotBlank()) { "待验证事实为空" }
        require(evidence.size >= 2) { "事实核验至少需要两个独立网页证据" }
        val sources = evidence.take(6).joinToString("\n\n") { "URL=${it.url}\nTITLE=${it.title}\nTEXT=${it.text.take(8000)}" }
        val prompt = """
请进行严格的网页事实核验。不要根据常识补全，不要把搜索摘要视为事实。
待验证声明：$claim

网页证据：
$sources

返回严格 JSON：
{"verdict":"SUPPORTED|PARTIAL|CONTRADICTED|INSUFFICIENT","confidence":0.0,"supportingSources":["url"],"conflictingSources":["url"],"rationale":"仅基于给出的网页证据"}
如果来源之间冲突，必须指出冲突；如果证据不足，返回 INSUFFICIENT。
        """.trimIndent()
        val raw = client.complete("你是严谨的事实核验器，只根据提供的来源判断。", prompt)
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val j = JSONObject(clean)
        WebFactCheck(claim, j.optString("verdict", "INSUFFICIENT"), j.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            jsonStrings(j.optJSONArray("supportingSources")), jsonStrings(j.optJSONArray("conflictingSources")), j.optString("rationale"))
    }

    private fun jsonStrings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.optString(i) }.filter(String::isNotBlank) } ?: emptyList()
}

data class FailurePattern(
    val id: String,
    val projectPath: String,
    val signature: String,
    val title: String,
    val symptoms: String,
    val rootCause: String,
    val remediation: String,
    val occurrences: Int = 1,
    val resolved: Int = 0,
    val reward: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun decayedReward(now: Long = System.currentTimeMillis()): Double {
        val days = ((now - updatedAt).coerceAtLeast(0) / 86_400_000.0)
        return reward * exp(-days / 60.0)
    }
}

/** 失败模式库：把构建/Agent 失败从一次性日志变成可检索、可衰减的模式。 */
class FailurePatternStore(private val context: android.content.Context) {
    private fun file(projectPath: String) = File(projectPath, ".nebulaforge/agent-memory/failure-patterns.json")
    fun load(projectPath: String): List<FailurePattern> = runCatching {
        val f = file(projectPath); if (!f.isFile) return@runCatching emptyList()
        val a = JSONArray(f.readText()); (0 until a.length()).mapNotNull { decode(a.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized fun record(projectPath: String, title: String, symptoms: String, rootCause: String, remediation: String, resolved: Boolean): FailurePattern {
        val signature = signature(symptoms + "\n" + rootCause)
        val list = load(projectPath).toMutableList()
        val old = list.firstOrNull { it.signature == signature }
        val item = if (old == null) FailurePattern(signature, projectPath, signature, title, symptoms.take(3000), rootCause.take(3000), remediation.take(5000), 1, if (resolved) 1 else 0, if (resolved) 1.0 else -0.15)
        else old.copy(occurrences = old.occurrences + 1, resolved = old.resolved + if (resolved) 1 else 0, reward = (old.reward + if (resolved) 1.0 else -0.15).coerceIn(-20.0, 20.0), updatedAt = System.currentTimeMillis())
        val idx = list.indexOfFirst { it.signature == signature }; if (idx >= 0) list[idx] = item else list += item
        val f = file(projectPath); f.parentFile?.mkdirs(); f.writeText(JSONArray().apply { list.takeLast(1000).forEach { put(encode(it)) } }.toString(2))
        return item
    }

    fun recall(projectPath: String, query: String, limit: Int = 8): List<FailurePattern> = load(projectPath).sortedByDescending {
        lexical(it.symptoms + " " + it.rootCause + " " + it.remediation, query) + it.decayedReward() * .08 + kotlin.math.ln(1.0 + it.occurrences) * .03
    }.take(limit)

    private fun lexical(text: String, query: String): Double {
        val q = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}_+#.-]+" )).filter { it.length >= 2 }
        val t = text.lowercase(Locale.ROOT); return if (q.isEmpty()) 0.0 else q.count { t.contains(it) }.toDouble() / q.size
    }
    private fun signature(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.trim().lowercase(Locale.ROOT).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun encode(x: FailurePattern) = JSONObject().apply { put("id",x.id);put("projectPath",x.projectPath);put("signature",x.signature);put("title",x.title);put("symptoms",x.symptoms);put("rootCause",x.rootCause);put("remediation",x.remediation);put("occurrences",x.occurrences);put("resolved",x.resolved);put("reward",x.reward);put("createdAt",x.createdAt);put("updatedAt",x.updatedAt) }
    private fun decode(j: JSONObject?) = j?.let { FailurePattern(it.optString("id"),it.optString("projectPath"),it.optString("signature"),it.optString("title"),it.optString("symptoms"),it.optString("rootCause"),it.optString("remediation"),it.optInt("occurrences"),it.optInt("resolved"),it.optDouble("reward"),it.optLong("createdAt"),it.optLong("updatedAt")) }
}
