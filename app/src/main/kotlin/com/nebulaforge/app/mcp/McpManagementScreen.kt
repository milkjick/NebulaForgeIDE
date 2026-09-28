package com.nebulaforge.app.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.app.R
import com.nebulaforge.core.mcp.McpServerState
import com.nebulaforge.core.mcp.McpToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * MCP 管理页。
 *
 * 分为两个 Tab：
 *  - 外部服务器：真实可用的「添加 / 编辑 / 删除 / 连接测试 / 查看工具」，配置持久化；
 *  - 内部服务：把 IDE 自身能力通过 JSON-RPC 暴露给 Agent，并提供内部工具调用台。
 *
 * 连接状态直接来自 McpHost.remoteServers()（真实运行时状态），不做乐观伪造。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpManagementScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as NebulaForgeApplication
    val store = remember { McpServerStore(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var servers by remember { mutableStateOf(store.load()) }
    var connected by remember { mutableStateOf(app.mcpHost.remoteServers().toSet()) }
    var toolsByServer by remember { mutableStateOf<Map<String, List<McpToolDefinition>>>(emptyMap()) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var editorOpen by remember { mutableStateOf(false) }

    fun refreshConnected() { connected = app.mcpHost.remoteServers().toSet() }

    // 真实状态来自 McpHost 的探测结果（initialize + tools/list）。
    // 以前把「注册过」当成「已连接」，服务器挂了/URL 写错界面一样显示已连接 —— 这就是
    // 用户说的「MCP 嗅探不到外部服务器状态」。
    val snapshots by app.mcpHost.snapshots.collectAsState()
    fun probeStateOf(id: String) = snapshots.firstOrNull { it.id == id }?.state ?: McpServerState.IDLE
    fun probeDetailOf(id: String) = snapshots.firstOrNull { it.id == id }?.detail
    fun probeToolsOf(id: String) = snapshots.firstOrNull { it.id == id }?.toolCount ?: 0

    /** 探测一个服务器：必要时先建连接对象，再走真实往返；异常一律收敛成 ERROR 状态。 */
    suspend fun probeNow(server: McpServerConfig, announce: Boolean) {
        withContext(Dispatchers.IO) {
            runCatching {
                if (server.id !in app.mcpHost.remoteServers()) {
                    // HTTP / stdio 交给应用统一入口判断，这里不关心传输细节。
                    app.connectExternalMcp(server)
                }
            }
        }
        val snap = runCatching { app.mcpHost.probe(server.id) }.getOrNull()
        refreshConnected()
        if (snap != null && snap.state == McpServerState.READY) {
            toolsByServer = toolsByServer + (server.id to app.mcpHost.remoteToolDefinitions(server.id))
        }
        if (announce || snap?.state == McpServerState.ERROR) {
            message = when (snap?.state) {
                McpServerState.READY -> context.getString(R.string.mcp_ready_with_tools, snap.toolCount)
                McpServerState.ERROR -> context.getString(R.string.mcp_probe_detail, snap.detail ?: "未知原因")
                else -> context.getString(R.string.mcp_connect_failed, "未取得探测结果")
            }
        }
    }

    fun probe(server: McpServerConfig, announce: Boolean = true) {
        scope.launch {
            busyId = server.id
            if (announce) message = context.getString(R.string.mcp_probing_now, server.name)
            probeNow(server, announce)
            busyId = null
        }
    }

    fun connect(server: McpServerConfig) = probe(server, announce = true)

    // 进入页面就把「启用 + 自动连接」的服务器探一遍，状态一进来就是真实的。
    LaunchedEffect(servers.map { it.id + ":" + it.url + ":" + it.enabled + ":" + it.autoConnect }) {
        val targets = servers.filter { it.enabled && it.autoConnect }
        if (targets.isEmpty()) return@LaunchedEffect
        targets.forEach { probeNow(it, announce = false) }
        message = context.getString(R.string.mcp_auto_probe, targets.size)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.mcp_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.mcp_internal_hint), style = MaterialTheme.typography.bodySmall)

        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.mcp_tab_external)) })
            Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.mcp_tab_internal)) })
        }

        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        if (tab == 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = null; editorOpen = true }) { Text(stringResource(R.string.mcp_add_server)) }
                OutlinedButton(
                    enabled = servers.any { it.enabled && it.id !in connected },
                    onClick = { servers.filter { it.enabled && it.id !in connected }.forEach(::connect) }
                ) { Text(stringResource(R.string.mcp_connect_all)) }
                OutlinedButton(
                    enabled = servers.any { it.enabled } && busyId == null,
                    onClick = {
                        val targets = servers.filter { it.enabled }
                        scope.launch {
                            targets.forEach { busyId = it.id; probeNow(it, announce = false) }
                            busyId = null
                            val probed = targets.map { app.mcpHost.stateOf(it.id) }
                            message = context.getString(
                                R.string.mcp_probe_summary,
                                probed.count { it == McpServerState.READY },
                                probed.count { it == McpServerState.ERROR }
                            )
                        }
                    }
                ) { Text(stringResource(R.string.mcp_probe_all)) }
            }

            if (servers.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.mcp_empty_title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.mcp_empty_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.id }) { server ->
                    val pstate = probeStateOf(server.id)
                    val isConnected = pstate == McpServerState.READY
                    val tools = toolsByServer[server.id].orEmpty()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(server.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                AssistChip(
                                    onClick = {},
                                    label = {
                                        Text(
                                            when (pstate) {
                                                McpServerState.READY -> stringResource(R.string.mcp_ready_with_tools, probeToolsOf(server.id))
                                                McpServerState.PROBING -> stringResource(R.string.mcp_state_probing)
                                                McpServerState.ERROR -> stringResource(R.string.mcp_state_error)
                                                McpServerState.INTERNAL -> stringResource(R.string.mcp_state_connected)
                                                McpServerState.IDLE -> stringResource(R.string.mcp_state_idle)
                                            }
                                        )
                                    }
                                )
                            }
                            Text(server.endpointSummary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val transport = snapshots.firstOrNull { it.id == server.id }?.transport
                            if (transport != null) {
                                Text(
                                    stringResource(R.string.mcp_transport_label, transport.label),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            probeDetailOf(server.id)?.let { detail ->
                                Text(
                                    stringResource(R.string.mcp_probe_detail, detail),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (server.note.isNotBlank()) {
                                Text(server.note, style = MaterialTheme.typography.labelSmall)
                            }
                            if (server.isCleartext) {
                                Text(
                                    stringResource(R.string.mcp_cleartext_warning),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = busyId != server.id,
                                    onClick = { connect(server) }
                                ) { Text(stringResource(if (isConnected) R.string.mcp_reconnect else R.string.mcp_connect)) }
                                OutlinedButton(
                                    enabled = busyId != server.id,
                                    onClick = { probe(server) }
                                ) { Text(stringResource(R.string.mcp_probe)) }
                                OutlinedButton(
                                    enabled = isConnected,
                                    onClick = {
                                        expanded = if (server.id in expanded) expanded - server.id else expanded + server.id
                                        if (server.id !in toolsByServer) {
                                            toolsByServer = toolsByServer + (server.id to app.mcpHost.remoteToolDefinitions(server.id))
                                        }
                                    }
                                ) { Text(stringResource(R.string.mcp_view_tools)) }
                                if (isConnected) {
                                    OutlinedButton(onClick = {
                                        app.mcpHost.remove(server.id)
                                        refreshConnected()
                                        message = context.getString(R.string.mcp_disconnected, server.name)
                                    }) { Text(stringResource(R.string.mcp_disconnect)) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { editing = server; editorOpen = true }) { Text(stringResource(R.string.mcp_edit)) }
                                TextButton(onClick = {
                                    servers = store.remove(server.id)
                                    app.mcpHost.remove(server.id)
                                    toolsByServer = toolsByServer - server.id
                                    refreshConnected()
                                    message = context.getString(R.string.mcp_deleted, server.name)
                                }) { Text(stringResource(R.string.mcp_delete)) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = { servers = store.upsert(server.copy(enabled = it)) }
                                )
                                Text(stringResource(R.string.mcp_switch_enabled), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.width(12.dp))
                                Switch(
                                    checked = server.autoConnect,
                                    onCheckedChange = { servers = store.upsert(server.copy(autoConnect = it)) }
                                )
                                Text(stringResource(R.string.mcp_switch_autoconnect), style = MaterialTheme.typography.bodySmall)
                            }
                            if (server.id in expanded) {
                                HorizontalDivider()
                                if (tools.isEmpty()) {
                                    Text(stringResource(R.string.mcp_no_tools), style = MaterialTheme.typography.bodySmall)
                                } else {
                                    tools.forEach { tool ->
                                        Column(Modifier.padding(vertical = 2.dp)) {
                                            Text(tool.name, style = MaterialTheme.typography.labelLarge)
                                            if (tool.description.isNotBlank()) {
                                                Text(tool.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            InternalServerTab(app, onMessage = { message = it })
        }
    }

    if (editorOpen) {
        ServerEditorSheet(
            initial = editing,
            onDismiss = { editorOpen = false },
            onSave = { config ->
                servers = store.upsert(config)
                editorOpen = false
                message = context.getString(R.string.mcp_saved, config.name)
            }
        )
    }
}

/** 内部服务 Tab：列出当前项目的内部 MCP 工具并支持真实调用。 */
@Composable
private fun InternalServerTab(app: NebulaForgeApplication, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val workspace by app.workspaceState.state.collectAsState()
    val root = remember(workspace.projectPath) { workspace.projectPath?.let(::File) }
    val internalId = root?.let { "project:${it.absolutePath}" }
    val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTool by remember { mutableStateOf("") }
    var arguments by remember { mutableStateOf("{}") }
    var output by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                if (internalId == null) {
                    onMessage(context.getString(R.string.mcp_no_project))
                } else {
                    tools = app.mcpHost.internalTools(internalId).map { it.name }
                    onMessage(context.getString(R.string.mcp_internal_ready))
                }
            }) { Text(stringResource(R.string.mcp_list_internal)) }
        }
        if (tools.isNotEmpty()) {
            Text(stringResource(R.string.mcp_internal_tools), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                items(tools) { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        modifier = Modifier.fillMaxWidth().let { m ->
                            if (name == selectedTool) m else m
                        }
                    )
                }
            }
            OutlinedTextField(selectedTool, { selectedTool = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.mcp_tool_name)) })
            OutlinedTextField(arguments, { arguments = it }, Modifier.fillMaxWidth().heightIn(min = 80.dp), label = { Text(stringResource(R.string.mcp_tool_args)) })
            Button(
                enabled = internalId != null && selectedTool.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching {
                            val args = withContext(Dispatchers.Default) { JSONObject(arguments) }
                            withContext(Dispatchers.IO) { app.mcpHost.callInternalTool(requireNotNull(internalId), selectedTool, args) }.toString(2)
                        }.onSuccess { output = it }.onFailure { output = "调用失败：${it.message ?: it.javaClass.simpleName}" }
                    }
                }
            ) { Text(stringResource(R.string.mcp_call_internal)) }
        }
        if (output.isNotBlank()) {
            Text(stringResource(R.string.mcp_call_result), style = MaterialTheme.typography.titleSmall)
            Text(output, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 新增 / 编辑外部 MCP 服务器。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerEditorSheet(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "http://127.0.0.1:8787/mcp") }
    // 传输方式：HTTP（本地/远程 URL）或 stdio（本地进程，按行 JSON-RPC）。
    var transport by remember {
        mutableStateOf(if (initial?.isStdio == true) McpServerConfig.TRANSPORT_STDIO else McpServerConfig.TRANSPORT_HTTP)
    }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var workdir by remember { mutableStateOf(initial?.workdir ?: "") }
    val isStdio = transport == McpServerConfig.TRANSPORT_STDIO
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var autoConnect by remember { mutableStateOf(initial?.autoConnect ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(if (initial == null) R.string.mcp_add_server else R.string.mcp_edit_server),
                style = MaterialTheme.typography.titleLarge
            )
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.mcp_field_name)) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FilterChip(
                    selected = !isStdio,
                    onClick = { transport = McpServerConfig.TRANSPORT_HTTP },
                    label = { Text(stringResource(R.string.mcp_transport_http)) }
                )
                androidx.compose.material3.FilterChip(
                    selected = isStdio,
                    onClick = { transport = McpServerConfig.TRANSPORT_STDIO },
                    label = { Text(stringResource(R.string.mcp_transport_stdio)) }
                )
                Text(stringResource(R.string.mcp_channel_hint, McpStdioProcessBridge.channelHint()), style = MaterialTheme.typography.labelSmall)
            }
            if (isStdio) {
                OutlinedTextField(command, { command = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.mcp_stdio_command)) })
                OutlinedTextField(workdir, { workdir = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.mcp_stdio_workdir)) })
                Text(stringResource(R.string.mcp_stdio_hint), style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.mcp_server_url)) })
                Text(stringResource(R.string.mcp_url_hint), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.mcp_field_note)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enabled, onCheckedChange = { enabled = it })
                Text(stringResource(R.string.mcp_switch_enabled), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(12.dp))
                Switch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                Text(stringResource(R.string.mcp_switch_autoconnect), style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val problem = if (isStdio) McpServerStore.validateCommand(command) else McpServerStore.validateUrl(url)
                    if (problem != null) {
                        error = problem
                    } else {
                        onSave(
                            (initial ?: McpServerConfig(id = McpServerStore.newId(), name = "", url = "")).copy(
                                name = name.trim().ifBlank { "MCP Server" },
                                url = if (isStdio) "" else url.trim(),
                                note = note.trim(),
                                enabled = enabled,
                                autoConnect = autoConnect,
                                transport = transport,
                                command = if (isStdio) command.trim() else "",
                                workdir = if (isStdio) workdir.trim() else ""
                            )
                        )
                    }
                }) { Text(stringResource(R.string.mcp_save)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.mcp_cancel)) }
            }
        }
    }
}
