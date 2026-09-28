package com.nebulaforge.core.toolchain

import android.content.Context
import com.nebulaforge.core.environment.Environment
import java.io.File

/**
 * Android SDK 布局兼容层：让「已装好一半的 SDK」能被各构建工具认出来。
 *
 * ## 真机取证（Flutter 报告「No Android SDK found」）
 * 设备上 `<ANDROID_HOME>` 只有 `cmdline-tools/` + `platforms/`，没有 `licenses/`、`platform-tools/`
 * 时，`flutter build apk` 会在依赖解析完成后直接失败：
 *
 * ```
 * No Android SDK found. Try setting the ANDROID_HOME environment variable.
 * ```
 *
 * 但 `ANDROID_HOME` 明明已经 export 了、目录也确实存在。翻 Flutter 自己的源码
 * （`packages/flutter_tools/lib/src/android/android_sdk.dart`）才看到判定条件：
 *
 * ```dart
 * static bool validSdkDirectory(String dir) =>
 *     sdkDirectoryHasLicenses(dir) || sdkDirectoryHasPlatformTools(dir);
 * //     ↑ 有 licenses/ 目录          ↑ 有 platform-tools/ 目录
 * ```
 *
 * 而且 `locateAndroidSdk()` 里 `ANDROID_HOME` 只是 else-if 链的一环，**`validSdkDirectory` 返回
 * false 就直接继续往后找**（`$HOME/Android/Sdk` → 从 PATH 上的 aapt 反推），最后返回 null →
 * 上层 `exitWithNoSdkMessage()` 打出上面那句话。所以「环境变量对不对」在这里无关紧要，
 * **缺 `licenses/` 才是根因**。
 *
 * ## 为什么可以直接写 licenses/
 * Android SDK 的「接受许可」落地形式就是 `licenses/android-sdk-license` 这类**纯文本哈希文件**
 * （`sdkmanager --licenses` 交互式回答 y 之后写的就是它们）。哈希是公开常量，CI 里普遍直接落盘，
 * 不需要联网、不需要用户手点。这里只在缺失时补写，已存在则一个字都不动。
 *
 * ## 为什么还要诊断 build-tools / platform-tools
 * `licenses/` 只能让工具**找到** SDK；`flutter build apk` / AGP 真正编译资源还需要
 * `build-tools/<ver>/aapt2`，`flutter run`/adb 相关功能还需要 `platform-tools/`。
 * 这两个包在真机上确实缺失，与其让用户看到一段 AGP 的晦涩报错，不如在构建输出里直接说清楚。
 */
object AndroidSdkCompat {

    /**
     * 标准许可哈希（与 `sdkmanager --licenses` 落盘内容一致）。
     *
     * 只补最常见的几个；缺失的许可由用户后续 `sdkmanager --licenses` 补齐即可。
     */
    private val LICENSE_FILES = linkedMapOf(
        "android-sdk-license" to listOf(
            "24333f8a63b6825ea9c5514f83c2829b004d1fee",
            "8933bad161af4178b1185d1a37fbf41ea5269c55",
            "d56f5187479451eabf01fb78af6dfcb131a6481e"
        ),
        "android-sdk-preview-license" to listOf("84831b9409646a918e30573bab4c9c91346d8abd"),
        "android-googletv-license" to listOf("601085b94cd77f0b54ff86406957099ebe79c4d6"),
        "android-sdk-arm-dbt-license" to listOf("859f317696f67ef3d7f30a50a5560e783445620d"),
        "intel-android-extra-license" to listOf("d975f751698a77b662f1254ddbeed3901e976f5a")
    )

    private fun licensesDir(context: Context): File = File(Environment.androidSdkRoot(context), "licenses")

    /**
     * 补齐最小 SDK 布局，返回「实际做了改动」的说明（无改动返回空列表，避免每次构建刷屏）。
     */
    @Synchronized
    fun ensureSdkLayout(context: Context): List<String> {
        val notes = mutableListOf<String>()
        val sdk = File(Environment.androidSdkRoot(context))
        if (!sdk.isDirectory) return notes

        val dir = licensesDir(context)
        val created = mutableListOf<String>()
        for ((name, hashes) in LICENSE_FILES) {
            val f = File(dir, name)
            if (f.isFile && f.length() > 0) continue
            runCatching {
                dir.mkdirs()
                f.writeText(hashes.joinToString("\n") + "\n")
                created += name
            }
        }
        if (created.isNotEmpty()) {
            notes += "已补齐 Android SDK 许可文件（${created.joinToString("/")}）——" +
                "Flutter/Dart 只有看到 licenses/ 才认为 SDK 有效，否则报「No Android SDK found」"
        }

        // 重复的 platform-tools 目录必须清掉（真机取证）。
        // `sdkmanager --install "platform-tools"` 在目标目录已存在时会写成 `platform-tools-<n>`
        // （设备上实际留下了 `platform-tools-2/`），于是 SDK 根下出现两个**同名包**，AGP 每次构建都会警告：
        //   Observed package id 'platform-tools' in inconsistent location
        //     '/data/.../android-sdk/platform-tools-2' (Expected '/data/.../android-sdk/platform-tools')
        // 严重时 AGP 直接判 SDK 损坏。保留规范目录 `platform-tools/`，删掉重复项；
        // 若只有重复项而没有规范目录，则把它改名成规范名（不丢组件）。
        val canonical = File(sdk, "platform-tools")
        val duplicates = sdk.listFiles { f -> f.isDirectory && f.name.startsWith("platform-tools-") }.orEmpty()
        if (duplicates.isNotEmpty()) {
            if (!canonical.isDirectory) {
                val first = duplicates.first()
                if (runCatching { first.renameTo(canonical) }.getOrDefault(false)) {
                    notes += "已将重复的 ${first.name}/ 恢复为规范目录 platform-tools/"
                }
            } else {
                for (d in duplicates) {
                    if (runCatching { d.deleteRecursively() }.getOrDefault(false)) {
                        notes += "已清理重复的 SDK 组件目录 ${d.name}/（AGP 会因同名包位置不一致判 SDK 损坏）"
                    }
                }
            }
        }
        return notes
    }

    /**
     * SDK 完整性诊断：缺什么、会导致什么、怎么补。只报告，不阻塞构建。
     */
    fun diagnose(context: Context): List<String> {
        val sdk = File(Environment.androidSdkRoot(context))
        if (!sdk.isDirectory) {
            return listOf("Android SDK 目录不存在（${sdk.absolutePath}）：请在「设置 → 工具链」安装 Android SDK")
        }
        val warnings = mutableListOf<String>()

        val buildTools = File(sdk, "build-tools")
        val buildToolsVersions = buildTools.listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty()
        if (buildToolsVersions.isEmpty()) {
            warnings += "Android SDK 缺少 build-tools（AGP/Flutter 编译资源需要其中的 aapt2）——" +
                "构建会在资源编译阶段失败。补装：sdkmanager \"build-tools;34.0.0\""
        }
        if (!File(sdk, "platform-tools").isDirectory) {
            warnings += "Android SDK 缺少 platform-tools（adb，`flutter run`/安装到设备需要）——" +
                "补装：sdkmanager \"platform-tools\""
        }
        if (!File(sdk, "platforms").isDirectory) {
            warnings += "Android SDK 缺少 platforms（android-34）——补装：sdkmanager \"platforms;android-34\""
        }
        return warnings
    }
}
