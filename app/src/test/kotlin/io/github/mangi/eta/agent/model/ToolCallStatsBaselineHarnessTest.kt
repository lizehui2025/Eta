package io.github.mangi.eta.agent.model

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实基线 harness：分析从设备拉取的 Room 归档库副本。
 *
 * 用法：
 *   adb shell "run-as io.github.mangi.eta cat databases/eta.db" > /tmp/eta-archive.db
 *   ./gradlew :app:testDebugUnitTest --tests "*ToolCallStatsBaselineHarnessTest" \
 *     -Deta.toolstats.db=/tmp/eta-archive.db
 *
 * 未提供或文件不存在时跳过，不伪造真实数据；合成基线见 [ToolCallStatsAnalyzerTest]。
 */
class ToolCallStatsBaselineHarnessTest {
    @Test
    fun analyzePulledArchiveDatabaseWhenProvided() {
        val path = System.getProperty(DB_PROPERTY) ?: System.getenv("ETA_TOOLSTATS_DB").orEmpty()
        assumeTrue("未提供 $DB_PROPERTY，跳过真实基线", path.isNotBlank())
        val file = File(path)
        assumeTrue("归档库副本不存在：$path", file.isFile)

        val database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        val transcripts = try {
            database.rawQuery("SELECT transcript_json FROM runtime_archive_runs", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.let(::add)
                    }
                }
            }
        } finally {
            database.close()
        }

        val transcript = transcripts
            .filter { it.isNotBlank() }
            .flatMap { AgentConversationCodec.decodeTranscript(it) }
        val report = ToolCallStatsAnalyzer.analyze(transcript, emptyList())
        val formatted = ToolCallStatsAnalyzer.format(report, "device:$path")
        println(formatted)
        // 归档存在时至少应产出可解析的结构；具体失败率由报告呈现，不在测试里硬编码。
        assertTrue(report.totalCalls >= 0)
    }

    private companion object {
        const val DB_PROPERTY = "eta.toolstats.db"
    }
}
