package com.nebulaforge.core.terminal

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.environment.TermuxGuest
import com.nebulaforge.core.toolchain.ToolchainManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Owns real Termux TerminalSession instances for the IDE.
 * The manager persists only session metadata; PTYs themselves are deliberately recreated after
 * process death because a PID/PTY from a previous Android process cannot safely be reused.
 */
class TerminalSessionManager private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: TerminalSessionManager? = null

        /**
         * 进程级 app context：Termux 的原生「复制 / 粘贴」走
         * [TerminalSessionClient.onCopyTextToClipboard] / [TerminalSessionClient.onPasteTextFromClipboard]，
         * 这两个回调里拿不到 Context，而 IDE 侧从不传 delegate（见 [IdeTerminalSessionClient]）→
         * 「长按选中 → 复制」的文本会被丢进虚空，剪贴板里什么都没有（真机反馈的问题）。
         * 这里留一个落点，让原生动作真正落到系统剪贴板。
         */
        @Volatile private var appContext: Context? = null

        fun get(context: Context): TerminalSessionManager = instance ?: synchronized(this) {
            instance ?: TerminalSessionManager(context.applicationContext).also { instance = it }
        }

        /** 写系统剪贴板：Termux 原生复制的兜底落点。 */
        fun copyToSystemClipboard(text: String, label: String = "NebulaForge"): Boolean {
            if (text.isEmpty()) return false
            val ctx = appContext ?: return false
            return runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
                cm != null
            }.getOrDefault(false)
        }

        /** 读系统剪贴板：Termux 原生粘贴的兜底来源。 */
        fun readSystemClipboard(): String? {
            val ctx = appContext ?: return null
            return runCatching {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
    }

    init {
        appContext = context.applicationContext
    }
    data class Record(
        val id: String,
        val title: String,
        val cwd: String,
        val startedAt: Long,
        val active: Boolean = false
    )

    private val prefsFile = File(context.filesDir, "terminal/sessions.json")
    private val toolchain = ToolchainManager(context)
    private val _records = MutableStateFlow(loadRecords())
    val records: StateFlow<List<Record>> = _records.asStateFlow()

    private val sessions = LinkedHashMap<String, TerminalSession>()

    /**
     * 会话 id → 会话客户端，用于把「PTY 有新输出」转成 `TerminalView.onScreenUpdated()`。
     * 见 [IdeTerminalSessionClient.screenUpdateHook] 的原因说明。
     */
    private val sessionClients = HashMap<String, IdeTerminalSessionClient>()

    /**
     * 绑定屏幕刷新回调（必须在主线程调用）。
     * 终端页在把会话挂到 [com.termux.view.TerminalView] 之后调用本方法；
     * 没有它，终端输出不会触发重绘，界面刷新要等光标闪烁或触摸。
     */
    fun bindScreenUpdates(id: String, hook: ((TerminalSession) -> Unit)?) {
        sessionClients[id]?.screenUpdateHook = hook
    }

    fun create(
        cwd: String = Environment.ensureHome(context).absolutePath,
        title: String = "Terminal ${_records.value.size + 1}",
        client: TerminalSessionClient,
        /**
         * 会话结束回调 (recordId, session)。
         * 用于把「终端已退出/退出码」回报给会话总线 —— 没有它，上层会话状态会永远停在“运行中”。
         */
        onFinished: ((String, TerminalSession) -> Unit)? = null
    ): Pair<Record, TerminalSession> {
        check(Environment.isBootstrapInstalled(context)) { "Embedded Termux runtime is not ready" }
        val env = toolchain.environmentSession().terminal
        val shell = Environment.resolveShell(context)
        val id = UUID.randomUUID().toString()
        val record = Record(id, title, cwd, System.currentTimeMillis(), active = true)
        val resolvedCwd = File(cwd).takeIf { it.isDirectory }?.absolutePath
            ?: Environment.ensureHome(context).absolutePath

        // 终端必须跑在「前缀对齐」的 guest 里：Termux 的 apt/pkg/dpkg/脚本按
        // /data/data/com.termux/files/usr 找自己的配置与库，直接以私有目录前缀运行必然失败。
        // guest 未就绪（proot 缺失）时退回原行为，至少保证 shell 可用。
        val guestReady = runCatching {
            TermuxGuest.ensureSetup(context)
            TermuxGuest.isReady(context)
        }.getOrDefault(false)

        // 统一的 client：转发终端页的回调，并在会话结束时落盘启动诊断（proot 启动失败信息
        // 只出现在终端输出里，不落盘则设备上无从定位「终端打不开 / 敲键盘没反应」）。
        val wiredClient = IdeTerminalSessionClient(client) { finished ->
            runCatching { dumpLaunchDiagnostics(context, resolvedCwd, finished) }
            onFinished?.invoke(id, finished)
        }
        sessionClients[id] = wiredClient

        val session = if (guestReady) {
            val prootPath = TermuxGuest.prootBinary(context).absolutePath
            TerminalSession(
                prootPath,
                resolvedCwd,
                // 关键：Termux 的 JNI 是 `execvp(shellPath, args)`，args 被**原样当作 argv**，
                // 不会补 argv[0]（见 terminal-emulator/termux.c create_subprocess）。
                // 少了 argv[0]=proot，proot 解析时从 argv[1] 开始，会把作为 argv[0] 的
                // `-r` 直接跳过 → rootfs 被当成「要执行的命令」→ 报
                // `'<rootfs>' is not a regular file` 并立刻退出：
                // 终端表现为「进程已完成(code 1)、敲键盘毫无反应」。
                (listOf(prootPath) + TermuxGuest.terminalArgs(context, resolvedCwd)).toTypedArray(),
                // 关键：把**工具链解析出的环境**（JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME /
                // 含 build-tools 的 PATH …）并入 guest 环境。此前这里传的是纯 guestEnv，
                // 等于把 ToolchainManager/Environment 解析的结果整包丢掉，构建时必然缺 JAVA_HOME/ANDROID_HOME。
                TermuxGuest.guestEnvWith(context, env).map { "${it.key}=${it.value}" }.toTypedArray(),
                4000,
                wiredClient
            )
        } else {
            TerminalSession(
                shell,
                // cwd 不存在时退回已确保存在的 home，避免 pty 子进程以非法工作目录启动。
                resolvedCwd,
                // 同理必须自带 argv[0]，否则 shell 会把 "-i" 当成 argv[0]。
                // forShell 对 bash 会附 `--norc --noprofile`：兜底路径下没有 proot 前缀绑定，
                // 交互式 bash 会去 source 不可达的 bash.bashrc 并打印 Permission denied。
                com.nebulaforge.core.pty.PtyShellArgs.forShell(shell),
                env.map { "${it.key}=${it.value}" }.toTypedArray(),
                4000,
                wiredClient
            )
        }
        sessions[id] = session
        _records.value = _records.value.map { it.copy(active = false) } + record
        persist()
        return record to session
    }

    fun attach(id: String): TerminalSession? = sessions[id]

    fun close(id: String) {
        sessions.remove(id)?.finishIfRunning()
        sessionClients.remove(id)
        _records.value = _records.value.filterNot { it.id == id }
        persist()
    }

    fun closeAll() {
        sessions.values.forEach { it.finishIfRunning() }
        sessions.clear()
        sessionClients.clear()
        _records.value = emptyList()
        persist()
    }

    fun markActive(id: String) {
        if (_records.value.none { it.id == id }) return
        _records.value = _records.value.map { it.copy(active = it.id == id) }
        persist()
    }

    fun write(id: String, text: String) {
        sessions[id]?.write(text)
    }

    fun writeCodePoint(id: String, alt: Boolean, codePoint: Int) {
        sessions[id]?.writeCodePoint(alt, codePoint)
    }

    fun listLiveIds(): Set<String> = sessions.keys.toSet()

    /** Remove persisted records whose PTY cannot be restored after process death. */
    fun pruneDeadRecords() {
        if (_records.value.isNotEmpty()) {
            _records.value = _records.value.filter { sessions.containsKey(it.id) }
            persist()
        }
    }

    private fun persist() {
        prefsFile.parentFile?.mkdirs()
        val array = JSONArray()
        _records.value.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("cwd", it.cwd)
                put("startedAt", it.startedAt)
                put("active", it.active)
            })
        }
        prefsFile.writeText(array.toString())
    }

    /**
     * 终端启动诊断：把本次启动的 cwd、退出码、实际命令行以及**终端屏幕内容**追加到
     * `logs/terminal-launch.log`。
     *
     * 为什么必须落盘：proot / guest shell 的启动失败信息只出现在终端输出里
     * （例如 `proot error: ...`），这类内容既不进 logcat 也不在会话元数据里，
     * 一旦出现「终端打不开、敲键盘没反应」就完全无从定位。
     */
    private fun dumpLaunchDiagnostics(context: Context, cwd: String, session: TerminalSession) {
        val log = File(context.filesDir, "logs/terminal-launch.log")
        log.parentFile?.mkdirs()
        // 体积上限：日志只用于诊断，超限直接重建，避免长期累积。
        if (log.isFile && log.length() > 256 * 1024) log.delete()
        val exit = runCatching { session.exitStatus }.getOrDefault(-1)
        val screen = runCatching {
            session.emulator?.screen?.transcriptText?.trimEnd().orEmpty()
        }.getOrDefault("")
        val cmdLine = runCatching {
            (listOf(TermuxGuest.prootBinary(context).absolutePath) +
                TermuxGuest.terminalArgs(context, cwd)).joinToString(" ") { shellQuote(it) }
        }.getOrDefault("")
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        runCatching {
            log.appendText(
                buildString {
                    append("==== ").append(stamp).append(" ====\n")
                    append("cwd: ").append(cwd).append('\n')
                    append("exit: ").append(exit).append('\n')
                    append("cmd: ").append(cmdLine).append('\n')
                    append("--- screen ---\n").append(screen).append("\n--- end ---\n")
                }
            )
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun loadRecords(): List<Record> {
        if (!prefsFile.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(prefsFile.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(Record(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        title = o.optString("title", "Terminal"),
                        cwd = o.optString("cwd", Environment.ensureHome(context).absolutePath),
                        startedAt = o.optLong("startedAt", System.currentTimeMillis()),
                        active = false
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }
}

/** Small client with no dependency on the full Termux app UI layer. */
open class IdeTerminalSessionClient(
    /** 原客户端：所有回调先转发给它，避免替换 client 时丢掉终端页自己的回调。 */
    private val delegate: TerminalSessionClient? = null,
    private val onFinished: ((TerminalSession) -> Unit)? = null
) : TerminalSessionClient {
    /**
     * 屏幕刷新钩子：PTY 有新输出并已喂给 emulator 后立刻重绘终端视图。
     *
     * 为什么必须有它（Termux 0.118 起的行为变化）：
     * - `TerminalSession$MainThreadHandler.handleMessage()` 在主线程 `mEmulator.append(...)`
     *   之后调用 `notifyScreenUpdate()` → `TerminalSessionClient.onTextChanged()`；
     * - 唯一的重绘入口是 `TerminalView.onScreenUpdated()`（内部 `invalidate()`），
     *   而 `TerminalView.attachSession()` **不会**把视图注册成会话客户端（0.118 已移除该行为）。
     *
     * 于是「只把 onTextChanged 转发给空 delegate」时，输出进了 emulator 却没人重绘，
     * 界面只能等光标闪烁 / 触摸 / 尺寸变化才更新 —— 表现为「敲了命令要等一下才显示」。
     * 回调在主线程触发，故可直接调用 view 方法，无需再 post。
     */
    @Volatile var screenUpdateHook: ((TerminalSession) -> Unit)? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        delegate?.onTextChanged(changedSession)
        screenUpdateHook?.invoke(changedSession)
    }
    override fun onTitleChanged(changedSession: TerminalSession) { delegate?.onTitleChanged(changedSession) }
    override fun onSessionFinished(finishedSession: TerminalSession) {
        delegate?.onSessionFinished(finishedSession)
        onFinished?.invoke(finishedSession)
    }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        delegate?.onCopyTextToClipboard(session, text)
        // 没有 delegate（IDE 的三个创建点都不传）时不能让文本凭空消失：直接写系统剪贴板。
        // 这条路径正是 Termux 原生「长按选中 → 复制」的出口。
        if (delegate == null) TerminalSessionManager.copyToSystemClipboard(text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        if (delegate != null) {
            delegate.onPasteTextFromClipboard(session)
            return
        }
        // 同理：原生「粘贴」必须自己把剪贴板内容写进 pty，否则菜单点了没反应。
        val text = TerminalSessionManager.readSystemClipboard() ?: return
        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }
    override fun onBell(session: TerminalSession) { delegate?.onBell(session) }
    override fun onColorsChanged(session: TerminalSession) { delegate?.onColorsChanged(session) }
    override fun onTerminalCursorStateChange(state: Boolean) { delegate?.onTerminalCursorStateChange(state) }
    override fun getTerminalCursorStyle(): Int? = delegate?.getTerminalCursorStyle()
    override fun logError(tag: String, message: String) { delegate?.logError(tag, message) ?: android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { delegate?.logWarn(tag, message) ?: android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { delegate?.logInfo(tag, message) ?: android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { delegate?.logDebug(tag, message) ?: android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { delegate?.logVerbose(tag, message) ?: android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { delegate?.logStackTraceWithMessage(tag, message, e) ?: android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { delegate?.logStackTrace(tag, e) ?: android.util.Log.e(tag, "terminal", e) }
}
