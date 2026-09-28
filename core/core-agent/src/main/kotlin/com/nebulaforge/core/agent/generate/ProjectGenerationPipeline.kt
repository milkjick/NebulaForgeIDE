package com.nebulaforge.core.agent.generate

import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.ProjectType
import java.io.File

/**
 * AI 全项目生成流水线的状态机（开发方案第 6.1 节）。
 *
 * 每个状态对应一个明确的输入/输出契约，支持暂停、重试、取消，
 * 且整个流水线的中间产物可持久化（应用被杀死后可恢复）。
 *
 * 说明：本文件按方案 6.1 的定义逐条实现，未做裁剪。状态机的推进逻辑见
 * [ProjectGenerationOrchestrator]；文件系统事务见 [GenerationTransaction]。
 */
sealed class PipelineState {

    /** 阶段1：需求解析。输入：用户自然语言。输出：结构化需求清单 */
    data class RequirementParsing(val userPrompt: String) : PipelineState()

    /** 阶段2：技术栈推断。输入：结构化需求。输出：推荐的 ProjectType + 依赖建议 */
    data class TechStackInference(val requirements: RequirementSpec) : PipelineState()

    /** 阶段3：方案确认。输入：技术栈推断结果。输出：用户确认/修改后的最终方案（人工介入点） */
    data class PlanConfirmation(val proposedPlan: ProjectPlan) : PipelineState()

    /** 阶段4：项目骨架生成。输入：确认后的方案。输出：调用模板引擎产出的目录结构 */
    data class ScaffoldGeneration(val plan: ProjectPlan) : PipelineState()

    /** 阶段5：依赖解析写入。输入：骨架 + 需求中提到的库。输出：更新后的 build.gradle/pubspec.yaml/package.json */
    data class DependencyResolution(val scaffold: ScaffoldResult) : PipelineState()

    /** 阶段6：逐文件代码生成。输入：需求清单中未覆盖的具体文件列表。输出：文件内容（增量产出，支持中途暂停续写） */
    data class CodeGeneration(
        val fileQueue: List<PendingFile>,
        val completed: List<GeneratedFile>
    ) : PipelineState()

    /** 阶段7：构建验证循环。输入：已生成的完整项目。输出：构建成功 或 结构化错误列表（触发自动修复） */
    data class BuildVerification(
        val attemptCount: Int,
        val lastErrors: List<BuildError>
    ) : PipelineState()

    /** 阶段8：运行验证。输入：构建产物。输出：运行日志/崩溃堆栈（若有） */
    data class RunVerification(val crashLog: String?) : PipelineState()

    /** 阶段9：交付审查。输入：全部变更。输出：用户逐文件 accept/reject 后的最终产物 */
    data class DeliveryReview(val changeSummary: List<FileChange>) : PipelineState()

    data class Failed(val stage: String, val reason: String) : PipelineState()
    object Completed : PipelineState()

    /** 供持久化与 UI 展示使用的阶段名（非本地化标识，日志/DB 用英文原文） */
    val stageName: String
        get() = when (this) {
            is RequirementParsing -> "RequirementParsing"
            is TechStackInference -> "TechStackInference"
            is PlanConfirmation -> "PlanConfirmation"
            is ScaffoldGeneration -> "ScaffoldGeneration"
            is DependencyResolution -> "DependencyResolution"
            is CodeGeneration -> "CodeGeneration"
            is BuildVerification -> "BuildVerification"
            is RunVerification -> "RunVerification"
            is DeliveryReview -> "DeliveryReview"
            is Failed -> "Failed"
            is Completed -> "Completed"
        }

    /** 需要人工介入的阶段：编排器在此挂起，等待外部调用 confirmPlan()/finalizeDelivery() */
    val awaitsUserInput: Boolean
        get() = this is PlanConfirmation || this is DeliveryReview

    /** 是否终态 */
    val isTerminal: Boolean
        get() = this is Completed || this is Failed
}

data class RequirementSpec(
    val features: List<String>,
    val suggestedTechStack: String?,
    val constraints: List<String> // 用户明确提出的约束，如"用 Compose + Room"
)

data class ProjectPlan(
    val projectType: ProjectType,
    val templateId: String,
    val dependencies: List<String>,
    val moduleBreakdown: List<ModulePlan>
)

data class ModulePlan(val name: String, val description: String, val files: List<String>)

data class PendingFile(val path: String, val purpose: String, val dependsOn: List<String>)
data class GeneratedFile(val path: String, val content: String)

/**
 * 阶段4 的产出：骨架生成的落盘结果。
 * [stagingDir] 指向临时工作区（.nebulaforge/tmp/<sessionId>/），不指向真实项目目录。
 */
data class ScaffoldResult(
    val stagingDir: File,
    val packageName: String,
    val files: List<String>
)

data class FileChange(
    val path: String,
    val diffSummary: String,
    val status: ChangeStatus
) {
    enum class ChangeStatus { PENDING, ACCEPTED, REJECTED }
}
