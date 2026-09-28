package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.projectmodel.ProjectDescriptor
import java.io.File

/**
 * 将项目声明的需求解析为一次性的、可验证的 ProjectEnvironment。
 * Build / Terminal / LSP / Run 都应消费这个对象，避免各自重新拼接 PATH。
 */
data class ProjectEnvironment(
    val project: ProjectDescriptor,
    val jdkHome: File,
    val sdkRoot: File,
    val buildTools: File?,
    val ndkRoot: File?,
    val gradleExecutable: File?,
    val variables: Map<String, String>,
    val issues: List<String>
) {
    val ready: Boolean get() = issues.isEmpty()
}

class ProjectToolchainResolver(private val context: Context) {
    private val app = context.applicationContext

    fun resolve(project: ProjectDescriptor, requestedJdkMajor: Int? = null): ProjectEnvironment {
        val stored = com.nebulaforge.core.projectmodel.ProjectToolchainConfig.read(project.root)
        val cfg = if (requestedJdkMajor != null && requestedJdkMajor != stored.jdkMajor) stored.copy(jdkMajor = requestedJdkMajor) else stored
        val issues = mutableListOf<String>()

        // ---- 先按项目类型划定「哪些工具链才与这个项目有关」 ----
        // 过去这里对**所有**项目类型都跑全套 Android/Gradle 检查，后果是真机实测出来的：
        // 一个只有 main.py 的 Python 项目，构建输出里会冒出
        //   [toolchain] ⚠ 项目没有完整 Gradle Wrapper，且 IDE 环境中没有 Gradle fallback
        // 一条与它毫无关系的警告；没装 SDK 的机器上还会再多一条「Android SDK 不存在」。
        // 这些 issue 还会让 `ProjectEnvironment.ready` 变 false，把 Python 项目误判成「工具链不完整」。
        val typeId = project.type.id
        // Android 原生工程：SDK + build-tools + platform + ADB + Gradle 全套。
        val needsAndroid = typeId == "android" || typeId.contains("xposed", ignoreCase = true)
        // Flutter 工程 `flutter build apk` 最终由 AGP 落地，同样需要 JDK 与 Android SDK；
        // 但它**不使用项目内的 Gradle Wrapper**（Flutter 自己生成/管理 android/ 目录），
        // 也不要求 ADB（构建不需要设备），所以只借用 needsJava / SDK 存在性检查。
        val needsFlutter = typeId.contains("flutter", ignoreCase = true)
        val needsJava = needsAndroid || needsFlutter || typeId.contains("java", ignoreCase = true)
        val needsGradle = needsAndroid
        val needsAndroidSdk = needsAndroid || needsFlutter

        val jdk = Environment.resolveJdkHome(app, cfg.jdkMajor)?.let(::File)
        if (needsJava && jdk == null) issues += "缺少 JDK ${cfg.jdkMajor}"

        val sdk = File(cfg.androidSdk ?: Environment.androidSdkRoot(app))
        if (needsAndroidSdk && !sdk.isDirectory) issues += "Android SDK 不存在：${sdk.absolutePath}"

        val requestedBuildTools = cfg.buildToolsVersion ?: project.metadata.buildToolsVersion
        val buildTools = requestedBuildTools?.let { File(sdk, "build-tools/$it") }
            ?: Environment.latestBuildToolsDir(app)
        if (needsAndroid && (buildTools == null || !buildTools.isDirectory)) {
            if (cfg.buildToolsVersion != null) {
                issues += "缺少工程指定的 Android Build Tools ${cfg.buildToolsVersion}（构建前会尝试从本地资产恢复）"
            } else {
                // 未指定版本时才允许使用内置用户态或其它已安装版本兜底；工程明确指定版本不能被其它版本掩盖。
                if (Environment.latestBuildToolsExecutable(app, "aapt2") == null) issues += "缺少 Android Build Tools"
            }
        }

        if (needsAndroid) project.metadata.compileSdk?.let { api ->
            if (!File(sdk, "platforms/android-$api/android.jar").isFile) {
                // 这条以前只说「缺少」，用户不知道下一步做什么；真机反馈「安卓空项目建出来就编译不过」。
                // 现在直接把两条可执行的出路写清楚：装平台，或把工程降到已装的版本。
                val installed = runCatching {
                    File(sdk, "platforms").listFiles()?.mapNotNull { it.name.removePrefix("android-").toIntOrNull() }?.sorted()
                }.getOrNull().orEmpty()
                val fallback = installed.lastOrNull()
                issues += "缺少 Android Platform android-$api（工程 compileSdk=$api 在设备上没有对应平台）" +
                    "→ 打开「设置 → 工具链 → 安装 SDK 组件」安装 platforms;android-$api" +
                    (fallback?.let { "，或把 app/build.gradle.kts 的 compileSdk/targetSdk 改成已安装的 $it" } ?: "")
            }
        }

        val ndk = cfg.ndkVersion?.let { File(sdk, "ndk/$it") }
            ?: project.metadata.ndkVersion?.let { File(sdk, "ndk/$it") }
        // 真机坑（2.12.95）：AGP 自动安装 NDK 失败后会留下 `ndk/<版本>/.installer` 空壳
        // （实测 11K）。只判断 isDirectory 会把它当成「已安装」，于是既不报缺、也不阻止构建，
        // 最后就是无限期卡在 NDK 安装上。这里要求真正存在 source.properties 才算装了。
        val ndkIncomplete = ndk != null && ndk.isDirectory && !File(ndk, "source.properties").isFile
        if (needsAndroid && project.metadata.ndkVersion != null && (ndk == null || !ndk.isDirectory || ndkIncomplete)) {
            issues += "缺少 NDK ${project.metadata.ndkVersion}" +
                "（提示：纯 Flutter/Kotlin 工程不需要 NDK；若确实要用，请在「设置 → 工具链 → 安装 SDK 组件」里安装，" +
                "不要让 AGP 自动去 Google 源下载——境内网络会长时间挂住）"
        }

        // 同理：adb 也常由内置用户态提供（files/usr/bin/adb，aarch64）。
        // Environment.resolveAdb 已经「SDK platform-tools 优先，其次内置工具」，这里直接复用，
        // 避免出现「设置页显示 ADB 已就绪，构建时却说缺 ADB」的自相矛盾。
        if (needsAndroid && Environment.resolveAdb(app) == null) issues += "缺少可执行 ADB"

        // ---- 语言级工具链：缺了会在构建时直接 `command not found`，必须提前告知并指到设置页 ----
        // 真机现象：Go 项目点「构建」，输出只有一行 `go: command not found`，用户无法得知
        // 「这是工具链没装」以及「去哪里装」——这一组 issue 就是把这个断点补上。
        for (tool in requiredTools(typeId)) {
            if (tool.names.none { Environment.findExecutable(app, it) != null }) {
                issues += "缺少 ${tool.component.title} 工具链（未找到 ${tool.names.first()}）" +
                    "→ 打开「设置 → 工具链」安装「${tool.component.title}」"
            }
        }

        // Gradle 只在 AGP 工程里是必需品：Java 后端用 Maven、Python/Node/Go 项目根本不用 Gradle。
        val gradle = if (project.wrapperProperties?.isFile == true && File(project.root, "gradlew").isFile) {
            File(project.root, "gradlew")
        } else Environment.resolveGradle(app)
        if (needsGradle && gradle == null) issues += "项目没有完整 Gradle Wrapper，且 IDE 环境中没有 Gradle fallback"

        val env = if (needsJava && jdk != null) Environment.buildGradleEnv(app, cfg.jdkMajor).toMutableMap()
        else Environment.buildSdkEnv(app).toMutableMap()
        env["NEBULAFORGE_PROJECT_ROOT"] = project.root.absolutePath
        // Android 变量只对 AGP 工程注入：非 Android 项目注入 ANDROID_SDK_ROOT 等会让构建输出里
        // 出现一堆用不上的 export（真机上表现为「打开构建面板先刷半屏环境变量」）。
        if (needsAndroidSdk) {
            env["ANDROID_SDK_ROOT"] = sdk.absolutePath
            env["ANDROID_HOME"] = sdk.absolutePath
            if (buildTools != null) env["NEBULAFORGE_BUILD_TOOLS"] = buildTools.absolutePath
            if (ndk != null) env["ANDROID_NDK_ROOT"] = ndk.absolutePath
        }
        // GRADLE_USER_HOME 一律注入：Flutter 工程不属于「needsGradle」的 AGP 工程，但它内部同样跑 Gradle
        // （packages/flutter_tools/gradle 是 included build），而 Gradle 只会从 GRADLE_USER_HOME/init.d
        // 读取镜像与插件门户配置。真机 Flutter 构建卡在
        // 「Plugin [id: 'org.gradle.kotlin.kotlin-dsl'] was not found」就是这里没接上插件门户。
        env["GRADLE_USER_HOME"] = Environment.gradleUserHome(app)
        // 每次解析项目环境都幂等刷新镜像/插件门户脚本（原先只有死代码 BuildEnvironmentPreparer.prepare
        // 会写它，导致设备上的 init.d 停留在旧内容）。
        runCatching { GradleMavenMirrors.ensure(app) }

        return ProjectEnvironment(project, jdk ?: File(""), sdk, buildTools, ndk, gradle, env, issues.distinct())
    }

    /** 一条「项目类型构建时真正会用到的命令」需求。[names] 为候选命令名（任一存在即可用）。 */
    private data class RequiredTool(val names: List<String>, val component: ToolchainComponent)

    /**
     * 项目类型 → 构建命令实际会调用的可执行文件。
     *
     * 只覆盖**语言自身**的工具链；Android/Java/Gradle 那套（JDK / SDK / build-tools / ADB / Gradle）
     * 已在上面的专门分支里逐项检查，这里不重复，避免一个 Android 项目报两条同样的缺失。
     *
     * 与模板的对应关系（`TasksJson.defaultTasks` 生成的构建命令）：
     * - `go build ./...`            → go
     * - `cargo build`               → cargo
     * - `composer install`          → php, composer
     * - `mvn -q package`            → mvn（JDK 由 needsJava 覆盖）
     * - `cmake -S . -B build`       → cmake, make（未指定生成器时为 Unix Makefiles，必须能调 make）
     * - `make -j4`                  → make, cc（Termux 的 clang 包提供 cc/gcc 链接）
     * - `lua_check ...`             → lua5.4 / lua（解释器与 luac 二选一，见 LanguageCommands）
     * - `npm run build`             → node
     * - `python3 -m compileall`     → python3
     * - `flutter build apk`         → flutter（Android SDK/JDK 同上单独检查）
     */
    private fun requiredTools(typeId: String): List<RequiredTool> {
        val id = typeId.lowercase()
        return when {
            // AGP 工程：走 JDK/SDK/build-tools/ADB/Gradle 专门检查。
            id == "android" || id.contains("xposed") -> emptyList()
            id.contains("flutter") -> listOf(RequiredTool(listOf("flutter"), ToolchainComponent.FLUTTER))
            id.contains("go") -> listOf(RequiredTool(listOf("go"), ToolchainComponent.GO))
            id.contains("rust") -> listOf(RequiredTool(listOf("cargo"), ToolchainComponent.RUST))
            id.contains("php") -> listOf(
                RequiredTool(listOf("php"), ToolchainComponent.PHP),
                RequiredTool(listOf("composer"), ToolchainComponent.COMPOSER)
            )
            // Java 后端用 Maven 打包；纯 Java 控制台项目只需要 JDK 里的 javac（needsJava 已覆盖）。
            id == "java-backend" -> listOf(RequiredTool(listOf("mvn"), ToolchainComponent.MAVEN))
            // 注意：C++ 模板的 **ProjectType id 是 "cpp"**（"cmake" 是它 BuildSystem 的 id），
            // 这里两种命名都认，否则 cpp-cmake 项目一条工具链检查都不会跑。
            id == "cpp" || id == "cmake" -> listOf(
                RequiredTool(listOf("cmake"), ToolchainComponent.CMAKE),
                RequiredTool(listOf("make"), ToolchainComponent.CMAKE)
            )
            id == "c" -> listOf(
                RequiredTool(listOf("make"), ToolchainComponent.C_COMPILER),
                RequiredTool(listOf("cc", "clang"), ToolchainComponent.C_COMPILER)
            )
            id == "lua" -> listOf(RequiredTool(listOf("lua5.4", "lua", "luajit"), ToolchainComponent.LUA))
            // TypeScript 独立工程：构建任务就是 `tsc -p tsconfig.json`，所以除了 node 还必须有 tsc。
            // Vite / Nest 这类框架工程不走这条分支（它们由自己的 ProjectType 归到 web-* ），
            // 其 typescript 来自项目 devDependencies，npm install 即就位。
            id == "typescript" -> listOf(
                RequiredTool(listOf("node"), ToolchainComponent.NODE),
                RequiredTool(listOf("tsc"), ToolchainComponent.TYPESCRIPT)
            )
            id == "web-frontend" || id == "web-backend" || id == "npm" || id == "javascript" ->
                listOf(RequiredTool(listOf("node"), ToolchainComponent.NODE))
            id == "python" || id == "python-backend" || id == "html" || id == "css" ->
                listOf(RequiredTool(listOf("python3", "python"), ToolchainComponent.PYTHON))
            else -> emptyList()
        }
    }

    /** 将解析出的 SDK/JDK 写入项目 local.properties，仅写绝对路径，不写用户环境变量。 */
    fun syncLocalProperties(environment: ProjectEnvironment): File {
        val file = File(environment.project.root, "local.properties")
        val lines = mutableListOf("sdk.dir=${escape(environment.sdkRoot.absolutePath)}")
        environment.ndkRoot?.let { lines += "ndk.dir=${escape(it.absolutePath)}" }
        file.writeText(lines.joinToString("\n") + "\n")
        return file
    }

    private fun escape(path: String): String = path.replace("\\", "\\\\").replace(" ", "\\ ")
}
