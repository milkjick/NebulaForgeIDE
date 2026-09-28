package com.nebulaforge.app.plugins

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.environment.TermuxGuest
import com.nebulaforge.core.plugin.PluginActivationStore
import com.nebulaforge.core.pty.NativePty
import com.nebulaforge.core.pty.PtyShellArgs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

/**
 * JS 扩展宿主（宿主侧进程管理器）。
 *
 * ## 为什么需要它
 * VSIX 的插件逻辑是 JS：`package.json` 的 `main` 指向一个 Node 模块。只把它转成「声明式
 * 贡献点」（补全片段 / 语言关联）只能覆盖纯数据型扩展；带 `activate()` 的扩展（注册命令、
 * 补全 provider、读写工作区）必须真的把 JS 跑起来。
 *
 * ## 运行方式
 * 与 [com.nebulaforge.core.session.LspClient] 同构的 Way-B 运行时：用 [NativePty] fork/exec
 * 一个 `/system/bin/sh`，`stty -echo` 后 `exec node bootstrap.js <扩展目录>`。
 * 也就是说 **不需要 proot**：工具链（Node.js 等）本就装在 `filesDir/usr`（即 guest 前缀
 * `$PREFIX`，见 [Environment.binDir]），app 以 targetSdk=28 刻意保留了对私有目录内 ELF 的
 * 执行权限，终端 / 构建 / LSP / 本宿主走的是同一条用户态。
 *
 * 协议：Content-Length 分帧的 JSON-RPC 2.0，stdout 双向复用。PTY 只有一个输出流，扩展往
 * stderr 写的日志必然混在帧之间，因此两端解析器都「先找 Content-Length 标记再取 body」，
 * 并把帧外文本如实上报成宿主日志，而不是丢弃。
 *
 * ## 诚实边界
 * - Node 未安装 → 状态置 [Status.NO_NODE] 并给出安装提示，不假装已生效。
 * - 扩展调用了宿主没实现的 API（showInputBox / webview / 真实终端）→ 日志里登记「宿主未
 *   实现」，调用方拿到 null/false，而不是静默成功。
 */
class JsExtensionHost private constructor(private val context: Context) {

    // ------------------------------------------------------------------ 对外模型

    enum class Status { STOPPED, STARTING, RUNNING, FAILED, NO_NODE }

    /**
     * 扩展命令。
     *
     * [declared] = package.json `contributes.commands` 里有声明（命令面板里「看得见」）；
     * [registered] = 扩展在运行时真的调过 `commands.registerCommand`（点得动）。
     *
     * 两者必须分开：真实扩展（vscode-go）把调试类命令注册在 `activate()` 的后段，
     * 缺工具链时激活中途终止，于是命令「声明了但永远没注册」。
     * 若把这两件事混成一个布尔值，用户点下去只会得到一句无从下手的结果。
     */
    data class CommandEntry(
        val pluginId: String,
        val pluginName: String,
        val id: String,
        val title: String,
        val registered: Boolean,
        val declared: Boolean = false
    )

    /** 扩展注册的语言能力。[driven] 表示宿主目前会真正驱动它（仅 completion）。 */
    data class ProviderEntry(
        val pluginId: String,
        val kind: String,
        val id: String,
        val languages: List<String>,
        val triggerCharacters: List<String>,
        val driven: Boolean
    )

    data class LogLine(val pluginId: String, val level: String, val text: String, val at: Long)

    data class HostMessage(
        val pluginId: String,
        val level: String,
        val text: String,
        val actions: List<String>
    )

    /** 需要 UI 作答的请求；无人作答（或超时）时扩展收到 null，等同 VS Code 的「用户取消」。 */
    data class UiRequest(
        val id: Long,
        val pluginId: String,
        val kind: String,
        val message: String,
        val options: List<String>,
        val multi: Boolean,
        /** 输入框的预填值（`showInputBox` 的 value）。 */
        val prefill: String = ""
    )

    data class HostState(
        val pluginId: String,
        val displayName: String,
        val version: String,
        val status: Status,
        val detail: String,
        val nodeVersion: String? = null,
        val main: String? = null,
        val activationEvents: List<String> = emptyList(),
        val commands: List<CommandEntry> = emptyList(),
        val providers: List<ProviderEntry> = emptyList(),
        /** 激活失败原因（已翻译成可读文本）；未失败为 null。UI 据此提示「为什么插件命令点不动」。 */
        val activationError: String? = null
    ) {
        val running: Boolean get() = status == Status.RUNNING
    }

    data class SimpleEdit(
        val startLine: Int,
        val startCharacter: Int,
        val endLine: Int,
        val endCharacter: Int,
        val newText: String
    )

    /** 编辑器桥：把扩展的「改/开/存文档、应用 WorkspaceEdit」落到真实编辑器。 */
    interface EditorBridge {
        suspend fun applyEdits(uri: String, edits: List<SimpleEdit>): Boolean
        suspend fun openDocument(uri: String): Boolean
        suspend fun saveDocument(uri: String): Boolean
        suspend fun activeDocument(): Pair<String, String>? // uri to languageId
    }

    data class JsCompletionItem(
        val label: String,
        val insertText: String,
        val detail: String?,
        val documentation: String?,
        val kind: Int,
        val sortText: String?,
        val filterText: String?,
        val isSnippet: Boolean,
        val commitCharacters: List<String>
    )

    /** 扩展注册进来的 WebView 视图/面板（供宿主侧渲染与管理）。 */
    data class WebviewEntry(
        val pluginId: String,
        val id: String,
        val kind: String,
    )

    /**
     * 一个已打开、可渲染的插件界面面板。
     *
     * [revision] 随 `webview.html` 变化自增，Compose 侧据此重新加载 WebView；
     * [outbox] 是扩展已发出、但界面还没取走的 `postMessage` 载荷（原样 JSON 文本）。
     */
    data class WebviewPanelState(
        val id: String,
        val pluginId: String,
        val viewType: String,
        val title: String,
        val html: String,
        val enableScripts: Boolean,
        val revision: Int,
        val outbox: List<String> = emptyList(),
        /** 插件安装目录：WebView 里的本地资源（asWebviewUri）从这里读盘。 */
        val rootDir: String? = null,
    )

    data class DiagnosticEntry(
        val message: String,
        val severity: Int,
        val line: Int,
        val character: Int,
        val source: String?
    )

    // ------------------------------------------------------------------ 全局状态

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _states = MutableStateFlow<List<HostState>>(emptyList())
    val states: StateFlow<List<HostState>> = _states.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _messages = MutableSharedFlow<HostMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<HostMessage> = _messages.asSharedFlow()

    private val _uiRequests = MutableStateFlow<UiRequest?>(null)
    val uiRequests: StateFlow<UiRequest?> = _uiRequests.asStateFlow()

    private val _commands = MutableStateFlow<List<CommandEntry>>(emptyList())
    val commands: StateFlow<List<CommandEntry>> = _commands.asStateFlow()

    private val _providers = MutableStateFlow<List<ProviderEntry>>(emptyList())
    val providers: StateFlow<List<ProviderEntry>> = _providers.asStateFlow()

    /**
     * 兼容层兜底过的 API 清单（扩展请求了、宿主没实现）。
     *
     * 这是给用户看的**真实缺口清单**：既能解释“为什么某个插件功能没反应”，
     * 又避免把缺口伪装成正常。来源是 guest 侧 guardNamespace / 兼容桩的上报。
     */
    private val _apiGaps = MutableStateFlow<List<String>>(emptyList())
    val apiGaps: StateFlow<List<String>> = _apiGaps.asStateFlow()

    /**
     * 扩展宿主内被隔离的未捕获异常（保活策略下不再连带整宿主崩溃）。
     *
     * 每条为 `插件ID` 到 `堆栈摘要`，供面板诊断展示。
     */
    private val _isolatedCrashes = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val isolatedCrashes: StateFlow<List<Pair<String, String>>> = _isolatedCrashes.asStateFlow()

    /** 扩展注册的 WebView 视图/面板（用于宿主侧真实渲染）。 */
    private val _webviews = MutableStateFlow<List<WebviewEntry>>(emptyList())
    val webviews: StateFlow<List<WebviewEntry>> = _webviews.asStateFlow()

    /**
     * 正在**打开**的 WebView 面板（扩展用 `createWebviewPanel` / 视图提供者创建）。
     *
     * 与 [webviews]（注册表）不同，这里是真实有内容的界面：扩展把 html 交上来，
     * 宿主渲染成 Compose 里的 WebView，并把两侧的消息互相投递。
     * 只有这样才能真正「在 IDE 里打开插件界面」，而不是记一条日志了事。
     */
    private val _panels = MutableStateFlow<List<WebviewPanelState>>(emptyList())
    val panels: StateFlow<List<WebviewPanelState>> = _panels.asStateFlow()

    private val panelLock = Any()

    /** 取走该面板「扩展 → WebView」的待投递消息（取走即清空，避免重复派发）。 */
    fun drainPanelOutbox(panelId: String): List<String> = synchronized(panelLock) {
        val current = _panels.value
        val panel = current.firstOrNull { it.id == panelId } ?: return emptyList()
        if (panel.outbox.isEmpty()) return emptyList()
        _panels.value = current.map { if (it.id == panelId) it.copy(outbox = emptyList()) else it }
        panel.outbox
    }

    /** WebView → 扩展：把界面里的 `postMessage` 投递给扩展的 `onDidReceiveMessage`。 */
    fun postPanelMessageFromWebview(panelId: String, messageJson: String) {
        val panel = _panels.value.firstOrNull { it.id == panelId } ?: return
        val owner = hosts[panel.pluginId] ?: run {
            appendLog(panel.pluginId, "warn", "面板 $panelId 的消息无处投递：扩展宿主已不在")
            return
        }
        scope.launch { owner.deliverWebviewMessage(panelId, messageJson) }
    }

    /** 宿主侧关闭面板（用户点 ✕）：界面立即收起，同时通知扩展触发 `onDidDispose`。 */
    fun closePanel(panelId: String) {
        val panel = _panels.value.firstOrNull { it.id == panelId } ?: return
        removePanel(panelId)
        hosts[panel.pluginId]?.let { owner -> scope.launch { owner.disposeWebviewPanel(panelId) } }
    }

    /**
     * 打开扩展注册的侧边视图（`registerWebviewViewProvider`）。
     *
     * 视图是"按需解析"的：只有宿主真的要显示它时才调用 `resolveWebviewView`，
     * 扩展这时才把 html 交上来 —— 所以面板列表要等扩展应答后才会出现。
     */
    fun openRegisteredView(pluginId: String, viewId: String) {
        val owner = hosts[pluginId] ?: run {
            appendLog(pluginId, "warn", "打不开视图 $viewId：插件宿主不存在（可能未安装或未启动）")
            return
        }
        scope.launch {
            val error = owner.resolveWebviewView(viewId)
            if (error != null) appendLog(pluginId, "warn", "视图 $viewId 打开失败：$error")
        }
    }

    internal fun upsertPanel(
        pluginId: String,
        id: String,
        viewType: String,
        title: String? = null,
        html: String? = null,
        enableScripts: Boolean? = null,
        rootDir: String? = null,
    ) {
        val stale: List<String>
        synchronized(panelLock) {
            val current = _panels.value
            val existing = current.firstOrNull { it.id == id }
            val effectiveViewType = viewType.ifBlank { existing?.viewType ?: "" }
            // 同一插件、同一 viewType 只保留一个面板。
            //
            // 为什么必须收敛：手机上不存在「编辑器标签页排一排」的空间，而扩展每点一次
            // Open 就新建一个面板（Claude Code 一次登录流程能建 5~6 个）。它们全部保持挂载，
            // 于是多个 WebView 同时渲染：CPU 被打满、宿主向 WebView 投递消息开始超时、
            // 界面互相遮挡 —— 用户看到的却是「点了没反应」。
            // 保留最新那个，旧的走 dispose 语义回收，扩展侧的 webviewPanels 也一并清理。
            stale = if (effectiveViewType.isNotBlank()) {
                current.filter {
                    it.id != id && it.pluginId == pluginId && it.viewType == effectiveViewType
                }.map { it.id }
            } else {
                emptyList()
            }
            val updated = WebviewPanelState(
                id = id,
                pluginId = pluginId,
                viewType = effectiveViewType,
                title = title?.takeIf { it.isNotBlank() } ?: existing?.title ?: viewType.ifBlank { "插件面板" },
                html = html ?: existing?.html ?: "",
                enableScripts = enableScripts ?: existing?.enableScripts ?: true,
                // html 变化就自增版本号：UI 据此重载 WebView（否则扩展改内容界面不动）。
                revision = (existing?.revision ?: 0) + if (html != null) 1 else 0,
                outbox = existing?.outbox ?: emptyList(),
                rootDir = rootDir ?: existing?.rootDir,
            )
            _panels.value = (current.filterNot { it.id == id || it.id in stale }) + updated
        }
        if (stale.isNotEmpty()) {
            appendLog(pluginId, "info", "同类型面板收敛：回收 ${stale.size} 个旧面板（$viewType）")
            hosts[pluginId]?.let { owner -> scope.launch { stale.forEach { owner.disposeWebviewPanel(it) } } }
        }
    }

    internal fun appendPanelOutbox(panelId: String, messageJson: String) = synchronized(panelLock) {
        _panels.value = _panels.value.map {
            if (it.id == panelId) it.copy(outbox = it.outbox + messageJson) else it
        }
    }

    internal fun removePanel(panelId: String) = synchronized(panelLock) {
        _panels.value = _panels.value.filterNot { it.id == panelId }
    }

    /**
     * 扩展通过 `window.withProgress` 上报的进度（插件 ID 到标题/描述）。
     *
     * 以前这些通知是「未处理」直接丢掉的：用户在等一个没有反馈的长任务，只能怀疑是卡死。
     */
    private val _progress = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val progress: StateFlow<List<Pair<String, String>>> = _progress.asStateFlow()

    /**
     * 模型网关回调（由 app 层注入）。
     *
     * 为 null 时模型类能力会明确回「宿主未接入模型网关」，而不是返回空字符串假装成功 ——
     * 插件因此能给出可读提示，用户也能看懂到底缺什么。
     */
    @Volatile
    var modelGateway: (suspend (caller: String, prompt: String, system: String?) -> String)? = null

    /**
     * 受授权、可审计的能力网关：插件 / AI Agent / 外部网关调用宿主功能的唯一入口。
     *
     * 授权决定落盘在 `state/capability-grants.json`，审计落在 `state/capability-audit.log`，
     * 界面（插件诊断面板）可以直接展示"谁在什么时候调用了什么、允许还是拒绝"。
     */
    val capabilityBridge: HostCapabilityBridge by lazy {
        HostCapabilityBridge(
            grantsFile = File(stateDir, "capability-grants.json"),
            auditFile = File(stateDir, "capability-audit.log"),
            auditSink = { caller, text -> appendLog(caller, "info", text) }
        ).also { registerBuiltinCapabilities(it) }
    }

    private val hosts = ConcurrentHashMap<String, PluginHost>()
    private var workspaceRoot: File? = null
    private var nodeInfo: NodeInfo? = null

    /** 编辑器桥（由 app 层注入；未注入时编辑类 API 返回 false 并记录日志）。 */
    @Volatile
    var editorBridge: EditorBridge? = null

    /** 扩展往终端写文本时的落点（由 app 层注入；未注入时只记日志）。 */
    @Volatile
    var terminalSink: ((name: String, text: String) -> Unit)? = null

    /** 扩展上报的诊断（由 app 层注入 DiagnosticStore）。 */
    @Volatile
    var diagnosticSink: ((pluginId: String, path: String, items: List<DiagnosticEntry>) -> Unit)? = null

    /** 宿主内置命令所需的 IDE 动作（由 app 层注入；未注入时内置命令走「已识别无动作」软处理）。 */
    @Volatile
    var ideActions: IdeActions? = null

    /**
     * 宿主内置命令表（`setContext`、`workbench.*`、`editor.action.*`…）。
     *
     * 这些命令在 VS Code 中由 workbench 提供，不属于任何扩展；宿主不实现它们时，
     * 依赖 UI 命令的扩展会持续报 `IDE 未提供命令 xxx`，并可能中断交互流程。
     */
    val builtinCommands: BuiltinCommandRegistry by lazy {
        BuiltinCommandRegistry(
            actionsProvider = { ideActions },
            log = { level, message -> appendLog("-", level, message) },
            contextKeysFile = File(stateDir, "context-keys.json"),
        )
    }

    private data class NodeInfo(val binary: File)

    private val activation = PluginActivationStore(context)

    /** 扩展注册的自定义文件系统 scheme（诊断用）。 */
    private val fileSystemSchemes = ConcurrentHashMap.newKeySet<String>()

    /** 当前存活的文件监视器数量（诊断用）。 */
    private val watcherCount = java.util.concurrent.atomic.AtomicInteger(0)

    private val uiSeq = AtomicLong(1)
    private val uiWaiters = ConcurrentHashMap<Long, CompletableDeferred<JSONObject?>>()

    /** 扩展用 `vscode.lm.registerTool` 注册的模型工具（键为 `插件ID::工具名`）。 */
    private val lmTools = ConcurrentHashMap<String, String>()

    /**
     * 界面交互的等待上限。
     *
     * 与 VS Code 的区别必须诚实：VS Code 的对话框会一直等下去，手机上没人盯着的弹窗
     * 会永远挂住扩展。这里给一个上限，超时按「用户取消」返回 —— 扩展拿到的是 null（合法语义），
     * 而不是被吊死。取值给足用户读完提示并输入的时间。
     */
    private val UI_WAIT_MS = 180_000L

    // ------------------------------------------------------------------ 能力网关

    /**
     * 注册宿主开箱提供的能力。
     *
     * 这些能力同时服务三方：插件（`vscode.lm` / 命令面板）、IDE 内 AI Agent、外部 AI 网关。
     * 风险等级决定默认授权：只读直接放行，写入/执行/模型调用默认弹窗问用户。
     */
    private fun registerBuiltinCapabilities(bridge: HostCapabilityBridge) {
        bridge.register(
            CapabilityDef(
                "plugin.command.execute", "执行插件命令", CapRisk.EXEC,
                "调用任意已注册的扩展命令（MS 会把它当作可执行入口，因此默认需要用户确认）",
                """{"commandId":"vscode.golang.go.test","args":[]}"""
            )
        ) { args ->
            val id = args.optString("commandId")
            if (id.isBlank()) CapResult(false, "缺少 commandId")
            else {
                val err = executeCommand(id, args.optJSONArray("args"))
                if (err == null) CapResult(true, "命令 $id 执行完成") else CapResult(false, err)
            }
        }

        bridge.register(
            CapabilityDef(
                "workspace.readFile", "读取工作区文件", CapRisk.READ,
                "读取工作区内（或已授权目录）的文本文件", """{"path":"src/main.kt"}"""
            )
        ) { args ->
            val f = resolveUri(args.optString("path"))
                ?: return@register CapResult(false, "路径不在可访问范围内：${args.optString("path")}")
            if (!f.isFile) CapResult(false, "文件不存在：${f.absolutePath}")
            else CapResult(true, f.readText())
        }

        bridge.register(
            CapabilityDef(
                "workspace.writeFile", "写入工作区文件", CapRisk.WRITE,
                "覆盖写文本文件（AI 改代码走这条，审计里能查到改的是哪个文件）",
                """{"path":"src/main.kt","text":"..."}"""
            )
        ) { args ->
            val f = resolveUri(args.optString("path"))
                ?: return@register CapResult(false, "路径不在可访问范围内：${args.optString("path")}")
            runCatching {
                f.parentFile?.mkdirs()
                f.writeText(args.optString("text"))
            }.fold(
                onSuccess = { CapResult(true, "已写入 ${f.absolutePath}") },
                onFailure = { CapResult(false, "写入失败：${it.message ?: it.javaClass.simpleName}") }
            )
        }

        bridge.register(
            CapabilityDef(
                "workspace.listDir", "列目录", CapRisk.READ,
                "列出目录条目（AI 摸清工程结构的第一步）", """{"path":"."}"""
            )
        ) { args ->
            val f = resolveUri(args.optString("path", "."))
                ?: return@register CapResult(false, "路径不在可访问范围内")
            if (!f.isDirectory) CapResult(false, "不是目录：${f.absolutePath}")
            else CapResult(true, f.listFiles().orEmpty().joinToString("\n") { (if (it.isDirectory) "d " else "f ") + it.name })
        }

        bridge.register(
            CapabilityDef(
                "terminal.run", "在 IDE 终端执行", CapRisk.EXEC,
                "把命令写进 IDE 内嵌终端（终端有用户态工具链环境，适合编译/运行）",
                """{"command":"gradle assembleDebug"}"""
            )
        ) { args ->
            val sink = terminalSink
                ?: return@register CapResult(false, "终端未就绪（IDE 没打开终端面板）")
            sink("Nebula", args.optString("command") + "\n")
            CapResult(true, "已发送到终端")
        }

        bridge.register(
            CapabilityDef(
                "model.chat", "调用模型网关", CapRisk.MODEL,
                "把提示词交给用户在设置里配置的模型档位/网关（插件与 AI 共用同一条受控通道）",
                """{"prompt":"解释这段代码","system":"可选"}"""
            )
        ) { args ->
            val gw = modelGateway
                ?: return@register CapResult(false, "宿主未接入模型网关：请在 AI 设置里配置模型档位")
            val prompt = args.optString("prompt")
            if (prompt.isBlank()) CapResult(false, "缺少 prompt")
            else runCatching { CapResult(true, gw("capability", prompt, args.optString("system").takeIf { it.isNotBlank() })) }
                .getOrElse { CapResult(false, "模型调用失败：${it.message ?: it.javaClass.simpleName}") }
        }

        bridge.register(
            CapabilityDef(
                "ui.notify", "弹出提示", CapRisk.UI,
                "在 IDE 里给用户一条提示（插件报告状态用）", """{"message":"构建完成"}"""
            )
        ) { args ->
            val msg = args.optString("message")
            _messages.tryEmit(HostMessage("nebulaforge", "info", msg, emptyList()))
            CapResult(true, "已提示")
        }

        bridge.register(
            CapabilityDef(
                "host.pluginList", "列出插件与命令", CapRisk.READ,
                "列出已装插件、激活状态与命令 ID（AI 决定「该调用哪个命令」的依据）",
                "{}"
            )
        ) { _ ->
            val text = _states.value.joinToString("\n") { st ->
                val err = st.activationError?.let { "｜激活失败：$it" }.orEmpty()
                // 命令按「已注册 / 仅声明」标注：只声明未注册的命令在面板里点下去必然报
                // "command not found"，AI 一看这行就知道该不该重试激活。
                val cmds = st.commands.joinToString(",") { c ->
                    (if (c.registered) c.id else "${c.id}(未注册)")
                }.ifBlank { "无" }
                "${st.pluginId} ${st.version} [${st.status}] 命令=$cmds$err"
            }
            CapResult(true, text.ifBlank { "（没有已加载的插件）" })
        }

        bridge.register(
            CapabilityDef(
                "host.diagnostics", "读取宿主诊断", CapRisk.READ,
                "激活失败清单、兼容层降级缺口、被隔离的异常（AI 自诊用）", "{}"
            )
        ) { _ ->
            val failed = _states.value.filter { !it.activationError.isNullOrBlank() }
            val parts = buildList {
                add("插件 ${_states.value.size} 个，激活失败 ${failed.size} 个")
                failed.forEach { add("失败：${it.pluginId} → ${it.activationError}") }
                if (_apiGaps.value.isNotEmpty()) add("兼容层降级：" + _apiGaps.value.joinToString(", "))
                _isolatedCrashes.value.takeLast(5).forEach { (id, stack) ->
                    add("隔离异常 $id：" + stack.lineSequence().firstOrNull().orEmpty())
                }
            }
            CapResult(true, parts.joinToString("\n"))
        }
        // 插件自查用：能力目录与调用审计（只读，不需要授权）。
        // 有它插件才能自己解释「为什么我调不动某个能力」——是没注册，还是用户没授权。
        bridge.register(
            CapabilityDef(
                "host.capabilities", "读取能力目录", CapRisk.READ,
                "宿主已注册能力（含参数契约）与当前授权状态（插件自查「我能调什么」）", "{}"
            )
        ) { _ ->
            CapResult(true, capabilityCatalogText())
        }

        bridge.register(
            CapabilityDef(
                "host.audit", "读取调用审计", CapRisk.READ,
                "最近的能力调用记录（谁调的、准没准、成没成）", """{"limit":20}"""
            )
        ) { args ->
            CapResult(true, capabilityAuditText(args.optInt("limit", 20)))
        }
    }

    /**
     * 供插件 / AI Agent / 网关调用能力的公共入口。
     *
     * [caller] 是审计主体（`plugin:<id>` / `agent` / `gateway`）；
     * 需要用户授权时，用界面弹窗当场确认（不阻塞主线程，超时按拒绝处理）。
     */
    suspend fun invokeCapability(caller: String, capability: String, args: JSONObject): CapResult =
        capabilityBridge.invoke(caller, capability, args) { def, preview ->
            val answer = askChoice(
                "${def.title}（${def.risk.label}）\n调用方：$caller\n参数：$preview",
                listOf("允许本次", "始终允许", "拒绝")
            )
            when (answer) {
                "始终允许" -> { capabilityBridge.setGrant(caller, def.id, GrantDecision.ALLOW); true }
                "允许本次" -> true
                else -> false
            }
        }

    /**
     * 能力目录 + 授权状态的文本快照。
     *
     * 同一份渲染给三方共用：插件的 `host.capabilities`、AI Agent 的 `host_capability(list)`、
     * 以及插件诊断面板 —— 保证「AI 看到的能力」和「用户看到的能力」永远一致，
     * 不会出现 AI 以为能调、用户却根本没开的情况。
     */
    fun capabilityCatalogText(): String {
        val defs = capabilityBridge.capabilities.value
        if (defs.isEmpty()) return "（宿主尚未注册任何能力）"
        val grants = capabilityBridge.grants.value
        return defs.joinToString("\n") { def ->
            val d = grants["agent|${def.id}"] ?: grants.entries.firstOrNull { it.key.endsWith("|${def.id}") }?.value
            val state = when (d) {
                GrantDecision.ALLOW -> "已授权"
                GrantDecision.DENY -> "已拒绝"
                else -> if (def.risk == CapRisk.READ) "默认放行（只读）" else "每次询问"
            }
            "${def.id}｜${def.title}｜${def.risk.label}｜$state\n    参数：${def.arguments}\n    用途：${def.detail}"
        }
    }

    /** 最近能力调用审计的文本快照（谁调的、准没准、成没成、参数摘要）。 */
    fun capabilityAuditText(limit: Int = 20): String {
        val rows = capabilityBridge.audit.value.takeLast(limit.coerceIn(1, 200))
        if (rows.isEmpty()) return "（暂无能力调用记录）"
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
        return rows.joinToString("\n") { c ->
            "${fmt.format(java.util.Date(c.at))} ${c.caller} → ${c.capability}｜${c.decision}｜" +
                (if (c.ok) "成功" else "失败") + "｜${c.argsPreview}" +
                if (c.detail.isBlank()) "" else "｜${c.detail}"
        }
    }

    /** 撤销某调用方对某能力的授权（界面里「不再允许」）；传 null 能力则清空该调用方全部授权。 */
    fun revokeCapabilityGrant(caller: String, capability: String?) {
        if (capability == null) {
            capabilityBridge.clearGrants()
        } else {
            capabilityBridge.setGrant(caller, capability, GrantDecision.DENY)
        }
    }

    /** 用宿主界面的问答通道问一个选择题，返回用户选的标签（取消/超时为 null）。 */
    private suspend fun askChoice(message: String, options: List<String>): String? {
        val answer = askUiRaw("message", message, options)
        return answer?.optJSONArray("selected")?.optString(0)?.takeIf { it.isNotBlank() }
    }

    /** 用宿主界面的问答通道要一段文本（`window.showInputBox` 的落点）。 */
    private suspend fun askInput(pluginId: String, message: String, prefill: String): String? {
        val answer = askUiRaw("input", message, emptyList(), pluginId, prefill)
        return answer?.optString("text")
    }

    private suspend fun askUiRaw(
        kind: String,
        message: String,
        options: List<String>,
        pluginId: String = "nebulaforge",
        prefill: String = ""
    ): JSONObject? {
        val id = uiSeq.getAndIncrement()
        val slot = CompletableDeferred<JSONObject?>()
        uiWaiters[id] = slot
        _uiRequests.value = UiRequest(id, pluginId, kind, message, options, multi = false, prefill = prefill)
        val answer = withTimeoutOrNull(UI_WAIT_MS) { slot.await() }
        uiWaiters.remove(id)
        if (_uiRequests.value?.id == id) _uiRequests.value = null
        return answer
    }

    // ------------------------------------------------------------------ 目录约定

    private val hostDir: File
        get() = File(Environment.homeRoot(context), ".nebulaforge/js-host").apply { mkdirs() }
    private val extRoot: File
        get() = File(Environment.homeRoot(context), ".nebulaforge/js-extensions").apply { mkdirs() }
    private val bootstrapFile: File get() = File(hostDir, "bootstrap.js")
    private val configFile: File get() = File(hostDir, "config.json")
    private val stateDir: File
        get() = File(hostDir, "state").apply { mkdirs() }
    private val bootstrapAsset = "extension-host/bootstrap.js"
    private val builtinAssetRoot = "extension-host/builtin"
    private val builtinDir: File get() = File(hostDir, "builtin")

    // ------------------------------------------------------------------ 生命周期

    /**
     * 按当前已安装插件重建宿主。
     *
     * @param root 当前工作区（注入为扩展的 `workspaceFolders`）；无项目时传 null。
     */
    @Synchronized
    fun refresh(root: File? = workspaceRoot) {
        workspaceRoot = root
        nodeInfo = resolveNode()
        // 只有「用户显式启用」的扩展才允许拉起 node 进程：装了但没启用（或已停用）的扩展
        // 绝不能因为冷启动就跑代码 —— 声明式贡献可以随时被读，可执行 JS 必须有明确的授权动作。
        val summaries = runCatching { DeclarativePluginLoader.of(context).refresh() }
            .getOrElse { emptyList() }
            .filter { it.js != null && activation.isEnabled(it.pluginId) }

        val wanted = summaries.map { it.pluginId }.toSet()
        hosts.keys.filter { it !in wanted }.forEach { stop(it) }

        summaries.forEach { summary ->
            val host = hosts.getOrPut(summary.pluginId) { PluginHost(summary) }
            host.update(summary, root)
            host.ensureStarted()
        }
        publish()
    }

    /** IDE 启动完成后调用：激活 `*` / `onStartupFinished` 扩展（VS Code 语义）。 */
    fun activateStartupExtensions() {
        hosts.values.forEach { it.activateIfEvent(null, startup = true) }
    }

    /** 打开某语言文档时激活 `onLanguage:<id>` 扩展。 */
    fun notifyLanguage(languageId: String) {
        if (languageId.isBlank()) return
        hosts.values.forEach { host ->
            if (host.activationEvents.any { it.equals("onLanguage:$languageId", ignoreCase = true) }) {
                host.activateIfEvent("onLanguage:$languageId")
            }
        }
    }

    fun shutdown() {
        hosts.keys.toList().forEach { stop(it) }
        scope.cancel()
    }

    private fun stop(pluginId: String) {
        hosts.remove(pluginId)?.close()
        publish()
    }

    /**
     * 执行扩展命令（插件详情页的「运行」按钮 / IDE 侧调用都走这里）。
     *
     * 与 VS Code 一致：先按 `onCommand:<id>` 激活尚未激活的属主扩展，再转发执行。
     * @return 失败原因；成功返回 null。
     */
    suspend fun executeCommand(commandId: String, args: JSONArray? = null): String? {
        val owner = hosts.values.firstOrNull { it.owns(commandId) }
            ?: return "没有已安装的扩展注册命令 $commandId"
        owner.activationEvents.firstOrNull { it.equals("onCommand:$commandId", ignoreCase = true) }
            ?.let { owner.activateIfEvent(it)?.join() }
        if (!owner.waitReady(15_000)) return "扩展「${owner.displayName}」尚未就绪：${owner.statusText()}"
        return owner.executeCommand(commandId, args)
    }

    private fun publish() {
        val all = hosts.values.map { it.snapshot() }.sortedBy { it.displayName.lowercase() }
        _states.value = all
        _commands.value = all.flatMap { it.commands }
        _providers.value = all.flatMap { it.providers }
    }

    internal fun appendLog(pluginId: String, level: String, text: String) {
        if (text.isBlank()) return
        val line = LogLine(pluginId, level, text.trimEnd(), System.currentTimeMillis())
        _logs.value = (_logs.value + line).takeLast(400)
        persistLog(line)
    }

    /**
     * 同时落盘一份日志。
     *
     * 为什么必须有：扩展宿主跑在 guest node 里，失败往往发生在「UI 还没拿到状态」之前
     * （解包失败、proot 起不来、activate 抛错）。只留内存日志的话，用户截图里只有一个
     * 「启动失败」，真实原因（`Cannot find module` / `Cannot LINK EXECUTABLE node` …）
     * 根本看不到 —— 排查只能靠猜。落盘后可用 `run-as cat` 直接取证。
     *
     * 有界增长：超过 512KB 就保留尾部一半，避免长跑把私有目录撑爆。
     */
    private fun persistLog(line: LogLine) {
        runCatching {
            val file = File(hostDir, "extension-host.log")
            if (file.length() > 512 * 1024) {
                val tail = file.readText().takeLast(256 * 1024)
                file.writeText(tail)
            }
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(line.at))
            file.appendText("$stamp [${line.level}] ${line.pluginId}: ${line.text}\n")
        }
    }

    /** UI 对 [uiRequests] 作答。 */
    fun answerUi(id: Long, value: JSONObject?) {
        uiWaiters.remove(id)?.complete(value)
        if (_uiRequests.value?.id == id) _uiRequests.value = null
    }

    // ------------------------------------------------------------------ 文档事件

    fun documentOpened(uri: String, languageId: String, text: String, version: Int = 1) {
        notifyLanguage(languageId)
        hosts.values.forEach {
            it.sendNotificationIfRunning(
                "documentOpened",
                JSONObject().put("uri", uri).put("languageId", languageId)
                    .put("text", text).put("version", version)
            )
        }
    }

    fun documentChanged(uri: String, text: String, version: Int) {
        hosts.values.forEach {
            it.sendNotificationIfRunning(
                "documentChanged",
                JSONObject().put("uri", uri).put("text", text).put("version", version)
            )
        }
    }

    fun documentSaved(uri: String) {
        hosts.values.forEach { it.sendNotificationIfRunning("documentSaved", JSONObject().put("uri", uri)) }
    }

    fun documentClosed(uri: String) {
        hosts.values.forEach { it.sendNotificationIfRunning("documentClosed", JSONObject().put("uri", uri)) }
    }

    fun activeDocumentChanged(uri: String?) {
        hosts.values.forEach {
            it.sendNotificationIfRunning("setActiveEditor", JSONObject().put("uri", uri ?: JSONObject.NULL))
        }
    }

    /**
     * 可用的补全 provider（供编辑器补全链路按语言筛选）。
     *
     * 匹配刻意宽松：VS Code 扩展在 `documentSelector` 里写的是自己的语言 id（python / vue / javascript…），
     * 而 IDE 侧语言 id 由语言服务给出，两者未必逐字相同。空选择器与 `*` 一律视为全语言；
     * 其余按小写包含关系匹配（`python` ↔ `Python`、`vue` ↔ `vue-html`），宁可多问一次，
     * 也不让「装了扩展但补全不出现」这种最难排查的情况发生 —— 多问一次的代价只是扩展返回空。
     */
    fun completionProviders(languageId: String): List<ProviderEntry> {
        val wanted = languageId.trim().lowercase()
        return _providers.value.filter { provider ->
            if (provider.kind != "completion") return@filter false
            if (provider.languages.isEmpty()) return@filter true
            provider.languages.any { raw ->
                val declared = raw.trim().lowercase()
                declared.isEmpty() || declared == "*" || wanted.isEmpty() ||
                    declared == wanted || declared.contains(wanted) || wanted.contains(declared)
            }
        }
    }

    /**
     * 编辑器补全的便捷入口：把请求发给所有匹配的 provider 并合并结果。
     *
     * 单个 provider 失败/超时只丢它自己的候选（[completion] 内部已吞异常），不影响其它扩展与本地补全。
     */
    suspend fun completionForDocument(
        uri: String,
        languageId: String,
        text: String,
        line: Int,
        character: Int
    ): List<JsCompletionItem> {
        val providers = completionProviders(languageId)
        if (providers.isEmpty()) return emptyList()
        val merged = ArrayList<JsCompletionItem>()
        providers.forEach { provider ->
            merged += runCatching { completion(provider.id, uri, languageId, text, line, character) }
                .getOrDefault(emptyList())
        }
        return merged
    }

    /**
     * 向扩展的补全 provider 要候选（编辑器补全链路调用）。
     * 失败/超时返回空列表 —— 让内置补全继续工作，绝不因扩展卡住编辑器。
     */
    suspend fun completion(
        providerId: String,
        uri: String,
        languageId: String,
        text: String,
        line: Int,
        character: Int,
        triggerKind: Int = 0,
        triggerCharacter: String? = null
    ): List<JsCompletionItem> {
        val host = hosts.values.firstOrNull { it.hasProvider(providerId) } ?: return emptyList()
        val result = host.request(
            "provideCompletionItems",
            JSONObject().apply {
                put("providerId", providerId)
                put("uri", uri)
                put("languageId", languageId)
                put("text", text)
                put("line", line)
                put("character", character)
                put("triggerKind", triggerKind)
                put("triggerCharacter", triggerCharacter ?: JSONObject.NULL)
            },
            timeoutMs = 6_000
        ) ?: return emptyList()
        val items = result.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val label = item.optString("label")
                if (label.isBlank()) continue
                add(
                    JsCompletionItem(
                        label = label,
                        insertText = item.optString("insertText").takeIf { it.isNotBlank() } ?: label,
                        detail = item.optString("detail").takeIf { it.isNotBlank() },
                        documentation = item.optString("documentation").takeIf { it.isNotBlank() },
                        kind = item.optInt("kind", 0),
                        sortText = item.optString("sortText").takeIf { it.isNotBlank() },
                        filterText = item.optString("filterText").takeIf { it.isNotBlank() },
                        isSnippet = item.optBoolean("isSnippet", false),
                        commitCharacters = item.optJSONArray("commitCharacters")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                        }.orEmpty()
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------ 环境探测

    private fun resolveNode(): NodeInfo? {
        val candidates = listOf(
            File(Environment.binDir(context), "node"),
            File(Environment.usrRoot(context), "bin/node")
        )
        val binary = candidates.firstOrNull { it.isFile } ?: return null
        return NodeInfo(binary)
    }

    /** 把 assets 里的 bootstrap.js 落到磁盘（内容比对，改了才覆盖）。 */
    private fun ensureBootstrap(): File? {
        val bytes = runCatching { context.assets.open(bootstrapAsset).use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            appendLog("-", "error", "无法从 assets 读取扩展宿主 bootstrap（$bootstrapAsset）")
            return null
        }
        val current = if (bootstrapFile.isFile) runCatching { bootstrapFile.readBytes() }.getOrNull() else null
        if (current == null || !current.contentEquals(bytes)) {
            bootstrapFile.parentFile?.mkdirs()
            val ok = runCatching { bootstrapFile.writeBytes(bytes) }.isSuccess
            if (!ok) {
                appendLog("-", "error", "写入 bootstrap 失败：${bootstrapFile.absolutePath}")
                return null
            }
        }
        return bootstrapFile
    }

    /**
     * 把 assets 里的内置扩展（builtin/<name>）铺到 bootstrap.js 同级的 `builtin/` 目录。
     *
     * 为什么必须落到磁盘而不是塞进 JS 常量：真实扩展会把内置扩展当成真实安装的扩展用，
     * 例如 Vue Volar 会 `require.resolve('./dist/extension.js', { paths: [tsExt.extensionPath] })`
     * 再把该文件读出来打补丁 —— 必须存在真实文件，光有内存对象不够。
     */
    private fun ensureBuiltinExtensions() {
        val names = runCatching { context.assets.list(builtinAssetRoot)?.toList() }.getOrNull().orEmpty()
        if (names.isEmpty()) {
            appendLog("-", "warn", "assets 中没有内置扩展（$builtinAssetRoot）")
            return
        }
        for (name in names) copyAssetTree("$builtinAssetRoot/$name", File(builtinDir, name))
    }

    /** 递归复制 assets 子树到磁盘（逐文件内容比对，变了才写，避免每次启动都重写）。 */
    private fun copyAssetTree(assetPath: String, target: File) {
        val children = runCatching { context.assets.list(assetPath)?.toList() }.getOrNull()
        if (children.isNullOrEmpty()) {
            val bytes = runCatching { context.assets.open(assetPath).use { it.readBytes() } }.getOrNull() ?: return
            val current = if (target.isFile) runCatching { target.readBytes() }.getOrNull() else null
            if (current == null || !current.contentEquals(bytes)) {
                target.parentFile?.mkdirs()
                runCatching { target.writeBytes(bytes) }
            }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetTree("$assetPath/$child", File(target, child))
    }

    // ================================================================== 单个扩展宿主

    private inner class PluginHost(private var summary: DeclarativePluginLoader.Summary) {

        val pluginId: String get() = summary.pluginId
        val displayName: String get() = summary.displayName.ifBlank { summary.pluginId }
        private val js: DeclarativePluginLoader.JsExtension get() = summary.js!!

        @Volatile private var status: Status = Status.STOPPED
        @Volatile private var detail: String = "未启动"
        @Volatile private var nodeVersion: String? = null
        @Volatile private var initialized = false
        @Volatile private var activated = false
        @Volatile private var starting = false
        @Volatile private var handle = 0L
        @Volatile private var root: File? = null

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
        private val requestSeq = AtomicLong(1)
        private val writeLock = Any()
        private val registeredCommands = LinkedHashMap<String, String>()   // id -> title（含 package.json 声明）
        /** 运行时真的 `registerCommand` 过的命令 id：与「声明」区分，决定命令能否点动。 */
        private val runtimeCommandIds = LinkedHashSet<String>()
        @Volatile private var activationErrorText: String? = null
        private val registeredProviders = LinkedHashMap<String, ProviderEntry>()

        val activationEvents: List<String> get() = js.activationEvents

        private val extDir: File get() = File(extRoot, pluginId)

        /**
         * guest 的「就绪」信号（bootstrap 启动后发出的第一条通知）。
         *
         * 为什么必须等它：pty 的行规程默认开着回显，我们写进去的**每一帧**都会先被原样回显
         * 出来。真机实测就是栽在这——initialize 请求的回显和自己的响应搅在一起，
         * 解析出的 `Content-Length` 与正文错位，宿主只看到 `End of input at character 223 of
         * {"jsonrpc":"2.0","id":1,...}`，于是「宿主未响应 initialize」。
         *
         * guest 的 `stty -echo` 在 `exec node` **之前**执行，所以 `ready` 到达即代表回显已关、
         * 协议通道干净。此后才允许发送任何请求/通知。
         */
        @Volatile
        private var readySignal = CompletableDeferred<Unit>()

        /** readLoop 与 runHost 共享：就绪后清掉启动期的回显残留。 */
        private val frames = FrameBuffer { noise -> reportNoise(noise) }

        /**
         * 扩展宿主进程：stdin/stdout/stderr 全是 pipe，**不走 tty**。
         *
         * 真机取证（2026-09-23）：同一份 proot 命令用管道跑 1 秒内就回 `ready`，
         * 走 pty 却永远收不到任何字节（node 存活、S 态、卡在 write 上）。tty 行规程
         * 与 proot 的 write 拦截组合不可靠，而 JSON-RPC 也不需要 tty。
         */
        @Volatile
        private var process: Process? = null

        fun update(next: DeclarativePluginLoader.Summary, workspace: File?) {
            summary = next
            root = workspace
            // package.json 声明的命令先入表：未激活也能显示，符合 VS Code 的「命令面板可见」。
            js.commands.forEach { c -> registeredCommands.putIfAbsent(c.command, c.title) }
        }

        fun statusText(): String = detail

        fun owns(commandId: String): Boolean =
            registeredCommands.containsKey(commandId) || js.commands.any { it.command == commandId }

        fun hasProvider(providerId: String): Boolean = registeredProviders.containsKey(providerId)

        fun snapshot(): HostState = HostState(
            pluginId = pluginId,
            displayName = displayName,
            version = summary.version,
            status = status,
            detail = detail,
            nodeVersion = nodeVersion,
            main = js.main,
            activationEvents = activationEvents,
            commands = registeredCommands.map { (id, title) ->
                CommandEntry(
                    pluginId = pluginId,
                    pluginName = displayName,
                    id = id,
                    title = title,
                    registered = runtimeCommandIds.contains(id),
                    declared = js.commands.any { it.command == id }
                )
            },
            providers = registeredProviders.values.toList(),
            activationError = activationErrorText
        )

        @Synchronized
        fun ensureStarted() {
            if (starting || handle != 0L) return
            val node = nodeInfo
            if (node == null) {
                status = Status.NO_NODE
                detail = "未安装 Node.js；请在终端执行 pkg install nodejs"
                appendLog(pluginId, "error", "$displayName：$detail")
                return
            }
            val bootstrap = ensureBootstrap()
            if (bootstrap == null) {
                status = Status.FAILED
                detail = "扩展宿主脚本不可用"
                return
            }
            // 内置扩展与 bootstrap.js 同级，必须一起就位：扩展在 require 阶段就会读它们。
            ensureBuiltinExtensions()
            val dir = extractPayload()
            if (dir == null) {
                status = Status.FAILED
                detail = "无法解包扩展负载"
                return
            }
            starting = true
            status = Status.STARTING
            detail = "启动 Node 扩展宿主…"
            appendLog(pluginId, "info", "$displayName：启动 JS 宿主（${node.binary.absolutePath}）")
            scope.launch { runHost(node, bootstrap, dir) }
        }

        fun activateIfEvent(event: String?, startup: Boolean = false): Job? {
            val wildcard = activationEvents.any { it == "*" }
            val allowed = when {
                wildcard -> true
                startup -> activationEvents.any {
                    it.equals("onStartupFinished", true) || it.equals("onStartup", true)
                }
                event != null -> activationEvents.any { it.equals(event, ignoreCase = true) }
                else -> false
            }
            if (!allowed || status != Status.RUNNING) return null
            val reason = event ?: if (startup) "onStartupFinished" else "api"
            return scope.launch {
                val result = request("activate", JSONObject().put("event", reason), timeoutMs = 20_000)
                when {
                    result == null -> {
                        detail = "激活超时（插件 activate() 可能未返回）"
                        activationErrorText = detail
                    }
                    result.optBoolean("ok", false) -> {
                        activated = true
                        detail = "已激活（$reason）"
                        activationErrorText = null
                        appendLog(pluginId, "info", "$displayName：已激活（$reason）")
                    }
                    else -> {
                        val reason2 = result.optString("error").take(400)
                        activationErrorText = reason2
                        detail = "激活失败：${reason2.take(200)}"
                    }
                }
                if (detail.startsWith("激活")) appendLog(pluginId, "warn", "$displayName：$detail")
                publish()
            }
        }

        /** 阻塞等待 initialize 完成；供 `executeCommand` 在激活竞态下兜底。 */
        suspend fun waitReady(timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (status == Status.RUNNING && initialized) return true
                if (status == Status.FAILED || status == Status.NO_NODE) return false
                delay(100)
            }
            return status == Status.RUNNING && initialized
        }

        // ---------------------------------------------------------- 负载解包

        /**
         * 把转换包里的载荷（默认 `extension/` 前缀下的文件）解到私有目录。
         *
         * 用「源包大小:时间戳:文件数」做标记，避免每次开 IDE 都重解（Volar 这类扩展上千文件）。
         */
        private fun extractPayload(): File? {
            // 容错：兼容旧描述符/其它构造点里可能存过裸文件名的情况，按插件目录兜底解析。
            val declared = File(summary.packageFile)
            val zipFile = when {
                declared.isAbsolute && declared.isFile -> declared
                declared.isAbsolute -> declared
                else -> File(Environment.pluginsDir(context), summary.packageFile)
            }
            if (!zipFile.isFile) {
                appendLog(pluginId, "error", "转换包不存在：${summary.packageFile}（解析为 ${zipFile.absolutePath}）")
                return null
            }
            val marker = File(extDir, ".nebula-payload.json")
            val stamp = "${zipFile.length()}:${zipFile.lastModified()}:${js.payloadFiles}"
            if (marker.isFile && payloadLooksComplete()) {
                val saved = runCatching { JSONObject(marker.readText()) }.getOrNull()
                if (saved?.optString("stamp") == stamp) return extDir
            }
            extDir.deleteRecursively()
            extDir.mkdirs()
            val prefix = js.payload.trimEnd('/').ifBlank { "extension" }
            var written = 0
            val failure = runCatching {
                ZipFile(zipFile).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name.removePrefix("./")
                        if (!name.startsWith("$prefix/")) continue
                        val relative = name.substring(prefix.length + 1)
                        if (relative.isBlank()) continue
                        if (relative == ".vsixmanifest" || relative == "[Content_Types].xml") continue
                        val target = File(extDir, relative)
                        // 载荷来自外部下载的包，必须防 ZipSlip 逃逸。
                        if (!target.canonicalPath.startsWith(extDir.canonicalPath + File.separator)) {
                            appendLog(pluginId, "warn", "跳过非法路径条目：$name")
                            continue
                        }
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        written++
                    }
                }
            }.exceptionOrNull()
            if (failure != null) {
                appendLog(pluginId, "error", "解包扩展负载失败：${failure.message}")
                return null
            }
            if (written == 0) {
                appendLog(pluginId, "error", "转换包里没有 $prefix/ 载荷，JS 入口无法运行：${zipFile.name}")
                return null
            }
            runCatching {
                marker.writeText(JSONObject().put("stamp", stamp).put("files", written).toString())
            }
            appendLog(pluginId, "info", "已释放扩展负载：$written 个文件")
            return extDir
        }

        private fun payloadLooksComplete(): Boolean =
            File(extDir, "package.json").isFile || js.main?.let { File(extDir, it).isFile } == true

        // ---------------------------------------------------------- 进程与协议

        private suspend fun runHost(node: NodeInfo, bootstrap: File, dir: File) {
            try {
                // 每次启动都重新装填：stop → start 会复用同一个 PluginHost 实例，
                // 沿用上一轮已完成/已污染的信号会让这一轮跳过等待、又被回显打乱协议。
                readySignal = CompletableDeferred()
                frames.reset()
                val env = Environment.buildNodeEnv(context).toMutableMap().apply {
                    put("TERM", "dumb")
                    put("NODE_NO_WARNINGS", "1")
                    put("NEBULA_EXTENSION_HOST", "1")
                    put("NEBULA_PLUGIN_ID", pluginId)
                }
                fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
                // 命令行整体交给 guest 的 proot 拼装（与终端同一套：bind / PROOT_* / env 都靠它），
                // 但**不经过 pty**：宿主直接以管道形态起 `sh -c <命令行>`。
                val inner = "exec ${q(node.binary.absolutePath)} " +
                    "${q(bootstrap.absolutePath)} ${q(dir.absolutePath)}"
                val command = TermuxGuest.guestCommandLine(context, inner, env, dir)
                runCatching { launchScriptFile.writeText(command + "\n") }
                appendLog(
                    pluginId, "info",
                    "启动 JS 宿主（管道直连，无 tty）：sh -c <${command.length} 字节>；" +
                        "命令行已落盘 ${launchScriptFile.absolutePath}"
                )
                val started = withContext(Dispatchers.IO) {
                    runCatching {
                        ProcessBuilder("/system/bin/sh", "-c", command).start()
                    }
                }
                val proc = started.getOrElse { error ->
                    starting = false
                    status = Status.FAILED
                    detail = "无法启动扩展宿主进程：${error.message}"
                    appendLog(pluginId, "error", "$displayName：$detail")
                    publish()
                    return
                }
                process = proc
                val local = 1L
                handle = local
                scope.launch { readLoop(local) }
                scope.launch { errorLoop(proc) }

                // 等 guest 明确就绪再开口，避免启动期回显污染协议（详见 readySignal 注释）。
                val ready = withTimeoutOrNull(30_000) { readySignal.await() }
                if (ready == null) {
                    // 先取证再宣告失败：探针结果会落进日志文件，下次不用猜。
                    runCatching { probeOnTimeout(node.binary) }
                    starting = false
                    status = Status.FAILED
                    detail = "扩展宿主未就绪：guest 未回 ready（node 未能启动？详见扩展宿主日志）"
                    appendLog(pluginId, "error", "$displayName：$detail")
                    close()
                    return
                }
                // 丢掉 stty -echo 之前那批回显残留，别让它混进后续帧。
                frames.reset()

                val init = request(
                    "initialize",
                    JSONObject().apply {
                        put("extensionId", pluginId)
                        put("displayName", displayName)
                        put("version", summary.version)
                        put("workspaceFolders", JSONArray().apply {
                            root?.let {
                                put(JSONObject().put("uri", it.toURI().toString()).put("name", it.name))
                            }
                        })
                        put("state", loadState())
                    },
                    timeoutMs = 20_000
                )
                starting = false
                if (init == null) {
                    // 进程起了但没回应：把真实原因留给日志，绝不伪造「运行中」。
                    status = Status.FAILED
                    detail = "扩展宿主未响应 initialize（详见扩展宿主日志）"
                } else {
                    initialized = true
                    nodeVersion = init.optString("nodeVersion").takeIf { it.isNotBlank() }
                    status = Status.RUNNING
                    detail = buildString {
                        append("已运行")
                        nodeVersion?.let { append("（node $it）") }
                        when {
                            init.optBoolean("mainResolved", false) -> append("｜JS 入口已加载")
                            js.main != null -> append("｜⚠ 未找到 JS 入口 ${js.main}")
                        }
                    }
                    appendLog(pluginId, "info", "$displayName：$detail")
                    activateIfEvent(null, startup = true)
                }
            } catch (t: Throwable) {
                starting = false
                status = Status.FAILED
                detail = "扩展宿主启动异常：${t.message}"
                appendLog(pluginId, "error", "$displayName：$detail")
            } finally {
                starting = false
                publish()
            }
        }

        /**
         * 生成写进 PTY 的**宿主 shell 单行命令**。
         *
         * ## 为什么必须包一层 proot（而不是像 LSP 那样直接 exec）
         * 内置 userland 的 node 是带 Termux 前缀的动态链接程序（interpreter / shebang 写死
         * `/data/data/com.termux/files/usr/...`）。在宿主机（App 私有目录前缀）直接 exec 会得到
         * 前缀错位：`CANNOT LINK EXECUTABLE node: library "libnode.so" not found` /
         * `bad interpreter: ... Permission denied` —— 现象是「扩展宿主起了但 initialize 永不应答」。
         * 真机实测同类症状见 [TermuxGuest.hostProotEnv] 的清单；Python / Git 也栽在同一处。
         * 因此这里复用 [TermuxGuest.guestCommandLine]（内含宿主侧 PROOT_* 前置 export），
         * 与构建、工具链探测走同一条前缀对齐路径，不另写一份合并逻辑。
         *
         * 两处 `stty -echo`：外层关宿主 PTY 回显、内层关 guest PTY 回显——否则写进 stdin 的
         * JSON-RPC 帧会被回显成噪声，帧解析直接错位。
         */
        /** 启动脚本落盘路径（同时用于探针取证）。 */
        private val launchScriptFile: File
            get() = File(hostDir, "launch-" + pluginId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".sh")

        /**
         * 把整条 proot 启动命令**落盘**，PTY 里只键入一行 `. '<脚本>'`。
         *
         * 为什么不直接把命令当一行敲进去：见 [write] 的注释——pty 短写会把超长行截断，
         * 截断点之后连结尾换行都丢了，外层 shell 会一直等这一行结束，表现就是
         * 「进程起了但零输出」。短行（几十字节）远小于 pty 缓冲，配合写满循环才可靠。
         */
        private fun launchLine(
            dir: File,
            node: File,
            bootstrap: File,
            env: Map<String, String>
        ): ByteArray {
            fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
            // 宿主绝对路径在 guest 内同样可见（filesDir 整体被绑定），无需换算前缀。
            val inner = "stty -echo 2>/dev/null; exec ${q(node.absolutePath)} " +
                "${q(bootstrap.absolutePath)} ${q(dir.absolutePath)}"
            val wrapped = TermuxGuest.guestCommandLine(context, inner, env, dir)
            val script = "stty -echo 2>/dev/null\n$wrapped\n"
            val file = launchScriptFile
            runCatching { file.writeText(script) }
                .onFailure { appendLog(pluginId, "error", "启动脚本落盘失败：${it.message}") }
            return (". ${q(file.absolutePath)}\n").toByteArray(Charsets.UTF_8)
        }

        /**
         * 就绪超时后的取证探针：把「pty 子进程是否还活着 / node 在该上下文能否执行」
         * 直接打进日志，避免下一次又只能靠猜。
         */
        /**
         * 就绪超时的取证：进程存活 / 退出码 / 命令行落盘位置。guest 侧的输出本来就由
         * [errorLoop] 如实进日志，这里只补「进程还在不在」这一层事实。
         */
        private fun probeOnTimeout(node: File) {
            val proc = process
            val alive = proc?.isAlive == true
            val exit = if (proc != null && !alive) runCatching { proc.exitValue() }.getOrNull() else null
            appendLog(
                pluginId, "warn",
                "就绪超时取证：进程存活=$alive 退出码=$exit node=${node.absolutePath} " +
                    "命令行=${launchScriptFile.absolutePath}"
            )
        }

        /**
         * 写入必须**写满**。
         *
         * PTY 是行缓冲设备：从端未排空输入队列时主设备会短写，而 `nativeWrite` 返回实际字节数。
         * 原实现忽略返回值只写一次，于是长命令被静默截断——真机现象是「命令只回显到一半、
         * 随后什么都没发生」（`TermuxCommandExecutor` 顶部注释记录了同一个坑）。
         * 这里循环补写，并把异常情况如实写进日志，不让截断再次伪装成「node 起不来」。
         */
        private fun write(bytes: ByteArray) {
            val out = process?.outputStream ?: run {
                appendLog(pluginId, "warn", "扩展宿主未启动，${bytes.size} 字节写入被丢弃")
                return
            }
            synchronized(writeLock) {
                runCatching {
                    // 管道写不需要「写满循环」：OutputStream.write 要么写完要么抛异常。
                    out.write(bytes)
                    out.flush()
                }.onFailure {
                    appendLog(pluginId, "error", "写入扩展宿主失败：${it.message}")
                }
            }
        }

        private fun frame(message: JSONObject): ByteArray {
            val body = message.toString().toByteArray(Charsets.UTF_8)
            return "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII) + body
        }

        private fun sendJson(message: JSONObject) = write(frame(message))

        fun sendNotificationIfRunning(method: String, params: JSONObject) {
            if (status != Status.RUNNING) return
            sendJson(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))
        }

        suspend fun request(method: String, params: JSONObject, timeoutMs: Long = 15_000): JSONObject? {
            if (handle == 0L) return null
            val id = requestSeq.getAndIncrement().toInt()
            val slot = CompletableDeferred<JSONObject>()
            pending[id] = slot
            sendJson(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
            val reply = withTimeoutOrNull(timeoutMs) { slot.await() }
            pending.remove(id)
            if (reply == null) appendLog(pluginId, "warn", "请求 $method 超时（${timeoutMs}ms）")
            return reply
        }

        /**
         * 宿主 → 扩展：调用扩展用 `vscode.lm.registerTool` 注册的模型工具。
         *
         * 方向与 [executeCommand] 相同（宿主发起、扩展应答），这样 IDE 内的 AI Agent
         * 才能真正复用插件提供的工具，而不是只把它们列出来当摆设。
         */
        suspend fun invokeLmTool(name: String, input: JSONObject): GuestResult {
            val reply = request(
                "invokeLmTool",
                JSONObject().put("name", name).put("input", input),
                30_000
            ) ?: return fail("扩展未响应工具调用：$name（可能没有注册该工具）")
            val text = reply.optString("text")
            return if (reply.optBoolean("ok", true)) ok(JSONObject().put("text", text)) else fail(text)
        }

        /**
         * WebView → 扩展：投递界面消息（对应扩展的 `webview.onDidReceiveMessage`）。
         *
         * 用 request 而不是 notify，是为了在日志里能区分"扩展没有监听"和"消息已送达"。
         */
        suspend fun deliverWebviewMessage(panelId: String, messageJson: String): Boolean {
            val message = runCatching { JSONTokener(messageJson).nextValue() }.getOrElse { messageJson }
            // 30s + 一次重试：扩展侧（node）可能正忙着启动 CLI / 处理界面消息，proot 下
            // CPU 被抢占时 10s 是够不着的 —— 一旦超时，界面就停在空白，用户看到的正是
            // 「面板打开了但什么都没显示」。宁可多等一次，也不要让界面数据静默丢失。
            repeat(2) { attempt ->
                val reply = request(
                    "webview.deliver",
                    JSONObject().put("id", panelId).put("message", message),
                    timeoutMs = 30_000
                )
                if (reply == null) {
                    if (attempt == 0) {
                        appendLog(pluginId, "info", "界面消息投递超时，重试一次（面板 $panelId）")
                    }
                    return@repeat
                }
                if (!reply.optBoolean("ok", false)) {
                    val reason = reply.optString("error").ifBlank { "扩展未响应该面板" }
                    appendLog(pluginId, "warn", "界面消息投递失败：$reason")
                    return false
                }
                return true
            }
            return false
        }

        /** 宿主侧关闭面板 → 触发扩展的 `onDidDispose`，让扩展有机会清理资源。 */
        suspend fun disposeWebviewPanel(panelId: String) {
            request("webview.dispose", JSONObject().put("id", panelId), timeoutMs = 5_000)
        }

        /**
         * 解析侧边视图提供者成真实面板（对应 `resolveWebviewView`）。
         * 返回 null 表示成功，否则返回可读错误文本。
         */
        suspend fun resolveWebviewView(viewId: String): String? {
            if (!waitReady(10_000)) return "扩展「$displayName」尚未就绪：${statusText()}"
            val reply = request("webview.resolveView", JSONObject().put("viewId", viewId), timeoutMs = 20_000)
                ?: return "扩展未响应视图解析请求"
            if (reply.optBoolean("ok", false)) return null
            return reply.optString("error").ifBlank { "扩展未能提供视图 $viewId" }
        }

        suspend fun executeCommand(commandId: String, args: JSONArray?, waitMs: Long = 20_000): String? {
            val result = request(
                "executeCommand",
                JSONObject().put("command", commandId).put("args", args ?: JSONArray())
                    // 大扩展（vscode-go）在 activate() 后段才注册部分命令，宿主要给一个等待窗口，
                    // 否则「激活进行中」会被误报成「命令不存在」。
                    .put("waitMs", waitMs),
                timeoutMs = 60_000
            ) ?: return "扩展「$displayName」未响应命令 $commandId"
            if (result.optBoolean("ok", false)) return null
            val reason = result.optString("error").ifBlank { "命令 $commandId 执行失败" }
            val activation = activationErrorText
            return if (activation.isNullOrBlank()) reason else "$reason（扩展激活状态：$activation）"
        }

        private suspend fun readLoop(local: Long) {
            val proc = process ?: return
            val input = BufferedInputStream(proc.inputStream)
            val buffer = ByteArray(32 * 1024)
            try {
                while (handle == local) {
                    val n = withContext(Dispatchers.IO) { input.read(buffer) }
                    if (n <= 0) break
                    frames.append(buffer, n)
                    while (true) {
                        val message = frames.nextMessage() ?: break
                        dispatch(message)
                    }
                }
            } catch (t: Throwable) {
                appendLog(pluginId, "error", "扩展宿主读取异常：${t.message}")
            }
            if (handle == local) {
                handle = 0L
                initialized = false
                activated = false
                if (status == Status.RUNNING || status == Status.STARTING) {
                    status = Status.FAILED
                    detail = "扩展宿主进程已退出"
                    appendLog(pluginId, "error", "$displayName：扩展宿主进程已退出")
                }
                pending.values.forEach { it.complete(JSONObject()) }
                pending.clear()
                publish()
            }
        }

        /**
         * stderr 单独一条通道：node 的崩溃栈、扩展的 console.error、proot/bash 的报错
         * 全在这里。管道模式下它不再和协议帧抢同一个 fd，历史上「零输出」的盲区就此消失。
         */
        private suspend fun errorLoop(proc: Process) {
            val input = BufferedInputStream(proc.errorStream)
            val buffer = ByteArray(8 * 1024)
            try {
                while (true) {
                    val n = withContext(Dispatchers.IO) { input.read(buffer) }
                    if (n <= 0) break
                    reportNoise(String(buffer, 0, n, Charsets.UTF_8))
                }
            } catch (t: Throwable) {
                appendLog(pluginId, "warn", "扩展宿主 stderr 读取异常：${t.message}")
            }
        }

        /** 帧外文本（node 警告 / 扩展 stderr / 启动错误）如实进日志，不静默丢弃。 */
        private fun reportNoise(text: String) {
            text.split('\n').map { it.trim() }.filter { it.isNotBlank() }.take(12).forEach { line ->
                val trimmed = if (line.length > 240) line.take(240) + "…" else line
                val level = when {
                    line.contains("Cannot find", true) || line.contains("Error:", true) -> "error"
                    line.contains("Warning", true) || line.startsWith("(node:") -> "warn"
                    else -> "info"
                }
                appendLog(pluginId, level, trimmed)
            }
        }

        private fun dispatch(message: JSONObject) {
            val id = message.optInt("id", -1)
            val method = message.optString("method")
            if (method.isBlank()) {
                // 响应帧：唤醒等待中的 request()。
                //
                // 必须**剥掉 result 包装**再交付：guest 的帧形如
                // `{"jsonrpc":"2.0","id":1,"result":{...}}`，而全部调用方（initialize /
                // completion / executeCommand / configuration …）都在读 result 里面的字段。
                // 交整帧时这些字段一律读空，真机表现就是：`mainResolved` 恒为 false
                // （「⚠ 未找到 JS 入口」）、扩展补全恒 0 条、每个扩展命令都报失败 ——
                // 一整类「JS 扩展装了也不生效」都出自这一处。
                if (id > 0) {
                    val slot = pending.remove(id)
                    if (slot != null) {
                        val error = message.optJSONObject("error")
                        if (error != null) {
                            appendLog(
                                pluginId, "warn",
                                "guest 响应错误（id=$id）：${error.optString("message").take(200)}"
                            )
                            slot.complete(JSONObject())
                        } else {
                            slot.complete(message.optJSONObject("result") ?: JSONObject())
                        }
                    }
                }
                // 并发限流：扩展通常只会反调几个 API，顺序处理足够且避免 UI 排队混乱。
                return
            }
            val params = message.optJSONObject("params") ?: JSONObject()
            if (id > 0) {
                scope.launch {
                    val result = handleGuestRequest(this@PluginHost, method, params)
                    val reply = JSONObject().put("jsonrpc", "2.0").put("id", id)
                    if (result.ok) reply.put("result", result.value ?: JSONObject.NULL)
                    else reply.put("error", JSONObject().put("code", -32000).put("message", result.error))
                    sendJson(reply)
                }
            } else {
                handleGuestNotification(method, params)
            }
        }

        private fun handleGuestNotification(method: String, params: JSONObject) {
            when (method) {
                "ready" -> {
                    nodeVersion = params.optString("node").takeIf { it.isNotBlank() } ?: nodeVersion
                    appendLog(pluginId, "info", "扩展宿主就绪（node ${params.optString("node")}）")
                    readySignal.complete(Unit)
                }
                "log" -> appendLog(pluginId, params.optString("level", "info"), params.optString("text"))
                "output" -> {
                    val channel = params.optString("channel", "扩展输出")
                    val text = params.optString("text")
                    if (params.optBoolean("clear", false)) appendLog(pluginId, "info", "[$channel] 已清空输出")
                    else appendLog(pluginId, "info", "[$channel] ${text.trimEnd()}")
                }
                "statusBar" -> {
                    val text = params.optString("text")
                    if (text.isNotBlank()) appendLog(pluginId, "info", "状态栏：$text")
                }
                "registerCommand" -> {
                    val id = params.optString("id")
                    if (id.isNotBlank()) {
                        registeredCommands[id] = js.commands.firstOrNull { it.command == id }?.title ?: id
                        runtimeCommandIds.add(id)
                        publish()
                    }
                }
                "unregisterCommand" -> {
                    val id = params.optString("id")
                    // 保留 package.json 里声明的条目：VS Code 里命令依然是「已声明」的。
                    if (id.isNotBlank()) {
                        runtimeCommandIds.remove(id)
                        if (js.commands.none { it.command == id }) {
                            registeredCommands.remove(id)
                        }
                        publish()
                    }
                }
                "registerProvider" -> {
                    val id = params.optString("id")
                    val kind = params.optString("kind")
                    if (id.isNotBlank()) {
                        val languages = params.optJSONArray("languages")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                        }.orEmpty()
                        val triggers = params.optJSONArray("triggerCharacters")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                        }.orEmpty()
                        registeredProviders[id] = ProviderEntry(
                            pluginId = pluginId,
                            kind = kind,
                            id = id,
                            languages = languages,
                            triggerCharacters = triggers,
                            driven = kind == "completion"
                        )
                        if (kind != "completion") {
                            appendLog(pluginId, "warn", "扩展注册了 $kind，宿主尚未驱动该能力（已如实登记）")
                        }
                        publish()
                    }
                }
                "unregisterProvider" -> {
                    registeredProviders.remove(params.optString("id"))
                    publish()
                }
                "diagnostics.update" -> {
                    val uri = params.optString("uri")
                    val path = uriToPath(uri)
                    val items = params.optJSONArray("items")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val d = arr.optJSONObject(i) ?: return@mapNotNull null
                            DiagnosticEntry(
                                message = d.optString("message"),
                                severity = d.optInt("severity", 1),
                                line = d.optInt("line", 0) + 1,
                                character = d.optInt("character", 0) + 1,
                                source = d.optString("source").takeIf { it.isNotBlank() }
                            )
                        }
                    }.orEmpty()
                    if (path != null) diagnosticSink?.invoke(pluginId, path, items)
                    appendLog(pluginId, "info", "诊断更新：${path ?: uri}（${items.size} 条）")
                }
                "terminal.create" -> appendLog(
                    pluginId, "info",
                    "扩展请求终端「${params.optString("name")}」；IDE 未提供真实终端会话，已登记"
                )
                "terminal.sendText" -> {
                    val name = params.optString("name")
                    val text = params.optString("text")
                    val sink = terminalSink
                    if (sink != null) {
                        sink(name, text)
                        appendLog(pluginId, "info", "[终端 $name] ${text.trimEnd()}")
                    } else {
                        appendLog(pluginId, "warn", "扩展往终端「$name」写入但 IDE 未接入终端：${text.take(200)}")
                    }
                }
                "terminal.show" -> appendLog(pluginId, "info", "扩展请求显示终端「${params.optString("name")}」")
                // ------------------------------------------------------ 资源注册类通知
                // 这些在旧版本里落进 else 分支、按 warn 刷屏。它们本身是**正常协议**：
                // 扩展把 provider/watcher 注册进来，宿主登记即可（真正的读取已由
                // workspace.fs / 文档内容 provider 走请求通道处理）。
                "registerFileSystemProvider", "unregisterFileSystemProvider" -> {
                    val scheme = params.optString("scheme")
                    if (scheme.isNotBlank()) fileSystemSchemes.add(scheme.lowercase())
                    if (method.startsWith("unregister")) fileSystemSchemes.remove(scheme.lowercase())
                    appendLog(
                        pluginId, "info",
                        if (method.startsWith("unregister")) "注销文件系统 provider：$scheme"
                        else "注册文件系统 provider：$scheme（workspace.fs 已按 scheme 路由到扩展实现）"
                    )
                }
                "registerTextDocumentContentProvider", "unregisterTextDocumentContentProvider" -> {
                    val scheme = params.optString("scheme")
                    appendLog(pluginId, "info", "文本内容 provider（$scheme）${if (method.startsWith("unregister")) "已注销" else "已注册"}")
                }
                "createFileSystemWatcher", "disposeFileSystemWatcher" -> {
                    val pattern = params.optString("pattern")
                    if (method == "createFileSystemWatcher") watcherCount.incrementAndGet() else watcherCount.decrementAndGet()
                    appendLog(pluginId, "info", "文件监视器 $pattern ${if (method == "createFileSystemWatcher") "已创建" else "已释放"}")
                }
                // ------------------------------------------------- WebView 面板（真实渲染）
                // 扩展把界面画在 WebView 里，这些通知就是它的"窗口"：宿主据此建面板、
                // 收 html、双向转发消息。以前这里只有日志，插件界面永远打不开。
                "webview.create" -> {
                    val id = params.optString("id")
                    if (id.isBlank()) {
                        appendLog(pluginId, "warn", "扩展请求 WebView 面板但没有 id，已忽略")
                    } else {
                        upsertPanel(
                            pluginId = pluginId,
                            id = id,
                            viewType = params.optString("viewType"),
                            title = params.optString("title"),
                            enableScripts = params.optBoolean("enableScripts", true),
                            rootDir = params.optString("rootDir").takeIf { it.isNotBlank() },
                        )
                        appendLog(pluginId, "info", "已打开插件界面「${params.optString("title").ifBlank { params.optString("viewType") }}」")
                    }
                }
                "webview.setHtml" -> {
                    val id = params.optString("id")
                    val html = params.optString("html")
                    if (id.isNotBlank()) {
                        upsertPanel(pluginId = pluginId, id = id, viewType = params.optString("viewType"), html = html)
                        appendLog(pluginId, "info", "插件界面内容已更新（${html.length} 字节）")
                    }
                }
                "webview.title" -> {
                    val id = params.optString("id")
                    if (id.isNotBlank()) upsertPanel(pluginId, id, params.optString("viewType"), title = params.optString("title"))
                }
                "webview.reveal" -> {
                    val id = params.optString("id")
                    if (id.isNotBlank()) {
                        upsertPanel(pluginId, id, params.optString("viewType"))
                        appendLog(pluginId, "info", "扩展请求显示插件界面 $id")
                    }
                }
                "webview.postMessage" -> {
                    // 扩展 → WebView。界面还没取走前先入队，避免"扩展先发、界面后加载"时丢消息。
                    val id = params.optString("id")
                    if (id.isNotBlank()) {
                        val message = params.opt("message") ?: org.json.JSONObject.NULL
                        if (_panels.value.any { it.id == id }) appendPanelOutbox(id, message.toString())
                        else appendLog(pluginId, "warn", "面板 $id 不在打开状态，${message.toString().length} 字节消息被丢弃")
                    }
                }
                "webview.dispose" -> {
                    val id = params.optString("id")
                    if (id.isNotBlank()) {
                        removePanel(id)
                        appendLog(pluginId, "info", "插件界面已关闭：$id")
                    }
                }
                "registerWebviewViewProvider", "unregisterWebviewViewProvider",
                "registerWebviewPanelSerializer", "unregisterWebviewPanelSerializer" -> {
                    val id = params.optString("viewId").ifBlank { params.optString("viewType") }
                    val removing = method.startsWith("unregister")
                    _webviews.value = if (removing) _webviews.value.filterNot { it.id == id && it.pluginId == pluginId }
                    else _webviews.value.filterNot { it.id == id && it.pluginId == pluginId } + WebviewEntry(pluginId, id, method)
                    appendLog(pluginId, "info", "WebView 注册：$id（${if (removing) "已注销" else method}）")
                }
                // ------------------------------------------------------ 进度与模型工具
                "progress.begin" -> {
                    val title = params.optString("title").ifBlank { "任务" }
                    _progress.value = (_progress.value.filterNot { it.first == pluginId } + (pluginId to title)).takeLast(20)
                    appendLog(pluginId, "info", "开始进度：$title")
                }
                "progress.report" -> {
                    val title = params.optString("title")
                    val message = params.optString("message")
                    _progress.value = _progress.value.map {
                        if (it.first == pluginId) pluginId to (message.ifBlank { it.second }) else it
                    }
                    if (message.isNotBlank()) appendLog(pluginId, "info", "进度：${title.ifBlank { "" }} $message".trim())
                }
                "progress.end" -> {
                    val title = params.optString("title")
                    _progress.value = _progress.value.filterNot { it.first == pluginId }
                    appendLog(pluginId, "info", "结束进度：${title.ifBlank { "" }}".trim())
                }
                "lm.registerTool", "lm.unregisterTool" -> {
                    val name = params.optString("name")
                    if (name.isNotBlank()) {
                        if (method == "lm.registerTool") lmTools["$pluginId::$name"] = name else lmTools.remove("$pluginId::$name")
                        appendLog(pluginId, "info", "模型工具 ${if (method == "lm.registerTool") "已注册" else "已注销"}：$name")
                    }
                }

                // ------------------------------------------------------ 兼容层上报
                "unsupportedApi" -> {
                    val api = params.optString("api")
                    if (api.isNotBlank() && _apiGaps.value.none { it == api }) {
                        _apiGaps.value = (_apiGaps.value + api).takeLast(200)
                    }
                }
                "extensionCrash" -> {
                    val stack = params.optString("stack").lines().take(6).joinToString("\n")
                    _isolatedCrashes.value = (_isolatedCrashes.value + (pluginId to stack)).takeLast(50)
                }
                else -> appendLog(pluginId, "warn", "扩展宿主收到未处理的扩展通知：$method")
            }
        }

        // ---------------------------------------------------------- 会话状态

        /** 读回扩展的 globalState / workspaceState（跨会话保持，与 VS Code 的 Memento 对应）。 */
        private fun loadState(): JSONObject {
            val file = File(stateDir, "$pluginId.json")
            val saved = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
            return JSONObject().apply {
                put("global", saved.optJSONObject("global") ?: JSONObject())
                put("workspace", saved.optJSONObject("workspace") ?: JSONObject())
            }
        }

        fun persistState(key: String, value: Any?, global: Boolean) {
            val file = File(stateDir, "$pluginId.json")
            val saved = runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()
            val bucket = saved.optJSONObject(if (global) "global" else "workspace") ?: JSONObject()
            if (value == null) bucket.remove(key) else bucket.put(key, value)
            saved.put(if (global) "global" else "workspace", bucket)
            runCatching { file.writeText(saved.toString()) }
        }

        fun close() {
            val h = handle
            initialized = false
            activated = false
            pending.values.forEach { it.complete(JSONObject()) }
            pending.clear()
            handle = 0L
            status = Status.STOPPED
            detail = "已停止"
            val proc = process
            process = null
            if (h != 0L && proc != null) {
                // 先关 stdin：guest 的 bootstrap 收到管道 EOF 会自己 exit（防孤儿 node）。
                runCatching { proc.outputStream.close() }
                runCatching { proc.destroy() }
                runCatching { if (proc.isAlive) proc.destroyForcibly() }
                runCatching { proc.errorStream.close() }
                runCatching { proc.inputStream.close() }
            }
        }
    }

    // ================================================================== 扩展 → 宿主

    private data class GuestResult(val ok: Boolean, val value: Any? = null, val error: String = "")

    private fun ok(value: Any? = null) = GuestResult(true, value)
    private fun fail(message: String) = GuestResult(false, error = message)

    /**
     * 处理扩展对宿主的反向请求。
     *
     * 未实现的 API 一律「明确失败 + 记日志」，而不是返回假成功 —— 否则扩展会以为自己
     * 写入了文件 / 弹出了对话框，行为与 VS Code 不一致且难以排查。
     */
    private suspend fun handleGuestRequest(host: PluginHost, method: String, params: JSONObject): GuestResult {
        return try {
            when (method) {
                // ---------------------------------------------------------- 窗口交互
                "window.showMessage" -> {
                    val level = params.optString("kind", "info")
                    val message = params.optString("message")
                    val options = params.optJSONArray("items")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
                    }.orEmpty()
                    _messages.tryEmit(HostMessage(host.pluginId, level, message, options))
                    appendLog(host.pluginId, if (level == "error") "error" else "info", "提示：$message")
                    if (options.isEmpty()) ok(JSONObject.NULL)
                    else ok(askUi(host, "message", message, options, multi = false))
                }

                "window.showQuickPick" -> {
                    val items = params.optJSONArray("items")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.optString("label")?.takeIf { it.isNotBlank() }
                        }
                    }.orEmpty()
                    val placeholder = params.optString("placeholder").takeIf { it.isNotBlank() }
                        ?: "请选择"
                    val multi = params.optBoolean("canPickMany", false)
                    if (items.isEmpty()) ok(JSONObject.NULL)
                    else ok(askUi(host, "quickPick", placeholder, items, multi))
                }

                "window.showInputBox" -> {
                    val prompt = params.optString("prompt")
                        .ifBlank { params.optString("placeHolder") }
                        .ifBlank { "请输入" }
                    val value = askInput(host.pluginId, prompt, params.optString("value"))
                    if (value == null) ok(JSONObject.NULL)
                    else ok(JSONObject().put("selected", JSONArray().put(value)))
                }

                // vscode.env.openExternal：插件「点登录 / 打开文档 / 跳转控制台」全靠它。
                // 以前返回假成功（true 但不做任何事）→ 浏览器不弹、界面一直转
                // （Claude Code 面板点 Sign in 无反应的根因）。这里由宿主进程用 Intent
                // 真正交给系统浏览器，并且只放行 http(s)，避免插件借 file:// / intent://
                // 打开任意内容。Android 12 起 app 不能借 `am` 命令行启动 Activity
                // （UID 校验），所以这一步只能由宿主自己做。
                "window.openExternal" -> {
                    val url = params.optString("url")
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        appendLog(host.pluginId, "warn", "openExternal 拒绝非 http(s) 地址：$url")
                        ok(JSONObject().put("opened", false))
                    } else {
                        val opened = try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            true
                        } catch (t: Throwable) {
                            appendLog(host.pluginId, "error", "openExternal 打开浏览器失败：${t.message}")
                            false
                        }
                        if (opened) appendLog(host.pluginId, "info", "已在系统浏览器打开：$url")
                        ok(JSONObject().put("opened", opened))
                    }
                }

                "window.showOpenDialog", "window.showSaveDialog" -> {
                    // 文件对话框在手机上没有 VS Code 那种原生选择器；这里保持"明确未实现"，
                    // 让扩展走它的降级分支，而不是收到一个假的空路径去写文件。
                    appendLog(host.pluginId, "warn", "$method：宿主未实现文件对话框，已返回 null")
                    ok(JSONObject.NULL)
                }

                // ---------------------------------------------------------- 语言模型 / 会话
                // ms-python 这类新扩展依赖 `vscode.lm`；宿主以前完全没有这个命名空间，
                // 于是扩展在 activate 里直接 TypeError 崩掉。这里给它一条**受授权**的真通道：
                // 没有授权时返回空模型列表（VS Code 的合法语义：没有可用模型），
                // 而不是让扩展拿到一个半截对象。
                "lm.selectChatModels" -> {
                    val decision = capabilityBridge.decisionFor("plugin:${host.pluginId}", "model.chat")
                    val models = JSONArray()
                    if (decision == GrantDecision.ALLOW) {
                        models.put(
                            JSONObject()
                                .put("id", "nebulaforge-gateway")
                                .put("name", "NebulaForge 网关模型")
                                .put("vendor", "nebulaforge")
                                .put("family", "gateway")
                                .put("maxInputTokens", 128_000)
                        )
                    } else {
                        appendLog(host.pluginId, "info", "selectChatModels：插件未获模型授权，按 VS Code 语义返回空列表")
                    }
                    ok(JSONObject().put("models", models))
                }

                "lm.sendRequest" -> {
                    val prompt = params.optJSONArray("messages")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.optString("content")
                        }.joinToString("\n")
                    }.orEmpty()
                    val r = invokeCapability("plugin:${host.pluginId}", "model.chat", JSONObject().put("prompt", prompt))
                    if (!r.ok) return fail(r.text)
                    ok(JSONObject().put("text", r.text))
                }

                "lm.invokeTool" -> {
                    val name = params.optString("name")
                    if (name.isBlank()) fail("缺少工具名")
                    else { val r = host.invokeLmTool(name, params.optJSONObject("input") ?: JSONObject()); if (r.ok) ok(r.value) else fail(r.error) }
                }

                "chat.registerParticipant" -> {
                    appendLog(host.pluginId, "info", "注册会话参与者：${params.optString("id")}")
                    ok(JSONObject().put("registered", true))
                }

                // ---------------------------------------------------------- 工作区文件
                "workspace.readFile" -> {
                    val file = resolveUri(params.optString("uri"))
                        ?: return fail("路径不在可访问范围内：${params.optString("uri")}")
                    if (!file.isFile) return fail("文件不存在：${file.absolutePath}")
                    val bytes = file.readBytes()
                    if (params.optBoolean("base64", false)) {
                        ok(JSONObject().put("base64", Base64.getEncoder().encodeToString(bytes)))
                    } else {
                        ok(
                            JSONObject()
                                .put("text", String(bytes, Charsets.UTF_8))
                                .put("languageId", languageIdFor(file))
                        )
                    }
                }

                "workspace.writeFile" -> {
                    val file = resolveUri(params.optString("uri"))
                        ?: return fail("路径不在可访问范围内：${params.optString("uri")}")
                    file.parentFile?.mkdirs()
                    file.writeBytes(Base64.getDecoder().decode(params.optString("base64")))
                    appendLog(host.pluginId, "info", "写入文件：${file.absolutePath}")
                    ok(JSONObject.NULL)
                }

                "workspace.stat" -> {
                    val file = resolveUri(params.optString("uri"))
                        ?: return fail("路径不在可访问范围内：${params.optString("uri")}")
                    if (!file.exists()) return fail("不存在：${file.absolutePath}")
                    ok(
                        JSONObject()
                            .put("directory", file.isDirectory)
                            .put("size", file.length())
                            .put("mtime", file.lastModified())
                    )
                }

                "workspace.readDirectory" -> {
                    val dir = resolveUri(params.optString("uri"))
                        ?: return fail("路径不在可访问范围内：${params.optString("uri")}")
                    if (!dir.isDirectory) return fail("不是目录：${dir.absolutePath}")
                    val entries = JSONArray()
                    dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
                        entries.put(JSONObject().put("name", child.name).put("directory", child.isDirectory))
                    }
                    ok(entries)
                }

                "workspace.createDirectory" -> {
                    val dir = resolveUri(params.optString("uri"))
                        ?: return fail("路径不在可访问范围内：${params.optString("uri")}")
                    if (!dir.isDirectory && !dir.mkdirs()) return fail("创建目录失败：${dir.absolutePath}")
                    ok(JSONObject.NULL)
                }

                "workspace.delete" -> {
                    val file = resolveUri(params.optString("uri"))
                        ?: return fail("路径不在可访问范围内：${params.optString("uri")}")
                    val removed = runCatching { file.deleteRecursively() }.getOrDefault(false)
                    if (!removed) fail("删除失败：${file.absolutePath}") else ok(JSONObject.NULL)
                }

                "workspace.rename", "workspace.copy" -> {
                    val from = resolveUri(params.optString("from"))
                    val to = resolveUri(params.optString("to"))
                    if (from == null || to == null) return fail("路径不在可访问范围内")
                    if (!from.exists()) return fail("源不存在：${from.absolutePath}")
                    to.parentFile?.mkdirs()
                    if (method == "workspace.copy") {
                        if (from.isDirectory) from.copyRecursively(to, overwrite = true)
                        else from.copyTo(to, overwrite = true)
                    } else {
                        if (!from.renameTo(to)) return fail("重命名失败：${from.absolutePath} → ${to.absolutePath}")
                    }
                    ok(JSONObject.NULL)
                }

                "workspace.findFiles" -> {
                    val include = params.optString("include").takeIf { it.isNotBlank() } ?: "**/*"
                    val exclude = params.optString("exclude").takeIf { it.isNotBlank() }
                    val base = workspaceRoot
                        ?: return fail("当前没有打开工作区，findFiles 不可用")
                    val includes = include.split(',').map { it.trim() }.filter { it.isNotBlank() }.map { globToRegex(it) }
                    val excludes = exclude?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
                        ?.map { globToRegex(it) }.orEmpty()
                    val result = JSONArray()
                    var scanned = 0
                    base.walkTopDown()
                        .onEnter { it.name != ".git" && it.name != "node_modules" }
                        .forEach { file ->
                            if (scanned > 20_000 || result.length() >= 2_000) return@forEach
                            scanned++
                            if (!file.isFile) return@forEach
                            val relative = file.relativeTo(base).path
                            if (includes.none { it.matches(relative) }) return@forEach
                            if (excludes.any { it.matches(relative) }) return@forEach
                            result.put(file.absolutePath)
                        }
                    ok(result)
                }

                // ---------------------------------------------------------- 编辑器
                "workspace.applyEdit", "editor.applyEdits" -> {
                    var applied = 0
                    // 文件级操作（create/delete/rename）：WorkspaceEdit 里最常见的非文本编辑。
                    // 只依赖文件系统，不需要编辑器桥，否则「模板生成/批量重命名」这类插件会静默失败。
                    val fileOps = params.optJSONArray("fileOperations") ?: JSONArray()
                    for (i in 0 until fileOps.length()) {
                        val op = fileOps.optJSONObject(i) ?: continue
                        val options = op.optJSONObject("options")
                        when (op.optString("type")) {
                            "create" -> {
                                val target = resolveUri(op.optString("uri")) ?: continue
                                val asDir = options?.optBoolean("isDirectory") == true
                                val made = if (asDir) {
                                    target.isDirectory || target.mkdirs()
                                } else {
                                    runCatching {
                                        target.parentFile?.mkdirs()
                                        target.createNewFile()
                                    }.getOrDefault(false) || target.exists()
                                }
                                if (made) applied++
                            }
                            "delete" -> {
                                val target = resolveUri(op.optString("uri")) ?: continue
                                if (!target.exists()) continue
                                val recursive = options?.optBoolean("recursive") == true
                                val removed = when {
                                    !target.isDirectory -> target.delete()
                                    recursive -> target.deleteRecursively()
                                    else -> target.delete()
                                }
                                if (removed) applied++
                            }
                            "rename" -> {
                                val from = resolveUri(op.optString("oldUri")) ?: continue
                                val to = resolveUri(op.optString("newUri")) ?: continue
                                if (!from.exists()) continue
                                to.parentFile?.mkdirs()
                                if (from.renameTo(to)) applied++
                            }
                        }
                    }
                    // 文本编辑需要编辑器桥（用于刷新打开的缓冲区）。
                    if (method == "editor.applyEdits" && editorBridge == null) {
                        return fail("编辑器桥未接入，无法应用编辑")
                    }
                    editorBridge?.let { bridge ->
                        val batches = if (method == "editor.applyEdits") {
                            listOf(params.optString("uri") to (params.optJSONArray("edits") ?: JSONArray()))
                        } else {
                            val arr = params.optJSONArray("edits") ?: JSONArray()
                            (0 until arr.length()).mapNotNull { i ->
                                val group = arr.optJSONObject(i) ?: return@mapNotNull null
                                (group.optString("uri").takeIf { it.isNotBlank() } ?: "") to
                                    (group.optJSONArray("edits") ?: JSONArray())
                            }
                        }
                        for ((uri, editsJson) in batches) {
                            if (uri.isBlank()) continue
                            val edits = parseEdits(editsJson)
                            if (edits.isEmpty()) continue
                            val file = resolveUri(uri) ?: continue
                            if (bridge.applyEdits(file.toURI().toString(), edits)) applied++
                        }
                    }
                    ok(applied > 0)
                }

                "editor.openDocument" -> {
                    val file = resolveUri(params.optString("uri"))
                        ?: return fail("路径不在可访问范围内：${params.optString("uri")}")
                    val bridge = editorBridge ?: return fail("编辑器桥未接入，无法打开文档")
                    val opened = bridge.openDocument(file.toURI().toString())
                    if (!opened) fail("编辑器未能打开：${file.absolutePath}") else ok(JSONObject.NULL)
                }

                "workspace.saveDocument" -> {
                    val file = resolveUri(params.optString("uri"))
                        ?: return fail("路径不在可访问范围内")
                    val bridge = editorBridge ?: return fail("编辑器桥未接入，无法保存文档")
                    if (!bridge.saveDocument(file.toURI().toString())) fail("保存失败：${file.absolutePath}")
                    else ok(JSONObject.NULL)
                }

                // ---------------------------------------------------------- 命令
                "commands.executeCommand" -> {
                    val command = params.optString("command")
                    if (command.isBlank()) return fail("命令为空")
                    // 1) 宿主内置命令优先（setContext / workbench.* / editor.action.* 等）。
                    when (val builtin = builtinCommands.execute(command, params.optJSONArray("args"))) {
                        is BuiltinCommandRegistry.Outcome.Handled ->
                            return ok(builtin.value ?: JSONObject.NULL)
                        is BuiltinCommandRegistry.Outcome.Declared ->
                            // 已识别为内置/UI 命令但宿主无动作：软成功，避免中断扩展流程。
                            return ok(JSONObject.NULL)
                        BuiltinCommandRegistry.Outcome.Unknown -> Unit
                    }
                    val other = hosts.values.firstOrNull { it !== host && it.owns(command) }
                    if (other != null) {
                        val err = other.executeCommand(command, params.optJSONArray("args"))
                        return if (err == null) ok(JSONObject.NULL) else fail(err)
                    }
                    fail("IDE 未提供命令 $command")
                }

                // ---------------------------------------------------------- 状态
                "state.set" -> {
                    host.persistState(
                        params.optString("key"),
                        params.opt("value").takeIf { it != null && it != JSONObject.NULL },
                        params.optBoolean("global", false)
                    )
                    ok(JSONObject.NULL)
                }

                // ---------------------------------------------------------- 配置
                "workspace.getConfiguration" -> ok(readConfiguration(params.optString("section"), params.optString("key")))
                "workspace.updateConfiguration" -> {
                    writeConfiguration(params.optString("section"), params.optString("key"), params.opt("value"))
                    appendLog(host.pluginId, "info", "更新配置：${params.optString("section")}.${params.optString("key")}")
                    ok(JSONObject.NULL)
                }

                "window.withProgress" -> ok(JSONObject.NULL)

                else -> {
                    appendLog(host.pluginId, "warn", "宿主未实现扩展请求：$method")
                    fail("宿主未实现 $method")
                }
            }
        } catch (t: Throwable) {
            appendLog(host.pluginId, "error", "$method 处理失败：${t.message}")
            fail(t.message ?: "处理失败")
        }
    }

    /** 需要 UI 作答的请求：无人作答（或超时）时返回 null，等同 VS Code 的「用户取消」。 */
    private suspend fun askUi(
        host: PluginHost,
        kind: String,
        message: String,
        options: List<String>,
        multi: Boolean
    ): Any {
        val id = uiSeq.getAndIncrement()
        val slot = CompletableDeferred<JSONObject?>()
        uiWaiters[id] = slot
        _uiRequests.value = UiRequest(id, host.pluginId, kind, message, options, multi)
        val answer = withTimeoutOrNull(60_000) { slot.await() }
        uiWaiters.remove(id)
        if (_uiRequests.value?.id == id) _uiRequests.value = null
        if (answer == null) {
            appendLog(host.pluginId, "warn", "$kind 未作答（超时/取消），已按「取消」返回 null")
            return JSONObject.NULL
        }
        val selected = answer.optJSONArray("selected")
        return when {
            selected == null -> JSONObject.NULL
            selected.length() == 1 -> selected.optString(0)
            else -> selected
        }
    }

    // ================================================================== 辅助

    private fun parseEdits(arr: JSONArray): List<SimpleEdit> = buildList {
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val range = e.optJSONObject("range") ?: continue
            val start = range.optJSONObject("start") ?: continue
            val end = range.optJSONObject("end") ?: continue
            add(
                SimpleEdit(
                    startLine = start.optInt("line", 0),
                    startCharacter = start.optInt("character", 0),
                    endLine = end.optInt("line", 0),
                    endCharacter = end.optInt("character", 0),
                    newText = e.optString("newText")
                )
            )
        }
    }

    private fun uriToPath(uri: String): String? {
        if (uri.isBlank()) return null
        return runCatching {
            if (uri.startsWith("file://")) File(java.net.URI(uri)).absolutePath else uri
        }.getOrNull()
    }

    /**
     * 把扩展给的 uri/path 解析成受控的 File。
     *
     * 只放开应用私有区与公共存储：扩展是外部下载的代码，不能借宿主之手读写任意系统路径。
     */
    private fun resolveUri(uri: String): File? {
        val path = uriToPath(uri) ?: return null
        val file = File(path)
        val allowed: List<File> = listOf(
            File(Environment.homeRoot(context)),
            context.filesDir,
            context.cacheDir,
            File("/sdcard"),
            File("/storage")
        )
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return if (allowed.any { root ->
                val rootPath = runCatching { root.canonicalFile }.getOrNull()?.path ?: return@any false
                canonical.path == rootPath || canonical.path.startsWith(rootPath + File.separator)
            }
        ) canonical else null
    }

    private val languageByExtension = mapOf(
        "ts" to "typescript", "tsx" to "typescriptreact", "js" to "javascript", "jsx" to "javascriptreact",
        "mjs" to "javascript", "cjs" to "javascript", "json" to "json", "jsonc" to "jsonc",
        "py" to "python", "pyi" to "python", "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java", "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "hpp" to "cpp",
        "cs" to "csharp", "go" to "go", "rs" to "rust", "rb" to "ruby", "php" to "php",
        "sh" to "shellscript", "bash" to "shellscript", "zsh" to "shellscript",
        "html" to "html", "htm" to "html", "css" to "css", "scss" to "scss", "less" to "less",
        "md" to "markdown", "markdown" to "markdown", "xml" to "xml", "yaml" to "yaml", "yml" to "yaml",
        "toml" to "toml", "ini" to "ini", "sql" to "sql", "vue" to "vue", "svelte" to "svelte",
        "gradle" to "groovy", "lua" to "lua", "dart" to "dart", "swift" to "swift", "pl" to "perl"
    )

    private fun languageIdFor(file: File): String {
        val name = file.name.lowercase()
        if (name == "cmakelists.txt") return "cmake"
        return languageByExtension[name.substringAfterLast('.', "")] ?: "plaintext"
    }

    /** 简单 glob → 正则：支持 `*`、`?`、`**`、`{a,b}`，用于 findFiles 的 include/exclude。 */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        // `**/` 允许零级目录：VS Code 的 `**/*.ts` 要能匹配根目录下的 a.ts
                        if (i + 2 < glob.length && glob[i + 2] == '/') {
                            sb.append("(?:.*/)?")
                            i += 3
                            continue
                        }
                        sb.append(".*")
                        i += 2
                        continue
                    }
                    sb.append("[^/]*")
                }
                '?' -> sb.append("[^/]")
                '{' -> sb.append("(?:")
                '}' -> sb.append(")")
                ',' -> sb.append("|")
                '.', '(', ')', '+', '|', '^', '$', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    // ------------------------------------------------------------------ 配置存储

    private val configLock = Any()
    private val defaultsFile: File get() = File(hostDir, "defaults.json")
    private val userFile: File get() = File(hostDir, "settings.json")

    private fun readConfiguration(section: String, key: String): JSONObject {
        synchronized(configLock) {
            val defaults = runCatching { JSONObject(defaultsFile.readText()) }.getOrNull() ?: JSONObject()
            val settings = runCatching { JSONObject(userFile.readText()) }.getOrNull() ?: JSONObject()
            // 点号分段下钻：`editor.tabSize` 等价于 settings.editor.tabSize
            val defaultValue = descend(defaults, section, key)
            val userValue = descend(settings, section, key)
            return JSONObject().apply {
                put("value", userValue ?: defaultValue ?: JSONObject.NULL)
                put("hasUserValue", userValue != null)
                put("section", section)
                put("key", key)
            }
        }
    }

    private fun writeConfiguration(section: String, key: String, value: Any?) {
        synchronized(configLock) {
            val settings = runCatching { JSONObject(userFile.readText()) }.getOrNull() ?: JSONObject()
            var node = settings
            val path = buildList { addAll(section.split('.').filter { it.isNotBlank() }); add(key) }
                .filter { it.isNotBlank() }
            path.dropLast(1).forEach { part ->
                node = node.optJSONObject(part) ?: JSONObject().also { node.put(part, it) }
            }
            val leaf = path.lastOrNull() ?: return
            if (value == null || value == JSONObject.NULL) node.remove(leaf) else node.put(leaf, value)
            userFile.parentFile?.mkdirs()
            userFile.writeText(settings.toString())
        }
    }

    private fun descend(root: JSONObject, section: String, key: String): Any? {
        var node: JSONObject? = root
        (section.split('.') + key).filter { it.isNotBlank() }.forEach { part ->
            node = node?.optJSONObject(part)
        }
        return node
    }

    /** 供设置界面写入扩展的默认配置（扩展包 `configuration` 段的 defaults）。 */
    fun registerDefaults(defaults: JSONObject) {
        synchronized(configLock) {
            val current = runCatching { JSONObject(defaultsFile.readText()) }.getOrNull() ?: JSONObject()
            defaults.keys().forEach { key -> current.put(key, defaults.get(key)) }
            defaultsFile.parentFile?.mkdirs()
            defaultsFile.writeText(current.toString())
        }
    }

    // ------------------------------------------------------------------ 帧解析

    /**
     * Content-Length 分帧缓冲。
     *
     * PTY 只有一个输出流：扩展的 stderr / node 警告会插在协议帧之间，因此解析器先定位
     * `Content-Length:` 标记，把标记之前的文本交回日志，而不是假设流里只有帧。
     */
    private class FrameBuffer(private val onNoise: (String) -> Unit) {
        private val ascii = "Content-Length:".toByteArray(Charsets.US_ASCII)
        private var pending = ByteArray(0)
        private var expected = -1

        fun append(buffer: ByteArray, length: Int) {
            val chunk = buffer.copyOf(length)
            pending = if (pending.isEmpty()) chunk else pending + chunk
        }

        /** 丢弃当前缓冲与半帧状态（仅用于「启动期回显已过、通道已确认干净」这一刻）。 */
        fun reset() {
            pending = ByteArray(0)
            expected = -1
        }

        fun nextMessage(): JSONObject? {
            if (expected < 0) {
                val at = indexOf(pending, ascii)
                if (at < 0) return null
                if (at > 0) {
                    onNoise(String(pending.copyOfRange(0, at), Charsets.UTF_8))
                    pending = pending.copyOfRange(at, pending.size)
                }
                val split = indexOf(pending, "\r\n\r\n".toByteArray(Charsets.US_ASCII))
                if (split < 0) return null
                val header = String(pending.copyOfRange(0, split), Charsets.US_ASCII)
                val value = header.substringAfter("Content-Length:", "").trim().substringBefore('\r')
                val size = value.toIntOrNull()
                if (size == null || size <= 0) {
                    onNoise(header)
                    pending = pending.copyOfRange(split + 4, pending.size)
                    return null
                }
                expected = size
                pending = pending.copyOfRange(split + 4, pending.size)
            }
            if (pending.size < expected) return null
            val body = pending.copyOfRange(0, expected)
            pending = pending.copyOfRange(expected, pending.size)
            expected = -1
            return runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrElse {
                onNoise("无法解析协议帧：${it.message}")
                null
            }
        }

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            if (needle.isEmpty() || haystack.size < needle.size) return -1
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }

    companion object {
        @Volatile private var instance: JsExtensionHost? = null

        /** 进程内单例：扩展宿主是有状态的长时进程，不能每次调用都新建。 */
        fun of(context: Context): JsExtensionHost =
            instance ?: synchronized(this) {
                instance ?: JsExtensionHost(context.applicationContext).also { instance = it }
            }
    }
}
