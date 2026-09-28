package com.nebulaforge.app.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nebulaforge.core.device.DeviceChannel
import com.nebulaforge.core.device.DeviceShellGate
import com.nebulaforge.core.device.RootAccess
import com.nebulaforge.core.device.ShizukuAccess
import java.io.File

/**
 * APK 归档信息：直接读文件本身（PackageManager.getPackageArchiveInfo），
 * 不需要设备特权，也不依赖 aapt2 —— 构建面板要展示「装的是哪个包、哪个版本」时随手就能拿到。
 */
data class ApkArchiveInfo(
    val file: File,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val sizeBytes: Long,
    val lastModified: Long
) {
    val sizeText: String
        get() = if (sizeBytes >= 1024L * 1024L) "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
        else "%.0f KB".format(sizeBytes / 1024.0)

    /** 「com.example.app 1.2.3」这样的展示串；读不到归档信息时退回文件名。 */
    val label: String
        get() {
            val pkg = packageName ?: return file.name
            return versionName?.let { "$pkg $it" } ?: pkg
        }
}

/**
 * 安装结果。
 *
 * [channel] 标明这次**实际**走的通道：静默安装（Shizuku/Root）与系统安装器（用户手动确认）
 * 是两种完全不同的体验，UI 必须如实展示，不能让用户以为已经装好了。
 */
data class InstallOutcome(
    val ok: Boolean,
    val channel: DeviceChannel,
    val message: String,
    val detail: String = ""
)

/**
 * 把构建产物装到**本机**。
 *
 * 通道优先级与理由：
 *  1. **Shizuku（shell, uid=2000）**：shell 自带 INSTALL_PACKAGES 权限，`pm install -r -d` 全程静默；
 *  2. **Root（uid=0）**：同上；
 *  3. 两者都没有 → 回落 `ACTION_VIEW` 系统安装器（需要 REQUEST_INSTALL_PACKAGES + 用户确认）。
 *
 * 内嵌 bootstrap 通道（[DeviceChannel.EMBEDDED]）**故意排除**：它是应用自身 uid，没有
 * INSTALL_PACKAGES，用它跑 `pm install` 只会得到一条看起来像权限错误的失败 —— 与其给假希望，
 * 不如直接走系统安装器。
 *
 * 文件可见性（真机实测的两个坑）：
 *  * `pm install` 由 shell 执行，而应用私有目录（Flutter 产物在 `<filesDir>/home/.nebulaforge/build/…`）
 *    shell 读不到 → 先由**应用自己**把 APK 复制到公共目录 `/sdcard/NebulaForgeInstalls/`
 *    （应用有 MANAGE_EXTERNAL_STORAGE，且该目录下文件落在 media_rw 组，shell 可读）；
 *  * 直接从 `/sdcard` 安装会撞 SELinux/FUSE 的路径限制 → shell 内先拷进 `/data/local/tmp` 再装。
 */
object ApkInstaller {

    /** 静默安装落地的公共中转目录（shell 可读）。 */
    private const val PUBLIC_STAGE_DIR = "/sdcard/NebulaForgeInstalls"

    private const val REMOTE_TMP = "/data/local/tmp/nebulaforge-install.apk"

    /** 读取 APK 的包名/版本；读不到时字段为 null（不抛异常，安装照旧可以走）。 */
    @Suppress("DEPRECATION")
    fun describe(context: Context, apk: File): ApkArchiveInfo {
        val info = runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info?.longVersionCode ?: 0L
        else info?.versionCode?.toLong() ?: 0L
        return ApkArchiveInfo(
            file = apk,
            packageName = info?.packageName,
            versionName = info?.versionName,
            versionCode = code,
            sizeBytes = runCatching { apk.length() }.getOrDefault(0L),
            lastModified = runCatching { apk.lastModified() }.getOrDefault(0L)
        )
    }

    /**
     * 当前可用的**静默**安装通道。
     *
     * 注意不要直接用 [DeviceShellGate.resolve]：它在没有 Root/Shizuku 时会回落到内嵌 bootstrap，
     * 而那个通道没有 INSTALL_PACKAGES，装不了。
     */
    fun silentChannel(context: Context): DeviceChannel = when (DeviceShellGate.resolve(context)) {
        DeviceChannel.ROOT, DeviceChannel.SHIZUKU -> DeviceShellGate.resolve(context)
        else -> when {
            RootAccess.isAvailable() -> DeviceChannel.ROOT
            ShizukuAccess.isAuthorized() -> DeviceChannel.SHIZUKU
            else -> DeviceChannel.NONE
        }
    }

    /** 静默安装（Shizuku/Root）。UI 上应先看 [silentChannel] 是否可用。 */
    fun installSilently(context: Context, apk: File, timeoutMs: Long = 300_000): InstallOutcome {
        val channel = silentChannel(context)
        if (channel == DeviceChannel.NONE) {
            return InstallOutcome(
                ok = false, channel = DeviceChannel.NONE, message = "没有可用的静默安装通道",
                detail = "静默安装需要 Shizuku 已授权，或设备已 Root。"
            )
        }
        if (!apk.isFile) {
            return InstallOutcome(false, channel, "APK 不存在", apk.absolutePath)
        }
        val staged = runCatching { stageForShell(apk) }.getOrElse { e ->
            return InstallOutcome(
                ok = false, channel = channel, message = "准备安装文件失败",
                detail = "${e.javaClass.simpleName}: ${e.message ?: ""}\n可以改用系统安装器安装（不需要中转文件）。"
            )
        }
        // -r 覆盖安装；-d 允许版本降级（反复改包名/降版本调试时很常见）。
        val cmd = "cp ${q(staged.absolutePath)} ${q(REMOTE_TMP)}" +
            " && pm install -r -d ${q(REMOTE_TMP)}" +
            "; rc=\$?; rm -f ${q(REMOTE_TMP)}; exit \$rc"
        val result = DeviceShellGate.exec(context, cmd, channel = channel, timeoutMs = timeoutMs)
        val text = (result.stdout.trim() + "\n" + result.stderr.trim()).trim()
        val ok = result.exitCode == 0 && !text.contains("Failure")
        return when {
            result.timedOut -> InstallOutcome(false, channel, "安装超时", text.ifBlank { "超过 ${timeoutMs / 1000} 秒未返回。" })
            ok -> InstallOutcome(true, channel, "安装成功", text)
            else -> InstallOutcome(false, channel, "安装失败", hintFor(text).ifBlank { text.ifBlank { "退出码 ${result.exitCode}" } })
        }
    }

    /**
     * 系统安装器兜底：把 APK 通过 FileProvider 交给 `ACTION_VIEW`，由用户手动确认安装。
     * 返回 [InstallOutcome.ok] 表示**安装界面已成功拉起**，不代表已经装好。
     */
    fun openSystemInstaller(context: Context, apk: File): InstallOutcome {
        if (!apk.isFile) return InstallOutcome(false, DeviceChannel.NONE, "APK 不存在", apk.absolutePath)
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrElse { e ->
            return InstallOutcome(
                ok = false, channel = DeviceChannel.NONE, message = "无法生成安装用文件 URI",
                detail = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            )
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            InstallOutcome(true, DeviceChannel.NONE, "已拉起系统安装器", "请在系统界面上确认安装。")
        }.getOrElse { e ->
            InstallOutcome(
                ok = false, channel = DeviceChannel.NONE, message = "系统安装器无法启动",
                detail = "${e.javaClass.simpleName}: ${e.message ?: ""}"
            )
        }
    }

    /** 系统「安装未知应用」开关是否被关掉（API 26+）。关着的话 ACTION_VIEW 会被系统直接拒绝。 */
    fun unknownSourcesBlocked(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()

    /** 跳到「允许安装未知应用」设置页，省得用户自己翻。 */
    fun openUnknownSourcesSettings(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@runCatching false
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 装完之后直接把应用拉起来（静默通道可用时才做）。 */
    fun launchApp(context: Context, packageName: String): InstallOutcome {
        val channel = silentChannel(context)
        if (channel == DeviceChannel.NONE) {
            return InstallOutcome(false, DeviceChannel.NONE, "无可用通道，无法自动启动", "请在桌面上手动打开 $packageName。")
        }
        val r = DeviceShellGate.exec(
            context,
            "monkey -p ${q(packageName)} -c android.intent.category.LAUNCHER 1",
            channel = channel,
            timeoutMs = 30_000
        )
        val text = (r.stdout.trim() + "\n" + r.stderr.trim()).trim()
        val ok = r.exitCode == 0 && !text.contains("No activities found")
        return if (ok) InstallOutcome(true, channel, "已启动 $packageName", text)
        else InstallOutcome(false, channel, "启动失败", hintFor(text).ifBlank { text })
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 让 shell 能读到这个 APK：公共存储下的文件本来就可读（落在 media_rw 组），
     * 只有应用私有目录（如 Flutter 暂存产物）才需要复制到公共中转目录。
     */
    private fun stageForShell(apk: File): File {
        val path = apk.absolutePath
        val publiclyReadable = path.startsWith("/sdcard/") || path.startsWith("/storage/emulated/0/")
        if (publiclyReadable) return apk
        val dir = File(PUBLIC_STAGE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) error("无法创建 ${dir.absolutePath}（缺少存储权限？）")
        // 固定文件名覆盖：避免每次构建都在中转目录里堆一个新文件。
        val out = File(dir, "install.apk")
        apk.copyTo(out, overwrite = true)
        return out
    }

    /** 把 `pm install` 的英文失败原因翻成人话，省得用户对着 INSTALL_FAILED_* 猜。 */
    private fun hintFor(text: String): String = when {
        text.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE") ->
            "设备上已有同包名但签名不同的应用，需要先卸载它再装。\n$text"
        text.contains("INSTALL_FAILED_VERSION_DOWNGRADE") ->
            "设备上的版本更高，本包是降级安装。\n$text"
        text.contains("INSTALL_FAILED_ALREADY_EXISTS") ->
            "已存在同包名应用，且本次不是覆盖安装。\n$text"
        text.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE") ->
            "设备存储空间不足。\n$text"
        text.contains("INSTALL_FAILED_USER_RESTRICTED") ->
            "系统限制了安装（部分机型的「纯净模式 / 外部来源应用」策略），请在系统设置里放行后重试。\n$text"
        text.contains("INSTALL_FAILED_TEST_ONLY") ->
            "这是 test-only 包，需要 `pm install -t`。\n$text"
        text.contains("INSTALL_PARSE_FAILED") ->
            "APK 解析失败（可能是产物不完整或签名缺失）。\n$text"
        text.contains("PERMISSION_DENIED") || text.contains("SecurityException") ->
            "执行身份权限不足，请检查 Shizuku 授权是否还在。\n$text"
        else -> text
    }

    /** shell 单引号转义。 */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
