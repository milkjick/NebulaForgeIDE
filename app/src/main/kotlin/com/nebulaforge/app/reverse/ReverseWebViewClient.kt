package com.nebulaforge.app.reverse

import android.net.Uri
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 通过 shouldInterceptRequest 观察并代理可安全重放的 GET/HEAD 请求。
 * POST/上传类请求保留给 WebView 原生网络栈，以避免丢失请求体。
 */
class ReverseWebViewClient(
    private val recorder: WebTrafficRecorder,
    /**
     * 是否放行「本机 MITM 代理 CA 签发」的证书错误。
     *
     * Android 7+ 起用户安装的 CA 默认不被 WebView 信任，而 MITM 抓包看到的正是本机 CA
     * 动态签发的叶子证书 —— 于是用户会看到「代理已启动，却一条 HTTPS 都抓不到」。
     * 这里用回调实时读取开关（WebViewClient 在 factory 中只创建一次，不能靠构造值），
     * 只有用户显式打开该开关时才 proceed，其余情况一律 cancel。
     */
    private val trustLocalProxyCertificates: () -> Boolean = { false },
    /**
     * 导航状态变化回调（前进/后退可用性、标题、URL）。
     *
     * WebView 的 `canGoBack()/canGoForward()` 不是 Compose 可观察状态，只能在回调里主动通知 UI；
     * 不通知的话界面上「后退/前进」按钮永远是灰的，用户会以为功能没做。
     */
    private val onNavigated: () -> Unit = {}
) : WebViewClient() {

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        onNavigated()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onNavigated()
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError) {
        if (trustLocalProxyCertificates()) handler.proceed() else handler.cancel()
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val method = request.method.uppercase(Locale.US)
        val requestHeaders = request.requestHeaders.toMap()
        val requestKind = classifyRequest(request.url, requestHeaders)
        val id = recorder.newId()

        // 只重放 http/https。
        //
        // 真机「整页白屏」根因之一：旧版对**任何** scheme 都走 HttpURLConnection 重放。
        // 现代页面大量使用 `data:`（内联图片/字体）、`blob:`（前端生成的下载与媒体），
        // 这些 scheme 用 HttpURLConnection 打开必然抛异常；即使回退 WebView，也已经浪费一次
        // 拦截并污染证据。这里直接放行，交回 WebView 原生栈。
        val scheme = request.url.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return null

        if (method != "GET" && method != "HEAD") {
            recorder.add(
                WebTrafficRecorder.Record(
                    id = id,
                    timeMs = System.currentTimeMillis(),
                    method = method,
                    url = request.url.toString(),
                    kind = requestKind,
                    requestHeaders = requestHeaders,
                    note = "此请求包含请求体，未在 WebView 拦截层重放；仅记录请求元数据。"
                )
            )
            return null
        }

        if (requestHeaders.entries.any { it.key.equals("Upgrade", true) && it.value.equals("websocket", true) }) {
            recorder.add(
                WebTrafficRecorder.Record(
                    id = id,
                    timeMs = System.currentTimeMillis(),
                    method = method,
                    url = request.url.toString(),
                    kind = WebTrafficRecorder.Kind.WEBSOCKET,
                    requestHeaders = requestHeaders,
                    note = "检测到 WebSocket 升级请求；WebView 拦截层不读取 WebSocket 帧。"
                )
            )
            return null
        }

        return try {
            proxyGet(view, request, id, requestKind)
        } catch (t: Throwable) {
            recorder.add(
                WebTrafficRecorder.Record(
                    id = id,
                    timeMs = System.currentTimeMillis(),
                    method = method,
                    url = request.url.toString(),
                    kind = requestKind,
                    requestHeaders = requestHeaders,
                    note = "拦截代理失败，已回退 WebView：${t.message ?: t.javaClass.simpleName}"
                )
            )
            null
        }
    }

    /**
     * 用应用侧 HTTP 栈重放 GET/HEAD，并把响应交回 WebView。
     *
     * ## 为什么必须清理请求头与响应头（真机「Web 页一片空白」的根因）
     *
     * 旧实现把 WebView 的请求头**原样**转给 `HttpURLConnection`，再把响应头**原样**交回 WebView，
     * 正好踩中 Android 网络栈与 WebView 之间的三个语义冲突，结果就是「页面白屏、资源全挂」：
     *
     *  1. **`Accept-Encoding`**：WebView 会带 `gzip, deflate, br`。一旦由我们转发，连接层就认为
     *     「调用方自己负责解压」而**不再**透明 gunzip；浏览器侧拿到的是已解压字节，
     *     却被同一份响应头里的 `Content-Encoding: gzip` 指示再解压一次 → 双重解压失败 → 白屏。
     *     修法：**不转发** `Accept-Encoding`（让连接层自己协商并自行解压），
     *     并在回写时**剥掉** `Content-Encoding`（交出去的字节已经是明文）。
     *  2. **条件请求头**（`If-None-Match` / `If-Modified-Since` / `If-Range` / `Range`）：转发后服务端
     *     会返回 **304/206 且没有正文**，而我们伪造的空正文响应破坏了 WebView 的缓存语义，
     *     资源被当作加载失败。修法：不转发这些头；真的收到 304/204 时**不构造响应**，回退原生栈。
     *  3. **`Content-Length`**：解压后的真实字节数与原始长度必然不一致，交给 WebView 会按旧长度截断。
     *     修法：剥掉 `Content-Length` / `Transfer-Encoding`，让 WebView 以 EOF 判定结束。
     *
     * 另外旧实现把 `contentEncoding`（取值如 `gzip`）当成 **字符集** 传给 `WebResourceResponse`，
     * 文本页会被按 `gzip` 这个不存在的 charset 解码 → 乱码或空白。这里改为从 `Content-Type` 的
     * `charset=` 解析，取不到时退回 UTF-8。
     */
    private fun proxyGet(
        view: WebView,
        request: WebResourceRequest,
        id: Long,
        requestKind: WebTrafficRecorder.Kind
    ): WebResourceResponse? {
        val connection = (URL(request.url.toString()).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = request.method.uppercase(Locale.US)
            request.requestHeaders.forEach { (name, value) ->
                // 见上文注释：这几类头必须丢弃，否则会造成双重解压 / 空正文 304 / 长度截断。
                val blocked = name.equals("Host", true) || name.equals("Content-Length", true) ||
                    name.equals("Connection", true) || name.equals("Accept-Encoding", true) ||
                    name.equals("If-None-Match", true) || name.equals("If-Modified-Since", true) ||
                    name.equals("If-Range", true) || name.equals("Range", true) ||
                    name.equals("Transfer-Encoding", true) || name.equals("Upgrade", true)
                if (!blocked) setRequestProperty(name, value)
            }
            // 默认 UA：WebView 的 UA 里带 "wv" 标记，部分站点会据此降级页面；桌面 UA 已在 UI 里可切。
            if (request.requestHeaders.keys.none { it.equals("User-Agent", true) }) {
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            CookieManager.getInstance().getCookie(request.url.toString())?.let { setRequestProperty("Cookie", it) }
        }
        connection.connect()
        val status = connection.responseCode

        // 无正文响应：不构造 WebResourceResponse，交回 WebView 原生栈处理（缓存语义在它手里）。
        if (status == 304 || status == 204 || status == 205) {
            val headers = readResponseHeaders(connection, keepBodyHeaders = false)
            runCatching { connection.disconnect() }
            recorder.add(
                WebTrafficRecorder.Record(
                    id = id,
                    timeMs = System.currentTimeMillis(),
                    method = request.method.uppercase(Locale.US),
                    url = request.url.toString(),
                    kind = requestKind,
                    requestHeaders = request.requestHeaders,
                    statusCode = status,
                    responseHeaders = headers,
                    note = "服务端返回 $status（无正文），已交回 WebView 原生处理。"
                )
            )
            return null
        }

        val headers = readResponseHeaders(connection, keepBodyHeaders = false)
        val contentType = connection.contentType.orEmpty()
        val mime = contentType.substringBefore(';').trim().takeIf { it.isNotBlank() }
        // 字符集从 Content-Type 解析；旧实现误把 contentEncoding(如 gzip) 当 charset。
        val charset = contentType.substringAfter("charset=", "")
            .trim().trim('"').substringBefore(';').takeIf { it.isNotBlank() } ?: "UTF-8"
        val transferEncoding = connection.getHeaderField("Transfer-Encoding")
        val kind = classifyResponse(request.url, headers, mime, requestKind)
        val input = if (request.method.equals("HEAD", true)) {
            connection.disconnect()
            "".byteInputStream()
        } else {
            val source = if (status >= 400) (connection.errorStream ?: connection.inputStream) else connection.inputStream
            RecordingInputStream(source) { bytes, chunk ->
                recorder.updateCapture(id, bytes, chunk)
            }
        }

        recorder.add(
            WebTrafficRecorder.Record(
                id = id,
                timeMs = System.currentTimeMillis(),
                method = request.method.uppercase(Locale.US),
                url = request.url.toString(),
                kind = kind,
                requestHeaders = request.requestHeaders,
                statusCode = status,
                responseHeaders = headers,
                mimeType = mime,
                responseCaptured = true,
                note = when {
                    transferEncoding?.contains("chunked", true) == true -> "检测到 chunked 响应。"
                    mime?.equals("text/event-stream", true) == true -> "检测到 SSE 响应。"
                    else -> null
                }
            )
        )

        // 主文档若没给出 MIME（少见的裸响应），按 HTML 处理，避免 WebView 当成下载而显示空白。
        val effectiveMime = mime ?: if (request.isForMainFrame) "text/html" else "application/octet-stream"
        return WebResourceResponse(effectiveMime, charset, status, connection.responseMessage ?: "OK", headers, input)
    }

    /**
     * 读取响应头并剥掉「与字节流语义相关」的几个字段。
     *
     * [keepBodyHeaders] 为 true 时保留原样（仅用于诊断展示），默认 false —— 交给 WebView 之前
     * 必须去掉 `Content-Encoding`（字节已解压）、`Content-Length`/`Transfer-Encoding`（长度不再可信）。
     */
    private fun readResponseHeaders(connection: HttpURLConnection, keepBodyHeaders: Boolean): Map<String, String> {
        val raw = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key!! }
            .mapValues { it.value.joinToString(", ") }
        if (keepBodyHeaders) return raw
        return raw.filterKeys { name ->
            !name.equals("Content-Encoding", true) && !name.equals("Content-Length", true) &&
                !name.equals("Transfer-Encoding", true) && !name.equals("Connection", true)
        }
    }

    private fun classifyRequest(url: Uri, headers: Map<String, String>): WebTrafficRecorder.Kind {
        if (headers.entries.any { it.key.equals("Upgrade", true) && it.value.equals("websocket", true) }) {
            return WebTrafficRecorder.Kind.WEBSOCKET
        }
        val path = url.path.orEmpty().lowercase(Locale.US)
        return when {
            path.contains("/api/") || path.endsWith("/api") -> WebTrafficRecorder.Kind.API
            path.contains(".js") || path.contains(".css") || path.contains(".png") || path.contains(".jpg") || path.contains(".svg") -> WebTrafficRecorder.Kind.STATIC
            else -> WebTrafficRecorder.Kind.DOCUMENT
        }
    }

    private fun classifyResponse(
        url: Uri,
        headers: Map<String, String>,
        mime: String?,
        fallback: WebTrafficRecorder.Kind
    ): WebTrafficRecorder.Kind {
        if (mime?.equals("text/event-stream", true) == true) return WebTrafficRecorder.Kind.SSE
        if (headers.entries.any { it.key.equals("Transfer-Encoding", true) && it.value.contains("chunked", true) }) {
            return WebTrafficRecorder.Kind.CHUNKED
        }
        return if (mime?.contains("json", true) == true && fallback == WebTrafficRecorder.Kind.DOCUMENT) {
            WebTrafficRecorder.Kind.API
        } else fallback
    }

    private class RecordingInputStream(
        source: InputStream,
        private val onBytes: (Long, ByteArray) -> Unit
    ) : FilterInputStream(source) {
        override fun read(): Int {
            val value = super.read()
            if (value >= 0) onBytes(1, byteArrayOf(value.toByte()))
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count > 0) onBytes(count.toLong(), buffer.copyOfRange(offset, offset + count))
            return count
        }
    }
}
