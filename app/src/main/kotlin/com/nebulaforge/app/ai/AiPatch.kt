package com.nebulaforge.app.ai

import java.io.File
import java.util.UUID

/**
 * 待确认的写文件改动。
 *
 * 任务模式里 AI 提出 [AiToolCatalog.WRITE_FILE] 时**不直接落盘**，而是先生成一个
 * `AiPendingPatch` 挂到状态里，由用户在界面上看完 Diff 点「应用」才真正写入。
 * 这是「AI 可以改代码，但改什么必须你点头」的落地方式。
 */
data class AiPendingPatch(
    val id: String = UUID.randomUUID().toString(),
    val toolCallId: String,
    val path: String,
    val content: String,
    /** 原文件内容（新建文件为 null）。 */
    val original: String?,
    val createdAt: Long = System.currentTimeMillis()
) {
    val exists: Boolean get() = original != null

    /** 统一 Diff 文本，界面直接展示。 */
    val diff: String by lazy { UnifiedDiff.render(path, original, content) }

    val addedLines: Int get() = diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
    val removedLines: Int get() = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
    val summary: String
        get() = buildString {
            append(if (exists) "修改 " else "新建 ").append(File(path).name)
            append("（+").append(addedLines).append(" / -").append(removedLines).append(" 行）")
        }
}

/**
 * 极小的统一 Diff 实现（够用即可，不引入 diffutils 依赖）。
 *
 * 采用「公共前缀 + 公共后缀裁剪 + 中段整块替换」策略：代码编辑场景里 AI 往往是
 * 局部改动，这样能给出**可读且诚实**的 Diff，而不是把整个文件都标成 +/-。
 */
object UnifiedDiff {

    fun render(path: String, original: String?, updated: String): String {
        val header = "--- ${if (original == null) "/dev/null" else "a/$path"}\n+++ b/$path"
        if (original == null) {
            return header + "\n@@ 新建文件，共 ${updated.lineCount()} 行 @@\n" +
                updated.lineSequence().joinToString("\n") { "+$it" }
        }
        if (original == updated) return "$header\n@@ 内容无变化 @@"
        val a = original.split('\n')
        val b = updated.split('\n')

        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++

        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix &&
            a[a.size - 1 - suffix] == b[b.size - 1 - suffix]
        ) suffix++

        val contextBefore = minOf(3, prefix)
        val contextAfter = minOf(3, suffix)
        val fromLine = prefix - contextBefore + 1
        val head = a.subList(prefix - contextBefore, prefix)
        val tail = a.subList(a.size - suffix, a.size - suffix + contextAfter)
        val removedMid = a.subList(prefix, a.size - suffix)
        val addedMid = b.subList(prefix, b.size - suffix)

        return buildString {
            append(header).append('\n')
            append("@@ -").append(fromLine).append(',').append(head.size + removedMid.size + tail.size)
                .append(" +").append(fromLine).append(',').append(head.size + addedMid.size + tail.size).append(" @@\n")
            head.forEach { append("  ").append(it).append('\n') }
            removedMid.forEach { append("- ").append(it).append('\n') }
            addedMid.forEach { append("+ ").append(it).append('\n') }
            tail.forEach { append("  ").append(it).append('\n') }
        }.trimEnd()
    }

    private fun String.lineCount(): Int = if (isEmpty()) 0 else count { it == '\n' } + 1
}
