package com.nebulaforge.app.run

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.app.terminal.IdeTerminalPanel
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.projectmodel.RunConfigurationSpec
import com.nebulaforge.core.session.*
import com.nebulaforge.core.session.RunConfigurationEditorModel
import com.nebulaforge.app.build.TaskOutputList
import com.nebulaforge.core.terminal.TerminalSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** 面板配色（与构建面板统一，避免两个底部页签看起来像两个产品）。 */
private val RunBg = Color(0xFF1B1B1B)
private val RunBar = Color(0xFF252526)
private val RunDim = Color(0xFF9AA0A6)
private val RunOk = Color(0xFF89D185)
private val RunErr = Color(0xFFF48771)

/**
 * 运行工具窗（VSCode 式）：
 *
 * ```
 * [运行配置 ▼] [▶ 运行] [■ 停止] [清空] [−][+] [配置] [记录]   ← 工具条
 * ● 运行中 3.2s · 退出码 — · 问题 0/0                      ← 状态行
 * ┌──────────────────────────────────────────────────────┐
 * │ 终端（真 pty：ANSI 颜色 / 进度条 / 交互式输入都正常）      │
 * └──────────────────────────────────────────────────────┘
 * ```
 *
 * 设计要点（和旧版「大表单 + 会话卡片」的区别）：
 * 1. **输出区就是终端**：运行走 [com.nebulaforge.app.build.WorkspaceTaskRunner]（pty 会话），
 *    颜色、`\r` 进度刷新、`需要你输入 y/n` 这类交互提示都能正常工作，
 *    也能在「终端」工具窗里接管同一会话继续敲命令；
 * 2. **配置编辑退到「配置」按钮后面**：日常只用选一个配置、点运行；调参时才展开，
 *    手机屏幕不会被一堆输入框占满；
 * 3. **会话历史退到「记录」按钮后面**：默认不占屏幕，需要对比多次运行时才展开。
 *
 * 状态与执行仍由事件总线（[IdeSessionBus]）承载：运行会话会被 [RunCenterStore] 记录、
 * 被会话图与 Logcat 链路识别，切页签不中断运行。
 */
@Composable
fun RunToolWindowScreen(
    onOpenFile: (String) -> Unit = {},
    onOpenBuild: () -> Unit = {}
) {
    val context = LocalContext.current
    val app = context.applicationContext as NebulaForgeApplication
    val records by app.runCenter.records.collectAsState()
    val graph by app.sessionGraph.snapshot.collectAsState()
    val configs by app.runConfigurations.all.collectAsState()
    val runState by app.workspaceTasks.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedSession by remember { mutableStateOf<String?>(null) }
    var selectedConfigId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<RunDeviceCapability>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("run") }
    var argsText by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var workingDir by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("") }
    var showConfig by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // 运行面板与构建面板**共用同一份**持久化字号（工作区状态 terminalFontSize）。
    // 以前这里是面板内 remember：切页/重启就丢，而且和构建面板各记一份、互相覆盖 ——
    // 用户「在构建面板调好了，到运行面板又变回去」正是这个原因。
    val workspaceSnapshot by app.workspaceState.state.collectAsState()
    val fontSize = workspaceSnapshot.terminalFontSize
    val setFontSize: (Int) -> Unit = { app.workspaceState.updateTerminalFont(it) }
    val timeline = remember(graph, selectedSession) { SessionExecutionTimelineBuilder.build(graph, selectedSession) }

    LaunchedEffect(records) { if (selectedSession == null) selectedSession = records.lastOrNull()?.id }
    val current = records.firstOrNull { it.id == selectedSession } ?: records.lastOrNull()
    val projectRoot = current?.projectPath?.let(::File) ?: runCatching {
        File(Environment.projectsDir(context)).listFiles().orEmpty().firstOrNull { it.isDirectory }
    }.getOrNull()
    val projectConfigs = projectRoot?.let { app.runConfigurations.forProject(it) }.orEmpty()
    val selectedConfig = projectConfigs.firstOrNull { it.id == selectedConfigId } ?: projectConfigs.firstOrNull()

    LaunchedEffect(selectedConfig?.id) {
        selectedConfig?.let {
            selectedConfigId = it.id
            name = it.name; mode = it.mode; argsText = it.arguments.joinToString(" ")
            envText = it.environment.entries.joinToString("\n") { e -> "${e.key}=${e.value}" }
            workingDir = it.workingDirectory.orEmpty(); deviceId = it.deviceId.orEmpty(); portText = it.port?.toString().orEmpty()
        }
    }

    val manager = remember(context) { TerminalSessionManager.get(context) }
    // pty 会话 id 由 runner 的 StateFlow 暴露（见 WorkspaceTaskRunner.activePty）：
    // 任务启动 → ensurePty 建出会话 → flow 变化 → 这里的终端视图自动挂上。
    // 以前这里是「本地 var + LaunchedEffect(running, sessionId)」，两个键都在 ensurePty
    // **之前**就变化了，pty 真建出来时不再有任何状态变化 → 运行面板恒为空白（看不到输出）。
    val ptyId by app.workspaceTasks.activePty.collectAsState()
    // 输出区直接渲染 runner 文本（与 pty 解耦，见 TaskOutputList 注释）。
    val allOutput by app.workspaceTasks.output.collectAsState()
    // 运行中每秒刷新一次耗时显示（终端内容本身由 pty 推送，不需要重组的参与）。
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(runState.running) {
        while (runState.running) {
            tick = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsedMs = when {
        runState.startedAt == 0L -> 0L
        runState.running -> (if (tick > 0) tick else System.currentTimeMillis()) - runState.startedAt
        else -> runState.durationMs
    }

    /** 运行当前配置：生成命令行 → 交给 pty 执行器（与构建共用一套会话/停止/日志采集）。 */
    fun launch(spec: RunConfigurationSpec) {
        val root = File(spec.projectPath)
        val command = runCatching { RunConfigurationEngine().command(spec) }.getOrElse { t ->
            message = "无法生成运行命令：${t.message}"
            return
        }
        if (command.isBlank()) {
            message = "该配置没有可执行的运行命令"
            return
        }
        val cwd = runCatching { RunConfigurationEngine().workingDirectory(spec) }.getOrNull()
        val started = app.workspaceTasks.runCommand(
            root = root,
            label = spec.name,
            commandLine = command,
            kind = SessionKind.RUN,
            cwd = cwd,
            env = spec.environment,
            matchers = emptyList()
        )
        message = if (started) "已启动「${spec.name}」：$command" else "终端里已有任务在运行，请先停止"
    }

    Column(Modifier.fillMaxSize().background(RunBg)) {

        // ------------------------------------------------------------------ 工具条
        // 与构建面板同一套布局：任务区可横向滚动，**字号段固定**。
        // 真机反馈「运行时输出文本调不了字号」的根因就是这个工具条既不可滚动、
        // 字号又排在 Spacer(weight) 之后 —— 窄屏上「−/+」直接被挤出屏幕，永远点不到。
        Row(
            modifier = Modifier.fillMaxWidth().background(RunBar).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { menuOpen = true },
                    label = { Text(selectedConfig?.name ?: "选择运行配置", fontSize = 12.sp, maxLines = 1) }
                )
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (projectConfigs.isEmpty()) {
                        DropdownMenuItem(text = { Text("当前项目没有运行配置") }, enabled = false, onClick = {})
                    }
                    projectConfigs.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.name) },
                            onClick = { selectedConfigId = c.id; menuOpen = false }
                        )
                    }
                }
            }
            Button(onClick = { selectedConfig?.let { launch(it) } }, enabled = selectedConfig != null && !runState.running) {
                Text("▶ 运行", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { app.workspaceTasks.stop() }, enabled = runState.running) {
                Text("■ 停止", fontSize = 13.sp)
            }
            TextButton(onClick = { app.workspaceTasks.clearOutput() }) { Text("清空", fontSize = 13.sp, color = RunDim) }
            // 一键复制全部输出。局部选择直接长按输出区：TaskOutputList 已包 SelectionContainer，
            // 系统会长按弹「选择/复制」菜单。
            TextButton(onClick = {
                val text = allOutput.joinToString("\n")
                message = if (TerminalSessionManager.copyToSystemClipboard(text)) {
                    "已复制全部输出（${text.length} 字符）"
                } else {
                    "没有可复制的输出"
                }
            }) { Text("复制", fontSize = 13.sp, color = RunDim) }
            TextButton(onClick = { onOpenBuild() }) { Text("构建", fontSize = 13.sp, color = RunDim) }
            TextButton(onClick = { showConfig = !showConfig; if (showConfig) showHistory = false }) {
                Text("配置", fontSize = 13.sp, color = if (showConfig) RunOk else RunDim)
            }
            TextButton(onClick = { showHistory = !showHistory; if (showHistory) showConfig = false }) {
                Text("记录", fontSize = 13.sp, color = if (showHistory) RunOk else RunDim)
            }
            }

            // 字号（固定段）：不再随任务区滚动，也不再用面板内的局部状态。
            Text("字号", fontSize = 11.sp, color = RunDim)
            Text(
                text = "A−", fontSize = 13.sp, color = RunDim,
                modifier = Modifier.clickable { setFontSize(fontSize - 1) }.padding(horizontal = 6.dp, vertical = 4.dp)
            )
            Text("$fontSize", fontSize = 11.sp, color = Color(0xFFE0E0E0), fontFamily = FontFamily.Monospace)
            Text(
                text = "A+", fontSize = 13.sp, color = RunDim,
                modifier = Modifier.clickable { setFontSize(fontSize + 1) }.padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }

        // ------------------------------------------------------------------ 状态行
        Row(
            modifier = Modifier.fillMaxWidth().background(RunBar).padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (runState.running) "●" else "○", color = if (runState.running) RunOk else RunDim, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                buildString {
                    append(runState.label.ifBlank { "运行" })
                    append(" · ")
                    append(
                        when {
                            runState.running -> "运行中 ${elapsedMs / 1000}s"
                            runState.cancelled -> "已停止"
                            runState.success == true -> "已结束（退出码 0）${elapsedMs / 1000}s"
                            runState.exitCode != null -> "失败（退出码 ${runState.exitCode}）${elapsedMs / 1000}s"
                            else -> "就绪"
                        }
                    )
                    if (runState.problemCount > 0) append(" · 问题 ${runState.errorCount}/${runState.problemCount}")
                },
                fontSize = 11.sp,
                color = when {
                    runState.running -> RunOk
                    runState.success == true -> RunOk
                    runState.exitCode != null -> RunErr
                    else -> RunDim
                },
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            message?.let { Text(it, fontSize = 10.sp, color = RunDim, maxLines = 1) }
        }

        // ------------------------------------------------------------------ 输出
        // 直接渲染 runner 的文本输出（与 pty/emulator 解耦）：真机上终端链路一旦不出内容，
        // 旧实现（只挂终端视图）就是「面板永远空白 + 状态停在运行中」。
        TaskOutputList(
            lines = allOutput,
            fontSize = fontSize,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            emptyHint = when {
                projectRoot == null -> "未打开项目"
                projectConfigs.isEmpty() -> "该项目没有可用运行配置。可点下方齿轮新建；独立语言工程（Python / Node / C / C++ / 静态站点等）会自动创建一条。"
                else -> "点「▶ 运行」启动，日志实时显示在这里"
            }
        )

        // ------------------------------------------------------------------ 配置（按需展开）
        if (showConfig) {
            Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).background(RunBar)) {
                if (projectRoot != null && projectConfigs.isNotEmpty()) {
                    ConfigurationEditor(
                        configs = projectConfigs,
                        selected = selectedConfig,
                        selectedId = selectedConfigId,
                        onSelect = { selectedConfigId = it },
                        name = name, onName = { name = it },
                        mode = mode, onMode = { mode = it },
                        args = argsText, onArgs = { argsText = it },
                        env = envText, onEnv = { envText = it },
                        workingDir = workingDir, onWorkingDir = { workingDir = it },
                        deviceId = deviceId, devices = devices, typeId = selectedConfig?.typeId.orEmpty(), onDevice = { deviceId = it },
                        port = portText, onPort = { portText = it },
                        onRefreshDevices = {
                            scope.launch {
                                devices = app.runDevices.discoverCapabilities(selectedConfig?.typeId.orEmpty())
                                val compatible = RunConfigurationDeviceFilter.compatible(selectedConfig?.typeId.orEmpty(), mode, deviceId, devices)
                                if (deviceId.isNotBlank() && compatible.none { it.id == deviceId }) deviceId = ""
                                message = "设备刷新完成：${devices.size} 个，可用于当前模式：${compatible.size} 个"
                            }
                        },
                        onCopy = {
                            selectedConfig?.let { old ->
                                val copy = RunConfigurationEditorModel.create(File(old.projectPath), old.typeId, "${old.name} Copy")
                                app.runConfigurations.upsert(copy)
                                selectedConfigId = copy.id
                            }
                        },
                        onDelete = {
                            selectedConfig?.let { old -> app.runConfigurations.delete(old); selectedConfigId = null }
                        },
                        onSave = {
                            selectedConfig?.let { old ->
                                val port = portText.toIntOrNull()
                                if (port != null && !PortAllocator.isAvailable(port) && port != old.port) {
                                    message = "端口 $port 当前不可用"
                                } else {
                                    val parsedArgs = runCatching { RunConfigurationEditorModel.parseArguments(argsText) }
                                    val parsedEnv = RunConfigurationEditorModel.parseEnvironment(envText)
                                    if (parsedArgs.isFailure) {
                                        message = parsedArgs.exceptionOrNull()?.message ?: "启动参数无效"
                                    } else if (parsedEnv.isFailure) {
                                        message = parsedEnv.exceptionOrNull()?.message ?: "环境变量无效"
                                    } else {
                                        val normalizedMode = RunConfigurationEditorModel.sanitizeMode(old.typeId, mode)
                                        if (normalizedMode.isBlank()) {
                                            message = "当前项目类型没有可用运行模式"
                                        } else {
                                            app.runConfigurations.upsert(old.copy(
                                                name = name.ifBlank { old.name }, mode = normalizedMode,
                                                arguments = parsedArgs.getOrThrow(),
                                                environment = parsedEnv.getOrThrow(),
                                                workingDirectory = workingDir.trim().takeIf { it.isNotBlank() },
                                                deviceId = deviceId.trim().takeIf { it.isNotBlank() }, port = port
                                            ))
                                            message = "运行配置已保存"
                                        }
                                    }
                                }
                            }
                        },
                        onRun = { selectedConfig?.let { launch(it) } },
                        onBuild = { selectedConfig?.let { onOpenBuild() } }
                    )
                } else {
                    Card(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text("当前没有可用项目运行配置。打开一个 Android / Flutter / Web 项目后会自动创建默认配置。", Modifier.padding(16.dp))
                    }
                }
            }
        }

        // ------------------------------------------------------------------ 会话记录（按需展开）
        if (showHistory) {
            Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).background(RunBar)) {
                if (records.isEmpty()) {
                    Text("暂无运行会话。", color = RunDim, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(records.reversed(), key = { it.id }) { r ->
                            ListItem(
                                headlineContent = { Text(r.kind.name, fontSize = 13.sp) },
                                supportingContent = {
                                    Text(
                                        (r.projectPath ?: "未知项目").substringAfterLast('/') + " · " + stateText(r.state),
                                        fontSize = 11.sp
                                    )
                                },
                                trailingContent = {
                                    // 退出码只在失败会话上可得（成功/取消不单独记码）。
                                    val code = (r.state as? SessionState.Failed)?.exitCode
                                    if (code != null) Text("退出码 $code", fontSize = 11.sp, color = RunErr)
                                },
                                modifier = Modifier.fillMaxWidth().clickable { selectedSession = r.id }
                            )
                        }
                    }
                    current?.let { r ->
                        Column(Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
                            if (timeline.entries.isNotEmpty()) {
                                Text("执行链路", fontSize = 11.sp, color = RunDim)
                                timeline.entries.forEachIndexed { index, entry ->
                                    Text(
                                        (if (index == 0) "" else "→ ") + "${entry.label} · ${stateText(entry.state)}",
                                        fontSize = 11.sp, color = RunDim, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            r.output.takeLast(40).forEach { Text(it, fontSize = 11.sp, color = RunDim, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationEditor(
    configs: List<RunConfigurationSpec>, selected: RunConfigurationSpec?, selectedId: String?, onSelect: (String) -> Unit,
    name: String, onName: (String) -> Unit, mode: String, onMode: (String) -> Unit,
    args: String, onArgs: (String) -> Unit, env: String, onEnv: (String) -> Unit,
    workingDir: String, onWorkingDir: (String) -> Unit, deviceId: String, devices: List<RunDeviceCapability>, typeId: String, onDevice: (String) -> Unit,
    port: String, onPort: (String) -> Unit, onRefreshDevices: () -> Unit, onSave: () -> Unit, onCopy: () -> Unit, onDelete: () -> Unit,
    onRun: () -> Unit, onBuild: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected?.name ?: "选择运行配置") }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    configs.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { onSelect(c.id); expanded = false }) }
                }
            }
            Button(onClick = onRun, enabled = selected != null) { Text("运行") }
            OutlinedButton(onClick = onBuild) { Text("构建") }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(name, onName, label = { Text("名称") }, modifier = Modifier.weight(1f), singleLine = true)
            ModeSelector(
                typeId = selected?.typeId.orEmpty(), value = mode, onValue = onMode, modifier = Modifier.weight(1f)
            )
            OutlinedTextField(port, onPort, label = { Text("端口") }, modifier = Modifier.width(100.dp), singleLine = true)
        }
        OutlinedTextField(args, onArgs, label = { Text("启动参数（空格分隔）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(workingDir, onWorkingDir, label = { Text("工作目录，留空=项目根") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(env, onEnv, label = { Text("环境变量 KEY=VALUE，每行一项") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefreshDevices) { Text("刷新设备") }
            if (devices.isNotEmpty()) {
                var deviceExpanded by remember { mutableStateOf(false) }
                val compatibility = RunConfigurationDeviceFilter.filter(typeId, mode, null, devices)
                val selectedDevice = devices.firstOrNull { it.id == deviceId }
                Box {
                    OutlinedButton(onClick = { deviceExpanded = true }) { Text(selectedDevice?.label ?: if (deviceId.isBlank()) "选择设备" else deviceId) }
                    DropdownMenu(deviceExpanded, onDismissRequest = { deviceExpanded = false }) {
                        compatibility.forEach { item ->
                            DropdownMenuItem(
                                text = { Text("${item.device.label} · ${item.device.status.name.lowercase()}${item.device.apiLevel?.let { " · API $it" } ?: ""}") },
                                enabled = item.compatible,
                                onClick = { onDevice(item.device.id); deviceExpanded = false }
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = onSave, enabled = selected != null) { Text("保存配置") }
            OutlinedButton(onClick = onCopy, enabled = selected != null) { Text("复制") }
            OutlinedButton(onClick = onDelete, enabled = selected != null && configs.size > 1) { Text("删除") }
        }
    }
}

@Composable
private fun ModeSelector(typeId: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val options = RunConfigurationEditorModel.modes(typeId)
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(options.firstOrNull { it.id == value }?.label ?: value)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onValue(option.id); expanded = false })
            }
        }
    }
}

private fun stateText(state: SessionState): String = when (state) {
    SessionState.Idle -> "空闲"; is SessionState.Preparing -> "准备中"; is SessionState.Running -> "运行中"
    is SessionState.Succeeded -> "已结束"; is SessionState.Failed -> "失败"; SessionState.Cancelled -> "已停止"
}
