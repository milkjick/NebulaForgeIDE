package com.nebulaforge.app.plugins

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件诊断面板：把「插件到底怎么了」一次说清楚。
 *
 * 解决的问题：
 *  - 用户点了插件命令没反应，却看不到原因（是被激活失败拦住了？还是命令没注册？还是扩展内部抛错？）；
 *  - 扩展宿主曾经「一异常就整进程退出」，用户体感是闪退，并且没有任何可查的证据；
 *  - 兼容层为了不闪退会对未实现的 API 降级，但降级必须**可见**，否则就是把缺陷伪装成正常。
 *
 * 因此这里集中展示：插件激活状态与失败原因、兼容层降级缺口、被隔离的未捕获异常、
 * WebView 注册情况，以及宿主与各扩展的实时日志（可长按选中复制）。
 */
@Composable
fun PluginDiagnosticsDialog(
    host: JsExtensionHost,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val states by host.states.collectAsState()
    val logs by host.logs.collectAsState()
    val gaps by host.apiGaps.collectAsState()
    val crashes by host.isolatedCrashes.collectAsState()
    val webviews by host.webviews.collectAsState()
    val builtinGaps by host.builtinCommands.gaps.collectAsState()
    // 能力网关状态：目录（能调什么）、授权（谁被允许了）、审计（谁真的调过）。
    // 这三份数据是「AI/插件到底动了什么」的唯一可信来源，所以直接挂在诊断面板上给用户看。
    val caps by host.capabilityBridge.capabilities.collectAsState()
    val capGrants by host.capabilityBridge.grants.collectAsState()
    val capAudit by host.capabilityBridge.audit.collectAsState()

    var logFilter by remember { mutableStateOf("") }
    var restarting by remember { mutableStateOf(false) }

    val filteredLogs = remember(logs, logFilter) {
        val q = logFilter.trim()
        val source = if (q.isEmpty()) logs else logs.filter {
            it.pluginId.contains(q, ignoreCase = true) || it.text.contains(q, ignoreCase = true)
        }
        source.takeLast(120)
    }
    val failed = states.filter { !it.activationError.isNullOrBlank() }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插件诊断") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${states.size} 个插件 · 失败 ${failed.size} · 降级 ${gaps.size} 项 · 异常 ${crashes.size} 次 · " +
                            "能力 ${caps.size} 项 / 授权 ${capGrants.size} 条 / 审计 ${capAudit.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    if (restarting) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = {
                            restarting = true
                            scope.launch {
                                // 重启会重拉扩展进程，必须离开主线程；否则界面卡死，用户又以为是闪退。
                                withContext(Dispatchers.IO) { runCatching { host.refresh() } }
                                restarting = false
                            }
                        }) { Text("重启宿主", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = logFilter,
                    onValueChange = { logFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("过滤日志（插件 ID 或关键字）") }
                )
                Spacer(Modifier.height(6.dp))
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    // ---------------------------------------------------------- 插件状态
                    item { SectionTitle("插件状态") }
                    items(states, key = { "state-" + it.pluginId }) { state ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(
                                "${state.displayName}  ${state.version}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "状态：${statusText(state)} · 命令 ${state.commands.size} · " +
                                    "能力 ${state.providers.size} · ${state.pluginId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            state.activationError?.takeIf { it.isNotBlank() }?.let { err ->
                                Text(
                                    "激活失败：$err",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // ---------------------------------------------------------- 兼容层缺口
                    if (gaps.isNotEmpty() || builtinGaps.isNotEmpty()) {
                        item { SectionTitle("兼容层降级（功能会退化为无操作）") }
                        item {
                            Text(
                                "这些 API 宿主尚未实现。扩展不会因此崩溃，但对应功能不会真正生效；" +
                                    "把它列出来是为了让缺口可见，而不是伪装成正常。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(gaps, key = { "gap-" + it }) { api ->
                            Text("· $api", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                        items(builtinGaps, key = { "bgan-" + it }) { cmd ->
                            Text(
                                "· 内置命令 $cmd（已识别、无动作）",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // ---------------------------------------------------------- 隔离异常
                    if (crashes.isNotEmpty()) {
                        item { SectionTitle("已隔离的扩展异常（宿主保持运行）") }
                        items(crashes.reversed().take(10), key = { "crash-" + it.first + "-" + it.second.hashCode() }) { (pluginId, stack) ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(pluginId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                Text(stack, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }

                    // ------------------------------------------------- 能力授权与审计
                    item { SectionTitle("能力网关（受授权 + 可审计）") }
                    item {
                        Text(
                            "插件、AI Agent、外部网关调用宿主功能（读写文件 / 跑终端 / 调模型 / 通知）都走这一条门；" +
                                "写入、执行、模型调用在调用时请你授权，答「始终允许」才会记住。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    items(caps, key = { "cap-" + it.id }) { def ->
                        val granted = capGrants.entries.filter { it.key.endsWith("|" + def.id) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                "${def.id}（${def.risk.label}）",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(def.title + " · " + def.detail, style = MaterialTheme.typography.labelSmall)
                            Text(
                                "参数：${def.arguments}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                when {
                                    granted.isEmpty() && def.risk == CapRisk.READ -> "授权：默认放行（只读）"
                                    granted.isEmpty() -> "授权：每次询问"
                                    else -> "授权：" + granted.joinToString("、") { (k, v) ->
                                        k.substringBefore("|") + "=" + when (v) {
                                            GrantDecision.ALLOW -> "已允许"
                                            GrantDecision.DENY -> "已拒绝"
                                            GrantDecision.ASK -> "每次询问"
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "最近调用审计",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            // 撤销要真的能撤销：AI 一旦被「始终允许」，用户必须有地方收回。
                            TextButton(onClick = { host.revokeCapabilityGrant("agent", null) }) {
                                Text("撤销全部授权", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    item {
                        SelectionContainer {
                            Column {
                                val recent = capAudit.takeLast(20)
                                if (recent.isEmpty()) {
                                    Text(
                                        "（暂无调用记录：没有任何插件或 AI 用过宿主能力）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                recent.forEach { c ->
                                    Text(
                                        "${timeFormat.format(Date(c.at))} ${c.caller} → ${c.capability}｜${c.decision}｜" +
                                            (if (c.ok) "成功" else "失败") + "｜${c.argsPreview}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (c.ok) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    // ---------------------------------------------------------- WebView
                    if (webviews.isNotEmpty()) {
                        item { SectionTitle("已注册 WebView 视图/面板") }
                        items(webviews, key = { "wv-" + it.pluginId + it.id }) { wv ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "· ${wv.id}（${wv.pluginId}，${wv.kind}）",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                                // 视图是"按需解析"的：只有宿主真的要求显示，扩展才会把 html 交上来。
                                if (wv.kind.startsWith("registerWebviewView")) {
                                    TextButton(onClick = { host.openRegisteredView(wv.pluginId, wv.id) }) {
                                        Text("打开界面", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    // ---------------------------------------------------------- 日志
                    item { SectionTitle("最近日志（${filteredLogs.size} 条，可长按复制）") }
                    item {
                        SelectionContainer {
                            Column {
                                filteredLogs.forEach { line ->
                                    Text(
                                        "${timeFormat.format(Date(line.at))} [${line.level}] ${line.pluginId}: ${line.text}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = when (line.level) {
                                            "error" -> MaterialTheme.colorScheme.error
                                            "warn" -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "完整日志落在应用私有目录 .nebulaforge/js-host/extension-host.log。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun SectionTitle(text: String) {
    Box(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

private fun statusText(state: JsExtensionHost.HostState): String = when (state.status) {
    JsExtensionHost.Status.RUNNING -> "运行中"
    JsExtensionHost.Status.STARTING -> "启动中"
    JsExtensionHost.Status.FAILED -> "失败"
    JsExtensionHost.Status.STOPPED -> "未启动"
    JsExtensionHost.Status.NO_NODE -> "缺少 Node 运行时"
}
