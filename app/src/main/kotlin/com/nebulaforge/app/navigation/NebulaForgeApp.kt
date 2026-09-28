package com.nebulaforge.app.navigation

import android.content.Context

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nebulaforge.app.ai.BuildFixReviewScreen
import com.nebulaforge.app.plugins.JsExtensionHost
import com.nebulaforge.app.plugins.PluginWebviewPanels
import com.nebulaforge.app.ai.AgentPlanScreen
import com.nebulaforge.app.ai.AgentLearningCenterScreen
import androidx.compose.ui.platform.LocalContext
import com.nebulaforge.app.home.EditorHomeScreen
import com.nebulaforge.app.workspace.WorkspaceScreen
import com.nebulaforge.app.database.DatabaseScreen
import com.nebulaforge.app.workspace.NewProjectWizardScreen
import com.nebulaforge.app.editor.FileEditorScreen
import android.net.Uri
import com.nebulaforge.app.build.BuildCenterScreen
import com.nebulaforge.app.run.RunToolWindowScreen
import com.nebulaforge.app.problems.ProblemsScreen
import com.nebulaforge.core.session.ProblemLocation
import com.nebulaforge.app.reverse.ReverseWorkbenchScreen
import com.nebulaforge.app.device.DeviceAccessScreen
import com.nebulaforge.app.settings.SettingsScreen
import com.nebulaforge.app.terminal.TermuxTerminalScreen
import com.nebulaforge.app.mcp.McpManagementScreen
import com.nebulaforge.app.ai.AiSettingsScreen
import com.nebulaforge.app.onboarding.OnboardingWizardScreen
import com.nebulaforge.app.plugins.PluginManagementScreen

/** 首次启动自检向导的持久化标记（非敏感状态，仅记录是否已看过向导） */
private const val ONBOARDING_PREFS = "nebulaforge.onboarding"
private const val KEY_ONBOARDING_DONE = "onboarding_completed"

/**
 * 应用导航根组件。
 * 对应开发方案第十章断点规则：
 *   Compact（< 600dp）  -> 底部 NavigationBar，单栏堆叠
 *   Medium / Expanded（>= 600dp） -> NavigationRail，左列表 + 右详情
 * 严格遵循"零、全局约定 0.2 节"：全篇禁止使用 NavigationDrawer。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun NebulaForgeApp(windowSizeClass: WindowSizeClass, initialRoute: String? = null) {
    val context = LocalContext.current

    // 1.7：首次启动先走环境自检向导（全屏路由页，不使用弹窗）。
    // 完成或关闭后落盘，后续启动直接进入主界面；用户仍可在设置页重新运行自检。
    val prefs = remember { context.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE) }
    var onboardingFinished by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_DONE, false)) }
    if (!onboardingFinished) {
        OnboardingWizardScreen(
            onFinished = {
                prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
                onboardingFinished = true
            }
        )
        return
    }

    val navController = rememberNavController()
    val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

    Box(Modifier.fillMaxSize()) {
        if (isCompact) {
            CompactLayout(navController, initialRoute)
        } else {
            WideLayout(navController, initialRoute)
        }
        // 插件界面（扩展的 WebView 面板）叠在最上层：任何路由下，只要扩展创建了面板
        // 就能立刻看到真实界面，而不是像以前那样只在日志里留一行「不渲染 WebView」。
        PluginWebviewPanels(JsExtensionHost.of(context))
    }
}

@Composable
private fun CompactLayout(navController: NavHostController, initialRoute: String? = null) {
    // 「编辑器为主 + 底部工具窗口」的 IDE 工作台（工作区 / 文件编辑器）自带左侧项目树、
    // 底部 Terminal/Build/Problems 等工具窗口；再叠一层 App 式底部导航既挤占编辑器空间，
    // 也与真实 Android IDE 的形态不符 —— 这两类路由下隐藏底部导航栏。
    val backStackEntry by navController.currentBackStackEntryAsState()
    val ideMode = isIdeRoute(backStackEntry?.destination?.route)
    Scaffold(
        bottomBar = { if (!ideMode) NebulaBottomBar(navController) }
    ) { padding ->
        // padding 由 Scaffold 提供的底部导航栏高度，传递给各 Screen 避免内容被遮挡；
        // 各 Screen 内部若自带 Scaffold（如设置页），可选择性使用该 padding 或自行处理
        NebulaNavHost(
            navController = navController,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            initialRoute = initialRoute
        )
    }
}

@Composable
private fun WideLayout(navController: NavHostController, initialRoute: String? = null) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val isIde = isIdeRoute(backStackEntry?.destination?.route)
    Row(Modifier.fillMaxSize()) {
        // IDE 工作台自带左侧项目树工具窗口，宽屏下再挂导航轨会让编辑器过窄。
        if (!isIde) NebulaNavigationRail(navController)
        NebulaNavHost(navController = navController, modifier = Modifier.fillMaxSize(), initialRoute = initialRoute)
    }
}

/**
 * 允许被 intent 直达的起始路由白名单。
 *
 * 存在的意义：调试与自动化冒烟不需要手点进工作区，直接
 * `am start -n com.nebulaforge.app/.MainActivity -e route workspace` 即可落在目标页。
 * 只接受白名单内的字面路由，避免外部 intent 传入非法路由导致 NavHost 起点不存在而崩溃。
 */
private val ALLOWED_START_ROUTES = setOf(
    NebulaDestination.EDITOR.route,
    NebulaDestination.TERMINAL.route,
    NebulaDestination.AI.route,
    NebulaDestination.REVERSE.route,
    NebulaDestination.SETTINGS.route,
    "workspace",
    "build_center",
    "problems",
    "run_center",
    "database",
    "mcp",
    "plugins",
    "ai_build_fix",
    "new_project",
    // AI 自身的功能页：模型用 app_control(action="open", arg="ai_plan") 等能把用户直接带到
    // 「建立计划 / 改 AI 设置 / 看学习中心」的界面。旧版本这几个路由不在白名单里，
    // sanitizeStartRoute 会把它们静默替换成 editor_home —— 用户看到的就是「AI 说打开了，其实没打开」。
    "ai_plan",
    "ai_settings",
    "ai_learning"
)

private fun sanitizeStartRoute(route: String?): String =
    if (route != null && route in ALLOWED_START_ROUTES) route else NebulaDestination.EDITOR.route

/**
 * 是否属于「IDE 工作台」路由（工作区 / 文件编辑器）。
 *
 * 这两类路由内部就是完整 IDE 壳层（左侧项目树 + 编辑器 + 底部 Terminal/Build/Problems 工具窗口），
 * 外层再套 App 式导航栏/导航轨会同时挤占编辑器空间并造成「双层导航」。
 */
private fun isIdeRoute(route: String?): Boolean {
    if (route == null) return false
    return route.startsWith("workspace") || route.startsWith("editor?")
}

@Composable
private fun NebulaNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues? = null,
    initialRoute: String? = null
) {
    // 新建向导创建完成后把项目路径回传给工作区，由工作区刷新列表并选中。
    var pendingCreatedProject by remember { mutableStateOf<String?>(null) }
    NavHost(
        navController = navController,
        startDestination = sanitizeStartRoute(initialRoute),
        modifier = if (contentPadding != null) modifier.padding(contentPadding) else modifier
    ) {
        composable(NebulaDestination.EDITOR.route) {
            EditorHomeScreen(
                onWorkspace = { navController.navigate("workspace") },
                onProblems = { navController.navigate("problems") },
                onSettings = { navController.navigate(NebulaDestination.SETTINGS.route) },
                onMcp = { navController.navigate("mcp") },
                onPlugins = { navController.navigate("plugins") }
            )
        }
        composable("workspace") {
            WorkspaceScreen(
                onOpenFile = { path -> navController.navigate("editor?path=${Uri.encode(path)}") },
                onOpenBuild = { navController.navigate("ai_build_fix") },
                onOpenProblems = { navController.navigate("problems") },
                onOpenRun = { navController.navigate("run_center") },
                onOpenNewProject = { navController.navigate("new_project") },
                onOpenDatabase = { navController.navigate("database") },
                pendingOpenProject = pendingCreatedProject,
                onPendingOpenProjectConsumed = { pendingCreatedProject = null }
            )
        }
        // 5.3 新建项目向导：全屏路由页（非弹窗），创建成功后回传路径并返回工作区。
        composable("new_project") {
            NewProjectWizardScreen(
                onBack = { navController.popBackStack() },
                onCreated = { project ->
                    pendingCreatedProject = project.absolutePath
                    navController.popBackStack()
                }
            )
        }
        composable("editor?path={path}&line={line}&column={column}&reviewHunk={reviewHunk}") { entry ->
            val path = entry.arguments?.getString("path")?.let(Uri::decode).orEmpty()
            val line = entry.arguments?.getString("line")?.toIntOrNull() ?: 0
            val column = entry.arguments?.getString("column")?.toIntOrNull() ?: 0
            val reviewHunk = entry.arguments?.getString("reviewHunk")?.toIntOrNull() ?: -1
            FileEditorScreen(path = path, initialLine = line, initialColumn = column, reviewHunk = reviewHunk, onBack = { navController.popBackStack() })
        }
        composable(NebulaDestination.TERMINAL.route) { TermuxTerminalScreen() }
        composable("database") { DatabaseScreen(LocalContext.current.applicationContext as com.nebulaforge.app.NebulaForgeApplication, onBack = { navController.popBackStack() }) }
        // AI 工作台：只有任务模式（聊天模式已删除）。AI 会自己读代码、跑命令、改文件，
        // 写文件前必须过 Diff 审查；「计划流程」（先出计划再批准执行）单独一个路由。
        composable(NebulaDestination.AI.route) {
            com.nebulaforge.app.ai.AiWorkspaceScreen(
                LocalContext.current.applicationContext as com.nebulaforge.app.NebulaForgeApplication,
                onLearningCenter = { navController.navigate("ai_learning") },
                onOpenAiSettings = { navController.navigate("ai_settings") },
                onOpenPlan = { navController.navigate("ai_plan") },
                // 工作台上的 MCP 状态条要能一键跳到 MCP 管理页（添加服务器 / 看失败原因）
                onOpenMcp = { navController.navigate("mcp") }
            )
        }
        composable("ai_plan") {
            com.nebulaforge.app.ai.AgentPlanScreen(
                LocalContext.current.applicationContext as com.nebulaforge.app.NebulaForgeApplication,
                onLearningCenter = { navController.navigate("ai_learning") },
                onOpenAiSettings = { navController.navigate("ai_settings") }
            )
        }
        composable("ai_settings") { AiSettingsScreen(onBack = { navController.popBackStack() }) }
        composable("ai_learning") { AgentLearningCenterScreen(LocalContext.current.applicationContext as com.nebulaforge.app.NebulaForgeApplication) }
        composable(NebulaDestination.REVERSE.route) { ReverseWorkbenchScreen() }
        composable(NebulaDestination.SETTINGS.route) { SettingsScreen(onOpenAiSettings = { navController.navigate("ai_settings") }, onOpenDeviceAccess = { navController.navigate("device_access") }) }
        composable("device_access") { DeviceAccessScreen(onBack = { navController.popBackStack() }) }
        composable("mcp") { McpManagementScreen() }
        composable("plugins") { PluginManagementScreen() }
        composable("build_center") { BuildCenterScreen(onOpenFile = { path -> navController.navigate("editor?path=${Uri.encode(path)}&line=0&column=0") }, onOpenRun = { navController.navigate("run_center") }, onOpenProblems = { navController.navigate("problems") }) }
        composable("run_center") { RunToolWindowScreen(onOpenFile = { path -> navController.navigate("editor?path=${Uri.encode(path)}&line=0&column=0") }) }
        composable("problems") { ProblemsScreen(onOpenFile = { loc -> navController.navigate("editor?path=${Uri.encode(loc.file)}&line=${loc.line}&column=${loc.column}") }, onAiFix = { navController.navigate("ai_build_fix") }) }
        composable("ai_build_fix") { BuildFixReviewScreen(onOpenFile = { path, line, column, hunk -> navController.navigate("editor?path=${Uri.encode(path)}&line=$line&column=$column&reviewHunk=$hunk") }) }
    }
    // 「构建成功 → 选择安装方式」弹窗的全局宿主：挂在 NavHost 之上而不是某个页面里。
    // 构建产物与待办都已落盘，所以无论用户在哪个页面、切走过多久、甚至进程被系统杀掉后重启，
    // 这个入口都会自己回来（旧实现把弹窗放在底部构建面板里，面板不再组合就永远弹不出来）。
    com.nebulaforge.app.build.InstallPromptHost()
}

@Composable
private fun NebulaBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        NebulaDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(destination.icon, contentDescription = stringResource(destination.labelRes)) },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}

@Composable
private fun NebulaNavigationRail(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationRail {
        NebulaDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = currentRoute == destination.route,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(destination.icon, contentDescription = stringResource(destination.labelRes)) },
                label = { Text(stringResource(destination.labelRes)) }
            )
        }
    }
}
