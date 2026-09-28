package com.nebulaforge.core.projectmodel

/** Persisted user-selectable Run Configuration. The stack-specific RunConfiguration remains the command factory. */
data class RunConfigurationSpec(
    val id: String,
    val name: String,
    val projectPath: String,
    val typeId: String,
    val mode: String = "run",
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: String? = null,
    val deviceId: String? = null,
    val port: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

object RunConfigurationDefaults {
    fun forProject(projectRoot: java.io.File, type: ProjectType): List<RunConfigurationSpec> {
        val base = projectRoot.absolutePath
        return when (type.id) {
            "android" -> listOf(RunConfigurationSpec("android-debug", "Android Debug", base, "android", "debug"))
            "flutter" -> listOf(
                RunConfigurationSpec("flutter-run", "Flutter Run", base, "flutter", "run"),
                RunConfigurationSpec("flutter-profile", "Flutter Profile", base, "flutter", "profile"),
                RunConfigurationSpec("flutter-release", "Flutter Release", base, "flutter", "release"),
                RunConfigurationSpec("flutter-web", "Flutter Web Server", base, "flutter", "web", port = 8080)
            )
            "web-frontend" -> listOf(
                RunConfigurationSpec("web-dev", "Web Dev", base, "web-frontend", "dev", port = 5173),
                RunConfigurationSpec("web-start", "Web Start", base, "web-frontend", "start", port = 3000)
            )
            "cpp" -> listOf(
                RunConfigurationSpec("cpp-run", "C++ 运行", base, "cpp", "run"),
                RunConfigurationSpec("cpp-debug", "C++ 调试构建", base, "cpp", "build")
            )
            "web-backend" -> listOf(
                RunConfigurationSpec("web-backend-dev", "Backend Dev", base, "web-backend", "dev"),
                RunConfigurationSpec("web-backend-start", "Backend Start", base, "web-backend", "start")
            )
            "go-backend" -> listOf(
                RunConfigurationSpec("go-run", "Go 运行", base, "go-backend", "run", port = 8080)
            )
            "java-backend" -> listOf(
                RunConfigurationSpec("java-run", "Spring Boot 运行", base, "java-backend", "run", port = 8080)
            )
            "python-backend" -> listOf(
                RunConfigurationSpec("python-run", "Python 运行", base, "python-backend", "run", port = 8000)
            )
            // ---- 独立语言工程（无框架 / 无重量级构建工具） ----
            // id 取自 StandaloneProjectTypes 的 ProjectType.id，运行命令由
            // LanguageCommands.runCommand(模板 id, root) 推导（见 StandaloneCommands）。
            // 这里曾经漏掉这些类型，后果是 python-script / c-console / html-site 等模板工程
            // 的「运行」面板配置列表为空（提示「该项目没有可用运行配置」），运行按钮永远灰着，
            // 于是「程序能编译却看不到任何输出」。
            "java" -> listOf(RunConfigurationSpec("java-console-run", "Java 运行", base, "java", "run"))
            "python" -> listOf(
                RunConfigurationSpec("python-script-run", "Python 运行", base, "python", "run")
            )
            "javascript" -> listOf(RunConfigurationSpec("javascript-run", "Node 运行", base, "javascript", "run"))
            "html" -> listOf(
                RunConfigurationSpec("html-site-run", "静态站点预览", base, "html", "run", port = 8080)
            )
            "css" -> listOf(
                RunConfigurationSpec("css-project-run", "静态站点预览", base, "css", "run", port = 8080)
            )
            "c" -> listOf(RunConfigurationSpec("c-console-run", "C 运行", base, "c", "run"))
            "php-backend" -> listOf(
                RunConfigurationSpec("php-run", "PHP 运行", base, "php-backend", "run", port = 8000)
            )
            "rust-backend" -> listOf(
                RunConfigurationSpec("rust-run", "Rust 运行", base, "rust-backend", "run", port = 8080)
            )
            "lua" -> listOf(
                RunConfigurationSpec("lua-run", "Lua 运行", base, "lua", "run")
            )
            // ---- TypeScript 独立工程（tsc 构建 / tsx 热运行） ----
            // 两条配置对应两种日常用法，刻意都留着：
            //  - dev：`npm run dev` → tsx 直接跑源码，改完即生效，开发期首选；
            //  - start：`npm run start` → 模板里 start 自带 `npm run build`，
            //          所以拿到的永远是刚编译出来的 dist，不会跑旧产物。
            // 命令由 NpmRunConfiguration 生成（读 package.json 的 scripts），
            // ProjectType "typescript" 的 createRunConfiguration() 也返回同一个实现。
            "typescript" -> listOf(
                RunConfigurationSpec("typescript-dev", "TypeScript 运行（tsx 免构建）", base, "typescript", "dev"),
                RunConfigurationSpec("typescript-start", "TypeScript 构建并运行", base, "typescript", "start")
            )
            else -> emptyList()
        }
    }
}
