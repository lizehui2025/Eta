package io.github.mangi.eta.agent.tool

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * get_health_summary 在记录全为 0 时必须区分“未授权/不可读”与“确实无数据”；
 * 有数据时保持既有结构不变。直接注入内存数据库，不触发 Root 快照。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentPrivateDatabaseHealthSummaryTest {
    private lateinit var database: SQLiteDatabase
    private lateinit var root: BoundedRootCommandExecutor
    private lateinit var tools: AgentPrivateDatabaseTools

    @Before
    fun setUp() {
        database = SQLiteDatabase.create(null)
        root = BoundedRootCommandExecutor(NoOpLogger, rootAvailable = { false })
        tools = AgentPrivateDatabaseTools(RuntimeEnvironment.getApplication(), root)
    }

    @After
    fun tearDown() {
        database.close()
        root.close()
    }

    @Test
    fun emptyButReadableHealthDatabaseReportsGrantedPermission() {
        createCoreTables()

        val result = summary()

        assertTrue(result.getBoolean("ok"))
        assertEquals("get_health_summary", result.getString("tool"))
        assertEquals(0, result.getJSONObject("summary").getJSONObject("steps").getInt("records"))
        assertTrue(result.getBoolean("readable"))
        assertEquals("granted", result.getString("permission"))
        assertTrue(result.getString("note").contains("确实没有记录"))
        assertFalse(result.has("missing_tables"))
    }

    @Test
    fun missingHealthSchemaReportsUnavailablePermission() {
        database.execSQL("CREATE TABLE unrelated (value TEXT)")

        val result = summary()

        assertFalse(result.getBoolean("readable"))
        assertEquals("unavailable", result.getString("permission"))
        assertEquals(3, result.getJSONArray("missing_tables").length())
        assertTrue(result.getString("note").contains("未授予"))
    }

    @Test
    fun existingRecordsKeepSummaryStructureWithoutAStatusBlock() {
        createCoreTables()
        val now = System.currentTimeMillis()
        database.execSQL("INSERT INTO steps_record_table VALUES (?, ?)", arrayOf<Any>(now, 20))
        database.execSQL("INSERT INTO sleep_session_record_table VALUES (?, ?)", arrayOf<Any>(now - 60_000, now))

        val result = summary()

        val summary = result.getJSONObject("summary")
        assertEquals(1, summary.getJSONObject("steps").getInt("records"))
        assertEquals(20, summary.getJSONObject("steps").getInt("count"))
        assertEquals(1, summary.getJSONObject("sleep").getInt("sessions"))
        assertEquals(60_000L, summary.getJSONObject("sleep").getLong("duration_ms"))
        assertFalse(result.has("permission"))
        assertFalse(result.has("readable"))
    }

    private fun summary(): JSONObject =
        JSONObject(tools.healthSummary(database, JSONObject().put("days", 7)))

    private fun createCoreTables() {
        database.execSQL("CREATE TABLE steps_record_table (end_time INTEGER, count INTEGER)")
        database.execSQL("CREATE TABLE sleep_session_record_table (start_time INTEGER, end_time INTEGER)")
        database.execSQL("CREATE TABLE exercise_session_record_table (start_time INTEGER, end_time INTEGER)")
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
