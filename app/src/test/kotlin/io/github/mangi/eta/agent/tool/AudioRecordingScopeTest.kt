package io.github.mangi.eta.agent.tool

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * search_audio / search_recordings 的筛选差异：两者都读媒体库 audio/media，
 * 但用互补的 relative_path 条件区分“音乐/语音”和“录音应用产物”。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AudioRecordingScopeTest {
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
    fun audioAndRecordingsApplyComplementaryPathFilters() {
        database.execSQL("CREATE TABLE audio (relative_path TEXT)")
        listOf(
            "Music/song.mp3",
            "Download/voice.mp3",
            "Ringtones/ring.ogg",
            "Recordings/record-1.m4a",
            "录音/通话录音.m4a",
            "Voice Recorder/note.m4a",
        ).forEach { database.execSQL("INSERT INTO audio VALUES (?)", arrayOf<Any>(it)) }
        database.execSQL("INSERT INTO audio VALUES (NULL)")

        assertEquals(
            listOf("Music/song.mp3", "Download/voice.mp3", "Ringtones/ring.ogg", NULL_PATH),
            paths(AUDIO_NON_RECORDING_PATH_FILTER),
        )
        assertEquals(
            listOf("Recordings/record-1.m4a", "录音/通话录音.m4a", "Voice Recorder/note.m4a"),
            paths(AUDIO_RECORDING_PATH_FILTER),
        )
    }

    @Test
    fun filtersAreComplementsAndNeverBothMatchTheSameRow() {
        database.execSQL("CREATE TABLE audio (relative_path TEXT)")
        listOf("Music/a.mp3", "Recordings/b.m4a", "录音/c.m4a").forEach {
            database.execSQL("INSERT INTO audio VALUES (?)", arrayOf<Any>(it))
        }

        val matchedAsAudio = paths(AUDIO_NON_RECORDING_PATH_FILTER).toSet()
        val matchedAsRecording = paths(AUDIO_RECORDING_PATH_FILTER).toSet()

        assertEquals(3, matchedAsAudio.size + matchedAsRecording.size)
        assertEquals(emptySet<String>(), matchedAsAudio intersect matchedAsRecording)
    }

    private fun paths(filter: String): List<String> =
        database.rawQuery("SELECT relative_path FROM audio WHERE $filter", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0) ?: NULL_PATH)
            }
        }

    private companion object {
        const val NULL_PATH = "<null>"
    }
}
