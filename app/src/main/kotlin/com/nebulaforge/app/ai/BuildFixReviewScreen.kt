package com.nebulaforge.app.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.core.agent.FileChange

/** Human review surface for BuildFixAgent proposals. No model output is committed without explicit acceptance. */
@Composable
fun BuildFixReviewScreen(onOpenFile: (String, Int, Int, Int) -> Unit = { _, _, _, _ -> }) {
    val app = LocalContext.current.applicationContext as NebulaForgeApplication
    val state by app.buildFixCoordinator.state.collectAsState()
    val proposal = state.proposal
    var diffPreview by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("AI Build Fix", style = MaterialTheme.typography.headlineSmall)
                Text(state.message.ifBlank { state.phase.name }, style = MaterialTheme.typography.labelMedium)
                Text("修复轮次 ${state.round.coerceAtLeast(1)} / ${state.maxRounds} · 每轮仍需人工审核", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { app.buildFixCoordinator.cancel() }) { Text("关闭") }
        }
        Spacer(Modifier.height(8.dp))
        when (state.phase) {
            com.nebulaforge.core.agent.BuildFixCoordinator.Phase.IDLE,
            com.nebulaforge.core.agent.BuildFixCoordinator.Phase.FAILED,
            com.nebulaforge.core.agent.BuildFixCoordinator.Phase.RESOLVED -> {
                if (state.phase == com.nebulaforge.core.agent.BuildFixCoordinator.Phase.IDLE || state.phase == com.nebulaforge.core.agent.BuildFixCoordinator.Phase.FAILED) {
                    Button(
                        enabled = state.phase == com.nebulaforge.core.agent.BuildFixCoordinator.Phase.IDLE || state.round < state.maxRounds,
                        onClick = { app.buildFixCoordinator.analyzeCurrent() }
                    ) { Text(if (state.round > 1) "继续分析第 ${state.round} 轮" else "分析当前 Problems") }
                }
                if (state.phase == com.nebulaforge.core.agent.BuildFixCoordinator.Phase.RESOLVED) {
                    Text("所有 Build 错误已清除", Modifier.padding(top = 8.dp))
                }
                if (state.history.isNotEmpty()) {
                    Text("修复历史", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    state.history.forEach { h ->
                        Text(
                            "第 ${h.round} 轮 · 错误 ${h.errorCount} · ${h.outcome}" +
                                (h.rebuildSessionId?.let { " · Rebuild ${it.take(8)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (state.history.any { it.outcome == "NO_PROGRESS" }) {
                    Text("检测到错误签名没有变化，自动修复已停止，避免重复修改同一问题。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error)
                }
                if (state.message.isNotBlank()) Text(state.message, Modifier.padding(top = 8.dp))
            }
            com.nebulaforge.core.agent.BuildFixCoordinator.Phase.ANALYZING,
            com.nebulaforge.core.agent.BuildFixCoordinator.Phase.APPLYING,
            com.nebulaforge.core.agent.BuildFixCoordinator.Phase.REBUILDING -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.message, Modifier.padding(top = 8.dp))
            }
            com.nebulaforge.core.agent.BuildFixCoordinator.Phase.REVIEW -> {
                proposal?.let { p ->
                    Text("${p.changes.size} 个文件变更 · ${p.changes.count { it.status == FileChange.Status.ACCEPTED }} 个已接受", style = MaterialTheme.typography.titleMedium)
                    if (p.explanation.isNotBlank()) Text(p.explanation, Modifier.padding(vertical = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { app.buildFixCoordinator.acceptAll() }) { Text("全部接受") }
                        TextButton(onClick = { app.buildFixCoordinator.rejectAll() }) { Text("全部拒绝") }
                        Button(enabled = p.changes.any { it.status == FileChange.Status.ACCEPTED }, onClick = { app.buildFixCoordinator.applyAndRebuild() }) { Text("应用并重新构建") }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                        items(p.changes, key = { it.relativePath }) { change ->
                            var expanded by remember(change.relativePath) { mutableStateOf(true) }
                            val root: java.io.File? = state.projectPath?.let { java.io.File(it) }
                            val original: String = root?.let { r -> java.io.File(r, change.relativePath).takeIf { f -> f.isFile }?.readText() } ?: ""
                            val hunks = remember(change.relativePath, change.proposedContent, state.hunkStatuses[change.relativePath]) {
                                com.nebulaforge.core.agent.DiffReviewEngine.diff(original, change.proposedContent).map { h ->
                                    h.copy(status = state.hunkStatuses[change.relativePath]?.get(h.index) ?: com.nebulaforge.core.agent.DiffReviewEngine.HunkStatus.PENDING)
                                }
                            }
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text(change.relativePath, style = MaterialTheme.typography.titleSmall)
                                            Text("${hunks.size} 个 Diff Hunk", style = MaterialTheme.typography.labelSmall)
                                        }
                                        Switch(checked = change.status == FileChange.Status.ACCEPTED, onCheckedChange = {
                                            app.buildFixCoordinator.updateAccepted(change.relativePath, it)
                                        })
                                    }
                                    Text(if (change.originalSha256 == null) "新文件" else "已有文件 · SHA-256 乐观并发校验", style = MaterialTheme.typography.labelSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起 Diff" else "展开 Diff") }
                                        TextButton(onClick = { app.buildFixCoordinator.acceptAllHunks(change.relativePath) }) { Text("接受全部 Hunk") }
                                        TextButton(onClick = { app.buildFixCoordinator.rejectAllHunks(change.relativePath) }) { Text("拒绝全部 Hunk") }
                                        TextButton(onClick = { state.projectPath?.let { rootPath -> onOpenFile(java.io.File(rootPath, change.relativePath).absolutePath, 0, 0, -1) } }) { Text("打开文件") }
                                        TextButton(onClick = {
                                            val text = runCatching { com.nebulaforge.core.agent.DiffReviewEngine.unifiedDiff(original, change.proposedContent) }.getOrDefault("")
                                            diffPreview = "${change.relativePath}\n\n$text"
                                        }) { Text("统一 Diff") }
                                    }
                                    if (expanded) {
                                        hunks.forEach { h ->
                                            val ok = h.status == com.nebulaforge.core.agent.DiffReviewEngine.HunkStatus.ACCEPTED
                                            val rejected = h.status == com.nebulaforge.core.agent.DiffReviewEngine.HunkStatus.REJECTED
                                            Card(Modifier.fillMaxWidth().padding(top = 4.dp), colors = CardDefaults.cardColors(containerColor = if (rejected) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                                                Column(Modifier.padding(8.dp)) {
                                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                        Text("Hunk #${h.index + 1} · -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount}", style = MaterialTheme.typography.labelMedium)
                                                        Row {
                                                            TextButton(onClick = { app.buildFixCoordinator.updateHunk(change.relativePath, h.index, false) }) { Text("拒绝") }
                                                            TextButton(onClick = { app.buildFixCoordinator.updateHunk(change.relativePath, h.index, true) }) { Text("接受") }
                                                            TextButton(onClick = {
                                                                state.projectPath?.let { rootPath ->
                                                                    val line = (h.newStart - 1).coerceAtLeast(0)
                                                                    onOpenFile(java.io.File(rootPath, change.relativePath).absolutePath, line, 0, h.index)
                                                                }
                                                            }) { Text("跳到代码") }
                                                        }
                                                    }
                                                    h.oldLines.take(8).forEach { line -> Text("- $line", style = MaterialTheme.typography.bodySmall) }
                                                    h.newLines.take(8).forEach { line -> Text("+ $line", style = MaterialTheme.typography.bodySmall) }
                                                    Text(if (ok) "已接受" else if (rejected) "已拒绝" else "待审查", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    diffPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { diffPreview = null },
            confirmButton = { TextButton(onClick = { diffPreview = null }) { Text("关闭") } },
            title = { Text("Unified Diff") },
            text = {
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    item { Text(preview, style = MaterialTheme.typography.bodySmall) }
                }
            }
        )
    }
}
