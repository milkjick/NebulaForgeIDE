package com.nebulaforge.app.reverse.ai

import com.nebulaforge.core.agent.AiCompletionClient
import com.nebulaforge.app.reverse.ApkReverseEngine
import com.nebulaforge.app.reverse.WebTrafficRecorder
import com.nebulaforge.app.reverse.api.ApiReverseRegistry
import org.json.JSONObject

/** 逆向 AI 助手：统一 APK、Web、API 三类分析上下文，不直接修改目标文件。 */
class ReverseAiAssistant(private val client: AiCompletionClient) {
    data class ModificationProposal(val risk: String, val explanation: String, val unifiedDiff: String?)

    suspend fun analyzeApk(report: ApkReverseEngine.ApkReport): String = complete(
        "你是 Android 逆向分析助手。只分析用户拥有或已获授权的样本，不执行破坏性操作。",
        "分析以下 APK 静态信息，输出：1.应用结构 2.权限/组件风险点 3.网络端点线索 4.值得人工检查的 Smali/资源位置。不要虚构不存在的代码。\n${apkJson(report)}"
    )
    suspend fun analyzeWeb(records: List<WebTrafficRecorder.Record>): String = complete(
        "你是 Web/API 流量分析助手。仅根据捕获到的事实分析，不猜测未捕获的请求。",
        "分析以下 WebView 流量，按 DOCUMENT/API/SSE/WEBSOCKET/CHUNKED 分类，总结接口模式、认证头线索、SSE 事件格式和 WebSocket 特征，并列出下一步人工检查项。\n${records.takeLast(200).joinToString("\n") { "${it.method} ${it.url} kind=${it.kind} status=${it.statusCode} mime=${it.mimeType}" }}"
    )
    suspend fun analyzeApi(endpoints: List<ApiReverseRegistry.Endpoint>): String = complete(
        "你是 API 逆向分析助手。只使用捕获数据，不能把推测当成事实。",
        "分析以下 API 端点，输出：接口分组、认证方式线索、请求/响应格式、疑似分页/详情/列表关系、重复端点和建议的 OpenAPI/MCP 整理方式。\n${endpoints.take(300).joinToString("\n") { "${it.method} ${it.normalizedUrl} status=${it.status} mime=${it.mimeType} body=${it.requestBody?.take(500)}" }}"
    )
    suspend fun proposeModification(filePath: String, original: String, goal: String): String = complete(
        "你是 Android 逆向修改审查助手。只提供修改建议和补丁思路，不自动写入文件；用户必须审查后再应用。",
        "目标文件：$filePath\n修改目标：$goal\n原始内容：\n$original\n请输出风险、修改位置、建议后的完整片段或 unified diff。不要虚构 API。"
    )
    suspend fun proposeStructuredModification(filePath: String, original: String, goal: String): ModificationProposal {
        val raw = complete(
            "你是 Android 逆向修改审查助手。只针对用户授权的逆向工作区生成候选补丁。必须基于输入内容，不得虚构类、方法或资源。输出 JSON：risk、explanation、unifiedDiff；没有安全可靠的补丁时 unifiedDiff 必须为 null。",
            "文件：$filePath\n修改目标：$goal\n原始内容：\n$original"
        )
        val jsonText = raw.substringAfter('{', raw).substringBeforeLast('}', raw).let { "{$it}" }
        val json = runCatching { JSONObject(jsonText) }.getOrElse { JSONObject().put("risk", "需要人工审查").put("explanation", raw).put("unifiedDiff", JSONObject.NULL) }
        return ModificationProposal(json.optString("risk", "需要人工审查"), json.optString("explanation"), json.optString("unifiedDiff").takeIf { it.isNotBlank() && it != "null" })
    }

    private suspend fun complete(system: String, user: String) = client.complete(system, user)
    private fun apkJson(r: ApkReverseEngine.ApkReport) = JSONObject().apply {
        put("apk", r.apkPath); put("sha256", r.sha256); put("size", r.size); put("dexCount", r.dexCount)
        put("permissions", r.manifestSummary.permissions); put("activities", r.manifestSummary.activities)
        put("services", r.manifestSummary.services); put("receivers", r.manifestSummary.receivers)
        put("providers", r.manifestSummary.providers); put("urls", r.urls); put("nativeLibraries", r.nativeLibraries)
    }.toString(2)
}
