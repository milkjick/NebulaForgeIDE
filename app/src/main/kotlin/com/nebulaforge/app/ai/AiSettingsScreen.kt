package com.nebulaforge.app.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nebulaforge.core.agent.ActiveAiProfile
import com.nebulaforge.core.agent.AiProtocol
import com.nebulaforge.core.agent.AiProviderSettings
import com.nebulaforge.core.agent.AiProviderSettingsStore
import com.nebulaforge.core.agent.toProviderConfig
import com.nebulaforge.core.aiprovider.AiDiagnostics
import kotlinx.coroutines.launch

/**
 * AI 配置管理页（多配置版）。
 *
 * 2.0 变更：从"只能配置一套服务商"升级为"多套配置并存 + 随时切换当前生效"。
 *  - 列表：每套配置显示名称/协议/端点/模型/可用状态，可启用（切换）、编辑、复制、删除；
 *  - 新增：底部抽屉只负责选协议，表单进入全屏编辑器（遵循"向导/表单用全屏页"的 UI 规范）；
 *  - 生效：切换即全局生效（任务面板、逆向助手、构建修复、向量检索都通过当前配置读取）。
 *
 * 安全约束：API Key 只写入应用私有 SharedPreferences；界面仅显示掩码；
 * 连接测试的错误信息不回显 Key（错误信息由 HTTP 状态码与服务端返回体构成）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { AiProviderSettingsStore(context) }

    var profiles by remember { mutableStateOf(store.profiles()) }
    var activeId by remember { mutableStateOf(store.activeId()) }
    var editing by remember { mutableStateOf<AiProviderSettings?>(null) }
    var protocolSheet by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AiProviderSettings?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var diagnosing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    fun reload() {
        profiles = store.profiles()
        activeId = store.activeId()
        ActiveAiProfile.refresh(context)
    }

    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    val currentEditing = editing
    if (currentEditing != null) {
        AiProfileEditor(
            profile = currentEditing,
            isActive = currentEditing.id == activeId,
            onSave = { edited ->
                ActiveAiProfile.publish(context, edited)
                reload()
                editing = null
                message = "已保存「${edited.displayName()}」，当前生效配置已同步更新。"
            },
            onCancel = { editing = null },
            onDelete = {
                ActiveAiProfile.remove(context, currentEditing.id)
                reload()
                editing = null
                message = "已删除「${currentEditing.displayName()}」。"
            }
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AI 配置管理", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onBack) { Text("返回") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("当前生效 AI", style = MaterialTheme.typography.titleMedium)
                    if (active == null) {
                        Text("尚无任何配置，请先新增。", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            if (active.isUsable()) "状态：已启用，Agent 可调用" else "状态：不可用（未启用或未填 Key/模型），Agent 调用会失败",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (active.isUsable()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text("名称：${active.displayName()}", style = MaterialTheme.typography.bodyMedium)
                        Text("协议：${active.protocol.label}", style = MaterialTheme.typography.bodyMedium)
                        Text("服务地址：${active.normalizedBaseUrl()}", style = MaterialTheme.typography.bodyMedium)
                        Text("对话模型：${active.model.ifBlank { "未填写" }}", style = MaterialTheme.typography.bodyMedium)
                        Text("嵌入模型：${active.embeddingModel.ifBlank { "未启用" }}", style = MaterialTheme.typography.bodyMedium)
                        Text("API Key：${maskKey(active.apiKey)}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { protocolSheet = true }) { Text("新增配置") }
                OutlinedButton(onClick = {
                    if (active != null) editing = active
                }, enabled = active != null) { Text("编辑当前") }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("连接诊断", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "测速会真实发起一次对话请求：首字节 = 从请求发出到收到第一个字的时间；" +
                            "完整响应 = 整段返回结束的总时间。余额只在服务商公开余额接口时显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = active != null && !diagnosing && active.apiKey.isNotBlank(),
                            onClick = {
                                val target = active ?: return@OutlinedButton
                                scope.launch {
                                    diagnosing = true
                                    diagnostics = "测速中…"
                                    val speed = AiDiagnostics.speedTest(target.copy(enabled = true).toProviderConfig())
                                    diagnostics = if (speed.ok) {
                                        "✅ 连通正常 · 首字节 ${speed.firstByteMs}ms · 完整响应 ${speed.totalMs}ms"
                                    } else {
                                        "❌ 连接失败：${speed.error}"
                                    }
                                    diagnosing = false
                                }
                            }
                        ) { Text(if (diagnosing) "测试中…" else "测速") }
                        OutlinedButton(
                            enabled = active != null && !diagnosing && active.apiKey.isNotBlank(),
                            onClick = {
                                val target = active ?: return@OutlinedButton
                                scope.launch {
                                    diagnosing = true
                                    diagnostics = "查询余额…"
                                    val report = AiDiagnostics.balance(target.copy(enabled = true).toProviderConfig())
                                    diagnostics = (if (report.supported) "💰 " else "ℹ️ ") + report.display +
                                        if (report.detail.isNotBlank()) "\n" + report.detail else ""
                                    diagnosing = false
                                }
                            }
                        ) { Text("查询余额") }
                    }
                    diagnostics?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        item {
            Text("已保存配置（${profiles.size} 套）", style = MaterialTheme.typography.titleMedium)
        }

        items(profiles, key = { it.id }) { profile ->
            AiProfileCard(
                profile = profile,
                isActive = profile.id == activeId,
                onActivate = {
                    ActiveAiProfile.switchTo(context, profile.id)
                    reload()
                    message = "已切换到「${profile.displayName()}」，全局立即生效。"
                },
                onEdit = { editing = profile },
                onDuplicate = {
                    val copy = store.duplicate(profile.id)
                    reload()
                    message = if (copy != null) "已复制为「${copy.displayName()}」，可在列表中编辑。" else "复制失败：配置不存在。"
                },
                onDelete = { pendingDelete = profile }
            )
        }

        item {
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        item {
            HorizontalDivider()
        }

        item {
            Text(
                "说明：切换「当前生效」后，任务面板、计划器、构建修复、逆向助手与向量检索会立即改用新配置，" +
                    "无需重启应用。API Key 仅保存在本机应用私有存储中，不写入项目文件或日志。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    if (protocolSheet) {
        ModalBottomSheet(onDismissRequest = { protocolSheet = false }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    "选择服务商协议",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                AiProtocol.entries.forEach { protocol ->
                    ListItem(
                        headlineContent = { Text(protocol.label) },
                        supportingContent = { Text(protocol.defaultBaseUrl, style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.fillMaxWidth().clickableItem {
                            val created = store.create("", protocol)
                            protocolSheet = false
                            reload()
                            editing = created
                        }
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除配置") },
            text = { Text("确定删除「${target.displayName()}」吗？该配置的 API Key 会一并从本机移除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    ActiveAiProfile.remove(context, target.id)
                    pendingDelete = null
                    reload()
                    message = "已删除「${target.displayName()}」。"
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun AiProfileCard(
    profile: AiProviderSettings,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = isActive, onClick = { if (!isActive) onActivate() })
                Column(Modifier.weight(1f)) {
                    Text(profile.displayName(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${profile.protocol.label} · ${profile.normalizedBaseUrl()}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text("对话模型：${profile.model.ifBlank { "未填写" }}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "嵌入模型：${profile.embeddingModel.ifBlank { "未启用" }} · API Key：${maskKey(profile.apiKey)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                when {
                    !profile.enabled -> "已停用：可启用后再使用"
                    !profile.isUsable() -> "不可用：缺少 API Key 或模型"
                    isActive -> "当前生效中"
                    else -> "可用，未生效"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !profile.isUsable() -> MaterialTheme.colorScheme.error
                    isActive -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isActive) TextButton(onClick = onActivate) { Text("设为当前") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDuplicate) { Text("复制") }
                TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

/** 只显示首尾片段，避免在界面上完整暴露密钥。 */
internal fun maskKey(key: String): String {
    val trimmed = key.trim()
    return when {
        trimmed.isEmpty() -> "未填写"
        trimmed.length <= 8 -> "****"
        else -> trimmed.take(4) + "****" + trimmed.takeLast(4)
    }
}

/** 让 ListItem 整体可点击（ListItem 自身不接收 onClick 时才需要）。 */
private fun Modifier.clickableItem(onClick: () -> Unit): Modifier = clickable(onClick = onClick)
