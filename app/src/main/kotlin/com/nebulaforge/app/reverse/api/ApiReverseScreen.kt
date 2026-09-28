package com.nebulaforge.app.reverse.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.app.R
import com.nebulaforge.app.reverse.ai.ReverseAiAssistant
import com.nebulaforge.core.agent.AiProviderSettingsStore
import com.nebulaforge.core.agent.OpenAiCompatibleCompletionClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HTTPS API 逆向（MITM 代理）界面。
 *
 * ## 关键修复点
 * 1. **代理实例共享**：改用 `NebulaForgeApplication.mitmProxy`（AI Agent 用同一个实例）。
 *    以前界面自建 `MitmProxyServer`，与 Agent 各持一个 ServerSocket，第二次 `start()` 会抛
 *    BindException，表现为「点启动代理没反应」。
 * 2. **离开页面不再关代理**：原来的 `onDispose { proxy.stop() }` 会在切到「Web 逆向」页时
 *    把代理停掉 —— 而 HTTPS 抓包必须一边开代理一边在 Web 页浏览，等于抓包永远不工作。
 *    现在代理只在用户显式点「停止代理」时关闭。
 * 3. **状态取自真实代理**：`running`/`port` 直接用 `proxy.isRunning()`/`proxy.port()` 初始化，
 *    不再依赖界面局部状态（切页回来会与实际状态脱节）。
 * 4. **CA 三条落地通道**：系统安装页（用户证书）、导出 `.pem`/`.crt` 手动安装、
 *    Root/Shizuku 写入系统证书库（唯一能让其它 App 也信任的办法）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiReverseScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as NebulaForgeApplication
    // 与 AI Agent 共用同一个 CA 与代理实例（见 NebulaForgeApplication.reverseProxy 注释）。
    val ca = remember(app) { app.reverseCa }
    val proxy = remember(app) { app.mitmProxy }
    val registry = app.reverseEvidence.apiRegistry

    var endpointVersion by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(proxy.isRunning()) }
    var port by remember { mutableStateOf(proxy.port().takeIf { it > 0 } ?: -1) }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ApiReverseRegistry.Endpoint?>(null) }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var methodFilter by remember { mutableStateOf<String?>(null) }
    var showGuide by remember { mutableStateOf(true) }
    val ai = remember(context) {
        ReverseAiAssistant(OpenAiCompatibleCompletionClient(AiProviderSettingsStore(context).load()))
    }

    val all = remember(endpointVersion) { registry.all() }
    val visible = remember(all, query, methodFilter) {
        all.filter { e ->
            (methodFilter == null || e.method.equals(methodFilter, true)) &&
                (query.isBlank() || e.url.contains(query, true) || e.method.contains(query, true) ||
                    (e.mimeType ?: "").contains(query, true))
        }
    }
    val methods = remember(all) { all.map { it.method.uppercase() }.distinct().sorted() }

    // 代理运行中时定时刷新端点列表：抓包发生在别的页面，不主动刷新用户会以为「没抓到」。
    LaunchedEffect(running) {
        while (running) {
            delay(1500)
            endpointVersion++
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.reverse_api_title)) }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.reverse_web_guide_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { showGuide = !showGuide }) {
                                Text(stringResource(if (showGuide) R.string.reverse_web_guide_hide else R.string.reverse_web_guide_show))
                            }
                        }
                        if (showGuide) {
                            Text(stringResource(R.string.reverse_api_guide_body), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(stringResource(R.string.reverse_api_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                runCatching {
                                    val p = proxy.start(0)
                                    port = p
                                    // WebView 只能通过 ProxyController 走代理；不支持时只提示，不让代理实例白启动。
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                                        ProxyController.getInstance().setProxyOverride(
                                            ProxyConfig.Builder().addProxyRule("127.0.0.1:$p").build(),
                                            context.mainExecutor
                                        ) { endpointVersion++ }
                                    }
                                    running = true
                                    status = context.getString(R.string.reverse_api_started, p) +
                                        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                                            "\n注意：当前 WebView 不支持代理覆盖，「Web 逆向」页抓不到 HTTPS，请用系统级代理或其它客户端。"
                                        } else ""
                                }.onFailure {
                                    status = context.getString(R.string.reverse_api_start_failed, it.message ?: it.javaClass.simpleName)
                                }
                            },
                            enabled = !running
                        ) { Text(stringResource(R.string.reverse_api_start)) }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                                        ProxyController.getInstance().clearProxyOverride(context.mainExecutor) { }
                                    }
                                    proxy.stop()
                                }.onFailure {
                                    status = context.getString(R.string.reverse_api_action_failed, it.message ?: it.javaClass.simpleName)
                                }
                                running = false
                                port = -1
                                status = context.getString(R.string.reverse_api_stopped)
                            },
                            enabled = running
                        ) { Text(stringResource(R.string.reverse_api_stop)) }
                    }
                    Text(
                        if (running) context.getString(R.string.reverse_api_running, port) else stringResource(R.string.reverse_api_not_running),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            // ---- CA 安装：三条通道一次给全，避免「点了没反应」时用户无路可走 ----
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CA 证书", style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.reverse_api_ca_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching { context.startActivity(ca.installIntent()) }
                                    .onFailure {
                                        status = context.getString(R.string.reverse_api_ca_failed, it.message ?: it.javaClass.simpleName)
                                    }
                            }) { Text(stringResource(R.string.reverse_api_install_ca_user)) }
                            OutlinedButton(onClick = {
                                val exported = ca.exportToPublic(context)
                                status = if (exported != null) {
                                    context.getString(R.string.reverse_api_export_ca_done, exported.absolutePath)
                                } else {
                                    context.getString(R.string.reverse_api_export_ca_failed, "无法写入公共目录")
                                }
                            }) { Text(stringResource(R.string.reverse_api_export_ca)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    val exported = ca.exportToPublic(context)
                                    if (exported == null) {
                                        status = context.getString(R.string.reverse_api_export_ca_failed, "无法写入公共目录")
                                    } else {
                                        context.startActivity(ca.shareIntent(exported))
                                    }
                                }.onFailure {
                                    status = context.getString(R.string.reverse_api_action_failed, it.message ?: it.javaClass.simpleName)
                                }
                            }) { Text(stringResource(R.string.reverse_api_share_ca)) }
                            OutlinedButton(onClick = {
                                status = "正在通过 Root/Shizuku 写入系统证书库…"
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) { SystemCaInstaller.install(context, ca) }
                                    status = outcome.detail
                                }
                            }) { Text(stringResource(R.string.reverse_api_install_ca_system)) }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                runCatching { context.startActivity(ca.securitySettingsIntent()) }
                                    .onFailure { status = context.getString(R.string.reverse_api_action_failed, it.message ?: it.javaClass.simpleName) }
                            }) { Text(stringResource(R.string.reverse_api_open_security_settings)) }
                            TextButton(onClick = {
                                runCatching { context.startActivity(ca.installIntent()) }
                                    .onFailure { status = context.getString(R.string.reverse_api_ca_failed, it.message ?: it.javaClass.simpleName) }
                            }) { Text("改用系统安装页重试") }
                        }
                    }
                }
            }

            item {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }

            // ---- 导出 / 分析 ----
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.reverse_api_endpoint_count, all.size), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { ai.analyzeApi(all) }
                                    .onSuccess { aiResult = it }
                                    .onFailure { aiResult = "AI 分析失败：${it.message}" }
                            }
                        }, enabled = all.isNotEmpty()) { Text("AI 分析 API") }
                        OutlinedButton(onClick = {
                            val text = all.joinToString("\n\n") { registry.toCurl(it) }
                            copyToClipboard(context, "curl", text)
                            status = context.getString(R.string.reverse_api_copied)
                        }, enabled = all.isNotEmpty()) { Text(stringResource(R.string.reverse_api_copy_all_curl)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val json = registry.toOpenApi()
                            val file = File(context.filesDir, "reverse/api/openapi.json")
                            runCatching { file.parentFile?.mkdirs(); file.writeText(json) }
                            copyToClipboard(context, "openapi", json)
                            status = context.getString(R.string.reverse_api_exported, file.absolutePath)
                        }, enabled = all.isNotEmpty()) { Text(stringResource(R.string.reverse_api_export_openapi)) }
                        OutlinedButton(onClick = {
                            copyToClipboard(context, "openapi", registry.toOpenApi())
                            status = context.getString(R.string.reverse_api_copied)
                        }, enabled = all.isNotEmpty()) { Text(stringResource(R.string.reverse_api_copy_openapi)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val file = File(context.filesDir, "reverse/api/mcp-tools.json")
                            status = runCatching {
                                file.parentFile?.mkdirs()
                                file.writeText(registry.mcpTools().toString(2))
                                context.getString(R.string.reverse_api_exported_mcp, file.absolutePath)
                            }.getOrElse { context.getString(R.string.reverse_api_export_failed, it.message ?: it.javaClass.simpleName) }
                        }, enabled = all.isNotEmpty()) { Text(stringResource(R.string.reverse_api_export_mcp)) }
                        OutlinedButton(onClick = { endpointVersion++; status = "" }) { Text(stringResource(R.string.reverse_api_refresh)) }
                        TextButton(onClick = {
                            runCatching { registry.clear() }
                                .onFailure { status = context.getString(R.string.reverse_api_action_failed, it.message ?: it.javaClass.simpleName) }
                            endpointVersion++
                        }) { Text(stringResource(R.string.reverse_api_clear)) }
                    }
                    Text(stringResource(R.string.reverse_api_mcp_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "提示：HTTPS 抓包请到「Web 逆向」页打开「信任本地 MITM 证书」，本页只负责启动代理并汇聚端点。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            aiResult?.let { result ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text("AI API 分析", style = MaterialTheme.typography.titleMedium)
                            Text(result)
                        }
                    }
                }
            }

            // ---- 搜索与过滤 ----
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.reverse_api_search)) }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = methodFilter == null,
                        onClick = { methodFilter = null },
                        label = { Text(stringResource(R.string.reverse_api_filter_all)) }
                    )
                    methods.take(6).forEach { m ->
                        FilterChip(selected = methodFilter == m, onClick = { methodFilter = m }, label = { Text(m) })
                    }
                }
            }

            if (visible.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.reverse_api_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(visible, key = { it.key }) { endpoint ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${endpoint.method} ${endpoint.url}", style = MaterialTheme.typography.titleSmall)
                        Text("HTTP ${endpoint.status} · ${endpoint.mimeType ?: "unknown"}", style = MaterialTheme.typography.bodySmall)
                        endpoint.responsePreview?.takeIf { it.isNotBlank() }?.let {
                            Text(it, maxLines = 4, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { selected = endpoint }) { Text(stringResource(R.string.reverse_api_detail)) }
                            TextButton(onClick = {
                                copyToClipboard(context, "curl", registry.toCurl(endpoint))
                                status = context.getString(R.string.reverse_api_curl_copied)
                            }) { Text(stringResource(R.string.reverse_api_copy_curl)) }
                        }
                    }
                }
            }
        }
    }

    selected?.let { endpoint ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.reverse_api_detail_title), style = MaterialTheme.typography.headlineSmall)
                Text("${endpoint.method} ${endpoint.url}")
                Text("HTTP ${endpoint.status} · ${endpoint.mimeType ?: "unknown"}")
                Text("归一化：${endpoint.normalizedUrl}", style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.reverse_api_request_headers), style = MaterialTheme.typography.titleMedium)
                endpoint.requestHeaders.entries.take(40).forEach {
                    Text("${it.key}: ${it.value}", style = MaterialTheme.typography.bodySmall)
                }
                endpoint.requestBody?.takeIf { it.isNotBlank() }?.let {
                    Text("请求体", style = MaterialTheme.typography.titleMedium)
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 40)
                }
                Text(stringResource(R.string.reverse_api_response_preview), style = MaterialTheme.typography.titleMedium)
                endpoint.responsePreview?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 40) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        copyToClipboard(context, "curl", registry.toCurl(endpoint))
                        status = context.getString(R.string.reverse_api_curl_copied)
                    }) { Text(stringResource(R.string.reverse_api_copy_curl)) }
                    OutlinedButton(onClick = { selected = null }) { Text(stringResource(R.string.reverse_api_close)) }
                }
            }
        }
    }
}

/** 剪贴板写入统一走这里：Compose 回调里抛异常会直接崩进程，所以全程 runCatching 吞掉。 */
private fun copyToClipboard(context: Context, label: String, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
