package com.nebulaforge.app.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nebulaforge.app.NebulaForgeApplication
import com.nebulaforge.app.build.TaskRunState
import com.nebulaforge.core.agent.AgentPlan
import com.nebulaforge.core.agent.AgentPlanStatus
import com.nebulaforge.core.agent.AgentPlanStep
import com.nebulaforge.core.agent.AgentPlanStepStatus
import com.nebulaforge.core.agent.elapsedMs
import com.nebulaforge.core.agent.estimatedRemainingMs
import com.nebulaforge.core.agent.progressPercent
import com.nebulaforge.core.projectmodel.TaskDefinition
import com.nebulaforge.core.projectmodel.TasksJson
import java.io.File

/**
 * 任务中心 —— 按参考图（图 1 任务管理 / 图 2 执行计划）重建的面板。
 *
 * ## 本轮改造（图 1/图 2 一比一）
 * 1. **配色改成参考图的浅蓝底 + 蓝色强调**（[PanelPalette]）：底色浅蓝、行是白卡片、
 *    选中页签与动作按钮是蓝色。旧版跟随 MD3 主题（青绿），与参考图不是一个视觉。
 * 2. **行数据模型换成 [BoardTask]**（见 TaskBoard.kt）：每行能表达自己的进度（`0/7`）
 *    与状态（`已用 / 已禁用 / 已完成`），并且**行尾带 `×` 可单独删除**——
 *    旧版只能删「运行历史」，且面板刷新后又会复活。
 * 3. **四个分组固定**：未来任务 / 当前任务 / 执行计划 / 历史任务，空分组也显示 `(0)`。
 *
 * ## 数据来源全部真实（硬约束，无占位假数据）
 * | 区块 | 真实来源 |
 * | --- | --- |
 * | 目标、执行计划、计划步骤 | [NebulaForgeApplication.agentPlanState] 的 AgentPlan |
 * | 当前 / 历史任务（构建运行） | WorkspaceTaskRunner.state / .history |
 * | 未来任务（可运行任务） | 工程 tasks.json → [TasksJson.load]，点「运行」直接跑 |
 * | 进度与用量 | 计划步骤统计 + [AiChatUiState.contextTokens]/[contextLimit] |
 *
 * 参考图上的「余额」没有对应的真实数据源（本 IDE 不代管用户账户额度），
 * 这里不做假数字，只展示服务端真实返回的 token 用量。
 *
 * @param compact 工作台内联形态：默认收起、隐藏行内按钮、不放用量条（输入框上方已有）。
 */
@Composable
fun AgentTaskCenter(
    app: NebulaForgeApplication,
    compact: Boolean = false,
    showUsage: Boolean = !compact,
    onOpenPlanPage: (() -> Unit)? = null
) {
    val planState by app.agentPlanState.collectAsStateWithLifecycle()
    val run by app.workspaceTasks.state.collectAsStateWithLifecycle()
    val history by app.workspaceTasks.history.collectAsStateWithLifecycle()
    val chat by app.chatState.collectAsStateWithLifecycle()
    // 「删了哪些 / 禁用了哪些」是用户选择，持久化在 Application 里。
    val dismissed by app.boardDismissed.collectAsStateWithLifecycle()
    val disabled by app.boardDisabled.collectAsStateWithLifecycle()

    val plan = planState.plan
    val steps = plan?.steps.orEmpty()
    var expanded by remember(compact) { mutableStateOf(!compact) }
    // 参考图 1/图 2 的两段头部：「任务管理」与「执行计划」。
    var tab by remember(compact) { mutableStateOf(0) }

    // 当前工程的「可运行任务」（tasks.json → 默认任务 → 语言命令），作为「未来任务」的可执行入口。
    val rootPath = app.currentProjectPath()
    val availableTasks = remember(rootPath, run.running, run.startedAt) {
        val root = rootPath?.let(::File)?.takeIf { it.isDirectory } ?: return@remember emptyList()
        val active = app.workspaceState.state.value.activeFile?.let(::File)?.takeIf { it.isFile }
        runCatching { TasksJson.load(root, active) }.getOrDefault(emptyList())
    }

    // 一次算出整份任务列表：来源是计划 + 运行态 + tasks.json，再套用用户的删除/禁用选择。
    val rows = remember(plan, run, history, availableTasks, rootPath, dismissed, disabled) {
        TaskBoardBuilder.build(plan, run, history, availableTasks, rootPath, dismissed, disabled)
    }
    val byGroup = rows.groupBy { it.group }

    val hasAnything = plan != null || run.running || run.startedAt > 0L ||
        history.isNotEmpty() || availableTasks.isNotEmpty()
    if (!hasAnything) return

    Surface(
        color = PanelPalette.Background,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PanelTabHeader(
                tab = tab,
                onTab = { tab = it },
                expanded = expanded,
                onToggleExpand = { expanded = !expanded }
            )

            GoalBlock(plan, run, compact)

            if (expanded) {
                if (tab == 0) {
                    // 图 1 的顺序：未来任务 → 当前任务 → 执行计划 → 历史任务。
                    TaskSection(
                        app, BoardGroup.FUTURE, byGroup[BoardGroup.FUTURE].orEmpty(),
                        compact, if (compact) 3 else 20, availableTasks, rootPath
                    )
                    TaskSection(
                        app, BoardGroup.CURRENT, byGroup[BoardGroup.CURRENT].orEmpty(),
                        compact, 20, availableTasks, rootPath
                    )
                    TaskSection(
                        app, BoardGroup.PLAN, byGroup[BoardGroup.PLAN].orEmpty(),
                        compact, 20, availableTasks, rootPath, footer = onOpenPlanPage
                    )
                    TaskSection(
                        app, BoardGroup.HISTORY, byGroup[BoardGroup.HISTORY].orEmpty(),
                        compact, if (compact) 3 else 30, availableTasks, rootPath
                    )
                    if (dismissed.isNotEmpty()) {
                        TextButton(onClick = { app.restoreBoardTasks() }) {
                            Text(
                                "已删除 ${dismissed.size} 条 · 恢复",
                                style = MaterialTheme.typography.labelSmall,
                                color = PanelPalette.Primary
                            )
                        }
                    }
                } else {
                    if (steps.isNotEmpty()) {
                        PlanStepsBlock(steps)
                    } else {
                        Text(
                            "暂无执行计划",
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelPalette.TextSecondary
                        )
                    }
                }
            } else {
                CompactSummary(rows)
            }

            if (showUsage) PanelUsageBar(chat)
        }
    }
}

/**
 * 参考图配色。**刻意不跟随 MD3 明暗主题**：参考图就是浅蓝底 + 蓝色强调，
 * 跟随主题会让深色模式下整块面板与图完全不一致（本面板要求一比一还原）。
 */
private object PanelPalette {
    val Background = Color(0xFFF2F5FB)
    val Card = Color(0xFFFFFFFF)
    val Primary = Color(0xFF3661E1)
    val TextPrimary = Color(0xFF21252D)
    val TextSecondary = Color(0xFF6B7280)
    val Divider = Color(0xFFE3E8F4)
    val Success = Color(0xFF2E9E63)
    val Warning = Color(0xFFD08911)
    val Danger = Color(0xFFD64545)
    val Muted = Color(0xFF9CA3AD)
}

/** 头部：左「任务管理 / 执行计划」分段，右「展开 / 收起」。 */
@Composable
private fun PanelTabHeader(tab: Int, onTab: (Int) -> Unit, expanded: Boolean, onToggleExpand: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = PanelPalette.Card, shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.padding(3.dp)) {
                listOf("任务管理", "执行计划").forEachIndexed { index, label ->
                    val selected = tab == index
                    Surface(
                        color = if (selected) PanelPalette.Primary else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        onClick = { onTab(index) }
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) Color.White else PanelPalette.TextSecondary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleExpand) {
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = PanelPalette.Primary
            )
        }
    }
}

/** 目标卡片：整句话目标 + 状态 + 进度 + 用时。计划优先，无计划时退回最近一次构建任务。 */
@Composable
private fun GoalBlock(plan: AgentPlan?, run: TaskRunState, compact: Boolean) {
    val goal = plan?.userRequest?.takeIf { it.isNotBlank() }
        ?: run.label.takeIf { it.isNotBlank() }
        ?: return
    val summary = plan?.summary.orEmpty()
    Surface(color = PanelPalette.Card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "目标：" + goal,
                style = MaterialTheme.typography.bodyMedium,
                color = PanelPalette.TextPrimary,
                maxLines = if (compact) 2 else 6,
                overflow = TextOverflow.Ellipsis
            )
            if (!compact && summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelPalette.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (plan != null) {
                    PanelChip(planProgressText(plan), plan.status.boardState())
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { plan.progressPercent() / 100f },
                        modifier = Modifier.weight(1f).height(5.dp),
                        color = PanelPalette.Primary,
                        trackColor = PanelPalette.Divider
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        plan.progressPercent().toString() + "%",
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelPalette.TextSecondary
                    )
                } else {
                    PanelChip(
                        if (run.running) "执行中" else "最近一次",
                        if (run.running) BoardTaskState.RUNNING else BoardTaskState.DONE
                    )
                    Spacer(Modifier.width(8.dp))
                    if (run.running) {
                        LinearProgressIndicator(
                            modifier = Modifier.weight(1f).height(5.dp),
                            color = PanelPalette.Primary,
                            trackColor = PanelPalette.Divider
                        )
                    } else {
                        // 已结束的任务没有中间态进度可言：整条填满表示「这一轮跑完了」，
                        // 成败由状态标签表达。
                        LinearProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.weight(1f).height(5.dp),
                            color = PanelPalette.Success,
                            trackColor = PanelPalette.Divider
                        )
                    }
                }
            }
            val meta = buildString {
                if (plan != null && plan.startedAt != null) {
                    append("已用 ").append(boardShortDuration(plan.elapsedMs()))
                    plan.estimatedRemainingMs()?.let { append(" · 预计剩余 ").append(boardShortDuration(it)) }
                } else if (run.startedAt > 0L) {
                    val ms = if (run.running) System.currentTimeMillis() - run.startedAt else run.durationMs
                    append("已用 ").append(boardShortDuration(ms))
                    if (run.problemCount > 0) append(" · 问题 ").append(run.problemCount)
                    if (run.errorCount > 0) append(" · 错误 ").append(run.errorCount)
                }
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = PanelPalette.TextSecondary)
            }
        }
    }
}

/** 目标卡片的标签：`N/M 状态`。 */
private fun planProgressText(plan: AgentPlan): String {
    val done = plan.steps.count { it.status == AgentPlanStepStatus.COMPLETED }
    return "$done/${plan.steps.size} ${plan.status.label()}"
}

private fun AgentPlanStatus.label(): String = when (this) {
    AgentPlanStatus.COMPLETED -> "已完成"
    AgentPlanStatus.FAILED -> "失败"
    AgentPlanStatus.CANCELLED -> "已取消"
    AgentPlanStatus.EXECUTING -> "执行中"
    AgentPlanStatus.PAUSED -> "已暂停"
    AgentPlanStatus.DRAFT -> "草稿"
    AgentPlanStatus.APPROVED -> "已批准"
}

/** 执行计划页签：编号 + 勾选标记 + 标题（进行中的步骤额外显示动作与说明）。 */
@Composable
private fun PlanStepsBlock(steps: List<AgentPlanStep>) {
    val done = steps.count { it.status == AgentPlanStepStatus.COMPLETED }
    Surface(color = PanelPalette.Card, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "执行计划 · $done/${steps.size}",
                style = MaterialTheme.typography.labelMedium,
                color = PanelPalette.TextSecondary
            )
            steps.forEachIndexed { index, step ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "%02d".format(index + 1),
                        style = MaterialTheme.typography.labelSmall,
                        color = PanelPalette.Muted,
                        modifier = Modifier.width(22.dp)
                    )
                    Text(
                        step.status.planMark(),
                        style = MaterialTheme.typography.labelMedium,
                        color = step.status.stateColor(),
                        modifier = Modifier.width(18.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            step.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = PanelPalette.TextPrimary,
                            maxLines = if (step.status == AgentPlanStepStatus.RUNNING) 2 else 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (step.status == AgentPlanStepStatus.RUNNING && step.description.isNotBlank()) {
                            Text(
                                step.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = PanelPalette.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (step.requiresApproval && step.status == AgentPlanStepStatus.PENDING) {
                        PanelChip("待批", BoardTaskState.WAITING)
                    }
                }
            }
        }
    }
}

/** 收起态：一行说明「当前在做什么 / 还剩什么」。 */
@Composable
private fun CompactSummary(rows: List<BoardTask>) {
    val current = rows.firstOrNull { it.group == BoardGroup.CURRENT }
    val future = rows.count { it.group == BoardGroup.FUTURE }
    val latest = rows.firstOrNull { it.group == BoardGroup.HISTORY }
    val line = when {
        current != null -> "正在执行：" + current.title
        future > 0 -> "$future 个任务待执行"
        latest != null -> "最近：" + latest.title + " · " + latest.stateLabel
        else -> "暂无进行中的任务"
    }
    Text(
        line,
        style = MaterialTheme.typography.labelSmall,
        color = PanelPalette.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 一个任务分组：分隔线 + 标题 `(数量)` + 行列表 + 「还有 N 条」+ 可选页脚按钮。
 *
 * 非紧凑形态下空分组也显示标题与 `(0)`（参考图如此，用户能一眼确认「确实没有」）；
 * 紧凑形态下空分组直接省略，避免工作台被四行空标题占满。
 */
@Composable
private fun TaskSection(
    app: NebulaForgeApplication,
    group: BoardGroup,
    rows: List<BoardTask>,
    compact: Boolean,
    limit: Int,
    tasks: List<TaskDefinition>,
    rootPath: String?,
    footer: (() -> Unit)? = null
) {
    if (compact && rows.isEmpty()) return
    HorizontalDivider(color = PanelPalette.Divider)
    Text(
        "${group.title} (${rows.size})",
        style = MaterialTheme.typography.labelMedium,
        color = PanelPalette.TextSecondary
    )
    if (rows.isEmpty()) {
        Text("暂无", style = MaterialTheme.typography.labelSmall, color = PanelPalette.Muted)
        return
    }
    rows.take(limit).forEach { row ->
        BoardTaskRow(
            row = row,
            compact = compact,
            onRun = { runBoardTask(app, row, tasks, rootPath) },
            onStop = { app.workspaceTasks.stop() },
            onDelete = { app.dismissBoardTask(row.id) },
            onToggleDisabled = { app.setBoardTaskDisabled(row.id, !row.enabled) }
        )
    }
    if (rows.size > limit) {
        Text(
            "还有 ${rows.size - limit} 条…",
            style = MaterialTheme.typography.labelSmall,
            color = PanelPalette.Muted
        )
    }
    footer?.let { open ->
        TextButton(onClick = open) {
            Text("打开执行计划页", style = MaterialTheme.typography.labelSmall, color = PanelPalette.Primary)
        }
    }
}

/** 一行任务：状态圆点 + 标题/明细 + `N/M 状态` 标签 + 动作 + 删除 `×`。 */
@Composable
private fun BoardTaskRow(
    row: BoardTask,
    compact: Boolean,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onToggleDisabled: () -> Unit
) {
    Surface(color = PanelPalette.Card, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 参考图每行标题前有一个实心圆点，颜色跟随状态。
                Box(Modifier.width(14.dp)) {
                    Text("●", style = MaterialTheme.typography.labelSmall, color = row.state.stateColor())
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        row.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.state == BoardTaskState.DISABLED) PanelPalette.Muted
                        else PanelPalette.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.detail.isNotBlank()) {
                        Text(
                            row.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = PanelPalette.TextSecondary,
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                // 参考图里进度与状态挤在同一个标签里（如「0/7 已用」「6/7 已禁用」）。
                PanelChip(row.chip, row.state)
                if (!compact) {
                    when {
                        row.state == BoardTaskState.RUNNING -> RowAction("停止", PanelPalette.Danger, onStop)
                        row.source == BoardTask.SOURCE_TASK && row.enabled ->
                            RowAction("运行", PanelPalette.Primary, onRun)
                        else -> RowAction(
                            if (row.enabled) "禁用" else "启用",
                            if (row.enabled) PanelPalette.Muted else PanelPalette.Primary,
                            onToggleDisabled
                        )
                    }
                }
                // 参考图每行右侧的 ×：单独删掉这一条（跨重启生效）。
                TextButton(onClick = onDelete) {
                    Text("✕", style = MaterialTheme.typography.labelMedium, color = PanelPalette.Muted)
                }
            }
            // 步骤执行输出：非紧凑形态才显示（工作台省空间，完整日志在「构建」面板）。
            if (!compact && row.output.isNotBlank()) {
                Text(
                    row.output,
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelPalette.TextSecondary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun RowAction(label: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(34.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 状态标签（参考图里「已用 / 已禁用」所在的位置）。 */
@Composable
private fun PanelChip(text: String, state: BoardTaskState) {
    val color = state.stateColor()
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

private fun BoardTaskState.stateColor(): Color = when (this) {
    BoardTaskState.DONE -> PanelPalette.Success
    BoardTaskState.RUNNING -> PanelPalette.Primary
    BoardTaskState.WAITING -> PanelPalette.Warning
    BoardTaskState.FAILED -> PanelPalette.Danger
    BoardTaskState.CANCELLED, BoardTaskState.DISABLED -> PanelPalette.Muted
    BoardTaskState.FUTURE -> PanelPalette.TextSecondary
}

private fun AgentPlanStepStatus.stateColor(): Color = when (this) {
    AgentPlanStepStatus.COMPLETED -> PanelPalette.Success
    AgentPlanStepStatus.RUNNING -> PanelPalette.Primary
    AgentPlanStepStatus.WAITING_APPROVAL -> PanelPalette.Warning
    AgentPlanStepStatus.FAILED -> PanelPalette.Danger
    else -> PanelPalette.Muted
}

private fun AgentPlanStepStatus.planMark(): String = when (this) {
    AgentPlanStepStatus.PENDING -> "○"
    AgentPlanStepStatus.WAITING_APPROVAL -> "⏸"
    AgentPlanStepStatus.RUNNING -> "▶"
    AgentPlanStepStatus.COMPLETED -> "✓"
    AgentPlanStepStatus.FAILED -> "✕"
    AgentPlanStepStatus.SKIPPED -> "⤼"
}

/**
 * 「运行」动作。
 *
 * 只有 tasks.json 里的任务能直接跑（把它交给工作区任务执行器）；
 * 计划类行没有独立运行的语义 —— 它由执行计划页批准后整体推进，
 * 这里点「运行」不做事比乱开一个进程更安全。
 */
private fun runBoardTask(
    app: NebulaForgeApplication,
    row: BoardTask,
    tasks: List<TaskDefinition>,
    rootPath: String?
) {
    if (row.source != BoardTask.SOURCE_TASK) return
    val root = rootPath?.let(::File)?.takeIf { it.isDirectory } ?: return
    val id = row.id.removePrefix(TaskBoardBuilder.PREFIX_TASK)
    tasks.firstOrNull { it.id == id }?.let { app.workspaceTasks.run(root, it) }
}

/**
 * 用量条：底部 `used/limit (%)` + `max_tokens`。
 *
 * 参考图这里是「余额」，但本 IDE 不代管用户账户额度、也拿不到真实余额，
 * 所以只展示服务端真实返回的 token 用量（宁缺勿假）。
 */
@Composable
private fun PanelUsageBar(state: AiChatUiState) {
    val used = maxOf(state.contextTokens, state.lastPromptTokens + state.lastCompletionTokens)
    val ratio = if (state.contextLimit <= 0) 0f else (used.toFloat() / state.contextLimit).coerceIn(0f, 1f)
    Surface(color = PanelPalette.Card, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$used/${state.contextLimit} (${(ratio * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelPalette.TextSecondary
                )
                Spacer(Modifier.weight(1f))
                Text(
                    buildString {
                        append("max_tokens ").append(state.maxTokens)
                        if (state.lastPromptTokens > 0) {
                            append(" · ↑").append(state.lastPromptTokens)
                            append(" ↓").append(state.lastCompletionTokens)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PanelPalette.TextSecondary
                )
            }
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = PanelPalette.Primary,
                trackColor = PanelPalette.Divider
            )
        }
    }
}
