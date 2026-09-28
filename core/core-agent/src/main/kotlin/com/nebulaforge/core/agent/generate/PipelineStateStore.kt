package com.nebulaforge.core.agent.generate

import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.ProjectTypeRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 流水线状态持久化层（开发方案第 6.2 节）。
 *
 * 契约与方案一致：编排器每个阶段转换都会 save 一次，`run()` 启动时 load 恢复，
 * 保证进程被杀死后可从最后一个完成的阶段继续，而不是从头重来。
 *
 * 实现说明：方案原文建议基于 Room。本仓库的离线依赖仓库未内置 androidx.room，
 * 为不引入不可编译的依赖，这里提供一个等价语义的 JSON 文件实现
 * （[JsonPipelineStateStore]）与内存实现（[InMemoryPipelineStateStore]）。
 * 后续若接入 Room，只需替换 [PipelineStateStore] 的实现，编排器无需改动。
 */
interface PipelineStateStore {
    fun load(sessionId: String): PipelineState?
    fun save(sessionId: String, state: PipelineState)
    fun clear(sessionId: String)
}

/** 单进程内存实现：用于单元测试或不关心恢复能力的场景 */
class InMemoryPipelineStateStore : PipelineStateStore {
    private val states = LinkedHashMap<String, PipelineState>()

    @Synchronized
    override fun load(sessionId: String): PipelineState? = states[sessionId]

    @Synchronized
    override fun save(sessionId: String, state: PipelineState) {
        states[sessionId] = state
    }

    @Synchronized
    override fun clear(sessionId: String) {
        states.remove(sessionId)
    }
}

/**
 * JSON 文件实现：每个会话一个 `<root>/<sessionId>.json`，原子写（先写临时文件再重命名）。
 * 进程被杀死后重新 load 即可从最后一个完成的阶段恢复。
 */
class JsonPipelineStateStore(private val root: File) : PipelineStateStore {

    @Synchronized
    override fun load(sessionId: String): PipelineState? {
        val file = stateFile(sessionId)
        if (!file.isFile) return null
        return runCatching { PipelineStateCodec.decode(JSONObject(file.readText())) }.getOrNull()
    }

    @Synchronized
    override fun save(sessionId: String, state: PipelineState) {
        root.mkdirs()
        val file = stateFile(sessionId)
        val tmp = File(root, "$sessionId.json.tmp")
        tmp.writeText(PipelineStateCodec.encode(state).toString())
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    @Synchronized
    override fun clear(sessionId: String) {
        stateFile(sessionId).delete()
    }

    private fun stateFile(sessionId: String): File =
        File(root, sessionId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}

/** PipelineState ⟷ JSON 编解码。ProjectType 以 id 形式落盘，反序列化时经 [ProjectTypeRegistry] 还原。 */
object PipelineStateCodec {

    fun encode(state: PipelineState): JSONObject = JSONObject().apply {
        put("stage", state.stageName)
        when (state) {
            is PipelineState.RequirementParsing ->
                put("userPrompt", state.userPrompt)

            is PipelineState.TechStackInference ->
                put("requirements", encodeRequirements(state.requirements))

            is PipelineState.PlanConfirmation ->
                put("plan", encodePlan(state.proposedPlan))

            is PipelineState.ScaffoldGeneration ->
                put("plan", encodePlan(state.plan))

            is PipelineState.DependencyResolution ->
                put("scaffold", JSONObject().apply {
                    put("stagingDir", state.scaffold.stagingDir.absolutePath)
                    put("packageName", state.scaffold.packageName)
                    put("files", JSONArray(state.scaffold.files))
                })

            is PipelineState.CodeGeneration -> {
                put("fileQueue", JSONArray(state.fileQueue.map { pending ->
                    JSONObject().apply {
                        put("path", pending.path)
                        put("purpose", pending.purpose)
                        put("dependsOn", JSONArray(pending.dependsOn))
                    }
                }))
                put("completed", JSONArray(state.completed.map { generated ->
                    JSONObject().apply {
                        put("path", generated.path)
                        put("content", generated.content)
                    }
                }))
            }

            is PipelineState.BuildVerification -> {
                put("attemptCount", state.attemptCount)
                put("lastErrors", JSONArray(state.lastErrors.map { error ->
                    JSONObject().apply {
                        put("filePath", error.filePath ?: JSONObject.NULL)
                        put("line", error.line ?: JSONObject.NULL)
                        put("column", error.column ?: JSONObject.NULL)
                        put("message", error.message)
                        put("severity", error.severity.name)
                    }
                }))
            }

            is PipelineState.RunVerification ->
                put("crashLog", state.crashLog ?: JSONObject.NULL)

            is PipelineState.DeliveryReview ->
                put("changeSummary", JSONArray(state.changeSummary.map { change ->
                    JSONObject().apply {
                        put("path", change.path)
                        put("diffSummary", change.diffSummary)
                        put("status", change.status.name)
                    }
                }))

            is PipelineState.Failed -> {
                put("failedStage", state.stage)
                put("reason", state.reason)
            }

            is PipelineState.Completed -> Unit
        }
    }

    fun decode(json: JSONObject): PipelineState = when (val stage = json.optString("stage")) {
        "RequirementParsing" ->
            PipelineState.RequirementParsing(json.optString("userPrompt"))

        "TechStackInference" ->
            PipelineState.TechStackInference(decodeRequirements(json.optJSONObject("requirements") ?: JSONObject()))

        "PlanConfirmation" ->
            PipelineState.PlanConfirmation(decodePlan(json.optJSONObject("plan") ?: JSONObject()))

        "ScaffoldGeneration" ->
            PipelineState.ScaffoldGeneration(decodePlan(json.optJSONObject("plan") ?: JSONObject()))

        "DependencyResolution" -> {
            val scaffold = json.optJSONObject("scaffold") ?: JSONObject()
            PipelineState.DependencyResolution(
                ScaffoldResult(
                    stagingDir = File(scaffold.optString("stagingDir")),
                    packageName = scaffold.optString("packageName"),
                    files = scaffold.optJSONArray("files").toStringList()
                )
            )
        }

        "CodeGeneration" -> PipelineState.CodeGeneration(
            fileQueue = json.optJSONArray("fileQueue").toObjectList { obj ->
                PendingFile(
                    path = obj.optString("path"),
                    purpose = obj.optString("purpose"),
                    dependsOn = obj.optJSONArray("dependsOn").toStringList()
                )
            },
            completed = json.optJSONArray("completed").toObjectList { obj ->
                GeneratedFile(obj.optString("path"), obj.optString("content"))
            }
        )

        "BuildVerification" -> PipelineState.BuildVerification(
            attemptCount = json.optInt("attemptCount"),
            lastErrors = json.optJSONArray("lastErrors").toObjectList { obj ->
                BuildError(
                    filePath = obj.optString("filePath").takeIf { it.isNotBlank() && it != "null" },
                    line = obj.optIntOrNull("line"),
                    column = obj.optIntOrNull("column"),
                    message = obj.optString("message"),
                    severity = runCatching {
                        BuildError.Severity.valueOf(obj.optString("severity"))
                    }.getOrDefault(BuildError.Severity.ERROR)
                )
            }
        )

        "RunVerification" ->
            PipelineState.RunVerification(json.optString("crashLog").takeIf { it.isNotBlank() && it != "null" })

        "DeliveryReview" -> PipelineState.DeliveryReview(
            json.optJSONArray("changeSummary").toObjectList { obj ->
                FileChange(
                    path = obj.optString("path"),
                    diffSummary = obj.optString("diffSummary"),
                    status = runCatching {
                        FileChange.ChangeStatus.valueOf(obj.optString("status"))
                    }.getOrDefault(FileChange.ChangeStatus.PENDING)
                )
            }
        )

        "Failed" -> PipelineState.Failed(json.optString("failedStage"), json.optString("reason"))
        "Completed" -> PipelineState.Completed
        else -> error("未知的流水线阶段：$stage")
    }

    private fun encodeRequirements(spec: RequirementSpec) = JSONObject().apply {
        put("features", JSONArray(spec.features))
        put("suggestedTechStack", spec.suggestedTechStack ?: JSONObject.NULL)
        put("constraints", JSONArray(spec.constraints))
    }

    private fun decodeRequirements(json: JSONObject) = RequirementSpec(
        features = json.optJSONArray("features").toStringList(),
        suggestedTechStack = json.optString("suggestedTechStack").takeIf { it.isNotBlank() && it != "null" },
        constraints = json.optJSONArray("constraints").toStringList()
    )

    private fun encodePlan(plan: ProjectPlan) = JSONObject().apply {
        put("projectTypeId", plan.projectType.id)
        put("templateId", plan.templateId)
        put("dependencies", JSONArray(plan.dependencies))
        put("moduleBreakdown", JSONArray(plan.moduleBreakdown.map { module ->
            JSONObject().apply {
                put("name", module.name)
                put("description", module.description)
                put("files", JSONArray(module.files))
            }
        }))
    }

    private fun decodePlan(json: JSONObject): ProjectPlan {
        val typeId = json.optString("projectTypeId")
        val projectType = ProjectTypeRegistry.getById(typeId)
            ?: error("无法还原技术栈类型：$typeId（未注册的 ProjectType）")
        return ProjectPlan(
            projectType = projectType,
            templateId = json.optString("templateId"),
            dependencies = json.optJSONArray("dependencies").toStringList(),
            moduleBreakdown = json.optJSONArray("moduleBreakdown").toObjectList { obj ->
                ModulePlan(
                    name = obj.optString("name"),
                    description = obj.optString("description"),
                    files = obj.optJSONArray("files").toStringList()
                )
            }
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }
    }

    private fun <T> JSONArray?.toObjectList(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.let(mapper) }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null
}
