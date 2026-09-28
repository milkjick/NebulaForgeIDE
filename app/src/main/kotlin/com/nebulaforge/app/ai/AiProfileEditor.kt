package com.nebulaforge.app.ai

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nebulaforge.core.agent.AiProtocol
import com.nebulaforge.core.agent.AiProviderSettings
import com.nebulaforge.core.agent.formatKeyValueLines
import com.nebulaforge.core.agent.parseKeyValueLines
import com.nebulaforge.core.agent.toProviderConfig
import com.nebulaforge.core.aiprovider.AiDiagnostics
import kotlinx.coroutines.launch

/**
 * 单套 AI 配置的全屏编辑器（新增 / 编辑共用）。
 *
 * 设计约束：不使用弹窗承载表单（项目 UI 规范：向导/表单用全屏路由页，工具窗才用 ModalBottomSheet）。
 * 安全约束：API Key 仅写入应用私有存储；界面默认掩码显示；连接测试的错误信息不回显 Key。
 */
@Composable
fun AiProfileEditor(
    profile: AiProviderSettings,
    isActive: Boolean,
    onSave: (AiProviderSettings) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // 小众/中转兼容：折叠区默认收起，避免日常配置被高级项干扰。
    var showAdvanced by remember(profile.id) { mutableStateOf(false) }
    var headersText by remember(profile.id) { mutableStateOf(formatKeyValueLines(profile.extraHeaders)) }
    var queryText by remember(profile.id) { mutableStateOf(formatKeyValueLines(profile.extraQuery)) }
    var discoveredModels by remember(profile.id) { mutableStateOf<List<String>>(emptyList()) }
    var discoveringModels by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (profile.apiKey.isBlank()) "新增 AI 配置" else "编辑 AI 配置", style = MaterialTheme.typography.headlineSmall)
                if (isActive) Text("当前生效配置", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onCancel) { Text("取消") }
        }

        Text("配置名称", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("名称") },
            supportingText = { Text("用于区分多套配置，例如「DeepSeek 主力」「本地 Ollama 离线」") }
        )

        Text("服务商协议", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProtocol.entries.forEach { p ->
                FilterChip(
                    selected = draft.protocol == p,
                    onClick = {
                        // 切换协议时把地址/模型换成该协议的默认值，避免残留上一个协议的不兼容端点
                        draft = draft.copy(protocol = p, baseUrl = p.defaultBaseUrl, model = p.defaultModel)
                        result = null
                    },
                    label = { Text(p.label) }
                )
            }
        }

        val presets = presetsFor(draft.protocol)
        if (presets.isNotEmpty()) {
            Text("快速预设", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { preset ->
                            OutlinedButton(
                                onClick = {
                                    draft = draft.copy(baseUrl = preset.baseUrl, model = preset.model)
                                    result = "已载入预设：${preset.label}，请填写 API Key"
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text(preset.label) }
                        }
                        if (row.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }
        }

        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { draft = draft.copy(baseUrl = it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Base URL") },
            supportingText = {
                Text(
                    when (draft.protocol) {
                        AiProtocol.ANTHROPIC -> "Anthropic Messages 端点，例如 https://api.anthropic.com/v1/messages"
                        AiProtocol.GEMINI -> "Gemini 端点前缀，例如 https://generativelanguage.googleapis.com/v1beta"
                        AiProtocol.OPENAI_COMPATIBLE -> "OpenAI 兼容端点，例如 https://api.openai.com/v1 或本地 http://127.0.0.1:11434/v1"
                    }
                )
            }
        )

        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { draft = draft.copy(apiKey = it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("API Key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } },
            supportingText = { Text("只保存在本机应用私有存储，不会写入项目文件或日志") }
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("对话模型") }
            )
            Column {
                OutlinedButton(
                    enabled = !discoveringModels && draft.apiKey.isNotBlank() && draft.baseUrl.isNotBlank(),
                    onClick = {
                        scope.launch {
                            discoveringModels = true
                            result = "正在读取模型列表…"
                            val found = AiDiagnostics.discoverModels(draft.copy(enabled = true).toProviderConfig())
                            found.onSuccess {
                                discoveredModels = it
                                result = "已发现 ${it.size} 个模型，请选择或直接编辑模型名。"
                                modelMenuOpen = it.isNotEmpty()
                            }.onFailure {
                                result = "模型列表获取失败：${it.message?.take(160).orEmpty()}"
                            }
                            discoveringModels = false
                        }
                    }
                ) { Text(if (discoveringModels) "读取中…" else "获取模型") }
                DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                    discoveredModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = { draft = draft.copy(model = model); modelMenuOpen = false }
                        )
                    }
                }
            }
        }
        if (discoveredModels.isNotEmpty()) {
            Text("最近获取：${discoveredModels.size} 个模型；下拉选择后仍可手动修改。", style = MaterialTheme.typography.labelMedium)
        }

        OutlinedTextField(
            value = draft.embeddingModel,
            onValueChange = { draft = draft.copy(embeddingModel = it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("嵌入模型（向量检索/经验库）") },
            supportingText = {
                Text(
                    if (draft.protocol == AiProtocol.OPENAI_COMPATIBLE) "留空则关闭向量检索；仅在协议为 OpenAI 兼容时可用"
                    else "当前协议不支持 /embeddings，向量检索会自动跳过"
                )
            }
        )

        HorizontalDivider()
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "▾ 高级设置（小众 API / 中转兼容）" else "▸ 高级设置（小众 API / 中转兼容）")
        }
        if (showAdvanced) {
            Text(
                "官方之外的第三方 API 与中转/聚合网关往往有差异：鉴权头名不同、端点路径非标准、" +
                    "或不认 `stream_options` 等参数。下面的开关用来逐项对齐你的服务商。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = draft.authHeader,
                onValueChange = { draft = draft.copy(authHeader = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("鉴权头名（留空用协议默认）") },
                supportingText = { Text("当前协议默认：${defaultAuthHeaderName(draft.protocol)}") }
            )
            OutlinedTextField(
                value = draft.authPrefix ?: defaultAuthPrefix(draft.protocol),
                onValueChange = { draft = draft.copy(authPrefix = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("鉴权前缀") },
                supportingText = { Text("例如 \"Bearer \"；网关直接收裸 Key 时清空此项") }
            )
            OutlinedTextField(
                value = draft.chatPath,
                onValueChange = { draft = draft.copy(chatPath = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("对话端点路径覆盖（可留空）") },
                supportingText = { Text("留空 = baseUrl + /chat/completions；可填 /v1/openai/chat/completions 或完整 URL") }
            )
            OutlinedTextField(
                value = headersText,
                onValueChange = {
                    headersText = it
                    draft = draft.copy(extraHeaders = parseKeyValueLines(it))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("自定义请求头（每行 `键: 值`）") },
                supportingText = { Text("例如 X-Channel: default；网关要 `api-key` 时也可在此直接写") },
                minLines = 2
            )
            OutlinedTextField(
                value = queryText,
                onValueChange = {
                    queryText = it
                    draft = draft.copy(extraQuery = parseKeyValueLines(it))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("额外查询参数（每行 `键: 值`）") },
                supportingText = { Text("例如 api-version: 2024-05-01") },
                minLines = 2
            )
            OutlinedTextField(
                value = draft.balancePath,
                onValueChange = { draft = draft.copy(balancePath = it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("余额接口路径（可留空）") },
                supportingText = { Text("中转/自建网关可填 /api/user/self 等；留空时只识别官方服务商") }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = draft.sendStreamOptions, onCheckedChange = { draft = draft.copy(sendStreamOptions = it) })
                Column(Modifier.padding(start = 8.dp)) {
                    Text("流式请求携带 stream_options", style = MaterialTheme.typography.bodyMedium)
                    Text("网关报 400 / 不识别参数时可关闭；系统遇到 400 也会自动去掉后重试", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
            Column(Modifier.padding(start = 8.dp)) {
                Text("启用此配置", style = MaterialTheme.typography.bodyMedium)
                Text("关闭后 Agent 调用此配置会被拒绝", style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = draft.autoFixOnBuildFailure, onCheckedChange = { draft = draft.copy(autoFixOnBuildFailure = it) })
            Column(Modifier.padding(start = 8.dp)) {
                Text("构建失败时自动进入 AI 修复", style = MaterialTheme.typography.bodyMedium)
                Text("仅对当前生效配置生效", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (draft.baseUrl.trim().startsWith("http://", true)) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "当前使用明文 HTTP 端点：仅建议用于本机或可信局域网服务，公网传输可能泄露 Key。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = draft.name.isNotBlank(), onClick = { onSave(draft) }) { Text("保存") }
            OutlinedButton(
                enabled = !testing && draft.apiKey.isNotBlank() && draft.model.isNotBlank(),
                onClick = {
                    scope.launch {
                        testing = true
                        result = "正在测速（真实发起一次对话请求）…"
                        val probe = draft.copy(enabled = true).toProviderConfig()
                        val speed = AiDiagnostics.speedTest(probe)
                        val balance = AiDiagnostics.balance(probe)
                        result = buildString {
                            if (speed.ok) {
                                append("连接成功 · 首字节 ${speed.firstByteMs}ms · 完整响应 ${speed.totalMs}ms")
                            } else {
                                append("连接失败：${speed.error}")
                            }
                            append(" · 余额：")
                            append(balance.display)
                        }
                        testing = false
                    }
                }
            ) { Text(if (testing) "测试中…" else "测试连接") }

            if (onDelete != null) {
                TextButton(onClick = { confirmDelete = true }) { Text("删除此配置", color = MaterialTheme.colorScheme.error) }
            }
        }

        if (draft.embeddingModel.isBlank() && draft.protocol == AiProtocol.OPENAI_COMPATIBLE) {
            Text("提示：嵌入模型为空时，项目记忆的向量检索会退化为关键词检索。", style = MaterialTheme.typography.labelMedium)
        }
        result?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Normal)
        }
    }

    if (confirmDelete && onDelete != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除配置") },
            text = { Text("确定删除「${profile.displayName()}」吗？该配置的 API Key 会一并从本机移除，此操作不可撤销。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

/** 协议默认鉴权头名（与 core-ai-provider 各 Provider 实现保持一致，仅用于界面提示）。 */
fun defaultAuthHeaderName(protocol: AiProtocol): String = when (protocol) {
    AiProtocol.ANTHROPIC -> "x-api-key"
    AiProtocol.GEMINI -> "x-goog-api-key（不填则走 ?key=）"
    AiProtocol.OPENAI_COMPATIBLE -> "Authorization"
}

/** 协议默认鉴权前缀；仅用于界面回填与提示。 */
fun defaultAuthPrefix(protocol: AiProtocol): String = when (protocol) {
    AiProtocol.OPENAI_COMPATIBLE -> "Bearer "
    else -> ""
}

data class AiProviderPreset(val label: String, val baseUrl: String, val model: String)

/** 按协议给出预设端点；预设只填地址与模型，Key 始终由用户自己填写。 */
fun presetsFor(protocol: AiProtocol): List<AiProviderPreset> = when (protocol) {
    AiProtocol.ANTHROPIC -> listOf(
        AiProviderPreset("Claude 官方", "https://api.anthropic.com/v1/messages", "claude-3-5-sonnet-latest")
    )

    AiProtocol.GEMINI -> listOf(
        AiProviderPreset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-1.5-flash")
    )

    AiProtocol.OPENAI_COMPATIBLE -> listOf(
        AiProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
        AiProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        AiProviderPreset("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        AiProviderPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        AiProviderPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        AiProviderPreset("本地 Ollama", "http://127.0.0.1:11434/v1", "llama3.1")
    )
}
