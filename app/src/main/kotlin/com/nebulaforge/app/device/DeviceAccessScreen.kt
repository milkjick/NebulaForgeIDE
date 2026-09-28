package com.nebulaforge.app.device

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.core.device.DeviceChannel
import com.nebulaforge.core.device.DeviceShellGate
import com.nebulaforge.core.device.DeviceShellResult
import com.nebulaforge.core.device.RootAccess
import com.nebulaforge.core.device.ShizukuAccess
import com.nebulaforge.core.device.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备权限与终端通道管理页。
 *
 * 这一页负责三件事，缺一不可：
 *  1. **授权**：申请 Shizuku 权限 / 触发 Root 探测 / 打开 Shizuku 应用；
 *  2. **选择**：确定 AI 与工具窗口实际使用的执行通道（Root、Shizuku、内嵌 bootstrap）；
 *  3. **审计**：查看 AI 执行过的命令记录，并提供手工试跑入口验证通道是否真的可用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAccessScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as NebulaForgeApplication
    val scope = rememberCoroutineScope()
    val prefs = remember { app.deviceAccessPrefs }

    var preferred by remember { mutableStateOf(prefs.preferredChannel()) }
    var aiEnabled by remember { mutableStateOf(prefs.isAiTerminalEnabled()) }
    var shizukuStatus by remember { mutableStateOf(ShizukuAccess.status(context)) }
    var rootAvailable by remember { mutableStateOf(RootAccess.isAvailable()) }
    var embeddedReady by remember { mutableStateOf(DeviceShellGate.hasEmbeddedShell) }
    var effective by remember { mutableStateOf(DeviceShellGate.identity(context)) }
    var command by remember { mutableStateOf("id") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DeviceShellResult?>(null) }
    var audit by remember { mutableStateOf(app.aiTerminal.audit()) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        shizukuStatus = ShizukuAccess.status(context)
        rootAvailable = RootAccess.isAvailable(forceRefresh = true)
        embeddedReady = DeviceShellGate.hasEmbeddedShell
        effective = DeviceShellGate.identity(context)
        audit = app.aiTerminal.audit()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("设备权限与终端通道") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            },
            actions = {
                IconButton(onClick = { refresh(); message = "已重新检测通道状态" }) {
                    Icon(Icons.Default.Refresh, contentDescription = "重新检测")
                }
            }
        )
    }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前生效通道：$effective", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "命令执行身份会真实生效，不会被静默替换成更弱的通道；若指定的通道不可用，执行会直接失败并说明原因。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(DeviceShellGate.statusSummary(context), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Shizuku 授权", style = MaterialTheme.typography.titleMedium)
                        Text("状态：${shizukuStatus.label}", style = MaterialTheme.typography.bodyMedium)
                        ShizukuAccess.execIdentity()?.let { Text("执行身份：$it", style = MaterialTheme.typography.labelSmall) }
                        ShizukuAccess.lastError?.let { Text("最近错误：$it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                        Text(
                            "Shizuku 以 shell(uid=2000) 身份执行命令，可以完成应用自身做不到的设备操作；需要先安装并启动 Shizuku 应用。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !busy && shizukuStatus != ShizukuStatus.READY,
                                onClick = {
                                    val ok = ShizukuAccess.requestPermission()
                                    message = if (ok) "已发起 Shizuku 授权请求，请在弹窗中允许" else "无法发起授权：${ShizukuAccess.lastError ?: "Shizuku 服务未运行"}"
                                }
                            ) { Text("请求授权") }
                            TextButton(onClick = {
                                val opened = runCatching {
                                    context.startActivity(
                                        context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            ?: throw IllegalStateException("未安装 Shizuku")
                                    )
                                    true
                                }.getOrDefault(false)
                                if (!opened) message = "未检测到 Shizuku 应用，请先安装 Shizuku"
                            }) { Text("打开 Shizuku 应用") }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Root", style = MaterialTheme.typography.titleMedium)
                        Text(if (rootAvailable) "su 可用（uid=0）" else "su 不可用（设备未 Root 或未授权本应用）", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { refresh(); message = "Root 探测结果：${if (rootAvailable) "可用" else "不可用"}" }) {
                            Text("重新探测 su")
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("内嵌用户态（bootstrap）", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (embeddedReady) "已就绪：可在应用内置 Termux 环境里执行命令（无设备特权）"
                            else "未就绪：请到「设置 → 内置用户态」完成解压初始化",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("通道选择", style = MaterialTheme.typography.titleMedium)
                        DeviceChannel.entries.filter { it != DeviceChannel.NONE }.forEach { channel ->
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = preferred == channel, onClick = {
                                    preferred = channel
                                    prefs.setPreferredChannel(channel)
                                    effective = DeviceShellGate.identity(context)
                                    message = "已切换首选通道：${channel.label}"
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(channel.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(channel.description, style = MaterialTheme.typography.labelSmall)
                                }
                                if (channel == DeviceChannel.EMBEDDED) {
                                    Text(if (embeddedReady) "可用" else "未就绪", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("允许 AI 使用终端通道", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "开启后，Agent 计划里的「执行终端命令」步骤（需人工批准）会真实执行；破坏性命令仍会被策略拒绝。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(checked = aiEnabled, onCheckedChange = {
                                aiEnabled = it
                                app.aiTerminal.setEnabled(it)
                                message = if (it) "已允许 AI 使用终端通道（${DeviceShellGate.identity(context)}）" else "已禁止 AI 使用终端通道"
                            })
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("通道自测", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("要执行的命令") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false
                        )
                        Button(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                message = null
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        DeviceShellGate.exec(context, command, preferred, 30_000)
                                    }
                                    result = r
                                    busy = false
                                    refresh()
                                }
                            }
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text(if (busy) " 执行中…" else " 在当前通道执行")
                        }
                        result?.let { r ->
                            Text(
                                "通道=${r.level.label} · 退出码=${if (r.timedOut) "超时" else r.exitCode.toString()} · ${r.durationMs}ms",
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (r.stdout.isNotBlank()) Text(r.stdout.take(4000), style = MaterialTheme.typography.bodySmall)
                            if (r.stderr.isNotBlank()) Text(r.stderr.take(2000), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI 命令审计（最近 ${audit.size} 条）", style = MaterialTheme.typography.titleMedium)
                        Text("记录 AI 通过终端通道执行的每条命令及其结果，便于事后核查。", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { app.aiTerminal.clearAudit(); audit = emptyList() }) { Text("清空") }
                }
            }
            if (audit.isEmpty()) {
                item { Text("暂无 AI 命令记录", style = MaterialTheme.typography.bodySmall) }
            }
            items(audit) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${fmt(entry.at)} · ${entry.channel} · exit=${entry.exitCode}",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(entry.command, style = MaterialTheme.typography.bodySmall)
                        if (entry.summary.isNotBlank()) Text(entry.summary, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            message?.let { msg -> item { Text(msg, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

private fun fmt(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(at))
