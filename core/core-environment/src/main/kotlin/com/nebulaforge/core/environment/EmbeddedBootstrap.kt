package com.nebulaforge.core.environment

import java.io.File

/**
 * APK 内置 Termux bootstrap 元数据（开发方案 1.4 / 3.x：用户态随包分发，首启解压即用）。
 *
 * 背景：早期实现依赖联网下载 bootstrap，移动网络下 GitHub release 常见 302 中断与
 * 限流（403），用户首次启动要等几十 MB 下载且经常失败。现在把 arm64 用户态压缩包
 * 直接打进 APK 的 assets，首启只需本地解压 + 校验，离线可用。
 *
 * 打包约束（与 [BootstrapInstaller] 的解压逻辑严格对应）：
 *   - zip 为 Termux 官方 bootstrap 布局：条目位于根（`bin/`、`lib/`、`SYMLINKS.txt`…），
 *     **不含** `usr/` 前缀，解压目标即 `Environment.usrRoot(context)`。
 *   - 官方包内不含 `bin/sh` 实体文件，`bin/sh` 由 `SYMLINKS.txt` 中的 `dash←./bin/sh` 重建。
 *   - 因此 [sha256] 与 [sizeBytes] 是唯一可信来源，必须与真实文件一致；
 *     二者由 `tools/bootstrap/embed_bootstrap.sh` 自动生成，避免手写漂移。
 *
 * 仅内置 aarch64（当前设备与绝大多数真机为 arm64-v8a）；其他架构仍走网络下载回退。
 */
object EmbeddedBootstrap {

    /** APK 内的 bootstrap 资源描述。 */
    data class Asset(
        /** 与 [BootstrapInstaller.detectArchitecture] 的返回值一致的架构标识 */
        val arch: String,
        /** APK assets 内相对路径 */
        val assetPath: String,
        val sizeBytes: Long,
        val sha256: String,
        /** Termux 官方 bootstrap release tag，写入版本文件供工具链状态展示 */
        val version: String
    )

    /** 该资产对应的 bootstrap 版本（Termux 官方 release tag） */
    const val VERSION: String = "2026.09.20-r1+apt.android-7"

    /** 已内置的 bootstrap 资产清单。 */
    val assets: List<Asset> = listOf(
        Asset(
            arch = "aarch64",
            assetPath = "bootstrap/bootstrap-aarch64.zip",
            sizeBytes = 32_806_628L,
            sha256 = "65ba578133ea2f4e5cc07234568815397cf9e1236b5da8c06ce6753cf036cc69",
            version = VERSION
        )
    )

    fun forArch(arch: String): Asset? = assets.firstOrNull { it.arch == arch }

    val embeddedArches: List<String> get() = assets.map { it.arch }

    /**
     * ELF 机器码（e_machine）期望值，用于确认内置包与设备架构匹配。
     * 12 位小端整数：AArch64=183(0xB7)、ARM=40(0x28)、x86_64=62(0x3E)、i386=3(0x03)。
     */
    fun expectedElfMachine(arch: String): Int? = when (arch) {
        "aarch64" -> 183
        "arm" -> 40
        "x86_64" -> 62
        "i686" -> 3
        else -> null
    }

    /**
     * 校验已落盘的 zip 与元数据一致：先比尺寸（廉价），再比 SHA-256（权威）。
     * 注：assets 的真实存在性必须通过 `AssetManager` 探测（APK 内 assets 不在 ClassLoader 资源空间），
     * 由 [BootstrapInstaller] 负责。
     * @return 不一致的原因，null 表示一致。
     */
    fun mismatchReason(file: File, asset: Asset): String? = when {
        !file.isFile -> "内置 bootstrap 解包失败：文件不存在"
        file.length() != asset.sizeBytes ->
            "内置 bootstrap 尺寸不符（期望 ${asset.sizeBytes}，实际 ${file.length()}）"
        else -> null
    }
}
