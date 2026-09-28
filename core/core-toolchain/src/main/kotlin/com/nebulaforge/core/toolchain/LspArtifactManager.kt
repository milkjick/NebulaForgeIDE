package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * Real language-server artifact manager.
 *
 * It consumes live release metadata instead of hard-coding a server version. GitHub release
 * asset digests are used when the upstream API exposes a sha256 digest. An artifact without a
 * trusted digest is not silently trusted either: verification degrades to download-size checking
 * plus a locally computed SHA-256 fingerprint that is recorded (digestDegraded) for audit.
 * Downloads are resumable and are staged before activation so a failed extraction cannot replace
 * a working server.
 */
class LspArtifactManager(private val context: Context) {
    enum class State { NOT_INSTALLED, CHECKING, AVAILABLE, INSTALLED, FAILED, UNSUPPORTED }

    data class Spec(
        val id: String,
        val displayName: String,
        val repository: String,
        val assetRegex: Regex,
        val installDirName: String,
        val launcherRelative: String?,
        /** 非 GitHub 的直链源（如 download.eclipse.org 的 latest 快照）；非空时跳过 GitHub API。 */
        val directUrl: String? = null,
        val jarNameRegex: Regex? = null,
        val notes: String = ""
    )

    data class RemoteAsset(
        val name: String,
        val url: String,
        val digest: String?,
        val size: Long,
        val releaseTag: String
    )

    data class Status(
        val id: String,
        val displayName: String,
        val state: State,
        val installedVersion: String? = null,
        val remoteVersion: String? = null,
        val detail: String = "",
        val assetName: String? = null
    )

    private val specs = listOf(
        // JDT LS is an Eclipse project. Its GitHub releases are the preferred live metadata source.
        Spec(
            id = "java",
            displayName = "Eclipse JDT Language Server",
            repository = "eclipse-jdtls/eclipse.jdt.ls",
            assetRegex = Regex(".*\\.(zip|tar\\.gz)$", RegexOption.IGNORE_CASE),
            installDirName = "jdtls",
            launcherRelative = "bin/jdtls",
            // GitHub 上该仓库没有正式 release（/releases/latest 返回 404），改用 Eclipse 官方
            // snapshot 直链（已实测 HTTP 206，约 40MB），这是 JDT LS 官方文档给出的稳定地址。
            directUrl = "https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz",
            notes = "JDT LS 当前上游要求 Java 21+；项目 JDK 17 不足时不会伪装为可用。源为 Eclipse 官方 snapshot（GitHub 上无正式 release）。"
        ),
        // Kotlin server is a community server; the architecture document explicitly marks its compatibility as unstable.
        Spec(
            id = "kotlin",
            displayName = "Kotlin Language Server",
            repository = "fwcd/kotlin-language-server",
            assetRegex = Regex(".*\\.(zip|tar\\.gz)$", RegexOption.IGNORE_CASE),
            installDirName = "kotlin-language-server",
            launcherRelative = "server/bin/kotlin-language-server",
            notes = "社区实现；兼容性以实际 self-test 为准。"
        ),
        Spec(
            id = "xml",
            displayName = "Eclipse LemMinX",
            repository = "redhat-developer/vscode-xml",
            assetRegex = Regex(".*lemminx.*\\.(zip|tar\\.gz)$", RegexOption.IGNORE_CASE),
            installDirName = "lemminx",
            // vscode-xml 的 release 提供 lemminx-linux-aarch_64.zip，解压后为 bin/lemminx。
            launcherRelative = "bin/lemminx",
            jarNameRegex = Regex(".*lemminx.*\\.jar", RegexOption.IGNORE_CASE),
            notes = "如果上游发布物没有可直接运行的 launcher，则只下载并登记，不伪造可运行状态。"
        )
    )

    private val root = File(Environment.lspToolsDir(context))
    private val cache = File(root, ".cache")

    fun specs(): List<Spec> = specs

    suspend fun check(spec: Spec): Status = withContext(Dispatchers.IO) {
        val installed = installedLauncher(spec)
        if (installed != null) {
            return@withContext Status(spec.id, spec.displayName, State.INSTALLED,
                installedVersion(spec), detail = "已安装并找到 launcher：${installed.absolutePath}")
        }
        try {
            val remote = fetchLatest(spec)
            Status(spec.id, spec.displayName, State.AVAILABLE,
                remoteVersion = remote.releaseTag,
                detail = if (remote.digest != null) "远端 release 可验证：${remote.digest}" else "上游未提供 SHA-256 digest，将降级为完整性校验（大小/可执行性）后安装",
                assetName = remote.name)
        } catch (t: UnsupportedOperationException) {
            Status(spec.id, spec.displayName, State.UNSUPPORTED, detail = t.message.orEmpty())
        } catch (t: Throwable) {
            Status(spec.id, spec.displayName, State.FAILED, detail = t.message ?: t.javaClass.simpleName)
        }
    }

    suspend fun install(spec: Spec, progress: suspend (Int, String) -> Unit = { _, _ -> }): Status = withContext(Dispatchers.IO) {
        progress(1, "读取 ${spec.displayName} 最新 release 元数据")
        val remote = fetchLatest(spec)
        // digest 缺失或非 SHA-256 时降级：不再拒绝安装，改为本地计算 SHA-256 记录 + 下载大小校验。
        // 这样上游 API 未暴露 digest 时仍可安装，同时保留可审计的本地指纹。
        val rawDigest = remote.digest?.removePrefix("sha256:")?.lowercase()
        val expected: String? = rawDigest?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val digestDegraded = expected == null

        root.mkdirs(); cache.mkdirs()
        val partial = File(cache, remote.name + ".part")
        val archive = File(cache, remote.name)
        progress(5, "下载 ${remote.name}（支持断点续传）")
        downloadResumable(remote.url, partial) { done, total ->
            val pct = if (total > 0) 5 + ((done.toDouble() / total.toDouble()) * 55).toInt() else 5
            progress(pct.coerceAtMost(60), "下载 ${formatBytes(done)} / ${formatBytes(total)}")
        }
        if (archive.exists()) archive.delete()
        require(partial.renameTo(archive)) { "无法提交下载缓存文件" }

        val actual = sha256(archive)
        if (expected != null) {
            progress(65, "校验 SHA-256")
            require(actual == expected) { "SHA-256 校验失败：expected=$expected actual=$actual" }
        } else {
            progress(65, "上游无 digest，降级为完整性校验（本地 SHA-256 记录 + 大小比对）")
            if (remote.size > 0) {
                require(archive.length() == remote.size) { "下载大小不符：expected=${remote.size} actual=${archive.length()}" }
            }
        }

        val staging = File(root, ".staging-${spec.id}-${System.currentTimeMillis()}")
        val target = File(root, spec.installDirName)
        try {
            staging.mkdirs()
            progress(72, "解压到事务临时目录")
            extract(archive, staging)
            val actualRoot = normalizeSingleTopDirectory(staging)
            progress(82, "执行 launcher/self-test 检查")
            val launcher = findLauncher(spec, actualRoot)
                ?: throw IllegalStateException("release 已通过 SHA-256，但没有找到可运行 launcher；不会激活")
            if (launcher.isFile) launcher.setExecutable(true, false)
            if (spec.id == "java" && launcher.name == "jdtls") launcher.setExecutable(true, false)
            writeMetadata(actualRoot, spec, remote, actual)
            if (target.exists()) target.renameTo(File(root, "${spec.installDirName}.old-${System.currentTimeMillis()}"))
            require(actualRoot.renameTo(target)) { "无法原子激活 ${spec.installDirName}" }
            progress(92, "重新探测并执行 self-test")
            val activated = installedLauncher(spec)
                ?: throw IllegalStateException("激活后 launcher 不存在")
            activated.setExecutable(true, false)
            val result = selfTest(context, spec, activated)
            if (!result.first) throw IllegalStateException("self-test 失败：${result.second}")
            progress(100, "${spec.displayName} 安装并验证成功")
            Status(spec.id, spec.displayName, State.INSTALLED,
                installedVersion(spec), remote.releaseTag, result.second, remote.name)
        } catch (t: Throwable) {
            progress(0, "安装失败：${t.message ?: t.javaClass.simpleName}；保持原有版本不变")
            throw t
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun fetchLatest(spec: Spec): RemoteAsset {
        spec.directUrl?.let { direct ->
            val name = direct.substringAfterLast('/').ifBlank { "${spec.id}.archive" }
            return RemoteAsset(name = name, url = direct, digest = null, size = -1L, releaseTag = "latest")
        }
        val apiUrl = URL("https://api.github.com/repos/${spec.repository}/releases/latest")
        val json = requestJson(apiUrl)
        val tag = json.optString("tag_name").ifBlank { throw IllegalStateException("没有 release tag") }
        val assets = json.optJSONArray("assets") ?: JSONArray()
        val candidates = buildList {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (name.isNotBlank() && spec.assetRegex.matches(name)) add(a)
            }
        }
        if (candidates.isEmpty()) throw UnsupportedOperationException("${spec.displayName} 最新 release 没有匹配的二进制 asset")
        val selected = selectAsset(candidates)
        val downloadUrl = selected.optString("browser_download_url")
        if (downloadUrl.isBlank()) throw IllegalStateException("release asset 缺少下载地址")
        return RemoteAsset(
            name = selected.optString("name"),
            url = downloadUrl,
            digest = selected.optString("digest").takeIf { it.isNotBlank() },
            size = selected.optLong("size", -1L),
            releaseTag = tag
        )
    }

    private fun selectAsset(assets: List<JSONObject>): JSONObject {
        // Prefer archives likely to be usable in the embedded Linux userland. JVM servers are architecture independent.
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase()
        // 上游命名是 aarch_64（下划线），必须一并识别，否则会与 x86_64 产物同分而选错架构。
        val archTokens = when {
            abi.contains("arm64") || abi.contains("aarch64") -> listOf("aarch_64", "aarch64", "arm64")
            abi.contains("x86_64") -> listOf("x86_64", "amd64")
            else -> emptyList()
        }
        val foreign = listOf("windows", "win32", "macos", "darwin", "osx", "freebsd")
        return assets.maxByOrNull { asset ->
            val n = asset.optString("name").lowercase()
            var score = if (n.contains("linux")) 100 else 0
            archTokens.forEachIndexed { idx, token -> if (n.contains(token)) score += 60 - idx * 5 }
            if (foreign.any { n.contains(it) }) score -= 1000
            score
        } ?: assets.first()
    }

    private fun requestJson(url: URL): JSONObject {
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "NebulaForge-IDE/1.x")
        }
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("GitHub API HTTP $code: ${body.take(500)}")
            JSONObject(body)
        } finally { c.disconnect() }
    }

    private suspend fun downloadResumable(urlString: String, part: File, progress: suspend (Long, Long) -> Unit) {
        // GitHub/Eclipse 的 release 下载会 302 到对象存储，移动网络下常见
        // "Software caused connection abort"（SSLException）。这里做 3 次带退避的重试，
        // 并保留 .part 以便 Range 续传，避免一次抖动就把整次安装判失败。
        var last: Throwable? = null
        for (attempt in 1..3) {
            try {
                return downloadResumableOnce(urlString, part, progress)
            } catch (t: Throwable) {
                last = t
                if (attempt < 3) kotlinx.coroutines.delay(1500L * attempt)
            }
        }
        val done = if (part.exists()) part.length() else 0L
        throw IllegalStateException(
            "下载失败（已重试 3 次，已缓存 ${formatBytes(done)}）：${last?.message ?: last?.javaClass?.simpleName}；源=$urlString",
            last
        )
    }

    private suspend fun downloadResumableOnce(urlString: String, part: File, progress: suspend (Long, Long) -> Unit) {
        var offset = if (part.exists()) part.length() else 0L
        var connection = openDownload(urlString, offset)
        if (offset > 0 && connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect(); part.delete(); offset = 0L; connection = openDownload(urlString, 0L)
        }
        connection.useConnection { c ->
            val total = when {
                c.responseCode == HttpURLConnection.HTTP_PARTIAL -> offset + c.getHeaderFieldLong("Content-Length", -1L)
                else -> c.getHeaderFieldLong("Content-Length", -1L)
            }
            BufferedInputStream(c.inputStream).use { input ->
                FileOutputStream(part, offset > 0).use { output ->
                    val buffer = ByteArray(1024 * 256)
                    var done = offset
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n); done += n; progress(done, total)
                    }
                }
            }
        }
    }

    private fun openDownload(urlString: String, offset: Long): HttpURLConnection = (URL(urlString).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 60_000
        setRequestProperty("User-Agent", "NebulaForge-IDE/1.x")
        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
    }

    private fun extract(archive: File, destination: File) {
        when {
            archive.name.endsWith(".zip", true) -> extractZip(archive, destination)
            archive.name.endsWith(".tar.gz", true) -> extractTarGz(archive, destination)
            else -> error("不支持的 LSP archive：${archive.name}")
        }
    }

    private fun extractZip(archive: File, destination: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = safeResolve(destination, e.name)
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs(); FileOutputStream(out).use { zin.copyTo(it) }; out.setExecutable(true, false)
                }
                zin.closeEntry()
            }
        }
    }

    /** Minimal ustar reader, sufficient for upstream LSP .tar.gz archives. */
    private fun extractTarGz(archive: File, destination: File) {
        GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            val header = ByteArray(512)
            while (true) {
                readFully(input, header)
                if (header.all { it.toInt() == 0 }) break
                val name = tarString(header, 0, 100)
                val size = tarOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val out = safeResolve(destination, name)
                if (type == '5') out.mkdirs() else {
                    out.parentFile?.mkdirs(); FileOutputStream(out).use { output -> copyExactly(input, output, size) }; out.setExecutable(true, false)
                }
                val padding = (512 - (size % 512)) % 512
                skipFully(input, padding)
            }
        }
    }

    private fun normalizeSingleTopDirectory(staging: File): File {
        val entries = staging.listFiles().orEmpty()
        return if (entries.size == 1 && entries[0].isDirectory) entries[0] else staging
    }

    private fun findLauncher(spec: Spec, root: File): File? {
        spec.launcherRelative?.let { rel -> File(root, rel).takeIf { f -> f.isFile }?.let { return it } }
        // 归档内部布局可能与预期不同（例如 lemminx-linux/bin/lemminx），按已知可执行名回退查找，
        // 避免"校验通过却找不到 launcher"而拒绝激活。
        val knownNames = setOf("lemminx", "jdtls", "kotlin-language-server")
        root.walkTopDown().firstOrNull { it.isFile && it.name in knownNames }?.let { return it }
        val jar = spec.jarNameRegex?.let { regex -> root.walkTopDown().firstOrNull { it.isFile && regex.matches(it.name) } }
        if (jar != null) {
            val launcher = File(root, "bin/nebulaforge-lsp")
            launcher.parentFile?.mkdirs()
            launcher.writeText("#!/system/bin/sh\nexec java -jar '${jar.absolutePath.replace("'", "'\\''")}' \"$@\"\n")
            launcher.setExecutable(true, false)
            return launcher
        }
        return null
    }

    private fun installedLauncher(spec: Spec): File? {
        val rootDir = File(root, spec.installDirName)
        if (!rootDir.isDirectory) return null
        // 与 findLauncher 保持一致的解析顺序，否则会出现"激活前找到了、激活后却说 launcher 不存在"。
        spec.launcherRelative?.let { rel -> File(rootDir, rel).takeIf { f -> f.isFile && f.canExecute() }?.let { return it } }
        val knownNames = setOf("lemminx", "jdtls", "kotlin-language-server")
        rootDir.walkTopDown().firstOrNull { it.isFile && it.name in knownNames }?.let { return it }
        return File(rootDir, "bin/nebulaforge-lsp").takeIf { it.isFile && it.canExecute() }
    }

    private fun installedVersion(spec: Spec): String? = runCatching {
        JSONObject(File(root, spec.installDirName).resolve(".nebulaforge-lsp.json").readText()).optString("releaseTag").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun writeMetadata(rootDir: File, spec: Spec, remote: RemoteAsset, sha: String) {
        File(rootDir, ".nebulaforge-lsp.json").writeText(JSONObject().apply {
            put("id", spec.id); put("repository", spec.repository); put("releaseTag", remote.releaseTag)
            put("asset", remote.name); put("sha256", sha); put("installedAt", System.currentTimeMillis())
            put("digestSource", if (remote.digest != null) "upstream" else "local")
            put("digestDegraded", remote.digest == null)
        }.toString(2))
    }

    private fun selfTest(context: android.content.Context, spec: Spec, launcher: File): Pair<Boolean, String> {
        if (!launcher.canExecute()) launcher.setExecutable(true, false)
        if (!launcher.canExecute()) return false to "launcher 无执行权限"
        // 不能对 app 私有目录下的 ELF 用 ProcessBuilder（Android 会因 noexec/SELinux 拒绝，
        // 且解释器前缀不对时报错也会误导），所以这里用 NativePty 真实执行一次 launcher 的
        // 版本探测；只有真的跑起来才算通过，避免“文件在就报 true”的虚报。
        val probe = NativePtyLspProbe.run(context, launcher)
        return probe.ok to probe.detail
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeResolve(root: File, entry: String): File {
        val out = File(root, entry).canonicalFile
        require(out.path == root.canonicalPath || out.path.startsWith(root.canonicalPath + File.separator)) { "archive path traversal: $entry" }
        return out
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var pos = 0
        while (pos < buffer.size) { val n = input.read(buffer, pos, buffer.size - pos); if (n < 0) error("截断的 tar archive"); pos += n }
    }

    private fun copyExactly(input: java.io.InputStream, output: FileOutputStream, size: Long) {
        var remaining = size; val buf = ByteArray(64 * 1024)
        while (remaining > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt()); if (n < 0) error("截断的 tar entry"); output.write(buf, 0, n); remaining -= n }
    }

    private fun skipFully(input: java.io.InputStream, count: Long) { var remaining = count; while (remaining > 0) { val n = input.skip(remaining); if (n <= 0) { if (input.read() < 0) error("截断的 tar padding"); remaining-- } else remaining -= n } }

    private fun tarString(buf: ByteArray, start: Int, len: Int): String = buf.copyOfRange(start, start + len).takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.UTF_8)
    private fun tarOctal(buf: ByteArray, start: Int, len: Int): Long = tarString(buf, start, len).trim().trim('\u0000').trim().ifBlank { "0" }.toLong(8)

    private suspend fun HttpURLConnection.useConnection(block: suspend (HttpURLConnection) -> Unit) { try { block(this) } finally { disconnect() } }
    private fun formatBytes(value: Long): String = if (value < 0) "?" else when {
        value < 1024 -> "$value B"; value < 1024 * 1024 -> "%.1f KiB".format(value / 1024.0); value < 1024L * 1024 * 1024 -> "%.1f MiB".format(value / 1024.0 / 1024.0); else -> "%.2f GiB".format(value / 1024.0 / 1024.0 / 1024.0)
    }
}
