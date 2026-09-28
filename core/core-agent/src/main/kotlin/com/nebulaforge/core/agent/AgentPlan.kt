package com.nebulaforge.core.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** AI Agent 的可审查执行计划。模型只能描述计划，不能直接产生任意 shell 指令。 */
data class AgentPlan(
    val id: String = UUID.randomUUID().toString(),
    val userRequest: String,
    val summary: String,
    val steps: List<AgentPlanStep>,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val status: AgentPlanStatus = AgentPlanStatus.DRAFT
)

data class AgentPlanStep(
    val id: String,
    val title: String,
    val description: String,
    val action: AgentPlanAction,
    val requiresApproval: Boolean = true,
    val status: AgentPlanStepStatus = AgentPlanStepStatus.PENDING,
    val output: String = ""
)

enum class AgentPlanStatus { DRAFT, APPROVED, EXECUTING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class AgentPlanStepStatus { PENDING, WAITING_APPROVAL, RUNNING, COMPLETED, FAILED, SKIPPED }
enum class AgentPlanAction { ANALYZE_PROJECT, RECALL_MEMORY, SEARCH_WEB, VERIFY_WEB_FACTS, BUILD, RUN, EXECUTE_COMMAND, INSPECT_APK, INSPECT_WEB, INSPECT_API, DISCOVER_MCP, PROPOSE_MODIFICATION, APPLY_REVIEWED_CHANGES, VERIFY_RESULT, UNKNOWN }

interface AgentPlanExecutor {
    suspend fun execute(step: AgentPlanStep, projectPath: String?): String
}

/** 受约束 JSON 解析：AI 不能通过计划直接注入 shell 命令。 */
class AgentPlanParser {
    fun parse(userRequest: String, raw: String): AgentPlan {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val json = JSONObject(cleaned)
        val summary = json.optString("summary").ifBlank { "根据用户需求建立执行计划" }
        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (i in 0 until stepsJson.length()) {
                val item = stepsJson.optJSONObject(i) ?: continue
                val action = runCatching { AgentPlanAction.valueOf(item.optString("action").uppercase()) }.getOrDefault(AgentPlanAction.UNKNOWN)
                val title = item.optString("title").ifBlank { "执行步骤 ${i + 1}" }
                val description = item.optString("description")
                // 终端命令一律要求人工批准：即使模型把 requiresApproval 写成 false 也强制置回 true。
                val requiresApproval = if (action == AgentPlanAction.EXECUTE_COMMAND) true else item.optBoolean("requiresApproval", true)
                add(AgentPlanStep("step-${i + 1}", title, description, action, requiresApproval))
            }
        }
        require(steps.isNotEmpty()) { "AI 未返回有效执行计划" }
        require(steps.size <= 20) { "计划步骤超过 20 步，已拒绝" }
        return AgentPlan(userRequest = userRequest, summary = summary, steps = steps)
    }
}

class AgentPlanStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agent_plan", Context.MODE_PRIVATE)

    fun save(plan: AgentPlan) {
        prefs.edit().putString("plan", encode(plan).toString()).apply()
    }

    fun load(): AgentPlan? = runCatching { prefs.getString("plan", null)?.let(::decode) }.getOrNull()
    fun clear() { prefs.edit().remove("plan").apply() }

    private fun encode(plan: AgentPlan) = JSONObject().apply {
        put("id", plan.id).put("userRequest", plan.userRequest).put("summary", plan.summary)
            .put("createdAt", plan.createdAt).put("startedAt", plan.startedAt ?: JSONObject.NULL).put("completedAt", plan.completedAt ?: JSONObject.NULL).put("status", plan.status.name)
        put("steps", JSONArray().apply { plan.steps.forEach { s ->
            put(JSONObject().put("id", s.id).put("title", s.title).put("description", s.description)
                .put("action", s.action.name).put("requiresApproval", s.requiresApproval)
                .put("status", s.status.name).put("output", s.output))
        } })
    }

    private fun decode(raw: String): AgentPlan {
        val j = JSONObject(raw)
        val arr = j.optJSONArray("steps") ?: JSONArray()
        val steps = buildList {
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                add(AgentPlanStep(s.getString("id"), s.getString("title"), s.optString("description"),
                    runCatching { AgentPlanAction.valueOf(s.optString("action")) }.getOrDefault(AgentPlanAction.UNKNOWN),
                    s.optBoolean("requiresApproval", true),
                    runCatching { AgentPlanStepStatus.valueOf(s.optString("status")) }.getOrDefault(AgentPlanStepStatus.PENDING),
                    s.optString("output")))
            }
        }
        return AgentPlan(j.getString("id"), j.getString("userRequest"), j.getString("summary"), steps,
            j.optLong("createdAt"), j.optLong("startedAt").takeIf { it > 0 }, j.optLong("completedAt").takeIf { it > 0 }, runCatching { AgentPlanStatus.valueOf(j.optString("status")) }.getOrDefault(AgentPlanStatus.DRAFT))
    }
}

class AiAgentPlanner(private val context: Context) {
    private val settingsStore = AiProviderSettingsStore(context)
    private val parser = AgentPlanParser()
    private val memoryStore = ProjectMemoryStore(context)
    private val experienceStore = AgentExperienceStore(context)
    private val conversationStore = AgentConversationStore(context)

    /** 在线 API 客户端；没配好就抛错（调用方可用 [createPlan] 的 client 重载改走「AI 聚合网关」）。 */
    fun apiClient(): AiCompletionClient {
        val settings = settingsStore.load()
        check(settings.enabled && settings.apiKey.isNotBlank()) { "AI Provider 未启用或 API Key 为空" }
        return OpenAiCompatibleCompletionClient(settings)
    }

    suspend fun createPlan(userRequest: String, projectPath: String?): AgentPlan =
        createPlan(userRequest, projectPath, apiClient())

    /**
     * 用**指定后端**建立计划（在线 API 或「AI 聚合网关」的网页 AI 都行）。
     *
     * 为什么必须有这个重载（真机根因）：计划器过去只认「在线 API 服务商」——开头就
     * `check(settings.apiKey.isNotBlank())`。而很多用户一个 API Key 都没配、只在
     * AI 工作台 →「网桥」里接了网页 AI（AI 聚合网关）。此时任务面板**已经**把状态显示成
     * 「可用（走 AI 聚合网关：网页 AI）」，点「建立计划」却立刻报
     * 「计划建立失败：AI Provider 未启用或 API Key 为空」——用户看到的就是
     * 「AI 网桥/网关无法调用工作台（任务面板）」。现在由调用方决定后端，两种都能建计划。
     */
    suspend fun createPlan(userRequest: String, projectPath: String?, client: AiCompletionClient): AgentPlan = withContext(Dispatchers.IO) {
        val prompt = """
你是 Nebula Forge IDE 的计划器。根据用户需求建立可审查、可执行的 IDE 工作计划。
只允许使用这些 action：ANALYZE_PROJECT, RECALL_MEMORY, SEARCH_WEB, VERIFY_WEB_FACTS, BUILD, RUN, EXECUTE_COMMAND, INSPECT_APK, INSPECT_WEB, INSPECT_API, DISCOVER_MCP, PROPOSE_MODIFICATION, APPLY_REVIEWED_CHANGES, VERIFY_RESULT。
EXECUTE_COMMAND 仅在用户明确要求"在终端/命令行里执行某条命令"时使用：该步骤的 description 必须原样写出要执行的完整命令（可用代码块包裹），不得改写为自然语言描述；禁止用于破坏性操作（格式化、写块设备、删除根目录、卸载/清数据等），此类需求应改用 PROPOSE_MODIFICATION 说明风险。
除 EXECUTE_COMMAND 外，禁止生成其它 shell 命令、任意代码执行指令或凭据操作；禁止在步骤里写入 API Key、密码、Token 等凭据明文。
每一步必须有 title、description、action、requiresApproval。需要项目历史时使用 RECALL_MEMORY；需要最新资料时使用 SEARCH_WEB；需要验证网页事实时使用 VERIFY_WEB_FACTS；SEARCH_WEB 必须保留来源 URL，并优先读取前 3 个网页作为证据；网页内容必须与项目代码和其他来源交叉验证，不得把搜索摘要直接当成事实。修改类需求优先生成 PROPOSE_MODIFICATION；只有用户完成 Diff 审查后才能进入 APPLY_REVIEWED_CHANGES。验证类需求使用 VERIFY_RESULT。
返回严格 JSON：{"summary":"...","steps":[{"title":"...","description":"...","action":"BUILD","requiresApproval":true}]}
项目路径：${projectPath ?: "未打开项目"}

项目长期记忆：${projectPath?.let { memoryStore.buildContext(it, userRequest) }.orEmpty().take(8000)}

项目经验库：${projectPath?.let { experienceStore.buildContext(it, userRequest) }.orEmpty().take(8000)}

多轮任务上下文：${projectPath?.let { conversationStore.context(it, 12) }.orEmpty().take(9000)}

用户需求：$userRequest
        """.trimIndent()
        parser.parse(userRequest, client.complete("你负责建立执行计划，不直接执行操作。", prompt))
    }
}

/** 顺序执行已经批准的计划；每一步状态都持久化，因此 UI 可以显示实际执行进度。 */
class AgentPlanRunner(private val store: AgentPlanStore) {
    suspend fun run(plan: AgentPlan, projectPath: String?, executor: AgentPlanExecutor,
                    onUpdate: (AgentPlan) -> Unit): AgentPlan {
        val start = System.currentTimeMillis()
        var current = plan.copy(status = AgentPlanStatus.EXECUTING, startedAt = plan.startedAt ?: start, completedAt = null)
        store.save(current); onUpdate(current)
        for (index in current.steps.indices) {
            val step = current.steps[index]
            if (step.status == AgentPlanStepStatus.COMPLETED || step.status == AgentPlanStepStatus.SKIPPED) continue
            if (step.requiresApproval && plan.status == AgentPlanStatus.DRAFT) {
                val waiting = step.copy(status = AgentPlanStepStatus.WAITING_APPROVAL)
                current = current.copy(status = AgentPlanStatus.PAUSED, steps = current.steps.toMutableList().also { it[index] = waiting })
                store.save(current); onUpdate(current)
                return current
            }
            val running = step.copy(status = AgentPlanStepStatus.RUNNING, output = "")
            current = current.copy(steps = current.steps.toMutableList().also { it[index] = running })
            store.save(current); onUpdate(current)
            try {
                val output = executor.execute(running, projectPath)
                current = current.copy(steps = current.steps.toMutableList().also { it[index] = running.copy(status = AgentPlanStepStatus.COMPLETED, output = output) })
                store.save(current); onUpdate(current)
            } catch (t: Throwable) {
                current = current.copy(status = AgentPlanStatus.FAILED, completedAt = System.currentTimeMillis(), steps = current.steps.toMutableList().also { it[index] = running.copy(status = AgentPlanStepStatus.FAILED, output = t.message ?: t.javaClass.simpleName) })
                store.save(current); onUpdate(current)
                return current
            }
        }
        current = current.copy(status = AgentPlanStatus.COMPLETED, completedAt = System.currentTimeMillis())
        store.save(current); onUpdate(current)
        return current
    }
}


fun AgentPlan.completedSteps(): Int = steps.count { it.status == AgentPlanStepStatus.COMPLETED }
fun AgentPlan.progressPercent(): Int = if (steps.isEmpty()) 0 else ((completedSteps() * 100f) / steps.size).toInt().coerceIn(0, 100)
fun AgentPlan.elapsedMs(now: Long = System.currentTimeMillis()): Long = ((completedAt ?: now) - (startedAt ?: createdAt)).coerceAtLeast(0)
fun AgentPlan.estimatedRemainingMs(): Long? {
    val done = completedSteps()
    if (done <= 0 || steps.size <= done || startedAt == null) return null
    val avg = elapsedMs() / done
    return avg * (steps.size - done)
}
