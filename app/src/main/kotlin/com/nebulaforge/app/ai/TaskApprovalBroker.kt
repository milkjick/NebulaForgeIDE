package com.nebulaforge.app.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 需要用户点头的操作类别。 */
enum class TaskApprovalKind { COMMAND, WRITE_FILE, RUN_SKILL }

/**
 * 一次待确认操作。
 *
 * [command] 与 [patch] 二选一：命令走「执行确认」弹窗，写文件走「Diff 审查」弹窗。
 */
data class TaskApprovalRequest(
    val id: String,
    val callId: String,
    val kind: TaskApprovalKind,
    val title: String,
    val detail: String,
    val command: String = "",
    val patch: AiPendingPatch? = null
)

/**
 * 审批代理：让工具执行**挂起**直到用户确认，而不是偷偷执行或直接拒绝。
 *
 * 设计要点：
 *  - 单槽位即可：工具循环是串行的，任何时刻只可能有 1 个待确认操作；
 *  - 「本次任务内不再询问」按类别记忆（命令 / 写文件分开），新任务开始时 [reset]；
 *  - [reset] 会以「拒绝」唤醒挂起的等待者，保证停止任务时协程不会永远卡住。
 */
class TaskApprovalBroker {

    private val _pending = MutableStateFlow<TaskApprovalRequest?>(null)
    val pending: StateFlow<TaskApprovalRequest?> = _pending

    private var waiter: CompletableDeferred<Boolean>? = null
    private val autoApproved = linkedSetOf<TaskApprovalKind>()

    /**
     * 全自动模式：**默认开启** —— 用户明确要求「AI 自己执行所有操作和命令，不需要审批」。
     * 开启时命令 / 写文件 / 技能一律直接放行，不再逐条弹确认；破坏性命令仍有独立硬底线拦截。
     * 关掉即回到逐条确认：那是留给用户的安全绳，不是必须的手动配置。
     */
    private val _autoAll = MutableStateFlow(true)
    val autoAll: StateFlow<Boolean> = _autoAll

    fun setAutoAll(enabled: Boolean) { _autoAll.value = enabled }

    val autoApprovedKinds: Set<TaskApprovalKind> get() = autoApproved.toSet()

    fun isAutoApproved(kind: TaskApprovalKind): Boolean = kind in autoApproved

    /** 挂起等待用户对 [request] 的决定；返回 true 表示允许执行。 */
    suspend fun awaitApproval(request: TaskApprovalRequest): Boolean {
        if (_autoAll.value) return true
        if (isAutoApproved(request.kind)) return true
        val deferred = CompletableDeferred<Boolean>()
        waiter = deferred
        _pending.value = request
        return try {
            deferred.await()
        } finally {
            _pending.value = null
            waiter = null
        }
    }

    /** 界面回调：用户点了「允许/拒绝」，[remember] 表示本次任务内同类不再询问。 */
    fun resolve(approved: Boolean, remember: Boolean = false) {
        val request = _pending.value
        if (remember && request != null) autoApproved.add(request.kind)
        waiter?.complete(approved)
    }

    /** 任务结束/停止：清空记忆并以「拒绝」唤醒等待者，避免协程悬挂。 */
    fun reset() {
        autoApproved.clear()
        val current = waiter
        waiter = null
        _pending.value = null
        current?.complete(false)
    }
}
