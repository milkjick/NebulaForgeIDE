package com.nebulaforge.core.environment

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 首次启动环境自检向导的检查逻辑（开发方案第 1.7 节）。
 *
 * 方案要求：每步一个可核对的检查项，**检查失败给出具体修复动作而非笼统报错**。
 * 因此每个结果都携带中文 [OnboardingCheckResult.guidance]（失败引导文案）
 * 与可选的自动修复动作：
 *  - [OnboardingCheckResult.autoFixIsBootstrapInstall] = true：修复动作是「安装内嵌运行时」，
 *    由 UI 调用 [BootstrapInstaller] 完成（不是一条 shell 命令，因此不能塞进 autoFixCommand）；
 *  - [OnboardingCheckResult.autoFixCommand] != null：在内嵌运行时里执行的一条 shell 命令。
 *
 * ## 架构订正（重要）
 * 本 IDE **不再依赖外部 Termux 应用**：命令执行、工具链、构建全部走 App 私有存储下的
 * 内嵌用户态（proot + Termux bootstrap，见 [BootstrapInstaller] / [BootstrapRuntime]）。
 * 早期版本的第 1 步会去 `PackageManager` 里找包名 `com.termux` 并引导用户去 F-Droid 安装，
 * 与内嵌运行时架构直接矛盾：真机上「内嵌运行时已装好、工具链可用」的用户，向导却报
 * 「未检测到 Termux」，而且**从未提供安装内嵌运行时的入口**。
 * 现在第 1 步直接检测内嵌运行时本体的「已安装 + 可真实执行」两件事。
 *
 * UI 层（app 模块的 OnboardingWizardScreen）只负责逐步呈现，不重复实现判定逻辑。
 */
enum class OnboardingStep(val order: Int, val titleZh: String) {
    /** 内嵌运行时（proot + Termux bootstrap）是否已安装且 sh 能真实执行。 */
    EMBEDDED_RUNTIME(1, "内嵌运行时"),

    /** JDK 17 是否已在内嵌用户态里就绪（Android/Gradle 构建的硬前置）。 */
    JDK17(2, "JDK 17"),

    /**
     * 构建工具链全量检测：列出宿主支持的全部 [com.nebulaforge.core.toolchain.ToolchainComponent]
     * 及其真实探测结果与完成度。
     *
     * 判定与清单渲染在 **app 模块**实现（core-environment 不能依赖 core-toolchain，否则成环）。
     */
    BUILD_TOOLCHAIN(3, "构建工具链"),

    /** Android SDK 基础组件（platform-tools / build-tools）。 */
    ANDROID_SDK(4, "Android SDK 基础组件"),

    /** 网络与镜像（官方源 / 阿里云镜像可达性，取更快者）。 */
    NETWORK_MIRROR(5, "网络与镜像")
}

data class OnboardingCheckResult(
    val step: OnboardingStep,
    val ok: Boolean,
    /** 通过/失败的具体事实（例如检测到的路径、耗时），用于让用户核对 */
    val detail: String,
    /** 失败时展示的中文引导文案 */
    val guidance: String,
    /** 一键修复按钮文案；为空表示该步骤没有自动修复动作 */
    val autoFixLabel: String? = null,
    /** 一键修复要执行的命令（在内嵌运行时中执行） */
    val autoFixCommand: String? = null,
    /**
     * 一键修复动作为「安装/重装内嵌运行时」。
     *
     * 这条路径不能表达成 shell 命令：它要做的是解压 bootstrap、补可执行位、重建符号链接
     * （见 [BootstrapInstaller]），必须在 UI 层直接驱动安装器。
     */
    val autoFixIsBootstrapInstall: Boolean = false
)

class OnboardingChecker(private val context: Context) {

    /**
     * 依次执行全部**由 core 实现**的检查步，每步完成即回调（供 UI 流式刷新）。
     *
     * [OnboardingStep.BUILD_TOOLCHAIN] 的判定在 app 层（依赖 core-toolchain），此处跳过，
     * 由向导 UI 单独驱动。
     */
    suspend fun checkAll(onResult: (OnboardingCheckResult) -> Unit = {}): List<OnboardingCheckResult> {
        val results = listOf(
            checkEmbeddedRuntime(),
            checkJdk17(),
            checkAndroidSdk(),
            checkNetworkAndMirror()
        )
        results.forEach(onResult)
        return results
    }

    /**
     * 步骤1：内嵌运行时是否已安装、且 `sh` 能**真实执行**一条命令。
     *
     * 只判断文件存在是不够的：真机上出现过「usr/bin/sh 是悬空符号链接」「解压后缺可执行位」
     * 「bootstrap 标记已写入但用户态不可用」等情况，一律必须用一次真实执行来确认。
     * 失败时用 [BootstrapRuntime.diagnoseRuntime] 给出可定位原因。
     */
    suspend fun checkEmbeddedRuntime(): OnboardingCheckResult {
        val prefix = Environment.usrRoot(context)
        val sh = File(Environment.binDir(context), "sh")
        val installed = runCatching { Environment.isBootstrapInstalled(context) }.getOrDefault(false)

        if (!installed || !sh.isFile) {
            val reason = when {
                !installed && !sh.isFile -> "尚未安装内嵌运行时（$prefix 为空）"
                !installed -> "内嵌运行时的安装标记缺失，但已存在 $sh（安装可能被中断）"
                else -> "安装标记存在，但找不到 $sh"
            }
            return OnboardingCheckResult(
                OnboardingStep.EMBEDDED_RUNTIME, false,
                reason,
                "本 IDE 自带内嵌用户态，**不需要**外部 Termux 应用。点下方按钮即可自动安装内嵌运行时，安装完成后终端与构建工具链立即可用。",
                autoFixLabel = "立即安装内嵌运行时",
                autoFixIsBootstrapInstall = true
            )
        }

        val (exit, output) = probeShellExecution(sh)
        val ok = exit == 0 && output.contains("ok")
        if (ok) {
            return OnboardingCheckResult(
                OnboardingStep.EMBEDDED_RUNTIME, true,
                "内嵌运行时已就绪：$prefix（测试命令 echo ok 执行成功，退出码 0）", ""
            )
        }
        val diagnosis = runCatching { BootstrapRuntime(context).diagnoseRuntime() }
            .getOrElse { "运行时自检失败（退出码 $exit）" }
        return OnboardingCheckResult(
            OnboardingStep.EMBEDDED_RUNTIME, false,
            "内嵌运行时不可执行（退出码 $exit${if (output.isNotBlank()) "，输出「$output」" else ""}）。$diagnosis",
            "内嵌用户态可能损坏（悬空符号链接 / 缺少可执行位 / 解压不完整）。点下方按钮重新安装内嵌运行时；已安装的命令与项目文件不受影响。",
            autoFixLabel = "重新安装内嵌运行时",
            autoFixIsBootstrapInstall = true
        )
    }

    /** 在内嵌运行时里真实执行一条无害命令，验证「能否真正跑起外部命令」。 */
    private suspend fun probeShellExecution(shell: File): Pair<Int, String> = withContext(Dispatchers.IO) {
        var output = ""
        var exit = -1
        try {
            val env = Environment.buildTerminalEnv(context).toMutableMap()
            env["PATH"] = "${Environment.binDir(context)}:${env["PATH"].orEmpty()}"
            val executor = com.nebulaforge.core.exec.TermuxCommandExecutor(shell.absolutePath)
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                executor.execute("echo ok", Environment.ensureHome(context), env).collect { event ->
                    when (event) {
                        is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Line -> output += event.text
                        is com.nebulaforge.core.exec.TermuxCommandExecutor.Event.Finished -> exit = event.exitCode
                    }
                }
            }
        } catch (_: Throwable) {
            // 探测失败按「不可执行」处理，不向上抛异常：向导必须能继续走完并给出修复入口
        }
        exit to output.trim()
    }

    /** 步骤2：JDK 17 是否已安装 */
    fun checkJdk17(): OnboardingCheckResult {
        val home = Environment.resolveJdkHome(context, 17)
        return if (home != null) {
            OnboardingCheckResult(OnboardingStep.JDK17, true, "已检测到 JDK 17：$home", "")
        } else {
            OnboardingCheckResult(
                OnboardingStep.JDK17, false,
                "未在内嵌运行时中找到 JDK 17（已安装版本：${Environment.listInstalledJdkVersions(context).joinToString("、") { "JDK ${it.majorVersion}" }.ifBlank { "无" }}）",
                "点击下方按钮自动安装 JDK 17（Android/Gradle 构建的硬前置，约 200MB，需要联网）",
                autoFixLabel = "自动安装 JDK 17",
                autoFixCommand = "pkg install -y openjdk-17"
            )
        }
    }

    /** 步骤4：Android SDK 基础组件是否存在 */
    fun checkAndroidSdk(): OnboardingCheckResult {
        val sdkRoot = File(Environment.androidSdkRoot(context))
        val platformTools = File(sdkRoot, "platform-tools")
        val buildTools = File(sdkRoot, "build-tools")
        val missing = buildList {
            if (!platformTools.isDirectory) add("platform-tools")
            if (!buildTools.isDirectory) add("build-tools")
        }
        return if (missing.isEmpty()) {
            OnboardingCheckResult(
                OnboardingStep.ANDROID_SDK, true,
                "SDK 基础组件就绪：$sdkRoot（build-tools：${Environment.latestBuildToolsDir(context)?.name ?: "未识别"}）", ""
            )
        } else {
            OnboardingCheckResult(
                OnboardingStep.ANDROID_SDK, false,
                "缺少组件：${missing.joinToString("、")}（SDK 根目录：$sdkRoot）",
                "点击下方按钮自动安装 SDK 基础组件（约 200MB，需要联网）；也可以在设置页按 API 版本单独安装。",
                autoFixLabel = "自动安装 SDK 基础组件",
                autoFixCommand = "sdkmanager --sdk_root=${sdkRoot.absolutePath} --install \"platform-tools\" \"build-tools;34.0.0\""
            )
        }
    }

    /** 步骤5：网络连通性（含镜像可用性探测，取更快者） */
    suspend fun checkNetworkAndMirror(): OnboardingCheckResult = withContext(Dispatchers.IO) {
        val official = probeLatency(OFFICIAL_MAVEN)
        val mirror = probeLatency(ALIYUN_MAVEN)
        when {
            official == null && mirror == null -> OnboardingCheckResult(
                OnboardingStep.NETWORK_MIRROR, false,
                "官方源与阿里云镜像均不可达",
                "请检查网络连接后重试；离线环境下可在设置中配置本地 Maven 仓库"
            )

            official != null && (mirror == null || official <= mirror) -> OnboardingCheckResult(
                OnboardingStep.NETWORK_MIRROR, true,
                "官方源可达（${official}ms），阿里云镜像 ${mirror?.let { "${it}ms" } ?: "不可达"}，使用官方源", ""
            )

            else -> OnboardingCheckResult(
                OnboardingStep.NETWORK_MIRROR, true,
                "已自动切换为阿里云镜像（镜像 ${mirror}ms 快于官方源 ${official ?: "不可达"}）",
                "检测到访问官方源较慢，已自动切换为阿里云镜像，可在设置中手动更改"
            )
        }
    }

    private fun probeLatency(url: String): Long? = try {
        val started = System.nanoTime()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NebulaForgeIDE")
        }
        val code = conn.responseCode
        conn.disconnect()
        if (code in 200..399) (System.nanoTime() - started) / 1_000_000 else null
    } catch (_: Throwable) {
        null
    }

    companion object {
        /**
         * 外部 Termux 包名。
         *
         * 仅作为**迁移提示**保留（老用户的项目/脚本可能写在 Termux 里），
         * 不再作为任何检查步骤的通过条件。
         */
        const val TERMUX_PACKAGE = "com.termux"
        const val OFFICIAL_MAVEN = "https://dl.google.com/dl/android/maven2/"
        const val ALIYUN_MAVEN = "https://maven.aliyun.com/repository/public/"
        private const val PROBE_TIMEOUT_MS = 10_000L
    }
}
