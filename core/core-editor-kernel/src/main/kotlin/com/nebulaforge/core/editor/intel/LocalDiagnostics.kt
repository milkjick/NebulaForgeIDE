package com.nebulaforge.core.editor.intel

/**
 * 本地静态检查：把"能自动看出来的问题"变成编辑器里的问题列表。
 *
 * 分级：
 * - ERROR：语法层面的硬错误（括号/引号未闭合、JSON 解析失败、XML 标签不匹配）；
 * - WARNING：可疑写法（缩进与代码块不符、未使用的变量或导入、空的 catch、疑似硬编码密钥）；
 * - INFO：代码卫生（TODO 标记、调试输出、超长行、连续空行）。
 *
 * 说明：这是启发式检查，不替代编译器；命名与文案都按"提示"而非"断言"处理。
 */
object LocalDiagnostics {

    private const val MAX_ISSUES = 400

    fun analyze(fileName: String, text: String): List<LocalIssue> {
        if (text.isEmpty()) return emptyList()
        val lang = IntelLanguages.of(fileName)
        val out = ArrayList<LocalIssue>()

        // 单项隔离：诊断是"提示"性质，任何一项出错（拼错正则、极端输入）都只放弃那一项。
        // 真机取证：checkCodeSmells 里的 catch 空块正则漏转义 `}` → PatternSyntaxException，
        // 而它跑在编辑器协程里，异常直接上抛成 FATAL EXCEPTION: main —— 打开文件就闪退。
        val isolated: (() -> Unit) -> Unit = { body -> runCatching(body) }

        isolated { when (lang) {
            IntelLanguage.JSON -> checkJson(text, out)
            IntelLanguage.XML -> checkXml(text, out)
            IntelLanguage.PROPERTIES -> checkProperties(text, out)
            IntelLanguage.YAML -> checkYaml(text, out)
            IntelLanguage.SHELL -> checkShell(text, out)
            IntelLanguage.MARKDOWN, IntelLanguage.PLAIN -> Unit
            else -> {
                checkBrackets(text, out)
                checkIndentation(lang, text, out)
            }
        } }
        if (lang !in setOf(IntelLanguage.MARKDOWN, IntelLanguage.PLAIN, IntelLanguage.XML, IntelLanguage.JSON)) {
            isolated { checkCodeSmells(fileName, lang, text, out) }
        }
        isolated { checkMarkers(text, out) }
        return out.take(MAX_ISSUES)
    }

    // ---------------------------------------------------------------- 括号 / 引号

    private fun checkBrackets(text: String, out: MutableList<LocalIssue>) {
        val mask = Lexer.codeMask(text)
        val stack = ArrayList<Pair<Char, Int>>()
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        for (i in text.indices) {
            if (!mask[i]) continue
            val c = text[i]
            when (c) {
                '(', '[', '{' -> stack.add(c to i)
                ')', ']', '}' -> {
                    val expected = pairs[c]!!
                    if (stack.isEmpty()) {
                        val (l, col) = Offsets.toLineColumn(text, i)
                        out.add(LocalIssue(l, col, l, col + 1, IssueSeverity.ERROR, "多余的右括号 '$c'"))
                    } else if (stack.last().first != expected) {
                        val (l, col) = Offsets.toLineColumn(text, i)
                        out.add(
                            LocalIssue(
                                l, col, l, col + 1, IssueSeverity.ERROR,
                                "括号不匹配：'$c' 与 '${stack.last().first}' 不配对"
                            )
                        )
                        stack.removeAt(stack.size - 1)
                    } else {
                        stack.removeAt(stack.size - 1)
                    }
                }
            }
        }
        stack.forEach { (ch, idx) ->
            val (l, col) = Offsets.toLineColumn(text, idx)
            out.add(LocalIssue(l, col, l, col + 1, IssueSeverity.ERROR, "括号 '$ch' 未闭合"))
        }
    }

    // ---------------------------------------------------------------- 缩进

    private fun checkIndentation(lang: IntelLanguage, text: String, out: MutableList<LocalIssue>) {
        val mask = Lexer.codeMask(text)
        val langLines = text.split('\n')
        val depths = braceDepths(text, mask)
        val unit = detectIndentUnit(langLines)

        var reported = 0
        var idx = 0
        var offset = 0
        for ((lineNo, rawLine) in langLines.withIndex()) {
            idx = offset
            offset += rawLine.length + 1
            val trimmed = rawLine.trimStart()
            if (trimmed.isEmpty()) continue
            // 行首处在字符串/注释内则跳过
            if (idx < mask.size && !mask[idx] && trimmed.first() != '/' && trimmed.first() != '*') continue
            val indentStr = rawLine.substring(0, rawLine.length - trimmed.length)
            val indent = indentStr.replace("\t", "    ").length

            val tabIntolerant = lang in setOf(
                IntelLanguage.KOTLIN, IntelLanguage.JAVA, IntelLanguage.DART,
                IntelLanguage.JAVASCRIPT, IntelLanguage.TYPESCRIPT, IntelLanguage.GROOVY, IntelLanguage.PYTHON
            )
            if (tabIntolerant && indentStr.contains('\t') && reported < 40) {
                out.add(
                    LocalIssue(
                        lineNo, 0, lineNo, indentStr.length, IssueSeverity.WARNING,
                        "本文件以空格缩进，这一行混用了 Tab"
                    )
                )
                reported++
            }

            // 与括号深度比对（跳过上一行的续行）
            val prev = langLines.getOrNull(lineNo - 1)?.trimEnd() ?: ""
            val isContinuation = prev.endsWith(",") || prev.endsWith("+") || prev.endsWith("&&") ||
                prev.endsWith("||") || prev.endsWith("(") || prev.endsWith(".") || prev.endsWith("=") ||
                prev.endsWith("->") || prev.endsWith("{")
            if (isContinuation) continue
            val depth = if (lineNo < depths.size) depths[lineNo] else 0
            val expected = depth * unit
            if (expected != indent && kotlin.math.abs(expected - indent) >= unit) {
                if (reported < 60) {
                    out.add(
                        LocalIssue(
                            lineNo, 0, lineNo, indent.coerceAtLeast(1), IssueSeverity.WARNING,
                            "缩进与代码块层级不一致（当前 $indent 空格，期望 $expected 空格）"
                        )
                    )
                    reported++
                }
            }
        }
    }

    private fun detectIndentUnit(lines: List<String>): Int {
        var two = 0
        var four = 0
        var tab = 0
        for (l in lines.take(400)) {
            val ws = l.takeWhile { it == ' ' || it == '\t' }
            if (ws.isEmpty() || l.trim().isEmpty()) continue
            if (ws.contains('\t')) tab++
            else when (ws.length % 4) {
                0 -> four++
                else -> if (ws.length % 2 == 0) two++ else four++
            }
        }
        return when {
            tab > 0 && tab >= two && tab >= four -> 4
            two > four -> 2
            else -> 4
        }
    }

    /** 每行行首的花括号深度（只在代码区计数）。 */
    private fun braceDepths(text: String, mask: BooleanArray): IntArray {
        val count = text.count { it == '\n' } + 1
        val depths = IntArray(count)
        var d = 0
        var line = 0
        for (i in text.indices) {
            val c = text[i]
            if (c == '\n') {
                line++
                if (line < count) depths[line] = d
            } else if (i < mask.size && mask[i]) {
                if (c == '{') d++
                else if (c == '}') d = (d - 1).coerceAtLeast(0)
            }
        }
        return depths
    }

    // ---------------------------------------------------------------- JSON

    private fun checkJson(text: String, out: MutableList<LocalIssue>) {
        try {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return
            if (trimmed.startsWith("[")) org.json.JSONArray(trimmed) else org.json.JSONObject(trimmed)
        } catch (t: Throwable) {
            val msg = t.message ?: "JSON 解析失败"
            val charIdx = Regex("character\\s+(\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
            if (charIdx != null && charIdx in 0..text.length) {
                val (l, c) = Offsets.toLineColumn(text, charIdx)
                out.add(LocalIssue(l, c, l, c + 1, IssueSeverity.ERROR, cleanJsonMessage(msg)))
            } else {
                out.add(LocalIssue(0, 0, 0, 1, IssueSeverity.ERROR, cleanJsonMessage(msg)))
            }
        }
    }

    private fun cleanJsonMessage(raw: String): String {
        val base = raw.substringBefore(" at character").substringBefore(" of ")
        return when {
            base.contains("Unterminated string") -> "字符串未闭合"
            base.contains("Unterminated object") -> "对象未闭合（缺少 }）"
            base.contains("Unterminated array") -> "数组未闭合（缺少 ]）"
            base.contains("Expected ':'") -> "键值之间缺少冒号"
            base.contains("Expected ','") -> "缺少逗号分隔"
            base.contains("Expected a string key") -> "键必须是双引号字符串"
            base.contains("Expected a value") -> "缺少值"
            base.contains("Expected '}'") -> "缺少右花括号"
            base.contains("Expected ']'") -> "缺少右方括号"
            base.contains("A JSONObject text must begin") -> "JSON 必须以 { 开始"
            base.contains("A JSONArray text must begin") -> "JSON 数组必须以 [ 开始"
            else -> base
        }
    }

    // ---------------------------------------------------------------- XML

    private val VOID_TAGS = setOf("br", "img", "meta", "link", "input", "hr", "area", "col", "embed", "source")

    private fun checkXml(text: String, out: MutableList<LocalIssue>) {
        val cleaned = text
            .replace(Regex("<!--[\\s\\S]*?-->"), { " ".repeat(it.value.length) })
            .replace(Regex("<\\?[\\s\\S]*?\\?>"), { " ".repeat(it.value.length) })
            .replace(Regex("<!\\[CDATA\\[[\\s\\S]*?\\]\\]>"), { " ".repeat(it.value.length) })
        val tagRe = Regex("<(/?)([A-Za-z_][A-Za-z0-9_:.-]*)([^>]*?)(/?)>")
        val stack = ArrayList<Pair<String, Int>>()
        tagRe.findAll(cleaned).forEach { m ->
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2]
            val selfClose = m.groupValues[4] == "/" || name.lowercase() in VOID_TAGS
            val start = m.range.first
            if (closing) {
                val top = stack.lastOrNull()
                if (top == null) {
                    val (l, c) = Offsets.toLineColumn(text, start)
                    out.add(LocalIssue(l, c, l, c + m.value.length, IssueSeverity.ERROR, "多余的结束标签 </$name>"))
                } else if (top.first != name) {
                    val (l, c) = Offsets.toLineColumn(text, start)
                    out.add(
                        LocalIssue(
                            l, c, l, c + m.value.length, IssueSeverity.ERROR,
                            "</$name> 与最近打开标签 <${top.first}> 不匹配"
                        )
                    )
                    stack.removeAt(stack.size - 1)
                } else {
                    stack.removeAt(stack.size - 1)
                }
            } else if (!selfClose) {
                stack.add(name to start)
            }
        }
        stack.forEach { (name, start) ->
            val (l, c) = Offsets.toLineColumn(text, start)
            out.add(LocalIssue(l, c, l, c + name.length + 1, IssueSeverity.ERROR, "标签 <$name> 未闭合"))
        }
    }

    // ---------------------------------------------------------------- properties / yaml / shell

    private fun checkProperties(text: String, out: MutableList<LocalIssue>) {
        text.split('\n').forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("[")) return@forEachIndexed
            if (!line.contains('=') && !line.contains(':') && !line.startsWith("include")) {
                out.add(
                    LocalIssue(
                        i, 0, i, raw.length.coerceAtLeast(1), IssueSeverity.WARNING,
                        "属性行缺少分隔符（应为 key=value）"
                    )
                )
            }
        }
    }

    private fun checkYaml(text: String, out: MutableList<LocalIssue>) {
        text.split('\n').forEachIndexed { i, raw ->
            val ws = raw.takeWhile { it == ' ' || it == '\t' }
            val isItem = raw.trim().startsWith("- ") || raw.trim().isNotEmpty()
            if (ws.contains('\t') && isItem) {
                out.add(LocalIssue(i, 0, i, ws.length, IssueSeverity.WARNING, "YAML 缩进不应使用 Tab"))
            }
        }
    }

    private fun checkShell(text: String, out: MutableList<LocalIssue>) {
        val lines = text.split('\n')
        if (lines.isNotEmpty()) {
            val first = lines.first()
            val scriptCode = lines.drop(1).count { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
            if (!first.startsWith("#!") && scriptCode > 3) {
                out.add(
                    LocalIssue(0, 0, 0, 1, IssueSeverity.INFO, "脚本建议添加 shebang（如 #!/usr/bin/env bash）")
                )
            }
        }
        lines.forEachIndexed { i, raw ->
            val t = raw.trim()
            if (t.startsWith("cd ") && !t.contains("||")) {
                out.add(LocalIssue(i, 0, i, raw.length, IssueSeverity.WARNING, "cd 失败时不会中断，建议写成 cd ... || exit 1"))
            }
        }
    }

    // ---------------------------------------------------------------- 代码卫生

    private fun checkCodeSmells(
        fileName: String,
        lang: IntelLanguage,
        text: String,
        out: MutableList<LocalIssue>
    ) {
        val lines = text.split('\n')

        // 未使用的导入 / 未使用的局部变量
        val index = DocumentIndex.build(fileName, text)
        val importSymbols = index.symbols.filter { it.detail.startsWith("导入：") }
        importSymbols.forEach { s ->
            val uses = Regex("\\b${Regex.escape(s.name)}\\b").findAll(text).count()
            if (uses <= 1) {
                out.add(
                    LocalIssue(
                        s.line, 0, s.line, lines.getOrNull(s.line)?.length ?: 1,
                        IssueSeverity.WARNING, "导入的 ${s.name} 未在本文件中使用"
                    )
                )
            }
        }
        index.symbols.filter {
            it.kind == CompletionKind.VARIABLE && !it.detail.startsWith("参数")
        }.forEach { s ->
            val raw = lines.getOrNull(s.line) ?: return@forEach
            // 只检查有明显的局部变量（有缩进或含 = 的赋值）
            if (!raw.contains('=')) return@forEach
            val uses = Regex("\\b${Regex.escape(s.name)}\\b").findAll(text).count()
            if (uses <= 1) {
                out.add(
                    LocalIssue(
                        s.line, raw.indexOf(s.name).coerceAtLeast(0), s.line, raw.length,
                        IssueSeverity.WARNING, "变量 ${s.name} 声明后未使用"
                    )
                )
            }
        }

        // 空 catch 块
        Regex("catch\\s*\\([^)]*\\)\\s*\\{\\s*\\}").findAll(text).forEach { m ->
            val (l, c) = Offsets.toLineColumn(text, m.range.first)
            out.add(LocalIssue(l, c, l, c + m.value.length, IssueSeverity.WARNING, "catch 块为空，异常被静默吞掉"))
        }

        // 疑似硬编码敏感信息
        Regex(
            "(?i)(password|passwd|secret|token|api_?key|access_?key|private_?key)\\s*[:=]\\s*[\"'][^\"']{6,}[\"']"
        ).findAll(text).forEach { m ->
            val (l, c) = Offsets.toLineColumn(text, m.range.first)
            val key = Regex("(?i)^(\\w+)").find(m.value)?.groupValues?.get(1) ?: "敏感字段"
            out.add(
                LocalIssue(
                    l, c, l, c + m.value.length, IssueSeverity.WARNING,
                    "疑似硬编码敏感信息（$key），建议改用环境变量或密钥库"
                )
            )
        }

        // 调试输出残留
        val debugPattern = when (lang) {
            IntelLanguage.KOTLIN -> "\\bprintln\\s*\\("
            IntelLanguage.JAVASCRIPT, IntelLanguage.TYPESCRIPT -> "console\\.(log|debug)\\s*\\("
            IntelLanguage.DART -> "\\b(print|debugPrint)\\s*\\("
            IntelLanguage.PYTHON -> "\\bprint\\s*\\("
            else -> null
        }
        if (debugPattern != null) {
            Regex(debugPattern).findAll(text).take(20).forEach { m ->
                val (l, c) = Offsets.toLineColumn(text, m.range.first)
                out.add(LocalIssue(l, c, l, c + m.value.length, IssueSeverity.INFO, "调试输出语句，发布前建议移除"))
            }
        }

        // 连续空行
        var blankRun = 0
        lines.forEachIndexed { i, raw ->
            if (raw.isBlank()) {
                blankRun++
                if (blankRun == 3) {
                    out.add(LocalIssue(i, 0, i, 1, IssueSeverity.INFO, "存在连续多余空行"))
                }
            } else {
                blankRun = 0
            }
        }
    }

    private fun checkMarkers(text: String, out: MutableList<LocalIssue>) {
        Regex("\\b(TODO|FIXME|XXX|HACK)\\b").findAll(text).take(50).forEach { m ->
            val (l, c) = Offsets.toLineColumn(text, m.range.first)
            out.add(LocalIssue(l, c, l, c + m.value.length, IssueSeverity.INFO, "${m.value} 待处理标记"))
        }
    }
}
