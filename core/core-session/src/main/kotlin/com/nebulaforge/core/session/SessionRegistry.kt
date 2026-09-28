package com.nebulaforge.core.session

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent registry for IDE runtime sessions.
 * It persists lifecycle metadata only; OS/PTY processes are never resurrected from disk.
 */
class SessionRegistry(context: Context) {
    private val file = File(context.applicationContext.filesDir, "sessions/registry.json")
    private val _records = MutableStateFlow(load())
    val records: StateFlow<List<SessionRegistryRecord>> = _records.asStateFlow()

    @Synchronized
    fun register(session: IdeSession, projectPath: String? = null) {
        val old = _records.value.firstOrNull { it.id == session.id }
        upsert(SessionRegistryRecord(
            id = session.id,
            kind = session.kind,
            state = session.state.value,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            projectPath = projectPath ?: old?.projectPath
        ))
    }

    @Synchronized
    fun update(session: IdeSession, state: SessionState, projectPath: String? = null) {
        val old = _records.value.firstOrNull { it.id == session.id }
        upsert(SessionRegistryRecord(
            id = session.id,
            kind = session.kind,
            state = state,
            createdAt = old?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            projectPath = projectPath ?: old?.projectPath
        ))
    }

    @Synchronized
    fun markInterruptedAfterRestart() {
        _records.value = _records.value.map {
            if (it.state is SessionState.Running || it.state is SessionState.Preparing) {
                it.copy(state = SessionState.Failed("IDE 重启，原进程已不存在"), updatedAt = System.currentTimeMillis())
            } else it
        }
        persist()
    }

    fun remove(id: String) {
        _records.value = _records.value.filterNot { it.id == id }
        persist()
    }

    private fun upsert(record: SessionRegistryRecord) {
        _records.value = _records.value.filterNot { it.id == record.id } + record
        persist()
    }

    private fun persist() = runCatching {
        file.parentFile?.mkdirs()
        val a = JSONArray()
        _records.value.takeLast(120).forEach { r ->
            a.put(JSONObject().apply {
                put("id", r.id); put("kind", r.kind.name); put("state", encodeState(r.state))
                put("createdAt", r.createdAt); put("updatedAt", r.updatedAt)
                put("projectPath", r.projectPath ?: JSONObject.NULL)
            })
        }
        file.writeText(a.toString())
    }

    private fun load(): List<SessionRegistryRecord> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val a = JSONArray(file.readText())
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val kind = runCatching { SessionKind.valueOf(o.optString("kind")) }.getOrDefault(SessionKind.BUILD)
                add(SessionRegistryRecord(o.getString("id"), kind, decodeState(o.optJSONObject("state")), o.optLong("createdAt"), o.optLong("updatedAt"), o.optString("projectPath").takeIf { it.isNotBlank() }))
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeState(state: SessionState) = JSONObject().apply {
        when (state) {
            SessionState.Idle -> put("type", "idle")
            is SessionState.Preparing -> put("type", "preparing").put("message", state.message)
            is SessionState.Running -> put("type", "running").put("message", state.message)
            is SessionState.Succeeded -> put("type", "succeeded").put("message", state.message)
            is SessionState.Failed -> put("type", "failed").put("message", state.message).put("exitCode", state.exitCode ?: JSONObject.NULL)
            SessionState.Cancelled -> put("type", "cancelled")
        }
    }

    private fun decodeState(o: JSONObject?): SessionState {
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
}

data class SessionRegistryRecord(
    val id: String,
    val kind: SessionKind,
    val state: SessionState,
    val createdAt: Long,
    val updatedAt: Long,
    val projectPath: String?
)
