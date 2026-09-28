package com.nebulaforge.core.editor.intel

/** 补全项类型（用于图标/排序，不依赖任何 IDE UI）。 */
enum class CompletionKind {
    KEYWORD, FUNCTION, METHOD, CLASS, PROPERTY, VARIABLE, CONSTANT, SNIPPET, MODULE, TAG
}

/** 一条补全建议。 */
data class CompletionSuggestion(
    val label: String,
    val insertText: String,
    val detail: String,
    val kind: CompletionKind,
    /** 越小越靠前。 */
    val weight: Int
)

/** 静态语言知识条目（关键字 / 常用函数 / 片段）。 */
data class ApiEntry(
    val name: String,
    val insert: String,
    val detail: String,
    val kind: CompletionKind = CompletionKind.FUNCTION
)

/** 诊断严重级别。 */
enum class IssueSeverity { ERROR, WARNING, INFO }

/** 一条本地诊断结果（行列均为 0 基）。 */
data class LocalIssue(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    val severity: IssueSeverity,
    val message: String,
    val source: String = "本地检查"
)

/** 语言分类（按文件扩展名判定）。 */
enum class IntelLanguage {
    KOTLIN, GROOVY, JAVA, DART, XML, JSON, YAML, PROPERTIES,
    PYTHON, JAVASCRIPT, TYPESCRIPT, SHELL, C, CPP, RUST, GO, SQL, MARKDOWN, PLAIN
}

object IntelLanguages {

    fun of(fileName: String): IntelLanguage = of(fileName, null)

    fun of(fileName: String, extension: String?): IntelLanguage {
        val name = fileName.lowercase()
        val ext = (extension ?: name.substringAfterLast('.', "")).lowercase()
        // 插件（含 VSIX 转换来的声明式插件）贡献的语言关联优先于内置表：
        // 只在它确实能映射到内置语言时才生效，映射不到就保持内置判定 ——
        // 否则装一个「自定义语言」插件会把原本能高亮的 .kt/.py 打成 PLAIN，属于负优化。
        DeclarativeContributions.languageIdFor(name, ext)?.let { declared ->
            val mapped = ofLanguageId(declared)
            if (mapped != IntelLanguage.PLAIN) return mapped
        }
        return when (ext) {
            "kt", "kts" -> IntelLanguage.KOTLIN
            "gradle", "groovy", "gvy" -> IntelLanguage.GROOVY
            "java" -> IntelLanguage.JAVA
            "dart" -> IntelLanguage.DART
            "xml", "html", "htm", "svg", "plist" -> IntelLanguage.XML
            "json" -> IntelLanguage.JSON
            "yaml", "yml" -> IntelLanguage.YAML
            "properties", "ini", "cfg", "env" -> IntelLanguage.PROPERTIES
            "py" -> IntelLanguage.PYTHON
            "js", "jsx", "mjs", "cjs" -> IntelLanguage.JAVASCRIPT
            "ts", "tsx" -> IntelLanguage.TYPESCRIPT
            "sh", "bash", "zsh" -> IntelLanguage.SHELL
            "c", "h" -> IntelLanguage.C
            "cc", "cpp", "cxx", "hpp", "hh" -> IntelLanguage.CPP
            "rs" -> IntelLanguage.RUST
            "go" -> IntelLanguage.GO
            "sql" -> IntelLanguage.SQL
            "md", "markdown" -> IntelLanguage.MARKDOWN
            else -> IntelLanguage.PLAIN
        }
    }

    /**
     * 把插件声明的语言 id（VSCode 风格：`kotlin`、`python`、`typescriptreact`…）归一到内置语言。
     *
     * 判定用「包含关键字」而不是精确匹配：外部生态的语言 id 命名极其发散
     * （`kotlin`/`kotlin-lang`/`jetbrains.kotlin`/`source.kotlin` 都指 Kotlin），
     * 精确匹配会让绝大多数真实插件失配。顺序上 `typescript`/`javascript` 必须早于 `java`，
     * 否则 `javascript` 会被 `java` 抢先匹配。
     */
    fun ofLanguageId(languageId: String): IntelLanguage {
        val id = languageId.lowercase().substringAfterLast('/').trim()
        if (id.isEmpty()) return IntelLanguage.PLAIN
        return when {
            id.contains("kotlin") || id == "kt" || id == "kts" -> IntelLanguage.KOTLIN
            id.contains("groovy") || id.contains("gradle") -> IntelLanguage.GROOVY
            id.contains("typescript") || id == "ts" || id == "tsx" -> IntelLanguage.TYPESCRIPT
            id.contains("javascript") || id == "js" || id == "jsx" || id == "mjs" || id == "cjs" ||
                id.contains("node") -> IntelLanguage.JAVASCRIPT
            id.contains("java") -> IntelLanguage.JAVA
            id.contains("dart") || id.contains("flutter") -> IntelLanguage.DART
            id.contains("python") || id == "py" -> IntelLanguage.PYTHON
            id.contains("shell") || id.contains("bash") || id.contains("zsh") ||
                id == "sh" || id == "shellscript" -> IntelLanguage.SHELL
            id.contains("rust") -> IntelLanguage.RUST
            id == "go" || id.contains("golang") -> IntelLanguage.GO
            id == "c" -> IntelLanguage.C
            id.contains("cpp") || id.contains("c++") -> IntelLanguage.CPP
            id.contains("xml") || id.contains("html") || id.contains("svg") -> IntelLanguage.XML
            id.contains("json") -> IntelLanguage.JSON
            id.contains("yaml") || id == "yml" -> IntelLanguage.YAML
            id.contains("properties") || id.contains("ini") -> IntelLanguage.PROPERTIES
            id.contains("sql") -> IntelLanguage.SQL
            id.contains("markdown") || id == "md" -> IntelLanguage.MARKDOWN
            else -> IntelLanguage.PLAIN
        }
    }
}

/** 通用文本工具：标识符扫描、注释/字符串剔除等，供补全与诊断复用。 */
internal object Lexer {

    fun isIdentStart(c: Char) = c == '_' || c == '$' || c.isLetter()
    fun isIdentPart(c: Char) = c == '_' || c == '$' || c.isLetterOrDigit()

    /** 取 [index] 之前连续标识符（即当前正在输入的词）。 */
    fun prefixAt(text: String, index: Int): String {
        var i = index
        while (i > 0 && isIdentPart(text[i - 1])) i--
        return text.substring(i, index)
    }

    /** 取 [index] 之前被点号访问的接收者名（如 `list.ma` 的 `list`），无则 null。 */
    fun receiverAt(text: String, index: Int): String? {
        var i = index
        // 先跳过当前前缀
        while (i > 0 && isIdentPart(text[i - 1])) i--
        if (i < 2) return null
        // 允许 ?. 与 .
        var dotCount = 0
        if (i >= 2 && text[i - 1] == '.' && text[i - 2] == '?') dotCount = 2
        else if (text[i - 1] == '.') dotCount = 1
        if (dotCount == 0) return null
        val end = i - dotCount
        var j = end
        while (j > 0 && isIdentPart(text[j - 1])) j--
        if (j == end) return null
        return text.substring(j, end)
    }

    /**
     * 逐字符扫描，产出"代码字符"位置集合之外的信息：
     * 只在字符串/注释之外的字符才参与括号配对检查。
     */
    fun codeMask(text: String): BooleanArray {
        val mask = BooleanArray(text.length) { true }
        var i = 0
        var state = 0 // 0=code 1=lineComment 2=blockComment 3=string 4=rawString 5=char
        while (i < text.length) {
            val c = text[i]
            when (state) {
                0 -> when {
                    c == '/' && i + 1 < text.length && text[i + 1] == '/' -> { mask[i] = false; state = 1; i++ }
                    c == '/' && i + 1 < text.length && text[i + 1] == '*' -> { mask[i] = false; mask[i + 1] = false; state = 2; i += 2; continue }
                    c == '"' && i + 2 < text.length && text[i + 1] == '"' && text[i + 2] == '"' -> { mask[i] = false; state = 4; i += 3; continue }
                    c == '"' -> { mask[i] = false; state = 3 }
                    c == '\'' -> { mask[i] = false; state = 5 }
                    else -> {}
                }
                1 -> { mask[i] = false; if (c == '\n') state = 0 }
                2 -> { mask[i] = false; if (c == '*' && i + 1 < text.length && text[i + 1] == '/') { mask[i + 1] = false; state = 0; i += 2; continue } }
                3 -> {
                    mask[i] = false
                    if (c == '\\') { if (i + 1 < text.length) mask[i + 1] = false; i += 2; continue }
                    if (c == '\n') state = 0 else if (c == '"') state = 0
                }
                4 -> {
                    mask[i] = false
                    if (c == '"' && i + 2 < text.length && text[i + 1] == '"' && text[i + 2] == '"') { mask[i + 1] = false; mask[i + 2] = false; state = 0; i += 3; continue }
                }
                5 -> {
                    mask[i] = false
                    if (c == '\\') { if (i + 1 < text.length) mask[i + 1] = false; i += 2; continue }
                    if (c == '\n') state = 0 else if (c == '\'') state = 0
                }
            }
            i++
        }
        return mask
    }
}

/** 把绝对字符偏移换算成行列（0 基）。 */
internal object Offsets {
    fun toLineColumn(text: String, index: Int): Pair<Int, Int> {
        var line = 0
        var lastLineStart = 0
        var i = 0
        val end = index.coerceIn(0, text.length)
        while (i < end) {
            if (text[i] == '\n') { line++; lastLineStart = i + 1 }
            i++
        }
        return line to (end - lastLineStart)
    }
}
