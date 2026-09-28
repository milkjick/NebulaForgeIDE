package com.nebulaforge.app.reverse

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nebulaforge.app.R
import com.nebulaforge.app.reverse.ai.ReverseAiAssistant
import com.nebulaforge.core.agent.AiProviderSettingsStore
import com.nebulaforge.core.agent.OpenAiCompatibleCompletionClient
import com.nebulaforge.core.toolchain.ToolchainComponent
import com.nebulaforge.core.toolchain.ToolchainManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * 第 9.1 节 APK 逆向工作台。
 *
 * 这里只处理用户主动选择的 APK，并在进入工具前显示一次合规声明。
 * 原 APK 不会被修改；所有反编译/回编译结果放在独立工作区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApkReverseScreen(showCompliance: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { ApkReverseEngine(context) }
    // 注入 ToolchainManager：apktool / JADX 优先走 Termux 仓库（apt）安装，
    // GitHub 下载只在 apt 路径不可用时兜底（真机实测该下载必然断流）。
    val provisioner = remember { ReverseToolProvisioner(context, ToolchainManager(context)) }
    var consent by remember { mutableStateOf(false) }
    var selectedApk by remember { mutableStateOf<File?>(null) }
    var report by remember { mutableStateOf<ApkReverseEngine.ApkReport?>(null) }
    var output by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(engine.toolStatus()) }
    var editableFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedEditableFile by remember { mutableStateOf<File?>(null) }
    var editorText by remember { mutableStateOf("") }
    var editorDirty by remember { mutableStateOf(false) }
    var editorMessage by remember { mutableStateOf<String?>(null) }
    var aiResult by remember { mutableStateOf<String?>(null) }
    val ai = remember { ReverseAiAssistant(OpenAiCompatibleCompletionClient(AiProviderSettingsStore(context).load())) }

    // 安装进度（进度条 + 实时日志行）：解决「点了安装之后页面毫无反应、不知道在下什么」。
    var installLabel by remember { mutableStateOf<String?>(null) }
    var installPercent by remember { mutableStateOf(0) }
    var installLog by remember { mutableStateOf<List<String>>(emptyList()) }

    /**
     * 统一的安装入口：把 provisioner 的进度回调接到 Compose 状态上。
     * 并发点击无效（installLabel 非空时直接返回），避免两个安装任务互相覆盖进度。
     */
    fun startInstall(label: String, block: suspend (ProgressListener) -> ReverseToolProvisioner.Result) {
        if (installLabel != null) return
        installLabel = label
        installPercent = 0
        installLog = listOf("开始：$label")
        scope.launch {
            val result = runCatching {
                block { percent, message ->
                    installPercent = percent
                    installLog = (installLog + message).takeLast(60)
                }
            }.getOrElse { ReverseToolProvisioner.Result(false, "安装异常：${it.message ?: it.javaClass.simpleName}") }
            output = output + result.message
            installLog = (installLog + (if (result.success) "✔ " else "✘ ") + result.message).takeLast(60)
            installPercent = 100
            installLabel = null
            status = engine.toolStatus()
        }
    }

    // 网络受限时的本地兜底：直接用用户已下载好的 jadx 发行 zip / 单个 jar 安装，不再依赖联网。
    // 这台设备上 GitHub 直连断流是常态，「下载失败」把用户彻底堵死过。
    val jadxLocalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = runCatching { stageToolFile(context, uri) }.getOrNull()
            val result = if (staged == null) {
                ReverseToolProvisioner.Result(false, "无法读取所选文件")
            } else {
                provisioner.installJadxFromLocal(staged)
            }
            output = output + result.message
            status = engine.toolStatus()
        }
    }
    val apktoolLocalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val staged = runCatching { stageToolFile(context, uri) }.getOrNull()
            val result = if (staged == null) {
                ReverseToolProvisioner.Result(false, "无法读取所选文件")
            } else {
                provisioner.installApktoolFromLocal(staged)
            }
            output = output + result.message
            status = engine.toolStatus()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val input = copyUriToCache(context, uri)
                selectedApk = input
                report = engine.inspect(input)
                output = listOf("已读取 APK：${input.name}")
            }.onFailure {
                output = listOf("读取失败：${it.message ?: "未知错误"}")
            }
        }
    }

    if (showCompliance && !consent) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.reverse_compliance_title)) },
            text = { Text(stringResource(R.string.reverse_compliance_text)) },
            confirmButton = {
                TextButton(onClick = { consent = true }) {
                    Text(stringResource(R.string.reverse_compliance_confirm))
                }
            }
        )
        return
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.reverse_apk_title)) }) }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "*/*")) }) {
                    Text(stringResource(R.string.reverse_select_apk))
                }
                OutlinedButton(onClick = { status = engine.toolStatus() }) {
                    Text(stringResource(R.string.reverse_refresh_tools))
                }
            }

            ToolStatusCard(
                status = status,
                onInstallApktool = {
                    startInstall("安装 apktool") { progress -> provisioner.installApktool(progress) }
                },
                onInstallJadx = {
                    startInstall("安装 JADX") { progress -> provisioner.installJadx(progress) }
                },
                // apksigner 随 Android build-tools 提供，不在逆向工具目录里：
                // 缺它时直接把用户接到既有工具链安装流程（后台任务，可在「工具链」页看进度）。
                onInstallBuildTools = {
                    val task = ToolchainManager(context).enqueueInstall(ToolchainComponent.BUILD_TOOLS)
                    output = output + "已提交后台任务：安装 Android Build Tools（apksigner 随 build-tools 提供），任务号 $task"
                },
                onPickLocalJadx = { jadxLocalPicker.launch(arrayOf("application/zip", "application/java-archive", "*/*")) },
                onPickLocalApktool = { apktoolLocalPicker.launch(arrayOf("application/java-archive", "application/zip", "*/*")) },
                installing = installLabel,
                installPercent = installPercent,
                installLog = installLog,
                onDismissLog = { installLog = emptyList() }
            )

            selectedApk?.let { apk ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(apk.name, style = MaterialTheme.typography.titleMedium)
                        Text(report?.let { "SHA-256：${it.sha256}" } ?: "")
                        report?.let { r ->
                            Text("大小：${formatSize(r.size)} · DEX：${r.dexCount} · ZIP 条目：${r.entries.size}")
                            Text("Manifest：${if (r.hasManifest) "已发现" else "缺失"} · resources.arsc：${if (r.hasResources) "已发现" else "缺失"}")
                            Text(stringResource(R.string.reverse_apk_counts, r.nativeLibraries.size, r.assets.size, r.resources.size, r.urls.size))
                            ManifestSummary(r.manifestSummary)
                            if (r.dexFiles.isNotEmpty()) {
                                Text(stringResource(R.string.reverse_apk_dex_analysis), style = MaterialTheme.typography.titleMedium)
                                r.dexFiles.forEach { d -> Text(stringResource(R.string.reverse_apk_dex_summary, d.fileName, d.version, d.stringCount, d.typeCount, d.methodCount, d.classCount), style = MaterialTheme.typography.bodySmall) }
                            }
                            r.signingCertificates.take(3).forEach { c -> Text(stringResource(R.string.reverse_apk_signing, c.sha256, c.subject), style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                            r.urls.take(20).forEach { u -> Text(u, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = report != null && !running, onClick = {
                        val r = report ?: return@OutlinedButton
                        scope.launch { runCatching { ai.analyzeApk(r) }.onSuccess { aiResult = it }.onFailure { aiResult = "AI 分析失败：${it.message}" } }
                    }) { Text("AI 分析 APK") }
                    Button(
                        enabled = !running && status.readyForDecompile,
                        onClick = {
                            running = true
                            output = emptyList()
                            scope.launch {
                                runCatching {
                                    engine.decompileResources(apk).collect { event ->
                                        when (event) {
                                            is ApkReverseEngine.Event.Output -> output = output + event.text
                                            is ApkReverseEngine.Event.Finished -> {
                                                output = output + "apktool 结束，退出码：${event.exitCode}"
                                                if (event.exitCode == 0) editableFiles = engine.listEditableFiles(engine.workspaceFor(apk).resolve("apktool"))
                                                running = false
                                            }
                                        }
                                    }
                                }.onFailure {
                                    output = output + "执行失败：${it.message ?: "未知错误"}"
                                    running = false
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.reverse_decompile)) }

                    Button(
                        enabled = !running && status.readyForJava,
                        onClick = {
                            running = true
                            output = emptyList()
                            scope.launch {
                                runCatching {
                                    engine.generateJava(apk).collect { event ->
                                        when (event) {
                                            is ApkReverseEngine.Event.Output -> output = output + event.text
                                            is ApkReverseEngine.Event.Finished -> {
                                                output = output + "JADX 结束，退出码：${event.exitCode}"
                                                running = false
                                            }
                                        }
                                    }
                                }.onFailure {
                                    output = output + "执行失败：${it.message ?: "未知错误"}"
                                    running = false
                                }
                            }
                        }
                    ) { Text(stringResource(R.string.reverse_jadx)) }
                }

                val decoded = engine.workspaceFor(apk).resolve("apktool")
                if (decoded.isDirectory) {
                    if (editableFiles.isEmpty()) {
                        editableFiles = engine.listEditableFiles(decoded)
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(stringResource(R.string.reverse_modify_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.reverse_modify_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    editableFiles = engine.listEditableFiles(decoded)
                                }) { Text(stringResource(R.string.reverse_modify_refresh)) }
                                OutlinedButton(
                                    enabled = selectedEditableFile != null && File(
                                        selectedEditableFile!!.parentFile,
                                        selectedEditableFile!!.name + ".nebulaforge.bak"
                                    ).isFile,
                                    onClick = {
                                        runCatching {
                                            engine.restoreEditableBackup(selectedEditableFile!!)
                                            editorText = engine.readEditableFile(decoded, selectedEditableFile!!)
                                            editorDirty = false
                                            editorMessage = context.getString(R.string.reverse_modify_restored)
                                        }.onFailure { editorMessage = context.getString(R.string.reverse_modify_restore_failed, it.message ?: "未知错误") }
                                    }
                                ) { Text(stringResource(R.string.reverse_modify_restore)) }
                            }
                            LazyColumn(
                                Modifier.fillMaxWidth().height(150.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(editableFiles, key = { it.absolutePath }) { file ->
                                    TextButton(onClick = {
                                        runCatching {
                                            editorText = engine.readEditableFile(decoded, file)
                                            selectedEditableFile = file
                                            editorDirty = false
                                            editorMessage = null
                                        }.onFailure { editorMessage = context.getString(R.string.reverse_modify_open_failed, it.message ?: "未知错误") }
                                    }) {
                                        Text(file.relativeTo(decoded).path, maxLines = 1)
                                    }
                                }
                            }
                            selectedEditableFile?.let { file ->
                                Text(
                                    "正在编辑：${file.relativeTo(decoded).path}${if (editorDirty) " *" else ""}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                                OutlinedTextField(
                                    value = editorText,
                                    onValueChange = { editorText = it; editorDirty = true },
                                    modifier = Modifier.fillMaxWidth().height(300.dp),
                                    singleLine = false,
                                    label = { Text(stringResource(R.string.reverse_modify_content)) }
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = editorDirty,
                                        onClick = {
                                            runCatching {
                                                engine.writeEditableFile(decoded, file, editorText)
                                                editorDirty = false
                                                editableFiles = engine.listEditableFiles(decoded)
                                                editorMessage = context.getString(R.string.reverse_modify_saved)
                                            }.onFailure { editorMessage = context.getString(R.string.reverse_modify_save_failed, it.message ?: "未知错误") }
                                        }
                                    ) { Text(stringResource(R.string.reverse_modify_save)) }
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                editorText = engine.readEditableFile(decoded, file)
                                                editorDirty = false
                                                editorMessage = context.getString(R.string.reverse_modify_reloaded)
                                            }.onFailure { editorMessage = context.getString(R.string.reverse_modify_reload_failed, it.message ?: "未知错误") }
                                        }
                                    ) { Text(stringResource(R.string.reverse_modify_reload)) }
                                }
                                editorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }

                aiResult?.let { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("AI 逆向分析", style = MaterialTheme.typography.titleMedium); Text(it) } } }

                OutlinedButton(
                    enabled = !running && decoded.isDirectory && status.readyForBuild,
                    onClick = {
                        running = true
                        val outApk = engine.workspaceFor(apk).resolve("${apk.nameWithoutExtension}-signed.apk")
                        output = emptyList()
                        scope.launch {
                            runCatching {
                                engine.rebuildAndSign(decoded, outApk).collect { event ->
                                    when (event) {
                                        is ApkReverseEngine.Event.Output -> output = output + event.text
                                        is ApkReverseEngine.Event.Finished -> {
                                            output = output + if (event.exitCode == 0)
                                                "回编译并调试签名完成：${outApk.absolutePath}"
                                            else
                                                "回编译/签名失败，退出码：${event.exitCode}"
                                            running = false
                                        }
                                    }
                                }
                            }.onFailure {
                                output = output + "执行失败：${it.message ?: "未知错误"}"
                                running = false
                            }
                        }
                    }
                ) { Text(stringResource(R.string.reverse_rebuild_sign)) }
            }

            Divider()
            Text(stringResource(R.string.reverse_output_title), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(output) { line -> Text(line, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}

@Composable
private fun ToolStatusCard(
    status: ApkReverseEngine.ToolStatus,
    onInstallApktool: () -> Unit,
    onInstallJadx: () -> Unit,
    onInstallBuildTools: () -> Unit,
    onPickLocalJadx: () -> Unit,
    onPickLocalApktool: () -> Unit,
    installing: String?,
    installPercent: Int,
    installLog: List<String>,
    onDismissLog: () -> Unit
) {
    val busy = installing != null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.reverse_tools_title), style = MaterialTheme.typography.titleMedium)
            Text("JDK 17：${if (status.readyForAnalysis) "已就绪" else "未就绪"}")
            Text("apktool：${if (status.apktoolJar != null) "已安装" else "未安装"}")
            Text("JADX：${if (status.jadxJar != null) "已安装" else "未安装"}")
            Text("apksigner：${if (status.apksigner != null) "已发现" else "未发现"}")

            // —— 安装进度：进度条 + 实时阶段/日志。安装期间按钮整体禁用，避免并发任务互相覆盖进度。
            if (busy) {
                val phase = installLog.lastOrNull().orEmpty()
                Text("正在$installing …", style = MaterialTheme.typography.labelLarge)
                if (installPercent > 0) {
                    LinearProgressIndicator(
                        progress = { installPercent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("$installPercent%  ·  ${phase.ifBlank { "处理中…" }}", style = MaterialTheme.typography.bodySmall)
                } else {
                    // -1 / 0 表示「还不知道总量」（apt 安装、等待镜像响应），用不确定进度条而不是卡在 0%。
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(phase.ifBlank { "正在与源建立连接…" }, style = MaterialTheme.typography.bodySmall)
                }
            }

            // 一行最多两个按钮：三个会挤出屏幕，所以拆成两行。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.apktoolJar == null) OutlinedButton(onClick = onInstallApktool, enabled = !busy) { Text(stringResource(R.string.reverse_install_apktool)) }
                if (status.jadxJar == null) OutlinedButton(onClick = onInstallJadx, enabled = !busy) { Text(stringResource(R.string.reverse_install_jadx)) }
            }
            if (status.apksigner == null) {
                OutlinedButton(onClick = onInstallBuildTools, enabled = !busy) { Text(stringResource(R.string.reverse_install_build_tools)) }
            }
            // 下载不通时的本地兜底：用户手里已有 zip / jar 也能装。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.jadxJar == null) TextButton(onClick = onPickLocalJadx, enabled = !busy) { Text(stringResource(R.string.reverse_install_local, "JADX")) }
                if (status.apktoolJar == null) TextButton(onClick = onPickLocalApktool, enabled = !busy) { Text(stringResource(R.string.reverse_install_local, "apktool")) }
            }
            if (!status.readyForDecompile || !status.readyForJava) {
                Text(
                    stringResource(R.string.reverse_tools_hint, status.toolsDir.absolutePath),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 安装结束后保留日志（含失败原因与各镜像的回退过程），用户可自行复制排查。
            if (!busy && installLog.isNotEmpty()) {
                Divider(Modifier.padding(vertical = 4.dp))
                Text("最近安装日志", style = MaterialTheme.typography.labelLarge)
                installLog.takeLast(14).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismissLog) { Text("关闭日志") }
            }
        }
    }
}

@Composable
private fun ManifestSummary(summary: ApkReverseEngine.ManifestSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        summary.packageName?.let { Text("包名：$it") }
        summary.versionName?.let { Text("版本名：$it") }
        summary.versionCode?.let { Text("版本号：$it") }
        if (summary.permissions.isNotEmpty()) Text("权限：${summary.permissions.joinToString("、")}")
        if (summary.activities.isNotEmpty()) Text("Activity：${summary.activities.size} 个")
        if (summary.services.isNotEmpty()) Text("Service：${summary.services.size} 个")
        if (summary.receivers.isNotEmpty()) Text("Receiver：${summary.receivers.size} 个")
        if (summary.providers.isNotEmpty()) Text("Provider：${summary.providers.size} 个")
    }
}

/** 把 SAF 选中的工具文件落到 cache 再交给安装器：安装器只接受普通文件路径。 */
private fun stageToolFile(context: Context, uri: Uri): File {
    val dir = File(context.cacheDir, "reverse-tools")
    dir.mkdirs()
    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        ?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        ?: "tool-${System.currentTimeMillis()}.bin"
    val target = File(dir, name)
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法打开所选文件" }
        target.outputStream().use { output -> input.copyTo(output) }
    }
    return target
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    val dir = File(context.cacheDir, "reverse-apk")
    dir.mkdirs()
    val target = File(dir, "selected-${System.currentTimeMillis()}.apk")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法打开所选文件" }
        target.outputStream().use { output -> input.copyTo(output) }
    }
    return target
}

private fun formatSize(size: Long): String = when {
    size >= 1024 * 1024 -> "%.1f MB".format(size / 1024.0 / 1024.0)
    size >= 1024 -> "%.1f KB".format(size / 1024.0).replace(".0 KB", " KB")
    else -> "$size B"
}
