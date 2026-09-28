package com.nebulaforge.core.environment

import android.content.Context
import java.io.File

/**
 * 统一环境路径管理对象。
 *
 * 【工具链路线：方式 B —— IDE 内嵌 Termux 开源终端核心】
 * 不依赖用户单独安装 Termux App，而是把 Termux 的 bootstrap 用户态（一份最小化的 Linux
 * 用户态压缩包，按 CPU 架构分发）下载解压到本 App 自己的私有目录下，运行时以子进程方式执行。
 *
 * 与"方式 A（依赖外部 Termux App + RunCommandService）"的关键差异：
 *   - 路径不再是 /data/data/com.termux/files/usr（那是另一个 App 的私有存储，本 App 访问不到），
 *     而是本 App 自己的 filesDir 下的用户态目录，由 BootstrapInstaller 首次启动时下载解压。
 *   - 不再需要 com.termux.permission.RUN_COMMAND 权限，也不需要引导用户去 Termux 里手动改
 *     allow-external-apps；作为代价，IDE 自己要负责 bootstrap 包的下载、解压、执行权限设置。
 *   - 二进制执行仍需处理 Android 10+ 对私有目录 noexec 挂载的限制：bootstrap 解压后的可执行文件
 *     需要位于允许执行的目录，具体规避方案见 BootstrapInstaller 内的说明。
 *
 * 设计原则不变：不硬编码"安装后子目录名不确定"的路径，一律通过探测函数在运行时解析。
 */
object Environment {

    /**
     * 内嵌用户态根目录，位于本 App 私有存储下，不再是别的 App 的目录。
     * 对应 BootstrapInstaller 解压 bootstrap zip 后的目标位置。
     */
    fun usrRoot(context: Context): String = File(context.filesDir, "usr").absolutePath
    fun homeRoot(context: Context): String = File(context.filesDir, "home").absolutePath

    /**
     * 私有目录骨架的**唯一创建入口**。所有把 home 当工作目录的调用点都必须先走这里。
     *
     * 真机事故复盘：homeRoot()（files/home）被终端默认 cwd、工具链、LSP 探测、构建与运行时自检
     * 等约 15 处当作工作目录，但全仓库没有任何地方创建它。后果是一条被掩盖的因果链：
     *   1. 全新启动（且未曾触发逆向工具安装，后者会以 mkdirs 副作用顺带建出 home）时 files/home 不存在；
     *   2. [com.nebulaforge.core.exec.TermuxCommandExecutor] 的脚本以 `cd <cwd> || exit 125` 开头，
     *      于是命令还没执行就返回 125；
     *   3. 自检只看到「退出码 125 + marker 未打印」，就报 `embedded sh cannot execute` / `pty=fail exit=125`，
     *      看起来像 SELinux/noexec 拒绝 execve，实际只是工作目录不存在。
     * 因此 Application、安装器、运行时自检、终端都必须先调用本方法，而不是各自假设目录已存在。
     */
    fun ensureDirs(context: Context): File {
        val home = File(homeRoot(context))
        listOf(
            home,
            File(home, ".nebulaforge"),
            File(home, ".nebulaforge/bootstrap"),
            File(home, ".nebulaforge/toolchain"),
            File(home, ".nebulaforge/lsp"),
            File(home, ".nebulaforge/plugins"),
            File(home, "projects"),
            File(home, "android-sdk"),
            File(home, ".gradle"),
            File(home, ".android"),
            File(usrRoot(context)),
            File(tmpDir(context)),
            File(context.filesDir, "terminal"),
            File(context.filesDir, "reverse/mitm"),
            File(context.filesDir, "reverse/api"),
            File(context.filesDir, ".nebulaforge")
        ).forEach { runCatching { it.mkdirs() } }
        return home
    }

    /** 确保 home 存在并返回它；用于所有把 home 当 cwd / HOME 的调用点。 */
    fun ensureHome(context: Context): File = ensureDirs(context)

    fun binDir(context: Context): String = "${usrRoot(context)}/bin"
    fun libDir(context: Context): String = "${usrRoot(context)}/lib"
    fun tmpDir(context: Context): String = "${usrRoot(context)}/tmp"

    /**
     * 用户可见的项目根目录：/storage/emulated/0/NebulaForgeProjects
     *
     * 设计取舍：项目本体放在公共存储，用户在文件管理器里能直接看到、导出、拷贝；
     * 而工具链用户态（usr/、home/ 下的 SDK、Gradle 缓存、bootstrap）仍留在应用私有目录，
     * 因为那些路径需要可执行权限，公共存储挂载为 noexec。
     */
    fun publicProjectsRoot(): File = File("/storage/emulated/0/NebulaForgeProjects")

    /** 私有目录下的旧项目位置（1.62.x 及更早版本使用），仅用于一次性迁移 */
    fun legacyProjectsDir(context: Context): String = "${homeRoot(context)}/projects"

    /**
     * 项目目录。优先公共存储；若公共存储不可写（未授权/无卡），回退到私有目录，保证功能可用。
     */
    fun projectsDir(context: Context): String {
        val pub = publicProjectsRoot()
        if (pub.isDirectory || pub.mkdirs()) {
            val probe = File(pub, ".write_probe")
            if (runCatching { probe.writeText("ok"); probe.delete(); true }.getOrDefault(false)) {
                return pub.absolutePath
            }
        }
        return legacyProjectsDir(context)
    }

    /** 是否已具备公共存储读写能力（供设置页/新建向导提示用） */
    fun canWritePublicProjects(): Boolean {
        val pub = publicProjectsRoot()
        if (!pub.isDirectory && !pub.mkdirs()) return false
        val probe = File(pub, ".write_probe")
        return runCatching { probe.writeText("ok"); probe.delete(); true }.getOrDefault(false)
    }

    /**
     * 一次性把私有目录里的旧项目复制到公共项目目录。
     *
     * 采用「复制不删除」策略：迁移失败或用户不满意时可回退，不破坏既有数据。
     * 目标已存在同名目录时跳过，不覆盖用户已修改的版本。
     *
     * ★★ 真机取证（这是用户报「每次打开项目工作区都给我新建一个 python 项目」的根因）：
     * 旧实现**每次启动都跑一遍**这段复制，而旧目录 `files/home/projects/` 会一直留着
     * （复制不删除）。于是用户把 `py-multi`/`python-script-copy` 这类旧项目从
     * `/storage/emulated/0/NebulaForgeProjects` 里删掉之后，**下次冷启动又被原样复制回来** ——
     * 用户看到的就是「项目删不干净、一开工作区就冒出个 python 项目」。
     *
     * 现在两道闸门：
     *  1) **公共目录已有项目就完全不迁移**（用户已经有自己的工作区，旧目录里的东西不该再掺进来）；
     *  2) 迁移过就写一次性标记（`SharedPreferences` + 旧目录里的 `.migration_done` 双保险），
     *     之后再也不复制 —— 用户删掉的项目就真的消失了。
     *
     * @return 迁移结果（成功复制数、跳过数、失败明细）
     */
    fun migrateLegacyProjects(context: Context): MigrationResult {
        val prefs = context.applicationContext
            .getSharedPreferences("nebulaforge.environment", Context.MODE_PRIVATE)
        val legacy = File(legacyProjectsDir(context))
        val marker = File(legacy, ".migration_done")
        if (prefs.getBoolean(KEY_LEGACY_MIGRATED, false) || marker.isFile) {
            return MigrationResult(0, 0, emptyList())
        }
        if (!legacy.isDirectory) {
            markLegacyMigrated(prefs, context)
            return MigrationResult(0, 0, emptyList())
        }
        val target = publicProjectsRoot()
        // 闸门 1：公共目录里已经有项目 → 用户已有工作区，不再把旧目录内容掺进来。
        val existing = target.listFiles()?.count { it.isDirectory } ?: 0
        if (existing > 0) {
            markLegacyMigrated(prefs, context)
            return MigrationResult(0, 0, emptyList())
        }
        if (!canWritePublicProjects()) {
            return MigrationResult(0, 0, listOf("公共存储不可写，迁移已跳过"))
        }
        var moved = 0
        var skipped = 0
        val failures = mutableListOf<String>()
        legacy.listFiles()?.filter { it.isDirectory }?.forEach { src ->
            val dst = File(target, src.name)
            if (dst.exists()) { skipped++; return@forEach }
            val ok = runCatching { src.copyRecursively(dst, overwrite = false); true }.getOrDefault(false)
            if (ok) moved++ else failures += src.name
        }
        // 闸门 2：这一轮跑完（无论复制了几个）就永久收摊，避免下次启动又把用户删掉的项目变回来。
        markLegacyMigrated(prefs, context)
        return MigrationResult(moved, skipped, failures)
    }

    /** 写下「旧项目迁移已处理」的一次性标记（`SharedPreferences` + 旧目录内标记文件）。 */
    private fun markLegacyMigrated(
        prefs: android.content.SharedPreferences,
        context: Context
    ) {
        runCatching { prefs.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply() }
        runCatching {
            val dir = File(legacyProjectsDir(context))
            if (dir.isDirectory) {
                File(dir, ".migration_done").writeText(
                    "legacy projects already handled at ${System.currentTimeMillis()}\n"
                )
            }
        }
    }

    data class MigrationResult(val copied: Int, val skipped: Int, val failures: List<String>)

    /** 「旧项目已迁移过」的一次性标记键（见 [migrateLegacyProjects]）。 */
    private const val KEY_LEGACY_MIGRATED = "legacy_projects_migrated"

    /**
     * 旧项目迁移的一次性提示文案。
     *
     * 迁移在 Application 启动的后台线程完成，UI 无法直接拿到返回值，
     * 因此通过这个 StateFlow 把结果广播给首页/设置页做一次性提示；读取后应清空。
     */
    val projectMigrationNotice = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** 取出迁移提示并清空，避免重复弹给用户。 */
    fun consumeMigrationNotice(): String? = projectMigrationNotice.value?.also { projectMigrationNotice.value = null }

    fun androidSdkRoot(context: Context): String = "${homeRoot(context)}/android-sdk"
    fun gradleUserHome(context: Context): String = "${homeRoot(context)}/.gradle"

    /**
     * 各语言工具链在 **HOME 下**的用户级可执行目录。
     *
     * 为什么单独列出来：`go install` → `$GOPATH/bin`、`cargo install`/rustup → `$HOME/.cargo/bin`、
     * Flutter SDK → `$HOME/flutter/bin`（flutter 自带 dart）、`pip install --user` → `$HOME/.local/bin`。
     * 这些**都不在** `PREFIX/bin` 里，而 `PREFIX/bin` 曾是唯一的查找位置，于是真机现象是
     * 「设置里显示已安装，构建却 command not found」。
     *
     * 必须同时作用于两处，缺一即出现上述不一致：
     * 1. 构建注入的 PATH（[buildEnvironment]，由 pty 里的 shell 消费）；
     * 2. 工具链探测与查找（[findExecutable]，由工具链管理器消费）。
     */
    fun languageToolDirs(context: Context): List<String> = listOf(
        File(homeRoot(context), ".cargo/bin"),   // rustup / cargo install
        File(homeRoot(context), "go/bin"),       // go install
        File(homeRoot(context), "flutter/bin"),  // Flutter SDK（自带 dart）
        File(homeRoot(context), ".local/bin")    // pip install --user
    ).map { it.absolutePath }
    fun lspToolsDir(context: Context): String = "${homeRoot(context)}/.nebulaforge/lsp"
    fun pluginsDir(context: Context): String = "${homeRoot(context)}/.nebulaforge/plugins"

    /** bootstrap 包解压完成的标记文件路径；BootstrapInstaller 完成后写入该文件，避免重复解压 */
    fun bootstrapMarkerFile(context: Context): File = File(context.filesDir, ".bootstrap_installed")

    /** 判断内嵌用户态是否已完成初始化（bootstrap 是否已解压） */
    fun isBootstrapInstalled(context: Context): Boolean = bootstrapMarkerFile(context).exists()

    /**
     * 探测指定 JDK 主版本号的实际安装路径。
     * 扫描 usr/lib/jvm/ 下名称包含 "java-<majorVersion>" 的目录。
     *
     * @return 找到则返回绝对路径，未安装时返回 null（调用方需处理 null 情况）
     */
    fun resolveJdkHome(context: Context, majorVersion: Int): String? {
        val jvmRoot = File("${usrRoot(context)}/lib/jvm")
        if (!jvmRoot.exists() || !jvmRoot.isDirectory) return null
        return jvmRoot.listFiles { f -> f.isDirectory && f.name.contains("java-$majorVersion") }
            ?.firstOrNull()
            ?.absolutePath
    }

    /** 列出所有已探测到的 JDK 版本目录（供设置页"JDK 管理"卡片展示） */
    fun listInstalledJdkVersions(context: Context): List<InstalledJdk> {
        val jvmRoot = File("${usrRoot(context)}/lib/jvm")
        if (!jvmRoot.exists()) return emptyList()
        val versionRegex = Regex("""java-(\d+)""")
        return jvmRoot.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val match = versionRegex.find(dir.name) ?: return@mapNotNull null
            InstalledJdk(majorVersion = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null, home = dir.absolutePath)
        }
    }

    /** 探测已安装的 NDK 版本目录列表，供项目级设置下拉选择 */
    fun listInstalledNdkVersions(context: Context): List<String> {
        val ndkRoot = File("${androidSdkRoot(context)}/ndk")
        if (!ndkRoot.exists()) return emptyList()
        return ndkRoot.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty().sorted()
    }

    /**
     * adb 解析顺序：SDK platform-tools 优先，其次内置用户态（Termux android-tools 包）。
     *
     * aarch64 设备上官方 platform-tools 是 x86_64 二进制，在设备上根本无法执行，内置用户态里的
     * 原生 adb 才是真能跑的那个。此前只认 `platform-tools/adb`，导致「工具链里装了 android-tools
     * 且终端里 adb 可用，设置页/构建前置检查却永远显示 adb 不可用」。
     */
    fun resolveAdb(context: Context): File? = listOf(
        // 顺序很关键（arm64 真机取证）：
        //  - 内置用户态里的 adb 是 Termux android-tools 提供的 **aarch64 原生**二进制，设备上真能跑；
        //  - Android SDK platform-tools 里的官方 adb 是 **x86_64** ELF，在 arm64 设备上执行必然报
        //    `cannot execute: required file not found`（exit=127）。
        // 原先 SDK 优先，于是"终端里 adb 可用、设置页却永远显示 adb 失败"。
        File(binDir(context), "adb"),
        File(androidSdkRoot(context), "platform-tools/adb")
    ).firstOrNull { it.isFile && it.canExecute() }

    /** 内置用户态里的原生工具（aarch64 可直接执行）。 */
    fun guestTool(context: Context, name: String): File? =
        File(binDir(context), name).takeIf { it.isFile && it.canExecute() }

    /**
     * 检查 Android SDK 基础组件（platform-tools/内置 adb、build-tools 至少一个版本）是否已就绪。
     *
     * build-tools 判定兼顾内置用户态：官方 build-tools 里的 aapt2 是 x86_64 二进制，在 aarch64
     * 设备上不可执行，Termux 的原生 aapt2 才是设备上真能用的（APK 构建与逆向都依赖它）。
     */
    fun isAndroidSdkReady(context: Context): Boolean {
        val buildToolsRoot = File("${androidSdkRoot(context)}/build-tools")
        val hasSdkBuildTools = buildToolsRoot.exists() && buildToolsRoot.listFiles()?.any { it.isDirectory } == true
        return resolveAdb(context) != null && (hasSdkBuildTools || guestTool(context, "aapt2") != null)
    }

    fun resolveShell(context: Context): String {
        val candidates = listOf(File(binDir(context), "bash"), File(binDir(context), "sh"), File("/system/bin/sh"))
        return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath ?: "/system/bin/sh"
    }


    /**
     * SDK environment. ADB/sdk probes must not fail *because* a JDK is missing, so a missing JDK is
     * tolerated (javaHome = null)；但 sdkmanager / avdmanager 是 Java 程序，必须靠 JAVA_HOME 才能启动，
     * 因此 JDK 一旦存在就一并注入（这也修复了「装了 cmdline-tools 但 sdkmanager 起不来」）。
     */
    fun buildSdkEnv(context: Context): Map<String, String> {
        val sdk = androidSdkRoot(context)
        val buildTools = latestBuildToolsDir(context)?.absolutePath.orEmpty()
        return buildEnvironment(context, javaHome = resolveJdkHome(context, 17), extraPath = listOf(
            File(sdk, "platform-tools").absolutePath,
            File(sdk, "cmdline-tools/latest/bin").absolutePath,
            buildTools
        ))
    }

    /**
     * 取「最新且可用」的 build-tools 目录。
     *
     * 真机回归（2.12.90）：这里以前是 `maxByOrNull { it.name }`，而设备上同时存在
     *  - `build-tools/34.0.0/`：官方安装包，aapt2 / aidl / d8 / apksigner / zipalign / package.xml 齐全；
     *  - `build-tools/35.0.0/`：由 [ToolchainManager] 为 arm64 设备**外挂**的一个目录，
     *    里面只有一个指向内置用户态 `aapt2` 的符号链接（没有任何元数据）。
     * 按名字取最大值必然选中那个残缺目录，于是 `NEBULAFORGE_BUILD_TOOLS` 与设置页都指向它，
     * 而 AGP 见到这种目录会直接判 "Installed Build Tools revision 35.0.0 is corrupted"。
     *
     * 现在只认「真的装过」的版本：官方包一定带 `source.properties` / `package.xml`。
     * 若一个都没有（例如用户只靠内置用户态工具），再退回「至少得有 aapt2」的宽松判据，
     * 保证不会比改动前更差。
     */
    fun latestBuildToolsDir(context: Context): File? {
        val dirs = File(androidSdkRoot(context), "build-tools").listFiles { f -> f.isDirectory }
            ?: return null
        val complete = dirs.filter {
            File(it, "source.properties").isFile || File(it, "package.xml").isFile
        }
        val pool = if (complete.isNotEmpty()) complete else dirs.filter { File(it, "aapt2").isFile }
        return pool.maxByOrNull { it.name }
    }

    /**
     * build-tools 里的可执行文件：SDK 目录优先，其次退回内置用户态的同名原生工具。
     *
     * 逆向签名用的 apksigner 就靠这里解析：它随官方 build-tools 提供（x86_64 设备无关的
     * Java 脚本 + jar），而 arm 设备上更常见的是内置用户态里的 apksigner。
     */
    fun latestBuildToolsExecutable(context: Context, name: String): File? = latestBuildToolsDir(context)?.let { File(it, name) }
        ?.takeIf { it.isFile && it.canExecute() }
        ?: guestTool(context, name)
        ?.takeIf { it.isFile }

    fun findSdkExecutable(context: Context, name: String): File? {
        val candidates = listOf(
            File(androidSdkRoot(context), "cmdline-tools/latest/bin/$name"),
            File(androidSdkRoot(context), "platform-tools/$name"),
            File(binDir(context), name)
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    fun findExecutable(context: Context, name: String): File? {
        val direct = (
            listOf(
                File(binDir(context), name),
                File(androidSdkRoot(context), "platform-tools/$name"),
                latestBuildToolsDir(context)?.let { File(it, name) }
            ) + languageToolDirs(context).map { File(it, name) }
            ).filterNotNull().firstOrNull { it.isFile && it.canExecute() }
        if (direct != null) return direct
        return System.getenv("PATH").orEmpty().split(File.pathSeparator).asSequence()
            .map { File(it, name) }.firstOrNull { it.isFile && it.canExecute() }
    }

    /** Project Wrapper first; installed Gradle is only a fallback for non-wrapper projects. */
    fun resolveGradle(context: Context): File? {
        val candidates = listOf(
            File(binDir(context), "gradle"),
            File(homeRoot(context), "gradle/bin/gradle")
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() } ?: findExecutable(context, "gradle")
    }

    /**
     * 以**任意前缀**构造执行环境，供安装阶段探测 staging 目录里的用户态使用。
     *
     * 为什么必须显式设置 LD_LIBRARY_PATH：
     * Termux 官方 bootstrap 里的二进制把 DT_RUNPATH 硬编码成
     * `/data/data/com.termux/files/usr/lib`（Termux 自己的包目录），本 App 里该路径并不存在。
     * 动态链接器的搜索顺序是 DT_RPATH → LD_LIBRARY_PATH → DT_RUNPATH，
     * 也就是说 **LD_LIBRARY_PATH 优先于 DT_RUNPATH**；只要把它指向实际前缀的 lib 目录，
     * dash/bash 这类二进制才能在本 App 包名下正常启动。
     * 这条正是「初始化内置运行环境」始终停在 `embedded sh cannot execute` 的根因：
     * 探测 staging 里的 sh 时，环境里的 LD_LIBRARY_PATH/PREFIX 却指向尚未提交的最终 usr 目录。
     */
    fun buildPrefixEnv(context: Context, prefix: File): Map<String, String> {
        ensureDirs(context) // HOME 指向 homeRoot，必须先存在，否则探测脚本的 cd 会失败
        val lib = File(prefix, "lib").absolutePath
        val bin = File(prefix, "bin").absolutePath
        val path = listOf(bin, binDir(context), System.getenv("PATH").orEmpty())
            .filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)
        val libs = listOf(lib, libDir(context))
            .filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator)
        return linkedMapOf(
            "PREFIX" to prefix.absolutePath,
            "HOME" to homeRoot(context),
            "TMPDIR" to File(prefix, "tmp").absolutePath,
            "PATH" to path,
            "LD_LIBRARY_PATH" to libs,
            "TERM" to "xterm-256color", "COLORTERM" to "truecolor", "LANG" to "en_US.UTF-8",
            "PS1" to "\\u@nebulaforge:\\w\$ "
        )
    }

    private fun buildEnvironment(context: Context, javaHome: String?, extraPath: List<String>): Map<String, String> {
        ensureDirs(context) // 见 buildPrefixEnv：兜底保证 HOME/ANDROID_USER_HOME 等路径可用
        val sdk = androidSdkRoot(context)
        val pathParts = (if (javaHome != null) listOf("$javaHome/bin") else emptyList()) +
            extraPath + listOf(binDir(context), File(sdk, "platform-tools").absolutePath) +
            languageToolDirs(context) + listOf(System.getenv("PATH").orEmpty())
        val libs = (if (javaHome != null) listOf("$javaHome/lib") else emptyList()) + listOf(libDir(context), System.getenv("LD_LIBRARY_PATH").orEmpty())
        return linkedMapOf(
            "HOME" to homeRoot(context), "PREFIX" to usrRoot(context), "TMPDIR" to tmpDir(context),
            "ANDROID_HOME" to sdk, "ANDROID_SDK_ROOT" to sdk,
            "ANDROID_USER_HOME" to File(homeRoot(context), ".android").absolutePath,
            "GRADLE_USER_HOME" to gradleUserHome(context),
            // ---- 各语言的用户级缓存/工作目录：指到可写的 HOME 下，避免工具尝试写入 PREFIX 而失败 ----
            // 未安装对应工具链时这些变量无害（工具自己会创建目录）。
            "GOPATH" to File(homeRoot(context), "go").absolutePath,
            "GOCACHE" to File(homeRoot(context), ".cache/go-build").absolutePath,
            "GOMODCACHE" to File(homeRoot(context), "go/pkg/mod").absolutePath,
            // 代理走国内镜像 + direct 兜底：直连 proxy.golang.org 在境内基本不可用，
            // 表现为 `go build ./...` 卡住或报 unreachable；镜像不可用时仍会回退直连。
            "GOPROXY" to "https://goproxy.cn,direct",
            "CARGO_HOME" to File(homeRoot(context), ".cargo").absolutePath,
            "RUSTUP_HOME" to File(homeRoot(context), ".rustup").absolutePath,
            // sparse 协议是 cargo 1.70+ 的默认源协议，显式声明可避免旧版本走 git 索引（极慢）。
            "CARGO_REGISTRIES_CRATES_IO_PROTOCOL" to "sparse",
            "NPM_CONFIG_CACHE" to File(homeRoot(context), ".npm").absolutePath,
            "TERM" to "xterm-256color", "COLORTERM" to "truecolor", "LANG" to "en_US.UTF-8",
            "PATH" to pathParts.filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator),
            "LD_LIBRARY_PATH" to libs.filter { it.isNotBlank() }.distinct().joinToString(File.pathSeparator),
            "PS1" to "\\u@nebulaforge:\\w\$ "
        ).also { if (javaHome != null) it["JAVA_HOME"] = javaHome }
    }

    /** Runtime environment for Flutter/Dart without requiring Android/JDK build components. */
    fun buildFlutterEnv(context: Context): Map<String, String> {
        val flutter = File(homeRoot(context), "flutter")
        val dartSdk = File(flutter, "bin/cache/dart-sdk")
        return buildEnvironment(context, javaHome = null, extraPath = listOf(
            File(flutter, "bin").absolutePath,
            File(dartSdk, "bin").absolutePath,
            binDir(context)
        )).toMutableMap().apply {
            this["FLUTTER_ROOT"] = flutter.absolutePath
            this["PUB_CACHE"] = File(homeRoot(context), ".pub-cache").absolutePath
            // Flutter 首次运行 / pub get 都会去 flutter.dev、storage.googleapis.com 和 pub.dev
            // 拉引擎产物与包；真机实测这些官方域名在国内不可达（github.com 直接超时 10s+），
            // 表现就是「Flutter 装好了但 flutter --version 一直卡着」。指向国内可达镜像
            // （真机 guest 内实测 storage.flutter-io.cn / pub.flutter-io.cn 均 HTTP 200，0.3s）。
            this["PUB_HOSTED_URL"] = "https://pub.flutter-io.cn"
            this["FLUTTER_STORAGE_BASE_URL"] = "https://storage.flutter-io.cn"
        }
    }

    /** Runtime environment for Node/npm/TypeScript without requiring a JDK. */
    fun buildNodeEnv(context: Context): Map<String, String> = buildEnvironment(
        context, javaHome = null, extraPath = listOf(binDir(context))
    )

    fun buildTerminalEnv(context: Context): Map<String, String> = buildEnvironment(
        context,
        javaHome = resolveJdkHome(context, 17),
        extraPath = listOf(File(androidSdkRoot(context), "cmdline-tools/latest/bin").absolutePath, latestBuildToolsDir(context)?.absolutePath.orEmpty())
    )

    /** Language-server-specific runtime environment. Some servers, notably current JDT LS, require a newer JDK than the Android build JDK. */
    fun buildLspEnv(context: Context, jdkMajorVersion: Int): Map<String, String> {
        val javaHome = resolveJdkHome(context, jdkMajorVersion)
            ?: error("未找到 LSP 所需 JDK $jdkMajorVersion")
        return buildEnvironment(
            context,
            javaHome,
            listOf(File(androidSdkRoot(context), "cmdline-tools/latest/bin").absolutePath, latestBuildToolsDir(context)?.absolutePath.orEmpty())
        )
    }

    /**
     * 构建注入给 Gradle 子进程的环境变量表。
     * 方式 B 下子进程由本 App 直接 fork/exec，而非跨进程 Intent 下发，
     * 因此这里的路径全部指向本 App 私有目录。
     *
     * @throws IllegalStateException 若指定版本的 JDK 未安装
     */
    fun buildGradleEnv(context: Context, jdkMajorVersion: Int): Map<String, String> {
        val javaHome = resolveJdkHome(context, jdkMajorVersion)
            ?: error("未找到 JDK $jdkMajorVersion，请先在设置-工具链中安装")
        return buildEnvironment(
            context,
            javaHome,
            listOf(File(androidSdkRoot(context), "cmdline-tools/latest/bin").absolutePath, latestBuildToolsDir(context)?.absolutePath.orEmpty())
        )
    }
}

data class InstalledJdk(val majorVersion: Int, val home: String)
