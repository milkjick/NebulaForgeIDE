package com.nebulaforge.core.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sqrt

/** OpenAI-compatible Embeddings 客户端。真正的向量来自 /embeddings，不用词频伪装成向量。 */
class EmbeddingClient(private val settings: AiProviderSettings) {
    suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        // 嵌入走 OpenAI 兼容的 /embeddings 端点；Anthropic/Gemini 需各自不同的向量接口，
        // 这里显式拒绝并给出可操作提示，而不是发出必然失败的请求。
        require(settings.protocol == AiProtocol.OPENAI_COMPATIBLE) {
            "当前 AI 配置协议为「${settings.protocol.label}」，不支持 /embeddings；请将嵌入模型所需配置切到 OpenAI 兼容协议。"
        }
        require(settings.enabled && settings.apiKey.isNotBlank()) { "AI Provider 未启用或 API Key 为空，无法生成 Embedding" }
        if (texts.isEmpty()) return@withContext emptyList()
        val body = JSONObject().put("model", settings.embeddingModel).put("input", JSONArray(texts)).toString()
        val url = URL(settings.normalizedBaseUrl().trimEnd('/') + "/embeddings")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
            setRequestProperty("Authorization", "Bearer ${settings.apiKey}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val response = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(c.responseCode in 200..299) { "Embedding HTTP ${c.responseCode}: ${response.take(500)}" }
            val data = JSONObject(response).optJSONArray("data") ?: error("Embedding 响应缺少 data")
            val out = (0 until data.length()).map { i ->
                val arr = data.getJSONObject(i).optJSONArray("embedding") ?: error("Embedding 缺少向量")
                FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
            }
            require(out.size == texts.size) { "Embedding 数量不一致：${out.size}/${texts.size}" }
            out
        } finally { c.disconnect() }
    }
}

data class VectorDocument(
    val id: String,
    val projectPath: String,
    val kind: String,
    val title: String,
    val text: String,
    val source: String,
    val verified: Boolean,
    val reward: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val vector: FloatArray
)

data class VectorHit(
    val document: VectorDocument,
    val similarity: Double,
    val finalScore: Double
)

/** 持久化本地向量索引；按项目和全局经验库分层，支持余弦相似度 + 新鲜度 + 验证 + reward。 */
class VectorKnowledgeStore(private val context: Context) {
    private fun projectFile(projectPath: String) = File(projectPath, ".nebulaforge/agent-memory/vector-index.json")
    private fun globalFile() = File(context.filesDir, "nebulaforge-agent/global-experience-vectors.json")

    @Synchronized fun upsertProject(doc: VectorDocument) = upsert(projectFile(doc.projectPath), doc)
    @Synchronized fun upsertGlobal(doc: VectorDocument) = upsert(globalFile(), doc.copy(projectPath = "*"))

    fun loadProject(projectPath: String): List<VectorDocument> = load(projectFile(projectPath))
    fun loadGlobal(): List<VectorDocument> = load(globalFile())

    fun search(projectPath: String, queryVector: FloatArray, limit: Int = 16, crossProject: Boolean = true): List<VectorHit> {
        val now = System.currentTimeMillis()
        val docs = (loadProject(projectPath) + if (crossProject) loadGlobal() else emptyList())
        return docs.map { doc ->
            val cosine = cosine(queryVector, doc.vector)
            val ageDays = ((now - doc.updatedAt).coerceAtLeast(0) / 86_400_000.0)
            val decay = exp(-ageDays / 45.0)
            val verificationBoost = if (doc.verified) 0.18 else 0.0
            val rewardBoost = (doc.reward.coerceIn(-1.0, 1.0) * 0.12)
            val finalScore = cosine * 0.72 + decay * 0.08 + verificationBoost + rewardBoost
            VectorHit(doc, cosine, finalScore)
        }.sortedByDescending { it.finalScore }.take(limit)
    }

    private fun upsert(file: File, doc: VectorDocument) {
        val list = load(file).toMutableList()
        val i = list.indexOfFirst { it.id == doc.id }
        if (i >= 0) list[i] = doc.copy(updatedAt = System.currentTimeMillis()) else list += doc
        file.parentFile?.mkdirs()
        file.writeText(JSONArray().apply { list.takeLast(3000).forEach { put(encode(it)) } }.toString(2))
    }

    private fun load(file: File): List<VectorDocument> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { decode(a.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun encode(d: VectorDocument) = JSONObject().apply {
        put("id", d.id); put("projectPath", d.projectPath); put("kind", d.kind); put("title", d.title); put("text", d.text)
        put("source", d.source); put("verified", d.verified); put("reward", d.reward); put("createdAt", d.createdAt); put("updatedAt", d.updatedAt)
        put("vector", JSONArray().apply { d.vector.forEach { put(it.toDouble()) } })
    }
    private fun decode(j: JSONObject?): VectorDocument? = j?.let {
        val a = it.optJSONArray("vector") ?: return@let null
        VectorDocument(it.optString("id"), it.optString("projectPath"), it.optString("kind"), it.optString("title"), it.optString("text"),
            it.optString("source"), it.optBoolean("verified"), it.optDouble("reward"), it.optLong("createdAt"), it.optLong("updatedAt"),
            FloatArray(a.length()) { i -> a.optDouble(i).toFloat() })
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { val x = a[i].toDouble(); val y = b[i].toDouble(); dot += x * y; na += x * x; nb += y * y }
        return if (na == 0.0 || nb == 0.0) 0.0 else (dot / (sqrt(na) * sqrt(nb))).coerceIn(-1.0, 1.0)
    }
}

class VectorRagService(
    private val context: Context,
    private val embeddingClient: EmbeddingClient,
    private val store: VectorKnowledgeStore,
    private val localEmbedding: LocalEmbeddingModel = LocalEmbeddingModel(context)
) {
    private suspend fun embed(text: String): FloatArray = runCatching { embeddingClient.embed(listOf(text))[0] }.getOrElse { localEmbedding.encode(text) }
    suspend fun indexExperience(experience: AgentExperience, reward: Double = if (experience.verified) 1.0 else -0.1) {
        val vector = embed("${experience.title}\n${experience.problem}\n${experience.solution}\n${experience.tags.joinToString(" ")}")
        val doc = VectorDocument(experience.id, experience.projectPath, "experience", experience.title,
            "问题：${experience.problem}\n解决：${experience.solution}\n结果：${experience.outcome}\n标签：${experience.tags.joinToString(",")}",
            experience.source, experience.verified, reward, experience.createdAt, System.currentTimeMillis(), vector)
        store.upsertProject(doc)
        store.upsertGlobal(doc.copy(projectPath = "*", title = "跨项目/${File(experience.projectPath).name}/${experience.title}", text = "来源项目：${experience.projectPath}\n${doc.text}"))
    }

    suspend fun indexMemory(entry: ProjectMemoryEntry) {
        val vector = embed("${entry.category}\n${entry.key}\n${entry.content}")
        store.upsertProject(VectorDocument(entry.id, entry.projectPath, "memory", entry.key, entry.content, entry.source,
            entry.confidence >= .9, entry.confidence - 0.5, entry.createdAt, entry.updatedAt, vector))
    }

    suspend fun recall(projectPath: String, query: String, limit: Int = 12, crossProject: Boolean = true): List<VectorHit> {
        val vector = embed(query)
        return store.search(projectPath, vector, limit, crossProject)
    }

    fun fallbackFingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.trim().lowercase(Locale.ROOT).toByteArray()).joinToString("") { "%02x".format(it) }
}
