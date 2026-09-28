package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Android SDK **平台包（platforms/android-NN）自愈 + 自动下载**。
 *
 * ## 真机根因（原生安卓工程「点构建跑一半莫名报错 / 一直卡在下载」）
 *
 * 设备上 `platforms/android-34/` 是**半装**的：只有 `data/`、`package.xml`、`skins/`、
 * `framework.aidl`，**缺 `android.jar` 和 `source.properties`**。AGP 判定这份 SDK 不可用，
 * 于是自己触发内置的 SDK 下载器；而下载器解压时撞上上次中断留下的
 * `.temp/PackageOperation01/unzip/android-34/...`：
 *
 * ```
 * Warning: An error occurred while preparing SDK package Android SDK Platform 34:
 *   .temp/PackageOperation01/unzip/android-34/data/res/drawable/ratingbar.xml.:
 *   java.nio.file.FileAlreadyExistsException
 * "Install Android SDK Platform 34 (revision 3)" failed.
 * ...
 * Build properties not found for package Android SDK Platform 34
 * > Failed to find target with hash string 'android-34' in: .../android-sdk
 * ```
 *
 * 一句话：**AGP 自己下不动、也修不好**这份半装平台，构建就在资源/清单阶段失败，
 * 用户看到的是「原生安卓项目一构建就报错」。Flutter 工程的 `android/` 子工程同样吃这份 SDK，
 * 所以两者症状同源。
 *
 * ## 做法
 *
 * 抢在 AGP 之前把 SDK 平台**补齐**（幂等、离线可缓存）：
 *  1. 清掉 `.temp/` 里上一轮失败安装留下的半成品——它们是 `FileAlreadyExistsException` 的根因；
 *  2. 扫出所有**残缺**的 `platforms/android-NN`（缺 `android.jar` 或 `source.properties`）并删掉；
 *  3. 对「工程需要但没装 / 刚被删掉」的平台，从 `dl.google.com` 下载官方 zip 并解压
 *     （剥掉压缩包最外层 `android-NN/` 目录），落成**完整**平台。
 *
 * 完成后再启动构建，AGP 看到的是完整平台，直接跳过它自己那套会踩坑的下载器。
 *
 * ## 为什么直接下载而不是调 sdkmanager
 * 设备上 `cmdline-tools/latest/bin/sdkmanager` 的包装脚本 shebang 指向 guest 里不存在的解释器，
 * 直接执行报 `No such file or directory`；而平台包本身就是一个 zip，官方 URL 稳定、
 * 结构固定（顶层 `android-NN/`），自己下载解压最可控，也就能顺带把 `.temp` 半成品清掉。
 */
object AndroidPlatformRepair {

    /** 官方下载根（真机实测可达：build-tools/platform 均由该域下载）。 */
    private const val BASE = "https://dl.google.com/android/repository/"

    /**
     * 兜底「API → 官方 zip 文件名」映射。
     *
     * 正常情况下优先解析 `repository2-3.xml`（见 [resolveZipName]），拿到**当前**修订号对应的
     * 文件名；这张表只在 XML 解析失败（无网/格式变动）时生效，覆盖模板会用到的主流版本。
     */
    private val FALLBACK_ZIP = mapOf(
        21 to "android-21_r02.zip",
        22 to "android-22_r02.zip",
        23 to "platform-23_r03.zip",
        24 to "platform-24_r02.zip",
        25 to "platform-25_r03.zip",
        26 to "platform-26_r02.zip",
        27 to "platform-27_r03.zip",
        28 to "platform-28_r06.zip",
        29 to "platform-29_r05.zip",
        30 to "platform-30_r03.zip",
        31 to "platform-31_r01.zip",
        32 to "platform-32_r01.zip",
        33 to "platform-33-ext3_r03.zip",
        34 to "platform-34-ext7_r03.zip",
        35 to "platform-35_r02.zip",
        36 to "platform-36_r02.zip"
    )

    /**
     * 确保工程需要的平台可用；只在不完整/缺失时才有网络与写盘动作。
     *
     * @param requiredApis 工程声明需要的 API（例如 compileSdk=34）。会与「扫出来的残缺平台」
     *                     合并处理，因此即使调用方不知道 API、也仍能修好半装的那份。
     * @return 展示给用户的行（无改动则空列表，避免每次构建刷屏）。
     */
    @Synchronized
    fun ensure(context: Context, requiredApis: Set<Int>): List<String> {
        val notes = mutableListOf<String>()
        val sdk = File(Environment.androidSdkRoot(context))
        if (!sdk.isDirectory) return notes

        // ① 先清失败安装残留：不先清，AGP / 我们的解压都会撞 FileAlreadyExistsException。
        cleanStaleTemp(sdk, notes)

        val platforms = File(sdk, "platforms")
        platforms.mkdirs()

        // ② 找出所有残缺平台（有目录但缺关键文件）。
        val broken = mutableSetOf<Int>()
        platforms.listFiles { f -> f.isDirectory }?.forEach { dir ->
            val api = dir.name.removePrefix("android-").toIntOrNull() ?: return@forEach
            if (!isComplete(dir)) broken += api
        }

        // ③ 需要处理的 API = 工程要求的 ∪ 已残缺的。
        val targets = (requiredApis.filter { it > 0 } + broken).toSortedSet()

        for (api in targets) {
            val dir = File(platforms, "android-$api")
            if (isComplete(dir)) continue
            install(sdk, api, notes)
        }
        return notes
    }

    // ------------------------------------------------------------------ 判定

    /**
     * 平台「完整」的判据：`android.jar` + `source.properties` 都在。
     *
     * 真机取证：半装的 `android-34` 有 `data/`、`package.xml` 却**没有**这两个文件，
     * AGP 正是据此报 `Build properties not found`（`source.properties` 提供 API 级别），
     * 以及 `Failed to find target`（`android.jar` 是编译期 bootclasspath）。
     */
    private fun isComplete(dir: File): Boolean =
        File(dir, "android.jar").isFile && File(dir, "source.properties").isFile

    // ------------------------------------------------------------------ 修复

    /** 清理 `.temp/` 下上一轮安装留下的半成品（`PackageOperation*`）。 */
    private fun cleanStaleTemp(sdk: File, notes: MutableList<String>) {
        val temp = File(sdk, ".temp")
        if (!temp.isDirectory) return
        val leftovers = temp.listFiles() ?: return
        if (leftovers.isEmpty()) return
        var removed = false
        leftovers.forEach { if (runCatching { it.deleteRecursively() }.getOrDefault(false)) removed = true }
        if (removed) {
            notes += "已清理 SDK 安装残留（.temp）——上次失败安装留下的半成品会让本次解压报 " +
                "FileAlreadyExistsException，是「平台反复装不上」的直接原因"
        }
    }

    private fun install(sdk: File, api: Int, notes: MutableList<String>) {
        val dir = File(sdk, "platforms/android-$api")
        // 半装目录必须整份删掉：留着它 AGP 既不认、也不重装。
        if (dir.exists()) runCatching { dir.deleteRecursively() }
        dir.parentFile?.mkdirs()

        val zip = File(sdk, ".temp/nebula-platform-$api.zip")
        zip.parentFile?.mkdirs()
        val url: String
        try {
            url = BASE + resolveZipName(api)
        } catch (t: Throwable) {
            notes += "⚠ 无法确定 Android Platform android-$api 的下载地址：${t.message ?: t.javaClass.simpleName}"
            return
        }

        val result = runCatching {
            zip.delete()
            download(url, zip)
            extractStrippingTop(zip, dir)
            zip.delete()
            if (!isComplete(dir)) error("解压后仍缺少 android.jar / source.properties")
            "已自动下载并安装 Android Platform android-$api（${url.substringAfterLast('/')}）"
        }
        result.onSuccess { notes += it }.onFailure {
            runCatching { zip.delete() }
            runCatching { dir.deleteRecursively() }
            notes += "⚠ 自动安装 Android Platform android-$api 失败：${it.message ?: it.javaClass.simpleName}" +
                "（可检查网络是否可达 dl.google.com，或在「设置 → 工具链」手动安装）"
        }
    }

    // ------------------------------------------------------------------ 解析下载地址

    /**
     * 解析某个 API 对应的官方 zip 文件名。
     *
     * 优先从 `repository2-3.xml` 读「当前 revision」的文件名（平台包会随修订号改名，例如
     * `platform-34-ext7_r03.zip`），失败则退回 [FALLBACK_ZIP]。
     */
    private fun resolveZipName(api: Int): String {
        val fromXml = runCatching {
            val xml = fetchText(BASE + "repository2-3.xml")
            val block = Regex(
                "<remotePackage path=\"platforms;android-$api\"[^>]*>(.*?)</remotePackage>",
                RegexOption.DOT_MATCHES_ALL
            ).find(xml)?.groupValues?.getOrNull(1)
            block?.let { Regex("<url>([^<]+\\.zip)</url>").find(it)?.groupValues?.getOrNull(1) }
        }.getOrNull()
        return fromXml ?: FALLBACK_ZIP[api]
            ?: error("不在已知平台列表内（API $api）")
    }

    // ------------------------------------------------------------------ IO

    private fun fetchText(url: String): String = open(url, 0) { conn ->
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun download(url: String, dest: File) = open(url, 0) { conn ->
        dest.outputStream().use { out ->
            conn.inputStream.use { input -> input.copyTo(out, 128 * 1024) }
        }
    }

    private inline fun <T> open(url: String, offset: Long, block: (HttpURLConnection) -> T): T {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NebulaForgeIDE")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            return block(conn)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 解压并**剥掉压缩包最外层的 `android-NN/` 目录**，得到 `platforms/android-NN/<文件>`。 */
    private fun extractStrippingTop(zip: File, outDir: File) {
        val outRoot = outDir.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val relative = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (relative.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val target = File(outRoot, relative)
                // zip-slip 防护：拒绝逃逸出 outDir 的条目。
                if (!target.canonicalPath.startsWith(outRoot.path + File.separator) &&
                    target.canonicalPath != outRoot.path
                ) {
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zis.copyTo(out, 128 * 1024) }
                }
                zis.closeEntry()
            }
        }
    }
}
