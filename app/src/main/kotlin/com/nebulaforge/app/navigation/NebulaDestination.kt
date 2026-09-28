package com.nebulaforge.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.nebulaforge.app.R

/**
 * 第一层导航目的地，对应开发方案第十章："第一层：底部 NavigationBar，5 个入口：
 * 编辑器 / 终端 / AI / 逆向 / 设置；宽屏切 NavigationRail"。
 *
 * 当前实现状态（随功能推进请保持本注释与 NebulaForgeApp.kt 的 NebulaNavHost 同步更新）：
 *   - EDITOR：接"打开或创建项目"首页（EditorHomeScreen），真实实现，含"项目工作区/问题/项目文件"入口卡片
 *     （原"构建 / 运行"入口卡片已移除：构建属于 IDE 内部工作，只在工作区底部「构建」工具窗内提供）
 *   - TERMINAL：接 TermuxTerminalScreen，真实实现（core-terminal 的 TerminalSessionManager 驱动）
 *   - AI：接 BuildFixReviewScreen，真实实现（AI 构建错误修复审查，core-agent 驱动）
 *   - REVERSE：接 APK/Web 逆向工作台，当前已覆盖 APK 静态分析与 WebView 流量观察
 *   - SETTINGS：接工具链状态页（SettingsScreen），真实实现
 *   - MCP：接 MCP 服务器管理页（McpManagementScreen），含内部 Server 与外部 Streamable HTTP 服务器，
 *     并对每个外部服务器做**真实可达性探测**（initialize + tools/list，结果分就绪/失败/未检测三种状态）
 *
 * "问题"（ProblemsScreen）、"构建"（BuildCenterScreen）、"运行"（RunToolWindowScreen）三个页面
 * 也都是真实实现，但不在这一级导航栏里，而是作为工作区底部工具窗（或彼此之间跳转）的二级路由，
 * 这是本次讨论后明确保留的设计决策：底部导航栏一级入口数量维持方案原定的 5 个不变。
 */
enum class NebulaDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector
) {
    EDITOR(route = "editor_home", labelRes = R.string.nav_editor, icon = Icons.Default.Code),
    TERMINAL(route = "terminal", labelRes = R.string.nav_terminal, icon = Icons.Default.Terminal),
    AI(route = "ai", labelRes = R.string.nav_ai, icon = Icons.Default.SmartToy),
    REVERSE(route = "reverse", labelRes = R.string.nav_reverse, icon = Icons.Default.Build),
    SETTINGS(route = "settings", labelRes = R.string.nav_settings, icon = Icons.Default.Settings),
    MCP(route = "mcp", labelRes = R.string.nav_mcp, icon = Icons.Default.Hub)
}
