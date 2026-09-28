package com.nebulaforge.stack.server

import com.nebulaforge.core.projectmodel.LanguageCommands
import java.io.File

/**
 * 独立语言项目（Java / Python / JavaScript / HTML / CSS / C / Lua）的构建与运行命令推导。
 *
 * **已统一到 [LanguageCommands]**：命令推导的实现（目录遍历、主类解析、shell 引号）
 * 全部搬进了 `core-project-model`，原因是「构建」工具窗通过 `TasksJson.defaultTasks()`
 * 读取默认任务，而那个文件在 `core-project-model` 里，无法反向依赖本模块。
 * 两份实现漂移过一次，后果是 java-console / html-site / lua-script 等模板在构建面板里
 * 一个任务都没有。这里只保留薄转发，保持既有调用点不变。
 */
internal object StandaloneCommands {

    fun shellQuote(value: String): String = LanguageCommands.shellQuote(value)

    fun javaSources(root: File): List<File> = LanguageCommands.javaSources(root)
    fun cSources(root: File): List<File> = LanguageCommands.cSources(root)
    fun jsSources(root: File): List<File> = LanguageCommands.jsSources(root)

    fun javaMainClass(root: File): String? = LanguageCommands.javaMainClass(root)

    fun javaCompile(root: File): String = LanguageCommands.javaCompile(root)
    fun javaRun(root: File): String = LanguageCommands.javaRun(root)

    fun cTargetName(root: File): String = LanguageCommands.cTargetName(root)
    fun cBuild(root: File): String = LanguageCommands.cBuild(root)
    fun cRun(root: File): String = LanguageCommands.cRun(root)

    fun jsCheck(root: File): String = LanguageCommands.jsCheck(root)

    const val STATIC_SERVER_PORT: String = LanguageCommands.STATIC_SERVER_PORT

    val staticServer: String get() = LanguageCommands.staticServer

    fun htmlCheck(root: File): String = LanguageCommands.htmlCheck(root)
    fun cssCheck(root: File): String = LanguageCommands.cssCheck(root)

    /** 构建任务列表：**第一项即默认构建命令**（BuildSystem 约定取 first 作为主任务）。 */
    fun tasksFor(id: String, root: File): List<String> = LanguageCommands.tasksFor(id, root)

    fun pythonRun(root: File): String = LanguageCommands.pythonRun(root)
    fun javascriptRun(root: File): String = LanguageCommands.javascriptRun(root)

    fun runCommand(id: String, root: File): String = LanguageCommands.runCommand(id, root)
}
