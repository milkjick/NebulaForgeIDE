package com.nebulaforge.core.session

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 有界持久事件日志。
 * 事件是恢复 UI/工作流状态的事实来源，但绝不用于伪造恢复已经死亡的进程。
 */
class SessionEventJournal(context: Context) {
    private val file = File(context.applicationContext.filesDir, "sessions/events.jsonl")
    private val maxEvents = 1200
    private val maxBytesBeforeCompaction = 768 * 1024L

    @Synchronized
    fun append(event: IdeEvent) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(encode(event).toString() + "\n")
            if (file.length() > maxBytesBeforeCompaction) compact()
        }
    }

    @Synchronized
    fun recent(limit: Int = 200): List<IdeEventSnapshot> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        file.readLines()
            .filter { it: String -> it.isNotBlank() }
            .takeLast(limit.coerceIn(1, maxEvents))
            .mapNotNull { line: String -> runCatching { decode(JSONObject(line)) }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun compact() {
        if (!file.isFile) return
        val kept = file.readLines().filter { it.isNotBlank() }.takeLast(maxEvents)
        val tmp = File(file.parentFile, "events.jsonl.tmp")
        tmp.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        if (!tmp.renameTo(file)) {
            tmp.delete()
        }
    }

    private fun encode(e: IdeEvent) = JSONObject().apply {
        put("sessionId", e.sessionId)
        put("timeMs", e.timeMs)
        when (e) {
            is IdeEvent.SessionRegistered -> put("type", "session_registered").put("kind", e.kind.name).put("projectPath", e.projectPath ?: JSONObject.NULL)
            is IdeEvent.State -> put("type", "state").put("state", state(e.state))
            is IdeEvent.Output -> put("type", "output").put("text", e.text).put("stderr", e.stderr)
            is IdeEvent.Diagnostic -> put("type", "diagnostic").put("file", e.file ?: JSONObject.NULL).put("line", e.line ?: JSONObject.NULL).put("column", e.column ?: JSONObject.NULL).put("message", e.message).put("severity", e.severity.name)
            is IdeEvent.Artifact -> put("type", "artifact").put("path", e.path).put("kind", e.kind)
            is IdeEvent.Device -> put("type", "device").put("serial", e.serial).put("deviceState", e.state)
            is IdeEvent.Relation -> put("type", "relation").put("relatedSessionId", e.relatedSessionId).put("relation", e.relation)
            is IdeEvent.FileChanged -> put("type", "file_changed").put("path", e.path).put("lastModified", e.lastModified).put("length", e.length)
        }
    }

    private fun state(s: SessionState) = JSONObject().apply {
        put("type", when (s) {
            SessionState.Idle -> "idle"
            is SessionState.Preparing -> "preparing"
            is SessionState.Running -> "running"
            is SessionState.Succeeded -> "succeeded"
            is SessionState.Failed -> "failed"
            SessionState.Cancelled -> "cancelled"
        })
        when (s) {
            is SessionState.Preparing -> put("message", s.message)
            is SessionState.Running -> put("message", s.message)
            is SessionState.Succeeded -> put("message", s.message)
            is SessionState.Failed -> put("message", s.message).put("exitCode", s.exitCode ?: JSONObject.NULL)
            else -> Unit
        }
    }

    private fun decode(o: JSONObject): IdeEventSnapshot = IdeEventSnapshot(
        sessionId = o.optString("sessionId"),
        timeMs = o.optLong("timeMs"),
        type = o.optString("type"),
        payload = o
    ).also { check(it.sessionId.isNotBlank()) }
}

data class IdeEventSnapshot(val sessionId: String, val timeMs: Long, val type: String, val payload: JSONObject)
