package com.nebulaforge.app.reverse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.app.R
import com.nebulaforge.app.reverse.ai.ReverseAiAssistant
import com.nebulaforge.app.reverse.api.ApiReverseScreen
import com.nebulaforge.app.reverse.api.SystemCaInstaller
import com.nebulaforge.core.agent.AiProviderSettingsStore
import com.nebulaforge.core.agent.OpenAiCompatibleCompletionClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReverseWorkbenchScreen() {
    var tab by remember { mutableStateOf(0) }
    var consent by remember { mutableStateOf(false) }
    if (!consent) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.reverse_compliance_title)) },
            text = { Text(stringResource(R.string.reverse_compliance_text)) },
            confirmButton = {
                TextButton(onClick = { consent = true }) { Text(stringResource(R.string.reverse_compliance_confirm)) }
            }
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.reverse_tab_apk)) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.reverse_tab_web)) })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.reverse_tab_api)) })
        }
        when (tab) {
            0 -> ApkReverseScreen(showCompliance = false)
            1 -> WebReverseScreen()
            2 -> ApiReverseScreen()
        }
    }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36"

/**
 * Web 逆向（流量观察）界面。
 *
 * ## 为什么旧版这一页「一片空白」（真机定位结论，非推测）
 * 旧实现把 ~750dp 的**固定高度**控件（引导卡 / 诊断卡 / 导航行 / 地址行 / 声明的 260dp WebView / 记录头 …）
 * 与一个 `LazyColumn(Modifier.fillMaxSize())` 一起塞进**不可滚动**的 `Column`。
 * `Column` 顺序测量、按剩余空间分配高度：固定控件吃光视口后，排在最后的记录列表拿到 **0 高度**，
 * WebView 也被压扁（`ui.xml` 实测：声明 260dp，实际 bounds 只剩 182px）。
 * 后果：
 *  - 记录列表、计数、「导出/并入接口库/清空」、搜索与筛选**全部不在无障碍树里**（被裁成 0 高度）；
 *  - 整页没有一个 `scrollable="true"` 节点 → 用户既看不到、也滚不到，只能判定为「空白」。
 *
 * ## 修法：让滚动由一个真正的滚动容器承担
 *  - 底部**记录区**改为 `LazyColumn(Modifier.weight(1f))`：权重保证它永远拿到一块**真实可用**的高度，
 *    不再被上面的控件挤成 0；记录、计数与所有操作按钮必然可见、可滚。
 *  - 顶部浏览器区保持固定（WebView **不能**放进 LazyColumn：滚出屏幕会被回收销毁，
 *    回来时页面已丢，表现为「滚一下就白屏」），但可**整体收起**，把高度让给记录区。
 *  - 代理启停、CA 导出/系统安装、信任开关直接做在本页：这三件事是「抓不到 HTTPS」的全部原因，
 *    放在别的标签页等于让用户来回试错。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebReverseScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as NebulaForgeApplication
    val recorder = app.reverseEvidence.webRecorder
    val apiRegistry = app.reverseEvidence.apiRegistry
    // 与「API / MITM」页、AI 工具共用同一实例：各自 new 一个会端口冲突、并生成两张互不相同的 CA。
    val proxy = remember(app) { app.mitmProxy }
    val ca = remember(app) { app.reverseCa }
    val scope = rememberCoroutineScope()

    var aiResult by remember { mutableStateOf<String?>(null) }
    val records by recorder.records.collectAsState()
    val defaultUrl = stringResource(R.string.reverse_web_default_url)
    var url by remember { mutableStateOf(defaultUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 原始 UA 用普通数组持有（不放 Compose state）：factory 在组合期执行，
    // 在 factory 里写 snapshot state 属于「组合期改状态」，没必要冒这个风险。
    val uaHolder = remember { arrayOfNulls<String>(1) }
    var selected by remember { mutableStateOf<WebTrafficRecorder.Record?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    // MITM 抓包时 WebView 面对的是本机 CA 动态签发的证书；Android 7+ 默认不信任用户安装的 CA，
    // 不开这个开关就会「代理正常、HTTPS 一条都抓不到」。
    var trustLocalMitm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<WebTrafficRecorder.Kind?>(null) }
    var desktopUa by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var navVersion by remember { mutableStateOf(0) }
    var showGuide by remember { mutableStateOf(true) }
    var browserExpanded by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    /**
     * 子页签：0=浏览器 1=抓包记录 2=证书与代理。
     *
     * 以前这一页把所有东西（引导 / 环境 / 记录 / 详情）平铺在同一个不可滚动的 Column 里，
     * 结果固定控件吃光视口、记录列表被压成 0 高度（真机空白根因），
     * 「导出 CA / 装 CA」这些小按钮也挤成一行，点不准、看不到反馈。
     * 拆成子页后每个功能区**独占整屏高度**：浏览器区的 WebView 能被拉满，
     * 证书区能把「导出 / 装系统 / 装用户证书」做成整行大按钮。
     */
    var sub by remember { mutableStateOf(0) }
    /** CA 安装/导出的完整输出（可能很长），单独展示，不挤在按钮旁边。 */
    var installOut by remember { mutableStateOf<String?>(null) }
    /** 最近一次导出成功的证书文件（成功后出现「分享」按钮）。 */
    var exported by remember { mutableStateOf<File?>(null) }
    // 代理/CA 状态不是 Compose 可观察量，且可能被 AI 工具或其它页面改动 → 轻量轮询保持真实。
    var proxyRunning by remember { mutableStateOf(proxy.isRunning()) }
    var caInstalled by remember { mutableStateOf(SystemCaInstaller.isInstalled(context, ca)) }
    LaunchedEffect(proxy, ca) {
        while (true) {
            proxyRunning = runCatching { proxy.isRunning() }.getOrDefault(false)
            caInstalled = runCatching { SystemCaInstaller.isInstalled(context, ca) }.getOrDefault(false)
            delay(2_000)
        }
    }

    val visible = remember(records, query, kindFilter) {
        records.filter { r ->
            (kindFilter == null || r.kind == kindFilter) &&
                (query.isBlank() || r.url.contains(query, true) || r.method.contains(query, true) ||
                    (r.mimeType ?: "").contains(query, true))
        }
    }
    val canBack = remember(navVersion, webView) { webView?.canGoBack() == true }
    val canForward = remember(navVersion, webView) { webView?.canGoForward() == true }

    /**
     * 常用站点：从真实抓到的记录里抽主机名（去重、跳过本机代理），点一下即可回到该站点。
     * 这不是「预置一堆网址」那种摆设 —— 列表完全由用户自己的流量长出来。
     */
    val hosts = remember(records) {
        records.asReversed()
            .mapNotNull { r -> runCatching { Uri.parse(r.url).host }.getOrNull() }
            .filter { it.isNotBlank() && it != "127.0.0.1" && !it.startsWith("localhost") }
            .distinct()
            .take(8)
    }

    // 地址栏跟随真实导航：SPA 内部跳转/重定向后，输入框不该还停在旧地址。
    LaunchedEffect(navVersion, webView) {
        webView?.url?.takeIf { it.isNotBlank() && it != "about:blank" }?.let { url = it }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.reverse_web_title)) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // ------------------------------------------------------------ 状态条：抓包链路是否就绪，一眼可见
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill("代理", proxyRunning, if (proxyRunning) "127.0.0.1:${proxy.port()}" else "未运行")
                StatusPill("系统CA", caInstalled, if (caInstalled) "已装" else "未装")
                StatusPill("信任", trustLocalMitm, if (trustLocalMitm) "开" else "关")
                Text(
                    "记录 ${records.size}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }

            // ------------------------------------------------------------ 子页签：每个功能区独占整屏高度
            TabRow(selectedTabIndex = sub) {
                Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("浏览器") })
                Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("抓包记录 ${records.size}") })
                Tab(
                    selected = sub == 2,
                    onClick = { sub = 2 },
                    text = { Text(if (proxyRunning && caInstalled && trustLocalMitm) "证书与代理 ✓" else "证书与代理 ⚠") }
                )
            }

            // 全局提示条：代理启停 / 导出 / 并入的结果在任何子页都看得到，不用回头找按钮旁边的字。
            notice?.let { msg ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        msg,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 3
                    )
                    TextButton(onClick = { notice = null }) { Text("知道了") }
                }
            }

            if (sub == 0) {
                // -------------------------------------------------------- 浏览器工具条
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { webView?.goBack() }, enabled = canBack) { Text("后退") }
                    TextButton(onClick = { webView?.goForward() }, enabled = canForward) { Text("前进") }
                    TextButton(onClick = { webView?.reload() }) { Text("刷新") }
                    TextButton(onClick = { webView?.stopLoading(); progress = 0 }) { Text("停止") }
                    TextButton(onClick = { sub = 1 }) { Text("看记录") }
                    FilterChip(selected = !desktopUa, onClick = { desktopUa = false }, label = { Text("手机") })
                    FilterChip(selected = desktopUa, onClick = { desktopUa = true }, label = { Text("桌面") })
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(stringResource(R.string.reverse_web_url)) }
                    )
                    Button(onClick = {
                        val target = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
                        webView?.loadUrl(target)
                    }) { Text(stringResource(R.string.reverse_web_load)) }
                }

                // 常用站点：从真实抓到的记录里抽主机名，点一下就能回到那个站点 —— 省去手打域名。
                if (hosts.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("常用站点", style = MaterialTheme.typography.labelSmall)
                        hosts.forEach { host ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val target = "https://$host"
                                    url = target
                                    webView?.loadUrl(target)
                                },
                                label = { Text(host) }
                            )
                        }
                    }
                }

                if (pageTitle.isNotBlank()) {
                    Text(
                        pageTitle,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    )
                }
            }

            // ------------------------------------------------------------ 主内容区
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (sub) {
                    // ---------------------------------------------------- 抓包记录：整屏都给列表
                    1 -> Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.reverse_web_records, records.size) +
                                    if (visible.size != records.size) "（筛后 ${visible.size}）" else "",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                enabled = records.isNotEmpty(),
                                onClick = {
                                    notice = runCatching {
                                        val file = exportJsonFile(context, "reverse/traffic.json", exportRecordsJson(records))
                                        "已导出 ${records.size} 条到 ${file.absolutePath}"
                                    }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
                                }
                            ) { Text("导出JSON") }
                            OutlinedButton(
                                enabled = visible.isNotEmpty(),
                                onClick = {
                                    notice = runCatching {
                                        val n = ReverseBridge.mergeWebRecordsIntoApi(visible, apiRegistry)
                                        if (n == 0) {
                                            "没有可并入的接口（静态资源会跳过、已存在的端点会合并）"
                                        } else {
                                            "已并入 $n 个接口到端点库，去「API / MITM」页可导出 OpenAPI"
                                        }
                                    }.getOrElse { "并入失败：${it.message ?: it.javaClass.simpleName}" }
                                }
                            ) { Text("并入接口库") }
                            TextButton(
                                enabled = records.isNotEmpty(),
                                onClick = {
                                    recorder.clear()
                                    selected = null
                                    notice = "已清空记录"
                                }
                            ) { Text("清空") }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(stringResource(R.string.reverse_web_search)) }
                            )
                            OutlinedButton(
                                enabled = records.isNotEmpty(),
                                onClick = {
                                    aiResult = "正在分析…"
                                    scope.launch {
                                        // 客户端在点击时才构造（读配置要落盘），避免组合期做 I/O。
                                        aiResult = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val client = OpenAiCompatibleCompletionClient(
                                                    AiProviderSettingsStore(context).load()
                                                )
                                                ReverseAiAssistant(client).analyzeWeb(records)
                                            }.getOrElse { "AI 分析失败：${it.message ?: it.javaClass.simpleName}" }
                                        }
                                    }
                                }
                            ) { Text("AI 分析") }
                            TextButton(onClick = {
                                runCatching { CookieManager.getInstance().removeAllCookies(null) }
                                notice = "已清空 WebView Cookie"
                            }) { Text("清Cookie") }
                        }

                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = kindFilter == null,
                                onClick = { kindFilter = null },
                                label = { Text(stringResource(R.string.reverse_web_filter_all)) }
                            )
                            listOf(
                                WebTrafficRecorder.Kind.API,
                                WebTrafficRecorder.Kind.DOCUMENT,
                                WebTrafficRecorder.Kind.SSE,
                                WebTrafficRecorder.Kind.WEBSOCKET,
                                WebTrafficRecorder.Kind.STATIC
                            ).forEach { kind ->
                                FilterChip(
                                    selected = kindFilter == kind,
                                    onClick = { kindFilter = if (kindFilter == kind) null else kind },
                                    label = { Text(kindLabel(kind)) }
                                )
                            }
                        }

                        aiResult?.let { text ->
                            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Column(
                                    Modifier.padding(10.dp).heightIn(max = 150.dp).verticalScroll(rememberScrollState())
                                ) {
                                    Text("AI Web 分析", style = MaterialTheme.typography.titleSmall)
                                    Text(text, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        LazyColumn(
                            Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (visible.isEmpty()) {
                                item {
                                    Text(
                                        if (records.isEmpty()) stringResource(R.string.reverse_web_empty) else "当前筛选/搜索没有匹配的记录。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            items(visible.asReversed(), key = { it.id }) { record ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            "${kindLabel(record.kind)} · ${record.method} · ${record.statusCode ?: "—"}",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Text(record.url, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            record.mimeType ?: stringResource(R.string.reverse_web_no_mime),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { selected = record }) {
                                                Text(stringResource(R.string.reverse_web_details))
                                            }
                                            TextButton(onClick = {
                                                copyText(context, "url", record.url)
                                                notice = context.getString(R.string.reverse_web_copied) + "：" + record.url
                                            }) { Text(stringResource(R.string.reverse_web_copy_url)) }
                                        }
                                    }
                                }
                            }
                            item {
                                Text(
                                    "共 ${records.size} 条 · 最多保留最近若干条，超出后自动丢弃最早的记录。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // ---------------------------------------------------- 证书与代理：整屏给 CA，按钮全宽
                    2 -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("按顺序做这 4 步，HTTPS 才抓得到", style = MaterialTheme.typography.titleMedium)

                                StepLine(1, proxyRunning, "启动本机代理") {
                                    Button(
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !busy,
                                        onClick = {
                                            notice = runCatching {
                                                if (proxyRunning) {
                                                    proxy.stop()
                                                    "代理已停止"
                                                } else {
                                                    "已启动代理：127.0.0.1:${proxy.start(0)}（把 Wi-Fi 代理指向它）"
                                                }
                                            }.getOrElse { "代理操作失败：${it.message ?: it.javaClass.simpleName}" }
                                            proxyRunning = runCatching { proxy.isRunning() }.getOrDefault(false)
                                        }
                                    ) {
                                        Text(if (proxyRunning) "停止代理（127.0.0.1:${proxy.port()}）" else "启动代理")
                                    }
                                }

                                StepLine(2, caInstalled, "安装本机 CA 到系统证书库") {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !busy,
                                            onClick = {
                                                busy = true
                                                installOut = "正在写入系统证书库（需要 Root 或已授权的 Shizuku）…"
                                                scope.launch {
                                                    val text = withContext(Dispatchers.IO) {
                                                        runCatching { SystemCaInstaller.install(context, ca) }.fold(
                                                            onSuccess = {
                                                                (if (it.success) "✓ 安装成功\n" else "✗ 安装失败\n") + it.detail
                                                            },
                                                            onFailure = { "✗ 安装失败：${it.message ?: it.javaClass.simpleName}" }
                                                        )
                                                    }
                                                    caInstalled = runCatching { SystemCaInstaller.isInstalled(context, ca) }
                                                        .getOrDefault(false)
                                                    installOut = text
                                                    notice = if (caInstalled) {
                                                        "CA 已写入系统证书库"
                                                    } else {
                                                        "CA 未写入系统证书库（详见下方「执行结果」）"
                                                    }
                                                    busy = false
                                                }
                                            }
                                        ) { Text(if (caInstalled) "重新安装到系统证书库" else "安装到系统证书库（最强）") }

                                        OutlinedButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !busy,
                                            onClick = {
                                                notice = runCatching {
                                                    context.startActivity(ca.installIntent())
                                                    "已打开系统安装界面：请选「CA 证书」"
                                                }.getOrElse { "无法打开系统安装界面：${it.message ?: it.javaClass.simpleName}" }
                                            }
                                        ) { Text("安装为用户证书（系统弹窗）") }

                                        OutlinedButton(
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !busy,
                                            onClick = {
                                                notice = runCatching {
                                                    context.startActivity(ca.securitySettingsIntent())
                                                    "已打开系统「加密与凭据」设置"
                                                }.getOrElse { "无法打开系统设置：${it.message ?: it.javaClass.simpleName}" }
                                            }
                                        ) { Text("打开系统「加密与凭据」设置") }
                                    }
                                }

                                StepLine(3, trustLocalMitm, "打开「信任本地 MITM 证书」") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Switch(checked = trustLocalMitm, onCheckedChange = { trustLocalMitm = it })
                                        Text(
                                            if (trustLocalMitm) {
                                                "已信任：WebView 接受本机 CA 签发的证书"
                                            } else {
                                                "未信任：WebView 会报证书错误，HTTPS 一条都抓不到"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                StepLine(4, records.isNotEmpty(), "在浏览器访问站点，回到「抓包记录」看流量") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { sub = 0 }) { Text("去浏览器") }
                                        OutlinedButton(onClick = { sub = 1 }) { Text("看记录（${records.size}）") }
                                    }
                                }
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("证书与状态", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "系统证书库：" + (if (caInstalled) "✓ 已安装" else "✗ 未安装"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    SystemCaInstaller.systemPath(context, ca),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "本机代理：" + (if (proxyRunning) "运行中 127.0.0.1:${proxy.port()}" else "未运行"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                exported?.let {
                                    Text(
                                        "已导出：${it.absolutePath}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !busy,
                                    onClick = {
                                        notice = runCatching {
                                            val file = ca.exportToPublic(context)
                                            if (file == null) {
                                                exported = null
                                                "导出失败：公共目录不可写（检查存储权限）"
                                            } else {
                                                exported = file
                                                "已导出证书：${file.absolutePath}（同目录另有 .crt 供系统安装）"
                                            }
                                        }.getOrElse { "导出失败：${it.message ?: it.javaClass.simpleName}" }
                                    }
                                ) { Text("导出 CA 证书到手机存储") }

                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = exported != null,
                                    onClick = {
                                        val file = exported
                                        if (file == null) {
                                            notice = "先点上面的「导出 CA 证书到手机存储」"
                                        } else {
                                            notice = runCatching {
                                                context.startActivity(
                                                    Intent.createChooser(ca.shareIntent(file), "分享 CA 证书")
                                                )
                                                "已打开分享面板"
                                            }.getOrElse { "分享失败：${it.message ?: it.javaClass.simpleName}" }
                                        }
                                    }
                                ) { Text("分享证书文件到其它设备") }
                            }
                        }

                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "执行结果",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    installOut?.let { text ->
                                        TextButton(onClick = {
                                            copyText(context, "结果", text)
                                            notice = "结果已复制"
                                        }) { Text("复制") }
                                    }
                                }
                                Text(
                                    installOut ?: "还没执行过安装 / 导出。点上面的按钮，完整输出会显示在这里。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        Text(
                            "为什么要装「系统」证书：Android 7 起，用系统界面装的都算「用户证书」，除本应用外基本不被信任，" +
                                "别的 App 照样报证书错误。把 CA 写进系统证书库（需要 Root 或已授权的 Shizuku）才能真正抓到它们的 HTTPS；" +
                                "没有 Root 时，退而求其次是在本应用内置浏览器里抓（即打开上面的「信任本地 MITM 证书」）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // WebView 常驻组合：浏览器子页让它铺满整块内容区（旧版被钉死 220dp，真机上只剩 182px），
                // 切到其它子页时缩成 0 尺寸**保活** —— 一旦移出组合，WebView 就被销毁、页面丢失。
                AndroidView(
                    modifier = if (sub == 0) Modifier.fillMaxSize() else Modifier.size(0.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            uaHolder[0] = settings.userAgentString
                            webViewClient = ReverseWebViewClient(
                                recorder,
                                trustLocalProxyCertificates = { trustLocalMitm },
                                onNavigated = { navVersion++ }
                            )
                            webChromeClient = object : WebChromeClient() {
                                override fun onReceivedTitle(view: WebView?, title: String?) {
                                    pageTitle = title.orEmpty()
                                }

                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            webView = this
                            loadUrl(defaultUrl)
                        }
                    },
                    update = { view ->
                        // UA 切换只能通过 update 写回：WebView 只在 factory 里建一次。
                        val target = if (desktopUa) DESKTOP_UA else (uaHolder[0] ?: view.settings.userAgentString)
                        if (view.settings.userAgentString != target) view.settings.userAgentString = target
                        webView = view
                    }
                )
            }
        }
    }

    // 只销毁 WebView 自身；**不动代理**：代理由用户/AI 显式启停，
    // 旧版在这里 stop 会导致「一离开本页就断抓包」。
    DisposableEffect(Unit) {
        onDispose {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }
    }

    selected?.let { record ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.reverse_web_detail_title), style = MaterialTheme.typography.titleLarge)
                Text(record.url, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${record.method} · ${record.statusCode ?: "—"} · ${record.responseBytes} B",
                    style = MaterialTheme.typography.labelMedium
                )
                record.mimeType?.let { Text("MIME：$it") }
                record.note?.let { Text(it) }
                if (record.requestHeaders.isNotEmpty()) {
                    Text(stringResource(R.string.reverse_web_request_headers), style = MaterialTheme.typography.titleMedium)
                    record.requestHeaders.entries.take(30).forEach {
                        Text("${it.key}: ${it.value}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (record.responseHeaders.isNotEmpty()) {
                    Text(stringResource(R.string.reverse_web_response_headers), style = MaterialTheme.typography.titleMedium)
                    record.responseHeaders.entries.take(30).forEach {
                        Text("${it.key}: ${it.value}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                record.responsePreview?.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.reverse_web_response_preview), style = MaterialTheme.typography.titleMedium)
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 80)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        copyText(context, "url", record.url)
                        notice = context.getString(R.string.reverse_web_copied)
                    }) { Text(stringResource(R.string.reverse_web_copy_url)) }
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(record.url))) }
                        selected = null
                    }) { Text("用系统浏览器打开") }
                    TextButton(onClick = { selected = null }) { Text(stringResource(R.string.reverse_api_close)) }
                }
            }
        }
    }
}

/** Compose 回调里抛异常会崩进程，剪贴板写入全程吞异常。 */
private fun copyText(context: Context, label: String, text: String) {
    runCatching {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun kindLabel(kind: WebTrafficRecorder.Kind): String = when (kind) {
    WebTrafficRecorder.Kind.DOCUMENT -> "文档"
    WebTrafficRecorder.Kind.API -> "API"
    WebTrafficRecorder.Kind.SSE -> "SSE"
    WebTrafficRecorder.Kind.WEBSOCKET -> "WebSocket"
    WebTrafficRecorder.Kind.CHUNKED -> "流式"
    WebTrafficRecorder.Kind.STATIC -> "静态资源"
    WebTrafficRecorder.Kind.OTHER -> "其它"
}

/**
 * 导出文件：优先写公共目录（用户能在文件管理器里看到、也能用数据线取走），
 * 没有存储权限时退回落盘到应用私有目录 —— 宁可路径丑，也不能「导出点了没反应」。
 */
private fun exportJsonFile(context: Context, name: String, content: String): File {
    val publicFile = File("/storage/emulated/0/NebulaForgeIDE", name)
    val ok = runCatching {
        publicFile.parentFile?.mkdirs()
        publicFile.writeText(content)
        true
    }.getOrDefault(false)
    if (ok) return publicFile
    val privateFile = File(context.filesDir, name)
    privateFile.parentFile?.mkdirs()
    privateFile.writeText(content)
    return privateFile
}

/**
 * Web 抓包导出为 JSON。
 *
 * 只带出记录器已持有的元数据与前 16KB 响应预览（与列表内一致），不额外放大内存占用；
 * 证据因此可以离开设备（发给自己、交给 AI 或写进报告），而不是只能留在内存里。
 */
private fun exportRecordsJson(records: List<WebTrafficRecorder.Record>): String {
    val array = org.json.JSONArray()
    records.forEach { r ->
        val requestHeaders = org.json.JSONObject().apply { r.requestHeaders.forEach { (k, v) -> put(k, v) } }
        val responseHeaders = org.json.JSONObject().apply { r.responseHeaders.forEach { (k, v) -> put(k, v) } }
        array.put(org.json.JSONObject().apply {
            put("time", r.timeMs)
            put("method", r.method)
            put("url", r.url)
            put("kind", r.kind.name)
            put("status", r.statusCode ?: 0)
            put("mime", r.mimeType ?: "")
            put("responseBytes", r.responseBytes)
            put("captured", r.responseCaptured)
            put("requestHeaders", requestHeaders)
            put("responseHeaders", responseHeaders)
            r.responsePreview?.let { put("preview", it.take(16_384)) }
            r.note?.let { put("note", it) }
        })
    }
    return org.json.JSONObject()
        .put("capturedAt", System.currentTimeMillis())
        .put("count", records.size)
        .put("records", array)
        .toString(2)
}

/**
 * 顶部状态小标：「✓ 系统CA 已装」/「✗ 系统CA 未装」。
 *
 * 用颜色 + 勾叉而不是长句子，是为了让用户在**任何一个子页**都能一眼判断
 * 「现在到底能不能抓到 HTTPS」，不用切到证书页逐条读。
 */
@Composable
private fun StatusPill(name: String, ok: Boolean, value: String) {
    Text(
        "${if (ok) "✓" else "✗"} $name $value",
        style = MaterialTheme.typography.labelSmall,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
}

/**
 * 「4 步」里的单步：一行标题（带 ✓/○ 进度）+ 该步的**操作按钮**。
 *
 * 旧版把 4 步写成一整段多行文本（真机上被挤成密密麻麻几行、还被裁掉半句），
 * 用户既看不清顺序、也不知道该点哪里。拆成「标题行 + 按钮」后，
 * 每一步要做什么、做没做、去哪儿做，都是可见的。
 */
@Composable
private fun StepLine(index: Int, done: Boolean, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (done) "✓" else "○",
                style = MaterialTheme.typography.titleMedium,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$index. $title",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
        content()
    }
}
