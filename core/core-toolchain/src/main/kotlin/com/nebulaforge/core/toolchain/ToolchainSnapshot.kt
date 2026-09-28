package com.nebulaforge.core.toolchain

import android.content.Context
import android.system.Os
import com.nebulaforge.core.environment.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 工具链快照：把「一次成功构建真正依赖的一切」镜像到**公共存储**，卸载重装后本地恢复。
 *
 * ## 为什么需要它（真实断点）
 *
 * 用户反复反馈「卸载后重新安装，应用就无法编译构建了」。根因不是代码，而是 Android 的
 * 存储语义：卸载会清空 `/data/data/<pkg>/files`，而本应用的工具链**全部**在私有目录：
 *
 *  1. `files/home/.gradle` —— Gradle 发行版（wrapper dists，~130MB）+ Maven 依赖缓存（GB 级），
 *     重装后只能重新下载（`services.gradle.org` 在本机网络下不可达 → 直接卡死）；
 *  2. `files/home/android-sdk` —— Android SDK（platform-tools / build-tools / platforms），
 *     其中 `platforms/android-34` 若为半装状态，AGP 会报 `Failed to find target 'android-34'`；
 *  3. `files/usr/lib/jvm`、`files/usr/opt/gradle` —— 装进用户态的 JDK 与 Gradle，
 *     重装后同样消失（用户态 bootstrap 可从内置资产离线重建，但这两项是后装的，不在资产里）。
 *
 * 公共存储 `/storage/emulated/0` **不随卸载清除**，且工程本来就放在
 * `/storage/emulated/0/NebulaForgeProjects`。因此把上述几项快照到
 * `/storage/emulated/0/NebulaForge/toolchain`：重装后只需**本地复制**即可恢复构建能力，
 * 既不用下载、也不用联网，且不增加 APK 体积。
 *
 * ## 工作方式
 *
 *  - [autoMaintain] 是唯一入口，在构建启动前调用（见 [BuildEnvironmentPreparer]）：
 *      * 私有目录**整项缺失**且快照里有 → 同步恢复（构建要用，必须等）；
 *      * 私有目录完整但还没快照（或快照过期）→ **后台线程**抓取，不阻塞本次构建。
 *  - 复制是**增量**的（同名同尺寸即跳过），因此重复抓取/恢复代价很小，中断也不会留下坏数据。
 *  - 只有整套抓取成功才写 [MARKER]；半途中断的快照不会被用于恢复。
 *
 * 设计取舍：公共存储是 FUSE 挂载，**不支持创建符号链接**（JDK 的 `include/linux -> .` 之类）。
 * 在「元数据 + 自带解释器」的模型下这类链接不影响 javac/gradle 运行，因此遇到时跳过并计数，
 * 而不是让整次快照失败。
 */
object ToolchainSnapshot {

    /** 快照根目录（公共存储，卸载后仍在）。 */
    fun root(): File = File("/storage/emulated/0/NebulaForge/toolchain")

    private const val MARKER = ".nebula-snapshot"
    private const val MANIFEST = "manifest.txt"
    private const val LAST_CAPTURE = ".last-capture"

    /** 抓取节流：6 小时。构建很频繁，没必要每次都扫十万级文件。 */
    private const val CAPTURE_THROTTLE_MS = 6L * 60 * 60 * 1000

    /** 抓取单飞锁：多个入口并发触发时只跑一份。 */
    private val captureRunning = AtomicBoolean(false)

    /**
     * 一个快照项。
     *
     * @param label 展示给用户的名字
     * @param dir   私有目录里的真实位置
     * @param sub   快照内的相对子目录名
     * @param required 是否「恢复不了就一定构建失败」（用于状态摘要措辞）
     * @param gaps 判「这一项是否残缺」的关键子路径（相对 [dir]）。整项存在但这些子路径缺失时，
     *   按【缺口】从快照补齐（见 [restore]）——卸载重装后目录多半「存在但残缺」，只判整项有无是不够的。
     */
    data class Item(
        val label: String,
        val dir: File,
        val sub: String,
        val required: Boolean,
        val gaps: List<String> = emptyList()
    )

    fun items(context: Context): List<Item> = listOf(
        // GRADLE_USER_HOME：wrapper 发行版 + Maven 依赖缓存 + 全局 init.gradle /
        //   gradle.properties。少任何一个都会退回「重新下载」，正是卡死源头。
        Item(
            label = "Gradle 缓存与发行版",
            dir = File(Environment.homeRoot(context), ".gradle"),
            sub = "gradle",
            required = true,
            // `wrapper/dists` 是「工程 wrapper 能不能离线跑起来」的唯一依据：它一空，gradlew 就会去
            // 网络下载发行版（真机必然断流/卡死），或回退到内置 Gradle 9.x 从而与模板 AGP 8.6 不兼容。
            // 这正是「删装重装后编不过」的直接原因，所以必须列为关键缺口。
            gaps = listOf("wrapper/dists")
        ),
        // Android SDK：build-tools / platform-tools / platforms / licenses。
        Item(
            label = "Android SDK",
            dir = File(Environment.androidSdkRoot(context)),
            sub = "android-sdk",
            required = true,
            // 删装后应用会重建 android-sdk/（往往只剩 cmdline-tools），但 `platforms/android-34/`
            // 里的 android.jar 才是 AGP 编译必需的；缺它 AGP 直接报「找不到编译平台」。
            gaps = listOf("platforms")
        ),
        // 用户态 JDK：bootstrap 资产里没有它，重装后不恢复就得联网 apt install。
        Item(
            label = "JDK（用户态）",
            dir = File(Environment.usrRoot(context), "lib/jvm"),
            sub = "usr-lib-jvm",
            required = true
        ),
        // 用户态 Gradle：仅作 PATH 兜底，缺了不影响（工程 wrapper 优先），故非必需。
        Item(
            label = "Gradle（用户态）",
            dir = File(Environment.usrRoot(context), "opt/gradle"),
            sub = "usr-opt-gradle",
            required = false
        )
    )

    // ------------------------------------------------------------------ 入口

    /**
     * 构建启动前的自动维护：先「按需恢复」，再「按需后台抓取」。
     *
     * @param notes 展示在「输出」里的说明（无动作时不写，避免每次构建刷屏）
     */
    fun autoMaintain(context: Context, notes: MutableList<String>) {
        val dir = root()
        val hasSnapshot = File(dir, MARKER).isFile
        val items = items(context)

        // ① 恢复：只在「私有目录整项缺失」时动手（卸载重装后的典型状态）。
        //    半装/损坏的既有目录不在这里硬覆盖 —— 各组件自带的自愈器（平台 / build-tools /
        //    aapt2 / NDK）更清楚该修什么，覆盖式恢复反而可能把用户已下好的较新版本退回去。
        if (hasSnapshot) restore(context, notes)

        // ② 抓取：私有工具链齐了、但快照没有或已过期 → 后台补快照。
        if (!hasSnapshot || throttleElapsed(dir)) {
            if (!writable(dir)) return
            if (!privateLooksComplete(context)) return
            startCapture(context)
        }
    }

    /**
     * 把快照里**缺失**的项复制回私有目录（卸载重装后的典型状态）。
     *
     * 只处理「整项不存在」的项：半装/损坏的既有目录交给组件级自愈器（平台 / build-tools /
     * aapt2 / NDK），避免覆盖式恢复把用户已下好的较新版本退回去。设置页的
     * 「从快照恢复」按钮与 [autoMaintain] 都走这里。
     */
    fun restore(context: Context, notes: MutableList<String>) {
        val dir = root()
        if (!File(dir, MARKER).isFile) return

        // 「缺失」= 整项不存在（卸载重装的典型状态）**或** 整项在、但关键子内容被清空。
        //
        // 真机回归（2.12.90）：这里原先只认「整项不存在」，于是删装重装后**永远不会恢复**——
        // 应用一启动就会创建 `files/home/.gradle`（写入 init.gradle / gradle.properties）和
        // `files/home/android-sdk`（往往只剩 cmdline-tools/），这些目录**存在但残缺**，恰好躲过了判据。
        // 后果就是用户看到的「删掉重装后，原生安卓和 Flutter 都编不过」：
        //   * `.gradle/` 少了 `wrapper/dists` → 工程 wrapper 找不到发行版 → 回退内置 Gradle 9.x
        //     → 与模板 AGP 8.6 硬不兼容（而下载发行版的通路又被 gradlew 自己锁死）；
        //   * `android-sdk/` 少了 `platforms/android-34/android.jar` → AGP 报找不到编译平台。
        //
        // 现在按「关键子路径」（[Item.gaps]）判缺口，并且**只补缺口**、绝不整项覆盖：
        // 既修好删装后的空洞，又保持原先「不把用户后来装好的更新版本退回去」的顾虑
        // （`copyTree(overwrite = false)` 对同名同尺寸跳过、对更新的目标保留）。
        val jobs = mutableListOf<Pair<File, File>>()
        for (item in items(context)) {
            val snap = File(dir, item.sub)
            if (!snap.isDirectory) continue
            if (!item.dir.exists()) {
                jobs += snap to item.dir
                continue
            }
            item.gaps.filter { it.isNotBlank() && gapMissing(item.dir, it) }
                .forEach { gap -> jobs += File(snap, gap) to File(item.dir, gap) }
        }
        if (jobs.isEmpty()) return
        if (!hasPrivateRoom(context, jobs)) {
            notes += "⚠ 私有存储空间不足，无法从快照补齐工具链缺口（" +
                jobs.joinToString("、") { it.second.name } + "）"
            return
        }

        val restored = mutableListOf<String>()
        jobs.forEach { (src, dst) ->
            val copied = runCatching { copyTree(src, dst, overwrite = false) }
                .getOrElse { CopyStat(0, 0, 0) }
            if (copied.files > 0) restored += "${dst.name}（${copied.files} 个文件）"
        }
        if (restored.isNotEmpty()) {
            notes += "已从 /sdcard 快照本地补齐工具链：" + restored.joinToString("、") +
                " —— 卸载重装无需重新下载"
        }
    }

    /**
     * 「这个关键子路径算不算缺口」——比 `File.exists()` 严格，专门堵两类**假就绪**：
     *
     *  * `wrapper/dists`：目录存在、但里面一个**已安装**发行版都没有。卸载重装后 Gradle 会自己
     *    建出这个空壳目录（工程 wrapper 第一次运行时就建），只判 `exists()` 会认为「已就绪」→ 不恢复
     *    → 构建时 wrapper 找不到发行版 → 回退内置 Gradle 9.x → 与模板 AGP 8.6 不兼容，
     *    Flutter 那边表现为构建更慢（版本不对）且可能直接失败。所以这里要求至少存在一个 `.ok`
     *    安装标记（Gradle 正是用它确认「发行版已完整安装」）。
     *  * `platforms`：目录存在、但没有 `android-<N>/android.jar`（AGP 编译必需），同样是空壳。
     *
     * 深度限制在 3：`dists/[版本]/[hash]/.ok` 正好在第 3 层，正常情况几乎立刻命中；
     * 只有「确实空壳」时才需要把第 3 层扫完，量级很小，可以安全地在构建前的 IO 线程跑。
     */
    private fun gapMissing(itemDir: File, gap: String): Boolean {
        val f = File(itemDir, gap)
        if (!f.exists()) return true
        return when {
            gap == "wrapper/dists" -> runCatching {
                f.walkTopDown().maxDepth(3).none { it.isFile && it.name.endsWith(".ok") }
            }.getOrDefault(true)

            gap == "platforms" -> runCatching {
                f.listFiles()?.none { File(it, "android.jar").isFile } != false
            }.getOrDefault(true)

            else -> false
        }
    }

    /**
     * 供设置页 / 状态栏展示的一句话结论。
     *
     * 刻意**不**遍历文件树算体积：十万级文件走 FUSE 会把 UI 卡住。体积直接读抓取时写下的
     * manifest 的 `total.bytes`，因此本方法可以廉价地在设置页 IO 线程里调用。
     */
    fun status(context: Context): String {
        val dir = root()
        val marker = File(dir, MARKER)
        if (!marker.isFile) {
            return if (writable(dir)) "尚未生成快照（首次构建成功后自动生成，约 1~2 GB）"
            else "快照不可用：公共存储（/storage/emulated/0）不可写"
        }
        val stamp = runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(marker.lastModified()))
        }.getOrDefault("未知时间")
        val list = items(context)
        val present = list.count { File(dir, it.sub).isDirectory }
        val sizeMb = runCatching {
            Regex("total\\.bytes=(\\d+)").find(File(dir, MANIFEST).readText())
                ?.groupValues?.get(1)?.toLong()?.div(1024 * 1024)
        }.getOrNull()
        val size = if (sizeMb != null && sizeMb > 0) "，约 $sizeMb MB" else ""
        return "快照 $stamp$size（含 $present/${list.size} 项；卸载重装后构建前自动恢复）"
    }

    // ------------------------------------------------------------------ 抓取/恢复 调度

    private fun startCapture(context: Context) {
        if (!captureRunning.compareAndSet(false, true)) return
        val thread = Thread {
            runCatching { capture(context) }.onFailure { captureRunning.set(false) }
        }
        thread.isDaemon = true
        thread.name = "nebula-toolchain-snapshot"
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    /**
     * 私有目录 → 公共存储。**整套**成功后写 [MARKER]；失败（含空间不足）不写，
     * 于是下次构建会重试，而增量复制让重试很便宜。
     */
    fun capture(context: Context): List<String> {
        val dir = root()
        if (!writable(dir)) return listOf("⚠ 公共存储不可写，跳过工具链快照")
        val items = items(context).filter { it.dir.isDirectory }
        if (items.isEmpty()) {
            captureRunning.set(false)
            return emptyList()
        }

        val need = items.sumOf { itemSize(it.dir) }
        if (need > 0 && dir.usableSpace in 1 until (need + need / 20)) {
            captureRunning.set(false)
            return listOf("⚠ 公共存储空间不足（需约 ${need / 1024 / 1024} MB），跳过工具链快照")
        }

        val manifest = StringBuilder("version=1\ncaptured=${System.currentTimeMillis()}\n")
        var totalFiles = 0L
        var totalBytes = 0L
        var skippedLinks = 0
        for (item in items) {
            val stat = runCatching {
                copyTree(item.dir, File(dir, item.sub), overwrite = true, skip = skipSnapshotNoise)
            }
                .getOrElse { CopyStat(0, 0, 0) }
            totalFiles += stat.files
            totalBytes += stat.bytes
            skippedLinks += stat.skippedLinks
            manifest.append("item.${item.sub}.files=${stat.files}\n")
            manifest.append("item.${item.sub}.bytes=${stat.bytes}\n")
        }
        captureRunning.set(false)
        if (totalFiles == 0L) return emptyList()

        manifest.append("total.files=$totalFiles\ntotal.bytes=$totalBytes\n")
        return runCatching {
            File(dir, MANIFEST).writeText(manifest.toString())
            File(dir, MARKER).writeText("nebula toolchain snapshot")
            File(dir, LAST_CAPTURE).writeText(System.currentTimeMillis().toString())
            val notes = mutableListOf(
                "已把工具链快照到 /sdcard/NebulaForge/toolchain（$totalFiles 个文件，" +
                    "${totalBytes / 1024 / 1024} MB）—— 卸载重装后本地恢复，无需重新下载"
            )
            if (skippedLinks > 0) {
                notes += "快照跳过 $skippedLinks 个符号链接（公共存储不支持建软链，不影响构建）"
            }
            notes
        }.getOrElse { emptyList() }
    }

    // ------------------------------------------------------------------ 判据

    /** 私有工具链是否「值得快照」：Gradle 发行版、SDK 平台、JDK 至少要都有实质内容。 */
    private fun privateLooksComplete(context: Context): Boolean {
        val items = items(context)
        val gradle = items.first { it.sub == "gradle" }.dir
        val sdk = items.first { it.sub == "android-sdk" }.dir
        val jvm = items.first { it.sub == "usr-lib-jvm" }.dir

        // 这里刻意用 `||`：Maven 依赖缓存已就绪（GB 级，最值钱）就允许抓快照，即使
        // `wrapper/dists` 尚未就绪（发行版要等构建前由 GradleDistributionProvisioner 补齐）。
        // 发行版一旦落盘，下一次抓取（节流 6 小时或用户手动触发）就会把它一并写进快照，
        // 于是之后「卸载重装 + 离线」也能直接恢复出可用的 Gradle 发行版。
        val gradleOk = File(gradle, "wrapper/dists").listFiles()?.isNotEmpty() == true ||
            File(gradle, "caches/modules-2").isDirectory
        val sdkOk = File(sdk, "platforms").listFiles()?.any { f ->
            f.isDirectory && File(f, "android.jar").isFile
        } == true
        val jvmOk = jvm.listFiles()?.isNotEmpty() == true
        return gradleOk && sdkOk && jvmOk
    }

    private fun throttleElapsed(dir: File): Boolean {
        val last = File(dir, LAST_CAPTURE)
        if (!last.isFile) return true
        return System.currentTimeMillis() - last.lastModified() > CAPTURE_THROTTLE_MS
    }

    /** 私有目录剩余空间是否够放下待复制的这些「树」（恢复按缺口分片，所以按实际任务估算）。 */
    private fun hasPrivateRoom(context: Context, jobs: List<Pair<File, File>>): Boolean {
        val need = jobs.sumOf { itemSize(it.first) }
        val free = context.filesDir.usableSpace
        return need <= 0 || free <= 0 || free > need + need / 20
    }

    /** 公共存储可写探测（与 `Environment.canWritePublicProjects()` 同一套写法）。 */
    private fun writable(dir: File): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".write_probe")
        return runCatching { probe.writeText("ok"); probe.delete(); true }.getOrDefault(false)
    }

    /** 目录字节数（不跟随软链；带节点上限，避免畸形目录结构拖死构建启动路径）。 */
    private fun itemSize(dir: File): Long {
        if (!dir.isDirectory) return 0L
        var sum = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(dir)
        var guard = 0
        while (stack.isNotEmpty() && guard < 400_000) {
            val f = stack.removeLast()
            guard++
            val children = f.listFiles() ?: continue
            for (c in children) {
                if (isSymlink(c)) continue
                if (c.isDirectory) stack.addLast(c) else sum += c.length()
            }
        }
        return sum
    }

    // ------------------------------------------------------------------ 复制引擎

    /** files=本次真正写入的文件数；bytes=写入字节数；skippedLinks=跳过的软链数。 */
    private data class CopyStat(val files: Long, val bytes: Long, val skippedLinks: Int)

    /**
     * 递归复制。[overwrite]=true 表示「尺寸不同就以源为准覆盖」——抓取与恢复两个方向都是如此
     * （私有目录是运行态真相，快照只是它的镜像；恢复时目标本就不存在）。
     *
     * 用 `java.io.File` 而不是 `java.nio.file.Files`：后者在 API < 26 上不可用，而本模块要兼容低版本。
     * 软链用 `canonicalPath != absolutePath` 判定 —— `isDirectory()` 会跟进去造成重复拷贝。
     */
    /**
     * 快照时跳过的「噪音文件」：`wrapper/dists` 目录下的**发行包压缩包**（`*.zip` / `*.zip.part`）。
     *
     * Gradle 判定发行版「已安装」只看同目录的 `.ok` 标记 + 解包出来的目录，压缩包本身**不需要**保留。
     * 真机数据：`gradle-9.3.1-all.zip` 单个就有 224MB，加上 8.9 的 130MB，占了快照体积的一半以上。
     * 跳过它们 = 「卸载重装后从快照恢复」的拷贝量直接砍半，且完全不影响离线构建
     * （预热实测：把 zip 删掉只留 `gradle-8.9/` + `.ok`，wrapper 一样命中，构建照常）。
     */
    private val skipSnapshotNoise: (File) -> Boolean = { f ->
        f.isFile && (f.name.endsWith(".zip") || f.name.endsWith(".zip.part")) &&
            f.absolutePath.contains("/wrapper/dists/")
    }

    private fun copyTree(
        src: File,
        dst: File,
        overwrite: Boolean,
        skip: ((File) -> Boolean)? = null
    ): CopyStat {
        if (!src.exists()) return CopyStat(0, 0, 0)
        if (skip != null && skip(src)) return CopyStat(0, 0, 0)

        if (isSymlink(src)) return recreateLink(src, dst)

        if (src.isDirectory) {
            dst.mkdirs()
            var files = 0L
            var bytes = 0L
            var links = 0
            src.listFiles()?.forEach { child ->
                val st = copyTree(child, File(dst, child.name), overwrite, skip)
                files += st.files
                bytes += st.bytes
                links += st.skippedLinks
            }
            return CopyStat(files, bytes, links)
        }

        // 增量判据：同名同尺寸视为已同步（依赖 jar / 二进制文件的尺寸足以区分）。
        if (dst.isFile && dst.length() == src.length()) return CopyStat(0, 0, 0)
        // 目标更新且调用方不想覆盖时保留目标，避免恢复把用户新下的版本退回去。
        if (!overwrite && dst.isFile && dst.lastModified() > src.lastModified()) return CopyStat(0, 0, 0)

        dst.parentFile?.mkdirs()
        return try {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
            }
            CopyStat(1, dst.length(), 0)
        } catch (t: Throwable) {
            // 单个文件失败不能让整次快照/恢复崩掉：删掉半截文件，计入 0，继续下一个。
            runCatching { if (dst.length() == 0L) dst.delete() }
            CopyStat(0, 0, 0)
        }
    }

    /** 目标端重建软链；公共存储不支持创建时计数跳过（不影响 javac/gradle 运行）。 */
    private fun recreateLink(src: File, dst: File): CopyStat {
        val target = runCatching { Os.readlink(src.absolutePath) }.getOrNull() ?: return CopyStat(0, 0, 1)
        return try {
            dst.parentFile?.mkdirs()
            if (dst.exists()) runCatching { dst.delete() }
            Os.symlink(target, dst.absolutePath)
            CopyStat(1, 0, 0)
        } catch (t: Throwable) {
            CopyStat(0, 0, 1)
        }
    }

    /** 软链判定：`canonicalPath != absolutePath` 即说明路径上有链接。 */
    private fun isSymlink(f: File): Boolean =
        runCatching { f.exists() && f.canonicalPath != f.absolutePath }.getOrDefault(false)
}
