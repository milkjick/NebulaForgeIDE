package com.nebulaforge.app.reverse.api

import android.content.Context
import com.nebulaforge.core.device.RootAccess
import com.nebulaforge.core.device.ShizukuAccess
import com.nebulaforge.core.device.ShizukuStatus
import java.io.File

/**
 * 把本机 MITM CA 写进**系统**证书库，让除本应用以外的 App 也信任它。
 *
 * ## 为什么需要这条通道
 * 用 KeyChain 安装的证书是「用户 CA」，Android 7+ 起默认不被任何 App（含 WebView）信任。
 * 表现就是：代理明明在跑、请求也进了代理，但目标 App 依然报证书错误。
 * 真正生效的做法是把 CA 放进 `/system/etc/security/cacerts/<subject_hash>.0`。
 *
 * ## 两条实现路径
 *  - **Root**（uid=0）：能 remount /system 写盘，持久生效；
 *  - **Shizuku**（shell, uid=2000）：**一般写不了 /system**（remount 需要 root），
 *    只有设备本身是 userdebug/eng 或已 remount 过才可能成功，因此这里把它当「尽力而为」。
 *
 * ## Android 10+ 的坑
 * 10 起 `cacerts` 由 conscrypt APEX 提供，运行时读的是 `/apex/com.android.conscrypt/cacerts`。
 * 所以脚本除了持久化写入 `/system/etc/security/cacerts`，还会**把单张证书 bind mount 进 APEX 目录**，
 * 这样无需重启即可对**新启动**的进程生效（已运行的进程仍需重启才会重新读取证书库）。
 */
object SystemCaInstaller {

    /** `channel` 为实际使用的提权通道；`detail` 是原始输出，失败时用它解释原因。 */
    data class Outcome(val success: Boolean, val channel: String, val detail: String)

    /**
     * 本机 CA 是否已经落在**系统**证书库里。
     *
     * 界面与 AI 工具都需要一个「到底装没装」的确定答案，否则只能靠用户回忆点没点过安装按钮 ——
     * 「代理在跑却抓不到 HTTPS」八成就是这里没装成。
     *
     * 两个位置都查：Android 10+ 的证书库由 conscrypt APEX 提供（`/apex/...` 是运行时实际读的），
     * 而持久化写入的目标仍是 `/system/etc/security/cacerts`；只查一处会误报。
     */
    fun isInstalled(context: Context, ca: CertificateAuthority): Boolean {
        val hash = runCatching { ca.subjectHash(context) }.getOrNull() ?: return false
        return listOf(
            "/system/etc/security/cacerts/$hash.0",
            "/apex/com.android.conscrypt/cacerts/$hash.0"
        ).any { path -> runCatching { File(path).let { it.isFile && it.length() > 0 } }.getOrDefault(false) }
    }

    /** 系统证书库的路径（给界面/工具展示用，避免各处硬编码字符串）。 */
    fun systemPath(context: Context, ca: CertificateAuthority): String =
        "/system/etc/security/cacerts/${runCatching { ca.subjectHash(context) }.getOrDefault("?")}.0"

    fun install(context: Context, ca: CertificateAuthority, timeoutMs: Long = 60_000): Outcome {
        val hash = ca.subjectHash(context)
        val der = runCatching { ca.certificateFile().readBytes() }.getOrElse {
            return Outcome(false, "无", "CA 证书读取失败：${it.message ?: it.javaClass.simpleName}")
        }

        // 源文件必须放在提权进程读得到的路径：/sdcard 公共目录（shell/root 均可读）优先，
        // 应用私有 cacheDir 只有 root 读得到，所以按可用性二选一。
        val publicCopy = File("/storage/emulated/0/NebulaForgeIDE/certs", "nebulaforge-ca.crt")
            .also { runCatching { it.parentFile?.mkdirs(); it.writeBytes(der) } }
        val cacheCopy = File(context.cacheDir, "nf-ca-$hash.0")
            .also { runCatching { it.writeBytes(der) } }
        val src = when {
            publicCopy.isFile && publicCopy.length() > 0 -> publicCopy.absolutePath
            cacheCopy.isFile && cacheCopy.length() > 0 -> cacheCopy.absolutePath
            else -> return Outcome(false, "无", "无法暂存 CA 证书文件")
        }

        val rootAvailable = RootAccess.isAvailable()
        val shizukuStatus = runCatching { ShizukuAccess.status(context) }.getOrDefault(ShizukuStatus.ERROR)
        // Root 优先；没有 Root 且 Shizuku 未就绪时直接给出可读结论，不做无意义的尝试。
        if (!rootAvailable && shizukuStatus != ShizukuStatus.READY) {
            return Outcome(
                false, "无",
                "需要 Root 或已授权的 Shizuku 才能写系统证书库（当前：Root 不可用，Shizuku ${shizukuStatus.label}）"
            )
        }

        val script = buildString {
            appendLine("SRC=$src")
            appendLine("HASH=$hash")
            appendLine("TMP=/data/local/tmp/nf-ca-\$HASH.0")
            appendLine("APEX=/apex/com.android.conscrypt/cacerts")
            appendLine("SYS=/system/etc/security/cacerts")
            appendLine("cp \"\$SRC\" \"\$TMP\" 2>&1; chmod 644 \"\$TMP\" 2>&1")
            // 有的设备 /system 是独立分区，有的已经合并进 /；两种都试着 remount。
            appendLine("mount -o rw,remount /system 2>&1 || mount -o rw,remount / 2>&1 || true")
            appendLine("mkdir -p \"\$SYS\" 2>/dev/null || true")
            appendLine("if cp \"\$TMP\" \"\$SYS/\$HASH.0\" 2>/dev/null; then")
            appendLine("  chmod 644 \"\$SYS/\$HASH.0\" 2>/dev/null || true")
            appendLine("  chown root:root \"\$SYS/\$HASH.0\" 2>/dev/null || true")
            appendLine("  chcon u:object_r:system_security_cacerts_file:s0 \"\$SYS/\$HASH.0\" 2>/dev/null || true")
            appendLine("  echo SYS-INSTALLED")
            appendLine("fi")
            appendLine("if [ -d \"\$APEX\" ]; then")
            appendLine("  mount -o bind \"\$TMP\" \"\$APEX/\$HASH.0\" 2>/dev/null && echo APEX-BOUND || true")
            appendLine("fi")
            appendLine("ls -l \"\$SYS/\$HASH.0\" 2>/dev/null || true")
        }

        val channel = if (rootAvailable) "Root" else "Shizuku"
        val result = runCatching {
            if (rootAvailable) RootAccess.exec(script, timeoutMs) else ShizukuAccess.exec(script, timeoutMs)
        }.getOrElse {
            return Outcome(false, channel, "执行失败：${it.message ?: it.javaClass.simpleName}")
        }

        val out = result.stdout + result.stderr
        val sysOk = out.contains("SYS-INSTALLED")
        val apexOk = out.contains("APEX-BOUND")
        val success = sysOk || apexOk
        val detail = buildString {
            append("通道：").append(channel).append("；subject_hash=").append(hash)
            append(if (success) "\n结果：证书已写入系统证书库" else "\n结果：写入失败")
            if (sysOk) append("\n · /system/etc/security/cacerts/$hash.0（重启后仍生效）")
            if (apexOk) append("\n · 已 bind mount 到 conscrypt APEX（新启动的进程立即生效）")
            if (!success) {
                append("\n提示：Shizuku 身份是 shell，通常无权 remount /system；")
                append("若本机没有 Root，请改用「安装 CA（用户证书）」或在本应用内开启「信任本地 MITM 证书」。")
            }
            append("\n--- 原始输出 ---\n").append(out.trim().take(600))
        }
        return Outcome(success, channel, detail)
    }
}
