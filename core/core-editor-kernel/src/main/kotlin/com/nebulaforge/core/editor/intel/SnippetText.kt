package com.nebulaforge.core.editor.intel

/**
 * VS Code 片段正文 → 可插入纯文本。
 *
 * ## 边界（必须说清楚，不能假装支持）
 * Sora 的补全管线插入的是**纯文本**，没有 tab stop（`$1`/`${2:name}` 的跳转占位）能力。
 * 所以这里把占位符「就地展开成默认值」：
 * - `${1:list}` → `list`（有默认值的占位符保留默认值，最贴近用户预期）；
 * - `${1|a,b,c|}` → `a`（多选占位符取第一项）；
 * - `${1}` / `$1` → 空串（没有默认值就没什么可插入的）；
 * - `${TM_FILENAME}` / `$TM_FILENAME` → 空串（变量由编辑器运行时提供，静态展开会写错内容）；
 * - `\$` → `$`（转义的美元符号是字面量，不能被当成占位符）。
 *
 * 结果可能残留空行/多余空格，交给调用方按需 `trimIndent`，这里只保证「不出现占位符语法」。
 */
internal object SnippetText {

    private const val ESCAPED_DOLLAR = "\u0000NEBULA_DOLLAR\u0000"

    /** `${1|a,b,c|}`：取第一项。 */
    private val CHOICE = Regex("\\$\\{\\d+\\|([^|{}]*)[^}]*\\}")

    /** `${1:default}`：保留默认值（默认值内不允许再嵌套花括号，与 VS Code 语义一致）。 */
    private val DEFAULT = Regex("\\$\\{\\d+:([^{}]*)}")

    /** `${1}`：无默认值的索引占位符 → 空。 */
    private val INDEXED = Regex("\\$\\{\\d+}")

    /** `$1`：简写索引占位符 → 空。 */
    private val PLAIN_INDEX = Regex("\\$\\d+")

    /** `${TM_FILENAME}`、`${TM_FILENAME:default}`、`$TM_FILENAME`：变量 → 空。 */
    private val VARIABLE = Regex("\\$\\{[A-Za-z_][A-Za-z0-9_]*(?::[^{}]*)?}|\\$[A-Z_][A-Z0-9_]*")

    fun expand(body: String): String {
        if (body.isEmpty()) return body
        if (!body.contains('$')) return body
        val protected = body.replace("\\$", ESCAPED_DOLLAR)
        val expanded = VARIABLE.replace(
            DEFAULT.replace(
                CHOICE.replace(protected) { it.groupValues[1] }
            ) { it.groupValues[1] }
                .let { INDEXED.replace(it, "") }
                .let { PLAIN_INDEX.replace(it, "") }
        ) { "" }
        return expanded.replace(ESCAPED_DOLLAR, "$")
    }
}
