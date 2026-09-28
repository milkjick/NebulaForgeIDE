package com.nebulaforge.app.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nebulaforge.core.terminal.TerminalSessionManager
import com.termux.view.TerminalView

/** 终端默认底色（与 VSCode 深色面板一致，避免和周围 Compose 背景割裂）。 */
internal const val TERMINAL_BG = 0xFF1B1B1B.toInt()

/**
 * 内嵌终端：把 [TerminalSessionManager] 里的 pty 会话渲染成可滚动、可回看的终端画面。
 *
 * 构建面板、运行面板共用这一份实现（VSCode 里两者也是同一个终端的不同任务），
 * 避免「一个面板修好了终端刷新、另一个面板还在空白」这类分叉问题。
 *
 * 注意 Termux 的 [TerminalView] 是 **Android View**，字号走的是 `Int`（px 基准的 sp 值），
 * 不是 Compose 的 `sp`；混用会导致每次重组都 resize pty（输出被反复重排、乱序）。
 *
 * @param ptyId 会话 id（来自 TerminalSessionManager）；null 时显示 [emptyHint]
 * @param fontSize 终端字号（Termux 语义：直接传给 `setTextSize(Int)`）
 */
@Composable
fun IdeTerminalPanel(
    manager: TerminalSessionManager,
    ptyId: String?,
    fontSize: Int = 13,
    modifier: Modifier = Modifier,
    background: Int = TERMINAL_BG,
    emptyHint: String = "",
    onView: ((TerminalView) -> Unit)? = null
) {
    val context = LocalContext.current
    // Termux 的 TerminalView 只有 setTextSize(int)，没有 getter —— 自己记一份「已应用字号」，
    // 否则每次重组都会重新 setTextSize，触发 pty resize（输出被重排、光标乱跳）。
    val applied = remember { intArrayOf(-1) }
    Box(modifier.fillMaxSize().background(Color(background))) {
        if (ptyId == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(emptyHint, color = Color(0xFF9AA0A6), fontSize = 13.sp)
            }
            return@Box
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    setBackgroundColor(background)
                    setTextSize(fontSize)
                    applied[0] = fontSize
                    setTerminalViewClient(IdeTerminalViewClient({ false }, { false }, { false }, { false }))
                }
            },
            update = { view ->
                bindTerminalSession(manager, view, ptyId)
                if (applied[0] != fontSize) {
                    view.setTextSize(fontSize)
                    applied[0] = fontSize
                }
                onView?.invoke(view)
            }
        )
    }
}
