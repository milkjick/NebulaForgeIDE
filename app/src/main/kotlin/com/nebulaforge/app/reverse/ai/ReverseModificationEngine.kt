package com.nebulaforge.app.reverse.ai

import java.io.File
import java.security.MessageDigest

/** AI 逆向修改安全事务：只对用户指定的逆向工作区文件应用 unified diff，并保留回滚备份。 */
class ReverseModificationEngine {
    data class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<String>)
    data class Patch(val filePath: String, val hunks: List<Hunk>)

    fun parseUnifiedDiff(text: String): Patch {
        val lines = text.lineSequence().toList()
        val file = lines.firstOrNull { it.startsWith("+++ ") }?.removePrefix("+++ ")?.removePrefix("b/")
            ?: error("AI 输出中没有 unified diff 的目标文件")
        val hunks = mutableListOf<Hunk>()
        var i = 0
        while (i < lines.size) {
            val header = lines[i]
            if (!header.startsWith("@@")) { i++; continue }
            val m = Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@").find(header)
                ?: error("无法解析 diff 块：$header")
            val body = mutableListOf<String>(); i++
            while (i < lines.size && !lines[i].startsWith("@@")) {
                if (lines[i].startsWith(" ") || lines[i].startsWith("+") || lines[i].startsWith("-")) body += lines[i]
                i++
            }
            hunks += Hunk(m.groupValues[1].toInt(), m.groupValues[2].ifBlank { "1" }.toInt(), m.groupValues[3].toInt(), m.groupValues[4].ifBlank { "1" }.toInt(), body)
        }
        require(hunks.isNotEmpty()) { "没有可应用的 diff" }
        return Patch(file, hunks)
    }

    fun preview(file: File, patch: Patch): String {
        val original = file.readLines(Charsets.UTF_8)
        val result = applyLines(original, patch.hunks, dryRun = true)
        return buildString {
            append("文件：").append(file.name).append('\n')
            append("原始 SHA-256：").append(sha256(original.joinToString("\n").toByteArray())).append('\n')
            append("修改后行数：").append(result.size).append('\n')
            append("Hunk 数量：").append(patch.hunks.size)
        }
    }

    fun apply(file: File, patch: Patch, workspace: File) {
        require(file.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath())) { "禁止修改逆向工作区之外的文件" }
        val original = file.readLines(Charsets.UTF_8)
        val result = applyLines(original, patch.hunks, dryRun = false)
        val backup = File(file.parentFile, file.name + ".nebulaforge.ai.bak")
        if (!backup.exists()) file.copyTo(backup)
        val tmp = File(file.parentFile, file.name + ".nebulaforge.ai.tmp")
        tmp.writeText(result.joinToString("\n") + if (result.isNotEmpty()) "\n" else "", Charsets.UTF_8)
        require(tmp.renameTo(file)) { "AI 修改写入失败" }
    }

    private fun applyLines(original: List<String>, hunks: List<Hunk>, dryRun: Boolean): List<String> {
        val out = original.toMutableList(); var delta = 0
        hunks.forEach { h ->
            var index = h.oldStart - 1 + delta
            require(index >= 0 && index <= out.size) { "diff 行号超出范围：${h.oldStart}" }
            var consumed = 0
            val additions = mutableListOf<String>()
            h.lines.forEach { line ->
                when (line.firstOrNull()) {
                    ' ' -> { require(index + consumed < out.size && out[index + consumed] == line.drop(1)) { "diff 上下文不匹配" }; consumed++ }
                    '-' -> { require(index + consumed < out.size && out[index + consumed] == line.drop(1)) { "diff 删除内容不匹配" }; if (!dryRun) out.removeAt(index + consumed) }
                    '+' -> additions += line.drop(1)
                }
            }
            if (!dryRun) out.addAll(index, additions)
            delta += additions.size - h.lines.count { it.startsWith("-") }
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
