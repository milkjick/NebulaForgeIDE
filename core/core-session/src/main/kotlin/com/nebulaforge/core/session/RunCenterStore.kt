package com.nebulaforge.core.session

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Unified Run tool-window state. It is fed exclusively by IdeSessionBus events so
 * Android/Flutter/Web output and lifecycle state have one UI source.
 * Only bounded metadata/output is persisted; processes are never resurrected.
 */
data class RunCenterRecord(
    val id: String,
    val kind: SessionKind,
    val projectPath: String?,
    val state: SessionState = SessionState.Idle,
    val output: List<String> = emptyList(),
    val artifact: String? = null,
    val device: String? = null,
    val buildSessionId: String? = null,
    val logcatSessionId: String? = null,
    val runSessionId: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = startedAt
)

class RunCenterStore(
    context: Context,
    private val bus: IdeSessionBus,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val file = File(context.applicationContext.filesDir, "sessions/run-center.json")
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<RunCenterRecord>> = _records.asStateFlow()
    private var collector: Job = scope.launch {
        bus.events.collect { event -> consume(event) }
    }

    @Synchronized
    private fun consume(event: IdeEvent) {
        val current = _records.value
        val old = current.firstOrNull { it.id == event.sessionId }
        val base = old ?: RunCenterRecord(event.sessionId, SessionKind.RUN, null)
        val next = when (event) {
            is IdeEvent.SessionRegistered -> base.copy(kind = event.kind, projectPath = event.projectPath ?: base.projectPath, updatedAt = event.timeMs)
            is IdeEvent.State -> base.copy(state = event.state, updatedAt = event.timeMs)
            is IdeEvent.Output -> base.copy(output = (base.output + event.text).takeLast(1200), updatedAt = event.timeMs)
            is IdeEvent.Artifact -> base.copy(artifact = event.path, updatedAt = event.timeMs)
            is IdeEvent.Device -> base.copy(device = event.serial, updatedAt = event.timeMs)
            is IdeEvent.Relation -> {
                when (event.relation) {
                    "build" -> {
                        val build = current.firstOrNull { it.id == event.relatedSessionId }
                        base.copy(buildSessionId = event.relatedSessionId, artifact = base.artifact ?: build?.artifact, updatedAt = event.timeMs)
                    }
                    "logcat" -> base.copy(logcatSessionId = event.relatedSessionId, updatedAt = event.timeMs)
                    "run" -> base.copy(runSessionId = event.relatedSessionId, updatedAt = event.timeMs)
                    else -> base.copy(updatedAt = event.timeMs)
                }
            }
            is IdeEvent.Diagnostic -> base.copy(output = (base.output + "[${event.severity}] ${event.message}").takeLast(1200), updatedAt = event.timeMs)
            is IdeEvent.FileChanged -> base.copy(updatedAt = event.timeMs)
        }
        _records.value = (_records.value.filterNot { it.id == next.id } + next).takeLast(32)
        persist()
    }

    fun bindProject(id: String, kind: SessionKind, projectPath: String?) {
        val old = _records.value.firstOrNull { it.id == id }
        val record = (old ?: RunCenterRecord(id, kind, projectPath)).copy(
            kind = kind,
            projectPath = projectPath ?: old?.projectPath,
            updatedAt = System.currentTimeMillis()
        )
        _records.value = (_records.value.filterNot { it.id == id } + record).takeLast(32)
        persist()
    }

    fun clearOutput(id: String) {
        _records.value = _records.value.map { if (it.id == id) it.copy(output = emptyList(), updatedAt = System.currentTimeMillis()) else it }
        persist()
    }

    private fun persist() = runCatching {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        _records.value.forEach { r ->
            a.put(JSONObject().apply {
                put("id", r.id); put("kind", r.kind.name); put("projectPath", r.projectPath ?: JSONObject.NULL)
                put("state", encodeState(r.state)); put("artifact", r.artifact ?: JSONObject.NULL); put("device", r.device ?: JSONObject.NULL)
                put("buildSessionId", r.buildSessionId ?: JSONObject.NULL); put("logcatSessionId", r.logcatSessionId ?: JSONObject.NULL); put("runSessionId", r.runSessionId ?: JSONObject.NULL)
                put("startedAt", r.startedAt); put("updatedAt", r.updatedAt)
                put("output", JSONArray(r.output.takeLast(300)))
            })
        }
        val tmp = File(file.parentFile, "run-center.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
    }

    private fun load(): List<RunCenterRecord> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val a = JSONArray(file.readText())
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val kind = runCatching { SessionKind.valueOf(o.optString("kind")) }.getOrDefault(SessionKind.RUN)
                val out = o.optJSONArray("output")?.let { arr -> buildList { for (j in 0 until arr.length()) add(arr.optString(j)) } } ?: emptyList()
                add(RunCenterRecord(o.optString("id"), kind, o.optString("projectPath").takeIf { it.isNotBlank() }, decodeState(o.optJSONObject("state")), out, o.optString("artifact").takeIf { it.isNotBlank() }, o.optString("device").takeIf { it.isNotBlank() }, o.optString("buildSessionId").takeIf { it.isNotBlank() }, o.optString("logcatSessionId").takeIf { it.isNotBlank() }, o.optString("runSessionId").takeIf { it.isNotBlank() }, o.optLong("startedAt"), o.optLong("updatedAt")))
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeState(s: SessionState) = JSONObject().apply {
        put("type", when (s) { SessionState.Idle -> "idle"; is SessionState.Preparing -> "preparing"; is SessionState.Running -> "running"; is SessionState.Succeeded -> "succeeded"; is SessionState.Failed -> "failed"; SessionState.Cancelled -> "cancelled" })
        when (s) { is SessionState.Preparing -> put("message", s.message); is SessionState.Running -> put("message", s.message); is SessionState.Succeeded -> put("message", s.message); is SessionState.Failed -> put("message", s.message).put("exitCode", s.exitCode ?: JSONObject.NULL); else -> Unit }
    }

    private fun decodeState(o: JSONObject?): SessionState = when (o?.optString("type")) {
        "preparing" -> SessionState.Preparing(o.optString("message")); "running" -> SessionState.Running(o.optString("message")); "succeeded" -> SessionState.Succeeded(o.optString("message")); "failed" -> SessionState.Failed(o.optString("message"), if (o.isNull("exitCode")) null else o.optInt("exitCode")); "cancelled" -> SessionState.Cancelled; else -> SessionState.Idle
    }
}
