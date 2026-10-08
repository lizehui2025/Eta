package io.github.mangi.eta.agent.tool

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.github.mangi.eta.core.ColorOsMemoryBridgeProtocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ColorOsMemoryDatabaseQueryTest {
    private lateinit var database: SQLiteDatabase

    @Before
    fun createDatabase() {
        database = SQLiteDatabase.create(null)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun memoriesExcludeDeletedRowsAndIncludeActiveRelatedDetails() {
        createMemories()
        insertMemory("old", "较早的记忆", createdTime = 1)
        insertMemory("new", "最近的记忆", createdTime = 2)
        insertMemory("deleted", "删除的记忆", createdTime = 3, deleted = 1)
        insertMemory("recycled", "回收站记忆", createdTime = 4, recycleTime = 4)
        database.execSQL(
            "CREATE TABLE bills (associate_memory_id TEXT, amount REAL, currency TEXT, status INTEGER)",
        )
        database.execSQL("INSERT INTO bills VALUES ('new', 12.5, 'CNY', 0)")
        database.execSQL("INSERT INTO bills VALUES ('new', 99, 'CNY', 1)")
        database.execSQL("INSERT INTO bills VALUES ('deleted', 88, 'CNY', 0)")

        val result = JSONObject(
            // detail=true 保持既有完整结构；关联详情现在属于按需展开内容。
            ColorOsMemoryDatabaseQuery.execute(
                database,
                ColorOsMemoryBridgeProtocol.OPERATION_SEARCH,
                JSONObject().put("detail", true),
            ),
        )

        assertTrue(result.getBoolean("ok"))
        assertEquals("search_coloros_memories", result.getString("tool"))
        assertEquals(2, result.getInt("count"))
        assertFalse(result.getBoolean("truncated"))
        val items = result.getJSONArray("items")
        assertEquals("new", items.getJSONObject(0).getString("memory_id"))
        assertEquals("old", items.getJSONObject(1).getString("memory_id"))
        val bills = items.getJSONObject(0).getJSONObject("details").getJSONArray("bills")
        assertEquals(1, bills.length())
        assertEquals(12.5, bills.getJSONObject(0).getDouble("amount"), 0.0)
        assertEquals("CNY", bills.getJSONObject(0).getString("currency"))
    }

    @Test
    fun ordersSearchAssociatedShipmentAndProjectReservedOrderColumn() {
        createMemories()
        insertMemory("shipment", "保存的单据")
        insertMemory("unrelated", "普通记忆")
        database.execSQL("CREATE TABLE shipments (associate_memory_id TEXT, \"order\" TEXT, status TEXT)")
        val order = "ETA_'%_123"
        database.execSQL("INSERT INTO shipments VALUES (?, ?, ?)", arrayOf<Any>("shipment", order, "待取件"))

        val result = query(ColorOsMemoryBridgeProtocol.OPERATION_ORDERS, order)

        assertTrue(result.getBoolean("ok"))
        assertEquals("search_personal_orders", result.getString("tool"))
        assertEquals(1, result.getInt("count"))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertEquals("shipment", item.getString("memory_id"))
        val shipment = item.getJSONObject("details").getJSONArray("shipments").getJSONObject(0)
        assertEquals(order, shipment.getString("order"))
        assertEquals("待取件", shipment.getString("status"))
    }

    @Test
    fun memorySearchTreatsLikeWildcardsQuotesAndBackslashesAsLiteralText() {
        createMemories()
        val keyword = "100%_O'Brien\\笔记"
        insertMemory("literal", "前缀 $keyword 后缀")
        insertMemory("wildcard", "100AAO'Brien\\笔记")
        insertMemory("other", "其他笔记")

        val result = query(ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, keyword)

        assertTrue(result.getBoolean("ok"))
        assertEquals(1, result.getInt("count"))
        assertEquals("literal", result.getJSONArray("items").getJSONObject(0).getString("memory_id"))
        assertEquals(0, query(ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, "' OR 1=1 --").getInt("count"))
    }

    @Test
    fun placesSearchUsesBoundLiteralTextAndPreservesCoordinates() {
        database.execSQL(
            "CREATE TABLE memory_address (memory_id TEXT, name TEXT, address TEXT, " +
                "longitude REAL, latitude REAL, create_time INTEGER)",
        )
        val keyword = "100%_O'Brien\\站"
        database.execSQL(
            "INSERT INTO memory_address VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>("place", keyword, "测试街道", 120.5, 30.25, 2),
        )
        database.execSQL(
            "INSERT INTO memory_address VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any>("other", "100AAO'Brien\\站", "另一街道", 0.0, 0.0, 1),
        )

        val result = query(ColorOsMemoryBridgeProtocol.OPERATION_PLACES, keyword)

        assertTrue(result.getBoolean("ok"))
        assertEquals("search_saved_places", result.getString("tool"))
        assertEquals(1, result.getInt("count"))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertEquals("place", item.getString("memory_id"))
        assertEquals(keyword, item.getString("name"))
        assertEquals(120.5, item.getDouble("longitude"), 0.0)
        assertEquals(30.25, item.getDouble("latitude"), 0.0)
        assertEquals(0, query(ColorOsMemoryBridgeProtocol.OPERATION_PLACES, "' OR 1=1 --").getInt("count"))
    }

    @Test
    fun missingTablesAndRequiredColumnsReturnUnsupportedSchema() {
        assertError(ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, "COLOROS_MEMORY_SCHEMA_UNSUPPORTED")
        assertError(ColorOsMemoryBridgeProtocol.OPERATION_ORDERS, "COLOROS_MEMORY_SCHEMA_UNSUPPORTED")
        assertError(ColorOsMemoryBridgeProtocol.OPERATION_PLACES, "COLOROS_PLACE_SCHEMA_UNSUPPORTED")

        database.execSQL("CREATE TABLE memories (unrelated TEXT)")
        database.execSQL("CREATE TABLE memory_address (name TEXT)")
        assertError(ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, "COLOROS_MEMORY_SCHEMA_UNSUPPORTED")
        assertError(ColorOsMemoryBridgeProtocol.OPERATION_PLACES, "COLOROS_PLACE_SCHEMA_UNSUPPORTED")

        database.execSQL("DROP TABLE memories")
        database.execSQL("CREATE TABLE memories (memory_id TEXT)")
        assertError(ColorOsMemoryBridgeProtocol.OPERATION_ORDERS, "COLOROS_ORDER_SCHEMA_UNSUPPORTED")
    }

    @Test
    fun schemaQueryFailurePropagatesInsteadOfReportingUnsupportedSchema() {
        val failure = SQLiteException("database unavailable")
        val unavailable = ColorOsMemoryReadDatabase { _, _ -> throw failure }

        val thrown = assertThrows(SQLiteException::class.java) {
            ColorOsMemoryDatabaseQuery.execute(unavailable, ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, JSONObject())
        }

        assertSame(failure, thrown)
    }

    @Test
    fun dataQueryFailurePropagatesAfterSuccessfulSchemaInspection() {
        createMemories()
        val failure = SQLiteException("query unavailable")
        val failingQuery = ColorOsMemoryReadDatabase { sql, args ->
            if (sql.startsWith("SELECT")) throw failure
            database.rawQuery(sql, args)
        }

        val thrown = assertThrows(SQLiteException::class.java) {
            ColorOsMemoryDatabaseQuery.execute(failingQuery, ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, JSONObject())
        }

        assertSame(failure, thrown)
    }

    @Test
    fun memoriesDefaultToSummaryModeWithOmittedGroups() {
        createRichMemories()
        createBills()
        val longText = "OCR全文".repeat(60)
        val longAbstract = "这是一条关于快递的长摘要".repeat(20)
        insertRichMemory(
            id = "rich",
            text = longText,
            cleanup = longText,
            abstractText = longAbstract,
            screenshot = "https://cdn.example.com/memory.jpg?sig=TEMP_SIGNATURE",
            extraData = "{\"nested\":{\"deep\":true}}",
            subSceneData = "{\"scene\":1}",
        )
        database.execSQL("INSERT INTO bills VALUES ('rich', 5.0, 0)")

        val result = JSONObject(
            ColorOsMemoryDatabaseQuery.execute(database, ColorOsMemoryBridgeProtocol.OPERATION_SEARCH, JSONObject()),
        )

        assertTrue(result.getBoolean("ok"))
        assertEquals("search_coloros_memories", result.getString("tool"))
        assertTrue(result.getBoolean("summary_mode"))
        assertTrue(result.getString("note").contains("摘要"))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertEquals("rich", item.getString("memory_id"))
        // 摘要：标题/来源/时间类小字段保留，全文、URL、嵌套 JSON 与关联详情默认不返回。
        assertEquals("测试应用", item.getString("app_name"))
        assertEquals(10, item.getInt("created_time"))
        assertFalse(item.has("data_text"))
        assertFalse(item.has("data_text_cleanup"))
        assertFalse(item.has("data_abstract"))
        assertFalse(item.has("screenshot"))
        assertFalse(item.has("extra_data"))
        assertFalse(item.has("sub_scene_data"))
        assertFalse(item.has("details"))
        val summary = item.getString("summary")
        assertEquals(200 + 1, summary.length)
        assertTrue(summary.endsWith("…"))
        val omitted = item.getJSONArray("omitted").stringValues().toSet()
        assertTrue("full_text" in omitted)
        assertTrue("image_urls" in omitted)
        assertTrue("extra_data" in omitted)
        assertTrue("details" in omitted)
    }

    @Test
    fun memoriesExpansionFlagsReturnOnlyRequestedGroups() {
        createRichMemories()
        createBills()
        insertRichMemory(
            id = "rich",
            text = "OCR正文",
            cleanup = "清洗后的正文",
            abstractText = "短摘要",
            screenshot = "https://cdn.example.com/memory.jpg?sig=TEMP_SIGNATURE",
            extraData = "{\"a\":1}",
            subSceneData = "{\"b\":2}",
        )
        database.execSQL("INSERT INTO bills VALUES ('rich', 5.0, 0)")

        val partial = JSONObject(
            ColorOsMemoryDatabaseQuery.execute(
                database,
                ColorOsMemoryBridgeProtocol.OPERATION_SEARCH,
                JSONObject().put("include_full_text", true).put("include_image_urls", true),
            ),
        )

        assertTrue(partial.getBoolean("summary_mode"))
        val partialItem = partial.getJSONArray("items").getJSONObject(0)
        assertEquals("OCR正文", partialItem.getString("data_text"))
        assertEquals("清洗后的正文", partialItem.getString("data_text_cleanup"))
        assertEquals("https://cdn.example.com/memory.jpg?sig=TEMP_SIGNATURE", partialItem.getString("screenshot"))
        assertFalse(partialItem.has("extra_data"))
        assertFalse(partialItem.has("details"))
        val partialOmitted = partialItem.getJSONArray("omitted").stringValues().toSet()
        assertFalse("full_text" in partialOmitted)
        assertFalse("image_urls" in partialOmitted)
        assertTrue("extra_data" in partialOmitted)
        assertTrue("details" in partialOmitted)

        val full = JSONObject(
            ColorOsMemoryDatabaseQuery.execute(
                database,
                ColorOsMemoryBridgeProtocol.OPERATION_SEARCH,
                JSONObject().put("detail", true),
            ),
        )

        assertFalse(full.has("summary_mode"))
        val fullItem = full.getJSONArray("items").getJSONObject(0)
        assertEquals("OCR正文", fullItem.getString("data_text"))
        assertEquals("短摘要", fullItem.getString("data_abstract"))
        assertEquals("{\"a\":1}", fullItem.getString("extra_data"))
        assertFalse(fullItem.has("omitted"))
        assertEquals(1, fullItem.getJSONObject("details").getJSONArray("bills").length())
    }

    private fun createRichMemories() {
        database.execSQL(
            "CREATE TABLE memories (memory_id TEXT, data_text TEXT, data_text_cleanup TEXT, data_abstract TEXT, " +
                "screenshot TEXT, audio_file TEXT, deeplink TEXT, extra_data TEXT, sub_scene_data TEXT, " +
                "app_name TEXT, scene_name TEXT, image_count INTEGER, created_time INTEGER, deleted INTEGER, " +
                "recycle_time INTEGER)",
        )
    }

    private fun createBills() {
        database.execSQL("CREATE TABLE bills (associate_memory_id TEXT, amount REAL, status INTEGER)")
    }

    private fun insertRichMemory(
        id: String,
        text: String,
        cleanup: String,
        abstractText: String,
        screenshot: String,
        extraData: String,
        subSceneData: String,
    ) {
        database.execSQL(
            "INSERT INTO memories VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?, '测试应用', '快递', 2, 10, 0, 0)",
            arrayOf<Any>(id, text, cleanup, abstractText, screenshot, extraData, subSceneData),
        )
    }

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map { getString(it) }

    private fun createMemories() {
        database.execSQL(
            "CREATE TABLE memories (memory_id TEXT, data_text TEXT, created_time INTEGER, " +
                "deleted INTEGER, recycle_time INTEGER)",
        )
    }

    private fun insertMemory(
        id: String,
        text: String,
        createdTime: Int = 1,
        deleted: Int = 0,
        recycleTime: Int = 0,
    ) {
        database.execSQL(
            "INSERT INTO memories VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any>(id, text, createdTime, deleted, recycleTime),
        )
    }

    private fun query(operation: String, keyword: String = ""): JSONObject = JSONObject(
        ColorOsMemoryDatabaseQuery.execute(
            ColorOsMemoryReadDatabase(database::rawQuery),
            operation,
            JSONObject().put("query", keyword),
        ),
    )

    private fun assertError(operation: String, expectedCode: String) {
        val result = query(operation)
        assertFalse(result.getBoolean("ok"))
        assertEquals(expectedCode, result.getString("code"))
    }
}
