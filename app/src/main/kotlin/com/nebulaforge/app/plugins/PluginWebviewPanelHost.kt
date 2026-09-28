package com.nebulaforge.app.plugins

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 插件界面（WebView）宿主。
 *
 * VS Code 插件把自己的界面画在 WebView 里：扩展执行命令后调用
 * `window.createWebviewPanel(...)`，把 html 交给宿主，之后两侧靠 `postMessage` 往返。
 * 宿主以前只把这件事记成一行日志（"当前宿主不渲染 WebView 内容"），
 * 于是「命令执行成功、界面却永远不会出现」——用户在 IDE 里就是打不开这类插件。
 *
 * 这里把链路补全：
 *  1. [JsExtensionHost.panels] 提供扩展创建的面板（标题 / html / 待投递消息）；
 *  2. 用真正的 [WebView] 渲染，并注入 `acquireVsCodeApi()` 兼容层，
 *     让页面里的 `postMessage` 走到扩展的 `onDidReceiveMessage`；
 *  3. 扩展侧 `webview.postMessage` 反向投递回页面（派发标准 `message` 事件）；
 *  4. 扩展用 `webview.asWebviewUri()` 引用的插件内资源映射为
 *     `https://nebulaforge.local/ext/...`，由 [PluginPanelWebViewClient] 从插件目录直接读盘，
 *     否则打包出来的 `webview.js` / `index.css` 一律 404，界面只会白屏。
 */
@Composable
fun PluginWebviewPanels(host: JsExtensionHost) {
    val panels by host.panels.collectAsState()
    var activeId by remember { mutableStateOf("") }
    // 用户点「收起」后整个面板层隐藏；扩展再开新面板时重新弹出来。
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(panels.map { it.id }) {
        if (panels.isEmpty()) {
            dismissed = false
            activeId = ""
        } else if (activeId.isBlank() || panels.none { it.id == activeId }) {
            activeId = panels.last().id
        }
    }

    if (panels.isEmpty() || dismissed) return

    Dialog(
        // 关闭只走面板自带的按钮：插件界面里常有未保存状态，误触返回不该直接丢掉。
        onDismissRequest = { },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                PluginPanelHeader(
                    panels = panels,
                    activeId = activeId,
                    onSelect = { activeId = it },
                    onClosePanel = { host.closePanel(it) },
                    onDismissAll = { dismissed = true },
                )
                HorizontalDivider()
                Box(Modifier.fillMaxSize()) {
                    // 所有面板保持挂载（切换标签不重建 WebView）：插件界面里往往有长连接/状态，
                    // 重建等于把它的会话清空。未选中的面板被压在下面，既不显示也不接收触摸。
                    panels.forEach { panel ->
                        key(panel.id) {
                            val active = panel.id == activeId
                            Box(
                                Modifier
                                    // 非激活面板缩到 1dp：仍留在组合里（WebView 实例与页面状态都不丢），
                                    // 但不参与绘制 —— 否则每个面板都在渲染，多开几个就把 CPU 占满，
                                    // 宿主向 WebView 投递消息随即超时，界面永远是空白。
                                    .then(if (active) Modifier.fillMaxSize() else Modifier.size(1.dp))
                                    .zIndex(if (active) 1f else 0f)
                            ) {
                                PluginPanelWebView(host, panel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginPanelHeader(
    panels: List<JsExtensionHost.WebviewPanelState>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClosePanel: (String) -> Unit,
    onDismissAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "插件界面",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "由扩展渲染的 WebView 内容",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (panels.size > 1) {
                TextButton(onClick = { if (activeId.isNotBlank()) onClosePanel(activeId) }) { Text("关闭当前") }
            }
            TextButton(onClick = onDismissAll) { Text("收起") }
        }
        if (panels.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                panels.forEach { panel ->
                    val selected = panel.id == activeId
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            Modifier.padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = panel.title.ifBlank { panel.viewType.ifBlank { "面板" } },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .padding(vertical = 6.dp)
                            )
                            TextButton(
                                onClick = { onSelect(panel.id) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.padding(start = 2.dp)
                            ) {
                                Text("切换", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = { onClosePanel(panel.id) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.padding(start = 2.dp, end = 4.dp)
                            ) {
                                Text("✕", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PluginPanelWebView(host: JsExtensionHost, panel: JsExtensionHost.WebviewPanelState) {
    val context = LocalContext.current
    val webRef = remember(panel.id) { AtomicReference<WebView?>() }
    // 已经灌进 WebView 的 html 版本：与 panel.revision 比较，决定是否重载。
    val loadedRevision = remember(panel.id) { AtomicReference(-1) }
    // 页面加载完成前不能派发消息，否则 __nfDispatch 还不存在，消息会被静默丢掉。
    var pageReady by remember(panel.id) { mutableStateOf(false) }
    val pending = remember(panel.id) { mutableStateListOf<String>() }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = panel.enableScripts
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.javaScriptCanOpenWindowsAutomatically = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                webViewClient = PluginPanelWebViewClient(panel) { pageReady = true }
                addJavascriptInterface(PluginPanelBridge(host, panel.id), BRIDGE_NAME)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }.also { webRef.set(it) }
        },
        update = { web ->
            webRef.set(web)
            // 首次组合也会走这里：加载 html；之后只有扩展更新了 html（revision 变化）才重载。
            if (loadedRevision.get() != panel.revision) {
                loadedRevision.set(panel.revision)
                pageReady = false
                web.loadDataWithBaseURL(
                    "${PluginPanelWebViewClient.RESOURCE_ORIGIN}/panel/${panel.id}/",
                    wrapPluginHtml(panel.html, panel.enableScripts),
                    "text/html",
                    "utf-8",
                    null
                )
            }
        }
    )

    // 扩展 → 界面：先把宿主里的待投递消息搬到本地队列（页面没就绪时也不会丢）。
    LaunchedEffect(panel.id, panel.outbox.size) {
        if (panel.outbox.isNotEmpty()) pending.addAll(host.drainPanelOutbox(panel.id))
    }

    // 页面就绪后逐条派发：`message` 事件是 VS Code WebView 与扩展约定的唯一入站通道。
    LaunchedEffect(pageReady, pending.size) {
        if (!pageReady || pending.isEmpty()) return@LaunchedEffect
        val web = webRef.get() ?: return@LaunchedEffect
        pending.toList().forEach { json ->
            web.evaluateJavascript(
                "window.__nfDispatch && window.__nfDispatch(${JSONObject.quote(json)})",
                null
            )
        }
        pending.clear()
    }
}

private const val BRIDGE_NAME = "NebulaForgeHost"

/** WebView → 扩展：页面里的 `acquireVsCodeApi().postMessage(...)` 落到这里。 */
private class PluginPanelBridge(
    private val host: JsExtensionHost,
    private val panelId: String,
) {
    @JavascriptInterface
    fun postMessage(json: String) {
        host.postPanelMessageFromWebview(panelId, json)
    }
}

/**
 * 把插件自己的资源（`webview.asWebviewUri`）喂给 WebView。
 *
 * 为什么不直接用 `file://`：主页面 baseUrl 是 https 源，加载 file:// 子资源会被现代
 * WebView 按混合内容拦掉；换成拦截自有的伪域名后既不触发混合内容策略，
 * 也能让扩展的 CSP（`webview.cspSource`）正好覆盖这些资源。
 */
private class PluginPanelWebViewClient(
    private val panel: JsExtensionHost.WebviewPanelState,
    private val onPageFinished: () -> Unit,
) : WebViewClient() {

    override fun onPageFinished(view: WebView, url: String?) {
        onPageFinished()
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (!uri.host.equals(RESOURCE_HOST, ignoreCase = true)) return null
        val segments = uri.pathSegments ?: return null
        if (segments.size < 2 || segments.first() != "ext") return null
        val root = panel.rootDir?.let { File(it) } ?: return null
        val target = File(root, segments.drop(1).joinToString("/"))
        // 目录穿越防护：扩展可能被诱导构造 ../../ 路径去读宿主私有文件。
        return runCatching {
            val canonicalRoot = root.canonicalPath
            val canonical = target.canonicalPath
            if (!canonical.startsWith(canonicalRoot) || !target.isFile) return null
            WebResourceResponse(
                mimeTypeOf(target.name),
                null,
                200,
                "OK",
                mapOf("Access-Control-Allow-Origin" to "*"),
                target.inputStream()
            )
        }.getOrNull()
    }

    companion object {
        const val RESOURCE_HOST = "nebulaforge.local"
        const val RESOURCE_ORIGIN = "https://$RESOURCE_HOST"
    }
}

/**
 * 给扩展页面注入 VS Code WebView 兼容层。
 *
 * 两个必须点：
 *  - `acquireVsCodeApi()` 必须在扩展自己的脚本之前就存在（页面首帧就会调用），
 *    所以只能写进 html，而不能等 onPageFinished 再 evaluateJavascript；
 *  - 扩展的 CSP 会给脚本配 nonce，注入脚本必须复用同一个 nonce，否则直接被拦，
 *    表现为"界面渲染了但按钮全都没反应"。
 */
private fun wrapPluginHtml(html: String, enableScripts: Boolean): String {
    if (html.isBlank()) {
        return """
            <html><body style="margin:0;padding:24px;font-family:sans-serif;line-height:1.6">
            <b>插件界面内容为空</b><br/>
            扩展创建了面板，但还没有提交 html（或界面资源未随包发布）。<br/>
            可在「插件诊断」查看扩展日志确认原因。
            </body></html>
        """.trimIndent()
    }
    // 扩展显式关闭脚本（webview 选项 enableScripts=false）：照做，只渲染结构。
    if (!enableScripts) return html

    val nonce = cspNonceOf(html)
    val nonceAttr = if (nonce != null) " nonce=\"$nonce\"" else ""
    val shim = """
<script$nonceAttr>
(function () {
  var state = null;
  function bridge() { return window.$BRIDGE_NAME; }
  window.acquireVsCodeApi = function () {
    return {
      postMessage: function (message) {
        try { bridge().postMessage(JSON.stringify(message === undefined ? null : message)); } catch (e) {}
      },
      getState: function () { return state; },
      setState: function (value) { state = value; return value; }
    };
  };
  // 扩展 → 界面：宿主用 evaluateJavascript 调这里，再派发成标准 message 事件。
  window.__nfDispatch = function (json) {
    try {
      var data = JSON.parse(json);
      window.dispatchEvent(new MessageEvent('message', { data: data }));
    } catch (e) {}
  };
})();
</script>
""".trimIndent()

    val headIndex = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
    if (headIndex != null) {
        val at = headIndex.range.last + 1
        return html.substring(0, at) + "\n" + shim + html.substring(at)
    }
    return shim + "\n" + html
}

/** 从扩展页面的 CSP 里取出脚本 nonce（没有 CSP 就返回 null）。 */
private fun cspNonceOf(html: String): String? =
    Regex("nonce-([A-Za-z0-9+/=_\\-]+)").find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

private fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "js", "mjs", "cjs" -> "application/javascript"
    "css" -> "text/css"
    "html", "htm" -> "text/html"
    "json", "map" -> "application/json"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"
    "wasm" -> "application/wasm"
    else -> "application/octet-stream"
}
