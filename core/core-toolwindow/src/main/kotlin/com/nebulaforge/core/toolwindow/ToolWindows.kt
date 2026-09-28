package com.nebulaforge.core.toolwindow

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

enum class ToolWindowAnchor { LEFT, BOTTOM, RIGHT }

data class ToolWindowDescriptor(
    val id: String,
    val title: String,
    val anchor: ToolWindowAnchor = ToolWindowAnchor.BOTTOM,
    val content: @Composable () -> Unit
)

/** Tool Window 注册表同时暴露 StateFlow，确保运行时注册不会因为 Compose 快照而丢失。 */
object ToolWindowRegistry {
    private val list = CopyOnWriteArrayList<ToolWindowDescriptor>()
    private val _windows = MutableStateFlow<List<ToolWindowDescriptor>>(emptyList())
    val windows: StateFlow<List<ToolWindowDescriptor>> = _windows.asStateFlow()

    @Synchronized fun register(d: ToolWindowDescriptor) {
        list.removeIf { it.id == d.id }
        list += d
        _windows.value = list.toList()
    }

    @Synchronized fun unregister(id: String) {
        list.removeIf { it.id == id }
        _windows.value = list.toList()
    }

    @Synchronized fun all(): List<ToolWindowDescriptor> = list.toList()
    fun find(id: String): ToolWindowDescriptor? = list.firstOrNull { it.id == id }
}

/**
 * 工具窗口宿主。
 *
 * 两种形态：
 *  - **dockBottom = false（旧行为）**：选中的工具窗口一律以 `ModalBottomSheet` 弹出
 *    （LEFT/RIGHT 锚点在宽屏下改为右侧面板）。弹层会盖住编辑区，一边写代码一边看
 *    终端/构建输出是不可能的。
 *  - **dockBottom = true（真实 IDE 形态）**：BOTTOM 锚点的工具窗口改为**常驻底部工具窗口**
 *    —— 底部一条可横滑的标签栏（终端 / 构建 / 问题 / 运行 / Logcat …），点击标签就把面板
 *    升起，编辑器仍留在上方可见；面板顶部有拖拽手柄可改高度、有"收起"按钮。这正是
 *    IntelliJ / AIDE 这类桌面与移动 IDE 的交互，也是开发方案第十章要求的形态。
 *    LEFT/RIGHT 锚点的窗口仍走侧栏/弹层。
 *
 * 面板布局（是否展开、高度）默认由调用方持久化传入，宿主本身保持无状态受控组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolWindowHost(
    mainContent: @Composable () -> Unit,
    selectedId: String? = null,
    onSelected: (String?) -> Unit = {},
    // 默认不再常驻渲染任何工具窗口按钮。此前底部有一排 TextButton 覆盖在主内容之上，
    // 把编辑区压成一两条缝，这也是"不像代码编辑器"的直接原因之一。
    // 需要常驻入口的调用方（宽屏工作台）可显式传 true。
    showAffordances: Boolean = false,
    // 常驻底部工具窗口（终端/构建/问题/运行…）
    dockBottom: Boolean = false,
    bottomPanelVisible: Boolean = false,
    bottomPanelHeightDp: Int = 240,
    onBottomPanelVisibilityChange: (Boolean) -> Unit = {},
    onBottomPanelHeightChange: (Int) -> Unit = {}
) {
    val windows by ToolWindowRegistry.windows.collectAsState()
    var activeId by remember { mutableStateOf(selectedId) }
    LaunchedEffect(selectedId) { activeId = selectedId }
    val active = windows.firstOrNull { it.id == activeId }
    // 原实现调用 calculateWindowSizeClass() 缺少必需的 Activity 参数导致编译失败；
    // 改用 LocalConfiguration 直接读取当前宽度，既不需要 Experimental API，也不需要
    // 本模块（core-toolwindow，一个纯 UI 组件库）反过来依赖 Activity/Context，
    // 断点阈值 600dp 与开发方案第十章、MainActivity.kt 里 WindowWidthSizeClass.Compact
    // 的默认阈值保持一致。
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val wide = screenWidthDp >= 600
    val left = windows.filter { it.anchor == ToolWindowAnchor.LEFT }
    val right = windows.filter { it.anchor == ToolWindowAnchor.RIGHT }
    val bottom = windows.filter { it.anchor == ToolWindowAnchor.BOTTOM }
    // 底部面板高度上限跟随屏幕，避免在小屏上把编辑器挤没（最多吃掉 70% 高度）。
    val maxPanelHeight = (screenHeightDp * 0.7f).toInt().coerceAtLeast(160)
    var heightDraft by remember { mutableStateOf(bottomPanelHeightDp.coerceIn(120, maxPanelHeight)) }
    LaunchedEffect(bottomPanelHeightDp, maxPanelHeight) {
        heightDraft = bottomPanelHeightDp.coerceIn(120, maxPanelHeight)
    }

    fun select(id: String) { activeId = id; onSelected(id) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (showAffordances && wide && left.isNotEmpty()) {
                    Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        left.forEach { window -> TextButton(onClick = { select(window.id) }) { Text(window.title.take(2)) } }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { mainContent() }
                if (showAffordances && wide && right.isNotEmpty()) {
                    Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        right.forEach { window -> TextButton(onClick = { select(window.id) }) { Text(window.title.take(2)) } }
                    }
                }
            }
            if (dockBottom && bottom.isNotEmpty()) {
                BottomToolWindowBar(
                    windows = bottom,
                    activeId = activeId,
                    panelVisible = bottomPanelVisible,
                    onOpen = { id -> select(id); onBottomPanelVisibilityChange(true) },
                    onCollapse = { onBottomPanelVisibilityChange(false) }
                )
                val docked = bottom.firstOrNull { it.id == activeId }
                if (bottomPanelVisible && docked != null) {
                    BottomResizeHandle(onDragUp = { delta ->
                        val next = (heightDraft + delta).coerceIn(120, maxPanelHeight)
                        if (next != heightDraft) {
                            heightDraft = next
                            onBottomPanelHeightChange(next)
                        }
                    })
                    Surface(Modifier.fillMaxWidth().height(heightDraft.dp), tonalElevation = 2.dp) {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier.fillMaxWidth().height(34.dp).padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    docked.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onBottomPanelVisibilityChange(false) }) { Text("收起") }
                            }
                            Box(Modifier.fillMaxWidth().weight(1f)) { docked.content() }
                        }
                    }
                }
            }
        }
        // 原实现把这个 if 块写在外层 Box{} 的 lambda 之外，导致 Modifier.align() 没有
        // BoxScope 可用（"Unresolved reference 'align'"）。align 只在 Box/Row/Column 的
        // content lambda 内可用，因此把详情面板挪进同一个 Box 作用域内，
        // 使其相对整个 ToolWindowHost 区域定位，而不是相对某个子 Row。
        //
        // dockBottom 形态下，底部窗口已经常驻在下方，这里只负责 LEFT/RIGHT 锚点的窗口。
        if (active != null && !(dockBottom && active.anchor == ToolWindowAnchor.BOTTOM)) {
            if (wide && active.anchor != ToolWindowAnchor.BOTTOM) {
                Surface(Modifier.align(Alignment.CenterEnd).width(360.dp).fillMaxHeight(), tonalElevation = 3.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(active.title, style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { activeId = null; onSelected(null) }) { Text("关闭") }
                        }
                        active.content()
                    }
                }
            } else {
                ModalBottomSheet(onDismissRequest = { activeId = null; onSelected(null) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
                    Text(active.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
                    active.content()
                }
            }
        }
    }
}

/** IDE 底部工具窗口标签栏：横向可滑，点标签升起面板，再点当前标签收起。 */
@Composable
private fun BottomToolWindowBar(
    windows: List<ToolWindowDescriptor>,
    activeId: String?,
    panelVisible: Boolean,
    onOpen: (String) -> Unit,
    onCollapse: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                windows.forEach { window ->
                    val selected = panelVisible && window.id == activeId
                    TextButton(
                        onClick = { if (selected) onCollapse() else onOpen(window.id) },
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(
                            window.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 底部工具窗口的上下拖拽手柄：向上拖变高。 */
@Composable
private fun BottomResizeHandle(onDragUp: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    onDragUp((-dragAmount).toInt())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
        )
    }
}
