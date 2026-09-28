package com.nebulaforge.core.session

import com.nebulaforge.core.pty.NativePty
import com.nebulaforge.core.pty.PtyShellArgs
import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.CompletionItem
import com.nebulaforge.core.projectmodel.Diagnostic
import com.nebulaforge.core.projectmodel.Location
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real LSP JSON-RPC client for the embedded Way-B runtime.
 *
 * The language server is fork/exec'ed by the native PTY layer instead of
 * Android ProcessBuilder. The PTY is prepared with stty -echo and the shell
 * is replaced with the requested language-server process, so JSON-RPC frames
 * are not intentionally mixed with a shell prompt.
 */
class LspClient(
    private val bus: IdeSessionBus,
    private val workingDir: File,
    private val command: List<String>,
    private val environment: Map<String, String>,
    private val diagnosticStore: DiagnosticStore? = null
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val _diagnostics = MutableSharedFlow<Pair<String, List<Diagnostic>>>(extraBufferCapacity = 64)
    val diagnostics: SharedFlow<Pair<String, List<Diagnostic>>> = _diagnostics
    private val session = IdeSession(kind = SessionKind.LSP)
    private var handle = 0L
    private var initialized = false

    suspend fun start(rootUri: String): Boolean {
        if (command.isEmpty() || !workingDir.exists()) return false
        return try {
            val env = environment.toMutableMap().apply {
                put("TERM", "dumb")
                put("LC_ALL", get("LC_ALL") ?: "C.UTF-8")
            }
            val envArray = env.map { "${it.key}=${it.value}" }.toTypedArray()
            // 传显式 argv（内部会加 --norc --noprofile）：否则外层 bash 以交互式启动，
            // 会去 source 不可达的前缀 bash.bashrc，LSP 日志首行永远是 Permission denied 噪声。
            val lspShell = env["SHELL"] ?: "/system/bin/sh"
            handle = NativePty.nativeOpen(
                lspShell, 40, 160, PtyShellArgs.forShell(lspShell), envArray
            )
            if (handle == 0L) error("无法创建 LSP PTY")
            bus.state(session, SessionState.Running("LSP: ${command.first()}"))
            scope.launch { readLoop() }
            // NativePty currently starts an interactive shell. Disable terminal echo,
            // enter the project, then replace the shell with the LSP executable.
            write(buildLaunchScript(workingDir, command))
            val init = request("initialize", JSONObject().apply {
                put("processId", android.os.Process.myPid())
                put("rootUri", rootUri)
                put("workspaceFolders", JSONArray().put(JSONObject().put("uri", rootUri).put("name", workingDir.name)))
                put("capabilities", JSONObject().apply {
                    put("textDocument", JSONObject().apply {
                        put("completion", JSONObject().put("completionItem", JSONObject().put("snippetSupport", true)))
                        put("definition", JSONObject())
                    })
                })
                put("clientInfo", JSONObject().put("name", "NebulaForge IDE").put("version", "1.6.0"))
            }, timeoutMs = 20_000)
            initialized = init != null
            if (initialized) sendNotification("initialized", JSONObject())
            initialized
        } catch (t: Throwable) {
            bus.state(session, SessionState.Failed("LSP 启动失败：${t.message}"))
            close()
            false
        }
    }

    suspend fun didOpen(uri: String, languageId: String, text: String) {
        if (!initialized) return
        sendNotification("textDocument/didOpen", JSONObject().put("textDocument", JSONObject()
            .put("uri", uri).put("languageId", languageId).put("version", 1).put("text", text)))
    }

    suspend fun didClose(uri: String) {
        if (!initialized) return
        sendNotification("textDocument/didClose", JSONObject().put("textDocument", JSONObject().put("uri", uri)))
    }

    suspend fun didChange(uri: String, version: Int, text: String) {
        if (!initialized) return
        sendNotification("textDocument/didChange", JSONObject().put("textDocument", JSONObject()
            .put("uri", uri).put("version", version))
            .put("contentChanges", JSONArray().put(JSONObject().put("text", text))))
    }

    suspend fun completion(uri: String, line: Int, column: Int): List<CompletionItem> {
        val result = request("textDocument/completion", positionParams(uri, line, column)) ?: return emptyList()
        val items = when {
            result.has("items") -> result.optJSONArray("items")
            result.has("result") && result.optJSONObject("result")?.has("items") == true -> result.optJSONObject("result")?.optJSONArray("items")
            else -> null
        } ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // 现代 LSP 服务端（jdtls / kotlin-language-server / clangd）普遍只填 `textEdit.newText`
                // 或 `insertText`，旧版才用 `insertText` 裸文本；三者都要兼容，否则插入的候选是空的。
                val editText = item.optJSONObject("textEdit")?.optString("newText").orEmpty()
                val insert = editText.ifBlank { item.optString("insertText").ifBlank { item.optString("label") } }
                val docs = renderDocumentation(item.opt("documentation"))
                val deprecated = item.optBoolean("deprecated", false) ||
                    item.optJSONArray("tags")?.let { tags ->
                        (0 until tags.length()).any { tags.optInt(it) == 1 }
                    } == true
                add(
                    CompletionItem(
                        label = item.optString("label"),
                        insertText = insert,
                        kind = item.optInt("kind", 1).toString(),
                        detail = item.optString("detail").ifBlank { null },
                        documentation = listOfNotNull(
                            item.optString("detail").ifBlank { null },
                            docs,
                            if (deprecated) "已废弃" else null
                        ).joinToString("\n").ifBlank { null },
                        sortText = item.optString("sortText").ifBlank { null },
                        filterText = item.optString("filterText").ifBlank { null },
                        snippet = item.optInt("insertTextFormat", 1) == 2
                    )
                )
            }
        }.sortedWith(compareBy({ it.sortText ?: it.label }, { it.label }))
    }

    /** LSP `documentation` 既可能是字符串，也可能是 `{ kind, value }`（MarkupContent）。 */
    private fun renderDocumentation(value: Any?): String? = when (value) {
        null -> null
        is String -> value.ifBlank { null }
        is JSONObject -> value.optString("value").ifBlank { null }
        else -> value.toString().ifBlank { null }
    }

    suspend fun definition(uri: String, line: Int, column: Int): List<Location> {
        val response = request("textDocument/definition", positionParams(uri, line, column)) ?: return emptyList()
        val result = response.opt("result") ?: return emptyList()
        val locations = if (result is JSONArray) result else JSONArray().put(result)
        return buildList {
            for (i in 0 until locations.length()) {
                val obj = locations.optJSONObject(i) ?: continue
                val range = obj.optJSONObject("range") ?: continue
                val start = range.optJSONObject("start") ?: continue
                add(Location(obj.optString("uri"), start.optInt("line") + 1, start.optInt("character") + 1))
            }
        }
    }

    private fun positionParams(uri: String, line: Int, column: Int) = JSONObject().apply {
        put("textDocument", JSONObject().put("uri", uri))
        put("position", JSONObject().put("line", line.coerceAtLeast(1) - 1).put("character", column.coerceAtLeast(1) - 1))
    }

    private suspend fun request(method: String, params: JSONObject, timeoutMs: Long = 8_000): JSONObject? {
        val id = requestId.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        sendJson(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        return try { withTimeout(timeoutMs) { deferred.await() } }
        catch (_: TimeoutCancellationException) { pending.remove(id); null }
    }

    private fun sendNotification(method: String, params: JSONObject) =
        sendJson(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))

    @Synchronized private fun sendJson(message: JSONObject) {
        write(frame(message))
    }

    private fun write(bytes: ByteArray) {
        if (handle != 0L) NativePty.nativeWrite(handle, bytes)
    }

    private fun frame(message: JSONObject): ByteArray {
        val bytes = message.toString().toByteArray(StandardCharsets.UTF_8)
        return "Content-Length: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII) + bytes
    }

    private suspend fun readLoop() {
        val buffer = ByteArray(32 * 1024)
        val data = ByteArrayOutput()
        try {
            while (currentCoroutineContext().isActive && handle != 0L) {
                val n = NativePty.nativeRead(handle, buffer)
                if (n <= 0) break
                data.append(buffer, n)
                while (true) {
                    val message = data.nextMessage() ?: break
                    handleMessage(message)
                }
            }
        } catch (t: Throwable) {
            bus.emit(IdeEvent.Output(session.id, "LSP read error: ${t.message}\n", stderr = true))
        }
    }

    private fun handleMessage(json: JSONObject) {
        val id = json.optInt("id", -1)
        if (id > 0) pending.remove(id)?.complete(json)
        val method = json.optString("method")
        if (method == "textDocument/publishDiagnostics") {
            val params = json.optJSONObject("params") ?: return
            val uri = params.optString("uri")
            val list = parseDiagnostics(params.optJSONArray("diagnostics"))
            _diagnostics.tryEmit(uri to list)
            val path = uriToPath(uri)
            if (path != null) {
                diagnosticStore?.replace(path, list.map { d ->
                    IdeEvent.Diagnostic(session.id, path, d.line, d.column, d.message, d.severity.toSessionSeverity())
                })
            }
            list.forEach { d ->
                bus.emit(IdeEvent.Diagnostic(session.id, path, d.line, d.column, d.message, d.severity.toSessionSeverity()))
            }
        } else if (method.isNotBlank()) {
            bus.emit(IdeEvent.Output(session.id, "LSP notification: $method\n"))
        }
    }

    private fun BuildError.Severity.toSessionSeverity(): Severity = when (this) {
        BuildError.Severity.ERROR -> Severity.ERROR
        BuildError.Severity.WARNING -> Severity.WARNING
    }

    private fun parseDiagnostics(array: JSONArray?): List<Diagnostic> = buildList {
        if (array == null) return@buildList
        for (i in 0 until array.length()) {
            val d = array.optJSONObject(i) ?: continue
            val start = d.optJSONObject("range")?.optJSONObject("start")
            add(Diagnostic(
                line = (start?.optInt("line", 0) ?: 0) + 1,
                column = (start?.optInt("character", 0) ?: 0) + 1,
                message = d.optString("message"),
                severity = when (d.optInt("severity", 1)) {
                    2 -> BuildError.Severity.WARNING
                    3, 4 -> BuildError.Severity.WARNING
                    else -> BuildError.Severity.ERROR
                }
            ))
        }
    }

    private fun uriToPath(uri: String): String? = runCatching {
        if (uri.startsWith("file://")) URI(uri).path else uri
    }.getOrNull()

    private fun buildLaunchScript(dir: File, command: List<String>): ByteArray {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val cmd = command.joinToString(" ") { q(it) }
        return ("stty -echo 2>/dev/null; cd ${q(dir.absolutePath)} || exit 125; exec $cmd\n").toByteArray(StandardCharsets.UTF_8)
    }

    override fun close() {
        initialized = false
        pending.values.forEach { it.cancel() }
        pending.clear()
        scope.cancel()
        if (handle != 0L) {
            NativePty.nativeSignal(handle, 15)
            NativePty.nativeClose(handle)
            handle = 0L
        }
        bus.state(session, SessionState.Cancelled)
    }

    private class ByteArrayOutput {
        private var bytes = ByteArray(0)
        fun append(src: ByteArray, count: Int) { bytes += src.copyOf(count) }
        fun nextMessage(): JSONObject? {
            val text = String(bytes, StandardCharsets.UTF_8)
            val marker = "Content-Length:"
            val start = text.indexOf(marker)
            if (start < 0) { if (bytes.size > 65536) bytes = bytes.takeLast(4096).toByteArray(); return null }
            val sep = text.indexOf("\r\n\r\n", start)
            if (sep < 0) return null
            val header = text.substring(start, sep)
            val len = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
            val bodyStart = sep + 4
            val bodyEnd = bodyStart + len
            val raw = text.toByteArray(StandardCharsets.UTF_8)
            if (raw.size < bodyEnd) return null
            val body = raw.copyOfRange(bodyStart, bodyEnd)
            bytes = raw.copyOfRange(bodyEnd, raw.size)
            return runCatching { JSONObject(String(body, StandardCharsets.UTF_8)) }.getOrNull()
        }
    }
}
