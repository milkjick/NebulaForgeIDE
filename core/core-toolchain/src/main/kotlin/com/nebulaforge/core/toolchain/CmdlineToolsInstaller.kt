package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Android Command-line Tools 安装器。
 *
 * ## 为什么需要它
 * Termux 主仓库**不提供** `sdkmanager`，而 `sdkmanager` 又不能靠 `sdkmanager` 自举（鸡生蛋）。
 * 因此这里直接下载 Google 官方 `commandlinetools-linux-*.zip` 并解压到
 * `<ANDROID_SDK_ROOT>/cmdline-tools/latest/`。
 *
 * ## 为什么在 Android/aarch64 上可行
 * cmdline-tools 本体是**纯 Java**：`bin/sdkmanager` 是 shell 包装脚本 + `lib` 下的 jar 包。
 * 只要有一个 JDK 即可运行，而内置用户态里可以 apt 安装 `openjdk-17`。
 *
 * ## 为什么自带解压
 * bootstrap 默认不带 `unzip`，用 Kotlin 的 [ZipInputStream] 解压可以避免再引入一个
 * 必须联网安装的依赖，也避免把「解压失败」伪装成「安装失败」。
 */
class CmdlineToolsInstaller(private val context: Context) {

    private val app = context.applicationContext

    /**
     * 候选下载地址（先官方，再镜像兜底）。
     * 版本号固定为 Google 长期保留的 `_latest` 包名，避免"最新版"链接漂移。
     */
    private val candidateUrls = listOf(
        "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip",
        "https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip"
    )

    fun sdkRoot(): File = File(Environment.androidSdkRoot(app))

    /** 官方布局：`<sdk>/cmdline-tools/latest/bin/sdkmanager` */
    fun targetDir(): File = File(sdkRoot(), "cmdline-tools/latest")

    fun sdkManager(): File = File(targetDir(), "bin/sdkmanager")

    fun isInstalled(): Boolean = sdkManager().isFile

    /**
     * 下载并安装 cmdline-tools。失败会抛异常（由任务中心上报为 FAILED），
     * 并会依次尝试候选源。
     *
     * @param report (percent 0..100, message)
     */
    fun install(report: (Int, String) -> Unit) {
        if (isInstalled()) {
            report(100, "Android Command-line Tools 已存在：${sdkManager().absolutePath}")
            return
        }
        val zip = File(app.cacheDir, "commandlinetools.zip")
        if (zip.exists()) zip.delete()

        var lastError: Throwable? = null
        for (url in candidateUrls) {
            try {
                report(3, "下载 commandline-tools：$url")
                download(url, zip) { percent -> report(3 + percent / 2, "下载中 $percent%") }
                report(53, "解压到 ${targetDir().absolutePath}")
                extract(zip, targetDir()) { percent -> report(53 + percent / 4, "解压中 $percent%") }
                markExecutables(targetDir())
                runCatching { zip.delete() }
                if (!sdkManager().isFile) error("解压完成但未找到 bin/sdkmanager（包结构异常）")
                report(80, "sdkmanager 已就绪")
                return
            } catch (t: Throwable) {
                lastError = t
                report(3, "该源失败：${t.message ?: t.javaClass.simpleName}，尝试下一个源")
            }
        }
        throw IllegalStateException(
            "Android Command-line Tools 安装失败：${lastError?.message ?: "未知错误"}。" +
                "该组件需从 dl.google.com 下载，请确认网络可达。"
        )
    }

    // ------------------------------------------------------------------ 下载

    private fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NebulaForgeIDE")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (total > 0) {
                            val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 解压

    /** 解压并**剥掉压缩包最外层的 `cmdline-tools/` 目录**，得到 `<sdk>/cmdline-tools/latest/bin/...`。 */
    private fun extract(zip: File, outDir: File, onProgress: (Int) -> Unit) {
        outDir.mkdirs()
        val outRoot = outDir.canonicalFile
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val raw = entry.name
                val relative = raw.substringAfter('/', missingDelimiterValue = "")
                if (relative.isEmpty()) {
                    zis.closeEntry()
                    continue
                }
                val target = File(outRoot, relative)
                // zip-slip 防护：解析后必须仍位于目标目录内。
                if (!target.canonicalPath.startsWith(outRoot.path + File.separator)) {
                    zis.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zis.copyTo(out, 64 * 1024) }
                    count++
                    if (count % 20 == 0) onProgress((count.coerceAtMost(400) * 100 / 400))
                }
                zis.closeEntry()
            }
        }
        onProgress(100)
    }

    /** bin/ 下的启动脚本必须可执行，否则 sdkmanager "不可执行"。 */
    private fun markExecutables(root: File) {
        val bin = File(root, "bin")
        bin.listFiles()?.forEach { file ->
            file.setReadable(true, false)
            file.setExecutable(true, false)
        }
    }
}
