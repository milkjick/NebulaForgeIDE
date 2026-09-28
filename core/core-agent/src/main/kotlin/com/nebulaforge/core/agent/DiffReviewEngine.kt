package com.nebulaforge.core.agent

/** Line-oriented unified diff model used by BuildFix review. It never writes files itself. */
object DiffReviewEngine {
    enum class HunkStatus { PENDING, ACCEPTED, REJECTED }

    data class DiffHunk(
        val index: Int,
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val oldLines: List<String>,
        val newLines: List<String>,
        val status: HunkStatus = HunkStatus.PENDING
    )

    data class DiffLine(
        val prefix: Char,
        val text: String,
        val oldLine: Int?,
        val newLine: Int?
    )

    /** Produces bounded hunks with three lines of context around changed regions. */
    fun diff(original: String, proposed: String, context: Int = 3, maxLines: Int = 20_000): List<DiffHunk> {
        val old = splitLines(original)
        val newer = splitLines(proposed)
        if (old.size > maxLines || newer.size > maxLines) {
            return listOf(DiffHunk(0, 1, old.size, 1, newer.size, old, newer))
        }
        val ops = lcsDiff(old, newer)
        val changed = ops.withIndex().filter { it.value.first != Op.EQUAL }
        if (changed.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = changed.first().index
        var end = start
        for (i in changed.drop(1)) {
            if (i.index <= end + context * 2 + 1) end = i.index else { ranges += (maxOf(0, start - context)..minOf(ops.lastIndex, end + context)); start = i.index; end = i.index }
        }
        ranges += (maxOf(0, start - context)..minOf(ops.lastIndex, end + context))
        return ranges.mapIndexed { idx, range ->
            val slice = ops.subList(range.first, range.last + 1)
            val oldLines = slice.filter { it.first != Op.INSERT }.map { it.second }
            val newLines = slice.filter { it.first != Op.DELETE }.map { it.second }
            val oldStart = 1 + ops.take(range.first).count { it.first != Op.INSERT }
            val newStart = 1 + ops.take(range.first).count { it.first != Op.DELETE }
            DiffHunk(idx, oldStart, oldLines.size, newStart, newLines.size, oldLines, newLines)
        }
    }

    /** Renders a standard unified-diff view for human review. */
    fun unifiedDiff(original: String, proposed: String, context: Int = 3, maxLines: Int = 20_000): String {
        val hunks = diff(original, proposed, context, maxLines)
        if (hunks.isEmpty()) return ""
        val all = diffLines(original, proposed, context, maxLines)
        val out = StringBuilder("--- original\n+++ proposed\n")
        hunks.forEach { h ->
            out.append("@@ -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount} @@\n")
            val oldEnd = h.oldStart + h.oldCount - 1
            val newEnd = h.newStart + h.newCount - 1
            all.filter { line ->
                val oldHit = line.oldLine?.let { it in h.oldStart..oldEnd } == true
                val newHit = line.newLine?.let { it in h.newStart..newEnd } == true
                oldHit || newHit
            }.forEach { line ->
                out.append(line.prefix).append(line.text).append('\n')
            }
        }
        return out.toString().trimEnd()
    }

    /** Returns line-level mapping used by the editor review surface. */
    fun diffLines(original: String, proposed: String, context: Int = 3, maxLines: Int = 20_000): List<DiffLine> {
        val old = splitLines(original)
        val newer = splitLines(proposed)
        if (old.size > maxLines || newer.size > maxLines) {
            return old.mapIndexed { i, line -> DiffLine('-', line, i + 1, null) } +
                newer.mapIndexed { i, line -> DiffLine('+', line, null, i + 1) }
        }
        val ops = lcsDiff(old, newer)
        var oi = 1
        var ni = 1
        return ops.map { (op, text) ->
            when (op) {
                Op.EQUAL -> DiffLine(' ', text, oi++, ni++)
                Op.DELETE -> DiffLine('-', text, oi++, null)
                Op.INSERT -> DiffLine('+', text, null, ni++)
            }
        }
    }

    /** Applies accepted/rejected hunks against the original file. Pending hunks are accepted by default. */
    fun apply(original: String, proposed: String, hunks: List<DiffHunk>): String {
        if (hunks.isEmpty()) return original
        val all = splitLines(original).toMutableList()
        val decisions = hunks.associateBy { it.index }
        // Reconstruct by taking original and applying each selected hunk using its old/new ranges.
        var offset = 0
        for (h in hunks.sortedBy { it.oldStart }) {
            val status = decisions[h.index]?.status ?: HunkStatus.PENDING
            if (status == HunkStatus.REJECTED) continue
            val start = (h.oldStart - 1 + offset).coerceIn(0, all.size)
            val removeCount = h.oldCount.coerceAtMost((all.size - start).coerceAtLeast(0))
            repeat(removeCount) { all.removeAt(start) }
            all.addAll(start, h.newLines)
            offset += h.newLines.size - removeCount
        }
        return joinLines(all, original.endsWith("\n") || proposed.endsWith("\n"))
    }

    private fun splitLines(s: String): List<String> = s.split('\n').let { if (it.size == 1 && it[0].isEmpty()) emptyList() else it.dropLastWhile { x -> x.isEmpty() && s.endsWith("\n") } }
    private fun joinLines(lines: List<String>, trailing: Boolean): String = lines.joinToString("\n") + if (trailing && lines.isNotEmpty()) "\n" else ""

    private enum class Op { EQUAL, DELETE, INSERT }

    private fun lcsDiff(a: List<String>, b: List<String>): List<Pair<Op, String>> {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = ArrayList<Pair<Op, String>>(n + m)
        var i = 0; var j = 0
        while (i < n && j < m) {
            if (a[i] == b[j]) { out += Op.EQUAL to a[i]; i++; j++ }
            else if (dp[i + 1][j] >= dp[i][j + 1]) { out += Op.DELETE to a[i]; i++ }
            else { out += Op.INSERT to b[j]; j++ }
        }
        while (i < n) out += Op.DELETE to a[i++]
        while (j < m) out += Op.INSERT to b[j++]
        return out
    }
}
