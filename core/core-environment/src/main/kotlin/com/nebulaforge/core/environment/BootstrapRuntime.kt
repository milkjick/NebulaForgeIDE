package com.nebulaforge.core.environment

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Owns the lifecycle of the embedded Termux userland.
 *
 * This is deliberately separate from the UI installer: terminal/build/LSP code can ask for a
 * ready runtime and receives the same environment every time. Installation is transactional:
 * an incomplete extraction can never leave the normal usr/ tree marked as usable.
 */
class BootstrapRuntime(private val context: Context) {
    enum class State { MISSING, DOWNLOADING, EXTRACTING, VERIFYING, READY, FAILED }

    data class Status(
        val state: State,
        val architecture: String,
        val version: String,
        val message: String,
        val downloaded: Long = 0L,
        val total: Long = -1L
    )

    private val mutex = Mutex()
    private val installer = BootstrapInstaller(context)

    suspend fun ensureReady(onStatus: (Status) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 架构与版本号只解析一次：releaseVersion() 在版本文件尚未落盘时可能触发网络解析，
            // 若放在 onStatus 回调里逐次调用，解压阶段上千次回调会把安装拖到不可用。
            val arch = installer.detectArchitectureSafe()
            val version = releaseVersion()
            if (Environment.isBootstrapInstalled(context) && verifyRuntime()) {
                onStatus(Status(State.READY, arch, version, "内置用户态就绪"))
                return@withLock Result.success(Unit)
            }
            runCatching {
                onStatus(Status(State.DOWNLOADING, arch, version, "准备释放内置用户态（$arch）"))
                installer.installWithStatus { phase, done, total, message ->
                    val state = when (phase) {
                        BootstrapInstaller.Phase.DOWNLOAD -> State.DOWNLOADING
                        BootstrapInstaller.Phase.EXTRACT -> State.EXTRACTING
                        BootstrapInstaller.Phase.VERIFY -> State.VERIFYING
                    }
                    onStatus(Status(state, arch, version, message, done, total))
                }.getOrThrow()
                check(verifyRuntime()) { "内置用户态自检失败：${diagnoseRuntime()}" }
                onStatus(Status(State.READY, arch, version, "内置用户态就绪"))
            }.onFailure {
                onStatus(Status(State.FAILED, arch, version, it.message ?: "内置用户态初始化失败"))
            }
        }
    }

    /**
     * 自检失败时给出可定位的原因，替代此前只有一句 "runtime self-test failed" 的黑盒报错。
     * 典型原因：bin/sh 是悬空符号链接（SYMLINKS.txt 相对目标解析错误）、sh 缺失、
     * 或私有目录被 ROM 施加 noexec 限制。
     */
    fun diagnoseRuntime(): String {
        val sh = File(Environment.binDir(context), "sh")
        val prefix = File(Environment.usrRoot(context))
        if (!prefix.isDirectory) return "用户态根目录不存在：${prefix.absolutePath}"
        if (!sh.exists()) {
            val link = readlink(sh)
            return if (link != null) {
                "usr/bin/sh 是悬空符号链接 -> $link（目标不存在，通常是符号链接清单解析错误）"
            } else {
                "usr/bin/sh 缺失或符号链接重建失败"
            }
        }
        if (!sh.canExecute()) return "usr/bin/sh 存在但无执行权限（chmod 未生效？）"
        return "usr/bin/sh 无法真正执行（设备可能对私有目录施加了 noexec 限制，需走方案 A 伪装 .so 回退）"
    }

    /** 读取符号链接目标（android.system.Os.readlink，API 21+；失败返回 null）。 */
    private fun readlink(file: File): String? = runCatching {
        val osClass = Class.forName("android.system.Os")
        osClass.getMethod("readlink", String::class.java).invoke(null, file.absolutePath) as? String
    }.getOrNull()

    fun verifyRuntime(): Boolean {
        val sh = File(Environment.binDir(context), "sh")
        val prefix = File(Environment.usrRoot(context))
        if (!prefix.isDirectory || !sh.isFile || !sh.canExecute()) return false
        return try {
            val env = Environment.buildTerminalEnv(context).toMutableMap()
            env["PATH"] = "${Environment.binDir(context)}:${env["PATH"].orEmpty()}"
            val executor = com.nebulaforge.core.exec.TermuxCommandExecutor(sh.absolutePath)
            val marker = "NEBULA_BOOTSTRAP_OK"
            var sawMarker = false
            var exitCode = -1
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) {
                    val okFile = File(Environment.usrRoot(context), "bin/sh")
                    val testCmd = "printf 'NEBULA_BOOTSTRAP_OK\n'; test -x " + okFile.absolutePath + " && command -v sh >/dev/null"
                    // 同 BootstrapInstaller：探测 cwd 必须是真实存在的目录，否则 cd 失败→125 会被误判。
                    executor.execute(testCmd, Environment.ensureHome(context), env)
                        .collect { event ->
                            when (event) {
                                is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Line ->
                                    if (event.text.contains(marker)) sawMarker = true
                                is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Finished -> exitCode = event.exitCode
                            }
                        }
                }
            }
            sawMarker && exitCode == 0
        } catch (_: Throwable) { false }
    }

    /** 进程内缓存，避免每次状态回调都重复落地读盘 / 联网解析版本号。 */
    @Volatile
    private var cachedVersion: String? = null

    /**
     * 实际使用的 bootstrap release 版本。
     * 优先级：安装时落盘的 version 文件 → 内置 assets 的版本号 → 动态解析 GitHub 最新 tag
     * → FALLBACK_RELEASE_VERSION。
     *
     * 关键约束：**禁止在进度回调路径上联网**。此方法会被 onStatus 频繁调用，
     * 若每次都访问 GitHub API（未落盘时）会把安装过程拖到不可用，故结果必须缓存。
     */
    fun releaseVersion(): String {
        cachedVersion?.let { return it }
        val persisted = File(Environment.homeRoot(context), ".nebulaforge/bootstrap/version")
            .takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
        if (persisted != null) {
            cachedVersion = persisted
            return persisted
        }
        // 内置资产自带版本号：随包分发就是离线场景，不该为了显示版本号去联网。
        val embedded = EmbeddedBootstrap.forArch(installer.detectArchitectureSafe())?.version
        val resolved = embedded
            ?: TermuxReleaseMetadata.resolveLatestVersion()
            ?: BootstrapInstaller.FALLBACK_RELEASE_VERSION
        cachedVersion = resolved
        return resolved
    }
}

/** Small metadata client used by the installer to obtain GitHub's published asset digest. */
internal object TermuxReleaseMetadata {
    data class Asset(val name: String, val url: String, val sha256: String?)

    /**
     * 动态解析 Termux bootstrap 最新真实 release tag。
     * 硬编码版本号一旦与官方发布节奏错位，就会出现下载 404 进而 self-test failed，
     * 因此正常路径必须走这里；只有网络不可用时才回退到 BootstrapInstaller.FALLBACK_RELEASE_VERSION。
     */
    fun resolveLatestVersion(): String? = resolveFromApi() ?: resolveFromAtomFeed()

    /** 首选：GitHub REST API（可拿到权威 tag）。 */
    private fun resolveFromApi(): String? {
        val endpoint = "https://api.github.com/repos/termux/termux-packages/releases?per_page=10"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NebulaForgeIDE")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val arr = org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            for (i in 0 until arr.length()) {
                val tag = arr.getJSONObject(i).optString("tag_name")
                if (tag.startsWith("bootstrap-") && tag.contains("apt.android")) {
                    return tag.removePrefix("bootstrap-")
                }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 回退：github.com 的 releases atom 订阅。
     * api.github.com 在部分网络下被阻断/限流，而 github.com 主域与 release 下载同源，
     * 能通就能拿到同一份 tag 列表，避免「解析失败 → 用旧版本号 → 下载 404」。
     */
    private fun resolveFromAtomFeed(): String? {
        val endpoint = "https://github.com/termux/termux-packages/releases.atom"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "NebulaForgeIDE")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            Regex("releases/tag/(bootstrap-[0-9A-Za-z.\\-+]+)")
                .findAll(text)
                .map { it.groupValues[1].replace("%2B", "+") }
                .firstOrNull { it.contains("apt.android") }
                ?.removePrefix("bootstrap-")
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun resolve(version: String, architecture: String): Asset? {
        val tag = "bootstrap-${version.replace("+", "%2B")}"
        val endpoint = "https://api.github.com/repos/termux/termux-packages/releases/tags/$tag"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NebulaForgeIDE")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return null
            val wanted = "bootstrap-$architecture.zip"
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == wanted) {
                    val digest = a.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                    return Asset(wanted, a.optString("browser_download_url"), digest)
                }
            }
            null
        } finally { conn.disconnect() }
    }
}
