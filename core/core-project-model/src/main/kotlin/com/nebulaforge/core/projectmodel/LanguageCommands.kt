package com.nebulaforge.core.projectmodel

import java.io.File

/**
 * 各语言「编译 / 运行 / 检查」命令的**唯一真源**。
 *
 * ## 为什么必须集中一处
 * 同一批命令此前存在两份：
 * 1. `stack-server/StandaloneCommands.kt`（各 ProjectType 的 BuildSystem / RunConfiguration 用）；
 * 2. `TasksJson.defaultTasks()`（「构建」工具窗真正执行的默认任务用）。
 * 两份不一致的后果是「新建项目 → 构建面板点了没反应」：模板为 `java-console`/`html-site`/`lua-script`
 * 等项目时，`defaultTasks()` 的 `when` 里没有任何分支命中，直接落进 `else -> emptyList()`，
 * 构建面板于是**一个任务都没有**（比构建失败更难排查）。现在两边都引用本对象，不会再漂移。
 *
 * ## 为什么命令在 Kotlin 里拼而不是丢给 shell
 * 源文件集合、Java 主类名、C 可执行名都取决于用户实际写的代码。写死 `find src -name '*.java'`
 * 会依赖 Termux 是否装了 findutils、是否支持 globstar，一旦缺失就表现为
 * 「构建失败但原因莫名其妙」。这里直接用 Kotlin 遍历目录，生成一条**只包含真实存在文件**的命令，
 * 并用单引号包住所有路径（容忍空格与中文）。
 */
object LanguageCommands {

    /** 只对含空白/特殊字符的参数加引号；路径一律走 [shellQuote]。 */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val SKIP_DIRS = setOf("build", "target", "node_modules", ".git", "dist", "out")

    /** 递归收集指定扩展名的源文件；跳过构建产物与隐藏目录，最多 200 个，避免误扫。 */
    fun sources(root: File, vararg extensions: String): List<File> = root.walkTopDown()
        .onEnter { dir -> dir == root || (dir.name !in SKIP_DIRS && !dir.name.startsWith(".")) }
        .filter { it.isFile && extensions.any { ext -> it.extension.equals(ext, ignoreCase = true) } }
        .take(200)
        .toList()

    fun javaSources(root: File): List<File> = sources(root, "java")
    fun pythonSources(root: File): List<File> = sources(root, "py")
    fun cSources(root: File): List<File> = sources(root, "c")
    fun jsSources(root: File): List<File> = sources(root, "js")
    fun luaSources(root: File): List<File> = sources(root, "lua")

    fun hasJava(root: File): Boolean = javaSources(root).isNotEmpty()
    fun hasPython(root: File): Boolean = pythonSources(root).isNotEmpty()
    fun hasLua(root: File): Boolean = luaSources(root).isNotEmpty()
    fun hasJs(root: File): Boolean = jsSources(root).isNotEmpty()

    /** 从源码解析主类全限定名：优先含 `static void main` 的文件，再读它的 package 声明。 */
    fun javaMainClass(root: File): String? {
        val main = javaSources(root).firstOrNull { file ->
            runCatching { file.readText() }.getOrDefault("").contains("static void main")
        } ?: return null
        val text = runCatching { main.readText() }.getOrDefault("")
        val pkg = Regex("^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*;", RegexOption.MULTILINE)
            .find(text)?.groupValues?.get(1)
        return if (pkg.isNullOrBlank()) main.nameWithoutExtension else pkg + "." + main.nameWithoutExtension
    }

    private fun joinFiles(files: List<File>): String = files.joinToString(" ") { shellQuote(it.absolutePath) }

    // ------------------------------------------------------------------ Java（纯 JDK）

    fun javaCompile(root: File): String {
        val files = javaSources(root)
        if (files.isEmpty()) return "echo '[nebula] 没有找到 .java 源文件' >&2; false"
        return "javac -encoding UTF-8 -d build " + joinFiles(files)
    }

    fun javaRun(root: File): String {
        val main = javaMainClass(root)
            ?: return javaCompile(root) + "; echo '[nebula] 未找到含 static void main 的类' >&2; false"
        return javaCompile(root) + " && java -cp build " + main
    }

    // ------------------------------------------------------------------ C（clang）

    fun cTargetName(root: File): String = root.name.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "app" }

    fun cBuild(root: File): String {
        val files = cSources(root)
        if (files.isEmpty()) return "echo '[nebula] 没有找到 .c 源文件' >&2; false"
        val include = if (File(root, "include").isDirectory) " -Iinclude" else ""
        return "mkdir -p build && clang -std=c17 -Wall -Wextra -O2" + include +
            " -o build/" + cTargetName(root) + " " + joinFiles(files)
    }

    fun cRun(root: File): String = cBuild(root) + " && ./build/" + cTargetName(root)

    // ------------------------------------------------------------------ C++（CMake）

    /** 从 CMakeLists.txt 取第一个 `add_executable` 的目标名：CMake 生成的二进制就叫这个名字。 */
    fun cmakeExecutableName(root: File): String? {
        val list = File(root, "CMakeLists.txt")
        if (!list.isFile) return null
        val text = runCatching { list.readText() }.getOrNull() ?: return null
        val match = Regex("add_executable\\s*\\(\\s*([A-Za-z0-9_.-]+)").find(text) ?: return null
        return match.groupValues[1].takeIf { it.isNotBlank() }
    }

    /**
     * CMake 工程运行：配置 → 构建 → 执行目标。
     *
     * 解析不到目标名时**明确失败**（只构建 + 非零退出），而不是回落到「命令看起来像成功了」——
     * 否则用户会以为「程序跑了但没输出」，正是本项目踩过的坑。
     */
    fun cmakeRun(root: File): String {
        val build = "cmake -S . -B build && cmake --build build -j4"
        val target = cmakeExecutableName(root)
            ?: return "$build; echo '[nebula] 未能在 CMakeLists.txt 里解析出 add_executable 目标，已构建但无法运行' >&2; false"
        return "$build && ./build/$target"
    }

    // ------------------------------------------------------------------ JavaScript（Node，无 npm 依赖）

    /** node --check 每次只接受一个文件，所以用 for 循环逐个检查；exit 落在执行器的子 shell 内，安全。 */
    fun jsCheck(root: File): String {
        val files = jsSources(root)
        if (files.isEmpty()) return "echo '[nebula] 没有找到 .js 文件' >&2; false"
        return "for __f in " + joinFiles(files) + "; do node --check \"\$__f\" || exit 1; done; " +
            "echo '[nebula] JavaScript 语法检查通过（" + files.size + " 个文件）'"
    }

    // ------------------------------------------------------------------ Lua

    /**
     * Termux 的 lua54 包只提供 `lua5.4` / `luac5.4`（已核对 deb 内容），而 luarocks、系统 lua
     * 等来源可能提供 `lua` / `luajit`，所以用一段 shell 兜底：装了任意一种都能跑，
     * 而不是在任务里写死一个可能不存在的可执行名。
     */
    val LUA_RUNNER: String =
        "lua_run() { for c in lua5.4 lua luajit; do command -v \"\$c\" >/dev/null 2>&1 && exec \"\$c\" \"\$@\"; done; " +
            "echo 'lua 解释器未安装（lua5.4/lua/luajit 均未找到）' >&2; return 127; }; "

    val LUA_SYNTAX_CHECKER: String =
        "lua_check() { for c in luac5.4 luac luac5.3; do command -v \"\$c\" >/dev/null 2>&1 && exec \"\$c\" -p \"\$@\"; done; " +
            // 没有 luac* 时的兜底：用解释器只做**编译**（loadfile 不执行代码），语义与 luac -p 等价。
            //
            // 文件列表**必须**经 stdin 逐行传入。实测（Lua 5.4.3）若写成
            // `lua -e '<code>' 文件1 文件2`，lua 会把第一个文件当成**脚本执行**：
            //   arg[0]=文件1，arg[-1]=-e，arg[1..] 为空
            // 结果是「既一个文件都没检查，又真的把用户脚本跑了一遍」——漏检 + 副作用。
            "for i in lua5.4 lua lua5.3 luajit; do if command -v \"\$i\" >/dev/null 2>&1; then " +
            "printf '%s\\n' \"\$@\" | \"\$i\" -e 'for f in io.lines() do local ok, err = loadfile(f); " +
            "if not ok then io.stderr:write(tostring(err) .. \"\\n\"); os.exit(1) end end'; return \$?; fi; done; " +
            "echo 'Lua 解释器未安装（luac5.4/luac/lua5.4/lua 均未找到）→ 请在「设置 → 工具链」安装「Lua 5.4」' >&2; return 127; }; "

    /** 语法检查所有真实存在的 .lua；无源文件时明确失败而不是假装通过。 */
    fun luaCheck(root: File): String {
        val files = luaSources(root)
        if (files.isEmpty()) return LUA_SYNTAX_CHECKER + "echo '[nebula] 没有找到 .lua 文件' >&2; false"
        return LUA_SYNTAX_CHECKER + "for __f in " + joinFiles(files) +
            "; do lua_check \"\$__f\" || exit 1; done; echo '[nebula] Lua 语法检查通过（" + files.size + " 个文件）'"
    }

    fun luaRun(root: File, activeFile: File? = null): String {
        // 同 Python/JS：优先当前打开的文件；其次 main.lua，最后工程里第一个 .lua。
        // 用**相对工程根的路径**而不是文件名：嵌套目录（src/foo.lua）也要能跑。
        val entry = activeEntry(root, activeFile, listOf("lua"))
            ?: File(root, "main.lua").takeIf { it.isFile }?.name
            ?: luaSources(root).firstOrNull()?.name
        if (entry == null) return LUA_RUNNER + "echo '[nebula] 没有找到 .lua 文件' >&2; false"
        return LUA_RUNNER + "cd " + shellQuote(root.absolutePath) + " && lua_run " + shellQuote(entry)
    }

    /** Lua 同样无构建阶段：构建=语法检查（有结论行）+ 执行入口脚本。 */
    fun luaBuild(root: File, activeFile: File? = null): String =
        luaCheck(root) + " && " + luaRun(root, activeFile)

    // ------------------------------------------------------------------ 静态站点 / CSS（Python 标准库）

    const val STATIC_SERVER_PORT: String = "8080"

    val staticServer: String get() = "python -m http.server $STATIC_SERVER_PORT || python3 -m http.server $STATIC_SERVER_PORT"

    /** 自检脚本存在时执行真实校验，缺失时明确跳过而不是静默通过。 */
    private fun scriptCheck(root: File, relative: String): String {
        val script = File(root, relative)
        if (!script.isFile) return "echo '[nebula] 未找到 " + relative + "，跳过自检'"
        return "python " + shellQuote(script.absolutePath) + " || python3 " + shellQuote(script.absolutePath)
    }

    fun htmlCheck(root: File): String = scriptCheck(root, "scripts/check_site.py")
    fun cssCheck(root: File): String = scriptCheck(root, "scripts/check_css.py")

    // ------------------------------------------------------------------ Python / Node 运行

    /**
     * Python 解释器兜底：`python3` 优先，其次 `python`。
     *
     * guest 里两者都在（`files/usr/bin/python` 是指向 `python3.x` 的软链），但**不能假定处处都有 `python`**：
     * 只写 `python main.py` 的环境会直接 127「命令找不到」（本地最小 rootfs 校验时就复现了），
     * 用户看到的又是一次不明所以的失败。与 Lua 的 [LUA_RUNNER] 同一思路：先探测再执行。
     */
    val PY_RUNNER: String =
        "py_run() { for c in python3 python; do command -v \"\$c\" >/dev/null 2>&1 && { \"\$c\" \"\$@\"; return \$?; }; done; " +
            "echo 'python 解释器未安装（python3/python 均未找到）→ 请在「设置 → 工具链」安装 Python' >&2; return 127; }; "

    /** 入口脚本名：`main.py` 优先，其次 `app.py`；都没有则为 null。 */
    /**
     * Python 入口脚本。
     *
     * **优先当前打开的文件**（真机反馈：「我明明选中的是 greeter.py，编译的却是 main.py」）。
     * 旧实现只看 `main.py`/`app.py` 是否存在，与编辑器里正在编辑哪个文件完全无关，
     * 于是同一个工程多脚本时，用户点「构建」永远跑的是 main.py —— 与直觉相反。
     * 找不到活动文件（或活动文件是 `helper.py` 这种被 import 的模块）时才退回启发式。
     */
    fun pythonEntry(root: File, activeFile: File? = null): String? {
        activeEntry(root, activeFile, listOf("py"))?.let { return it }
        return when {
            File(root, "main.py").isFile -> "main.py"
            File(root, "app.py").isFile -> "app.py"
            else -> null
        }
    }

    fun pythonRun(root: File, activeFile: File? = null): String =
        PY_RUNNER + "py_run " + (pythonEntry(root, activeFile)?.let { shellQuote(it) } ?: "-m compileall -q .")

    // ------------------------------------------------------------------ 活动文件解析

    /**
     * 「当前打开的文件」→ 可作为入口的**工程内相对路径**。
     *
     * @param extensions 允许的扩展名（小写，不带点）。扩展名不符或文件在工程外时返回 null，
     *   由调用方退回项目级启发式 —— 例如用户在 Android 工程里打开 `build.gradle.kts`，
     *   不应该把 `gradle` 当成 Python/JS 入口去执行。
     */
    private fun activeEntry(root: File, activeFile: File?, extensions: List<String>): String? {
        val f = activeFile ?: return null
        if (!f.isFile) return null
        if (f.extension.lowercase() !in extensions) return null
        val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val filePath = runCatching { f.canonicalPath }.getOrNull() ?: return null
        if (!filePath.startsWith(rootPath + File.separator)) return null
        return runCatching {
            f.canonicalFile.relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/')
        }.getOrNull()
    }

    /**
     * 脚本工程的「构建」：语法检查（有明确结论行）+ **执行入口脚本**。
     *
     * 真机反馈（截图：`Python-script/main.py` 只有一行 `print("hellow")`，「构建」2s / exit 0 / 面板空白）
     * —— 旧实现的默认构建任务是 `python -m compileall -q .`：`-q` 静默，而且**根本不执行程序**，
     * 于是「构建成功」却看不到 hellow，用户判断为「Python 无法编译运行」。
     * 解释型语言没有真正的构建阶段；让「构建」直接产出可观察的程序输出才是符合直觉的行为，
     * 所以这里先做语法检查（失败即停），再执行入口脚本。
     */
    fun pythonBuild(root: File, activeFile: File? = null): String {
        val entry = pythonEntry(root, activeFile)
        val banner = entry?.let { "，执行 $it" } ?: "（工程内没有 main.py/app.py，无可执行入口）"
        val run = entry?.let { " && py_run " + shellQuote(it) }.orEmpty()
        // `-q` 只留退出码、吞掉错误行：用户只看到一句「构建失败」，不知道是哪个文件第几行坏了。
        // 所以失败时**自动重跑一次不带 -q 的 compileall**，把具体 SyntaxError 打进输出面板；
        // 用 `(exit 1)` 而不是 `exit 1`，避免把复用中的交互 shell 一起结束掉。
        val check =
            "py_run -m compileall -q . || { echo '[nebula] Python 语法检查失败，以下为具体错误：'; " +
                "py_run -m compileall .; (exit 1); }"
        return PY_RUNNER + check + " && echo '[nebula] Python 语法检查通过" + banner + "'" + run
    }

    /**
     * 单元测试：**只有存在 tests/ 目录时才跑**。
     * 旧实现无条件执行 `python -m unittest discover -s tests`，没有 tests/ 的模板工程必然报错，
     * 用户看到的就是一条莫名其妙的构建失败。
     */
    fun pythonTest(root: File): String = if (File(root, "tests").isDirectory) {
        // 复用 defaultTasks 的同一实现：`-t .` 在缺少 tests/__init__.py 的用户工程上必然抛
        // "Start directory is not importable"（真机复现），这里再次踩坑就会与面板任务行为不一致。
        TaskDefinition.UNITTEST_DISCOVER
    } else {
        "echo '[nebula] 未发现 tests/ 目录，跳过单元测试'"
    }

    fun javascriptRun(root: File, activeFile: File? = null): String {
        // 同 Python：优先当前打开的文件（用户选 index.js 却跑 main.js 是同一个坑）。
        activeEntry(root, activeFile, listOf("js", "mjs", "cjs"))?.let { return "node " + shellQuote(it) }
        return when {
            File(root, "index.js").isFile -> "node index.js"
            File(root, "main.js").isFile -> "node main.js"
            else -> "node ."
        }
    }

    /** 与 [pythonBuild] 同理：Node 也没有构建阶段，构建=语法检查（有结论行）+ 执行入口。 */
    fun javascriptBuild(root: File, activeFile: File? = null): String =
        jsCheck(root) + " && " + javascriptRun(root, activeFile)

    fun javascriptTest(root: File): String = if (File(root, "tests").isDirectory) {
        "node --test tests"
    } else {
        "echo '[nebula] 未发现 tests/ 目录，跳过单元测试'"
    }

    // ------------------------------------------------------------------ 汇总

    /**
     * 独立语言项目的构建任务列表：**第一项即默认构建命令**。
     *
     * `id` 使用 [ProjectType] 的 id，与各模板的 `typeId` 一致。
     */
    fun tasksFor(id: String, root: File): List<String> = when (id) {
        "java-console" -> listOf(javaCompile(root), javaRun(root))
        "python-script" -> listOf(pythonBuild(root), pythonTest(root), pythonRun(root))
        "javascript-node" -> listOf(javascriptBuild(root), javascriptTest(root), javascriptRun(root))
        "html-site" -> listOf(htmlCheck(root), staticServer)
        "css-project" -> listOf(cssCheck(root), staticServer)
        "c-console" -> listOf(cBuild(root), cRun(root))
        "cpp-cmake-console" -> listOf("cmake -S . -B build && cmake --build build -j4", cmakeRun(root))
        "lua" -> listOf(luaBuild(root), luaRun(root))
        else -> listOf("build")
    }

    fun runCommand(id: String, root: File): String = when (id) {
        "java-console" -> javaRun(root)
        "python-script" -> pythonRun(root)
        "javascript-node" -> javascriptRun(root)
        "html-site" -> staticServer
        "css-project" -> staticServer
        "c-console" -> cRun(root)
        "cpp-cmake-console" -> cmakeRun(root)
        "lua" -> luaRun(root)
        else -> "build"
    }
}
