package com.nebulaforge.app.build

import android.content.Context
import java.io.File

/**
 * 构建产物定位：从「这一次构建」的时间窗里，找出真正新产出的 APK。
 *
 * 背景（真机反馈「编译出 APK 之后没有任何安装入口」）：
 * `RealAndroidBuildRunController` 里有一份 `findDebugApk`，但它服务于「构建并实机运行」那条链路；
 * 构建面板走的是 [WorkspaceTaskRunner]（pty 原样输出），那条路径**从不** emit `IdeEvent.Artifact`，
 * 于是面板上的「最近产物」永远停在旧值，也没有任何安装入口。
 *
 * 两个搜索根：
 *  1. 构建归属工程（`TaskRunState.projectRoot`）——Gradle 产物在 `<module>/build/outputs/apk/…`；
 *  2. `<filesDir>/home/.nebulaforge/build`——Flutter 构建走 nb-flutter 外壳，在暂存目录里跑，
 *     产物不在工程目录内（这一步真机上很容易漏）。
 *
 * 只认「本次构建时间窗内新写出」的文件（[sinceMs]），避免把上一次构建的旧 APK 当成这次的成果 ——
 * 用户点「安装」装到的必须是他刚编出来的那一个。
 */
object BuildArtifactLocator {

    /**
     * 目录名黑名单：命中即整棵子树跳过。
     * 其中 `intermediates`、`transforms` 是 Gradle 的中间产物区，文件量极大，
     * 且那里的 `*-unaligned.apk` 并不是可安装产物。
     */
    private val SKIP_DIRS = setOf(
        ".gradle", ".git", ".idea", "node_modules", ".dart_tool", ".cxx",
        "intermediates", "transforms", "caches", "tmp", "kotlin", "symbols"
    )

    private const val MAX_DEPTH = 9

    /** 快路径的扫描深度：`build/` → `app/` → `outputs/` → `flutter-apk/` 就够了。 */
    private const val QUICK_DEPTH = 4

    /**
     * 快路径：[newestApk] 的「秒回」版本，用于构建成功那一刻**立刻**弹安装窗。
     *
     * 只扫「产物标准输出目录」——`<工程>/build`、`<工程>/app/build`，以及 Flutter 镜像里的
     * `<filesDir>/home/.nebulaforge/build/<工程名>/build`，且深度受限（[QUICK_DEPTH]）。
     * 这些目录里除被 [SKIP_DIRS] 排除的中间产物外文件极少，**完全不碰 /storage 上的整棵源码树**，
     * 所以是毫秒级。
     *
     * 真机现象（用户反馈「构建已经成功，安装弹窗却要等几秒才出来」）：根因是弹窗之前先做了
     * 一次全树递归扫描 —— /storage 是 FUSE，逐级读元数据很慢；而且这条扫描还被跑了两遍
     * （构建面板一次、结束回调一次）。快路径没命中（自定义模块名/自定义输出目录）时，
     * 调用方再补一次 [newestApk] 全树扫描即可，代价只在少数非常规工程上出现。
     */
    fun quickApk(context: Context, projectRoot: File?, sinceMs: Long): File? {
        val name = projectRoot?.name.orEmpty()
        val roots = buildList {
            projectRoot?.let { root ->
                File(root, "build").takeIf { it.isDirectory }?.let(::add)
                File(root, "app/build").takeIf { it.isDirectory }?.let(::add)
            }
            // Flutter 走 nb-flutter 外壳时是在应用私有目录的镜像里产出，产物随后才拷回工程。
            if (name.isNotBlank()) {
                File(File(File(context.filesDir, "home"), ".nebulaforge/build/$name"), "build")
                    .takeIf { it.isDirectory }?.let(::add)
            }
        }
        var best: File? = null
        roots.forEach { root ->
            scan(root, 0, sinceMs, QUICK_DEPTH) { f ->
                val current = best
                if (current == null || f.lastModified() > current.lastModified()) best = f
            }
        }
        return best
    }

    /** 本次构建（[sinceMs] 起）产出的最新 APK；没有则返回 null。 */
    fun newestApk(context: Context, projectRoot: File?, sinceMs: Long): File? {
        val roots = buildList {
            projectRoot?.takeIf { it.isDirectory }?.let(::add)
            File(File(context.filesDir, "home"), ".nebulaforge/build").takeIf { it.isDirectory }?.let(::add)
        }
        var best: File? = null
        roots.forEach { root ->
            scan(root, 0, sinceMs) { f ->
                val current = best
                if (current == null || f.lastModified() > current.lastModified()) best = f
            }
        }
        return best
    }

    private fun scan(dir: File, depth: Int, sinceMs: Long, maxDepth: Int = MAX_DEPTH, onApk: (File) -> Unit) {
        if (depth > maxDepth) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name in SKIP_DIRS) continue
                scan(child, depth + 1, sinceMs, maxDepth, onApk)
            } else if (isInstallableApk(child) && child.lastModified() >= sinceMs) {
                onApk(child)
            }
        }
    }

    private fun isInstallableApk(f: File): Boolean =
        f.name.endsWith(".apk", ignoreCase = true) &&
            !f.name.contains("unaligned", ignoreCase = true)
}
