package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File

/**
 * 「构建自愈」之一：把**残缺的 build-tools** 补成 AGP 能接受的样子。
 *
 * ## 真机取证（用户截图）
 * 构建 Android 工程时 AGP 直接失败：
 * ```
 * Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.
 * > Installed Build Tools revision 34.0.0 is corrupted. Remove and install again using the SDK Manager.
 * ```
 * 现场取证：`<sdk>/build-tools/34.0.0/` 里**只有 3 个文件**（`aapt2`、`source.properties`、`package.xml`），
 * 而官方 build-tools 包还有 `d8`、`apksigner`、`lib/d8.jar`、`lib/apksigner.jar`、`core-lambda-stubs.jar`
 * 等一整套 —— AGP 校验不过就判「corrupted」，而「用 SDK Manager 重装」这句话在手机上根本无从下手。
 *
 * ## 为什么可以离线补全
 * 1. `aapt2` **必须**是本机能执行的 arm64 版：官方包里的 aapt2 是 x86_64 ELF，在 arm64 设备上
 *    连启动都不行。设备内置用户态里已经有一个可用的（Termux 原生 aapt2，实测 `aapt2 version` 正常，
 *    在 guest 里带上 `LD_LIBRARY_PATH` 即可运行）—— **只保留、绝不覆盖**。
 * 2. `d8` / `apksigner` / `lib/ 下的 jar` / `core-lambda-stubs.jar` 全是**纯 Java**：`d8` 脚本本质就是
 *    `java -cp lib/d8.jar com.android.tools.r8.D8`，在 arm64 + 设备 JDK 上照跑。因此它们可以作为
 *    应用资产随包分发，**完全不依赖网络**（这台设备 DNS 时不时解析失败，走网络的自愈并不可靠）。
 * 3. 其余 `aidl` / `zipalign` / `dexdump` 等是 x86_64 原生工具，debug（测试包）构建不会调用；
 *    为满足存在性校验写入**明确的占位脚本**：真被调用时打印可读原因并以非 0 退出，
 *    而不是让 AGP 再抛一句无从下手的 corrupted。
 *
 * 全程幂等：文件都在就直接返回空列表（不产生任何输出噪音）。
 */
object BuildToolsRepair {

    private const val ASSET_ROOT = "android-buildtools"

    /** 随包分发的官方组件（纯 Java，arm64 可直接运行）。 */
    private val ASSET_FILES = listOf(
        "d8", "apksigner",
        "lib/d8.jar", "lib/apksigner.jar",
        "core-lambda-stubs.jar",
        "NOTICE.txt", "runtime.properties"
    )

    /** 官方包里的 x86_64 原生工具：只补「存在性」，被真调用时给出可读原因。 */
    private val STUB_TOOLS = listOf(
        "aapt", "aidl", "zipalign", "split-select", "dexdump", "bcc_compat", "llvm-rs-cc",
        "aarch64-linux-android-ld", "arm-linux-androideabi-ld", "i686-linux-android-ld",
        "mipsel-linux-android-ld", "x86_64-linux-android-ld"
    )

    /**
     * 检查并修复 SDK 里所有 build-tools 版本目录；返回需要展示给用户的自愈说明（无改动则空）。
     * 只处理应用资产里带版本号的目录（目前 34.0.0），其它版本原样不动。
     */
    fun ensure(context: Context, requestedVersion: String? = null): List<String> {
        val app = context.applicationContext
        val buildTools = File(Environment.androidSdkRoot(app), "build-tools")
        val installed = runCatching {
            buildTools.listFiles { f -> f.isDirectory }?.map { it.name }
        }.getOrNull().orEmpty()
        val versions = linkedSetOf<String>().apply {
            requestedVersion?.takeIf { it.matches(Regex("\\d+\\.\\d+\\.\\d+")) }?.let(::add)
            addAll(installed)
        }
        val notes = mutableListOf<String>()
        for (version in versions) {
            if (!hasAssets(app, version) && version !in installed) continue
            runCatching { notes += repairVersion(app, File(buildTools, version), version) }
        }
        return notes
    }

    private fun hasAssets(app: Context, version: String): Boolean =
        runCatching { app.assets.list("$ASSET_ROOT/$version")?.isNotEmpty() == true }.getOrDefault(false)

    /** 脚本包装器判定（首字节 '#'）：Aapt2ArchRepair 生成的 arm64 aapt2 包装器要认成「有效」。 */
    private fun startsWithShebang(f: File): Boolean =
        runCatching { f.inputStream().use { it.read() } == '#'.code }.getOrDefault(false)

    private fun repairVersion(app: Context, dir: File, version: String): List<String> {
        val existed = dir.isDirectory
        val missing = ASSET_FILES.filterNot { File(dir, it).isFile }
        val stubMissing = STUB_TOOLS.filterNot { File(dir, it).isFile }
        val aapt2 = File(dir, "aapt2")
        // 包装器脚本（首字节 '#'）也算「有 aapt2」：这是 Aapt2ArchRepair 生成的、
        // 自带 LD_LIBRARY_PATH 的 arm64 包装器，不是残缺，不要每轮构建都覆盖它。
        val aapt2Ok = aapt2.isFile && (aapt2.length() > 100_000 || startsWithShebang(aapt2))
        val srcProps = File(dir, "source.properties")
        val srcOk = runCatching { srcProps.readText().contains("Pkg.Revision") }.getOrDefault(false)
        val pkgXml = File(dir, "package.xml")
        if (missing.isEmpty() && stubMissing.isEmpty() && aapt2Ok && srcOk && pkgXml.isFile) return emptyList()

        val notes = mutableListOf<String>()
        dir.mkdirs()

        var copied = 0
        for (rel in missing) {
            val target = File(dir, rel)
            runCatching {
                target.parentFile?.mkdirs()
                app.assets.open("$ASSET_ROOT/$version/$rel").use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                target.setExecutable(true, false)
            }.onSuccess { copied++ }
        }
        if (!existed && copied > 0) {
            notes += "工程要求 build-tools $version，但设备未安装该目录；已用应用内置资产创建并补齐 $copied 个组件" +
                "（来源为本地资产，无需联网）"
        } else if (copied > 0) {
            notes += "build-tools $version 残缺（AGP 直接判 corrupted），已用应用内置资产补齐 $copied 个组件" +
                "（d8 / apksigner / lib/d8.jar / core-lambda-stubs.jar 等，纯 Java，arm64 可直接运行）"
        }

        var stubs = 0
        for (name in stubMissing) {
            val f = File(dir, name)
            runCatching {
                f.writeText(stubScript(name))
                f.setExecutable(true, false)
            }.onSuccess { stubs++ }
        }
        if (stubs > 0) {
            notes += "已补齐 $stubs 个官方 x86_64 工具的占位（测试包构建不会调用；真被调用会打印可读原因）"
        }

        if (!aapt2Ok) {
            val prefixAapt2 = File(Environment.usrRoot(app), "bin/aapt2")
            if (prefixAapt2.isFile) {
                runCatching {
                    prefixAapt2.copyTo(aapt2, overwrite = true)
                    aapt2.setExecutable(true, false)
                }.onSuccess {
                    notes += "aapt2 缺失，已复制设备内置 arm64 版（官方包里的 aapt2 是 x86_64，本机无法执行）"
                }
            } else {
                notes += "⚠ aapt2 缺失，且内置用户态里没有可用的 arm64 版本：" +
                    "请在「设置 → 工具链」安装 Android SDK CLI"
            }
        }

        if (!srcOk) {
            runCatching { srcProps.writeText("Pkg.Desc=Android SDK Build-Tools\nPkg.Revision=$version\n") }
                .onSuccess { notes += "已补写 source.properties（Pkg.Revision=$version）" }
        }
        if (!pkgXml.isFile) {
            runCatching { pkgXml.writeText(packageXml(version)) }
                .onSuccess { notes += "已补写 package.xml（AGP 靠它识别已安装的 build-tools）" }
        }
        return notes
    }

    private fun stubScript(name: String): String = """
        |#!/system/bin/sh
        |# NebulaForge 构建自愈占位脚本。
        |# 官方 build-tools 里的 $name 是 x86_64 原生二进制，arm64 设备无法执行；
        |# 这里只用于满足 AGP 的存在性校验。debug（测试包）构建不会调用到它。
        |echo "[nebula] $name 是 x86_64 工具，无法在本机 arm64 上执行（正式包请在电脑上出）" >&2
        |exit 1
    """.trimMargin() + "\n"

    private fun packageXml(version: String): String {
        val parts = version.split('.')
        val major = parts.getOrNull(0) ?: "34"
        val minor = parts.getOrNull(1) ?: "0"
        val micro = parts.getOrNull(2) ?: "0"
        return """<?xml version="1.0" encoding="UTF-8"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns3="http://schemas.android.com/repository/android/generic/01">
  <localPackage path="build-tools;$version" obsolete="false">
    <type-details xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="ns3:genericDetailsType"/>
    <revision>
      <major>$major</major>
      <minor>$minor</minor>
      <micro>$micro</micro>
    </revision>
    <display-name>Android SDK Build-Tools $version</display-name>
    <uses-license ref="android-sdk-license"/>
  </localPackage>
</ns2:repository>
"""
    }
}
