package com.nebulaforge.core.editorui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * 编辑器骨架 UI（对应开发方案 12.1）：标签页 + 面包屑 + 状态栏。
 * 只依赖通用 Compose 组件，不耦合具体 IDE 状态存储。
 */

data class EditorFileTabState(
    val path: String,
    val fileName: String,
    val isDirty: Boolean
)

@Composable
fun EditorFileTabRow(
    tabs: List<EditorFileTabState>,
    activeIndex: Int,
    onTabSelect: (Int) -> Unit,
    onTabClose: (Int) -> Unit,
    dirtyMarker: String,
    closeDescription: String
) {
    if (tabs.isEmpty()) return
    // 这里刻意不用 Material3 的 ScrollableTabRow：它的默认 indicator 会直接执行
    // tabPositions[selectedTabIndex]，而 tabPositions 来自上一次布局测量、落后一帧。
    // 于是"标签从 N 个增加到 N+1 个"的瞬间会出现 selectedTabIndex = N 而 tabPositions.size = N，
    // 抛出 IndexOutOfBoundsException: Index: 2, Size: 2
    //   at androidx.compose.material3.TabRowKt$ScrollableTabRow$1.invoke(TabRow.kt:502)
    // 调用方即使对 activeIndex 做了 coerceIn 也拦不住，因为越界发生在组件内部。
    // 因此改为自绘一行可横向滚动的标签条，所有索引都在这里收敛。
    val scrollState = rememberScrollState()
    val safeActive = activeIndex.coerceIn(0, tabs.lastIndex)
    val tabOffsets = remember(tabs.size) { mutableStateMapOf<Int, Int>() }
    // 选中标签变化时把它滚进可视区域（尽量靠左，留一点边距）。
    LaunchedEffect(safeActive, tabs.size) {
        val x = tabOffsets[safeActive] ?: return@LaunchedEffect
        val target = (x - 8).coerceIn(0, scrollState.maxValue)
        if (target != scrollState.value) scrollState.animateScrollTo(target)
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scrollState),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            Box(
                Modifier.onGloballyPositioned { coords ->
                    val x = coords.positionInParent().x.toInt()
                    if (tabOffsets[index] != x) tabOffsets[index] = x
                }
            ) {
                EditorFileTab(
                    tab = tab,
                    selected = index == safeActive,
                    onClick = { onTabSelect(index) },
                    onClose = { onTabClose(index) },
                    dirtyMarker = dirtyMarker,
                    closeDescription = closeDescription
                )
            }
        }
    }
}

@Composable
private fun EditorFileTab(
    tab: EditorFileTabState,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    dirtyMarker: String,
    closeDescription: String
) {
    Row(
        Modifier
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (tab.isDirty) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            tab.fileName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "$closeDescription ${tab.fileName}$dirtyMarker",
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** 面包屑：按路径分段展示，最后一段为当前文件，高亮显示。 */
@Composable
fun EditorBreadcrumbBar(segments: List<String>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEachIndexed { index, seg ->
            if (index > 0) {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                seg,
                style = MaterialTheme.typography.labelMedium,
                color = if (index == segments.lastIndex) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 状态栏：左侧 Git 分支，右侧行列号 / 编码 / 换行符 / 语言。 */
@Composable
fun EditorStatusBar(
    line: Int,
    column: Int,
    encoding: String,
    lineEnding: String,
    languageMode: String?,
    gitBranch: String?,
    lineColumnTemplate: String,
    scrollPercent: Int? = null,
    extraRight: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!gitBranch.isNullOrBlank()) {
                Text(
                    gitBranch,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                String.format(Locale.getDefault(), lineColumnTemplate, line, column),
                style = MaterialTheme.typography.labelSmall
            )
            if (scrollPercent != null) {
                Text(
                    " · ${scrollPercent.coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(encoding, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(12.dp))
            Text(lineEnding, style = MaterialTheme.typography.labelSmall)
            if (!languageMode.isNullOrBlank()) {
                Spacer(Modifier.width(12.dp))
                Text(languageMode, style = MaterialTheme.typography.labelSmall)
            }
            if (!extraRight.isNullOrBlank()) {
                Spacer(Modifier.width(12.dp))
                Text(extraRight, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

