package com.nebulaforge.app.ai

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.UUID

/**
 * AI 会话持久化。
 *
 * 旧实现把聊天记录只放在内存（AiChatUiState）里：切页、内存回收、进程重启后历史全丢，
 * 用户会直接理解为「对话被截断/丢了」。
 *
 * 存储布局（应用私有目录）：
 *   - `files/sessions/ai-chat/index.json`        ：会话元信息列表（列表页只读它）
 *   - `files/sessions/ai-chat/<sessionId>.json`  ：单个会话的完整消息
 */
class AiChatStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "sessions/ai-chat")
    private val indexFile = File(dir, "index.json")

    /** 会话列表，按最近更新排序。 */
    fun metas(): List<AiSessionMeta> = runCatching {
        if (!indexFile.exists()) return@runCatching emptyList()
        val array = JSONArray(indexFile.readText())
        buildList { for (i in 0 until array.length()) add(AiSessionMeta.fromJson(array.getJSONObject(i))) }
            .sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    /** 读取单个会话的完整消息；文件不存在或损坏时返回空列表而不是抛错。 */
    fun messages(sessionId: String): List<AiChatMessage> = runCatching {
        val file = File(dir, "$sessionId.json")
        if (!file.exists()) return@runCatching emptyList()
        val root = org.json.JSONObject(file.readText())
        AiChatMessage.listFromJson(root.optJSONArray("messages"))
    }.getOrDefault(emptyList())

    /** 保存（覆盖）一个会话：先写消息文件，再更新索引，避免索引指向不存在的文件。 */
    fun save(meta: AiSessionMeta, messages: List<AiChatMessage>) {
        runCatching {
            dir.mkdirs()
            File(dir, "${meta.id}.json").writeText(
                org.json.JSONObject()
                    .put("meta", meta.toJson())
                    .put("messages", AiChatMessage.listToJson(messages))
                    .toString()
            )
            val next = metas().filterNot { it.id == meta.id } + meta
            indexFile.writeText(JSONArray().apply { next.sortedByDescending { it.updatedAt }.forEach { put(it.toJson()) } }.toString())
        }
    }

    /** 删除会话（连同消息文件）。 */
    fun delete(sessionId: String) {
        runCatching {
            File(dir, "$sessionId.json").delete()
            val next = metas().filterNot { it.id == sessionId }
            indexFile.writeText(JSONArray().apply { next.forEach { put(it.toJson()) } }.toString())
        }
    }

    /** 清空所有会话。 */
    fun clear() {
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    fun newSessionId(): String = UUID.randomUUID().toString()

    /** 用首条用户消息生成标题（超长截断，避免列表被撑爆）。 */
    fun titleFrom(text: String): String {
        val firstLine = text.trim().lineSequence().firstOrNull().orEmpty().trim()
        val title = firstLine.ifBlank { "新会话" }
        return if (title.length > 24) title.take(24) + "…" else title
    }
}
