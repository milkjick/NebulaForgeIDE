package com.nebulaforge.app.terminal

import android.util.Log
import com.nebulaforge.core.terminal.TerminalSessionManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/** 终端相关日志统一 tag。 */
internal const val TERMINAL_TAG = "NebulaTerm"

/**
 * 把会话挂到终端视图上，并把「PTY 有新输出」接到 [TerminalView.onScreenUpdated]。
 *
 * 必须成对做：Termux 0.118 的 `TerminalView.attachSession()` 不再把视图注册为会话客户端，
 * 只 attach 不 bind 时输出只进 emulator 不触发 `invalidate()`，
 * 终端要等光标闪烁或触摸才刷新（表现为「敲了命令迟迟不显示」）。
 * 回调在主线程触发，此处的守卫用于防止刚切换会话时旧会话的回调重绘新会话画面。
 *
 * 终端页与构建面板共用这一份实现（两处都要求「复用现有终端」的能力）。
 *
 * @param onScreenUpdated 可选：屏幕更新时的额外回调（终端页用来打「敲键→重绘」耗时）。
 */
internal fun bindTerminalSession(
    manager: TerminalSessionManager,
    view: TerminalView?,
    id: String?,
    onScreenUpdated: ((TerminalSession) -> Unit)? = null
) {
    val v = view ?: return
    val key = id ?: return
    val session = manager.attach(key) ?: return
    if (v.mTermSession !== session) {
        val ok = v.attachSession(session)
        // attachSession 自身不重绘；重连已有会话（有回滚缓冲）时需要主动刷一次。
        v.onScreenUpdated()
        // 真机取证：视图尚未完成布局时 attach，emulator 会以 0 列 0 行建出来，且其后
        // onSizeChanged 不再触发 → 终端永久空白（敲键盘也没反应）。延到下一帧按真实尺寸补一次。
        v.post {
            runCatching { v.updateSize() }
            runCatching { if (session.emulator == null) session.initializeEmulator(80, 24) }
            v.onScreenUpdated()
        }
        Log.i(TERMINAL_TAG, "会话已挂载到终端视图: $key attach=$ok")
    }
    manager.bindScreenUpdates(key) { changed ->
        if (v.mTermSession === changed) {
            onScreenUpdated?.invoke(changed)
            v.onScreenUpdated()
        }
    }
}
