package com.nebulaforge.core.editor.intel

/**
 * 声明式插件贡献点注册表。
 *
 * ## 为什么需要它
 * 外部生态（VSCode 的 VSIX）里的扩展分两类贡献：
 * - **声明式**：语言关联（`contributes.languages`）、代码片段（`contributes.snippets`）、
 *   编辑器默认设置（`contributes.configurationDefaults`）—— 这些只是数据，不需要执行任何代码；
 * - **命令式**：`main` / `commands` / `menus` / `keybindings` / `activationEvents` —— 必须在
 *   JavaScript 运行时里跑（本宿主没有 JS 引擎，架构上不可执行，见转换器的 `unsupported` 清单）。
 *
 * 声明式那部分没有理由不能生效：把它转成纯数据灌进注册表，编辑器内核（语言判定、补全）直接读取，
 * 用户装的 VSIX 就能在编辑器里真正起作用，而不是「下载完躺在目录里」。
 *
 * ## 分层与线程
 * 本对象在 `core-editor-kernel`（纯 Kotlin、无 Android 依赖），因为消费方是补全引擎与语言判定；
 * 灌数据的一方是宿主（app 模块的 [com.nebulaforge.app.plugins.DeclarativePluginLoader]）。
 * 读取发生在 Sora 的补全线程，写入发生在 IO 线程 → 全部索引都是 `@Volatile` 的不可变映射，
 * 写入时整体替换，读到的永远是自洽的一份快照。
 */
object DeclarativeContributions {

    /** 语言关联：某插件声明「这些扩展名/文件名属于某个语言 id」。 */
    data class LanguageContribution(
        val pluginId: String,
        val languageId: String,
        /** 扩展名，小写、**不含**前导点（如 `kt`、`gradle.kts`）。 */
        val extensions: List<String>,
        /** 精确文件名，小写（如 `dockerfile`）。 */
        val fileNames: List<String> = emptyList(),
        val aliases: List<String> = emptyList()
    )

    /** 代码片段：VS Code snippet 的扁平化形式。 */
    data class SnippetContribution(
        val pluginId: String,
        val languageId: String,
        val name: String,
        /** 触发前缀（VS Code 的 `prefix`，可能是数组或单值）。 */
        val prefix: List<String>,
        /** 片段正文，**保留**原始 `${1:name}` / `$TM_FILENAME` 占位符，由消费方决定如何展开。 */
        val body: String,
        val description: String?
    )

    /** 编辑器默认设置（`contributes.configurationDefaults`）。[languageId] 为空表示全局。 */
    data class EditorDefaults(
        val pluginId: String,
        val values: Map<String, String>,
        val languageId: String? = null
    )

    /** 全部声明式贡献的一份自洽快照。 */
    data class Snapshot(
        val languages: List<LanguageContribution> = emptyList(),
        val snippets: List<SnippetContribution> = emptyList(),
        val defaults: List<EditorDefaults> = emptyList()
    ) {
        val isEmpty: Boolean get() = languages.isEmpty() && snippets.isEmpty() && defaults.isEmpty()
        val extensionCount: Int get() = languages.sumOf { it.extensions.size }
    }

    @Volatile
    private var current = Snapshot()

    /** 扩展名（小写无点）→ 语言 id。 */
    @Volatile
    private var extensionIndex: Map<String, String> = emptyMap()

    /** 精确文件名（小写）→ 语言 id。 */
    @Volatile
    private var fileNameIndex: Map<String, String> = emptyMap()

    /** 内置语言 → 该语言可用的片段（片段的语言 id 已归一到 [IntelLanguage]）。 */
    @Volatile
    private var snippetIndex: Map<IntelLanguage, List<SnippetContribution>> = emptyMap()

    /** 全局编辑器默认设置（不带语言限定）。 */
    @Volatile
    private var globalDefaults: Map<String, String> = emptyMap()

    /** 语言限定的编辑器默认设置（VS Code 的 `"[javascript]": {...}` 形式）。 */
    @Volatile
    private var languageDefaults: Map<IntelLanguage, Map<String, String>> = emptyMap()

    fun snapshot(): Snapshot = current

    /**
     * 整体替换所有声明式贡献。
     *
     * 由宿主在「应用启动」与「插件安装/卸载后」调用。整体替换而不是增量注册，
     * 是为了让卸载插件不留残影 —— 索引永远等于「当前已安装声明式插件」的全集。
     *
     * 同一声明被多个插件覆盖时取**后写入者**（即安装/扫描顺序靠后的那个），
     * 与 VSCode 里「后安装的扩展覆盖语言关联」一致。
     */
    @Synchronized
    fun replaceAll(snapshot: Snapshot) {
        current = snapshot
        extensionIndex = buildMap {
            snapshot.languages.forEach { lang ->
                lang.extensions.forEach { ext -> put(normalizeExtension(ext), lang.languageId) }
            }
        }
        fileNameIndex = buildMap {
            snapshot.languages.forEach { lang ->
                lang.fileNames.forEach { name -> put(name.lowercase(), lang.languageId) }
            }
        }
        val byLanguage = HashMap<IntelLanguage, MutableList<SnippetContribution>>()
        snapshot.snippets.forEach { snippet ->
            // 片段只在其语言 id 能映射到内置语言时可用：映射不到的内置语言没有补全管线，
            // 硬塞进去只会占用候选位（也不会被渲染成对应语言的补全）。
            val language = IntelLanguages.ofLanguageId(snippet.languageId)
            if (language != IntelLanguage.PLAIN) byLanguage.getOrPut(language) { mutableListOf() }.add(snippet)
        }
        snippetIndex = byLanguage

        globalDefaults = buildMap {
            snapshot.defaults.filter { it.languageId.isNullOrBlank() }.forEach { putAll(it.values) }
        }
        languageDefaults = buildMap {
            snapshot.defaults.filterNot { it.languageId.isNullOrBlank() }.forEach { d ->
                val language = IntelLanguages.ofLanguageId(d.languageId!!)
                if (language != IntelLanguage.PLAIN) {
                    put(language, get(language).orEmpty() + d.values)
                }
            }
        }
    }

    /**
     * 取某文件应使用的缩进宽度（来自插件 `configurationDefaults.editor.tabSize`）。
     *
     * 语言限定的声明优先于全局声明，与 VS Code 一致。取不到或取值非法（非数字 / 超出 1..16）
     * 时返回 null，由调用方回落到内置默认（4）。
     */
    fun editorTabSizeFor(fileName: String): Int? {
        val language = IntelLanguages.of(fileName)
        val scoped = languageDefaults[language]?.get(TAB_SIZE)
        val raw = scoped ?: globalDefaults[TAB_SIZE] ?: return null
        val value = raw.trim().toDoubleOrNull()?.toInt() ?: return null
        return value.takeIf { it in MIN_TAB_SIZE..MAX_TAB_SIZE }
    }

    private const val TAB_SIZE = "editor.tabSize"
    private const val MIN_TAB_SIZE = 1
    private const val MAX_TAB_SIZE = 16

    fun clear() = replaceAll(Snapshot())

    /**
     * 按文件名（可带显式扩展名）查插件声明的语言 id；没有插件声明时返回 null。
     *
     * 注意：这里只回答「插件说它是什么语言」，不认识的语言 id 由 [IntelLanguages.ofLanguageId] 兜底。
     */
    fun languageIdFor(fileName: String, extension: String? = null): String? {
        val name = fileName.lowercase()
        val ext = normalizeExtension(extension ?: name.substringAfterLast('.', ""))
        fileNameIndex[name]?.let { return it }
        if (ext.isEmpty()) return null
        return extensionIndex[ext]
    }

    fun snippetsFor(language: IntelLanguage): List<SnippetContribution> = snippetIndex[language].orEmpty()

    /** 全部片段（供插件详情页展示「这个插件到底贡献了什么」）。 */
    fun allSnippets(): List<SnippetContribution> = current.snippets

    private fun normalizeExtension(ext: String): String =
        ext.lowercase().removePrefix(".").removePrefix("*.")
}
