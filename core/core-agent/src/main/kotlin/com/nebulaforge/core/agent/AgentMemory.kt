package com.nebulaforge.core.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 项目级 Agent 记忆：只保存结构化事实、决策、约束和已验证经验，不保存 API Key 等秘密。 */
data class ProjectMemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val projectPath: String,
    val category: String,
    val key: String,
    val content: String,
    val source: String = "agent",
    val confidence: Double = 1.0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AgentExperience(
    val id: String = UUID.randomUUID().toString(),
    val projectPath: String,
    val title: String,
    val problem: String,
    val solution: String,
    val outcome: String,
    val tags: List<String> = emptyList(),
    val verified: Boolean = false,
    val source: String = "agent",
    val createdAt: Long = System.currentTimeMillis()
)

class ProjectMemoryStore(private val context: Context) {
    fun load(projectPath: String): List<ProjectMemoryEntry> = read(projectPath, "memory.json") { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeMemory) }
    }

    fun upsert(entry: ProjectMemoryEntry) {
        val list = load(entry.projectPath).toMutableList()
        val index = list.indexOfFirst { it.key == entry.key && it.category == entry.category }
        if (index >= 0) list[index] = entry.copy(updatedAt = System.currentTimeMillis()) else list += entry
        write(entry.projectPath, "memory.json", JSONArray().apply { list.takeLast(500).forEach { put(encodeMemory(it)) } })
    }

    fun recall(projectPath: String, query: String, limit: Int = 12): List<ProjectMemoryEntry> =
        load(projectPath).sortedByDescending { score(it.key + " " + it.content + " " + it.category, query) }.take(limit)

    fun buildContext(projectPath: String, query: String, limit: Int = 12): String = recall(projectPath, query, limit)
        .joinToString("\n") { "[${it.category}] ${it.key}: ${it.content} (来源=${it.source}, 置信度=${"%.2f".format(it.confidence)})" }

    private fun score(text: String, query: String): Int = query.lowercase().split(Regex("\\s+|[,，。:：]"))
        .filter { it.length >= 2 }.count { text.lowercase().contains(it) }

    private fun file(projectPath: String, name: String): File = File(projectPath, ".nebulaforge/agent-memory/$name")
    private fun <T> read(projectPath: String, name: String, block: (JSONArray) -> T): T = runCatching {
        val f = file(projectPath, name); if (!f.isFile) return@runCatching block(JSONArray())
        block(JSONArray(f.readText()))
    }.getOrElse { block(JSONArray()) }
    private fun write(projectPath: String, name: String, value: JSONArray) {
        val f = file(projectPath, name); f.parentFile?.mkdirs(); f.writeText(value.toString(2))
    }
    private fun encodeMemory(e: ProjectMemoryEntry) = JSONObject().apply {
        put("id", e.id); put("projectPath", e.projectPath); put("category", e.category); put("key", e.key); put("content", e.content)
        put("source", e.source); put("confidence", e.confidence); put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    private fun decodeMemory(j: JSONObject) = ProjectMemoryEntry(j.optString("id", UUID.randomUUID().toString()), j.optString("projectPath"), j.optString("category"), j.optString("key"), j.optString("content"), j.optString("source", "agent"), j.optDouble("confidence", 1.0), j.optLong("createdAt"), j.optLong("updatedAt"))

    /** 删除一条记忆（工作台记忆面板支持「忘记」）。 */
    fun delete(projectPath: String, id: String): Boolean {
        val list = load(projectPath)
        if (list.none { it.id == id }) return false
        write(projectPath, "memory.json", JSONArray().apply { list.filterNot { it.id == id }.forEach { put(encodeMemory(it)) } })
        return true
    }

    /** 清空当前项目记忆（用户显式操作才允许）。 */
    fun clear(projectPath: String): Int {
        val count = load(projectPath).size
        write(projectPath, "memory.json", JSONArray())
        return count
    }
}

class AgentExperienceStore(private val context: Context) {
    fun load(projectPath: String): List<AgentExperience> = runCatching {
        val f = file(projectPath); if (!f.isFile) return@runCatching emptyList()
        val a = JSONArray(f.readText()); (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::decode) }
    }.getOrDefault(emptyList())

    fun add(experience: AgentExperience) {
        val list = load(experience.projectPath).toMutableList()
        val fingerprint = fingerprint(experience.problem + "\n" + experience.solution)
        if (list.any { fingerprint(it.problem + "\n" + it.solution) == fingerprint }) return
        list += experience
        val f = file(experience.projectPath); f.parentFile?.mkdirs()
        f.writeText(JSONArray().apply { list.takeLast(500).forEach { put(encode(it)) } }.toString(2))
    }

    fun recall(projectPath: String, query: String, limit: Int = 8): List<AgentExperience> =
        load(projectPath).sortedByDescending { score(it.problem + " " + it.solution + " " + it.tags.joinToString(" "), query) + if (it.verified) 2 else 0 }.take(limit)

    fun buildContext(projectPath: String, query: String, limit: Int = 8): String = recall(projectPath, query, limit)
        .joinToString("\n") { "[经验] ${it.title}: 问题=${it.problem}; 解决=${it.solution}; 结果=${it.outcome}; 标签=${it.tags.joinToString(",")}; 已验证=${it.verified}" }

    private fun score(text: String, query: String) = query.lowercase().split(Regex("\\s+|[,，。:：]"))
        .filter { it.length >= 2 }.count { text.lowercase().contains(it) }
    private fun file(projectPath: String) = File(projectPath, ".nebulaforge/agent-memory/experiences.json")
    private fun fingerprint(s: String) = MessageDigest.getInstance("SHA-256").digest(s.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
    private fun encode(e: AgentExperience) = JSONObject().apply { put("id", e.id); put("projectPath", e.projectPath); put("title", e.title); put("problem", e.problem); put("solution", e.solution); put("outcome", e.outcome); put("tags", JSONArray(e.tags)); put("verified", e.verified); put("source", e.source); put("createdAt", e.createdAt) }

    /** 删除一条经验（工作台经验面板）。 */
    fun delete(projectPath: String, id: String): Boolean {
        val list = load(projectPath)
        if (list.none { it.id == id }) return false
        persist(projectPath, list.filterNot { it.id == id })
        return true
    }

    /** 标记经验是否已被真实复现验证（验证过的经验在召回时加权更高）。 */
    fun setVerified(projectPath: String, id: String, verified: Boolean): Boolean {
        val list = load(projectPath).toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        list[index] = list[index].copy(verified = verified)
        persist(projectPath, list)
        return true
    }

    private fun persist(projectPath: String, list: List<AgentExperience>) {
        val f = file(projectPath); f.parentFile?.mkdirs()
        f.writeText(JSONArray().apply { list.takeLast(500).forEach { put(encode(it)) } }.toString(2))
    }
    private fun decode(j: JSONObject): AgentExperience {
        val tags = j.optJSONArray("tags") ?: JSONArray()
        return AgentExperience(j.optString("id", UUID.randomUUID().toString()), j.optString("projectPath"), j.optString("title"), j.optString("problem"), j.optString("solution"), j.optString("outcome"), (0 until tags.length()).map { tags.optString(it) }, j.optBoolean("verified"), j.optString("source", "agent"), j.optLong("createdAt"))
    }
}
