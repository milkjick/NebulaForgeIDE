package com.nebulaforge.app.build

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 任务输出列表（构建/运行面板的输出区）。
 *
 * 为什么必须渲染 runner 的文本输出、而不是只挂一个 pty 终端视图：
 * [WorkspaceTaskRunner] 的每条输出都写进它的 `output` 流；而终端视图依赖 pty 会话 + emulator
 * 链路。真机上后者一旦不产出内容，用户在任何窗口都看不到一个字（面板恒空白，状态却停在
 * 「运行中 Ns」）。这里直接渲染 runner 文本，与 pty/emulator 解耦，保证「有输出就一定看得见」。
 *
 * 可复制性：整段输出必须包在 [SelectionContainer] 里 —— 否则长按没有系统选择/复制菜单，
 * 用户只能看不能选（真机反馈「程序运行输出的文本无法复制」）。选中后可拖动跨行选择，
 * 再点系统菜单的「复制」；面板头部的「复制」按钮则一键复制全部输出。
 */
@Composable
fun TaskOutputList(
    lines: List<String>,
    fontSize: Int,
    modifier: Modifier = Modifier,
    emptyHint: String = "还没有输出"
) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) runCatching { state.animateScrollToItem(lines.lastIndex) }
    }
    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(emptyHint, fontSize = (fontSize - 1).coerceAtLeast(9).sp, color = Color(0xFF8A8A8A))
        }
        return
    }
    SelectionContainer(modifier = modifier.fillMaxWidth().background(Color(0xFF0E0E10))) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(6.dp)
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    fontSize = fontSize.sp,
                    color = when {
                        line.startsWith("[nebula]") -> Color(0xFF7FB0FF)
                        line.startsWith("$ ") -> Color(0xFF9AE6B4)
                        else -> Color(0xFFE0E0E0)
                    },
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
