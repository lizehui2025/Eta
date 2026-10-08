package io.github.mangi.eta.agent.tool

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** web_search 结果抽取 / 字符预算 / 返回体标注的纯逻辑测试（不触网、不启动浏览器）。 */
class AgentWebSearchResultsTest {

    // ---- 结果抽取 ----

    @Test
    fun extractFromHtmlParsesResultBlocksAndFiltersNoise() {
        val html = """
            <html><body><ol id="b_results">
              <li class="b_algo"><h2><a href="https://github.com/example/eta">Eta — <strong>Android</strong> Agent</a></h2>
                <div class="b_caption"><p class="b_lineclamp2">Eta is a local agent &amp; tool runner.</p></div></li>
              <li class="b_algo"><h2><a href="https://www.bing.com/search?q=eta">相关搜索</a></h2><p>页面噪声</p></li>
              <li class="b_algo"><h2><a href="https://github.com/example/eta">Eta — Android Agent</a></h2><p>重复条目</p></li>
              <li class="b_algo"><h2><a href="https://docs.example.org/eta/guide">Eta 指南</a></h2><p>逐步说明。</p></li>
            </ol></body></html>
        """.trimIndent()

        val items = AgentWebSearchResults.extractFromHtml(html, limit = 8)

        assertEquals(2, items.size)
        assertEquals("Eta — Android Agent", items[0].title)
        assertEquals("https://github.com/example/eta", items[0].url)
        assertEquals("Eta is a local agent & tool runner.", items[0].snippet)
        assertEquals("https://docs.example.org/eta/guide", items[1].url)
        assertEquals("逐步说明。", items[1].snippet)
    }

    @Test
    fun extractFromHtmlRespectsLimit() {
        val html = """
            <li class="b_algo"><h2><a href="https://a.example/1">条目一</a></h2><p>摘要一</p></li>
            <li class="b_algo"><h2><a href="https://b.example/2">条目二</a></h2><p>摘要二</p></li>
        """.trimIndent()

        val items = AgentWebSearchResults.extractFromHtml(html, limit = 1)

        assertEquals(1, items.size)
        assertEquals("https://a.example/1", items[0].url)
    }

    @Test
    fun extractFromHtmlDecodesBingRedirectLinks() {
        val target = "https://example.com/page"
        val encoded = "a1" + Base64.getUrlEncoder().withoutPadding().encodeToString(target.toByteArray())
        val html = """
            <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=$encoded&amp;ntb=1">Example Page</a></h2><p>跳转中间页。</p></li>
        """.trimIndent()

        val items = AgentWebSearchResults.extractFromHtml(html, limit = 8)

        assertEquals(1, items.size)
        assertEquals(target, items[0].url)
    }

    @Test
    fun extractFromHtmlFallsBackToAnchorScanWithoutResultBlocks() {
        val html = """
            <div><a href="https://ok.example/x">Ok Page</a>
            <a href="https://www.bing.com/search?q=x">下一页</a>
            <a href="javascript:void(0)">脚本</a>
            <a href="https://go.microsoft.com/fwlink/?linkid=1">Microsoft</a></div>
        """.trimIndent()

        val items = AgentWebSearchResults.extractFromHtml(html, limit = 8)

        assertEquals(1, items.size)
        assertEquals("Ok Page", items[0].title)
        assertEquals("https://ok.example/x", items[0].url)
        assertEquals("", items[0].snippet)
    }

    @Test
    fun extractFromHtmlReturnsEmptyForPlainText() {
        assertTrue(AgentWebSearchResults.extractFromHtml("没有链接的纯文本", 8).isEmpty())
    }

    // ---- 字符预算 ----

    @Test
    fun applyBudgetKeepsAllItemsWhenTheyFit() {
        val items = sampleItems()
        val budget = items.sumOf { it.charCount() }

        val budgeted = AgentWebSearchResults.applyBudget(items, budget)

        assertEquals(3, budgeted.items.size)
        assertFalse(budgeted.truncated)
        assertEquals(budget, budgeted.totalChars)
        assertEquals(budget, budgeted.returnedChars)
    }

    @Test
    fun applyBudgetDropsTailItemsBeyondBudget() {
        val items = sampleItems()
        val firstTwo = items[0].charCount() + items[1].charCount()

        val budgeted = AgentWebSearchResults.applyBudget(items, firstTwo)

        assertEquals(listOf(items[0], items[1]), budgeted.items)
        assertTrue(budgeted.truncated)
        assertEquals(items.sumOf { it.charCount() }, budgeted.totalChars)
        assertEquals(firstTwo, budgeted.returnedChars)
    }

    @Test
    fun applyBudgetTruncatesFirstSnippetWhenItIsTheOnlyCandidate() {
        val item = AgentWebSearchResults.Item("T", "https://a.example/1", "x".repeat(80))
        val head = item.title.length + item.url.length
        val budget = head + 40

        val budgeted = AgentWebSearchResults.applyBudget(listOf(item), budget)

        assertEquals(1, budgeted.items.size)
        assertTrue(budgeted.items[0].snippet.endsWith("…"))
        assertTrue(budgeted.items[0].charCount() <= budget)
        assertEquals(item.charCount(), budgeted.totalChars)
        assertTrue(budgeted.truncated)
    }

    @Test
    fun applyBudgetDropsEverythingWhenBudgetCannotHoldTitleAndUrl() {
        val budgeted = AgentWebSearchResults.applyBudget(sampleItems(), budget = 20)

        assertTrue(budgeted.items.isEmpty())
        assertTrue(budgeted.truncated)
        assertEquals(0, budgeted.returnedChars)
    }

    // ---- 返回体字段标注 ----

    @Test
    fun structuredEnvelopeCarriesBudgetAndSharingMarkers() {
        val items = sampleItems()
        val budget = 8_000

        val json = AgentWebSearchResults.structuredEnvelope("eta agent", items, budget, limit = 8)

        assertTrue(json.getBoolean("ok"))
        assertEquals("public-search", json.getString("provider"))
        assertEquals(8, json.getInt("limit"))
        assertEquals(budget, json.getInt("max_chars"))
        assertEquals(3, json.getInt("total_results"))
        assertEquals(3, json.getInt("returned_results"))
        assertFalse(json.getBoolean("truncated"))
        assertFalse(json.getBoolean("shared_browser"))
        assertFalse(json.has("note"))
        assertEquals(items.sumOf { it.charCount() }, json.getInt("total_chars"))
        assertEquals(json.getInt("total_chars"), json.getInt("returned_chars"))
        val first = json.getJSONArray("results").getJSONObject(0)
        assertEquals(items[0].title, first.getString("title"))
        assertEquals(items[0].url, first.getString("url"))
        assertTrue(first.getString("retrieved_at").isNotBlank())
    }

    @Test
    fun structuredEnvelopeNotesBudgetTruncation() {
        val items = sampleItems()
        val budget = items[0].charCount()

        val json = AgentWebSearchResults.structuredEnvelope("eta agent", items, budget, limit = 8)

        assertTrue(json.getBoolean("truncated"))
        assertEquals(1, json.getInt("returned_results"))
        assertEquals(3, json.getInt("total_results"))
        assertEquals(1, json.getJSONArray("results").length())
        assertTrue(json.getString("note").contains("max_chars=$budget"))
    }

    @Test
    fun structuredEnvelopeNotesEmptyResultsWhenBudgetTooSmall() {
        val json = AgentWebSearchResults.structuredEnvelope("eta agent", sampleItems(), budget = 20, limit = 8)

        assertTrue(json.getBoolean("truncated"))
        assertEquals(0, json.getJSONArray("results").length())
        assertTrue(json.getString("note").contains("max_chars=20"))
    }

    @Test
    fun fallbackEnvelopeTruncatesBodyAndExplains() {
        val text = "正".repeat(2_000)

        val json = AgentWebSearchResults.fallbackEnvelope("eta agent", text, budget = 512, limit = 8)

        assertTrue(json.getBoolean("ok"))
        assertTrue(json.getBoolean("truncated"))
        assertEquals(512, json.getString("text").length)
        assertEquals(2_000, json.getInt("total_chars"))
        assertEquals(512, json.getInt("returned_chars"))
        assertFalse(json.getBoolean("shared_browser"))
        assertFalse(json.has("results"))
        assertTrue(json.getString("note").contains("截断"))
    }

    @Test
    fun fallbackEnvelopeKeepsShortBodyUntruncated() {
        val text = "简短正文"

        val json = AgentWebSearchResults.fallbackEnvelope("eta agent", text, budget = 512, limit = 8)

        assertFalse(json.getBoolean("truncated"))
        assertEquals(text, json.getString("text"))
        assertTrue(json.getString("note").contains("正文"))
    }

    @Test
    fun readEnvelopeCarriesUrlOffsetAndBudgetMarkers() {
        val json = AgentWebSearchResults.readEnvelope(
            "https://example.com/page",
            offset = 100,
            text = "x".repeat(1_000),
            maxChars = 512,
        )

        assertEquals("https://example.com/page", json.getString("url"))
        assertEquals(100, json.getInt("offset"))
        assertEquals(512, json.getString("text").length)
        assertEquals(1_000, json.getInt("total_chars"))
        assertEquals(512, json.getInt("returned_chars"))
        assertTrue(json.getBoolean("truncated"))
        assertTrue(json.getBoolean("has_more"))
        assertFalse(json.getBoolean("shared_browser"))
    }

    @Test
    fun annotateSharedBrowserMarksPageAndBackfillsBudgetFields() {
        val content = JSONObject()
            .put("ok", true)
            .put("url", "https://example.com/page")
            .put("text", "正文")
            .put("text_length", 900)
            .toString()

        val annotated = JSONObject(
            AgentWebSearchResults.annotateSharedBrowser(content, "https://fallback.example"),
        )

        assertTrue(annotated.getBoolean("shared_browser"))
        assertEquals("https://example.com/page", annotated.getString("current_url"))
        assertEquals(900, annotated.getInt("total_chars"))
        assertEquals(2, annotated.getInt("returned_chars"))
    }

    @Test
    fun annotateSharedBrowserUsesFallbackUrlAndKeepsNonJsonPayload() {
        val json = JSONObject(
            AgentWebSearchResults.annotateSharedBrowser(
                JSONObject().put("ok", true).toString(),
                "https://fallback.example",
            ),
        )

        assertEquals("https://fallback.example", json.getString("current_url"))
        assertTrue(json.getBoolean("shared_browser"))
        assertEquals(
            "not json",
            AgentWebSearchResults.annotateSharedBrowser("not json", "https://fallback.example"),
        )
    }

    @Test
    fun flattenHtmlStripsMarkupAndCollapsesWhitespace() {
        val html = "<script>var x = 1;</script><style>.a{}</style><p>Hello   <b>world</b></p>"

        assertEquals("Hello world", AgentWebSearchResults.flattenHtml(html))
    }

    private fun sampleItems() = listOf(
        AgentWebSearchResults.Item("A", "https://a.example/1", "aaaa"),
        AgentWebSearchResults.Item("B", "https://b.example/2", "bbbb"),
        AgentWebSearchResults.Item("C", "https://c.example/3", "cccc"),
    )
}
