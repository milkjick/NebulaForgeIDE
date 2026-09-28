package com.nebulaforge.core.projectmodel

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条可执行的任务定义（对齐 VSCode `tasks.json` 的语义，但只保留 IDE 真正用得上的字段）。
 *
 * 为什么不让 UI 直接读 JSON：构建面板要能「选任务 / 看工作目录 / 看环境变量 / 匹配问题」，
 * 这些都需要强类型；而且 `tasks.json` 由用户手写，容错解析必须集中一处（见 [TasksJson]）。
 */
data class TaskDefinition(
    /** 稳定标识：来自 `id`；缺省时由 label 归一化生成（写入诊断/运行事件用）。 */
    val id: String,
    val label: String,
    /** `shell`（默认，交给 guest 的 bash 执行整行）或 `process`（直接 exec，不经过 shell）。 */
    val type: String = "shell",
    /** 命令主体。既支持「可执行文件 + args 分开写」，也支持「整行命令行」（VSCode 两种都允许）。 */
    val command: String,
    val args: List<String> = emptyList(),
    /** `build` / `test` / 其它；`build` 用于「保存后自动构建」与顶栏构建按钮的默认任务选择。 */
    val group: String? = null,
    /** 相对项目根的工作目录；null = 项目根。 */
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** 问题匹配器名称（`$nebula-gradle` / `$nebula-gcc` …）；空 = 用全部内置规则兜底。 */
    val problemMatcher: List<String> = emptyList(),
    val detail: String? = null
) {
    /** 真正送给 shell 的一行命令。 */
    fun commandLine(): String =
        if (args.isEmpty()) command.trim()
        else (listOf(command) + args).joinToString(" ") { shellToken(it) }

    val isBuild: Boolean get() = group.equals("build", ignoreCase = true)

    companion object {
        /**
         * unittest 发现式测试命令。
         *
         * 为什么要包一层：Python 3.12 起，`unittest` 在**一个用例都没跑**时会以退出码 5 结束
         * （并打印 `NO TESTS RAN`）。构建面板只认退出码 0，于是「这个项目还没写测试」会被显示成
         * 红色的「构建失败」，用户完全看不出真实原因。
         *
         * 注意用 `(exit $rc)` 而不是 `exit $rc`：整条任务命令是包在 `{ ... }` 里写进**可复用的
         * 交互 shell** 的，直接 exit 会把用户的终端会话一起干掉（后续任务失去复用）。
         *
         * ## 为什么不能只写 `unittest discover -s tests -t .`（真机复现的「多文件就构建失败」）
         *
         * `-t .` 把顶层目录设为工程根，此时 `tests/` **必须是一个包**（`tests/__init__.py`）。
         * 模板工程会生成这个文件，但用户**自己往工程里加文件**（这是最常见的「打开/新增多个文件」）
         * 时不会 —— 于是 Python 3.14（guest 里的版本）直接抛：
         * `ImportError: Start directory is not importable: '.../tests'`，整个构建任务变红。
         * 真机复现：同一份多文件工程，构建（语法检查+运行）exit 0 且输出正常，单元测试 exit 1。
         *
         * 现在按目录真实形态选择发现方式：
         *  - `tests/__init__.py` 存在 → 维持包语义（`-t .`，相对导入可用）；
         *  - 不存在 → 顶层目录取 `tests` 本身（`-s tests -p 'test*.py'`），完全不需要包标记；
         *  - 连 `tests/` 都没有 → 退化为扫描工程根目录下的 `test*.py`。
         * 两种情况都加 `PYTHONPATH=.`，保证测试能 import 到工程内的模块（`from greeter import greeting`）。
         * 解释器同样做 `python3` → `python` 兜底，避免 127「命令找不到」被当成测试失败。
         */
        const val UNITTEST_DISCOVER =
            "nebula_ut() { " +
                "for c in python3 python; do command -v \"\$c\" >/dev/null 2>&1 && break; done; " +
                "if [ -d tests ]; then " +
                "if [ -f tests/__init__.py ]; then PYTHONPATH=. \"\$c\" -m unittest discover -s tests -t . -q; " +
                "else PYTHONPATH=. \"\$c\" -m unittest discover -s tests -p 'test*.py' -q; fi; " +
                "else PYTHONPATH=. \"\$c\" -m unittest discover -s . -p 'test*.py' -q; fi; }; " +
                "nebula_ut; __rc=\$?; " +
                "if [ \"\$__rc\" = 5 ]; then echo '[nebula] 未发现测试用例（不是构建失败）'; __rc=0; fi; (exit \$__rc)"

        /**
         * 生成 Android/Gradle 的启动命令：`sh ./gradlew <target>`，并在 wrapper 根本跑不起来时
         * 回退到 PATH 上的 `gradle`。
         *
         * 三种「wrapper 起不来」的情况都要兜住，且各自退出码不同（真机实测）：
         *   - `./gradlew`（缺执行位，或工程在 /storage 这类 noexec 文件系统上）→ 126
         *   - `sh ./gradlew` 但文件已被移走 → sh 自己报 cannot open，退出码是 **2** 不是 127
         * 所以先 `-f` 判断存在性，再只在启动失败（126/127）时兜底。
         * **真实构建失败是 1，绝不能触发兜底** —— 否则会把失败的构建白跑一遍并掩盖真正的报错。
         *
         * 「构建方式」弹窗（[com.nebulaforge.app.build.AndroidBuildPlan]）复用这个函数，
         * 保证弹窗里拼出来的命令和任务清单里的命令完全同源。
         */
        fun gradleInvocation(projectRoot: File, target: String): String {
            // 境内网络下官方仓库偶发「握手成功但读极慢」，Gradle 默认 30s read timeout 容易直接
            // 把依赖解析判死。放宽到 60s，让镜像/回退到官方源时都有机会读完。
            //
            // 真机上必须**禁用 Gradle 守护进程**（--no-daemon）：
            //  1) 应用按任务/按工程注入环境变量，复用的 daemon 会报
            //     "Unable to set daemon's environment variables to match the client"，
            //     并且可能拿着**上一次构建的陈旧环境**（旧 JAVA_HOME / 旧 ANDROID_HOME）继续构建，结果不可信；
            //  2) 被中断/被杀的构建会留下 busy daemon 占着 .gradle/ 与缓存锁，下一次构建卡在等锁上，
            //     用户看到的现象就是「构建一直显示运行中」（用户截图：Starting a Gradle Daemon，
            //     2 busy and 1 incompatible daemons could not be reused）。
            // 代价是每次构建多几秒配置时间，换来确定性，值得。
            // 构建前卫生处理：把上一次被中断/半死的构建残留清掉，否则下一次构建必然停在这里
            //   > Task :app:processDebugResources FAILED
            //   > AAPT2 aapt2-8.6.0-11315950-linux Daemon #1: Daemon startup failed
            // 也就是用户报的「编译一次后再次编译会失败」。
            //
            // 三层处理，逐层减弱对环境的依赖：
            //  1) pkill 收掉残留的 aapt2 / Gradle 守护进程。模式写成 aapt[2]-[0-9] /
            //     Gradle[D]aemon：`pkill -f` 拿正则匹配**整条命令行**，而本任务自己的命令行里
            //     也含这些字面量，括号拆开后不再命中自己（已验证：不会自杀）。
            //  2) 清掉 aapt2 守护进程的临时状态（socket / lock / log）。真机上 aapt2 是 x86-64
            //     二进制经 QEMU 模拟，守护进程半死后残留的 socket 会让新守护进程直接启动失败；
            //     而 pkill 在部分沙箱里**看不到进程列表**（/proc 被过滤，实测 pkill -f 全不命中），
            //     所以这一步是 pkill 失明时的兜底。
            //  3) 清 Gradle daemon 的 registry.bin（「Timeout waiting to lock」就是它写坏的）。
            //
            // 只动守护进程的瞬态状态，不碰依赖缓存；rm 全带 -f 与 2>/dev/null，任何一步失败都不影响构建。
            //
            // 真机回归（2.12.90 取证，必须去掉对 Gradle 守护进程的 pkill）：
            // 原先这里还会 `pkill -f 'Gradle[D]aemon'`。但构建用的是 `--no-daemon` 的**单次**
            // 守护进程，它正好与「本次构建」同时存在 —— 用户连点两次构建时，第二次的 pkill 会把
            // 第一次正在使用的守护进程直接杀掉，日志表现为：
            //   The message received from the daemon indicates that the daemon has disappeared.
            //   Daemon vm is shutting down... terminated in response to a user interrupt.
            //   FAILURE: Gradle build daemon disappeared unexpectedly
            // 也就是说这条「卫生处理」本身在制造它想避免的失败。Gradle 的 busy/陈旧守护进程由
            // `--no-daemon` + 每次构建后自动退出解决，不需要（也不应该）由我们强杀。
            // 只保留 aapt2：它是**跨构建复用**的长生命周期守护进程，半死后残留的 socket 会让
            // 下一次资源编译直接 `Daemon startup failed`（真机取证），这才是真正需要清掉的。
            val hygiene = "if command -v pkill >/dev/null 2>&1; then " +
                "pkill -f 'aapt[2]-[0-9]' 2>/dev/null; " +
                "fi; " +
                "for nb_t in \"\${TMPDIR:-/tmp}\" \"\$HOME/.android\"; do " +
                "if [ -d \"\$nb_t\" ]; then rm -rf \"\$nb_t\"/aapt2-* 2>/dev/null; fi; done; "
            // ── 真机提速（用户实测「原生安卓和 flutter 构建太慢」）──────────────────────────
            // 这里以前恒带 `--no-daemon`，等价于每个构建都：启动一个 JVM → 跑完全量配置阶段
            // （AGP/KGP 插件解析与配置是纯开销，几十秒级）→ 退出。第二次构建与第一次一样贵。
            //
            // 早前禁 daemon 的三条理由，现在都不成立了：
            //  1) "Unable to set daemon's environment variables to match the client" ——
            //     Gradle 8/9 遇到客户端环境变量不同时不再报错，而是直接 fork 一个匹配的新守护进程；
            //     何况我们每次构建注入的 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME 都是固定值；
            //  2) busy daemon 占锁 —— 现在守护进程按 org.gradle.daemon.idletimeout 自动退出，
            //     且下面**不再**每构建删 registry.bin（那正是"守护进程永远复用不上"的原因之一）；
            //  3) 单次守护进程被本任务的 pkill 连坐杀掉 —— pkill 'Gradle[D]aemon' 早已移除。
            //
            // 保留确定性的做法：仍不杀守护进程；守护进程起不来时 Gradle 会直接报错，
            // 由上层（构建面板重试/用户再点一次）兜底，而不会静默给出错误结果。
            val opts = "-Dorg.gradle.internal.http.connectionTimeout=60000 " +
                "-Dorg.gradle.internal.http.socketTimeout=60000"
            // 用不用工程自带的 wrapper？判定必须**精确到发行包缓存目录**，不能只看版本目录。
            //
            // Gradle 把发行包缓存在 $GRADLE_USER_HOME/wrapper/dists/<gradle-x.y-kind>/<hash-of-url>/，
            // 目录名是 **distributionUrl 的 md5 的 base36**（Gradle PathAssembler.getHash）。
            //
            // 踩坑（2.12.32 第一版就是这么翻车的，真机日志钉死）：
            //   [toolchain] Gradle：/storage/.../android-app/gradlew
            //   Downloading https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.9-bin.zip
            //   （之后再无输出、任务永不结束 → 用户看到「再次编译一直卡在这里」）
            // 工具链层每个构建都会重写 distributionUrl（换成国内镜像），**URL 一变目录 hash 就变**、
            // 缓存必然失效；而第一版只检查 <gradle-x.y-kind>/*/*.ok（任意 hash）就会误判放行 →
            // 于是又去下载。设备上该版本目录下同时存在 3656…/1wcp… 两个 hash 就是证据。
            //
            // 现在：hash 精确命中 .ok 才用 wrapper；命中不了就用 IDE 内置 gradle，**绝不触发下载**。
            // 同时兼容 python3 缺失的情况（算不出 hash → 视为未缓存 → 走内置 gradle）。
            val pick = "nb_gu=\"\${GRADLE_USER_HOME:-\$HOME/.gradle}\"; nb_cached=0; " +
                "if [ -f gradle/wrapper/gradle-wrapper.properties ]; then " +
                "nb_url=\$(grep -m1 '^distributionUrl=' gradle/wrapper/gradle-wrapper.properties 2>/dev/null | cut -d= -f2- | tr -d '\\\\'); " +
                "nb_wv=\${nb_url##*/}; nb_wv=\${nb_wv%.zip}; " +
                "nb_hash=\$(printf '%s' \"\$nb_url\" | python3 -c 'import hashlib,sys;n=int.from_bytes(hashlib.md5(sys.stdin.buffer.read()).digest(),\"big\");print(\"\".join(\"0123456789abcdefghijklmnopqrstuvwxyz\"[(n//(36**i))%36] for i in range(25,-1,-1)).lstrip(\"0\") or \"0\")' 2>/dev/null); " +
                "if [ -n \"\$nb_hash\" ] && [ -f \"\$nb_gu/wrapper/dists/\$nb_wv/\$nb_hash/\$nb_wv.zip.ok\" ]; then nb_cached=1; fi; " +
                "fi; "
            val runWrapper = "sh ./gradlew $opts $target; __rc=\$?"
            val runGuest = "gradle $opts $target; __rc=\$?"
            val hint = "echo '[nebula] 使用 IDE 内置 Gradle 构建（wrapper 发行包未缓存，真机下载会断流/永久卡死）'; "
            val noGuest = "echo '[nebula] wrapper 发行包未缓存且 PATH 上没有 gradle，只能尝试下载发行包'; " + runWrapper
            val fallback = "echo '[nebula] gradlew 无法执行（缺执行位，或工程所在文件系统 noexec），回退到 PATH 上的 gradle'; " + runGuest
            return hygiene + pick +
                "if [ \"\$nb_cached\" = 1 ]; then " + runWrapper + "; " +
                "elif command -v gradle >/dev/null 2>&1; then " + hint + runGuest + "; " +
                "else " + noGuest + "; fi; " +
                "if [ \$__rc = 126 ] || [ \$__rc = 127 ]; then " + fallback + "; fi; " +
                "(exit \$__rc)"
        }

        /**
         * Flutter 构建前的「平台目录自愈」前缀。
         *
         * 模板只生成 Dart 层（`pubspec.yaml` + `lib/main.dart`），**没有 `android/` 原生骨架**，
         * 于是 `flutter build apk` 会在 4 秒内直接失败：
         *   flutter failed to read .../android/app/build.gradle (PathNotFoundException: errno 2)
         * 这里在构建前用官方 CLI 补齐（`flutter create --platforms=android .`）——
         * 这正是 Flutter 官方「给已有工程补平台目录」的做法，比手写 Gradle 骨架可靠得多。
         *
         * 幂等：`android/app/build.gradle(.kts)` 已存在时只是一次 `[ -f ]` 判断，零开销。
         * `flutter create` 失败也不挡住构建（`|| echo`），让真实报错照常冒出来。
         */
        fun ensureFlutterAndroidPlatform(): String =
            "if [ ! -f android/app/build.gradle ] && [ ! -f android/app/build.gradle.kts ]; then " +
                "echo '[nebula] 缺少 android/ 原生骨架（模板只生成 Dart 层），先用 flutter create 生成…'; " +
                "flutter create --platforms=android . || echo '[nebula] ⚠ flutter create 失败，继续尝试构建'; " +
                "fi; " +
                // Flutter 的 `build apk` 最终是让 Gradle 编 android/ 子工程。
                // 真机提速：这里以前写死 org.gradle.daemon=false（每个构建重付 JVM 启动 + 全量配置）。
                // 现在**强制**写成 true（老版本写进去的 false 会被改写过来），让连续构建复用同一个 JVM。
                // 守护进程起不来时 FlutterBuildShim 尾部有自动 --no-daemon 兜底。
                "if [ -f android/gradle.properties ]; then " +
                "if grep -q '^org.gradle.daemon=' android/gradle.properties; then " +
                "sed -i 's|^org.gradle.daemon=.*|org.gradle.daemon=true|' android/gradle.properties; " +
                "else printf 'org.gradle.daemon=true\\n' >> android/gradle.properties; fi; fi; "

        /**
         * Flutter 工程的下发命令。只有产出 APK/AAB 的目标需要 android/ 骨架；
         * `analyze`/`test` 这类纯 Dart 命令不碰平台目录，不套自愈前缀。
         */
        /**
         * Flutter 命令下发。
         *
         * 优先用 App 安装的构建外壳 `nb-flutter`（见 core-toolchain 的 `FlutterBuildShim`）：
         * 用户工程在 `/storage/emulated/0`（fuse 挂载，**noexec**，文件权限固定 0660），工程内的
         * `android/gradlew` 永远不具可执行位，`flutter` 会直接报
         * `ProcessException: Found candidates, but lacked sufficient permissions to execute
         * ".../android/gradlew"`（真机截图取证）。外壳把工程镜像到应用私有目录（可执行）里构建，
         * 结束后把产物拷回原工程，用户看到的工程/产物路径不变。
         *
         * 外壳缺失时退回原路径（就地自愈 + `flutter`），保证老环境不会更差。
         * 注意：`nb-flutter` 这个名字是命令侧与 core-toolchain 之间的契约，两处必须一致。
         */
        fun flutterInvocation(args: String, needsAndroidPlatform: Boolean = false): String {
            val fallback =
                (if (needsAndroidPlatform) ensureFlutterAndroidPlatform() else "") + "flutter " + args
            return "if command -v nb-flutter >/dev/null 2>&1; then nb-flutter " + args +
                "; else " + fallback + "; fi"
        }

        /** 仅对含空白/特殊字符的参数加引号，避免把 `./gradlew`、`-Pfoo=bar` 这类参数画蛇添足地包起来。 */
        fun shellToken(value: String): String {
            if (value.isEmpty()) return "''"
            val safe = value.all { it.isLetterOrDigit() || it in "._-/=:@%+,^" }
            if (safe) return value
            return "'" + value.replace("'", "'\\''") + "'"
        }
    }
}

/**
 * `.vscode/tasks.json` 的读取、容错解析与模板生成。
 *
 * ## 容错策略（为什么不能直接用 JSONObject）
 * 用户手写的 tasks.json 极常出现：`//` 注释、尾随逗号、`args` 写成单个字符串。
 * 标准的 `JSONObject(text)` 对前两者直接抛异常 —— 那样「用户改错一个逗号 → 构建面板空掉」，
 * 排查成本极高。因此这里先做一轮**注释/尾逗号清洗**，再解析；失败则退化为
 * 「内置默认任务」，保证面板永远有东西可点。
 */
object TasksJson {

    const val FILE_NAME = "tasks.json"
    const val DIR_NAME = ".vscode"

    fun fileFor(projectRoot: File): File = File(File(projectRoot, DIR_NAME), FILE_NAME)

    /** 读取项目任务；无 tasks.json 或解析失败时返回按项目特征推断的默认任务。 */
    fun load(projectRoot: File, activeFile: File? = null): List<TaskDefinition> {
        val file = fileFor(projectRoot)
        if (!file.isFile) return defaultTasks(projectRoot, activeFile)
        val text = runCatching { file.readText() }.getOrNull() ?: return defaultTasks(projectRoot, activeFile)
        val parsed = parse(text)
        return parsed.ifEmpty { defaultTasks(projectRoot, activeFile) }
    }

    /** 解析 tasks.json 文本（宽容：注释、尾逗号、顶层数组或 `{"tasks": [...]}` 都接受）。 */
    fun parse(text: String): List<TaskDefinition> {
        val cleaned = stripJsonComments(text).replace(Regex(",(\\s*[}\\]])"), "$1")
        val array = runCatching {
            when (val trimmed = cleaned.trim()) {
                "" -> null
                else -> if (trimmed.startsWith("[")) JSONArray(trimmed)
                else JSONObject(trimmed).optJSONArray("tasks")
            }
        }.getOrNull() ?: return emptyList()

        val result = mutableListOf<TaskDefinition>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val label = obj.optString("label").ifBlank { obj.optString("taskName").ifBlank { "任务 ${i + 1}" } }
            val command = obj.optString("command")
            if (command.isBlank()) continue
            result += TaskDefinition(
                id = obj.optString("id").ifBlank { slug(label) },
                label = label,
                type = obj.optString("type", "shell"),
                command = command,
                args = stringList(obj.opt("args")),
                group = groupOf(obj.opt("group")),
                cwd = obj.optJSONObject("options")?.optString("cwd")?.ifBlank { null }
                    ?: obj.optString("cwd").ifBlank { null },
                env = envOf(obj.optJSONObject("options")?.optJSONObject("env") ?: obj.optJSONObject("env")),
                problemMatcher = stringList(obj.opt("problemMatcher")),
                detail = obj.optString("detail").ifBlank { null }
            )
        }
        return result
    }

    /**
     * 按项目特征推断默认任务（不写入磁盘，仅用于「面板先能用」）。
     * 返回的第一个 build 任务即顶栏构建按钮的默认目标。
     */
    fun defaultTasks(projectRoot: File, activeFile: File? = null): List<TaskDefinition> {
        fun exists(vararg names: String) = names.any { File(projectRoot, it).exists() }

        /**
         * 当前打开的文件是否属于给定语言、且位于**本工程内**。
         *
         * 真机反馈：「我明明选中的是 greeter.py，编译的却是 main.py」。根因是默认任务只看
         * `main.py`/`app.py` 是否存在，与编辑器当前文件无关。这里让「活动文件」也参与
         * 项目类型判定：只有 `greeter.py`、没有 `main.py` 的工程同样应该出现 Python 构建任务。
         */
        fun activeIs(vararg extensions: String): Boolean {
            val f = activeFile ?: return false
            if (!f.isFile || f.extension.lowercase() !in extensions) return false
            return runCatching {
                f.canonicalPath.startsWith(projectRoot.canonicalPath + File.separator)
            }.getOrDefault(false)
        }
        /**
         * 项目里是否真的存在 Python 测试用例。
         *
         * 判定「有测试」的宽松程度决定任务列表是否可用：
         * - 太松（只看是不是 Python 项目）→ 无测试的项目也出现「单元测试」任务，点了必然 0 tests；
         * - 太严（只认 tests/ 目录）→ 测试放在根目录或 `<pkg>/tests` 的项目会被漏掉。
         * 这里按「出现 test_*.py / *_test.py 文件」判定，深度限制 3 层并跳过虚拟环境与缓存目录。
         */
        fun hasPythonTests(): Boolean {
            val skip = listOf("/__pycache__", "/.venv", "/venv", "/.tox", "/site-packages", "/node_modules", "/.git")
            fun src(f: File) = f.isFile && f.name.endsWith(".py") &&
                (f.name.startsWith("test_") || f.name.endsWith("_test.py")) &&
                skip.none { f.path.contains(it) }
            return projectRoot.walkTopDown().maxDepth(3).any(::src)
        }

        fun fileContains(name: String, needle: String): Boolean {
            val f = File(projectRoot, name)
            return f.isFile && runCatching { f.readText() }.getOrDefault("").contains(needle)
        }
        return when {
            exists("gradlew", "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts") -> {
                // 注意：本 IDE 的 Android 模板**不生成 gradlew**（wrapper 由 GradleWrapperProvisioner
                // 事后用真实 Gradle 生成，`.nebulaforge/project.json` 里 wrapperProvisioned=false）。
                // 因此这里绝不能硬编码 `./gradlew` —— 否则新建项目在构建面板第一次点击就是
                // `./gradlew: No such file or directory`。有 wrapper 用 wrapper，没有就退回 PATH 上的 `gradle`
                // （工具链 GRADLE 组件通过 apt 装进 guest 前缀，一定在 PATH 里）。
                // 真机取证：`./gradlew: Permission denied`，退出码 126，构建面板 1s 就报失败。
                // 两个互相独立的原因都会这样，且都不是「工程坏了」：
                //   ① wrapper 缺执行位 —— 从 zip/FUSE 拷进来的 gradlew 实测是 0644；
                //   ② 工程位于 /storage/emulated/0，该 FUSE 挂载是 noexec
                //      （mount 显示 rw,nosuid,nodev,noexec），内核直接拒绝 execve，chmod 也救不回来。
                // 所以统一用 `sh ./gradlew` 启动：sh 只「读取」脚本再做解释，既不需要执行位、
                // 也不走 execve。仅当连启动都失败（126=权限、127=找不到）时才回落到 PATH 上的
                // gradle —— 用 `||` 无条件兜底会把真实构建失败重跑一遍，白等几分钟并掩盖真正的报错。
                fun gradleCmd(target: String): String = TaskDefinition.gradleInvocation(projectRoot, target)
                listOf(
                    TaskDefinition("gradle-build", "Gradle: assembleDebug（测试包）", command = gradleCmd("assembleDebug"),
                        group = "build", problemMatcher = listOf("\$nebula-gradle"), detail = "Android/Gradle 测试包（debug）"),
                    // 「编译全量包还是测试包」是明确的用户需求：同一份工程要能一键出 release 包。
                    // 未配置签名时 AGP 产出 app-release-unsigned.apk（仍是成功构建），配置了 signingConfig
                    // 则直接出可安装的 release 包 —— 两种都不会因为「没签名」变成构建失败。
                    TaskDefinition("gradle-release", "Gradle: assembleRelease（全量包）", command = gradleCmd("assembleRelease"),
                        group = "build", problemMatcher = listOf("\$nebula-gradle"), detail = "Android/Gradle 全量包（release）"),
                    TaskDefinition("gradle-test", "Gradle: test", command = gradleCmd("test"), group = "test",
                        problemMatcher = listOf("\$nebula-gradle")),
                    TaskDefinition("gradle-clean", "Gradle: clean", command = gradleCmd("clean"))
                )
            }
            exists("pubspec.yaml") -> listOf(
                TaskDefinition("flutter-build", "Flutter: build apk (debug)", command = TaskDefinition.flutterInvocation("build apk --debug", needsAndroidPlatform = true),
                    group = "build", problemMatcher = listOf("\$nebula-dart"), detail = "Flutter 测试包（debug）"),
                TaskDefinition("flutter-build-release", "Flutter: build apk (release)", command = TaskDefinition.flutterInvocation("build apk --release", needsAndroidPlatform = true),
                    group = "build", problemMatcher = listOf("\$nebula-dart"), detail = "Flutter 全量包（release）"),
                TaskDefinition("flutter-analyze", "Flutter: analyze", command = TaskDefinition.flutterInvocation("analyze"),
                    problemMatcher = listOf("\$nebula-dart")),
                TaskDefinition("flutter-test", "Flutter: test", command = TaskDefinition.flutterInvocation("test"), group = "test")
            )
            exists("package.json") -> listOf(
                // 自愈：首次构建时 node_modules 还不存在（vite / @nestjs/cli / @angular/cli 都装在项目本地），
                // 直接 `npm run build` 只会报 `vite: not found`，用户会以为模板坏了。
                // 幂等检查后自动补装依赖，已装过则零开销（不重复 install）。
                TaskDefinition("npm-build", "npm: run build",
                    command = "[ -d node_modules ] || npm install; npm run build", group = "build",
                    problemMatcher = listOf("\$nebula-tsc")),
                TaskDefinition("npm-install", "npm: install", command = "npm install"),
                // --if-present：package.json 里没有 test 脚本时 npm 直接以 0 退出，
                // 而不是抛 "Missing script: test" 让用户以为构建链坏了（前端模板本来就不带测试）。
                TaskDefinition("npm-test", "npm: test", command = "npm test --if-present", group = "test")
            )
            exists("CMakeLists.txt") -> listOf(
                TaskDefinition("cmake-build", "CMake: 配置并构建", command = "cmake -S . -B build && cmake --build build -j4",
                    group = "build", problemMatcher = listOf("\$nebula-gcc")),
                TaskDefinition("cmake-clean", "CMake: 清理", command = "rm -rf build")
            )
            exists("Makefile", "makefile", "GNUmakefile") -> listOf(
                TaskDefinition("make-build", "make: 构建", command = "make -j4", group = "build",
                    problemMatcher = listOf("\$nebula-gcc")),
                TaskDefinition("make-clean", "make: clean", command = "make clean")
            )
            exists("Cargo.toml") -> listOf(
                TaskDefinition("cargo-build", "Cargo: build", command = "cargo build", group = "build",
                    problemMatcher = listOf("\$nebula-rust")),
                TaskDefinition("cargo-test", "Cargo: test", command = "cargo test", group = "test")
            )
            exists("go.mod") -> listOf(
                TaskDefinition("go-build", "Go: build ./...", command = "go build ./...", group = "build",
                    problemMatcher = listOf("\$nebula-go")),
                TaskDefinition("go-test", "Go: test ./...", command = "go test ./...", group = "test")
            )
            // `LanguageCommands.hasPython` 兜底：模板里 main.py 是标配，但用户完全可能自己写一个
            // 只有模块文件（helper.py / utils.py）的工程。少了这个兜底就会落进 else -> 空列表，
            // 构建面板「一个任务都没有」（Lua/JS/Java 三个分支早就这么兜了，Python 之前漏了）。
            exists("pyproject.toml", "setup.py", "requirements.txt", "main.py") ||
                activeIs("py") || LanguageCommands.hasPython(projectRoot) -> {
                // 测试命令按项目真实配置选择：只有确实声明了 pytest 才用 pytest。
                // 独立语言工程（python-script 模板）只依赖标准库 unittest，`unittest discover` 必然可用；
                // 而在没装 pytest 的机器上跑 `python3 -m pytest` 会直接报错，且 0 用例时 pytest 退出码为 5，
                // 会被构建面板当成「测试失败」——这正是「模板开箱即坏」的来源之一。
                val usesPytest = exists("pytest.ini", "conftest.py") ||
                    fileContains("pyproject.toml", "pytest") || fileContains("requirements.txt", "pytest") ||
                    fileContains("requirements-dev.txt", "pytest")
                buildList {
                    // 构建任务 = 语法检查 + **执行入口脚本**。
                    //
                    // 真机反馈（截图）：一个只有 print("hellow") 的 Python-script 工程，点「构建」显示
                    // 「构建成功 · 2s · exit 0」，输出面板却一片空白 —— 因为旧命令 `compileall -q`
                    // 既静默又根本不执行程序，用户直接判断为「Python 无法编译运行」。
                    // 解释型语言没有真正的构建阶段，点「构建」想看的本来就是程序输出。
                    // 命令与 LanguageCommands.pythonBuild 同源，避免两处各写一份再漂移。
                    add(
                        TaskDefinition("py-compile", "Python: 语法检查并运行", command = LanguageCommands.pythonBuild(projectRoot, activeFile),
                            group = "build", problemMatcher = listOf("\$nebula-python"))
                    )
                    // 测试任务**只在这个项目真的写了测试时才给**。
                    // 反例（真机上实测）：一个只有 main.py 的 Python 项目也被塞进「单元测试」任务，
                    // `unittest discover` 一个用例都找不到 → 面板显示 0 tests，用户以为项目坏了。
                    if (hasPythonTests()) {
                        add(
                            TaskDefinition(
                                "py-test",
                                if (usesPytest) "Python: pytest" else "Python: 单元测试 (unittest)",
                                command = if (usesPytest) "python3 -m pytest -q" else TaskDefinition.UNITTEST_DISCOVER,
                                group = "test"
                            )
                        )
                    }
                }
            }

            // ---------- 以下分支补齐「模板有、默认任务却缺失」的项目族 ----------
            // 曾经 javac/Maven/Composer/Lua/静态站点/纯 Node 全部落进 else -> 空列表，
            // 用户在构建面板看到的是「一个任务都没有」（比构建失败更难排查）。
            // 命令统一取自 LanguageCommands，与各 ProjectType 的 BuildSystem 同源。

            // Maven（java-spring-boot 模板）
            exists("pom.xml") -> listOf(
                TaskDefinition("maven-package", "Maven: package", command = "mvn -q package", group = "build",
                    problemMatcher = listOf("\$nebula-gcc", "\$nebula-generic"), detail = "Maven 打包（跳过测试加 -DskipTests）"),
                TaskDefinition("maven-test", "Maven: test", command = "mvn -q test", group = "test",
                    problemMatcher = listOf("\$nebula-gcc", "\$nebula-generic")),
                TaskDefinition("maven-clean", "Maven: clean", command = "mvn -q clean")
            )
            // Composer（php-laravel 模板）
            exists("composer.json") -> listOf(
                TaskDefinition("composer-install", "Composer: install", command = "composer install", group = "build"),
                TaskDefinition("composer-autoload", "Composer: dump-autoload", command = "composer dump-autoload"),
                TaskDefinition("php-serve", "PHP: artisan serve / 内置服务器",
                    command = if (exists("artisan")) "php artisan serve --host=0.0.0.0 --port=8000"
                    else "php -S 0.0.0.0:8000 -t public")
            )
            // Lua（lua-script 模板）：Termux 只提供 lua5.4/luac5.4，命令内自带可执行名兜底
            exists("main.lua", "init.lua", ".luarc.json") || activeIs("lua") ||
                LanguageCommands.hasLua(projectRoot) -> listOf(
                TaskDefinition("lua-check", "Lua: 语法检查并运行", command = LanguageCommands.luaBuild(projectRoot, activeFile), group = "build"),
                TaskDefinition("lua-run", "Lua: 运行", command = LanguageCommands.luaRun(projectRoot, activeFile))
            )
            // 纯 JDK Java（java-console 模板，无 Maven/Gradle）
            LanguageCommands.hasJava(projectRoot) -> listOf(
                TaskDefinition("java-compile", "Java: 编译", command = LanguageCommands.javaCompile(projectRoot),
                    group = "build", problemMatcher = listOf("\$nebula-generic", "\$nebula-gcc")),
                TaskDefinition("java-run", "Java: 编译并运行", command = LanguageCommands.javaRun(projectRoot),
                    problemMatcher = listOf("\$nebula-generic", "\$nebula-gcc"))
            )
            // 静态站点（html-site 模板）
            exists("index.html") -> listOf(
                TaskDefinition("html-check", "HTML: 资源自检", command = LanguageCommands.htmlCheck(projectRoot), group = "build"),
                TaskDefinition("html-serve", "HTML: 本地预览", command = LanguageCommands.staticServer)
            )
            // 纯 CSS（css-project 模板）
            exists("demo.html", "styles.css", "css") -> listOf(
                TaskDefinition("css-check", "CSS: 样式自检", command = LanguageCommands.cssCheck(projectRoot), group = "build"),
                TaskDefinition("css-preview", "CSS: 本地预览", command = LanguageCommands.staticServer)
            )
            // 纯 Node（javascript-node 模板，无 package.json）
            activeIs("js", "mjs", "cjs") || LanguageCommands.hasJs(projectRoot) -> listOf(
                // 同 Python：Node 也没有构建阶段，构建=语法检查（有结论行）+ 执行入口，
                // 否则用户点「构建」同样只会看到「成功 · exit 0」而没有任何程序输出。
                TaskDefinition("js-check", "JavaScript: 语法检查并运行", command = LanguageCommands.javascriptBuild(projectRoot, activeFile),
                    group = "build", problemMatcher = listOf("\$nebula-generic")),
                TaskDefinition("js-run", "JavaScript: 运行", command = LanguageCommands.javascriptRun(projectRoot, activeFile))
            )

            else -> emptyList()
        }
    }

    /** 生成**只含默认任务**的 tasks.json 文本（供「快速配置」弹窗一键写入）。 */
    fun defaultJson(projectRoot: File): String {
        val tasks = defaultTasks(projectRoot)
        if (tasks.isEmpty()) {
            return """
            {
              // NebulaForge：未识别到项目类型，这里给出一个最小示例，按需修改。
              "version": "2.0.0",
              "tasks": [
                {
                  "id": "echo",
                  "label": "示例：打印工作目录",
                  "type": "shell",
                  "command": "pwd",
                  "group": "build",
                  "problemMatcher": []
                }
              ]
            }
            """.trimIndent()
        }
        val lines = tasks.joinToString(",\n") { t ->
            buildString {
                append("    {\n")
                append("      \"id\": \"${t.id}\",\n")
                append("      \"label\": \"${t.label}\",\n")
                append("      \"type\": \"${t.type}\",\n")
                append("      \"command\": \"${t.command.replace("\\", "\\\\").replace("\"", "\\\"")}\",\n")
                if (t.group != null) append("      \"group\": \"${t.group}\",\n")
                append("      \"problemMatcher\": [${t.problemMatcher.joinToString(", ") { "\"$it\"" }}]\n")
                append("    }")
            }
        }
        return """
        {
          // NebulaForge 任务配置（VSCode 兼容）：改完保存即生效，构建面板会重新读取。
          "version": "2.0.0",
          "tasks": [
$lines
          ]
        }
        """.trimIndent()
    }

    // ------------------------------------------------------------------ 工具

    private fun stringList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).ifBlank { null } }
        is String -> if (value.isBlank()) emptyList() else splitArgs(value)
        else -> emptyList()
    }

    /** `args` 写成整串时按空白切分（支持单/双引号包裹的片段）。 */
    private fun splitArgs(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote: Char? = null
        text.forEach { c ->
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') -> quote = c
                quote == null && c.isWhitespace() -> if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun groupOf(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is JSONObject -> value.optString("kind").ifBlank { null }
        else -> null
    }

    private fun envOf(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        obj.keys().forEach { key -> out[key] = obj.optString(key) }
        return out
    }

    private fun slug(label: String): String =
        label.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").trim('-').ifBlank { "task" }

    /** 去掉 `//`、`/* */` 注释（跳过字符串内部，避免把 `"http://x"` 削掉）。 */
    fun stripJsonComments(text: String): String {
        val sb = StringBuilder(text.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    sb.append(c)
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    i++
                }
                c == '"' -> { inString = true; sb.append(c); i++ }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(text.length)
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }
}
