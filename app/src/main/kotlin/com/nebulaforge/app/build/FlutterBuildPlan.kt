package com.nebulaforge.app.build

import android.content.Context
import com.nebulaforge.core.projectmodel.TaskDefinition
import java.io.File

/**
 * 「构建方式」弹窗（Flutter 版）—— 和 [AndroidBuildPlan] 完全同构的三档选择。
 *
 * 用户反馈原话：「flutter 和其它 apk 模板无法像原生安卓那样选择编译构建」。
 * 根因是 Flutter 工程**不是 Gradle 工程**：没有 `settings.gradle(.kts)` / `gradlew`，
 * 于是 `AndroidBuildPlan.isGradleProject(root)` 为 false，顶栏「构建」直接跳过弹窗，
 * 用户只能吃到任务清单里写死的 `flutter build apk --debug` / `--release` 二选一。
 *
 * 这里把它补成和安卓一致的三档：
 *  ① 编什么产物 —— 测试包 / 全量包 / 上架 AAB / Web 产物；
 *  ② 只编一部分 —— Flutter 的「一部分」＝少编 ABI（真机自己只有一种 ABI，
 *     编全 ABI 是 3 倍时间）：只编 arm64，或按 ABI 拆成多个包；
 *  ③ 其它动作 —— analyze（最快的「只编译不打包」）/ test / pub get / clean；
 * 外加附加项：先 clean、详细输出（-v）。
 *
 * 命令一律由 [TaskDefinition.flutterInvocation] 生成，与任务清单、运行入口**同源**，
 * 所以「模板工程缺 `android/` 原生骨架」的自愈前缀在弹窗里同样生效（否则选了也白选）。
 */
data class FlutterBuildPlan(
    val goal: Goal = Goal.DEBUG_APK,
    /** 先 `flutter clean` 再编（依赖缓存被搞坏时的救命选项）。 */
    val cleanFirst: Boolean = false,
    /** `-v`：verbose，排查构建失败时用。 */
    val verbose: Boolean = false
) {

    /** 弹窗分组，与 [AndroidBuildPlan.Section] 一一对应，用户心智一致。 */
    enum class Section(val title: String, val hint: String) {
        PACKAGE("① 打包产物（出安装包）", "产出 APK/AAB/Web，装机器或上架用"),
        PARTIAL("② 只编一部分（少编、快跑）", "Flutter 的「一部分」＝少编 ABI：真机只装自己那一种，编全 ABI 要 3 倍时间"),
        OTHER("③ 其它动作", "静态检查、跑测试、拉依赖、清理")
    }

    enum class Goal(
        val label: String,
        /** flutter CLI 的子命令与参数（不含 flags、不含 clean 前置）。 */
        val args: String,
        val detail: String,
        val section: Section,
        /** 是否需要 `android/` 原生骨架（需要则套平台自愈前缀）。 */
        val needsAndroidPlatform: Boolean = false,
        /** 是否产出可安装/可分发的产物（用于提示产物路径）。 */
        val packages: Boolean = false
    ) {
        DEBUG_APK(
            "测试包（debug APK）", "build apk --debug",
            "debug 包，未签名也能直接装到手机", Section.PACKAGE,
            needsAndroidPlatform = true, packages = true
        ),
        RELEASE_APK(
            "全量包（release APK）", "build apk --release",
            "正式包；Flutter 会用 debug 签名兜底，能直接装", Section.PACKAGE,
            needsAndroidPlatform = true, packages = true
        ),
        RELEASE_AAB(
            "上架 AAB", "build appbundle --release",
            "上架应用市场用的 .aab 产物（不能直接安装）", Section.PACKAGE,
            needsAndroidPlatform = true, packages = true
        ),
        WEB(
            "Web 产物（release）", "build web --release",
            "产出 build/web/，可直接丢到静态服务器", Section.PACKAGE
        ),
        ARM64_ONLY(
            "只编 arm64（本机那一种）", "build apk --debug --target-platform android-arm64",
            "只编 arm64 单 ABI 的测试包：手机自己就是 arm64，编 armeabi-v7a/x86_64 纯属浪费时间", Section.PARTIAL,
            needsAndroidPlatform = true, packages = true
        ),
        SPLIT_ABI(
            "按 ABI 拆成多个包（--split-per-abi）", "build apk --debug --split-per-abi",
            "每种 ABI 各出一个 APK，单包体积最小（装之前先删掉旧的，包名相同会冲突）", Section.PARTIAL,
            needsAndroidPlatform = true, packages = true
        ),
        ANALYZE(
            "静态检查（flutter analyze）", "analyze",
            "Dart 静态分析：不碰 Android 工具链，是最快的「能不能过编译」验证", Section.OTHER
        ),
        TEST(
            "单元测试（flutter test）", "test",
            "跑 test/ 下的单元测试", Section.OTHER
        ),
        PUB_GET(
            "拉取依赖（flutter pub get）", "pub get",
            "按 pubspec.yaml 补齐依赖缓存（构建前依赖报错时先跑这个）", Section.OTHER
        ),
        CLEAN(
            "只做清理（flutter clean）", "clean",
            "删除 build/ 与 .dart_tool/ 产物", Section.OTHER
        );

        /** 是否是「编一部分」这一类（弹窗里会额外解释 ABI 概念）。 */
        val partOnly: Boolean get() = section == Section.PARTIAL
    }

    /** 附加 flag（追加在 flutter 子命令之后）。 */
    fun flags(): String = buildList {
        if (verbose) add("-v")
    }.joinToString(" ")

    /** 终端会话标题，也是「输出属于哪次构建」的标识。 */
    fun label(): String = buildString {
        append(goal.label)
        if (cleanFirst && goal != Goal.CLEAN) append(" · 先clean")
        if (verbose) append(" · 详细")
    }

    /** 预期产物（相对工程根），仅用于提示；真正定位产物靠构建事件。 */
    fun artifactHint(): String? = when {
        !goal.packages -> null
        goal == Goal.RELEASE_AAB -> "build/app/outputs/bundle/release/*.aab"
        else -> "build/app/outputs/flutter-apk/*.apk"
    }

    /** 真正下发的完整命令行（含「缺 android/ 骨架」的自愈前缀）。 */
    fun command(): String {
        val sb = StringBuilder()
        // 只做清理时当然不用先清理一次。
        if (cleanFirst && goal != Goal.CLEAN) sb.append("flutter clean >/dev/null 2>&1; ")
        sb.append(TaskDefinition.flutterInvocation(goal.args, needsAndroidPlatform = goal.needsAndroidPlatform))
        val f = flags()
        if (f.isNotBlank()) sb.append(' ').append(f)
        return sb.toString()
    }

    /** 稳定编码，用于按工程持久化。 */
    fun encode(): String = listOf(goal.name, cleanFirst.toString(), verbose.toString()).joinToString("|")

    companion object {
        val DEFAULT = FlutterBuildPlan()

        fun decode(value: String?): FlutterBuildPlan {
            if (value.isNullOrBlank()) return DEFAULT
            val parts = value.split("|")
            val goal = runCatching { Goal.valueOf(parts.getOrNull(0) ?: "") }.getOrDefault(Goal.DEBUG_APK)
            return FlutterBuildPlan(
                goal = goal,
                cleanFirst = parts.getOrNull(1)?.toBoolean() ?: false,
                verbose = parts.getOrNull(2)?.toBoolean() ?: false
            )
        }

        /** 是否是需要 Flutter「构建方式」弹窗的工程。 */
        fun isFlutterProject(root: File): Boolean =
            File(root, "pubspec.yaml").isFile && !AndroidBuildPlan.isGradleProject(root)
    }
}

/**
 * Flutter「构建方式」按工程持久化。与 [BuildPlanStore] 分开命名空间，
 * 避免同一个工程（理论上）两种 plan 的键互相覆盖。
 */
class FlutterPlanStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nebula_flutter_build_plan", Context.MODE_PRIVATE)

    fun load(root: File): FlutterBuildPlan = FlutterBuildPlan.decode(prefs.getString(key(root), null))

    fun save(root: File, plan: FlutterBuildPlan) {
        prefs.edit().putString(key(root), plan.encode()).apply()
    }

    private fun key(root: File): String = "plan:" + root.absolutePath
}
