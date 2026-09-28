package com.nebulaforge.core.toolchain

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「构建自愈」之二：把 Gradle Wrapper 的发行版下载地址换成**本机网络可达的镜像**。
 *
 * ## 真机取证（用户截图：Flutter 工程）
 * ```
 * Running Gradle task 'assembleDebug'...
 * sun.security.ssl.SSLSocketInputRecord.decode(...)
 * ... 16 more
 * Gradle threw an error while downloading artifacts from the network
 * ```
 * 同时该工程 `android/gradle/wrapper/gradle-wrapper.properties` 写着：
 * `distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-all.zip`。
 * 在设备上实测：`services.gradle.org` **完全不通**（连接超时 / SSL 异常），
 * 而 `mirrors.aliyun.com/macports/distfiles/gradle/` 同一文件 **200** 秒回，
 * 且路径与文件名与官方**完全一致** —— 因此只需换 host，无需改路径、不需要任何额外校验。
 *
 * 幂等且安全：
 *  - 只改写官方 `services.gradle.org/distributions/` 开头的 URL；
 *  - 改写前对镜像做一次 HEAD 探测，镜像上没有这个文件就原样不动（绝不把用户工程改坏）；
 *  - 已经是镜像地址就直接跳过；文件读不到、网络不可用等一律静默放过（自愈不能反过来打断构建）。
 */
object GradleDistributionMirror {

    private const val OFFICIAL = "https://services.gradle.org/distributions/"

    /** 实测可用的国内镜像（路径/文件名与官方一致）。 */
    private val MIRRORS = listOf(
        "https://mirrors.aliyun.com/macports/distfiles/gradle/",
        "https://mirrors.huaweicloud.com/gradle/"
    )

    private const val PROPS = "gradle/wrapper/gradle-wrapper.properties"

    /** 返回需要展示给用户的自愈说明；无需改动时为 null。 */
    fun ensure(projectRoot: File): String? {
        if (!projectRoot.isDirectory) return null
        val targets = listOf(
            File(projectRoot, PROPS),
            File(projectRoot, "android/$PROPS")
        ).filter { it.isFile }
        if (targets.isEmpty()) return null

        val notes = mutableListOf<String>()
        for (props in targets) {
            val text = runCatching { props.readText() }.getOrNull() ?: continue
            val match = Regex("""distributionUrl\s*=\s*(\S+)""").find(text) ?: continue
            // properties 里 `:` 会被转义成 `\:`，还原后再判断
            val current = match.groupValues[1].replace("\\:", ":")
            if (!current.startsWith(OFFICIAL)) continue
            val fileName = current.substringAfterLast('/')
            val mirror = MIRRORS.firstOrNull { reachable(it + fileName) } ?: continue
            val fixed = text.replace(match.value, "distributionUrl=" + (mirror + fileName).replace(":", "\\:"))
            runCatching { props.writeText(fixed) }.onSuccess {
                notes += "Gradle 发行版改用镜像下载：$fileName（services.gradle.org 在本机网络下不可达，" +
                    "已改为 ${mirror.substringAfter("//").substringBefore('/')}）"
            }
        }
        return notes.ifEmpty { null }?.joinToString("；")
    }

    private fun reachable(url: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        conn.instanceFollowRedirects = true
        val code = conn.responseCode
        conn.disconnect()
        code in 200..399
    }.getOrDefault(false)
}
