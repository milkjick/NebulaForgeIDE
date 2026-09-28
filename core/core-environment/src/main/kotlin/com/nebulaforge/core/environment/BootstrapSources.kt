package com.nebulaforge.core.environment

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 工具链下载源（bootstrap 及其它 GitHub release 制品）。
 *
 * 设计目标：内置多个下载源（官方源 / 镜像源 / 加速源 / 用户自定义源），
 * 安装前先做轻量探测（Range 请求只读 1KB），按「用户首选 → 上次成功 → 实测最快」排序，
 * 下载失败时自动切换到下一个源，并把成功过的源记住。
 *
 * 背景：Termux bootstrap 只发布在 GitHub Releases，国内直连经常超时/被 reset；
 * 此前实现里「官方源」与「SourceForge 镜像」二选一且不做探测，一旦首选源不通就整体失败。
 */
data class BootstrapSource(
    val id: String,
    val label: String,
    val description: String,
    val kind: Kind,
    /** 下载地址模板，占位符：{tag} = release tag（去掉 bootstrap- 前缀）、{arch} = 架构标识 */
    val template: String,
    val official: Boolean = false
) {
    enum class Kind(val display: String) {
        OFFICIAL("官方源"), MIRROR("镜像源"), ACCELERATOR("加速源"), CUSTOM("自定义源")
    }

    fun url(tag: String, arch: String): String = template.replace("{tag}", tag).replace("{arch}", arch)
}

object BootstrapSourceCatalog {
    const val CUSTOM_ID = "custom"
    const val TEMPLATE_HINT = "https://your-mirror.example.com/bootstrap-{tag}/bootstrap-{arch}.zip"

    /** 内置源清单。所有地址均经真实 Range 探测（HTTP 206）确认可达。 */
    val builtIn: List<BootstrapSource> = listOf(
        BootstrapSource(
            id = "official-github",
            label = "官方源 · GitHub Releases",
            description = "Termux 官方发布，最权威；国内链路可能超时或被重置",
            kind = BootstrapSource.Kind.OFFICIAL,
            template = "https://github.com/termux/termux-packages/releases/download/bootstrap-{tag}/bootstrap-{arch}.zip",
            official = true
        ),
        BootstrapSource(
            id = "mirror-sourceforge",
            label = "镜像源 · SourceForge",
            description = "Termux 官方镜像项目（海外链路，速度一般但稳定）",
            kind = BootstrapSource.Kind.MIRROR,
            template = "https://sourceforge.net/projects/termux-packages.mirror/files/bootstrap-{tag}/bootstrap-{arch}.zip/download"
        ),
        BootstrapSource(
            id = "accel-ghproxy",
            label = "加速源 · gh-proxy.com",
            description = "GitHub 加速代理，实测国内延迟最低",
            kind = BootstrapSource.Kind.ACCELERATOR,
            template = "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download/bootstrap-{tag}/bootstrap-{arch}.zip"
        ),
        BootstrapSource(
            id = "accel-llkk",
            label = "加速源 · gh.llkk.cc",
            description = "GitHub 加速代理",
            kind = BootstrapSource.Kind.ACCELERATOR,
            template = "https://gh.llkk.cc/https://github.com/termux/termux-packages/releases/download/bootstrap-{tag}/bootstrap-{arch}.zip"
        ),
        BootstrapSource(
            id = "accel-ghproxy-net",
            label = "加速源 · ghproxy.net",
            description = "GitHub 加速代理",
            kind = BootstrapSource.Kind.ACCELERATOR,
            template = "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download/bootstrap-{tag}/bootstrap-{arch}.zip"
        ),
        BootstrapSource(
            id = "accel-ghfast",
            label = "加速源 · ghfast.top",
            description = "GitHub 加速代理",
            kind = BootstrapSource.Kind.ACCELERATOR,
            template = "https://ghfast.top/https://github.com/termux/termux-packages/releases/download/bootstrap-{tag}/bootstrap-{arch}.zip"
        )
    )

    fun custom(template: String) = BootstrapSource(
        id = CUSTOM_ID,
        label = "自定义源",
        description = "自建或私有镜像，模板需含 {tag} 与 {arch} 占位符",
        kind = BootstrapSource.Kind.CUSTOM,
        template = template
    )

    /** 模板校验，返回错误文案；null 表示合法。 */
    fun validateTemplate(template: String): String? = when {
        template.isBlank() -> "模板不能为空"
        !template.startsWith("https://") -> "自定义源必须使用 HTTPS"
        !template.contains("{tag}") || !template.contains("{arch}") ->
            "模板必须同时包含 {tag} 与 {arch} 两个占位符"
        else -> null
    }
}

/** 单个下载源的探测结果。 */
data class SourceProbe(
    val sourceId: String,
    val url: String,
    val reachable: Boolean,
    val httpCode: Int,
    val latencyMs: Long,
    val message: String
) {
    val statusLabel: String get() = if (reachable) "可用 · ${latencyMs}ms" else message
}

/** 下载源选择的持久化（SharedPreferences）。 */
class BootstrapSourceStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("nebula_bootstrap_source", Context.MODE_PRIVATE)

    /** 用户选定的源 id；[AUTO] 表示自动选择实测最快的可用源。 */
    var selectedSourceId: String
        get() = prefs.getString(KEY_SELECTED, AUTO) ?: AUTO
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    /** 自定义源模板（空表示未配置）。 */
    var customTemplate: String
        get() = prefs.getString(KEY_CUSTOM_TEMPLATE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM_TEMPLATE, value.trim()).apply()

    /** 最近一次下载成功的源，作为首选以外的第二顺位。 */
    var lastWorkingSourceId: String?
        get() = prefs.getString(KEY_LAST_WORKING, null)
        set(value) = prefs.edit().putString(KEY_LAST_WORKING, value).apply()

    /** 缓存到的 release tag，网络不可用时用它拼下载地址。 */
    var cachedReleaseTag: String?
        get() = prefs.getString(KEY_CACHED_TAG, null)
        set(value) = prefs.edit().putString(KEY_CACHED_TAG, value).apply()

    /** 是否优先使用 APK 内置用户态（离线秒装）。 */
    var preferEmbedded: Boolean
        get() = prefs.getBoolean(KEY_PREFER_EMBEDDED, true)
        set(value) = prefs.edit().putBoolean(KEY_PREFER_EMBEDDED, value).apply()

    fun resolveCustom(): BootstrapSource? {
        val template = customTemplate
        return if (template.isBlank() || BootstrapSourceCatalog.validateTemplate(template) != null) null
        else BootstrapSourceCatalog.custom(template)
    }

    /** 内置源 + 已配置的自定义源。 */
    fun allSources(): List<BootstrapSource> =
        BootstrapSourceCatalog.builtIn + listOfNotNull(resolveCustom())

    companion object {
        const val AUTO = "auto"
        private const val KEY_SELECTED = "selected_source"
        private const val KEY_CUSTOM_TEMPLATE = "custom_template"
        private const val KEY_LAST_WORKING = "last_working_source"
        private const val KEY_CACHED_TAG = "cached_release_tag"
        private const val KEY_PREFER_EMBEDDED = "prefer_embedded"
    }
}

/**
 * 下载源的探测与排序。探测只发 Range: bytes=0-1023 并读 1KB 就断开，
 * 不会真的把几十 MB 的包拉下来。
 */
class BootstrapSourceResolver(private val context: Context) {

    val store: BootstrapSourceStore = BootstrapSourceStore(context)

    fun allSources(): List<BootstrapSource> = store.allSources()

    suspend fun probe(source: BootstrapSource, tag: String, arch: String): SourceProbe =
        withContext(Dispatchers.IO) {
            val url = source.url(tag, arch)
            val started = System.currentTimeMillis()
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 8_000
                    readTimeout = 12_000
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=0-1023")
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                connection.connect()
                val code = connection.responseCode
                val latency = System.currentTimeMillis() - started
                if (code !in 200..299) {
                    SourceProbe(source.id, url, false, code, latency, "HTTP $code")
                } else {
                    val read = runCatching {
                        connection.inputStream.use { it.read(ByteArray(1024)) }
                    }.getOrDefault(-1)
                    if (read <= 0) SourceProbe(source.id, url, false, code, latency, "响应体为空")
                    else SourceProbe(source.id, url, true, code, latency, "OK")
                }
            } catch (t: Throwable) {
                SourceProbe(
                    source.id, url, false, -1,
                    System.currentTimeMillis() - started,
                    t.message ?: t.javaClass.simpleName
                )
            } finally {
                runCatching { connection?.disconnect() }
            }
        }

    /** 并发探测多个源（互不阻塞，UI 只等最慢的那个）。 */
    suspend fun probeAll(
        sources: List<BootstrapSource> = allSources(),
        tag: String,
        arch: String
    ): List<SourceProbe> = coroutineScope {
        sources.map { source -> async(Dispatchers.IO) { probe(source, tag, arch) } }.awaitAll()
    }

    /**
     * 按可用性排序的候选列表：[preferredId]/用户选定源 → 上次成功源 → 其它（可用优先、延迟低优先）。
     * 不可用的源不会丢弃，只是排在最后，避免探测误报导致完全无法下载。
     */
    suspend fun rankedCandidates(
        tag: String,
        arch: String,
        preferredId: String? = null
    ): List<Pair<BootstrapSource, SourceProbe>> {
        val sources = allSources()
        val probes = probeAll(sources, tag, arch).associateBy { it.sourceId }
        val requested = preferredId
            ?.takeIf { it != BootstrapSourceStore.AUTO }
            ?: store.selectedSourceId.takeIf { it != BootstrapSourceStore.AUTO }

        fun rank(source: BootstrapSource): Int = when {
            source.id == requested -> 0
            source.id == store.lastWorkingSourceId -> 1
            else -> 2
        }

        return sources
            .sortedWith(
                compareBy(
                    { rank(it) },
                    { if (probes[it.id]?.reachable == true) 0 else 1 },
                    { probes[it.id]?.latencyMs?.takeIf { l -> l >= 0 } ?: Long.MAX_VALUE }
                )
            )
            .map { source ->
                source to (probes[source.id] ?: SourceProbe(source.id, source.url(tag, arch), false, -1, -1, "未探测"))
            }
    }

    /** 下载成功后记录：下次优先复用这个源。 */
    fun rememberWorking(sourceId: String) {
        store.lastWorkingSourceId = sourceId
    }

    /** 记住可用的 release tag，网络不可用时仍能拼出下载地址。 */
    fun rememberTag(tag: String) {
        if (tag.isNotBlank()) store.cachedReleaseTag = tag
    }

    companion object {
        const val USER_AGENT = "NebulaForgeIDE/1"
    }
}
