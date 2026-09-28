package com.nebulaforge.core.agent.generate

import com.nebulaforge.core.projectmodel.BuildEvent
import com.nebulaforge.core.projectmodel.BuildSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * 流水线编排器（开发方案第 6.2 节）。
 *
 * 驱动状态机推进，支持暂停/恢复/取消；每个阶段转换都把当前 [PipelineState] 持久化到
 * [PipelineStateStore]，保证进程被杀死后可从最后一个完成的阶段恢复，而不是从头重来。
 *
 * 两个人工介入点（`PlanConfirmation` / `DeliveryReview`）会挂起等待外部调用
 * [confirmPlan] / [finalizeDelivery] 推进。
 */
class ProjectGenerationOrchestrator(
    private val aiClient: GenerationAiClient,
    private val scaffolder: ProjectScaffolder,
    private val buildSystem: BuildSystem,
    private val stateStore: PipelineStateStore,
    private val maxBuildRetries: Int = 5
) {
    private val transactions = LinkedHashMap<String, GenerationTransaction>()

    /** 取得（或创建）某会话的文件系统事务。真实项目目录在首次访问时确定。 */
    @Synchronized
    fun transaction(sessionId: String, realProjectDir: File): GenerationTransaction =
        transactions.getOrPut(sessionId) { GenerationTransaction(sessionId, realProjectDir) }

    /** 入口：新建一次生成会话（种子状态为阶段1「需求解析」） */
    fun start(sessionId: String, userPrompt: String) {
        stateStore.save(sessionId, PipelineState.RequirementParsing(userPrompt))
    }

    /**
     * 从已持久化的状态继续推进流水线，逐阶段 emit 新状态。
     * 遇到 [PipelineState.awaitsUserInput]（方案确认 / 交付审查）或终态时停止。
     */
    fun run(
        sessionId: String,
        realProjectDir: File,
        packageName: String = "com.example.nebulaforge",
        buildTask: String = "assembleDebug",
        env: Map<String, String> = emptyMap(),
        onLog: (String) -> Unit = {}
    ): Flow<PipelineState> = flow {
        val context = AdvanceContext(sessionId, realProjectDir, packageName, buildTask, env, onLog)
        var state = stateStore.load(sessionId) ?: PipelineState.RequirementParsing("")
        while (!state.isTerminal) {
            state = advance(state, context)
            stateStore.save(sessionId, state)
            emit(state)
            if (state.awaitsUserInput) break
        }
    }

    /** 供用户在 PlanConfirmation 阶段点击「确认」后调用，携带用户修改后的方案 */
    fun confirmPlan(sessionId: String, finalPlan: ProjectPlan) {
        stateStore.save(sessionId, PipelineState.ScaffoldGeneration(finalPlan))
    }

    /** 供用户在 DeliveryReview 阶段逐文件确认后调用；全部文件已表态即提交，否则丢弃临时工作区 */
    @Synchronized
    fun finalizeDelivery(sessionId: String, changes: List<FileChange>) {
        val allDecided = changes.isNotEmpty() && changes.all { it.status != FileChange.ChangeStatus.PENDING }
        if (!allDecided) {
            stateStore.save(sessionId, PipelineState.DeliveryReview(changes))
            return
        }
        val transaction = transactions[sessionId]
        val accepted = changes.filter { it.status == FileChange.ChangeStatus.ACCEPTED }.map { it.path }.toSet()
        when {
            accepted.isEmpty() -> transaction?.rollback()
            else -> transaction?.commit(accepted)
        }
        transactions.remove(sessionId)
        // 保留 Completed 终态记录：再次 run() 会立即因 isTerminal 退出，不会从头重跑
        stateStore.save(sessionId, PipelineState.Completed)
    }

    /** 用户取消生成：丢弃整个临时工作区，真实项目不受影响 */
    @Synchronized
    fun cancel(sessionId: String) {
        transactions.remove(sessionId)?.rollback()
        stateStore.clear(sessionId)
    }

    // ---- 内部推进 ----

    private data class AdvanceContext(
        val sessionId: String,
        val realProjectDir: File,
        val packageName: String,
        val buildTask: String,
        val env: Map<String, String>,
        val onLog: (String) -> Unit
    )

    private suspend fun advance(state: PipelineState, context: AdvanceContext): PipelineState = when (state) {
        is PipelineState.RequirementParsing -> if (state.userPrompt.isBlank()) {
            PipelineState.Failed("RequirementParsing", "需求描述为空，请先输入项目需求")
        } else {
            translate("RequirementParsing") { PipelineState.TechStackInference(aiClient.parseRequirement(state.userPrompt)) }
        }

        is PipelineState.TechStackInference ->
            translate("TechStackInference") { PipelineState.PlanConfirmation(aiClient.inferTechStack(state.requirements)) }

        // 等待用户手动确认，由外部调用 confirmPlan() 推进
        is PipelineState.PlanConfirmation -> state

        is PipelineState.ScaffoldGeneration -> {
            val transaction = transaction(context.sessionId, context.realProjectDir)
            translate("ScaffoldGeneration") {
                PipelineState.DependencyResolution(
                    scaffolder.scaffold(state.plan, transaction.tempDir, context.packageName)
                )
            }
        }

        is PipelineState.DependencyResolution -> translate("DependencyResolution") {
            PipelineState.CodeGeneration(aiClient.planRemainingFiles(state.scaffold), emptyList())
        }

        is PipelineState.CodeGeneration -> if (state.fileQueue.isEmpty()) {
            PipelineState.BuildVerification(attemptCount = 0, lastErrors = emptyList())
        } else {
            val next = state.fileQueue.first()
            val transaction = transaction(context.sessionId, context.realProjectDir)
            translate("CodeGeneration") {
                val content = aiClient.generateFile(next, state.completed)
                // 6.3：先落临时工作区，绝不直接写真实项目目录
                transaction.stageFile(next.path, content)
                PipelineState.CodeGeneration(
                    fileQueue = state.fileQueue.drop(1),
                    completed = state.completed + GeneratedFile(next.path, content)
                )
            }
        }

        is PipelineState.BuildVerification -> verifyBuild(state, context)

        is PipelineState.RunVerification ->
            PipelineState.DeliveryReview(
                transaction(context.sessionId, context.realProjectDir).changeSummary()
            )

        // 等待用户逐文件 accept/reject
        is PipelineState.DeliveryReview -> state
        is PipelineState.Failed -> state
        is PipelineState.Completed -> state
    }

    private suspend fun verifyBuild(state: PipelineState.BuildVerification, context: AdvanceContext): PipelineState {
        val transaction = transaction(context.sessionId, context.realProjectDir)
        val logs = StringBuilder()
        var success = false
        try {
            buildSystem.build(context.buildTask, transaction.tempDir, context.env).collect { event ->
                when (event) {
                    is BuildEvent.LogLine -> {
                        logs.append(event.text).append('\n')
                        context.onLog(event.text)
                    }
                    is BuildEvent.Progress -> context.onLog("[${event.percent}%] ${event.message}")
                    is BuildEvent.Finished -> success = event.success
                }
            }
        } catch (t: Throwable) {
            return fail("BuildVerification", t)
        }
        if (success) return PipelineState.RunVerification(crashLog = null)

        val errors = runCatching { buildSystem.parseErrors(logs.toString()) }.getOrDefault(emptyList())
        return when {
            state.attemptCount >= maxBuildRetries ->
                PipelineState.Failed("BuildVerification", "达到最大重试次数（$maxBuildRetries），仍有 ${errors.size} 个错误")

            errors.isEmpty() ->
                PipelineState.Failed("BuildVerification", "构建失败但未解析出结构化错误，请查看构建日志")

            else -> {
                // 触发自动修复：把修复结果写回临时工作区后重试构建
                val fixes = runCatching { aiClient.fixBuildErrors(errors, transaction.tempDir) }.getOrDefault(emptyList())
                fixes.forEach { fix -> runCatching { transaction.stageFile(fix.path, fix.content) } }
                PipelineState.BuildVerification(state.attemptCount + 1, errors)
            }
        }
    }

    /** 统一把阶段内的异常收敛为 [PipelineState.Failed]，避免整条流水线因单阶段异常直接崩溃 */
    private suspend fun translate(stage: String, block: suspend () -> PipelineState): PipelineState =
        try {
            block()
        } catch (t: Throwable) {
            fail(stage, t)
        }

    private fun fail(stage: String, t: Throwable): PipelineState.Failed {
        if (t is CancellationException) throw t
        return PipelineState.Failed(stage, t.message ?: t.toString())
    }
}
