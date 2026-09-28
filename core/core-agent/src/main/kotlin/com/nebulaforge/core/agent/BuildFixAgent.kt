package com.nebulaforge.core.agent

import com.nebulaforge.core.projectmodel.BuildError
import com.nebulaforge.core.session.IdeEvent
import com.nebulaforge.core.session.IdeSession
import com.nebulaforge.core.session.IdeSessionBus
import com.nebulaforge.core.session.SessionKind
import com.nebulaforge.core.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** AI provider boundary. No fake provider is installed: callers must supply a real client. */
interface AiCompletionClient {
    suspend fun complete(systemPrompt: String, userPrompt: String): String

    /**
     * 多轮 + 流式补全。
     *
     * [history] 按时间顺序给出 (role, content)，role 为 "user"/"assistant"：
     * 必须把**助手自己的历史回复**也一并传入，否则模型看不到自己上一轮说过什么，
     * 现象就是「聊到后面接不上 / 像被截断」。
     *
     * 默认实现回退到单轮 [complete]（既有实现零改动可用）；OpenAI 兼容实现覆写为真流式，
     * UI 才能逐字显示，并在 finish_reason=length 时提供「继续」。
     */
    suspend fun streamCompletion(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        options: com.nebulaforge.core.aiprovider.ChatOptions,
        onDelta: (String) -> Unit
    ): com.nebulaforge.core.aiprovider.ChatResult {
        val user = history.lastOrNull { it.first == "user" }?.second.orEmpty()
        val result = com.nebulaforge.core.aiprovider.ChatResult(complete(systemPrompt, user))
        if (result.text.isNotEmpty()) onDelta(result.text)
        return result
    }
}

data class AgentContext(
    val projectRoot: File,
    val primaryFile: File?,
    val primaryContent: String?,
    val errors: List<BuildError>,
    val relatedFiles: List<File>,
    val projectSummary: String
)

data class FileChange(
    val relativePath: String,
    val originalSha256: String?,
    val proposedContent: String,
    val status: Status = Status.PENDING
) {
    enum class Status { PENDING, ACCEPTED, REJECTED }
}

data class BuildFixProposal(
    val sessionId: String,
    val explanation: String,
    val changes: List<FileChange>
)

/** Reads only bounded project context; never uploads the whole project by default. */
class FileContextResolver(
    private val maxPrimaryChars: Int = 40_000,
    private val maxRelatedChars: Int = 12_000,
    private val maxRelatedFiles: Int = 5
) {
    suspend fun resolve(root: File, errors: List<BuildError>): AgentContext = withContext(Dispatchers.IO) {
        val primary = errors.firstOrNull()?.filePath?.let { resolveProjectFile(root, it) }
        val related = if (primary != null) relatedFiles(root, primary) else emptyList()
        AgentContext(
            root,
            primary,
            primary?.takeIf { it.isFile }?.readText()?.take(maxPrimaryChars),
            errors.take(20),
            related,
            projectSummary(root)
        )
    }

    private fun resolveProjectFile(root: File, path: String): File? {
        val candidate = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
        val canonicalRoot = root.canonicalFile
        return if (candidate.path == canonicalRoot.path || candidate.path.startsWith(canonicalRoot.path + File.separator)) candidate.takeIf { it.isFile } else null
    }

    private fun relatedFiles(root: File, primary: File): List<File> {
        val text = runCatching { primary.readText() }.getOrDefault("")
        val names = Regex("""(?:import|include)\s*[<"]([^>"]+)""").findAll(text).map { it.groupValues[1] }.toSet()
        return root.walkTopDown().filter { it.isFile && it.length() <= maxRelatedChars && it != primary }
            .filter { f -> names.any { n -> f.name == n.substringAfterLast('/').substringAfterLast('.') || f.nameWithoutExtension == n.substringAfterLast('.')} }
            .take(maxRelatedFiles).toList()
    }

    private fun projectSummary(root: File): String = buildString {
        listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "gradle.properties", ".nebulaforge/project.json")
            .map { File(root, it) }.filter { it.isFile }.forEach { f ->
                append("\n--- ").append(f.name).append(" ---\n")
                append(runCatching { f.readText() }.getOrDefault("").take(6000))
            }
    }
}

/** Parses a deliberately constrained JSON proposal instead of executing model-produced shell/code. */
class BuildFixProposalParser {
    fun parse(sessionId: String, raw: String, root: File): BuildFixProposal {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val json = org.json.JSONObject(cleaned)
        val explanation = json.optString("explanation")
        val changes = buildList {
            val arr = json.optJSONArray("changes") ?: org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val path = o.optString("path").trim()
                val content = o.optString("content", "")
                if (path.isBlank()) continue
                val target = File(root, path).canonicalFile
                if (target.path != root.canonicalFile.path && !target.path.startsWith(root.canonicalFile.path + File.separator)) continue
                add(FileChange(path, if (target.isFile) sha256(target) else null, content))
            }
        }
        return BuildFixProposal(sessionId, explanation, changes)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

/** Transactional file application with optimistic concurrency checks, backups and recovery. */
class GenerationTransaction(private val sessionId: String, private val realProjectDir: File) {
    private val tempDir = File(realProjectDir, ".nebulaforge/tmp/$sessionId")
    private val backupDir = File(tempDir, "backup")
    private val manifest = File(tempDir, "commit-manifest.json")

    fun stage(changes: List<FileChange>) {
        changes.forEach { change ->
            val target = safe(change.relativePath)
            target.parentFile?.mkdirs()
            File(tempDir, change.relativePath).canonicalFile.parentFile?.mkdirs()
            File(tempDir, change.relativePath).canonicalFile.writeText(change.proposedContent)
        }
    }

    fun commit(changes: List<FileChange>): List<String> {
        val accepted = changes.filter { it.status == FileChange.Status.ACCEPTED }
        require(accepted.isNotEmpty()) { "没有接受任何修改" }
        val entries = org.json.JSONArray()
        accepted.forEach { change ->
            val destination = safe(change.relativePath)
            val currentSha = if (destination.isFile) sha256(destination) else null
            if (currentSha != change.originalSha256) {
                throw IllegalStateException("文件在 AI 审查期间已改变：${change.relativePath}")
            }
            val staged = File(tempDir, change.relativePath).canonicalFile
            check(staged.isFile) { "缺少暂存文件：${change.relativePath}" }
            val backup = File(backupDir, change.relativePath).canonicalFile
            if (destination.isFile) {
                backup.parentFile?.mkdirs()
                destination.copyTo(backup, overwrite = true)
            }
            entries.put(org.json.JSONObject()
                .put("path", change.relativePath)
                .put("existed", destination.isFile)
                .put("backup", if (destination.isFile) backup.relativeTo(backupDir).path else ""))
        }
        backupDir.mkdirs()
        manifest.parentFile?.mkdirs()
        manifest.writeText(org.json.JSONObject().put("sessionId", sessionId).put("entries", entries).toString())
        try {
            accepted.forEach { change ->
                val destination = safe(change.relativePath)
                val staged = File(tempDir, change.relativePath).canonicalFile
                destination.parentFile?.mkdirs()
                staged.copyTo(destination, overwrite = true)
            }
            rollback()
            return accepted.map { it.relativePath }
        } catch (t: Throwable) {
            recoverAndRollback(sessionId, realProjectDir)
            throw t
        }
    }

    fun rollback() { tempDir.deleteRecursively() }

    private fun safe(relative: String): File {
        val root = realProjectDir.canonicalFile
        val f = File(root, relative).canonicalFile
        require(f.path.startsWith(root.path + File.separator)) { "非法项目相对路径：$relative" }
        return f
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    companion object {
        fun recoverAndRollback(sessionId: String, projectDir: File): Boolean {
            val temp = File(projectDir, ".nebulaforge/tmp/$sessionId")
            val manifest = File(temp, "commit-manifest.json")
            if (!manifest.isFile) return temp.exists().also { if (it) temp.deleteRecursively() }
            return runCatching {
                val root = projectDir.canonicalFile
                val entries = org.json.JSONObject(manifest.readText()).optJSONArray("entries") ?: org.json.JSONArray()
                for (i in 0 until entries.length()) {
                    val e = entries.getJSONObject(i)
                    val rel = e.getString("path")
                    val dest = File(root, rel).canonicalFile
                    require(dest.path.startsWith(root.path + File.separator))
                    if (e.optBoolean("existed")) {
                        val backup = File(temp, "backup/${e.optString("backup")}").canonicalFile
                        require(backup.path.startsWith(File(temp, "backup").canonicalPath + File.separator) || backup.path == File(temp, "backup").canonicalPath)
                        if (backup.isFile) {
                            dest.parentFile?.mkdirs()
                            backup.copyTo(dest, overwrite = true)
                        } else return@runCatching false
                    } else {
                        dest.delete()
                    }
                }
                temp.deleteRecursively()
                true
            }.getOrDefault(false)
        }
    }
}

class BuildFixAgent(
    private val aiClient: AiCompletionClient,
    private val fileContext: FileContextResolver = FileContextResolver(),
    private val parser: BuildFixProposalParser = BuildFixProposalParser(),
    private val bus: IdeSessionBus
) {
    private val session = IdeSession(kind = SessionKind.BUILD_FIX)
    private var registeredProject: String? = null

    private fun ensureRegistered(projectRoot: File) {
        if (registeredProject == projectRoot.absolutePath) return
        bus.register(session, projectRoot.absolutePath)
        registeredProject = projectRoot.absolutePath
    }

    suspend fun propose(projectRoot: File, errors: List<BuildError>): BuildFixProposal {
        require(errors.isNotEmpty()) { "没有可修复的构建错误" }
        ensureRegistered(projectRoot)
        val context = fileContext.resolve(projectRoot, errors)
        bus.state(session, SessionState.Running("AI 构建错误分析"), projectRoot.absolutePath)
        return try {
            val raw = aiClient.complete(systemPrompt(), userPrompt(context))
            val proposal = parser.parse(session.id, raw, projectRoot)
            bus.emit(IdeEvent.Output(session.id, "AI 修复建议生成：${proposal.changes.size} 个文件\n"))
            bus.state(session, SessionState.Succeeded("等待逐文件审查"), projectRoot.absolutePath)
            proposal
        } catch (t: Throwable) {
            bus.state(session, SessionState.Failed("AI 修复失败：${t.message}"), projectRoot.absolutePath)
            throw t
        }
    }

    fun stage(proposal: BuildFixProposal, projectRoot: File): GenerationTransaction {
        val tx = GenerationTransaction(proposal.sessionId, projectRoot)
        tx.stage(proposal.changes)
        return tx
    }

    fun commitAccepted(proposal: BuildFixProposal, tx: GenerationTransaction): List<String> = tx.commit(proposal.changes)

    fun rollback(tx: GenerationTransaction) = tx.rollback()

    private fun systemPrompt() = """
        你是 NebulaForge IDE 的 BuildFixAgent。只分析编译错误并提出最小代码修改。
        输出必须是 JSON：{"explanation":"...","changes":[{"path":"relative/path","content":"完整文件内容"}]}。
        禁止 shell 命令、权限修改、二进制文件、绝对路径。不要修改无关文件。
        如果无法安全确定完整文件内容，返回空 changes 并说明原因。
    """.trimIndent()

    private fun userPrompt(c: AgentContext) = buildString {
        append("项目摘要：\n").append(c.projectSummary)
        append("\n\n构建错误：\n")
        c.errors.forEach { append("${it.filePath}:${it.line}:${it.column} [${it.severity}] ${it.message}\n") }
        c.primaryFile?.let { append("\n主要文件 ${it.relativeTo(c.projectRoot).path}：\n").append(c.primaryContent.orEmpty()) }
        c.relatedFiles.forEach { f -> append("\n关联文件 ${f.relativeTo(c.projectRoot).path}：\n").append(runCatching { f.readText() }.getOrDefault("").take(12_000)) }
    }
}
