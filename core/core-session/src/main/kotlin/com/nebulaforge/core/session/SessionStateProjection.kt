package com.nebulaforge.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 将事件流投影为轻量的当前会话状态。
 * UI 只订阅这个投影，不主动轮询 Build/Run/Logcat Manager。
 */
data class SessionStateItem(
    val id: String,
    val kind: SessionKind,
    val state: SessionState,
    val projectPath: String? = null,
    val lastOutput: String? = null,
    val artifact: String? = null,
    val device: String? = null,
    val diagnostics: Int = 0,
    val relatedSessionIds: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

class SessionStateProjection(
    bus: IdeSessionBus,
    scope: CoroutineScope,
    journal: SessionEventJournal? = null
) {
    private val values = LinkedHashMap<String, SessionStateItem>()
    private val _items = MutableStateFlow<List<SessionStateItem>>(emptyList())
    val items: StateFlow<List<SessionStateItem>> = _items.asStateFlow()
    init {
        journal?.recent(400)?.forEach { replay(it) }
    }

    private val job: Job = scope.launch {
        bus.events.collect { event -> apply(event) }
    }


    private fun replay(snapshot: IdeEventSnapshot) {
        val o = snapshot.payload
        val event = runCatching {
            when (snapshot.type) {
                "session_registered" -> IdeEvent.SessionRegistered(snapshot.sessionId, runCatching { SessionKind.valueOf(o.optString("kind")) }.getOrDefault(SessionKind.RUN), o.optString("projectPath").takeIf { it.isNotBlank() && it != "null" }, snapshot.timeMs)
                "state" -> IdeEvent.State(snapshot.sessionId, decodeState(o.optJSONObject("state")), snapshot.timeMs)
                "output" -> IdeEvent.Output(snapshot.sessionId, o.optString("text"), o.optBoolean("stderr"), snapshot.timeMs)
                "artifact" -> IdeEvent.Artifact(snapshot.sessionId, o.optString("path"), o.optString("kind"), snapshot.timeMs)
                "device" -> IdeEvent.Device(snapshot.sessionId, o.optString("serial"), o.optString("deviceState"), snapshot.timeMs)
                "relation" -> IdeEvent.Relation(snapshot.sessionId, o.optString("relatedSessionId"), o.optString("relation"), snapshot.timeMs)
                "diagnostic" -> IdeEvent.Diagnostic(snapshot.sessionId, o.optString("file").takeIf { it.isNotBlank() && it != "null" }, if (o.isNull("line")) null else o.optInt("line"), if (o.isNull("column")) null else o.optInt("column"), o.optString("message"), runCatching { Severity.valueOf(o.optString("severity")) }.getOrDefault(Severity.INFO), snapshot.timeMs)
                else -> null
            }
        }.getOrNull()
        if (event != null) apply(event)
    }

    private fun decodeState(o: org.json.JSONObject?): SessionState = when (o?.optString("type")) {
        "preparing" -> SessionState.Preparing(o.optString("message"))
        "running" -> SessionState.Running(o.optString("message"))
        "succeeded" -> SessionState.Succeeded(o.optString("message"))
        "failed" -> SessionState.Failed(o.optString("message"), if (o.isNull("exitCode")) null else o.optInt("exitCode"))
        "cancelled" -> SessionState.Cancelled
        else -> SessionState.Idle
    }

    private fun apply(event: IdeEvent) {
        val old = values[event.sessionId]
        when (event) {
            is IdeEvent.SessionRegistered -> values[event.sessionId] = SessionStateItem(event.sessionId, event.kind, old?.state ?: SessionState.Idle, event.projectPath ?: old?.projectPath, old?.lastOutput, old?.artifact, old?.device, old?.diagnostics ?: 0, old?.relatedSessionIds.orEmpty(), event.timeMs)
            is IdeEvent.State -> values[event.sessionId] = old.copyOrDefault(event.sessionId).copy(state = event.state, updatedAt = event.timeMs)
            is IdeEvent.Output -> values[event.sessionId] = old.copyOrDefault(event.sessionId).copy(lastOutput = event.text, updatedAt = event.timeMs)
            is IdeEvent.Artifact -> values[event.sessionId] = old.copyOrDefault(event.sessionId).copy(artifact = event.path, updatedAt = event.timeMs)
            is IdeEvent.Device -> values[event.sessionId] = old.copyOrDefault(event.sessionId).copy(device = event.serial, updatedAt = event.timeMs)
            is IdeEvent.Diagnostic -> values[event.sessionId] = old.copyOrDefault(event.sessionId).copy(diagnostics = (old?.diagnostics ?: 0) + 1, updatedAt = event.timeMs)
            is IdeEvent.FileChanged -> values[event.sessionId] = old.copyOrDefault(event.sessionId).copy(kind = SessionKind.EDITOR, projectPath = old?.projectPath ?: java.io.File(event.path).parentFile?.absolutePath, updatedAt = event.timeMs)
            is IdeEvent.Relation -> {
                val ids = (old?.relatedSessionIds.orEmpty() + event.relatedSessionId).distinct().takeLast(16)
                values[event.sessionId] = old.copyOrDefault(event.sessionId).copy(relatedSessionIds = ids, updatedAt = event.timeMs)
            }
            else -> return
        }
        _items.value = values.values.sortedByDescending { it.updatedAt }.take(128)
    }

    private fun SessionStateItem?.copyOrDefault(id: String): SessionStateItem = this ?: SessionStateItem(id, SessionKind.RUN, SessionState.Idle)

    fun close() = job.cancel()
}
