package com.nebulaforge.core.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import kotlin.concurrent.thread

/** Shizuku 可用状态。 */
enum class ShizukuStatus(val label: String) {
    NOT_INSTALLED("未安装 Shizuku"),
    NOT_RUNNING("Shizuku 已安装但服务未运行"),
    OUTDATED("Shizuku 版本过低（需 v11+）"),
    NOT_AUTHORIZED("Shizuku 已运行，等待本应用授权"),
    READY("Shizuku 已授权可用"),
    ERROR("Shizuku 状态检测失败")
}

/**
 * Shizuku 接入（授权 + 提权命令执行）。
 *
 * 说明：
 *  - 授权走 Shizuku 自己的权限机制（`moe.shizuku.manager.permission.API_V23`），
 *    需要在 AndroidManifest 里声明 provider 与 uses-permission；
 *  - 执行走 `IShizukuService.newProcess(...)`：服务端由 Shizuku 以 shell/root 身份 fork，
 *    客户端只拿到 stdout/stderr/exit code 三个管道；
 *  - 该通道**没有 tty**，因此只用于一次性命令（AI 操作终端、adb-like 操作），
 *    交互式终端仍由内嵌 PTY + Termux 用户态承担。
 */
object ShizukuAccess {

    /** 请求授权的 requestCode，与 [ShizukuAccess.onPermissionResult] 配对。 */
    const val REQUEST_CODE = 42101

    private const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"

    @Volatile
    var lastError: String? = null
        private set

    private var listenersInstalled = false
    private var onStateChanged: ((ShizukuStatus) -> Unit)? = null

    /** Shizuku 管理端是否已安装（用于在"未运行"与"未安装"之间区分提示）。 */
    fun isManagerInstalled(context: Context?): Boolean {
        val ctx = context ?: return false
        return runCatching {
            ctx.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }

    /** 管理端版本号（未安装返回 null）。 */
    fun managerVersion(context: Context?): String? {
        val ctx = context ?: return null
        return runCatching { ctx.packageManager.getPackageInfo(MANAGER_PACKAGE, 0).versionName }.getOrNull()
    }

    fun ping(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 当前是否已获得 Shizuku 授权（binder 不在或版本过低时均为 false）。 */
    fun isAuthorized(): Boolean = runCatching {
        ping() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(context: Context?): ShizukuStatus = runCatching {
        if (!ping()) {
            return@runCatching if (isManagerInstalled(context)) ShizukuStatus.NOT_RUNNING else ShizukuStatus.NOT_INSTALLED
        }
        if (Shizuku.isPreV11()) return@runCatching ShizukuStatus.OUTDATED
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) ShizukuStatus.READY
        else ShizukuStatus.NOT_AUTHORIZED
    }.getOrElse { e ->
        lastError = errText(e)
        ShizukuStatus.ERROR
    }

    /**
     * 注册 binder 生命周期与授权结果监听；重复调用安全。
     *
     * 必须在拿到 binder 之后才能调用 Shizuku 的其它 API，因此这里用 binder 回调驱动 UI 刷新。
     */
    fun installListeners(context: Context?, onChanged: (ShizukuStatus) -> Unit = {}) {
        onStateChanged = onChanged
        if (listenersInstalled) return
        listenersInstalled = runCatching {
            Shizuku.addBinderReceivedListenerSticky { onStateChanged?.invoke(status(context)) }
            Shizuku.addBinderDeadListener { onStateChanged?.invoke(status(context)) }
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQUEST_CODE) {
                    lastError = if (grantResult == PackageManager.PERMISSION_GRANTED) null else "用户拒绝了 Shizuku 授权"
                    onStateChanged?.invoke(status(context))
                }
            }
            true
        }.getOrElse { e ->
            lastError = errText(e)
            false
        }
    }

    fun canRequestPermission(): Boolean = runCatching { ping() && !isAuthorized() }.getOrDefault(false)

    /** 发起授权请求；返回 false 表示当前无法请求（服务未运行）。 */
    fun requestPermission(): Boolean = runCatching {
        if (!ping()) {
            lastError = "Shizuku 服务未运行，请先在 Shizuku 应用中启动服务"
            return@runCatching false
        }
        Shizuku.requestPermission(REQUEST_CODE)
        true
    }.getOrElse { e ->
        lastError = errText(e)
        false
    }

    /** 供 Activity 的 onRequestPermissionsResult 转发。 */
    fun onPermissionResult(requestCode: Int, grantResult: Int, context: Context?) {
        if (requestCode != REQUEST_CODE) return
        lastError = if (grantResult == PackageManager.PERMISSION_GRANTED) null else "用户拒绝了 Shizuku 授权"
        onStateChanged?.invoke(status(context))
    }

    /** 执行身份描述（已授权时返回 shell / root）。 */
    fun execIdentity(): String? = runCatching {
        if (!isAuthorized()) return@runCatching null
        val uid = Shizuku.getUid()
        when (uid) {
            0 -> "root(uid=0)"
            2000 -> "shell(uid=2000)"
            else -> "uid=$uid"
        }
    }.getOrNull()

    /**
     * 打开一个**长驻**进程（全双工管道），供 MCP stdio 这类需要保持连接的协议使用。
     *
     * 与 [exec] 的区别：exec 等进程结束再返回整体输出；这里立刻返回可读写的句柄。
     * 失败（未授权 / binder 不可用）返回 null，让上层回退到别的通道。
     */
    fun openProcess(command: String, workdir: String? = null): DeviceProcessHandle? = runCatching {
        if (!isAuthorized()) return@runCatching null
        val binder = Shizuku.getBinder() ?: return@runCatching null
        val service = IShizukuService.Stub.asInterface(binder) ?: return@runCatching null
        val remote = service.newProcess(arrayOf("sh", "-c", command), null, workdir) ?: return@runCatching null
        val out = android.os.ParcelFileDescriptor.AutoCloseOutputStream(
            remote.outputStream ?: error("Shizuku 未提供进程写端")
        )
        val inp = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            remote.inputStream ?: error("Shizuku 未提供进程读端")
        )
        val err = runCatching {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream) as java.io.InputStream
        }.getOrElse { java.io.ByteArrayInputStream(ByteArray(0)) }
        DeviceProcessHandle(out, inp, err) { runCatching { remote.destroy() } }
    }.getOrNull()

    /**
     * 通过 Shizuku 执行一次性命令。
     *
     * @param command 交给 `sh -c` 的完整命令串
     * @param timeoutMs 超时后 destroy 远程进程
     */
    fun exec(command: String, timeoutMs: Long = 30_000): DeviceShellResult {
        val started = System.currentTimeMillis()
        check(ping()) { "Shizuku 服务未运行" }
        check(isAuthorized()) { "尚未获得 Shizuku 授权" }
        val binder = Shizuku.getBinder() ?: error("Shizuku binder 不可用")
        val service = IShizukuService.Stub.asInterface(binder) ?: error("无法获取 Shizuku 服务接口")
        val remote = service.newProcess(arrayOf("sh", "-c", command), null, null)
            ?: error("Shizuku 拒绝创建进程")

        var stdout = ""
        var stderr = ""
        var exitCode = -1
        val outThread = thread(name = "shizuku-stdout") { stdout = readAll(remote.inputStream) }
        val errThread = thread(name = "shizuku-stderr") { stderr = readAll(remote.errorStream) }
        val waitThread = thread(name = "shizuku-wait") {
            exitCode = runCatching { remote.waitFor() }.getOrDefault(-1)
        }
        waitThread.join(timeoutMs)
        val timedOut = waitThread.isAlive
        if (timedOut) runCatching { remote.destroy() }
        outThread.join(3000)
        errThread.join(3000)
        if (timedOut) stderr = (stderr + "\n命令超时（${timeoutMs}ms），已终止远程进程").trim()

        return DeviceShellResult(
            command = command,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            level = DeviceChannel.SHIZUKU,
            durationMs = System.currentTimeMillis() - started,
            timedOut = timedOut
        )
    }

    private fun readAll(pfd: ParcelFileDescriptor?): String = runCatching {
        pfd ?: return ""
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream: InputStream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrElse { errText(it) }
}
