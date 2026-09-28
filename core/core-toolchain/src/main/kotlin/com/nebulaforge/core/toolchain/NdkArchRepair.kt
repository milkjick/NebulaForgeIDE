package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File

/**
 * 「构建自愈」之四：让 NDK 自带的 `llvm-strip` 在 arm64 真机上能跑。
 *
 * ## 真机取证（Flutter 测试包构建失败）
 * ```
 * > Task :app:stripDebugDebugSymbols FAILED
 * .../ndk/<ver>/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip:
 *     not executable: 64-bit ELF file
 * ```
 * 现场取证：`ndk/<ver>/toolchains/llvm/prebuilt/` 下**只有** `linux-x86_64` 一套 host 工具
 * （`llvm-strip` 还是指向 `llvm-objcopy` 的符号链接，ELF e_machine=0x3E）。AGP 打包时会用
 * `<sdk>/ndk/<ver>/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip` 裁剪 `jniLibs` 里的
 * `.so`（Flutter 工程必然带 `libflutter.so`），而 arm64 设备无法执行 x86_64 二进制，
 * 于是 `stripDebugDebugSymbols` 必然失败，用户看到的就是「Flutter 测试包构建失败（exit 1）」。
 *
 * ## 修法
 * 设备用户态里**已经有一个能跑的 arm64 `llvm-strip`**（`<usrRoot>/bin/llvm-strip`，LLVM 21）：
 * 它和 NDK 的 llvm-strip 同源、命令行完全兼容，且**与目标架构无关**（arm64-v8a / armeabi-v7a /
 * x86_64 的 `.so` 都能裁）。所以只把 NDK 那条调用路径换成包装器：
 * ```
 * llvm-strip                  —— 包装器：内设 LD_LIBRARY_PATH=<usrRoot>/lib 后 exec 真身
 * llvm-strip.x86_64.orig      —— 原 x86_64（含符号链接形态）备份，不删，可回溯
 * ```
 * 写成包装器而不是直接拷二进制，是因为该二进制依赖用户态里的 `libc++_shared.so` 等，
 * 而 AGP 是**直接 exec**、不会带 `LD_LIBRARY_PATH`（与 [Aapt2ArchRepair] 同一个坑）。
 *
 * 全程幂等：已是本版本包装器时不重写、无输出；NDK 缺失 / 已是 arm64 时不做任何事。
 */
object NdkArchRepair {

    private const val WRAPPER = "llvm-strip"
    private const val BACKUP = "llvm-strip.x86_64.orig"

    /**
     * 检查并修复。返回需要展示给用户的自愈说明（无改动则空列表）。
     */
    fun ensure(context: Context): List<String> {
        val app = context.applicationContext
        val tools = runCatching { findStripTools(app) }.getOrDefault(emptyList())
        if (tools.isEmpty()) return emptyList()

        val natives = runCatching { nativeStrip(app) }.getOrNull()

        // 需要落盘的目标：① x86_64 原生二进制（含符号链接）；② 不是本版本的旧包装器。
        val script = natives?.let { wrapperScript(it, Environment.usrRoot(app), ndkGuestShell(app)) }
        val broken = tools.filter { ndkIsForeignElf(it) }
        val stale = tools.filter {
            !ndkIsForeignElf(it) && (script == null || runCatching { it.readText() != script }.getOrDefault(true))
        }
        if (broken.isEmpty() && stale.isEmpty()) return emptyList()

        if (script == null) {
            return listOf(
                "⚠ NDK 自带的 llvm-strip 是 linux-x86_64 版（arm64 真机无法执行，会报 " +
                    "\"not executable: 64-bit ELF file\"），但用户态里没有可用的 arm64 llvm-strip 可替换：" +
                    "请先在「设置 → 工具链」补齐用户态（Bootstrap）后重试"
            )
        }

        var fixed = 0
        for (tool in broken + stale) {
            runCatching {
                patch(tool, script)
                fixed++
            }
        }
        return if (fixed > 0) {
            listOf(
                "NDK 自带的 llvm-strip 是 linux-x86_64 版（arm64 真机执行必然失败），" +
                    "已在 $fixed 处替换为 arm64 版包装器（AGP 的 stripDebugSymbols 可正常工作）"
            )
        } else {
            emptyList()
        }
    }

    // ------------------------------------------------------------ 定位

    /** `<sdk>/ndk/<ver>/toolchains/llvm/prebuilt/<host>/bin/llvm-strip`（host 名不写死）。 */
    private fun findStripTools(app: Context): List<File> {
        val versions = File(Environment.androidSdkRoot(app), "ndk").listFiles { f -> f.isDirectory }
            ?: return emptyList()
        val out = ArrayList<File>()
        for (ndk in versions) {
            val hosts = File(ndk, "toolchains/llvm/prebuilt").listFiles { f -> f.isDirectory } ?: continue
            for (host in hosts) {
                val tool = File(host, "bin/$WRAPPER")
                if (tool.isFile) out += tool
            }
        }
        return out
    }

    /** 用户态里真正能执行的 arm64 strip（llvm-strip 优先，其次 binutils 的 strip）。 */
    private fun nativeStrip(app: Context): File? {
        val bin = File(Environment.usrRoot(app), "bin")
        return listOf("llvm-strip", "aarch64-linux-android-strip", "strip")
            .map { File(bin, it) }
            .firstOrNull { it.isFile && ndkElfMachine(it) == NDK_AARCH64 }
    }

    // ------------------------------------------------------------ 落盘

    /** 备份原 x86_64（若是符号链接则连链接一起改名）→ 用包装器占住 AGP 的调用路径。 */
    private fun patch(tool: File, script: String) {
        val backup = File(tool.parentFile, BACKUP)
        if (ndkIsForeignElf(tool)) {
            if (backup.exists()) backup.delete()
            if (!tool.renameTo(backup)) {
                runCatching { tool.copyTo(backup, overwrite = true) }
            }
        }
        // rename 掉符号链接时目标文件仍在，但为稳妥一律先删再写。
        if (tool.exists()) tool.delete()
        tool.writeText(script)
        tool.setExecutable(true, false)
    }

    private fun wrapperScript(native: File, usrRoot: String, shell: String): String =
        "#!$shell\n" +
            "# NebulaForge 生成：NDK 自带的 llvm-strip 是 linux-x86_64 ELF，arm64 真机无法执行。\n" +
            "# 转发到用户态里的 arm64 llvm-strip（与 NDK 同源、与目标架构无关，可裁剪任意 ABI 的 .so）。\n" +
            "export LD_LIBRARY_PATH=$usrRoot/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\n" +
            "exec ${native.absolutePath} \"\$@\"\n"
}

// ---------------------------------------------------------------- 文件级辅助

/**
 * guest 里真实存在的 shell（Termux 用户态）。
 *
 * 与 [Aapt2ArchRepair] 同因：`#!/system/bin/sh` 在 proot 下会让链接器往 stderr 打告警，而 AGP
 * 对工具进程的输出很敏感。统一用用户态 shell 更稳。
 */
private fun ndkGuestShell(app: Context): String {
    val bin = File(Environment.usrRoot(app), "bin")
    return listOf("bash", "sh", "dash")
        .map { File(bin, it) }
        .firstOrNull { it.isFile }
        ?.absolutePath
        ?: "/system/bin/sh"
}

/** ELF e_machine 小端在偏移 18：aarch64 = 0xB7，x86_64 = 0x3E。 */
private const val NDK_AARCH64 = 0xB7

/**
 * 读 ELF 头得到 e_machine（小端在偏移 18）。
 * 返回 null 表示：不是常规可执行文件（太小 / 读不到 / 已经是脚本包装器）。
 */
private fun ndkElfMachine(f: File): Int? = runCatching {
    if (!f.isFile || f.length() <= 100_000L) return null
    f.inputStream().use { ins ->
        val head = ByteArray(20)
        if (ins.read(head) < 20) return null
        if (head[0] == '#'.code.toByte()) return null
        head[18].toInt() and 0xFF
    }
}.getOrNull()

/** 是否是「非 aarch64」的常规二进制（本机跑不了，需要替换）。 */
private fun ndkIsForeignElf(f: File): Boolean {
    val machine = ndkElfMachine(f) ?: return false
    return machine != NDK_AARCH64
}
