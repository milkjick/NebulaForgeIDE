package com.nebulaforge.core.session

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.projectmodel.CompletionItem
import com.nebulaforge.core.projectmodel.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped language-service registry and lifecycle owner.
 *
 * The registry deliberately does not pretend that a server exists. A service becomes
 * Available only when its real executable has been detected in the embedded Way-B runtime.
 * One server instance is shared by all open documents of the same project/language.
 */
class LanguageServiceRegistry(
    private val context: Context,
    private val bus: IdeSessionBus,
    private val diagnosticStore: DiagnosticStore
) {
    data class Descriptor(
        val id: String,
        val displayName: String,
        val extensions: Set<String>,
        val commandCandidates: List<List<String>>
    )

    enum class State { STOPPED, STARTING, RUNNING, UNAVAILABLE, FAILED }

    data class ServiceStatus(
        val project: String,
        val languageId: String,
        val state: State,
        val message: String = ""
    )

    private data class Key(val project: String, val languageId: String)
    private val lock = Mutex()
    private val services = mutableMapOf<Key, GenericLspLanguageService>()
    private val versions = ConcurrentHashMap<String, Int>()
    private val _statuses = MutableStateFlow<List<ServiceStatus>>(emptyList())
    val statuses: StateFlow<List<ServiceStatus>> = _statuses.asStateFlow()

    private val descriptors = listOf(
        Descriptor("java", "Java", setOf("java"), listOf(
            listOf("\$HOME/.nebulaforge/lsp/jdtls/bin/jdtls"),
            listOf("jdtls")
        )),
        Descriptor("kotlin", "Kotlin", setOf("kt", "kts"), listOf(
            listOf("\$HOME/.nebulaforge/lsp/kotlin-language-server/server/bin/kotlin-language-server"),
            listOf("kotlin-language-server")
        )),
        Descriptor("xml", "XML", setOf("xml"), listOf(
            listOf("\$HOME/.nebulaforge/lsp/lemminx/bin/nebulaforge-lsp"),
            listOf("lemminx")
        )),
        Descriptor("dart", "Dart", setOf("dart"), listOf(
            listOf("dart", "language-server", "--protocol=lsp")
        )),
        Descriptor("typescript", "TypeScript", setOf("ts", "tsx", "js", "jsx"), listOf(
            listOf("typescript-language-server", "--stdio")
        )),
        Descriptor("cpp", "C/C++", setOf("c", "cc", "cpp", "cxx", "h", "hpp"), listOf(
            listOf("clangd")
        )),
        // ---- 与后端/脚本模板对应的语言服务（此前缺失；找不到就如实报 UNAVAILABLE，不伪造运行状态）----
        Descriptor("go", "Go", setOf("go"), listOf(
            listOf("gopls"),
            listOf("\$HOME/.nebulaforge/lsp/gopls/gopls")
        )),
        Descriptor("rust", "Rust", setOf("rs"), listOf(
            listOf("rust-analyzer"),
            listOf("\$HOME/.nebulaforge/lsp/rust-analyzer/rust-analyzer")
        )),
        Descriptor("php", "PHP", setOf("php"), listOf(
            listOf("phpactor", "language-server"),
            listOf("\$HOME/.nebulaforge/lsp/phpactor/phpactor", "language-server")
        )),
        Descriptor("python", "Python", setOf("py"), listOf(
            listOf("pylsp"),
            listOf("python", "-m", "pylsp"),
            listOf("pyright-langserver", "--stdio")
        )),
        Descriptor("lua", "Lua", setOf("lua"), listOf(
            listOf("lua-language-server"),
            listOf("\$HOME/.nebulaforge/lsp/lua-language-server/bin/lua-language-server")
        ))
    )

    fun descriptorFor(file: File): Descriptor? = descriptors.firstOrNull { file.extension.lowercase() in it.extensions }

    fun findDescriptor(languageId: String): Descriptor? = descriptors.firstOrNull { it.id == languageId }

    fun descriptors(): List<Descriptor> = descriptors.toList()

    fun isCommandAvailable(descriptor: Descriptor): Boolean = resolveCommand(descriptor) != null

    suspend fun openFile(projectRoot: File, file: File, text: String): ServiceStatus {
        val descriptor = descriptorFor(file)
            ?: return ServiceStatus(projectRoot.canonicalPath, "unknown", State.UNAVAILABLE, "没有注册对应语言服务")
        val key = Key(projectRoot.canonicalPath, descriptor.id)
        return lock.withLock {
            val existing = services[key]
            if (existing != null) {
                existing.didOpen(file, text)
                versions[file.canonicalPath] = 1
                val status = ServiceStatus(key.project, key.languageId, State.RUNNING, "语言服务器已运行")
                publish(status)
                return@withLock status
            }

            val command = resolveCommand(descriptor)
            if (command == null) {
                val status = ServiceStatus(key.project, key.languageId, State.UNAVAILABLE,
                    "未检测到 ${descriptor.displayName} Language Server；不会伪造启动状态")
                publish(status)
                return@withLock status
            }

            val starting = ServiceStatus(key.project, key.languageId, State.STARTING, "启动 ${descriptor.displayName} Language Server…")
            publish(starting)
            val service = GenericLspLanguageService(
                languageId = descriptor.id,
                supportedExtensions = descriptor.extensions.map { ".${it}" },
                command = command,
                bus = bus,
                diagnosticStore = diagnosticStore
            )
            val env = runCatching {
                // Current Eclipse JDT LS requires Java 21+; other servers can use the IDE's JDK 17.
                val requiredJdk = if (descriptor.id == "java") 21 else 17
                Environment.buildLspEnv(context, requiredJdk)
            }.getOrElse {
                if (descriptor.id == "java") {
                    publish(ServiceStatus(key.project, key.languageId, State.UNAVAILABLE, "Java Language Server 需要 JDK 21；请先安装 JDK 21"))
                    return@withLock ServiceStatus(key.project, key.languageId, State.UNAVAILABLE, "Java Language Server 需要 JDK 21；请先安装 JDK 21")
                }
                Environment.buildTerminalEnv(context)
            }
            val started = try { service.start(projectRoot, env) } catch (_: Throwable) { false }
            if (!started) {
                service.stop()
                val status = ServiceStatus(key.project, key.languageId, State.FAILED,
                    "Language Server 启动失败；请检查工具链与服务器日志")
                publish(status)
                return@withLock status
            }
            services[key] = service
            versions[file.canonicalPath] = 1
            service.didOpen(file, text)
            val running = ServiceStatus(key.project, key.languageId, State.RUNNING, "${descriptor.displayName} Language Server 已连接")
            publish(running)
            running
        }
    }

    suspend fun changeFile(projectRoot: File, file: File, text: String) {
        val descriptor = descriptorFor(file) ?: return
        val key = Key(projectRoot.canonicalPath, descriptor.id)
        val service = lock.withLock { services[key] } ?: return
        val version = versions.merge(file.canonicalPath, 1) { a, b -> a + b } ?: 1
        service.didChange(file, version, text)
    }

    suspend fun closeFile(projectRoot: File, file: File) {
        val descriptor = descriptorFor(file) ?: return
        val key = Key(projectRoot.canonicalPath, descriptor.id)
        lock.withLock { services[key] }?.didClose(file)
        versions.remove(file.canonicalPath)
    }

    suspend fun stopProject(projectRoot: File) {
        val canonical = projectRoot.canonicalPath
        lock.withLock {
            val entries = services.filterKeys { it.project == canonical }.toList()
            entries.forEach { (key, service) ->
                try { service.stop() } catch (_: Throwable) { }
                services.remove(key)
                publish(ServiceStatus(key.project, key.languageId, State.STOPPED, "项目关闭"))
            }
            versions.keys.removeIf { it.startsWith(canonical + File.separator) }
        }
    }

    suspend fun stopAll() {
        lock.withLock {
            services.forEach { (key, service) ->
                try { service.stop() } catch (_: Throwable) { }
                publish(ServiceStatus(key.project, key.languageId, State.STOPPED, "语言服务已停止"))
            }
            services.clear()
            versions.clear()
        }
    }

    // ------------------------------------------------------------ 编辑器请求入口

    /**
     * 请求补全候选（编辑器调用）。
     *
     * 设计约定：
     * - **永不抛异常**、**永不阻塞等待启动**：未启动/未安装服务时直接返回空列表，
     *   编辑器随后只用本地候选，保证「打字不卡、弹窗不消失」。
     * - `line`/`column` 是 1 基（与编辑器光标一致），内部由 LspClient 转 0 基。
     */
    suspend fun completion(projectRoot: File, file: File, line: Int, column: Int): List<CompletionItem> {
        val descriptor = descriptorFor(file) ?: return emptyList()
        val key = Key(projectRoot.canonicalPath, descriptor.id)
        val service = lock.withLock { services[key] } ?: return emptyList()
        return runCatching { service.requestCompletion(file.toURI().toString(), line, column) }
            .getOrDefault(emptyList())
    }

    /** 跳转定义（编辑器调用）；返回 1 基行列。 */
    suspend fun definition(projectRoot: File, file: File, line: Int, column: Int): List<Location> {
        val descriptor = descriptorFor(file) ?: return emptyList()
        val key = Key(projectRoot.canonicalPath, descriptor.id)
        val service = lock.withLock { services[key] } ?: return emptyList()
        return runCatching { service.requestDefinition(file.toURI().toString(), line, column) }
            .getOrDefault(emptyList())
    }

    /** 某文件当前是否已有可用的语言服务（用于 UI 提示「LSP 未就绪，仅本地补全」）。 */
    fun isServing(projectRoot: File, file: File): Boolean {
        val descriptor = descriptorFor(file) ?: return false
        return services.containsKey(Key(projectRoot.canonicalPath, descriptor.id))
    }

    private fun resolveCommand(descriptor: Descriptor): List<String>? {
        val home = Environment.ensureHome(context).absolutePath
        for (candidate in descriptor.commandCandidates) {
            if (candidate.isEmpty()) continue
            val expanded = candidate.map { it.replace("\$HOME", home) }
            val first = expanded.first()
            val executable = if (first.contains(File.separator)) File(first) else Environment.findExecutable(context, first)
            if (executable != null && executable.isFile && executable.canExecute()) {
                return listOf(executable.absolutePath) + expanded.drop(1)
            }
        }
        return null
    }

    private fun publish(status: ServiceStatus) {
        _statuses.value = (_statuses.value.filterNot {
            it.project == status.project && it.languageId == status.languageId
        } + status).sortedWith(compareBy({ it.project }, { it.languageId }))
    }
}
