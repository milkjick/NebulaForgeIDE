package com.nebulaforge.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/** Single diagnostic source. Diagnostics are partitioned by session so a new Build cannot inherit stale errors. */
class DiagnosticStore {
    private val byFile = ConcurrentHashMap<String, List<IdeEvent.Diagnostic>>()
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    @Synchronized fun replace(file: String, diagnostics: List<IdeEvent.Diagnostic>) {
        if (diagnostics.isEmpty()) byFile.remove(file) else byFile[file] = diagnostics
        bump()
    }

    /** Atomically replaces every diagnostic owned by one session, preserving LSP diagnostics from other sessions. */
    @Synchronized fun replaceSession(sessionId: String, diagnostics: List<IdeEvent.Diagnostic>) {
        val cleaned = byFile.mapValues { (_, list) -> list.filterNot { it.sessionId == sessionId } }.filterValues { it.isNotEmpty() }.toMutableMap()
        diagnostics.groupBy { it.file ?: "" }.forEach { (file, list) ->
            if (file.isNotBlank()) cleaned[file] = (cleaned[file].orEmpty() + list)
        }
        byFile.clear()
        byFile.putAll(cleaned)
        bump()
    }

    @Synchronized fun clear(file: String? = null) {
        if (file == null) byFile.clear() else byFile.remove(file)
        bump()
    }

    @Synchronized fun clearSession(sessionId: String) {
        val cleaned = byFile.mapValues { (_, list) -> list.filterNot { it.sessionId == sessionId } }.filterValues { it.isNotEmpty() }
        byFile.clear(); byFile.putAll(cleaned); bump()
    }

    fun all(): List<IdeEvent.Diagnostic> = byFile.values.flatten().sortedWith(compareBy({ it.file ?: "" }, { it.line ?: Int.MAX_VALUE }, { it.column ?: Int.MAX_VALUE }, { it.timeMs }))
    fun forFile(file: String): List<IdeEvent.Diagnostic> = byFile[file].orEmpty()
    fun forSession(sessionId: String): List<IdeEvent.Diagnostic> = byFile.values.flatten().filter { it.sessionId == sessionId }.sortedWith(compareBy({ it.file ?: "" }, { it.line ?: Int.MAX_VALUE }, { it.column ?: Int.MAX_VALUE }, { it.timeMs }))
    fun errorForSession(sessionId: String): List<IdeEvent.Diagnostic> = forSession(sessionId).filter { it.severity == Severity.ERROR }
    fun latestSessionWithErrors(): String? = byFile.values.flatten().filter { it.severity == Severity.ERROR }.maxByOrNull { it.timeMs }?.sessionId

    private fun bump() { _version.value = _version.value + 1 }
}
