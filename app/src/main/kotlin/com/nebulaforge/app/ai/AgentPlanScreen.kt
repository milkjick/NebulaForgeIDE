package com.nebulaforge.app.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nebulaforge.app.NebulaForgeApplication

/**
 * 任务面板（独立页）。
 *
 * ## 本轮按参考图做的改造
 * 以前这里是「一堆状态卡 + 一段步骤列表」：AI 现状、终端开关、计划卡、步骤卡分散在四张卡里，
 * 用户看不出「目标是什么、现在跑到哪、还剩几步、历史上跑过什么」。现在首屏就是
 * [AgentTaskCenter] —— 目标 / 执行计划（可勾选步骤）/ 未来任务 / 当前任务 / 历史任务 /
 * 进度与用量，一个面板说清全部状态；配置类信息（当前 AI、终端通道）收拢到下方。
 *
 * 页面职责保持单一：**建立计划 → 批准执行 → 看进度**。AI 配置的增删改仍在设置页。
 */
@Composable
fun AgentPlanScreen(
    app: NebulaForgeApplication,
    onLearningCenter: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {}
) {
    val state by app.agentPlanState.collectAsStateWithLifecycle()
    var request by remember { mutableStateOf("") }
    var approval by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // 当前生效 AI 通过 ActiveAiProfile 全局广播：此处可随时切换并立刻看到结果，
    // 不再需要重启应用或回到设置页刷新。
    val aiSettings by com.nebulaforge.core.agent.ActiveAiProfile.current.collectAsStateWithLifecycle()
    val aiStore = remember { com.nebulaforge.core.agent.AiProviderSettingsStore(context) }
    var aiProfiles by remember { mutableStateOf(aiStore.profiles()) }
    var switcherOpen by remember { mutableStateOf(false) }
    var aiTerminalEnabled by remember { mutableStateOf(app.aiTerminal.isEnabled()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.nebulaforge.core.agent.ActiveAiProfile.refresh(context)
        aiProfiles = aiStore.profiles()
    }
    // 与任务台后端选择（CompletionBackend.resolve）保持一致的可用性判定：
    // 没配在线 API、但已在「网桥」接入网页 AI（AI 聚合网关）时同样算「可用」，
    // 否则这里会误报「AI 尚未配置，模型调用会失败」——用户明明已经把网关接好了。
    var webBridgeReady by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        webBridgeReady = runCatching { WebAiBridge.all(context).any { it.usable } }.getOrDefault(false)
    }
    val aiConfigured = aiSettings.isUsable() || webBridgeReady
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("任务面板", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { aiProfiles = aiStore.profiles(); switcherOpen = true }) { Text("切换 AI") }
            TextButton(onClick = onOpenAiSettings) { Text("AI 设置") }
        }

        // ① 首屏主体：目标 / 执行计划 / 未来·当前·历史任务 / 进度与用量（参考图布局）。
        AgentTaskCenter(app, compact = false, showUsage = true)

        // ② 建立计划：唯一需要用户输入的入口，紧跟在状态之下。
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("描述你要完成的需求，AI 会先生成可逐步执行的计划，再由你确认执行。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(request, { request = it }, Modifier.fillMaxWidth(), label = { Text("告诉 Agent 你要完成什么") }, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = request.isNotBlank() && state.busy.not(), onClick = { app.createAgentPlan(request) }) { Text("建立计划") }
                    if (state.plan != null) TextButton(onClick = { approval = true }) { Text("批准并执行") }
                    TextButton(onClick = onLearningCenter) { Text("学习中心") }
                }
                state.message.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ③ 执行环境：当前 AI + 终端通道（配置类信息，不再抢占首屏）。
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("当前 AI：${aiSettings.displayName()}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${aiSettings.protocol.label} · 模型 ${aiSettings.model.ifBlank { "未填写" }} · " +
                        when {
                            aiSettings.isUsable() -> "可用（共 ${aiProfiles.size} 套配置）"
                            webBridgeReady -> "可用（走 AI 聚合网关：网页 AI）"
                            else -> "不可用（共 ${aiProfiles.size} 套配置）"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (aiConfigured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
                // 终端通道状态：Agent 的 EXECUTE_COMMAND 步骤是否真的会落到底层执行，取决于这一行。
                val shizukuState by app.shizukuState.collectAsStateWithLifecycle()
                val channelLine = remember(shizukuState, aiTerminalEnabled) {
                    app.deviceChannelSummary().lineSequence().firstOrNull().orEmpty()
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("$channelLine · AI 终端命令：${if (aiTerminalEnabled) "已开启" else "已关闭"}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (aiTerminalEnabled) "「执行终端命令」步骤经你批准后会真实执行；破坏性命令仍会被安全策略拒绝。"
                            else "「执行终端命令」步骤会跳过执行。可在右侧开关直接开启，或到设置 → 设备权限中管理。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    androidx.compose.material3.Switch(checked = aiTerminalEnabled, onCheckedChange = {
                        app.aiTerminal.setEnabled(it)
                        aiTerminalEnabled = it
                    })
                }
            }
        }

        if (!aiConfigured) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("AI 尚未配置，模型调用会失败。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenAiSettings) { Text("去配置") }
                }
            }
        }
    }
    if (approval && state.plan != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { approval = false },
            title = { Text("确认执行 AI 计划") },
            text = { Text("Agent 将按上述步骤顺序执行。文件修改仍需要单独经过 Diff 审查，不会因为批准计划而自动接受 AI 修改。") },
            confirmButton = { TextButton(onClick = { approval = false; app.executeAgentPlan() }) { Text("确认执行") } },
            dismissButton = { TextButton(onClick = { approval = false }) { Text("取消") } }
        )
    }
    if (switcherOpen) {
        AiSwitcherSheet(
            profiles = aiProfiles,
            activeId = aiSettings.id,
            onSwitch = { profile ->
                com.nebulaforge.core.agent.ActiveAiProfile.switchTo(context, profile.id)
                aiProfiles = aiStore.profiles()
                switcherOpen = false
            },
            onOpenSettings = { switcherOpen = false; onOpenAiSettings() }
        ) { switcherOpen = false }
    }
}

/**
 * 任务面板内的「切换当前 AI」底部抽屉。
 * 只做选择，不做编辑——新增/编辑/删除统一在 AI 配置管理页完成（单一职责，避免两处表单不一致）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AiSwitcherSheet(
    profiles: List<com.nebulaforge.core.agent.AiProviderSettings>,
    activeId: String,
    onSwitch: (com.nebulaforge.core.agent.AiProviderSettings) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "切换当前 AI",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (profiles.isEmpty()) {
                Text(
                    "尚未配置任何 AI，请前往 AI 设置新增。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            } else {
                profiles.forEach { profile ->
                    androidx.compose.material3.ListItem(
                        headlineContent = { Text(profile.displayName()) },
                        supportingContent = {
                            Text(
                                "${profile.protocol.label} · ${profile.model.ifBlank { "未填写模型" }}" +
                                    if (profile.isUsable()) "" else "（不可用）",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        leadingContent = {
                            androidx.compose.material3.RadioButton(
                                selected = profile.id == activeId,
                                onClick = { onSwitch(profile) }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSwitch(profile) }
                    )
                }
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                TextButton(onClick = onOpenSettings) { Text("管理 AI 配置（新增/编辑/删除）") }
            }
        }
    }
}
