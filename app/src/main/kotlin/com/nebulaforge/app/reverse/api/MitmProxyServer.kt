package com.nebulaforge.app.reverse.api

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** 本机 HTTPS MITM 代理：处理 CONNECT、动态证书、HTTP 请求解析与端点记录。 */
class MitmProxyServer(
    private val ca: CertificateAuthority,
    private val registry: ApiReverseRegistry,
    private val onEvent: (String) -> Unit = {}
) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool()

    @Synchronized fun start(port: Int = 0): Int {
        if (running) return server?.localPort ?: port
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", port))
        server = ss
        running = true
        workers.execute {
            while (running) {
                runCatching { ss.accept() }.onSuccess { socket -> workers.execute { handle(socket) } }.onFailure { if (running) onEvent("代理监听失败：${it.message}") }
            }
        }
        onEvent("HTTPS MITM 代理已启动：127.0.0.1:${ss.localPort}")
        return ss.localPort
    }

    @Synchronized fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        onEvent("HTTPS MITM 代理已停止")
    }

    fun isRunning(): Boolean = running
    fun port(): Int = server?.localPort ?: -1

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 45_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val header = readHeader(input) ?: return
            val requestLine = header.firstOrNull().orEmpty()
            val headers = parseHeaders(header.drop(1))
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            if (method == "CONNECT") {
                val authority = parts[1]
                val host = authority.substringBefore(':').ifBlank { authority }
                val port = authority.substringAfter(':', "443").toIntOrNull() ?: 443
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)); output.flush()
                val mitm = ca.sslContextFor(host).socketFactory.createSocket(socket, host, socket.port, false) as SSLSocket
                mitm.use { tls ->
                    tls.useClientMode = false
                    tls.soTimeout = 45_000
                    tls.startHandshake()
                    handleTls(tls, host)
                }
            } else {
                handlePlainHttp(socket, requestLine, headers, input, output)
            }
        }
    }

    private fun handleTls(client: SSLSocket, host: String) {
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val header = readHeader(input) ?: return
        val line = header.firstOrNull().orEmpty(); val parts = line.split(' '); if (parts.size < 2) return
        val method = parts[0].uppercase(); val path = parts[1]
        val headers = parseHeaders(header.drop(1))
        val targetHost = headers.entries.firstOrNull { it.key.equals("Host", true) }?.value?.substringBefore(':') ?: host
        val targetPort = headers.entries.firstOrNull { it.key.equals("Host", true) }?.value?.substringAfter(':')?.toIntOrNull() ?: 443
        val upstream = SSLContext.getDefault().socketFactory.createSocket(targetHost, targetPort) as SSLSocket
        upstream.use { up ->
            up.soTimeout = 45_000
            up.startHandshake()
            val upOut = BufferedOutputStream(up.getOutputStream())
            val upgrade = isUpgrade(headers)
            // 升级请求必须原样保留 Connection/Upgrade 头，否则上游不会回 101。
            val requestData = buildRequest(line, headers, input, targetHost, keepConnection = upgrade)
            upOut.write(requestData.bytes); upOut.flush()
            val responseInput = BufferedInputStream(up.getInputStream())
            val responseHeader = readHeader(responseInput) ?: return
            val status = responseHeader.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            val responseHeaders = parseHeaders(responseHeader.drop(1))
            if (upgrade && status == 101) {
                // WebSocket：握手成功后双向透传。旧实现把 101 当普通响应去读 body，
                // 没有 Content-Length 时会阻塞到 45s 读超时 → 表现为「WS 抓不到、相关页面卡死」。
                output.write(responseHeader.joinToString("\r\n").plus("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)); output.flush()
                record(method, "wss://$targetHost$path", headers, null, responseHeaders, status, "WebSocket 已建立（帧未解析）".toByteArray())
                tlsReadTimeoutZero(client, up)
                relayBidirectional(input, output, responseInput, upOut)
                return
            }
            if (isStreaming(responseHeaders)) {
                output.write(responseHeader.joinToString("\r\n").plus("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)); output.flush()
                val preview = relayStreaming(responseInput, output)
                record(method, "https://$targetHost$path", headers, requestData.bodyPreview, responseHeaders, status, preview.toByteArray())
            } else {
                val body = readBody(responseInput, responseHeaders)
                val safeResponseHeader = sanitizeResponseHeader(responseHeader, body.size)
                output.write(safeResponseHeader.joinToString("\r\n").plus("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                output.write(body); output.flush()
                record(method, "https://$targetHost$path", headers, requestData.bodyPreview, responseHeaders, status, body)
            }
        }
    }

    private fun handlePlainHttp(socket: Socket, requestLine: String, headers: Map<String, String>, input: BufferedInputStream, output: BufferedOutputStream) {
        val uri = runCatching { URI(requestLine.split(' ').getOrNull(1) ?: return) }.getOrNull() ?: return
        val host = uri.host ?: headers.entries.firstOrNull { it.key.equals("Host", true) }?.value?.substringBefore(':') ?: return
        val port = if (uri.port > 0) uri.port else 80
        val upstream = Socket(host, port)
        upstream.use { up ->
            val upOut = BufferedOutputStream(up.getOutputStream())
            val upgrade = isUpgrade(headers)
            val requestData = buildRequest(requestLine, headers, input, host, keepConnection = upgrade)
            upOut.write(requestData.bytes); upOut.flush()
            val responseInput = BufferedInputStream(up.getInputStream())
            val responseHeader = readHeader(responseInput) ?: return
            val status = responseHeader.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            val responseHeaders = parseHeaders(responseHeader.drop(1))
            if (upgrade && status == 101) {
                output.write(responseHeader.joinToString("\r\n").plus("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)); output.flush()
                record(requestLine.substringBefore(' '), uri.toString(), headers, null, responseHeaders, status, "WebSocket 已建立（帧未解析）".toByteArray())
                socket.soTimeout = 0
                up.soTimeout = 0
                relayBidirectional(input, output, responseInput, upOut)
                return
            }
            if (isStreaming(responseHeaders)) {
                output.write(responseHeader.joinToString("\r\n").plus("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)); output.flush()
                val preview = relayStreaming(responseInput, output)
                record(requestLine.substringBefore(' '), uri.toString(), headers, requestData.bodyPreview, responseHeaders, status, preview.toByteArray())
            } else {
                val body = readBody(responseInput, responseHeaders)
                val safeResponseHeader = sanitizeResponseHeader(responseHeader, body.size)
                output.write(safeResponseHeader.joinToString("\r\n").plus("\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1)); output.write(body); output.flush()
                record(requestLine.substringBefore(' '), uri.toString(), headers, requestData.bodyPreview, responseHeaders, status, body)
            }
        }
    }

    private data class RequestData(val bytes: ByteArray, val bodyPreview: String?)

    private fun buildRequest(
        line: String,
        headers: Map<String, String>,
        input: BufferedInputStream,
        host: String,
        keepConnection: Boolean = false
    ): RequestData {
        val bodyLength = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull()
        val body = when {
            bodyLength != null && bodyLength > 0 -> input.readNBytes(bodyLength)
            headers.entries.any { it.key.equals("Transfer-Encoding", true) && it.value.contains("chunked", true) } -> readChunked(input)
            else -> ByteArray(0)
        }
        val out = ByteArrayOutputStream()
        out.write(line.toByteArray(StandardCharsets.ISO_8859_1)); out.write("\r\n".toByteArray())
        headers.forEach { (k, v) ->
            if (k.equals("Proxy-Connection", true)) return@forEach
            if (k.equals("Connection", true) && !keepConnection) return@forEach
            out.write("$k: $v\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        out.write("Host: $host\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        if (!keepConnection) out.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1)); out.write(body)
        val preview = body.copyOf(minOf(body.size, 16_384)).toString(StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
        return RequestData(out.toByteArray(), preview)
    }

    private fun readBody(input: BufferedInputStream, headers: Map<String, String>): ByteArray {
        val length = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull()
        return when {
            length != null -> input.readNBytes(length)
            headers.entries.any { it.key.equals("Transfer-Encoding", true) && it.value.contains("chunked", true) } -> readChunked(input)
            else -> input.readBytes().take(1_048_576).toByteArray()
        }
    }

    private fun readChunked(input: BufferedInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val n = line.trim().substringBefore(';').toIntOrNull(16) ?: break
            if (n == 0) { readLine(input); break }
            out.write(input.readNBytes(n)); readLine(input)
            if (out.size() > 1_048_576) break
        }
        return out.toByteArray()
    }

    private fun record(method: String, url: String, req: Map<String, String>, requestBody: String?, resp: Map<String, String>, status: Int, body: ByteArray) {
        val mime = resp.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.substringBefore(';')
        val preview = body.copyOf(minOf(body.size, 16_384)).toString(StandardCharsets.UTF_8)
        registry.record(ApiReverseRegistry.Endpoint(method, url, req, requestBody, resp, status, mime, preview))
        onEvent("捕获 API：$method $url → $status")
    }


    /** 是否是 WebSocket 之类的协议升级请求（Upgrade: websocket）。 */
    private fun isUpgrade(headers: Map<String, String>): Boolean =
        headers.entries.any { it.key.equals("Upgrade", true) && it.value.contains("websocket", true) } ||
            headers.entries.any { it.key.equals("Connection", true) && it.value.contains("upgrade", true) }

    /** 升级为长连接后必须去掉 45s 读超时，否则空闲的 WS 连接会被我们自己掐断。 */
    private fun tlsReadTimeoutZero(vararg sockets: Socket) {
        sockets.forEach { runCatching { it.soTimeout = 0 } }
    }

    /**
     * 升级后的双向透传：客户端→上游、上游→客户端各占一个工作线程，直到任一端关闭。
     * 只记录「已建立 WebSocket」，不解析帧，因此不会破坏协议，也不把帧内容写进注册表。
     */
    private fun relayBidirectional(
        clientIn: java.io.InputStream,
        clientOut: java.io.OutputStream,
        upstreamIn: java.io.InputStream,
        upstreamOut: java.io.OutputStream
    ) {
        val toUpstream = workers.submit { copyStream(clientIn, upstreamOut) }
        try {
            copyStream(upstreamIn, clientOut)
        } finally {
            toUpstream.cancel(true)
        }
    }

    private fun copyStream(from: java.io.InputStream, to: java.io.OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = runCatching { from.read(buffer) }.getOrDefault(-1)
            if (n <= 0) break
            val wrote = runCatching { to.write(buffer, 0, n); to.flush() }.isSuccess
            if (!wrote) break
        }
    }

    private fun isStreaming(headers: Map<String, String>): Boolean =
        headers.entries.any { it.key.equals("Transfer-Encoding", true) && it.value.contains("chunked", true) } ||
            headers.entries.any { it.key.equals("Content-Type", true) && it.value.contains("text/event-stream", true) }

    private fun relayStreaming(input: BufferedInputStream, output: BufferedOutputStream): ByteArrayOutputStream {
        val preview = ByteArrayOutputStream(16_384)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = runCatching { input.read(buffer) }.getOrDefault(-1)
            if (n <= 0) break
            output.write(buffer, 0, n); output.flush()
            if (preview.size() < 16_384) preview.write(buffer, 0, minOf(n, 16_384 - preview.size()))
        }
        return preview
    }

    private fun sanitizeResponseHeader(lines: List<String>, bodySize: Int): List<String> {
        val out = lines.filterNot { it.startsWith("Transfer-Encoding:", true) || it.startsWith("Content-Length:", true) }.toMutableList()
        out += "Content-Length: $bodySize"
        out += "Connection: close"
        return out
    }

    private fun readHeader(input: BufferedInputStream): List<String>? {
        val lines = mutableListOf<String>()
        while (true) { val line = readLine(input) ?: return if (lines.isEmpty()) null else lines; if (line.isEmpty()) return lines; lines += line; if (lines.size > 200) return lines }
    }
    private fun readLine(input: BufferedInputStream): String? { val out = ByteArrayOutputStream(); while (true) { val b = input.read(); if (b < 0) return null; if (b == '\n'.code) break; if (b != '\r'.code) out.write(b); if (out.size() > 32_768) return null }; return out.toString(StandardCharsets.ISO_8859_1.name()) }
    private fun parseHeaders(lines: List<String>): Map<String, String> = buildMap { lines.forEach { val i = it.indexOf(':'); if (i > 0) put(it.substring(0, i).trim(), it.substring(i + 1).trim()) } }
}
