package com.nebulaforge.app.editor

import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import android.widget.FrameLayout
import android.view.Gravity
import io.github.rosemoe.sora.widget.CodeEditor
import com.nebulaforge.core.session.WorkspaceStateStore
import com.nebulaforge.core.editor.DocumentModel
import com.nebulaforge.core.editor.TextDocument
import com.nebulaforge.core.editor.ReplaceTextCommand
import com.nebulaforge.core.editor.CommandStack
import com.nebulaforge.core.session.ProjectLanguageProfile
import com.nebulaforge.core.session.FileChangeMonitor
import com.nebulaforge.core.session.IdeEvent
import com.nebulaforge.core.toolchain.ToolchainManager
import com.nebulaforge.core.agent.DiffReviewEngine
import com.nebulaforge.core.session.DiagnosticStore
import com.nebulaforge.app.plugins.JsExtensionHost
import com.nebulaforge.app.plugins.PluginUiRequestHost
import com.nebulaforge.app.plugins.PluginCommandButton
import com.nebulaforge.app.plugins.PluginCommandPaletteDialog
import com.nebulaforge.core.editor.intel.DeclarativeContributions
import com.nebulaforge.core.editorui.EditorBreadcrumbBar
import com.nebulaforge.core.editorui.EditorStatusBar
import com.nebulaforge.app.NebulaForgeApplication
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import com.nebulaforge.core.editor.intel.LocalDiagnostics
import com.nebulaforge.core.session.Severity
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


private fun appSessionBus(context: android.content.Context): com.nebulaforge.core.session.IdeSessionBus =
    (context.applicationContext as NebulaForgeApplication).sessionBus

private fun activeReviewHunkPath(file: File, projectPath: String?): String {
    val root = projectPath?.let(::File)?.canonicalFile ?: return file.name
    return runCatching { file.canonicalFile.relativeTo(root).path }.getOrDefault(file.name)
}

/**
 * Real source editor based on Sora Editor rather than a Compose TextField.
 * The document remains a normal on-disk project file; Sora owns cursor,
 * selection, undo/redo, search/replace and syntax rendering.
 *
 * 编辑器骨架遵循开发方案 12.1：面包屑（BreadcrumbBar）位于内容区顶部，
 * 状态栏（EditorStatusBar）常驻底部，展示行列号 / 编码 / 换行符 / 语言。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(path: String, initialLine: Int = 0, initialColumn: Int = 0, reviewHunk: Int = -1, onBack: () -> Unit) {
    val context = LocalContext.current
    val workspace = remember(context) { WorkspaceStateStore(context) }
    val diagnosticStore = (context.applicationContext as NebulaForgeApplication).diagnosticStore
    val diagnosticVersion by diagnosticStore.version.collectAsState()
    val file = remember(path) { File(path) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    var diffOverlay by remember { mutableStateOf<DiffGutterOverlay?>(null) }
    val documentModel = remember(path) {
        DocumentModel(TextDocument(file.toURI().toString(), file.extension.lowercase().ifBlank { "text" }, runCatching { file.readText() }.getOrDefault("")))
    }
    val commandStack = remember(path) { CommandStack() }
    var dirty by remember(path) { mutableStateOf(documentModel.isDirty) }
    var syncingFromModel by remember(path) { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var languageStatus by remember { mutableStateOf<com.nebulaforge.core.session.LanguageServiceRegistry.ServiceStatus?>(null) }
    var cursorLine by remember(path) { mutableStateOf(0) }
    var cursorColumn by remember(path) { mutableStateOf(0) }
    // 状态栏滚动百分比（图3）：由 Sora ScrollEvent 实时更新
    var scrollPercent by remember(path) { mutableStateOf(100) }
    // 本地诊断（离线启发式，不依赖 AI / 网络 / LSP）：内容变更后防抖重算一次。
    var localIssueTick by remember(path) { mutableStateOf(0) }
    val editorScope = rememberCoroutineScope()
    // 已装扩展（VSIX 的 JS 逻辑）宿主：编辑器把文档事件与补全请求接到它上面，
    // 扩展的补全/诊断/命令才真正「在 IDE 里生效」，而不是装了只躺在磁盘上。
    val extensionHost = remember(context) { JsExtensionHost.of(context) }
    // 独立编辑器同样要有插件界面请求的落点（与工作区一致：插件在这里也能问到人）。
    PluginUiRequestHost(extensionHost)
    // 当前文档语言 id：打开文件后由语言服务确定，扩展 provider 的 selector 按它匹配。
    var documentLanguageId by remember(path) { mutableStateOf("") }
    // 文档版本号（扩展侧用于判断增减），本地单调递增即可。
    val documentVersion = remember(path) { intArrayOf(1) }
    val documentUri = remember(path) { file.toURI().toString() }
    val appSessionBus = appSessionBus(context)
    val fileMonitor = remember(appSessionBus) { FileChangeMonitor(appSessionBus, editorScope) }
    LaunchedEffect(path) {
        workspace.openDocument(path, dirty = false)
        fileMonitor.watch(file)
        try { kotlinx.coroutines.awaitCancellation() } finally { fileMonitor.unwatch(file) }
    }
    LaunchedEffect(path, appSessionBus) {
        appSessionBus.events.collect { event ->
            if (event is IdeEvent.FileChanged && event.path == file.absolutePath && event.sessionId.startsWith("editor:")) {
                if (!dirty) {
                    val latest = runCatching { file.readText() }.getOrNull()
                    if (latest != null && latest != documentModel.document.value.text) {
                        documentModel.markReloaded(latest)
                        dirty = false
                        workspace.markDirty(file.absolutePath, false)
                        message = "检测到外部修改，已自动重新载入"
                    }
                } else {
                    message = "检测到外部修改：当前文件有未保存内容，请先保存或重新载入"
                }
            }
        }
    }
    val app = context.applicationContext as NebulaForgeApplication
    val languageServices = app.languageServices
    val toolchain = app.toolchainManager
    val languageProfile = remember(languageServices) { ProjectLanguageProfile(languageServices) }
    var projectRequirements by remember(path) { mutableStateOf(emptyList<com.nebulaforge.core.session.ProjectLanguageRequirement>()) }

    fun save() {
        val e = editor ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val text = e.text.toString()
            file.writeText(text)
            documentModel.markSaved()
            dirty = false
            workspace.markDirty(file.absolutePath, false)
            // 扩展拿到「已保存」事件（VS Code 的 onDidSaveTextDocument 语义）。
            extensionHost.documentSaved(documentUri)
            message = "已保存"
        }.onFailure { message = "保存失败：${it.message}" }
    }

    fun findProjectRoot(start: File): File {
        var current: File? = start.parentFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile || File(current, "settings.gradle").isFile) return current
            current = current.parentFile
        }
        return start.parentFile ?: start
    }

    val projectRoot = remember(path) { findProjectRoot(file) }
    // 编辑器智能输入宿主：工作区符号索引 + 语言服务器补全。
    // scope 用 editorScope（编辑器销毁即取消），避免协程泄漏到页面之外。
    val editorIntel = remember(projectRoot, path, languageServices) {
        EditorIntel(
            projectRoot, file, languageServices, app.editorIndex, editorScope,
            jsCompletions = { line, character ->
                // 语言 id 优先用语言服务给的；还没拿到时退回插件声明的「扩展名 → 语言」关联，
                // 否则刚打开文件的头几百毫秒内扩展补全会因为「不知道是什么语言」而漏掉。
                val languageId = documentLanguageId.ifBlank {
                    DeclarativeContributions.languageIdFor(file.name, file.extension.lowercase()).orEmpty()
                }
                val text = runCatching { editor?.text?.toString() }.getOrNull().orEmpty()
                if (text.isEmpty()) emptyList()
                else extensionHost.completionForDocument(documentUri, languageId, text, line, character)
            }
        )
    }
    LaunchedEffect(projectRoot) {
        // 打开文件即在后台重建工作区符号索引（同项目只扫一次），跨文件补全随后可用。
        app.rebuildWorkspaceIndexIfNeeded(projectRoot)
    }
    LaunchedEffect(projectRoot) {
        projectRequirements = languageProfile.discover(projectRoot)
    }

    LaunchedEffect(path) {
        val text = runCatching { file.readText() }.getOrDefault("")
        languageStatus = languageServices.openFile(projectRoot, file, text)
        val languageId = languageStatus?.languageId
            ?: DeclarativeContributions.languageIdFor(file.name, file.extension.lowercase()).orEmpty()
        documentLanguageId = languageId
        // 扩展语义：打开文档 → `onLanguage:<id>` 扩展激活 + 所有已启动宿主收到文档事件。
        extensionHost.refresh(projectRoot)
        if (languageId.isNotBlank()) {
            extensionHost.documentOpened(documentUri, languageId, text, documentVersion[0])
            extensionHost.activeDocumentChanged(documentUri)
        }
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            extensionHost.documentClosed(documentUri)
            languageServices.closeFile(projectRoot, file)
        }
    }

    LaunchedEffect(documentModel) {
        snapshotFlow { documentModel.document.value.text }.collect { modelText ->
            val e = editor ?: return@collect
            if (e.text.toString() != modelText) {
                syncingFromModel = true
                e.setText(modelText)
                syncingFromModel = false
                documentVersion[0]++
                extensionHost.documentChanged(documentUri, modelText, documentVersion[0])
            }
            dirty = documentModel.isDirty
        }
    }

    BackHandler {
        if (dirty) save()
        onBack()
    }

    val buildFix = app.buildFixCoordinator
    val buildFixState by buildFix.state.collectAsState()
    val reviewHunks = remember(path, buildFixState.proposal, buildFixState.hunkStatuses, buildFixState.projectPath) {
        buildFix.reviewHunksForAbsolutePath(file.absolutePath)
    }
    var currentReviewHunk by remember(path, reviewHunk) { mutableStateOf(reviewHunk.coerceAtLeast(-1)) }
    val activeReviewHunk = reviewHunks.firstOrNull { it.index == currentReviewHunk }
    LaunchedEffect(activeReviewHunk?.index, editor) {
        val h = activeReviewHunk ?: return@LaunchedEffect
        editor?.setSelection((h.newStart - 1).coerceAtLeast(0), 0)
    }
    LaunchedEffect(reviewHunks, diffOverlay, currentReviewHunk) {
        diffOverlay?.updateHunks(reviewHunks)
        diffOverlay?.setSelectedHunk(currentReviewHunk)
    }

    val fileDiagnostics = remember(path, diagnosticVersion) { diagnosticStore.forFile(file.absolutePath) }
    val localSessionId = remember(path) { "local-editor:${file.absolutePath}" }
    // 本地诊断区域缓存：扩展/LSP 诊断上屏时与它合并成一整批，避免两组互相 setDiagnostics 清空对方。
    var localRegions by remember(path) { mutableStateOf<List<DiagnosticRegion>>(emptyList()) }
    LaunchedEffect(path, localIssueTick) {
        val e = editor ?: return@LaunchedEffect
        if (localIssueTick > 0) kotlinx.coroutines.delay(400)
        val snapshot = runCatching { e.text.toString() }.getOrNull() ?: return@LaunchedEffect
        val issues = withContext(Dispatchers.Default) { LocalDiagnostics.analyze(file.name, snapshot) }
        val container = DiagnosticsContainer()
        val mapped = ArrayList<IdeEvent.Diagnostic>(issues.size)
        val regions = ArrayList<DiagnosticRegion>(issues.size)
        val length = e.text.length
        issues.forEachIndexed { index, issue ->
            val start = runCatching { e.text.getCharIndex(issue.startLine, issue.startColumn) }.getOrDefault(-1)
            val end = runCatching { e.text.getCharIndex(issue.endLine, issue.endColumn) }.getOrDefault(-1)
            if (start in 0 until length) {
                val endIndex = (if (end > start) end else start + 1).coerceAtMost(length)
                val region = DiagnosticRegion(start, endIndex, soraSeverityOf(issue.severity), index.toLong())
                region.detail = detailOf(issue)
                container.addDiagnostic(region)
                regions += region
            }
            mapped += IdeEvent.Diagnostic(
                sessionId = localSessionId,
                file = file.absolutePath,
                line = issue.startLine,
                column = issue.startColumn,
                message = issue.message,
                severity = coreSeverityOf(issue.severity)
            )
        }
        // 编辑器内波浪线
        e.setDiagnostics(container)
        localRegions = regions
        // 问题面板 / 状态栏：与 Build、LSP 共用同一个诊断真源，按会话整体替换避免残留。
        diagnosticStore.replaceSession(localSessionId, mapped)
    }

    /**
     * 已装扩展（VSIX 的 JS 逻辑）与 LSP 的诊断也画进编辑器波浪线。
     *
     * 为什么单独一条 effect：诊断真源里的条目只有行列、没有字符区间，
     * 且本地诊断那条链路每次都会整体 setDiagnostics；两条链路分开画必然互相清空。
     * 所以这里把「本地 + 其它会话」合成一批再整体上屏，谁更新都重画完整的一批。
     */
    LaunchedEffect(path, localRegions, fileDiagnostics) {
        val e = editor ?: return@LaunchedEffect
        val others = fileDiagnostics.filter { it.sessionId != localSessionId }
        if (others.isEmpty()) return@LaunchedEffect
        runCatching {
            val text = e.text
            val container = DiagnosticsContainer()
            localRegions.forEach { container.addDiagnostic(it) }
            others.forEachIndexed { index, d ->
                val line = ((d.line ?: 1) - 1).coerceIn(0, (text.lineCount - 1).coerceAtLeast(0))
                val column = ((d.column ?: 1) - 1).coerceAtLeast(0)
                val start = text.getCharIndex(line, column)
                val end = text.getCharIndex(line, text.getColumnCount(line)).coerceAtLeast(start + 1)
                val region = DiagnosticRegion(start, end, soraSeverityOfSession(d.severity), 10_000L + index)
                region.detail = DiagnosticDetail(d.message, d.message)
                container.addDiagnostic(region)
            }
            e.setDiagnostics(container)
        }
    }

    /**
     * 扩展 → 编辑器的写通道：`workspace.applyEdit` / 打开 / 保存 / 取活动文档。
     *
     * 跟 [editor] 实例绑定：编辑器出现才接线，页面退出（协程取消）立刻摘桥，
     * 避免扩展把编辑写到已经销毁的编辑器上（那是最容易「看起来生效、其实丢改动」的坑）。
     */
    LaunchedEffect(editor, path) {
        val e = editor ?: return@LaunchedEffect
        val bridge = object : JsExtensionHost.EditorBridge {
            override suspend fun applyEdits(uri: String, edits: List<JsExtensionHost.SimpleEdit>): Boolean {
                if (uri != documentUri) return false
                return withContext(Dispatchers.Main.immediate) {
                    runCatching {
                        val content = e.text
                        // 从后往前改：一次 WorkspaceEdit 里的多条编辑坐标相对「原文」，
                        // 先改前面会让后面的行列失效。
                        edits.sortedWith(
                            compareByDescending<JsExtensionHost.SimpleEdit> { it.startLine }
                                .thenByDescending { it.startCharacter }
                        ).forEach { edit ->
                            val lastLine = (content.lineCount - 1).coerceAtLeast(0)
                            val startLine = edit.startLine.coerceIn(0, lastLine)
                            val endLine = edit.endLine.coerceIn(0, lastLine)
                            val startColumn = edit.startCharacter.coerceIn(0, content.getColumnCount(startLine))
                            val endColumn = edit.endCharacter.coerceIn(0, content.getColumnCount(endLine))
                            content.replace(startLine, startColumn, endLine, endColumn, edit.newText)
                        }
                        val updated = content.toString()
                        documentModel.recordExternalChange(updated)
                        dirty = documentModel.isDirty
                        workspace.markDirty(file.absolutePath, dirty)
                        true
                    }.getOrDefault(false)
                }
            }

            /** 本编辑器同一时刻只承载一个文档，扩展要求打开别的文档时如实返回失败。 */
            override suspend fun openDocument(uri: String): Boolean = uri == documentUri

            override suspend fun saveDocument(uri: String): Boolean {
                if (uri != documentUri) return false
                withContext(Dispatchers.Main.immediate) { save() }
                return true
            }

            override suspend fun activeDocument(): Pair<String, String>? =
                documentUri to documentLanguageId
        }
        extensionHost.editorBridge = bridge
        extensionHost.activeDocumentChanged(documentUri)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            if (extensionHost.editorBridge === bridge) extensionHost.editorBridge = null
        }
    }

    var editMenuOpen by remember { mutableStateOf(false) }
    // 面板开关放在界面级：TopAppBar 的 actions 在窄屏上会溢出，尾部按钮点不到，
    // 因此「编辑」菜单里也提供入口，两条路共用同一份状态。
    var pluginPaletteOpen by remember { mutableStateOf(false) }
    if (pluginPaletteOpen) PluginCommandPaletteDialog(onDismiss = { pluginPaletteOpen = false })
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(file.name + if (dirty) " \u2022" else "") },
            navigationIcon = {
                TextButton(onClick = { if (dirty) save(); onBack() }) { Text("返回") }
            },
            actions = {
                // 编辑菜单：剪贴板四件套（无选区时复制/剪切按「当前行」，空文档也能粘贴）。
                // 独立编辑器与工作区编辑器共用同一套 Sora 实现，两处行为保持一致。
                Box {
                    TextButton(onClick = { editMenuOpen = true }) { Text("编辑") }
                    DropdownMenu(expanded = editMenuOpen, onDismissRequest = { editMenuOpen = false }) {
                        val e = editor
                        DropdownMenuItem(text = { Text("全选") }, enabled = e != null, onClick = {
                            editMenuOpen = false
                            runCatching { e?.selectAll() }
                        })
                        DropdownMenuItem(text = { Text("复制") }, enabled = e != null, onClick = {
                            editMenuOpen = false
                            runCatching { e?.copyText() }
                        })
                        DropdownMenuItem(text = { Text("剪切") }, enabled = e != null, onClick = {
                            editMenuOpen = false
                            runCatching { e?.cutText() }
                        })
                        DropdownMenuItem(text = { Text("粘贴") }, enabled = e != null, onClick = {
                            editMenuOpen = false
                            runCatching { e?.pasteText() }
                        })
                        DropdownMenuItem(text = { Text("插件命令…") }, onClick = {
                            editMenuOpen = false
                            pluginPaletteOpen = true
                        })
                    }
                }
                TextButton(enabled = commandStack.canUndo(), onClick = { commandStack.undo(documentModel) }) { Text("撤销") }
                TextButton(enabled = commandStack.canRedo(), onClick = { commandStack.redo(documentModel) }) { Text("重做") }
                TextButton(onClick = {
                    val e = editor
                    message = if (e != null && e.formatCodeAsync()) "已按括号层级重排缩进" else "当前语言暂不支持格式化"
                }) { Text("格式化") }
                TextButton(enabled = dirty, onClick = { save() }) { Text("保存") }
                // 插件命令入口：装在设备上的扩展（VSIX）提供的命令过去没有任何 UI 可以触发，
                // 只能靠插件详情页的「运行」按钮。这里给编辑器一个常驻入口。
                PluginCommandButton()
            }
        )
        EditorBreadcrumbBar(
            segments = remember(path, projectRoot) {
                val rel = runCatching {
                    file.canonicalFile.relativeTo(projectRoot.canonicalFile).path
                }.getOrDefault(file.name)
                rel.split(File.separatorChar).filter { it.isNotBlank() }
            }
        )
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(if (dirty) "未保存" else "已保存", style = MaterialTheme.typography.labelMedium)
            Text("  ${file.absolutePath}", style = MaterialTheme.typography.labelSmall)
        }
        if (initialLine > 0 || initialColumn > 0) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Text("AI Diff 定位：第 ${initialLine + 1} 行，列 ${initialColumn + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        languageStatus?.let { status ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Text("语言服务：${status.state}", style = MaterialTheme.typography.labelSmall)
                if (status.message.isNotBlank()) {
                    Text("  ${status.message}", style = MaterialTheme.typography.labelSmall)
                }
                if (status.state == com.nebulaforge.core.session.LanguageServiceRegistry.State.UNAVAILABLE) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        toolchain.enqueueLspInstallForLanguage(status.languageId)
                        message = "已加入 ${status.languageId} Language Server 安装任务"
                    }) { Text("安装") }
                }
            }
        }
        if (reviewHunks.isNotEmpty() && buildFixState.proposal?.changes?.any { it.relativePath == runCatching { file.relativeTo(buildFixState.projectPath?.let(::File) ?: file.parentFile ?: file).path }.getOrDefault(file.name) } == true) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text("AI Diff 编辑器审查", style = MaterialTheme.typography.titleSmall)
                    Text("当前文件 ${reviewHunks.size} 个 Hunk · 编辑器修改只更新 AI 候选，不会直接写入项目", style = MaterialTheme.typography.labelSmall)
                    if (activeReviewHunk != null) {
                        Text(
                            "Hunk #${activeReviewHunk.index + 1} · -${activeReviewHunk.oldStart},${activeReviewHunk.oldCount}  +${activeReviewHunk.newStart},${activeReviewHunk.newCount}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                val i = reviewHunks.indexOfFirst { it.index == activeReviewHunk.index }
                                if (i > 0) currentReviewHunk = reviewHunks[i - 1].index
                            }) { Text("上一个") }
                            TextButton(onClick = {
                                val i = reviewHunks.indexOfFirst { it.index == activeReviewHunk.index }
                                if (i >= 0 && i < reviewHunks.lastIndex) currentReviewHunk = reviewHunks[i + 1].index
                            }) { Text("下一个") }
                            TextButton(onClick = { buildFix.updateHunk(activeReviewHunkPath(file, buildFixState.projectPath), activeReviewHunk.index, false) }) { Text("拒绝") }
                            TextButton(onClick = { buildFix.updateHunk(activeReviewHunkPath(file, buildFixState.projectPath), activeReviewHunk.index, true) }) { Text("接受") }
                            TextButton(onClick = {
                                val relative = activeReviewHunkPath(file, buildFixState.projectPath)
                                val ok = buildFix.replaceProposalContentFromEditor(relative, editor?.text?.toString().orEmpty(), acceptAllHunks = false)
                                message = if (ok) "已把当前编辑器内容作为候选修订；项目文件尚未写入" else "无法更新 AI 修复候选"
                            }) { Text("保留编辑") }
                            TextButton(onClick = {
                                val relative = activeReviewHunkPath(file, buildFixState.projectPath)
                                val ok = buildFix.replaceProposalContentFromEditor(relative, editor?.text?.toString().orEmpty(), acceptAllHunks = true)
                                message = if (ok) "已将当前编辑器内容作为人工修订并接受；尚未写入项目" else "无法接受当前编辑内容"
                            }) { Text("编辑并接受") }
                        }
                        activeReviewHunk.newLines.take(5).forEach { line ->
                            Text("+ $line", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (projectRequirements.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                val missing = projectRequirements.count { !it.installedCommandDetected }
                Text(
                    if (missing == 0) "项目语言服务依赖：已检测" else "项目语言服务依赖：${missing} 项未安装",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (missing == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val frame = FrameLayout(context)
                val metrics = context.resources.displayMetrics
                val codeSp = 14.5f
                val e = CodeEditor(context).apply {
                    setTypefaceText(Typeface.MONOSPACE)
                    setTypefaceLineNumber(Typeface.MONOSPACE)
                    setTextSizePx(
                        android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_SP, codeSp, metrics
                        )
                    )
                    setLineInfoTextSize(
                        android.util.TypedValue.applyDimension(
                            android.util.TypedValue.COMPLEX_UNIT_SP, codeSp - 2.5f, metrics
                        )
                    )
                    setLineSpacing(2.5f * metrics.density, 1.35f)
                    setTabWidth(4)
                    setPinLineNumber(true)
                    setHighlightCurrentLine(true)
                    // 手机竖屏下自动换行，长行不再被水平截断。
                    isWordwrap = true
                    setText(runCatching { file.readText() }.getOrDefault(""))
                    if (initialLine > 0 || initialColumn > 0) {
                        setSelection(initialLine.coerceAtLeast(0), initialColumn.coerceAtLeast(0))
                    }
                    setEditorLanguage(com.nebulaforge.app.editor.editorLanguageFor(file, editorIntel))
                    // 本地补全：输入标识符字符或 "." 时自动弹出候选窗口（全部离线推导）。
                    runCatching { getComponent(EditorAutoCompletion::class.java)?.setEnabled(true) }
                    // 关键：Android 软键盘输入走 IME composing 通道（Gboard 等英文输入长时间停在组合态）。
                    // Sora 默认 autoCompletionOnComposing=false，会在组合态直接隐藏补全窗口并且不请求候选，
                    // 现象就是「敲字母毫无候选」。显式打开后组合态也照常补全。
                    runCatching {
                        props.autoCompletionOnComposing = true
                        props.disallowSuggestions = false
                    }
                    subscribeEvent(io.github.rosemoe.sora.event.SelectionChangeEvent::class.java) { ev, _ ->
                        cursorLine = ev.left.line
                        cursorColumn = ev.left.column
                    }
                    subscribeEvent(io.github.rosemoe.sora.event.ScrollEvent::class.java) { _, _ ->
                        val maxY = getScrollMaxY()
                        scrollPercent = if (maxY > 0) ((scrollY.toFloat() / maxY) * 100f).toInt().coerceIn(0, 100) else 100
                    }
                    subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) { ev, _ ->
                        val current = text.toString()
                        if (!syncingFromModel) {
                            val previous = documentModel.document.value.text
                            if (previous != current) {
                                documentModel.recordExternalChange(current)
                                commandStack.record(ReplaceTextCommand(previous, current))
                            }
                        }
                        dirty = documentModel.isDirty
                        workspace.markDirty(file.absolutePath, dirty)
                        // 扩展宿主：文档变更事件（增量计算、格式化、实时诊断都靠它）。
                        documentVersion[0]++
                        extensionHost.documentChanged(documentUri, current, documentVersion[0])
                        localIssueTick++
                        editorScope.launch {
                            languageServices.changeFile(projectRoot, file, text.toString())
                        }
                        // 输入即弹：插入标识符字符（或 "."）后主动请求补全。
                        // 不依赖 Sora 内部那条 selection-change 触发路径 —— IME 组合态下它可能不发事件，
                        // 于是「敲了字母却什么都不弹」。这里按键落字后直接要候选。
                        if (ev.action == io.github.rosemoe.sora.event.ContentChangeEvent.ACTION_INSERT) {
                            val typed = runCatching {
                                val end = ev.changeEnd
                                text.getLineString(end.line).getOrNull(end.column - 1)
                            }.getOrNull()
                            if (typed != null && (typed.isLetterOrDigit() || typed == '_' || typed == '.')) {
                                postInLifecycle {
                                    runCatching {
                                        getComponent(EditorAutoCompletion::class.java)?.requireCompletion()
                                    }
                                }
                            }
                        }
                    }
                }
                frame.addView(e, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                editor = e
                if (reviewHunks.isNotEmpty()) {
                    val overlay = DiffGutterOverlay(e, reviewHunks) { h ->
                        currentReviewHunk = h.index
                        e.setSelection((h.newStart - 1).coerceAtLeast(0), 0)
                    }
                    val overlayParams = FrameLayout.LayoutParams(
                        (18 * context.resources.displayMetrics.density).toInt(),
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.START
                    )
                    frame.addView(overlay, overlayParams)
                    diffOverlay = overlay
                }
                frame
            },
            update = { frame ->
                val e = frame.getChildAt(0) as? CodeEditor ?: return@AndroidView
                editor = e
                val overlay = frame.getChildAt(1) as? DiffGutterOverlay
                if (reviewHunks.isNotEmpty()) {
                    if (overlay == null) {
                        val created = DiffGutterOverlay(e, reviewHunks) { h ->
                            currentReviewHunk = h.index
                            e.setSelection((h.newStart - 1).coerceAtLeast(0), 0)
                        }
                        frame.addView(created, FrameLayout.LayoutParams(
                            (18 * frame.resources.displayMetrics.density).toInt(),
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.START
                        ))
                        diffOverlay = created
                    } else {
                        overlay.setEditor(e)
                        overlay.updateHunks(reviewHunks)
                        overlay.setSelectedHunk(currentReviewHunk)
                        diffOverlay = overlay
                    }
                } else if (overlay != null) {
                    frame.removeView(overlay)
                    diffOverlay = null
                }
            }
        )
        }
        if (fileDiagnostics.isNotEmpty()) {
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(8.dp)) {
                    val errorCount = fileDiagnostics.count { it.severity == Severity.ERROR }
                    val warningCount = fileDiagnostics.count { it.severity == Severity.WARNING }
                    Text(
                        "代码诊断（${fileDiagnostics.size}）· 错误 $errorCount · 警告 $warningCount",
                        style = MaterialTheme.typography.titleSmall
                    )
                    LazyColumn(Modifier.heightIn(max = 140.dp)) {
                        items(fileDiagnostics) { d ->
                            Text(
                                "${(d.line ?: 0) + 1}:${(d.column ?: 0) + 1} ${d.message}",
                                color = when (d.severity) {
                                    Severity.ERROR -> MaterialTheme.colorScheme.error
                                    Severity.WARNING -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(vertical = 3.dp)
                                    .clickable {
                                        val target = editor ?: return@clickable
                                        runCatching {
                                            target.setSelection(
                                                (d.line ?: 0).coerceAtLeast(0),
                                                (d.column ?: 0).coerceAtLeast(0)
                                            )
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
        message?.let { Text(it, Modifier.padding(8.dp)) }
        EditorStatusBar(
            line = cursorLine + 1,
            column = cursorColumn + 1,
            encoding = "UTF-8",
            lineEnding = "LF",
            languageMode = file.extension.lowercase().ifBlank { null },
            gitBranch = null,
            lineColumnTemplate = androidx.compose.ui.res.stringResource(com.nebulaforge.app.R.string.editor_status_bar_line_column),
            scrollPercent = scrollPercent
        )
    }
}

/** 诊断真源（会话）级别 → Sora 波浪线级别；扩展/LSP 诊断共用。 */
private fun soraSeverityOfSession(severity: Severity): Short = when (severity) {
    Severity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
    Severity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
    Severity.INFO -> DiagnosticRegion.SEVERITY_TYPO
}
