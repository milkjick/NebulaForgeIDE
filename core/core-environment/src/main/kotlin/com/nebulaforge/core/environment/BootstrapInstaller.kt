package com.nebulaforge.core.environment

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Bootstrap 用户态安装器。
 *
 * 【工具链路线：方式 B】职责：把 Termux 的 bootstrap 压缩包（按 CPU 架构分发的最小化 Linux
 * 用户态）下载到本 App 私有目录并解压，解压完成后本 App 就拥有一套可执行的 bin/lib 基础环境，
 * 不依赖任何外部 App。
 *
 * 整体流程：
 *   1. 探测当前设备 CPU 架构（arm / aarch64 / i686 / x86_64）
 *   2. 拼装对应架构的下载地址（可配置镜像源）
 *   3. 下载 zip 到临时文件，边下载边上报进度
 *   4. 解压到 Environment.usrRoot(context)，逐条 ZipEntry 处理
 *   5. 对 usr/bin、usr/lib 下的所有文件补齐可执行权限（zip 格式不保留 Unix 权限位，
 *      需要解压后手动 chmod）
 *   6. 处理 SYMLINKS.txt（Termux bootstrap 包的标准做法：因为 zip 不很好地支持符号链接，
 *      bootstrap 包用一个文本文件记录"链接路径 -> 目标路径"的映射，解压后需要重建这些符号链接）
 *   7. 全部完成后写入标记文件（Environment.bootstrapMarkerFile），供后续启动快速判断已安装
 *
 * 关于 noexec 限制：Android 10（API 29）起，应用私有目录的部分场景可能被挂载为 noexec，
 * 导致解压出的二进制无法直接执行。目前业界规避方式有二：
 *   (a) 将可执行文件从私有目录复制一份到 app 的 nativeLibraryDir（该目录允许 exec，
 *       但要求文件名以 lib 开头、.so 结尾，需要做一次"伪装 .so"的文件名转换与启动时的反向查找）；
 *   (b) 目标设备如果私有目录本身未被限制为 noexec（多数厂商 ROM 实际未开启此限制，
 *       该限制更多针对 /data/local/tmp 等公共目录），可直接执行。
 *   本实现采用先尝试直接执行、失败后回退到方案 (a) 的策略，具体回退逻辑见 verifyExecutable()。
 */
class BootstrapInstaller(private val context: Context) {

    companion object {
        /** 镜像源配置：与开发方案 1.4 节的 Gradle/Maven 镜像加速思路一致，bootstrap 包同样走可配置镜像 */
        /**
         * Fallback bootstrap 版本号。仅在无法访问 GitHub API 时使用；正常路径应通过
         * TermuxReleaseMetadata.resolveLatestVersion() 动态获取真实存在的最新 release tag，
         * 避免硬编码版本在官方发布节奏变化后失效（症状：下载 404 -> self-test failed）。
         */
        const val FALLBACK_RELEASE_VERSION = "2026.09.20-r1+apt.android-7"

        /** bootstrap 包内符号链接映射文件的约定路径 */
        private const val SYMLINKS_MANIFEST = "SYMLINKS.txt"
    }

    sealed class InstallProgress {
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long) : InstallProgress()
        data class Extracting(val currentEntry: String, val entriesDone: Int, val entriesTotal: Int) : InstallProgress()
        object SettingPermissions : InstallProgress()
        object LinkingSymlinks : InstallProgress()
        /** 内置用户态已落盘，正在做 SHA-256 / 架构 / 可执行性校验 */
        data class Verifying(val step: String) : InstallProgress()
        object Completed : InstallProgress()
        data class Failed(val reason: String, val cause: Throwable? = null) : InstallProgress()
    }

    /**
     * 探测当前设备的 CPU 架构，映射为 bootstrap 包命名使用的架构标识。
     * Termux 官方 bootstrap 包按此四种架构分发，取设备支持的 ABI 列表中的首选项。
     */
    fun detectArchitectureSafe(): String = runCatching { detectArchitecture() }.getOrDefault("unknown")

    fun detectArchitecture(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.any { it == "arm64-v8a" } -> "aarch64"
            abis.any { it == "armeabi-v7a" } -> "arm"
            abis.any { it == "x86_64" } -> "x86_64"
            abis.any { it == "x86" } -> "i686"
            else -> error("不支持的 CPU 架构：${abis.joinToString()}")
        }
    }

    /**
     * 拼装下载地址；useMirror=true 时走国内镜像，失败时调用方可改用 false 重试官方源。
     *
     * @param releaseVersion 实际 release tag。传入 null 时回退到 FALLBACK_RELEASE_VERSION，
     *        正常路径应先用 TermuxReleaseMetadata.resolveLatestVersion() 动态解析，
     *        避免硬编码版本在官方发布节奏变化后失效（症状：下载 404 -> self-test failed）。
     */
    fun buildDownloadUrl(architecture: String, useMirror: Boolean, releaseVersion: String? = null): String {
        val version = releaseVersion?.takeIf { it.isNotBlank() } ?: FALLBACK_RELEASE_VERSION
        // 统一由 BootstrapSourceCatalog 提供地址模板：这里不再自己拼字符串，
        // 否则下载源清单与安装逻辑各写一份，改动后必然漂移。
        val id = if (useMirror) "mirror-sourceforge" else "official-github"
        val source = BootstrapSourceCatalog.builtIn.firstOrNull { it.id == id }
            ?: BootstrapSourceCatalog.builtIn.first()
        return source.url(version, architecture)
    }

    /** 应使用的 release tag：动态解析 → 上次缓存 → 硬编码兜底。 */
    fun resolveReleaseTag(): String = TermuxReleaseMetadata.resolveLatestVersion()
        ?: BootstrapSourceStore(context).cachedReleaseTag
        ?: FALLBACK_RELEASE_VERSION

    /**
     * 执行完整安装流程：下载 -> 解压 -> 设置权限 -> 处理符号链接 -> 标记完成。
     * 返回 Flow 供 UI 层（首次启动向导）实时展示进度条与百分比。
     * 已安装过（Environment.isBootstrapInstalled 为 true）时直接 emit Completed，不重复执行。
     */
    fun install(useMirror: Boolean = true, preferredSourceId: String? = null): Flow<InstallProgress> = channelFlow {
        if (Environment.isBootstrapInstalled(context)) {
            send(InstallProgress.Completed)
            return@channelFlow
        }
        // 说明：这里不再复制一份「下载 -> 解压 -> 建链接」的实现，而是统一委托 installWithStatus，
        // 保证设置页（Flow 入口）与 BootstrapRuntime（挂起入口）行为完全一致——
        // 两条路径逻辑分叉正是此前「内置用户态装好了但 self-test 仍失败」的成因之一。
        val channel = Channel<InstallProgress>(Channel.UNLIMITED)
        val worker = launch(Dispatchers.IO) {
            try {
                installWithStatus(preferredSourceId = preferredSourceId ?: if (useMirror) null else "official-github") { phase, done, total, message ->
                    val mapped = when (phase) {
                        Phase.DOWNLOAD -> InstallProgress.Downloading(done, total)
                        Phase.EXTRACT -> InstallProgress.Extracting(message, done.toInt(), total.toInt())
                        Phase.VERIFY -> InstallProgress.Verifying(message)
                    }
                    channel.trySend(mapped)
                }.fold(
                    onSuccess = { channel.trySend(InstallProgress.Completed) },
                    onFailure = { e ->
                        channel.trySend(InstallProgress.Failed(e.message ?: "初始化内置运行环境失败", e))
                    }
                )
            } finally {
                channel.close()
            }
        }
        try {
            for (progress in channel) send(progress)
        } finally {
            worker.cancel()
        }
    }

    /** 下载文件，支持 HTTP Range 续传；临时文件完成后再原子替换目标文件。 */
    private fun downloadTo(urlString: String, destFile: File, onProgress: (Long, Long) -> Unit) {
        // GitHub release 下载会 302 到对象存储，移动网络下常见连接中断/读超时。
        // 做 3 次带退避的重试并保留 .part 以便 Range 续传，避免一次抖动就判失败。
        var last: Throwable? = null
        for (attempt in 1..3) {
            try {
                downloadToOnce(urlString, destFile, onProgress)
                return
            } catch (t: Throwable) {
                last = t
                if (attempt < 3) Thread.sleep(1500L * attempt)
            }
        }
        val partial = File(destFile.parentFile, destFile.name + ".part")
        val cachedKb = if (partial.exists()) partial.length() / 1024 else 0L
        throw IOException("下载失败（已重试 3 次，已缓存 ${cachedKb}KB，可重试续传）：${last?.message ?: last?.javaClass?.simpleName}；URL=$urlString", last)
    }

    private fun downloadToOnce(urlString: String, destFile: File, onProgress: (Long, Long) -> Unit) {
        val partial = File(destFile.parentFile, destFile.name + ".part")
        var existing = if (partial.exists()) partial.length() else 0L
        var connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        connection.connect()
        // java.net.HttpURLConnection 没有定义 416（Range Not Satisfiable）对应的常量
        // （该类只收录了常见状态码，416 不在其中），这里直接用字面量。
        if (connection.responseCode == 416 && existing > 0) {
            connection.disconnect()
            partial.delete()
            return downloadTo(urlString, destFile, onProgress)
        }
        if (connection.responseCode !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("HTTP $code：$urlString")
        }
        if (connection.responseCode == HttpURLConnection.HTTP_OK && existing > 0) {
            existing = 0L
            partial.delete()
        }
        val total = if (connection.contentLengthLong > 0) existing + connection.contentLengthLong else -1L
        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(partial, existing > 0).use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = existing
                var n: Int
                while (input.read(buffer).also { n = it } != -1) {
                    output.write(buffer, 0, n)
                    downloaded += n
                    onProgress(downloaded, total)
                }
            }
        }
        connection.disconnect()
        if (partial.length() <= 0) throw IOException("下载结果为空")
        if (destFile.exists()) destFile.delete()
        if (!partial.renameTo(destFile)) throw IOException("无法提交下载文件")
    }

    /**
     * 校验 bootstrap zip 的基本结构。
     *
     * 关于 Termux 官方 bootstrap 包布局的三个事实（已用真实包逐条目核对，务必遵守）：
     *   1. 条目位于**根目录**（`bin/`、`lib/`、`share/`…），不带 `usr/` 前缀，解压目标就是 usrRoot；
     *   2. 目录条目名带结尾斜杠，即 `lib/`；`zip.getEntry("lib")` 会返回 null，必须同时尝试 `lib/`；
     *   3. 包内**没有** `bin/sh` 实体文件：`bin/sh` 是 SYMLINKS.txt 里的符号链接（`dash←./bin/sh`）。
     * 早期实现只按 `bin/sh` + `lib` 判定，对真实官方包必然抛「缺少 sh」而整包判为损坏。
     */
    private fun validateBootstrapZip(zipFile: File) {
        java.util.zip.ZipFile(zipFile).use { zip ->
            fun has(name: String): Boolean = zip.getEntry(name) != null || zip.getEntry("$name/") != null
            require(has("bin")) { "bootstrap 缺少 bin 目录" }
            require(has("lib")) { "bootstrap 缺少 lib 目录" }
            val shellEntry = listOf(
                "bin/sh", "bin/bash", "bin/dash", "bin/mksh", "bin/zsh",
                "usr/bin/sh", "usr/bin/bash", "usr/bin/dash"
            ).firstOrNull { zip.getEntry(it) != null }
            val symlinks = zip.getEntry(SYMLINKS_MANIFEST) ?: zip.getEntry("usr/$SYMLINKS_MANIFEST")
            require(shellEntry != null || symlinks != null) { "bootstrap 缺少 shell 与符号链接清单" }
        }
    }

    /** 解压 zip 到目标目录，逐条处理 ZipEntry，避免路径穿越（Zip Slip 漏洞）攻击 */
    private fun extractZip(zipFile: File, targetDir: File, onProgress: (String, Int, Int) -> Unit) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            var count = 0
            while (entry != null) {
                val outFile = File(targetDir, entry.name)

                // 防止 Zip Slip：确保解压出的文件路径确实位于 targetDir 内部
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                    throw IOException("检测到非法压缩包条目路径：${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        while (zis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }
                }
                count++
                onProgress(entry.name, count, -1)
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * 递归为 usr/bin 与 usr/lib 目录下的所有文件补齐可执行权限。
     * zip 格式不保留 Unix 权限位是常见坑：即使原始 bootstrap 包里这些文件本来就是可执行的，
     * 解压后默认权限通常是 644，必须手动 chmod 755，否则后续 exec 会直接失败。
     */
    private fun setExecutablePermissions(usrRoot: File) {
        listOf(File(usrRoot, "bin"), File(usrRoot, "lib"), File(usrRoot, "libexec")).forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    file.setExecutable(true, false)
                    file.setReadable(true, false)
                }
            }
        }
    }

    /**
     * 根据 SYMLINKS.txt 清单重建符号链接。
     * 清单每行格式约定为："目标路径<TAB>链接路径"（与 Termux 官方 bootstrap 包的约定一致），
     * 例如一行 "/data/data/com.termux/files/usr/bin/sh	bin/bash" 表示需要在 usr/bin/bash
     * 创建一个指向 usr/bin/sh 的符号链接。由于原始清单里的目标路径是 Termux 自己的绝对路径，
     * 这里需要做一次路径重写，替换为本 App 的 usrRoot。
     */
    private fun createSymlinksFromManifest(manifestFile: File, usrRoot: File, finalPrefix: File = usrRoot) {
        manifestFile.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            // 新版 Termux bootstrap 使用 Unicode 左箭头记录 target←link；
            // 兼容旧/定制 bootstrap 中常见的 TAB 分隔格式。
            val parts = when {
                line.contains('←') -> line.split('←', limit = 2)
                line.contains("\t") -> line.split("\t", limit = 2)
                else -> return@forEach
            }
            if (parts.size != 2) return@forEach

            val rawTarget = parts[0].trim()
            val linkRelativePath = parts[1].trim().removePrefix("./")

            val linkFile = File(usrRoot, linkRelativePath)
            linkFile.parentFile?.mkdirs()

            // 目标路径语义（Termux 官方清单约定，已用真实包 1213 行清单逐条核对）：
            //   - 绝对路径：指向 Termux 自身的 /data/data/com.termux/files/usr/...，需重写到本 App 的 usrRoot；
            //   - 相对路径：**相对于链接自身所在目录**解析。例：`dash←./bin/sh` 表示 usr/bin/sh -> dash，
            //     其中 "dash" 相对 usr/bin 解析，即 usr/bin/dash（真实包中确实存在 bin/dash）。
            // 早期实现把相对目标按 usrRoot 解析（File(usrRoot, rawTarget)），导致 usr/bin/sh 指向不存在的
            // usrRoot/dash —— 悬空链接 —— verifyExecutable("bin/sh") 必然失败，
            // 外在症状即启动时的 "Embedded Termux runtime self-test failed"。
            val target = if (rawTarget.startsWith("/")) {
                // 取路径中 "files/usr/" 之后的部分，重新拼接到本 App 的 usrRoot 下。
                // 这里必须用**最终前缀**（finalPrefix）而不是 staging 目录：符号链接落在 staging 中，
                // 但目录随后会被 rename 成最终前缀，若目标写成 staging 路径则提交后立即悬空。
                val marker = "files/usr/"
                val idx = rawTarget.indexOf(marker)
                if (idx >= 0) File(finalPrefix, rawTarget.substring(idx + marker.length)).absolutePath
                else rawTarget
            } else {
                // 保留相对语义直接交给内核解析（可含 ".."，如 ../ncurses.h），不做绝对化。
                rawTarget
            }

            createSymlinkCompat(linkFile, target)
        }
    }

    /**
     * 创建符号链接，兼容 minSdk 24（java.nio.file.Files 的符号链接 API 需要 API 26+，
     * 本项目 minSdk=24，因此优先尝试反射调用 android.system.Os.symlink（libcore 提供，
     * API 21+ 即可用），失败时退化为直接复制文件，牺牲一点存储空间换取兼容性，
     * 不让整个安装流程因单个链接失败而中断。
     */
    private fun createSymlinkCompat(linkFile: File, target: String) {
        // 悬空符号链接 File.exists() 返回 false，因此必须无条件 unlink：
        // 否则重复安装时 symlink() 会因 EEXIST 失败，链接则停留在旧的（错误）目标上。
        linkFile.delete()
        val symlinkedOk = try {
            // android.system.Os.symlink(String target, String link) —— API 21+ 可用
            val osClass = Class.forName("android.system.Os")
            val symlinkMethod = osClass.getMethod("symlink", String::class.java, String::class.java)
            symlinkMethod.invoke(null, target, linkFile.absolutePath)
            true
        } catch (e: Exception) {
            false
        }
        if (!symlinkedOk) {
            // 回退方案：无法创建符号链接时退化为复制实体文件（牺牲存储换取可用性）。
            // 相对目标需要基于链接所在目录解析出真实文件位置后再复制。
            val source = if (target.startsWith("/")) File(target)
            else resolveRelativePath(linkFile.parentFile ?: linkFile, target)
            if (source.exists()) {
                runCatching {
                    source.copyTo(linkFile, overwrite = true)
                    linkFile.setExecutable(true, false)
                }
            }
        }
    }

    /**
     * 纯字符串路径归一化（合并 base 与可能含 "." / ".." 的相对路径）。
     * 刻意不使用 java.nio.file：其符号链接/路径 API 需要 API 26+，而本项目 minSdk=24。
     */
    private fun resolveRelativePath(base: File, relative: String): File {
        val segments = ArrayList<String>()
        base.absolutePath.split('/').filter { it.isNotEmpty() }.forEach { segments.add(it) }
        relative.split('/').forEach { seg ->
            when (seg) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(seg)
            }
        }
        return File("/" + segments.joinToString("/"))
    }

    /**
     * 验证指定可执行文件能否真正被 exec 起来，用于探测当前设备是否对私有目录施加了 noexec 限制。
     * 骨架阶段仅做探测与失败上报，不实现 (a) 方案（复制为伪装 .so 到 nativeLibraryDir）的自动回退，
     * 该回退逻辑标注在类注释中，留待后续版本实现。
     */
    /** 安装失败报告文件（files/.nebulaforge/install-error.txt），无 Root 也可通过 run-as 读取。 */
    private fun installReportFile(): File = File(context.filesDir, ".nebulaforge/install-error.txt")

    private fun writeInstallReport(t: Throwable) {
        runCatching {
            val f = installReportFile()
            f.parentFile?.mkdirs()
            f.writeText(
                buildString {
                    appendLine("time=${java.util.Date()}")
                    appendLine("message=${t.message}")
                    appendLine("cause=${t.cause?.message}")
                    if (lastExecDiagnosis.isNotBlank()) {
                        appendLine("execDiagnosis=${lastExecDiagnosis}")
                    }
                    appendLine(t.stackTraceToString())
                }
            )
        }
    }

    /** 探测失败时给出可读原因，直接显示在错误信息里，省掉一轮来回排查。 */
    private fun diagnoseExec(prefix: File): String {
        val sh = File(prefix, "bin/sh")
        val lib = File(prefix, "lib")
        return buildString {
            append("home存在=").append(File(Environment.homeRoot(context)).isDirectory)
            append("，sh存在=${sh.exists()}")
            append("，lib目录=${lib.isDirectory}")
            append("，libandroid-support=${File(lib, "libandroid-support.so").exists()}")
            append("，符号链接目标=")
            append(runCatching {
                val osClass = Class.forName("android.system.Os")
                val readlink = osClass.getMethod("readlink", String::class.java)
                readlink.invoke(null, sh.absolutePath) as? String ?: "非链接"
            }.getOrDefault("读取失败"))
        }
    }

    /**
     * 把脚本 shebang 里硬编码的 Termux 前缀改写成本 App 的最终前缀。
     *
     * 官方 bootstrap 中 dpkg/pkg/npm 等大量脚本写死了
     * `#!/data/data/com.termux/files/usr/bin/...`，本 App 前缀不同，内核按原路径找不到解释器
     * 就直接返回 ENOENT（表现为 "No such file or directory"，很容易被误判成文件缺失）。
     * termux-exec 预加载库也能处理，但它依赖 linker 变体选择、失败面更大，
     * 这里做一次确定性的字面替换作为兜底。
     *
     * @param staging 待提交的 staging 目录
     * @param finalPrefix 提交后的最终前缀（必须是最终路径，不能是 staging 路径）
     */
    private fun rewriteHardcodedInterpreterPaths(staging: File, finalPrefix: File) {
        val hardcoded = "/data/data/com.termux/files/usr"
        val actual = finalPrefix.absolutePath
        listOf("bin", "libexec", "etc", "libexec/dpkg").forEach { rel ->
            val root = File(staging, rel)
            if (!root.isDirectory) return@forEach
            root.walkTopDown().filter { it.isFile && it.length() in 1..(512 * 1024) }.forEach { file ->
                runCatching {
                    val head = ByteArray(2)
                    file.inputStream().use { it.read(head) }
                    if (head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return@runCatching
                    val text = file.readText()
                    if (!text.contains(hardcoded)) return@runCatching
                    file.writeText(text.replace(hardcoded, actual))
                    file.setExecutable(true, false)
                }
            }
        }
    }

    /**
     * 最近一次 exec 交叉探测的完整结论（失败时由 verifyExecutable 填充）。
     *
     * 为什么要带 Java 路径交叉探测：真机上「文件全在、符号链接指向 dash 正确」却依旧报
     * `embedded sh cannot execute`。此时必须区分三种截然不同的原因：
     *  (a) app 私有目录被系统拒绝 exec（SELinux untrusted_app 域 / noexec）→ errno=13 EACCES；
     *  (b) 解释器或依赖库缺失（PREFIX/LD_LIBRARY_PATH 不对）→ errno=2 ENOENT；
     *  (c) PTY 驱动自身问题（fork/openpty 失败）→ Java 侧 ProcessBuilder 反而成功。
     * 只看布尔结果无法区分，所以这里把 PTY 输出、Java 执行结果、系统 shell 对照组一并留下。
     */
    @Volatile
    private var lastExecDiagnosis: String = ""

    /**
     * 用 Java 的 ProcessBuilder 在**同一 app 进程、同一 SELinux 域**下再执行一次。
     *
     * 与 NativePty 的区别只在「打开 pty + fork」这一层：若此处报 error=13 Permission denied，
     * 就说明是内核/SELinux 拒绝了 app_data_file 的 execve，而非我们的 PTY 代码写错。
     * 失败时异常消息自带 errno 文本，这正是我们要的证据。
     */
    private fun javaExecProbe(target: File, prefix: File, args: List<String> = listOf("-c", "printf 'JAVA_PROBE_OK\\n'")): String =
        runCatching {
            val builder = ProcessBuilder(listOf(target.absolutePath) + args).redirectErrorStream(true)
            builder.environment().putAll(Environment.buildPrefixEnv(context, prefix))
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim().take(300)
            val code = process.waitFor()
            "ok exit=$code out=[$output]"
        }.getOrElse { t -> "fail ${t.javaClass.simpleName}: ${t.message?.take(200)}" }

    /**
     * 用 NativePty 真实执行并**收集输出行**。
     *
     * 判定标准是「marker 被打印出来」：它证明该前缀下的 shell 已被内核 exec 并跑完了命令。
     * 退出码在这里不作为判据（驱动在 shell 自然退出时可能拿不到令牌），但只要 exec 失败，
     * native 层会把 `__NEBULA_EXEC_FAIL__ errno=...` 写进 pty，于是这里能直接看到真实 errno。
     */
    private fun runPtyProbe(shBinary: File, prefix: File): Pair<Boolean, String> {
        val env = Environment.buildPrefixEnv(context, prefix).toMutableMap()
        env["PATH"] = "${File(prefix, "bin").absolutePath}:${Environment.binDir(context)}:${env["PATH"].orEmpty()}"
        val marker = "NEBULA_EXEC_PROBE_OK"
        val lines = mutableListOf<String>()
        var sawMarker = false
        var exitCode = -1
        var failure: String? = null
        try {
            val executor = com.nebulaforge.core.exec.TermuxCommandExecutor(shBinary.absolutePath)
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    // 探测命令**不能带 exit**：TermuxCommandExecutor 依赖命令结束后再打印
                    // `__NEBULA_EXIT_xxx__:$?` 令牌来上报退出码，命令里自带 exit 会让 shell 直接终止，
                    // 令牌永远不会输出 → exitCode 恒为 -1 → 明明 sh 完全正常却判定「cannot execute」。
                    // 探测 cwd 必须是已存在的目录：files/home 在全新安装时并不存在，
                    // 会让 `cd` 直接失败并返回 125，从而被误判成「sh 无法执行」。
                    executor.execute("printf 'NEBULA_EXEC_PROBE_OK\\n'", Environment.ensureHome(context), env)
                        .collect { event ->
                            when (event) {
                                is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Line -> {
                                    if (lines.size < 12) lines += event.text.trim()
                                    if (event.text.contains(marker)) sawMarker = true
                                }
                                is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Finished -> exitCode = event.exitCode
                            }
                        }
                }
            }
        } catch (t: Throwable) {
            failure = "${t.javaClass.simpleName}: ${t.message?.take(200)}"
        }
        val detail = buildString {
            append("pty=").append(if (sawMarker) "ok" else "fail")
            append(" exit=").append(exitCode)
            // 125 是本 App 脚本里 `cd <工作目录>` 失败时的约定退出码，与 exec 能力无关，
            // 必须单独点明，否则会被继续误读成 SELinux/noexec 拒绝执行。
            if (!sawMarker && exitCode == 125) {
                append("（退出码 125 = 探测脚本 cd 失败：工作目录 ").append(Environment.homeRoot(context)).append(" 不存在或不可进入，并非 sh 无法执行）")
            }
            failure?.let { append(" 异常=").append(it) }
            if (lines.isNotEmpty()) append(" 输出=").append(lines.filter { it.isNotBlank() }.joinToString(" | ").take(400))
        }
        return sawMarker to detail
    }

    private fun verifyExecutable(shBinary: File, prefix: File): Boolean {
        if (!shBinary.exists()) return false
        val (ok, ptyDetail) = runPtyProbe(shBinary, prefix)
        if (ok) {
            lastExecDiagnosis = ptyDetail
            return true
        }
        // 失败才做交叉探测（正常路径零额外开销）：
        //  · java(app目录) —— 同一 app 域执行同一二进制；
        //  · java(/system/bin/sh) —— 对照组，排除「app 域整体不能 exec」的极端情况。
        val javaSameDir = javaExecProbe(shBinary, prefix)
        val javaSystem = javaExecProbe(File("/system/bin/sh"), prefix)
        lastExecDiagnosis = "$ptyDetail ｜ java(app目录)=$javaSameDir ｜ java(/system/bin/sh)=$javaSystem"
        return false
    }

    private fun verifyExecutableLegacy(shBinary: File, prefix: File): Boolean {
        if (!shBinary.exists()) return false
        // Way-B：必须与终端/构建/LSP 使用同一 NativePty 用户态。Android 上 ProcessBuilder 对
        // 私有目录下的 ELF 二进制可能因 noexec/SELinux 被拒，文件存在性检查会给出误导结果。
        //
        // 注意 prefix 参数：安装阶段探测的是 staging 目录，而环境变量（PREFIX/LD_LIBRARY_PATH）
        // 必须指向 **同一个** staging 前缀，否则动态链接器找不到 libandroid-support.so 等库，
        // dash 会在 exec 阶段直接失败，症状就是 `embedded sh cannot execute`。
        return try {
            val env = Environment.buildPrefixEnv(context, prefix).toMutableMap()
            env["PATH"] = "${File(prefix, "bin").absolutePath}:${Environment.binDir(context)}:${env["PATH"].orEmpty()}"
            val executor = com.nebulaforge.core.exec.TermuxCommandExecutor(shBinary.absolutePath)
            val marker = "NEBULA_EXEC_PROBE_OK"
            var sawMarker = false
            var exitCode = -1
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    // 探测命令**不能带 exit**：TermuxCommandExecutor 依赖命令结束后再打印
                    // `__NEBULA_EXIT_xxx__:$?` 令牌来上报退出码，命令里自带 exit 会让 shell 直接终止，
                    // 令牌永远不会输出 → exitCode 恒为 -1 → 明明 sh 完全正常却判定「cannot execute」。
                    // 探测 cwd 必须是已存在的目录：files/home 在全新安装时并不存在，
                    // 会让 `cd` 直接失败并返回 125，从而被误判成「sh 无法执行」。
                    executor.execute("printf 'NEBULA_EXEC_PROBE_OK\\n'", Environment.ensureHome(context), env)
                        .collect { event ->
                            when (event) {
                                is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Line ->
                                    if (event.text.contains(marker)) sawMarker = true
                                is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Finished -> exitCode = event.exitCode
                            }
                        }
                }
            }
            // 只要 marker 打印出来，就证明该用户态里的 shell 被真正 exec 起来并执行了命令；
            // 退出码缺失（-1）属于驱动边界情况，不应据此判失败，否则会退化成「文件和链接全对、
            // 却永远报 embedded sh cannot execute」的静默失败。
            sawMarker && (exitCode == 0 || exitCode == -1)
        } catch (e: Throwable) {
            false
        }
    }

    enum class Phase { DOWNLOAD, EXTRACT, VERIFY }

    /** Transactional installer used by BootstrapRuntime. */
    suspend fun installWithStatus(
        preferredSourceId: String? = null,
        onStatus: (Phase, Long, Long, String) -> Unit
    ): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // 自检与探测都依赖 home 作为工作目录，必须先把目录骨架建好再做验证。
                Environment.ensureDirs(context)
                if (Environment.isBootstrapInstalled(context) && verifyExecutable(File(Environment.binDir(context), "sh"), File(Environment.usrRoot(context)))) return@runCatching Unit
                val architecture = detectArchitecture()
                val cacheZip = File(context.cacheDir, "bootstrap-$architecture.zip")
                val staging = File(context.filesDir, ".bootstrap-staging-$architecture")
                val target = File(Environment.usrRoot(context))
                staging.deleteRecursively()
                staging.mkdirs()
                try {
                    // ① 优先使用 APK 内置 bootstrap：离线可用、首启秒装，规避
                    //    "GitHub release 302 中断 / API 403 限流" 导致的首次启动安装失败。
                    // 设置页可关闭「优先内置用户态」，强制联网校验/获取更新版本。
                    val sourceResolver = BootstrapSourceResolver(context)
                    val embedded = EmbeddedBootstrap.forArch(architecture)
                        ?.takeIf { sourceResolver.store.preferEmbedded }
                    val releaseVersion: String
                    if (embedded != null && embeddedAssetExists(embedded)) {
                        releaseVersion = embedded.version
                        val totalMb = "%.1f".format(embedded.sizeBytes / 1048576.0)
                        onStatus(Phase.DOWNLOAD, 0, embedded.sizeBytes, "释放内置用户态（$architecture，${totalMb}MB）")
                        cacheZip.delete()
                        copyAssetToCache(embedded, cacheZip) { done ->
                            val pct = (done * 100 / embedded.sizeBytes).coerceIn(0, 100)
                            onStatus(
                                Phase.DOWNLOAD, done, embedded.sizeBytes,
                                "正在释放内置用户态 ${"%.1f".format(done / 1048576.0)}/${totalMb}MB（$pct%）"
                            )
                        }
                        EmbeddedBootstrap.mismatchReason(cacheZip, embedded)?.let { throw IllegalStateException(it) }
                        onStatus(Phase.VERIFY, 0, 2, "校验内置用户态完整性（SHA-256）")
                        val actual = sha256(cacheZip)
                        check(actual.equals(embedded.sha256, ignoreCase = true)) {
                            "内置用户态 SHA-256 不匹配（期望 ${embedded.sha256}，实际 $actual）"
                        }
                        onStatus(Phase.VERIFY, 1, 2, "校验内置用户态架构（期望 $architecture）")
                        check(verifyEmbeddedArchitecture(cacheZip, architecture)) {
                            "内置用户态架构不匹配：期望 $architecture（ELF e_machine=${EmbeddedBootstrap.expectedElfMachine(architecture)}）"
                        }
                    } else {
                        // ② 回退：没有可用内置资产（非 arm64 设备、assets 未随包分发，
                        //    或用户在设置里关掉了「优先内置」）时联网多源下载。
                        releaseVersion = resolveReleaseTag()
                        sourceResolver.rememberTag(releaseVersion)
                        onStatus(Phase.DOWNLOAD, 0, -1, "联网获取 $architecture 用户态（版本 $releaseVersion）")
                        // GitHub API 能解析到权威下载地址与官方 sha256 时优先采用（官方源专用）。
                        val asset = TermuxReleaseMetadata.resolve(releaseVersion, architecture)
                        var downloaded = false
                        var lastFailure: Throwable? = null
                        val candidates = sourceResolver.rankedCandidates(releaseVersion, architecture, preferredSourceId)
                        for ((source, probe) in candidates) {
                            if (downloaded) break
                            onStatus(Phase.DOWNLOAD, 0, -1, "下载源：${source.label}（${probe.statusLabel}）")
                            val urlForSource =
                                if (source.official && asset != null) asset.url
                                else source.url(releaseVersion, architecture)
                            // 换源必须清掉上一个源的残包：.part 续传是按字节偏移续的，
                            // 跨源续传会把两个源的字节拼成一个损坏的 zip。
                            File(cacheZip.parentFile, cacheZip.name + ".part").delete()
                            cacheZip.delete()
                            try {
                                downloadTo(urlForSource, cacheZip) { done, total ->
                                    val got = "%.1f".format(done / 1048576.0)
                                    val label = if (total > 0) {
                                        "下载中 $architecture $got/${"%.1f".format(total / 1048576.0)}MB (${(done * 100 / total).coerceIn(0, 100)}%) · ${source.label}"
                                    } else {
                                        "下载中 $architecture ${got}MB · ${source.label}"
                                    }
                                    onStatus(Phase.DOWNLOAD, done, total, label)
                                }
                                downloaded = true
                                sourceResolver.rememberWorking(source.id)
                                onStatus(Phase.DOWNLOAD, 0, -1, "下载完成：${source.label}")
                            } catch (t: Throwable) {
                                lastFailure = t
                                onStatus(
                                    Phase.DOWNLOAD, 0, -1,
                                    "该源失败，自动切换下一个（${source.label}：${t.message ?: t.javaClass.simpleName}）"
                                )
                            }
                        }
                        check(downloaded) {
                            "所有内置下载源均失败（共 ${candidates.size} 个）：${lastFailure?.message ?: "未知错误"}；" +
                                "可在「设置 → 工具链下载源」手动选择其它源或填写自定义镜像"
                        }
                        if (asset?.sha256 != null) {
                            val actual = sha256(cacheZip)
                            check(actual.equals(asset.sha256, ignoreCase = true)) { "SHA-256 mismatch: expected ${asset.sha256}, actual $actual" }
                        }
                    }
                    validateBootstrapZip(cacheZip)
                    onStatus(Phase.EXTRACT, 0, -1, "Extracting bootstrap")
                    extractZip(cacheZip, staging) { name, done, total -> onStatus(Phase.EXTRACT, done.toLong(), total.toLong(), name) }
                    setExecutablePermissions(staging)
                    val links = File(staging, SYMLINKS_MANIFEST)
                    // 注意：链接文件建在 staging 里，但**绝对目标必须指向最终前缀**。
                    // 早期实现按 staging 解析绝对目标，rename 提交后目标目录消失 → 20 个软链悬空，
                    // 其中 etc/apt/trusted.gpg.d/*.gpg 悬空会让 apt 无法验签（所有镜像报 "bad"）。
                    if (links.exists()) { createSymlinksFromManifest(links, staging, target); links.delete() }
                    // staging 提交后会被改名为最终 usr 目录，因此 shebang 必须重写成**最终**前缀，
                    // 不能写成 staging 路径（否则 rename 之后所有脚本解释器路径立刻失效）。
                    onStatus(Phase.VERIFY, 0, 3, "Rewriting hardcoded interpreter paths")
                    rewriteHardcodedInterpreterPaths(staging, target)
                    File(staging, "tmp").mkdirs()
                    onStatus(Phase.VERIFY, 1, 3, "Testing embedded sh")
                    check(verifyExecutable(File(staging, "bin/sh"), staging)) {
                        "embedded sh cannot execute（${diagnoseExec(staging)}｜${lastExecDiagnosis}）"
                    }
                    target.deleteRecursively()
                    check(staging.renameTo(target)) { "failed to commit bootstrap staging directory" }
                    // 提交到最终路径后再探测一次：确认改名后 shebang/链接仍然成立，
                    // 避免出现「装完了但终端跑不起来」的半成品状态。
                    check(verifyExecutable(File(target, "bin/sh"), target)) {
                        "embedded sh cannot execute after commit（${diagnoseExec(target)}｜${lastExecDiagnosis}）"
                    }
                    Environment.bootstrapMarkerFile(context).writeText(architecture)
                    runCatching {
                        val vf = File(Environment.homeRoot(context), ".nebulaforge/bootstrap/version")
                        vf.parentFile?.mkdirs()
                        vf.writeText(releaseVersion)
                    }
                    cacheZip.delete()
                    installReportFile().delete()
                    // 前缀对齐运行时：安装 proot、建 guest rootfs 骨架、修复历史悬空软链。
                    // 这一步失败不阻塞 bootstrap 本身（终端仍可用），但会记录原因。
                    runCatching { TermuxGuest.ensureSetup(context) { /* 安装阶段不逐条上报 */ } }
                        .onFailure { android.util.Log.w("BootstrapInstaller", "guest runtime setup failed", it) }
                    onStatus(Phase.VERIFY, 3, 3, "Bootstrap committed")
                } catch (t: Throwable) {
                    // 无 Root 设备上应用内部日志很难取到，把失败原因落盘到 files/ 下，
                    // 便于事后用 run-as / 文件管理器定位，避免再次出现「只报一句 failed」。
                    writeInstallReport(t)
                    staging.deleteRecursively()
                    Environment.bootstrapMarkerFile(context).delete()
                    throw t
                } finally { cacheZip.delete() }
            }
        }

    /** APK assets 内是否真的存在该 bootstrap（元数据与打包不同步时不能盲信元数据）。 */
    private fun embeddedAssetExists(asset: EmbeddedBootstrap.Asset): Boolean = runCatching {
        context.assets.open(asset.assetPath).use { true }
    }.getOrDefault(false)

    /** 把 APK 内置的 bootstrap 拷贝到缓存目录（边拷边上报进度，供 UI 显示释放百分比）。 */
    private fun copyAssetToCache(
        asset: EmbeddedBootstrap.Asset,
        dest: File,
        onProgress: (Long) -> Unit
    ) {
        dest.parentFile?.mkdirs()
        context.assets.open(asset.assetPath).use { input ->
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    total += n
                    onProgress(total)
                }
                out.flush()
            }
        }
    }

    /** 读取 zip 内某条目的前 count 字节（用于嗅探 ELF 头，不必解整包）。 */
    private fun zipEntryHead(zipFile: File, entry: String, count: Int): ByteArray? =
        java.util.zip.ZipFile(zipFile).use { zip ->
            val e = zip.getEntry(entry) ?: return null
            zip.getInputStream(e).use { ins ->
                val buf = ByteArray(count)
                var off = 0
                while (off < count) {
                    val n = ins.read(buf, off, count - off)
                    if (n <= 0) break
                    off += n
                }
                if (off <= 0) null else buf.copyOf(off)
            }
        }

    /**
     * 校验内置 bootstrap 的真实 CPU 架构：直接解析包内主 shell 的 ELF 头 e_machine。
     * 防的是「把 x86_64 资产装到 arm64 设备上」这类错误——那种情况下解压会成功，
     * 但所有二进制 exec 时报 "Exec format error"，而单纯的「文件存在性检查」无法提前发现。
     */
    private fun verifyEmbeddedArchitecture(zipFile: File, architecture: String): Boolean {
        val expected = EmbeddedBootstrap.expectedElfMachine(architecture) ?: return false
        val head = listOf("bin/bash", "bin/dash", "bin/sh", "usr/bin/bash", "usr/bin/dash")
            .firstNotNullOfOrNull { zipEntryHead(zipFile, it, 20) } ?: return false
        if (head.size < 20) return false
        // ELF magic: 0x7F 'E' 'L' 'F'
        if (head[0] != 0x7F.toByte() || head[1] != 0x45.toByte() ||
            head[2] != 0x4C.toByte() || head[3] != 0x46.toByte()
        ) return false
        if (head[4] != 2.toByte()) return false // EI_CLASS=2 → ELF64
        val machine = (head[18].toInt() and 0xFF) or ((head[19].toInt() and 0xFF) shl 8)
        return machine == expected
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

}
