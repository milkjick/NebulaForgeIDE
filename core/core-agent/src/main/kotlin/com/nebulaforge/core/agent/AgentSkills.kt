package com.nebulaforge.core.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 技能：一个有说明 + 可执行入口脚本的「能力包」。
 *
 * 与妙招的区别：妙招只改提示词（影响模型怎么说），技能带真实脚本（影响模型能做什么）。
 * 目录结构（放在应用私有目录，避免污染项目）：
 *
 *   files/skills/index.json          技能元数据
 *   files/skills/entries/<id>/<entry>  入口脚本（chmod +x）
 *
 * 脚本用 `/bin/sh` 编写：设备上的内嵌用户态与 proot guest 都保证有 sh，
 * 而 bash 在部分精简 rootfs 里并不存在，写死 bash 会让技能在真机上直接跑不起来。
 */
data class AgentSkill(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    /** 技能正文：输入/输出/步骤说明，进入系统提示词，告诉模型这个技能怎么用。 */
    val body: String = "",
    /** 入口脚本文件名（相对该技能的 entries 目录）。 */
    val entry: String = "",
    /** 创建时写入的脚本内容；为空表示只读说明型技能。 */
    val script: String = "",
    val tools: List<String> = emptyList(),
    val author: String = "user",
    val runCount: Int = 0,
    val lastRunAt: Long = 0,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val hasEntry: Boolean get() = entry.isNotBlank()
    val authorLabel: String get() = when (author) {
        "ai" -> "AI 创建"
        "builtin" -> "内置"
        else -> "用户"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("description", description).put("body", body)
        .put("entry", entry).put("tools", JSONArray(tools)).put("author", author)
        .put("runCount", runCount).put("lastRunAt", lastRunAt).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): AgentSkill {
            val arr = o.optJSONArray("tools") ?: JSONArray()
            return AgentSkill(
                id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                name = o.optString("name").ifBlank { "未命名技能" },
                description = o.optString("description"),
                body = o.optString("body"),
                entry = o.optString("entry"),
                script = "",
                tools = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() },
                author = o.optString("author", "user"),
                runCount = o.optInt("runCount"),
                lastRunAt = o.optLong("lastRunAt"),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

class AgentSkillStore(context: Context) {

    private val appContext = context.applicationContext

    val root: File get() = File(appContext.filesDir, "skills").apply { if (!isDirectory) mkdirs() }
    private val index: File get() = File(root, "index.json")

    fun list(): List<AgentSkill> = runCatching {
        if (!index.isFile) return@runCatching emptyList()
        val arr = JSONArray(index.readText())
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(AgentSkill::fromJson) }
    }.getOrDefault(emptyList())

    fun get(idOrName: String): AgentSkill? {
        val key = idOrName.trim()
        return list().firstOrNull { it.id == key } ?: list().firstOrNull { it.name.equals(key, ignoreCase = true) }
    }

    fun create(
        name: String,
        description: String,
        body: String,
        entry: String,
        script: String,
        tools: List<String>,
        author: String = "user"
    ): AgentSkill {
        val skill = AgentSkill(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "未命名技能" },
            description = description.trim(),
            body = body.trim(),
            entry = entry.trim(),
            script = script,
            tools = tools,
            author = author
        )
        if (entry.isNotBlank() && script.isNotBlank()) {
            val dir = entryDir(skill.id)
            dir.mkdirs()
            val f = File(dir, entry)
            f.parentFile?.mkdirs()
            f.writeText(script)
            runCatching { f.setExecutable(true, false) }
        }
        persist(list() + skill)
        return skill
    }

    fun delete(id: String): Boolean {
        val list = list()
        if (list.none { it.id == id }) return false
        persist(list.filterNot { it.id == id })
        runCatching { entryDir(id).deleteRecursively() }
        return true
    }

    fun markRun(id: String) {
        val list = list().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(runCount = list[i].runCount + 1, lastRunAt = System.currentTimeMillis())
        persist(list)
    }

    /** 入口脚本的绝对路径；未创建或不存在时返回 null。 */
    fun entryFile(idOrName: String): File? {
        val skill = get(idOrName) ?: return null
        if (!skill.hasEntry) return null
        val f = File(entryDir(skill.id), skill.entry)
        return if (f.isFile) f else null
    }

    fun entryDir(id: String): File = File(File(root, "entries"), id)

    /** 渲染进系统提示词：让模型知道**有哪些技能可用、怎么调用**。 */
    fun renderForPrompt(): String {
        val skills = list()
        if (skills.isEmpty()) return "（暂无技能）"
        return skills.joinToString("\n") { s ->
            buildString {
                append("- ").append(s.name).append("（id=").append(s.id.take(8)).append("）")
                if (s.description.isNotBlank()) append("：").append(s.description)
                if (s.hasEntry) append("；入口：").append(s.entry).append("（用 run_skill 调用）")
                if (s.body.isNotBlank()) append("\n  说明：").append(s.body.replace("\n", " ").take(400))
            }
        }
    }

    private fun persist(list: List<AgentSkill>) {
        root.mkdirs()
        index.writeText(JSONArray().apply { list.forEach { put(it.toJson()) } }.toString(2))
    }
}
