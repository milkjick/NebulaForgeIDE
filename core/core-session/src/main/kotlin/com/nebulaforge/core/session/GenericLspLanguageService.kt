package com.nebulaforge.core.session

import com.nebulaforge.core.projectmodel.CompletionItem
import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.Diagnostic
import com.nebulaforge.core.projectmodel.LanguageService
import com.nebulaforge.core.projectmodel.Location
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.io.File
import java.net.URI

/** Adapter exposing the real LspClient through the architecture's LanguageService API. */
class GenericLspLanguageService(
    override val languageId: String,
    override val supportedExtensions: List<String>,
    private val command: List<String>,
    private val bus: IdeSessionBus,
    private val diagnosticStore: DiagnosticStore? = null
) : LanguageService {
    private var client: LspClient? = null
    private val root = java.util.concurrent.atomic.AtomicReference<File?>()

    override suspend fun start(projectRoot: File, env: Map<String, String>): Boolean {
        stop()
        root.set(projectRoot)
        val c = LspClient(bus, projectRoot, command, env, diagnosticStore)
        client = c
        return c.start(projectRoot.toURI().toString())
    }

    override suspend fun stop() {
        client?.close()
        client = null
        root.set(null)
    }

    override suspend fun didOpen(file: File, text: String) {
        client?.didOpen(file.toURI().toString(), languageId, text)
    }

    override suspend fun didChange(file: File, version: Int, text: String) {
        client?.didChange(file.toURI().toString(), version, text)
    }

    override suspend fun didClose(file: File) {
        client?.didClose(file.toURI().toString())
    }

    override suspend fun requestCompletion(uri: String, line: Int, column: Int): List<CompletionItem> =
        client?.completion(uri, line, column).orEmpty()

    override suspend fun requestDefinition(uri: String, line: Int, column: Int): List<Location> =
        client?.definition(uri, line, column).orEmpty()

    override fun diagnosticsStream(uri: String): Flow<List<Diagnostic>> =
        client?.diagnostics
            ?.filter { it.first == uri }
            ?.map { (_, list) -> list.map { Diagnostic(it.line, it.column, it.message, it.severity) } }
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
}
