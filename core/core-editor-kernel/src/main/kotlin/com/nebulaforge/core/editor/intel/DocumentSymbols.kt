package com.nebulaforge.core.editor.intel

/** 从文档中提取出的一个符号。 */
data class DocSymbol(
    val name: String,
    val kind: CompletionKind,
    /** 变量类型 / 函数返回类型（可能为 null）。 */
    val type: String?,
    val detail: String,
    val line: Int,
    /** 所属类名（成员补全用；顶层符号为 null）。 */
    val containerClass: String? = null
)

/**
 * 当前文档的轻量索引：类、函数、属性、变量、导入。
 *
 * 只用正则 + 花括号/缩进层级，不做完整语法分析 —— 目标是"够快、够准、零依赖"。
 */
class DocumentIndex private constructor(
    val language: IntelLanguage,
    val symbols: List<DocSymbol>
) {

    val classNames: Set<String> = symbols.filter {
        it.kind == CompletionKind.CLASS && it.name.firstOrNull()?.isUpperCase() == true
    }.map { it.name }.toSet()

    /** 变量名 → 类型。 */
    val variableTypes: Map<String, String> = symbols
        .filter { it.kind == CompletionKind.VARIABLE || it.kind == CompletionKind.PROPERTY }
        .mapNotNull { s -> s.type?.let { t -> s.name to t } }
        .toMap()

    /** 函数名 → 返回类型。 */
    val functionReturnTypes: Map<String, String> = symbols
        .filter { it.kind == CompletionKind.FUNCTION }
        .mapNotNull { s -> s.type?.let { t -> s.name to t } }
        .toMap()

    /** 取某个类型上的成员（函数/属性）。 */
    fun membersOf(typeName: String): List<DocSymbol> {
        val t = TypeMembers.normalize(typeName)
        if (t.isEmpty()) return emptyList()
        return symbols.filter { it.containerClass != null && TypeMembers.normalize(it.containerClass!!) == t }
    }

    companion object {

        fun build(fileName: String, text: String): DocumentIndex {
            val lang = IntelLanguages.of(fileName)
            val symbols = when (lang) {
                IntelLanguage.PYTHON -> parseIndentLanguage(lang, text)
                IntelLanguage.KOTLIN, IntelLanguage.JAVA, IntelLanguage.GROOVY,
                IntelLanguage.DART, IntelLanguage.JAVASCRIPT, IntelLanguage.TYPESCRIPT,
                IntelLanguage.C, IntelLanguage.CPP -> parseBraceLanguage(lang, text)
                IntelLanguage.SHELL -> parseShell(text)
                else -> parseLoose(lang, text)
            }
            return DocumentIndex(lang, symbols)
        }

        // ------------------------------------------------------------ 通用工具

        private fun lines(text: String): List<String> = text.split('\n')

        /** 每个行首所处的花括号深度（只在代码区计数）。 */
        private fun depthsAtLineStart(text: String): IntArray {
            val mask = Lexer.codeMask(text)
            val depth = IntArray(1 + text.count { it == '\n' })
            var d = 0
            var line = 0
            depth[0] = 0
            for (i in text.indices) {
                val c = text[i]
                if (c == '\n') {
                    line++
                    depth[line] = d
                } else if (mask[i]) {
                    if (c == '{') d++
                    else if (c == '}') d = (d - 1).coerceAtLeast(0)
                }
            }
            return depth
        }

        private fun firstGroup(regex: Regex, line: String): MatchResult? = regex.find(line)

        // ------------------------------------------------------------ 花括号语言

        private val CLASS_RE = Regex(
            "(?:^|\\s)(?:public|private|protected|internal|abstract|final|sealed|data|open|" +
                "static|inner|enum|record|annotation|value|expect|actual|base|mixin)\\s+" +
                "(class|interface|object|enum\\s+class|data\\s+class|sealed\\s+class|trait|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)"
        )
        private val KT_FUN_RE = Regex(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:public|private|protected|internal|open|abstract|" +
                "override|final|suspend|inline|operator|infix|external|tailrec|actual|expect|fun)*\\s*" +
                "(?:fun)\\s+([A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*)\\s*\\("
        )
        private val KT_VAL_RE = Regex(
            "^\\s*(?:@\\w+\\s*)*(?:public|private|protected|internal|const|lateinit|override|final|open|" +
                "static|abstract|value|actual|expect)*\\s*(val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_.<>,?\\[\\] ]*?))?\\s*(?:=|$|\\bset\\b|\\bget\\b)"
        )
        private val JAVA_FIELD_RE = Regex(
            "^\\s*(?:public|private|protected|static|final|volatile|transient)\\s+(?:static\\s+|final\\s+)*" +
                "([A-Za-z_][A-Za-z0-9_.<>,?\\[\\]]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;|,)"
        )
        private val DART_DECL_RE = Regex(
            "^\\s*(?:final|const|late|static|var|dynamic|\\w+[?<][^=]*?)?\\s*" +
                "([A-Za-z_][A-Za-z0-9_]*(?:<[^>]*>)?)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;)"
        )
        private val FUNC_GENERIC_RE = Regex(
            "^\\s*(?:public|private|protected|static|final|async|\\w+)*\\s*" +
                "([A-Za-z_][A-Za-z0-9_]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\("
        )
        private val PARAM_RE = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*:\\s*([A-Za-z_][A-Za-z0-9_.<>,?\\[\\]]*)")

        private fun parseBraceLanguage(lang: IntelLanguage, text: String): List<DocSymbol> {
            val out = ArrayList<DocSymbol>()
            val src = lines(text)
            val depths = depthsAtLineStart(text)
            val classStack = ArrayList<Pair<Int, String>>()

            src.forEachIndexed { index, rawLine ->
                val depth = if (index < depths.size) depths[index] else 0
                while (classStack.isNotEmpty() && depth <= classStack.last().first) classStack.removeAt(classStack.size - 1)
                val container = classStack.lastOrNull()?.second
                val line = rawLine.trimEnd()

                // 导入
                val imp = Regex("^\\s*(?:from\\s+([\\w.]+)\\s+)?import\\s+([\\w.]+)(?:\\.\\*|\\*)?\\s*$").find(line)
                if (imp != null) {
                    val full = imp.groupValues[2]
                    val simple = full.substringAfterLast('.')
                    if (simple.isNotEmpty() && simple != "*") {
                        out.add(DocSymbol(simple, CompletionKind.CLASS, null, "导入：$full", index, null))
                    }
                    return@forEachIndexed
                }

                // 类 / 接口 / 对象
                val cls = CLASS_RE.find(line)
                if (cls != null) {
                    val name = cls.groupValues[2]
                    val kw = cls.groupValues[1].replace(Regex("\\s+"), " ")
                    out.add(DocSymbol(name, CompletionKind.CLASS, name, "$kw 声明", index, container))
                    // 主构造参数：class Foo(val a: Int, val b: String)
                    val headEnd = line.indexOf('{').let { if (it < 0) line.length else it }
                    val head = line.substring(cls.range.last + 1, headEnd)
                    PARAM_RE.findAll(head).forEach { p ->
                        val pname = p.groupValues[1]
                        val ptype = p.groupValues[2]
                        if (pname != "val" && pname != "var") {
                            out.add(DocSymbol(pname, CompletionKind.PROPERTY, ptype, "主构造参数：$ptype", index, name))
                        }
                    }
                    classStack.add(depth to name)
                    return@forEachIndexed
                }

                // Kotlin / Dart 函数
                val fn = KT_FUN_RE.find(line) ?: run {
                    if (lang == IntelLanguage.DART || lang == IntelLanguage.JAVASCRIPT || lang == IntelLanguage.TYPESCRIPT) {
                        FUNC_GENERIC_RE.find(line)
                    } else null
                }
                if (fn != null) {
                    val rawName = fn.groupValues[1]
                    val name = rawName.substringAfterLast('.')
                    val owner = if (rawName.contains('.')) rawName.substringBeforeLast('.') else container
                    // 返回类型：`) : Type` 或 Dart/JS 的 `Type name(`
                    val after = line.substring(fn.range.last)
                    val ret = Regex("^\\s*\\)?\\s*:\\s*([A-Za-z_][A-Za-z0-9_.<>,?\\[\\]]*)").find(after)?.groupValues?.get(1)
                    // 参数（Kotlin/Dart 的 name: Type）
                    PARAM_RE.findAll(line.substringAfter('(').substringBeforeLast(')')).forEach { p ->
                        val pname = p.groupValues[1]
                        if (pname != "val" && pname != "var") {
                            out.add(DocSymbol(pname, CompletionKind.VARIABLE, p.groupValues[2], "参数：${p.groupValues[2]}", index, null))
                        }
                    }
                    out.add(DocSymbol(name, CompletionKind.FUNCTION, ret, "fun $name", index, owner))
                    return@forEachIndexed
                }

                // Kotlin 属性 / 局部变量
                val kv = KT_VAL_RE.find(line)
                if (kv != null) {
                    val name = kv.groupValues[2]
                    val declared = kv.groupValues[3].takeIf { it.isNotBlank() }
                    val init = line.substringAfter('=', "").trim()
                    val type = declared ?: TypeMembers.inferFromInitializer(init)
                    out.add(
                        DocSymbol(
                            name,
                            if (kv.groupValues[1] == "val" && container != null) CompletionKind.PROPERTY else CompletionKind.VARIABLE,
                            type, if (type != null) "${kv.groupValues[1]} $name: $type" else "${kv.groupValues[1]} $name",
                            index, container
                        )
                    )
                    return@forEachIndexed
                }

                // Java 字段与方法
                if (lang == IntelLanguage.JAVA) {
                    val jf = JAVA_FIELD_RE.find(line)
                    if (jf != null && !line.contains('(')) {
                        out.add(
                            DocSymbol(
                                jf.groupValues[2], CompletionKind.PROPERTY, jf.groupValues[1],
                                "字段：${jf.groupValues[1]}", index, container
                            )
                        )
                        return@forEachIndexed
                    }
                }

                // Dart 声明
                if (lang == IntelLanguage.DART) {
                    val dd = DART_DECL_RE.find(line)
                    if (dd != null && dd.groupValues[1] !in setOf("return", "import", "export", "part", "class")) {
                        val t = dd.groupValues[1]
                        out.add(
                            DocSymbol(
                                dd.groupValues[2], CompletionKind.VARIABLE, t,
                                "$t ${dd.groupValues[2]}", index, container
                            )
                        )
                    }
                }
            }
            return out
        }

        // ------------------------------------------------------------ 缩进语言（Python）

        private val PY_DEF_RE = Regex("^(\\s*)(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
        private val PY_CLASS_RE = Regex("^(\\s*)class\\s+([A-Za-z_][A-Za-z0-9_]*)")
        private val PY_ASSIGN_RE = Regex("^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_.\\[\\]]*))?\\s*=")

        private fun parseIndentLanguage(lang: IntelLanguage, text: String): List<DocSymbol> {
            val out = ArrayList<DocSymbol>()
            val classStack = ArrayList<Pair<Int, String>>()
            lines(text).forEachIndexed { index, rawLine ->
                val line = rawLine
                val indent = line.takeWhile { it == ' ' || it == '\t' }.replace("\t", "    ").length
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
                while (classStack.isNotEmpty() && indent <= classStack.last().first) classStack.removeAt(classStack.size - 1)
                val container = classStack.lastOrNull()?.second

                val imp = Regex("^(?:from\\s+([\\w.]+)\\s+)?import\\s+([\\w.]+)").find(trimmed)
                if (imp != null) {
                    val simple = imp.groupValues[2].substringAfterLast('.')
                    if (simple != "*") {
                        out.add(DocSymbol(simple, CompletionKind.CLASS, null, "导入：${imp.groupValues[2]}", index, null))
                    }
                    return@forEachIndexed
                }
                val cls = PY_CLASS_RE.find(line)
                if (cls != null) {
                    val name = cls.groupValues[2]
                    out.add(DocSymbol(name, CompletionKind.CLASS, name, "class 声明", index, container))
                    classStack.add(indent to name)
                    return@forEachIndexed
                }
                val fn = PY_DEF_RE.find(line)
                if (fn != null) {
                    val name = fn.groupValues[2]
                    if (name != "__init__") {
                        out.add(DocSymbol(name, CompletionKind.FUNCTION, null, "def $name", index, container))
                    }
                    // 参数：name（Python 无类型标注时给 None）
                    val params = line.substringAfter('(').substringBeforeLast(')')
                    Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([A-Za-z_][A-Za-z0-9_.\\[\\]]*))?").findAll(params)
                        .forEach { p ->
                            val pn = p.groupValues[1]
                            val pt = p.groupValues[2].takeIf { it.isNotBlank() }
                            if (pn !in setOf("self", "cls") && pn.isNotEmpty()) {
                                out.add(DocSymbol(pn, CompletionKind.VARIABLE, pt, "参数：$pn", index, null))
                            }
                        }
                    return@forEachIndexed
                }
                val assign = PY_ASSIGN_RE.find(line)
                if (assign != null) {
                    val name = assign.groupValues[2]
                    val declared = assign.groupValues[3].takeIf { it.isNotBlank() }
                    val init = line.substringAfter('=', "")
                    val type = declared ?: when {
                        init.trimStart().startsWith("[") -> "list"
                        init.trimStart().startsWith("{") -> "dict"
                        init.trimStart().startsWith("\"") || init.trimStart().startsWith("'") -> "str"
                        init.trimStart().startsWith("set(") -> "set"
                        else -> null
                    }
                    if (name != "self") {
                        out.add(
                            DocSymbol(
                                name,
                                if (indent == 0) CompletionKind.VARIABLE else CompletionKind.VARIABLE,
                                type, "变量：$name", index, if (indent == 0) null else container
                            )
                        )
                    }
                }
            }
            return out
        }

        // ------------------------------------------------------------ Shell / 其它

        private fun parseShell(text: String): List<DocSymbol> {
            val out = ArrayList<DocSymbol>()
            lines(text).forEachIndexed { index, raw ->
                val line = raw.trim()
                Regex("^(?:function\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*\\)").find(line)?.let {
                    out.add(DocSymbol(it.groupValues[1], CompletionKind.FUNCTION, null, "函数 ${it.groupValues[1]}", index, null))
                }
                Regex("^(?:export\\s+)?([A-Z_][A-Z0-9_]*)\\s*=").find(line)?.let {
                    out.add(DocSymbol(it.groupValues[1], CompletionKind.CONSTANT, null, "变量 ${it.groupValues[1]}", index, null))
                }
            }
            return out
        }

        private fun parseLoose(lang: IntelLanguage, text: String): List<DocSymbol> {
            val out = ArrayList<DocSymbol>()
            lines(text).forEachIndexed { index, raw ->
                val line = raw.trim()
                Regex("^([A-Za-z_][A-Za-z0-9_]*)\\s*[:=]").find(line)?.let {
                    out.add(DocSymbol(it.groupValues[1], CompletionKind.VARIABLE, null, "键 ${it.groupValues[1]}", index, null))
                }
            }
            return out
        }
    }
}
