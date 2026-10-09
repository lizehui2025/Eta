package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small dependency-free read-only search adapter. It never sends cookies or credentials.
 *
 * 共享浏览器约定：`search` 只做 HTTP 结果抽取，不接触共享 Agent 浏览器；`read` 在浏览器可用时
 * 会导航共享浏览器抓正文（返回体显式标注 shared_browser=true 与 current_url），浏览器不可用或
 * 导航失败才回退 HTTP（回退结果标注 shared_browser=false）。
 */
internal class AgentWebSearch(
    private val context: Context? = null,
    private val runId: String = "",
    private val browserEnabled: () -> Boolean = { false },
) {
    fun execute(args: JSONObject): String = when (args.optString("operation")) {
        "search" -> search(
            query = args.optString("query"),
            limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
            budget = args.optInt("max_chars", AgentWebSearchResults.DEFAULT_SEARCH_CHARS)
                .coerceIn(AgentWebSearchResults.MIN_SEARCH_CHARS, AgentWebSearchResults.MAX_SEARCH_CHARS),
        )
        "read" -> read(
            rawUrl = args.optString("url"),
            offset = args.optInt("offset", 0),
            maxChars = args.optInt("max_chars", DEFAULT_READ_CHARS).coerceIn(MIN_READ_CHARS, MAX_READ_CHARS),
        )
        else -> error("INVALID_OPERATION", "web_search operation 仅支持 search 或 read")
    }

    /**
     * search 的默认行为是"结果列表抽取"：标题 + URL + 摘要，条数由 limit 控制，
     * 条目文本字符数受 max_chars 预算约束（超出显式标记 truncated）；整页正文留给 read。
     */
    private fun search(query: String, limit: Int, budget: Int): String {
        if (query.isBlank()) return error("INVALID_ARGUMENT", "query 不能为空")
        val url = "https://www.bing.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("num", limit.toString()).build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val html = try {
            AgentHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return error("SEARCH_HTTP_${response.code}", "搜索服务返回 HTTP ${response.code}")
                response.body.string()
            }
        } catch (failure: IOException) {
            return error("SEARCH_NETWORK_ERROR", "搜索请求失败：" + (failure.message ?: "网络不可用"))
        }
        val items = AgentWebSearchResults.extractFromHtml(html, limit)
        if (items.isNotEmpty()) return AgentWebSearchResults.structuredEnvelope(query, items, budget, limit).toString()
        // 抽取不出结构时回空结果信封：整页正文（实测 5994 字符 Bing SERP）会把视频卡片、相关搜索
        // 等噪声当成“结果”，这里只回 parse_failed 与极小的页面诊断，原文交给 read 按需获取。
        return AgentWebSearchResults
            .fallbackEnvelope(query, html, budget, limit, url.toString())
            .toString()
    }

    /**
     * read 读取指定 URL 的正文。浏览器可用时用共享 Agent 浏览器导航 + 抓正文（带显式共享标注），
     * 浏览器不可用或导航失败才回退 HTTP；两条路径的返回体都带 url。
     */
    private fun read(rawUrl: String, offset: Int, maxChars: Int): String {
        val browserContext = context
        if (browserContext != null && browserEnabled()) {
            browserRead(browserContext, rawUrl, offset, maxChars)?.let { return it }
        }
        return httpRead(rawUrl, offset, maxChars)
    }

    /** 共享浏览器路径：导航会改变浏览器当前页面，因此结果显式标注 shared_browser 与 current_url。 */
    private fun browserRead(context: Context, rawUrl: String, offset: Int, maxChars: Int): String? {
        val navigated = AgentBrowserSession.execute(
            context,
            JSONObject().put("action", "navigate").put("url", rawUrl),
            runId,
            "web-search-read",
        )
        if (!runCatching { JSONObject(navigated.content).optBoolean("ok", false) }.getOrDefault(false)) return null
        val readable = AgentBrowserSession.execute(
            context,
            JSONObject().put("action", "get_readable").put("offset", offset).put("max_chars", maxChars),
            runId,
            "web-search-read",
        )
        return AgentWebSearchResults.annotateSharedBrowser(readable.content, rawUrl)
    }

    private fun httpRead(rawUrl: String, offset: Int, maxChars: Int): String {
        val uri = runCatching { URI(rawUrl) }.getOrNull()
            ?: return error("INVALID_ARGUMENT", "url 不是有效地址")
        if (uri.scheme !in setOf("http", "https") || uri.userInfo != null) return error("INVALID_ARGUMENT", "只允许 http/https 公共 URL")
        val request = Request.Builder().url(rawUrl).header("User-Agent", USER_AGENT).build()
        val html = try {
            AgentHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return error("READ_HTTP_${response.code}", "网页返回 HTTP ${response.code}")
                response.body.string()
            }
        } catch (failure: IOException) {
            return error("READ_NETWORK_ERROR", "网页请求失败：" + (failure.message ?: "网络不可用"))
        }
        val text = AgentWebSearchResults.flattenHtml(html)
        return AgentWebSearchResults.readEnvelope(rawUrl, offset, text, maxChars).toString()
    }

    private fun error(code: String, message: String) = JSONObject().put("ok", false).put("code", code).put("message", message).put("retryable", code.contains("HTTP_5") || code.contains("HTTP_429")).toString()

    private companion object {
        const val USER_AGENT = "Eta/1.0 (read-only agent search)"
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 20
        const val DEFAULT_READ_CHARS = 12_000
        const val MIN_READ_CHARS = 512
        const val MAX_READ_CHARS = 50_000
    }
}

/**
 * web_search 的纯逻辑部分：结果抽取、字符预算、返回体组装与共享浏览器标注（不触网、不用 Android API）。
 *
 * - 抽取：从搜索结果页 HTML 的结果块（Bing 的 li.b_algo）取"标题 + URL + 摘要"；没有结果块时
 *   退化为全页锚点扫描（只有标题 + URL）；仍为空时由调用方返回 parse_failed=true 的空结果信封
 *   （只带极小页面诊断，不回整页正文）。
 * - 预算：条目文本（标题 + URL + 摘要）字符数受 max_chars 约束，超出即丢弃尾部条目，首条即超预算
 *   时截断其摘要；total_chars/returned_chars/truncated 显式标出裁剪前后字符数，不静默截断。
 */
internal object AgentWebSearchResults {
    /** search 默认预算，与浏览器 get_readable 的默认正文上限保持一致。 */
    const val DEFAULT_SEARCH_CHARS = 8_000

    /** 与 web_search schema 的 max_chars 边界一致。 */
    const val MIN_SEARCH_CHARS = 512
    const val MAX_SEARCH_CHARS = 50_000
    const val PROVIDER = "public-search"

    /** 抽取失败时回传的页面诊断上限（标题 + 字符数），避免诊断本身变成噪声。 */
    const val MAX_DIAGNOSTIC_CHARS = 512

    private const val TITLE_CHARS = 200
    private const val SNIPPET_CHARS = 240

    /** 预算余量小于这个长度时不再给出被截断的摘要。 */
    private const val MIN_SNIPPET_CHARS = 32

    private val SCRIPTISH = Regex("(?is)<script.*?</script>|<style.*?</style>|<noscript.*?</noscript>")
    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
    private val RESULT_BLOCK = Regex("(?is)<li[^>]*\\bclass=\"[^\"]*\\bb_algo\\b[^\"]*\"[^>]*>(.*?)</li>")
    private val ANCHOR = Regex("(?is)<a\\b[^>]*?\\bhref=\"([^\"]+)\"[^>]*>(.*?)</a>")
    private val PARAGRAPH = Regex("(?is)<p\\b[^>]*>(.*?)</p>")

    /** 页面标题：只用于抽取失败时的诊断，不参与结果抽取。 */
    private val PAGE_TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")

    /** 搜索引擎自身域名：结果列表里的这些链接是页面噪声（标签页、翻页、跳转中间页）。 */
    private val SEARCH_ENGINE_HOSTS = setOf("bing.com", "www.bing.com", "cn.bing.com", "go.microsoft.com")

    /** 单条搜索结果。 */
    data class Item(val title: String, val url: String, val snippet: String = "") {
        fun charCount(): Int = title.length + url.length + snippet.length
    }

    /** 预算裁剪结果：[totalChars] 为裁剪前、[returnedChars] 为裁剪后的条目文本字符数。 */
    data class BudgetedResults(
        val items: List<Item>,
        val truncated: Boolean,
        val totalChars: Int,
        val returnedChars: Int,
    )

    /** 结果块抽取 → 锚点退化抽取；两轮都按 URL 去重，最多 [limit] 条。 */
    fun extractFromHtml(html: String, limit: Int): List<Item> {
        if (limit <= 0) return emptyList()
        val seen = linkedSetOf<String>()
        val items = mutableListOf<Item>()
        for (block in RESULT_BLOCK.findAll(html)) {
            val fragment = block.groupValues[1]
            val link = firstLink(fragment) ?: continue
            if (!seen.add(link.second)) continue
            val snippet = PARAGRAPH.find(fragment)?.let { cleanText(it.groupValues[1]) }.orEmpty()
            items += Item(link.first, link.second, snippet.take(SNIPPET_CHARS))
            if (items.size >= limit) return items
        }
        if (items.isNotEmpty()) return items
        for (anchor in ANCHOR.findAll(html)) {
            val url = cleanResultUrl(anchor.groupValues[1]) ?: continue
            if (!seen.add(url)) continue
            val title = cleanText(anchor.groupValues[2]).take(TITLE_CHARS)
            if (title.isBlank()) continue
            items += Item(title, url)
            if (items.size >= limit) break
        }
        return items
    }

    /** 整页 HTML → 单行正文：read 路径用它取正文，search 抽取失败时只用它统计正文字符数。 */
    fun flattenHtml(html: String): String = cleanText(html)

    /** 页面标题：仅用于抽取失败信封里的诊断信息，取不到时返回空串。 */
    private fun pageTitle(html: String): String =
        PAGE_TITLE.find(html)?.let { cleanText(it.groupValues[1]) }.orEmpty()

    /** 按字符预算裁剪结果列表：整条放不下就丢弃尾部，首条即超预算时截断其摘要。 */
    fun applyBudget(items: List<Item>, budget: Int): BudgetedResults {
        val returned = mutableListOf<Item>()
        var used = 0
        for (item in items) {
            val cost = item.charCount()
            if (used + cost <= budget) {
                returned += item
                used += cost
                continue
            }
            // 首条就超预算：只截断摘要，保证低成本预算下仍能拿到标题与 URL。
            val remaining = budget - used - item.title.length - item.url.length
            if (returned.isEmpty() && remaining >= MIN_SNIPPET_CHARS) {
                returned += item.copy(snippet = item.snippet.take(remaining - 1).trimEnd() + "…")
            }
            break
        }
        val totalChars = items.sumOf { it.charCount() }
        val returnedChars = returned.sumOf { it.charCount() }
        return BudgetedResults(
            items = returned,
            // 条目被丢弃或首条摘要被截断都算截断：只看条目数会漏掉“唯一候选被截断”的情况，
            // 导致 total_chars/returned_chars 已显示内容被裁剪、truncated 与 note 却缺失。
            truncated = returned.size < items.size || returnedChars < totalChars,
            totalChars = totalChars,
            returnedChars = returnedChars,
        )
    }

    /** 结构化结果返回体：条目列表 + 显式预算标注。search 不驱动共享浏览器，故 shared_browser=false。 */
    fun structuredEnvelope(query: String, items: List<Item>, budget: Int, limit: Int): JSONObject {
        val budgeted = applyBudget(items, budget)
        val retrievedAt = Instant.now().toString()
        val results = JSONArray()
        for (item in budgeted.items) {
            results.put(
                JSONObject()
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("snippet", item.snippet)
                    .put("retrieved_at", retrievedAt),
            )
        }
        val note = when {
            budgeted.items.isEmpty() ->
                "结果条目超出 max_chars=$budget 预算，未返回任何条目；请提高 max_chars 或降低 limit。"
            budgeted.truncated ->
                "结果已按 max_chars=$budget 预算截断；total_chars/returned_chars 为裁剪前后的条目文本字符数，提高 max_chars 可获取更多。"
            else -> null
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("provider", PROVIDER)
            .put("limit", limit)
            .put("max_chars", budget)
            .put("results", results)
            .put("total_results", items.size)
            .put("returned_results", budgeted.items.size)
            .put("total_chars", budgeted.totalChars)
            .put("returned_chars", budgeted.returnedChars)
            .put("truncated", budgeted.truncated)
            .put("shared_browser", false)
            .also { json -> note?.let { json.put("note", it) } }
    }

    /**
     * 抽取失败时的返回体：不回整页正文，只回空结果列表 + parse_failed 标记 + 极小的页面诊断
     * （≤ [MAX_DIAGNOSTIC_CHARS] 字符）。这样调用方能区分“确实没有结果”（parse_failed=false）
     * 与“结构抽取失败”（parse_failed=true），也不会把 SERP 噪声误当搜索结果。
     */
    fun fallbackEnvelope(
        query: String,
        pageSource: String,
        budget: Int,
        limit: Int,
        pageUrl: String = "",
    ): JSONObject {
        val textChars = flattenHtml(pageSource).length
        val title = pageTitle(pageSource).take(TITLE_CHARS)
        val diagnostic = buildString {
            append("title=").append(title.ifBlank { "(无标题)" })
            append(" text_chars=").append(textChars)
            append(" html_chars=").append(pageSource.length)
        }.take(MAX_DIAGNOSTIC_CHARS)
        val recovery = if (pageUrl.isNotBlank()) {
            "可用 web_search.read 打开 page_url 查看检索页原文"
        } else {
            "可用 web_search.read 打开检索页查看原文"
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("provider", PROVIDER)
            .put("limit", limit)
            .put("max_chars", budget)
            .put("results", JSONArray())
            .put("total_results", 0)
            .put("returned_results", 0)
            .put("parse_failed", true)
            .put("diagnostic", diagnostic)
            .put("shared_browser", false)
            .also { json -> if (pageUrl.isNotBlank()) json.put("page_url", pageUrl) }
            .put(
                "note",
                "未从搜索结果页解析出结构化条目（parse_failed=true），已返回空结果、不回传整页正文；" +
                    "$recovery，或换个查询词重试。",
            )
    }

    /** HTTP read 返回体：正文 + 与 search 一致的预算标注，url 必定存在。 */
    fun readEnvelope(url: String, offset: Int, text: String, maxChars: Int): JSONObject {
        val start = offset.coerceIn(0, text.length)
        val end = (start + maxChars).coerceAtMost(text.length)
        return JSONObject()
            .put("ok", true)
            .put("url", url)
            .put("offset", start)
            .put("text", text.substring(start, end))
            .put("total_chars", text.length)
            .put("returned_chars", end - start)
            .put("truncated", end < text.length)
            .put("has_more", end < text.length)
            .put("shared_browser", false)
    }

    /**
     * 显式化共享浏览器状态：read 经由共享 Agent 浏览器时标注 shared_browser=true 与当前页地址，
     * 并补齐 total_chars/returned_chars，便于调用方统一检测页面错位与截断。
     */
    fun annotateSharedBrowser(content: String, fallbackUrl: String): String {
        val json = runCatching { JSONObject(content) }.getOrNull() ?: return content
        if (!json.has("total_chars")) {
            json.optInt("text_length", 0).takeIf { it > 0 }?.let { json.put("total_chars", it) }
        }
        if (!json.has("returned_chars")) {
            json.optString("text").length.takeIf { it > 0 }?.let { json.put("returned_chars", it) }
        }
        val currentUrl = listOf("url", "display_url", "canonical_url")
            .map { json.optString(it) }
            .firstOrNull(String::isNotBlank)
            ?: fallbackUrl
        json.put("shared_browser", true)
        if (currentUrl.isNotBlank()) json.put("current_url", currentUrl)
        return json.toString()
    }

    /** 结果块里第一条可用链接（跳过无文本、非 http(s) 与搜索引擎自身域名）。 */
    private fun firstLink(fragment: String): Pair<String, String>? {
        for (match in ANCHOR.findAll(fragment)) {
            val url = cleanResultUrl(match.groupValues[1]) ?: continue
            val title = cleanText(match.groupValues[2]).take(TITLE_CHARS)
            if (title.isNotBlank()) return title to url
        }
        return null
    }

    /** 归一化结果链接：还原 Bing 的 /ck/a 跳转中间页；拒绝非 http(s) 与搜索引擎自身域名。 */
    private fun cleanResultUrl(raw: String): String? {
        val value = raw.replace("&amp;", "&").trim()
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) return null
        val decoded = decodeRedirect(value)
        val host = runCatching { decoded.toHttpUrlOrNull()?.host }.getOrNull() ?: return null
        if (host.lowercase() in SEARCH_ENGINE_HOSTS) return null
        return decoded
    }

    /** Bing 的结果链接形如 /ck/a?u=a1<base64url>；能解码就还原真实地址，否则原样返回。 */
    private fun decodeRedirect(url: String): String {
        if (!url.contains("bing.com/ck/a", ignoreCase = true)) return url
        val encoded = runCatching { url.toHttpUrlOrNull()?.queryParameter("u") }.getOrNull() ?: return url
        if (!encoded.startsWith("a1") || encoded.length <= 2) return url
        val payload = encoded.substring(2)
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull() ?: return url
        return when {
            decoded.startsWith("http://", ignoreCase = true) -> decoded
            decoded.startsWith("https://", ignoreCase = true) -> decoded
            else -> url
        }
    }

    /** HTML 片段 → 单行文本：去脚本/样式与标签、还原常见实体、压缩空白。 */
    private fun cleanText(value: String): String = value
        .replace(SCRIPTISH, " ")
        .replace(TAG, " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(WHITESPACE, " ")
        .trim()
}
