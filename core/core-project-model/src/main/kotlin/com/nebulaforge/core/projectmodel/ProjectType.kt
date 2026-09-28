package com.nebulaforge.core.projectmodel

import java.io.File

/**
 * 项目类型描述接口。每种技术栈（Android/Flutter/Web/C++ 等）实现一个具体类。
 * 该接口只描述"是什么"，不包含具体构建/运行逻辑（那是 BuildSystem 和 RunConfiguration 的职责）。
 *
 * 对应开发方案第 2.2 节。新增一门语言只需实现本接口 + BuildSystem + LanguageService，
 * 不应修改本文件或核心导航/编辑器代码。
 */
interface ProjectType {
    /** 唯一标识，如 "android", "flutter", "web-frontend", "cpp" */
    val id: String

    /** 展示名称的字符串资源名（由上层通过资源系统解析，本模块不直接依赖 Android 资源类避免循环依赖） */
    val displayNameKey: String

    /** 判断给定目录是否属于本项目类型（用于"打开项目"时自动识别） */
    fun detect(projectRoot: File): Boolean

    /** 返回该项目类型关联的构建系统实现 */
    fun createBuildSystem(): BuildSystem

    /** Optional resident run configuration for non-APK stacks. */
    fun createRunConfiguration(): RunConfiguration? = null

    /** 返回该项目类型关联的语言服务标识列表（具体 LSP 接入在 feature 层实现，此处仅声明需要哪些） */
    fun requiredLanguageServiceIds(): List<String>

    /** 返回该项目类型的内置模板 ID 列表 */
    fun templateIds(): List<String>
}

/**
 * 全局单例注册表。核心模块与未来的插件均通过此对象注册/查询 ProjectType。
 */
object ProjectTypeRegistry {
    private val types = mutableMapOf<String, ProjectType>()

    fun register(type: ProjectType) {
        types[type.id] = type
    }

    fun all(): List<ProjectType> = types.values.toList()

    /** 按目录自动识别项目类型；多个类型都命中时取注册顺序中的第一个 */
    fun detect(projectRoot: File): ProjectType? =
        types.values.firstOrNull { it.detect(projectRoot) }

    fun getById(id: String): ProjectType? = types[id]

    fun unregister(id: String): ProjectType? = types.remove(id)

    /** 仅供测试使用，清空注册表 */
    internal fun clearForTest() = types.clear()
}
