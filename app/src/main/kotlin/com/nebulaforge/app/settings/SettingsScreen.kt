package com.nebulaforge.app.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nebulaforge.app.R
import com.nebulaforge.core.environment.BootstrapInstaller
import com.nebulaforge.core.environment.CheckResult
import com.nebulaforge.core.environment.CheckStep
import com.nebulaforge.core.toolchain.ToolchainComponent
import com.nebulaforge.core.toolchain.ToolchainState
import com.nebulaforge.core.toolchain.ToolchainStatus
import com.nebulaforge.core.toolchain.ToolchainTaskState
import com.nebulaforge.core.toolchain.ToolchainTask
import com.nebulaforge.core.agent.AiProviderSettingsStore
import com.nebulaforge.app.NebulaForgeApplication
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAiSettings: () -> Unit = {},
    onOpenDeviceAccess: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(LocalContext.current))
) {
    val checks by viewModel.checkResults.collectAsStateWithLifecycle()
    val bootstrap by viewModel.installState.collectAsStateWithLifecycle()
    val statuses by viewModel.toolchainStatuses.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val bridgePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::installBridge) }
    val aiStore = remember { AiProviderSettingsStore(context) }
    val languageServices = (context.applicationContext as NebulaForgeApplication).languageServices
    val languageStatuses by languageServices.statuses.collectAsStateWithLifecycle()
    val lspStatuses by viewModel.lspStatuses.collectAsStateWithLifecycle()
    val toolchainLog by viewModel.toolchainLiveLog.collectAsStateWithLifecycle()
    val androidSdkVersions by viewModel.androidSdkVersions.collectAsStateWithLifecycle()
    var ai by remember { mutableStateOf(aiStore.load()) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Toolchain Runtime Manager", style = MaterialTheme.typography.titleLarge)
                        Text("所有 OK 都必须来自真实二进制执行探测，不再只看文件是否存在。", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("AI 智能", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (ai.isUsable()) "当前生效：${ai.displayName()}（${ai.protocol.label} · ${ai.model}）"
                                    else "当前配置不可用：${ai.displayName()}，AI 面板与 Agent 无法调用模型",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = onOpenAiSettings) { Text("AI 配置管理") }
                        }
                        Text("服务地址：${ai.normalizedBaseUrl()}", style = MaterialTheme.typography.labelSmall)
                        Text("对话模型：${ai.model} · 嵌入模型：${ai.embeddingModel.ifBlank { "未启用" }}", style = MaterialTheme.typography.labelSmall)
                        Text("构建失败自动修复：${if (ai.autoFixOnBuildFailure) "已开启" else "已关闭"}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("设备权限（Shizuku / Root / 终端通道）", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "AI 操作终端需要显式开启，并按 Root → Shizuku → 内嵌用户态的顺序选择通道。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = onOpenDeviceAccess) { Text("管理") }
                        }
                        val deviceSummary = remember { com.nebulaforge.core.device.DeviceShellGate.statusSummary(context) }
                        Text(deviceSummary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            item {
                Button(onClick = viewModel::installBuildPrerequisites, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy != null) "正在修复工具链…" else "一键修复 Android 构建前置环境")
                }
            }
            message?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item {
                Text("Toolchain Task Center", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text("安装/修复任务持久化；Activity 重建不会伪造正在运行状态。", style = MaterialTheme.typography.bodySmall)
            }
            items(tasks.takeLast(12).reversed()) { task -> TaskCard(task) { viewModel.cancelTask(task.id) } }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Language Server Manager", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                        Text("读取上游 release 元数据；下载支持断点续传，只有 SHA-256 可验证的资产才允许激活。", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = viewModel::refreshLsp) { Text("检查更新") }
                }
            }
            items(lspStatuses) { status ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${status.displayName} · ${status.state}", style = MaterialTheme.typography.titleSmall)
                                status.remoteVersion?.let { Text("最新：$it", style = MaterialTheme.typography.labelSmall) }
                                status.installedVersion?.let { Text("已安装：$it", style = MaterialTheme.typography.labelSmall) }
                                if (status.assetName != null) Text("Asset：${status.assetName}", style = MaterialTheme.typography.labelSmall)
                                if (status.detail.isNotBlank()) Text(status.detail, style = MaterialTheme.typography.bodySmall)
                            }
                            if (status.state == com.nebulaforge.core.toolchain.LspArtifactManager.State.AVAILABLE) {
                                Button(onClick = { viewModel.installLsp(status.id) }) { Text("安装") }
                            }
                        }
                    }
                }
            }
            item {
                Text("Language Service Registry", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text("只显示真实探测/启动状态；未安装语言服务器不会伪装成运行中。", style = MaterialTheme.typography.bodySmall)
            }
            items(languageStatuses) { status ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${status.languageId} · ${status.state}", style = MaterialTheme.typography.titleSmall)
                        Text(status.project, style = MaterialTheme.typography.labelSmall)
                        if (status.message.isNotBlank()) Text(status.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { ToolchainSourcePanel() }
            // 工具链快照入口：状态 + 手动快照/恢复（自动恢复在构建启动前进行）。
            item { ToolchainSnapshotPanel() }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("运行环境自检", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    // 自检此前只在进入设置页时跑一次：用户装完 adb 却看不到状态变化，只能反复退出重进。
                    // 这里补手动重测入口，与「组件装完后自动重测」互补。
                    TextButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("重新检测")
                    }
                }
            }
            items(checks) { result -> LegacyCheckCard(result, bootstrap, viewModel::startBootstrapInstall) }
            item {
                AndroidSdkVersionsPanel(androidSdkVersions, viewModel::installAndroidSdkVersion, viewModel::refreshAndroidSdkVersions)
            }
            item { Text("真实工具链探测", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            // 完成度汇总：清单来源是 ToolchainComponent.entries（枚举即清单，装了必然出现在下面），
            // 这里只补统计与「构建必需是否齐」，省得用户在一屏 29 张卡片里自己数。
            item {
                val progress = com.nebulaforge.app.onboarding.ToolchainProgress.of(statuses)
                val readySet = statuses.filter { it.state == ToolchainState.READY }.map { it.component }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "完成度 ${progress.readyCount}/${progress.total}（${progress.percent}%）",
                            style = MaterialTheme.typography.titleSmall
                        )
                        LinearProgressIndicator(
                            progress = { progress.percent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        )
                        Text(
                            if (progress.allEssentialReady) {
                                "构建必需组件 ${progress.essentialReady}/${progress.essentialTotal} 已全部就绪，Android 工程可直接构建。"
                            } else {
                                "构建必需组件 ${progress.essentialReady}/${progress.essentialTotal}；缺少 " +
                                    com.nebulaforge.app.onboarding.ToolchainProgress.ESSENTIAL
                                        .filter { it !in readySet }
                                        .joinToString("、") { it.title } +
                                    "。可点页首「一键修复 Android 构建前置环境」。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (progress.allEssentialReady) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
            items(ToolchainComponent.entries) { component ->
                val status = statuses.firstOrNull { it.component == component }
                ToolchainCard(component, status, busy == component, if (component == ToolchainComponent.GRADLE_TOOLING_BRIDGE) { { bridgePicker.launch(arrayOf("application/java-archive", "application/octet-stream")) } } else { { viewModel.install(component) } })
            }
            // 实时输出面板：apt/pkg 下载、SDK 解压、gradle 启动都可能耗时几十秒，
            // 之前只有一个「安装中」字样，用户无法判断是在下载还是卡死。
            if (toolchainLog.isNotEmpty() || busy != null) {
                item {
                    val scroll = rememberScrollState()
                    val logContext = androidx.compose.ui.platform.LocalContext.current
                    LaunchedEffect(toolchainLog.size) {
                        // 自动跟随最新输出；用户手动回看时也只是短暂跳动，不影响可读性。
                        runCatching { scroll.scrollTo(scroll.maxValue) }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (busy != null) "安装输出（实时 · 运行中）" else "安装输出（实时）",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                // 排障时用户需要把整段安装输出贴给别人：提供一键复制，
                                // 同时用 SelectionContainer 支持长按选择任意片段。
                                TextButton(onClick = { copyPlainText(logContext, toolchainLog.joinToString("\n"), "安装输出") }) { Text("复制") }
                                TextButton(onClick = { viewModel.clearToolchainLiveLog() }) { Text("清空") }
                            }
                            Text(
                                "长按可选择文本，或点右上角「复制」整段复制",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SelectionContainer {
                                Text(
                                    toolchainLog.takeLast(400).joinToString("\n"),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 300.dp)
                                        .verticalScroll(scroll)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun AndroidSdkVersionsPanel(
    versions: List<AndroidSdkVersionState>,
    onInstall: (Int) -> Unit,
    onRefresh: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Android SDK 多版本", style = MaterialTheme.typography.titleMedium)
                    Text("platform 与 Build Tools 可并存，缺失版本通过 sdkmanager 联网安装。", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
            versions.forEach { version ->
                val ready = version.platformInstalled && version.buildToolsInstalled
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Android ${version.api} · Build Tools ${version.api}.0.0")
                        Text("platform ${if (version.platformInstalled) "已安装" else "缺失"}，Build Tools ${if (version.buildToolsInstalled) "已安装" else "缺失"}", style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { onInstall(version.api) }, enabled = !ready) { Text(if (ready) "已就绪" else "安装") }
                }
            }
        }
    }
}

@Composable private fun ToolchainCard(component: ToolchainComponent, status: ToolchainStatus?, installing: Boolean, onInstall: (() -> Unit)? = null) {
    val s = status?.state ?: ToolchainState.MISSING
    val ready = s == ToolchainState.READY
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ready) Icons.Default.CheckCircle else Icons.Default.Error, null,
                    tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(component.title, style = MaterialTheme.typography.titleMedium)
                    Text(status?.version?.takeIf { it.isNotBlank() } ?: status?.detail.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (component == ToolchainComponent.GRADLE_TOOLING_BRIDGE && !ready) Text("需要已构建并可运行的 bridge JAR；选择文件后会执行 SHA-256 + java -jar self-test。", style = MaterialTheme.typography.labelSmall)
                    status?.path?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
                if (!ready && onInstall != null) Button(onClick = onInstall, enabled = !installing) { Text(if (installing) "安装中" else if (component == ToolchainComponent.GRADLE_TOOLING_BRIDGE) "选择 JAR" else "安装/修复") }
            }
        }
    }
}

@Composable private fun TaskCard(task: ToolchainTask, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall)
                    Text(taskStateLabel(task.state) + " · " + task.message, style = MaterialTheme.typography.bodySmall)
                }
                if (task.state == ToolchainTaskState.RUNNING || task.state == ToolchainTaskState.QUEUED) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
            LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

private fun taskStateLabel(state: ToolchainTaskState): String = when (state) {
    ToolchainTaskState.QUEUED -> "排队"
    ToolchainTaskState.RUNNING -> "运行中"
    ToolchainTaskState.SUCCEEDED -> "成功"
    ToolchainTaskState.FAILED -> "失败"
    ToolchainTaskState.CANCELLED -> "已取消"
}

@Composable private fun LegacyCheckCard(result: CheckResult, installState: BootstrapInstaller.InstallProgress?, onInstall: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stepLabel(result.step), style = MaterialTheme.typography.titleSmall)
            Text(if (result.passed) "通过：${result.detail}" else "失败：${result.detail}", color = if (result.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            if (result.step == CheckStep.BOOTSTRAP_INSTALLED && !result.passed) {
                Button(onClick = onInstall, modifier = Modifier.padding(top = 6.dp)) { Text("初始化内置运行环境") }
            }
            if (installState is BootstrapInstaller.InstallProgress.Failed) Text("失败：${installState.reason}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else if (installState != null) Text(installProgressText(installState), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 把安装器上报的阶段翻译成用户可读的进度文案（正文 ≥14sp 由 typography.bodySmall 保证）。 */
private fun installProgressText(p: BootstrapInstaller.InstallProgress): String = when (p) {
    is BootstrapInstaller.InstallProgress.Downloading -> {
        if (p.totalBytes > 0) {
            val mb = { v: Long -> "%.1f".format(v / 1048576.0) }
            "正在释放内置用户态 ${mb(p.bytesDownloaded)}/${mb(p.totalBytes)}MB（${(p.bytesDownloaded * 100 / p.totalBytes).coerceIn(0, 100)}%）"
        } else {
            "正在准备内置用户态…"
        }
    }
    is BootstrapInstaller.InstallProgress.Extracting -> "正在解压用户态：${p.currentEntry}"
    BootstrapInstaller.InstallProgress.SettingPermissions -> "正在补齐可执行权限…"
    BootstrapInstaller.InstallProgress.LinkingSymlinks -> "正在重建符号链接…"
    is BootstrapInstaller.InstallProgress.Verifying -> p.step
    BootstrapInstaller.InstallProgress.Completed -> "内置运行环境就绪，可直接使用终端与构建工具链"
    is BootstrapInstaller.InstallProgress.Failed -> "失败：${p.reason}"
}

private fun stepLabel(step: CheckStep): String = when (step) {
    CheckStep.BOOTSTRAP_INSTALLED -> "Embedded runtime"
    CheckStep.JDK_17 -> "JDK 17"
    CheckStep.ANDROID_SDK_BASIC -> "Android SDK"
    CheckStep.ADB_EXECUTABLE -> "ADB"
    CheckStep.NETWORK -> "Network"
}

/**
 * 把长文本放进系统剪贴板。
 *
 * 之前安装输出只能看不能复制，用户遇到失败只能口述或截图；排障成本很高。
 */
internal fun copyPlainText(context: android.content.Context, text: String, label: String = "文本") {
    if (text.isBlank()) return
    val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    manager?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    android.widget.Toast.makeText(context, "$label 已复制（${text.length} 字符）", android.widget.Toast.LENGTH_SHORT).show()
}
