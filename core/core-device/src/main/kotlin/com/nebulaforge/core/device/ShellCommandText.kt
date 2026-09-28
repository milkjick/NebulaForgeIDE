package com.nebulaforge.core.device

/**
 * 从计划步骤描述中提取"要执行的命令原文"。
 *
 * 规划器被要求把命令原样放进 description，但模型实际输出常见三种形态：
 *  1. 纯命令行：`ls -l /sdcard`
 *  2. 代码块：```sh ... ```
 *  3. 命令前带 `$ ` 提示符或 `命令：` 之类前缀
 * 这里统一归一化，避免把前缀当成命令的一部分交给 shell 执行。
 */
object ShellCommandText {

    private val FENCE = Regex("```[ \\t]*[A-Za-z0-9_+-]*[ \\t]*\\r?\\n([\\s\\S]*?)```")
    private val PREFIXES = listOf("命令：", "命令:", "command:", "Command:", "CMD:", "cmd:")

    fun extract(raw: String): String {
        var text = raw.trim()
        FENCE.find(text)?.let { text = it.groupValues[1] }
        PREFIXES.forEach { prefix -> if (text.startsWith(prefix)) text = text.removePrefix(prefix) }
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> when {
                line.startsWith("\$ ") -> line.removePrefix("\$ ")
                line.startsWith("\\$") -> line.removePrefix("\\$")
                line.startsWith("\$") -> line.removePrefix("\$")
                line.startsWith("#!") -> line
                line.startsWith("#") -> ""
                else -> line
            } }
            .filter { it.isNotBlank() }
            .toList()
        return lines.joinToString("\n").trim()
    }
}
