package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File

/**
 * 给 Gradle 构建接上**国内 Maven 镜像**。
 *
 * ## 为什么必须有这一步（真机取证）
 * 「Android 空项目模板」在真机上构建失败的最后一环并不是模板内容，而是**依赖解析阶段就断了**：
 *
 * ```
 * * What went wrong:
 * A problem occurred configuring root project 'android-app'.
 * > Could not resolve all artifacts for configuration 'classpath'.
 *    > Could not resolve org.jetbrains.kotlin:kotlin-reflect:2.4.0.
 *       > Could not get resource 'https://repo.maven.apache.org/maven2/...'
 *          > Read timed out
 * > There are 67 more failures with identical causes.
 * ```
 *
 * 即 AGP/插件 classpath 根本没下下来，构建在“配置工程”阶段就 BUILD FAILED，用户看到的就是
 * 「模板缺文件 / 不能编译」。同一台设备上换成阿里云 / 华为云镜像后，同样的构件 200 秒回。
 *
 * ## 为什么写成 GRADLE_USER_HOME/init.d 里的初始化脚本
 * 1. Gradle 会自动应用 `GRADLE_USER_HOME/init.d/` 下的 `*.gradle`，**不需要改用户工程文件**，
 *    因此对「用户已经建好的老工程」同样生效（重建模板只帮得到新工程）；
 * 2. 用 `gradle.beforeSettings` 注入 `pluginManagement.repositories`，镜像排在工程自己声明的
 *    `google()/mavenCentral()` 之前 → 镜像优先、官方源兜底；
 * 3. `dependencyResolutionManagement.repositories` 在 `settingsEvaluated` 里注入，兼容
 *    `FAIL_ON_PROJECT_REPOS`（那是限制 *project* 级仓库，settings 级注入不受影响）。
 */
object GradleMavenMirrors {

    /**
     * 镜像顺序即优先级。
     *
     * - 阿里云 `google` 仓库代理的是 `dl.google.com/dl/android/maven2`（AGP、AndroidX）；
     * - 阿里云 `public` 代理 Maven Central（Kotlin、第三方依赖）；
     * - 华为云作为备用（真机上一个镜像偶发超时很常见，多一个源能显著提高成功率）。
     */
    private val MIRRORS = listOf(
        "https://maven.aliyun.com/repository/google",
        "https://maven.aliyun.com/repository/public",
        "https://repo.huaweicloud.com/repository/maven"
    )

    /**
     * 插件门户。**只给镜像是不够的**：Gradle 插件（`kotlin-dsl`、AGP 插件标记等）发布在
     * [Gradle Plugin Portal](https://plugins.gradle.org)，它**不是 Maven Central**，
     * 阿里云 `public` 仓库里没有这些插件标记。
     *
     * 真机取证（Flutter 工程）：
     * ```
     * Plugin [id: 'org.gradle.kotlin.kotlin-dsl', version: '6.7.3'] was not found in any of the following sources:
     * - Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
     * - Plugin Repositories ...
     *   Searched in the following repositories:
     *     maven(https://maven.aliyun.com/repository/google)
     *     maven2(https://maven.aliyun.com/repository/public)
     *     maven3(https://repo.huaweicloud.com/repository/maven)
     * ```
     * 三个仓库都不含插件门户内容 → 插件解析失败 → 构建在配置阶段就 FAILED。
     * 阿里云有插件门户镜像 `gradle-plugin`，排在官方门户之前。
     */
    private val PLUGIN_PORTALS = listOf(
        "https://maven.aliyun.com/repository/gradle-plugin"
    )

    /** 初始化脚本落盘位置（Gradle 自动加载 `init.d` 下所有脚本）。 */
    fun scriptFile(context: Context): File =
        File(Environment.gradleUserHome(context), "init.d/nebula-mirrors.gradle")

    /**
     * 幂等写入镜像初始化脚本。
     *
     * @return 需要展示给用户的一行说明；内容已是最新时返回 `null`（不产生噪音）。
     */
    @Synchronized
    fun ensure(context: Context): String? {
        val file = scriptFile(context)
        val text = render()
        if (file.isFile && runCatching { file.readText() }.getOrNull() == text) return null
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            "已接入国内 Maven 镜像（阿里云/华为云），官方源仅作兜底"
        }.getOrNull()
    }

    /** 供工具链/构建前言展示的一句话状态。 */
    fun describe(context: Context): String =
        if (scriptFile(context).isFile) "国内 Maven 镜像已接入（${scriptFile(context).absolutePath}）"
        else "国内 Maven 镜像未接入"

    private fun render(): String = buildString {
        appendLine("// 由 NebulaForgeIDE 自动生成 —— 请勿手改，应用会在构建前重新写入。")
        appendLine("// 真机取证：直连 repo.maven.apache.org / dl.google.com 在境内经常 `Read timed out`，")
        appendLine("// 一次构建出现 67 处同类超时，AGP/插件 classpath 解析失败 → 配置阶段直接 BUILD FAILED。")
        appendLine("// 这里把国内镜像插到最前面，工程自己声明的 google()/mavenCentral() 继续作为兜底。")
        appendLine("//")
        appendLine("// 【Flutter / 复合构建兼容 —— 真机取证，务必保留】")
        appendLine("// Flutter 工程的 settings.gradle.kts 用 pluginManagement.includeBuild(<flutter>/packages/")
        appendLine("// flutter_tools/gradle) 引入 Flutter 插件，那是**复合构建**。旧版无条件往 pluginManagement")
        appendLine("// 注入仓库，Flutter 构建在配置阶段直接炸（nb-flutter.log 原文）：")
        appendLine("//   Error resolving plugin [id: 'dev.flutter.flutter-plugin-loader', version: '1.0.0']")
        appendLine("//   > Build was configured to prefer settings repositories over project repositories")
        appendLine("//     but repository 'maven' was added by settings file 'settings.gradle.kts'")
        appendLine("// 两条护栏：")
        appendLine("//  1) 目标 settings 出现 includeBuild(（复合构建）→ 不碰其 pluginManagement，插件仓库交给")
        appendLine("//     工程自己声明（Flutter 插件由 includeBuild 直接提供，不依赖远端仓库）。")
        appendLine("//  2) included build（Gradle 单独建实例，parent 非空）→ 完全不注入。")
        appendLine("// 另：每个镜像显式命名，报错信息里能一眼看出是谁加的。")
        appendLine("def NEBULA_MIRRORS = [")
        MIRRORS.forEach { appendLine("    '$it',") }
        appendLine("]")
        appendLine("def NEBULA_PLUGIN_PORTALS = [")
        PLUGIN_PORTALS.forEach { appendLine("    '$it',") }
        appendLine("]")
        appendLine()
        appendLine("def nebulaMaven = { repos, urls, prefix ->")
        appendLine("    urls.eachWithIndex { u, i -> repos.maven { it.name = prefix + i; it.url = u } }")
        appendLine("}")
        appendLine()
        appendLine("// beforeSettings：必须早于 settings 脚本求值，否则 plugins {} 的插件仓库已经定下来了。")
        appendLine("// Gradle < 6.0 没有 beforeSettings：整段包 try，老工程不能因为这段脚本挂掉。")
        appendLine("try {")
        appendLine("gradle.beforeSettings { settings ->")
        appendLine("    if (settings.gradle.parent != null) return   // included build：不注入")
        appendLine("    try {")
        appendLine("        def dir = settings.rootDir")
        appendLine("        def composite = ['settings.gradle', 'settings.gradle.kts'].any { n ->")
        appendLine("            def f = new File(dir, n)")
        appendLine("            f.isFile() && f.text.contains('includeBuild(')")
        appendLine("        }")
        appendLine("        if (composite) return   // Flutter 等复合构建：插件仓库由工程自己定")
        appendLine("        settings.pluginManagement.repositories {")
        appendLine("            nebulaMaven(delegate, NEBULA_MIRRORS, 'nebulaMirror')")
        appendLine("            nebulaMaven(delegate, NEBULA_PLUGIN_PORTALS, 'nebulaPortal')")
        appendLine("            gradlePluginPortal()")
        appendLine("        }")
        appendLine("    } catch (Throwable ignored) { }")
        appendLine("}")
        appendLine("} catch (Throwable ignored) { }")
        appendLine()
        appendLine("// 依赖（implementation/api 等）走 settings 级仓库：这是 FAIL_ON_PROJECT_REPOS 下唯一合法位置。")
        appendLine("settingsEvaluated { settings ->")
        appendLine("    if (settings.gradle.parent != null) return   // included build：不注入")
        appendLine("    try {")
        appendLine("        settings.dependencyResolutionManagement.repositories {")
        appendLine("            nebulaMaven(delegate, NEBULA_MIRRORS, 'nebulaMirror')")
        appendLine("            nebulaMaven(delegate, NEBULA_PLUGIN_PORTALS, 'nebulaPortal')")
        appendLine("        }")
        appendLine("    } catch (Throwable ignored) { }")
        appendLine("}")
    }
}
