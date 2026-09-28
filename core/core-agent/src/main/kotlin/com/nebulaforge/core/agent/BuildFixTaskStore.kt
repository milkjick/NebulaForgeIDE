package com.nebulaforge.core.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable BuildFix task journal. It stores only task metadata/proposed file contents;
 * staged files remain under .nebulaforge/tmp/<sessionId> and are never copied into prefs.
 */
class BuildFixTaskStore(context: Context) {
    private val file = File(context.filesDir, "sessions/build-fix-task.json")

    data class Record(
        val phase: String,
        val projectPath: String?,
        val errorCount: Int,
        val message: String,
        val proposal: BuildFixProposal?,
        val round: Int = 0,
        val maxRounds: Int = 3,
        val sourceBuildSessionId: String? = null,
        val rebuildSessionId: String? = null,
        val history: List<BuildFixCoordinator.RoundRecord> = emptyList(),
        val diagnosticSignature: String? = null,
        val hunkStatuses: Map<String, Map<Int, DiffReviewEngine.HunkStatus>> = emptyMap()
    )

    @Synchronized
    fun save(state: BuildFixCoordinator.State) {
        file.parentFile?.mkdirs()
        val root = JSONObject()
            .put("phase", state.phase.name)
            .put("projectPath", state.projectPath)
            .put("errorCount", state.errorCount)
            .put("message", state.message)
            .put("round", state.round)
            .put("maxRounds", state.maxRounds)
            .put("sourceBuildSessionId", state.sourceBuildSessionId)
            .put("rebuildSessionId", state.rebuildSessionId)
            .put("diagnosticSignature", state.diagnosticSignature)
            .put("hunkStatuses", JSONObject().apply { state.hunkStatuses.forEach { (path, statuses) -> put(path, JSONObject().apply { statuses.forEach { (index, status) -> put(index.toString(), status.name) } }) } })
            .put("history", JSONArray().apply { state.history.forEach { h -> put(JSONObject()
                .put("round", h.round).put("buildSessionId", h.buildSessionId).put("fixSessionId", h.fixSessionId)
                .put("rebuildSessionId", h.rebuildSessionId).put("errorCount", h.errorCount).put("outcome", h.outcome).put("diagnosticSignature", h.diagnosticSignature)) } })
        state.proposal?.let { root.put("proposal", encode(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    @Synchronized
    fun load(): Record? = runCatching {
        if (!file.isFile) return null
        val root = JSONObject(file.readText())
        val proposal = root.optJSONObject("proposal")?.let(::decode)
        Record(
            phase = root.optString("phase", "IDLE"),
            projectPath = root.optString("projectPath").takeIf { it.isNotBlank() && it != "null" },
            errorCount = root.optInt("errorCount", 0),
            message = root.optString("message", ""),
            proposal = proposal,
            round = root.optInt("round", 0),
            maxRounds = root.optInt("maxRounds", 3),
            sourceBuildSessionId = root.optString("sourceBuildSessionId").takeIf { it.isNotBlank() && it != "null" },
            rebuildSessionId = root.optString("rebuildSessionId").takeIf { it.isNotBlank() && it != "null" },
            history = buildList {
                val a = root.optJSONArray("history") ?: JSONArray()
                for (i in 0 until a.length()) {
                    val h = a.optJSONObject(i) ?: continue
                    add(BuildFixCoordinator.RoundRecord(
                        h.optInt("round", 0), h.optString("buildSessionId").takeIf { it.isNotBlank() && it != "null" },
                        h.optString("fixSessionId"), h.optString("rebuildSessionId").takeIf { it.isNotBlank() && it != "null" },
                        h.optInt("errorCount", 0), h.optString("outcome", "UNKNOWN"), h.optString("diagnosticSignature").takeIf { it.isNotBlank() && it != "null" }
                    ))
                }
            },
            diagnosticSignature = root.optString("diagnosticSignature").takeIf { it.isNotBlank() && it != "null" },
            hunkStatuses = buildMap {
                val hs = root.optJSONObject("hunkStatuses") ?: JSONObject()
                val keys = hs.keys()
                while (keys.hasNext()) {
                    val path = keys.next()
                    val obj = hs.optJSONObject(path) ?: continue
                    val statuses = mutableMapOf<Int, DiffReviewEngine.HunkStatus>()
                    val sk = obj.keys()
                    while (sk.hasNext()) {
                        val idx = sk.next().toIntOrNull() ?: continue
                        val st = runCatching { DiffReviewEngine.HunkStatus.valueOf(obj.optString(idx.toString(), "PENDING")) }.getOrDefault(DiffReviewEngine.HunkStatus.PENDING)
                        statuses[idx] = st
                    }
                    put(path, statuses)
                }
            }
        )
    }.getOrNull()

    @Synchronized
    fun clear() { file.delete() }

    private fun encode(p: BuildFixProposal): JSONObject = JSONObject()
        .put("sessionId", p.sessionId)
        .put("explanation", p.explanation)
        .put("changes", JSONArray().apply {
            p.changes.forEach { c ->
                put(JSONObject()
                    .put("relativePath", c.relativePath)
                    .put("originalSha256", c.originalSha256)
                    .put("proposedContent", c.proposedContent)
                    .put("status", c.status.name))
            }
        })

    private fun decode(o: JSONObject): BuildFixProposal {
        val changes = buildList {
            val a = o.optJSONArray("changes") ?: JSONArray()
            for (i in 0 until a.length()) {
                val c = a.optJSONObject(i) ?: continue
                val status = runCatching { FileChange.Status.valueOf(c.optString("status", "PENDING")) }
                    .getOrDefault(FileChange.Status.PENDING)
                add(FileChange(
                    relativePath = c.optString("relativePath"),
                    originalSha256 = c.optString("originalSha256").takeIf { it.isNotBlank() && it != "null" },
                    proposedContent = c.optString("proposedContent", ""),
                    status = status
                ))
            }
        }
        return BuildFixProposal(o.optString("sessionId"), o.optString("explanation", ""), changes)
    }
}
