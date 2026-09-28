package com.nebulaforge.app.plugins

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.app.R
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.plugin.PluginActivationStore
import com.nebulaforge.core.plugin.PluginDescriptor
import com.nebulaforge.core.plugin.PluginPermission
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 插件市场 + 插件管理（单页两 Tab）。
 *
 * 事实边界（UI 必须说清楚，不能给「点了没反应」的按钮）：
 * - Nebula 插件：官方/自定义仓库里的 plugin.xml + dex 包，可下载、校验、安装、加载/卸载；
 * - Open VSX / JetBrains：真实检索真实扩展市场；`.vsix` 会被转换成声明式插件包安装，
 *   其中的 `main` 入口由内嵌 node 扩展宿主执行（命令 / 补全 / 诊断），`.jar` 只能浏览 + 打开详情页；
 * - MCP：官方注册表条目，可直接连远端 MCP 服务并列举工具。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagementScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as NebulaForgeApplication
    val scope = rememberCoroutineScope()
    val market = remember { PluginMarketplace(context) }
    val client = remember { PluginMarketClient(context) }
    val store = remember { PluginSourceStore(context) }
    val activation = remember { PluginActivationStore(context) }
    val vsixAdapter = remember { VsixAdapter(context) }
    val declarativeLoader = remember { DeclarativePluginLoader.of(context) }

    var tab by remember { mutableIntStateOf(0) }
    var sourceId by remember { mutableStateOf(store.selectedSourceId) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<MarketEntry>>(emptyList()) }
    var nebulaPlugins by remember { mutableStateOf<Map<String, PluginMarketplace.MarketPlugin>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<MarketEntry?>(null) }
    var pendingGrant by remember { mutableStateOf<PluginDescriptor?>(null) }
    var showSources by remember { mutableStateOf(false) }
    var jsLogFor by remember { mutableStateOf<String?>(null) }
    val jsHost = remember { JsExtensionHost.of(context) }
    val jsStates by jsHost.states.collectAsState()
    val jsLogs by jsHost.logs.collectAsState()
    var installed by remember { mutableStateOf(market.installed()) }
    // 已安装扫描中「读不出来的文件」的原因，非空时直接在列表上方如实展示，
    // 避免「插件目录里有东西但界面显示 0 个」这种无从排查的状态。
    var installedIssues by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadedIds by remember { mutableStateOf(app.pluginRuntime.loadedPlugins().map { it.descriptor.id }.toSet()) }
    val grants = remember {
        mutableStateMapOf<String, Set<String>>().apply {
            // 权限勾选要跨进程保留：恢复上次授予结果，与冷启动时的插件自动恢复保持一致，
            // 否则「重启后自动加载」会因为拿不到权限而失败。
            market.installed().forEach { descriptor -> put(descriptor.id, activation.granted(descriptor.id)) }
        }
    }

    /** 当前可选源 = 内置源（Nebula 目录地址可改）+ 用户添加的自定义仓库。 */
    fun availableSources(): List<MarketSource> =
        PluginMarketRegistry.builtIn.map { source ->
            if (source.id == "nebula-official") source.copy(repositoryUrl = store.nebulaRepositoryUrl) else source
        } + store.customRepositories().map { PluginMarketRegistry.custom(it) }

    fun currentSource(): MarketSource? =
        availableSources().firstOrNull { it.id == sourceId } ?: availableSources().firstOrNull()

    /**
     * 安装 / 启用 / 停用 / 卸载之后都必须让 JS 扩展宿主重新对账。
     *
     * 宿主是长时进程：装了新扩展而不刷新，宿主里就永远没有它（表现为「装了但命令跑不起来」）；
     * 停用了不刷新，扩展的代码还会继续跑（表现为「关掉了还在报诊断」）。
     */
    fun refreshJsHost() {
        scope.launch(Dispatchers.IO) { runCatching { JsExtensionHost.of(context).refresh(null) } }
    }

    fun refreshInstalled() {
        val scan = market.scanInstalled()
        installed = scan.plugins
        installedIssues = scan.issues
        loadedIds = app.pluginRuntime.loadedPlugins().map { it.descriptor.id }.toSet()
    }

    /**
     * 统一的外部包安装通道：从外部带进来的包（自定义仓库下载、外部生态下载、文件管理器里选的）
     * 一律先走这里，按「能不能真的用起来」三级降级处理，而不是下载完就丢在目录里：
     *
     * 1. **Nebula 原生插件**（含 plugin.xml + dex）→ 直接安装；
     * 2. **VSIX**（VSCode 扩展）→ 转换出声明式贡献（语言关联 / 代码片段 / 编辑器缩进设置）
     *    再按 Nebula 格式安装，装了就在编辑器里生效；
     *    命令式部分（JS 逻辑）本宿主没有 JS 引擎，转换报告里逐项如实列出；
     * 3. **都不是** → 如实说明「不是可安装的格式」，并给出落盘位置。
     *
     * 必须在 IO 线程调用（要解压 + 解析 + 写磁盘）。
     *
     * @return 安装成功时给出描述符，失败为 null；第二个值是给用户看的提示文案
     */
    fun installExternalPackage(file: File): Pair<PluginDescriptor?, String> {
        // 1) 原生 Nebula 插件包
        runCatching { market.installLocalPackage(file) }.getOrNull()?.let { descriptor ->
            return descriptor to context.getString(R.string.plugin_market_installed_message, descriptor.name)
        }

        // 2) VSIX → 声明式插件包
        if (vsixAdapter.isVsix(file)) {
            val result = runCatching {
                vsixAdapter.convert(file, File(context.cacheDir, "vsix-converted"))
            }.getOrElse { error ->
                return null to context.getString(
                    R.string.plugin_market_vsix_convert_failed,
                    error.message ?: error.javaClass.simpleName
                )
            }
            val descriptor = runCatching { market.installLocalPackage(result.packageFile) }.getOrNull()
                ?: return null to context.getString(
                    R.string.plugin_market_vsix_convert_failed,
                    result.packageFile.name
                )
            // 转换包里没有 dex，生效靠注册表：装完必须立刻刷新，否则「装了要等重启才生效」。
            declarativeLoader.refresh()
            val text = if (result.hasEffectiveContribution) {
                context.getString(
                    R.string.plugin_market_vsix_converted,
                    result.displayName,
                    result.snippetCount,
                    result.extensionCount,
                    result.unsupportedCount
                )
            } else {
                context.getString(
                    R.string.plugin_market_vsix_no_effective,
                    result.displayName,
                    result.unsupportedCount
                )
            }
            return descriptor to text
        }

        // 3) 既不是 Nebula 插件也不是 VSIX
        return null to context.getString(R.string.plugin_market_external_not_loadable, file.absolutePath)
    }

    /** Nebula 格式插件（官方仓库 / 自建仓库 / 内置离线）统一映射为市场条目。 */
    fun nebulaEntry(source: MarketSource, plugin: PluginMarketplace.MarketPlugin): MarketEntry = MarketEntry(
        key = "${source.id}:${plugin.id}:${plugin.version}",
        sourceId = source.id,
        sourceLabel = source.label,
        id = plugin.id,
        name = plugin.name,
        vendor = plugin.vendor,
        version = plugin.version,
        description = plugin.description,
        iconUrl = plugin.iconUrl,
        downloadUrl = plugin.downloadUrl.takeIf { it.isNotBlank() },
        pageUrl = plugin.downloadUrl.takeIf { it.isNotBlank() },
        kind = MarketEntry.Kind.NEBULA,
        extra = buildList {
            if (plugin.bundledAsset != null) add("随包内置 · 离线可装")
            if (plugin.aiEnhanced) add("AI 增强")
            if (plugin.dependencies.isNotEmpty()) add("依赖：${plugin.dependencies.joinToString()}")
            if (plugin.permissions.isNotEmpty()) add("权限：${plugin.permissions.joinToString()}")
            if (plugin.extensions.isNotEmpty()) add("扩展点：${plugin.extensions.joinToString()}")
        }
    )

    fun enable(descriptor: PluginDescriptor, granted: Set<String>) {
        runCatching { app.loadPlugin(descriptor.source, granted) }
            .onSuccess {
                // 记下「已启用 + 已授予的权限」，让安装效果在重启后依然成立。
                activation.setGranted(descriptor.id, granted)
                activation.setEnabled(descriptor.id, true)
                refreshInstalled()
                refreshJsHost()
                message = context.getString(R.string.plugin_market_loaded_message, descriptor.name)
            }
            .onFailure {
                message = context.getString(R.string.plugin_market_load_failed, it.message ?: it.javaClass.simpleName)
            }
        pendingGrant = null
    }

    fun load() {
        val source = currentSource() ?: return
        scope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    if (source.kind == MarketSourceKind.LOCAL_BUNDLE) {
                        // 内置离线目录：直接读 assets，不需要网络，装完即可加载。
                        val plugins = market.loadBundledCatalog()
                        val mapped = plugins.associateBy { "${source.id}:${it.id}:${it.version}" }
                        Pair(plugins.map { plugin -> nebulaEntry(source, plugin) }, mapped)
                    } else if (source.kind == MarketSourceKind.NEBULA_REPO || source.kind == MarketSourceKind.CUSTOM_HTTPS) {
                        val url = source.repositoryUrl
                            ?: error(context.getString(R.string.plugin_market_source_missing_url))
                        val plugins = market.loadCatalog(url, 40)
                        val mapped = plugins.associateBy { "${source.id}:${it.id}:${it.version}" }
                        Pair(plugins.map { plugin -> nebulaEntry(source, plugin) }, mapped)
                    } else {
                        Pair(client.load(source, query), emptyMap<String, PluginMarketplace.MarketPlugin>())
                    }
                }
            }.onSuccess { result ->
                // 兜底：任何源（MCP 注册表最典型）都可能返回同 key 条目，
                // LazyColumn 的 key 必须唯一，否则直接抛 IllegalArgumentException 崩溃。
                val used = HashSet<String>()
                entries = result.first.mapIndexed { index, entry ->
                    if (used.add(entry.key)) entry
                    else entry.copy(key = "${entry.key}#$index").also { used.add(it.key) }
                }
                nebulaPlugins = result.second
                message = context.getString(R.string.plugin_market_catalog_loaded, result.first.size)
            }.onFailure {
                entries = emptyList()
                nebulaPlugins = emptyMap()
                error = context.getString(R.string.plugin_market_catalog_failed, it.message ?: it.javaClass.simpleName)
                message = ""
            }
            loading = false
        }
    }

    /**
     * 外部生态（VSIX/JAR）真实下载安装包。
     *
     * 三方降级：VSIX 会被转换成 Nebula 声明式插件包后真安装（语言关联/片段/缩进设置进入编辑器），
     * 是 Nebula 格式的直接装，都不是的才退化成「已下载供查看」。
     */
    fun downloadPackage(entry: MarketEntry) {
        val url = entry.downloadUrl ?: return
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = url.substringAfterLast('/').substringBefore('?')
                        .ifBlank { "${entry.id}-${entry.version}.package" }
                    val file = market.downloadExternalPackage(url, name)
                    // 外部生态包里如果其实是 Nebula 插件格式（含 plugin.xml + dex），就直接装入插件目录，
                    // 让「装了就能在 IDE 里用」成立；VSIX 则转换成声明式插件包再装；
                    // 确实不是插件格式的才退化成「已下载供查看」。
                    val (descriptor, text) = installExternalPackage(file)
                    Triple(file, descriptor, text)
                }
            }.onSuccess { (_, descriptor, text) ->
                refreshInstalled()
                refreshJsHost()
                if (descriptor != null && descriptor.permissions.isEmpty()) {
                    enable(descriptor, emptySet())
                } else if (descriptor != null) {
                    pendingGrant = descriptor
                }
                // 文案必须在 enable() 之后写：enable 内部会把 message 改成「插件已加载」，
                // 而那会把「转换了什么、什么没生效」这句更关键的信息覆盖掉。
                message = text
            }.onFailure {
                message = context.getString(R.string.plugin_market_download_failed, it.message ?: it.javaClass.simpleName)
            }
            busy = false
        }
    }

    fun disable(descriptor: PluginDescriptor) {
        if (app.unloadPlugin(descriptor.id)) {
            activation.setEnabled(descriptor.id, false)
            refreshInstalled()
            refreshJsHost()
            message = context.getString(R.string.plugin_market_unloaded_message, descriptor.name)
        } else {
            message = context.getString(R.string.plugin_market_unload_failed, descriptor.name)
        }
    }

    fun uninstall(descriptor: PluginDescriptor) {
        runCatching {
            app.unloadPlugin(descriptor.id)
            market.uninstall(descriptor)
        }.onSuccess { removed ->
            // 卸载即清除启用记录与历史授权：避免将来重装同 id 的其它包继承旧权限。
            activation.forget(descriptor.id)
            refreshInstalled()
            refreshJsHost()
            message = if (removed) {
                context.getString(R.string.plugin_market_uninstalled_message, descriptor.name)
            } else {
                context.getString(R.string.plugin_market_uninstall_failed, descriptor.id)
            }
        }.onFailure {
            message = context.getString(R.string.plugin_market_uninstall_failed, it.message ?: it.javaClass.simpleName)
        }
    }

    /** 安装（可选先卸掉旧版本，避免插件目录里同时留存两个版本）。 */
    fun install(entry: MarketEntry, replace: PluginDescriptor? = null) {
        // key 可能被兜底逻辑加了 "#index" 后缀，这里按 id+version 再兜一层，
        // 避免「点了安装却说请先刷新」。
        val plugin = nebulaPlugins[entry.key]
            ?: nebulaPlugins.values.firstOrNull { it.id == entry.id && it.version == entry.version }
        if (plugin == null) {
            message = context.getString(R.string.plugin_market_install_failed2, context.getString(R.string.plugin_market_need_reload))
            return
        }
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    if (replace != null) {
                        app.unloadPlugin(replace.id)
                        market.uninstall(replace)
                    }
                    market.install(plugin)
                }
            }.onSuccess { descriptor ->
                refreshInstalled()
                // 安装成功后立即重建声明式注册表和 JS 宿主；否则插件只会显示为已安装，
                // 语言关联、片段和扩展命令要等重启后才出现。
                declarativeLoader.refresh()
                refreshJsHost()
                detail = null
                message = context.getString(R.string.plugin_market_installed_message, descriptor.name)
                if (descriptor.permissions.isEmpty()) enable(descriptor, emptySet()) else pendingGrant = descriptor
            }.onFailure {
                message = context.getString(R.string.plugin_market_install_failed2, it.message ?: it.javaClass.simpleName)
            }
            busy = false
        }
    }

    fun connectMcp(entry: MarketEntry) {
        val remoteUrl = entry.remoteUrl
        if (remoteUrl == null) {
            message = context.getString(R.string.plugin_market_mcp_no_remote)
            return
        }
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val id = "registry:${entry.id}"
                    val mcp = app.mcpHost.connectRemote(id, URI(remoteUrl))
                    mcp.initialize()
                    mcp.listTools().optJSONArray("tools")?.length() ?: 0
                }
            }.onSuccess { toolCount ->
                message = context.getString(R.string.plugin_market_mcp_connected, entry.name, toolCount)
            }.onFailure {
                message = context.getString(R.string.plugin_market_mcp_failed, it.message ?: it.javaClass.simpleName)
            }
            busy = false
        }
    }

    fun iconFor(id: String): String? =
        entries.firstOrNull { it.id == id && it.kind == MarketEntry.Kind.NEBULA }?.iconUrl

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                        ?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                        ?: "plugin-${System.currentTimeMillis()}.zip"
                    // 先落到 cache 再交给统一安装器：解析失败时插件目录里不会留下半截文件。
                    val staging = File(context.cacheDir, name)
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            staging.outputStream().use(input::copyTo)
                        } ?: error(context.getString(R.string.plugin_market_local_unreadable))
                        // 本地选包与市场下载走同一条通道：Nebula 包直接装，VSIX 先转换再装，
                        // 这样从文件管理器里选的 .vsix 也不会再「选完什么都没发生」。
                        installExternalPackage(staging)
                    } finally {
                        runCatching { staging.delete() }
                    }
                }
            }.onSuccess { (descriptor, text) ->
                refreshInstalled()
                refreshJsHost()
                if (descriptor != null && descriptor.permissions.isEmpty()) {
                    enable(descriptor, emptySet())
                } else if (descriptor != null) {
                    pendingGrant = descriptor
                }
                message = text
            }.onFailure {
                message = context.getString(R.string.plugin_market_install_failed2, it.message ?: it.javaClass.simpleName)
            }
            busy = false
        }
    }

    LaunchedEffect(sourceId) { load() }
    LaunchedEffect(Unit) { refreshInstalled() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plugin_market_title)) },
                actions = { TextButton(onClick = { showSources = true }) { Text(stringResource(R.string.plugin_market_manage_sources)) } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.plugin_market_tab_market)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.plugin_market_tab_installed)) })
            }

            if (tab == 0) {
                Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.plugin_market_source_label, currentSource()?.label ?: "—"),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showSources = true }) { Text(stringResource(R.string.plugin_market_manage_sources)) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text(stringResource(R.string.plugin_market_search)) }
                        )
                        Button(onClick = { load() }, enabled = !loading) {
                            Text(stringResource(R.string.plugin_market_search_action))
                        }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.plugin_market_scope_hint), style = MaterialTheme.typography.labelSmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

                    val activeSource = currentSource()
                    if (activeSource != null && !activeSource.installable) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    stringResource(R.string.plugin_market_external_source_notice),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    enabled = !busy,
                                    onClick = {
                                        sourceId = PluginMarketRegistry.BUNDLED_ID
                                        store.selectedSourceId = sourceId
                                    }
                                ) { Text(stringResource(R.string.plugin_market_switch_bundled)) }
                            }
                        }
                    }

                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (entries.isEmpty() && !loading && error == null) {
                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.plugin_market_empty_title), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.plugin_market_empty_hint), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        items(entries, key = { it.key }) { entry ->
                            MarketEntryCard(
                                entry = entry,
                                installedVersion = installed.firstOrNull { it.id == entry.id }?.version,
                                enabled = entry.id in loadedIds,
                                busy = busy,
                                onDetail = { detail = entry },
                                onInstall = { install(entry) },
                                onConnect = { connectMcp(entry) },
                                onDownload = { downloadPackage(entry) }
                            )
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { picker.launch(arrayOf("application/zip", "application/vnd.android.package-archive", "*/*")) },
                            enabled = !busy
                        ) { Text(stringResource(R.string.plugin_market_install_local)) }
                        OutlinedButton(onClick = { refreshInstalled() }) { Text(stringResource(R.string.plugin_market_refresh)) }
                    }
                    Text(
                        stringResource(R.string.plugin_market_installed_count, installed.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.plugin_market_plugin_dir, Environment.pluginsDir(context)),
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            JsRuntimeCard(
                                states = jsStates,
                                onRunCommand = { command ->
                                    scope.launch {
                                        val failure = jsHost.executeCommand(command.id, null)
                                        message = if (failure == null) {
                                            context.getString(R.string.plugin_market_command_ran, command.title)
                                        } else {
                                            context.getString(R.string.plugin_market_command_failed, command.title, failure)
                                        }
                                    }
                                },
                                onShowLogs = { jsLogFor = it },
                                onReload = {
                                    refreshJsHost()
                                    message = context.getString(R.string.plugin_market_js_reload_requested)
                                },
                                onShutdown = {
                                    runCatching { jsHost.shutdown() }
                                    message = context.getString(R.string.plugin_market_js_stopped)
                                }
                            )
                        }
                        if (installedIssues.isNotEmpty()) {
                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            stringResource(R.string.plugin_market_scan_issues, installedIssues.size),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        installedIssues.take(6).forEach { line ->
                                            Text("• $line", style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (installedIssues.size > 6) {
                                            Text(
                                                "… 其余 ${installedIssues.size - 6} 条见日志",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (installed.isEmpty()) {
                            item {
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.plugin_market_no_installed), style = MaterialTheme.typography.titleMedium)
                                        Text(stringResource(R.string.plugin_market_no_installed_hint), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        items(installed, key = { "${it.id}@${it.version}" }) { descriptor ->
                            InstalledPluginCard(
                                descriptor = descriptor,
                                iconUrl = iconFor(descriptor.id),
                                enabled = descriptor.id in loadedIds,
                                update = entries.firstOrNull {
                                    it.kind == MarketEntry.Kind.NEBULA && it.id == descriptor.id && it.version != descriptor.version
                                },
                                busy = busy,
                                granted = grants[descriptor.id].orEmpty(),
                                onTogglePermission = { key, on ->
                                    grants[descriptor.id] = grants[descriptor.id].orEmpty().let { if (on) it + key else it - key }
                                },
                                onEnable = {
                                    if (descriptor.permissions.isEmpty()) enable(descriptor, emptySet()) else pendingGrant = descriptor
                                },
                                onDisable = { disable(descriptor) },
                                onUninstall = { uninstall(descriptor) },
                                onUpdate = { entry -> install(entry, replace = descriptor) }
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { entry ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PluginIcon(entry.iconUrl, entry.name, 56.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${entry.id}${if (entry.version.isBlank()) "" else " · ${entry.version}"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        entry.vendor?.let { Text(stringResource(R.string.plugin_market_author, it), style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Text(
                    stringResource(R.string.plugin_market_kind, entry.sourceLabel, entry.kind.display),
                    style = MaterialTheme.typography.labelSmall
                )
                entry.downloads?.let { Text(stringResource(R.string.plugin_market_downloads, formatCount(it)), style = MaterialTheme.typography.labelSmall) }
                entry.rating?.let { Text(stringResource(R.string.plugin_market_rating, "%.2f".format(it)), style = MaterialTheme.typography.labelSmall) }
                entry.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                entry.extra.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                when (entry.kind) {
                    MarketEntry.Kind.NEBULA -> {
                        Text(stringResource(R.string.plugin_market_security), style = MaterialTheme.typography.bodySmall)
                        Button(
                            enabled = !busy,
                            onClick = { install(entry, replace = installed.firstOrNull { it.id == entry.id }) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.plugin_market_install_download)) }
                    }
                    MarketEntry.Kind.MCP -> {
                        Text(stringResource(R.string.plugin_market_mcp_hint), style = MaterialTheme.typography.bodySmall)
                        entry.remoteUrl?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        Button(
                            enabled = !busy && entry.remoteUrl != null,
                            onClick = { connectMcp(entry) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.plugin_market_mcp_connect)) }
                    }
                    else -> {
                        Text(stringResource(R.string.plugin_market_external_hint), style = MaterialTheme.typography.bodySmall)
                        if (entry.downloadUrl != null) {
                            Button(
                                enabled = !busy,
                                onClick = { downloadPackage(entry) },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(stringResource(R.string.plugin_market_download_package)) }
                        }
                        Button(
                            enabled = entry.pageUrl != null,
                            onClick = {
                                entry.pageUrl?.let { url ->
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.plugin_market_open_page)) }
                    }
                }
                TextButton(onClick = { detail = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.plugin_market_close))
                }
            }
        }
    }

    jsLogFor?.let { pluginId ->
        val lines = jsLogs.filter { it.pluginId == pluginId || it.pluginId == "-" }
        AlertDialog(
            onDismissRequest = { jsLogFor = null },
            title = { Text(stringResource(R.string.plugin_market_js_logs_title, pluginId)) },
            text = {
                // 扩展日志只留在内存里（最新 400 行）：它是排障用的现场记录，不是要长期留存的资产。
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (lines.isEmpty()) {
                        Text(stringResource(R.string.plugin_market_js_logs_empty), style = MaterialTheme.typography.bodySmall)
                    } else {
                        lines.takeLast(200).forEach { line ->
                            Text(
                                "${line.level.uppercase()} ${line.text}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { jsLogFor = null }) { Text(stringResource(R.string.plugin_market_close)) } }
        )
    }

    pendingGrant?.let { descriptor ->
        AlertDialog(
            onDismissRequest = { pendingGrant = null },
            title = { Text(stringResource(R.string.plugin_market_grant_title, descriptor.name)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.plugin_market_grant_hint), style = MaterialTheme.typography.bodySmall)
                    descriptor.permissions.forEach { permission ->
                        val key = permissionKey(permission)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = key in grants[descriptor.id].orEmpty(),
                                onCheckedChange = { on ->
                                    grants[descriptor.id] = grants[descriptor.id].orEmpty().let { if (on) it + key else it - key }
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(key, style = MaterialTheme.typography.bodyMedium)
                                permission.scope?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { enable(descriptor, grants[descriptor.id].orEmpty()) }) {
                    Text(stringResource(R.string.plugin_market_grant_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingGrant = null }) { Text(stringResource(R.string.plugin_market_grant_later)) }
            }
        )
    }

    if (showSources) {
        ModalBottomSheet(onDismissRequest = { showSources = false }) {
            SourceManagerSheet(
                sources = availableSources(),
                selectedId = currentSource()?.id ?: sourceId,
                nebulaRepositoryUrl = store.nebulaRepositoryUrl,
                onSelect = { id ->
                    sourceId = id
                    store.selectedSourceId = id
                    showSources = false
                },
                onSaveNebulaRepository = { url ->
                    store.nebulaRepositoryUrl = url
                    message = context.getString(R.string.plugin_market_source_saved)
                    load()
                },
                onAddCustom = { url ->
                    store.addCustomRepository(url)
                    message = context.getString(R.string.plugin_market_source_added)
                },
                onRemoveCustom = { url ->
                    store.removeCustomRepository(url)
                    if (sourceId == PluginMarketRegistry.CUSTOM_ID) {
                        sourceId = "open-vsx"
                        store.selectedSourceId = sourceId
                    }
                }
            )
        }
    }
}

/**
 * 「扩展运行时（JS）」：把内嵌 node 扩展宿主的**真实**状态摊开给用户。
 *
 * 为什么必须放在插件页而不是藏起来：JS 扩展跑在 guest node 里，出问题只会体现在
 * 状态（缺 Node 运行时 / 启动失败）和日志里；不给入口就等于「装了扩展却完全没法自查」。
 * 这里的按钮全部对应宿主真做过的动作（运行已注册命令、重载对账、停止进程），没有占位按钮。
 */
@Composable
private fun JsRuntimeCard(
    states: List<JsExtensionHost.HostState>,
    onRunCommand: (JsExtensionHost.CommandEntry) -> Unit,
    onShowLogs: (String) -> Unit,
    onReload: () -> Unit,
    onShutdown: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.plugin_market_js_runtime),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onReload) { Text(stringResource(R.string.plugin_market_js_reload)) }
                if (states.any { it.running }) {
                    TextButton(onClick = onShutdown) { Text(stringResource(R.string.plugin_market_js_stop_all)) }
                }
            }
            if (states.isEmpty()) {
                Text(stringResource(R.string.plugin_market_js_none), style = MaterialTheme.typography.bodySmall)
            }
            states.forEach { state ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOf(state.displayName, state.version).filter { it.isNotBlank() }.joinToString(" "),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            jsStatusText(state.status),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.status == JsExtensionHost.Status.FAILED ||
                                state.status == JsExtensionHost.Status.NO_NODE
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    if (state.detail.isNotBlank()) {
                        Text(state.detail, style = MaterialTheme.typography.labelSmall)
                    }
                    state.main?.let { Text(stringResource(R.string.plugin_market_js_main, it), style = MaterialTheme.typography.labelSmall) }
                    state.activationEvents.take(4).forEach { event ->
                        Text("· $event", style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.commands.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.commands.take(4).forEach { command ->
                                // 宿主没就绪时按钮禁用：与其点了报「未就绪」，不如直接从交互上排除。
                                OutlinedButton(onClick = { onRunCommand(command) }, enabled = state.running) {
                                    Text(command.title.ifBlank { command.id }, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { onShowLogs(state.pluginId) }) {
                        Text(stringResource(R.string.plugin_market_js_logs))
                    }
                }
            }
        }
    }
}

@Composable
private fun jsStatusText(status: JsExtensionHost.Status): String = stringResource(
    when (status) {
        JsExtensionHost.Status.STOPPED -> R.string.plugin_market_js_status_stopped
        JsExtensionHost.Status.STARTING -> R.string.plugin_market_js_status_starting
        JsExtensionHost.Status.RUNNING -> R.string.plugin_market_js_status_running
        JsExtensionHost.Status.FAILED -> R.string.plugin_market_js_status_failed
        JsExtensionHost.Status.NO_NODE -> R.string.plugin_market_js_status_no_node
    }
)

/**
 * 权限键必须与 PluginPermissionPolicy 的校验规则严格一致：type 或 "type:scope"。
 * 早前只授权 type 会造成「已勾选但仍报缺少权限」，这里统一成唯一的键生成入口。
 */
private fun permissionKey(permission: PluginPermission): String =
    permission.type + (permission.scope?.let { ":$it" } ?: "")

@Composable
private fun MarketEntryCard(
    entry: MarketEntry,
    installedVersion: String?,
    enabled: Boolean,
    busy: Boolean,
    onDetail: () -> Unit,
    onInstall: () -> Unit,
    onConnect: () -> Unit,
    onDownload: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PluginIcon(entry.iconUrl, entry.name)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${entry.id}${if (entry.version.isBlank()) "" else " · ${entry.version}"}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    entry.vendor?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
                if (entry.kind == MarketEntry.Kind.NEBULA) {
                    if (installedVersion != null) {
                        Text(
                            stringResource(if (enabled) R.string.plugin_market_enabled else R.string.plugin_market_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Button(onClick = onInstall, enabled = !busy) { Text(stringResource(R.string.plugin_market_install)) }
                    }
                }
            }
            entry.description?.let { Text(it, maxLines = 3, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.kind.display, style = MaterialTheme.typography.labelSmall)
                    entry.downloads?.let { Text(stringResource(R.string.plugin_market_downloads, formatCount(it)), style = MaterialTheme.typography.labelSmall) }
                    entry.rating?.let { Text(stringResource(R.string.plugin_market_rating, "%.2f".format(it)), style = MaterialTheme.typography.labelSmall) }
                }
                when {
                    entry.kind == MarketEntry.Kind.MCP -> OutlinedButton(
                        onClick = onConnect,
                        enabled = !busy && entry.remoteUrl != null
                    ) { Text(stringResource(R.string.plugin_market_mcp_connect)) }

                    entry.downloadUrl != null && entry.kind != MarketEntry.Kind.NEBULA -> OutlinedButton(
                        onClick = onDownload,
                        enabled = !busy
                    ) { Text(stringResource(R.string.plugin_market_download_package)) }
                }
                TextButton(onClick = onDetail) { Text(stringResource(R.string.plugin_market_details)) }
            }
        }
    }
}

@Composable
private fun InstalledPluginCard(
    descriptor: PluginDescriptor,
    iconUrl: String?,
    enabled: Boolean,
    update: MarketEntry?,
    busy: Boolean,
    granted: Set<String>,
    onTogglePermission: (String, Boolean) -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onUninstall: () -> Unit,
    onUpdate: (MarketEntry) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PluginIcon(iconUrl, descriptor.name)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(descriptor.name, style = MaterialTheme.typography.titleMedium)
                    Text("${descriptor.id} · ${descriptor.version}", style = MaterialTheme.typography.labelSmall)
                    descriptor.vendor?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    Text(
                        stringResource(if (enabled) R.string.plugin_market_enabled else R.string.plugin_market_disabled),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (descriptor.permissions.isNotEmpty()) {
                Text(stringResource(R.string.plugin_market_permission_title), style = MaterialTheme.typography.labelSmall)
                descriptor.permissions.forEach { permission ->
                    val key = permissionKey(permission)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = key in granted, onCheckedChange = { on -> onTogglePermission(key, on) })
                        Text(permission.scope?.let { "$key · $it" } ?: key, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (descriptor.extensions.isNotEmpty()) {
                Text(
                    stringResource(R.string.plugin_market_extensions, descriptor.extensions.joinToString { it.point.name }),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (enabled) {
                    OutlinedButton(onClick = onDisable, enabled = !busy) { Text(stringResource(R.string.plugin_market_disable)) }
                } else {
                    Button(onClick = onEnable, enabled = !busy) { Text(stringResource(R.string.plugin_market_enable)) }
                }
                if (update != null) {
                    OutlinedButton(onClick = { onUpdate(update) }, enabled = !busy) {
                        Text(stringResource(R.string.plugin_market_update_to, update.version))
                    }
                }
                TextButton(onClick = onUninstall, enabled = !busy) { Text(stringResource(R.string.plugin_market_uninstall)) }
            }
        }
    }
}

/** 下载量一类的数字用紧凑写法，避免长数字把卡片挤爆。 */
private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}

@Composable
private fun SourceManagerSheet(
    sources: List<MarketSource>,
    selectedId: String,
    nebulaRepositoryUrl: String,
    onSelect: (String) -> Unit,
    onSaveNebulaRepository: (String) -> Unit,
    onAddCustom: (String) -> Unit,
    onRemoveCustom: (String) -> Unit
) {
    var nebulaUrl by remember(nebulaRepositoryUrl) { mutableStateOf(nebulaRepositoryUrl) }
    var newRepository by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.plugin_market_manage_sources), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.plugin_market_source_summary, sources.size),
            style = MaterialTheme.typography.bodySmall
        )
        sources.filter { it.kind != MarketSourceKind.CUSTOM_HTTPS }.forEach { source ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedId == source.id, onClick = { onSelect(source.id) })
                Column(Modifier.weight(1f)) {
                    Text(source.label, style = MaterialTheme.typography.bodyMedium)
                    Text(source.kind.display, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(source.description, style = MaterialTheme.typography.labelSmall)
                    source.repositoryUrl?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Text(stringResource(R.string.plugin_market_source_nebula_url), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = nebulaUrl,
            onValueChange = { nebulaUrl = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            label = { Text(stringResource(R.string.plugin_market_source_url_hint)) }
        )
        TextButton(onClick = {
            val invalid = PluginMarketRegistry.validateRepository(nebulaUrl)
            if (invalid != null) {
                error = invalid
            } else {
                onSaveNebulaRepository(nebulaUrl)
                note = "已保存"
            }
        }) { Text(stringResource(R.string.plugin_market_source_save)) }

        Text(stringResource(R.string.plugin_market_source_custom_title), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = newRepository,
            onValueChange = { newRepository = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.plugin_market_source_url_hint)) }
        )
        TextButton(onClick = {
            val invalid = PluginMarketRegistry.validateRepository(newRepository)
            if (invalid != null) {
                error = invalid
            } else {
                onAddCustom(newRepository)
                newRepository = ""
            }
        }) { Text(stringResource(R.string.plugin_market_source_add)) }

        sources.filter { it.kind == MarketSourceKind.CUSTOM_HTTPS }.forEach { source ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selectedId == source.id, onClick = { onSelect(source.id) })
                Text(source.repositoryUrl.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { source.repositoryUrl?.let { onRemoveCustom(it) } }) {
                    Text(stringResource(R.string.plugin_market_source_remove))
                }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
    }
}
