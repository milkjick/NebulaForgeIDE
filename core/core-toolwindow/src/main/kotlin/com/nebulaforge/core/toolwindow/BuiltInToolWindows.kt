package com.nebulaforge.core.toolwindow

/** 内置工具窗 ID，保证各功能区使用同一组稳定标识。 */
object BuiltInToolWindows {
    const val PROJECT_TREE = "project_tree"
    const val STRUCTURE = "structure"
    const val TERMINAL = "terminal"
    const val BUILD_OUTPUT = "build_output"
    const val RUN = "run"
    const val LOGCAT = "logcat"
    const val PROBLEMS = "problems"
    const val VERSION_CONTROL = "version_control"
    const val MCP_LOG = "mcp_log"
    const val GRADLE = "gradle"
    const val NOTIFICATIONS = "notifications"

    val all = listOf(PROJECT_TREE, STRUCTURE, TERMINAL, BUILD_OUTPUT, RUN, LOGCAT, PROBLEMS, VERSION_CONTROL, MCP_LOG, GRADLE, NOTIFICATIONS)
}
