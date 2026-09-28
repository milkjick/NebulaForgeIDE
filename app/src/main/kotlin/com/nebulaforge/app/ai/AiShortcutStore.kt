package com.nebulaforge.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 一条快捷指令（长按消息「转为快捷指令 / 添加到快捷指令列表」存下来的可复用模板）。
 *
 * [body] 就是当时的消息正文：[title] 只是它在输入框上方 chip 行里的短名字。
 */
data class AiShortcut(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val body: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 用过几次（点一次 chip 记一次），用来把常用的排在前面。 */
    val runCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("body", body)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("runCount", runCount)

    companion object {
        fun fromJson(o: JSONObject): AiShortcut = AiShortcut(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = o.optString("title"),
            body = o.optString("body"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            runCount = o.optInt("runCount", 0)
        )
    }
}

/**
 * 快捷指令持久化。
 *
 * 为什么单独一个文件而不是塞进会话里：快捷指令要**跨会话、跨重启**活着
 * （「把这段代码按我的规范重构」这种指令换个会话还是同一句话），
 * 会话是可以被「全部删除」的，指令不该跟着一起没。
 */
class AiShortcutStore(context: Context) {

    private val file = File(
        File(context.applicationContext.filesDir, "sessions/ai-chat"),
        "shortcuts.json"
    )

    /** 全部指令，常用的排前面。 */
    fun all(): List<AiShortcut> = runCatching {
        if (!file.exists()) return emptyList()
        val root = JSONObject(file.readText())
        val array = root.optJSONArray("shortcuts") ?: JSONArray()
        (0 until array.length())
            .mapNotNull { runCatching { AiShortcut.fromJson(array.getJSONObject(it)) }.getOrNull() }
            .filter { it.title.isNotBlank() && it.body.isNotBlank() }
            .sortedWith(compareByDescending<AiShortcut> { it.runCount }.thenByDescending { it.updatedAt })
    }.getOrDefault(emptyList())

    /** 新增或覆盖（同名视为同一条：用户改完再存不该多出一条）。 */
    fun upsert(title: String, body: String): AiShortcut? {
        val cleanTitle = title.trim().ifBlank { suggestTitle(body) }
        val cleanBody = body.trim()
        if (cleanTitle.isBlank() || cleanBody.isBlank()) return null
        val existing = all()
        val sameName = existing.firstOrNull { it.title.equals(cleanTitle, ignoreCase = true) }
        val item = (sameName ?: AiShortcut()).copy(
            title = cleanTitle,
            body = cleanBody,
            updatedAt = System.currentTimeMillis()
        )
        write(existing.filterNot { it.id == item.id } + item)
        return item
    }

    fun remove(id: String) {
        write(all().filterNot { it.id == id })
    }

    /** 点了一次 chip：计数 +1，好让常用指令浮到前面。 */
    fun markRun(id: String) {
        write(all().map { if (it.id == id) it.copy(runCount = it.runCount + 1) else it })
    }

    /** 从正文里猜一个短标题：第一行，去掉多余标记，最多 16 字。 */
    fun suggestTitle(body: String): String {
        val firstLine = body.lineSequence()
            .map { it.trim().trimStart('#', '-', '*', '>', '「', '"') }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return firstLine.take(16).ifBlank { "快捷指令" }
    }

    private fun write(items: List<AiShortcut>) {
        runCatching {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            items.forEach { array.put(it.toJson()) }
            file.writeText(JSONObject().put("shortcuts", array).toString())
        }
    }
}
