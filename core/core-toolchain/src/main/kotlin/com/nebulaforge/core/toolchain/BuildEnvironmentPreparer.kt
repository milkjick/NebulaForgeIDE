package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.environment.TermuxGuest
import com.nebulaforge.core.projectmodel.ProjectResolver
import java.io.File

/**
 * 构建前「工具链接线」入口：把 [ProjectToolchainResolver] 的解析结果真正送进构建流程。
 *
 * ## 为什么需要它（真实断点）
 * 仓库里其实早就有一套完整的工具链解析：
 * `ProjectResolver` → `ProjectToolchainResolver.resolve()` → `ProjectEnvironment`（含 JAVA_HOME /
 * ANDROID_SDK_ROOT / GRADLE_USER_HOME / NDK / build-tools）+ `syncLocalProperties()`。
 * 但它**只被 `RealAndroidBuildRunController` 调用过一次**，而构建面板走的是
 * `WorkspaceTaskRunner`（pty + tasks.json）这条路 —— 于是：
 *
 * 1. `local.properties` 从来没被写出来。AGP 靠它定位 SDK，缺失时构建直接失败并只打印
 *    `SDK location not found`，用户完全看不出「工具链装了但没接上」；
 * 2. `ANDROID_SDK_ROOT` / `ANDROID_NDK_ROOT` / `NEBULAFORGE_BUILD_TOOLS` 这些项目级变量
 *    （`ProjectEnvironment.variables` 里专门拼好的）没有任何人消费；
 * 3. 解析出的 `issues`（缺少 JDK / Build Tools / Platform / ADB）被静默丢弃，
 *    表现为「点了构建，跑一半莫名其妙报错」。
 *
 * 本对象把这三件事在**构建启动前**补齐，且不改变 `WorkspaceTaskRunner` 的 pty 执行模型。
 */
class BuildEnvironmentPreparer(context: Context) {

    private val app = context.applicationContext
    private val projectResolver = ProjectResolver()
    private val toolchainResolver = ProjectToolchainResolver(app)

    /**
     * @param environment 解析出的项目环境（未识别为已知项目类型时为 null）
     * @param env 需要 `export` 进 guest 的变量（始终至少含 `Environment.buildTerminalEnv`）
     * @param notes 已完成的准备工作（展示在「输出」里，让用户看到工具链确实接上了）
     * @param warnings 不阻塞构建、但很可能导致失败的原因（如「缺少 Android Build Tools」）
     */
    data class Prepared(
        val environment: ProjectEnvironment?,
        val env: Map<String, String>,
        val notes: List<String>,
        val warnings: List<String>
    )

    fun prepare(projectRoot: File, jdkMajor: Int? = null): Prepared {
        // 基线：终端级环境（含 JAVA_HOME / ANDROID_HOME / GRADLE_USER_HOME / PATH）。
        // 即使项目类型识别失败也要有它，否则 guest 里连 java 都可能找不到。
        val base = runCatching { Environment.buildTerminalEnv(app) }
            .getOrElse { mapOf("HOME" to Environment.homeRoot(app), "PREFIX" to Environment.usrRoot(app)) }
        val env = base.toMutableMap()
        val notes = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // 构建自愈⓪：卸载重装后的「从公共存储快照本地恢复工具链」——必须排在所有自愈器**之前**，
        // 因为后面的修复都基于恢复后的目录内容。
        // 卸载会清空私有目录：Gradle 发行版（wrapper dists）、Maven 依赖缓存、Android SDK、
        // 用户态 JDK 全部消失，而重装后只能重新下载 —— 本机网络下 services.gradle.org 不可达，
        // 用户看到的就是「卸载重装后怎么都编不过」。这里改走本地复制：
        // 快照在 /storage/emulated/0/NebulaForge/toolchain（公共存储，不随卸载清除），
        // 首次构建成功后再由后台线程把工具链镜像过去（有节流，不阻塞构建）。
        runCatching { ToolchainSnapshot.autoMaintain(app, notes) }
            .onFailure { warnings += "工具链快照（恢复/抓取）未完成：${it.message ?: it.javaClass.simpleName}" }

        // Android SDK 布局兜底（离线、幂等）：Flutter 的 validSdkDirectory() 判定条件是
        // 「有 licenses/ 目录 或 有 platform-tools/ 目录」，两者都没有时，即使 ANDROID_HOME
        // 已正确导出、目录也确实存在，`flutter build apk` 依然报
        // 「No Android SDK found. Try setting the ANDROID_HOME environment variable.」（真机取证）。
        // 只在缺失时写盘，正常情况下没有任何输出。
        notes += runCatching { AndroidSdkCompat.ensureSdkLayout(app) }.getOrDefault(emptyList())

        // 构建自愈①：build-tools 残缺。真机取证：`build-tools/34.0.0/` 只有 aapt2+两个元数据文件，
        // AGP 直接判 "Installed Build Tools revision 34.0.0 is corrupted. Remove and install again
        // using the SDK Manager."，而手机上并没有 SDK Manager 可用。这里离线补齐纯 Java 组件
        // （d8 / apksigner / lib/*.jar / core-lambda-stubs.jar），aapt2 只保留设备上真能执行的 arm64 版。
        runCatching {
            val requestedBuildTools = runCatching {
                com.nebulaforge.core.projectmodel.ProjectToolchainConfig.read(projectRoot).buildToolsVersion
                    ?: detectBuildToolsVersion(projectRoot)
            }.getOrNull()
            BuildToolsRepair.ensure(app, requestedBuildTools)
        }
            .onSuccess { healed -> healed.forEach { notes += "自愈：$it" } }
            .onFailure { warnings += "构建自愈（build-tools）未完成：${it.message ?: it.javaClass.simpleName}" }

        // 构建自愈③：AGP **自带**的 aapt2 是 x86_64 版。真机取证：资源编译阶段
        // "AAPT2 aapt2-8.6.0-11315950-linux Daemon #0: Daemon startup failed"——AGP 8.x 会把
        // Maven 上的 aapt2 解到 Gradle 缓存里直接 exec，那份产物只有 linux/mac/win x86_64，
        // arm64 真机起不来；而且它裸跑还缺 libfmt.so，必须带 LD_LIBRARY_PATH。
        // 因此换成「arm64 真身 + 自带环境变量的包装器」，并用 AGP 官方开关
        // android.aapt2FromMavenOverride 指向内置 arm64 aapt2（缓存被清也不怕）。
        runCatching { Aapt2ArchRepair.ensure(app) }
            .onSuccess { healed -> healed.forEach { notes += "自愈：$it" } }
            .onFailure { warnings += "构建自愈（aapt2 架构）未完成：${it.message ?: it.javaClass.simpleName}" }

        // 构建自愈④：NDK 自带的 llvm-strip 同样是 x86_64 版。真机取证（Flutter 测试包）：
        // `> Task :app:stripDebugDebugSymbols FAILED` + ".../linux-x86_64/bin/llvm-strip:
        //  not executable: 64-bit ELF file"——只要工程带 jniLibs（Flutter 必然带 libflutter.so），
        // 打包阶段就必然失败。替换为「arm64 llvm-strip + 自带 LD_LIBRARY_PATH 的包装器」。
        // 放在 aapt2 之后：两者都是「host 工具架构」问题，一起在构建启动前修掉。
        runCatching { NdkArchRepair.ensure(app) }
            .onSuccess { healed -> healed.forEach { notes += "自愈：$it" } }
            .onFailure { warnings += "构建自愈（NDK strip 架构）未完成：${it.message ?: it.javaClass.simpleName}" }

        // Flutter 构建外壳（guest 内 `nb-flutter`）。
        // 真机第二堵墙：Flutter 的 Android 构建必须**执行**工程内的 `android/gradlew`，而用户
        // 工程在 /storage/emulated/0（fuse，noexec，权限固定 0660）——gradlew 永远不可执行，
        // 报 "lacked sufficient permissions to execute .../android/gradlew"。外壳把构建搬到
        // 应用私有目录（f2fs，可执行）再把产物拷回，工程路径对用户不变。
        // 判据用 pubspec.yaml（不依赖项目类型识别是否成功），安装幂等且静默。
        if (File(projectRoot, "pubspec.yaml").isFile) {
            runCatching { FlutterBuildShim.ensure(app) }
                .onSuccess { note -> note?.let { notes += it } }
                .onFailure { warnings += "安装 Flutter 构建外壳失败：${it.message ?: it.javaClass.simpleName}" }
        } else if (isGradleProject(projectRoot)) {
            // 原生工程也可能位于 /storage。Flutter 外壳不能覆盖这里，否则会改变 Gradle
            // 工程的工作目录和参数；专用外壳只负责镜像、执行原命令并回传 build 产物。
            runCatching { NativeBuildShim.ensure(app) }
                .onSuccess { note -> note?.let { notes += it } }
                .onFailure { warnings += "安装原生 Gradle 构建外壳失败：${it.message ?: it.javaClass.simpleName}" }
        }

        val descriptor = runCatching { projectResolver.resolve(projectRoot) }.getOrNull()
        if (descriptor == null) {
            // 未注册的项目类型（例如纯静态站点）：只需终端级环境，不产生噪音。
            return Prepared(null, env, notes, warnings)
        }

        val resolved = runCatching { toolchainResolver.resolve(descriptor, jdkMajor) }.getOrNull()
        if (resolved == null) {
            warnings += "项目工具链解析失败（${descriptor.type.id}），将按终端默认环境构建"
            return Prepared(null, env, notes, warnings)
        }

        // 项目级变量覆盖终端级：NDK / build-tools / 项目根 只有在这里才有。
        env.putAll(resolved.variables)

        // 会用到 Android SDK 的项目（含 Flutter：flutter build apk 最终由 AGP 落地）：
        // 把「缺 build-tools / platform-tools」这类必然会失败的原因提前说清楚，
        // 而不是让用户对着一句 AGP 的 aapt2 报错发呆。
        if (usesAndroidSdk(descriptor.type.id)) {
            warnings += runCatching { AndroidSdkCompat.diagnose(app) }.getOrDefault(emptyList())

            // 构建自愈⑤：Android SDK Platform 半装 / 缺失 → **自动补齐**。
            // 真机取证（原生 AGP 工程）：platforms/android-34 半装（缺 android.jar / source.properties），
            // AGP 自己那套下载器又撞上上次中断留下的 .temp/PackageOperation01 → FileAlreadyExistsException
            // → 反复「Preparing Install Android SDK Platform 34」却永远装不上 → Failed to find target
            // 'android-34'。这里抢在 AGP 之前：清 .temp 残留、删半装目录、按需下载官方 zip 补齐。
            runCatching { AndroidPlatformRepair.ensure(app, requiredPlatformApis(descriptor, projectRoot)) }
                .onSuccess { healed -> healed.forEach { notes += it } }
                .onFailure { warnings += "构建自愈（Android Platform）未完成：${it.message ?: it.javaClass.simpleName}" }
        }

        // Android/Gradle 的最关键一步：AGP 只认 local.properties 里的 sdk.dir。
        if (needsAndroidSdk(descriptor.type.id)) {
            runCatching { toolchainResolver.syncLocalProperties(resolved) }
                .onSuccess { notes += "已同步 local.properties（sdk.dir=${resolved.sdkRoot.absolutePath}）" }
                .onFailure { warnings += "写入 local.properties 失败：${it.message ?: it.javaClass.simpleName}" }
        }

        // Gradle Wrapper 补齐：模板不含 gradlew / gradle-wrapper.jar，缺了就只能退回 PATH 上的
        // Gradle（内置用户态是 9.7.1），而模板 AGP 是 8.6.0 —— AGP 8.x 不支持 Gradle 9.x，
        // 构建必失败。这里用应用内置资产补齐三件套，并把发行版钉在与 AGP 匹配的 Gradle 8.9。
        if (needsAndroidSdk(descriptor.type.id)) {
            runCatching { GradleWrapperBootstrap.ensure(app, projectRoot) }
                .onSuccess { applied -> applied?.notes?.forEach { notes += it } }
                .onFailure { warnings += "补齐 Gradle Wrapper 失败：${it.message ?: it.javaClass.simpleName}" }
        }

        // 国内 Maven 镜像：否则 AGP / 插件 classpath 会在「配置工程」阶段因 repo.maven.apache.org
        // 超时而直接 BUILD FAILED（真机取证：一次构建 67 处 `Read timed out`，用户看到的就是
        // 「模板不能编译」）。写进 GRADLE_USER_HOME/init.d，用户已建好的老工程同样受益。
        if (isGradleProject(projectRoot)) {
            val mirrorNote = runCatching { GradleMavenMirrors.ensure(app) }.getOrNull()
            if (mirrorNote != null) notes += mirrorNote
        }

        // 全局 init.gradle 版本迁移（每次构建前都跑，幂等；**不放在 isGradleProject 分支里**：
        // Flutter 工程根目录不是 Gradle 工程，Gradle 在 android/ 下，而全局 init.gradle 对
        // 两者都生效 —— 正是它让报错用户的 Flutter 工程与原生工程「都编不过」）。
        // 旧版 v1 用 allprojects 往**工程级**仓库注入镜像，与 Flutter / 新版 Android 模板的
        // RepositoriesMode.FAIL_ON_PROJECT_REPOS 冲突，构建在配置阶段直接失败：
        //   Build was configured to prefer settings repositories over project repositories
        //   but repository 'maven' was added by initialization script '…/.gradle/init.gradle'
        // 该文件老版本是「已存在就不写」，不迁移则修好的脚本永远到不了老设备。
        runCatching { TermuxGuest.ensureGradleInit(app) }
            .getOrNull()?.let { notes += it }

        // 构建自愈②：Gradle 发行版下载源。真机取证：`services.gradle.org` 在本机网络下完全不可达
        // （Flutter 工程构建时抛出 sun.security.ssl.* 异常堆栈 + "downloading artifacts from the
        // network" 失败），而阿里云 macports 镜像的**路径与文件名完全一致**且秒回 → 只换 host。
        // 不放在上面的 isGradleProject 分支里：Flutter 工程根目录不是 Gradle 工程，wrapper 在 android/ 下。
        runCatching { GradleDistributionMirror.ensure(projectRoot) }
            .getOrNull()?.let { notes += "自愈：$it" }

        // 构建自愈⑥：把 Gradle 发行版**真正取回本地**——这是「原生安卓 / Flutter 模板都编不过」的
        // 最终根因。构建脚本只在 wrapper 发行包**已缓存**时才用工程 wrapper（Gradle 8.9），否则回退到
        // 内置 Gradle 9.8.0，而模板 AGP 8.6.0 与 Gradle 9.x 不兼容；偏偏把发行包下载下来的唯一一条路
        // 又是 `gradlew` 自己 —— 而 `gradlew` 只有在「已经下载好」时才会被调用（死锁，详见该对象注释）。
        //
        // 必须排在 [GradleDistributionMirror] **之后**：先换成可达的国内镜像，再把镜像上那个发行包取回来。
        // 对 Flutter 工程同样有效：`android/` 骨架由 `flutter create` 生成，其 wrapper 也声明了发行包，
        // 应用侧先落好之后，内层 `flutter build` 就不必再去网络上拉 140MB（实测会断流/永久卡住）。
        if (usesAndroidSdk(descriptor.type.id) || File(projectRoot, "pubspec.yaml").isFile) {
            runCatching { GradleDistributionProvisioner.ensure(app, projectRoot) }
                .onSuccess { healed -> healed.forEach { notes += it } }
                .onFailure { warnings += "补齐 Gradle 发行版失败：${it.message ?: it.javaClass.simpleName}" }
            // 只对「原生 AGP 工程」给出这条告警：它的 wrapper 一定在（GradleWrapperBootstrap 刚写过），
            // 所以「仍未就绪」必然是下载没成功；Flutter 工程此刻可能还没有 android/ 目录，不适用。
            if (needsAndroidSdk(descriptor.type.id) && !GradleDistributionProvisioner.isReady(app, projectRoot)) {
                warnings += "Gradle 发行版未能落到本地（离线或镜像不可达）：本次构建会回退到内置 Gradle，" +
                    "而模板用的 AGP 8.6 与 Gradle 9.x 不兼容，构建很可能失败 —— 联网后重试一次即可自愈"
            }
        }

        warnings += resolved.issues
        resolved.gradleExecutable?.let { notes += "Gradle：${it.absolutePath}" }
        return Prepared(resolved, env, notes, warnings.distinct())
    }

    /** 需要 Android SDK 的项目类型：Android 应用/库、Xposed 模块（本质也是 AGP 工程）。 */
    private fun needsAndroidSdk(typeId: String): Boolean =
        typeId == "android" || typeId.contains("xposed", ignoreCase = true)

    /**
     * 会**用到** Android SDK 的项目类型。
     *
     * 与 [needsAndroidSdk] 的区别：Flutter 工程根目录没有 AGP 工程，不需要往根上写
     * `local.properties` / wrapper（`flutter build apk` 自己会在 `android/` 子工程里写），
     * 但它一样需要 SDK 里的 build-tools / platform-tools，所以诊断要覆盖它。
     */
    private fun usesAndroidSdk(typeId: String): Boolean =
        needsAndroidSdk(typeId) || typeId.contains("flutter", ignoreCase = true)

    /**
     * 是否 Gradle 工程：有 wrapper 或任意 `settings.gradle(.kts)` / `build.gradle(.kts)` 即算。
     *
     * 判断不看项目类型（`resolved` 可能识别失败），因为「依赖仓库超时」与语言无关：
     * 只要走 Gradle 构建，就需要国内镜像。
     */
    private fun isGradleProject(root: File): Boolean =
        File(root, "gradlew").isFile ||
            File(root, "settings.gradle.kts").isFile || File(root, "settings.gradle").isFile ||
            File(root, "build.gradle.kts").isFile || File(root, "build.gradle").isFile

    /**
     * 推断工程需要哪些 Android Platform（API）——用于 [AndroidPlatformRepair]，「缺就自动下载」。
     *
     *  - Android/Xposed：`ProjectResolver` 已从工程里解析出 `compileSdk`；
     *  - Flutter：根目录不是 AGP 工程，`android/` 子工程可能还没生成（由 `flutter build` 期间生成），
     *    所以先扫工程里的 `compileSdk` 字面量，再退回读 Flutter SDK 声明的默认 `compileSdkVersion`。
     *    两者都拿不到时返回空集——[AndroidPlatformRepair] 仍会修好「已存在但残缺」的平台。
     */
    private fun requiredPlatformApis(
        descriptor: com.nebulaforge.core.projectmodel.ProjectDescriptor,
        root: File
    ): Set<Int> {
        val apis = linkedSetOf<Int>()
        descriptor.metadata.compileSdk?.let { if (it > 0) apis += it }
        if (descriptor.type.id.contains("flutter", ignoreCase = true)) {
            if (apis.isEmpty()) detectCompileSdk(root)?.let { apis += it }
            if (apis.isEmpty()) flutterDefaultCompileSdk()?.let { apis += it }
        }
        return apis
    }

    /** 扫工程内 `build.gradle(.kts)`，取 `compileSdk`/`compileSdkVersion` 的**字面量数字**。 */
    private fun detectCompileSdk(root: File): Int? {
        val files = root.walkTopDown()
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .take(200)
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            Regex("""compileSdk(?:Version)?\s*=?\s*\(?\s*(\d+)""").find(text)?.let {
                it.groupValues[1].toIntOrNull()?.let { v -> return v }
            }
        }
        return null
    }

    /** 扫描工程声明的精确 Build Tools 版本，优先支持 Groovy/Kotlin DSL 两种写法。 */
    private fun detectBuildToolsVersion(root: File): String? {
        val files = root.walkTopDown()
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .take(200)
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            Regex("""buildToolsVersion\\s*[= ]\\s*[\\\"']([^\\\"']+)[\\\"']""").find(text)?.let {
                return it.groupValues[1]
            }
        }
        return null
    }

    /** 读 Flutter SDK 里声明的默认 `compileSdkVersion`（`FlutterExtension` / `flutter.groovy`）。 */
    private fun flutterDefaultCompileSdk(): Int? {
        val dir = File(Environment.homeRoot(app), "flutter/packages/flutter_tools/gradle")
        if (!dir.isDirectory) return null
        val files = dir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".groovy")) }
            .take(200)
        for (f in files) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            Regex("""compileSdkVersion\s*[:=]\s*(\d+)""").find(text)?.let {
                it.groupValues[1].toIntOrNull()?.let { v -> return v }
            }
        }
        return null
    }

    /** 供状态栏/面板展示「工具链是否就绪」的一句话结论。 */
    fun readinessSummary(prepared: Prepared): String = when {
        prepared.environment == null -> "未识别项目类型，使用终端默认工具链"
        prepared.environment.ready -> "工具链就绪"
        else -> "工具链不完整：" + prepared.environment.issues.joinToString("；")
    }
}
