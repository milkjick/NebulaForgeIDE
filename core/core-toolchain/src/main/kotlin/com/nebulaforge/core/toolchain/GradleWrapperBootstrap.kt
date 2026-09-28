package com.nebulaforge.core.toolchain

import android.content.Context
import java.io.File

/**
 * 为 Gradle / AGP 工程补齐并校正 **Gradle Wrapper**。
 *
 * ## 为什么必须有这一步（真机取证）
 * 项目模板刻意不生成 wrapper（二进制 jar 不适合塞进模板文本），于是新建的 Android 工程
 * 里没有 `gradlew` / `gradle-wrapper.jar`。构建面板的 Android 任务在这种情况下会退回
 * PATH 上的 `gradle`——而内置用户态装的 Gradle 是 **9.7.1**，模板用的 AGP 是 **8.6.0**：
 * AGP 8.x 与 Gradle 9.x 不兼容，构建必然以
 * `Unsupported class file major version` / `UnknownPluginException` 之类的方式失败。
 * 用户看到的只是一句「安卓空项目建出来就编译不过」，根因其实是**wrapper 缺失导致的
 * Gradle 版本错配**。
 *
 * ## 做法
 * 不调用真实 Gradle 去 `:wrapper`（那需要联网、还慢），而是把 wrapper 三件套
 * （`gradlew` / `gradle-wrapper.jar` / `gradle-wrapper.properties`）作为应用资产直接落盘，
 * 并把 `distributionUrl` 钉在**与 AGP 8.x 匹配的 Gradle 8.9**。首次构建时 wrapper 自行下载
 * 该发行版到 `GRADLE_USER_HOME/wrapper/dists`（应用私有目录，可执行，不受 /storage 的 noexec 影响）。
 *
 * 幂等：文件已存在就跳过；只有当工程用 AGP 8.x 却把 wrapper 钉在 Gradle 9.x 时才做一次校正。
 */
object GradleWrapperBootstrap {

    private const val GRADLEW = "gradlew"
    private const val WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar"
    private const val WRAPPER_PROPS = "gradle/wrapper/gradle-wrapper.properties"

    /** AGP 8.x 支持的 Gradle 版本（8.7 起支持 AGP 8.6，8.9 是同时兼容 8.2~8.7 的稳妥档）。 */
    private const val PINNED_VERSION = "8.9"

    data class Applied(val notes: List<String>)

    /** 是否是需要 wrapper 的 Gradle 工程。 */
    fun isGradleProject(root: File): Boolean =
        File(root, "settings.gradle.kts").isFile || File(root, "settings.gradle").isFile ||
            File(root, "build.gradle.kts").isFile || File(root, "build.gradle").isFile

    /**
     * 补齐/校正 wrapper。返回 null 表示无需改动（已经齐了）。
     * 任何单点失败都不抛给调用方——构建流程不能被「补 wrapper」本身打断。
     */
    fun ensure(context: Context, projectRoot: File): Applied? {
        if (!projectRoot.isDirectory || !isGradleProject(projectRoot)) return null
        val notes = mutableListOf<String>()

        val gradlew = File(projectRoot, GRADLEW)
        val jar = File(projectRoot, "gradle/wrapper/gradle-wrapper.jar")
        if (!gradlew.isFile || !jar.isFile) {
            val a = copyAsset(context, GRADLEW, gradlew)
            val b = copyAsset(context, WRAPPER_JAR, jar)
            if (a && b) {
                // /storage 是 noexec，执行位其实没用；但保留 0755 让「拷到别的机器」时也正常。
                runCatching { gradlew.setExecutable(true, false) }
                notes += "已补齐 Gradle Wrapper（gradlew + gradle-wrapper.jar）"
            } else {
                notes += "⚠ 应用资产里缺少 Gradle Wrapper，跳过补齐（可在设置→工具链里用真实 Gradle 生成）"
            }
        }

        val props = File(projectRoot, WRAPPER_PROPS)
        val agp = agpVersion(projectRoot)
        if (!props.isFile) {
            if (copyAsset(context, WRAPPER_PROPS, props)) {
                notes += "已写入 wrapper 配置（Gradle $PINNED_VERSION，与 AGP ${agp?.let { "${it.first}.${it.second}" } ?: "8.x"} 匹配）"
            }
        } else if (agp != null && agp.first < 9) {
            // 已有 wrapper，但指向 Gradle 9.x → 对 AGP 8.x 是硬不兼容，校正一次。
            val text = runCatching { props.readText() }.getOrDefault("")
            val declared = Regex("gradle-(\\d+)\\.(\\d+)").find(text)
                ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            if (declared != null && declared.first >= 9) {
                val fixed = text.replace(
                    Regex("gradle-\\d+\\.\\d+(?:-[A-Za-z0-9.]+)?-bin\\.zip"),
                    "gradle-$PINNED_VERSION-bin.zip"
                )
                runCatching { props.writeText(fixed) }
                    .onSuccess {
                        notes += "wrapper 由 Gradle ${declared.first}.${declared.second} 校正为 $PINNED_VERSION" +
                            "（工程用 AGP ${agp.first}.${agp.second}，不支持 Gradle 9.x）"
                    }
                    .onFailure { notes += "⚠ wrapper 版本校正失败：${it.message ?: it.javaClass.simpleName}" }
            }
        }
        // ---------------------------------------------------------------- 关闭 Gradle 守护进程
        // 命令行里的 `--no-daemon` 只覆盖「我们拼出来的命令」；工程自带的 .vscode/tasks.json、
        // 用户在终端里手敲的 gradle，都可能绕过它。真机上 daemon 有两个实打实的问题：
        //   1) "Unable to set daemon's environment variables to match the client" ——
        //      应用按任务注入环境变量，复用的 daemon 可能带着上一次的 JAVA_HOME/ANDROID_HOME 继续构建；
        //   2) 被中断/被杀的构建留下 busy daemon 占着 .gradle/ 与缓存锁，新构建卡在等锁上，
        //      用户看到的就是「构建一直显示运行中」（真机截图：2 busy and 1 incompatible daemons could not be reused）。
        // 写进工程根 gradle.properties 是最可靠的一刀（工程级配置优先级高于用户级）。
        // 幂等：已有 `org.gradle.daemon=` 就完全不动用户的文件。
        val gradleProps = File(projectRoot, "gradle.properties")
        val existingProps = runCatching { gradleProps.readText() }.getOrDefault("")
        // 真机提速（用户实测「原生安卓和 flutter 构建太慢」）：这一段以前写死 org.gradle.daemon=false，
        // 代价是每个构建都重付「JVM 启动 + 全量配置」（日志恒定：
        //   To honour the JVM settings for this build a single-use Daemon process will be forked.
        //   Daemon will be stopped at the end of the build）
        // 而且 org.gradle.caching=true 的收益会被每次冷启动（重新加载缓存索引）吃掉。
        //
        // 早前禁 daemon 的三条理由现在都不成立：
        //  1) "Unable to set daemon's environment variables to match the client" ——
        //     Gradle 8/9 不再报错，而是直接 fork 一个环境变量匹配的新守护进程；且我们注入的
        //     JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME 每次都是固定值；
        //  2) busy daemon 占锁 —— 守护进程按 idletimeout 自动退出，且我们不再每构建删 registry.bin
        //     （那才是「守护进程永远复用不上」的元凶，见 TaskDefinition）；
        //  3) 单次守护进程被本进程 pkill 连坐杀掉 —— pkill 'Gradle[D]aemon' 早已移除。
        //
        // **必须强制改写**：老版本已经把 `org.gradle.daemon=false` 写进了用户工程，只做「不存在才追加」
        // 是改不动的，提速会完全失效。这里按 key 强制设值（替换已存在的行，不存在才追加），幂等。
        val wanted = linkedMapOf(
            // 真机卡死根因（2.12.95 用户实测：原生安卓模板构建同样永远不出结果）：
            // AGP 把「缺少的 NDK / platform / build-tools」当成可以自己装的东西，于是去
            // dl.google.com 拉（NDK 约 1GB）。境内网络下这一步会永久挂住，面板上就是
            // 「一直运行中」。关掉它，缺组件交由 IDE 工具链中心处理（有进度、有明确错误）。
            "android.builder.sdkDownload" to "false",
            "org.gradle.daemon" to "true",
            "org.gradle.daemon.idletimeout" to "300000",
            "org.gradle.caching" to "true",
            // proot 里没有可用的 inotify：关掉文件监视，别让守护进程在这上面白等。
            "org.gradle.vfs.watch" to "false"
        )
        var propsText = existingProps
        val applied = mutableListOf<String>()
        wanted.forEach { (k, v) ->
            val re = Regex("(?m)^\\s*" + Regex.escape(k) + "\\s*=.*$")
            propsText = if (re.containsMatchIn(propsText)) {
                re.replace(propsText, "$k=$v")
            } else {
                (if (propsText.isNotEmpty() && !propsText.endsWith("\n")) propsText + "\n" else propsText) + "$k=$v\n"
            }
            applied += "$k=$v"
        }
        if (propsText != existingProps) {
            runCatching { gradleProps.writeText(propsText) }
                .onSuccess { notes += "已启用 Gradle 守护进程复用与构建缓存（真机提速）：" + applied.joinToString("、") }
                .onFailure { notes += "⚠ 写入 gradle.properties 失败：${it.message ?: it.javaClass.simpleName}" }
        }

        // ── 占位 NDK（真机验证得出的方案，2.12.98）────────────────────────────────
        // sdkDownload=false 之后 AGP 不再自动下载 SDK 组件，但它对「工程声明了 ndkVersion」
        // 的工程仍会校验 NDK 是否存在，缺了就**直接失败**：
        //   [CXX1101] NDK at <sdk>/ndk/28.2.13676358 did not have a source.properties file
        // 原生模板一般不声明 ndkVersion，但 Flutter 的 Gradle 插件会在配置期强制设回去，
        // 用户也可能自己加。纯 Java/Kotlin/Flutter 工程不做 native 编译（Flutter 的 so 是
        // 预编译提供的），真 NDK 约 1GB 且境内下载必然卡死 —— 而 AGP 只校验
        // source.properties 这一个文件，因此给它一个结构合法的占位目录即可。
        runCatching {
            val sdkHome = File(File(context.filesDir, "home"), "android-sdk")
            val ndkRoot = File(sdkHome, "ndk")
            val versions = ndkRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
            versions.forEach { v ->
                val dir = File(ndkRoot, v)
                val sp = File(dir, "source.properties")
                if (dir.isDirectory && !sp.isFile) {
                    sp.writeText("Pkg.Desc = Android NDK\nPkg.Revision = $v\n")
                    notes += "已为 NDK $v 写入占位信息（避免 AGP 因缺少 NDK 失败或联网下载约 1GB）"
                }
            }
        }

        // ---------------------------------------------------------------- 钳制 JVM 内存
        // 真机回归（2.12.90）：工程里的 `org.gradle.jvmargs` 常写着 `-Xmx8G -XX:MaxMetaspaceSize=4G`
        // （桌面档位，Flutter/Android Studio 模板都这么写）。真机上 Gradle 的单次构建进程
        // （即使 `--no-daemon`，Gradle 仍会 fork 一个 single-use daemon）会因内存被低内存杀手干掉，
        // 日志表现为：
        //   The message received from the daemon indicates that the daemon has disappeared.
        //   FAILURE: Gradle build daemon disappeared unexpectedly
        // 这里把 Xmx / MaxMetaspaceSize 钳到与 IDE 自身构建一致的档位（2G / 512M），并关掉 OOM 堆转储
        // （触发时会把几个 G 的 dump 写进用户可见存储）。幂等：值已在范围内就一个字都不改。
        runCatching {
            val text = gradleProps.takeIf { it.isFile }?.readText().orEmpty()
            val line = Regex("org\\.gradle\\.jvmargs\\s*=\\s*.+").find(text)?.value ?: return@runCatching
            val fixed = line
                .replace(Regex("-Xmx\\d+[kKmMgG]?"), "-Xmx2048m")
                .replace(Regex("-XX:MaxMetaspaceSize=\\d+[kKmMgG]?"), "-XX:MaxMetaspaceSize=512m")
                .replace("-XX:+HeapDumpOnOutOfMemoryError", "-XX:-HeapDumpOnOutOfMemoryError")
            if (fixed != line) {
                gradleProps.writeText(text.replace(line, fixed))
                notes += "已把 org.gradle.jvmargs 钳制为真机档位（-Xmx2048m -XX:MaxMetaspaceSize=512m）"
            }
        }.onFailure { notes += "⚠ 钳制 org.gradle.jvmargs 失败：${it.message ?: it.javaClass.simpleName}" }

        return if (notes.isEmpty()) null else Applied(notes)
    }

    /** 从工程里探测 AGP 版本（version catalog / plugins 块 / classpath 三种写法都认）。 */
    fun agpVersion(root: File): Pair<Int, Int>? {
        val candidates = listOf(
            "gradle/libs.versions.toml",
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle",
            "app/build.gradle.kts",
            "app/build.gradle"
        )
        val patterns = listOf(
            Regex("""com\.android\.(application|library)"\)\s*version\s*"(\d+)\.(\d+)"""),
            Regex("""com\.android\.tools\.build:gradle:(\d+)\.(\d+)"""),
            Regex("""agp\s*=\s*"(\d+)\.(\d+)""")
        )
        for (name in candidates) {
            val f = File(root, name)
            if (!f.isFile) continue
            val text = runCatching { f.readText() }.getOrDefault("")
            for (p in patterns) {
                val m = p.find(text) ?: continue
                val g = m.groupValues
                val majorIdx = if (g.size >= 4 && g[1].toIntOrNull() == null) 2 else 1
                val major = g[majorIdx].toIntOrNull() ?: continue
                val minor = g.getOrNull(majorIdx + 1)?.toIntOrNull() ?: continue
                return major to minor
            }
        }
        return null
    }

    private fun copyAsset(context: Context, assetPath: String, target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        context.assets.open("gradle-wrapper/$assetPath").use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        }
        true
    }.getOrDefault(false)
}
