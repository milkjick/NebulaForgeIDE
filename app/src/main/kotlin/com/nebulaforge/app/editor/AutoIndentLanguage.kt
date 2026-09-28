package com.nebulaforge.app.editor

import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import com.nebulaforge.core.editor.intel.DeclarativeContributions
import java.io.File

/**
 * 按文件类型选语言。
 *
 * 离线环境里没有 Kotlin / Gradle / JSON / XML 等 Sora 语言包（本地 Maven 仓库只有 language-java），
 * 所以除了 .java 走官方语言包（保留语法高亮，再叠加本地智能）之外，其余文本文件都挂 [NebulaLanguage]。
 *
 * 两种语言都带本地智能：自动缩进 + 括号配对 + 本地补全 + 本地诊断配合的格式化入口，
 * 全部离线计算，不依赖 AI、网络或 Language Server。
 */
fun editorLanguageFor(file: File, intel: EditorIntel? = null): Language {
    // 缩进宽度优先取插件（含 VSIX 转换来的声明式插件）的 configurationDefaults，
    // 例如 Vue/前端类扩展声明的 "editor.tabSize": 2 会在这里生效；没有声明则沿用内置 4 空格。
    val tabSize = DeclarativeContributions.editorTabSizeFor(file.name) ?: DEFAULT_TAB_SIZE
    return when (file.extension.lowercase()) {
        "java" -> NebulaJavaLanguage(file.name, tabSize = tabSize, intel = intel)
        else -> NebulaLanguage(file.name, tabSize = tabSize, intel = intel)
    }
}

/** 内置默认缩进宽度：没有任何插件声明时使用。 */
const val DEFAULT_TAB_SIZE = 4

/**
 * 轻量代码感知语言：只补两件写代码最要紧的事，不做语法着色。
 *
 * 1. **自动缩进**：行尾以 `{`、`(`、`[`、`:` 结束时，回车后新行自动多缩进一级；
 *    若下一行本来就是一个收尾括号（`}` / `)` / `]`），回车会一次生成三行并保留收尾括号，
 *    光标停在中行 —— 和桌面 IDE 的手感一致。
 * 2. **括号自动配对**：`{` `(` `[` `"` `'` 成对补全。
 *
 * 这里刻意用 [NewlineHandler] 接管回车，而不是只依赖 [getIndentAdvance]：
 * NewlineHandler 的输入输出完全可控（插入文本 + 光标回退量都由我们给出），
 * 不会出现"缩进加多一层/少一层"这种靠上游语义猜的情况。
 */
open class AutoIndentLanguage(private val tabSize: Int = 4) : EmptyLanguage() {

    private val symbolPairs: SymbolPairMatch = SymbolPairMatch().apply {
        putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
        putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
        putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
        putPair('"', SymbolPairMatch.SymbolPair("\"", "\""))
        putPair('\'', SymbolPairMatch.SymbolPair("'", "'"))
    }

    override fun getSymbolPairs(): SymbolPairMatch = symbolPairs

    override fun getNewlineHandlers(): Array<NewlineHandler> =
        arrayOf(AutoIndentNewlineHandler(tabSize.coerceAtLeast(1)))

    /** 兜底：Sora 在其它路径上可能问缩进增量，直接给出"未闭合括号层数 × 缩进宽度"。 */
    override fun getIndentAdvance(text: ContentReference, line: Int, column: Int): Int =
        indentAdvanceOf(text, line, column, tabSize)
}

/**
 * 缩进增量计算（供多种语言实现共用）：当前行光标之前若开启了代码块，返回一个缩进宽度。
 *
 * Sora 默认回车只会复制上一行缩进，遇到 `{` 结尾的行不会多缩进一级，写出来的代码就是
 * "括号堆叠、层次全平"。[NewlineHandler] 与这里共同保证大括号 / 冒号块都会自动加深一级。
 */
internal fun indentAdvanceOf(text: ContentReference, line: Int, column: Int, tabSize: Int): Int {
    if (line < 0 || line >= text.lineCount) return 0
    val content = text.getLine(line) ?: return 0
    val upto = content.substring(0, column.coerceIn(0, content.length))
    return if (opensBlock(upto)) tabSize.coerceAtLeast(1) else 0
}

/** 判断一段"光标之前的文本"是否意味着下一行应当缩进一级。 */
internal fun opensBlock(before: String): Boolean {
    val line = before.trimEnd()
    if (line.isEmpty()) return false
    // 括号法：统计剔除字符串字面量后的净层级。
    var depth = 0
    var quote: Char? = null
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            quote != null -> {
                if (c == '\\') i++ else if (c == quote) quote = null
            }
            c == '"' || c == '\'' -> quote = c
            c == '{' || c == '(' || c == '[' -> depth++
            c == '}' || c == ')' || c == ']' -> depth--
        }
        i++
    }
    if (depth > 0) return true
    if (depth < 0) return false
    // 缩进块写法：Groovy/Kotlin/YAML 常见的冒号、lambda 箭头、else。
    return line.endsWith(":") || line.endsWith("->") || line.endsWith("else") ||
        line.endsWith("do") || line.endsWith("try") || line.endsWith("finally")
}

/** 当前行"光标之前"的缩进（空格 / 制表符原样保留）。 */
internal fun leadingIndent(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

/**
 * 回车处理器：只在"行尾开启了一个代码块"时接管，其它情况交回 Sora 默认行为（复制上一行缩进）。
 */
class AutoIndentNewlineHandler(private val tabSize: Int) : NewlineHandler {

    override fun matchesRequirement(text: Content, cursor: CharPosition, style: Styles?): Boolean {
        val line = cursor.line
        if (line < 0 || line >= text.lineCount) return false
        val current = runCatching { text.getLineString(line) }.getOrNull() ?: return false
        val column = cursor.column.coerceIn(0, current.length)
        return opensBlock(current.substring(0, column))
    }

    override fun handleNewline(
        text: Content,
        cursor: CharPosition,
        style: Styles?,
        tabSize: Int
    ): NewlineHandleResult {
        val line = cursor.line
        val current = runCatching { text.getLineString(line) }.getOrNull().orEmpty()
        val column = cursor.column.coerceIn(0, current.length)
        val indent = leadingIndent(current)
        val step = " ".repeat(tabSize.coerceAtLeast(1))
        val inner = indent + step

        // 光标右侧（同一行剩余内容）若是一个收尾括号，回车后把它留在新的一行，光标停在中行。
        val rest = current.substring(column).trim()
        val isCloser = rest.isNotEmpty() &&
            (rest[0] == '}' || rest[0] == ')' || rest[0] == ']')

        return if (isCloser) {
            // 插入：换行 + 内层缩进 + 换行 + 原缩进；随后光标回退到中行行尾。
            val payload = "\n" + inner + "\n" + indent
            NewlineHandleResult(payload, indent.length + 1)
        } else {
            NewlineHandleResult("\n" + inner, 0)
        }
    }
}
