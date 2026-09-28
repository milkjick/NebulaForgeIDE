package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File

/**
 * 「构建自愈」之二：让 AGP 自带的 aapt2 在 arm64 真机上能跑起来。
 *
 * ## 真机取证（用户截图）
 * Android / Flutter 工程构建都在资源编译这一步炸掉：
 * ```
 * > Execution failed for AarResourcesCompilerTransform:
 *   .../caches/8.9/transforms/core-1.13.1.492d730d72cc3a27801c2878656b5a18/
 * > AAPT2 aapt2-8.6.0-11315950-linux Daemon #0: Daemon startup failed
 * ```
 * 现场取证：AGP 8.x **不用** `<sdk>/build-tools/<v>/aapt2`，而是把 Maven 上的
 * `com.android.tools.build:aapt2:8.6.0-11315950` 解到 Gradle 缓存
 * `<gradleUserHome>/caches/<ver>/transforms/<hash>/transformed/aapt2-8.6.0-11315950-linux/aapt2`，
 * 然后**直接 exec** 它。该产物只有 linux/mac/win 三套 **x86_64** 二进制（ELF e_machine=0x3E），
 * arm64 设备上根本起不来，于是 daemon 启动失败。
 *
 * ## 为什么不能只把二进制换掉
 * 设备里唯一能跑的 arm64 aapt2 依赖 `libfmt.so` / `libc++_shared.so`（在用户态 `<usrRoot>/lib`），
 * 裸跑直接报 `CANNOT LINK EXECUTABLE: library "libfmt.so" not found`；而 AGP 是直接 exec 的，
 * **不会带 `LD_LIBRARY_PATH`**。所以替换成三件套：
 * ```
 * aapt2              —— 包装器：内设 LD_LIBRARY_PATH=<usrRoot>/lib 后 exec 真身
 * aapt2.bin          —— arm64 真身（资产 toolchain/aapt2-arm64，回退用户态 bin/aapt2、SDK build-tools）
 * aapt2.x86_64.orig  —— 原 x86_64 二进制备份（不删，可回溯）
 * ```
 *
 * ## 再加一道保险
 * 缓存里那份是 AGP 解包出来的，缓存被清就会重新解出 x86_64。所以同时把 AGP 官方开关
 * `android.aapt2FromMavenOverride` 写进**全局** `<gradleUserHome>/gradle.properties`，指向
 * `<filesDir>/toolchain/aapt2`（同样是自带 LD_LIBRARY_PATH 的包装器）：Android 工程、Flutter
 * 工程（外壳会把工程搬到应用私有目录再构建）以及以后新建的工程全都受益，且不依赖缓存。
 *
 * 全程幂等：已是包装器 / 开关已正确 / 无需替换时都不产生任何输出噪音。
 */
object Aapt2ArchRepair {

    /** 随包分发的 arm64 aapt2 真身。 */
    private const val ASSET = "toolchain/aapt2-arm64"

    private const val DIR_PREFIX = "aapt2-"
    private const val DIR_SUFFIX = "-linux"
    private const val BIN = "aapt2.bin"
    private const val BACKUP = "aapt2.x86_64.orig"
    private const val WRAPPER = "aapt2"

    private const val OVERRIDE_KEY = "android.aapt2FromMavenOverride"

    /** ELF e_machine 小端在偏移 18：aarch64 = 0xB7，x86_64 = 0x3E。 */
    private const val AARCH64 = 0xB7

    private const val MIN_BIN_SIZE = 100_000L

    /**
     * 检查并修复。返回需要展示给用户的自愈说明（无改动则空列表）。
     */
    fun ensure(context: Context): List<String> {
        val app = context.applicationContext
        val caches = File(Environment.gradleUserHome(app), "caches")
        val dirs = runCatching { findAapt2Dirs(caches) }.getOrDefault(emptyList())
        val broken = dirs.filter { isForeignArch(File(it, WRAPPER)) }

        val wrapper = runCatching { ensureToolchain(app) }.getOrNull()
        if (wrapper == null) {
            return if (broken.isNotEmpty()) {
                listOf(
                    "⚠ AGP 自带的 aapt2 是 x86_64 版（本机 arm64 无法执行），" +
                        "但没有找到可用的 arm64 版本可替换：请先在「设置 → 工具链」安装 Android SDK CLI"
                )
            } else {
                emptyList()
            }
        }

        val notes = mutableListOf<String>()
        var fixed = 0
        val arm64Bin = File(wrapper.parentFile, BIN)
        val usrLib = Environment.usrRoot(app)
        val shell = shellFor(app)
        for (dir in broken) {
            runCatching {
                patch(dir, arm64Bin, usrLib, shell)
                fixed++
            }
        }
        if (fixed > 0) {
            notes += "AGP 自带的 aapt2 是 x86_64 版（arm64 上会报 AAPT2 daemon startup failed），" +
                "已在 $fixed 处替换为 arm64 版 + 自带 LD_LIBRARY_PATH 的包装器"
        }
        runCatching {
            if (ensureGlobalOverride(app, wrapper)) {
                notes += "已写入全局 $OVERRIDE_KEY → 内置 arm64 aapt2" +
                    "（Android / Flutter / 以后新建的工程都生效）"
            }
        }
        return notes
    }

    // ------------------------------------------------------------ 资产 / 真身

    /**
     * 保证 `<filesDir>/toolchain/` 下有可用的 arm64 aapt2 真身与包装器；
     * 返回包装器（供 [OVERRIDE_KEY] 使用），拿不到则 null。
     */
    fun ensureToolchain(app: Context): File? {
        val dir = File(app.filesDir, "toolchain").apply { mkdirs() }
        val bin = File(dir, BIN)
        if (!isArm64(bin)) {
            val fromAsset = runCatching {
                app.assets.open(ASSET).use { input ->
                    bin.outputStream().use { out -> input.copyTo(out) }
                }
                true
            }.getOrDefault(false)
            if (!fromAsset || !isArm64(bin)) {
                val fallback = listOf(
                    File(Environment.usrRoot(app), "bin/aapt2"),
                    latestSdkAapt2(app)
                ).firstOrNull { isArm64(it) } ?: return null
                runCatching { fallback.copyTo(bin, overwrite = true) }.getOrNull() ?: return null
            }
            bin.setExecutable(true, false)
        }
        val wrapper = File(dir, WRAPPER)
        runCatching {
            // 每次构建都会重写：既保证内容最新，也顺带把设备上早先写下的
            // `#!/system/bin/sh` 版本换成 guest shell（见 wrapperScript 的说明）。
            wrapper.writeText(wrapperScript(bin, Environment.usrRoot(app), shellFor(app)))
            wrapper.setExecutable(true, false)
        }.getOrNull() ?: return null
        return wrapper
    }

    /** 就地替换：备份原 x86_64 → 放 arm64 真身 → 用包装器占住 AGP 的调用路径。 */
    private fun patch(dir: File, arm64Bin: File, usrLib: String, shell: String) {
        val target = File(dir, WRAPPER)
        val backup = File(dir, BACKUP)
        if (!backup.isFile && target.isFile && target.length() > MIN_BIN_SIZE) {
            target.copyTo(backup, overwrite = true)
        }
        val bin = File(dir, BIN)
        arm64Bin.copyTo(bin, overwrite = true)
        bin.setExecutable(true, false)
        target.writeText(wrapperScript(bin, usrLib, shell))
        target.setExecutable(true, false)
    }

    /**
     * guest 里真实存在的 shell（Termux 用户态）。
     *
     * 真机取证：包装器用 `#!/system/bin/sh` 时，proot 下链接器会先往 **stderr** 打一行
     *   WARNING: linker: Warning: couldn't read '/linkerconfig/ld.config.txt' for
     *   '/system/bin/sh' (using default configuration instead)
     * 而 AGP 是以 **daemon** 方式调用 aapt2 并读取它的输出做握手，这行告警被判成
     * `Unexpected error output` → `AAPT2 aapt2-8.6.0-11315950-linux Daemon #1: Daemon startup failed`
     * → `> Task :app:processDebugResources FAILED`。这正是用户报的「编译一次后再次编译会失败」。
     * 换成 guest 自带 shell（链接器配置在 proot 内正常）后不再产生该告警。
     */
    private fun shellFor(app: Context): String {
        val bin = File(Environment.usrRoot(app), "bin")
        return listOf("bash", "sh", "dash")
            .map { File(bin, it) }
            .firstOrNull { it.isFile }
            ?.absolutePath
            ?: "/system/bin/sh"
    }

    private fun wrapperScript(bin: File, usrRoot: String, shell: String): String =
        "#!$shell\n" +
            "export LD_LIBRARY_PATH=$usrRoot/lib\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}\n" +
            "exec ${bin.absolutePath} \"\$@\"\n"
}

// ---------------------------------------------------------------- 文件级辅助

/**
 * 把 AGP 官方开关写进**全局** `<gradleUserHome>/gradle.properties`：
 * 无论缓存被清、还是 Flutter 外壳把工程搬到应用私有目录重新构建，都能命中内置 arm64 aapt2。
 * 已正确则返回 false（不产生噪音），首次改写前留一份 `gradle.properties.nf-bak`。
 */
private fun ensureGlobalOverride(app: Context, wrapper: File): Boolean {
    val props = File(Environment.gradleUserHome(app), "gradle.properties")
    props.parentFile?.mkdirs()
    val lines = runCatching { props.readLines().toMutableList() }.getOrDefault(mutableListOf())
    val value = wrapper.absolutePath
    val idx = lines.indexOfFirst { it.trim().startsWith("android.aapt2FromMavenOverride") }
    if (idx >= 0 && lines[idx].trim() == "android.aapt2FromMavenOverride=$value") return false
    val backup = File(props.parentFile, "gradle.properties.nf-bak")
    if (!backup.isFile && props.isFile) {
        runCatching { props.copyTo(backup, overwrite = true) }
    }
    if (idx >= 0) {
        lines[idx] = "android.aapt2FromMavenOverride=$value"
    } else {
        lines.add("android.aapt2FromMavenOverride=$value")
    }
    props.writeText(lines.joinToString("\n").trimEnd() + "\n")
    return true
}

/** 在 `<gradleUserHome>/caches` 下找 AGP 解包出来的 `aapt2-*-linux` 目录（限深，避免遍历整个缓存）。 */
private fun findAapt2Dirs(root: File): List<File> {
    val out = ArrayList<File>()
    fun walk(dir: File, depth: Int) {
        if (depth > 8) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (!child.isDirectory) continue
            if (child.name.startsWith("aapt2-") && child.name.endsWith("-linux")) out += child
            walk(child, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

/**
 * 读 ELF 头得到 e_machine（小端在偏移 18）。
 * 返回 null 表示：不是常规可执行文件（太小 / 读不到 / 已经是 `#!/system/bin/sh` 包装器）。
 */
private fun elfMachine(f: File): Int? {
    return runCatching {
        if (!f.isFile || f.length() <= 100_000L) return null
        f.inputStream().use { ins ->
            val head = ByteArray(20)
            if (ins.read(head) < 20) return null
            if (head[0] == '#'.code.toByte()) return null
            head[18].toInt() and 0xFF
        }
    }.getOrNull()
}

/** 是否是本机能执行的 arm64 二进制。 */
private fun isArm64(f: File): Boolean = elfMachine(f) == 0xB7

/** 是否是「非 aarch64」的常规二进制（本机跑不了，需要替换）。 */
private fun isForeignArch(f: File): Boolean {
    val machine = elfMachine(f) ?: return false
    return machine != 0xB7
}

/** SDK 里 version 最新且确实是 arm64 的 aapt2（部分设备由应用侧安装器放入）。 */
private fun latestSdkAapt2(app: Context): File {
    val buildTools = File(Environment.androidSdkRoot(app), "build-tools")
    val versions = runCatching {
        buildTools.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name }
    }.getOrNull().orEmpty()
    for (version in versions) {
        val direct = File(version, "aapt2")
        if (isArm64(direct)) return direct
        val real = File(version, "aapt2.bin")
        if (isArm64(real)) return real
    }
    return File(buildTools, "_none_/aapt2")
}
