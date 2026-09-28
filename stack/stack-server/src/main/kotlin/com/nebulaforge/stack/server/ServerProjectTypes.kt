package com.nebulaforge.stack.server

import android.content.Context
import com.nebulaforge.core.projectmodel.BuildSystem
import com.nebulaforge.core.projectmodel.ProjectType
import java.io.File

/**
 * 后端语言栈的 [ProjectType] 实现集合。
 *
 * 遵循 [ProjectType] 的约定：新增一门语言只需实现接口 + 注册，不改核心导航/编辑器。
 * 这些类型在应用启动时注册到 [com.nebulaforge.core.projectmodel.ProjectTypeRegistry]，
 * 注册顺序放在 Web 之后，避免 package.json 项目被误判为后端。
 */
private fun readIfFile(file: File): String = if (file.isFile) runCatching { file.readText() }.getOrDefault("") else ""

// --------------------------------------------------------------------------
// Go
// --------------------------------------------------------------------------

class GoBackendProjectType(private val context: Context) : ProjectType {
    override val id = "go-backend"
    override val displayNameKey = "stack_go_backend_name"
    override fun detect(projectRoot: File): Boolean = File(projectRoot, "go.mod").isFile
    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "go")
    override fun createRunConfiguration() = ServerRunConfiguration("go-run") { _, _ -> "go run ." }
    override fun requiredLanguageServiceIds(): List<String> = listOf("gopls")
    override fun templateIds(): List<String> = listOf("go-gin", "go-echo", "go-fiber", "go-gorm-gin")
}

// --------------------------------------------------------------------------
// Java（Spring Boot / Maven）
// --------------------------------------------------------------------------

class JavaBackendProjectType(private val context: Context) : ProjectType {
    override val id = "java-backend"
    override val displayNameKey = "stack_java_backend_name"

    override fun detect(projectRoot: File): Boolean {
        val pom = File(projectRoot, "pom.xml")
        if (pom.isFile && readIfFile(pom).contains("spring-boot")) return true
        val gradle = File(projectRoot, "build.gradle.kts")
        val gradleGroovy = File(projectRoot, "build.gradle")
        val text = readIfFile(gradle) + readIfFile(gradleGroovy)
        return text.contains("spring-boot") || text.contains("org.springframework.boot")
    }

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "maven")
    override fun createRunConfiguration() = ServerRunConfiguration("java-run") { root, _ ->
        if (File(root, "pom.xml").isFile) "mvn -q spring-boot:run" else "./gradlew bootRun"
    }
    override fun requiredLanguageServiceIds(): List<String> = listOf("java")
    override fun templateIds(): List<String> = listOf("java-spring-boot")
}

// --------------------------------------------------------------------------
// Python（FastAPI / Django / Flask）
// --------------------------------------------------------------------------

class PythonBackendProjectType(private val context: Context) : ProjectType {
    override val id = "python-backend"
    override val displayNameKey = "stack_python_backend_name"

    override fun detect(projectRoot: File): Boolean =
        File(projectRoot, "requirements.txt").isFile ||
            File(projectRoot, "pyproject.toml").isFile ||
            File(projectRoot, "manage.py").isFile

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "python")

    override fun createRunConfiguration() = ServerRunConfiguration("python-run") { root, _ ->
        when {
            File(root, "manage.py").isFile -> "python manage.py runserver 0.0.0.0:8000"
            File(root, "main.py").isFile -> "python -m uvicorn main:app --host 0.0.0.0 --port 8000"
            File(root, "app.py").isFile -> "python app.py"
            else -> "python -m http.server 8000"
        }
    }

    override fun requiredLanguageServiceIds(): List<String> = listOf("python")
    override fun templateIds(): List<String> = listOf("python-fastapi", "python-django", "python-flask")
}

// --------------------------------------------------------------------------
// PHP（Laravel）
// --------------------------------------------------------------------------

class PhpBackendProjectType(private val context: Context) : ProjectType {
    override val id = "php-backend"
    override val displayNameKey = "stack_php_backend_name"

    override fun detect(projectRoot: File): Boolean {
        val composer = File(projectRoot, "composer.json")
        if (composer.isFile && readIfFile(composer).contains("laravel")) return true
        return File(projectRoot, "artisan").isFile
    }

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "composer")

    override fun createRunConfiguration() = ServerRunConfiguration("php-run") { root, _ ->
        if (File(root, "artisan").isFile) "php artisan serve --host 0.0.0.0 --port 8000"
        else "php -S 0.0.0.0:8000 -t public"
    }

    override fun requiredLanguageServiceIds(): List<String> = listOf("php")
    override fun templateIds(): List<String> = listOf("php-laravel")
}

// --------------------------------------------------------------------------
// Rust（Axum）
// --------------------------------------------------------------------------

class RustBackendProjectType(private val context: Context) : ProjectType {
    override val id = "rust-backend"
    override val displayNameKey = "stack_rust_backend_name"
    override fun detect(projectRoot: File): Boolean = File(projectRoot, "Cargo.toml").isFile
    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "cargo")
    override fun createRunConfiguration() = ServerRunConfiguration("rust-run") { _, _ -> "cargo run" }
    override fun requiredLanguageServiceIds(): List<String> = listOf("rust-analyzer")
    override fun templateIds(): List<String> = listOf("rust-axum")
}

// --------------------------------------------------------------------------
// Lua（标准库脚本 / 模块）
// --------------------------------------------------------------------------

class LuaProjectType(private val context: Context) : ProjectType {
    override val id = "lua"
    override val displayNameKey = "stack_lua_name"

    override fun detect(projectRoot: File): Boolean {
        if (File(projectRoot, "main.lua").isFile || File(projectRoot, "init.lua").isFile) return true
        if (File(projectRoot, ".luarc.json").isFile) return true
        return projectRoot.listFiles()?.any { it.isFile && it.extension.equals("lua", ignoreCase = true) } == true
    }

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "lua")

    override fun createRunConfiguration() = ServerRunConfiguration("lua-run") { root, _ ->
        val entry = when {
            File(root, "main.lua").isFile -> "main.lua"
            File(root, "init.lua").isFile -> "init.lua"
            File(root, "src/main.lua").isFile -> "src/main.lua"
            else -> "main.lua"
        }
        LuaCommands.RUNNER + "lua_run " + entry
    }

    override fun requiredLanguageServiceIds(): List<String> = listOf("lua")
    override fun templateIds(): List<String> = listOf("lua-script")
}

/** 供应用启动时批量注册：顺序即 detect 优先级（放在 Web 之后）。 */
fun serverProjectTypes(context: Context): List<ProjectType> = listOf(
    GoBackendProjectType(context),
    JavaBackendProjectType(context),
    PythonBackendProjectType(context),
    PhpBackendProjectType(context),
    RustBackendProjectType(context),
    // ---- 独立语言项目（不依赖框架/构建工具） ----
    // 顺序即优先级：HTML 先于 CSS/JS（避免静态站点被 JS 类型抢走），
    // Java/Python 已排除 Maven/Gradle/框架特征，不会抢占上面的后端类型。
    HtmlStaticProjectType(context),
    CssProjectType(context),
    JavascriptNodeProjectType(context),
    JavaConsoleProjectType(context),
    PythonScriptProjectType(context),
    CConsoleProjectType(context),
    // Lua 的 detect 依赖 *.lua，放在最后，避免抢占其它栈的识别。
    LuaProjectType(context)
)
