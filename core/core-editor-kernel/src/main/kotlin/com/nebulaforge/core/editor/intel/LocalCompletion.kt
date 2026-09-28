package com.nebulaforge.core.editor.intel

/** 补全请求上下文。 */
data class CompletionContext(
    val fileName: String,
    val text: String,
    /** 光标所在行（0 基）。 */
    val line: Int,
    /** 光标所在列（0 基，按字符计）。 */
    val column: Int
)

/**
 * 本地补全引擎：不依赖 AI / 网络 / 语言服务器。
 *
 * 候选来源四层：
 * 1. 当前文档符号（类、函数、属性、局部变量、参数）—— 权重最高；
 * 2. 类型成员（`obj.` 之后），类型来自显式标注或初始化表达式推断；
 * 3. 语言关键字；
 * 4. 常用标准库 / 框架 API 与代码片段。
 */
object LocalCompletion {

    private val KOTLIN_ANNOTATIONS = listOf(
        "Override", "Composable", "Preview", "JvmStatic", "JvmField", "JvmOverloads", "JvmName",
        "Suppress", "Deprecated", "Test", "Before", "After", "Inject", "Provides", "Serializable",
        "Parcelize", "RequiresApi", "NonNull", "Nullable", "Volatile", "Synchronized", "Throws"
    )

    fun suggest(
        ctx: CompletionContext,
        limit: Int = 80,
        /** 工作区跨文件符号（由 [WorkspaceSymbolIndex] 在后台线程产出）。 */
        workspace: List<WorkspaceSymbol> = emptyList()
    ): List<CompletionSuggestion> {
        val text = ctx.text
        val textBefore = textBefore(text, ctx.line, ctx.column)
        val lang = IntelLanguages.of(ctx.fileName)

        // `@` 注解补全
        if (textBefore.endsWith("@")) {
            return KOTLIN_ANNOTATIONS.map {
                CompletionSuggestion("@" + it, "@" + it, "注解", CompletionKind.SNIPPET, 0)
            }.filter { it.label.length > 1 }.sortedBy { it.label }.take(limit)
        }

        val prefix = Lexer.prefixAt(textBefore, textBefore.length)
        val index = DocumentIndex.build(ctx.fileName, text)

        val receiver = Lexer.receiverAt(textBefore, textBefore.length) ?: callReceiverOf(textBefore)

        val candidates = ArrayList<CompletionSuggestion>(256)

        if (receiver != null) {
            addMemberCandidates(candidates, receiver, index, lang)
        } else {
            addPlainCandidates(candidates, index, lang, prefix, textBefore)
            addWorkspaceCandidates(candidates, workspace)
        }

        return rank(candidates, prefix, limit)
    }

    /**
     * 合入「同工作区其他文件」的符号。
     *
     * 权重 [WorkspaceSymbol.weight]（8~9）**低于**当前文档符号（0~3）而高于关键字（20）：
     * 写代码时本文件内的名字永远是最可能想用的，跨文件符号其次是关键字/片段。
     * 去重与前缀过滤交给 [rank] 统一处理（同 label 保留低权重项）。
     */
    private fun addWorkspaceCandidates(out: MutableList<CompletionSuggestion>, workspace: List<WorkspaceSymbol>) {
        if (workspace.isEmpty()) return
        val seen = HashSet<String>(out.size * 2)
        out.forEach { seen.add(it.label) }
        workspace.forEach { s ->
            if (!seen.add(s.name)) return@forEach
            out.add(CompletionSuggestion(s.name, s.name, s.detail, s.kind, s.weight))
        }
    }

    // ---------------------------------------------------------------- 成员补全

    private fun addMemberCandidates(
        out: MutableList<CompletionSuggestion>,
        rawReceiver: String,
        index: DocumentIndex,
        lang: IntelLanguage
    ) {
        val receiver = rawReceiver.removePrefix("CALL:")
        val isCall = rawReceiver.startsWith("CALL:")

        val type: String? = when {
            receiver == "this" || receiver == "self" -> currentClassName(index)
            receiver == "it" -> null
            isCall -> index.functionReturnTypes[receiver] ?: TypeMembers.inferFromInitializer(receiver)
            else -> index.variableTypes[receiver]
                ?: index.functionReturnTypes[receiver]
                ?: receiver.takeIf { it.firstOrNull()?.isUpperCase() == true }
        }

        if (type == null) {
            // 无法解析类型：给出文档内所有类的成员 + 通用 API，避免"什么都不提示"
            index.symbols.filter { it.containerClass != null }.forEach { s ->
                out.add(
                    CompletionSuggestion(
                        s.name, s.name,
                        s.detail + (s.containerClass?.let { " · $it" } ?: ""),
                        s.kind, 40
                    )
                )
            }
            LanguageApis.commonApis(lang).forEach { a ->
                out.add(CompletionSuggestion(a.name, a.insert, a.detail + " · 通用", a.kind, 60))
            }
            LanguageIntelligence.keywords(lang).forEach { k ->
                out.add(CompletionSuggestion(k, k, "关键字", CompletionKind.KEYWORD, 80))
            }
            return
        }

        // 文档内该类型声明的成员（最相关）
        index.membersOf(type).forEach { s ->
            out.add(
                CompletionSuggestion(
                    s.name, s.name,
                    s.detail + (s.type?.let { " → $it" } ?: ""),
                    s.kind, 0
                )
            )
        }
        // 框架/标准库成员表
        TypeMembers.members(type, lang).forEach { a ->
            out.add(CompletionSuggestion(a.name, a.insert, a.detail + " · $type", a.kind, 10))
        }
        // 类构造参数（`Foo(` 之后）暂不处理，保持成员语义清晰
    }

    // ---------------------------------------------------------------- 普通补全

    private fun addPlainCandidates(
        out: MutableList<CompletionSuggestion>,
        index: DocumentIndex,
        lang: IntelLanguage,
        prefix: String,
        textBefore: String
    ) {
        // 1. 文档符号
        index.symbols.forEach { s ->
            val weight = when (s.kind) {
                CompletionKind.VARIABLE -> 0
                CompletionKind.FUNCTION -> 1
                CompletionKind.PROPERTY -> 1
                CompletionKind.CLASS -> 1
                else -> 3
            }
            val detail = buildString {
                append(s.detail)
                if (s.containerClass != null) append(" · ").append(s.containerClass)
            }
            out.add(CompletionSuggestion(s.name, s.name, detail, s.kind, weight))
        }

        // 2. 关键字（赋值场景下 `val` 等排前）
        LanguageIntelligence.keywords(lang).forEach { k ->
            out.add(CompletionSuggestion(k, k, "关键字", CompletionKind.KEYWORD, 20))
        }
        LanguageIntelligence.structuralKeywords(lang).forEach { k ->
            out.add(CompletionSuggestion(k, k, "结构关键字", CompletionKind.KEYWORD, 22))
        }

        // 3. 常用 API
        LanguageApis.commonApis(lang).forEach { a ->
            out.add(CompletionSuggestion(a.name, a.insert, a.detail, a.kind, 30))
        }

        // 4. 片段：行首或空行时优先展示
        val atLineStart = textBefore.substringAfterLast('\n').trim().isEmpty()
        val firstNonSpace = textBefore.substringAfterLast('\n').trimStart()
        val snippetWeight = if (atLineStart || firstNonSpace.isEmpty()) 5 else 45
        snippetsOf(lang).forEach { s ->
            out.add(CompletionSuggestion(s.name, s.insert, s.detail, CompletionKind.SNIPPET, snippetWeight))
        }

        // 4.1 插件（含 VSIX 转换来的声明式插件）贡献的片段。
        //     只在用户已经输入了前缀时才挂载：插件可能带来成百上千条片段，
        //     无前缀时全量塞进去会把关键字/内置 API 挤出候选窗（limit 只有 80），
        //     而「敲前缀 → 片段出现」才是 VS Code 里片段的真实用法。
        if (prefix.isNotEmpty()) {
            DeclarativeContributions.snippetsFor(lang).forEach { s ->
                val detail = buildString {
                    append(s.name)
                    s.description?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    append(" · ").append(s.pluginId)
                }
                s.prefix.forEach { trigger ->
                    if (trigger.isBlank()) return@forEach
                    out.add(
                        CompletionSuggestion(
                            trigger,
                            SnippetText.expand(s.body),
                            detail,
                            CompletionKind.SNIPPET,
                            8
                        )
                    )
                }
            }
        }

        // 5. 高频内置类型名
        BUILTIN_TYPES.forEach { t ->
            out.add(CompletionSuggestion(t, t, "类型", CompletionKind.CLASS, 35))
        }

        // 6. `import ` 之后给常见包名（Kotlin/Groovy）
        val lineText = textBefore.substringAfterLast('\n')
        if (Regex("^\\s*import\\s+[\\w.]*$").containsMatchIn(lineText)) {
            COMMON_PACKAGES.forEach { p ->
                out.add(CompletionSuggestion(p, p, "包", CompletionKind.MODULE, 0))
            }
        }
    }

    private fun snippetsOf(lang: IntelLanguage): List<ApiEntry> = LanguageSnippets.of(lang)

    // ---------------------------------------------------------------- 排序与过滤

    private fun rank(candidates: List<CompletionSuggestion>, prefix: String, limit: Int): List<CompletionSuggestion> {
        val byLabel = LinkedHashMap<String, CompletionSuggestion>()
        for (c in candidates) {
            val existing = byLabel[c.label]
            if (existing == null || c.weight < existing.weight) byLabel[c.label] = c
        }
        val all = byLabel.values
        val p = prefix.lowercase()

        fun matchScore(c: CompletionSuggestion): Int? {
            val label = c.label.lowercase()
            if (p.isEmpty()) return c.weight
            return when {
                label == p -> c.weight - 10
                label.startsWith(p) -> c.weight
                // 驼峰缩写匹配：fto → forEachToString
                acronymMatch(label, p) -> c.weight + 12
                label.contains(p) -> c.weight + 25
                else -> null
            }
        }

        return all.mapNotNull { c -> matchScore(c)?.let { c to it } }
            .sortedWith(compareBy({ it.second }, { it.first.label.length }, { it.first.label }))
            .map { it.first }
            .take(limit)
    }

    /** 驼峰首字母缩写匹配（如 `sb` 匹配 `StringBuilder`）。 */
    private fun acronymMatch(label: String, prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        var pi = 0
        for (ch in label) {
            if (pi < prefix.length && ch == prefix[pi]) pi++
            if (pi == prefix.length) return true
        }
        return false
    }

    // ---------------------------------------------------------------- 辅助

    private fun textBefore(text: String, line: Int, column: Int): String {
        if (line <= 0) return text.take(column.coerceAtLeast(0))
        var idx = 0
        var currentLine = 0
        while (idx < text.length && currentLine < line) {
            if (text[idx] == '\n') currentLine++
            idx++
        }
        val lineEnd = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
        return text.substring(0, (idx + column).coerceAtMost(lineEnd))
    }

    private fun currentClassName(index: DocumentIndex): String? =
        index.symbols.filter { it.kind == CompletionKind.CLASS && it.name.firstOrNull()?.isUpperCase() == true }
            .maxByOrNull { it.line }?.name

    /** `foo().` 形式：取调用表达式对应的函数名。 */
    private fun callReceiverOf(textBefore: String): String? {
        var i = textBefore.length
        while (i > 0 && Lexer.isIdentPart(textBefore[i - 1])) i--
        if (i == 0 || textBefore[i - 1] != '.') return null
        i--
        if (i == 0 || textBefore[i - 1] != ')') return null
        var depth = 0
        var j = i - 1
        while (j >= 0) {
            val c = textBefore[j]
            if (c == ')') depth++
            else if (c == '(') {
                depth--
                if (depth == 0) break
            }
            j--
        }
        if (j <= 0) return null
        var k = j
        while (k > 0 && Lexer.isIdentPart(textBefore[k - 1])) k--
        if (k == j) return null
        return "CALL:" + textBefore.substring(k, j)
    }

    private val BUILTIN_TYPES = listOf(
        "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Any", "Unit",
        "List", "MutableList", "Map", "MutableMap", "Set", "MutableSet", "Array", "Pair", "Triple",
        "File", "StringBuilder", "Regex", "Thread", "Exception", "RuntimeException",
        "Context", "Intent", "Bundle", "Uri", "Toast", "Log", "Application", "Activity",
        "View", "TextView", "Button", "Fragment", "JSONObject", "JSONArray", "CoroutineScope",
        "Flow", "StateFlow", "State", "Modifier"
    )

    private val COMMON_PACKAGES = listOf(
        "java.io", "java.util", "java.nio.file", "kotlin.io", "kotlin.collections",
        "android.content", "android.os", "android.view", "android.widget", "android.util",
        "androidx.compose.runtime", "androidx.compose.material3", "androidx.compose.ui",
        "androidx.compose.foundation.layout", "kotlinx.coroutines", "kotlinx.coroutines.flow",
        "com.nebulaforge.core.editor"
    )
}
