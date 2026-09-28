package com.nebulaforge.core.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 妙招：一条可复用的「提示词套路 + 建议工具 + 模型参数」。
 *
 * 为什么把它做成数据而不是硬编码：用户的套路是长期资产，需要能新增/编辑/启停/删除，
 * 并且 **AI 自己也能创建**（工作台里让 AI「把这个套路存成妙招」）。
 * 因此 [author] 区分 user / ai / builtin，UI 上标注来源，避免"AI 悄悄改了行为"看不出来。
 */
data class AgentTrick(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val summary: String = "",
    /** 追加到系统提示词的指令正文（不覆盖基础人设，避免妙招把工具/安全规则冲掉）。 */
    val promptPatch: String = "",
    /** 建议启用的工具名；为空表示不额外启用。 */
    val tools: List<String> = emptyList(),
    val author: String = "user",
    val enabled: Boolean = true,
    val useCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val authorLabel: String get() = when (author) {
        "ai" -> "AI 创建"
        "builtin" -> "内置"
        else -> "用户"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("summary", summary).put("promptPatch", promptPatch)
        .put("tools", JSONArray(tools)).put("author", author).put("enabled", enabled)
        .put("useCount", useCount).put("createdAt", createdAt).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): AgentTrick {
            val arr = o.optJSONArray("tools") ?: JSONArray()
            return AgentTrick(
                id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = o.optString("name").ifBlank { "未命名妙招" },
                summary = o.optString("summary"),
                promptPatch = o.optString("promptPatch"),
                tools = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() },
                author = o.optString("author", "user"),
                enabled = o.optBoolean("enabled", true),
                useCount = o.optInt("useCount"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * 妙招持久化（应用私有 SharedPreferences，整体重写；条目数是个位数，无需增量更新）。
 *
 * 首次使用会写入 3 条内置妙招：让用户一进来就有可用范式，而不是面对空列表。
 */
class AgentTrickStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("nebulaforge.agent.tricks", Context.MODE_PRIVATE)

    fun list(): List<AgentTrick> {
        val raw = prefs.getString(KEY, null)
        if (raw.isNullOrBlank()) return seedIfEmpty()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(AgentTrick::fromJson) }
        }.getOrElse { seedIfEmpty() }
    }

    fun enabled(): List<AgentTrick> = list().filter { it.enabled }

    /** 新建或按 id 覆盖；返回落库后的对象（含生成的 id）。 */
    fun upsert(trick: AgentTrick): AgentTrick {
        val list = list().toMutableList()
        val stored = trick.copy(updatedAt = System.currentTimeMillis(), id = trick.id.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
        val index = list.indexOfFirst { it.id == stored.id }
        if (index >= 0) list[index] = stored else list += stored
        save(list)
        return stored
    }

    fun delete(id: String): Boolean {
        val list = list().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) save(list)
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = mutate(id) { it.copy(enabled = enabled) }

    fun markUsed(id: String) { mutate(id) { it.copy(useCount = it.useCount + 1) } }

    private fun mutate(id: String, block: (AgentTrick) -> AgentTrick): Boolean {
        val list = list().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        list[index] = block(list[index]).copy(updatedAt = System.currentTimeMillis())
        save(list)
        return true
    }

    private fun save(list: List<AgentTrick>) {
        prefs.edit().putString(KEY, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    /** 无数据时写入内置妙招（只写一次；用户全删后不再强行塞回）。 */
    private fun seedIfEmpty(): List<AgentTrick> {
        if (prefs.getBoolean(SEEDED, false)) return emptyList()
        val seeds = listOf(
            AgentTrick(
                name = "先读代码再改",
                summary = "动手前先把相关文件读一遍，避免凭想象改代码",
                promptPatch = "改动任何代码前，必须先用 read_file / grep_project 读到真实实现，并在回答里引用文件路径与行号；禁止凭猜测给出「可能的实现」。",
                tools = listOf(AgentToolCatalog.READ_FILE, AgentToolCatalog.GREP_PROJECT),
                author = "builtin"
            ),
            AgentTrick(
                name = "报错先定位根因",
                summary = "贴出报错时，先给出根因链再给补丁",
                promptPatch = "遇到报错时按「症状 → 直接原因 → 根因 → 最小修复 → 验证方式」的顺序回答；不确定时明确说明不确定，并给出验证命令。",
                tools = listOf(AgentToolCatalog.RUN_COMMAND),
                author = "builtin"
            ),
            AgentTrick(
                name = "联网核实",
                summary = "涉及版本/API 变更时先联网核实",
                promptPatch = "涉及 SDK 版本、API 变更、依赖兼容性时，先用 web_search 检索并引用来源；无法联网时明确告知「未联网核实」。",
                tools = listOf(AgentToolCatalog.WEB_SEARCH, AgentToolCatalog.FETCH_PAGE),
                author = "builtin"
            )
        )
        save(seeds)
        prefs.edit().putBoolean(SEEDED, true).apply()
        return seeds
    }

    private companion object {
        const val KEY = "tricks"
        const val SEEDED = "seeded"
    }
}
