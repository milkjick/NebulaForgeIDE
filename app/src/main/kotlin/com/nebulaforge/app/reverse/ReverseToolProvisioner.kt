package com.nebulaforge.app.reverse

import android.content.Context
import com.nebulaforge.core.toolchain.ToolchainComponent
import com.nebulaforge.core.toolchain.ToolchainManager
import com.nebulaforge.core.toolchain.ToolchainState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

/**
 * 进度回调：`percent` 为 0..100 的百分比（`-1` 表示「进行中但无法估算」），
 * `message` 是当前阶段或底层日志行（apt 输出、镜像名、文件名等）。
 *
 * 界面拿这两个值就能渲染进度条 + 一行实时说明，解决「点了安装之后什么也看不到」。
 */
typealias ProgressListener = (percent: Int, message: String) -> Unit

/**
 * 逆向工具安装器：优先走 IDE 工具链（Termux apt 仓库），再回退内置 assets / 多镜像下载 / 本地文件。
 *
 * ## 为什么要有「多镜像 + 断点续传 + 本地文件 + 进度」
 * 这台设备上实测过：GitHub Release 的 40MB 包（jadx）经常在中途断流，
 * 单次 120s 读超时直接抛错，而 `.part` 临时文件每次都被丢弃 → 用户点多少次「安装 JADX」都失败，
 * 且只看到一句笼统的「安装失败」。所以这里：
 * 1. 逐个镜像重试（GitHub 直连 + 常用 GitHub 代理），失败原因全部回传；
 * 2. 每次重试**带 Range 头续传**已下载的 `.part`，而不是从头再来；
 * 3. **每次读写都上报进度**（字节数 / 总量），界面能显示「已下载 8.3/40.1 MB」；
 * 4. 解包失败时保留压缩包（便于排查/二次利用），不再无条件删除；
 * 5. 网络彻底不通时，用户可以从本地文件（zip/jar）安装，这是唯一不依赖网络的成功路径。
 */
class ReverseToolProvisioner(
    private val context: Context,
    /**
     * 可选：IDE 工具链管理器。传入后 apktool / JADX **优先走 Termux 仓库（apt）安装**。
     *
     * 真机实测：GitHub Release 直连与 ghproxy 镜像都会在 2.0MB / 2.5MB 处断流，
     * `.part` 永远是半截文件，用户点多少次「安装」都失败；而 Termux main（清华镜像）
     * 里有 aarch64 可用的 apktool 3.0.3 / jadx 1.5.6，apt 通道在该设备上已被验证可用。
     */
    private val toolchain: ToolchainManager? = null
) {
    data class Result(val success: Boolean, val message: String)

    /** 仅用于并发转发 apt 日志 / 上报进度，随进程存活，无需显式释放。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 最近一次 apt 路径失败的原因，回退到下载失败时把它一起告诉用户。 */
    @Volatile
    private var aptFailure: String? = null

    private val root get() = File(com.nebulaforge.core.environment.Environment.homeRoot(context), ".nebulaforge/tools").apply { mkdirs() }

    suspend fun installApktool(onProgress: ProgressListener? = null): Result = withContext(Dispatchers.IO) {
        aptFailure = null
        onProgress?.invoke(3, "开始安装 apktool（先尝试 Termux 仓库）…")
        // 1) 首选 Termux 仓库（apt）：包里就是 usr/share/java/apktool.jar（+ usr/bin/apktool），
        //    把 jar 链接过来后 ApkReverseEngine 的 `java -jar` 路径零改动即可用。
        aptInstall(ToolchainComponent.APKTOOL, "apktool", apktoolJarInPrefix(), File(root, "apktool.jar"), onProgress)
            ?.let { return@withContext it }
        val reason = aptFailure?.let { "Termux 仓库不可用（$it），改用内置/网络下载。" }
        reason?.let { onProgress?.invoke(10, it) }
        // 2) 回退：内置 assets → 多镜像下载（带 Range 断点续传）→ 页面上的「本地文件安装」
        val target = File(root, "apktool.jar")
        runCatching {
            copyAssetIfPresent("tools/apktool.jar", target)
            if (target.isFile) {
                onProgress?.invoke(100, "使用随包内置的 apktool.jar")
            } else {
                downloadFirstAvailable(APKTOOL_URLS, target, onProgress)
                onProgress?.invoke(96, "校验 SHA-256 …")
                verifySha256(target, APKTOOL_SHA256)
            }
            onProgress?.invoke(100, "apktool 3.0.3 已安装（${target.length() / 1024} KB）")
            Result(true, "apktool 3.0.3 已安装")
        }.getOrElse {
            onProgress?.invoke(-1, "apktool 安装失败：${describe(it)}")
            Result(false, "apktool 安装失败：${describe(it)}")
        }
    }

    suspend fun installJadx(onProgress: ProgressListener? = null): Result = withContext(Dispatchers.IO) {
        aptFailure = null
        onProgress?.invoke(3, "开始安装 JADX（先尝试 Termux 仓库）…")
        // 1) 首选 Termux 仓库（apt）：jadx 包是 78MB 的 all-jar（含 CLI 主类 jadx.cli.JadxCLI）。
        aptInstall(ToolchainComponent.JADX, "jadx", jadxJarInPrefix(), File(root, "jadx-cli.jar"), onProgress)
            ?.let { return@withContext it }
        val reason = aptFailure?.let { "Termux 仓库不可用（$it），改用内置/网络下载。" }
        reason?.let { onProgress?.invoke(10, it) }
        // 2) 回退：内置 assets → 多镜像下载 → 本地文件安装
        val marker = File(root, "jadx-cli.jar")
        runCatching {
            copyAssetIfPresent("tools/jadx-cli.jar", marker)
            if (marker.isFile) {
                onProgress?.invoke(100, "使用随包内置的 jadx-cli.jar")
            } else {
                val zip = File(root, JADX_ZIP_NAME)
                downloadFirstAvailable(JADX_URLS, zip, onProgress)
                onProgress?.invoke(88, "解包 ${zip.name}（${zip.length() / 1024 / 1024} MB）…")
                val names = mutableListOf<String>()
                val chosen = extractJadx(zip, root, names) { percent ->
                    onProgress?.invoke(88 + percent / 8, "解包中 $percent%")
                }
                requireNotNull(chosen) {
                    "jadx 压缩包内没有可运行的 jar（共 ${names.size} 个条目，前几个：${names.take(6).joinToString()}）"
                }
                // 只有解包成功才清掉下载包；失败时保留，便于续传或换成「本地文件安装」。
                zip.delete()
            }
            require(marker.isFile) { "JADX CLI 未找到" }
            onProgress?.invoke(100, "JADX CLI 已安装（${marker.length() / 1024} KB）")
            Result(true, "JADX CLI 已安装（${marker.length() / 1024} KB）")
        }.getOrElse {
            onProgress?.invoke(-1, "JADX 安装失败：${describe(it)}")
            Result(false, "JADX 安装失败：${describe(it)}")
        }
    }

    // ------------------------------------------------------------------ Termux 仓库安装（首选路径）

    /**
     * 先用 apt 装包，再把包里的 jar 链接到逆向工具目录。
     *
     * 为什么不直接让引擎执行 `apktool` / `jadx` 命令：`ApkReverseEngine` 用 `java -jar` /
     * `java -cp <jar> jadx.cli.JadxCLI`（显式主类，避免 jadx 误进 GUI），链接 jar 后引擎与
     * 判据（`ToolStatus.apktoolJar` / `jadxJar`）都不用改，也避免 apt 包装脚本差异。
     *
     * 安装期间会**实时把工具链 liveLog 的新增行转发给 onProgress**：apt 的 `Get:` / `Unpacking`
     * 进度因此能直接显示在逆向页上，而不是只出现在「工具链」页。
     *
     * @return 走通 apt 路径返回结果；未注入 toolchain、apt 装失败、或包内没有预期 jar 时返回 null，由调用方回退下载。
     */
    private suspend fun aptInstall(
        component: ToolchainComponent,
        pkg: String,
        sourceJar: File?,
        target: File,
        onProgress: ProgressListener?
    ): Result? {
        val manager = toolchain ?: return null
        onProgress?.invoke(5, "通过 Termux 仓库（apt）安装 $pkg …")
        val seen = AtomicInteger(manager.liveLog.value.size)
        val highest = AtomicInteger(6)
        val forwarder: Job? = if (onProgress != null) scope.launch {
            while (isActive) {
                val lines = manager.liveLog.value
                if (lines.size > seen.get()) {
                    lines.drop(seen.get()).takeLast(4).forEach { raw ->
                        val line = raw.trim()
                        if (line.isNotEmpty()) {
                            val stage = aptStagePercent(line)
                            if (stage > highest.get()) highest.set(stage)
                            runCatching { onProgress(highest.get(), line.take(160)) }
                        }
                    }
                    seen.set(lines.size)
                }
                delay(300)
            }
        } else null

        val status = try {
            runCatching { manager.install(component) }.getOrElse { t ->
                aptFailure = describe(t)
                return null
            }
        } finally {
            forwarder?.cancel()
        }
        if (status.state != ToolchainState.READY) {
            aptFailure = "状态 ${status.state}${status.detail.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}"
            return null
        }
        val source = sourceJar?.takeIf { it.isFile }
        if (source == null) {
            aptFailure = "$pkg 已安装，但未在包目录里找到 jar"
            return null
        }
        return runCatching {
            onProgress?.invoke(94, "链接 ${source.name} → ${target.name}")
            linkOrCopy(source, target)
            onProgress?.invoke(100, "$pkg 已从 Termux 仓库就绪（${source.length() / 1024} KB）")
            Result(true, "$pkg 已通过 Termux 仓库安装（${source.length() / 1024} KB），可直接反编译")
        }.getOrElse { Result(false, "$pkg 已安装，但无法把 ${source.name} 放到 ${target.name}：${describe(it)}") }
    }

    /**
     * 把 apt 的一行输出映射成一个粗略百分比。
     *
     * apt 本身不打百分比，但它的阶段关键字是稳定的（Reading → Get → Unpacking → Setting up），
     * 用它推一个「只增不减」的进度，用户至少能看出安装确实在往前走。
     */
    private fun aptStagePercent(line: String): Int {
        val l = line.lowercase()
        return when {
            l.contains("setting up") -> 88
            l.contains("unpacking") -> 70
            l.contains("get:") || l.contains("已下载") || l.contains("下载") -> 45
            l.contains("reading package") || l.contains("正在读取") -> 15
            l.contains("reading database") -> 25
            l.contains("done") || l.contains("完成") -> 95
            else -> 0
        }
    }

    /** 优先符号链接：jadx 的 all-jar 有 78MB，复制既慢又双份占空间；失败再退回复制。 */
    private fun linkOrCopy(source: File, target: File) {
        target.delete()
        val linked = runCatching {
            android.system.Os.symlink(source.absolutePath, target.absolutePath)
            true
        }.getOrDefault(false)
        if (linked && target.exists()) return
        source.copyTo(target, overwrite = true)
    }

    /** apt 装的 apktool jar：`$PREFIX/share/java/apktool.jar`。 */
    private fun apktoolJarInPrefix(): File? =
        File(com.nebulaforge.core.environment.Environment.usrRoot(context), "share/java/apktool.jar")
            .takeIf { it.isFile }

    /**
     * apt 装的 jadx jar：`$PREFIX/share/java/jadx-<version>-all.jar`。
     * 版本号会变，用通配匹配取最新那个，避免 jadx 升级后又「找不到工具」。
     */
    private fun jadxJarInPrefix(): File? =
        File(com.nebulaforge.core.environment.Environment.usrRoot(context), "share/java").listFiles()
            ?.filter { it.isFile && it.name.startsWith("jadx-") && it.name.endsWith("-all.jar") }
            ?.maxByOrNull { it.lastModified() }

    // ------------------------------------------------------------------ 本地文件安装（无网络兜底）

    /**
     * 用用户选中的本地文件安装 JADX：既接受官方发行 zip（jadx-x.y.z.zip），也接受单个 jar。
     * 这条路径不依赖网络，是设备网络受限时的唯一可靠装法。
     */
    suspend fun installJadxFromLocal(source: File): Result = withContext(Dispatchers.IO) {
        runCatching {
            require(source.isFile) { "文件不存在：${source.name}" }
            val marker = File(root, "jadx-cli.jar")
            if (source.name.endsWith(".zip", ignoreCase = true)) {
                val names = mutableListOf<String>()
                val chosen = extractJadx(source, root, names)
                requireNotNull(chosen) {
                    "压缩包里没有 jadx 的 jar（共 ${names.size} 个条目，前几个：${names.take(6).joinToString()}）"
                }
                Result(true, "JADX CLI 已从 ${source.name} 安装（${marker.length() / 1024} KB）")
            } else {
                // 单个 jar：必须是可解析的 zip（jar 就是 zip），否则明确报错而不是装个坏文件。
                val entries = runCatching { zipEntryCount(source) }.getOrElse { -1 }
                require(entries > 0) { "不是有效的 jar（无法作为 zip 解析）：${source.name}" }
                commit(source, marker)
                Result(true, "JADX CLI 已从 ${source.name} 安装（${marker.length() / 1024} KB）")
            }
        }.getOrElse { Result(false, "本地安装 JADX 失败：${describe(it)}") }
    }

    suspend fun installApktoolFromLocal(source: File): Result = withContext(Dispatchers.IO) {
        runCatching {
            require(source.isFile) { "文件不存在：${source.name}" }
            val entries = runCatching { zipEntryCount(source) }.getOrElse { -1 }
            require(entries > 0) { "不是有效的 jar：${source.name}" }
            commit(source, File(root, "apktool.jar"))
            Result(true, "apktool 已从 ${source.name} 安装")
        }.getOrElse { Result(false, "本地安装 apktool 失败：${describe(it)}") }
    }

    // ------------------------------------------------------------------ 下载

    /** 依次尝试多个镜像；全部失败时把每个镜像的错误都带上（否则用户无从判断是网络还是包的问题）。 */
    private fun downloadFirstAvailable(urls: List<String>, target: File, onProgress: ProgressListener?) {
        val failures = mutableListOf<String>()
        urls.forEachIndexed { index, url ->
            val label = "镜像 ${index + 1}/${urls.size}：${hostOf(url)}"
            onProgress?.invoke(0, "尝试 $label")
            // 注意：必须具名传 onProgress —— downloadWithResume 的最后一个参数是 attempts: Int，
            // 用尾随 lambda 会把回调绑到 attempts 上（编译不过，就算过了也会静默丢进度）。
            runCatching {
                downloadWithResume(url, target, onProgress = { percent, message ->
                    onProgress?.invoke(percent, "[$label] $message")
                })
            }
                .onSuccess {
                    onProgress?.invoke(92, "$label 下载完成")
                    return
                }
                .onFailure { failures += "${hostOf(url)} → ${describe(it)}" }
        }
        error("所有下载源都失败：${failures.joinToString("；")}")
    }

    /**
     * 单源下载：失败可重试，重试时用 Range 续传已有 `.part`。
     * 下载完成后先写 `.part` 再 rename 提交，避免半截文件被当成装好了。
     *
     * 这里用手写循环（而不是 `copyTo`）就是为了能回调**字节级进度**：
     * 界面会显示「已下载 8.3/40.1 MB」，而不是一个转圈图标等上几分钟。
     */
    private fun downloadWithResume(
        url: String,
        target: File,
        onProgress: ProgressListener? = null,
        attempts: Int = 3
    ) {
        val tmp = File(target.parentFile, target.name + ".part")
        var last: Throwable? = null
        repeat(attempts) { attempt ->
            val outcome = runCatching {
                val have = if (tmp.isFile) tmp.length() else 0L
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = JADX_READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept-Encoding", "identity")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                conn.connect()
                val code = conn.responseCode
                require(code in 200..299) { "HTTP $code" }
                val resuming = have > 0 && code == HttpURLConnection.HTTP_PARTIAL
                if (!resuming) tmp.delete()
                val alreadyHave = if (resuming) have else 0L
                val total = conn.contentLengthLong.let { if (it > 0) it + alreadyHave else -1L }
                var written = alreadyHave
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                            written += n
                            if (written - lastReport >= 256 * 1024) {
                                lastReport = written
                                onProgress?.invoke(downloadPercent(written, total), downloadText(written, total))
                            }
                        }
                        output.flush()
                    }
                }
                conn.disconnect()
                require(tmp.length() > 0) { "下载文件为空" }
                commit(tmp, target)
            }
            if (outcome.isSuccess) return
            last = outcome.exceptionOrNull()
            if (attempt < attempts - 1) {
                onProgress?.invoke(-1, "第 ${attempt + 1} 次失败：${describe(last!!)}，将从断点重试…")
                Thread.sleep(800)
            }
        }
        throw last ?: IllegalStateException("下载失败")
    }

    private fun downloadPercent(written: Long, total: Long): Int =
        if (total > 0) ((written * 90) / total).toInt().coerceIn(0, 90) else -1

    private fun downloadText(written: Long, total: Long): String =
        if (total > 0) "已下载 ${mb(written)} / ${mb(total)}" else "已下载 ${mb(written)}"

    private fun mb(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

    private fun commit(from: File, target: File) {
        if (from.renameTo(target)) return
        target.delete()
        require(from.renameTo(target)) { "无法提交工具文件到 ${target.name}" }
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

    // ------------------------------------------------------------------ 解包

    /**
     * 从 jadx 发行 zip 里挑出**可独立运行的那个 jar**写入 `jadx-cli.jar`。
     *
     * 官方包内的命名换过好几轮（`jadx-cli.jar` / `jadx-cli-<v>.jar` / `jadx-<v>-all.jar`），
     * 旧实现只认前两种，于是「下载成功、解包命中 0 个条目」，用户看到的就是一句
     * 「JADX CLI 未找到」——包没问题，是匹配规则太窄。这里改为按名字打分取最优，
     * 并把条目清单回传给调用方用于报错。
     *
     * 两遍扫描：第一遍只读条目名选目标，第二遍把选中条目流式写盘（避免把 40MB 读进内存）。
     */
    private fun extractJadx(
        zip: File,
        root: File,
        names: MutableList<String>,
        onProgress: ((Int) -> Unit)? = null
    ): String? {
        var best: String? = null
        var bestScore = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/')
                    names += name
                    val score = jadxJarScore(name)
                    if (score > bestScore) { bestScore = score; best = name }
                }
                zis.closeEntry()
            }
        }
        val chosen = best ?: return null
        val target = File(root, "jadx-cli.jar")
        val tmp = File(root, "jadx-cli.jar.part")
        tmp.delete()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory && entry.name.replace('\\', '/') == chosen) {
                    val expected = entry.size
                    var written = 0L
                    tmp.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = zis.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (expected > 0) onProgress?.invoke(((written * 100) / expected).toInt().coerceIn(0, 100))
                        }
                    }
                    break
                }
                zis.closeEntry()
            }
        }
        require(tmp.isFile && tmp.length() > 0) { "解包失败：$chosen" }
        commit(tmp, target)
        return chosen
    }

    /**
     * 打分：命令行（CLI）可用的 jar 优先，GUI 包垫底。
     *
     * 旧实现把 `-all` 排在最前，理由是「自带依赖，可直接 java -jar」——但 jadx 官方发行包里
     * `jadx-gui-<v>-all.jar` 同样是 `-all`，它的 Main-Class 是 `jadx.gui.JadxGUI`：设备上跑
     * `java -jar` 会进 GUI 初始化，无字体配置时抛 `ExceptionInInitializerError: Fontconfig head is null`，
     * 用户看到的就是「JADX 装了却用不了」。因此 CLI 包最高分，GUI 包最低分。
     */
    private fun jadxJarScore(name: String): Int {
        val file = name.substringAfterLast('/')
        if (!file.endsWith(".jar", ignoreCase = true)) return 0
        if (!file.contains("jadx", ignoreCase = true)) return 0
        val gui = file.contains("gui", ignoreCase = true)
        val all = file.contains("all", ignoreCase = true)
        val cli = file.contains("cli", ignoreCase = true)
        return when {
            cli && all -> 5
            cli -> 4
            gui -> 1
            all -> 3
            else -> 2
        }
    }

    private fun zipEntryCount(zip: File): Int {
        var n = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) n++
                zis.closeEntry()
            }
        }
        return n
    }

    private fun copyAssetIfPresent(asset: String, target: File) {
        val input = runCatching { context.assets.open(asset) }.getOrNull() ?: return
        input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
    }

    private fun verifySha256(file: File, expected: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n <= 0) break; digest.update(buffer, 0, n) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual.equals(expected, true)) { "SHA-256 校验失败：$actual" }
    }

    /** 异常信息尽量带上类型：`it.message` 为 null 时（如 EOFException）不能只显示「安装失败」。 */
    private fun describe(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName

    companion object {
        const val APKTOOL_SHA256 = "dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423"

        private const val JADX_ZIP_NAME = "jadx-download.zip"
        private const val JADX_READ_TIMEOUT_MS = 300_000

        /** GitHub 直连 + 常用代理镜像（国内网络下直连断流概率很高，镜像越多成功率越高）。 */
        private val JADX_URLS = listOf(
            "https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip",
            "https://ghproxy.net/https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip",
            "https://gh-proxy.com/https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip",
            "https://ghfast.top/https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip",
            "https://hub.gitmirror.com/https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip",
            "https://mirror.ghproxy.com/https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip"
        )

        private val APKTOOL_URLS = listOf(
            "https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
            "https://ghproxy.net/https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
            "https://gh-proxy.com/https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
            "https://ghfast.top/https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
            "https://hub.gitmirror.com/https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar",
            "https://mirror.ghproxy.com/https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar"
        )
    }
}
