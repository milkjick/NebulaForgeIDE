package com.nebulaforge.core.agent

import android.content.Context
import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.RunConfigurationSpec
import com.nebulaforge.core.session.DiagnosticStore
import com.nebulaforge.core.session.UnifiedRunController
import com.nebulaforge.core.session.WorkspaceStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/** Durable Build -> Problems -> AI proposal -> review -> transactional commit -> rebuild coordinator. */
class BuildFixCoordinator(
    context: Context,
    private val unifiedRunController: UnifiedRunController,
    private val diagnostics: DiagnosticStore,
    private val workspace: WorkspaceStateStore,
    private val runConfigurations: kotlinx.coroutines.flow.StateFlow<List<RunConfigurationSpec>>,
    private val bus: com.nebulaforge.core.session.IdeSessionBus,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate)
) {
    data class State(
        val phase: Phase = Phase.IDLE,
        val projectPath: String? = null,
        val proposal: BuildFixProposal? = null,
        val errorCount: Int = 0,
        val message: String = "",
        val round: Int = 0,
        val maxRounds: Int = 3,
        val sourceBuildSessionId: String? = null,
        val rebuildSessionId: String? = null,
        val history: List<RoundRecord> = emptyList(),
        val diagnosticSignature: String? = null,
        val hunkStatuses: Map<String, Map<Int, DiffReviewEngine.HunkStatus>> = emptyMap()
    )

    data class RoundRecord(
        val round: Int,
        val buildSessionId: String?,
        val fixSessionId: String,
        val rebuildSessionId: String?,
        val errorCount: Int,
        val outcome: String,
        val diagnosticSignature: String? = null
    )

    enum class Phase { IDLE, ANALYZING, REVIEW, APPLYING, REBUILDING, RESOLVED, FAILED }

    private val settingsStore = AiProviderSettingsStore(context)
    private val taskStore = BuildFixTaskStore(context)
    private val _state = MutableStateFlow(State(maxRounds = 3))
    val state: StateFlow<State> = _state.asStateFlow()
    private var transaction: GenerationTransaction? = null
    private var job: Job? = null

    private fun sessionFor(proposal: BuildFixProposal, projectPath: String): com.nebulaforge.core.session.IdeSession {
        return com.nebulaforge.core.session.IdeSession(proposal.sessionId, com.nebulaforge.core.session.SessionKind.BUILD_FIX).also {
            bus.register(it, projectPath)
        }
    }

    private fun emitSessionState(proposal: BuildFixProposal, projectPath: String, state: com.nebulaforge.core.session.SessionState) {
        bus.state(sessionFor(proposal, projectPath), state, projectPath)
    }

    init { recoverPersistedTask() }

    private fun publish(value: State) {
        _state.value = value
        if (value.phase == Phase.IDLE) taskStore.clear() else taskStore.save(value)
    }

    private fun recoverPersistedTask() {
        val record = taskStore.load() ?: return
        val phase = runCatching { Phase.valueOf(record.phase) }.getOrDefault(Phase.IDLE)
        val proposal = record.proposal
        val project = record.projectPath?.let(::File)

        if (phase == Phase.REVIEW && proposal != null && project?.isDirectory == true) {
            transaction = GenerationTransaction(proposal.sessionId, project)
            emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Preparing("恢复待审查 AI 修复任务"))
            publish(State(Phase.REVIEW, project.absolutePath, proposal, record.errorCount,
                record.message.ifBlank { "已恢复待审查的 AI 修复任务" }, record.round, record.maxRounds,
                record.sourceBuildSessionId, record.rebuildSessionId, record.history, record.diagnosticSignature, record.hunkStatuses))
            return
        }

        // A process death during APPLYING must not leave a partially committed proposal.
        if ((phase == Phase.APPLYING || phase == Phase.REBUILDING) && proposal != null && project != null) {
            val rollback = GenerationTransaction.recoverAndRollback(proposal.sessionId, project)
            publish(State(
                Phase.FAILED,
                project.absolutePath,
                proposal,
                record.errorCount,
                if (rollback) "上次 AI 修复任务在应用重启时中断，已自动回滚未完成的文件提交；请重新分析当前 Problems"
                else "上次 AI 修复任务在应用重启时中断，无法确认事务状态，请检查文件后重新分析",
                record.round, record.maxRounds, record.sourceBuildSessionId, record.rebuildSessionId, record.history, record.diagnosticSignature, record.hunkStatuses
            ))
            return
        }

        if (phase == Phase.ANALYZING) {
            publish(State(Phase.FAILED, record.projectPath, proposal, record.errorCount,
                "上次 AI 分析在应用重启时中断，请重新分析当前 Problems", record.round, record.maxRounds,
                record.sourceBuildSessionId, record.rebuildSessionId, record.history, record.diagnosticSignature, record.hunkStatuses))
            return
        }

        publish(State(phase, record.projectPath, proposal, record.errorCount, record.message, record.round, record.maxRounds,
            record.sourceBuildSessionId, record.rebuildSessionId, record.history, record.diagnosticSignature, record.hunkStatuses))
    }

    private fun signatureFor(values: List<com.nebulaforge.core.session.IdeEvent.Diagnostic>): String =
        values.filter { it.severity == com.nebulaforge.core.session.Severity.ERROR }
            .sortedWith(compareBy({ it.file ?: "" }, { it.line ?: -1 }, { it.column ?: -1 }, { it.message }))
            .joinToString("\n") { listOf(it.file ?: "", it.line ?: -1, it.column ?: -1, it.message).joinToString("|") }

    private fun hunkModel(change: FileChange, root: File): List<DiffReviewEngine.DiffHunk> {
        val original = File(root, change.relativePath).takeIf { it.isFile }?.readText().orEmpty()
        val statuses = _state.value.hunkStatuses[change.relativePath].orEmpty()
        return DiffReviewEngine.diff(original, change.proposedContent).map { h -> h.copy(status = statuses[h.index] ?: DiffReviewEngine.HunkStatus.PENDING) }
    }

    fun reviewHunksForAbsolutePath(path: String): List<DiffReviewEngine.DiffHunk> {
        val root = _state.value.projectPath?.let(::File) ?: return emptyList()
        val relative = runCatching { File(path).canonicalFile.relativeTo(root.canonicalFile).path }.getOrNull() ?: return emptyList()
        return reviewHunks(relative)
    }

    fun reviewHunks(relativePath: String): List<DiffReviewEngine.DiffHunk> {
        val proposal = _state.value.proposal ?: return emptyList()
        val root = _state.value.projectPath?.let(::File) ?: return emptyList()
        val change = proposal.changes.firstOrNull { it.relativePath == relativePath } ?: return emptyList()
        val original = File(root, relativePath).takeIf { it.isFile }?.readText().orEmpty()
        val statuses = _state.value.hunkStatuses[relativePath].orEmpty()
        return DiffReviewEngine.diff(original, change.proposedContent).map { h ->
            h.copy(status = statuses[h.index] ?: DiffReviewEngine.HunkStatus.PENDING)
        }
    }

    fun reviewHunk(relativePath: String, hunkIndex: Int): DiffReviewEngine.DiffHunk? =
        reviewHunks(relativePath).firstOrNull { it.index == hunkIndex }

    /**
     * Replaces the staged proposal content with the current editor buffer.
     * This never writes the project file. The next Apply step will run the
     * normal GenerationTransaction + SHA-256 concurrency checks.
     */
    fun replaceProposalContentFromEditor(relativePath: String, editedContent: String, acceptAllHunks: Boolean = false): Boolean {
        val current = _state.value.proposal ?: return false
        val root = _state.value.projectPath?.let(::File) ?: return false
        if (current.changes.none { it.relativePath == relativePath }) return false
        val original = File(root, relativePath).takeIf { it.isFile }?.readText().orEmpty()
        val hunks = DiffReviewEngine.diff(original, editedContent)
        val nextStatus = if (hunks.isEmpty()) FileChange.Status.PENDING
            else if (acceptAllHunks) FileChange.Status.ACCEPTED else FileChange.Status.PENDING
        val nextProposal = current.copy(changes = current.changes.map {
            if (it.relativePath == relativePath) it.copy(proposedContent = editedContent, status = nextStatus) else it
        })
        val map = _state.value.hunkStatuses.toMutableMap()
        map[relativePath] = hunks.associate {
            it.index to if (acceptAllHunks) DiffReviewEngine.HunkStatus.ACCEPTED else DiffReviewEngine.HunkStatus.PENDING
        }
        publish(_state.value.copy(
            proposal = nextProposal,
            hunkStatuses = map,
            message = if (acceptAllHunks) "已将编辑器内容作为 ${relativePath} 的人工修订并接受"
            else "已将编辑器内容作为 ${relativePath} 的候选修订；尚未写入项目"
        ))
        return true
    }

    fun updateHunk(relativePath: String, hunkIndex: Int, accepted: Boolean) {
        val current = _state.value.proposal ?: return
        val root = _state.value.projectPath?.let(::File) ?: return
        val change = current.changes.firstOrNull { it.relativePath == relativePath } ?: return
        val original = File(root, relativePath).takeIf { it.isFile }?.readText().orEmpty()
        val baseHunks = DiffReviewEngine.diff(original, change.proposedContent)
        if (hunkIndex !in baseHunks.indices) return
        val map = _state.value.hunkStatuses.toMutableMap()
        val statuses = map[relativePath].orEmpty().toMutableMap()
        statuses[hunkIndex] = if (accepted) DiffReviewEngine.HunkStatus.ACCEPTED else DiffReviewEngine.HunkStatus.REJECTED
        map[relativePath] = statuses
        val nextFileStatus = when {
            statuses.values.any { it == DiffReviewEngine.HunkStatus.ACCEPTED } -> FileChange.Status.ACCEPTED
            baseHunks.isNotEmpty() && statuses.values.count { it == DiffReviewEngine.HunkStatus.REJECTED } == baseHunks.size -> FileChange.Status.REJECTED
            else -> change.status
        }
        val nextProposal = current.copy(changes = current.changes.map {
            if (it.relativePath == relativePath) it.copy(status = nextFileStatus) else it
        })
        publish(_state.value.copy(proposal = nextProposal, hunkStatuses = map, message = "已更新 ${relativePath} 第 ${hunkIndex + 1} 个 Diff Hunk"))
    }

    fun acceptAllHunks(relativePath: String) {
        val current = _state.value.proposal ?: return
        val root = _state.value.projectPath?.let(::File) ?: return
        val change = current.changes.firstOrNull { it.relativePath == relativePath } ?: return
        val original = File(root, relativePath).takeIf { it.isFile }?.readText().orEmpty()
        val hunks = DiffReviewEngine.diff(original, change.proposedContent)
        val map = _state.value.hunkStatuses.toMutableMap(); map[relativePath] = hunks.associate { it.index to DiffReviewEngine.HunkStatus.ACCEPTED }
        publish(_state.value.copy(hunkStatuses = map, message = "已接受 ${relativePath} 的全部 Diff Hunk"))
    }

    fun rejectAllHunks(relativePath: String) {
        val current = _state.value.proposal ?: return
        val root = _state.value.projectPath?.let(::File) ?: return
        val change = current.changes.firstOrNull { it.relativePath == relativePath } ?: return
        val original = File(root, relativePath).takeIf { it.isFile }?.readText().orEmpty()
        val hunks = DiffReviewEngine.diff(original, change.proposedContent)
        val map = _state.value.hunkStatuses.toMutableMap(); map[relativePath] = hunks.associate { it.index to DiffReviewEngine.HunkStatus.REJECTED }
        publish(_state.value.copy(hunkStatuses = map, message = "已拒绝 ${relativePath} 的全部 Diff Hunk"))
    }

    private fun analyzeInternal(project: File, currentRound: Int): Boolean {
        val sourceBuildSessionId = workspace.state.value.lastBuildSessionId
        val sourceDiagnostics = sourceBuildSessionId?.let { diagnostics.errorForSession(it) }
            ?: diagnostics.latestSessionWithErrors()?.let { diagnostics.errorForSession(it) }
            ?: emptyList()
        val errors = sourceDiagnostics.filter { it.file != null }
            .map { BuildError(it.file, it.line, it.column, it.message, BuildError.Severity.ERROR) }
        if (errors.isEmpty()) {
            publish(State(Phase.RESOLVED, project.absolutePath, errorCount = 0, round = currentRound,
                sourceBuildSessionId = sourceBuildSessionId, message = "当前构建没有剩余错误"))
            return false
        }
        val settings = settingsStore.load()
        if (!settings.enabled || settings.apiKey.isBlank()) {
            publish(State(Phase.FAILED, project.absolutePath, errorCount = errors.size, round = currentRound,
                sourceBuildSessionId = sourceBuildSessionId, message = "AI Provider 未启用或 API Key 未配置"))
            return false
        }
        job = scope.launch {
            val diagnosticSignature = signatureFor(sourceDiagnostics)
            publish(_state.value.copy(phase = Phase.ANALYZING, projectPath = project.absolutePath, errorCount = errors.size,
                round = currentRound, sourceBuildSessionId = sourceBuildSessionId, diagnosticSignature = diagnosticSignature,
                message = "正在分析第 $currentRound 轮构建错误…"))
            try {
                val agent = BuildFixAgent(OpenAiCompatibleCompletionClient(settings), bus = bus)
                val proposal = agent.propose(project, errors)
                sourceDiagnostics.filter { it.severity == com.nebulaforge.core.session.Severity.ERROR }.distinctBy {
                    listOf(it.file, it.line, it.column, it.message, it.sessionId)
                }.forEach { diagnostic ->
                    bus.emit(com.nebulaforge.core.session.IdeEvent.Relation(proposal.sessionId, diagnostic.sessionId, "fixes"))
                    bus.emit(com.nebulaforge.core.session.IdeEvent.Relation(diagnostic.sessionId, proposal.sessionId, "build_fix"))
                }
                sourceBuildSessionId?.let { source ->
                    bus.emit(com.nebulaforge.core.session.IdeEvent.Relation(source, proposal.sessionId, "build_fix"))
                    bus.emit(com.nebulaforge.core.session.IdeEvent.Relation(proposal.sessionId, source, "fixes"))
                }
                if (proposal.changes.isEmpty()) {
                    emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Failed("AI 没有提出安全修改"))
                    publish(State(Phase.FAILED, project.absolutePath, proposal, errors.size,
                        proposal.explanation.ifBlank { "AI 没有提出安全修改" }, currentRound, _state.value.maxRounds,
                        sourceBuildSessionId, diagnosticSignature = diagnosticSignature))
                    return@launch
                }
                transaction = agent.stage(proposal, project)
                emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Preparing("等待用户审查 AI 修改"))
                publish(_state.value.copy(phase = Phase.REVIEW, projectPath = project.absolutePath, proposal = proposal,
                    hunkStatuses = emptyMap(),
                    errorCount = errors.size, sourceBuildSessionId = sourceBuildSessionId, diagnosticSignature = diagnosticSignature,
                    message = "第 $currentRound 轮：请逐文件审查后选择接受"))
            } catch (t: Throwable) {
                publish(State(Phase.FAILED, project.absolutePath, errorCount = errors.size, round = currentRound,
                    sourceBuildSessionId = sourceBuildSessionId, diagnosticSignature = diagnosticSignature,
                    message = "AI 分析失败：${t.message}"))
            }
        }
        return true
    }

    fun analyzeCurrent(): Boolean {
        if (job?.isActive == true) return false
        val currentRound = _state.value.round.coerceAtLeast(1)
        val project = workspace.state.value.projectPath?.let(::File) ?: return false
        return analyzeInternal(project, currentRound)
    }

    fun updateAccepted(relativePath: String, accepted: Boolean) {
        val current = _state.value.proposal ?: return
        val changes = current.changes.map {
            if (it.relativePath == relativePath) it.copy(status = if (accepted) FileChange.Status.ACCEPTED else FileChange.Status.REJECTED) else it
        }
        publish(_state.value.copy(proposal = current.copy(changes = changes), message = "已更新审查选择"))
    }

    fun acceptAll() {
        val current = _state.value.proposal ?: return
        publish(_state.value.copy(proposal = current.copy(changes = current.changes.map {
            it.copy(status = FileChange.Status.ACCEPTED)
        })))
    }

    fun rejectAll() {
        val current = _state.value.proposal ?: return
        publish(_state.value.copy(proposal = current.copy(changes = current.changes.map {
            it.copy(status = FileChange.Status.REJECTED)
        })))
    }

    fun applyAndRebuild(): Boolean {
        if (job?.isActive == true || _state.value.phase != Phase.REVIEW) return false
        val project = _state.value.projectPath?.let(::File) ?: return false
        val proposal = _state.value.proposal ?: return false
        val accepted = proposal.changes.count { it.status == FileChange.Status.ACCEPTED }
        val currentRound = _state.value.round.coerceAtLeast(1)
        if (currentRound > _state.value.maxRounds) return false
        if (accepted == 0) {
            transaction?.rollback()
            transaction = null
            publish(State(Phase.IDLE, project.absolutePath, message = "没有接受任何文件修改"))
            return false
        }
        job = scope.launch {
            emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Running("提交已审查 AI 修改"))
            publish(_state.value.copy(phase = Phase.APPLYING, message = "正在提交 $accepted 个已审查文件…"))
            try {
                val agent = BuildFixAgent(OpenAiCompatibleCompletionClient(settingsStore.load()), bus = bus)
                val effectiveChanges = proposal.changes.map { change ->
                    if (change.status != FileChange.Status.ACCEPTED) return@map change
                    val original = File(project, change.relativePath).takeIf { it.isFile }?.readText().orEmpty()
                    val hunks = DiffReviewEngine.diff(original, change.proposedContent)
                    val statuses = _state.value.hunkStatuses[change.relativePath].orEmpty()
                    val effective = DiffReviewEngine.apply(original, change.proposedContent, hunks.map { h -> h.copy(status = statuses[h.index] ?: DiffReviewEngine.HunkStatus.PENDING) })
                    change.copy(proposedContent = effective)
                }
                val effectiveProposal = proposal.copy(changes = effectiveChanges)
                transaction?.rollback()
                transaction = agent.stage(effectiveProposal, project)
                agent.commitAccepted(effectiveProposal, transaction ?: error("事务不存在"))
                transaction = null
                val spec = runConfigurations.value.firstOrNull {
                    File(it.projectPath).canonicalPath == project.canonicalPath
                } ?: throw IllegalStateException("没有找到当前项目的运行配置")
                publish(_state.value.copy(phase = Phase.REBUILDING, message = "第 $currentRound 轮修改已写回，正在重新构建…"))
                val buildEvents = mutableListOf<com.nebulaforge.core.session.IdeEvent>()
                unifiedRunController.build(spec).collect { event ->
                    buildEvents += event
                    if (event is com.nebulaforge.core.session.IdeEvent.SessionRegistered && event.kind == com.nebulaforge.core.session.SessionKind.BUILD) {
                        bus.emit(com.nebulaforge.core.session.IdeEvent.Relation(proposal.sessionId, event.sessionId, "rebuild"))
                        bus.emit(com.nebulaforge.core.session.IdeEvent.Relation(event.sessionId, proposal.sessionId, "build_fix"))
                    }
                }
                val rebuildSessionId = buildEvents.filterIsInstance<com.nebulaforge.core.session.IdeEvent.SessionRegistered>()
                    .lastOrNull { it.kind == com.nebulaforge.core.session.SessionKind.BUILD }?.sessionId
                val remainingDiagnostics = rebuildSessionId?.let { diagnostics.errorForSession(it) }.orEmpty()
                val remaining = remainingDiagnostics.size
                val newSignature = signatureFor(remainingDiagnostics)
                val previousSignature = _state.value.diagnosticSignature
                val noProgress = remaining > 0 && previousSignature != null && newSignature == previousSignature
                val outcome = when {
                    remaining == 0 -> "RESOLVED"
                    noProgress -> "NO_PROGRESS"
                    currentRound >= _state.value.maxRounds -> "MAX_ROUNDS"
                    else -> "RETRY"
                }
                val history = _state.value.history + RoundRecord(currentRound, _state.value.sourceBuildSessionId, proposal.sessionId, rebuildSessionId, remaining, outcome, newSignature)
                if (remaining == 0) {
                    emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Succeeded("第 $currentRound 轮重新构建通过，问题已解决"))
                    publish(_state.value.copy(phase = Phase.RESOLVED, errorCount = 0, rebuildSessionId = rebuildSessionId, history = history, message = "第 $currentRound 轮重新构建通过，问题已解决"))
                } else if (noProgress) {
                    emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Failed("重新构建后错误签名未变化，停止自动修复以避免循环"))
                    publish(_state.value.copy(phase = Phase.FAILED, errorCount = remaining, rebuildSessionId = rebuildSessionId,
                        history = history, diagnosticSignature = newSignature, message = "第 $currentRound 轮重新构建后错误没有变化，已停止自动修复"))
                } else if (currentRound >= _state.value.maxRounds) {
                    emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Failed("达到最大修复轮次 ${_state.value.maxRounds}，仍有 $remaining 个错误"))
                    publish(_state.value.copy(phase = Phase.FAILED, errorCount = remaining, rebuildSessionId = rebuildSessionId, history = history,
                        diagnosticSignature = newSignature, message = "达到最大修复轮次 ${_state.value.maxRounds}，仍有 $remaining 个错误"))
                } else {
                    emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Succeeded("第 $currentRound 轮已完成，进入下一轮分析"))
                    val nextRound = currentRound + 1
                    transaction = null
                    publish(_state.value.copy(phase = Phase.ANALYZING, proposal = null, hunkStatuses = emptyMap(), errorCount = remaining, round = nextRound,
                        sourceBuildSessionId = rebuildSessionId, rebuildSessionId = rebuildSessionId, history = history,
                        diagnosticSignature = newSignature, message = "第 $currentRound 轮后仍有 $remaining 个错误，正在自动分析第 $nextRound 轮…"))
                    job = null
                    analyzeInternal(project, nextRound)
                }
            } catch (t: Throwable) {
                transaction?.rollback()
                transaction = null
                emitSessionState(proposal, project.absolutePath, com.nebulaforge.core.session.SessionState.Failed("提交或重新构建失败：${t.message}"))
                publish(_state.value.copy(phase = Phase.FAILED, message = "提交或重新构建失败：${t.message}"))
            }
        }
        return true
    }

    fun cancel() {
        job?.cancel()
        job = null
        val current = _state.value
        current.proposal?.let { proposal ->
            current.projectPath?.let { emitSessionState(proposal, it, com.nebulaforge.core.session.SessionState.Cancelled) }
        }
        transaction?.rollback()
        transaction = null
        publish(State())
    }
}
