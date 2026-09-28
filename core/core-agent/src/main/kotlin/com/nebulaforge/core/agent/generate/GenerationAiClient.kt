package com.nebulaforge.core.agent.generate

import com.nebulaforge.core.agent.AiCompletionClient
import com.nebulaforge.core.agent.FileContextResolver
import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.projectmodel.ProjectTypeRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 第 6 章流水线用到的 AI 能力封装（对应方案 6.2 中 `aiClient` 的一组方法）。
 *
 * 设计要点：只依赖 [AiCompletionClient] 这一抽象边界，不直接绑定任何厂商协议
 * （OpenAI 兼容 / Anthropic / Gemini 的差异由 core-ai-provider 内部消化）。
 * 所有解析均带安全兜底：模型返回非 JSON 时降级为可用结果，绝不因为一次格式抖动中断整条流水线。
 */
class GenerationAiClient(private val client: AiCompletionClient) {

    /** 阶段1：把自然语言需求解析为结构化需求清单 */
    suspend fun parseRequirement(userPrompt: String): RequirementSpec {
        val system = """
            你是移动端 IDE 的需求分析器。将用户的中文/英文需求转成 JSON，只输出 JSON，不要解释。
            JSON 结构：{"features":["..."],"suggestedTechStack":"android|flutter|web-frontend|web-backend|cpp","constraints":["..."]}
        """.trimIndent()
        val raw = runCatching { client.complete(system, userPrompt) }.getOrNull().orEmpty()
        val json = extractJsonObject(raw)
        if (json == null) {
            return RequirementSpec(features = listOf(userPrompt.trim()), suggestedTechStack = null, constraints = emptyList())
        }
        return RequirementSpec(
            features = json.optJSONArray("features").toStringList().ifEmpty { listOf(userPrompt.trim()) },
            suggestedTechStack = json.optString("suggestedTechStack").takeIf { it.isNotBlank() && it != "null" },
            constraints = json.optJSONArray("constraints").toStringList()
        )
    }

    /** 阶段2：由需求推断技术栈，给出 ProjectType + 模板 + 依赖建议 + 模块划分 */
    suspend fun inferTechStack(spec: RequirementSpec): ProjectPlan {
        val available = ProjectTypeRegistry.all().joinToString("、") { "${it.id}（模板：${it.templateIds().joinToString("/")}）" }
        val system = """
            你是移动端 IDE 的技术选型助手。只能从下列已注册技术栈中选择：
            $available
            只输出 JSON，不要解释。结构：
            {"projectTypeId":"...","templateId":"...","dependencies":["group:artifact:version"],"moduleBreakdown":[{"name":"...","description":"...","files":["相对路径"]}]}
        """.trimIndent()
        val user = buildString {
            append("需求：\n").append(spec.features.joinToString("\n") { "- $it" })
            if (spec.constraints.isNotEmpty()) append("\n约束：\n").append(spec.constraints.joinToString("\n") { "- $it" })
            spec.suggestedTechStack?.let { append("\n用户倾向技术栈：$it") }
        }
        val raw = runCatching { client.complete(system, user) }.getOrNull().orEmpty()
        val json = extractJsonObject(raw)
        val fallbackType = spec.suggestedTechStack?.let { ProjectTypeRegistry.getById(it) }
            ?: ProjectTypeRegistry.all().firstOrNull()
            ?: error("没有任何已注册的 ProjectType，无法推断技术栈")
        if (json == null) {
            return ProjectPlan(fallbackType, fallbackType.templateIds().firstOrNull() ?: "", emptyList(), emptyList())
        }
        val projectType = ProjectTypeRegistry.getById(json.optString("projectTypeId")) ?: fallbackType
        val templateId = json.optString("templateId").takeIf { it.isNotBlank() && it != "null" }
            ?: projectType.templateIds().firstOrNull()
            ?: ""
        return ProjectPlan(
            projectType = projectType,
            templateId = templateId,
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

    /** 阶段5→6：规划骨架之外还缺哪些文件（增量产出队列） */
    suspend fun planRemainingFiles(scaffold: ScaffoldResult): List<PendingFile> {
        val system = """
            你是移动端 IDE 的代码规划器。根据已有骨架文件，列出实现需求所缺的文件队列。只输出 JSON 数组，不要解释。
            结构：[{"path":"相对路径","purpose":"该文件职责","dependsOn":["相对路径"]}]
        """.trimIndent()
        val user = "已有文件：\n" + scaffold.files.joinToString("\n") { "- $it" } +
            "\n技术栈包名：${scaffold.packageName}"
        val raw = runCatching { client.complete(system, user) }.getOrNull().orEmpty()
        val array = extractJsonArray(raw) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { obj ->
                val path = obj.optString("path")
                if (path.isBlank()) null else PendingFile(
                    path = path,
                    purpose = obj.optString("purpose"),
                    dependsOn = obj.optJSONArray("dependsOn").toStringList()
                )
            }
        }
    }

    /** 阶段6：生成单个文件的完整内容（返回纯文本内容，非 JSON） */
    suspend fun generateFile(pending: PendingFile, context: List<GeneratedFile>): String {
        val system = """
            你是移动端 IDE 的代码生成器。只输出该文件的完整源码，不要 markdown 代码块标记，不要解释。
        """.trimIndent()
        val user = buildString {
            append("待生成文件：").append(pending.path).append('\n')
            append("职责：").append(pending.purpose).append('\n')
            if (pending.dependsOn.isNotEmpty()) append("依赖文件：").append(pending.dependsOn.joinToString("、")).append('\n')
            if (context.isNotEmpty()) {
                append("\n已生成的相关文件（仅供保持 API 一致）：\n")
                context.takeLast(5).forEach { append("--- ").append(it.path).append(" ---\n").append(it.content.take(4000)).append('\n') }
            }
        }
        return stripCodeFence(runCatching { client.complete(system, user) }.getOrDefault(""))
    }

    /** 单个文件的修复补丁（返回修复后的完整文件内容） */
    suspend fun generateFix(
        filePath: String,
        fileContent: String,
        errors: List<BuildError>,
        relatedContext: List<File>
    ): String {
        val system = "你是移动端 IDE 的构建错误修复器。只输出修复后该文件的完整内容，不要 markdown 标记，不要解释。"
        val user = buildString {
            append("文件：").append(filePath).append("\n\n当前内容：\n").append(fileContent).append("\n\n编译错误：\n")
            errors.forEach { append("- 行 ").append(it.line ?: 0).append("：").append(it.message).append('\n') }
            if (relatedContext.isNotEmpty()) {
                append("\n关联上下文：\n")
                relatedContext.take(5).forEach { f ->
                    append("--- ").append(f.name).append(" ---\n")
                    append(runCatching { f.readText() }.getOrDefault("").take(6000)).append('\n')
                }
            }
        }
        return stripCodeFence(runCatching { client.complete(system, user) }.getOrDefault(fileContent))
    }

    /**
     * 第 7.3 节：按文件分组批量修复构建错误。
     * 同一文件的多个错误合并成一次 AI 调用，减少请求次数。
     */
    suspend fun fixBuildErrors(errors: List<BuildError>, projectRoot: File): List<GeneratedFile> {
        val resolver = FileContextResolver()
        val fixes = mutableListOf<GeneratedFile>()
        errors.filter { !it.filePath.isNullOrBlank() }
            .groupBy { it.filePath!! }
            .forEach { (path, fileErrors) ->
                val file = File(path).let { if (it.isAbsolute) it else File(projectRoot, path) }
                if (!file.isFile) return@forEach
                val content = runCatching { file.readText() }.getOrDefault("")
                val related = runCatching { resolver.resolve(projectRoot, fileErrors).relatedFiles }.getOrDefault(emptyList())
                fixes += GeneratedFile(path, generateFix(path, content, fileErrors, related))
            }
        return fixes
    }

    // ---- 模型输出解析兜底 ----

    private fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpen = trimmed.removePrefix("```")
        val firstNewline = withoutOpen.indexOf('\n')
        val body = if (firstNewline >= 0) withoutOpen.substring(firstNewline + 1) else withoutOpen
        val end = body.lastIndexOf("```")
        return (if (end >= 0) body.substring(0, end) else body).trim()
    }

    private fun extractJsonObject(text: String): JSONObject? {
        val candidate = stripCodeFence(text)
        runCatching { return JSONObject(candidate) }
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(candidate.substring(start, end + 1)) }.getOrNull()
    }

    private fun extractJsonArray(text: String): JSONArray? {
        val candidate = stripCodeFence(text)
        runCatching { return JSONArray(candidate) }
        val start = candidate.indexOf('[')
        val end = candidate.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return runCatching { JSONArray(candidate.substring(start, end + 1)) }.getOrNull()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotBlank() && it != "null" }
    }

    private fun <T> JSONArray?.toObjectList(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it)?.let(mapper) }
    }
}
