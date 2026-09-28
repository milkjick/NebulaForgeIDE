package com.nebulaforge.core.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class SessionKind { TERMINAL, TOOLCHAIN, BUILD, BUILD_FIX, DEVICE, RUN, LOGCAT, LSP, EDITOR }

sealed interface SessionState {
    data object Idle : SessionState
    data class Preparing(val message: String) : SessionState
    data class Running(val message: String) : SessionState
    data class Succeeded(val message: String) : SessionState
    data class Failed(val message: String, val exitCode: Int? = null) : SessionState
    data object Cancelled : SessionState
}

sealed interface IdeEvent {
    val sessionId: String
    val timeMs: Long
    data class SessionRegistered(override val sessionId: String, val kind: SessionKind, val projectPath: String?, override val timeMs: Long = System.currentTimeMillis()) : IdeEvent
    data class State(override val sessionId: String, val state: SessionState, override val timeMs: Long = System.currentTimeMillis()) : IdeEvent
    data class Output(override val sessionId: String, val text: String, val stderr: Boolean = false, override val timeMs: Long = System.currentTimeMillis()) : IdeEvent
    data class Diagnostic(override val sessionId: String, val file: String?, val line: Int?, val column: Int?, val message: String, val severity: Severity, override val timeMs: Long = System.currentTimeMillis()) : IdeEvent
    data class Artifact(override val sessionId: String, val path: String, val kind: String, override val timeMs: Long = System.currentTimeMillis()) : IdeEvent
    data class Device(override val sessionId: String, val serial: String, val state: String, override val timeMs: Long = System.currentTimeMillis()) : IdeEvent
    /** Links build/run/logcat sessions without duplicating lifecycle state. */
    data class Relation(override val sessionId: String, val relatedSessionId: String, val relation: String, override val timeMs: Long = System.currentTimeMillis()) : IdeEvent
    /** 文件被 IDE 之外的进程修改，例如 Terminal/Gradle/Git。 */
    data class FileChanged(override val sessionId: String, val path: String, val lastModified: Long, val length: Long, override val timeMs: Long = System.currentTimeMillis()) : IdeEvent
}

enum class Severity { INFO, WARNING, ERROR }

class IdeSession(
    val id: String = UUID.randomUUID().toString(),
    val kind: SessionKind
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state
    fun setState(value: SessionState) { _state.value = value }
}

class IdeSessionBus(private val journal: SessionEventJournal? = null, private val registry: SessionRegistry? = null, private val workspace: WorkspaceStateStore? = null, private val diagnostics: DiagnosticStore? = null) {
    private val _events = MutableSharedFlow<IdeEvent>(extraBufferCapacity = 1024, replay = 32)
    val events: SharedFlow<IdeEvent> = _events

    fun emit(event: IdeEvent) {
        _events.tryEmit(event)
        journal?.append(event)
        when (event) {
            is IdeEvent.Artifact -> workspace?.recordArtifact(event.path)
            is IdeEvent.Diagnostic -> {
                workspace?.addDiagnostic(event)
                event.file?.let { file ->
                    val current = diagnostics?.forFile(file).orEmpty()
                    val duplicate = current.any {
                        it.sessionId == event.sessionId &&
                            it.line == event.line &&
                            it.column == event.column &&
                            it.message == event.message &&
                            it.severity == event.severity
                    }
                    if (!duplicate) diagnostics?.replace(file, current + event)
                }
            }
            is IdeEvent.State -> Unit
            is IdeEvent.FileChanged -> Unit
            else -> Unit
        }
    }

    fun register(session: IdeSession, projectPath: String? = null) {
        registry?.register(session, projectPath)
        emit(IdeEvent.SessionRegistered(session.id, session.kind, projectPath))
    }

    /** Replace one session's diagnostics without disturbing LSP/build diagnostics owned by other sessions. */
    fun replaceDiagnostics(sessionId: String, values: List<IdeEvent.Diagnostic>) {
        diagnostics?.replaceSession(sessionId, values)
        workspace?.replaceDiagnostics(sessionId, values)
        values.forEach { journal?.append(it) }
    }

    fun state(session: IdeSession, value: SessionState, projectPath: String? = null) {
        session.setState(value)
        registry?.update(session, value, projectPath)
        when (session.kind) {
            SessionKind.BUILD -> workspace?.recordBuild(session.id)
            SessionKind.RUN -> workspace?.recordRun(session.id)
            else -> Unit
        }
        emit(IdeEvent.State(session.id, value))
    }
}
