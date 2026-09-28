package com.nebulaforge.core.editor.intel

/**
 * 离线语言知识库：关键字、常用标准库函数、常用框架 API、代码片段。
 *
 * 刻意不依赖任何语言服务器或 AI —— 纯静态表 + 文档内符号索引 + 轻量类型推断
 * （见 [DocumentSymbols] / [LocalCompletion]），保证无网环境也能补全。
 */
object LanguageIntelligence {

    // ---------------------------------------------------------------- 关键字

    private val KOTLIN_KEYWORDS = listOf(
        "package", "import", "class", "interface", "object", "enum", "fun", "val", "var",
        "const", "private", "protected", "internal", "public", "override", "open", "final",
        "abstract", "sealed", "data", "annotation", "suspend", "inline", "noinline", "crossinline",
        "reified", "operator", "infix", "external", "lateinit", "companion", "init", "constructor",
        "return", "if", "else", "when", "for", "while", "do", "break", "continue", "throw", "try",
        "catch", "finally", "as", "is", "in", "out", "by", "where", "this", "super", "null", "true",
        "false", "typealias", "expect", "actual", "value", "vararg", "tailrec"
    )

    private val JAVA_KEYWORDS = listOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "var", "record", "sealed", "permits", "yield", "true", "false", "null"
    )

    private val GROOVY_KEYWORDS = listOf(
        "def", "class", "interface", "trait", "enum", "package", "import", "extends", "implements",
        "public", "private", "protected", "static", "final", "abstract", "synchronized", "volatile",
        "transient", "native", "return", "if", "else", "switch", "case", "default", "for", "while",
        "do", "break", "continue", "try", "catch", "finally", "throw", "throws", "new", "this",
        "super", "true", "false", "null", "void", "byte", "short", "int", "long", "float", "double",
        "char", "boolean", "in", "as", "instanceof", "assert", "it", "owner", "delegate"
    )

    private val DART_KEYWORDS = listOf(
        "abstract", "as", "assert", "async", "await", "base", "break", "case", "catch", "class",
        "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum",
        "export", "extends", "extension", "external", "factory", "false", "final", "finally", "for",
        "get", "hide", "if", "implements", "import", "in", "interface", "is", "late",
        "library", "mixin", "new", "null", "on", "operator", "part", "required", "rethrow", "return",
        "sealed", "set", "show", "static", "super", "switch", "sync", "this", "throw", "true", "try",
        "typedef", "var", "void", "when", "while", "with", "yield"
    )

    private val PYTHON_KEYWORDS = listOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
        "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda",
        "None", "nonlocal", "not", "or", "pass", "raise", "return", "self", "True", "False", "try",
        "while", "with", "yield", "match", "case"
    )

    private val JS_KEYWORDS = listOf(
        "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "finally", "for", "function", "if",
        "import", "in", "instanceof", "let", "new", "of", "return", "static", "super", "switch",
        "this", "throw", "try", "typeof", "undefined", "var", "void", "while", "yield", "null", "true", "false"
    )

    private val TS_KEYWORDS = JS_KEYWORDS + listOf(
        "interface", "type", "enum", "namespace", "declare", "implements", "readonly", "private",
        "public", "protected", "abstract", "as", "keyof", "infer", "asserts", "satisfies", "unknown", "never", "any"
    )

    private val SHELL_KEYWORDS = listOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac",
        "function", "return", "local", "export", "readonly", "unset", "shift", "break", "continue",
        "exit", "echo", "printf", "read", "cd", "source", "eval", "exec", "trap", "set"
    )

    private val C_KEYWORDS = listOf(
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
        "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register",
        "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
        "union", "unsigned", "void", "volatile", "while", "NULL", "true", "false"
    )

    private val CPP_KEYWORDS = C_KEYWORDS + listOf(
        "class", "namespace", "template", "typename", "public", "private", "protected", "virtual",
        "override", "final", "new", "delete", "this", "try", "catch", "throw", "using", "friend",
        "constexpr", "decltype", "noexcept", "nullptr", "operator", "explicit", "const_cast",
        "static_cast", "dynamic_cast", "reinterpret_cast", "auto", "concept", "requires"
    )

    private val RUST_KEYWORDS = listOf(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern",
        "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub",
        "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type",
        "unsafe", "use", "where", "while"
    )

    private val GO_KEYWORDS = listOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
        "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
        "return", "select", "struct", "switch", "type", "var", "nil", "true", "false"
    )

    private val SQL_KEYWORDS = listOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE",
        "TABLE", "ALTER", "DROP", "INDEX", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP",
        "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "DISTINCT", "AS", "AND", "OR", "NOT", "NULL",
        "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "DEFAULT", "UNIQUE", "COUNT", "SUM", "AVG", "MIN", "MAX"
    )

    fun keywords(lang: IntelLanguage): List<String> = when (lang) {
        IntelLanguage.KOTLIN -> KOTLIN_KEYWORDS
        IntelLanguage.JAVA -> JAVA_KEYWORDS
        IntelLanguage.GROOVY -> GROOVY_KEYWORDS
        IntelLanguage.DART -> DART_KEYWORDS
        IntelLanguage.PYTHON -> PYTHON_KEYWORDS
        IntelLanguage.JAVASCRIPT -> JS_KEYWORDS
        IntelLanguage.TYPESCRIPT -> TS_KEYWORDS
        IntelLanguage.SHELL -> SHELL_KEYWORDS
        IntelLanguage.C -> C_KEYWORDS
        IntelLanguage.CPP -> CPP_KEYWORDS
        IntelLanguage.RUST -> RUST_KEYWORDS
        IntelLanguage.GO -> GO_KEYWORDS
        IntelLanguage.SQL -> SQL_KEYWORDS
        IntelLanguage.JSON -> listOf("true", "false", "null")
        IntelLanguage.YAML -> listOf("true", "false", "null", "yes", "no")
        else -> emptyList()
    }

    /** XML 等结构化文本的关键字（标签名/属性名）。 */
    fun structuralKeywords(lang: IntelLanguage): List<String> = when (lang) {
        IntelLanguage.XML -> listOf("version", "encoding", "xmlns", "android", "tools", "app")
        else -> emptyList()
    }
}
