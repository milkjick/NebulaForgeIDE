package com.nebulaforge.core.projectmodel

import java.io.File

/** 模板落盘统一入口，供本文件与各 Stack 模板写出器复用。 */
internal fun writeTemplateFile(root: File, path: String, text: String) {
    val f = File(root, path)
    f.parentFile?.mkdirs()
    f.writeText(text)
}

/**
 * 新建项目模板注册表。
 *
 * 从「5 个平铺模板」升级为多语言前后端「模板矩阵」：每个模板都声明所属分类、
 * 主语言与框架，新建向导据此按「移动端 / 后端服务 / 前端应用 / 原生与系统 / 扩展模块」分组展示。
 *
 * 每个模板的 typeId 都对应一个已注册的 [ProjectType]（见 stack-* 模块），
 * 因此新建出来的项目能被「打开项目」自动识别、并具备匹配的构建/运行配置。
 *
 * 设计约束：生成的项目结构必须真实可用（不伪造 gradlew、不生成无法解析的构建文件）；
 * 元数据统一写入 `.nebulaforge/project.json`。
 */
object ProjectTemplateGenerator {

    val templates: List<ProjectTemplate> = listOf(
        // ---------- 移动端 ----------
        ProjectTemplate(
            "android-empty", "Android 空项目", "Kotlin + XML 的传统 View 体系 Android 项目", "android",
            TemplateCategory.MOBILE, "kotlin", "android-view", listOf("安卓", "kotlin", "xml")
        ),
        ProjectTemplate(
            "android-empty-compose", "Android Compose", "Kotlin + Jetpack Compose 的现代 Android 项目", "android",
            TemplateCategory.MOBILE, "kotlin", "compose", listOf("安卓", "kotlin", "compose")
        ),
        ProjectTemplate(
            "flutter-empty", "Flutter 空项目", "Flutter / Dart 最小可运行项目", "flutter",
            TemplateCategory.MOBILE, "dart", "flutter", listOf("跨平台", "dart", "flutter")
        ),

        // ---------- 后端服务 ----------
        ProjectTemplate(
            "go-gin", "Go + Gin", "Go 语言 Gin 框架 HTTP 服务", "go-backend",
            TemplateCategory.BACKEND, "go", "gin", listOf("golang", "gin", "http")
        ),
        ProjectTemplate(
            "go-echo", "Go + Echo", "Go 语言 Echo 框架 HTTP 服务", "go-backend",
            TemplateCategory.BACKEND, "go", "echo", listOf("golang", "echo", "http")
        ),
        ProjectTemplate(
            "go-fiber", "Go + Fiber", "Go 语言 Fiber 框架 HTTP 服务", "go-backend",
            TemplateCategory.BACKEND, "go", "fiber", listOf("golang", "fiber", "http")
        ),
        ProjectTemplate(
            "go-gorm-gin", "Go + Gin + GORM", "Gin 路由 + GORM 数据访问（SQLite 驱动）", "go-backend",
            TemplateCategory.BACKEND, "go", "gin+gorm", listOf("golang", "gin", "gorm", "orm")
        ),
        ProjectTemplate(
            "node-express", "Node + Express", "Node.js Express 服务端骨架", "web-backend",
            TemplateCategory.BACKEND, "javascript", "express", listOf("node", "express", "http")
        ),
        ProjectTemplate(
            "node-nest", "Node + NestJS", "TypeScript NestJS 模块化服务端骨架", "web-backend",
            TemplateCategory.BACKEND, "typescript", "nest", listOf("node", "nestjs", "typescript")
        ),
        ProjectTemplate(
            "typescript-express", "TypeScript + Express", "类型安全的 Express 服务端骨架（tsc 构建 + tsx 热运行）", "web-backend",
            TemplateCategory.BACKEND, "typescript", "express", listOf("typescript", "express", "node", "http", "后端")
        ),
        ProjectTemplate(
            "java-spring-boot", "Java + Spring Boot", "Maven 构建的 Spring Boot Web 服务", "java-backend",
            TemplateCategory.BACKEND, "java", "spring-boot", listOf("java", "spring", "maven")
        ),
        ProjectTemplate(
            "python-fastapi", "Python + FastAPI", "FastAPI 异步 Web 服务骨架", "python-backend",
            TemplateCategory.BACKEND, "python", "fastapi", listOf("python", "fastapi", "async")
        ),
        ProjectTemplate(
            "python-django", "Python + Django", "Django 项目骨架（含 manage.py 与 settings）", "python-backend",
            TemplateCategory.BACKEND, "python", "django", listOf("python", "django", "web")
        ),
        ProjectTemplate(
            "python-flask", "Python + Flask", "Flask 轻量 Web 服务骨架", "python-backend",
            TemplateCategory.BACKEND, "python", "flask", listOf("python", "flask", "web")
        ),
        ProjectTemplate(
            "php-laravel", "PHP + Laravel（骨架）", "Laravel 目录骨架与入口文件", "php-backend",
            TemplateCategory.BACKEND, "php", "laravel", listOf("php", "laravel", "composer")
        ),
        ProjectTemplate(
            "rust-axum", "Rust + Axum", "Rust Axum 异步 Web 服务骨架", "rust-backend",
            TemplateCategory.BACKEND, "rust", "axum", listOf("rust", "axum", "tokio")
        ),

        // ---------- 前端应用 ----------
        ProjectTemplate(
            "web-vite", "Vue 3 + Vite", "Vue 3 单文件组件 + Vite 开发服务器", "web-frontend",
            TemplateCategory.FRONTEND, "typescript", "vue3", listOf("vue", "vite", "前端")
        ),
        ProjectTemplate(
            "react-vite", "React + Vite", "React 18 + Vite 前端骨架", "web-frontend",
            TemplateCategory.FRONTEND, "typescript", "react", listOf("react", "vite", "前端")
        ),
        ProjectTemplate(
            "next-react", "Next.js", "Next.js App Router 全栈前端骨架", "web-frontend",
            TemplateCategory.FRONTEND, "typescript", "next", listOf("next", "react", "ssr")
        ),
        ProjectTemplate(
            "svelte-vite", "Svelte + Vite", "Svelte 组件 + Vite 前端骨架", "web-frontend",
            TemplateCategory.FRONTEND, "typescript", "svelte", listOf("svelte", "vite", "前端")
        ),
        ProjectTemplate(
            "angular", "Angular", "Angular 独立组件前端骨架", "web-frontend",
            TemplateCategory.FRONTEND, "typescript", "angular", listOf("angular", "rxjs", "前端")
        ),

        // ---------- 原生与系统 ----------
        ProjectTemplate(
            "cpp-cmake", "C++ CMake", "C++17 + CMake 独立工程", "cpp",
            TemplateCategory.NATIVE, "cpp", "cmake", listOf("c++", "cmake", "native")
        ),

        // ---------- 扩展模块 ----------
        ProjectTemplate(
            "xposed-module", "Xposed 模块", "Xposed / LSPosed Hook 模块骨架（含 xposed_init）", "android",
            TemplateCategory.MODULE, "kotlin", "xposed", listOf("xposed", "lsposed", "hook", "模块")
        ),
        ProjectTemplate(
            "lua-script", "Lua 脚本", "Lua 5.4 标准库工程骨架（模块 + 断言测试 + .luarc.json）", "lua",
            TemplateCategory.MODULE, "lua", "stdlib", listOf("lua", "lua5.4", "脚本", "script")
        ),

        // ---------- 独立语言项目（不依赖 Web 框架 / 重量级构建工具，开箱可编译可运行） ----------
        ProjectTemplate(
            "java-console", "Java 控制台", "纯 JDK 工程：javac 编译 + java 运行，无 Maven/Gradle 依赖", "java",
            TemplateCategory.STANDALONE, "java", "jdk", listOf("java", "jdk", "console", "控制台")
        ),
        ProjectTemplate(
            "python-script", "Python 脚本", "标准库 Python 工程：模块 + unittest 测试，python -m compileall 校验", "python",
            TemplateCategory.STANDALONE, "python", "stdlib", listOf("python", "脚本", "unittest")
        ),
        ProjectTemplate(
            "javascript-node", "JavaScript (Node)", "纯 Node.js 工程：CommonJS 模块 + node --check / node --test", "javascript",
            TemplateCategory.STANDALONE, "javascript", "node", listOf("javascript", "node", "js", "脚本")
        ),
        ProjectTemplate(
            "html-site", "HTML 静态网站", "多页面静态站点：HTML + CSS + JS，附带资源引用自检脚本", "html",
            TemplateCategory.STANDALONE, "html", "static", listOf("html", "静态", "网页", "site")
        ),
        ProjectTemplate(
            "css-project", "CSS 样式工程", "纯 CSS 工程：设计令牌 + 基础层 + 组件层，附带 var() 与括号配对自检", "css",
            TemplateCategory.STANDALONE, "css", "design-tokens", listOf("css", "样式", "design tokens")
        ),
        ProjectTemplate(
            "c-console", "C 控制台", "标准 C17 工程：clang 编译 + Makefile，多文件与头文件结构", "c",
            TemplateCategory.STANDALONE, "c", "clang", listOf("c", "clang", "console", "控制台")
        ),
        ProjectTemplate(
            "typescript-empty", "TypeScript 空项目", "tsc 编译 + node 运行的最小 TypeScript 工程（严格模式，含 node:test 自测）", "typescript",
            TemplateCategory.STANDALONE, "typescript", "tsc", listOf("typescript", "tsc", "ts", "node", "nodejs")
        ),
        ProjectTemplate(
            "typescript-lib", "TypeScript 库", "可发布的库骨架：tsc 产出 dist + .d.ts 类型声明 + node:test 测试", "typescript",
            TemplateCategory.STANDALONE, "typescript", "library", listOf("typescript", "lib", "declaration", "npm", "库")
        ),
        ProjectTemplate(
            "typescript-node-cli", "TypeScript CLI", "命令行工具：argv 解析、可执行 bin 入口、tsx 热运行", "typescript",
            TemplateCategory.STANDALONE, "typescript", "cli", listOf("typescript", "cli", "tsx", "argv", "命令行")
        )
    )

    /** 全部分类（按顺序），供向导渲染分组标题。 */
    fun categories(): List<TemplateCategory> = TemplateCategory.entries.sortedBy { it.order }

    /** 某分类下的模板；无模板的分类不会出现在向导中。 */
    fun byCategory(category: TemplateCategory): List<ProjectTemplate> = templates.filter { it.category == category }

    /** 按关键字过滤（匹配名称/描述/语言/框架/标签），空串返回全部。 */
    fun search(keyword: String): List<ProjectTemplate> {
        val k = keyword.trim().lowercase()
        if (k.isEmpty()) return templates
        return templates.filter { t ->
            t.name.lowercase().contains(k) || t.description.lowercase().contains(k) ||
                t.language.lowercase().contains(k) || t.framework.lowercase().contains(k) ||
                t.tags.any { it.lowercase().contains(k) }
        }
    }

    fun find(templateId: String): ProjectTemplate? = templates.firstOrNull { it.id == templateId }

    /**
     * 按模板创建项目。
     *
     * @throws IllegalArgumentException 模板不存在，或目标目录非空
     */
    fun create(templateId: String, root: File, packageName: String = "com.example.nebulaforge"): File {
        val template = find(templateId) ?: error("未知项目模板：$templateId")
        require(!root.exists() || root.listFiles().isNullOrEmpty()) { "目标目录非空：${root.absolutePath}" }
        root.mkdirs()
        // 包名只在需要包结构的模板里生效（Android/Xposed），其他栈按项目名生成模块标识。
        val pkg = packageName.ifBlank { "com.example.nebulaforge" }
        val moduleName = root.name.replace(Regex("[^A-Za-z0-9_.-]"), "-").ifBlank { "nebulaforge-project" }
        when (templateId) {
            "android-empty", "android-empty-compose" -> createAndroid(templateId, root, pkg)
            "flutter-empty" -> createFlutter(root, moduleName)
            "web-vite" -> createVue(root, moduleName)
            "react-vite" -> createReact(root, moduleName)
            "next-react" -> createNext(root, moduleName)
            "svelte-vite" -> createSvelte(root, moduleName)
            "angular" -> createAngular(root, moduleName)
            "cpp-cmake" -> createCpp(root)
            "xposed-module" -> createXposed(root, pkg)
            "lua-script" -> createLua(root, moduleName)
            "go-gin" -> createGo(root, moduleName, GoFramework.GIN)
            "go-echo" -> createGo(root, moduleName, GoFramework.ECHO)
            "go-fiber" -> createGo(root, moduleName, GoFramework.FIBER)
            "go-gorm-gin" -> createGo(root, moduleName, GoFramework.GIN_GORM)
            "node-express" -> createNodeExpress(root, moduleName)
            "node-nest" -> createNodeNest(root, moduleName)
            "java-spring-boot" -> createSpringBoot(root, moduleName, pkg)
            "python-fastapi" -> createFastApi(root, moduleName)
            "python-django" -> createDjango(root, moduleName)
            "python-flask" -> createFlask(root, moduleName)
            "php-laravel" -> createLaravel(root, moduleName)
            "rust-axum" -> createRustAxum(root, moduleName)
            "java-console" -> createJavaConsole(root, pkg)
            "python-script" -> createPythonScript(root, moduleName)
            "javascript-node" -> createJavascriptNode(root, moduleName)
            "html-site" -> createHtmlSite(root, moduleName)
            "css-project" -> createCssProject(root, moduleName)
            "c-console" -> createCConsoleProject(root, moduleName)
            "typescript-empty" -> createTypeScriptEmpty(root, moduleName)
            "typescript-lib" -> createTypeScriptLib(root, moduleName)
            "typescript-node-cli" -> createTypeScriptCli(root, moduleName)
            "typescript-express" -> createTypeScriptExpress(root, moduleName)
            else -> error("模板 $templateId 缺少生成器实现")
        }
        File(root, ".nebulaforge").mkdirs()
        writeTemplateFile(
            root, ".nebulaforge/project.json",
            "{\"type\":\"${template.typeId}\",\"template\":\"$templateId\"," +
                "\"category\":\"${template.category.id}\",\"language\":\"${template.language}\"," +
                "\"framework\":\"${template.framework}\",\"wrapperProvisioned\":false}\n"
        )
        return root
    }

    // ------------------------------------------------------------------
    // 移动端
    // ------------------------------------------------------------------

    /**
     * Android 应用模板。
     *
     * compileSdk/targetSdk 固定 **34**，理由（真机取证）：
     *  - 内置用户态预装的平台就是 `android-sdk/platforms/android-34`（`minimalAndroidBuild` 也照此对齐），
     *    App 自身所有模块同样是 compileSdk=34；
     *  - 模板若写 35，而设备上只有 android-34，AGP 会在构建时直接失败
     *    （`Failed to find platform android-35` / `Failed to find target with hash string 'android-35'`），
     *    用户看到的却是「安卓空项目一建出来就编译不过」——正是这个错配造成的；
     *  - 模板里用到的 androidx（core-ktx 1.13.1 / appcompat 1.7.0 / compose-bom 2024.09.00）
     *    在 compileSdk=34 下均可编译。
     * 若要出更高 API 的包，改这里之前请先确认设备已装对应平台（设置 → 工具链 → 安装 SDK 组件）。
     */
    private fun createAndroid(id: String, root: File, packageName: String) {
        val pkg = packageName.replace('.', '/')
        writeTemplateFile(
            root, "settings.gradle.kts",
            """
            import org.gradle.api.initialization.resolve.RepositoriesMode

            // 国内镜像优先：repo.maven.apache.org / dl.google.com 在境内经常 `Read timed out`，
            // 会让 AGP / 插件 classpath 解析失败，构建在「配置工程」阶段就 BUILD FAILED
            // （真机取证：一次构建 67 处同类超时）。官方源保留在后面作为兜底。
            //
            // 必须逐条写 `maven { url = uri("...") }`：之前写成
            //     val mirrors = listOf(...)   +   mirrors.forEach { maven(it) }
            // 在 Gradle 8.5 Kotlin DSL 下编译不过（真机报错已复现）：
            //     Unresolved reference: mirrors / Unresolved reference: it
            // 脚本顶层的 val 在 pluginManagement{}/repositories{} 的接收者作用域里不可见，
            // 且 `maven` 也不是 String 上的函数 —— 结果是模板一建出来就
            // `BUILD FAILED: Script compilation errors`（用户看到的就是这个）。
            pluginManagement {
                repositories {
                    maven { url = uri("https://maven.aliyun.com/repository/google") }
                    maven { url = uri("https://maven.aliyun.com/repository/public") }
                    maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    maven { url = uri("https://maven.aliyun.com/repository/google") }
                    maven { url = uri("https://maven.aliyun.com/repository/public") }
                    maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "${root.name}"
            include(":app")
            """.trimIndent() + "\n"
        )
        writeTemplateFile(root, "build.gradle.kts", "plugins { id(\"com.android.application\") version \"8.6.0\" apply false; id(\"org.jetbrains.kotlin.android\") version \"2.0.20\" apply false; id(\"org.jetbrains.kotlin.plugin.compose\") version \"2.0.20\" apply false }\n")
        writeTemplateFile(root, "gradle.properties", "org.gradle.jvmargs=-Xmx1536m\nandroid.useAndroidX=true\nkotlin.code.style=official\n# 境内网络到官方仓库偶发慢/超时：放宽 HTTP 超时，避免依赖解析被默认 10s 打断\nsystemProp.org.gradle.internal.http.connectionTimeout=60000\nsystemProp.org.gradle.internal.http.socketTimeout=60000\n")
        val app = File(root, "app"); File(app, "src/main/java/$pkg").mkdirs(); File(app, "src/main/res/values").mkdirs()
        val compose = id == "android-empty-compose"
        writeTemplateFile(app, "build.gradle.kts", if (compose) """plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace="$packageName"; compileSdk=34
  defaultConfig { applicationId="$packageName"; minSdk=24; targetSdk=34; versionCode=1; versionName="1.0" }
  buildFeatures { compose = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core-ktx:1.13.1"); implementation("androidx.activity:activity-compose:1.9.2"); implementation(platform("androidx.compose:compose-bom:2024.09.00")); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.material3:material3") }""" else """plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace="$packageName"; compileSdk=34
  defaultConfig { applicationId="$packageName"; minSdk=24; targetSdk=34; versionCode=1; versionName="1.0" }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core-ktx:1.13.1"); implementation("androidx.appcompat:appcompat:1.7.0") }""")
        writeTemplateFile(app, "src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:theme="@style/AppTheme" android:label="Nebula Forge"><activity android:name=".MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>""")
        // 主题随模板形态走（都是真机踩过的坑）：
        //  - 传统 View 用 AppCompatActivity，必须配 AppCompat 主题，否则一启动就崩
        //    "You need to use a Theme.AppCompat theme (or descendant) with this activity"；
        //  - Compose 模板没有 appcompat 依赖，写 Theme.AppCompat.* 会在资源编译阶段
        //    "resource style/Theme.AppCompat... not found" 直接构建失败，所以用平台主题。
        val themeParent =
            if (compose) "android:style/Theme.Material.Light.NoActionBar" else "Theme.AppCompat.Light.NoActionBar"
        writeTemplateFile(app, "src/main/res/values/styles.xml", "<resources><style name=\"AppTheme\" parent=\"$themeParent\"/></resources>")
        if (compose) {
            writeTemplateFile(app, "src/main/java/$pkg/MainActivity.kt", "package $packageName\nimport android.os.Bundle\nimport androidx.activity.ComponentActivity\nimport androidx.activity.compose.setContent\nimport androidx.compose.material3.Text\nclass MainActivity: ComponentActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContent{Text(\"Hello Nebula Forge\")}}}\n")
        } else {
            File(app, "src/main/res/layout").mkdirs()
            writeTemplateFile(app, "src/main/res/layout/activity_main.xml", "<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"match_parent\"/>")
            writeTemplateFile(app, "src/main/java/$pkg/MainActivity.kt", "package $packageName\nimport android.os.Bundle\nimport androidx.appcompat.app.AppCompatActivity\nclass MainActivity: AppCompatActivity(){override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(R.layout.activity_main)}}\n")
        }
    }

    private fun createFlutter(root: File, moduleName: String) {
        val pkgName = moduleName.lowercase().replace('-', '_').replace('.', '_')
        writeTemplateFile(root, "pubspec.yaml", "name: $pkgName\ndescription: Nebula Forge Flutter project\nenvironment:\n  sdk: '>=3.0.0 <4.0.0'\ndependencies:\n  flutter:\n    sdk: flutter\nflutter:\n  uses-material-design: true\n")
        writeTemplateFile(root, "lib/main.dart", "import 'package:flutter/material.dart';\nvoid main()=>runApp(const MaterialApp(home: Scaffold(body: Center(child: Text('Nebula Forge')))));\n")
    }

    // ------------------------------------------------------------------
    // 原生与系统
    // ------------------------------------------------------------------

    private fun createCpp(root: File) {
        writeTemplateFile(root, "CMakeLists.txt", "cmake_minimum_required(VERSION 3.20)\nproject(NebulaCpp LANGUAGES CXX)\nset(CMAKE_CXX_STANDARD 17)\nadd_executable(nebulacpp src/main.cpp)\n")
        File(root, "src").mkdirs()
        writeTemplateFile(root, "src/main.cpp", "#include <iostream>\nint main(){std::cout<<\"Nebula Forge C++\\n\";return 0;}\n")
    }

    // ------------------------------------------------------------------
    // Lua
    // ------------------------------------------------------------------

    /**
     * Lua 5.4 工程骨架。
     *
     * 只依赖标准库，不引入 luarocks / busted 等外部依赖，保证「新建即能跑」：
     * `lua5.4 main.lua` 可运行，`luac5.4 -p` / `lua tests/test_hello.lua` 可自检。
     * .luarc.json 让 lua-language-server 按 Lua 5.4 解析，避免 LSP 按旧版本诊断。
     */
    private fun createLua(root: File, moduleName: String) {
        val modName = moduleName.lowercase().replace('-', '_').replace('.', '_')
        writeTemplateFile(
            root, ".luarc.json",
            "{\n" +
                "  \"runtime.version\": \"Lua 5.4\",\n" +
                "  \"workspace.checkThirdParty\": false,\n" +
                "  \"diagnostics.disable\": [\"lowercase-global\"]\n" +
                "}\n"
        )
        writeTemplateFile(
            root, "main.lua",
            "-- $modName · 入口文件\n" +
                "-- 运行：lua5.4 main.lua（若未装 lua5.4，IDE 会自动回退到 lua / luajit）\n" +
                "local hello = require(\"lib.hello\")\n\n" +
                "local function main(argv)\n" +
                "  local name = argv[1] or \"Nebula Forge\"\n" +
                "  print(hello.greeting(name))\n" +
                "end\n\n" +
                "main(arg)\n"
        )
        writeTemplateFile(
            root, "lib/hello.lua",
            "-- 一个最小可复用模块\n" +
                "local M = {}\n\n" +
                "--- 返回问候语\n" +
                "-- @param name string|nil 称呼\n" +
                "-- @return string\n" +
                "function M.greeting(name)\n" +
                "  return string.format(\"Hello, %s! (%s)\", name or \"world\", _VERSION)\n" +
                "end\n\n" +
                "return M\n"
        )
        writeTemplateFile(
            root, "tests/test_hello.lua",
            "-- 纯标准库断言测试，无需 busted：lua5.4 tests/test_hello.lua\n" +
                "package.path = \"./?.lua;\" .. package.path\n" +
                "local hello = require(\"lib.hello\")\n\n" +
                "local failures = 0\n" +
                "local function check(name, cond)\n" +
                "  if cond then\n" +
                "    print(\"ok   - \" .. name)\n" +
                "  else\n" +
                "    failures = failures + 1\n" +
                "    io.stderr:write(\"FAIL - \" .. name .. \"\\n\")\n" +
                "  end\n" +
                "end\n\n" +
                "local text = hello.greeting(\"Nebula\")\n" +
                "check(\"greeting 包含传入的名字\", string.find(text, \"Nebula\", 1, true) ~= nil)\n" +
                "check(\"greeting 包含 Lua 版本\", string.find(text, \"Lua\", 1, true) ~= nil)\n" +
                "check(\"缺省名字回退到 world\", string.find(hello.greeting(), \"world\", 1, true) ~= nil)\n\n" +
                "if failures > 0 then\n" +
                "  os.exit(1)\n" +
                "end\n" +
                "print(\"all tests passed\")\n"
        )
        writeTemplateFile(
            root, "README.md",
            "# $modName\n\n" +
                "Lua 5.4 标准库工程骨架。\n\n" +
                "## 运行\n\n" +
                "```sh\nlua5.4 main.lua\n```\n\n" +
                "## 测试\n\n" +
                "```sh\nlua5.4 tests/test_hello.lua\n```\n"
        )
    }

    /** tsconfig：不产出类型声明（应用 / CLI）。 */
    private val TS_CONFIG_BASIC: String = """
        {
          "compilerOptions": {
            "target": "ES2022",
            "lib": ["ES2022"],
            "module": "commonjs",
            "moduleResolution": "node",
            "rootDir": "src",
            "outDir": "dist",
            "strict": true,
            "noUnusedLocals": true,
            "noImplicitReturns": true,
            "esModuleInterop": true,
            "skipLibCheck": true,
            "forceConsistentCasingInFileNames": true,
            "sourceMap": true,
            "declaration": false
          },
          "include": ["src/**/*.ts"],
          "exclude": ["node_modules", "dist"]
        }
        """.trimIndent()

    /** tsconfig：产出 .d.ts（库）。 */
    private val TS_CONFIG_DECLARATION: String = """
        {
          "compilerOptions": {
            "target": "ES2022",
            "lib": ["ES2022"],
            "module": "commonjs",
            "moduleResolution": "node",
            "rootDir": "src",
            "outDir": "dist",
            "strict": true,
            "declaration": true,
            "esModuleInterop": true,
            "skipLibCheck": true,
            "forceConsistentCasingInFileNames": true,
            "sourceMap": true
          },
          "include": ["src/**/*.ts"],
          "exclude": ["node_modules", "dist"]
        }
        """.trimIndent()

    /** .gitignore 文本（node_modules / dist 必须忽略）。 */
    private val GITIGNORE_TEXT: String = """
        node_modules/
        dist/
        *.log
        .DS_Store
        """.trimIndent()

    /** 应用 / CLI / 库 共用的 scripts。 */
    private val TS_SCRIPTS_APP: String = """
        {
            "build": "tsc -p tsconfig.json",
            "start": "npm run build && node dist/index.js",
            "dev": "tsx src/index.ts",
            "test": "tsc -p tsconfig.json && node --test dist/__tests__"
          }
        """.trimIndent()

    /** 三个模板共用的 devDependencies。 */
    private val TS_DEV_DEPS: String = """
        {
            "typescript": "^5.4.5",
            "ts-node": "^10.9.2",
            "tsx": "^4.16.2",
            "@types/node": "^20.14.9"
          }
        """.trimIndent()

    // ------------------------------------------------------------------
    // TypeScript（tsconfig.json 驱动：tsc 构建 → node 运行）
    // ------------------------------------------------------------------

    /**
     * 公共 tsconfig 文本。
     *
     * 取舍理由（真机可编译性优先）：
     *  - `module: commonjs` + `moduleResolution: node`，且 package.json 里**不写**
     *    `"type": "module"`：于是 `node dist/index.js` 可以直接运行，不必纠缠 ESM 的
     *    扩展名与解析规则；
     *  - `target: ES2022`：Node 18+ 原生支持，无需再降级；
     *  - `strict` + `noUnusedLocals`：模板一建出来就按严格类型检查，
     *    否则用户第一次照抄示例就掉进隐式 any 的坑；
     *  - `rootDir: src` / `outDir: dist`：源与产物分离，node_modules 与 dist 不进编译。
     */
    private fun tsConfigText(declaration: Boolean): String =
        if (declaration) TS_CONFIG_DECLARATION else TS_CONFIG_BASIC

    /** 写 tsconfig.json。 */
    private fun wfTsConfig(root: File, declaration: Boolean) {
        writeTemplateFile(root, "tsconfig.json", tsConfigText(declaration))
    }

    /** 写 .gitignore。 */
    private fun wfGitignore(root: File) {
        writeTemplateFile(root, ".gitignore", GITIGNORE_TEXT)
    }

    /**
     * 写 package.json。
     *
     * scripts 的口径与 IDE 的构建/运行链路对齐（见 TasksJson 的 npm 分支）：
     *  - build：`tsc -p tsconfig.json`（IDE「构建」按钮与构建面板第一条任务都走这里）；
     *  - start：`node dist/index.js`（先构建再运行的常规路径）；
     *  - dev：`tsx src/index.ts`（免构建直接跑，改完即生效）；
     *  - test：先编译再做类型检查，然后跑 `node --test`。
     * devDependencies 里同时声明 typescript / ts-node / tsx：即使 IDE 未装全局
     * TypeScript 工具链，`npm install` 也能把本地编译器装齐（构建任务会先自愈执行 npm install）。
     */
    private fun wfPackageJson(root: File, moduleName: String, description: String, main: String, scripts: String) {
        writeTemplateFile(
            root, "package.json",
            "{\n" +
                "  \"name\": \"" + moduleName + "\",\n" +
                "  \"version\": \"0.1.0\",\n" +
                "  \"private\": true,\n" +
                "  \"description\": \"" + description + "\",\n" +
                "  \"main\": \"" + main + "\",\n" +
                "  \"scripts\": " + scripts + ",\n" +
                "  \"devDependencies\": " + TS_DEV_DEPS + "\n" +
                "}\n"
        )
    }

    /** 写「库」版 package.json：额外声明 types 入口与 files 白名单。 */
    private fun wfPackageJsonWithTypes(root: File, moduleName: String, main: String, scripts: String) {
        writeTemplateFile(
            root, "package.json",
            "{\n" +
                "  \"name\": \"" + moduleName + "\",\n" +
                "  \"version\": \"0.1.0\",\n" +
                "  \"private\": true,\n" +
                "  \"description\": \"TypeScript 库骨架（NebulaForge IDE 生成）\",\n" +
                "  \"main\": \"" + main + "\",\n" +
                "  \"types\": \"dist/index.d.ts\",\n" +
                "  \"files\": [\"dist\", \"README.md\"],\n" +
                "  \"scripts\": " + scripts + ",\n" +
                "  \"devDependencies\": " + TS_DEV_DEPS + "\n" +
                "}\n"
        )
    }

    /**
     * TypeScript 空项目：最小「可编译 + 可运行 + 可自测」工程。
     *
     * 目录：
     *   src/index.ts                  入口（含 main 与可复用函数）
     *   src/__tests__/smoke.test.ts    node:test 自测（零测试框架依赖）
     */
    private fun createTypeScriptEmpty(root: File, moduleName: String) {
        wfPackageJson(root, moduleName, "TypeScript 工程骨架（NebulaForge IDE 生成）", "dist/index.js", TS_SCRIPTS_APP)
        wfTsConfig(root, declaration = false)
        writeTemplateFile(root, "src/index.ts", """
        /**
         * NebulaForge TypeScript 入口。
         *
         * 构建：npm run build   （tsc -p tsconfig.json，产物在 dist/）
         * 运行：npm start       （node dist/index.js）
         * 热运行：npm run dev    （tsx src/index.ts，免构建）
         * 测试：npm test        （编译后 node --test dist/__tests__）
         */

        /** 两个数字相加（示例：可被单测覆盖的纯函数）。 */
        export function add(a: number, b: number): number {
          return a + b;
        }

        function main(): void {
          const name: string = process.argv[2] ?? "Nebula Forge";
          console.log(`Hello, ${'$'}{name}! 2 + 3 = ${'$'}{add(2, 3)}`);
        }

        // 只有被直接执行时才跑 main；被 import 时保持安静（便于测试复用）。
        if (require.main === module) {
          main();
        }
        """.trimIndent())
        writeTemplateFile(root, "src/__tests__/smoke.test.ts", """
        import { strict as assert } from "node:assert";
        import { test } from "node:test";
        import { add } from "../index";

        // node:test + node:assert 是 Node 内置能力，所以这个模板不需要任何测试框架依赖。
        test("add 计算两数之和", () => {
          assert.equal(add(2, 3), 5);
          assert.equal(add(-1, 1), 0);
        });
        """.trimIndent())
        wfGitignore(root)
        writeTemplateFile(root, "README.md", """
        # TypeScript 工程骨架

        tsc 编译 → node 运行。以下命令都在本目录内执行。

        ## 环境

        需要 Node.js。若要在终端里直接用 `tsc` / `tsx`，请在 IDE「设置 → 工具链」里
        安装 **Node.js** 与 **TypeScript (tsc / ts-node / tsx)**；
        若只想用项目自带编译器，执行一次 `npm install` 即可（typescript 已在 devDependencies 中）。

        ## 命令

        ```sh
        npm install      # 安装本地编译器（IDE 已装全局工具链时可跳过）
        npm run build    # tsc -p tsconfig.json → dist/
        npm start        # 构建并运行（npm run build && node dist/index.js）
        npm run dev      # tsx src/index.ts（免构建热运行）
        npm test         # 编译后运行 node:test 测试
        ```

        ## 目录

        ```
        tsconfig.json               编译器配置（strict、rootDir=src、outDir=dist）
        src/index.ts                入口与可复用函数
        src/__tests__/smoke.test.ts node:test 自测
        ```
        """.trimIndent())
    }

    /**
     * TypeScript 库：可发布的库结构（dist + .d.ts + 测试）。
     *
     * 与「空项目」的差别：开启 declaration，并在 package.json 声明 types 入口与
     * files 白名单，这样 `npm pack` 出来的产物既有 JS 也有类型提示。
     */
    private fun createTypeScriptLib(root: File, moduleName: String) {
        wfPackageJsonWithTypes(root, moduleName, "dist/index.js", TS_SCRIPTS_APP)
        wfTsConfig(root, declaration = true)
        writeTemplateFile(root, "src/greeting.ts", """
        /** 生成问候语（库的核心 API，示例用）。 */
        export function greeting(name: string = "world"): string {
          return `Hello, ${'$'}{name}!`;
        }

        /** 库版本号，随 package.json 同步维护。 */
        export const VERSION = "0.1.0";
        """.trimIndent())
        writeTemplateFile(root, "src/index.ts", """
        // 库的统一出口：只在这里 re-export，避免调用方深路径依赖内部文件。
        export { greeting, VERSION } from "./greeting";
        """.trimIndent())
        writeTemplateFile(root, "src/__tests__/greeting.test.ts", """
        import { strict as assert } from "node:assert";
        import { test } from "node:test";
        import { greeting, VERSION } from "../index";

        test("greeting 使用传入的名字", () => {
          assert.match(greeting("Nebula"), /Nebula/);
        });

        test("greeting 缺省回退到 world", () => {
          assert.equal(greeting(), "Hello, world!");
        });

        test("VERSION 是语义化版本号", () => {
          assert.match(VERSION, /^\d+\.\d+\.\d+\$/);
        });
        """.trimIndent())
        wfGitignore(root)
        writeTemplateFile(root, "README.md", """
        # TypeScript 库骨架

        tsc 产出 `dist/` 与 `.d.ts` 类型声明。

        ## 命令

        ```sh
        npm install      # 安装本地编译器
        npm run build    # 产出 dist/（含 dist/index.d.ts）
        npm test         # 编译 + node:test 测试
        npm pack         # 打成可发布的 tgz（files 白名单只有 dist 与 README）
        ```

        ## 使用

        ```ts
        import { greeting, VERSION } from "你的包名";

        console.log(greeting("Nebula"), VERSION);
        ```

        ## 目录

        ```
        src/index.ts       库出口（只做 re-export）
        src/greeting.ts    实际实现
        src/__tests__/     测试（编译到 dist/__tests__）
        ```
        """.trimIndent())
    }

    /** CLI 的 scripts（start 指向编译产物 cli.js）。 */
    private val TS_SCRIPTS_CLI: String = """
        {
            "build": "tsc -p tsconfig.json",
            "start": "npm run build && node dist/cli.js",
            "dev": "tsx src/cli.ts",
            "test": "tsc -p tsconfig.json && node --test dist/__tests__"
          }
        """.trimIndent()

    /** Express 的 scripts（test 用 tsc 做类型检查，先编译再产出 dist）。 */
    private val TS_SCRIPTS_EXPRESS: String = """
        {
            "build": "tsc -p tsconfig.json",
            "start": "npm run build && node dist/server.js",
            "dev": "tsx watch src/server.ts",
            "test": "tsc -p tsconfig.json"
          }
        """.trimIndent()

    /** Express 运行时依赖。 */
    private val TS_DEPS_EXPRESS: String = """
        {
            "express": "^4.19.2"
          }
        """.trimIndent()

    /** Express 的类型包与编译工具。 */
    private val TS_DEV_DEPS_EXPRESS: String = """
        {
            "typescript": "^5.4.5",
            "tsx": "^4.16.2",
            "@types/node": "^20.14.9",
            "@types/express": "^4.17.21"
          }
        """.trimIndent()
    /** 写 CLI 版 package.json：额外声明 bin 入口，npm link 后可直接执行。 */
    private fun wfPackageJsonCli(root: File, moduleName: String) {
        writeTemplateFile(
            root, "package.json",
            "{\n" +
                "  \"name\": \"" + moduleName + "\",\n" +
                "  \"version\": \"0.1.0\",\n" +
                "  \"private\": true,\n" +
                "  \"description\": \"TypeScript 命令行工具骨架（NebulaForge IDE 生成）\",\n" +
                "  \"main\": \"dist/cli.js\",\n" +
                "  \"bin\": { \"" + moduleName + "\": \"dist/cli.js\" },\n" +
                "  \"scripts\": " + TS_SCRIPTS_CLI + ",\n" +
                "  \"devDependencies\": " + TS_DEV_DEPS + "\n" +
                "}\n"
        )
    }

    /** 写 Express 版 package.json：express 是运行时依赖，类型包放 devDependencies。 */
    private fun wfPackageJsonExpress(root: File, moduleName: String) {
        writeTemplateFile(
            root, "package.json",
            "{\n" +
                "  \"name\": \"" + moduleName + "\",\n" +
                "  \"version\": \"0.1.0\",\n" +
                "  \"private\": true,\n" +
                "  \"description\": \"TypeScript + Express 服务端骨架（NebulaForge IDE 生成）\",\n" +
                "  \"main\": \"dist/server.js\",\n" +
                "  \"scripts\": " + TS_SCRIPTS_EXPRESS + ",\n" +
                "  \"dependencies\": " + TS_DEPS_EXPRESS + ",\n" +
                "  \"devDependencies\": " + TS_DEV_DEPS_EXPRESS + "\n" +
                "}\n"
        )
    }

    /**
     * TypeScript CLI 工具：argv 解析 + bin 入口 + tsx 热运行。
     *
     * 目录：
     *   src/cli.ts                 入口（usage / 退出码 / shebang）
     *   src/args.ts                纯函数参数解析
     *   src/__tests__/args.test.ts node:test 测试
     */
    private fun createTypeScriptCli(root: File, moduleName: String) {
        wfPackageJsonCli(root, moduleName)
        wfTsConfig(root, declaration = false)
        writeTemplateFile(root, "src/args.ts", """
        /** 解析结果。 */
        export interface ParsedArgs {
          help: boolean;
          version: boolean;
          upper: boolean;
          name: string;
        }

        /**
         * 解析命令行参数（纯函数，便于单测）。
         *
         * 不引入 commander/yargs 这类依赖：模板要保持「零运行时依赖」，
         * 依赖越少，新建出来的工程越可能一次装成功、直接能跑。
         */
        export function parseArgs(argv: string[]): ParsedArgs {
          const result: ParsedArgs = { help: false, version: false, upper: false, name: "" };
          for (const arg of argv) {
            if (arg === "-h" || arg === "--help") {
              result.help = true;
            } else if (arg === "-v" || arg === "--version") {
              result.version = true;
            } else if (arg === "--upper") {
              result.upper = true;
            } else if (!arg.startsWith("-")) {
              result.name = arg;
            }
          }
          return result;
        }
        """.trimIndent())
        writeTemplateFile(root, "src/cli.ts", """
        #!/usr/bin/env node
        import { parseArgs } from "./args";

        const USAGE = [
          "用法: cli [选项] [名字]",
          "",
          "选项:",
          "  -h, --help     显示帮助",
          "  -v, --version  显示版本号",
          "  --upper        输出大写",
        ].join("\n");

        /** 程序主入口；返回进程退出码（0 = 成功）。 */
        export function main(argv: string[]): number {
          const parsed = parseArgs(argv);
          if (parsed.help) {
            console.log(USAGE);
            return 0;
          }
          if (parsed.version) {
            console.log("cli 0.1.0");
            return 0;
          }
          const name = parsed.name || "world";
          console.log(parsed.upper ? "Hello, " + name.toUpperCase() + "!" : "Hello, " + name + "!");
          return 0;
        }

        // 直接执行（node dist/cli.js）时运行；被 import 时不产生副作用。
        if (require.main === module) {
          process.exitCode = main(process.argv.slice(2));
        }
        """.trimIndent())
        writeTemplateFile(root, "src/__tests__/args.test.ts", """
        import { strict as assert } from "node:assert";
        import { test } from "node:test";
        import { parseArgs } from "../args";

        test("默认全部关闭、名字为空", () => {
          const parsed = parseArgs([]);
          assert.equal(parsed.help, false);
          assert.equal(parsed.version, false);
          assert.equal(parsed.upper, false);
          assert.equal(parsed.name, "");
        });

        test("识别 --help / --version / --upper", () => {
          const parsed = parseArgs(["--help", "--version", "--upper"]);
          assert.equal(parsed.help, true);
          assert.equal(parsed.version, true);
          assert.equal(parsed.upper, true);
        });

        test("位置参数作为名字", () => {
          assert.equal(parseArgs(["Nebula"]).name, "Nebula");
        });

        test("以 - 开头的参数不会被当成名字", () => {
          assert.equal(parseArgs(["--upper"]).name, "");
        });
        """.trimIndent())
        wfGitignore(root)
        writeTemplateFile(root, "README.md", """
        # TypeScript CLI

        命令行工具骨架：参数解析 + 可执行 bin 入口 + tsx 热运行。

        ## 命令

        ```sh
        npm install         # 安装本地编译器
        npm run build       # tsc → dist/
        npm start           # 构建并运行（npm run build && node dist/cli.js）
        npm run dev -- 名字  # tsx src/cli.ts（免构建热运行，透传参数用 --）
        npm test            # 编译 + node:test 测试
        node dist/cli.js --help
        ```

        ## 结构

        ```
        src/cli.ts          入口（含 usage 与退出码）
        src/args.ts         纯函数参数解析（被单测覆盖）
        src/__tests__/      测试
        ```

        ## 作为全局命令安装

        ```sh
        npm link            # 之后可直接执行 cli --help（package.json 里已声明 bin）
        ```
        """.trimIndent())
    }

    /**
     * TypeScript + Express 服务端。
     *
     * 与 JS 版（node-express）的差别：全链路类型化（含 @types/express），
     * 构建产物是 dist/ 而不是直接跑源码，因此部署时不需要 ts 运行时。
     *
     * 目录：
     *   src/server.ts              监听入口（读 PORT）
     *   src/app.ts                 app 组装（与监听分离，便于集成测试）
     *   src/routes/health.ts       健康检查路由
     */
    private fun createTypeScriptExpress(root: File, moduleName: String) {
        wfPackageJsonExpress(root, moduleName)
        wfTsConfig(root, declaration = false)
        writeTemplateFile(root, "src/app.ts", """
        import express, { Express, Request, Response } from "express";
        import { healthRouter } from "./routes/health";

        /**
         * 组装 Express 应用。
         *
         * 刻意与「监听端口」分离：这样将来接 supertest 之类的集成测试时，
         * 可以直接拿到 app 而不用真的占用端口。
         */
        export function createApp(): Express {
          const app = express();
          app.use(express.json());
          app.use("/health", healthRouter);
          app.get("/", (_req: Request, res: Response) => {
            res.json({ name: "TypeScript + Express", ok: true });
          });
          return app;
        }
        """.trimIndent())
        writeTemplateFile(root, "src/routes/health.ts", """
        import { Router, Request, Response } from "express";

        /** 健康检查路由：GET /health */
        export const healthRouter = Router();

        healthRouter.get("/", (_req: Request, res: Response) => {
          res.json({ status: "ok", uptimeSeconds: Math.round(process.uptime()) });
        });
        """.trimIndent())
        writeTemplateFile(root, "src/server.ts", """
        import { createApp } from "./app";

        // 端口优先取环境变量 PORT，便于在设备上换端口/被反向代理托管。
        const port = Number(process.env.PORT ?? 3000);
        const app = createApp();

        app.listen(port, () => {
          console.log("HTTP 服务已启动: http://127.0.0.1:" + port);
        });
        """.trimIndent())
        wfGitignore(root)
        writeTemplateFile(root, "README.md", """
        # TypeScript + Express

        类型安全的 Express 服务端骨架：`tsc` 构建、`tsx` 热运行。

        ## 环境

        在 IDE「设置 → 工具链」里安装 **Node.js** 与 **TypeScript (tsc / ts-node / tsx)**；
        或执行 `npm install` 用项目自带编译器（express 与 @types/* 已在 package.json 中声明）。

        ## 命令

        ```sh
        npm install      # 安装依赖
        npm run build    # tsc → dist/
        npm start        # 构建并启动（默认 3000 端口）
        npm run dev      # tsx watch src/server.ts（改完自动重启）
        npm test         # 类型检查（tsc --noEmit 等价于本模板的 tsc -p）
        PORT=8080 npm start   # 换端口
        ```

        ## 接口

        ```
        GET /          → {"name":"TypeScript + Express","ok":true}
        GET /health    → {"status":"ok","uptimeSeconds":N}
        ```

        ## 结构

        ```
        src/server.ts       启动与监听（读 PORT 环境变量）
        src/app.ts          组装 app（与监听分离，便于接入集成测试）
        src/routes/health.ts 健康检查路由
        ```
        """.trimIndent())
    }
}
