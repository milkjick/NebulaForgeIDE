package com.nebulaforge.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/** 联网检索 + 网页证据提取。默认无需搜索 Key 的 DuckDuckGo HTML，可替换为自建搜索网关。 */
data class WebSearchResult(val title: String, val url: String, val snippet: String, val source: String = "DuckDuckGo")
data class WebEvidence(val url: String, val title: String, val text: String, val fetchedAt: Long = System.currentTimeMillis())

class AgentWebSearchService {
    suspend fun search(query: String, limit: Int = 8): List<WebSearchResult> = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "搜索关键词为空" }
        val encoded = URLEncoder.encode(query.take(300), "UTF-8")
        val connection = (URL("https://html.duckduckgo.com/html/?q=$encoded").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "NebulaForgeIDE/1.60"); setRequestProperty("Accept", "text/html")
        }
        try {
            check(connection.responseCode in 200..299) { "搜索服务 HTTP ${connection.responseCode}" }
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseDuckDuckGo(html).take(limit.coerceIn(1, 20))
        } finally { connection.disconnect() }
    }

    suspend fun fetchEvidence(url: String, maxChars: Int = 30_000): WebEvidence = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "仅允许 HTTP/HTTPS 网页" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NebulaForgeIDE/1.60"); setRequestProperty("Accept", "text/html,text/plain,application/xhtml+xml")
        }
        try {
            check(c.responseCode in 200..299) { "网页 HTTP ${c.responseCode}" }
            val contentType = c.contentType?.lowercase(Locale.ROOT).orEmpty()
            check(contentType.contains("text/html") || contentType.contains("text/plain") || contentType.contains("xhtml")) { "暂不读取非文本网页：$contentType" }
            val raw = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(120_000) }
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(raw)?.groupValues?.getOrNull(1)?.let(::stripTags).orEmpty()
            val text = stripTags(raw).take(maxChars.coerceIn(1000, 50_000))
            WebEvidence(url, htmlDecode(title).trim(), htmlDecode(text).trim())
        } finally { c.disconnect() }
    }

    private fun parseDuckDuckGo(html: String): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        val regex = Regex("(?s)<a[^>]*class=\\\"result__a\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>.*?(?:<a[^>]*class=\\\"result__snippet[^>]*>(.*?)</a>|<div[^>]*class=\\\"result__snippet[^>]*>(.*?)</div>)")
        for (m in regex.findAll(html)) {
            val url = htmlDecode(stripTags(m.groupValues[1])).trim()
            val title = htmlDecode(stripTags(m.groupValues[2])).trim()
            val snippet = htmlDecode(stripTags(if (m.groupValues[3].isNotBlank()) m.groupValues[3] else m.groupValues[4])).trim()
            if (title.isNotBlank() && url.startsWith("http")) results += WebSearchResult(title, url, snippet)
        }
        return results.distinctBy { it.url }
    }

    private fun stripTags(s: String) = s.replace(Regex("(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
    private fun htmlDecode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
}
