package com.nebulaforge.core.exec

import com.nebulaforge.core.pty.NativePty
import com.nebulaforge.core.pty.PtyShellArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Way-B execution path: commands are fork/exec'ed by the embedded PTY runtime, not by
 * Android's ProcessBuilder. This keeps build/tool processes inside the same userland used
 * by the real terminal.
 *
 * 关键实现约束（真机踩坑后固化，勿随意改回）：
 * 1. **不再把命令当「一行超长文本」键入 PTY**。PTY 是行缓冲设备：主设备在从端未排空输入
 *    队列时会短写，而原实现只 write 一次且忽略返回值，于是脚本被静默截断。真机日志里
 *    「只回显到 `printf '`、随后 cd 看起来失败、exit=125、marker 从未打印」正是这个截断：
 *    shell 收到的是半条语句，`||` 分支被当成正常执行路径。现在把脚本落盘，
 *    PTY 里只键入一行 `. '<script>'`，长度恒为几十字节。
 * 2. **`cd` 不再致命**。工作目录不可进入时只告警并继续，绝不把「目录问题」伪装成
 *    「命令失败」；同时把 `-e/-d/-x/-r/-w` 探测结果与 `ls -ld` 一并回显，便于下一次
 *    一眼定位是权限（缺 x 位 / SELinux）还是路径不可见。
 * 3. 令牌行必须由**真实换行**结尾（脚本文件里 `printf '\n<token>:%s\n'`），
 *    否则退出码只能靠 EOF 兜底，长驻进程下永远拿不到 Finished。
 */
class TermuxCommandExecutor(private val shell: String, private val guestAware: Boolean = false) {
    sealed class Event {
        data class Line(val text: String) : Event()
        data class Finished(val exitCode: Int, val durationMs: Long) : Event()
    }

    fun execute(command: String, workingDir: File, env: Map<String, String>): Flow<Event> = callbackFlow {
        val token = "__NEBULA_EXIT_${UUID.randomUUID().toString().replace("-", "")}__"
        val handle = NativePty.nativeOpen(shell, 40, 140, PtyShellArgs.forShell(shell), outerEnv(env))
        // nativeOpen 失败时返回 -(errno)：0 是历史约定的「未知失败」，负值是真实 errno
        // （1=EACCES 拒绝执行、2=ENOENT 解释器/库缺失、8=ENOEXEC 格式错误），必须原样带回。
        if (handle <= 0L) {
            val detail = if (handle < 0L) "（errno=${-handle}，${errnoText(-handle)}）" else ""
            close(IllegalStateException("无法创建 embedded PTY$detail"))
            return@callbackFlow
        }
        val started = System.currentTimeMillis()
        val cancelled = AtomicBoolean(false)
        val reader = launch(Dispatchers.IO) {
            val buffer = ByteArray(32 * 1024)
            val pending = StringBuilder()
            try {
                while (!cancelled.get()) {
                    val n = NativePty.nativeRead(handle, buffer)
                    if (n <= 0) break
                    pending.append(String(buffer, 0, n, Charsets.UTF_8))
                    while (true) {
                        val idx = pending.indexOf("\n")
                        if (idx < 0) break
                        val line = pending.substring(0, idx).trimEnd('\r')
                        pending.delete(0, idx + 1)
                        val marker = "$token:"
                        if (line.contains(marker)) {
                            val code = line.substringAfter(marker).trim().toIntOrNull() ?: 1
                            trySend(Event.Finished(code, System.currentTimeMillis() - started))
                            cancelled.set(true)
                            close()
                            return@launch
                        }
                        // 过滤：① 脚本自打印的 __NEBULA_CMD_ECHO__ 标记；② PTY 对我们键入的
                        // `. '<脚本路径>'` 那一行的回显（内含 token，属于噪声不是命令输出）。
                        val isLaunchEcho = line.contains(token) || line.contains("cmd-$token.sh")
                        if (line.isNotBlank() && !line.startsWith("__NEBULA_CMD_ECHO__") && !isLaunchEcho) {
                            trySend(Event.Line(line))
                        }
                    }
                }
            } finally {
                if (pending.isNotBlank() && !cancelled.get()) trySend(Event.Line(pending.toString()))
            }
        }

        // 工作目录缺失是「可自愈」问题：先创建，再退回必然存在的目录，
        // 而不是让 cd 失败把整条命令判死。
        val cwd = resolveWorkingDir(workingDir)
        if (cwd.absolutePath != workingDir.absolutePath) {
            trySend(Event.Line("[nebula] 工作目录不可用：${workingDir.absolutePath}，已改用 ${cwd.absolutePath}"))
        }

        // guestAware：把命令包进 proot「前缀对齐」环境，并保留调用方的工作目录
        // （构建/LSP 都依赖 cwd 正确；proot 的 -w 用 guest 可见的绝对路径）。
        // GuestRuntime 在 guest 未就绪时返回 null，此时退化为宿主机执行（保持旧行为）。
        val effectiveCommand = if (guestAware) GuestRuntime.wrap(command, env, cwd) ?: command else command

        val script = buildString {
            append("printf '__NEBULA_CMD_ECHO__\\n'\n")
            append("if cd ").append(shellQuote(cwd.absolutePath)).append(" 2>/dev/null; then :\n")
            append("else\n")
            append("  printf '[nebula] 无法进入工作目录 %s：' ").append(shellQuote(cwd.absolutePath)).append("\n")
            append("  for __p in -e -d -x -r -w; do if [ \"\$__p\" ")
                .append(shellQuote(cwd.absolutePath))
                .append(" ]; then printf '%s=yes ' \"\$__p\"; else printf '%s=no ' \"\$__p\"; fi; done\n")
            append("  printf '| ls -ld: '; ls -ld ").append(shellQuote(cwd.absolutePath)).append(" 2>&1 | head -n 1\n")
            append("fi\n")
            // 子 shell 包裹：① 命令里的 exit 只会结束子 shell，不会在打印退出码之前
            // 把整个交互 shell 干掉（否则会话直接 EOF，UI 永远停在"运行中"）；
            // ② 子 shell 继承外层已 cd 好的工作目录，语义与直接执行一致。
            append("(\n").append(effectiveCommand).append("\n)\n")
            append("__nebula_code=\$?\n")
            append("printf '\\n").append(token).append(":%s\\n' \"\$__nebula_code\"\n")
            // 必须写入真实换行；字面量 "\\n" 会让外层 shell 的退出动作解析异常，
            // 出现退出标记已输出但最终进程码不一致。
            append("exit ${'$'}__nebula_code\n")
        }

        val scriptFile = File(resolveScriptDir(cwd), "cmd-$token.sh")
        val launchLine = withContext(Dispatchers.IO) {
            runCatching {
                scriptFile.writeText(script)
                ". ${shellQuote(scriptFile.absolutePath)}"
            }.getOrElse { t ->
                // 落盘失败（无空间/只读）时退化为单行键入：把换行换成 `;`，
                // 虽然又回到长行方案，但至少功能不丢，并把降级原因告诉用户。
                trySend(Event.Line("[nebula] 脚本落盘失败（${t.message ?: t.javaClass.simpleName}），已降级为单行执行"))
                script.lineSequence().filter { it.isNotBlank() }.joinToString("; ")
            }
        }
        withContext(Dispatchers.IO) { NativePty.nativeWrite(handle, (launchLine + "\n").toByteArray(Charsets.UTF_8)) }

        awaitClose {
            cancelled.set(true)
            reader.cancel()
            // nativeClose may block in the Android PTY/proot stack while the guest is
            // unwinding. Never run it on the flow collector/reader thread: the exit
            // marker has already been delivered and the task must be allowed to finish.
            launch(Dispatchers.IO) {
                runCatching { NativePty.nativeSignal(handle, 15) }
                runCatching { NativePty.nativeClose(handle) }
                runCatching { scriptFile.delete() }
            }
        }
    }

    fun cancel(handle: Long) {
        if (handle != 0L) NativePty.nativeSignal(handle, 2)
    }

    /** 返回一个**保证存在**的工作目录；缺目录时创建，仍失败则退回 shell 所在目录。 */
    private fun resolveWorkingDir(workingDir: File): File {
        if (workingDir.isDirectory) return workingDir
        runCatching { workingDir.mkdirs() }
        if (workingDir.isDirectory) return workingDir
        return File(shell).parentFile?.takeIf { it.isDirectory } ?: File("/")
    }

    /**
     * 脚本落盘目录：优先 embedded 前缀的 tmp（bootstrap 的 usr/tmp），
     * 其次工作目录下的隐藏临时目录，最后兜底工作目录本身。三者都做可写探测，
     * 避免把「无法写脚本」又变成一次「命令莫名失败」。
     */
    private fun resolveScriptDir(cwd: File): File {
        val prefixTmp = File(shell).absoluteFile.parentFile?.parentFile?.resolve("tmp")
        val candidates = listOfNotNull(prefixTmp, File(cwd, ".nebulaforge/tmp"), cwd)
        return candidates.firstOrNull { dir -> writable(dir) } ?: cwd
    }

    private fun writable(dir: File): Boolean {
        if (!dir.isDirectory && !runCatching { dir.mkdirs() }.getOrDefault(false)) return false
        if (!dir.isDirectory) return false
        val probe = File(dir, ".nebula-write-probe")
        return runCatching {
            probe.writeText("ok")
            true
        }.getOrDefault(false).also { runCatching { probe.delete() } }
    }

    private fun errnoText(errno: Long): String = when (errno) {
        1L -> "EPERM"
        2L -> "ENOENT 解释器或依赖库缺失"
        8L -> "ENOEXEC 格式错误"
        13L -> "EACCES 被系统拒绝执行"
        else -> "errno"
    }

    /** guest 前缀：proot 内部看到的 Termux 前缀（在宿主上这个路径属于 Termux 应用的私有目录）。 */
    private val GUEST_PREFIX_HINT = "/data/data/com.termux/files/usr"

    /**
     * 外层 PTY shell（即 embedded 前缀里的 Termux bash）会用 `$PREFIX` 去读 `$PREFIX/etc/bash.bashrc`。
     * 而调用方传进来的 env 是给 **guest（proot 内部）** 用的：`PREFIX=/data/data/com.termux/files/usr`。
     * 外层 shell 照着这个路径去读，就落到另一个 App 的私有目录，于是每次执行前都先打印两行噪声：
     *
     *   `bash: /data/data/com.termux/files/usr/etc/bash.bashrc: Permission denied`
     *   `bash: /data/data/com.termux/files/home/.bashrc: Permission denied`
     *
     * guest 内部真正需要的值由 TermuxGuest.guestCommandLine 在 proot 里重新 export
     * （这些键都在它的 PROTECTED_ENV_KEYS 中，不受外层影响），所以把**外层 shell** 的
     * PREFIX/HOME/PATH 换成宿主真实路径是安全的，且能彻底消除上述噪声。
     */
    private fun outerEnv(env: Map<String, String>): Array<String> {
        val out = LinkedHashMap(env)
        val prefix = outerShellPrefix()
        if (prefix == null) {
            // 不是 embedded 前缀下的 shell（如 /system/bin/sh）：不做任何前缀假设，避免误导。
            out.remove("PREFIX")
            out.remove("TERMUX_PREFIX")
        } else {
            val host = prefix.absolutePath
            out["PREFIX"] = host
            out["TERMUX_PREFIX"] = host
            out["HOME"] = File(prefix.parentFile ?: prefix, "home").absolutePath
            out["TMPDIR"] = File(host, "tmp").absolutePath
            val path = out["PATH"].orEmpty()
            if (path.isBlank() || path.startsWith(GUEST_PREFIX_HINT)) {
                out["PATH"] = "$host/bin:$host/bin/applets:/system/bin:/system/xbin:/vendor/bin"
            }
            val ld = out["LD_LIBRARY_PATH"].orEmpty()
            if (ld.contains(GUEST_PREFIX_HINT)) {
                out["LD_LIBRARY_PATH"] = ld.split(":")
                    .filter { it.isNotBlank() && !it.startsWith(GUEST_PREFIX_HINT) }
                    .joinToString(":")
            }
        }
        return out.map { "${it.key}=${it.value}" }.toTypedArray()
    }

    /** 从 shell 路径反推 embedded 前缀（形如 `<prefix>/bin/bash`）；非 embedded shell 返回 null。 */
    private fun outerShellPrefix(): File? {
        val bin = File(shell).absoluteFile.parentFile ?: return null
        if (bin.name != "bin") return null
        val prefix = bin.parentFile ?: return null
        return prefix.takeIf { File(it, "bin/bash").isFile && File(it, "etc").isDirectory }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
