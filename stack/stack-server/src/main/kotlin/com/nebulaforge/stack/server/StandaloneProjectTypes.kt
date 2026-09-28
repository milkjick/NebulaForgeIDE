package com.nebulaforge.stack.server

import android.content.Context
import com.nebulaforge.core.projectmodel.BuildSystem
import com.nebulaforge.core.projectmodel.ProjectType
import java.io.File

private val BUILD_ARTIFACT_DIRS = setOf("build", "target", "node_modules", ".git")

private fun hasFile(root: File, vararg names: String): Boolean = names.any { File(root, it).isFile }

/** 是否存在指定扩展名的源文件（递归，跳过构建产物与隐藏目录）。 */
private fun hasSource(root: File, vararg extensions: String): Boolean = root.walkTopDown()
    .onEnter { dir -> dir == root || (dir.name !in BUILD_ARTIFACT_DIRS && !dir.name.startsWith(".")) }
    .any { it.isFile && extensions.any { ext -> it.extension.equals(ext, ignoreCase = true) } }

/**
 * 独立语言项目类型：Java / Python / JavaScript(Node) / HTML / CSS / C。
 *
 * 与 Go / Spring / FastAPI 这些"框架型"后端类型不同，这些工程**不依赖 Web 框架或
 * 重量级构建工具**，构建与运行就是一条真实的编译器/解释器命令
 * （见 [StandaloneCommands]）。因此只要工具链面板装了 JDK / Python / Node / Clang，
 * 新建出来的工程就能直接编译、运行、跑测试——不需要联网拉依赖。
 *
 * 识别策略：detect 只匹配"没有其它构建系统特征"的工程，避免抢走
 * Android / Spring / Vite / CMake / FastAPI 等已有类型的判定。
 */

// --------------------------------------------------------------------------
// Java（纯 JDK）
// --------------------------------------------------------------------------

class JavaConsoleProjectType(private val context: Context) : ProjectType {
    override val id = "java"
    override val displayNameKey = "standalone_java_name"

    override fun detect(projectRoot: File): Boolean {
        // 有 Maven / Gradle 特征时交给 java-backend 与 android 类型处理。
        if (hasFile(projectRoot, "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            return false
        }
        return hasSource(projectRoot, "java")
    }

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "java-console")
    override fun createRunConfiguration() =
        ServerRunConfiguration("java-console-run") { root, _ -> StandaloneCommands.runCommand("java-console", root) }

    override fun requiredLanguageServiceIds(): List<String> = listOf("java")
    override fun templateIds(): List<String> = listOf("java-console")
}

// --------------------------------------------------------------------------
// Python（标准库）
// --------------------------------------------------------------------------

class PythonScriptProjectType(private val context: Context) : ProjectType {
    override val id = "python"
    override val displayNameKey = "standalone_python_name"

    override fun detect(projectRoot: File): Boolean {
        // 有依赖清单 / Django 入口的工程交给 python-backend，避免抢走框架模板。
        if (hasFile(projectRoot, "requirements.txt", "pyproject.toml", "manage.py", "Pipfile")) return false
        return hasSource(projectRoot, "py")
    }

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "python-script")
    override fun createRunConfiguration() =
        ServerRunConfiguration("python-script-run") { root, _ -> StandaloneCommands.runCommand("python-script", root) }

    override fun requiredLanguageServiceIds(): List<String> = listOf("python")
    override fun templateIds(): List<String> = listOf("python-script")
}

// --------------------------------------------------------------------------
// JavaScript（纯 Node.js）
// --------------------------------------------------------------------------

class JavascriptNodeProjectType(private val context: Context) : ProjectType {
    override val id = "javascript"
    override val displayNameKey = "standalone_javascript_name"

    override fun detect(projectRoot: File): Boolean {
        // 带 package.json 的工程交给 Web 前端/后端类型（它们才需要 npm 生态）。
        if (hasFile(projectRoot, "package.json", "index.html")) return false
        return hasSource(projectRoot, "js", "mjs", "cjs")
    }

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "javascript-node")
    override fun createRunConfiguration() =
        ServerRunConfiguration("javascript-run") { root, _ -> StandaloneCommands.runCommand("javascript-node", root) }

    // typescript 语言服务的 extensions 里已包含 js/jsx，Node 项目复用它即可。
    override fun requiredLanguageServiceIds(): List<String> = listOf("typescript")
    override fun templateIds(): List<String> = listOf("javascript-node")
}

// --------------------------------------------------------------------------
// HTML 静态网站
// --------------------------------------------------------------------------

class HtmlStaticProjectType(private val context: Context) : ProjectType {
    override val id = "html"
    override val displayNameKey = "standalone_html_name"

    override fun detect(projectRoot: File): Boolean = File(projectRoot, "index.html").isFile

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "html-site")
    override fun createRunConfiguration() =
        ServerRunConfiguration("html-site-run") { root, _ -> StandaloneCommands.runCommand("html-site", root) }

    // 语言服务目录里没有 HTML/CSS 服务器，如实返回空列表，不伪造"有 LSP"。
    override fun requiredLanguageServiceIds(): List<String> = emptyList()
    override fun templateIds(): List<String> = listOf("html-site")
}

// --------------------------------------------------------------------------
// CSS 样式工程
// --------------------------------------------------------------------------

class CssProjectType(private val context: Context) : ProjectType {
    override val id = "css"
    override val displayNameKey = "standalone_css_name"

    override fun detect(projectRoot: File): Boolean {
        // 有 index.html 的工程归 html 类型；这里只接管"以 css/ 目录为主体"的样式工程。
        if (File(projectRoot, "index.html").isFile) return false
        if (File(projectRoot, "demo.html").isFile && hasSource(projectRoot, "css")) return true
        val cssDir = File(projectRoot, "css")
        return cssDir.isDirectory && hasSource(cssDir, "css")
    }

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "css-project")
    override fun createRunConfiguration() =
        ServerRunConfiguration("css-project-run") { root, _ -> StandaloneCommands.runCommand("css-project", root) }

    override fun requiredLanguageServiceIds(): List<String> = emptyList()
    override fun templateIds(): List<String> = listOf("css-project")
}

// --------------------------------------------------------------------------
// C（标准 C17）
// --------------------------------------------------------------------------

class CConsoleProjectType(private val context: Context) : ProjectType {
    override val id = "c"
    override val displayNameKey = "standalone_c_name"

    override fun detect(projectRoot: File): Boolean {
        // CMake 工程（含 C 源文件）交给 cpp 类型，这里只处理"直接用 clang 编译"的工程。
        if (File(projectRoot, "CMakeLists.txt").isFile) return false
        return hasSource(projectRoot, "c")
    }

    override fun createBuildSystem(): BuildSystem = ServerBuildSystem(context, "c-console")
    override fun createRunConfiguration() =
        ServerRunConfiguration("c-console-run") { root, _ -> StandaloneCommands.runCommand("c-console", root) }

    // 语言服务目录里 clangd 的 id 是 "cpp"（extensions 覆盖 c/h），必须用它才能被真正拉起。
    override fun requiredLanguageServiceIds(): List<String> = listOf("cpp")
    override fun templateIds(): List<String> = listOf("c-console")
}
