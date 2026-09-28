package com.nebulaforge.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.io.File

/**
 * Live, derived view of Build/Run/Logcat relationships. It never owns a process;
 * IdeSessionBus remains the single event source. The graph is intentionally small
 * so the mobile UI can render it without reconstructing the event journal.
 */
data class SessionGraphNode(
    val id: String,
    val kind: SessionKind,
    val state: SessionState = SessionState.Idle,
    val projectPath: String? = null,
    val artifact: String? = null,
    val device: String? = null,
    val outputTail: List<String> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SessionGraphLink(val from: String, val to: String, val relation: String)

data class SessionGraphSnapshot(
    val nodes: List<SessionGraphNode> = emptyList(),
    val links: List<SessionGraphLink> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

class SessionGraphStore(
    bus: IdeSessionBus,
    journal: SessionEventJournal? = null,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val nodes = ConcurrentHashMap<String, SessionGraphNode>()
    private val links = ConcurrentHashMap<String, SessionGraphLink>()
    private val _snapshot = MutableStateFlow(SessionGraphSnapshot())
    val snapshot: StateFlow<SessionGraphSnapshot> = _snapshot.asStateFlow()

    init {
        // 进程重启后先从事件日志重建最近的工作流，再订阅实时事件。
        // 这只恢复 UI 状态，不恢复已经死亡的 Gradle/ADB/PTY 进程。
        journal?.recent(400)?.forEach { replay(it) }
    }

    private val collector: Job = scope.launch {
        bus.events.collect { apply(it) }
    }

    private fun replay(snapshot: IdeEventSnapshot) {
        val o = snapshot.payload
        val event = runCatching {
            when (snapshot.type) {
                "session_registered" -> IdeEvent.SessionRegistered(
                    snapshot.sessionId,
                    runCatching { SessionKind.valueOf(o.optString("kind")) }.getOrDefault(SessionKind.RUN),
                    o.optString("projectPath").takeIf { it.isNotBlank() && it != "null" },
                    snapshot.timeMs
                )
                "state" -> IdeEvent.State(snapshot.sessionId, decodeState(o.optJSONObject("state")), snapshot.timeMs)
                "output" -> IdeEvent.Output(snapshot.sessionId, o.optString("text"), o.optBoolean("stderr"), snapshot.timeMs)
                "diagnostic" -> IdeEvent.Diagnostic(snapshot.sessionId, o.optString("file").takeIf { it.isNotBlank() && it != "null" }, if (o.isNull("line")) null else o.optInt("line"), if (o.isNull("column")) null else o.optInt("column"), o.optString("message"), runCatching { Severity.valueOf(o.optString("severity")) }.getOrDefault(Severity.INFO), snapshot.timeMs)
                "artifact" -> IdeEvent.Artifact(snapshot.sessionId, o.optString("path"), o.optString("kind"), snapshot.timeMs)
                "device" -> IdeEvent.Device(snapshot.sessionId, o.optString("serial"), o.optString("deviceState"), snapshot.timeMs)
                "relation" -> IdeEvent.Relation(snapshot.sessionId, o.optString("relatedSessionId"), o.optString("relation"), snapshot.timeMs)
                "file_changed" -> IdeEvent.FileChanged(snapshot.sessionId, o.optString("path"), o.optLong("lastModified"), o.optLong("length"), snapshot.timeMs)
                else -> null
            }
        }.getOrNull()
        if (event != null) apply(event)
    }

    private fun decodeState(o: org.json.JSONObject?): SessionState {
        if (o == null) return SessionState.Idle
        return when (o.optString("type")) {
            "preparing" -> SessionState.Preparing(o.optString("message"))
            "running" -> SessionState.Running(o.optString("message"))
            "succeeded" -> SessionState.Succeeded(o.optString("message"))
            "failed" -> SessionState.Failed(o.optString("message"), if (o.isNull("exitCode")) null else o.optInt("exitCode"))
            "cancelled" -> SessionState.Cancelled
            else -> SessionState.Idle
        }
    }

    @Synchronized
    private fun apply(event: IdeEvent) {
        val old = nodes[event.sessionId]
        val base = old ?: SessionGraphNode(event.sessionId, SessionKind.RUN)
        nodes[event.sessionId] = when (event) {
            is IdeEvent.SessionRegistered -> base.copy(kind = event.kind, projectPath = event.projectPath ?: base.projectPath, updatedAt = event.timeMs)
            is IdeEvent.State -> base.copy(state = event.state, updatedAt = event.timeMs)
            is IdeEvent.Output -> base.copy(outputTail = (base.outputTail + event.text).takeLast(80), updatedAt = event.timeMs)
            is IdeEvent.Artifact -> base.copy(artifact = event.path, updatedAt = event.timeMs)
            is IdeEvent.Device -> base.copy(device = event.serial, updatedAt = event.timeMs)
            is IdeEvent.Diagnostic -> base.copy(outputTail = (base.outputTail + "[${event.severity}] ${event.message}").takeLast(80), updatedAt = event.timeMs)
            is IdeEvent.Relation -> {
                links["${event.sessionId}:${event.relatedSessionId}:${event.relation}"] = SessionGraphLink(event.sessionId, event.relatedSessionId, event.relation)
                base.copy(updatedAt = event.timeMs)
            }
            is IdeEvent.FileChanged -> base.copy(
                kind = SessionKind.EDITOR,
                projectPath = base.projectPath ?: File(event.path).parentFile?.absolutePath,
                updatedAt = event.timeMs
            )
        }
        _snapshot.value = SessionGraphSnapshot(
            nodes.values.sortedByDescending { it.updatedAt }.take(64),
            links.values.toList().take(128),
            event.timeMs
        )
    }
}
