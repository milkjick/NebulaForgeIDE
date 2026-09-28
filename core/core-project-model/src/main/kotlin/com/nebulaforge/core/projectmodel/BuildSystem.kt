package com.nebulaforge.core.projectmodel

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 统一构建入口。各技术栈各自实现（Gradle / pub / npm / CMake），
 * 上层 UI（构建按钮、进度条、Build 工具窗）只依赖本接口，不感知底层具体构建工具。
 *
 * 对应开发方案第 2.2 节 / 第三章。
 */
interface BuildSystem {
    /** 构建系统唯一标识，如 "gradle", "pub", "npm", "cmake" */
    val id: String

    /**
     * 执行构建任务，返回 Flow 以支持流式进度上报（供 Build 工具窗实时刷新）。
     *
     * @param task 构建任务标识（如 Gradle 的 "assembleDebug"）
     * @param workingDir 项目根目录
     * @param env 环境变量表，通常来自 Environment.buildGradleEnv() 或对应技术栈的等价函数
     */
    fun build(task: String, workingDir: File, env: Map<String, String>): Flow<BuildEvent>

    /** 列出该项目可用的构建任务，供 UI 下拉选择 */
    suspend fun listAvailableTasks(workingDir: File): List<String>

    /** 解析构建失败输出，提取结构化错误列表 */
    fun parseErrors(rawOutput: String): List<BuildError>
}

/**
 * 全局单例注册表：核心模块与插件均通过此对象注册/查询 [BuildSystem]，与 [ProjectTypeRegistry] 对称。
 *
 * 上层（Build 工具窗、流水线编排器）通过 id 取构建系统，避免在多处硬编码
 * `GradleBuildSystem()` / `NpmBuildSystem()` 的构造，插件新增技术栈时无需改动核心代码。
 */
object BuildSystemRegistry {
    private val systems = mutableMapOf<String, BuildSystem>()

    fun register(system: BuildSystem) {
        systems[system.id] = system
    }

    fun all(): List<BuildSystem> = systems.values.toList()

    fun getById(id: String): BuildSystem? = systems[id]

    fun unregister(id: String): BuildSystem? = systems.remove(id)

    /** 仅供测试使用，清空注册表 */
    internal fun clearForTest() = systems.clear()
}

/** 构建过程事件，统一给所有技术栈使用 */
sealed class BuildEvent {
    data class Progress(val percent: Int, val message: String) : BuildEvent()
    data class LogLine(val text: String, val isError: Boolean) : BuildEvent()
    data class Finished(val success: Boolean, val durationMs: Long) : BuildEvent()
}

/** 结构化构建错误 */
data class BuildError(
    val filePath: String?,
    val line: Int?,
    val column: Int?,
    val message: String,
    val severity: Severity
) {
    enum class Severity { ERROR, WARNING }
}
