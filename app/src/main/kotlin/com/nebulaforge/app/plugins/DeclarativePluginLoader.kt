package com.nebulaforge.app.plugins

import android.content.Context
import com.nebulaforge.core.editor.intel.DeclarativeContributions
import com.nebulaforge.core.environment.Environment
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

/**
 * 把「已安装的声明式插件」灌进编辑器内核。
 *
 * ## 职责边界
 * [DeclarativeContributions] 是纯 Kotlin 的只读注册表（编辑器补全线程在读它）；
 * 本类是宿主侧的**唯一写入方**：装了什么（或卸载了什么）→ 重新扫描插件目录 → 整体替换快照。
 *
 * 为什么要整体重扫而不是增量 add/remove：
 * - 卸载插件必须不留残影（增量移除一旦漏一处，就会留下永远生效的幽灵语言关联）；
 * - 插件目录是唯一事实来源，重扫天然与「文件系统里到底有什么」一致；
 * - 单个包解析失败只跳过它自己（不让一个坏包把其它插件的贡献一起清空）。
 *
 * ## 与 Nebula 原生插件的关系
 * 原生插件（含 dex）的扩展点由 `PluginRuntime` 实例化，走 DEX 那条路；
 * 这里只处理 `nebula/declarative.json` —— 即 VSIX 转换包与未来的声明式插件。
 * 两者在 UI 上都表现为「已安装插件」，但生效机制不同，故在摘要里区分标注。
 */
class DeclarativePluginLoader(private val context: Context) {

    /** 一个声明式插件的贡献摘要（UI 展示「装了什么、什么没生效」）。 */
    data class Summary(
        val pluginId: String,
        val displayName: String,
        val version: String,
        val packageFile: String,
        val languageCount: Int,
        val extensionCount: Int,
        val snippetCount: Int,
        val defaultsCount: Int,
        val unsupported: List<VsixAdapter.Unsupported>,
        /** 源自 VSIX 转换（而非手写的声明式包）。 */
        val convertedFromVsix: Boolean,
        /** 包内声明的 JS 逻辑；为空表示纯声明式扩展（没有可执行的 JS）。 */
        val js: JsExtension? = null
    ) {
        val effectiveCount: Int get() = extensionCount + snippetCount + defaultsCount
        val unsupportedCount: Int get() = unsupported.sumOf { it.count }
    }

    /**
     * 转换包 `js` 段的解析结果：JS 扩展宿主据此提取负载并启动 node 进程。
     * 插件目录不被解压，所以负载提取使用 `packageFile` 内的 `payload` 前缀。
     */
    data class JsExtension(
        val main: String?,
        val activationEvents: List<String>,
        val commands: List<Command>,
        /** 包内负载目录前缀（固定为 `extension/`）。 */
        val payload: String,
        val payloadFiles: Int,
        val payloadBytes: Long
    ) {
        data class Command(val command: String, val title: String)
    }

    @Volatile
    private var summaries: List<Summary> = emptyList()

    fun summaries(): List<Summary> = summaries

    fun summaryOf(pluginId: String): Summary? = summaries.firstOrNull { it.pluginId == pluginId }

    /**
     * 重扫插件目录并替换编辑器内核里的声明式贡献。
     *
     * 必须在新装/卸载插件后调用，否则「装了但编辑器没反应」。
     *
     * @return 当前生效的声明式插件摘要
     */
    fun refresh(): List<Summary> {
        val dir = File(Environment.pluginsDir(context))
        val found = ArrayList<Summary>()
        val snapshotLanguages = ArrayList<DeclarativeContributions.LanguageContribution>()
        val snapshotSnippets = ArrayList<DeclarativeContributions.SnippetContribution>()
        val snapshotDefaults = ArrayList<DeclarativeContributions.EditorDefaults>()

        dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { file ->
            val parsed = runCatching { parse(file) }.getOrNull() ?: return@forEach
            found += parsed.summary
            snapshotLanguages += parsed.languages
            snapshotSnippets += parsed.snippets
            snapshotDefaults += parsed.defaults
        }

        // 一次替换：避免「先清空再逐个追加」期间补全线程读到半截状态。
        DeclarativeContributions.replaceAll(
            DeclarativeContributions.Snapshot(
                languages = snapshotLanguages,
                snippets = snapshotSnippets,
                defaults = snapshotDefaults
            )
        )
        summaries = found
        return found
    }

    private class Parsed(
        val summary: Summary,
        val languages: List<DeclarativeContributions.LanguageContribution>,
        val snippets: List<DeclarativeContributions.SnippetContribution>,
        val defaults: List<DeclarativeContributions.EditorDefaults>
    )

    private fun parse(file: File): Parsed? {
        val json = readDeclarative(file) ?: return null
        val pluginId = json.optString("pluginId").ifBlank { file.nameWithoutExtension }
        val languages = ArrayList<DeclarativeContributions.LanguageContribution>()
        val snippets = ArrayList<DeclarativeContributions.SnippetContribution>()
        val defaults = ArrayList<DeclarativeContributions.EditorDefaults>()

        json.optJSONArray("languages")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                if (id.isEmpty()) continue
                languages += DeclarativeContributions.LanguageContribution(
                    pluginId = pluginId,
                    languageId = id,
                    extensions = stringList(item.optJSONArray("extensions")),
                    fileNames = stringList(item.optJSONArray("filenames")),
                    aliases = stringList(item.optJSONArray("aliases"))
                )
            }
        }
        json.optJSONArray("snippets")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val language = item.optString("language").trim()
                val body = item.optString("body")
                if (language.isEmpty() || body.isBlank()) continue
                snippets += DeclarativeContributions.SnippetContribution(
                    pluginId = pluginId,
                    languageId = language,
                    name = item.optString("name").ifBlank { "snippet-$i" },
                    prefix = stringList(item.optJSONArray("prefix")),
                    body = body,
                    description = item.optString("description").takeIf { it.isNotBlank() }
                )
            }
        }
        json.optJSONObject("editorDefaults")?.let { obj ->
            val values = stringMap(obj)
            if (values.isNotEmpty()) defaults += DeclarativeContributions.EditorDefaults(pluginId, values)
        }
        json.optJSONObject("languageDefaults")?.let { obj ->
            obj.keys().forEach { languageId ->
                val values = obj.optJSONObject(languageId)?.let(::stringMap).orEmpty()
                if (values.isNotEmpty()) {
                    defaults += DeclarativeContributions.EditorDefaults(pluginId, values, languageId)
                }
            }
        }

        val unsupported = ArrayList<VsixAdapter.Unsupported>()
        json.optJSONArray("unsupported")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                unsupported += VsixAdapter.Unsupported(
                    point = item.optString("point"),
                    reason = item.optString("reason"),
                    count = item.optInt("count", 1)
                )
            }
        }

        val js = parseJs(json)

        return Parsed(
            summary = Summary(
                pluginId = pluginId,
                displayName = json.optString("displayName").ifBlank { pluginId },
                version = json.optString("version"),
                // 必须是**绝对路径**：JS 宿主用它打开转换包解压 extension/ 负载。
                // 只存 file.name 的话（历史 bug），File(name) 会相对进程 CWD 解析 → 永远不存在
                // → 「无法解包扩展负载 / 启动失败」，真机实测就栽在这里。
                packageFile = file.absolutePath,
                languageCount = languages.size,
                extensionCount = languages.sumOf { it.extensions.size },
                snippetCount = snippets.size,
                defaultsCount = defaults.size,
                unsupported = unsupported,
                convertedFromVsix = json.optJSONObject("source")?.optString("kind") == "vsix",
                js = js
            ),
            languages = languages,
            snippets = snippets,
            defaults = defaults
        )
    }

    /** 读 `js` 段：只有存在入口/命令/活化事件时才返回非空。 */
    private fun parseJs(json: JSONObject): JsExtension? {
        val obj = json.optJSONObject("js") ?: return null
        val main = obj.optString("main").trim().takeIf { it.isNotEmpty() }
        val events = stringList(obj.optJSONArray("activationEvents"))
        val commands = ArrayList<JsExtension.Command>()
        obj.optJSONArray("commands")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("command").trim()
                if (id.isEmpty()) continue
                commands.add(JsExtension.Command(id, item.optString("title").trim()))
            }
        }
        if (main == null && events.isEmpty() && commands.isEmpty()) return null
        return JsExtension(
            main = main,
            activationEvents = events,
            commands = commands,
            payload = obj.optString("payload").ifBlank { "extension/" },
            payloadFiles = obj.optInt("payloadFiles", 0),
            payloadBytes = obj.optLong("payloadBytes", 0L)
        )
    }

    /** 读包内的 `nebula/declarative.json`；不是声明式包（或读不动）返回 null。 */
    private fun readDeclarative(file: File): JSONObject? = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(DECLARATIVE_ENTRY) ?: return null
            val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            JSONObject(text)
        }
    }.getOrNull()

    private fun stringList(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optString(i).takeIf { it.isNotBlank() }
        }
    }

    private fun stringMap(obj: JSONObject): Map<String, String> = buildMap {
        obj.keys().forEach { key -> obj.opt(key)?.let { put(key, it.toString()) } }
    }

    companion object {
        const val DECLARATIVE_ENTRY = "nebula/declarative.json"

        @Volatile
        private var instance: DeclarativePluginLoader? = null

        /** 进程级单例：注册表本身是全局的，多个实例会让「谁写了最后一份」变得不可预测。 */
        fun of(context: Context): DeclarativePluginLoader =
            instance ?: synchronized(this) {
                instance ?: DeclarativePluginLoader(context.applicationContext).also { instance = it }
            }
    }
}
