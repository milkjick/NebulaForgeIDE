package com.nebulaforge.app.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 插件市场条目：把「Nebula 插件仓库 / Open VSX / JetBrains Marketplace / MCP Registry」
 * 四种真实来源归一化成同一份 UI 模型。
 *
 * 关键取舍：只有 [Kind.NEBULA] 的条目能真正被本宿主加载（plugin.xml + dex 运行时），
 * 外部生态（VS Code 扩展、IntelliJ 插件）只能浏览/下载，UI 必须把这点显式告诉用户，
 * 不能给一个点了没反应的「安装」按钮。
 */
data class MarketEntry(
    val key: String,
    val sourceId: String,
    val sourceLabel: String,
    val id: String,
    val name: String,
    val vendor: String?,
    val version: String,
    val description: String?,
    val iconUrl: String?,
    val downloadUrl: String?,
    val pageUrl: String?,
    val remoteUrl: String? = null,
    val downloads: Long? = null,
    val rating: Double? = null,
    val kind: Kind,
    val extra: List<String> = emptyList()
) {
    enum class Kind(val display: String, val installable: Boolean) {
        NEBULA("Nebula 插件", true),
        VSX("VS Code 扩展（外部生态）", false),
        JETBRAINS("JetBrains 插件（外部生态）", false),
        MCP("MCP 服务", false)
    }
}

/** 市场源类型。 */
enum class MarketSourceKind(val display: String) {
    LOCAL_BUNDLE("内置离线插件"),
    OPEN_VSX("开源扩展市场"),
    JETBRAINS("IntelliJ 生态"),
    MCP_REGISTRY("MCP 服务注册表"),
    NEBULA_REPO("Nebula 插件仓库"),
    CUSTOM_HTTPS("自定义 HTTPS 仓库")
}

/** 一个市场源。 */
data class MarketSource(
    val id: String,
    val label: String,
    val description: String,
    val kind: MarketSourceKind,
    /** Nebula 仓库 / 自定义仓库的目录 URL；其它源在加载器里写死官方端点。 */
    val repositoryUrl: String? = null,
    val official: Boolean = false
) {
    /** 该源是否提供「可直接安装并加载」的插件（外部生态只提供浏览/下载）。 */
    val installable: Boolean
        get() = kind == MarketSourceKind.NEBULA_REPO ||
            kind == MarketSourceKind.CUSTOM_HTTPS ||
            kind == MarketSourceKind.LOCAL_BUNDLE
}

object PluginMarketRegistry {
    const val OPEN_VSX_SEARCH = "https://open-vsx.org/api/-/search"
    const val JETBRAINS_SEARCH = "https://plugins.jetbrains.com/api/searchPlugins"
    const val JETBRAINS_WEB = "https://plugins.jetbrains.com"
    const val MCP_REGISTRY = "https://registry.modelcontextprotocol.io/v0.1/servers"

    /** Nebula 官方目录地址（可通过「源管理」改成自建仓库；留空表示暂未配置）。 */
    const val DEFAULT_NEBULA_REPO = "https://raw.githubusercontent.com/nebulaforge-ide/plugin-registry/main/catalog.json"

    const val CUSTOM_ID = "custom"

    /** 随包内置的离线插件目录（assets/plugins），不依赖网络，安装后即可加载。 */
    const val BUNDLED_ID = "nebula-bundled"
    const val BUNDLED_ASSET_PATH = "plugins/catalog.json"

    val builtIn: List<MarketSource> = listOf(
        MarketSource(
            id = BUNDLED_ID,
            label = "星弦内置插件（离线可用）",
            description = "随应用打包的插件目录，装完就能用、不需要联网，可完整走通安装/授权/加载/卸载",
            kind = MarketSourceKind.LOCAL_BUNDLE,
            official = true
        ),
        MarketSource(
            id = "open-vsx",
            label = "Open VSX 开源扩展市场",
            description = "Eclipse 基金会的开放扩展市场，含真实图标、下载量与版本，约 4000+ 扩展",
            kind = MarketSourceKind.OPEN_VSX
        ),
        MarketSource(
            id = "jetbrains",
            label = "JetBrains 插件市场",
            description = "JetBrains 官方市场检索结果，用于了解主流 IDE 插件生态",
            kind = MarketSourceKind.JETBRAINS
        ),
        MarketSource(
            id = "mcp",
            label = "官方 MCP 源",
            description = "Model Context Protocol 官方注册表，可直接连接远端 MCP 服务",
            kind = MarketSourceKind.MCP_REGISTRY
        ),
        MarketSource(
            id = "nebula-official",
            label = "自建 Nebula 仓库",
            description = "需填自己的 HTTPS 仓库地址（plugin.xml + dex）。默认示例地址实测返回 404，请勿直接使用",
            kind = MarketSourceKind.NEBULA_REPO,
            repositoryUrl = DEFAULT_NEBULA_REPO,
            official = true
        ),
        MarketSource(
            id = CUSTOM_ID,
            label = "自定义 HTTPS 仓库",
            description = "自建/私有插件仓库，需返回 {\"plugins\":[…]} 索引结构",
            kind = MarketSourceKind.CUSTOM_HTTPS
        )
    )

    fun nebulaOfficial(repositoryUrl: String?): MarketSource = builtIn.first { it.id == "nebula-official" }
        .copy(repositoryUrl = repositoryUrl?.takeIf { it.isNotBlank() })

    fun custom(repositoryUrl: String): MarketSource = MarketSource(
        id = CUSTOM_ID,
        label = "自定义仓库",
        description = repositoryUrl,
        kind = MarketSourceKind.CUSTOM_HTTPS,
        repositoryUrl = repositoryUrl
    )

    /** 校验自定义仓库地址；返回错误文案，null 表示合法。 */
    fun validateRepository(url: String): String? = when {
        url.isBlank() -> "仓库地址不能为空"
        !url.startsWith("https://") -> "市场仓库必须使用 HTTPS"
        else -> null
    }
}

/** 市场源与自定义仓库的持久化。 */
class PluginSourceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nebula_plugin_sources", Context.MODE_PRIVATE)

    var selectedSourceId: String
        get() {
            val stored = prefs.getString(KEY_SELECTED, null)
                ?: return PluginMarketRegistry.BUNDLED_ID
            // 2.3.x 的默认源是 Open VSX（外部生态，只能浏览不能安装），用户会误以为「插件市场没有安装按钮」。
            // 2.4.0 起默认走内置离线源；这里对旧默认值做一次性迁移，之后尊重用户的手动选择。
            if (stored == LEGACY_DEFAULT_SOURCE && !prefs.getBoolean(KEY_MIGRATED, false)) {
                prefs.edit()
                    .putString(KEY_SELECTED, PluginMarketRegistry.BUNDLED_ID)
                    .putBoolean(KEY_MIGRATED, true)
                    .apply()
                return PluginMarketRegistry.BUNDLED_ID
            }
            if (!prefs.getBoolean(KEY_MIGRATED, false)) {
                prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            }
            return stored
        }
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    /** Nebula 官方目录地址（可改为自建仓库）。 */
    var nebulaRepositoryUrl: String
        get() = prefs.getString(KEY_NEBULA_REPO, PluginMarketRegistry.DEFAULT_NEBULA_REPO)
            ?: PluginMarketRegistry.DEFAULT_NEBULA_REPO
        set(value) = prefs.edit().putString(KEY_NEBULA_REPO, value.trim()).apply()

    /** 用户添加的自定义仓库列表。 */
    fun customRepositories(): List<String> =
        (prefs.getStringSet(KEY_CUSTOM_REPOS, emptySet()) ?: emptySet()).filter { it.isNotBlank() }.sorted()

    fun addCustomRepository(url: String) {
        prefs.edit().putStringSet(KEY_CUSTOM_REPOS, customRepositories().toSet() + url.trim()).apply()
    }

    fun removeCustomRepository(url: String) {
        prefs.edit().putStringSet(KEY_CUSTOM_REPOS, (customRepositories() - url).toSet()).apply()
    }

    private companion object {
        const val KEY_SELECTED = "selected_source"
        const val KEY_MIGRATED = "bundled_default_migrated"
        const val LEGACY_DEFAULT_SOURCE = "open-vsx"
        const val KEY_NEBULA_REPO = "nebula_repo"
        const val KEY_CUSTOM_REPOS = "custom_repos"
    }
}

/** 市场网络层：按源类型调用各自真实端点并归一化为 [MarketEntry]。 */
class PluginMarketClient(private val context: Context) {

    private val marketplace = PluginMarketplace(context)

    fun load(source: MarketSource, query: String, pageSize: Int = 40): List<MarketEntry> = when (source.kind) {
        MarketSourceKind.OPEN_VSX -> openVsx(source, query, pageSize)
        MarketSourceKind.JETBRAINS -> jetbrains(source, query, pageSize)
        MarketSourceKind.MCP_REGISTRY -> mcpRegistry(source, query, pageSize)
        MarketSourceKind.NEBULA_REPO -> nebulaRepository(source, source.repositoryUrl, query, pageSize)
        MarketSourceKind.CUSTOM_HTTPS -> nebulaRepository(source, source.repositoryUrl, query, pageSize)
        // 内置离线源不走网络，直接由 PluginMarketplace.loadBundledCatalog() 读 assets。
        MarketSourceKind.LOCAL_BUNDLE -> emptyList()
    }

    // ---------- Open VSX（真实扩展市场：含图标 / 版本 / 下载量 / 评分） ----------

    private fun openVsx(source: MarketSource, query: String, pageSize: Int): List<MarketEntry> {
        val search = if (query.isBlank()) "" else "&query=" + URLEncoder.encode(query, "UTF-8")
        val json = getJsonObject("${PluginMarketRegistry.OPEN_VSX_SEARCH}?size=$pageSize&sortBy=downloadCount$search")
        val array = json.optJSONArray("extensions") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val namespace = o.optString("namespace").trim()
                val name = o.optString("name").trim()
                if (namespace.isBlank() || name.isBlank()) continue
                val files = o.optJSONObject("files")
                val id = "$namespace.$name"
                add(
                    MarketEntry(
                        key = "${source.id}:$id",
                        sourceId = source.id,
                        sourceLabel = source.label,
                        id = id,
                        name = o.optString("displayName").ifBlank { id },
                        vendor = namespace,
                        version = o.optString("version"),
                        description = o.optString("description").ifBlank { null },
                        iconUrl = files?.optString("icon")?.takeIf { it.startsWith("https://") },
                        downloadUrl = files?.optString("download")?.takeIf { it.startsWith("https://") },
                        pageUrl = o.optString("url").takeIf { it.startsWith("https://") },
                        downloads = o.optLong("downloadCount").takeIf { it > 0 },
                        rating = o.optDouble("averageRating").takeIf { !it.isNaN() && it > 0 },
                        kind = MarketEntry.Kind.VSX,
                        extra = buildList {
                            if (o.optBoolean("verified")) add("已认证发布者")
                            if (o.optBoolean("deprecated")) add("已废弃")
                        }
                    )
                )
            }
        }
    }

    // ---------- JetBrains Marketplace（真实检索：含下载量 / 评分 / 图标） ----------

    private fun jetbrains(source: MarketSource, query: String, pageSize: Int): List<MarketEntry> {
        val search = if (query.isBlank()) "" else "&search=" + URLEncoder.encode(query, "UTF-8")
        val json = getJsonObject("${PluginMarketRegistry.JETBRAINS_SEARCH}?max=$pageSize$search")
        val array = json.optJSONArray("plugins") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val xmlId = o.optString("xmlId").ifBlank { o.optString("id") }
                if (xmlId.isBlank()) continue
                val link = o.optString("link")
                // JetBrains 只返回 SVG 图标，Android 的 BitmapFactory 解不了 SVG，
                // 因此这里只接受位图；SVG 交给占位头像，避免显示成空白方块。
                val icon = o.optString("icon").takeIf {
                    it.isNotBlank() && !it.endsWith(".svg", true)
                }?.let { if (it.startsWith("http")) it else PluginMarketRegistry.JETBRAINS_WEB + it }
                add(
                    MarketEntry(
                        key = "${source.id}:$xmlId",
                        sourceId = source.id,
                        sourceLabel = source.label,
                        id = xmlId,
                        name = o.optString("name").ifBlank { xmlId },
                        vendor = o.optJSONObject("vendor")?.optString("name")?.ifBlank { null },
                        version = "",
                        description = o.optString("preview").ifBlank { null },
                        iconUrl = icon,
                        downloadUrl = null,
                        pageUrl = link.takeIf { it.isNotBlank() }?.let { PluginMarketRegistry.JETBRAINS_WEB + it },
                        downloads = o.optLong("downloads").takeIf { it > 0 },
                        rating = o.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
                        kind = MarketEntry.Kind.JETBRAINS,
                        extra = listOfNotNull(
                            o.optString("pricingModel").takeIf { it.isNotBlank() }?.let { "授权：$it" }
                        )
                    )
                )
            }
        }
    }

    // ---------- MCP Registry（真实注册表：可解析远端 URL 供直接连接） ----------

    private fun mcpRegistry(source: MarketSource, query: String, pageSize: Int): List<MarketEntry> {
        val json = getJsonObject("${PluginMarketRegistry.MCP_REGISTRY}?limit=$pageSize")
        val array = json.optJSONArray("servers") ?: JSONArray()
        val needle = query.trim().lowercase()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val server = item.optJSONObject("server") ?: item
                val id = server.optString("name").trim()
                if (id.isBlank()) continue
                val title = server.optString("title").ifBlank { id }
                val description = server.optString("description").ifBlank { null }
                if (needle.isNotEmpty() &&
                    !"$id $title ${description.orEmpty()}".lowercase().contains(needle)
                ) continue
                val remotes = server.optJSONArray("remotes")
                val remoteUrl = remotes?.let { a ->
                    (0 until a.length()).asSequence()
                        .mapNotNull { a.optJSONObject(it)?.optString("url") }
                        .firstOrNull { it.startsWith("https://") }
                }
                val icon = server.optJSONArray("icons")?.let { a ->
                    (0 until a.length()).asSequence()
                        .mapNotNull { a.optJSONObject(it)?.optString("src") }
                        .firstOrNull { it.startsWith("https://") }
                }
                add(
                    MarketEntry(
                        key = "${source.id}:$id",
                        sourceId = source.id,
                        sourceLabel = source.label,
                        id = id,
                        name = title,
                        vendor = server.optString("repository").takeIf { it.isNotBlank() },
                        version = "",
                        description = description,
                        iconUrl = icon,
                        downloadUrl = null,
                        pageUrl = server.optString("websiteUrl").takeIf { it.startsWith("https://") },
                        remoteUrl = remoteUrl,
                        kind = MarketEntry.Kind.MCP,
                        extra = listOfNotNull(
                            remoteUrl?.let { "远端：$it" } ?: "未提供可直连的 HTTPS Remote"
                        )
                    )
                )
            }
            // MCP 注册表对同一 server 会返回多个版本条目，name（key 的来源）因此重复；
            // 直接喂给 LazyColumn 会因 key 冲突崩溃（实测 key "mcp:ac.inference.sh/mcp" 重复），
            // 这里按 id 去重，保留第一个匹配项。
        }.distinctBy { it.id }
    }

    // ---------- Nebula 仓库 / 自定义仓库（可直接安装并加载） ----------

    private fun nebulaRepository(
        source: MarketSource,
        repositoryUrl: String?,
        query: String,
        pageSize: Int
    ): List<MarketEntry> {
        val url = repositoryUrl?.takeIf { it.isNotBlank() }
            ?: error("该源尚未配置仓库地址，请在「源管理」里填写 HTTPS 目录 URL")
        return marketplace.loadCatalog(url, pageSize).map { plugin ->
            val matched = query.isBlank() ||
                "${plugin.id} ${plugin.name} ${plugin.description.orEmpty()}".contains(query, true)
            plugin.toEntry(source, matched)
        }.filter { it != null }.map { it!! }
    }

    // ---------- HTTP ----------

    private fun getJsonObject(url: String): JSONObject {
        require(url.startsWith("https://")) { "市场源必须使用 HTTPS：$url" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NebulaForge-IDE/2")
        }
        return try {
            connection.connect()
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code（$url）" }
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun PluginMarketplace.MarketPlugin.toEntry(source: MarketSource, matched: Boolean): MarketEntry? {
        if (!matched) return null
        return MarketEntry(
            key = "${source.id}:$id:$version",
            sourceId = source.id,
            sourceLabel = source.label,
            id = id,
            name = name,
            vendor = vendor,
            version = version,
            description = description,
            iconUrl = iconUrl,
            downloadUrl = downloadUrl,
            pageUrl = downloadUrl,
            downloads = null,
            rating = null,
            kind = MarketEntry.Kind.NEBULA,
            extra = buildList {
                if (aiEnhanced) add("AI 增强")
                if (dependencies.isNotEmpty()) add("依赖：${dependencies.joinToString()}")
                if (extensions.isNotEmpty()) add("扩展点：${extensions.joinToString()}")
            }
        )
    }
}
