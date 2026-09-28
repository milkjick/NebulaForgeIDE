package com.nebulaforge.app.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件命令面板（VS Code 的「命令面板」在移动端的等价物）。
 *
 * 背景：插件装好、扩展也激活了，但**没有任何地方能触发它的命令** —— 命令只存在于
 * `package.json.contributes.commands` 和扩展内部的注册表里，用户看不到、点不到。
 * 于是「插件激活了现在也无法在 IDE 编辑器上调用」。
 *
 * 本面板把宿主掌握的两类命令一起列出来，并明确标注可执行性：
 *  - 运行时已注册（[JsExtensionHost.CommandEntry.registered] = true）：点下去直接执行；
 *  - 声明了但未注册：仍然列出并可点击，执行时由宿主给出可定位的原因
 *    （扩展激活失败 / 该命令只在特定功能被触发时才注册），而不是静默无反应。
 *
 * 命令执行是异步的（扩展可能去拉语言服务器、做索引），因此面板保持打开并显示进度与结果，
 * 避免「点了没反应」的观感。
 */
@Composable
fun PluginCommandPaletteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val host = remember(context) { JsExtensionHost.of(context) }
    val commands by host.commands.collectAsState()
    val hostStates by host.states.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var runningId by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var argsText by remember { mutableStateOf("[]") }
        var activeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        var showDiagnostics by remember { mutableStateOf(false) }

    // 打开面板即让 `*` / `onStartupFinished` 扩展激活一次：用户刚装完插件、还没重启 IDE 时
    // 也能直接调用命令（与 VS Code 一致的激活时机语义）。
    LaunchedEffect(Unit) {
        runCatching { host.activateStartupExtensions() }
    }

    fun run(id: String) {
        if (runningId != null) return
        runningId = id
        outcome = null
        activeJob = scope.launch {
            val error = runCatching {
                val parsed = org.json.JSONTokener(argsText.trim()).nextValue()
                require(parsed is org.json.JSONArray) { "参数必须是 JSON 数组，例如 [] 或 [\"value\"]" }
                withContext(Dispatchers.IO) { host.executeCommand(id, parsed) }
            }.getOrElse { "命令执行异常：${it.message ?: it.javaClass.simpleName}" }
            outcome = if (error == null) id to true else "$id\n$error" to false
            runningId = null
            activeJob = null
        }
    }

    val filtered = remember(commands, query) {
        val q = query.trim()
        if (q.isEmpty()) commands
        else commands.filter {
            it.id.contains(q, ignoreCase = true) ||
                it.title.contains(q, ignoreCase = true) ||
                it.pluginName.contains(q, ignoreCase = true)
        }
    }
    val failedHosts = remember(hostStates) { hostStates.filter { !it.activationError.isNullOrBlank() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插件命令") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索命令 / 插件") }
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("命令参数（JSON 数组）") },
                    supportingText = { Text("例如 []、[\"value\"] 或 [{\"key\":\"value\"}]") }
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${commands.size} 条命令 · ${commands.count { it.registered }} 条来自运行时注册",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        // 诊断入口：把「为什么这条命令点不动」的原因（激活失败/未注册/扩展内部异常/
                        // 兼容层降级）直接摆到用户面前，而不是让用户自己猜。
                        TextButton(onClick = { showDiagnostics = true }) {
                            Text("诊断", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = {
                            refreshing = true
                            scope.launch {
                                // 宿主重建会重新拉起扩展进程，必须离开主线程，否则界面会被卡住。
                                withContext(Dispatchers.IO) { runCatching { host.refresh() } }
                                refreshing = false
                            }
                        }) { Text("刷新扩展", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                outcome?.let { (text, ok) ->
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .background(
                                if (ok) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.errorContainer
                            )
                            .padding(8.dp)
                    ) {
                        Text(
                            if (ok) "已执行：$text" else text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                if (failedHosts.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    // 激活失败的插件：先在这里说清楚，用户就不会把「命令点不动」当成命令面板的 bug。
                    failedHosts.forEach { state ->
                        Text(
                            "⚠ ${state.displayName} 激活失败：${state.activationError}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                if (filtered.isEmpty()) {
                    Text(
                        if (commands.isEmpty())
                            "没有可用的插件命令。请先在「插件」页安装并启用扩展（扩展需声明 contributes.commands）。"
                        else "没有匹配的命令。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(filtered, key = { it.pluginId + "/" + it.id }) { cmd ->
                            CommandRow(
                                cmd = cmd,
                                running = runningId == cmd.id,
                                busy = runningId != null,
                                onClick = { run(cmd.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (runningId != null) {
                    TextButton(onClick = { activeJob?.cancel() }) { Text("取消") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
    if (showDiagnostics) {
        PluginDiagnosticsDialog(host = host, onDismiss = { showDiagnostics = false })
    }
}

@Composable
private fun CommandRow(
    cmd: JsExtensionHost.CommandEntry,
    running: Boolean,
    busy: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                cmd.title.ifBlank { cmd.id },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cmd.pluginName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    cmd.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (!cmd.registered) {
                Text(
                    "未注册（该扩展尚未注册此命令；点击可查看原因）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}

/** 「运行插件命令」的通用入口：一个文本按钮 + 面板，方便任意界面复用。 */
@Composable
fun PluginCommandButton(
    modifier: Modifier = Modifier,
    label: String = "插件"
) {
    var open by remember { mutableStateOf(false) }
    TextButton(modifier = modifier, onClick = { open = true }) { Text(label) }
    if (open) PluginCommandPaletteDialog(onDismiss = { open = false })
}

/**
 * `⋮` 菜单里的一项：「插件命令…」。
 *
 * 关键：**面板状态由调用方持有**（[onOpen]），这里绝不自己 `remember`。
 * 菜单项在 `DropdownMenu(expanded = false)` 的瞬间就会被移出组合，
 * 它内部的任何 `remember` 都会随之销毁 —— 曾经因此出现「点了菜单项，面板根本不出现」
 * （`open=true` 刚写下就被丢弃，`if (open)` 永远不成立），且没有任何报错可供排查。
 */
@Composable
fun PluginCommandMenuItem(onDismiss: () -> Unit, onOpen: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text("插件命令…") },
        onClick = {
            onDismiss()
            onOpen()
        }
    )
}
