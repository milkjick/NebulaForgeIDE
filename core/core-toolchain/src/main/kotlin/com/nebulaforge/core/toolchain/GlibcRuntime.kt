package com.nebulaforge.core.toolchain

import android.content.Context
import android.content.res.AssetManager
import com.nebulaforge.core.environment.TermuxGuest
import java.io.File

/**
 * 内置 glibc 运行时（aarch64）。
 *
 * ## 为什么需要它（真机取证得到的根因）
 * Flutter SDK 自带的 `bin/cache/dart-sdk/bin/dart` 是 **glibc 版 Linux aarch64 ELF**，
 * 解释器写的是 `/lib/ld-linux-aarch64.so.1`；而本 App 的 guest 用户态是 Termux/bionic，
 * 根目录下既没有 glibc 装载器、也没有 glibc 核心库，于是任何 glibc 程序在这里只能报：
 *
 *  - `.../dart: cannot execute: required file not found`（缺 ELF 解释器）
 *  - `.../dart: error while loading shared libraries: libdl.so.2: cannot open shared object file`（缺库）
 *
 * 这就是「Flutter 组件装了却永远 FAILED」的真正原因 —— 既不是下载失败，也不是架构不对
 * （dart 二进制实测就是 aarch64，SDK 也下载完整）。补上 glibc 运行时后实测：
 * `Flutter 3.47.2 • channel stable` / `Dart SDK version: 3.13.2 ... linux_arm64` 均可正常运行。
 *
 * ## 落盘位置与理由
 *  - `termuxfs/lib64/`：aarch64 glibc 的**默认**库搜索路径就是 `/lib64:/usr/lib64`，
 *    放这里就不必设任何环境变量；
 *  - `termuxfs/lib/ld-linux-aarch64.so.1`：dart 的 ELF 解释器路径写在 `/lib/` 下。
 *
 * ## 为什么不塞进 LD_LIBRARY_PATH
 * bionic 的链接器不搜索 guest 根 `/lib64`，两边天然隔离。反之若把 glibc 目录挂进
 * `LD_LIBRARY_PATH`，bionic 的 `git` 会误加载 glibc 的 `libz.so.1`，再因找不到 glibc 装载器而崩
 * （真机实测：`CANNOT LINK EXECUTABLE "git": library "ld-linux-aarch64.so.1" not found: needed by
 * .../glibc/lib/libz.so.1`）。因此本实现**完全不动环境变量**。
 *
 * 运行时本体内置在 APK assets（约 7 MB，纯本地安装，不依赖任何网络）。
 */
object GlibcRuntime {

    private const val ASSET_ROOT = "glibc-aarch64"
    private const val LOADER = "ld-linux-aarch64.so.1"

    /** guest 根目录：proot 的 `-r`，对应宿主 `files/termuxfs`。 */
    private fun root(context: Context): File = TermuxGuest.rootfs(context)

    fun lib64Dir(context: Context): File = File(root(context), "lib64")

    fun isInstalled(context: Context): Boolean =
        File(lib64Dir(context), LOADER).let { it.isFile && it.canExecute() } &&
            File(lib64Dir(context), "libc.so.6").isFile

    /**
     * 幂等安装内置 glibc 运行时（纯本地）。
     *
     * 已安装则直接返回；任一文件缺失（例如被清理）会补齐。返回是否可用。
     */
    @Synchronized
    fun ensure(context: Context): Boolean = runCatching {
        if (isInstalled(context)) return true
        val lib64 = lib64Dir(context).apply { mkdirs() }
        val lib = File(root(context), "lib").apply { mkdirs() }
        val assets = context.assets
        // 装载器：/lib64 与 /lib 各一份（dart 的 ELF 解释器路径是 /lib/<loader>）
        copyAsset(assets, "$ASSET_ROOT/$LOADER", File(lib64, LOADER), exec = true)
        copyAsset(assets, "$ASSET_ROOT/$LOADER", File(lib, LOADER), exec = true)
        // 核心库：aarch64 glibc 的默认搜索目录是 /lib64
        assets.list("$ASSET_ROOT/lib").orEmpty().sorted().forEach { name ->
            copyAsset(assets, "$ASSET_ROOT/lib/$name", File(lib64, name), exec = false)
        }
        isInstalled(context)
    }.getOrDefault(false)

    /** 一句话状态，用于工具链卡片/安装日志。 */
    fun describe(context: Context): String =
        if (isInstalled(context)) "内置 glibc 运行时已就位（${lib64Dir(context).absolutePath}）"
        else "内置 glibc 运行时未安装"

    private fun copyAsset(assets: AssetManager, assetPath: String, dest: File, exec: Boolean) {
        assets.open(assetPath).use { input ->
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            tmp.outputStream().use { input.copyTo(it) }
            if (tmp.length() <= 0L) {
                tmp.delete()
                error("内置资源为空：$assetPath")
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
        dest.setReadable(true, false)
        dest.setExecutable(exec, false)
    }
}
