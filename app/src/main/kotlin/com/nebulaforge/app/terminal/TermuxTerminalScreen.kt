package com.nebulaforge.app.terminal

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nebulaforge.core.environment.BootstrapRuntime
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.core.terminal.IdeTerminalSessionClient
import com.nebulaforge.core.terminal.TerminalSessionManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Full IDE terminal deck backed by Termux's real TerminalEmulator + TerminalView.
 * The UI only owns controls; VT/ANSI parsing, scrollback, selection and PTY I/O stay in Termux.
 */
@Composable
fun TermuxTerminalScreen() {
    val context = LocalContext.current.applicationContext
    val runtime = remember { BootstrapRuntime(context) }
    val manager = remember { TerminalSessionManager.get(context) }
    val app = context as NebulaForgeApplication
    val records by manager.records.collectAsState()
    var ready by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Preparing embedded Termux runtime…") }
    var activeId by remember { mutableStateOf<String?>(null) }
    var viewRef by remember { mutableStateOf<TerminalView?>(null) }
    // 字号来自工作区持久化设置（与构建面板共用一份）：不再各自 remember，调一次两边都生效、重启也保留。
    val workspaceState by app.workspaceState.state.collectAsState()
    val fontSize = workspaceState.terminalFontSize
    val setFontSize: (Int) -> Unit = { app.workspaceState.updateTerminalFont(it) }
    // 已实际应用到视图的字号。TerminalView.setTextSize() 内部会重建 Renderer 并
    // 调 updateSize() → JNI.setPtyWindowSize() + emulator.resize()（无「尺寸未变则跳过」判断），
    // 即每次调用都会给 shell 发一次 SIGWINCH 让它重画整屏。
    // 而 AndroidView 的 update 在每次重组都会执行 → 必须自行做去重，
    // 否则终端会因反复 resize 出现明显卡顿/闪屏。
    val appliedFontSize = remember { intArrayOf(-1) }
    // 诊断：最近一次「字符被接受」的时间戳（软键盘 commitText / 硬件按键）。
    // 用于量化「敲键 → 触发重绘」的端到端延迟，日志标签 NebulaTerm。
    val lastInputNanos = remember { longArrayOf(0L) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var fn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        manager.pruneDeadRecords()
        runtime.ensureReady { status ->
            message = status.message
            ready = status.state == BootstrapRuntime.State.READY
        }
    }

    LaunchedEffect(ready, records) {
        if (ready && activeId == null) {
            // 优先跟随 manager 里被标记为「活动」的会话：构建/运行在后台新建会话时会 markActive，
            // 这样即使终端页是构建之后才打开的，也会直接落在这次构建的会话上。
            val active = records.firstOrNull { it.active && manager.attach(it.id) != null }
            val live = active ?: records.firstOrNull { manager.attach(it.id) != null }
            if (live != null) activeId = live.id
            else createSession(manager, context, app.sessionBus) { id -> activeId = id }
        }
    }

    // 构建（「构建」工具窗点 ▶）会在后台新建 pty 会话并 markActive。
    // 终端页必须跟着切过去，否则用户看到的是原来的 shell、以为「构建没有输出」——
    // 这正是真机反馈的问题。用户点标签自己切走时同样走 markActive，所以两者不会互相打架。
    LaunchedEffect(ready, records) {
        if (!ready) return@LaunchedEffect
        val target = records.firstOrNull { it.active && manager.attach(it.id) != null } ?: return@LaunchedEffect
        if (target.id == activeId) return@LaunchedEffect
        activeId = target.id
        bindTerminalSession(manager, viewRef, target.id) { logKeystrokeLatency(lastInputNanos) }
    }

    // 终端页最核心的可用性就是「打开就能打字」：会话就绪后主动聚焦并拉起软键盘。
    // 只在 activeId 变化时执行，避免每次重组都强行弹键盘。
    LaunchedEffect(ready, activeId) {
        if (!ready) return@LaunchedEffect
        val view = viewRef ?: return@LaunchedEffect
        view.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    if (!ready) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
            Text(message, modifier = Modifier.padding(24.dp))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { createSession(manager, context, app.sessionBus) { activeId = it } }) { Text("+") }
            records.forEach { record ->
                FilterChip(
                    selected = record.id == activeId,
                    onClick = {
                        manager.markActive(record.id)
                        activeId = record.id
                        bindTerminalSession(manager, viewRef, record.id) { logKeystrokeLatency(lastInputNanos) }
                    },
                    label = { Text(record.title, maxLines = 1) }
                )
            }
            Button(onClick = {
                activeId?.let { manager.close(it) }
                val next = records.firstOrNull { it.id != activeId && manager.attach(it.id) != null }
                activeId = next?.id
                bindTerminalSession(manager, viewRef, next?.id) { logKeystrokeLatency(lastInputNanos) }
            }) { Text("×") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyChip("CTRL", ctrl) { ctrl = !ctrl }
            KeyChip("ALT", alt) { alt = !alt }
            KeyChip("SHIFT", shift) { shift = !shift }
            KeyChip("FN", fn) { fn = !fn }
            KeyButton("ESC") { send(manager, activeId, 27) }
            KeyButton("TAB") { send(manager, activeId, '\t'.code) }
            KeyButton("↑") { activeId?.let { manager.attach(it)?.write("\u001b[A") } }
            KeyButton("↓") { activeId?.let { manager.attach(it)?.write("\u001b[B") } }
            KeyButton("←") { activeId?.let { manager.attach(it)?.write("\u001b[D") } }
            KeyButton("→") { activeId?.let { manager.attach(it)?.write("\u001b[C") } }
            KeyButton("C-c") { send(manager, activeId, 3) }
            KeyButton("C-z") { send(manager, activeId, 26) }
            KeyButton("C-d") { send(manager, activeId, 4) }
            // 复制：有选区复制选区，没选区复制整屏 + 回滚缓冲。
            // （旧实现调的是 view.showContextMenu()，TerminalView 没有注册上下文菜单 → 点了毫无反应，
            //  这就是真机反馈「终端里的文字复制不了」。）
            KeyButton("COPY") { copyTerminalText(context, viewRef) }
            // 清屏：把 clear 写进 pty，由 guest 里的 shell 执行，避免绕开 emulator 直接擦缓冲。
            KeyButton("CLR") { activeId?.let { manager.attach(it)?.write("clear\n") } }
            KeyButton("PASTE") {
                val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.primaryClip
                val text = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (!text.isNullOrEmpty()) activeId?.let { manager.attach(it)?.write(text) }
            }
            KeyButton("−") { setFontSize(fontSize - 1) }
            KeyButton("+") { setFontSize(fontSize + 1) }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    setBackgroundColor(Color.BLACK)
                    setTextSize(fontSize)
                    appliedFontSize[0] = fontSize
                    setTerminalViewClient(
                        IdeTerminalViewClient(
                            ctrl = { ctrl },
                            alt = { alt },
                            shift = { shift },
                            fn = { fn },
                            // 关键序列的起点：软键盘/硬件按键的输入被接受时打点。
                            onInput = { lastInputNanos[0] = System.nanoTime() },
                            // 长按 → 进入 Termux 原生文本选择模式（选择手柄 + ActionMode 的复制/粘贴）。
                            // 之前这里返回 false 且没有任何地方调 startTextSelectionMode，而
                            // TerminalView 自身没有 onLongClick → 长按永远选不中文字。
                            onLongPressView = { ev ->
                                val ok = runCatching { startTextSelectionMode(ev) }.isSuccess
                                requestFocus()
                                toast(
                                    ctx,
                                    if (ok) "已进入选择模式：拖动选择，再点 COPY 复制"
                                    else "当前没有可选内容"
                                )
                                true
                            }
                        )
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                    requestFocus()
                    setOnTouchListener { v, event ->
                        if (event.action == MotionEvent.ACTION_UP) {
                            v.requestFocus()
                            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                                ?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                        }
                        false
                    }
                    viewRef = this
                }
            },
            update = { view ->
                viewRef = view
                bindTerminalSession(manager, view, activeId) { logKeystrokeLatency(lastInputNanos) }
                // 只在字号真正变化时才应用：见 appliedFontSize 的说明（避免反复 resize PTY）。
                if (appliedFontSize[0] != fontSize) {
                    view.setTextSize(fontSize)
                    appliedFontSize[0] = fontSize
                }
            }
        )
    }
}

/** 敲键 → 屏幕重绘 的端到端耗时打点（修复前这里不会出现任何日志：没人调用 onScreenUpdated）。 */
private fun logKeystrokeLatency(lastInputNanos: LongArray) {
    val startedAt = lastInputNanos[0]
    if (startedAt == 0L) return
    lastInputNanos[0] = 0L
    android.util.Log.i(TERMINAL_TAG, "敲键→重绘 ${(System.nanoTime() - startedAt) / 1_000_000}ms")
}


private fun createSession(manager: TerminalSessionManager, context: Context, bus: com.nebulaforge.core.session.IdeSessionBus, onCreated: (String) -> Unit) {
    val cwd = Environment.ensureHome(context).absolutePath
    val (record, _) = manager.create(
        cwd = cwd,
        client = IdeTerminalSessionClient()
    ) { id, finished ->
        // 终端进程退出时必须上报终态，否则会话面板会一直停在“运行中”。
        val code = runCatching { finished.exitStatus }.getOrDefault(-1)
        bus.emit(
            com.nebulaforge.core.session.IdeEvent.State(
                id,
                if (code == 0) com.nebulaforge.core.session.SessionState.Succeeded("终端已退出")
                else com.nebulaforge.core.session.SessionState.Failed("终端退出码 $code", code)
            )
        )
    }
    manager.markActive(record.id)
    bus.register(com.nebulaforge.core.session.IdeSession(record.id, com.nebulaforge.core.session.SessionKind.TERMINAL), cwd)
    bus.emit(com.nebulaforge.core.session.IdeEvent.State(record.id, com.nebulaforge.core.session.SessionState.Running("终端已启动")))
    onCreated(record.id)
}

private fun send(manager: TerminalSessionManager, id: String?, codePoint: Int) {
    if (id != null) manager.writeCodePoint(id, false, codePoint)
}

private fun toast(context: Context, message: String) {
    runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
}

/**
 * 复制终端文本到系统剪贴板。
 *
 * 有选区就复制选区：Termux 只在包内可见的 controller 上暴露选择范围，
 * 因此用反射取 `getSelectors(int[4])`，再用**公开**的
 * `TerminalEmulator.getSelectedText(x1,y1,x2,y2)` 拿文本。
 * 没有选区则退化为「复制全部」（整屏 + 回滚缓冲的 transcript），
 * 保证这个按钮在任何情况下都不是哑的。
 */
private fun copyTerminalText(context: Context, view: TerminalView?) {
    val v = view ?: return
    val selected = v.selectedTextOrNull()
    val text = selected ?: v.transcriptTextOrNull()
    if (text.isNullOrEmpty()) {
        toast(context, "没有可复制的文本")
        return
    }
    val ok = TerminalSessionManager.copyToSystemClipboard(text)
    toast(
        context,
        when {
            !ok -> "复制失败：剪贴板不可用"
            selected != null -> "已复制选中内容（${text.length} 字符）"
            else -> "已复制全部终端内容（${text.length} 字符）"
        }
    )
}

/** 读取 Termux 视图当前选中的文本；没有选区时返回 null。 */
private fun TerminalView.selectedTextOrNull(): String? = runCatching {
    val controller = javaClass.getDeclaredMethod("getTextSelectionCursorController")
        .apply { isAccessible = true }
        .invoke(this) ?: return null
    val selectors = IntArray(4)
    controller.javaClass.getMethod("getSelectors", IntArray::class.java).invoke(controller, selectors)
    mEmulator?.getSelectedText(selectors[0], selectors[1], selectors[2], selectors[3])
}.getOrNull()?.takeIf { it.isNotBlank() }

/** 整屏 + 回滚缓冲的纯文本（Termux 的 transcript）。 */
private fun TerminalView.transcriptTextOrNull(): String? =
    runCatching { mTermSession?.emulator?.screen?.transcriptText }.getOrNull()?.takeIf { it.isNotBlank() }

@Composable
private fun KeyChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

@Composable
private fun KeyButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.widthIn(min = 48.dp)) { Text(text) }
}

internal class IdeTerminalViewClient(
    private val ctrl: () -> Boolean,
    private val alt: () -> Boolean,
    private val shift: () -> Boolean,
    private val fn: () -> Boolean,
    /** 输入被接受时的打点回调，用于测量「敲键 → 重绘」延迟。 */
    private val onInput: () -> Unit = {},
    /** 长按回调：返回 true 表示已自行处理（进入原生选择模式）。 */
    private val onLongPressView: ((MotionEvent) -> Boolean)? = null
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = scale
    override fun onSingleTapUp(e: MotionEvent) = Unit
    override fun shouldBackButtonBeMappedToEscape() = false
    // 必须为 false：true 会让 EditorInfo.inputType = TYPE_NULL（强制按键式输入），
    // 中文等第三方输入法的 commitText 会被丢掉，表现为「终端敲键盘毫无反应」。
    // false 走 Termux 的 TYPE_CLASS_TEXT 路径，软键盘与中文输入都能正常写入。
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = true
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        onInput()
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent): Boolean = onLongPressView?.invoke(event) ?: false
    override fun readControlKey() = ctrl()
    override fun readAltKey() = alt()
    override fun readShiftKey() = shift()
    override fun readFnKey() = fn()
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        onInput()
        if (!fn()) return false
        val seq: String = when (Character.toLowerCase(codePoint)) {
            'w'.code -> "\u001b[A"
            'a'.code -> "\u001b[D"
            's'.code -> "\u001b[B"
            'd'.code -> "\u001b[C"
            'p'.code -> "\u001b[5~"
            'n'.code -> "\u001b[6~"
            't'.code -> "\t"
            '1'.code -> "\u001bOP"
            '2'.code -> "\u001bOQ"
            '3'.code -> "\u001bOR"
            '4'.code -> "\u001bOS"
            '0'.code -> "\u001b[20~"
            else -> return false
        }
        val bytes = seq.toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
        return true
    }
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) { android.util.Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { android.util.Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { android.util.Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { android.util.Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { android.util.Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { android.util.Log.e(tag, "terminal", e) }
}
