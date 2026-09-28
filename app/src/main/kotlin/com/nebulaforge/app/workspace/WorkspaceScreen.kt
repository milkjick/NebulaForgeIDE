package com.nebulaforge.app.workspace

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.session.WorkspaceStateStore
import com.nebulaforge.core.toolwindow.ToolWindowHost
import com.nebulaforge.core.toolwindow.ToolWindowRegistry
import com.nebulaforge.core.toolwindow.ToolWindowDescriptor
import com.nebulaforge.core.toolwindow.ToolWindowAnchor
import com.nebulaforge.core.toolwindow.BuiltInToolWindows
import com.nebulaforge.core.mcp.InternalMcpServer
import org.json.JSONObject
import com.nebulaforge.app.terminal.TermuxTerminalScreen
import com.nebulaforge.app.build.BuildCenterScreen
import com.nebulaforge.app.problems.ProblemsScreen
import com.nebulaforge.core.projectmodel.TasksJson
import com.nebulaforge.app.run.RunToolWindowScreen
import com.nebulaforge.app.plugins.PluginCommandMenuItem
import com.nebulaforge.app.plugins.PluginCommandPaletteDialog
import java.io.File
import androidx.compose.ui.viewinterop.AndroidView
import com.nebulaforge.core.editorui.EditorFileTabRow
import com.nebulaforge.core.editorui.EditorFileTabState
import com.nebulaforge.core.editorui.EditorBreadcrumbBar
import com.nebulaforge.core.editorui.EditorStatusBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class OpenDoc(val file: File, val text: String, val dirty: Boolean, val recoveredDirty: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onOpenFile: (String) -> Unit = {},
    onOpenBuild: () -> Unit = {},
    onOpenProblems: () -> Unit = {},
    onOpenRun: () -> Unit = {},
    onOpenNewProject: () -> Unit = {},
    onOpenDatabase: () -> Unit = {},
    pendingOpenProject: String? = null,
    onPendingOpenProjectConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as com.nebulaforge.app.NebulaForgeApplication
    val workspace = remember(context) { WorkspaceStateStore(context) }
    val persistedWorkspace by workspace.state.collectAsState()
    var projects by remember { mutableStateOf(listProjects(context)) }
    var selectedProject by remember { mutableStateOf(persistedWorkspace.projectPath?.let(::File)?.takeIf { it.isDirectory } ?: projects.firstOrNull()) }
    var openDocs by remember(persistedWorkspace.projectPath) {
        mutableStateOf(
            persistedWorkspace.openDocuments.mapNotNull { d ->
                val f = File(d.path)
                if (!f.isFile || f.length() > 4_000_000) return@mapNotNull null
                OpenDoc(f, runCatching { f.readText() }.getOrDefault(""), d.dirty, d.dirty)
            }
        )
    }
    var activePath by remember { mutableStateOf(persistedWorkspace.activeFile?.takeIf { File(it).isFile }) }
    var filter by remember { mutableStateOf("") }
    // 代码优先：项目树与工具窗口都改为按需唤出的抽屉，屏幕上不再常驻它们的入口按钮。
    // 项目树分栏：可见性 / 宽度 / 字号都持久化在 workspace state，重启后保持上次布局。
    var treeWidthDraft by remember { mutableStateOf(persistedWorkspace.treeWidthDp.toFloat()) }
    var narrowTreeInitialized by remember { mutableStateOf(false) }
    val notifyScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val notify: (String) -> Unit = { msg -> notifyScope.launch { snackbarHostState.showSnackbar(msg) } }

    // ---- 插件扩展宿主接入（工作区编辑器是主编辑入口，必须与独立编辑器同语义）----------------
    // 此前只有独立编辑器接了宿主：工作区里「扩展看不到工作区、收不到文档事件、也没有编辑器桥」，
    // 结果就是「插件明明激活了，在 IDE 编辑器里却调不动、改了也不生效」。
    val extensionHost = remember(context) { com.nebulaforge.app.plugins.JsExtensionHost.of(context) }
    // 插件的界面请求（激活时确认工具链、选解释器等）必须有落点：没有对话框时请求会超时，
    // 扩展把「无人可问」误判成「用户取消」，直接宣告激活失败。
    com.nebulaforge.app.plugins.PluginUiRequestHost(extensionHost)
    // 文档版本号（扩展侧据此判断内容新旧）：按 uri 记录，跨标签页切换不乱序。
    val docVersions = remember { HashMap<String, Int>() }
    // 切换/打开项目时把工作区根注入宿主，并激活 `*` / `onStartupFinished` 扩展（VS Code 语义）。
    LaunchedEffect(selectedProject?.absolutePath) {
        val root = selectedProject
        // 宿主重建会拉起/结束扩展进程，必须离开主线程。
        withContext(Dispatchers.IO) { runCatching { extensionHost.refresh(root) } }
        extensionHost.activateStartupExtensions()
    }
    // 活动文档变化 → 通知宿主（触发 `onLanguage:<id>` 激活并更新「活动编辑器」）。
    LaunchedEffect(activePath, openDocs.map { it.file.absolutePath }) {
        val doc = openDocs.firstOrNull { it.file.absolutePath == activePath } ?: return@LaunchedEffect
        val uri = doc.file.toURI().toString()
        val languageId = com.nebulaforge.core.editor.intel.DeclarativeContributions
            .languageIdFor(doc.file.name, doc.file.extension.lowercase())
            .orEmpty()
        if (languageId.isNotBlank()) {
            val version = docVersions.getOrDefault(uri, 1)
            docVersions[uri] = version
            extensionHost.documentOpened(uri, languageId, doc.text, version)
            extensionHost.activeDocumentChanged(uri)
        }
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            extensionHost.activeDocumentChanged(null)
        }
    }

    // 顶栏「构建」的「构建方式」弹窗：AGP/Gradle 工程先让用户选（全量包 / 测试包 / 只编译一部分 / …），
    // 与构建面板的 ▶ 共用同一套 AndroidBuildPlan 与按工程持久化 —— 两处入口行为必须一致，
    // 否则用户从顶栏点构建会「绕过」自己刚在面板里选好的构建方式。
    val planStore = remember(context) { com.nebulaforge.app.build.BuildPlanStore(context) }
    var planRoot by remember { mutableStateOf<File?>(null) }
    // Flutter 工程不是 Gradle 工程，走不了上面那套 AndroidBuildPlan；但用户要求「像原生安卓那样能选」，
    // 所以这里再挂一套同构的 FlutterBuildPlan（测试包 / 全量包 / 只编 arm64 / AAB / analyze …）。
    val flutterPlanStore = remember(context) { com.nebulaforge.app.build.FlutterPlanStore(context) }
    var flutterPlanRoot by remember { mutableStateOf<File?>(null) }

    // 顶栏「构建」：跑工作区任务并让底部构建终端自动展开 + 聚焦构建工具窗。
    // tasks.json 的解析放 IO 线程：用户可能在仓库里放了很大的 tasks.json，别卡住首帧。
    val startDefaultBuild: (java.io.File) -> Unit = { root ->
        notifyScope.launch {
            // AGP/Gradle 工程：先弹「构建方式」，让用户自己决定编什么、编哪一部分。
            // 非 Gradle 工程（Python/Node…）没有变体与模块概念，弹窗里的选项全是噪音，直接跑任务。
            if (com.nebulaforge.app.build.AndroidBuildPlan.isGradleProject(root)) {
                workspace.selectToolWindow("build_output")
                workspace.updateBottomPanel(visible = true)
                planRoot = root
                return@launch
            }
            // Flutter 工程：同样先弹「构建方式」。Flutter 没有 Gradle 模块概念，
            // 「编哪一部分」换成 ABI（只编 arm64 / 按 ABI 拆分），并额外提供 analyze 作为最快的编译验证。
            if (com.nebulaforge.app.build.FlutterBuildPlan.isFlutterProject(root)) {
                workspace.selectToolWindow("build_output")
                workspace.updateBottomPanel(visible = true)
                flutterPlanRoot = root
                return@launch
            }
            // 构建目标跟随**当前打开的文件**（活动标签）：`greeter.py` 打开时点构建就跑 greeter.py，
            // 而不是固定跑 main.py。用本地 activePath（切标签时它最先更新），再回退到持久化状态。
            val active = activePath?.let(::File)?.takeIf { it.isFile }
                ?: persistedWorkspace.activeFile?.let(::File)?.takeIf { it.isFile }
            val task = withContext(Dispatchers.IO) {
                val all = runCatching { TasksJson.load(root, active) }.getOrDefault(emptyList())
                all.firstOrNull { it.isBuild } ?: all.firstOrNull()
            }
            if (task == null) {
                notify("项目里没有可执行任务（.vscode/tasks.json 为空）")
            } else {
                workspace.selectToolWindow("build_output")
                workspace.updateBottomPanel(visible = true)
                app.workspaceTasks.run(root, task)
            }
        }
    }
    var toolsSheetOpen by remember { mutableStateOf(false) }
    // 「打开文件夹」路径浏览器：就地打开任意目录作为项目（见 ProjectPane 的说明）。
    var folderBrowserOpen by remember { mutableStateOf(false) }
    val toolWindows by ToolWindowRegistry.windows.collectAsState()

    // 项目目录规范化：旧项目迁移到公共存储后，用一次性 Snackbar 告知用户迁移结果。
    LaunchedEffect(Unit) {
        Environment.consumeMigrationNotice()?.let { notify(it) }
    }

    // 新建向导是独立全屏路由页，返回本页后需要重新扫描项目目录，
    // 否则刚创建的项目不会出现在列表里（也覆盖在终端里新增目录的场景）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val latest = listProjects(context)
        projects = latest
        val current = selectedProject
        if (current == null || !current.isDirectory) selectedProject = latest.firstOrNull()
    }

    // 新建向导返回后携带的新建项目路径：切换选中项并提示用户。
    LaunchedEffect(pendingOpenProject) {
        val path = pendingOpenProject ?: return@LaunchedEffect
        val created = File(path)
        if (created.isDirectory) {
            projects = listProjects(context)
            selectedProject = created
            workspace.selectProject(created.absolutePath)
            notify("项目已创建：${created.name}")
        } else {
            notify("项目已创建，但目录不存在：$path")
        }
        onPendingOpenProjectConsumed()
    }

    LaunchedEffect(selectedProject) {
        workspace.selectProject(selectedProject?.absolutePath)
        val root = selectedProject
        if (root != null) {
            val server = InternalMcpServer(
                root,
                buildHandler = { task ->
                    val configs = app.runConfigurations.forProject(root)
                    val spec = configs.firstOrNull { it.id == task } ?: configs.firstOrNull()
                    if (spec == null) {
                        JSONObject().put("success", false).put("message", "当前项目没有 Run Configuration")
                    } else {
                        app.unifiedRunController.build(spec).collect { }
                        JSONObject().put("success", true).put("configurationId", spec.id).put("message", "构建任务已完成")
                    }
                },
                // 逆向能力（APK/Web/API/CA）+ 会话列表：统一挂在项目 Server 上，
                // 让 MCP 面板与聚合网关连上就能 listTools 到完整能力，而不是只有 read_file 这类基础工具。
                extraTools = app.reverseMcpExtraTools() + mapOf(
                    "list_sessions" to (com.nebulaforge.core.mcp.McpToolDefinition("list_sessions", "列出当前 IDE 会话", JSONObject().put("type", "object")) to {
                        JSONObject().put("sessions", org.json.JSONArray(app.sessionStateProjection.items.value.map { item ->
                            JSONObject().put("id", item.id).put("kind", item.kind.name).put("state", item.state.toString()).put("projectPath", item.projectPath ?: "")
                        }))
                    })
                )
            )
            app.mcpHost.registerInternal("project:${root.absolutePath}", server)
        }
    }

    // Recovery is metadata-only by design: unsaved source text is never serialized.
    // A dirty tab restored after process death is therefore reopened from disk and marked
    // "恢复但未保存" instead of pretending the lost buffer was recovered.
    LaunchedEffect(persistedWorkspace.activeFile, persistedWorkspace.openDocuments) {
        val valid = persistedWorkspace.openDocuments.mapNotNull { d ->
            val f = File(d.path)
            if (!f.isFile || f.length() > 4_000_000) null
            else OpenDoc(f, runCatching { f.readText() }.getOrDefault(""), d.dirty, d.dirty)
        }
        if (valid.isNotEmpty()) openDocs = valid
        activePath = persistedWorkspace.activeFile?.takeIf { File(it).isFile }
    }

    fun openFile(file: File) {
        if (!file.isFile || file.length() > 4_000_000) return
        val existing = openDocs.firstOrNull { it.file.absolutePath == file.absolutePath }
        if (existing == null) openDocs = openDocs + OpenDoc(file, runCatching { file.readText() }.getOrDefault(""), false, false)
        activePath = file.absolutePath
        workspace.openDocument(file.absolutePath)
    }
    fun updateActive(text: String) {
        val path = activePath ?: return
        openDocs = openDocs.map { if (it.file.absolutePath == path) it.copy(text = text, dirty = true, recoveredDirty = false) else it }
        workspace.markDirty(path, true)
        // 扩展语义：文档变更必须让宿主知道（否则扩展的格式化/诊断/补全一直按首次打开的内容算，
        // 表现为「插件装了、命令也能跑，但结果永远对不上当前代码」）。
        val uri = File(path).toURI().toString()
        val version = docVersions.getOrDefault(uri, 1) + 1
        docVersions[uri] = version
        extensionHost.documentChanged(uri, text, version)
    }
    fun saveActive() {
        val path = activePath ?: return
        openDocs = openDocs.map {
            if (it.file.absolutePath == path) {
                runCatching { it.file.writeText(it.text) }
                workspace.markDirty(it.file.absolutePath, false)
                it.copy(dirty = false, recoveredDirty = false)
            } else it
        }
        extensionHost.documentSaved(File(path).toURI().toString())
    }

    // 真正的编辑器不该让人先看空白页：进入工作区时若一个文件都没打开，自动打开项目入口源文件。
    // 只自动执行一次，用户手动关掉所有标签后不会被再次"抢着打开"。
    var autoOpenedEntry by remember { mutableStateOf(false) }
    LaunchedEffect(selectedProject, openDocs.isEmpty()) {
        if (autoOpenedEntry || openDocs.isNotEmpty()) return@LaunchedEffect
        autoOpenedEntry = true
        val root = selectedProject ?: return@LaunchedEffect
        val entry = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { pickEntryFile(root) }
        if (entry != null) openFile(entry)
    }

    // 关闭标签后必须把 activePath 移到相邻标签。此前的实现只从 openDocs 里移除，
    // 导致 activePath 悬空指向已关闭文件：标签条失去选中态，索引计算也会得到 -1。
    fun closeDoc(path: String) {
        val index = openDocs.indexOfFirst { it.file.absolutePath == path }
        val remaining = openDocs.filterNot { it.file.absolutePath == path }
        openDocs = remaining
        workspace.closeDocument(path)
        extensionHost.documentClosed(File(path).toURI().toString())
        if (activePath == path) {
            activePath = remaining.getOrNull((index - 1).coerceAtLeast(0))?.file?.absolutePath
                ?: remaining.firstOrNull()?.file?.absolutePath
            // 活动文件也必须跟着走，否则它会悬空指向已关闭的文件（构建目标随之跑偏）。
            activePath?.let { workspace.selectDocument(it) }
        }
    }

    // 导入状态：文件夹导入与压缩包导入共用一套进度与结果提示。
    // 导入大工程/大压缩包可能持续数十秒，必须在 IO 线程执行并回显进度，
    // 否则界面看起来像卡死，用户会误判成功能不可用。
    var importSheetOpen by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    val importScope = rememberCoroutineScope()

    fun runImport(label: String, block: () -> ProjectImporter.Result) {
        importScope.launch {
            importStatus = "正在导入：$label"
            importSheetOpen = true          // 让进度可见，导入过程不再是没有反馈的黑盒
            val outcome = withContext(Dispatchers.IO) { runCatching { block() } }
            outcome.onSuccess { result ->
                projects = listProjects(context)
                selectedProject = result.target
                workspace.selectProject(result.target.absolutePath)
                importStatus = buildString {
                    append("导入完成：").append(result.target.name)
                    append("（").append(result.files).append(" 个文件")
                    if (result.skipped > 0) append("，跳过 ").append(result.skipped).append(" 个")
                    append("）")
                }
            }.onFailure { error ->
                importStatus = "导入失败：${error.message ?: error.javaClass.simpleName}"
            }
        }
    }

    // 本地文件夹：使用 SAF 目录树，递归导入（不依赖存储权限）
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val destRoot = File(Environment.projectsDir(context))
        val hint = uri.lastPathSegment?.substringAfterLast(':')
        runImport("本地文件夹") { ProjectImporter.importFolder(context, uri, destRoot, hint) }
    }

    // 压缩包：使用 SAF 单文件选择，解压后作为一个新项目导入
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val destRoot = File(Environment.projectsDir(context))
        val hint = ProjectImporter.displayName(context, uri)
        runImport("压缩包") { ProjectImporter.importZip(context, uri, destRoot, hint) }
    }

    DisposableEffect(selectedProject) {
        ToolWindowRegistry.register(
            ToolWindowDescriptor(
                id = "project_tree", title = "项目", anchor = ToolWindowAnchor.LEFT
            ) {
                Column(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 520.dp).padding(8.dp)) {
                    ProjectPane(
                        projects, selectedProject, { selectedProject = it },
                        filter, { filter = it },
                        { file -> openFile(file) },
                        activePath,
                        fontScale = persistedWorkspace.treeFontScale,
                        onFontScale = { workspace.updateTreeLayout(fontScale = it) },
                        onNewProject = onOpenNewProject,
                        onImportProject = { importStatus = null; importSheetOpen = true },
                        onOpenFolder = { folderBrowserOpen = true },
                        onCollapse = { workspace.updateTreeLayout(visible = false) },
                        onNotify = notify,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = "terminal", title = "终端", anchor = ToolWindowAnchor.BOTTOM) {
                TermuxTerminalScreen()
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = "build_output", title = "构建", anchor = ToolWindowAnchor.BOTTOM) {
                BuildCenterScreen(
                    projectRoot = selectedProject?.absolutePath,
                    onOpenFile = { path -> onOpenFile(path) },
                    onOpenRun = onOpenRun,
                    onOpenProblems = onOpenProblems,
                    // 「在终端打开」：构建日志本就跑在 pty 里，这里把底部面板切到「终端」工具窗，
                    // 让用户能在真实终端里接管同一会话继续敲命令（VSCode 的任务终端语义）。
                    onOpenTerminal = {
                        workspace.selectToolWindow("terminal")
                        workspace.updateBottomPanel(visible = true)
                    },
                    // 「切到该工程」：把当前工程切到产出这份构建输出的工程（构出输出属于工程，不属于文件）。
                    onOpenProject = { path -> File(path).takeIf { it.isDirectory }?.let { selectedProject = it } }
                )
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = "problems", title = "问题", anchor = ToolWindowAnchor.BOTTOM) {
                ProblemsScreen(
                    onOpenFile = { location -> onOpenFile(location.file) },
                    onAiFix = onOpenBuild
                )
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = BuiltInToolWindows.RUN, title = "运行", anchor = ToolWindowAnchor.BOTTOM) {
                RunToolWindowScreen(
                    onOpenFile = { path -> onOpenFile(path) },
                    // 「构建」按钮直接切到构建工具窗（那里是真终端 + 问题匹配），
                    // 而不是在运行面板里再实现一遍构建 —— VSCode 也是「Run 任务」与「Build 任务」同属任务系统。
                    onOpenBuild = { workspace.selectToolWindow("build_output") }
                )
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = BuiltInToolWindows.STRUCTURE, title = "结构", anchor = ToolWindowAnchor.LEFT) {
                StructureToolWindow(activePath)
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = BuiltInToolWindows.LOGCAT, title = "Logcat", anchor = ToolWindowAnchor.BOTTOM) {
                LogcatToolWindow()
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = BuiltInToolWindows.VERSION_CONTROL, title = "版本控制", anchor = ToolWindowAnchor.BOTTOM) {
                VersionControlToolWindow(selectedProject)
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = BuiltInToolWindows.MCP_LOG, title = "MCP 日志", anchor = ToolWindowAnchor.BOTTOM) {
                McpLogToolWindow()
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = BuiltInToolWindows.GRADLE, title = "Gradle", anchor = ToolWindowAnchor.RIGHT) {
                GradleToolWindow()
            }
        )
        ToolWindowRegistry.register(
            ToolWindowDescriptor(id = BuiltInToolWindows.NOTIFICATIONS, title = "通知", anchor = ToolWindowAnchor.RIGHT) {
                NotificationsToolWindow()
            }
        )
        onDispose {
            BuiltInToolWindows.all.forEach(ToolWindowRegistry::unregister)
        }
    }

    ToolWindowHost(
        selectedId = persistedWorkspace.selectedToolWindow,
        onSelected = workspace::selectToolWindow,
        showAffordances = false,
        // 真实 IDE 形态：终端/构建/问题/运行/Logcat 常驻底部工具窗口，编辑器留在上方可见；
        // 展开状态与面板高度随工作区持久化，重启后保持上次布局。
        dockBottom = true,
        bottomPanelVisible = persistedWorkspace.bottomPanelVisible,
        bottomPanelHeightDp = persistedWorkspace.bottomPanelHeightDp,
        onBottomPanelVisibilityChange = { workspace.updateBottomPanel(visible = it) },
        onBottomPanelHeightChange = { workspace.updateBottomPanel(heightDp = it) },
        mainContent = {
            Box(Modifier.fillMaxSize()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 700.dp
                    val treeVisible = persistedWorkspace.treeVisible
                    val treeFontScale = persistedWorkspace.treeFontScale
                    val minTreeWidth = 196f
                    val maxTreeWidth = (maxWidth.value - 176f).coerceAtLeast(minTreeWidth)
                    // 窄屏首次进入先收起分栏，让代码区占满；之后随时用顶栏文件夹图标唤出，
                    // 宽度由 TreeResizeHandle 左右拖拽调整（窄屏下不做自动收起，避免"刚点开就消失"）。
                    LaunchedEffect(wide) {
                        if (!narrowTreeInitialized) {
                            narrowTreeInitialized = true
                            if (!wide && treeVisible) workspace.updateTreeLayout(visible = false)
                        }
                    }
                    Column(Modifier.fillMaxSize()) {
                        // 细顶栏（44dp）：项目树开关 + 项目名 + 运行 + 工具窗口。
                        Row(
                            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { workspace.updateTreeLayout(visible = !treeVisible) }) {
                                Icon(
                                    if (treeVisible) Icons.Outlined.Folder else Icons.Outlined.FolderOpen,
                                    contentDescription = if (treeVisible) "收起项目树" else "展开项目树"
                                )
                            }
                            Text(
                                selectedProject?.name ?: "未选择项目",
                                modifier = Modifier.weight(1f).padding(start = 6.dp),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // VSCode 式一键构建：展开底部「构建」终端并直接跑 tasks.json 里的 build 任务。
                            // 找不到 build 任务时退回第一个任务（模板生成的项目总会有）。
                            IconButton(
                                onClick = {
                                    val root = selectedProject
                                    if (root == null) {
                                        notify("先打开一个项目")
                                    } else {
                                        startDefaultBuild(root)
                                    }
                                },
                                enabled = selectedProject != null
                            ) {
                                Icon(Icons.Outlined.Build, contentDescription = "构建")
                            }
                            IconButton(onClick = { onOpenRun() }) {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = "运行")
                            }
                            IconButton(onClick = { toolsSheetOpen = true }) {
                                Icon(Icons.Outlined.Widgets, contentDescription = "工具窗口")
                            }
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxSize()) {
                            if (treeVisible) {
                                ProjectPane(
                                    projects, selectedProject, { selectedProject = it },
                                    filter, { filter = it },
                                    { file -> openFile(file) },
                                    activePath,
                                    fontScale = treeFontScale,
                                    onFontScale = { workspace.updateTreeLayout(fontScale = it) },
                                    onNewProject = onOpenNewProject,
                                    onImportProject = { importStatus = null; importSheetOpen = true },
                        onOpenFolder = { folderBrowserOpen = true },
                                    onCollapse = { workspace.updateTreeLayout(visible = false) },
                                    onNotify = notify,
                                    modifier = Modifier.width(treeWidthDraft.dp).fillMaxHeight()
                                )
                                TreeResizeHandle(
                                    onDeltaDp = { d -> treeWidthDraft = (treeWidthDraft + d).coerceIn(minTreeWidth, maxTreeWidth) },
                                    onCommit = { workspace.updateTreeLayout(widthDp = treeWidthDraft.toInt()) }
                                )
                            }
                            EditorPane(openDocs, activePath, ::saveActive, ::updateActive, { p ->
                                // 切换标签：本地 activePath 之外，必须同步到 WorkspaceState.activeFile，
                                // 否则「构建」仍会拿上一个文件当目标（选 greeter.py 却跑 main.py）。
                                activePath = p
                                workspace.selectDocument(p)
                            }, ::closeDoc, selectedProject, extensionHost, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
                SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
            }
        }
    )


    // 工具窗口抽屉：终端 / 构建 / 问题 / 运行 / Logcat / 版本控制 / Gradle / 通知 / MCP 日志，全部按需唤出。
    // 导入项目面板：由项目树的「导入项目」直接触发，与工具窗口抽屉相互独立。
    // 此前它被嵌在 if (toolsSheetOpen) 内，直接点「导入项目」时 toolsSheetOpen 仍为 false，
    // 弹层永远不会出现 —— 这是一个真实的显示 bug，这里一并修掉。
    // 「打开文件夹」路径浏览器：**就地**把设备上任意目录当项目打开（不复制文件）。
    // 不走系统 SAF 目录选择器：Android 11+ 的 SAF 会屏蔽 Download、Android/data 等目录，
    // 而用户想打开的工程常常正好在里面；应用本身已有公共存储读写权，按路径列目录更可靠。
    if (folderBrowserOpen) {
        FolderBrowserSheet(
            context = context,
            onDismiss = { folderBrowserOpen = false },
            onOpen = { dir ->
                folderBrowserOpen = false
                // 「打开文件夹」既要能就地打开外部工程，也常被用来在已有项目里跳目录 —— 后者**绝不能**
                // 变成一个新项目。用户报的「每次打开项目工作区都会自动新建一个项目」就是这里：
                // 旧实现把**任意**选中的目录都登记成外部根，而项目列表 = 受管项目 + 全部外部根，
                // 于是在项目里点几下（app、app/src/main/res/values、.dart_tool…）列表里就多出几个「项目」。
                val added = addExtraRoot(context, dir)
                projects = listProjects(context)
                val asProject = if (added) dir else projectRootFor(context, dir) ?: dir
                selectedProject = asProject
                workspace.selectProject(asProject.absolutePath)
                notify(
                    if (added) {
                        "已就地打开：${asProject.absolutePath}（文件保留在原位，未复制）"
                    } else {
                        "已切换到 ${asProject.absolutePath}（该目录属于现有项目，未新建项目）"
                    }
                )
            },
            onRemoveExtra = { path ->
                removeExtraRoot(context, path)
                projects = listProjects(context)
                val cur = selectedProject
                if (cur != null && cur.absolutePath == path) {
                    selectedProject = projects.firstOrNull()
                }
            }
        )
    }

    if (importSheetOpen) {
        ModalBottomSheet(onDismissRequest = { importSheetOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("导入项目", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "两种来源：本地文件夹（完整保留目录结构）与压缩包 zip（自动剥掉外层项目目录）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                ImportActionRow(
                    "导入本地文件夹",
                    "选择设备中的工程目录，递归导入全部子目录与文件"
                ) {
                    importSheetOpen = false
                    folderPicker.launch(null)
                }
                ImportActionRow(
                    "导入压缩包（zip）",
                    "选择 zip 压缩包，解压并作为一个新项目导入"
                ) {
                    importSheetOpen = false
                    zipPicker.launch(
                        arrayOf(
                            "application/zip", "application/x-zip-compressed",
                            "application/octet-stream", "*/*"
                        )
                    )
                }
                importStatus?.let { status ->
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (status.startsWith("正在导入")) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    // 顶栏「构建」触发的「构建方式」弹窗。选完即按选择构造命令，交给与构建面板同一个 runner
    // （同一套 pty 会话、结束标记、问题匹配、产物事件），所以输出/问题/产物三处表现完全一致。
    planRoot?.let { dir ->
        val initialPlan = remember(dir) { planStore.load(dir) }
        com.nebulaforge.app.build.AndroidBuildDialog(
            projectName = dir.name,
            projectRoot = dir,
            initial = initialPlan,
            onDismiss = { planRoot = null },
            onStart = { picked ->
                planStore.save(dir, picked)
                planRoot = null
                workspace.selectToolWindow("build_output")
                workspace.updateBottomPanel(visible = true)
                app.workspaceTasks.runCommand(
                    root = dir,
                    label = "Gradle: " + picked.label(),
                    commandLine = picked.commandLine(dir),
                    kind = com.nebulaforge.core.session.SessionKind.BUILD,
                    matchers = listOf("\$nebula-gradle")
                )
            }
        )
    }

    // Flutter 版「构建方式」弹窗：交互与安卓一致，命令换成 flutter CLI
    // （含「模板工程缺 android/ 骨架」的自愈前缀，所以选完真的能编出来）。
    flutterPlanRoot?.let { dir ->
        val initialPlan = remember(dir) { flutterPlanStore.load(dir) }
        com.nebulaforge.app.build.FlutterBuildDialog(
            projectName = dir.name,
            initial = initialPlan,
            onDismiss = { flutterPlanRoot = null },
            onStart = { picked ->
                flutterPlanStore.save(dir, picked)
                flutterPlanRoot = null
                workspace.selectToolWindow("build_output")
                workspace.updateBottomPanel(visible = true)
                app.workspaceTasks.runCommand(
                    root = dir,
                    label = "Flutter: " + picked.label(),
                    commandLine = picked.command(),
                    kind = com.nebulaforge.core.session.SessionKind.BUILD,
                    matchers = listOf("\$nebula-dart")
                )
            }
        )
    }

    // 工具窗口抽屉：终端 / 构建 / 问题 / 运行 / Logcat / 版本控制 / Gradle / 通知 / MCP 日志。
    // 注：底部锚点窗口现在常驻在 ToolWindowHost 的底部面板里，本列表作为完整入口兜底。
    if (toolsSheetOpen) {
        ModalBottomSheet(onDismissRequest = { toolsSheetOpen = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text("工具窗口", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                Spacer(Modifier.height(4.dp))
                toolWindows.filter { it.id != "project_tree" }.forEach { window ->
                    ListItem(
                        headlineContent = { Text(window.title) },
                        leadingContent = { Icon(toolWindowIcon(window.id), null) },
                        modifier = Modifier.clickable {
                            toolsSheetOpen = false
                            workspace.selectToolWindow(window.id)
                            // 底部锚点（终端/构建/问题/运行…）：直接升起常驻底部面板，
                            // 不再用弹层遮住编辑区；左侧/右侧锚点仍按原方式呈现。
                            if (window.anchor == ToolWindowAnchor.BOTTOM) {
                                workspace.updateBottomPanel(visible = true)
                            }
                        }
                    )
                }
                // 数据库管理：不是普通工具窗，而是独立全屏路由页。
                ListItem(
                    headlineContent = { Text("数据库管理") },
                    supportingContent = { Text("浏览 SQLite 表/数据并执行 SQL") },
                    leadingContent = { Icon(Icons.Outlined.Storage, null) },
                    modifier = Modifier.clickable {
                        toolsSheetOpen = false
                        onOpenDatabase()
                    }
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectPane(
    projects: List<File>, selected: File?, onSelect: (File) -> Unit,
    filter: String, onFilterChange: (String) -> Unit,
    onOpen: (File) -> Unit, activePath: String?,
    fontScale: Float, onFontScale: (Float) -> Unit,
    onNewProject: () -> Unit, onImportProject: () -> Unit,
    onOpenFolder: () -> Unit,
    onCollapse: () -> Unit, onNotify: (String) -> Unit,
    modifier: Modifier
) {
    // 文件树不做文件监听，改用一个"刷新令牌"：任何写盘操作后自增，作为 remember key 重新 listFiles。
    var treeVersion by remember { mutableStateOf(0) }
    // 新建目标目录：默认项目根，点过某个文件夹后就以那个文件夹为准；长按任意节点也能指定层级。
    var currentDir by remember(selected?.absolutePath) { mutableStateOf(selected) }
    // 重命名 / 删除的操作对象（点中的文件或文件夹）。
    var target by remember(selected?.absolutePath) { mutableStateOf<File?>(null) }
    var dialog by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }

    val hintStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (10.5f * fontScale).sp)

    fun requestNew(parent: File?, isDir: Boolean) {
        currentDir = parent ?: selected
        nameInput = ""
        errorText = null
        dialog = if (isDir) "dir" else "file"
    }
    fun requestRename(f: File?) {
        if (f == null) return
        target = f
        nameInput = f.name
        errorText = null
        dialog = "rename"
    }
    fun requestDelete(f: File?) {
        if (f == null) return
        target = f
        errorText = null
        dialog = "delete"
    }

    Column(modifier.padding(end = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.FolderOpen, contentDescription = null,
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text("项目", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            MiniIconButton(Icons.Outlined.Refresh, "刷新") { treeVersion++ }
            MiniIconButton(Icons.Outlined.Close, "收起项目树") { onCollapse() }
        }
        // 操作全部收进可自动换行的工具带：分栏再窄也不会把按钮挤出屏幕。
        FlowRow(Modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp)) {
            MiniIconButton(Icons.Outlined.NoteAdd, "新建文件") { requestNew(currentDir ?: selected, false) }
            MiniIconButton(Icons.Outlined.CreateNewFolder, "新建文件夹") { requestNew(currentDir ?: selected, true) }
            MiniIconButton(Icons.Outlined.DriveFileRenameOutline, "重命名", enabled = target != null) { requestRename(target) }
            MiniIconButton(Icons.Outlined.DeleteOutline, "删除", enabled = target != null) { requestDelete(target) }
            MiniIconButton(Icons.Outlined.Search, "过滤") { filterOpen = !filterOpen }
            MiniIconButton(Icons.Outlined.FolderCopy, "新建项目") { onNewProject() }
            MiniIconButton(Icons.Outlined.Upload, "导入项目") { onImportProject() }
            // 就地打开：把设备上任意目录当项目打开，**不复制**文件（仓库留在原地，
            // 只把路径登记进工作区）。这是「其它文件夹的项目无法直接在 IDE 打开」的修复入口。
            MiniIconButton(Icons.Outlined.FolderOpen, "打开文件夹") { onOpenFolder() }
            MiniIconButton(Icons.Outlined.TextDecrease, "缩小字号", enabled = fontScale > 0.7f) {
                onFontScale((fontScale - 0.1f).coerceAtLeast(0.7f))
            }
            MiniIconButton(Icons.Outlined.TextIncrease, "放大字号", enabled = fontScale < 1.7f) {
                onFontScale((fontScale + 0.1f).coerceAtMost(1.7f))
            }
        }
        Text(
            "新建位置：" + (currentDir?.name ?: "先点一个文件夹"),
            style = hintStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (filterOpen) {
            OutlinedTextField(
                value = filter, onValueChange = onFilterChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("过滤文件", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)) },
                trailingIcon = {
                    if (filter.isNotEmpty()) {
                        IconButton(onClick = { onFilterChange("") }) {
                            Icon(Icons.Outlined.Close, "清除过滤", Modifier.size(16.dp))
                        }
                    }
                }
            )
        }
        // 只显示「当前打开的项目」。
        // 之前把 workspace 下所有项目平铺成多个根节点，再展开选中的那个，视觉上等于
        // 把整片项目目录都塞进文件树；现在根节点唯一，其它项目收进下拉菜单切换。
        val opened = selected ?: projects.firstOrNull()
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                var switcherOpen by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            opened?.let { currentDir = it; target = it }
                        }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen, contentDescription = null,
                        modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        opened?.name ?: "未打开项目", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = (14f * fontScale).sp),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (projects.size > 1) {
                        Box {
                            MiniIconButton(Icons.Outlined.SwapHoriz, "切换项目") { switcherOpen = true }
                            DropdownMenu(expanded = switcherOpen, onDismissRequest = { switcherOpen = false }) {
                                projects.forEach { project ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                project.name, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        onClick = {
                                            switcherOpen = false
                                            onSelect(project)
                                            currentDir = project
                                            target = project
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (opened == null) {
                item {
                    Text(
                        "还没有项目。用工具栏的「新建项目」或「导入项目」创建后，这里只显示当前打开的项目文件。",
                        style = hintStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)
                    )
                }
            } else {
                item {
                    TreeNode(
                        base = opened, file = opened, filter = filter, onOpen = onOpen,
                        activePath = activePath, targetPath = target?.absolutePath,
                        refreshToken = treeVersion, fontScale = fontScale, projectRoot = opened,
                        onSelectDir = { dir -> currentDir = dir; target = dir },
                        onSelectFileEntry = { f -> target = f; currentDir = f.parentFile },
                        onRequestNew = { parent, isDir -> requestNew(parent, isDir) },
                        onRequestRename = { f -> requestRename(f) },
                        onRequestDelete = { f -> requestDelete(f) },
                        onNotify = onNotify
                    )
                }
            }
        }
    }

    // 新建 / 重命名 / 删除对话框（统一走一个 AlertDialog，避免弹窗嵌套）。
    if (dialog.isNotEmpty()) {
        val isDelete = dialog == "delete"
        AlertDialog(
            onDismissRequest = { dialog = ""; errorText = null },
            title = {
                Text(
                    when (dialog) {
                        "file" -> "新建文件"
                        "dir" -> "新建文件夹"
                        "rename" -> "重命名"
                        else -> "删除"
                    }
                )
            },
            text = {
                Column {
                    if (isDelete) {
                        Text("确定删除「${target?.name}」？文件夹会连同里面的内容一起删除，不可恢复。")
                    } else {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            singleLine = true,
                            label = { Text(if (dialog == "rename") "新名称" else "名称") }
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "位置：" + (currentDir?.absolutePath ?: "-"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (errorText != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            errorText!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val dir = currentDir
                    when (dialog) {
                        "file", "dir" -> {
                            val name = nameInput.trim()
                            when {
                                name.isEmpty() -> errorText = "请输入名称"
                                dir == null || !dir.isDirectory -> errorText = "请先在文件树里点一个文件夹"
                                name.contains('/') || name.contains('\\') -> errorText = "名称不能包含路径分隔符"
                                File(dir, name).exists() -> errorText = "同名文件或文件夹已存在"
                                else -> {
                                    val created = File(dir, name)
                                    val ok = if (dialog == "dir") created.mkdirs()
                                    else runCatching { created.createNewFile() }.getOrDefault(false)
                                    if (ok) {
                                        treeVersion++
                                        target = created
                                        errorText = null
                                        dialog = ""
                                    } else {
                                        errorText = "创建失败，请检查存储权限"
                                    }
                                }
                            }
                        }
                        "rename" -> {
                            val t = target
                            val name = nameInput.trim()
                            when {
                                t == null -> { errorText = null; dialog = "" }
                                name.isEmpty() -> errorText = "请输入名称"
                                name.contains('/') || name.contains('\\') -> errorText = "名称不能包含路径分隔符"
                                name == t.name -> { errorText = null; dialog = "" }
                                File(t.parentFile, name).exists() -> errorText = "同名文件或文件夹已存在"
                                else -> {
                                    val renamed = File(t.parentFile, name)
                                    if (runCatching { t.renameTo(renamed) }.getOrDefault(false)) {
                                        treeVersion++
                                        target = renamed
                                        errorText = null
                                        dialog = ""
                                    } else {
                                        errorText = "重命名失败，请检查存储权限"
                                    }
                                }
                            }
                        }
                        else -> {
                            val t = target
                            if (t == null || !t.exists()) {
                                errorText = null
                                dialog = ""
                            } else {
                                val ok = runCatching { t.deleteRecursively() }.getOrDefault(false)
                                if (ok) {
                                    treeVersion++
                                    target = null
                                    errorText = null
                                    dialog = ""
                                } else {
                                    errorText = "删除失败，请检查存储权限"
                                }
                            }
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = ""; errorText = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 文件树分栏的拖拽手柄：左右拖动改变树宽度，松手时把宽度写回 workspace state。
 * 视觉上是一条贴在分栏边缘的竖线，拖拽时高亮，避免用户不知道这里能拖。
 */
@Composable
private fun TreeResizeHandle(
    onDeltaDp: (Float) -> Unit,
    onCommit: () -> Unit
) {
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(10.dp)
            .background(
                if (dragging) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else Color.Transparent
            )
            .pointerInput(density) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false; onCommit() },
                    onDragCancel = { dragging = false; onCommit() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDeltaDp(dragAmount.x / density.density)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(if (dragging) 3.dp else 1.dp)
                .fillMaxHeight(0.16f)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    if (dragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
        )
    }
}

/** 文件树工具栏用的小图标按钮（34dp，兼顾触摸目标与横排密度）。 */
@Composable
private fun MiniIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp))
    }
}

/**
 * 文件树节点：文件夹/文件类型图标 + 层级缩进引导线 + 旋转展开箭头 + 右侧对齐大小 + 当前文件/操作对象高亮。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TreeNode(
    base: File, file: File, filter: String, onOpen: (File) -> Unit,
    activePath: String?, targetPath: String?, refreshToken: Int,
    fontScale: Float, projectRoot: File?,
    onSelectDir: (File) -> Unit, onSelectFileEntry: (File) -> Unit,
    onRequestNew: (File, Boolean) -> Unit,
    onRequestRename: (File) -> Unit,
    onRequestDelete: (File) -> Unit,
    onNotify: (String) -> Unit,
    depth: Int = 0
) {
    val clipboard = LocalClipboardManager.current
    val children = remember(file.absolutePath, filter, refreshToken) {
        file.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            ?.filter { filter.isBlank() || it.name.contains(filter, true) || it.isDirectory }?.take(500).orEmpty()
    }
    val step = 14.dp * fontScale
    val guideColor = MaterialTheme.colorScheme.outlineVariant
    val dirStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = (14f * fontScale).sp)
    val fileStyle = MaterialTheme.typography.bodySmall.copy(fontSize = (12.5f * fontScale).sp)
    val metaStyle = MaterialTheme.typography.labelSmall.copy(fontSize = (10.5f * fontScale).sp)
    children.forEach { child ->
        val isDir = child.isDirectory
        var expanded by remember(child.absolutePath) { mutableStateOf(depth < 1) }
        var menuOpen by remember(child.absolutePath) { mutableStateOf(false) }
        val arrow by animateFloatAsState(if (expanded) 90f else 0f, label = "treeArrow")
        val isActive = !isDir && child.absolutePath == activePath
        val isTarget = child.absolutePath == targetPath
        val hitFilter = filter.isNotBlank() && child.name.contains(filter, true)
        val rowBg = when {
            isTarget -> MaterialTheme.colorScheme.secondaryContainer
            isActive -> MaterialTheme.colorScheme.surfaceVariant
            else -> Color.Transparent
        }
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(rowBg)
                    .combinedClickable(
                        onClick = {
                            if (isDir) {
                                expanded = !expanded
                                onSelectDir(child)
                            } else {
                                onSelectFileEntry(child)
                                onOpen(child)
                            }
                        },
                        onLongClick = {
                            // 长按：把该节点设为操作对象并弹出上下文菜单（新建就在这一层，支持任意层级）。
                            if (isDir) onSelectDir(child) else onSelectFileEntry(child)
                            menuOpen = true
                        }
                    )
                    .drawBehind {
                        val stepPx = step.toPx()
                        val origin = 11.dp.toPx()
                        for (i in 0 until depth) {
                            val x = origin + i * stepPx + stepPx / 2f
                            drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        }
                    }
                    .padding(
                        start = 8.dp + step * depth,
                        end = 8.dp,
                        top = if (isDir) 7.dp else 5.dp,
                        bottom = if (isDir) 7.dp else 5.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDir) {
                    Icon(
                        Icons.Outlined.KeyboardArrowRight,
                        contentDescription = if (expanded) "收起目录" else "展开目录",
                        modifier = Modifier.size(16.dp).rotate(arrow),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        if (expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        child.name, modifier = Modifier.weight(1f),
                        style = dirStyle,
                        fontWeight = FontWeight.Medium,
                        color = if (hitFilter) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        child.listFiles()?.size?.let { "$it" } ?: "",
                        style = metaStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Icon(
                        fileTypeIcon(child.name), contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        child.name, modifier = Modifier.weight(1f),
                        style = fileStyle,
                        fontWeight = if (isActive || isTarget) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (hitFilter) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        formatFileSize(child.length()),
                        style = metaStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Text(
                    (if (isDir) "目录 · " else "文件 · ") + child.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("新建文件") },
                    leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) },
                    onClick = {
                        menuOpen = false
                        onRequestNew(if (isDir) child else (child.parentFile ?: child), false)
                    }
                )
                DropdownMenuItem(
                    text = { Text("新建文件夹") },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                    onClick = {
                        menuOpen = false
                        onRequestNew(if (isDir) child else (child.parentFile ?: child), true)
                    }
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                    onClick = { menuOpen = false; onRequestRename(child) }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                    onClick = { menuOpen = false; onRequestDelete(child) }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("复制路径") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    onClick = {
                        menuOpen = false
                        clipboard.setText(AnnotatedString(child.absolutePath))
                        onNotify("已复制路径：${child.name}")
                    }
                )
                DropdownMenuItem(
                    text = { Text("复制相对路径") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    onClick = {
                        menuOpen = false
                        val rel = runCatching {
                            child.canonicalFile.relativeTo((projectRoot ?: child.parentFile ?: child).canonicalFile).path
                        }.getOrDefault(child.name)
                        clipboard.setText(AnnotatedString(rel))
                        onNotify("已复制相对路径：$rel")
                    }
                )
            }
        }
        if (isDir && expanded) {
            TreeNode(
                base = child, file = child, filter = filter, onOpen = onOpen,
                activePath = activePath, targetPath = targetPath, refreshToken = refreshToken,
                fontScale = fontScale, projectRoot = projectRoot,
                onSelectDir = onSelectDir, onSelectFileEntry = onSelectFileEntry,
                onRequestNew = onRequestNew, onRequestRename = onRequestRename, onRequestDelete = onRequestDelete,
                onNotify = onNotify,
                depth = depth + 1
            )
        }
    }
}

/** 按扩展名给文件配图标，比清一色纯文本更易分辨。 */
private fun fileTypeIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "cc", "h", "hpp", "go", "rs", "sh", "gradle" ->
            Icons.Outlined.Code
        "json", "yaml", "yml", "toml", "properties", "cfg", "ini" -> Icons.Outlined.DataObject
        "md", "txt", "log", "csv" -> Icons.Outlined.Description
        "xml", "html", "htm", "css" -> Icons.Outlined.Web
        "png", "jpg", "jpeg", "webp", "gif", "svg", "ico", "bmp" -> Icons.Outlined.Image
        else -> Icons.Outlined.InsertDriveFile
    }
}

/** 人类可读的文件大小（B/KB/MB）。 */
internal fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
private fun EditorPane(
    docs: List<OpenDoc>, activePath: String?, save: () -> Unit,
    update: (String) -> Unit, select: (String) -> Unit, close: (String) -> Unit,
    projectRoot: File?, extensionHost: com.nebulaforge.app.plugins.JsExtensionHost, modifier: Modifier
) {
    val active = docs.firstOrNull { it.file.absolutePath == activePath }
    var query by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    // 命令面板开关必须由本界面持有：菜单关闭时菜单项会被移出组合，
    // 状态放在菜单项内部会立刻销毁 → 表现为「点『插件命令…』毫无反应」。
    var paletteOpen by remember { mutableStateOf(false) }
    // 代码优先：查找/替换默认收起，点 🔍 或 ⋮ 才展开，避免常驻工具行挤占代码区。
    var searchOpen by remember(activePath) { mutableStateOf(false) }
    var cursorLine by remember(activePath) { mutableStateOf(0) }
    var cursorColumn by remember(activePath) { mutableStateOf(0) }
    var scrollPercent by remember(activePath) { mutableStateOf(100) }
    var syncing by remember(activePath) { mutableStateOf(false) }
    // 查找结果：交给 Sora EditorSearcher，命中处由编辑器高亮，这里只保存"第 n/m 个"。
    var soraEditor by remember(activePath) { mutableStateOf<io.github.rosemoe.sora.widget.CodeEditor?>(null) }
    var matchCount by remember(activePath) { mutableStateOf(0) }
    var matchIndex by remember(activePath) { mutableStateOf(0) }

    fun applySearch(pattern: String) {
        val searcher = soraEditor?.searcher ?: return
        if (pattern.isEmpty()) {
            searcher.stopSearch()
            matchCount = 0
            matchIndex = 0
            return
        }
        searcher.search(pattern, io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions(true, false))
    }

    fun closeSearch() {
        soraEditor?.searcher?.stopSearch()
        matchCount = 0
        matchIndex = 0
        searchOpen = false
    }

    LaunchedEffect(searchOpen, soraEditor, activePath) {
        if (searchOpen && query.isNotEmpty()) applySearch(query)
    }

    /**
     * 扩展 → 编辑器的写通道（与独立编辑器同一套实现，避免两处行为不一致）。
     *
     * 与当前 Sora 实例和当前标签页绑定：换标签或页面退出立刻摘桥，
     * 否则扩展会把编辑写到已经切走的文档上 —— 这是「看起来生效、实际丢改动」的典型来源。
     */
    LaunchedEffect(soraEditor, active?.file?.absolutePath) {
        val e = soraEditor ?: return@LaunchedEffect
        val doc = active ?: return@LaunchedEffect
        val uri = doc.file.toURI().toString()
        val languageId = com.nebulaforge.core.editor.intel.DeclarativeContributions
            .languageIdFor(doc.file.name, doc.file.extension.lowercase()).orEmpty()
        val bridge = object : com.nebulaforge.app.plugins.JsExtensionHost.EditorBridge {
            override suspend fun applyEdits(
                uri2: String,
                edits: List<com.nebulaforge.app.plugins.JsExtensionHost.SimpleEdit>
            ): Boolean {
                if (uri2 != uri) return false
                return withContext(Dispatchers.Main.immediate) {
                    runCatching {
                        val content = e.text
                        // 从后往前改：同一次 WorkspaceEdit 里的编辑坐标都相对「原文」。
                        edits.sortedWith(
                            compareByDescending<com.nebulaforge.app.plugins.JsExtensionHost.SimpleEdit> { it.startLine }
                                .thenByDescending { it.startCharacter }
                        ).forEach { edit ->
                            val lastLine = (content.lineCount - 1).coerceAtLeast(0)
                            val sl = edit.startLine.coerceIn(0, lastLine)
                            val el = edit.endLine.coerceIn(0, lastLine)
                            val sc = edit.startCharacter.coerceIn(0, content.getColumnCount(sl))
                            val ec = edit.endCharacter.coerceIn(0, content.getColumnCount(el))
                            content.replace(sl, sc, el, ec, edit.newText)
                        }
                        update(content.toString())
                        true
                    }.getOrDefault(false)
                }
            }

            override suspend fun openDocument(uri2: String): Boolean = uri2 == uri

            override suspend fun saveDocument(uri2: String): Boolean {
                if (uri2 != uri) return false
                withContext(Dispatchers.Main.immediate) { save() }
                return true
            }

            override suspend fun activeDocument(): Pair<String, String>? = uri to languageId
        }
        extensionHost.editorBridge = bridge
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            if (extensionHost.editorBridge === bridge) extensionHost.editorBridge = null
        }
    }
    // 手机上竖屏写代码：默认开启自动换行，否则长行会被水平截断，必须左右拖动才能读。
    var wordwrap by remember(activePath) { mutableStateOf(true) }
    // 开发方案 12.1：自上而下为 可滚动标签页 → 面包屑 → 工具行（查找/替换/⋮）→ 代码区 → 状态栏。
    // 代码区改用 Sora CodeEditor：行号 gutter 与代码在同一滚动容器内，天然同步，并带语法高亮。
    Column(modifier) {
        EditorFileTabRow(
            tabs = docs.map { EditorFileTabState(it.file.absolutePath, it.file.name, it.dirty) },
            activeIndex = docs.indexOfFirst { it.file.absolutePath == activePath },
            onTabSelect = { index -> docs.getOrNull(index)?.let { select(it.file.absolutePath) } },
            onTabClose = { index -> docs.getOrNull(index)?.let { close(it.file.absolutePath) } },
            dirtyMarker = "（未保存）",
            closeDescription = "关闭"
        )
        if (active == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.Code, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(10.dp))
                Text("还没有打开文件", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点左上角文件夹图标打开项目树，选择文件即可开始编辑。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }
        // 开发方案 12.1 代码优先布局：面包屑与操作图标合并为同一行，代码区独占剩余高度。
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                EditorBreadcrumbBar(
                    segments = remember(active.file.absolutePath, projectRoot) { breadcrumbSegments(active.file, projectRoot) }
                )
            }
            IconButton(onClick = { searchOpen = !searchOpen }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Search, contentDescription = "查找与替换", modifier = Modifier.size(18.dp))
            }
            IconButton(enabled = active.dirty, onClick = save, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Save, contentDescription = "保存", modifier = Modifier.size(18.dp))
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // 剪贴板四件套：Android 上「长按选词 → 系统浮层」在没有选区、尤其是**空文件**
                    // 的时候根本不弹「粘贴」，用户的现象就是「IDE 界面空白时没法复制/剪切/粘贴」。
                    // 这里给四条确定可用的入口，直接调 Sora 自带实现：
                    //   - copyText()：有选区复制选区；无选区复制「当前行」（与 VS Code 一致）
                    //   - cutText() ：有选区剪切选区；无选区剪切整行（行尾自动补换行）
                    //   - pasteText()：读系统剪贴板并插入光标处，空文档、无选区同样可用
                    //   - selectAll()：整篇全选
                    // 全部走 Sora 的编辑通道，因此照常触发 ContentChangeEvent → 未保存标记与撤销栈都同步。
                    val editorForMenu = soraEditor
                    DropdownMenuItem(text = { Text("全选") }, enabled = editorForMenu != null, onClick = {
                        menuOpen = false
                        runCatching { editorForMenu?.selectAll() }
                    })
                    DropdownMenuItem(text = { Text("复制") }, enabled = editorForMenu != null, onClick = {
                        menuOpen = false
                        runCatching { editorForMenu?.copyText() }
                    })
                    DropdownMenuItem(text = { Text("剪切") }, enabled = editorForMenu != null, onClick = {
                        menuOpen = false
                        runCatching { editorForMenu?.cutText() }
                    })
                    DropdownMenuItem(text = { Text("粘贴") }, enabled = editorForMenu != null, onClick = {
                        menuOpen = false
                        runCatching { editorForMenu?.pasteText() }
                    })
                    DropdownMenuItem(text = { Text("查找 / 替换") }, onClick = {
                        menuOpen = false
                        searchOpen = true
                        if (query.isBlank()) query = active.file.name.substringBeforeLast('.')
                    })
                    DropdownMenuItem(
                        text = { Text(if (wordwrap) "自动换行：开" else "自动换行：关") },
                        onClick = { menuOpen = false; wordwrap = !wordwrap }
                    )
                    DropdownMenuItem(text = { Text("格式化当前文件") }, onClick = {
                        menuOpen = false
                        val e = soraEditor
                        if (e != null && e.formatCodeAsync()) {
                            update(e.text.toString())
                        }
                    })
                    DropdownMenuItem(text = { Text("转到行…") }, onClick = { menuOpen = false; cursorLine = 0; cursorColumn = 0 })
                    // 插件命令面板：与独立编辑器同一个入口，命令在「工作区编辑器」里也能触发。
                    PluginCommandMenuItem(
                        onDismiss = { menuOpen = false },
                        onOpen = { paletteOpen = true }
                    )
                    DropdownMenuItem(text = { Text("关闭当前文件") }, onClick = { menuOpen = false; close(active.file.absolutePath) })
                }
            }
        }
        if (paletteOpen) PluginCommandPaletteDialog(onDismiss = { paletteOpen = false })
        if (searchOpen) {
            // 查找 / 替换走 Sora EditorSearcher：命中处直接在代码区高亮，并显示"第 n/m 个"。
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        query,
                        { value -> query = value; applySearch(value) },
                        Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("查找") }
                    )
                    Text(
                        if (query.isEmpty()) "0/0" else "${if (matchCount > 0) matchIndex + 1 else 0}/$matchCount",
                        style = MaterialTheme.typography.labelMedium
                    )
                    IconButton(onClick = { closeSearch() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭查找")
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(replace, { replace = it }, Modifier.weight(1f), singleLine = true, label = { Text("替换为") })
                    TextButton(onClick = { soraEditor?.searcher?.gotoPrevious() }) { Text("上一个") }
                    TextButton(onClick = { soraEditor?.searcher?.gotoNext() }) { Text("下一个") }
                    TextButton(onClick = { if (query.isNotEmpty()) soraEditor?.searcher?.replaceAll(replace) { } }) { Text("全部替换") }
                }
            }
        }
        if (active.recoveredDirty) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("此标签页上次退出时有未保存修改；IDE 只持久化了元数据，当前内容已从磁盘恢复。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { close(active.file.absolutePath) }) { Text("关闭") }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).heightIn(min = 220.dp)) {
            key(active.file.absolutePath) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        val frame = android.widget.FrameLayout(context)
                        // 排版按"手机竖屏写代码"调优：等宽字体 + 舒适行距 + 4 空格制表 + 行号随行号宽度自适应。
                        // 字号用 sp 换算成 px，避免固定 px 在高 DPI 屏上过小。
                        val metrics = context.resources.displayMetrics
                        val codeSp = 14.5f
                        val editor = io.github.rosemoe.sora.widget.CodeEditor(context).apply {
                            setTypefaceText(android.graphics.Typeface.MONOSPACE)
                            setTypefaceLineNumber(android.graphics.Typeface.MONOSPACE)
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
                            isWordwrap = wordwrap
                            setText(active.text)
                            // java 用官方语言包（有高亮）；其余文件走 AutoIndentLanguage（自动缩进 + 括号配对）。
                            setEditorLanguage(com.nebulaforge.app.editor.editorLanguageFor(active.file))
                            // 本地补全：输入标识符字符或 "." 时自动弹出候选窗口。
                            runCatching {
                                getComponent(io.github.rosemoe.sora.widget.component.EditorAutoCompletion::class.java)?.setEnabled(true)
                            }
                            subscribeEvent(io.github.rosemoe.sora.event.PublishSearchResultEvent::class.java) { _, _ ->
                                // 只把事件当触发器：命中数从编辑器自身的 searcher 读取。
                                matchCount = searcher.matchedPositionCount
                                matchIndex = searcher.currentMatchedPositionIndex
                            }
                            soraEditor = this
                            subscribeEvent(io.github.rosemoe.sora.event.SelectionChangeEvent::class.java) { ev, _ ->
                                cursorLine = ev.left.line
                                cursorColumn = ev.left.column
                            }
                            subscribeEvent(io.github.rosemoe.sora.event.ScrollEvent::class.java) { _, _ ->
                                val maxY = getScrollMaxY()
                                scrollPercent = if (maxY > 0) ((scrollY.toFloat() / maxY) * 100f).toInt().coerceIn(0, 100) else 100
                            }
                            subscribeEvent(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) { _, _ ->
                                if (!syncing) update(text.toString())
                            }
                        }
                        frame.addView(
                            editor,
                            android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        )
                        frame
                    },
                    update = { frame ->
                        val editor = frame.getChildAt(0) as? io.github.rosemoe.sora.widget.CodeEditor ?: return@AndroidView
                        if (editor.isWordwrap != wordwrap) editor.isWordwrap = wordwrap
                        if (editor.text.toString() != active.text) {
                            syncing = true
                            editor.setText(active.text)
                            syncing = false
                        }
                    }
                )
            }
        }
        EditorStatusBar(
            line = cursorLine + 1,
            column = cursorColumn + 1,
            encoding = "UTF-8",
            lineEnding = if (active.text.contains("\r\n")) "CRLF" else "LF",
            languageMode = languageLabel(active.file),
            gitBranch = null,
            lineColumnTemplate = "Ln %d, Col %d",
            scrollPercent = scrollPercent,
            extraRight = if (wordwrap) "自动换行" else "不换行"
        )
    }
}

/** 面包屑分段：项目名 → 各级目录 → 文件名。 */
private fun breadcrumbSegments(file: File, projectRoot: File?): List<String> {
    val root = projectRoot ?: return listOf(file.name)
    return runCatching {
        val rel = file.canonicalFile.relativeTo(root.canonicalFile).path
        listOf(root.name) + rel.split(File.separatorChar).filter { it.isNotBlank() }
    }.getOrDefault(listOf(file.name))
}

/** 状态栏展示的语言模式。 */
private fun languageLabel(file: File): String = when (file.extension.lowercase()) {
    "kt", "kts" -> "Kotlin"
    "java" -> "Java"
    "py" -> "Python"
    "js" -> "JavaScript"
    "ts" -> "TypeScript"
    "dart" -> "Dart"
    "json" -> "JSON"
    "xml" -> "XML"
    "md" -> "Markdown"
    "yaml", "yml" -> "YAML"
    "gradle" -> "Gradle"
    "c", "h" -> "C"
    "cpp", "cc", "hpp" -> "C++"
    else -> file.extension.uppercase().ifBlank { "Plain Text" }
}

/**
 * 「打开文件夹」路径浏览器。
 *
 * 就地打开：只登记路径，不动磁盘文件 —— 下载目录、SD 卡、其它位置的工程都能直接进 IDE。
 * 列目录放在 IO 线程（/sdcard 下动辄上千条目，主线程列目录会卡住弹层首帧）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FolderBrowserSheet(
    context: android.content.Context,
    onDismiss: () -> Unit,
    onOpen: (File) -> Unit,
    onRemoveExtra: (String) -> Unit
) {
    val shortcuts = remember { browserShortcuts(context) }
    var current by remember {
        mutableStateOf(shortcuts.firstOrNull() ?: File("/storage/emulated/0"))
    }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var extras by remember { mutableStateOf(readExtraRoots(context)) }

    LaunchedEffect(current) {
        loading = true
        entries = withContext(Dispatchers.IO) { listSubDirectories(current) }
        loading = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 18.dp)) {
            Text("打开文件夹", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "就地打开设备上任意目录作为项目：文件保留在原位置，不做复制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = current.parentFile != null && current.parentFile != current,
                    onClick = { current.parentFile?.let { current = it } }
                ) { Text("↑ 上级") }
                Text(
                    current.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            FlowRow(Modifier.fillMaxWidth()) {
                shortcuts.forEach { s ->
                    val label = if (s.absolutePath == "/storage/emulated/0") "内部存储"
                    else s.name.ifBlank { s.absolutePath }
                    AssistChip(onClick = { current = s }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(6.dp))
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在读取目录…", style = MaterialTheme.typography.bodySmall)
                }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                if (entries.isEmpty() && !loading) {
                    item {
                        Text(
                            "该目录下没有可进入的子文件夹。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(entries) { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { current = d }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Folder, null, Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            d.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onOpen(current) }, modifier = Modifier.fillMaxWidth()) {
                Text("打开「${current.name.ifBlank { current.absolutePath }}」")
            }
            if (extras.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "已就地打开（移除只取消登记，磁盘文件不受影响）",
                    style = MaterialTheme.typography.labelMedium
                )
                Column(Modifier.fillMaxWidth()) {
                    extras.forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                e.absolutePath,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                onRemoveExtra(e.absolutePath)
                                extras = readExtraRoots(context)
                            }) { Text("移除") }
                        }
                    }
                }
            }
        }
    }
}

private fun listProjects(context: android.content.Context): List<File> {
    val dir = File(Environment.projectsDir(context))
    if (!dir.isDirectory) dir.mkdirs()
    val managed = dir.listFiles()
        ?.filter { it.isDirectory }
        .orEmpty()
    // 就地打开过的外部目录与受管项目并列：仓库留在原地，不必先复制进 NebulaForgeProjects。
    val extra = readExtraRoots(context).filter { it.absolutePath != dir.absolutePath }
    return (managed + extra)
        .distinctBy { it.absolutePath }
        .sortedBy { it.name.lowercase() }
}

// ---------------------------------------------------------------- 就地打开的目录（外部项目）

private const val EXTRA_ROOTS_PREF = "nebula_forge_workspace_extra_roots"
private const val EXTRA_ROOTS_KEY = "paths"

/**
 * 「就地打开」的外部项目目录列表。
 *
 * 只登记**路径**，不动用户文件：设备上任意位置（下载目录、SD 卡、其它 App 的公共目录）
 * 的工程都能直接作为项目打开。已经不存在的路径自动忽略。
 */
private fun readExtraRoots(context: android.content.Context): List<File> {
    val prefs = context.getSharedPreferences(EXTRA_ROOTS_PREF, android.content.Context.MODE_PRIVATE)
    val managed = File(Environment.projectsDir(context)).absolutePath.trimEnd('/')
    val all = prefs.getStringSet(EXTRA_ROOTS_KEY, emptySet()).orEmpty()
        .map(::File)
        .filter { it.isDirectory }
        .distinctBy { it.absolutePath }
    // 自愈：剔掉「其实已经是某个项目内部目录」的登记项（历史版本误登记的 app/、
    // app/src/main/res/values/、.dart_tool/、build/reports/problems/ …），并把清洗结果写回偏好 ——
    // 否则这些假项目会一直挂在列表里，看起来就像「每次打开都自动新建了一个项目」。
    val kept = all.filter { f ->
        val p = f.absolutePath.trimEnd('/')
        if (p == managed) return@filter false
        if (isInside(p, managed)) return@filter false
        all.none { o -> o.absolutePath.trimEnd('/') != p && isInside(p, o.absolutePath.trimEnd('/')) }
    }
    if (kept.size != all.size) {
        runCatching {
            prefs.edit().putStringSet(EXTRA_ROOTS_KEY, HashSet(kept.map { it.absolutePath })).commit()
        }
    }
    return kept.sortedBy { it.name.lowercase() }
}

/** path 是否位于 root 目录**之内**（更深的层级）；两端都是绝对路径，末尾斜杠不影响结果。 */
private fun isInside(path: String, root: String): Boolean {
    val p = path.trimEnd('/')
    val r = root.trimEnd('/')
    return p.length > r.length && p.startsWith("$r/")
}

/** `dir` 归属于哪个项目根（受管项目 / 已登记的外部根）；null 表示它本身就是一个新的外部项目。 */
private fun projectRootFor(context: android.content.Context, dir: File): File? {
    val managed = File(Environment.projectsDir(context))
    val mp = managed.absolutePath.trimEnd('/')
    val dp = dir.absolutePath.trimEnd('/')
    if (dp == mp || isInside(dp, mp)) {
        val top = dp.removePrefix("$mp/").substringBefore('/')
        return File(managed, top).takeIf { it.isDirectory } ?: managed
    }
    return readExtraRoots(context).firstOrNull { isInside(dp, it.absolutePath.trimEnd('/')) }
}

/**
 * 登记「就地打开」的外部项目根。返回 false = 该目录属于现有项目，**不**新建项目条目。
 *
 * 用户报「每次打开项目工作区都会自动新建一个项目」的根因就在这里：项目面板的「打开文件夹」
 * 常被用来在已有项目里跳目录，旧实现把**任意**选中目录都登记为外部根，而项目列表 =
 * 受管项目 + 全部外部根，于是在项目里点几下，列表里就凭空多出几个「项目」。
 */
private fun addExtraRoot(context: android.content.Context, dir: File): Boolean {
    // 已经是受管项目内部目录、或落在别的外部根里：不重复登记（列表里本来就有它所属的项目）。
    if (projectRootFor(context, dir) != null) return false
    val prefs = context.getSharedPreferences(EXTRA_ROOTS_PREF, android.content.Context.MODE_PRIVATE)
    val next = HashSet(prefs.getStringSet(EXTRA_ROOTS_KEY, emptySet()).orEmpty()).apply {
        add(dir.absolutePath)
    }
    runCatching { prefs.edit().putStringSet(EXTRA_ROOTS_KEY, next).commit() }
    return true
}

private fun removeExtraRoot(context: android.content.Context, path: String) {
    val prefs = context.getSharedPreferences(EXTRA_ROOTS_PREF, android.content.Context.MODE_PRIVATE)
    val next = HashSet(prefs.getStringSet(EXTRA_ROOTS_KEY, emptySet()).orEmpty()).apply { remove(path) }
    runCatching { prefs.edit().putStringSet(EXTRA_ROOTS_KEY, next).commit() }
}

private fun listSubDirectories(dir: File): List<File> = runCatching {
    dir.listFiles()?.filter { it.isDirectory && it.canRead() }?.sortedBy { it.name.lowercase() }
}.getOrNull().orEmpty()

/** 路径浏览器里的快捷入口（从最常见的存放位置出发，省得一层层点）。 */
private fun browserShortcuts(context: android.content.Context): List<File> = listOf(
    File("/storage/emulated/0"),
    Environment.publicProjectsRoot(),
    File(Environment.projectsDir(context)),
    File(Environment.homeRoot(context))
).distinctBy { it.absolutePath }.filter { it.isDirectory }

private fun safeName(raw: String): String {
    val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_', '.')
    return cleaned.ifBlank { "imported-project" }
}

/** 工具窗口抽屉里的图标映射（未知 id 退回通用图标）。 */
private fun toolWindowIcon(id: String) = when (id) {
    "terminal" -> Icons.Outlined.Terminal
    "build_output" -> Icons.Outlined.Build
    "problems" -> Icons.Outlined.Warning
    BuiltInToolWindows.RUN -> Icons.Outlined.PlayArrow
    BuiltInToolWindows.STRUCTURE -> Icons.Outlined.AccountTree
    BuiltInToolWindows.LOGCAT -> Icons.Outlined.ListAlt
    BuiltInToolWindows.VERSION_CONTROL -> Icons.Outlined.History
    BuiltInToolWindows.MCP_LOG -> Icons.Outlined.Dns
    BuiltInToolWindows.GRADLE -> Icons.Outlined.Architecture
    BuiltInToolWindows.NOTIFICATIONS -> Icons.Outlined.Notifications
    else -> Icons.Outlined.Widgets
}

/** 允许被"自动打开"的源码后缀。 */
private val ENTRY_EXTENSIONS = setOf(
    "kt", "java", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "cpp", "h", "hpp",
    "xml", "gradle", "kts", "md", "json", "yaml", "yml", "sh", "toml", "properties"
)

/** 目录名黑名单：这些目录通常巨大且不是人想先看的入口。 */
private val ENTRY_SKIP_DIRS = setOf("build", "node_modules", ".gradle", ".git", "out", "dist", ".idea")

/**
 * 选择项目里最有代表性的源文件作为"自动打开"目标：
 * 主入口（MainActivity / App / main / index）> Android/Kotlin 源文件 > 任意源码。
 */
private fun pickEntryFile(root: File): File? {
    if (!root.isDirectory) return null
    val priorityNames = setOf(
        "MainActivity.kt", "MainActivity.java", "App.kt", "App.js", "App.tsx",
        "main.py", "main.kt", "index.js", "index.ts", "index.tsx", "README.md"
    )
    val candidates = root.walkTopDown()
        .onEnter { dir -> !dir.name.startsWith(".") && dir.name !in ENTRY_SKIP_DIRS }
        .maxDepth(8)
        .filter { it.isFile && it.length() <= 4_000_000 && it.extension.lowercase() in ENTRY_EXTENSIONS }
        .take(1500)
        .toList()
    return candidates.firstOrNull { it.name in priorityNames }
        ?: candidates.firstOrNull { it.extension.lowercase() in setOf("kt", "kts", "java", "py", "js", "ts", "go", "rs", "c", "cpp") }
        ?: candidates.firstOrNull()
}

/** 导入来源选择行：图标 + 标题 + 说明，点击即拉起对应的系统文件选择器。 */
@Composable
private fun ImportActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Upload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
