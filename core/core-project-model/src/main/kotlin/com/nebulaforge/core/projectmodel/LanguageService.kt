package com.nebulaforge.core.projectmodel

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 统一语言服务接入。封装具体 LSP 进程的启动/通信细节，
 * 编辑器只通过本接口发送请求（补全、跳转定义、诊断等），不直接操作 JSON-RPC。
 *
 * 对应开发方案第 2.2 节 / 第十一章 11.3 节。
 * MVP 阶段此接口先定义骨架，具体 LSP 通信实现留到语言服务接入阶段（路线图阶段 2）。
 */
interface LanguageService {
    val languageId: String            // 如 "kotlin", "dart", "typescript", "cpp"
    val supportedExtensions: List<String>  // 如 [".kt", ".kts"]

    /** 启动语言服务器进程（若走 LSP）；非 LSP 的语言可直接返回 true */
    suspend fun start(projectRoot: File, env: Map<String, String>): Boolean

    suspend fun stop()

    /** Notify the language server that an editor document has been closed. */
    suspend fun didClose(file: File) {}

    /** Notify the language server that an editor document has been opened. */
    suspend fun didOpen(file: File, text: String) {}

    /** Notify the language server that an editor document changed. */
    suspend fun didChange(file: File, version: Int, text: String) {}

    suspend fun requestCompletion(uri: String, line: Int, column: Int): List<CompletionItem>
    suspend fun requestDefinition(uri: String, line: Int, column: Int): List<Location>
    fun diagnosticsStream(uri: String): Flow<List<Diagnostic>>
}

data class CompletionItem(
    val label: String,
    val insertText: String,
    val kind: String,
    /** LSP `detail`（如函数签名 `fun foo(x: Int): String`），用于候选列表的灰色副标题。 */
    val detail: String? = null,
    /** LSP `documentation`（Markdown/纯文本），用于候选右侧的文档提示。 */
    val documentation: String? = null,
    /** LSP `sortText`：服务端给出的排序权重（越小越靠前），空则按 label 排序。 */
    val sortText: String? = null,
    /** LSP `filterText`：前缀过滤使用的文本（与 label 不同，例如标签里带参数占位）。 */
    val filterText: String? = null,
    /** 是否需要把 `$(...)` 占位符展开成片段（snippet）。 */
    val snippet: Boolean = false
)
data class Location(val uri: String, val line: Int, val column: Int)
data class Diagnostic(val line: Int, val column: Int, val message: String, val severity: BuildError.Severity)
