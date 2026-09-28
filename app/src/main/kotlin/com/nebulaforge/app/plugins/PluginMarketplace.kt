package com.nebulaforge.app.plugins

import android.content.Context
import com.nebulaforge.core.environment.Environment
import com.nebulaforge.core.plugin.PluginDescriptor
import com.nebulaforge.core.plugin.PluginDescriptorReader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import android.util.Log

/** 插件市场网络层：读取 HTTPS JSON 仓库，并把经过描述符校验的插件安装到统一插件目录。 */
class PluginMarketplace(private val context: Context, private val hostApiVersion: Int = 1) {
    data class MarketPlugin(
        val id: String,
        val name: String,
        val version: String,
        val vendor: String?,
        val description: String?,
        val iconUrl: String?,
        val downloadUrl: String,
        val sha256: String?,
        val dependencies: List<String>,
        val aiEnhanced: Boolean,
        val extensions: List<String>,
        val permissions: List<String> = emptyList(),
        /** 非空表示插件包随应用内置（assets 路径），安装不需要网络。 */
        val bundledAsset: String? = null
    )

    /** 只做网络与解析；[limit] > 0 时最多返回前 N 条（市场分页用）。 */
    fun loadCatalog(repositoryUrl: String, limit: Int = 0): List<MarketPlugin> {
        val uri = URI(repositoryUrl.trim())
        require(uri.scheme.equals("https", true)) { "插件市场仓库必须使用 HTTPS" }
        val connection = (URL(repositoryUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NebulaForge-IDE/1")
        }
        connection.connect()
        connection.inputStream.use { input ->
            require(connection.responseCode in 200..299) { "市场请求失败：HTTP ${connection.responseCode}" }
            val root = JSONObject(input.bufferedReader(Charsets.UTF_8).readText())
            val array = root.optJSONArray("plugins") ?: JSONArray()
            return buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val id = o.optString("id").trim(); val version = o.optString("version").trim()
                    val url = o.optString("downloadUrl").trim()
                    if (id.isBlank() || version.isBlank() || url.isBlank()) continue
                    if (!URI(url).scheme.equals("https", true)) continue
                    add(MarketPlugin(id, o.optString("name", id), version, o.optString("vendor").ifBlank { null }, o.optString("description").ifBlank { null }, o.optString("iconUrl").trim().takeIf { it.startsWith("https://") }, url, o.optString("sha256").ifBlank { null }, o.optJSONArray("dependencies").toStringList(), o.optBoolean("aiEnhanced", false), o.optJSONArray("extensions").toStringList(), o.optJSONArray("permissions").toStringList(), null))
                    if (limit > 0 && size >= limit) break
                }
            }
        }
    }

    /**
     * 读取随包内置的离线插件目录（assets/plugins/catalog.json）。
     * 这条通道存在的意义：官方/自建仓库地址失效时，市场仍然有真实可安装的内容，
     * 而不是只能看到外部生态的只读列表。
     */
    fun loadBundledCatalog(): List<MarketPlugin> {
        val text = context.assets.open(BUNDLED_CATALOG_ASSET).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
        val array = JSONObject(text).optJSONArray("plugins") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val version = o.optString("version").trim()
                val bundled = o.optString("bundledAsset").trim()
                if (id.isBlank() || version.isBlank() || bundled.isBlank()) continue
                add(
                    MarketPlugin(
                        id = id,
                        name = o.optString("name", id),
                        version = version,
                        vendor = o.optString("vendor").ifBlank { null },
                        description = o.optString("description").ifBlank { null },
                        iconUrl = o.optString("iconUrl").trim().takeIf { it.isNotBlank() },
                        downloadUrl = "",
                        sha256 = o.optString("sha256").ifBlank { null },
                        dependencies = o.optJSONArray("dependencies").toStringList(),
                        aiEnhanced = o.optBoolean("aiEnhanced", false),
                        extensions = o.optJSONArray("extensions").toStringList(),
                        permissions = o.optJSONArray("permissions").toStringList(),
                        bundledAsset = bundled
                    )
                )
            }
        }
    }

    data class InstallCheck(val compatible: Boolean, val reasons: List<String>)

    fun check(plugin: MarketPlugin): InstallCheck {
        val reasons = mutableListOf<String>()
        if (plugin.id.isBlank()) reasons += "插件 ID 为空"
        if (plugin.version.isBlank()) reasons += "插件版本为空"
        if (!plugin.downloadUrl.startsWith("https://")) reasons += "插件下载地址不是 HTTPS"
        return InstallCheck(reasons.isEmpty(), reasons)
    }

    fun install(plugin: MarketPlugin): PluginDescriptor {
        plugin.bundledAsset?.let { assetPath -> return installBundled(plugin, assetPath) }
        require(check(plugin).compatible) { check(plugin).reasons.joinToString("；") }
        val dir = File(Environment.pluginsDir(context)).apply { mkdirs() }
        val safe = plugin.id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + "-" + plugin.version + ".zip"
        val target = File(dir, safe)
        val tmp = File(dir, ".download-${System.nanoTime()}.tmp")
        try {
            val uri = URI(plugin.downloadUrl); require(uri.scheme.equals("https", true)) { "插件下载地址必须使用 HTTPS" }
            val c = URL(plugin.downloadUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 60_000; c.requestMethod = "GET"
            c.connect(); require(c.responseCode in 200..299) { "插件下载失败：HTTP ${c.responseCode}" }
            require(c.contentLengthLong <= 100L * 1024 * 1024 || c.contentLengthLong < 0) { "插件包超过 100 MB" }
            var total = 0L
            c.inputStream.use { input -> tmp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 100L * 1024 * 1024) { "插件包超过 100 MB" }; output.write(buffer, 0, n) }
            } }
            plugin.sha256?.let { expected -> require(sha256(tmp).equals(expected, true)) { "插件 SHA-256 校验失败" } }
            val descriptor = PluginDescriptorReader(context).read(tmp)
            require(descriptor.id == plugin.id && descriptor.version == plugin.version) { "市场元数据与 plugin.xml 不一致" }
            require(hostApiVersion in descriptor.apiMin..descriptor.apiMax) { "插件 ${descriptor.id} 不兼容宿主 API $hostApiVersion" }
            if (target.exists()) target.delete()
            require(tmp.renameTo(target)) { "无法写入插件目录" }
            return PluginDescriptorReader(context).read(target)
        } finally { if (tmp.exists()) tmp.delete() }
    }

    /** 安装内置插件：直接从 assets 拷贝到插件目录，不做网络请求。 */
    private fun installBundled(plugin: MarketPlugin, assetPath: String): PluginDescriptor {
        val dir = File(Environment.pluginsDir(context)).apply { mkdirs() }
        val safe = plugin.id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + "-" + plugin.version + ".zip"
        val target = File(dir, safe)
        val tmp = File(dir, ".bundled-${System.nanoTime()}.tmp")
        try {
            context.assets.open(assetPath).use { input -> tmp.outputStream().use(input::copyTo) }
            val descriptor = PluginDescriptorReader(context).read(tmp)
            require(descriptor.id == plugin.id) { "内置插件索引与 plugin.xml 的 id 不一致" }
            require(descriptor.version == plugin.version) { "内置插件索引与 plugin.xml 的版本不一致" }
            require(hostApiVersion in descriptor.apiMin..descriptor.apiMax) { "插件 ${descriptor.id} 不兼容宿主 API $hostApiVersion" }
            if (target.exists()) target.delete()
            require(tmp.renameTo(target)) { "无法写入插件目录" }
            return PluginDescriptorReader(context).read(target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 把一个**已经落盘**的本地包按 Nebula 插件安装：解析 plugin.xml → 校验宿主 API 兼容 → 复制进统一插件目录。
     *
     * ## 为什么需要它
     * 用户要的是「装完能用」，而不是「下载目录里多一个文件」。所以凡是从外部带进来的包
     * ——自定义仓库下载、外部生态（VSIX/JAR）下载、文件管理器里选的本地安装包——
     * 只要**确实是 Nebula 插件格式**（含 plugin.xml 且能解析出描述符），就一律进入可加载状态。
     * 格式不符时抛异常，由调用方决定是提示「已下载供查看」还是「不是插件格式」。
     *
     * @return 插件目录里落定后的描述符（`source` 指向插件目录内的正式文件）
     */
    fun installLocalPackage(file: File): PluginDescriptor {
        require(file.isFile) { "插件包不存在：${file.name}" }
        // 先解析校验再落盘：校验失败的半成品不进插件目录，避免留下永远加载不了的垃圾文件。
        val parsed = PluginDescriptorReader(context).read(file)
        require(hostApiVersion in parsed.apiMin..parsed.apiMax) {
            "插件 ${parsed.id} 不兼容宿主 API $hostApiVersion（要求 ${parsed.apiMin}..${parsed.apiMax}）"
        }
        val dir = File(Environment.pluginsDir(context)).apply { mkdirs() }
        val ext = file.extension.lowercase().ifBlank { "zip" }
        val safe = parsed.id.replace(Regex("[^A-Za-z0-9_.-]"), "_") + "-" + parsed.version + "." + ext
        val target = File(dir, safe)
        // 文件已经在插件目录里（例如重新加载已安装插件）就不要自我复制。
        if (file.canonicalPath != target.canonicalPath) {
            file.copyTo(target, overwrite = true)
        }
        return PluginDescriptorReader(context).read(target)
    }

    /**
     * 把外部生态插件包（VSIX/JAR）真实下载到应用下载目录。
     * 外部生态的包星弦 IDE 无法加载（格式不同），但用户可能想看源码/资源，所以提供真实下载而不是假按钮。
     */
    fun downloadExternalPackage(url: String, fileName: String): File {
        val uri = URI(url)
        require(uri.scheme.equals("https", true)) { "只允许 HTTPS 下载" }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "plugin-packages").apply { mkdirs() }
        val safe = fileName.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "package-${System.nanoTime()}" }
        val target = File(dir, safe)
        val tmp = File(dir, ".part-${System.nanoTime()}")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "NebulaForge-IDE/2")
            connection.connect()
            require(connection.responseCode in 200..299) { "下载失败：HTTP ${connection.responseCode}" }
            var total = 0L
            connection.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= 200L * 1024 * 1024) { "安装包超过 200 MB" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (target.exists()) target.delete()
            require(tmp.renameTo(target)) { "无法写入下载目录" }
            return target
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 「已安装」扫描的结果：既有解析成功的插件，也有**没能解析的文件**。
     *
     * 为什么要把失败单独带出来：插件目录里可能出现下载中断的半包、旧版本残留、
     * 或插件格式被改坏的文件。此前这些文件只会写进 logcat，界面上一律显示「已安装 0 个插件」，
     * 用户完全不知道是「真没装」还是「装了但读不出来」。现在把原因一路带回界面。
     */
    data class InstalledScan(
        val plugins: List<PluginDescriptor>,
        /** 每个无法解析的插件文件一行说明：文件名 + 失败原因。 */
        val issues: List<String>,
        /** 插件目录本身不可读（权限/被删）时为 true，属于比单文件失败更严重的状态。 */
        val directoryUnreadable: Boolean = false
    )

    fun scanInstalled(): InstalledScan {
        // The scanner is the source of truth for the Installed tab. Never turn a
        // filesystem/descriptor error into an unexplained "0 plugins" result.
        Environment.ensureDirs(context)
        val dir = File(Environment.pluginsDir(context))
        val files = dir.listFiles()
        if (files == null) {
            Log.e(TAG, "plugin directory is not readable: ${dir.absolutePath}")
            return InstalledScan(emptyList(), listOf("插件目录不可读：${dir.absolutePath}"), directoryUnreadable = true)
        }
        val issues = mutableListOf<String>()
        val plugins = files
            .filter { it.isFile && (it.extension.equals("zip", true) || it.extension.equals("apk", true)) }
            .mapNotNull { file ->
                runCatching { PluginDescriptorReader(context).read(file) }
                    .onFailure {
                        val reason = it.message ?: it.javaClass.simpleName
                        Log.e(TAG, "cannot parse installed plugin ${file.name}: $reason")
                        issues += "${file.name}：$reason"
                    }
                    .getOrNull()
            }
            .sortedWith(compareBy<PluginDescriptor> { it.id }.thenByDescending { it.version })
        // 目录里还可能躺着「扫描器根本不认」的东西：手工解压出来的子目录、直接拷进来的
        // .vsix/.jar、下载中断留下的半包。它们以前被 filter 静默丢掉 —— 用户看到的是
        // 「明明装了却显示 0 个插件」，而且界面上一条线索都没有。这里如实列出来。
        files
            .filter { it.name.isNotEmpty() && !it.name.startsWith(".") }
            .filter { !(it.isFile && (it.extension.equals("zip", true) || it.extension.equals("apk", true))) }
            .forEach { stray ->
                val kind = if (stray.isDirectory) "目录" else "扩展名 ${stray.extension.ifBlank { "（无）" }}"
                Log.w(TAG, "unrecognized plugin artifact: ${stray.name} ($kind)")
                issues += "${stray.name}：不是 Nebula 插件包（$kind）。" +
                    "插件目录只接受 *.zip / *.apk；VSIX 请到市场页点「安装」由转换器生成"
            }
        // 正向也留一条：没有这行日志时，「0 个插件」到底是「真没装」还是「装了读不出来」
        // 只能靠猜，这正是之前无法定位的原因。
        Log.i(
            TAG,
            "scanInstalled: dir=${dir.absolutePath} entries=${files.size} plugins=${plugins.size} issues=${issues.size}",
        )
        return InstalledScan(plugins, issues)
    }

    fun installed(): List<PluginDescriptor> = scanInstalled().plugins

    /** 找到某插件在插件目录里的实际文件（卸载/重装都要按 id+version 精确定位）。 */
    fun fileOf(descriptor: PluginDescriptor): File? = File(Environment.pluginsDir(context)).listFiles().orEmpty()
        .filter { it.isFile && (it.extension.equals("zip", true) || it.extension.equals("apk", true)) }
        .firstOrNull { file ->
            runCatching {
                val read = PluginDescriptorReader(context).read(file)
                read.id == descriptor.id && read.version == descriptor.version
            }.getOrDefault(false)
        }

    /** 从磁盘卸载插件；返回是否真的删掉了文件。 */
    fun uninstall(descriptor: PluginDescriptor): Boolean {
        val file = fileOf(descriptor) ?: return false
        return file.delete()
    }

    private companion object {
        const val BUNDLED_CATALOG_ASSET = "plugins/catalog.json"
        const val TAG = "PluginMarketplace"
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun JSONArray?.toStringList(): List<String> = this?.let { a -> buildList { for (i in 0 until a.length()) a.optString(i).takeIf(String::isNotBlank)?.let(::add) } } ?: emptyList()
}
