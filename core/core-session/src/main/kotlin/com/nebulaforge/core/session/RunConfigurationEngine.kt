package com.nebulaforge.core.session

import com.nebulaforge.core.projectmodel.ProjectTypeRegistry
import com.nebulaforge.core.projectmodel.RunConfigurationSpec
import java.io.File

/** Resolves persisted configuration into a concrete stack command without changing stack implementations. */
class RunConfigurationEngine {
    fun command(spec: RunConfigurationSpec): String {
        val root = File(spec.projectPath)
        val type = ProjectTypeRegistry.getById(spec.typeId) ?: error("未知项目类型：${spec.typeId}")
        val config = type.createRunConfiguration() ?: error("项目类型 ${spec.typeId} 没有运行配置")
        require(config.supports(spec.mode)) { "运行模式 ${spec.mode} 不受 ${spec.typeId} 支持" }
        val base = config.command(root, spec.mode)
        val args = spec.arguments.joinToString(" ") { shellQuote(it) }
        return if (args.isBlank()) base else "$base $args"
    }

    fun environment(spec: RunConfigurationSpec, base: Map<String, String>): Map<String, String> =
        base.toMutableMap().apply { putAll(spec.environment) }

    fun workingDirectory(spec: RunConfigurationSpec): File =
        File(spec.workingDirectory?.takeIf { it.isNotBlank() } ?: spec.projectPath).canonicalFile

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
