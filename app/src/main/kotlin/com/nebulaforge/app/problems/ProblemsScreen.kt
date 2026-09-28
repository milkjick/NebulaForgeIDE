package com.nebulaforge.app.problems

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.core.session.IdeEvent
import com.nebulaforge.core.session.ProblemLocation
import com.nebulaforge.core.session.Severity

/** Live Problems surface backed by the single DiagnosticStore. */
@Composable
fun ProblemsScreen(onOpenFile: (ProblemLocation) -> Unit = {}, onAiFix: () -> Unit = {}) {
    val context = LocalContext.current
    val app = context.applicationContext as NebulaForgeApplication
    val version by app.diagnosticStore.version.collectAsState()
    val diagnostics = app.diagnosticStore.all()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                val errorCount = diagnostics.count { it.severity == Severity.ERROR }
                val warningCount = diagnostics.count { it.severity == Severity.WARNING }
                Text("问题", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "错误 $errorCount · 警告 $warningCount · 共 ${diagnostics.size} 条 · 更新 $version",
                    style = MaterialTheme.typography.labelMedium
                )
                Text("Build / LSP / 本地检查共用同一诊断源", style = MaterialTheme.typography.labelSmall)
            }
            Row {
                TextButton(enabled = diagnostics.any { it.severity == Severity.ERROR }, onClick = onAiFix) { Text("AI 修复") }
                TextButton(onClick = { app.diagnosticStore.clear() }) { Text("清空") }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (diagnostics.isEmpty()) {
            Card(Modifier.fillMaxWidth()) { Text("暂无诊断问题。", Modifier.padding(16.dp)) }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(diagnostics, key = { "${it.sessionId}:${it.file}:${it.line}:${it.column}:${it.message}" }) { d ->
                    val lineNo = d.line
                    val colNo = d.column
                    val location = d.file?.let { ProblemLocation(it, lineNo ?: 0, colNo ?: 0, d.sessionId, d.message) }
                    ListItem(
                        headlineContent = { Text(d.message, maxLines = 3) },
                        supportingContent = {
                            Text(buildString {
                                append(d.severity.name)
                                d.file?.let { append(" · ${it.substringAfterLast('/')}") }
                                if (lineNo != null) append(" :${lineNo + 1}")
                                if (colNo != null) append(":${colNo + 1}")
                            }, maxLines = 2)
                        },
                        modifier = Modifier.fillMaxWidth().clickable(enabled = location != null) {
                            location?.let {
                                app.problemNavigation.request(it)
                                onOpenFile(it)
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
