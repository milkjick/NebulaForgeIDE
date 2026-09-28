package com.nebulaforge.app.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** AI 向用户提出的一个问题。 */
data class AskRequest(
    val id: String,
    val question: String,
    val options: List<String> = emptyList()
)

/**
 * AI 提问通道：`ask_user` 工具把问题交给它并**挂起**，等用户在工作台作答后再恢复。
 *
 * 为什么需要它：以前 AI 缺关键信息（目标平台、包名、方案取舍）时只能「猜」或「把问题写进回答里然后结束」——
 * 结束之后用户回了话，AI 也接不上上下文。有了挂起通道，AI 可以在同一个任务里问、等、继续。
 */
class AskBroker {

    private val _pending = MutableStateFlow<AskRequest?>(null)

    /** 界面监听这个流：非空时弹出提问对话框。 */
    val pending: StateFlow<AskRequest?> = _pending.asStateFlow()

    private var waiter: CompletableDeferred<String>? = null

    /** 挂起直到用户作答（或任务被取消）。 */
    suspend fun ask(question: String, options: List<String>): String {
        val request = AskRequest("ask-" + System.nanoTime(), question.trim(), options)
        val deferred = CompletableDeferred<String>()
        waiter = deferred
        _pending.value = request
        return try {
            deferred.await()
        } finally {
            _pending.value = null
            waiter = null
        }
    }

    /** 用户在界面作答。 */
    fun answer(text: String): Boolean {
        val deferred = waiter ?: return false
        _pending.value = null
        return deferred.complete(text.ifBlank { "（用户未填写内容）" })
    }

    /** 界面被销毁/任务取消时放弃等待。 */
    fun cancel() {
        val deferred = waiter ?: return
        _pending.value = null
        deferred.complete("（用户没有回答，任务已中断）")
    }
}
