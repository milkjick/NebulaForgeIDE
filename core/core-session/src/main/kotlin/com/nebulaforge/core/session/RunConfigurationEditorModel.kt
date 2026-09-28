package com.nebulaforge.core.session

import com.nebulaforge.core.projectmodel.ProjectTypeRegistry
import com.nebulaforge.core.projectmodel.RunConfigurationSpec
import java.io.File
import java.util.UUID

/**
 * UI-independent model for editing Run Configurations.
 * The editor never manufactures stack commands; it only edits the persisted spec.
 */
object RunConfigurationEditorModel {
    data class ModeOption(val id: String, val label: String)

    fun modes(typeId: String): List<ModeOption> = when (typeId) {
        "android" -> listOf(ModeOption("debug", "Debug"), ModeOption("run", "Run"))
        "flutter" -> listOf(
            ModeOption("run", "Debug"), ModeOption("profile", "Profile"),
            ModeOption("release", "Release"), ModeOption("web", "Web")
        )
        "web-frontend", "web-backend" -> listOf(ModeOption("dev", "Dev"), ModeOption("start", "Start"))
        "cpp" -> listOf(ModeOption("run", "运行"), ModeOption("build", "构建"))
        // 后端与独立语言工程：命令推导只区分「运行」（构建走「构建」面板的任务列表）。
        // 这些类型过去没有条目 → sanitizeMode 返回空串 → 编辑器保存/复制配置时报
        // 「当前项目类型没有可用运行模式」，等于配置只读。
        "go-backend", "java-backend", "python-backend", "php-backend", "rust-backend",
        "lua", "java", "python", "javascript", "html", "css", "c" -> listOf(ModeOption("run", "运行"))
        else -> emptyList()
    }

    fun sanitizeMode(typeId: String, requested: String): String =
        modes(typeId).firstOrNull { it.id == requested }?.id ?: modes(typeId).firstOrNull()?.id.orEmpty()

    fun create(projectRoot: File, typeId: String, name: String? = null): RunConfigurationSpec {
        val type = ProjectTypeRegistry.getById(typeId) ?: error("未知项目类型：$typeId")
        val defaults = com.nebulaforge.core.projectmodel.RunConfigurationDefaults.forProject(projectRoot, type)
        val base = defaults.firstOrNull() ?: RunConfigurationSpec(
            id = "run-${UUID.randomUUID()}", name = "${typeId} Run",
            projectPath = projectRoot.canonicalPath, typeId = typeId,
            mode = modes(typeId).firstOrNull()?.id ?: "run"
        )
        return base.copy(
            id = "run-${UUID.randomUUID()}",
            name = name?.takeIf { it.isNotBlank() } ?: "${base.name} Copy",
            mode = sanitizeMode(typeId, base.mode),
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        )
    }

    /** Shell-like tokenization for the UI argument field; preserves simple quoted arguments. */
    fun parseArguments(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaping = false
        for (c in text) {
            if (escaping) { current.append(c); escaping = false; continue }
            if (c == '\\') { escaping = true; continue }
            if (quote != null) {
                if (c == quote) quote = null else current.append(c)
            } else when (c) {
                '\'', '"' -> quote = c
                ' ', '\t', '\n', '\r' -> if (current.isNotEmpty()) { result += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        if (escaping) current.append('\\')
        if (quote != null) throw IllegalArgumentException("启动参数存在未闭合引号")
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    fun parseEnvironment(text: String): Result<Map<String, String>> = runCatching {
        val map = linkedMapOf<String, String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val i = line.indexOf('=')
            require(i > 0) { "环境变量必须使用 KEY=VALUE：$line" }
            val key = line.substring(0, i).trim()
            val value = line.substring(i + 1)
            require(key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "非法环境变量名：$key" }
            require('\u0000' !in value) { "环境变量包含非法字符：$key" }
            map[key] = value
        }
        map
    }
}
