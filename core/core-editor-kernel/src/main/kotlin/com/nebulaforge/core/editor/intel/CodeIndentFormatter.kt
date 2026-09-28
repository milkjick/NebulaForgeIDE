package com.nebulaforge.core.editor.intel

/**
 * 纯文本格式化器：按代码块层级重排缩进。
 *
 * 目标不是"重写代码风格"，而是解决最常见的两类乱象：
 * 1. 括号层层堆叠但缩进已乱 → 用花括号深度重新推导每行缩进；
 * 2. 续行 / 链式调用没有缩进 → 首字符是 `.` `?.` `&&` 等或上一行以运算符结尾时加一级。
 *
 * 全部实现保持"语义不变、只改空白"，并且尽量不动多行字符串内部的内容。
 */
object CodeIndentFormatter {

    fun format(fileName: String, text: String): String {
        if (text.isEmpty()) return text
        val lang = IntelLanguages.of(fileName)
        val eol = if (text.contains("\r\n")) "\r\n" else "\n"
        val normalized = text.replace("\r\n", "\n")
        val result = try {
            when (lang) {
                IntelLanguage.PYTHON -> formatByIndentLevels(normalized)
                IntelLanguage.JSON -> formatJson(normalized, detectJsonStyle(normalized))
                IntelLanguage.XML -> formatXml(normalized)
                IntelLanguage.YAML -> normalized
                IntelLanguage.PROPERTIES -> normalized
                IntelLanguage.MARKDOWN, IntelLanguage.PLAIN -> normalized
                else -> formatByBraces(lang, normalized)
            }
        } catch (t: Throwable) {
            return text
        }
        val tidied = result.split('\n').joinToString("\n") { it.trimEnd() }.trimEnd('\n') + "\n"
        return if (eol == "\r\n") tidied.replace("\n", "\r\n") else tidied
    }

    // ---------------------------------------------------------------- 缩进单位

    private class IndentStyle(val unit: String, val width: Int)

    private fun detectStyle(lines: List<String>, lang: IntelLanguage): IndentStyle {
        var tabs = 0
        var spaces = 0
        val positives = ArrayList<Int>()
        lines.take(600).forEach { l ->
            val ws = l.takeWhile { it == ' ' || it == '\t' }
            if (l.isBlank() || ws.isEmpty()) return@forEach
            if (ws.contains('\t')) {
                tabs++
            } else {
                spaces++
                if (ws.length in 1..8) positives.add(ws.length)
            }
        }
        if (tabs > 0 && tabs >= spaces) return IndentStyle("\t", 1)
        val min = positives.minOrNull() ?: 4
        val width = when {
            min >= 8 -> 8
            min >= 4 -> 4
            min == 3 -> 3
            min == 2 -> 2
            else -> if (lang in setOf(IntelLanguage.JAVASCRIPT, IntelLanguage.TYPESCRIPT)) 2 else 4
        }
        return IndentStyle(" ".repeat(width), width)
    }

    // ---------------------------------------------------------------- 花括号语言

    private val CONTINUATION_STARTS =
        listOf(".", "?.", "&&", "||", "->", "::", "+", "-", "*", "/", "%", "?")

    private fun formatByBraces(lang: IntelLanguage, text: String): String {
        val lines = text.split('\n')
        val style = detectStyle(lines, lang)
        val mask = Lexer.codeMask(text)

        // 行首绝对偏移
        val lineStarts = IntArray(lines.size)
        run {
            var off = 0
            lines.forEachIndexed { i, l ->
                lineStarts[i] = off
                off += l.length + 1
            }
        }

        val out = StringBuilder(text.length + 64)
        var depth = 0

        lines.forEachIndexed { li, rawLine ->
            val lineStart = lineStarts[li]
            val trimmedEnd = rawLine.trimEnd()
            val trimmedStart = trimmedEnd.trimStart()
            val indentLen = trimmedEnd.length - trimmedStart.length

            if (trimmedStart.isEmpty()) {
                out.append('\n')
                return@forEachIndexed
            }

            // 行首落在字符串 / 注释内（含多行字符串、块注释续行）→ 原样保留
            val firstOffset = lineStart + indentLen
            if (firstOffset < mask.size && !mask[firstOffset]) {
                out.append(trimmedEnd).append('\n')
                return@forEachIndexed
            }

            // 行首闭合括号 → 该行反向缩进一级
            var leadingClosers = 0
            var ci = 0
            while (ci < trimmedStart.length && trimmedStart[ci] in "}])") {
                leadingClosers++
                ci++
            }
            var level = (depth - leadingClosers).coerceAtLeast(0)

            // 续行 / 链式调用多缩进一级
            if (leadingClosers == 0 && CONTINUATION_STARTS.any { trimmedStart.startsWith(it) }) {
                level += 1
            }

            out.append(style.unit.repeat(level)).append(trimmedStart).append('\n')

            // 维护括号深度（只统计代码区字符）
            var k = 0
            while (k < trimmedEnd.length) {
                val abs = lineStart + indentLen + k
                if (abs < mask.size && mask[abs]) {
                    when (trimmedEnd[k]) {
                        '{' -> depth++
                        '}' -> depth = (depth - 1).coerceAtLeast(0)
                    }
                }
                k++
            }
        }
        return out.toString().trimEnd('\n')
    }

    // ---------------------------------------------------------------- 缩进语言（Python）

    private fun formatByIndentLevels(text: String): String {
        val lines = text.split('\n')
        val style = detectStyle(lines, IntelLanguage.PYTHON)
        val unitWidth = style.width
        val out = StringBuilder(text.length + 64)
        var inTriple = false
        lines.forEach { raw ->
            val trimmedEnd = raw.trimEnd()
            val trimmedStart = trimmedEnd.trimStart()
            if (trimmedStart.isEmpty()) {
                out.append('\n')
                return@forEach
            }
            if (inTriple) {
                out.append(trimmedEnd).append('\n')
                if (trimmedEnd.count { it == '"' } % 2 == 1 || trimmedEnd.count { it == '\'' } % 2 == 1) inTriple = false
                return@forEach
            }
            if (trimmedStart.startsWith("\"\"\"") || trimmedStart.startsWith("'''")) {
                out.append(trimmedEnd).append('\n')
                if (trimmedEnd.length < 6) inTriple = true
                return@forEach
            }
            val indent = trimmedEnd.length - trimmedStart.length
            val level = Math.round(indent.toFloat() / unitWidth)
            out.append(style.unit.repeat(level.coerceAtLeast(0) + if (level == 0 && indent > 0) 1 else 0))
                .append(trimmedStart).append('\n')
        }
        return out.toString().trimEnd('\n')
    }

    // ---------------------------------------------------------------- JSON

    /** JSON 缩进宽度：沿用文件已有风格，默认 2 空格。 */
    private fun detectJsonStyle(text: String): IndentStyle {
        val indents = text.split('\n').mapNotNull { l ->
            val ws = l.takeWhile { it == ' ' }
            if (l.trim().isEmpty() || ws.isEmpty()) null else ws.length
        }
        val min = indents.minOrNull() ?: 2
        val width = when {
            min >= 8 -> 8
            min >= 4 -> 4
            min >= 3 -> 3
            min >= 2 -> 2
            else -> 2
        }
        return IndentStyle(" ".repeat(width), width)
    }

    private fun formatJson(text: String, style: IndentStyle): String {
        val out = StringBuilder(text.length + 128)
        var depth = 0
        var i = 0
        var inString = false
        var escaped = false
        fun newline() {
            out.append('\n').append(style.unit.repeat(depth))
        }
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                }
                c == '"' -> {
                    inString = true
                    out.append(c)
                }
                c == '{' || c == '[' -> {
                    depth++
                    out.append(c)
                    // 紧随其后的闭合符号不换行
                    val next = text.drop(i + 1).firstOrNull { !it.isWhitespace() }
                    if (next != '}' && next != ']' && next != null) newline()
                }
                c == '}' || c == ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    val last = tex0(out)
                    if (last != '}' && last != ']' && last != '{' && last != '[') {
                        out.append('\n').append(style.unit.repeat(depth))
                    }
                    out.append(c)
                }
                c == ',' -> {
                    out.append(c)
                    newline()
                }
                c == ':' -> out.append(": ")
                c.isWhitespace() -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString().trimEnd('\n')
    }

    private fun tex0(sb: StringBuilder): Char? =
        if (sb.isEmpty()) null else sb[sb.length - 1]

    // ---------------------------------------------------------------- XML

    private fun formatXml(text: String): String {
        val style = IndentStyle("    ", 4)
        val out = StringBuilder(text.length + 128)
        var depth = 0
        val tokenRe = Regex("(<[^>]*>|[^<]+)")
        tokenRe.findAll(text).forEach { m ->
            val token = m.value
            if (token.startsWith("<")) {
                val isClosing = token.startsWith("</")
                val isSelfClose = token.endsWith("/>") || token.startsWith("<?") || token.startsWith("<!")
                if (isClosing) depth = (depth - 1).coerceAtLeast(0)
                out.append(style.unit.repeat(depth)).append(token.trim()).append('\n')
                if (!isClosing && !isSelfClose) depth++
            } else {
                val content = token.trim()
                if (content.isNotEmpty()) out.append(style.unit.repeat(depth)).append(content).append('\n')
            }
        }
        return out.toString().trimEnd('\n')
    }
}
