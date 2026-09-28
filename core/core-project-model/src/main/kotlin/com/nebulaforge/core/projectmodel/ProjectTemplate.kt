package com.nebulaforge.core.projectmodel

/**
 * 新建项目模板的分类。
 *
 * 项目模板从「按技术栈平铺」升级为「按分类分组」：新建向导据此渲染分组列表，
 * 用户先选「要做后端 / 前端 / 移动端」，再在组内挑具体语言与框架。
 *
 * 对应开发方案第 5.2 节（模板引擎）与第 5.3 节（创建向导 UI）。
 */
enum class TemplateCategory(val id: String, val displayName: String, val order: Int) {
    MOBILE("mobile", "移动端", 0),
    BACKEND("backend", "后端服务", 1),
    FRONTEND("frontend", "前端应用", 2),
    NATIVE("native", "原生与系统", 3),
    MODULE("module", "扩展模块", 4),
    // 单语言/单页项目：不依赖任何 Web 框架或构建工具链，开箱即可编译运行。
    STANDALONE("standalone", "独立语言项目", 5);

    companion object {
        fun fromId(id: String): TemplateCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 单个项目模板描述。
 *
 * 除既有的 id/name/description/typeId 外，新增分类与语言/框架标识，
 * 供新建向导分组、搜索与标签过滤使用；这些字段全部有默认值，
 * 因此旧调用点（只传 4 个参数）保持兼容。
 */
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val typeId: String,
    val category: TemplateCategory = TemplateCategory.MOBILE,
    /** 主语言标识，如 go / kotlin / java / python / php / rust / typescript */
    val language: String = "",
    /** 框架标识，如 gin / echo / fiber / express / nest / spring-boot / fastapi */
    val framework: String = "",
    /** 额外检索标签（中英混合，便于按「Go 后端」这类词快速定位） */
    val tags: List<String> = emptyList()
) {
    val displayLanguage: String get() = language.ifBlank { "—" }
    val displayFramework: String get() = framework.ifBlank { "—" }
}
