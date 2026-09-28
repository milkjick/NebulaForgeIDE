package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.BootstrapRuntime
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.environment.ProotPathMapper
import com.nebulaforge.core.environment.TermuxGuest
import com.nebulaforge.core.exec.TermuxCommandExecutor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A real runtime probe. File existence alone is never considered Ready. */
data class ProbeResult(
    val ok: Boolean,
    val path: String? = null,
    val version: String = "",
    val detail: String = ""
)

enum class ToolchainComponent(val title: String) {
    BOOTSTRAP("Embedded Termux runtime"),
    GIT("Git"),
    PYTHON("Python"),
    NODE("Node.js"),
    JDK17("JDK 17"),
    JDK21_LSP("JDK 21 (JDT LS)"),
    GRADLE("Gradle"),
    ANDROID_CLI("Android SDK CLI"),
    PLATFORM_TOOLS("Android Platform Tools / ADB"),
    BUILD_TOOLS("Android Build Tools"),
    DART("Dart"),
    FLUTTER("Flutter"),
    GRADLE_TOOLING_BRIDGE("Gradle Tooling API Bridge"),

    // ---- 与「新建项目」模板一一对应的语言栈工具链（此前缺失：Go/Rust/PHP/Composer/Maven/CMake/Ninja/Lua）----
    GO("Go"),
    RUST("Rust"),
    PHP("PHP"),
    COMPOSER("Composer"),
    MAVEN("Maven"),
    CMAKE("CMake / Ninja / Clang"),
    // C 模板（c-console）只需要 clang + make，不该被迫装整套 CMake；
    // 同时 CppProjectType 的 clangd 也需要 clang 提供标准头文件。
    C_COMPILER("C 编译器 (Clang)"),
    NINJA("Ninja"),
    LUA("Lua 5.4"),

    // ---- 逆向工程工具（Termux main 仓库提供）----
    // 旧实现只有「从 GitHub Release 下载 jar」一条路：真机实测 apktool（约 20MB）在 2.0MB 处
    // 断流、jadx（约 40MB）在 2.5MB 处断流，`.part` 永远是半截文件 → 用户点多少次「安装」
    // 都失败，看到的就是「逆向工具链无法安装」。Termux main（清华镜像）里有 aarch64 可用的
    // apktool 3.0.3 与 jadx 1.5.6，走 apt 才是这台设备上唯一稳定的安装路径。
    APKTOOL("Apktool (APK 反编译/回编译)"),
    JADX("JADX (DEX 反编译)"),

    // ---- 模板所需语言服务（Termux 侧提供，安装后由 NativePty 真实执行探测，不靠"文件存在"报 READY）----
    LSP_GOPLS("Go LSP (gopls)"),
    LSP_RUST_ANALYZER("Rust LSP (rust-analyzer)"),
    LSP_LUA("Lua LSP (lua-language-server)"),

    // ---- TypeScript 语言栈 ----
    // Termux 仓库里**没有** typescript / ts-node / tsx / typescript-language-server 这些包
    // （它们只以 npm 包形式发布），因此不能走 runPackageInstall，必须走 npm 全局安装
    // （见 runNpmGlobalInstall）。
    TYPESCRIPT("TypeScript (tsc / ts-node / tsx)"),
    LSP_TYPESCRIPT("TypeScript LSP (typescript-language-server)")
}

enum class ToolchainState { MISSING, INSTALLING, READY, FAILED, BLOCKED }

data class ToolchainStatus(
    val component: ToolchainComponent,
    val state: ToolchainState,
    val path: String? = null,
    val version: String = "",
    val detail: String = "",
    val lastChecked: Long = System.currentTimeMillis()
)

/**
 * Single source of truth for toolchain state.
 * Terminal, Build/Run and LSP should consume EnvironmentSession from this manager instead of
 * rebuilding independent PATH/JAVA_HOME maps.
 */
/** 实时输出在内存里保留的最大行数（UI 只渲染尾部）。 */
private const val LIVE_LOG_LIMIT = 400

/** toolchain.log 超过该字节数就先清空，避免长期使用后无限增长。 */
private const val LIVE_LOG_FILE_LIMIT = 1_000_000L

class ToolchainManager(private val context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime = BootstrapRuntime(app)
    val taskCenter = ToolchainTaskCenter(app)
    val sdkInstaller = AndroidSdkInstaller(app, taskCenter)
    private val stateFile = File(Environment.homeRoot(app), ".nebulaforge/toolchain/state.json")

    /**
     * apt/dpkg 的全局串行闸门：同一时刻只允许一个 apt-get。
     *
     * dpkg 前端锁被占用时，后启动的 apt 会**立即**以
     * `E: Could not get lock ... It is held by process NNNNN (apt-get)` 退出（EXIT=100）。
     * 用户重复点「安装/修复」就会自己撞自己的锁，看到的现象却是「安装失败」。
     * 所以这里做的不是重试，而是从源头保证不并发。
     */
    private val aptGate = Mutex()

    private val _statuses = MutableStateFlow(loadPersisted())
    val statuses: StateFlow<List<ToolchainStatus>> = _statuses.asStateFlow()

    // ---- 实时输出：让「安装/修复」和探测的每一行输出都能被看见 ----
    // 之前这里只把 PTY 输出攒进局部 StringBuilder，调用返回后才写进状态；apt/pkg 下载动辄
    // 几十秒到几分钟，UI 只有一个 INSTALLING 字样，用户看起来就是「卡住 / 没有任何进度」。
    private val _liveLog = MutableStateFlow<List<String>>(emptyList())
    val liveLog: StateFlow<List<String>> = _liveLog.asStateFlow()

    fun clearLiveLog() {
        _liveLog.value = emptyList()
    }

    private fun appendLiveLog(line: String) {
        val current = _liveLog.value
        _liveLog.value = if (current.size >= LIVE_LOG_LIMIT) {
            current.subList(current.size - LIVE_LOG_LIMIT + 1, current.size) + line
        } else current + line
        // 同时落盘（App 私有目录），UI 被切走后仍可回溯；文件过大时先截断避免无限增长。
        runCatching {
            val file = File(app.filesDir, "logs/toolchain.log")
            file.parentFile?.mkdirs()
            if (file.length() > LIVE_LOG_FILE_LIMIT) file.writeText("")
            file.appendText(line + "\n")
        }
    }

    private val lspArtifacts = LspArtifactManager(app)
    private val _lspStatuses = MutableStateFlow<List<LspArtifactManager.Status>>(emptyList())
    val lspStatuses: StateFlow<List<LspArtifactManager.Status>>
        get() = _lspStatuses.asStateFlow()

    fun lspSpecs(): List<LspArtifactManager.Spec> = lspArtifacts.specs()

    fun lspSpecForLanguage(languageId: String): LspArtifactManager.Spec? =
        lspArtifacts.specs().firstOrNull { it.id == languageId.lowercase() }

    fun enqueueLspInstallForLanguage(languageId: String): String? =
        lspSpecForLanguage(languageId)?.let(::enqueueLspInstall)

    suspend fun refreshLspArtifacts(): List<LspArtifactManager.Status> = withContext(Dispatchers.IO) {
        val next = lspArtifacts.specs().map { lspArtifacts.check(it) }
        _lspStatuses.value = next
        next
    }

    fun enqueueLspInstall(spec: LspArtifactManager.Spec): String = taskCenter.enqueueCustom(
        "LSP · ${spec.displayName}"
    ) { report ->
        report(2, "读取 ${spec.displayName} release 元数据")
        lspArtifacts.install(spec) { percent, message -> report(percent, message) }
        refreshLspArtifacts()
    }

    /**
     * 全量重探所有组件，并**逐个**推送到 [statuses]。
     *
     * 为什么逐个推送：每个组件都要起一次内嵌用户态进程做真实探测，29 个组件在真机上要数十秒。
     * 以前是全部探完才 `_statuses.value = next`，期间界面上一片旧值、没有任何反馈，
     * 用户看到的就是「点了检测没反应」。现在每探完一个就刷新一次，清单会逐行亮起来。
     */
    suspend fun refresh(): List<ToolchainStatus> = withContext(Dispatchers.IO) {
        val previous = _statuses.value.associateBy { it.component }
        val accumulated = LinkedHashMap<ToolchainComponent, ToolchainStatus>()
        for (component in ToolchainComponent.entries) {
            accumulated[component] = probe(component)
            // 已探测的用新结果，未探测的沿用上一次快照，保证清单始终 29 行、顺序稳定
            _statuses.value = ToolchainComponent.entries.mapNotNull { accumulated[it] ?: previous[it] }
        }
        val next = accumulated.values.toList()
        _statuses.value = next
        persist(next)
        next
    }

    suspend fun install(component: ToolchainComponent): ToolchainStatus = withContext(Dispatchers.IO) {
        clearLiveLog()
        appendLiveLog("=== 安装/修复：${component.title} ===")
        setInstalling(component)
        try {
            when (component) {
                ToolchainComponent.BOOTSTRAP -> runtime.ensureReady().getOrThrow()
                ToolchainComponent.GIT -> runPackageInstall("git")
                ToolchainComponent.PYTHON -> runPackageInstall("python")
                ToolchainComponent.NODE -> runPackageInstall("nodejs-lts")
                ToolchainComponent.JDK17 -> runPackageInstall("openjdk-17")
                ToolchainComponent.JDK21_LSP -> runPackageInstall("openjdk-21")
                ToolchainComponent.GRADLE -> runPackageInstall("gradle")
                // SDK CLI 不能靠 sdkmanager 自举（鸡生蛋）：直接下载 Google 官方 commandline-tools（纯 Java）。
                ToolchainComponent.ANDROID_CLI -> runCmdlineToolsInstall()
                // adb：arm64 真机上只能用内置用户态的 android-tools（aarch64 原生 adb/fastboot）。
                // Google 官方 platform-tools 是 x86_64 ELF，装上也执行不了（真机 exit=127）。
                ToolchainComponent.PLATFORM_TOOLS -> installNativePlatformTools()
                // build-tools：官方 prebuilt 的 aapt2 同样是 x86_64（真机 `aapt2: cannot execute`），
                // 设备上真正可用的是 Termux 的 aarch64 原生 aapt2。
                ToolchainComponent.BUILD_TOOLS -> installNativeBuildTools()
                ToolchainComponent.DART -> runPackageInstall("dart")
                ToolchainComponent.FLUTTER -> runFlutterSdkInstall()
                ToolchainComponent.GRADLE_TOOLING_BRIDGE -> error("Gradle Tooling Bridge 需要提供已验证的 bridge JAR；请使用 GradleBridgeRuntimeManager.installFromUrl/installFromFile")
                // 包名均已在 Termux main 仓库索引中核实存在（golang/rust/php/composer/maven/cmake/ninja/clang/lua54/gopls/rust-analyzer/lua-language-server）
                ToolchainComponent.GO -> runPackageInstall("golang")
                ToolchainComponent.RUST -> runPackageInstall("rust")
                ToolchainComponent.PHP -> runPackageInstall("php")
                ToolchainComponent.COMPOSER -> runPackageInstall("composer")
                ToolchainComponent.MAVEN -> runPackageInstall("maven")
                // C++ 模板的构建链是 cmake+ninja+clang 三者，分开装会让用户装了 cmake 仍编译不了
                ToolchainComponent.CMAKE -> runPackageInstall("cmake ninja clang")
                ToolchainComponent.C_COMPILER -> runPackageInstall("clang make")
                ToolchainComponent.NINJA -> runPackageInstall("ninja")
                ToolchainComponent.LUA -> runPackageInstall("lua54")
                // 逆向工具：Termux main 仓库装（apktool 会连带 openjdk-21 + aapt2），
                // 不再走 GitHub Release 下载（真机实测必然在 2MB 左右断流）。
                ToolchainComponent.APKTOOL -> runPackageInstall("apktool")
                ToolchainComponent.JADX -> runPackageInstall("jadx")
                ToolchainComponent.LSP_GOPLS -> runPackageInstall("gopls")
                ToolchainComponent.LSP_RUST_ANALYZER -> runPackageInstall("rust-analyzer")
                ToolchainComponent.LSP_LUA -> runPackageInstall("lua-language-server")
                // TypeScript 三件套：编译器（构建任务用）+ 两个运行器（ts-node 直跑、tsx 更快的 watch/dev）。
                ToolchainComponent.TYPESCRIPT -> runNpmGlobalInstall(
                    listOf("typescript", "ts-node", "tsx"),
                    minFreeMb = 400
                )
                // 语言服务：typescript-language-server 运行时要 require("typescript")，
                // 因此必须与 typescript 同一次装进同一个全局 node_modules，单独装会报 MODULE_NOT_FOUND。
                ToolchainComponent.LSP_TYPESCRIPT -> runNpmGlobalInstall(
                    listOf("typescript-language-server", "typescript"),
                    minFreeMb = 300
                )
            }
            val status = probe(component)
            val final = if (status.state == ToolchainState.READY) status
            else status.copy(state = ToolchainState.FAILED, detail = status.detail.ifBlank { "安装命令完成，但真实探测仍未通过" })
            update(final)
            final
        } catch (t: Throwable) {
            val failed = ToolchainStatus(component, ToolchainState.FAILED, detail = t.message ?: t.javaClass.simpleName)
            update(failed)
            failed
        }
    }

    suspend fun installBuildPrerequisites(): List<ToolchainStatus> = withContext(Dispatchers.IO) {
        val order = listOf(
            ToolchainComponent.BOOTSTRAP,
            ToolchainComponent.JDK17,
            ToolchainComponent.GIT,
            ToolchainComponent.GRADLE,
            ToolchainComponent.PLATFORM_TOOLS,
            ToolchainComponent.BUILD_TOOLS
        )
        for (component in order) {
            val current = probe(component)
            if (current.state != ToolchainState.READY) install(component)
        }
        // SDK 平台本身不能只靠 build-tools/adb 判断；Android 项目 compileSdk 对应的
        // platforms/android-35 必须真实存在。使用同一事务任务安装最小 Android 构建 profile。
        if (probe(ToolchainComponent.ANDROID_CLI).state == ToolchainState.READY) {
            enqueueMinimalAndroidBuildEnvironment()
        }
        refresh()
    }

    fun environmentSession(jdkMajor: Int = 17): EnvironmentSession {
        return EnvironmentSession(
            terminal = Environment.buildTerminalEnv(app),
            gradle = runCatching { Environment.buildGradleEnv(app, jdkMajor) }.getOrElse { Environment.buildTerminalEnv(app) },
            sdk = Environment.buildSdkEnv(app)
        )
    }

    /** Queue an install operation so UI survives configuration changes and exposes cancellation. */
    /**
     * Android SDK 相关组件共用一把串行闸门：cmdline-tools / platform-tools / build-tools 最终都调用
     * sdkmanager，重复入队只会互相等待，用户看到的就是「同一个 ADB 出现两条任务」。
     */
    private val androidSdkGroup = listOf(
        ToolchainComponent.ANDROID_CLI,
        ToolchainComponent.PLATFORM_TOOLS,
        ToolchainComponent.BUILD_TOOLS
    )

    fun enqueueInstall(component: ToolchainComponent): String {
        if (component in androidSdkGroup) {
            taskCenter.activeTaskInGroup(androidSdkGroup.map { it.name })?.let { return it.id }
        }
        return enqueueToolchainInstall(component)
    }

    private fun enqueueToolchainInstall(component: ToolchainComponent): String = taskCenter.enqueueInstall(component) { report ->
        report(5, "准备 ${component.title}")
        install(component)
        report(95, "重新执行真实探测")
        val checked = probe(component)
        if (checked.state != ToolchainState.READY) error(checked.detail.ifBlank { "真实探测失败" })
        report(100, "${component.title} 已验证")
    }

    fun enqueueMinimalAndroidBuildEnvironment(): String = sdkInstaller.installProfile(AndroidSdkPackages.minimalAndroidBuild)

    /** 安装指定 Android API 对应的 platform 与 Build Tools；多个版本可并存。 */
    fun enqueueAndroidSdkVersion(api: Int, buildTools: String = "$api.0.0"): String {
        require(api in AndroidSdkPackages.supportedPlatformApis) { "不支持的 Android API：$api" }
        require(buildTools in AndroidSdkPackages.supportedBuildTools) { "不支持的 Build Tools：$buildTools" }
        return sdkInstaller.installPackages(
            "Android SDK $api / Build Tools $buildTools",
            listOf("platforms;android-$api", "build-tools;$buildTools")
        )
    }

    private fun probe(component: ToolchainComponent): ToolchainStatus = when (component) {
        ToolchainComponent.BOOTSTRAP -> {
            // 状态唯一真源：必须来自 verifyRuntime() 的真实执行探测；
            // 失败时用 diagnoseRuntime() 给出可定位原因（悬空符号链接 / 缺 sh / noexec），
            // 而不是一句 "bootstrap runtime self-test failed"。
            val ready = runtime.verifyRuntime()
            val installed = Environment.isBootstrapInstalled(app)
            val state = when {
                ready -> ToolchainState.READY
                installed -> ToolchainState.FAILED
                else -> ToolchainState.MISSING
            }
            val detail = if (ready) "内置用户态自检通过（sh 可真实执行）" else runtime.diagnoseRuntime()
            ToolchainStatus(component, state,
                Environment.usrRoot(app), BootstrapRuntimeVersion.version(app), detail)
        }
        ToolchainComponent.GIT -> probeCommand(component, "git", listOf("--version"))
        ToolchainComponent.PYTHON -> probeCommand(component, "python", listOf("--version"))
        ToolchainComponent.NODE -> probeCommand(component, "node", listOf("--version"))
        ToolchainComponent.JDK17 -> {
            val home = Environment.resolveJdkHome(app, 17)
            if (home == null) ToolchainStatus(component, ToolchainState.MISSING, detail = "JDK 17 not found under ${Environment.usrRoot(app)}/lib/jvm")
            else probeFile(component, File(home, "bin/java"), listOf("-version"), home)
        }
        ToolchainComponent.JDK21_LSP -> {
            val home = Environment.resolveJdkHome(app, 21)
            if (home == null) ToolchainStatus(component, ToolchainState.MISSING, detail = "JDK 21 not found; current JDT LS releases require Java 21+")
            else probeFile(component, File(home, "bin/java"), listOf("-version"), home)
        }
        ToolchainComponent.GRADLE -> {
            val installed = Environment.resolveGradle(app)
            if (installed != null) probeFile(component, installed, listOf("--version"), installed.parentFile?.parentFile?.absolutePath)
            else ToolchainStatus(component, ToolchainState.MISSING, detail = "No installed Gradle binary; project Gradle Wrapper remains preferred")
        }
        ToolchainComponent.ANDROID_CLI -> probeSdkCommand(component, "sdkmanager", "--version")
        // adb：优先内置用户态里的 aarch64 原生 adb（findExecutable 的 PATH 首位就是它）。
        // SDK platform-tools 里的官方 adb 是 x86_64，在 arm64 真机上永远执行不了（exit=127）。
        ToolchainComponent.PLATFORM_TOOLS -> probeAny(component, listOf("adb"), listOf("version"))
        ToolchainComponent.BUILD_TOOLS -> {
            // 先看内置用户态里的 aarch64 原生 aapt2（真机唯一能执行的那个），再看 SDK build-tools
            // （官方 build-tools 的 aapt2 是 x86_64，arm64 上执行必失败）。
            val native = Environment.guestTool(app, "aapt2")
            if (native != null) probeFile(component, native, listOf("version"), native.absolutePath)
            else {
                val aapt2 = Environment.latestBuildToolsExecutable(app, "aapt2")
                if (aapt2 != null) probeFile(component, aapt2, listOf("version"), aapt2.parentFile?.absolutePath)
                else ToolchainStatus(component, ToolchainState.MISSING,
                    detail = "没有可执行的 aapt2：点「安装/修复」会装内置用户态里的 aarch64 原生 aapt2（APK 资源打包工具）")
            }
        }
        ToolchainComponent.DART -> probeDart(component)
        // Flutter 首次运行 `--version` 会解压内置 Dart SDK（并可能下载 artifacts），
        // 40s 超时会把「正在初始化」误判成安装失败，所以这里放宽超时并做专门诊断。
        ToolchainComponent.FLUTTER -> probeFlutter(component)
        ToolchainComponent.GRADLE_TOOLING_BRIDGE -> probeGradleToolingBridge()
        ToolchainComponent.GO -> probeCommand(component, "go", listOf("version"))
        ToolchainComponent.RUST -> probeCommand(component, "rustc", listOf("--version"))
        ToolchainComponent.PHP -> probeCommand(component, "php", listOf("-v"))
        ToolchainComponent.COMPOSER -> probeCommand(component, "composer", listOf("--version"))
        ToolchainComponent.MAVEN -> probeCommand(component, "mvn", listOf("-v"))
        ToolchainComponent.CMAKE -> probeCommand(component, "cmake", listOf("--version"))
        ToolchainComponent.C_COMPILER -> probeCommand(component, "clang", listOf("--version"))
        ToolchainComponent.NINJA -> probeCommand(component, "ninja", listOf("--version"))
        // Lua 解释器可执行名随发行版而变（lua5.4 / lua / luajit），逐个候选探测，避免"装了却报 MISSING"
        ToolchainComponent.LUA -> probeAny(component, listOf("lua5.4", "lua", "luajit"), listOf("-v"))
        // 逆向工具：apt 装出的 apktool / jadx 是 $PREFIX/bin 下的包装脚本，真实执行 --version
        // （需要 JDK）才算 READY，避免「文件在但跑不起来」被误报为已安装。
        ToolchainComponent.APKTOOL -> probeAny(component, listOf("apktool"), listOf("--version"))
        ToolchainComponent.JADX -> probeAny(component, listOf("jadx"), listOf("--version"))
        ToolchainComponent.LSP_GOPLS -> probeCommand(component, "gopls", listOf("version"))
        ToolchainComponent.LSP_RUST_ANALYZER -> probeCommand(component, "rust-analyzer", listOf("--version"))
        ToolchainComponent.LSP_LUA -> probeCommand(component, "lua-language-server", listOf("--version"))
        // tsc 是 npm 全局装的 shim（$PREFIX/bin/tsc → lib/node_modules/typescript/bin/tsc），
        // 真实执行 `--version` 才能确认「shim 在、node 也能跑它」，不靠文件存在性报 READY。
        ToolchainComponent.TYPESCRIPT -> probeCommand(component, "tsc", listOf("--version"))
        ToolchainComponent.LSP_TYPESCRIPT -> probeCommand(component, "typescript-language-server", listOf("--version"))
    }

    private fun probeGradleToolingBridge(): ToolchainStatus {
        val jar = GradleBridgeRuntimeManager(app).bridgeJar
        if (!jar.isFile) return ToolchainStatus(ToolchainComponent.GRADLE_TOOLING_BRIDGE, ToolchainState.MISSING, jar.absolutePath, detail = "bridge JAR 不存在")
        return GradleBridgeRuntimeManager(app).probe()
    }

    private fun probeCommand(
        component: ToolchainComponent,
        command: String,
        args: List<String>,
        timeoutSeconds: Long = 40,
        killAfterSeconds: Long = 8
    ): ToolchainStatus {
        val file = Environment.findExecutable(app, command)
            ?: return ToolchainStatus(component, ToolchainState.MISSING,
                detail = "未在 IDE PATH 找到 $command；可在「安装/修复」里安装 ${component.title}")
        return probeFile(component, file, args, file.absolutePath, timeoutSeconds, killAfterSeconds)
    }

    /**
     * Flutter 探测：真机上 `flutter` 只见「一句天书」就 FAILED，需要翻译成能照做的提示。
     *
     * 背景（真机取证）：`bin/flutter` 只是 shell 脚本，真正跑的是它自己解压出来的
     * `bin/cache/dart-sdk/bin/dart`（实测为 aarch64 ELF，构型没问题），但这是 **glibc** 版
     * Linux 二进制，而 Android/Termux 用户态只有 bionic 装载器（/system/bin/linker64），
     * 于是报错：
     *   .../bin/cache/dart-sdk/bin/dart: cannot execute: required file not found
     * 这不是「没装好」，也不是架构错，而是宿主 ABI 不匹配：本机没有 /lib64/ld-linux-aarch64.so.1。
     */
    private fun probeFlutter(component: ToolchainComponent): ToolchainStatus {
        // 结论先行（真机实测）：Flutter 的 dart 是 glibc 版 Linux aarch64 二进制，guest 根下没有
        // glibc 装载器/核心库时一律「无法执行」。这里先自愈安装**内置**的 glibc 运行时（纯本地），
        // 补上之后 `flutter --version` 可直接跑通（Flutter 3.47.2 / Dart 3.13.2）。
        GlibcRuntime.ensure(app)
        val status = probeCommand(component, "flutter", listOf("--version"), timeoutSeconds = 300, killAfterSeconds = 15)
        if (status.state != ToolchainState.FAILED) return status
        val text = status.detail
        val loaderIssue = listOf("cannot execute", "required file not found", "error while loading shared libraries")
            .any { text.contains(it, ignoreCase = true) }
        if (!loaderIssue) return status
        return ToolchainStatus(
            component,
            ToolchainState.FAILED,
            status.path,
            status.version,
            "Flutter SDK 本体已就位，但它自带的 Dart 是 glibc 版 Linux 二进制，需要 glibc 运行时。" +
                "App 已内置该运行时并在此刻尝试安装：" + GlibcRuntime.describe(app) +
                "。若仍失败，通常是 SDK 目录损坏或 glibc 资产缺失，可在「安装/修复」里重装 Flutter。" +
                "原始报错：" + text.take(200)
        )
    }

    /**
     * Dart 探测。与 [probeFlutter] 同理：SDK 自带的 dart 是 glibc 版二进制，先补内置运行时再探测。
     */
    private fun probeDart(component: ToolchainComponent): ToolchainStatus {
        GlibcRuntime.ensure(app)
        return probeCommand(component, "dart", listOf("--version"))
    }

    /** 依次尝试多个候选命令名（同名工具在不同发行版下命名不同），命中即做真实执行探测。 */
    private fun probeAny(component: ToolchainComponent, commands: List<String>, args: List<String>): ToolchainStatus {
        val tried = mutableListOf<String>()
        for (name in commands) {
            val file = Environment.findExecutable(app, name)
            if (file != null) return probeFile(component, file, args, file.absolutePath)
            tried += name
        }
        return ToolchainStatus(component, ToolchainState.MISSING,
            detail = "未在 IDE PATH 找到可执行文件：${tried.joinToString(" / ")}")
    }

    private fun probeSdkCommand(component: ToolchainComponent, command: String, arg: String): ToolchainStatus {
        val file = Environment.findSdkExecutable(app, command)
            ?: return ToolchainStatus(
                component,
                if (command == "sdkmanager") ToolchainState.BLOCKED else ToolchainState.MISSING,
                detail = when (command) {
                    "sdkmanager" -> "sdkmanager 不可执行：请先安装 Android Command-line Tools（「Android SDK CLI」一项）"
                    // adb 属于 platform-tools，不在 build-tools 里；只抛一句英文（"adb is not executable"）
                    // 用户无法知道该装哪个组件。这里直接给出依赖来源。
                    "adb" -> "adb 不可执行：缺少 Android Platform Tools。请先装「Android SDK CLI」(cmdline-tools)，再装 platform-tools（提供 adb）"
                    else -> "$command 不可执行：Android SDK 中缺少对应组件"
                }
            )
        return probeFile(component, file, listOf(arg), file.absolutePath)
    }

    private fun probeFile(
        component: ToolchainComponent,
        file: File,
        args: List<String>,
        path: String?,
        timeoutSeconds: Long = 40,
        killAfterSeconds: Long = 8
    ): ToolchainStatus {
        if (!file.isFile || !file.canExecute()) return ToolchainStatus(component, ToolchainState.MISSING, path,
            detail = "不可执行：${file.absolutePath}（缺少可执行位或文件损坏）")
        val env = Environment.buildTerminalEnv(app)
        val inner = (listOf(file.absolutePath) + args).joinToString(" ") { shellQuote(it) }
        // 探测必须走 guest（proot 前缀对齐）：gradle/sdkmanager/dart/flutter/composer 等都是
        // 带 Termux shebang 的脚本，在宿主机直接执行会因 bash.bashrc/shebang 不可访问而失败。
        val r = runGuestAware(inner, env, timeoutSeconds = timeoutSeconds, killAfterSeconds = killAfterSeconds)
        val version = firstMeaningfulLine(r.output)
        // detail 同样过滤掉启动噪声：卡片在 version 为空时会回退显示 detail，
        // 若不过滤就会把 linker 警告原样展示给用户。
        val detail = meaningfulOutput(r.output).ifBlank { "exit=${r.code}" }
        return ToolchainStatus(component, if (r.ok) ToolchainState.READY else ToolchainState.FAILED,
            path ?: file.absolutePath, version, detail)
    }

    /** 去掉启动噪声后的完整输出（保留真实报错行，便于排查失败原因）。 */
    private fun meaningfulOutput(output: String): String =
        output.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() && !isProbeNoise(it.trim()) }
            .joinToString("\n")

    /**
     * 从探测输出里取出「第一行有意义的内容」当作版本号。
     *
     * 不能直接 `lineSequence().firstOrNull()`：PTY 外层 shell / proot / Android linker 会在真实
     * 输出之前先打一堆**启动噪声**，例如
     *   `WARNING: linker: failed to find generated linker configuration from "/linkerconfig/ld.config.txt"`
     *   `bash: /data/data/com.termux/files/usr/etc/bash.bashrc: Permission denied`
     *   `proot warning: can't sanitize binding ...`
     * 结果设置页把这条警告当成「Python 的版本」显示出来，看起来就像工具链坏了。
     *
     * 这里只做**展示层**过滤（状态判定仍用 exit code，未改动探测语义）。
     * 顺带修掉 proot 自身的 linker 配置后（见 TermuxGuest.ensureLinkerConfig）这类噪声本就不该再出现，
     * 但旧安装/异常环境仍可能残留，故保留兜底。
     */
    private fun firstMeaningfulLine(output: String): String =
        output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !isProbeNoise(it) }
            .orEmpty()

    /**
     * 判定一行是否属于启动噪声（linker/proot/shebang/bashrc 等），不属于工具的真实输出。
     *
     * 注意判据要**精确**：`Permission denied` 在真实失败里也会出现（例如可执行位丢失），
     * 所以只有落在 bootstrap 已知噪声形态上时才算噪声，避免把真正的错误原因滤掉。
     */
    private fun isProbeNoise(line: String): Boolean =
        line.contains("WARNING: linker") ||
            line.contains("failed to find generated linker configuration") ||
            line.contains("can't sanitize binding") ||
            line.contains("can't canonicalize") ||
            line.contains("proot warning") ||
            line.contains("proot error") ||
            line.contains("Unable to create temp directory") ||
            line.contains("CANNOT LINK EXECUTABLE") ||
            // bash 读不到 Termux 前缀下的启动脚本（宿主机直连时的典型噪声）
            line.contains("bash.bashrc") ||
            line.contains("bad interpreter")

    /**
     * 安装前检查可用空间。
     *
     * cmdline-tools 解压后约 300MB，build-tools + platform + platform-tools 再叠加数百 MB；
     * 空间不足时 sdkmanager/apt 只会给一句笼统错误，用户根本不知道是磁盘满了。
     */
    private fun requireFreeSpace(minMb: Long, label: String) {
        val anchor = File(Environment.homeRoot(app))
        runCatching { anchor.mkdirs() }
        val freeMb = anchor.usableSpace / (1024L * 1024L)
        check(freeMb >= minMb) {
            "$label 至少需要 ${minMb}MB 可用空间，当前只剩 ${freeMb}MB。请先清理存储（无用项目/构建缓存）后重试"
        }
    }

    /**
     * 探测设备上是否还有**别的** apt/dpkg 进程（跨进程，本进程 Mutex 管不到）。
     *
     * 注意正则写成 `ap[t]-get` / `dp[k]g`：否则这条探测命令自身的命令行会匹配到自己。
     */
    private fun foreignInstallProcess(): String? = runCatching {
        val inner = "ps -A -o args 2>/dev/null | grep -E 'ap[t]-get|dp[k]g' | grep -v grep | head -2"
        val r = runGuestAware(inner, Environment.buildTerminalEnv(app), timeoutSeconds = 15, killAfterSeconds = 5)
        r.output.lines().map { it.trim() }
            .firstOrNull { it.isNotBlank() && it.length < 200 && looksLikeInstallProcess(it) }
    }.getOrNull()

    /**
     * 判定一行输出**是否真的是**「占用 dpkg 锁的安装进程」。
     *
     * 必须严格：PTY 外层 shell 的启动噪声（bash 读不到前缀 bash.bashrc、linker 警告）曾经排在
     * 真实 `ps | grep` 结果之前，被 `firstOrNull { non-blank }` 误判成外部安装进程，于是白等 180 秒，
     * 日志里表现为：
     *   `检测到已有安装在运行：bash: /data/data/com.termux/files/usr/etc/bash.bashrc: Permission denied`
     *   `仍在等待该安装结束：bash: ...（已等待 5s）`
     * 而设备上根本没有其它 apt/dpkg 在跑 —— 安装被人为拖慢甚至误报失败。
     */
    private fun looksLikeInstallProcess(line: String): Boolean {
        if (line.contains("Permission denied") || line.contains("WARNING: linker") ||
            line.contains("bash.bashrc") || line.startsWith("bash:") || line.startsWith("sh:")
        ) return false
        // 真实结果形如 `/data/data/com.termux/files/usr/bin/apt-get install -y git` 或 `dpkg --configure -a`。
        return Regex("(^|\\s|/)(apt-get|apt|dpkg|dpkg-deb)(\\s|$)").containsMatchIn(line)
    }

    /** 轮询等待外部 apt/dpkg 进程结束（dpkg 锁是全局的，只能等）。 */
    private suspend fun awaitForeignInstall(maxSeconds: Int) {
        var waited = 0
        while (waited < maxSeconds) {
            delay(5_000)
            waited += 5
            val still = foreignInstallProcess() ?: return
            appendLiveLog("仍在等待该安装结束：$still（已等待 ${waited}s）")
        }
        appendLiveLog("等待超时（${maxSeconds}s）：继续尝试本次安装，如仍失败请重启应用后再试")
    }

    /**
     * 用 npm 全局安装语言工具（TypeScript 语言栈）。
     *
     * 为什么不能走 apt：Termux main 仓库里没有 `typescript` / `ts-node` / `tsx` /
     * `typescript-language-server` 这些包，这些工具只以 npm 包形式发布，
     * `apt-get install typescript` 只会得到 `Unable to locate package`。
     * 正确做法是在 guest 前缀里调用 npm 自身的全局安装：
     *   npm install -g --no-fund --no-audit <packages>
     * 安装产物落在 `$PREFIX/lib/node_modules`，可执行 shim 落在 `$PREFIX/bin`
     * （已在 [TermuxGuest.guestEnv] 的 PATH 首位），因此：
     *  - 构建任务（`tsc -p .`）与运行任务（`tsx src/index.ts`）能直接调用；
     *  - 编辑器语言服务（typescript-language-server）也能直接拉起。
     *
     * @param packages 要安装的 npm 包名（顺序有意义：依赖包先写）。
     * @param minFreeMb 全局安装前的空间预检门槛。
     */
    private suspend fun runNpmGlobalInstall(
        packages: List<String>,
        minFreeMb: Long = 400
    ) {
        require(Environment.isBootstrapInstalled(app)) { "内置运行时未就绪" }
        TermuxGuest.ensureSetup(app)
        check(TermuxGuest.isReady(app)) { "内置运行时缺少 proot 组件，无法执行 npm 安装" }
        // npm/node 由 Node.js 组件提供。没装 Node 就点 TypeScript 安装，原生失败信息只有
        // 一句 "npm: not found"，用户无从判断该先装什么，这里直接给出可照做的依赖提示。
        val node = Environment.findExecutable(app, "node")
            ?: error("找不到 node：请先在「工具链」里安装 Node.js，再安装 ${packages.joinToString(" ")}")
        val binDir = node.parentFile?.absolutePath.orEmpty()
        requireFreeSpace(minFreeMb, "npm 全局安装 ${packages.joinToString(" ")}")
        // 关键：PATH 首位必须是 node/java 等 shim 所在目录，否则 npm 会拉起自己的私有 node
        // 或找不到 node。npm 的全局前缀由 guest 环境决定（$PREFIX），不能再用 --prefix 覆盖，
        // 否则 shim 会落到 $PREFIX 之外、而 PATH 里没有那个目录 → 「装完了却探测不到」。
        val script = listOf(
            "export PATH=" + shellQuote(binDir) + ":\$PATH",
            "npm install -g --no-fund --no-audit --loglevel=error " + packages.joinToString(" ")
        ).joinToString("\n")
        appendLiveLog("npm 全局安装：${packages.joinToString(" ")}")
        val result = runGuestAware(script, TermuxGuest.guestEnv(app), timeoutSeconds = 1800, killAfterSeconds = 20)
        if (result.ok) return
        val output = result.output
        val hint = when {
            output.contains("No space left") -> "存储空间不足：请清理存储后重试"
            output.contains("ETIMEDOUT") || output.contains("ENOTFOUND") ||
                output.contains("request to") && output.contains("failed") ->
                "npm 源不可达：请检查网络；也可把 registry 指向国内镜像后重试" +
                    "（终端里执行 npm config set registry https://registry.npmmirror.com）"
            output.contains("EACCES") || output.contains("permission denied", ignoreCase = true) ->
                "npm 前缀目录不可写：请先修复内置运行时（Embedded Termux runtime）后重试"
            output.contains("not found") && output.contains("npm") ->
                "npm 不可用：请先安装 Node.js 组件"
            else -> "可点上方「复制」把完整日志贴出来排查"
        }
        error("npm install -g ${packages.joinToString(" ")} 失败（exit=${result.code}）：$hint\n${output.takeLast(3000)}")
    }

    private suspend fun runPackageInstall(packageName: String, reinstall: Boolean = false) {
        require(Environment.isBootstrapInstalled(app)) { "Embedded runtime is not ready" }
        val packages = packageName.split(' ').filter { it.isNotBlank() }
        check(packages.isNotEmpty()) { "未指定要安装的包" }
        // 1) guest 运行时（proot + 前缀对齐）必须就绪，否则 Termux 的 apt/dpkg 根本无法工作。
        TermuxGuest.ensureSetup(app)
        check(TermuxGuest.isReady(app)) { "内置运行时缺少 proot 组件，无法执行软件包安装" }
        // 2) 选一个真实可达的源（源本身一直可达，历史上失败原因是本地验签 + 前缀错位）。
        val mirror = TermuxGuest.ensureMirror(app)
        // 3) 在 proot 对齐的前缀里安装：apt-get 认为自己在标准 Termux 中运行。
        //    注意不能用 `proot -0`：Termux 的 apt/dpkg 打了「拒绝 root」补丁。
        //    进程内串行：多个安装任务（含重复点击）排队执行，否则两个 apt 会同时争 dpkg 锁。
        // 空间预检：真机上「装不上」的第一大原因是空间不足（apt 依赖树 + SDK 组件动辄数百 MB），
        // 而 apt/sdkmanager 在空间不足时只会抛一句笼统错误，用户无从下手。
        requireFreeSpace(600, "安装 ${packages.joinToString(" ")}")
        if (aptGate.isLocked) {
            appendLiveLog("等待其它软件包安装结束（apt/dpkg 同一时刻只能有一个）：${packages.joinToString(" ")}")
        }
        aptGate.withLock {
            var attempt = 0
            while (true) {
                attempt++
                // 本进程的 Mutex 只挡得住「本应用」的并发。设备上可能还有别的 apt（例如上次未退出的
                // proot 进程）持有 dpkg 锁：此时后启动的 apt 会**立刻** EXIT=100 并打印
                // "It is held by process NNNNN (apt-get)"，用户看到的就是「安装失败」。
                // 因此先探测外部进程并等待，再在失败且确为锁冲突时重试，而不是直接报错。
                foreignInstallProcess()?.let { holder ->
                    appendLiveLog("检测到已有安装在运行：$holder")
                    awaitForeignInstall(180)
                }
                val args = TermuxGuest.prootArgs(app, TermuxGuest.installScript(packages, reinstall))
                //    总超时给足：python/node/rust 等依赖树较大，几十秒到数分钟属正常。
                val result = exec(TermuxGuest.prootBinary(app), args, TermuxGuest.guestEnv(app), 1800, 30)
                if (result.ok) return@withLock
                val output = result.output
                val lockConflict = output.contains("Could not get lock") || output.contains("held by process") ||
                    output.contains("dpkg frontend") || output.contains("dpkg status database") ||
                    output.contains("another process")
                if (lockConflict && attempt < 3) {
                    appendLiveLog("dpkg 锁被占用（第 $attempt 次尝试），等待 20 秒后重试…")
                    delay(20_000)
                    continue
                }
                val hint = when {
                    lockConflict -> "另一个 apt/dpkg 进程仍持有锁；请等它结束，或重启应用后再试"
                    output.contains("No space left") -> "存储空间不足：请清理存储后重试"
                    output.contains("Unable to locate package") -> "源（$mirror）中没有这个包；可在「工具链源」里切换镜像"
                    output.contains("Temporary failure resolving") || output.contains("Could not resolve host") ->
                        "域名解析失败：请检查网络连通性（源：$mirror）"
                    else -> "可点上方「复制」把完整日志贴出来排查（源：$mirror）"
                }
                error("apt-get install ${packages.joinToString(" ")} 失败（exit=${result.code}）：$hint\n${output.takeLast(3000)}")
            }
        }
    }

    private suspend fun runSdkInstall(packageName: String) {
        val sdkmanager = Environment.findSdkExecutable(app, "sdkmanager")
            ?: error("sdkmanager 不可执行；请先在「Android SDK CLI」一项安装 Android Command-line Tools")
        val env = Environment.buildSdkEnv(app)
        // SDK 组件很大（build-tools+platform+platform-tools 常见 1GB 以上），先确认磁盘能装下。
        requireFreeSpace(1500, "安装 SDK 组件 $packageName")
        // sdkmanager 的包安装语法是直接传递 package id；不存在 `--install` 参数。
        // 许可证接受必须由用户在明确的安装操作中确认，不能静默吞掉许可失败。
        val command = "yes | " + shellQuote(sdkmanager.absolutePath) +
            " --sdk_root=" + shellQuote(Environment.androidSdkRoot(app)) + " --licenses >/dev/null 2>&1; " +
            shellQuote(sdkmanager.absolutePath) + " --sdk_root=" + shellQuote(Environment.androidSdkRoot(app)) +
            " " + shellQuote(packageName)
        // SDK 组件动辄上百 MB，超时给足（此前 20s 必然超时 → 一律失败）。
        val result = runGuestAware(command, env, timeoutSeconds = 1800, killAfterSeconds = 20)
        check(result.ok) { "sdkmanager install $packageName failed: ${result.output.takeLast(3000)}" }
    }

    /** 安装 Android Command-line Tools（Google 官方包，纯 Java，可用内置 JDK17 运行）。 */
    /**
     * Flutter SDK 安装。
     *
     * Termux 仓库里**没有** `flutter` 包（`dart` 包存在，但 Flutter SDK 不在其中），
     * 因此原先的 `runPackageInstall("flutter")` 必然报 `Unable to locate package flutter`。
     * 官方推荐做法是把 SDK 克隆到 HOME：`git clone -b stable`。
     * SDK 自带 dart，环境侧由 [Environment.languageToolDirs]（`$HOME/flutter/bin` 进 PATH）
     * 与 [Environment.buildFlutterEnv]（FLUTTER_ROOT / PUB_CACHE）提供命令与缓存位置。
     *
     * 体积提示：SDK 本体约 600 MB，首次 `flutter build` 还会再下载 Dart SDK / Android artifacts，
     * 因此这里预留 2 GB 空间并在失败时给出可操作的提示。
     */
    private suspend fun runFlutterSdkInstall() {
        require(Environment.isBootstrapInstalled(app)) { "内置运行时未就绪" }
        TermuxGuest.ensureSetup(app)
        check(TermuxGuest.isReady(app)) { "内置运行时缺少 proot 组件，无法安装 Flutter SDK" }
        val sdk = File(Environment.homeRoot(app), "flutter")
        // Flutter 能跑起来的前提是 glibc 运行时（内置 assets，纯本地安装）。
        // 放在这里而不是只在探测里做，是为了让「安装/修复」按钮按下后一套流程自洽：
        // 装完 SDK → 补 glibc → 探测即 READY。
        GlibcRuntime.ensure(app)
        appendLiveLog(GlibcRuntime.describe(app))
        if (File(sdk, "bin/flutter").isFile) {
            appendLiveLog("Flutter SDK 已存在：${sdk.absolutePath}（跳过克隆）")
            return
        }
        // 上一次中断的克隆会留下「非空但不是 SDK」的目录（典型只剩 `.git/`），此后每次重试
        // 都会以 exit=128 失败：
        //   fatal: destination path '.../flutter' already exists and is not an empty directory
        // 真机取证：files/home/flutter 里只有 .git（108K），于是「Flutter SDK 安装」永远失败，
        // 报错还完全指不到残留目录。所以克隆前先清理不完整安装。
        if (sdk.exists()) {
            appendLiveLog("检测到不完整的 Flutter SDK 残留（缺少 bin/flutter）：${sdk.absolutePath}，先清理再克隆")
            if (!sdk.deleteRecursively()) error("无法清理残留目录 ${sdk.absolutePath}（可能被其它进程占用），请手动删除后重试")
        }
        sdk.parentFile?.mkdirs()
        requireFreeSpace(2000, "安装 Flutter SDK")
        // 克隆本身需要 git；bootstrap 通常已带，缺了就补装。
        if (Environment.findExecutable(app, "git") == null) runPackageInstall("git")
        // 先克隆到 staging 目录，成功后再改名到最终位置：中途失败（断网/超时/被系统杀）就不会
        // 再留下半个 SDK —— 上面那段「清理残留」只是兜底，这才是根治。
        val staging = File(sdk.parentFile, "flutter.part")
        if (staging.exists()) staging.deleteRecursively()
        val guestTarget = ProotPathMapper.toGuest(app, staging.absolutePath)
        // 候选克隆源：真机 guest 内 `git ls-remote --heads <url> stable` 实测结果 ——
        //   github.com/...              超时不可达（国内网络）
        //   gitee.com/mirrors/flutter   ✅ 返回 refs/heads/stable
        //   ghproxy.net/https://github… ✅ 返回 refs/heads/stable
        // 官方源放最后：海外用户直连最快，国内用户自动回落到上面两个。
        val sources = listOf(
            "https://gitee.com/mirrors/flutter.git",
            "https://ghproxy.net/https://github.com/flutter/flutter.git",
            "https://github.com/flutter/flutter.git"
        )
        appendLiveLog("Flutter SDK 将克隆到 ${sdk.absolutePath}（约 600 MB，视网络需要数分钟；先落到 flutter.part 再改名）")
        var lastCode = -1
        var lastOutput = ""
        for (url in sources) {
            if (File(staging, "bin/flutter").isFile) break
            if (staging.exists()) staging.deleteRecursively()
            appendLiveLog("尝试克隆源：$url")
            val r = runGuestAware(
                "git clone --depth 1 -b stable " + url + " '" + guestTarget + "'",
                Environment.buildFlutterEnv(app), timeoutSeconds = 1800, killAfterSeconds = 30
            )
            lastCode = r.code
            lastOutput = r.output
            if (File(staging, "bin/flutter").isFile) {
                appendLiveLog("克隆成功（$url）")
                break
            }
            appendLiveLog("该源不可用（exit=${r.code}），换下一个…")
        }
        if (!File(staging, "bin/flutter").isFile) {
            staging.deleteRecursively()
            error("Flutter SDK 克隆失败（最后一个源 exit=$lastCode）：" + lastOutput.takeLast(400) +
                "；可改用「Dart」组件（仅 Dart 语法/分析）或手动把 Flutter SDK 放到 ${sdk.absolutePath}（需含 bin/flutter）")
        }
        if (!staging.renameTo(sdk)) {
            sdk.deleteRecursively()
            check(staging.renameTo(sdk)) { "Flutter SDK 已克隆到 ${staging.absolutePath}，但无法移动到 ${sdk.absolutePath}，请检查可用空间后重试" }
        }
        appendLiveLog("Flutter SDK 安装完成：${sdk.absolutePath}")
    }

    /**
     * 安装 aarch64 原生 platform-tools（adb / fastboot），并把它们暴露到 SDK 的固定路径。
     *
     * ## 真机取证（HBN-AL80 / arm64）
     * 1) SDK 里的官方 adb 是 **x86_64** ELF：
     *      `home/android-sdk/platform-tools/adb: cannot execute: required file not found`（exit=127）
     * 2) 内置用户态的 adb（Termux android-tools，aarch64）启动即报：
     *      `CANNOT LINK EXECUTABLE "adb": cannot locate symbol "_ZNSt6__ndk113__hash_memoryEPKvm"`
     *    根因是 bootstrap 自带的 libc++_shared.so（1,374,336 字节）没有该符号，仓库最新
     *    libc++ 里的同名 .so（1,423,696 字节）有 —— 而 apt 认为 libc++ "已安装"不会升级，
     *    所以这里必须 `--reinstall libc++`。
     *
     * 结论：arm64 设备上 adb 只能走内置用户态的原生包，顺序 = 先修 libc++，再装 android-tools。
     */
    private suspend fun installNativePlatformTools() {
        runPackageInstall("libc++", reinstall = true)
        runPackageInstall("android-tools")
        exposeNativeTool("platform-tools", "adb")
        exposeNativeTool("platform-tools", "fastboot")
    }

    /**
     * 安装 aarch64 原生 build-tools（aapt2），并暴露到 SDK `build-tools/35.0.0/`。
     *
     * 官方 build-tools 里的 aapt2 是 x86_64 ELF（真机 `aapt2: cannot execute`，exit=127）；
     * Termux 的 `aapt2`（android-build-tools 16.0.0.4，aarch64 原生）才是本机能真正执行的。
     */
    private suspend fun installNativeBuildTools() {
        runPackageInstall("aapt2")
        val native = Environment.guestTool(app, "aapt2")
            ?: error("已安装 aapt2，但内置用户态里找不到可执行的 aapt2（安装可能未完成或存储空间不足），请重试")
        val dir = File(Environment.androidSdkRoot(app), "build-tools/35.0.0").apply { mkdirs() }
        linkNativeInto(dir, native, "aapt2")
    }

    /**
     * 把内置用户态（guest）里的 aarch64 原生工具暴露到 Android SDK 目录。
     *
     * 为什么要暴露：Gradle/AGP 与 Flutter 都按**固定路径**找工具
     * （`platform-tools/adb`、`build-tools/<ver>/aapt2`），不会去 guest 的 `$PREFIX/bin` 里翻。
     * 这些位置若留着 Google 的 x86_64 二进制，工具链探测与真实构建会一直失败。
     */
    private fun exposeNativeTool(sdkSubDir: String, tool: String) {
        val native = Environment.guestTool(app, tool) ?: return
        val dir = File(Environment.androidSdkRoot(app), sdkSubDir).apply { mkdirs() }
        linkNativeInto(dir, native, tool)
    }

    /**
     * 把 `native` 链接到 `dir/tool`（软链优先，失败退回拷贝）。
     *
     * 冲突处理：目标位置若是**普通文件**（x86_64 官方二进制），改名成 `<tool>.x86_64.disabled`
     * 而不是删除 —— 保留证据、可回退，同时让 `findExecutable`/AGP 不再命中那个跑不起来的文件。
     */
    private fun linkNativeInto(dir: File, native: File, tool: String) {
        val target = File(dir, tool)
        if (target.exists() && !isSymlink(target)) {
            val parked = File(dir, "$tool.x86_64.disabled")
            val moved = parked.exists() || target.renameTo(parked)
            if (!moved) target.delete()
            appendLiveLog("发现同名 x86_64 官方二进制，已改名保留：${parked.name}")
        }
        if (target.exists() || isSymlink(target)) target.delete()
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(target.toPath(), native.toPath())
        }.isSuccess
        if (!linked) runCatching {
            native.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
        }
        appendLiveLog("已暴露原生 $tool：${target.absolutePath} → ${native.absolutePath}")
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    private suspend fun runCmdlineToolsInstall() {
        check(Environment.isBootstrapInstalled(app)) { "内置运行时未就绪" }
        // cmdline-tools 由 Java 运行，必须先把 JDK 装好，否则 sdkmanager 起不来。
        if (Environment.resolveJdkHome(app, 17) == null) runPackageInstall("openjdk-17")
        CmdlineToolsInstaller(app).install { percent, message -> /* 由任务中心回调接管进度 */ 
            android.util.Log.i("ToolchainManager", "[cmdline-tools] $percent% $message")
        }
    }

    private fun setInstalling(component: ToolchainComponent) = update(ToolchainStatus(component, ToolchainState.INSTALLING, detail = "installing…"))

    private fun update(status: ToolchainStatus) {
        _statuses.value = _statuses.value.filterNot { it.component == status.component } + status
        persist(_statuses.value)
    }

    private fun persist(list: List<ToolchainStatus>) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val a = JSONArray()
            list.forEach { s ->
                a.put(JSONObject().apply {
                    put("component", s.component.name); put("state", s.state.name); put("path", s.path); put("version", s.version); put("detail", s.detail); put("lastChecked", s.lastChecked)
                })
            }
            stateFile.writeText(a.toString())
        }
    }

    private fun loadPersisted(): List<ToolchainStatus> = runCatching {
        if (!stateFile.exists()) return@runCatching emptyList()
        val a = JSONArray(stateFile.readText())
        buildList {
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val c = ToolchainComponent.valueOf(o.getString("component"))
                add(ToolchainStatus(c, ToolchainState.valueOf(o.optString("state", "MISSING")), o.optString("path").takeIf { it != "null" && it.isNotBlank() }, o.optString("version"), o.optString("detail"), o.optLong("lastChecked")))
            }
        }
    }.getOrDefault(emptyList())

    private data class ExecResult(val code: Int, val output: String) { val ok get() = code == 0 }

    /** guest（proot 前缀对齐）运行时是否可用；探测/安装执行前据此决定是否进入 guest。 */
    private fun guestReady(): Boolean = runCatching {
        TermuxGuest.ensureSetup(app)
        TermuxGuest.isReady(app)
    }.getOrDefault(false)

    /**
     * 执行一段命令，guest 就绪时**自动进入 proot 前缀对齐环境**。
     *
     * 这是「执行已安装工具」的唯一入口：绝不能把 guest 二进制/脚本直接交给宿主 shell。
     * 宿主机前缀是 App 私有目录，而 Termux 的脚本 shebang 与 bash.bashrc 写死
     * `/data/data/com.termux/files/usr/...`，直连必然 `Permission denied`。
     */
    private fun runGuestAware(
        inner: String,
        env: Map<String, String>,
        timeoutSeconds: Long = 12,
        killAfterSeconds: Long = 5
    ): ExecResult {
        if (guestReady()) {
            return runShellLine(TermuxGuest.guestCommandLine(app, inner, env), env, timeoutSeconds, killAfterSeconds)
        }
        return runShellLine("exec $inner", env, timeoutSeconds, killAfterSeconds)
    }

    /**
     * Way-B only: toolchain probes must execute through the same embedded PTY runtime as the
     * terminal/build/LSP processes. Android ProcessBuilder is deliberately not used for embedded
     * userland ELF binaries because Android SELinux/noexec can make a file-exists check misleading.
     */
    private fun exec(file: File, args: List<String>, env: Map<String, String>, timeoutSeconds: Long = 8, killAfterSeconds: Long = 5): ExecResult =
        runShellLine((listOf(file.absolutePath) + args).joinToString(" ") { shellQuote(it) }, env, timeoutSeconds, killAfterSeconds)

    /** 以宿主 shell 执行一行命令（调用方负责决定是否已包成 proot guest 命令行）。 */
    private fun runShellLine(command: String, env: Map<String, String>, timeoutSeconds: Long, killAfterSeconds: Long): ExecResult {
        // 命令本身也进实时日志：用户能看到「现在到底在跑什么」，而不是只有一个 spinner。
        appendLiveLog("$ " + command.take(500))
        return try {
            val executor = TermuxCommandExecutor(Environment.resolveShell(context))
            val output = StringBuilder()
            var code = -1
            runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(timeoutSeconds * 1000L) {
                    // cwd 必须是已存在的目录：home 缺失时脚本的 `cd` 会直接失败并返回 125，
                    // 结果所有组件（java/gradle/adb…）都会被误判为 MISSING，看起来像"工具链安装异常"。
                    executor.execute(command, Environment.ensureHome(context), env).collect { event ->
                        when (event) {
                            is TermuxCommandExecutor.Event.Line -> {
                                output.append(event.text).append('\n')
                                appendLiveLog(event.text)
                            }
                            is TermuxCommandExecutor.Event.Finished -> {
                                code = event.exitCode
                                appendLiveLog("[exit=${event.exitCode}]")
                            }
                        }
                    }
                }
            }
            if (code < 0) {
                appendLiveLog("[timeout ${timeoutSeconds}s]")
                ExecResult(-1, output.toString().trimEnd() + "\ntimeout")
            } else ExecResult(code, output.toString().trim())
        } catch (t: Throwable) {
            appendLiveLog("[error] " + (t.message ?: t.javaClass.simpleName))
            ExecResult(-1, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

data class EnvironmentSession(
    val terminal: Map<String, String>,
    val gradle: Map<String, String>,
    val sdk: Map<String, String>
)

private object BootstrapRuntimeVersion {
    /** 版本号取自实际安装的 bootstrap（落盘版本文件 → 内置 assets 版本 → 联网解析），不再硬编码。 */
    fun version(context: android.content.Context): String =
        com.nebulaforge.core.environment.BootstrapRuntime(context).releaseVersion()
}
