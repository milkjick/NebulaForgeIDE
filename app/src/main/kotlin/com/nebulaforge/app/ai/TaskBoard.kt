package com.nebulaforge.app.ai

import android.content.Context
import com.nebulaforge.app.build.TaskRunState
import com.nebulaforge.core.agent.AgentPlan
import com.nebulaforge.core.agent.AgentPlanStatus
import com.nebulaforge.core.agent.AgentPlanStepStatus
import com.nebulaforge.core.projectmodel.TaskDefinition
import java.io.File

/**
 * 任务中心「多任务列表」的数据模型。
 *
 * ## 为什么要有它（旧实现的结构性问题）
 * 旧面板把「计划步骤 / 构建运行 / tasks.json 任务」三类东西**临时拼**成若干行，
 * 每一行只有标题与一句状态，既不能表达「一个任务自己的进度（3/7）」，也不能单独删除；
 * 于是「历史任务」永远只能靠 WorkspaceTaskRunner.history 里的一条条运行记录，
 * 用户没法把不要的条目清掉 —— 参考图里每个任务行右侧都有一个 `×`，正是为此。
 *
 * 本文件把「任务行」提升成一等数据：[BoardTask]。
 * 列表本身是**派生**的（由计划 / 运行状态 / tasks.json 实时算出，绝不存假数据），
 * 需要持久化的只有两件事，都是用户的**选择**：
 *  - 删掉了哪些行（[TaskBoardStore.dismissed]）；
 *  - 手动禁用了哪些行（[TaskBoardStore.disabled]，对应参考图的「已禁用」）。
 *
 * 这样「删除」不会在下一次刷新时复活，「禁用」也能跨重启保留。
 */

/** 任务行状态。文案对应参考图：「N/M 状态」。 */
enum class BoardTaskState { FUTURE, RUNNING, WAITING, DONE, FAILED, CANCELLED, DISABLED }

/** 参考图的四个分组（顺序即渲染顺序）。 */
enum class BoardGroup(val title: String) {
    FUTURE("未来任务"),
    CURRENT("当前任务"),
    PLAN("执行计划"),
    HISTORY("历史任务")
}

/**
 * 一行任务。
 *
 * @param id 稳定标识（`plan:` / `step:` / `run:` / `task:` 前缀），删除与禁用都按它记账
 * @param done 已完成步数；`total<=0` 时不显示进度前缀（构建/运行类任务没有步骤概念）
 * @param source 来源：决定「运行 / 停止 / 删除」各自动作与状态文案
 */
data class BoardTask(
    val id: String,
    val title: String,
    val detail: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val state: BoardTaskState = BoardTaskState.FUTURE,
    val enabled: Boolean = true,
    val elapsedMs: Long = 0L,
    val projectRoot: String = "",
    val output: String = "",
    val source: String = SOURCE_TASK,
    val commandLine: String = ""
) {
    /** 「5/7」；没有步骤概念的任务为空串。 */
    val progressLabel: String get() = if (total > 0) "$done/$total" else ""

    /** 状态文案：计划步骤完成叫「已用」，整份计划完成才叫「已完成」（参考图如此）。 */
    val stateLabel: String get() = when (state) {
        BoardTaskState.FUTURE -> "待执行"
        BoardTaskState.RUNNING -> "执行中"
        BoardTaskState.WAITING -> "待批"
        BoardTaskState.DONE -> if (source == SOURCE_PLAN_STEP) "已用" else "已完成"
        BoardTaskState.FAILED -> "失败"
        BoardTaskState.CANCELLED -> "已取消"
        BoardTaskState.DISABLED -> "已禁用"
    }

    /** 行尾标签，如 `0/7 已用`、`6/7 已禁用`、`执行中`。 */
    val chip: String get() = listOf(progressLabel, stateLabel).filter { it.isNotBlank() }.joinToString(" ")

    /** 该行属于哪个分组。禁用的任务还没跑、也不会跑，归「未来任务」。 */
    val group: BoardGroup get() = when (state) {
        BoardTaskState.RUNNING, BoardTaskState.WAITING -> BoardGroup.CURRENT
        BoardTaskState.FUTURE, BoardTaskState.DISABLED -> BoardGroup.FUTURE
        BoardTaskState.DONE, BoardTaskState.FAILED, BoardTaskState.CANCELLED ->
            if (source == SOURCE_PLAN) BoardGroup.PLAN else BoardGroup.HISTORY
    }

    companion object {
        const val SOURCE_PLAN = "plan"
        const val SOURCE_PLAN_STEP = "plan-step"
        const val SOURCE_RUN = "run"
        const val SOURCE_TASK = "task"
    }
}

/**
 * 「删除 / 禁用」两个用户选择的持久化。
 *
 * 只存**标识集合**，不存任务内容本身：任务内容永远从真实运行态派生，
 * 因此不会出现「面板里的历史任务和实际跑过的任务对不上」这种假数据问题。
 */
class TaskBoardStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nebula_task_board", Context.MODE_PRIVATE)

    fun dismissed(): Set<String> = prefs.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet()
    fun disabled(): Set<String> = prefs.getStringSet(KEY_DISABLED, emptySet()).orEmpty().toSet()

    /** 删掉一行（跨重启生效）。 */
    fun dismiss(id: String) {
        prefs.edit().putStringSet(KEY_DISMISSED, dismissed() + id).apply()
    }

    /** 恢复所有被删掉的行。 */
    fun restoreAll() {
        prefs.edit().remove(KEY_DISMISSED).apply()
    }

    /** 禁用/启用一行（参考图的「已禁用」）。 */
    fun setDisabled(id: String, disabled: Boolean) {
        val next = if (disabled) disabled() + id else disabled() - id
        prefs.edit().putStringSet(KEY_DISABLED, next).apply()
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed"
        const val KEY_DISABLED = "disabled"
    }
}

/** 把三类真实数据源拼成一份有序的任务行列表。纯函数，方便单测。 */
object TaskBoardBuilder {

    fun build(
        plan: AgentPlan?,
        run: TaskRunState,
        history: List<TaskRunState>,
        tasks: List<TaskDefinition>,
        projectRoot: String?,
        dismissed: Set<String>,
        disabled: Set<String>
    ): List<BoardTask> {
        val root = projectRoot.orEmpty()
        val rows = ArrayList<BoardTask>()
        val steps = plan?.steps.orEmpty()

        // 1) 执行计划本身（参考图「执行计划」分组里的一行）。
        if (plan != null) {
            rows += BoardTask(
                id = "$PREFIX_PLAN${plan.id}",
                title = plan.summary.ifBlank { plan.userRequest },
                detail = plan.userRequest,
                done = steps.count { it.status == AgentPlanStepStatus.COMPLETED },
                total = steps.size,
                state = plan.status.boardState(),
                source = BoardTask.SOURCE_PLAN,
                projectRoot = root
            )
        }

        // 2) 计划步骤：按状态落进未来 / 当前 / 历史三个分组。
        steps.forEachIndexed { index, step ->
            rows += BoardTask(
                id = "$PREFIX_STEP${plan?.id ?: "-"}:${step.id}",
                title = step.title,
                detail = step.description.ifBlank { step.action.name },
                done = index + 1,
                total = steps.size,
                state = step.status.boardState(),
                source = BoardTask.SOURCE_PLAN_STEP,
                output = step.output,
                projectRoot = root
            )
        }

        // 3) 构建/运行：正在跑的那条 + 历史记录（历史由 WorkspaceTaskRunner 维护，含退出码）。
        if (run.startedAt > 0L) rows += run.toBoardTask()
        history.forEach { rows += it.toBoardTask() }

        // 4) 当前工程 tasks.json 里可运行的任务。
        tasks.forEach { task ->
            rows += BoardTask(
                id = "$PREFIX_TASK${task.id}",
                title = task.label,
                detail = task.detail
                    ?: task.commandLine().let { if (it.length > 90) it.take(87) + "…" else it },
                state = BoardTaskState.FUTURE,
                source = BoardTask.SOURCE_TASK,
                commandLine = task.commandLine(),
                projectRoot = root
            )
        }

        return rows
            // 用户删掉的行不再出现（实时刷新也不会复活）。
            .filterNot { it.id in dismissed }
            // 「已禁用」是用户选择，覆盖原本的状态。
            .map { if (it.id in disabled) it.copy(state = BoardTaskState.DISABLED, enabled = false) else it }
            .sortedWith(compareBy({ it.group.ordinal }, { it.done }, { it.title }))
    }

    const val PREFIX_PLAN = "plan:"
    const val PREFIX_STEP = "step:"
    const val PREFIX_RUN = "run:"
    const val PREFIX_TASK = "task:"
}

private fun TaskRunState.toBoardTask(): BoardTask {
    val ms = if (running) (System.currentTimeMillis() - startedAt).coerceAtLeast(0L) else durationMs
    val detail = buildString {
        append(message)
        if (ms > 0) append(" · ").append(boardShortDuration(ms))
        exitCode?.let { append(" · 退出码 ").append(it) }
        if (problemCount > 0) append(" · 问题 ").append(problemCount)
        if (errorCount > 0) append(" · 错误 ").append(errorCount)
        if (projectRoot.isNotBlank()) append(" · ").append(File(projectRoot).name)
    }
    val state = when {
        running -> BoardTaskState.RUNNING
        cancelled -> BoardTaskState.CANCELLED
        success == true -> BoardTaskState.DONE
        success == false -> BoardTaskState.FAILED
        else -> BoardTaskState.CANCELLED
    }
    return BoardTask(
        id = "${TaskBoardBuilder.PREFIX_RUN}$startedAt",
        title = label.ifBlank { "构建任务" },
        detail = detail,
        state = state,
        elapsedMs = ms,
        projectRoot = projectRoot,
        source = BoardTask.SOURCE_RUN,
        commandLine = ""
    )
}

internal fun AgentPlanStatus.boardState(): BoardTaskState = when (this) {
    AgentPlanStatus.COMPLETED -> BoardTaskState.DONE
    AgentPlanStatus.FAILED -> BoardTaskState.FAILED
    AgentPlanStatus.CANCELLED -> BoardTaskState.CANCELLED
    AgentPlanStatus.EXECUTING -> BoardTaskState.RUNNING
    AgentPlanStatus.PAUSED -> BoardTaskState.WAITING
    AgentPlanStatus.DRAFT, AgentPlanStatus.APPROVED -> BoardTaskState.FUTURE
}

internal fun AgentPlanStepStatus.boardState(): BoardTaskState = when (this) {
    AgentPlanStepStatus.PENDING -> BoardTaskState.FUTURE
    AgentPlanStepStatus.WAITING_APPROVAL -> BoardTaskState.WAITING
    AgentPlanStepStatus.RUNNING -> BoardTaskState.RUNNING
    AgentPlanStepStatus.COMPLETED -> BoardTaskState.DONE
    AgentPlanStepStatus.FAILED -> BoardTaskState.FAILED
    AgentPlanStepStatus.SKIPPED -> BoardTaskState.CANCELLED
}

/** `1小时02分` / `3分07秒` / `45秒`（与面板其它位置保持一致的中文短时长）。 */
internal fun boardShortDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}小时${"%02d".format(m)}分"
        m > 0 -> "${m}分${"%02d".format(s)}秒"
        else -> "${s}秒"
    }
}
