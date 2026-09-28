package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlin.math.min

/**
 * Transactional runtime manager for the Termux-userland Gradle Tooling API worker.
 * It never marks a bridge ready from file existence alone: SHA-256 and a real
 * `java -jar ... --self-test` execution are required.
 */
class GradleBridgeRuntimeManager(context: Context) {
    private val app = context.applicationContext
    val bridgeJar = File(app.filesDir, "runtime/tools/gradle-tooling-bridge.jar")
    private val shaFile = File(bridgeJar.absolutePath + ".sha256")
    private val shell = Environment.resolveShell(app)

    fun probe(): ToolchainStatus {
        if (!bridgeJar.isFile) return ToolchainStatus(ToolchainComponent.GRADLE_TOOLING_BRIDGE, ToolchainState.MISSING, bridgeJar.absolutePath, detail = "bridge JAR 不存在")
        val expected = shaFile.takeIf { it.isFile }?.readText()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            ?: return ToolchainStatus(ToolchainComponent.GRADLE_TOOLING_BRIDGE, ToolchainState.FAILED, bridgeJar.absolutePath, detail = "缺少 SHA-256 sidecar")
        val actual = sha256(bridgeJar)
        if (!expected.equals(actual, ignoreCase = true)) {
            return ToolchainStatus(ToolchainComponent.GRADLE_TOOLING_BRIDGE, ToolchainState.FAILED, bridgeJar.absolutePath, detail = "SHA-256 不匹配: expected=$expected actual=$actual")
        }
        val result = kotlinx.coroutines.runBlocking { runSelfTest() }
        return if (result.first == 0) {
            ToolchainStatus(ToolchainComponent.GRADLE_TOOLING_BRIDGE, ToolchainState.READY, bridgeJar.absolutePath, result.second.ifBlank { "Gradle Tooling API bridge self-test passed" }, result.second)
        } else {
            ToolchainStatus(ToolchainComponent.GRADLE_TOOLING_BRIDGE, ToolchainState.FAILED, bridgeJar.absolutePath, detail = result.second.ifBlank { "bridge self-test failed (${result.first})" })
        }
    }

    /** Downloads to a private staging file, supports HTTP Range resume, verifies SHA-256, then atomically commits. */
    suspend fun installFromUrl(url: String, expectedSha256: String, report: suspend (Int, String) -> Unit = { _, _ -> }) {
        require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "expectedSha256 必须是 64 位 SHA-256" }
        val toolsDir = bridgeJar.parentFile ?: error("invalid bridge path")
        toolsDir.mkdirs()
        val staging = File(toolsDir, ".bridge-${UUID.randomUUID()}.part")
        try {
            downloadResumable(url, staging, expectedSha256.lowercase(), report)
            val verified = sha256(staging)
            check(verified.equals(expectedSha256, true)) { "SHA-256 校验失败" }
            val tmp = File(toolsDir, ".gradle-tooling-bridge-${UUID.randomUUID()}.jar")
            staging.copyTo(tmp, overwrite = true)
            val finalSha = File(tmp.absolutePath + ".sha256")
            finalSha.writeText("$verified  ${bridgeJar.name}\\n")
            check(tmp.renameTo(bridgeJar)) { "无法原子提交 bridge JAR" }
            check(finalSha.renameTo(shaFile)) { "无法提交 SHA-256 sidecar" }
            val status = probe()
            check(status.state == ToolchainState.READY) { "bridge 提交后 self-test 失败: ${status.detail}" }
            report(100, "Gradle Tooling API bridge 安装并验证完成")
        } catch (t: Throwable) {
            staging.delete()
            report(100, "bridge 安装回滚: ${t.message ?: t.javaClass.simpleName}")
            throw t
        }
    }

    suspend fun installFromFile(source: File, expectedSha256: String? = null, report: suspend (Int, String) -> Unit = { _, _ -> }) {
        require(source.isFile) { "bridge JAR 不存在: ${source.absolutePath}" }
        val actual = sha256(source)
        if (expectedSha256 != null) check(actual.equals(expectedSha256, true)) { "源 bridge SHA-256 不匹配" }
        val dir = bridgeJar.parentFile ?: error("invalid bridge path")
        dir.mkdirs()
        val tmp = File(dir, ".bridge-${UUID.randomUUID()}.jar")
        try {
            source.copyTo(tmp, overwrite = true)
            val side = File(tmp.absolutePath + ".sha256").apply { writeText("$actual  ${bridgeJar.name}\\n") }
            check(tmp.renameTo(bridgeJar)) { "无法提交 bridge JAR" }
            check(side.renameTo(shaFile)) { "无法提交 SHA-256 sidecar" }
            check(probe().state == ToolchainState.READY) { "bridge self-test 失败" }
            report(100, "bridge 文件安装并验证完成")
        } catch (t: Throwable) {
            tmp.delete(); File(tmp.absolutePath + ".sha256").delete(); throw t
        }
    }

    private suspend fun downloadResumable(url: String, target: File, expected: String, report: suspend (Int, String) -> Unit) {
        var offset = if (target.isFile) target.length() else 0L
        var conn: HttpURLConnection? = null
        try {
            fun open(range: Long): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Accept-Encoding", "identity")
                if (range > 0) setRequestProperty("Range", "bytes=$range-")
            }
            conn = open(offset)
            var code = conn!!.responseCode
            if (offset > 0 && code == HttpURLConnection.HTTP_OK) {
                conn!!.disconnect(); target.delete(); offset = 0; conn = open(0); code = conn!!.responseCode
            }
            check(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) { "HTTP $code" }
            if (offset > 0 && code != HttpURLConnection.HTTP_PARTIAL) { target.delete(); offset = 0 }
            val base = offset
            val total = conn!!.contentLengthLong.takeIf { it > 0 }?.let { it + base } ?: -1L
            conn!!.inputStream.use { input ->
                java.io.RandomAccessFile(target, "rw").use { raf ->
                    raf.seek(base)
                    val buf = ByteArray(64 * 1024)
                    var done = base
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) ((done * 100) / total).toInt().coerceAtMost(99) else 0
                        report(pct, "下载 bridge ${if (total > 0) "$pct%" else "$done bytes"}")
                    }
                }
            }
            check(sha256(target).equals(expected, true)) { "下载完成但 SHA-256 不匹配" }
        } finally { conn?.disconnect() }
    }

    private suspend fun runSelfTest(): Pair<Int, String> {
        val jdk = Environment.resolveJdkHome(app, 17) ?: return -1 to "JDK 17 不存在"
        val java = File(jdk, "bin/java")
        if (!java.isFile) return -1 to "JDK java 不存在: ${java.absolutePath}"
        val executor = com.nebulaforge.core.exec.TermuxCommandExecutor(shell, guestAware = true)
        var code = -1
        val lines = mutableListOf<String>()
        executor.execute("${quote(java.absolutePath)} -jar ${quote(bridgeJar.absolutePath)} --self-test", bridgeJar.parentFile ?: app.filesDir, Environment.buildTerminalEnv(app)).collect { event ->
            when (event) {
                is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Line -> lines += event.text
                is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Finished -> code = event.exitCode
            }
        }
        return code to lines.takeLast(20).joinToString("\n")
    }

    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val b=ByteArray(64*1024); while(true){val n=input.read(b);if(n<0)break;md.update(b,0,n)} }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
