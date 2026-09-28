package com.nebulaforge.app.plugins

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * VSIX（VSCode 扩展包）→ Nebula 插件包 转换器。
 *
 * ## 转换出的插件由两层组成
 * 1. **声明式贡献**：`contributes.languages`（语言关联）、`contributes.snippets`（代码片段）、
 *    `contributes.configurationDefaults`（编辑器默认设置）——纯数据，翻译成
 *    `nebula/declarative.json`，编辑器内核（语言判定 / 补全片段）直接消费；
 * 2. **JS 逻辑**：`main` 指向的入口 + `commands` / `activationEvents`——由 JS 扩展宿主
 *    （`assets/extension-host/bootstrap.js`，跑在 guest 的 node 里）执行。本转换器把扩展
 *    负载（`extension/` 下全部文件：package.json、入口、依赖）一并打进包里，并在 `declarative.json`
 *    的 `js` 段登记入口、活化事件、命令清单与负载体积。
 *
 * 仍无法生效的贡献点（menus / keybindings / grammar / 自定义视图等）逐项列进 `unsupported`，
 * UI 必须如实展示——不假装支持。
 *
 * ## 产物
 * 一个符合 Nebula 插件格式的 zip（`plugin.xml` + `nebula/declarative.json` + 转换报告 + JS 负载），
 * 可直接交给 `PluginMarketplace.installLocalPackage()` 安装，和原生插件一样出现在「已安装」里。
 */
class VsixAdapter(private val context: Context) {

    /** 单包转换结果的摘要，供 UI 如实汇报「装了什么、什么没生效」。 */
    data class Result(
        val packageFile: File,
        val pluginId: String,
        val displayName: String,
        val version: String,
        val languageCount: Int,
        val extensionCount: Int,
        val snippetCount: Int,
        val defaultsCount: Int,
        val unsupported: List<Unsupported>,
        /** JS 逻辑信息；为空表示这个扩展本来就没有可执行逻辑（纯声明式）。 */
        val js: JsInfo? = null
    ) {
        /** 是否有任何东西能在编辑器里真正生效（含 JS 逻辑，它会在扩展宿主里跑起来）。 */
        val hasEffectiveContribution: Boolean
            get() = extensionCount > 0 || snippetCount > 0 || defaultsCount > 0 || js != null

        val unsupportedCount: Int get() = unsupported.sumOf { it.count }
    }

    /**
     * 打进转换包的 JS 负载信息：宿主据此在激活时提取 `extension/` 并交给 node 执行。
     * `main` 为空但有命令/活化事件的扩展也存在（它们只注册声明式的东西），仍要建宿主。
     */
    data class JsInfo(
        val main: String?,
        val activationEvents: List<String>,
        val commands: List<Command>,
        val payloadFiles: Int,
        val payloadBytes: Long,
        /** 因体积超限而未打进负载的文件（如实列出，避免“静默缺文件”）。 */
        val skippedPayload: List<String>
    )

    data class Command(val id: String, val title: String)

    data class Unsupported(val point: String, val reason: String, val count: Int)

    /**
     * 判断一个文件是不是 VSIX：标准 VSIX 是 zip，扩展清单在 `extension/package.json`；
     * 少数打包工具会把 package.json 放在根目录，这里一并兼容。
     */
    fun isVsix(file: File): Boolean = runCatching {
        if (!file.isFile) return@runCatching false
        ZipFile(file).use { zip ->
            zip.getEntry(MANIFEST_IN_EXTENSION) != null || zip.getEntry(MANIFEST_AT_ROOT) != null
        }
    }.getOrDefault(false)

    /**
     * 执行转换。
     *
     * @param vsix 已下载/已选中的 .vsix 文件
     * @param outDir 产物目录（一般用 cacheDir：装完即弃，不需要长期占空间）
     */
    fun convert(vsix: File, outDir: File): Result {
        require(vsix.isFile) { "VSIX 文件不存在：${vsix.name}" }
        outDir.mkdirs()

        ZipFile(vsix).use { zip ->
            val manifestPath = when {
                zip.getEntry(MANIFEST_IN_EXTENSION) != null -> MANIFEST_IN_EXTENSION
                zip.getEntry(MANIFEST_AT_ROOT) != null -> MANIFEST_AT_ROOT
                else -> error("不是 VSIX：包内没有 package.json（${vsix.name}）")
            }
            val root = manifestPath.substringBeforeLast('/', "")
            val manifest = JSONObject(readText(zip, manifestPath) ?: error("package.json 为空"))

            val publisher = manifest.optString("publisher").ifBlank { "unknown" }
            val rawName = manifest.optString("name").ifBlank { vsix.nameWithoutExtension }
            val version = manifest.optString("version").ifBlank { "0.0.0" }
            val displayName = manifest.optString("displayName").ifBlank { rawName }
            val description = manifest.optString("description")
            val repositoryUrl = manifest.optString("repository").takeIf { it.startsWith("http") }
                ?: manifest.optJSONObject("repository")?.optString("url")?.takeIf { it.startsWith("http") }

            val pluginId = "vscode." + sanitize("$publisher.$rawName")

            val contributes = manifest.optJSONObject("contributes")
            val report = ConversionReport()

            val languages = parseLanguages(contributes?.optJSONArray("languages"), pluginId, report)
            val snippets = parseSnippets(contributes?.optJSONArray("snippets"), zip, root, pluginId, report)
            val (globalDefaults, languageDefaults) =
                parseDefaults(contributes?.optJSONObject("configurationDefaults"), report)

            // JS 负载：把扩展目录（package.json / 入口 / 依赖）一起装进转换包，
            // 宿主激活时提取到运行时目录，再交给 guest 的 node 执行。
            val payload = planPayload(zip, root)
            val jsInfo = parseJs(manifest, contributes, payload)
            if (jsInfo != null) {
                report.note(
                    "已支持 JS 逻辑：入口 ${jsInfo.main ?: "（无 main，仅注册命令）"}，" +
                        "活化事件 ${jsInfo.activationEvents.size} 条，命令 ${jsInfo.commands.size} 个"
                )
                report.note("已内置扩展负载：${jsInfo.payloadFiles} 个文件 / ${humanBytes(jsInfo.payloadBytes)}")
                jsInfo.skippedPayload.forEach { report.note("未内置（体积超限）：$it") }
            } else {
                report.note("该扩展没有 main / 命令 / 活化事件，属于纯声明式扩展：无需 JS 负载")
            }
            // 其余本宿主没有对应实现的贡献点，逐项列清。
            contributes?.let { c -> reportUnsupportedContributions(c, report) }

            val payloadWriter: ((ZipOutputStream) -> Unit)? =
                if (jsInfo != null) ({ out -> writePayload(zip, root, payload, out) }) else null

            val descriptorXml = buildPluginXml(pluginId, displayName, version, publisher, repositoryUrl, description)
            val declarativeJson = buildDeclarativeJson(
                pluginId, displayName, version, vsix.name, publisher, rawName,
                manifest.optJSONObject("engines")?.optString("vscode").orEmpty(),
                languages, snippets, globalDefaults, languageDefaults, jsInfo, report
            )

            val target = File(outDir, "${sanitize(pluginId)}-$version.nebula.zip")
            writePackage(
                target, descriptorXml, declarativeJson,
                report.render(pluginId, displayName, version),
                payloadWriter
            )

            return Result(
                packageFile = target,
                pluginId = pluginId,
                displayName = displayName,
                version = version,
                languageCount = languages.size,
                extensionCount = languages.sumOf { (it["extensions"] as List<*>).size },
                snippetCount = snippets.size,
                defaultsCount = globalDefaults.size + languageDefaults.values.sumOf { it.size },
                unsupported = report.entries(),
                js = jsInfo
            )
        }
    }

    // ------------------------------------------------------------------ 解析

    private fun parseLanguages(raw: JSONArray?, pluginId: String, report: ConversionReport): List<Map<String, Any>> {
        if (raw == null) return emptyList()
        val out = ArrayList<Map<String, Any>>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            val extensions = strings(item.opt("extensions")).map { it.lowercase().removePrefix(".").removePrefix("*.") }
                .filter { it.isNotEmpty() }
            val filenames = strings(item.opt("filenames")).map { it.lowercase() }.filter { it.isNotEmpty() }
            val aliases = strings(item.opt("aliases")).filter { it.isNotBlank() }
            if (item.opt("configuration") != null) {
                // 括号/注释/自动闭合规则要语言配置支持，本宿主没有 → 如实登记，只说扩展名生效部分。
                report.unsupported(
                    "contributes.languages[$id].configuration",
                    "语言的括号/注释/自动闭合规则未接入，只有扩展名关联生效",
                    1
                )
            }
            if (extensions.isEmpty() && filenames.isEmpty()) {
                // 只声明 id、不给文件名匹配规则的语言关联对编辑器没有作用，如实计入报告。
                report.unsupported("contributes.languages[$id]", "只声明语言 id，没有扩展名/文件名规则，无法参与判定", 1)
                continue
            }
            out += mapOf(
                "id" to id,
                "extensions" to extensions.distinct(),
                "filenames" to filenames.distinct(),
                "aliases" to aliases
            )
        }
        return out
    }

    /**
     * 片段来源有两种：`path` 指向包内 JSON 文件（主流），或直接内联 `body`。
     * 单个片段文件里的每个条目 = 一条片段（VS Code 片段文件格式）。
     */
    private fun parseSnippets(
        raw: JSONArray?,
        zip: ZipFile,
        root: String,
        pluginId: String,
        report: ConversionReport
    ): List<Map<String, Any>> {
        if (raw == null) return emptyList()
        val out = ArrayList<Map<String, Any>>(raw.length())
        for (i in 0 until raw.length()) {
            val entry = raw.optJSONObject(i) ?: continue
            val language = entry.optString("language").trim()
            if (language.isEmpty()) continue

            val path = entry.optString("path").trim()
            if (path.isNotEmpty()) {
                val entryName = resolve(zip, root, path)
                val text = entryName?.let { readText(zip, it) }
                if (text == null) {
                    report.unsupported("contributes.snippets[$language] ${path}", "包内找不到片段文件", 1)
                    continue
                }
                val parsed = runCatching { JSONObject(text) }.getOrNull()
                if (parsed == null) {
                    report.unsupported("contributes.snippets[$language] ${path}", "片段文件不是合法 JSON", 1)
                    continue
                }
                parsed.keys().forEach { key ->
                    val body = parsed.optJSONObject(key) ?: return@forEach
                    out += snippetMap(language, key, body, pluginId, report) ?: return@forEach
                }
            } else if (entry.has("body")) {
                out += snippetMap(language, entry.optString("name").ifBlank { "snippet-${out.size}" }, entry, pluginId, report)
                    ?: continue
            }
        }
        return out
    }

    private fun snippetMap(
        language: String,
        name: String,
        body: JSONObject,
        pluginId: String,
        report: ConversionReport
    ): Map<String, Any>? {
        val bodyText = when (val raw = body.opt("body")) {
            is JSONArray -> (0 until raw.length()).joinToString("\n") { raw.optString(it) }
            null -> ""
            else -> raw.toString()
        }
        if (bodyText.isBlank()) {
            report.unsupported("contributes.snippets.$name", "片段内容为空，无法插入", 1)
            return null
        }
        val prefixes = strings(body.opt("prefix")).filter { it.isNotBlank() }
        if (prefixes.isEmpty()) {
            report.unsupported("contributes.snippets.$name", "片段没有 prefix，无法被触发", 1)
            return null
        }
        val description = body.optString("description").takeIf { it.isNotBlank() }
            ?: strings(body.opt("scope")).firstOrNull()
        return buildMap {
            put("language", language)
            put("name", name)
            put("prefix", prefixes)
            put("body", bodyText)
            description?.let { put("description", it) }
        }
    }

    /**
     * `contributes.configurationDefaults` 有两种写法：
     * - 全局：`{"editor.tabSize": 2}`；
     * - 语言限定：`{"[javascript]": {"editor.tabSize": 2}}`。
     *
     * 只有缩进宽度是本编辑器真正消费的设置（[DeclarativeContributions.editorTabSizeFor]），
     * 其余一律进报告 —— 不假装支持一个没有落点的设置项。
     */
    private fun parseDefaults(
        raw: JSONObject?,
        report: ConversionReport
    ): Pair<Map<String, String>, Map<String, Map<String, String>>> {
        if (raw == null) return emptyMap<String, String>() to emptyMap()
        val global = LinkedHashMap<String, String>()
        val scoped = LinkedHashMap<String, Map<String, String>>()

        raw.keys().forEach { key ->
            if (key.startsWith("[") && key.endsWith("]")) {
                val languageId = key.substring(1, key.length - 1).trim()
                val values = raw.optJSONObject(key)
                if (values == null || languageId.isEmpty()) return@forEach
                val consumed = LinkedHashMap<String, String>()
                values.keys().forEach { inner ->
                    val value = values.opt(inner)
                    if (inner == TAB_SIZE && (value is Number || value is String)) {
                        consumed[inner] = value.toString()
                    } else if (inner == INSERT_SPACES && value == true) {
                        // 编辑器缩进恒为空格（见 AutoIndentNewlineHandler），值为 true 时无需额外记录。
                        Unit
                    } else {
                        report.unsupported("configurationDefaults.$key.$inner", defaultUnsupportedReason(inner), 1)
                    }
                }
                if (consumed.isNotEmpty()) scoped[languageId] = consumed
                return@forEach
            }
            val value = raw.opt(key)
            if (key == TAB_SIZE && (value is Number || value is String)) {
                global[key] = value.toString()
            } else {
                report.unsupported("configurationDefaults.$key", defaultUnsupportedReason(key), 1)
            }
        }
        return global to scoped
    }

    private fun defaultUnsupportedReason(key: String): String = when (key) {
        INSERT_SPACES -> "编辑器缩进固定为空格，无法切换为制表符"
        else -> "本编辑器没有对应设置项"
    }

    /**
     * 未生效贡献点的登记规则：**白名单之外一律登记**。
     *
     * 反过来写（只登记已知的不支持项）会静默丢东西 —— 实测 Volar 就带了
     * `jsonValidation` / `breakpoints` / `semanticTokenScopes`，它们不在任何手工清单里，
     * 于是「转换报告」会说一切正常，而用户实际什么都没得到。宁可多报，不可漏报。
     */
    private fun reportUnsupportedContributions(contributes: JSONObject, report: ConversionReport) {
        contributes.keys().forEach { key ->
            if (key in SUPPORTED_CONTRIBUTES) return@forEach
            val value = contributes.opt(key) ?: return@forEach
            report.unsupported("contributes.$key", UNSUPPORTED_REASONS[key] ?: GENERIC_REASON, countOf(value))
        }
    }

    // ------------------------------------------------------------------ 产物

    private fun buildPluginXml(
        id: String, name: String, version: String, vendor: String, vendorUrl: String?, description: String
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<!-- 由 VSIX 转换生成：声明式贡献（语言关联/片段/编辑器默认设置）见 nebula/declarative.json -->\n")
        append("<plugin>\n")
        append("  <id>").append(xml(id)).append("</id>\n")
        append("  <name>").append(xml(name)).append("</name>\n")
        append("  <version>").append(xml(version)).append("</version>\n")
        append("  <vendor url=\"").append(xml(vendorUrl ?: "")).append("\">").append(xml(vendor)).append("</vendor>\n")
        if (description.isNotBlank()) append("  <description>").append(xml(description)).append("</description>\n")
        // 转换包不含 dex，没有可实例化的扩展点实现；贡献由 declarative.json 驱动。
        append("  <apiVersion min=\"1\" max=\"1\"/>\n")
        append("</plugin>\n")
    }

    private fun buildDeclarativeJson(
        pluginId: String, displayName: String, version: String, sourceName: String, publisher: String,
        extensionName: String, enginesVscode: String,
        languages: List<Map<String, Any>>, snippets: List<Map<String, Any>>,
        globalDefaults: Map<String, String>, languageDefaults: Map<String, Map<String, String>>,
        js: JsInfo?, report: ConversionReport
    ): String {
        val root = JSONObject()
        root.put("format", 1)
        root.put("pluginId", pluginId)
        root.put("displayName", displayName)
        root.put("version", version)
        root.put(
            "source",
            JSONObject()
                .put("kind", "vsix")
                .put("file", sourceName)
                .put("publisher", publisher)
                .put("extension", extensionName)
                .put("enginesVscode", enginesVscode)
        )
        root.put("languages", JSONArray(languages.map { JSONObject(it) }))
        root.put("snippets", JSONArray(snippets.map { JSONObject(it) }))
        if (globalDefaults.isNotEmpty()) root.put("editorDefaults", JSONObject(globalDefaults))
        if (languageDefaults.isNotEmpty()) {
            root.put(
                "languageDefaults",
                JSONObject(languageDefaults.mapValues { (_, values) -> JSONObject(values) })
            )
        }
        if (js != null) {
            // JS 段：宿主据此建 Node 扩展宿主——入口、活化事件、命令清单与负载体积。
            root.put(
                "js",
                JSONObject().apply {
                    put("main", js.main ?: JSONObject.NULL)
                    put("activationEvents", JSONArray(js.activationEvents))
                    put(
                        "commands",
                        JSONArray(js.commands.map { JSONObject().put("command", it.id).put("title", it.title) })
                    )
                    put("payload", "extension/")
                    put("payloadFiles", js.payloadFiles)
                    put("payloadBytes", js.payloadBytes)
                }
            )
        }
        root.put(
            "unsupported",
            JSONArray(report.entries().map {
                JSONObject().put("point", it.point).put("reason", it.reason).put("count", it.count)
            })
        )
        return root.toString(2)
    }

    private fun writePackage(
        target: File, pluginXml: String, declarativeJson: String, reportText: String,
        payload: ((ZipOutputStream) -> Unit)? = null
    ) {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            fun put(name: String, content: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            put("plugin.xml", pluginXml)
            put("nebula/declarative.json", declarativeJson)
            put("nebula/conversion-report.txt", reportText)
            payload?.invoke(out)
        }
    }

    /** 转换报告：用户解包或从插件详情页就能看到「转换器到底做了什么判断」。 */
    private inner class ConversionReport {
        private val items = LinkedHashMap<String, Unsupported>()
        private val notes = ArrayList<String>()

        /** 记录一条「已支持 / 已内置 / 已跳过」的说明，直接出现在转换报告里。 */
        fun note(text: String) {
            notes += text
        }

        fun unsupported(point: String, reason: String, count: Int) {
            if (count <= 0) return
            val key = "$point|$reason"
            items[key] = (items[key]?.let { it.copy(count = it.count + count) } ?: Unsupported(point, reason, count))
        }

        fun unsupportedIfPresent(array: JSONArray?, point: String, reason: String) {
            if (array != null && array.length() > 0) unsupported(point, reason, array.length())
        }

        fun entries(): List<Unsupported> = items.values.toList()

        fun render(pluginId: String, displayName: String, version: String): String = buildString {
            append("VSIX → Nebula 插件 转换报告\n")
            append("插件：$displayName（$pluginId $version）\n")
            append("转换方式：声明式贡献 → nebula/declarative.json（编辑器内核直接消费）；\n")
            append("　　　　　JS 逻辑 → extension/ 负载 + js 段元数据（由 JS 扩展宿主在 guest 的 node 里执行）。\n\n")
            if (notes.isNotEmpty()) {
                append("转换说明：\n")
                notes.forEach { append("  · ").append(it).append('\n') }
                append('\n')
            }
            append("仍无法生效的贡献点（${entries().sumOf { it.count }} 项）：\n")
            if (entries().isEmpty()) append("  （无）\n")
            entries().forEach { append("  - ${it.point}：${it.reason}（${it.count} 项）\n") }
            append("\n详情见 nebula/declarative.json\n")
        }
    }

    // ------------------------------------------------------------------ JS 负载

    /** 负载体积上限：单文件 8 MB、总量 64 MB（避免转换出几百 MB 的包）。 */
    private data class PayloadPlan(val entries: List<ZipEntry>, val totalBytes: Long, val skipped: List<String>)

    /**
     * 规划要打进转换包的扩展负载：VSIX 里 `extension/` 下的每个文件（package.json、入口、
     * 依赖都在里面），按体积上限过滤，并如实记录被跳过的文件。
     */
    private fun planPayload(zip: ZipFile, root: String): PayloadPlan {
        val prefix = if (root.isEmpty()) "" else "$root/"
        val entries = ArrayList<ZipEntry>()
        val skipped = ArrayList<String>()
        var total = 0L
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            if (entry.isDirectory) continue
            if (prefix.isNotEmpty() && !entry.name.startsWith(prefix)) continue
            val relative = entry.name.removePrefix(prefix)
            if (relative.isEmpty()) continue
            // VSIX 自身的包清单不属于扩展负载。
            if (relative.equals("[Content_Types].xml", true)) continue
            if (relative.endsWith(".vsixmanifest", true)) continue
            val size = if (entry.size >= 0) entry.size else 0L
            if (size > MAX_PAYLOAD_FILE_BYTES) {
                skipped.add("$relative（${humanBytes(size)}，超过单文件上限 ${humanBytes(MAX_PAYLOAD_FILE_BYTES)}）")
                continue
            }
            if (total + size > MAX_PAYLOAD_TOTAL_BYTES) {
                skipped.add("$relative（超过负载总量上限 ${humanBytes(MAX_PAYLOAD_TOTAL_BYTES)}）")
                continue
            }
            total += size
            entries.add(entry)
        }
        return PayloadPlan(entries, total, skipped)
    }

    /** 把规划好的负载写进转换包，统一放到 `extension/` 前缀下（宿主提取时的根目录）。 */
    private fun writePayload(zip: ZipFile, root: String, plan: PayloadPlan, out: ZipOutputStream) {
        val prefix = if (root.isEmpty()) "" else "$root/"
        for (entry in plan.entries) {
            out.putNextEntry(ZipEntry("extension/" + entry.name.removePrefix(prefix)))
            zip.getInputStream(entry).use { input -> input.copyTo(out) }
            out.closeEntry()
        }
    }

    /**
     * 解析扩展的 JS 逻辑信息；没有入口 / 命令 / 活化事件时返回 null（纯声明式扩展）。
     * VS Code 1.74+ 起命令型扩展可以省略 `activationEvents`，所以 `main` 才是最关键的信号。
     */
    private fun parseJs(manifest: JSONObject, contributes: JSONObject?, payload: PayloadPlan): JsInfo? {
        val main = manifest.optString("main").trim().takeIf { it.isNotEmpty() }
            ?: manifest.optJSONObject("browser")?.optString("main")?.trim()?.takeIf { it.isNotEmpty() }

        val activationEvents = ArrayList<String>()
        manifest.optJSONArray("activationEvents")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotEmpty() }?.let { activationEvents.add(it) }
            }
        }

        val commands = ArrayList<Command>()
        contributes?.optJSONArray("commands")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("command").trim()
                if (id.isEmpty()) continue
                commands.add(Command(id, item.optString("title").trim()))
            }
        }

        if (main == null && activationEvents.isEmpty() && commands.isEmpty()) return null
        return JsInfo(
            main = main,
            activationEvents = activationEvents,
            commands = commands,
            payloadFiles = payload.entries.size,
            payloadBytes = payload.totalBytes,
            skippedPayload = payload.skipped
        )
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------ 工具

    private fun readText(zip: ZipFile, name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 包内路径解析：`./snippets/x.json`、`snippets/x.json`、`/snippets/x.json` 都要落到 `extension/` 下。 */
    private fun resolve(zip: ZipFile, root: String, rawPath: String): String? {
        val cleaned = rawPath.removePrefix("./").removePrefix("/")
        val candidates = listOfNotNull(
            if (root.isNotEmpty()) "$root/$cleaned" else cleaned,
            cleaned
        )
        return candidates.firstOrNull { zip.getEntry(it) != null }
    }

    private fun strings(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf { s -> s.isNotBlank() } }
        is String -> listOf(value)
        else -> listOf(value.toString())
    }

    private fun countOf(value: Any): Int = when (value) {
        is JSONArray -> value.length()
        is JSONObject -> value.length()
        else -> 1
    }

    private fun sanitize(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")

    private fun xml(raw: String): String = raw
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private companion object {
        const val MANIFEST_IN_EXTENSION = "extension/package.json"
        const val MANIFEST_AT_ROOT = "package.json"
        const val TAB_SIZE = "editor.tabSize"
        const val INSERT_SPACES = "editor.insertSpaces"

        /** 真正被本宿主消费的贡献点；不在这个集合里的一律进转换报告。 */
        val SUPPORTED_CONTRIBUTES = setOf("languages", "snippets", "configurationDefaults", "commands")

        const val GENERIC_REASON = "本宿主没有对应实现（该贡献点需要 VS Code 原生 UI / 运行时能力）"

        /** 常见贡献点的具体原因（说清楚为什么不行，而不是笼统「不支持」）。 */
        val UNSUPPORTED_REASONS = mapOf(
            "menus" to "菜单体系未接入（命令本身已可由扩展宿主执行）",
            "keybindings" to "按键绑定未接入（命令本身已可由扩展宿主执行）",
            "configuration" to "扩展设置页未接入",
            "grammars" to "TextMate 语法引擎尚未内置",
            "themes" to "编辑器暂不支持切换颜色主题",
            "iconThemes" to "编辑器暂不支持图标主题",
            "fileIconThemes" to "文件树暂不支持图标主题",
            "semanticTokenScopes" to "语义高亮映射未接入",
            "taskDefinitions" to "任务定义未接入（本宿主使用自己的任务/构建体系）",
            "debuggers" to "调试适配器未接入",
            "breakpoints" to "断点规则未接入",
            "views" to "自定义视图未接入",
            "viewsContainers" to "自定义视图容器未接入",
            "notebooks" to "笔记本视图无对应实现",
            "walkthroughs" to "欢迎页引导无对应实现",
            "authentication" to "认证提供方未接入",
            "localizations" to "扩展自身的界面本地化不适用于本宿主",
            "problemMatchers" to "问题匹配器未接入（构建输出解析使用自己的格式）",
            "jsonValidation" to "JSON Schema 在线校验未接入",
            "languageModelTools" to "AI 工具注册未接入",
            "chatParticipants" to "聊天参与者未接入"
        )

        /** 打进转换包的 JS 负载体积上限。 */
        const val MAX_PAYLOAD_FILE_BYTES = 8L * 1024 * 1024
        const val MAX_PAYLOAD_TOTAL_BYTES = 64L * 1024 * 1024
    }
}
