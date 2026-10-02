package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.model.AgentHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant

/** Small dependency-free read-only search adapter. It never sends cookies or credentials. */
internal class AgentWebSearch(
    private val context: Context? = null,
    private val runId: String = "",
    private val browserEnabled: () -> Boolean = { false },
) {
    fun execute(args: JSONObject): String {
        if (context != null && browserEnabled()) {
            browserBacked(args)?.let { return it }
        }
        return when (args.optString("operation")) {
            "search" -> search(args.optString("query"), args.optInt("limit", 8).coerceIn(1, 20))
            "read" -> read(args.optString("url"), args.optInt("offset", 0), args.optInt("max_chars", 12_000))
            else -> JSONObject().put("ok", false).put("code", "INVALID_OPERATION").put("message", "web_search operation 仅支持 search 或 read").toString()
        }
    }

    /** Prefer the shared WebView when enabled: it handles redirects, readable extraction and
     * modern pages more reliably than scraping a search response. HTTP remains the fallback. */
    private fun browserBacked(args: JSONObject): String? {
        val operation = args.optString("operation")
        val browserContext = context ?: return null
        val browserArgs = when (operation) {
            "search" -> JSONObject()
                .put("action", "navigate")
                .put("url", "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(args.optString("query"), "UTF-8"))
            "read" -> JSONObject().put("action", "navigate").put("url", args.optString("url"))
            else -> return null
        }
        val navigated = AgentBrowserSession.execute(browserContext, browserArgs, runId, "web-search")
        if (!runCatching { JSONObject(navigated.content).optBoolean("ok", false) }.getOrDefault(false)) return null
        val readable = AgentBrowserSession.execute(
            browserContext,
            JSONObject().put("action", "get_readable")
                .put("offset", args.optInt("offset", 0))
                .put("max_chars", args.optInt("max_chars", 12_000).coerceIn(512, 50_000)),
            runId,
            "web-search-read",
        )
        return readable.content
    }

    private fun search(query: String, limit: Int): String {
        if (query.isBlank()) return error("INVALID_ARGUMENT", "query 不能为空")
        val url = "https://www.bing.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("num", limit.toString()).build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val body = AgentHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return error("SEARCH_HTTP_${response.code}", "搜索服务返回 HTTP ${response.code}")
            response.body.string()
        }
        val results = JSONArray()
        val linkPattern = Regex("<a[^>]+href=\\\"(/url\\?q=|)(https?://[^\\\"&]+)[^\\\"]*\\\"[^>]*>(.*?)</a>", RegexOption.IGNORE_CASE)
        val seen = linkedSetOf<String>()
        for (match in linkPattern.findAll(body)) {
            val resultUrl = match.groupValues[2].replace("&amp;", "&")
            if (!seen.add(resultUrl) || resultUrl.contains("bing.com/search")) continue
            val title = stripHtml(match.groupValues[3]).trim()
            if (title.isBlank()) continue
            results.put(JSONObject().put("title", title).put("url", resultUrl).put("snippet", "").put("retrieved_at", Instant.now().toString()))
            if (results.length() >= limit) break
        }
        return JSONObject().put("ok", true).put("query", query).put("provider", "public-search").put("results", results).toString()
    }

    private fun read(rawUrl: String, offset: Int, maxChars: Int): String {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: return error("INVALID_ARGUMENT", "url 不是有效地址")
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null) return error("INVALID_ARGUMENT", "只允许 http/https 公共 URL")
        val request = Request.Builder().url(rawUrl).header("User-Agent", USER_AGENT).build()
        val html = AgentHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return error("READ_HTTP_${response.code}", "网页返回 HTTP ${response.code}")
            response.body.string()
        }
        val text = stripHtml(html).replace(Regex("\\s+"), " ").trim()
        val start = offset.coerceIn(0, text.length)
        return JSONObject().put("ok", true).put("url", rawUrl).put("offset", start).put("text", text.substring(start, (start + maxChars).coerceAtMost(text.length))).put("total_chars", text.length).put("has_more", start + maxChars < text.length).toString()
    }

    private fun stripHtml(value: String): String = value
        .replace(Regex("(?is)<script.*?</script>|<style.*?</style>|<noscript.*?</noscript>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

    private fun error(code: String, message: String) = JSONObject().put("ok", false).put("code", code).put("message", message).put("retryable", code.contains("HTTP_5") || code.contains("HTTP_429")).toString()
    private companion object { const val USER_AGENT = "Eta/1.0 (read-only agent search)" }
}
