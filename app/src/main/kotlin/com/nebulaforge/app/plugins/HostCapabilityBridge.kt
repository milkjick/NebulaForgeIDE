package com.nebulaforge.app.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** 能力风险等级：决定默认授权策略（只读放行，其余需要用户点头）。 */
enum class CapRisk(val label: String) {
    READ("只读"),
    WRITE("写入"),
    EXEC("执行"),
    MODEL("模型调用"),
    UI("界面交互")
}

/** 授权决定。[ASK] 表示每次都要问用户。 */
enum class GrantDecision { ALLOW, ASK, DENY }

/** 一条可供插件 / AI Agent / 网关调用的宿主能力。 */
data class CapabilityDef(
    val id: String,
    val title: String,
    val risk: CapRisk,
    val detail: String,
    /** 参数契约（给人看的 JSON 骨架），插件和 AI 都据此构造入参。 */
    val arguments: String
)

/** 一次能力调用的审计记录。 */
data class CapabilityCall(
    val at: Long,
    val caller: String,
    val capability: String,
    val decision: String,
    val ok: Boolean,
    val argsPreview: String,
    val detail: String
)

/** 调用结果。[ok]=false 时 [text] 是给调用方看的失败原因（而不是静默空串）。 */
data class CapResult(val ok: Boolean, val text: String) {
    fun toJson(): JSONObject = JSONObject().put("ok", ok).put("text", text)
}

/**
 * 受授权、可审计的宿主能力网关。
 *
 * 为什么要有这一层：插件、IDE 内的 AI Agent、外部 AI 网关都需要「调用软件功能」，
 * 但如果各自直接摸内部对象，就会同时丢掉三件东西 —— 权限、审计、契约。
 * 这里把它们收成一条通道：
 *
 *  - **统一入口**：[invoke] 是所有调用（插件命令 / 文件读写 / 终端 / 模型网关 / 界面交互）的唯一门；
 *  - **显式授权**：只读默认放行，写入 / 执行 / 模型调用默认 [GrantDecision.ASK]，
 *    由界面弹窗确认，用户答「记住」后落盘（`state/capability-grants.json`）；
 *  - **可审计**：无论放行、拒绝还是失败，都写一条审计（内存最近 200 条 + `state/capability-audit.log` 追加），
 *    所以"AI 到底动了什么"是可追溯的，而不是靠猜。
 */
class HostCapabilityBridge(
    private val grantsFile: File,
    private val auditFile: File,
    private val auditSink: (String, String) -> Unit
) {

    private val handlers = LinkedHashMap<String, suspend (JSONObject) -> CapResult>()
    private val defs = LinkedHashMap<String, CapabilityDef>()

    private val _capabilities = MutableStateFlow<List<CapabilityDef>>(emptyList())
    val capabilities: StateFlow<List<CapabilityDef>> = _capabilities.asStateFlow()

    private val _audit = MutableStateFlow<List<CapabilityCall>>(emptyList())
    val audit: StateFlow<List<CapabilityCall>> = _audit.asStateFlow()

    private val _grants = MutableStateFlow<Map<String, GrantDecision>>(emptyMap())
    val grants: StateFlow<Map<String, GrantDecision>> = _grants.asStateFlow()

    init {
        loadGrants()
    }

    /** 注册一条能力。id 采用 `域.动作` 形式（如 `workspace.writeFile`），便于 AI 侧做白名单。 */
    fun register(def: CapabilityDef, handler: suspend (JSONObject) -> CapResult) {
        synchronized(handlers) {
            defs[def.id] = def
            handlers[def.id] = handler
            _capabilities.value = defs.values.toList()
        }
    }

    fun capability(id: String): CapabilityDef? = defs[id]

    /** 某调用方对某能力的当前策略；未配置时按风险给默认值。 */
    fun decisionFor(caller: String, capability: String): GrantDecision {
        _grants.value[key(caller, capability)]?.let { return it }
        val risk = defs[capability]?.risk ?: return GrantDecision.ASK
        return when (risk) {
            CapRisk.READ -> GrantDecision.ALLOW
            else -> GrantDecision.ASK
        }
    }

    /** 用户点「始终允许 / 始终拒绝」后的落盘；[GrantDecision.ASK] 表示恢复逐次询问。 */
    fun setGrant(caller: String, capability: String, decision: GrantDecision) {
        val next = _grants.value.toMutableMap()
        next[key(caller, capability)] = decision
        _grants.value = next
        runCatching {
            grantsFile.parentFile?.mkdirs()
            val obj = JSONObject()
            next.forEach { (k, v) -> obj.put(k, v.name) }
            grantsFile.writeText(obj.toString())
        }
    }

    fun clearGrants() {
        _grants.value = emptyMap()
        runCatching { grantsFile.delete() }
    }

    /**
     * 调用入口。
     *
     * [ask] 只在策略为 [GrantDecision.ASK] 时被调用，返回 true 表示用户当场同意；
     * 抛出的异常一律转换成失败结果并记审计 —— 调用方（插件 / AI）永远拿到可读原因，
     * 不会因为宿主内部异常而"看起来啥也没发生"。
     */
    suspend fun invoke(
        caller: String,
        capability: String,
        args: JSONObject,
        argsPreviewLimit: Int = 160,
        ask: suspend (CapabilityDef, String) -> Boolean
    ): CapResult {
        val def = defs[capability]
            ?: return record(caller, capability, "未知能力", false, args, "宿主没有注册能力 $capability")
        var decision = decisionFor(caller, capability)
        if (decision == GrantDecision.ASK) {
            val allow = runCatching { ask(def, args.toString()) }.getOrDefault(false)
            decision = if (allow) GrantDecision.ALLOW else GrantDecision.DENY
        }
        if (decision == GrantDecision.DENY) {
            return record(caller, capability, "拒绝", false, args, "${def.title}：用户拒绝（$caller）")
        }
        val handler = synchronized(handlers) { handlers[capability] }
            ?: return record(caller, capability, "无实现", false, args, "能力 $capability 仅声明、无实现")
        return try {
            val r = handler(args)
            record(caller, capability, decision.name, r.ok, args, r.text, argsPreviewLimit)
        } catch (t: Throwable) {
            record(
                caller, capability, decision.name, false, args,
                "调用失败：${t.javaClass.simpleName}: ${t.message ?: ""}".trim(), argsPreviewLimit
            )
        }
    }

    private fun record(
        caller: String,
        capability: String,
        decision: String,
        ok: Boolean,
        args: JSONObject,
        detail: String,
        limit: Int = 160
    ): CapResult {
        val preview = args.toString().let { if (it.length > limit) it.take(limit) + "…" else it }
        val call = CapabilityCall(System.currentTimeMillis(), caller, capability, decision, ok, preview, detail.take(400))
        synchronized(handlers) {
            _audit.value = (_audit.value + call).takeLast(200)
        }
        // 审计同时进宿主日志：用户排查"谁动了我的文件"时，日志和面板两边都能对上。
        auditSink(caller, "[能力] $capability ${if (ok) "成功" else "失败"}（$decision）$detail")
        runCatching {
            auditFile.parentFile?.mkdirs()
            auditFile.appendText(
                JSONObject()
                    .put("at", call.at).put("caller", caller).put("capability", capability)
                    .put("decision", decision).put("ok", ok).put("args", preview).put("detail", call.detail)
                    .toString() + "\n"
            )
        }
        return CapResult(ok, detail)
    }

    private fun key(caller: String, capability: String) = "$caller|$capability"

    private fun loadGrants() {
        val text = runCatching { if (grantsFile.isFile) grantsFile.readText() else null }.getOrNull() ?: return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val map = LinkedHashMap<String, GrantDecision>()
        obj.keys().forEach { k ->
            runCatching { GrantDecision.valueOf(obj.optString(k)) }.getOrNull()?.let { map[k] = it }
        }
        _grants.value = map
    }
}
