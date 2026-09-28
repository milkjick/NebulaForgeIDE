package com.nebulaforge.stack.cpp

import com.nebulaforge.core.projectmodel.BuildSystem
import com.nebulaforge.core.projectmodel.LanguageCommands
import com.nebulaforge.core.projectmodel.ProjectType
import com.nebulaforge.core.projectmodel.RunConfiguration
import java.io.File

/** 独立 C/C++ CMake 工程类型；不修改核心 ProjectType 接口即可注册。 */
class CppProjectType(
    private val command: (String, File, Map<String, String>) -> kotlinx.coroutines.flow.Flow<String> = { _, _, _ -> kotlinx.coroutines.flow.emptyFlow() }
) : ProjectType {
    override val id: String = "cpp"
    override val displayNameKey: String = "stack_cpp_name"
    override fun detect(projectRoot: File): Boolean = CppProjectDetector.isCmakeProject(projectRoot)
    override fun createBuildSystem(): BuildSystem = CmakeBuildSystem(command)

    /**
     * CMake 工程的运行配置。
     *
     * 之前这个类型**没有**实现本方法（接口默认返回 null），而 `RunConfigurationDefaults` 里却
     * 为 "cpp" 预置了一条配置 → 用户点「运行」时 `RunConfigurationEngine` 直接抛
     * 「项目类型 cpp 没有运行配置」，C++ 模板工程等于只能构建不能运行。
     */
    override fun createRunConfiguration(): RunConfiguration = CmakeRunConfiguration()
    // LanguageServiceRegistry 里 clangd 的 id 是 "cpp"（extensions 覆盖 c/cc/cpp/h），
    // 原来写 "clangd" 会查不到描述符，等于 C/C++ 语言服务永远拉不起来。
    override fun requiredLanguageServiceIds(): List<String> = listOf("cpp")
    override fun templateIds(): List<String> = listOf("cpp-cmake-console")
}

/**
 * CMake 运行配置：命令由 [LanguageCommands.cmakeRun] 推导（配置 → 构建 → 执行目标）。
 * 与 `ServerRunConfiguration` 保持一致的模式集合，便于运行面板统一处理。
 */
private class CmakeRunConfiguration : RunConfiguration {
    override val id: String = "cpp-cmake-run"
    override fun command(projectRoot: File, mode: String): String = LanguageCommands.cmakeRun(projectRoot)
    override fun supports(mode: String): Boolean = mode == "run" || mode == "build" || mode == "debug"
}
