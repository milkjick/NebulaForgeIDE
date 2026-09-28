package com.nebulaforge.core.session

/**
 * Read-only presentation model derived from the live SessionGraph. It deliberately
 * contains no execution logic; commands still go through UnifiedRunController.
 */
data class SessionTimelineEntry(
    val sessionId: String,
    val kind: SessionKind,
    val state: SessionState,
    val label: String,
    val projectPath: String?,
    val artifact: String?,
    val device: String?,
    val updatedAt: Long
)

data class SessionExecutionTimeline(
    val root: SessionTimelineEntry? = null,
    val entries: List<SessionTimelineEntry> = emptyList(),
    val links: List<SessionGraphLink> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

object SessionExecutionTimelineBuilder {
    fun build(snapshot: SessionGraphSnapshot, rootId: String?): SessionExecutionTimeline {
        if (rootId.isNullOrBlank()) return SessionExecutionTimeline(updatedAt = snapshot.updatedAt)
        val byId = snapshot.nodes.associateBy { it.id }
        val root = byId[rootId] ?: return SessionExecutionTimeline(updatedAt = snapshot.updatedAt)
        val reachable = linked(rootId, snapshot.links)
        val ids = listOf(rootId) + reachable.filterNot { it == rootId }
        val entries = ids.mapNotNull { id -> byId[id] }.map { node ->
            SessionTimelineEntry(node.id, node.kind, node.state, label(node.kind), node.projectPath, node.artifact, node.device, node.updatedAt)
        }
        val links = snapshot.links.filter { it.from in ids && it.to in ids }
        return SessionExecutionTimeline(entries.firstOrNull(), entries, links, snapshot.updatedAt)
    }

    private fun linked(root: String, links: List<SessionGraphLink>): List<String> {
        val seen = linkedSetOf(root)
        var changed = true
        while (changed) {
            changed = false
            links.forEach { link ->
                if (link.from in seen && seen.add(link.to)) changed = true
                if (link.to in seen && seen.add(link.from)) changed = true
            }
        }
        return seen.toList()
    }

    private fun label(kind: SessionKind): String = when (kind) {
        SessionKind.BUILD -> "Build"
        SessionKind.BUILD_FIX -> "AI Build Fix"
        SessionKind.RUN -> "Run"
        SessionKind.LOGCAT -> "Logcat"
        SessionKind.DEVICE -> "Device"
        SessionKind.TERMINAL -> "Terminal"
        SessionKind.TOOLCHAIN -> "Toolchain"
        SessionKind.LSP -> "LSP"
        SessionKind.EDITOR -> "Editor"
    }
}
