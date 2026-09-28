package com.nebulaforge.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * One-shot source-navigation request shared by Problems/Build and the editor.
 * It carries only a location; it never mutates the source file or owns navigation.
 */
data class ProblemLocation(
    val file: String,
    val line: Int = 0,
    val column: Int = 0,
    val sessionId: String? = null,
    val message: String = ""
)

class ProblemNavigationStore {
    private val _request = MutableStateFlow<ProblemLocation?>(null)
    val request: StateFlow<ProblemLocation?> = _request.asStateFlow()

    fun request(location: ProblemLocation) {
        _request.value = location.copy(file = File(location.file).absolutePath, line = location.line.coerceAtLeast(0), column = location.column.coerceAtLeast(0))
    }

    fun consume() { _request.value = null }
}
