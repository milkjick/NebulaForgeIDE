package com.nebulaforge.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nebulaforge.core.toolchain.ToolchainSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「工具链快照」区块：把工具链镜像到公共存储，卸载重装后本地恢复，不再重新下载。
 *
 * ## 为什么需要用户可见的入口
 *
 * 自动恢复只覆盖「私有目录整项缺失」这一种情况（卸载重装后的典型状态）。用户还会遇到
 * 两种需要人工介入的场景：
 *  - 换机 / 刷机 / 恢复出厂前，想先把工具链存下来（在旧机上点「立即快照」）；
 *  - 恢复后想确认到底恢复了什么、快照有多新（状态行 + 操作结果原文）。
 *
 * ## 线程模型
 *
 * 快照/恢复都是 GB 级文件树的重 I/O（公共存储走 FUSE，更慢），因此一律在 [Dispatchers.IO]
 * 执行，期间按钮禁用；状态读取也放 IO（[ToolchainSnapshot.status] 已刻意不遍历文件树）。
 *
 * 结果用空串表示「无结果」，避免可空状态在 Compose 里多一层判空。
 */
@Composable
fun ToolchainSnapshotPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("正在读取快照状态…") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) { ToolchainSnapshot.status(context) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("工具链快照（卸载重装免下载）", style = MaterialTheme.typography.titleMedium)
            Text(
                "把 Gradle 发行版与依赖缓存、Android SDK、用户态 JDK 镜像到 " +
                    "/sdcard/NebulaForge/toolchain。卸载会清空应用私有目录，这些内容重装后本应" +
                    "重新下载（本机网络下 services.gradle.org 不可达，表现为「重装后怎么都编不过」）；" +
                    "存在快照时，构建启动前会自动从快照本地恢复。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(status, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        busy = true
                        result = ""
                        scope.launch {
                            val notes = withContext(Dispatchers.IO) { ToolchainSnapshot.capture(context) }
                            result = notes.joinToString("\n").ifBlank {
                                "无需快照：工具链尚未就绪（请先成功构建一次）"
                            }
                            status = ToolchainSnapshot.status(context)
                            busy = false
                        }
                    },
                    enabled = !busy
                ) { Text(if (busy) "处理中…" else "立即快照") }
                OutlinedButton(
                    onClick = {
                        busy = true
                        result = ""
                        scope.launch {
                            val notes = mutableListOf<String>()
                            withContext(Dispatchers.IO) { ToolchainSnapshot.restore(context, notes) }
                            result = notes.joinToString("\n").ifBlank {
                                "没有需要恢复的项（私有工具链已完整）"
                            }
                            status = ToolchainSnapshot.status(context)
                            busy = false
                        }
                    },
                    enabled = !busy
                ) { Text("从快照恢复") }
            }
            if (result.isNotBlank()) {
                SelectionContainer {
                    Text(
                        result,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
