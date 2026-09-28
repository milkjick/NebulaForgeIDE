package com.nebulaforge.app.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.app.terminal.IdeTerminalPanel
import com.nebulaforge.core.projectmodel.TaskDefinition
import com.nebulaforge.core.projectmodel.TasksJson
import com.nebulaforge.core.session.IdeEvent
import com.nebulaforge.core.session.SessionKind
import com.nebulaforge.core.session.SessionState
import com.nebulaforge.core.session.Severity
import com.nebulaforge.core.terminal.TerminalSessionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 终端面板配色（VSCode 深色终端风格）。 */
internal val TermBg = Color(0xFF1B1B1B)
internal val TermBar = Color(0xFF252526)
private val TermFg = Color(0xFFD4D4D4)
internal val TermDim = Color(0xFF8C8C8C)
private val TermOk = Color(0xFF89D185)
internal val TermErr = Color(0xFFF48771)
internal val TermAccent = Color(0xFF569CD6)
private val TermWarn = Color(0xFFDCDCAA)

/**
 * 构建页：点击「构建」→ 任务在 **pty 终端**里执行，日志实时滚动，问题同步进「问题」面板。
 *
 * ## 与 VSCode 的对齐
 * - 任务来自 `.vscode/tasks.json`（没有则由 [TasksJson] 生成内置默认任务，面板永远可点）；
 * - 执行发生在真实终端会话里，所以 ANSI 颜色、\r 进度、交互式提示（Gradle 输入、sdkmanager 许可）
 *   都能正常工作，也能在「终端」工具窗里接管同一会话继续敲命令；
 * - 停止 = Ctrl-C（SIGINT 到前台进程组），失败/成功状态进 [com.nebulaforge.core.session.IdeSessionBus]，
 *   与运行、日志、诊断共用同一事件源。
 *
 * 状态保存在 Application 级 [WorkspaceTaskRunner] 上，切工具窗 tab 不中断构建。
 *
 * ## 四个标签页（对齐参考设计图）
 * | 标签 | 数据源 | 说明 |
 * |---|---|---|
 * | 输出 | pty 终端 | 构建的真实 stdout/stderr（ANSI 还原） |
 * | 问题 | [com.nebulaforge.core.session.DiagnosticStore] | 本次构建匹配出的错误/警告，可点击跳转 |
 * | 应用日志 | `RunCenterStore` 的 LOGCAT 会话 | 被构建应用在设备上的 logcat |
 * | IDE日志 | `IdeSessionBus.events` | IDE 自身的会话生命周期/工具链/诊断事件 |
 *
 * 之前这四个信息散落在四个互不相通的页面里，用户必须自己拼凑「为什么构建失败」；
 * 现在合并到一个工具窗，构建失败时不必跳页。
 */
@Composable
fun BuildCenterScreen(
    projectRoot: String? = null,
    onOpenFile: (String) -> Unit = {},
    onOpenRun: () -> Unit = {},
    onOpenProblems: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    // 输出属于「跑这次构建的工程」，可能不是当前打开的工程；给一个一键切回去的入口。
    onOpenProject: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as NebulaForgeApplication
    val runner = app.workspaceTasks
    val runState by runner.state.collectAsState()
    val workspaceSnapshot by app.workspaceState.state.collectAsState()
    val diagnosticsVersion by app.diagnosticStore.version.collectAsState()
    val runRecords by app.runCenter.records.collectAsState()

    val root = remember(projectRoot) {
        projectRoot?.let(::File)?.takeIf { it.isDirectory }
            ?: app.currentProjectPath()?.let(::File)?.takeIf { it.isDirectory }
    }
    val manager = remember(context) { TerminalSessionManager.get(context) }

    // 当前**活动文件**（编辑器里正打开的那一个）。构建目标必须跟随它：
    // 真机反馈「我明明选中的是 greeter.py，编译的却是 main.py」——默认任务的命令以前只按
    // 「工程里有没有 main.py/app.py」推断，与当前文件无关。这里把活动文件一路传进
    // TasksJson.load → defaultTasks → LanguageCommands，命令里的入口脚本即为当前文件。
    val activeFile = workspaceSnapshot.activeFile?.let(::File)?.takeIf { it.isFile }

    // tasks.json 在「项目变化 / 当前文件变化 / 每次运行结束」时重读：用户改完 tasks.json 不必重启应用，
    // 切换文件后任务列表与命令也立刻跟上。
    val tasks = remember(root, runState.running, runState.startedAt, activeFile) {
        root?.let { runCatching { TasksJson.load(it, activeFile) }.getOrDefault(emptyList()) }.orEmpty()
    }
    // 选中的任务**按工程持久化**（workspace/state.json → buildTaskByProject）。
    // 以前是 `remember(root)`：切换文件导致面板重建、或切到别的工程再切回来，选择就没了，
    // 面板会退回「第一个 build 任务」——用户看到的就是「无法自由选择要编译什么」。
    val rememberedTaskId = root?.absolutePath?.let { workspaceSnapshot.buildTaskByProject[it] }
    var selectedTaskId by remember(root, rememberedTaskId) { mutableStateOf(rememberedTaskId) }
    val rememberTask: (String) -> Unit = { id ->
        selectedTaskId = id
        root?.absolutePath?.let { app.workspaceState.updateBuildTask(it, id) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    val selectedTask: TaskDefinition? = tasks.firstOrNull { it.id == selectedTaskId }
        ?: tasks.firstOrNull { it.isBuild }
        ?: tasks.firstOrNull()

    // 注意：Termux 的 TerminalView.setTextSize/setBackgroundColor 走的是 Android View 的
    // Int/Float 版本（px 与 ARGB int），不是 Compose 的 sp/Color —— 类型必须对齐，
    // 否则 ptysize 被反复 resize（也编译不过）。
    //
    // 字号改存工作区状态：以前是面板内的 remember，切页/重启就丢，而且「终端」页自己还有一份，
    // 两边会互相覆盖。现在两个面板共用同一份持久化设置（terminalFontSize），
    // 并且这里把它同步应用到四个标签页（输出/问题/应用日志/IDE日志）。
    val fontSize = workspaceSnapshot.terminalFontSize
    val setFontSize: (Int) -> Unit = { app.workspaceState.updateTerminalFont(it) }
    // 构建面板同样订阅 runner 暴露的 pty 会话 id（见 WorkspaceTaskRunner.activePty）：
    // 「构建」按钮以前靠 run() 返回后手读 id 侥幸能用，而「运行」（RunToolWindowScreen）
    // 没有这一步，于是运行面板永远挂不上会话。统一订阅后两条路径行为一致。
    val ptyId by runner.activePty.collectAsState()
    // 输出区渲染 runner 文本（与 pty 解耦）：终端链路不出内容时也保证输出可见。
    val allOutput by runner.output.collectAsState()
    var tab by remember { mutableStateOf(BuildTab.OUTPUT) }

    // ---- 「构建方式」弹窗（用户需求：点构建先弹窗，自由选择编什么/怎么编）----
    // 只对 Gradle/AGP 工程弹：其它工程没有变体与模块概念，弹窗里的选项全是无效噪音。
    val isGradleProject = remember(root) { root?.let { AndroidBuildPlan.isGradleProject(it) } == true }
    val planStore = remember(context) { BuildPlanStore(context) }
    var buildPlan by remember(root) { mutableStateOf(root?.let { planStore.load(it) } ?: AndroidBuildPlan()) }
    var planDialogOpen by remember { mutableStateOf(false) }

    // 「安装到本机」弹窗的目标 APK：由产物区 chip 旁的「安装」按钮写入（见下方产物区）。
    // 「安装到本机」统一走 App 级弹窗宿主（InstallPromptHost）：
    // 面板只负责把「有新产物等着安装」写进 InstallPromptStore 的持久化待办，
    // 不再自己渲染弹窗 —— 否则弹窗会同时依赖「底部工具窗活着 + 进程没被杀」两件不可控的事。
    val requestInstall: (File) -> Unit = { apk ->
        runCatching { InstallPromptStore(context).offer(apk, root?.absolutePath, runState.sessionId) }
    }

    // 面板自己再定位一次本次产物：不依赖「事件总线有没有把 Artifact 落进工作区」，双保险。
    // 用户报的「编译完成无法选择自动安装」，本质就是入口在真机上没出现 —— 这里必须自己兜住。
    // 注意 key 不能用 justFinished：那个字段会被 acknowledgeFinish() 立刻清掉，
    // LaunchedEffect 随之取消，IO 还没跑完就被打断（这正是「看起来没生效」的经典坑）。
    var detectedApk by remember { mutableStateOf<File?>(null) }
    val installScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(runState.sessionId, runState.exitCode) {
        if (runState.running || runState.exitCode == null || runState.success != true) return@LaunchedEffect
        val dir = runState.projectRoot.takeIf { it.isNotBlank() }?.let(::File) ?: root
        if (dir == null) return@LaunchedEffect
        val since = runState.startedAt - 5_000L
        // 快路径优先：只看产物标准输出目录（build/app/outputs…），毫秒级返回。
        // 以前这里直接做**全树递归扫描**（/storage 是 FUSE，逐级读元数据很慢），
        // 用户看到的就是「构建已经成功，安装弹窗却要等好几秒」——这是弹窗延迟的根因。
        val quick = withContext(Dispatchers.IO) {
            runCatching { BuildArtifactLocator.quickApk(context, dir, since) }.getOrNull()
        }
        // 快路径没命中（自定义模块名/输出目录）才补一次全树扫描，保证「非常规工程也找得到」。
        val found = quick ?: withContext(Dispatchers.IO) {
            runCatching { BuildArtifactLocator.newestApk(context, dir, since) }.getOrNull()
        }
        if (found != null) {
            detectedApk = found
            app.workspaceState.recordArtifact(found.absolutePath)
            // 弹窗本身**只由 App 级 InstallPromptHost 渲染一次**（见 InstallPromptStore.kt）：
            // 面板这里曾经自己 `installTarget = found` 弹窗，于是弹窗的存活被绑死在「底部构建工具窗
            // 是否组出来 + 进程是否还活着」上 —— 真机上 Flutter 构建结束 24s 后进程被系统回收，
            // 弹窗再也没出现过。现在统一写进持久化待办，面板、切页、重启后都能拿到同一个入口。
            runCatching {
                InstallPromptStore(context).offer(
                    apk = found,
                    projectRoot = dir.absolutePath,
                    sessionId = runState.sessionId
                )
            }
        }
    }


    // 构建中每 500ms 走一格，用于「已运行 xx 秒」。
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(runState.running) {
        while (runState.running) {
            tick = System.currentTimeMillis()
            delay(500)
        }
    }
    LaunchedEffect(runState.justFinished) {
        if (runState.justFinished) runner.acknowledgeFinish()
    }

    val elapsedMs = when {
        runState.startedAt == 0L -> 0L
        runState.running -> (if (tick > 0) tick else System.currentTimeMillis()) - runState.startedAt
        else -> runState.durationMs
    }

    // ---- 「问题」标签：优先展示本次构建会话的诊断（新构建不继承旧诊断），没有会话时展示全部。
    val problems = remember(diagnosticsVersion, runState.sessionId) {
        runState.sessionId
            ?.let { app.diagnosticStore.forSession(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: app.diagnosticStore.all()
    }

    // ---- 「应用日志」标签：最近一次 logcat 会话的输出（被构建应用的设备日志）。
    val appLogLines = remember(runRecords) {
        runRecords.asSequence()
            .filter { it.kind == SessionKind.LOGCAT || it.id.endsWith(":logcat") }
            .maxByOrNull { it.updatedAt }
            ?.output
            .orEmpty()
    }

    // ---- 「IDE日志」标签：IDE 自身的会话事件（状态/诊断/产物/工具链），刻意不含终端 stdout，
    //      否则会被构建输出淹没。SharedFlow 带 replay=32，首次订阅即可看到最近历史。
    val ideLog = remember { mutableStateListOf<String>() }
    val ideTime = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    LaunchedEffect(Unit) {
        app.sessionBus.events.collect { event ->
            describeIdeEvent(event)?.let { text ->
                ideLog.add("${ideTime.format(Date(event.timeMs))}  $text")
                if (ideLog.size > MAX_IDE_LOG_LINES) ideLog.removeAt(0)
            }
        }
    }

    // 「构建方式」弹窗：确认后把选择按工程持久化，然后把拼好的命令交给同一个 runner
    // （与任务清单共用 pty 会话、退出标记、问题匹配，所以「输出/问题」两个标签页照旧工作）。
    if (planDialogOpen && root != null) {
        val dir = root
        AndroidBuildDialog(
            projectName = dir.name,
            projectRoot = dir,
            initial = buildPlan,
            selectedTaskLabel = selectedTask?.label,
            onDismiss = { planDialogOpen = false },
            onStart = { picked ->
                buildPlan = picked
                planStore.save(dir, picked)
                planDialogOpen = false
                tab = BuildTab.OUTPUT
                runner.runCommand(
                    root = dir,
                    label = "Gradle: " + picked.label(),
                    commandLine = picked.commandLine(dir),
                    kind = SessionKind.BUILD,
                    matchers = listOf("\$nebula-gradle")
                )
            },
            onRunSelectedTask = {
                planDialogOpen = false
                selectedTask?.let { t -> tab = BuildTab.OUTPUT; runner.run(dir, t) }
            }
        )
    }

    Column(Modifier.fillMaxSize().background(TermBg)) {

        // ------------------------------------------------------------ 工具条
        // 真机反馈「输出字号调不了」的根因就在这里：任务名 chip 没有宽度上限，长任务名
        // （如「Python: 语法编译检查（含单元测试）」）会把后面的 清空/运行/−/+ 全部挤出屏幕，
        // 而这些按钮本身不可滚动。现在 chip 限宽 + 工具条可横向滚动，字号入口永远能点到；
        // 同时在底部状态栏再放一套字号控件（那里一定在屏幕内）。
        // 工具条分两段：左段**可横向滚动**（任务区，长任务名不会把别的控件挤没），
        // 右段是**固定**的字号控件。真机反馈「构建/运行时输出文本调不了字号」的根因就在这：
        // 原来字号挂在可滚动段的最右侧（还隔着 Spacer(weight)），窄屏上要么被裁掉、
        // 要么得先把工具条横向拖到尽头才够得着。现在它不参与滚动，任何屏宽都一定可见。
        Row(
            modifier = Modifier.fillMaxWidth().background(TermBar).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            Box {
                AssistChip(
                    onClick = { menuOpen = true },
                    modifier = Modifier.widthIn(max = 190.dp),
                    label = {
                        Text(
                            selectedTask?.label ?: "无任务",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp
                        )
                    },
                    enabled = tasks.isNotEmpty()
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    tasks.forEach { t ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(t.label, fontSize = 13.sp)
                                    t.detail?.let { Text(it, fontSize = 11.sp, color = TermDim, maxLines = 1) }
                                }
                            },
                            onClick = { rememberTask(t.id); menuOpen = false }
                        )
                    }
                }
            }

            if (runState.running) {
                Button(
                    onClick = { runner.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = TermErr),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("■ 停止", fontSize = 13.sp) }
            } else {
                Button(
                    onClick = {
                        val dir = root ?: return@Button
                        tab = BuildTab.OUTPUT
                        if (isGradleProject) {
                            // AGP 工程：先让用户选「编什么/怎么编」（弹窗里也能一键改用所选任务）。
                            planDialogOpen = true
                        } else {
                            val task = selectedTask ?: return@Button
                            runner.run(dir, task)
                        }
                    },
                    enabled = selectedTask != null && root != null,
                    colors = ButtonDefaults.buttonColors(containerColor = TermAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text("▶ 构建", fontSize = 13.sp) }
            }

            TextButton(onClick = { runner.clearOutput() }, enabled = !runState.running) {
                Text("清空", fontSize = 13.sp, color = TermDim)
            }
            // 一键复制全部构建输出。局部选择直接长按输出区（TaskOutputList 已包 SelectionContainer）。
            TextButton(onClick = {
                val text = allOutput.joinToString("\n")
                val ok = com.nebulaforge.core.terminal.TerminalSessionManager.copyToSystemClipboard(text)
                android.widget.Toast.makeText(
                    context,
                    if (ok) "已复制全部输出（${text.length} 字符）" else "没有可复制的输出",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }) { Text("复制", fontSize = 13.sp, color = TermDim) }
            // 「装到本机」常驻入口：不依赖本次构建是否识别到产物，按需在工程里找最新的 APK。
            // 用户需求是「编译出 apk 后可以选择直接安装到系统」，所以这个入口必须始终可达。
            TextButton(
                enabled = root != null && !runState.running,
                onClick = {
                    val dir = root ?: return@TextButton
                    val known = (detectedApk ?: workspaceSnapshot.lastArtifact?.let(::File))
                        ?.takeIf { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
                    if (known != null) {
                        requestInstall(known)
                        return@TextButton
                    }
                    installScope.launch {
                        val found = withContext(Dispatchers.IO) {
                            // 先快路径（只看产物输出目录），没有再退回全树扫描：点一下就能弹窗。
                            runCatching { BuildArtifactLocator.quickApk(context, dir, 0L) }.getOrNull()
                                ?: runCatching { BuildArtifactLocator.newestApk(context, dir, 0L) }.getOrNull()
                        }
                        if (found != null) {
                            detectedApk = found
                            requestInstall(found)
                        } else {
                            android.widget.Toast.makeText(
                                context, "这个工程里还没找到 APK 产物，先编译一次", android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            ) { Text("安装", fontSize = 13.sp, color = TermOk) }
            TextButton(onClick = onOpenRun) { Text("运行", fontSize = 13.sp, color = TermDim) }
            // 构建输出同时存在 pty 会话里，用户可以在真正的「终端」里接管继续敲命令
            // （VSCode 的任务终端就是这个语义）。
            TextButton(onClick = onOpenTerminal) { Text("在终端打开", fontSize = 13.sp, color = TermDim) }

                // 「测试包 / 全量包」二选一：Android=assembleDebug/assembleRelease，
                // Flutter=build apk --debug/--release。同一份工程一键切换要出的包。
                BuildVariantSelector(tasks, selectedTask?.id) { rememberTask(it) }
            }

            // 字号（固定段，不参与横向滚动）。值写回工作区持久化设置，四个标签页共用同一份。
            Text("字号", fontSize = 11.sp, color = TermDim)
            Text(
                text = "A−", fontSize = 13.sp, color = TermDim,
                modifier = Modifier.clickable { setFontSize(fontSize - 1) }.padding(horizontal = 6.dp, vertical = 4.dp)
            )
            Text("$fontSize", fontSize = 12.sp, color = TermFg, fontFamily = FontFamily.Monospace)
            Text(
                text = "A+", fontSize = 13.sp, color = TermDim,
                modifier = Modifier.clickable { setFontSize(fontSize + 1) }.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }

        // ------------------------------------------------------------ 状态横幅（参考设计图）
        // 构建中显示 "SYNCING PROJECT / Running build, please wait..."，空闲时回落到任务名与结论。
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF1F1F1F)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        runState.running -> "SYNCING PROJECT"
                        runState.success == false -> "BUILD FAILED"
                        runState.startedAt == 0L -> "BUILD CENTER"
                        else -> "BUILD FINISHED"
                    },
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        runState.running -> TermAccent
                        runState.success == false -> TermErr
                        runState.startedAt == 0L -> TermDim
                        else -> TermOk
                    }
                )
                Text(
                    text = when {
                        runState.running -> "Running build, please wait..."
                        else -> buildString {
                            append(runState.message)
                            // 归属工程优先取「跑这次构建的工程」：构建输出属于工程而不是文件，
                            // 之前一律显示当前工程名，切文件后横幅与下方输出会自相矛盾。
                            val owner = runState.projectRoot.takeIf { it.isNotBlank() }?.let { File(it).name } ?: root?.name
                            owner?.let { append(" · ") ; append(it) }
                            if (elapsedMs > 0) append(" · ").append(if (elapsedMs >= 1000) "${elapsedMs / 1000}s" else "${elapsedMs}ms")
                            runState.exitCode?.let { if (!runState.running) append(" · exit $it") }
                        }
                    },
                    fontSize = 12.sp,
                    color = if (runState.running) TermWarn else TermDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 最近产物（由构建脚本 emit 的 Artifact 事件落盘）：点一下把路径交回上层打开。
            val artifact = workspaceSnapshot.lastArtifact
            if (!artifact.isNullOrBlank()) {
                Text(
                    text = artifact.substringAfterLast('/'),
                    fontSize = 11.sp,
                    color = TermOk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(enabled = File(artifact).isFile) { onOpenFile(artifact) }
                        .padding(start = 8.dp)
                )
                // 构建成功后的安装入口（用户需求：编出 APK 就能直接装进系统）。
                // 只在产物确实是 APK 且文件还在时才出现 —— .aab / 日志 / 已被清理的路径都不该给「安装」。
                val apkArtifact = File(artifact)
                if (apkArtifact.isFile && artifact.endsWith(".apk", ignoreCase = true)) {
                    TextButton(onClick = { requestInstall(apkArtifact) }) {
                        Text("安装", fontSize = 12.sp, color = TermOk)
                    }
                }
            }
        }
        if (runState.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = TermAccent, trackColor = TermBar)
        }

        // ------------------------------------------------------------ 产物横幅
        // 构建成功且定位到 APK 时出现：把「装到本机」直接摆在构建结果旁边 —— 一眼可见、一次点击。
        // 这是用户需求「编译出 apk 后可以选择直接安装到系统」的主入口。
        val resultApk = (detectedApk ?: workspaceSnapshot.lastArtifact?.let(::File))
            ?.takeIf { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
        if (!runState.running && runState.success == true && resultApk != null) {
            val apkInfo = remember(resultApk.absolutePath, resultApk.lastModified()) {
                com.nebulaforge.app.device.ApkInstaller.describe(context, resultApk)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1F2A1F))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text("✓ 构建产出", color = TermOk, fontSize = 12.sp)
                Text(
                    text = "${resultApk.name} · ${apkInfo.sizeText}" + (apkInfo.versionName?.let { " · v$it" } ?: ""),
                    color = TermDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                TextButton(onClick = { requestInstall(resultApk) }) {
                    Text("安装到本机", color = TermOk, fontSize = 12.sp)
                }
            }
        }

        // ------------------------------------------------------------ 输出归属提示
        // 输出（pty）属于「跑构建的工程」，任务列表属于「当前工程」。切文件到另一个工程后两者会不一致，
        // 用户看到的现象就是「切换文件后编译构建输出不同步」。这里直接说明白是哪个工程的输出，
        // 并给一键切过去的入口，而不是让用户自己猜。
        val ownerRoot = runState.projectRoot.takeIf { it.isNotBlank() }
        if (ownerRoot != null && root != null && File(ownerRoot).absolutePath != root.absolutePath) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF2A2318)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "下方输出来自工程「${File(ownerRoot).name}」，与当前工程「${root.name}」不同",
                    fontSize = 12.sp,
                    color = TermWarn,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { onOpenProject(ownerRoot) }) { Text("切到该工程", fontSize = 12.sp, color = TermAccent) }
            }
        }

        // ------------------------------------------------------------ 标签页
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = TermBar,
            contentColor = TermAccent
        ) {
            BuildTab.entries.forEach { t ->
                val badge = when (t) {
                    BuildTab.PROBLEMS -> if (problems.isEmpty()) "" else " ${problems.size}"
                    else -> ""
                }
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        Text(
                            t.label + badge,
                            fontSize = 12.sp,
                            color = when {
                                tab != t -> TermDim
                                t == BuildTab.PROBLEMS && runState.errorCount > 0 -> TermErr
                                else -> TermFg
                            }
                        )
                    }
                )
            }
        }

        when (tab) {
            // ---------------------------------------------------------- 输出（runner 文本）
            BuildTab.OUTPUT -> TaskOutputList(
                lines = allOutput,
                fontSize = fontSize,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                emptyHint = when {
                    root == null -> "未打开项目"
                    tasks.isEmpty() -> "该项目没有可用任务（.vscode/tasks.json 为空）"
                    else -> "点「▶ 构建」执行任务，日志实时显示在这里"
                }
            )

            // ---------------------------------------------------------- 问题
            BuildTab.PROBLEMS -> ProblemsTab(
                problems = problems,
                running = runState.running,
                onOpenFile = onOpenFile,
                fontSize = fontSize,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )

            // ---------------------------------------------------------- 应用日志（设备 logcat）
            BuildTab.APP_LOG -> LogLinesTab(
                lines = appLogLines,
                tint = TermFg,
                fontSize = fontSize,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                emptyHint = "暂无应用日志：运行应用后这里显示 adb logcat 输出"
            )

            // ---------------------------------------------------------- IDE日志
            BuildTab.IDE_LOG -> LogLinesTab(
                lines = ideLog.toList(),
                tint = TermDim,
                fontSize = fontSize,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                emptyHint = "暂无 IDE 日志"
            )
        }

        // ------------------------------------------------------------ 底部状态（与事件状态源一致）
        Row(
            modifier = Modifier.fillMaxWidth().background(TermBar).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    append(runState.message)
                    if (elapsedMs > 0) append(" · ").append(if (elapsedMs >= 1000) "${elapsedMs / 1000}s" else "${elapsedMs}ms")
                    runState.exitCode?.let { if (!runState.running) append(" · exit $it") }
                },
                fontSize = 11.sp,
                color = when {
                    runState.running -> TermAccent
                    runState.success == true -> TermOk
                    runState.success == false -> TermErr
                    else -> TermDim
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "问题 ${runState.errorCount}/${runState.problemCount}",
                fontSize = 11.sp,
                color = if (runState.errorCount > 0) TermErr else TermDim,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable { tab = BuildTab.PROBLEMS }
                    .padding(start = 8.dp)
            )
            // 底部状态栏第二套字号控件：状态栏一定在屏幕内，窄屏上也不会被任务名挤掉。
            Text(
                text = "字号",
                fontSize = 11.sp,
                color = TermDim,
                modifier = Modifier.padding(start = 10.dp)
            )
            Text(
                text = "A−",
                fontSize = 12.sp,
                color = TermDim,
                modifier = Modifier
                    .clickable { setFontSize(fontSize - 1) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Text(
                text = "$fontSize",
                fontSize = 11.sp,
                color = TermFg,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "A+",
                fontSize = 12.sp,
                color = TermDim,
                modifier = Modifier
                    .clickable { setFontSize(fontSize + 1) }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** 构建工具窗的四个标签页。 */
private enum class BuildTab(val label: String) {
    OUTPUT("输出"),
    PROBLEMS("问题"),
    APP_LOG("应用日志"),
    IDE_LOG("IDE日志")
}

/** 问题列表：severity + 位置 + 描述；有绝对路径的行可点击跳转到编辑器。 */
@Composable
private fun ProblemsTab(
    problems: List<IdeEvent.Diagnostic>,
    running: Boolean,
    onOpenFile: (String) -> Unit,
    fontSize: Int = 13,
    modifier: Modifier = Modifier
) {
    // 行文本跟随「输出字号」设置：以前这里写死 12.sp，用户在输出页调字号时只觉得「没反应」。
    val body = (fontSize - 1).coerceAtLeast(9).sp
    val meta = (fontSize - 3).coerceAtLeast(8).sp
    if (problems.isEmpty()) {
        Box(modifier, Alignment.Center) {
            Text(
                if (running) "正在构建，问题会在匹配到后实时出现…" else "没有匹配到问题（构建通过，或输出未被 problemMatcher 识别）",
                color = TermDim, fontSize = body
            )
        }
        return
    }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = modifier.padding(vertical = 4.dp)) {
        items(problems) { d ->
            val color = when (d.severity) {
                Severity.ERROR -> TermErr
                Severity.WARNING -> TermWarn
                Severity.INFO -> TermFg
            }
            val location = buildString {
                d.file?.let { append(File(it).name) }
                d.line?.let { append(":").append(it) }
                d.column?.let { append(":").append(it) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !d.file.isNullOrBlank()) { d.file?.let(onOpenFile) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = when (d.severity) {
                        Severity.ERROR -> "✕"
                        Severity.WARNING -> "⚠"
                        Severity.INFO -> "i"
                    },
                    color = color, fontSize = body, modifier = Modifier.width(20.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(d.message, color = TermFg, fontSize = body)
                    if (location.isNotBlank()) {
                        Text(location, color = TermDim, fontSize = meta, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF2D2D2D))
        }
    }
}

/** 纯文本日志列表（应用日志 / IDE日志共用），自动滚到底部。 */
@Composable
private fun LogLinesTab(
    lines: List<String>,
    tint: Color,
    emptyHint: String,
    fontSize: Int = 13,
    modifier: Modifier = Modifier
) {
    val body = (fontSize - 1).coerceAtLeast(9).sp
    if (lines.isEmpty()) {
        Box(modifier, Alignment.Center) { Text(emptyHint, color = TermDim, fontSize = body) }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) runCatching { listState.scrollToItem(lines.size - 1) }
    }
    LazyColumn(state = listState, modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        items(lines.size) { i ->
            Text(lines[i], color = tint, fontSize = body, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 把 IDE 事件渲染成一行日志；终端 stdout（[IdeEvent.Output]）不属于 IDE 日志，返回 null 过滤掉。 */
private fun describeIdeEvent(event: IdeEvent): String? = when (event) {
    is IdeEvent.Output -> null
    is IdeEvent.SessionRegistered -> "[SESSION] ${event.sessionId.take(8)} kind=${event.kind} ${event.projectPath ?: ""}"
    is IdeEvent.State -> "[STATE] ${event.sessionId.take(8)} ${describeState(event.state)}"
    is IdeEvent.Diagnostic -> "[DIAG/${event.severity}] ${event.file ?: "-"}:${event.line ?: 0} ${event.message}"
    is IdeEvent.Artifact -> "[ARTIFACT] ${event.path}"
    is IdeEvent.Device -> "[DEVICE] ${event.serial} ${event.state}"
    is IdeEvent.Relation -> "[RELATION] ${event.sessionId.take(8)} -${event.relation}-> ${event.relatedSessionId.take(8)}"
    is IdeEvent.FileChanged -> "[FILE] ${event.path}"
    else -> null
}

private fun describeState(state: SessionState): String = when (state) {
    is SessionState.Idle -> "空闲"
    is SessionState.Preparing -> "准备：${state.message}"
    is SessionState.Running -> "运行中：${state.message}"
    is SessionState.Succeeded -> "成功：${state.message}"
    is SessionState.Failed -> "失败：${state.message}" + (state.exitCode?.let { " (exit $it)" } ?: "")
    is SessionState.Cancelled -> "已取消"
    else -> state.toString()
}

private const val MAX_IDE_LOG_LINES = 600

/**
 * 「测试包 / 全量包」构建变体选择器。
 *
 * 用户需求原话：编译构建 APK 时希望**可以选择**编译全量包还是测试包。实现上不改任务清单，
 * 只把 defaultTasks 里成对的 debug/release 任务聚成一个二选一控件；两者本来也都还留在
 * 任务下拉框里（习惯用列表的人不受影响），点这里则是切换「▶ 构建」执行的任务。
 *
 * 只有项目同时提供同一族的两个变体时才出现：Gradle(assembleDebug/assembleRelease)、
 * Flutter(build apk --debug/--release)。其它工程（Python/Node/C++/…）没有变体概念，
 * 这个控件直接不渲染 —— 避免出现点了没反应的假按钮。
 */
@Composable
private fun BuildVariantSelector(tasks: List<TaskDefinition>, selectedId: String?, onSelect: (String) -> Unit) {
    val pairs = listOf(
        listOf("gradle-build" to "测试包", "gradle-release" to "全量包"),
        listOf("flutter-build" to "测试包", "flutter-build-release" to "全量包")
    )
    val pair = pairs.firstOrNull { ids -> ids.all { (id, _) -> tasks.any { it.id == id } } } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        pair.forEach { (id, label) ->
            val on = selectedId == id
            Text(
                text = label,
                fontSize = 12.sp,
                color = if (on) TermBg else TermDim,
                modifier = Modifier
                    .background(if (on) TermAccent else Color(0xFF2A2A2A), RoundedCornerShape(10.dp))
                    .clickable { onSelect(id) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
