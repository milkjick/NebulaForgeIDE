package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 「构建自愈」之六：把 Gradle Wrapper 的**发行版**真正落到本地磁盘。
 *
 * ## 为什么必须有这一步（真机取证，这是原生安卓 / Flutter「都编不过」的最终根因）
 *
 * 构建脚本（`TaskDefinition.gradleInvocation`）选择 Gradle 的逻辑是：
 * 只有当 `$GRADLE_USER_HOME/wrapper/dists/<gradle-x.y-bin>/<hash-of-url>/<…>.zip.ok`
 * **已经存在**时才用工程 wrapper，否则回退到 PATH 上的内置 Gradle。
 *
 * 而 `$GRADLE_USER_HOME/wrapper/dists/` 在本机**从来就是空的**（工具链快照里也没有）：
 *  - 模板不含 wrapper，由 [GradleWrapperBootstrap] 从资产补齐并钉住 Gradle 8.9；
 *  - 唯一能把它下载下来的路径是 `gradlew` 自己，而 `gradlew` 只有在「已经下载好」时才会被调用。
 *
 * → **死锁**：内置 Gradle 是 9.8.0（真机日志），模板 AGP 是 8.6.0，AGP 8.x 不支持 Gradle 9.x。
 * 于是每次构建都走「回退到内置 Gradle」这条路，真机日志表现为：
 * ```
 * [nebula] 使用 IDE 内置 Gradle 构建（wrapper 发行包未缓存，真机下载会断流/永久卡死）
 * FAILURE: Gradle build daemon disappeared unexpectedly
 * [nebula] 进程已退出（code=1，64716 ms）
 * ```
 * 用户看到的就是「模板建出来就编不过，怎么点都不行」。
 *
 * ## 做法：把「下载」从 `gradlew` 手里拿走，改由应用自己**有超时、可重试、可续传**地完成
 *
 * 1. 精确复刻 Gradle `PathAssembler` 的目录算法（`distributionUrl` 的 md5 → BigInteger → base36），
 *    与构建脚本里那段 python **逐字一致**，否则算出的目录名对不上，等于没做；
 * 2. 解包到 `dists/<distName>/<hash>/`，保留 zip 内的 `gradle-x.y/` 顶层目录，
 *    与 Gradle 自己解出来的布局完全相同；
 * 3. 写出 Gradle `Install` 用来判定「已安装」的标记文件 `<zipName>.ok`
 *    —— 正是构建脚本检查的那个文件名，于是 `nb_cached=1` 成立，构建改用工程 wrapper（Gradle 8.9）；
 * 4. 把 zip 镜像一份到公共存储（`/storage/emulated/0/NebulaForge/toolchain/gradle-dist/`，
 *    不随卸载清除），重装后可离线恢复，不必再下 136MB。
 *
 * ## 与既有自愈器的分工
 * - [GradleDistributionMirror]：只把 `distributionUrl` 换成可达的国内镜像
 *   （`services.gradle.org` 在本机网络完全不通）；
 * - 本对象：把那个 URL 对应的**发行包**真正取回本地并安装好。
 *
 * ## 安全性
 * 全程 `runCatching` 包裹，任何失败都只返回一句可读的 note，绝不抛给构建流程；
 * 单次 [ensure] 的下载总耗时受 [DOWNLOAD_BUDGET_MS] 约束，不会让「准备构建」无限期卡住。
 */
object GradleDistributionProvisioner {

    private const val PROPS = "gradle/wrapper/gradle-wrapper.properties"

    /**
     * 公共存储里的发行包镜像目录。
     *
     * 放在这里而不是私有目录，是因为**卸载会清空私有目录**：重装后从快照直接复制即可离线恢复，
     * 无须再走一次 136MB 的网络下载（本机网络下正是「卸载重装后怎么都编不过」的来源）。
     */
    private const val SNAPSHOT_ZIP_DIR = "/storage/emulated/0/NebulaForge/toolchain/gradle-dist"

    /** 实测可达的国内镜像（路径与文件名与官方完全一致，只换 host）。 */
    private val MIRRORS = listOf(
        "https://mirrors.aliyun.com/macports/distfiles/gradle/",
        "https://mirrors.huaweicloud.com/gradle/"
    )

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_ATTEMPTS = 3

    /** 一次「准备构建」在补齐发行包上最多花的时间；超时则放弃并给出明确告警。 */
    private const val DOWNLOAD_BUDGET_MS = 300_000L

    /**
     * 为工程补齐 Gradle 发行版，返回需要展示给用户的说明（无需改动时为空列表）。
     *
     * 同时覆盖两种布局：原生工程（`gradle/…`）与 Flutter 工程（`android/gradle/…`）。
     */
    fun ensure(context: Context, projectRoot: File, allowNetwork: Boolean = false): List<String> {
        if (!projectRoot.isDirectory) return emptyList()
        val propsFiles = listOf(File(projectRoot, PROPS), File(projectRoot, "android/$PROPS"))
            .filter { it.isFile }
        if (propsFiles.isEmpty()) return emptyList()
        val notes = mutableListOf<String>()
        for (props in propsFiles) {
            val note = runCatching { provision(context, props, allowNetwork) }.getOrElse { e ->
                "⚠ Gradle 发行版本地化失败（${props.parentFile?.parentFile?.name}）：" +
                    (e.message ?: e.javaClass.simpleName)
            }
            if (note != null) notes += note
        }
        return notes.distinct()
    }

    /**
     * 只读判定：工程的 wrapper 发行版是否**已经**在本地可用。
     *
     * 供调用方在构建前给出「本次会回退到内置 Gradle」这类明确告警用（不写盘、不联网）。
     */
    fun isReady(context: Context, projectRoot: File): Boolean {
        val props = listOf(File(projectRoot, PROPS), File(projectRoot, "android/$PROPS"))
            .firstOrNull { it.isFile } ?: return false
        val layout = distLayout(context, props) ?: return false
        return File(layout.first, "${layout.second}.ok").isFile
    }

    // ------------------------------------------------------------------ 内部实现

    /** 返回 `dists/<distName>/<hash>` 与 zip 文件名；URL 不合法时返回 null。 */
    private fun distLayout(context: Context, props: File): Pair<File, String>? {
        val url = distributionUrl(props) ?: return null
        val zipName = url.substringAfterLast('/')
        if (!zipName.startsWith("gradle-") || !zipName.endsWith(".zip")) return null
        val distName = zipName.removeSuffix(".zip")
        val hash = runCatching {
            // 与 Gradle PathAssembler.getHash 完全一致：md5(url) 当作无符号大整数转 36 进制。
            BigInteger(1, MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))).toString(36)
        }.getOrNull() ?: return null
        val dir = File(Environment.gradleUserHome(context), "wrapper/dists/$distName/$hash")
        return dir to zipName
    }

    /** 读出并还原 `distributionUrl`（properties 里 `:` 被转义成 `\:`）。 */
    private fun distributionUrl(props: File): String? {
        val text = runCatching { props.readText() }.getOrNull() ?: return null
        return Regex("""distributionUrl\s*=\s*(\S+)""").find(text)
            ?.groupValues?.get(1)
            ?.replace("\\:", ":")
            ?.takeIf { it.startsWith("http") || it.startsWith("file:") }
    }

    private fun provision(context: Context, props: File, allowNetwork: Boolean): String? {
        val layout = distLayout(context, props) ?: return null
        val (distDir, zipName) = layout
        val marker = File(distDir, "$zipName.ok")
        // Gradle Install.isDistInstalled() 认的就是它 —— 命中即代表「本地已装好」，零副作用返回。
        if (marker.isFile) return null
        val url = distributionUrl(props) ?: return null

        val zip = File(distDir, zipName)

        // ① 本地优先：dists 里已有的整包 → 公共存储快照（不随卸载清除） → 私有区搬运目录
        var source = firstUsable(zip, File(SNAPSHOT_ZIP_DIR, zipName), carryZip(context, zipName))
        if (source == null) {
            // ② 构建启动路径（allowNetwork=false）**一律不联网**。发行包 136MB，本机网络下既慢又会
            //    中途断流；早先在这里同步下载（预算 300s），而构建面板的命令回显排在 prepare() 之后，
            //    于是用户看到的就是「点构建后一直没有任何输出」。这里改成毫秒级返回一句可执行告警，
            //    联网下载只在显式入口（allowNetwork=true）里做。
            if (!allowNetwork) {
                return "⚠ Gradle 发行版 $zipName 不在本地，已跳过联网下载以保证构建面板立即响应：" +
                    "请把 $zipName 放到 $SNAPSHOT_ZIP_DIR/（或断网前先联网下载一次），本次构建会回退内置 Gradle"
            }
            source = download(url, zip, context)
        }
        distDir.mkdirs()

        // ② 解包；包损坏（半截）就删掉重下一次，最多一次
        try {
            unpack(source, distDir)
        } catch (t: Throwable) {
            runCatching { zip.delete() }
            source = download(url, zip, context)
            unpack(source, distDir)
        }

        // ③ 标记「已安装」：`<zipName>.ok` 既是 Gradle 的判据，也是构建脚本检查的那个名字。
        //    再补一个无 `.zip` 前缀的旧式标记，避免个别版本判成未安装而重新下载。
        runCatching { marker.writeText("") }
        runCatching { File(distDir, "$zipName.lck").writeText("") }
        runCatching { File(distDir, ".ok").writeText("") }

        // ④ 镜像到公共存储：卸载重装后可直接离线恢复。只在显式联网路径做 —— 构建启动路径上
        //    复制 136MB 到公共存储（FUSE）同样是「没有输出」的来源。
        if (allowNetwork) mirrorToSnapshot(source, zipName)

        val mb = source.length() / 1024 / 1024
        return "Gradle 发行版已本地化：$zipName（${mb}MB，已解包到 wrapper/dists）" +
            "——本次构建改用工程 wrapper（Gradle 8.9），避开 AGP 8.x 与内置 Gradle 9.x 的不兼容"
    }

    private fun carryZip(context: Context, zipName: String): File =
        File(Environment.homeRoot(context), ".gradle-dist/$zipName")

    /** 第一个「存在、够大、且确实是个 zip」的文件；半截的残留不会被当成可用包。 */
    private fun firstUsable(vararg candidates: File): File? =
        candidates.firstOrNull { it.isFile && it.length() > (1L shl 20) && looksLikeZip(it) }

    private fun looksLikeZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    /**
     * 带超时 / 重试 / 断点续传 / 多镜像的下载。
     *
     * 之所以不用 `gradlew` 自带的下载器：它的 `networkTimeout` 只有 10s，本机网络下
     * 「握手成功但读极慢」时会被直接判死（真机日志 "Download was interrupted ... timeout"）。
     */
    private fun download(url: String, target: File, context: Context): File {
        val part = File(target.parentFile, target.name + ".part")
        part.parentFile?.mkdirs()
        val sources = linkedSetOf(url)
        val fileName = url.substringAfterLast('/')
        MIRRORS.forEach { sources += it + fileName }

        val deadline = System.currentTimeMillis() + DOWNLOAD_BUDGET_MS
        var last: Throwable? = null
        for (source in sources) {
            var attempt = 0
            while (attempt < MAX_ATTEMPTS && System.currentTimeMillis() < deadline) {
                attempt++
                val r = runCatching { fetch(source, part, deadline) }
                if (r.isSuccess) {
                    runCatching { if (target.exists()) target.delete() }
                    if (!part.renameTo(target)) {
                        // 跨挂载点时 renameTo 会失败，退化成拷贝
                        part.copyTo(target, overwrite = true)
                        runCatching { part.delete() }
                    }
                    // 顺手在私有搬运目录留一份，供下次（乃至快照失败时）复用
                    runCatching {
                        val carry = carryZip(context, fileName)
                        carry.parentFile?.mkdirs()
                        target.copyTo(carry, overwrite = true)
                    }
                    return target
                }
                last = r.exceptionOrNull()
            }
            runCatching { part.delete() }
        }
        throw last ?: IllegalStateException("下载失败：$fileName")
    }

    /** 单次请求（支持 `Range` 续传）；长度与 `Content-Length` 不符即视为失败。 */
    private fun fetch(urlString: String, part: File, deadline: Long) {
        var offset = if (part.isFile) part.length() else 0L
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NebulaForge/1.0 (Android)")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = conn.responseCode
            if (offset > 0 && code == HttpURLConnection.HTTP_OK) {
                // 服务端没做断点续传，从头来
                offset = 0
                runCatching { part.delete() }
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("HTTP $code")
            }
            val expected = conn.getHeaderFieldLong("Content-Length", -1L).let { if (it > 0) offset + it else -1L }
            conn.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (System.currentTimeMillis() > deadline) throw IllegalStateException("下载超时（已保留断点）")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            }
            if (expected > 0 && part.length() != expected) {
                throw IllegalStateException("发行包不完整（${part.length()}/$expected）")
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 解包到 [distDir]，保留 zip 内的 `gradle-x.y/` 顶层目录（与 Gradle 自己解出来的布局一致）。
     *
     * 解完给 `bin/` 下的启动脚本补执行位：Gradle 的 `Install` 同样会 chmod，缺了它用户在终端里
     * 直接跑 `wrapper/dists/.../bin/gradle` 会 Permission denied。
     */
    private fun unpack(zipFile: File, distDir: File) {
        val root = distDir.absolutePath
        var files = 0
        ZipInputStream(zipFile.inputStream().buffered(1 shl 16)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                if (name.isBlank()) continue
                val out = File(distDir, name)
                // 目录穿越防护（与 Gradle PathTraversalChecker 同义）
                val path = out.absolutePath
                if (path != root && !path.startsWith(root + File.separator)) continue
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zin.copyTo(fos, 1 shl 16) }
                    files++
                }
                zin.closeEntry()
            }
        }
        if (files == 0) throw IllegalStateException("发行包为空或不可读：${zipFile.name}")
        distDir.listFiles()?.filter { it.isDirectory }?.forEach { top ->
            File(top, "bin").listFiles()?.forEach { runCatching { it.setExecutable(true, false) } }
        }
    }

    /** 把发行包镜像到公共存储，卸载重装后可直接离线恢复（同长度即视为已镜像）。 */
    private fun mirrorToSnapshot(source: File, zipName: String) {
        val dir = File(SNAPSHOT_ZIP_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return
        val dst = File(dir, zipName)
        if (dst.isFile && dst.length() == source.length()) return
        runCatching { source.copyTo(dst, overwrite = true) }
    }
}
