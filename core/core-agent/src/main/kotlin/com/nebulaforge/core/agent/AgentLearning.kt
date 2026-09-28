package com.nebulaforge.core.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ln

/**
 * 可审计的 Agent 持续学习层。
 * 不修改模型权重，而是学习项目事实、成功经验、失败模式、来源可信度和用户反馈。
 */
data class LearningRecord(
    val id: String,
    val projectPath: String,
    val kind: String,
    val query: String,
    val content: String,
    val source: String,
    val verified: Boolean,
    val reward: Double,
    val createdAt: Long = System.currentTimeMillis()
)

data class KnowledgeHit(val text: String, val score: Double, val source: String, val verified: Boolean)

data class LearningStats(val memories: Int, val experiences: Int, val verifiedExperiences: Int, val learningRecords: Int, val failurePatterns: Int, val averageReward: Double, val conversationTurns: Int)

class AgentLearningService(
    private val memoryStore: ProjectMemoryStore,
    private val experienceStore: AgentExperienceStore,
    private val context: Context,
    private val vectorRag: VectorRagService? = null,
    private val failurePatterns: FailurePatternStore = FailurePatternStore(context)
) {
    private val evaluator = AgentLearningEvaluator()
    private val conversations = AgentConversationStore(context)
    private val attributor = FailureAttributor()
    private val records = LearningRecordStore(context)

    suspend fun learnFromResult(projectPath: String, request: String, plan: AgentPlan): AgentExperience? = withContext(Dispatchers.IO) {
        val completed = plan.steps.filter { it.status == AgentPlanStepStatus.COMPLETED }
        if (completed.isEmpty()) return@withContext null
        val evaluation = evaluator.evaluate(plan)
        val success = evaluation.score >= 0.85
        val solution = completed.joinToString("\n") { "${it.title}: ${it.output.take(1800)}" }.take(7000)
        val experience = AgentExperience(
            projectPath = projectPath,
            title = "Agent 任务：${request.take(100)}",
            problem = request.take(1600),
            solution = solution,
            outcome = "计划状态=${plan.status.name}，完成步骤=${completed.size}/${plan.steps.size}",
            tags = plan.steps.map { it.action.name }.distinct().take(16),
            verified = success,
            source = "agent-run"
        )
        experienceStore.add(experience)
        runCatching { vectorRag?.indexExperience(experience, if (success) 1.0 else -0.2) }
        if (!success) {
            val attribution = attributor.attribute(completed.joinToString("\n") { it.output })
            failurePatterns.record(projectPath, "${experience.title} · ${attribution.category}", request, attribution.rootCause, "${attribution.evidence}；$solution", false)
        }
        records.add(LearningRecord(experience.id, projectPath, "experience", request, solution, "agent-run", success, evaluation.score * 2.0 - 1.0))
        conversations.append(projectPath, "user", request)
        conversations.append(projectPath, "agent", "${evaluation.label}：${solution.take(5000)}")
        if (success) {
            val memory = ProjectMemoryEntry(
                projectPath = projectPath,
                category = "verified-experience",
                key = "verified-${fingerprint(request)}",
                content = "${experience.title}；${experience.solution.take(3500)}",
                source = "agent-learning",
                confidence = 0.92
            )
            memoryStore.upsert(memory)
            runCatching { vectorRag?.indexMemory(memory) }
        }
        experience
    }

    fun recordFeedback(projectPath: String, query: String, content: String, positive: Boolean, source: String = "user-feedback") {
        records.add(LearningRecord(
            id = fingerprint(projectPath + query + content + System.currentTimeMillis()),
            projectPath = projectPath,
            kind = "feedback",
            query = query,
            content = content.take(5000),
            source = source,
            verified = positive,
            reward = if (positive) 1.0 else -1.0
        ))
    }

    /** 混合词法 + 反馈 + 验证 + 新鲜度排序，作为轻量本地 RAG，不需要联网。 */
    fun recallKnowledge(projectPath: String, query: String, limit: Int = 16): List<KnowledgeHit> {
        val memories = memoryStore.recall(projectPath, query, 50).map {
            KnowledgeHit("[记忆] ${it.key}: ${it.content}", lexical(it.content, query) + if (it.confidence >= .9) .25 else 0.0, it.source, it.confidence >= .9)
        }
        val experiences = experienceStore.recall(projectPath, query, 50).map {
            KnowledgeHit("[经验] ${it.title}: ${it.problem} -> ${it.solution}; 结果=${it.outcome}", lexical(it.problem + it.solution, query) + if (it.verified) .4 else 0.0, it.source, it.verified)
        }
        val failures = failurePatterns.recall(projectPath, query, 12).map {
            KnowledgeHit("[失败模式] ${it.title}: 症状=${it.symptoms}; 根因=${it.rootCause}; 修复=${it.remediation}; 命中=${it.occurrences}; reward=${"%.2f".format(it.decayedReward())}", lexical(it.symptoms + it.rootCause + it.remediation, query) + it.decayedReward() * .08, "failure-pattern", it.resolved > 0)
        }
        val learned = records.load(projectPath).map {
            KnowledgeHit("[学习记录] ${it.content}", lexical(it.content, query) + it.reward * .15, it.source, it.verified)
        }
        return (memories + experiences + failures + learned).sortedByDescending { it.score }.take(limit)
    }

    suspend fun recallVectorKnowledge(projectPath: String, query: String, limit: Int = 12): List<KnowledgeHit> = runCatching {
        vectorRag?.recall(projectPath, query, limit, true)?.map {
            KnowledgeHit("[向量${if (it.document.projectPath == "*") "/跨项目" else ""}/${it.document.kind}] ${it.document.title}: ${it.document.text}", it.finalScore, it.document.source, it.document.verified)
        }.orEmpty()
    }.getOrDefault(emptyList())

    fun learningStats(projectPath: String): LearningStats {
        val memory = memoryStore.load(projectPath).size
        val experience = experienceStore.load(projectPath).size
        val learning = records.load(projectPath)
        val failures = failurePatterns.load(projectPath)
        val verified = experienceStore.load(projectPath).count { it.verified }
        val averageReward = if (learning.isEmpty()) 0.0 else learning.map { it.reward }.average()
        return LearningStats(memory, experience, verified, learning.size, failures.size, averageReward, conversations.load(projectPath).size)
    }

    fun exportTrainingDataset(projectPath: String): File = records.exportJsonl(projectPath)

    private fun lexical(text: String, query: String): Double {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return 0.0
        val hay = text.lowercase(Locale.ROOT)
        val hits = tokens.count { hay.contains(it) }
        return hits.toDouble() / tokens.size
    }

    private fun tokenize(s: String): List<String> = s.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}_+#.-]+"))
        .filter { it.length >= 2 }.distinct().take(32)

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.trim().lowercase(Locale.ROOT).toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class LearningRecordStore(private val context: Context) {
    fun load(projectPath: String): List<LearningRecord> = runCatching {
        val f = file(projectPath)
        if (!f.isFile) return@runCatching emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).mapNotNull { decode(a.optJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized fun add(record: LearningRecord) {
        val list = load(record.projectPath).toMutableList()
        list += record
        val f = file(record.projectPath)
        f.parentFile?.mkdirs()
        f.writeText(JSONArray().apply { list.takeLast(2000).forEach { put(encode(it)) } }.toString(2))
    }

    fun exportJsonl(projectPath: String): File {
        val out = File(projectPath, ".nebulaforge/agent-memory/training-dataset.jsonl")
        out.parentFile?.mkdirs()
        out.writeText(load(projectPath).joinToString("\n") { r ->
            JSONObject().put("instruction", r.query).put("response", r.content).put("source", r.source).put("verified", r.verified).put("reward", r.reward).toString()
        })
        return out
    }

    private fun file(projectPath: String) = File(projectPath, ".nebulaforge/agent-memory/learning.json")
    private fun encode(r: LearningRecord) = JSONObject().apply {
        put("id", r.id); put("projectPath", r.projectPath); put("kind", r.kind); put("query", r.query); put("content", r.content)
        put("source", r.source); put("verified", r.verified); put("reward", r.reward); put("createdAt", r.createdAt)
    }
    private fun decode(j: JSONObject?) = j?.let {
        LearningRecord(it.optString("id"), it.optString("projectPath"), it.optString("kind"), it.optString("query"), it.optString("content"), it.optString("source"), it.optBoolean("verified"), it.optDouble("reward"), it.optLong("createdAt"))
    }
}
