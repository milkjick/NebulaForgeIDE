package com.nebulaforge.app.build

import android.content.Context
import com.nebulaforge.core.projectmodel.TaskDefinition
import java.io.File

/**
 * 「构建方式」弹窗的选项模型 —— 用户需求：**编译构建 APK 时先弹窗，自己选编什么、编哪一部分、怎么编**。
 *
 * 之前构建面板只有一个 ▶ 按钮，编出来的东西完全由任务清单决定（assembleDebug / assembleRelease 二选一），
 * 想编个 AAB、想只编某个模块、想「只过一遍编译不出包」、想先 clean 再编、想离线编，都做不到。
 *
 * 这里把「编什么」拆成三个正交维度，任意组合：
 *  - [goal]              产物/动作：全量包(release) / 测试包(debug) / AAB / **只编译不打包** / 装到设备 / 单元测试 / Lint / 清理
 *  - [module]            编译范围：整个工程，或 `:app`、`:feature-x` 这样的单个模块（＝「只编译一部分」）
 *  - 附加动作：先 clean、离线、跳过测试、**单线程**（真机内存紧张时防 OOM 被杀）
 *
 * 生成的命令行复用 [TaskDefinition.gradleInvocation]（同一套 `sh ./gradlew` + 退出码 126/127 兜底），
 * 所以弹窗里编出来的命令和任务清单里的命令语义完全一致。
 *
 * 两个容易被忽略但真机上一定会踩的坑，这里都处理了：
 *  1. **模块类型不同，任务名不同**。`compileDebugSources`/`assembleDebug` 只有 AGP 模块才有；
 *     对着 `:core`（java-library）执行 `:core:assembleDebug` 只会得到 “Task 'assembleDebug' not found”。
 *     所以 [taskName] 会按模块实际插件把任务替换成等价任务（`classes`/`assemble`/`test`/`check`）。
 *  2. **并发会把内存吃穿**。真机 proot 下 Gradle 多 worker 构建容易被 OOM Killer SIGKILL（退出码 137），
 *     现象是「构建莫名其妙中断」。所以把 `--max-workers=1` 做成一等选项 [limitedWorkers]。
 */
data class AndroidBuildPlan(
    val goal: Goal = Goal.DEBUG_APK,
    /** 空 = 整个工程；否则是 Gradle 模块路径，如 `:app`。 */
    val module: String = "",
    val cleanFirst: Boolean = false,
    val offline: Boolean = false,
    val skipTests: Boolean = false,
    /** 单线程构建（`--max-workers=1`）：真机内存紧时更稳，代价是慢一点。 */
    val limitedWorkers: Boolean = false
) {

    /** 弹窗「编什么」的分组，按用户关注度排序：先出包，再只编译，最后其它动作。 */
    enum class Section(val title: String, val hint: String) {
        PACKAGE("① 打包产物（出安装包）", "产出 APK/AAB，装机器或上架用"),
        COMPILE("② 只编译（不出安装包）", "只验证能不能编过，跑得最快（Gradle 自动增量）"),
        OTHER("③ 其它动作", "装到本机、跑测试、静态检查、清理")
    }

    enum class Goal(
        val label: String,
        /** AGP 任务名；非 Android 模块会被 [taskName] 换成等价任务。 */
        val task: String,
        val detail: String,
        val section: Section,
        /** 需要哪个构建变体；null = 与变体无关。 */
        val variant: String? = null,
        /** 是否产出可安装的包（用于提示产物路径 / 构建后定位产物）。 */
        val packages: Boolean = false,
        /** 该动作是否真的有「测试任务」可跳过（决定弹窗里 `-x test` 是否可用）。 */
        val runsTests: Boolean = false
    ) {
        RELEASE_APK(
            "全量包（release APK）", "assembleRelease",
            "正式包；没配签名时产物是 app-release-unsigned.apk", Section.PACKAGE, "release", packages = true
        ),
        DEBUG_APK(
            "测试包（debug APK）", "assembleDebug",
            "debug 包，未签名也能直接装到手机", Section.PACKAGE, "debug", packages = true
        ),
        RELEASE_AAB(
            "上架 AAB", "bundleRelease",
            "上架应用市场用的 .aab 产物", Section.PACKAGE, "release", packages = true
        ),
        COMPILE_DEBUG(
            "只编译源码（debug）", "compileDebugSources",
            "只编源码、不出包：改完代码最快验证一遍能不能过编译", Section.COMPILE, "debug"
        ),
        COMPILE_RELEASE(
            "只编译源码（release）", "compileReleaseSources",
            "release 变体只编源码、不出包（能提前暴露 release-only 报错）", Section.COMPILE, "release"
        ),
        INSTALL_DEBUG(
            "编译并装到本机", "installDebug",
            "编好并安装到已连接的设备", Section.OTHER, "debug"
        ),
        UNIT_TEST(
            "单元测试", "testDebugUnitTest",
            "跑 debug 单元测试，报告在 build/reports/tests", Section.OTHER, "debug", runsTests = true
        ),
        LINT(
            "Lint 检查", "lintDebug",
            "静态检查，报告在 build/reports/lint-results-debug.html", Section.OTHER, "debug", runsTests = true
        ),
        CLEAN(
            "只做清理", "clean",
            "删除各模块 build/ 产物", Section.OTHER
        );

        /** 是否是「只编译不打包」这一类（弹窗里会额外解释增量行为）。 */
        val compileOnly: Boolean get() = section == Section.COMPILE
    }

    /** 非 Android（java-library / kotlin-jvm）模块上的等价任务。 */
    private fun genericTask(): String = when (goal) {
        Goal.CLEAN -> "clean"
        Goal.UNIT_TEST -> "test"
        Goal.LINT -> "check"
        Goal.COMPILE_DEBUG, Goal.COMPILE_RELEASE -> "classes"
        Goal.DEBUG_APK, Goal.RELEASE_APK, Goal.RELEASE_AAB, Goal.INSTALL_DEBUG -> "assemble"
    }

    /**
     * 实际下发的 Gradle 任务（**不含** clean 前置）：按模块真实存在的任务来挑，
     * 避免弹窗预览得很漂亮、按下去却 “Task not found”。
     */
    fun taskName(root: File?): String {
        val base = if (root == null) {
            goal.task
        } else {
            val android = if (module.isBlank()) AndroidBuildPlan.isAndroidProject(root)
            else AndroidBuildPlan.isAndroidModule(root, module)
            if (android) goal.task else genericTask()
        }
        return if (module.isBlank()) base else "$module:$base"
    }

    /** Gradle 任务串（含模块前缀、clean 前置任务）。 */
    fun gradleTarget(root: File? = null): String {
        val task = taskName(root)
        val clean = if (cleanFirst && goal != Goal.CLEAN) {
            if (module.isBlank()) "clean " else "$module:clean "
        } else ""
        return clean + task
    }

    /** 附加 flag。 */
    fun flags(): String = buildList {
        if (offline) add("--offline")
        // 只跳过测试任务本身，别把 lint 之类一起 -x 掉；跑单测时更不能跳过。
        if (skipTests && goal.runsTests) {
            add("-x")
            add("test")
        }
        // 真机内存紧：Gradle 并发 worker 会同时开多个编译进程，很容易被 OOM Killer 杀掉（137）。
        if (limitedWorkers) {
            add("--max-workers=1")
            add("-Dorg.gradle.workers.max=1")
        }
    }.joinToString(" ")

    /** 终端会话标题，也是「输出属于哪次构建」的标识。 */
    fun label(): String = buildString {
        append(goal.label)
        if (module.isNotBlank()) append(" · 只编 ").append(module)
        if (cleanFirst && goal != Goal.CLEAN) append(" · 先clean")
        if (offline) append(" · 离线")
        if (skipTests && goal.runsTests) append(" · 跳过测试")
        if (limitedWorkers) append(" · 单线程")
    }

    /** 预期产物（相对工程根），仅用于提示；真正定位产物靠构建事件里的 Artifact。 */
    fun artifactHint(): String? {
        if (!goal.packages) return null
        val dir = if (module.isBlank()) "app" else module.trimStart(':')
        return when (goal) {
            Goal.DEBUG_APK -> "$dir/build/outputs/apk/debug/*.apk"
            Goal.RELEASE_APK -> "$dir/build/outputs/apk/release/*.apk"
            Goal.RELEASE_AAB -> "$dir/build/outputs/bundle/release/*.aab"
            else -> null
        }
    }

    /** 真正下发的 shell 命令行。 */
    fun commandLine(root: File): String {
        val target = listOf(gradleTarget(root), flags()).filter { it.isNotBlank() }.joinToString(" ")
        return TaskDefinition.gradleInvocation(root, target)
    }

    /** 稳定编码，用于按工程持久化（SharedPreferences 一行一个）。 */
    fun encode(): String = listOf(
        goal.name, module, cleanFirst.toString(), offline.toString(), skipTests.toString(),
        limitedWorkers.toString()
    ).joinToString("|")

    companion object {
        val DEFAULT = AndroidBuildPlan()

        data class ModuleInfo(val path: String, val android: Boolean)

        fun decode(value: String?): AndroidBuildPlan {
            if (value.isNullOrBlank()) return DEFAULT
            val parts = value.split("|")
            val goal = runCatching { Goal.valueOf(parts.getOrNull(0) ?: "") }.getOrDefault(Goal.DEBUG_APK)
            return AndroidBuildPlan(
                goal = goal,
                module = parts.getOrNull(1).orEmpty(),
                cleanFirst = parts.getOrNull(2)?.toBoolean() ?: false,
                offline = parts.getOrNull(3)?.toBoolean() ?: false,
                skipTests = parts.getOrNull(4)?.toBoolean() ?: false,
                // 老版本只存了 5 段，第 6 段缺失 → 默认关闭单线程，保持向后兼容。
                limitedWorkers = parts.getOrNull(5)?.toBoolean() ?: false
            )
        }

        /** 是否是需要「构建方式」弹窗的工程（Gradle / AGP）。 */
        fun isGradleProject(root: File): Boolean = listOf(
            "settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "gradlew"
        ).any { File(root, it).exists() }

        /** 模块目录下的第一个构建脚本。 */
        private fun buildScript(root: File, module: String): File? {
            val dir = if (module.isBlank()) root else File(root, module.trimStart(':'))
            return listOf("build.gradle.kts", "build.gradle").map { File(dir, it) }.firstOrNull { it.isFile }
        }

        /**
         * 该模块是不是 Android（AGP）模块。
         *
         * 只看构建脚本里的插件声明，不跑 Gradle：`com.android.application` / `com.android.library`
         * / `android { }` 块任一出现即视为 AGP。真机上跑一次 `gradle tasks` 代价太大，不能为弹窗付这个成本。
         */
        fun isAndroidModule(root: File, module: String): Boolean {
            val text = buildScript(root, module)?.let { runCatching { it.readText() }.getOrDefault("") } ?: return false
            return text.contains("com.android.") || Regex("""(^|\W)android\s*\{""").containsMatchIn(text)
        }

        /**
         * 整个工程能不能按 AGP 的任务来编。
         *
         * 根工程的 build.gradle 通常只是 `plugins { alias(...) apply false }`，判不出 Android；
         * 所以要再看各模块。否则「整个工程 + 只编译」会退化成 `classes`，对 AGP 工程是错的任务。
         */
        fun isAndroidProject(root: File): Boolean =
            isAndroidModule(root, "") || modulesOf(root).any { isAndroidModule(root, it) }

        /**
         * 列出可编译的模块：先解析 `settings.gradle(.kts)` 的 `include(...)`，
         * 失败再退化为「含 build.gradle* 的一级子目录」。返回结果以 `:` 开头，按名字排序。
         */
        fun modulesOf(root: File): List<String> {
            val settings = listOf("settings.gradle.kts", "settings.gradle")
                .map { File(root, it) }
                .firstOrNull { it.isFile }
            val fromInclude = settings?.let { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                Regex("""include\s*\(?\s*(.*)""").findAll(text)
                    .flatMap { m -> Regex("""["'](:[A-Za-z0-9_\-:.]+)["']""").findAll(m.groupValues[1]).map { it.groupValues[1] } }
                    .toList()
            }.orEmpty()
            val found = (fromInclude + root.listFiles().orEmpty()
                .filter { it.isDirectory && File(it, "build.gradle.kts").isFile || it.isDirectory && File(it, "build.gradle").isFile }
                .map { ":" + it.name })
                .distinct()
                .sorted()
            return if (found.isEmpty()) listOf(":app").filter { File(root, "app").isDirectory } else found
        }

        /** 模块 + 类型（弹窗里要显示「Android 模块 / JVM 模块」，并据此算出真正会执行的任务）。 */
        fun moduleInfos(root: File): List<ModuleInfo> =
            modulesOf(root).map { ModuleInfo(it, isAndroidModule(root, it)) }
    }
}

/**
 * 「构建方式」按工程持久化。
 *
 * 和任务选择（`workspace/state.json → buildTaskByProject`）同样的理由：切文件/切工程回来
 * 不该把用户刚选好的构建方式重置掉。这里只存一个短字符串，用 SharedPreferences 足够，
 * 不必再动工作区 schema。
 */
class BuildPlanStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nebula_build_plan", Context.MODE_PRIVATE)

    fun load(root: File): AndroidBuildPlan = AndroidBuildPlan.decode(prefs.getString(key(root), null))

    fun save(root: File, plan: AndroidBuildPlan) {
        prefs.edit().putString(key(root), plan.encode()).apply()
    }

    private fun key(root: File): String = "plan:" + root.absolutePath
}
