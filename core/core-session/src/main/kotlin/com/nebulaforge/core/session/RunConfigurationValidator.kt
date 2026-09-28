package com.nebulaforge.core.session

import com.nebulaforge.core.projectmodel.RunConfigurationSpec
import com.nebulaforge.core.projectmodel.ProjectTypeRegistry
import java.io.File

/** Validates persisted Run Configuration data before any process is started. */
object RunConfigurationValidator {
    data class Result(val ok: Boolean, val message: String? = null)

    fun validate(spec: RunConfigurationSpec): Result {
        val root = runCatching { File(spec.projectPath).canonicalFile }.getOrElse { return Result(false, "项目路径无效") }
        if (!root.isDirectory) return Result(false, "项目目录不存在：${root.path}")
        val type = ProjectTypeRegistry.getById(spec.typeId) ?: return Result(false, "未知项目类型：${spec.typeId}")
        if (!type.detect(root)) return Result(false, "项目类型与运行配置不匹配：${spec.typeId}")
        val config = type.createRunConfiguration() ?: return Result(false, "项目类型没有运行配置：${spec.typeId}")
        if (!config.supports(spec.mode)) return Result(false, "运行模式不受支持：${spec.mode}")
        spec.arguments.forEach { if ('\u0000' in it) return Result(false, "运行参数包含非法字符") }
        spec.environment.forEach { (k, v) ->
            if (!k.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return Result(false, "环境变量名非法：$k")
            if ('\u0000' in v) return Result(false, "环境变量 $k 包含非法字符")
        }
        spec.port?.let { if (it !in 1..65535) return Result(false, "端口超出范围：$it") }
        if (spec.workingDirectory != null) {
            val work = runCatching { File(spec.workingDirectory).canonicalFile }.getOrElse { return Result(false, "工作目录无效") }
            if (!work.isDirectory) return Result(false, "工作目录不存在：${work.path}")
        }
        return Result(true)
    }
}
