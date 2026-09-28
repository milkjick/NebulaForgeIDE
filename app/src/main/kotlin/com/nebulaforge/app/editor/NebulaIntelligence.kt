package com.nebulaforge.app.editor

import android.os.Bundle
import com.nebulaforge.core.editor.intel.CodeIndentFormatter
import com.nebulaforge.core.editor.intel.CompletionContext
import com.nebulaforge.core.editor.intel.CompletionKind
import com.nebulaforge.core.editor.intel.CompletionSuggestion
import com.nebulaforge.core.editor.intel.IssueSeverity
import com.nebulaforge.core.editor.intel.LocalCompletion
import com.nebulaforge.core.editor.intel.LocalIssue
import com.nebulaforge.core.editor.intel.WorkspaceSymbol
import com.nebulaforge.core.editor.intel.WorkspaceSymbolIndex
import com.nebulaforge.core.projectmodel.CompletionItem
import com.nebulaforge.core.session.LanguageServiceRegistry
import com.nebulaforge.app.plugins.JsExtensionHost.JsCompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.langs.java.JavaLanguage
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.text.TextRange
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 编辑器的智能输入宿主：把一个文件需要的外部能力打成一包交给 [NebulaLanguage]。
 *
 * 为什么不把 registry / index 直接塞进 Language：
 * Language 会被 Sora 在补全线程里反复调用，保持它只依赖「一包冻结好的引用」可以避免
 * 每次输入都重新解析项目根、重新查表。
 */
class EditorIntel(
    val projectRoot: File,
    val file: File,
    val registry: LanguageServiceRegistry,
    val index: WorkspaceSymbolIndex,
    val scope: CoroutineScope,
    /**
     * 已装扩展（VSIX 的 JS 逻辑）提供的补全：入参是 1 基行列，返回扩展给出的候选。
     * null = 没有可用扩展宿主（没装 Node、没有补全型扩展）。
     */
    val jsCompletions: (suspend (Int, Int) -> List<JsCompletionItem>)? = null
)

/**
 * 编辑器「本地智能」的接线层。
 *
 * 真正的算法都在 [com.nebulaforge.core.editor.intel]（纯 Kotlin、零依赖、可单测）；
 * 这里只负责把结果接到 Sora 的扩展点上：
 *
 * - 补全：`Language.requireAutoComplete` → `CompletionPublisher`
 * - 缩进：`Language.getIndentAdvance` + `Language.getNewlineHandlers`
 * - 格式化：`Language.getFormatter` → `CodeEditor.formatCodeAsync()`
 * - 诊断：不由 Language 承载，直接 `CodeEditor.setDiagnostics(DiagnosticsContainer)`
 *
 * 候选分三级落地，保证「按一个字母就有东西弹」：
 * 1. **本地**（同步、必然有结果）：当前文件符号 / 关键字 / 成员 / API / 片段；
 * 2. **工作区索引**（同步、内存里现成）：其他文件的顶层类与函数名，解决「跨文件写标识符」；
 * 3. **语言服务器**（异步增量）：装了 jdtls/kotlinc 之类才可用，回来多少补多少，绝不阻塞前两级。
 */
internal object NebulaIntelligence {

    private const val MAX_COMPLETION_ITEMS = 60

    /**
     * @param workspace 工作区跨文件符号（[WorkspaceSymbolIndex.snapshot]，可为空）
     * @param lsp 语言服务器补全请求（1 基行列）；为 null 表示该文件没有可用服务
     * @param lspScope 异步补全用的作用域（与编辑器生命周期一致）
     */
    fun autoComplete(
        fileName: String,
        text: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        workspace: List<WorkspaceSymbol> = emptyList(),
        lsp: (suspend (Int, Int) -> List<CompletionItem>)? = null,
        lspScope: CoroutineScope? = null,
        /** 已装扩展（VSIX 的 JS 逻辑）提供的候选请求；与 LSP 同级异步追加。 */
        js: (suspend (Int, Int) -> List<JsCompletionItem>)? = null,
        jsScope: CoroutineScope? = null
    ) {
        val line = position.line
        if (line < 0 || line >= text.lineCount) return
        val full = runCatching { text.toString() }.getOrNull() ?: return
        if (full.isBlank()) return
        val column = position.column.coerceAtLeast(0)
        // 已输入的前缀长度：决定补全项是否需要替换光标前的那截词。
        val prefixLength = runCatching {
            val index = text.getCharIndex(line, column)
            if (index in 0..full.length) identPrefixLength(full, index) else 0
        }.getOrDefault(0)

        val suggestions = runCatching {
            LocalCompletion.suggest(
                CompletionContext(fileName = fileName, text = full, line = line, column = column),
                limit = MAX_COMPLETION_ITEMS,
                workspace = workspace
            )
        }.getOrDefault(emptyList())

        if (suggestions.isNotEmpty()) {
            // 设备侧诊断留痕：配合 `adb logcat -s NebulaComplete` 可直接确认「敲键→候选」链路是否通。
            android.util.Log.i(
                "NebulaComplete",
                "file=$fileName line=$line col=$column 前缀=$prefixLength 本地候选=${suggestions.size} 首项=${suggestions.firstOrNull()?.label} 工作区符号=${workspace.size}"
            )
            publisher.addItems(suggestions.map { IntelCompletionItem.fromLocal(it, prefixLength) })
            runCatching { publisher.updateList() }
        } else {
            android.util.Log.i(
                "NebulaComplete",
                "file=$fileName line=$line col=$column 无本地候选（前缀=$prefixLength 工作区符号=${workspace.size}）"
            )
        }

        // ---- 已装扩展（VSIX JS）贡献的补全：异步追加，扩展卡住也绝不影响本地候选 ----
        val jsRequest = js
        val jsRunScope = jsScope
        if (jsRequest != null && jsRunScope != null) {
            jsRunScope.launch {
                val items = runCatching { jsRequest(line + 1, column + 1) }.getOrElse { error ->
                    android.util.Log.w("NebulaExtension", "扩展补全请求失败：${error.message}")
                    emptyList()
                }
                if (items.isEmpty()) return@launch
                val mapped = items.mapNotNull { item ->
                    runCatching {
                        IntelCompletionItem(
                            label = item.label,
                            detail = item.detail ?: item.documentation?.lineSequence()?.firstOrNull(),
                            insertText = stripSnippetPlaceholders(item.insertText.ifBlank { item.label }),
                            kind = soraKindOf(item.kind.toString(), item.label),
                            // 扩展候选是「有语言知识的一方」，排在本地启发式候选之前（数值越大越靠前）。
                            weight = 20,
                            prefixLength = prefixLength
                        )
                    }.getOrNull()
                }
                if (mapped.isEmpty()) return@launch
                withContext(Dispatchers.Main.immediate) {
                    runCatching { publisher.checkCancelled() }.onSuccess {
                        android.util.Log.i(
                            "NebulaExtension",
                            "扩展补全 ${mapped.size} 项（file=$fileName line=$line）"
                        )
                        runCatching { publisher.addItems(mapped) }
                        runCatching { publisher.updateList() }
                    }
                }
            }
        }

        // ---- 语言服务器：增量追加，成功与否都不影响已经弹出的本地候选 ----
        val request = lsp ?: return
        val scope = lspScope ?: return
        scope.launch {
            val items = runCatching { request(line + 1, column + 1) }.getOrDefault(emptyList())
            if (items.isEmpty()) return@launch
            val mapped = items.mapNotNull { item ->
                runCatching {
                    val cleaned = stripSnippetPlaceholders(item.insertText.ifBlank { item.label })
                    IntelCompletionItem(
                        label = item.label,
                        detail = item.detail ?: item.documentation?.lineSequence()?.firstOrNull(),
                        insertText = cleaned,
                        kind = soraKindOf(item.kind.toString(), item.label),
                        weight = 9,
                        prefixLength = item.filterText?.length ?: prefixLength
                    )
                }.getOrNull()
            }
            if (mapped.isEmpty()) return@launch
            // updateList/moveDown 这类 UI 动作必须回主线程；没有可用的主线程时直接放弃（本地候选已经在）。
            withContext(Dispatchers.Main.immediate) {
                // checkCancelled 会在补全被新输入打断时抛异常 —— 此时不能再往里塞候选，
                // 否则「打字越快、候选越乱」。
                runCatching { publisher.checkCancelled() }.onSuccess {
                    android.util.Log.i("NebulaComplete", "LSP 补全 ${mapped.size} 项（file=$fileName line=$line）")
                    runCatching { publisher.addItems(mapped) }
                    runCatching { publisher.updateList() }
                }
            }
        }
    }
}

/**
 * 组装「语言服务器补全」请求。
 *
 * 必须单独成函数并**显式写出 suspend 函数类型**：直接写成 `intel?.let { { l, c -> ... } }`
 * 时 Kotlin 推断不出内层 λ 是 suspend 的（期望类型丢失），编译期就会报类型不匹配。
 */
private fun lspRequestOf(intel: EditorIntel?): (suspend (Int, Int) -> List<CompletionItem>)? {
    if (intel == null) return null
    return { line, column -> intel.registry.completion(intel.projectRoot, intel.file, line, column) }
}

/**
 * 组装「已装扩展（VSIX JS）补全」请求。
 *
 * 与 [lspRequestOf] 同样必须显式写出 suspend 函数类型，否则内层 λ 的期望类型丢失。
 */
private fun jsRequestOf(intel: EditorIntel?): (suspend (Int, Int) -> List<JsCompletionItem>)? {
    val source = intel?.jsCompletions ?: return null
    return { line, column -> source(line, column) }
}

/** 光标前已输入的标识符前缀长度（本地计算，不依赖内核内部类型）。 */
private fun identPrefixLength(text: String, index: Int): Int {
    var i = index - 1
    while (i >= 0) {
        val c = text[i]
        if (c == '_' || c == '$' || c.isLetterOrDigit()) i-- else break
    }
    return index - 1 - i
}

/**
 * 去掉 LSP snippet 的 `${1:name}` / `$0` 占位符，只保留默认文本。
 *
 * 不引入 Sora 的 SnippetCompletionItem 是有意为之：候选来自不同服务端，
 * 占位符语法五花八门，插进去变成源码里的乱码比「没占位符」糟糕得多。
 */
private fun stripSnippetPlaceholders(text: String): String {
    if (!text.contains('$')) return text
    return text
        .replace(Regex("\\$\\{\\d+:([^}]*)\\}"), "$1")
        .replace(Regex("\\$\\{\\d+\\}"), "")
        .replace(Regex("\\$\\d+"), "")
}

/** LSP 的 CompletionItemKind（字符串或数字）→ Sora 图标种类。 */
private fun soraKindOf(kind: String?, label: String): CompletionItemKind {
    val k = kind?.trim()?.lowercase().orEmpty()
    // 数字形式（LSP 原始 kind）：2=Method 3=Function 5=Field 6=Variable 7=Class 14=Keyword …
    k.toIntOrNull()?.let { n ->
        return when (n) {
            7, 22 -> CompletionItemKind.Class
            2, 3, 4 -> CompletionItemKind.Method
            5, 10 -> CompletionItemKind.Property
            6 -> CompletionItemKind.Variable
            21 -> CompletionItemKind.Constant
            14 -> CompletionItemKind.Keyword
            9 -> CompletionItemKind.Module
            15 -> CompletionItemKind.Snippet
            else -> CompletionItemKind.Property
        }
    }
    return when {
        k.contains("interface") || k.contains("struct") -> CompletionItemKind.Class
        k.contains("class") || k.contains("enum") -> CompletionItemKind.Class
        k.contains("method") -> CompletionItemKind.Method
        k.contains("func") || k.contains("constructor") -> CompletionItemKind.Function
        k.contains("field") || k.contains("property") -> CompletionItemKind.Property
        k.contains("constant") -> CompletionItemKind.Constant
        k.contains("variable") || k.contains("local") -> CompletionItemKind.Variable
        k.contains("keyword") || k.contains("operator") -> CompletionItemKind.Keyword
        k.contains("module") || k.contains("namespace") || k.contains("package") -> CompletionItemKind.Module
        k.contains("snippet") -> CompletionItemKind.Snippet
        k.contains("tag") -> CompletionItemKind.Field
        // 服务端没给 kind 时按书写习惯兜底：大写开头当类型，其余当成员。
        else -> if (label.isNotEmpty() && label.first().isUpperCase()) CompletionItemKind.Class else CompletionItemKind.Property
    }
}

/** 片段里 `\n    \n` 中间那行留作占位，补全后把光标放到这一行。 */
private const val SNIPPET_PLACEHOLDER = "\n    \n"
private const val PLACEHOLDER_INDENT = 4

/**
 * 一条补全项：按权重排序，选中后替换光标前的词。
 *
 * 本地候选与 LSP 候选共用这一个实现，保证「点击插入 / 回车插入」行为完全一致。
 */
private class IntelCompletionItem(
    label: String,
    detail: String?,
    private val insertText: String,
    kind: CompletionItemKind,
    weight: Int,
    prefixLength: Int
) : io.github.rosemoe.sora.lang.completion.CompletionItem(label, detail) {

    init {
        this.prefixLength = prefixLength
        this.kind = kind
        // 权重越小越靠前：sortText 升序即可让「当前文件符号」排在关键字与 API 之前。
        sortText = "%05d".format((10000 - weight).coerceIn(0, 99999))
    }

    override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
        val startColumn = (column - prefixLength).coerceIn(0, column)
        val insert = insertText
        val placeholder = insert.indexOf(SNIPPET_PLACEHOLDER)
        val caretOffset =
            if (placeholder >= 0) (placeholder + 1 + PLACEHOLDER_INDENT).coerceAtMost(insert.length)
            else insert.length
        text.replace(line, startColumn, line, column, insert)
        val head = insert.substring(0, caretOffset)
        val newlines = head.count { it == '\n' }
        val caretLine = line + newlines
        val caretColumn =
            if (newlines == 0) startColumn + head.length
            else head.length - head.lastIndexOf('\n') - 1
        runCatching { editor.setSelection(caretLine, caretColumn) }
        editor.postInvalidate()
    }

    companion object {
        /** 本地候选：weight 决定排序（核心引擎已经算好），snippet 的插入文本原样使用。 */
        fun fromLocal(suggestion: CompletionSuggestion, prefixLength: Int): IntelCompletionItem =
            IntelCompletionItem(
                label = suggestion.label,
                detail = suggestion.detail,
                insertText = suggestion.insertText,
                kind = soraKindOfLocal(suggestion.kind),
                weight = suggestion.weight,
                prefixLength = prefixLength
            )

        private fun soraKindOfLocal(kind: CompletionKind): CompletionItemKind = when (kind) {
            CompletionKind.KEYWORD -> CompletionItemKind.Keyword
            CompletionKind.FUNCTION -> CompletionItemKind.Function
            CompletionKind.METHOD -> CompletionItemKind.Method
            CompletionKind.CLASS -> CompletionItemKind.Class
            CompletionKind.PROPERTY -> CompletionItemKind.Property
            CompletionKind.VARIABLE -> CompletionItemKind.Variable
            CompletionKind.CONSTANT -> CompletionItemKind.Constant
            CompletionKind.SNIPPET -> CompletionItemKind.Snippet
            CompletionKind.MODULE -> CompletionItemKind.Module
            CompletionKind.TAG -> CompletionItemKind.Field
        }
    }
}

/**
 * 本地缩进格式化器：按括号深度重排每行缩进。
 *
 * [CodeEditor.formatCodeAsync] 的约定是「把文档副本交给 Formatter，Formatter 再把结果交回去」，
 * 所以这里只用 [CodeIndentFormatter] 算出新文本，不直接改 `Content`。
 * 只调整空白，不改动任何语义，也不碰多行字符串内部的相对缩进。
 */
private class IndentFormatter(private val fileName: String) : Formatter {

    private var receiver: Formatter.FormatResultReceiver? = null

    override fun setReceiver(receiver: Formatter.FormatResultReceiver?) {
        this.receiver = receiver
    }

    override fun format(content: Content, range: TextRange) {
        val original = runCatching { content.toString() }.getOrNull() ?: return
        val formatted = runCatching { CodeIndentFormatter.format(fileName, original) }.getOrNull() ?: return
        if (formatted == original) return
        receiver?.onFormatSucceed(formatted, range)
    }

    override fun formatRegion(content: Content, range: TextRange, cursorRange: TextRange) {
        format(content, range)
    }

    /** 同步实现，不存在"正在格式化"的后台任务。 */
    override fun isRunning(): Boolean = false

    override fun destroy() {
        receiver = null
    }
}

/**
 * 通用文件语言：自动缩进 + 括号配对 + 本地补全 + 本地格式化。
 *
 * 不挂语法着色（离线仓库没有对应语言包），但保证"能顺手写代码"。
 */
class NebulaLanguage(
    private val fileName: String,
    tabSize: Int = 4,
    /** 智能输入宿主（工作区索引 + 语言服务器）；null = 纯本地补全。 */
    private val intel: EditorIntel? = null
) : AutoIndentLanguage(tabSize) {

    override fun requireAutoComplete(
        text: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        env: Bundle
    ) {
        NebulaIntelligence.autoComplete(
            fileName = fileName,
            text = text,
            position = position,
            publisher = publisher,
            workspace = intel?.index?.snapshot().orEmpty(),
            lsp = lspRequestOf(intel),
            lspScope = intel?.scope,
            js = jsRequestOf(intel),
            jsScope = intel?.scope
        )
    }

    override fun getFormatter(): Formatter = IndentFormatter(fileName)
}

/**
 * Java 文件语言：保留官方 `language-java` 的语法高亮，再叠加本地智能。
 *
 * 直接继承 [JavaLanguage] 只覆盖需要的几个扩展点，着色 / 括号 / 词法分析仍用官方实现。
 */
class NebulaJavaLanguage(
    private val javaFileName: String,
    private val tabSize: Int = 4,
    private val intel: EditorIntel? = null
) : JavaLanguage() {

    override fun requireAutoComplete(
        text: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        env: Bundle
    ) {
        NebulaIntelligence.autoComplete(
            fileName = javaFileName,
            text = text,
            position = position,
            publisher = publisher,
            workspace = intel?.index?.snapshot().orEmpty(),
            lsp = lspRequestOf(intel),
            lspScope = intel?.scope,
            js = jsRequestOf(intel),
            jsScope = intel?.scope
        )
    }

    override fun getFormatter(): Formatter = IndentFormatter(javaFileName)

    override fun getIndentAdvance(text: ContentReference, line: Int, column: Int): Int =
        indentAdvanceOf(text, line, column, tabSize)

    override fun getNewlineHandlers(): Array<NewlineHandler> =
        arrayOf(AutoIndentNewlineHandler(tabSize.coerceAtLeast(1)))
}

/** 本地诊断的严重级别 → Sora 波浪线级别。 */
internal fun soraSeverityOf(severity: IssueSeverity): Short = when (severity) {
    IssueSeverity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
    IssueSeverity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
    IssueSeverity.INFO -> DiagnosticRegion.SEVERITY_TYPO
}

/** 本地诊断的严重级别 → IDE 会话诊断级别（问题面板 / 状态栏共用同一真源）。 */
internal fun coreSeverityOf(severity: IssueSeverity): com.nebulaforge.core.session.Severity = when (severity) {
    IssueSeverity.ERROR -> com.nebulaforge.core.session.Severity.ERROR
    IssueSeverity.WARNING -> com.nebulaforge.core.session.Severity.WARNING
    IssueSeverity.INFO -> com.nebulaforge.core.session.Severity.INFO
}

/** 一条本地问题 → Sora 波浪线的提示详情。 */
internal fun detailOf(issue: LocalIssue): DiagnosticDetail =
    DiagnosticDetail(issue.message, "${issue.source}：${issue.message}", emptyList(), null)
