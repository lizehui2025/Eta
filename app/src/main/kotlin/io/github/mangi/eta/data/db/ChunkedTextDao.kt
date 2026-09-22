package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.mangi.eta.core.AndroidAgentLogger

/** 每行有界，文档总长不限；避免完整历史占用单个 CursorWindow 行。 */
@Entity(tableName = "agent_text_chunks", primaryKeys = ["owner_table", "owner_id", "field", "chunk_index"])
internal data class AgentTextChunkEntity(
    @ColumnInfo(name = "owner_table") val ownerTable: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val field: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
)

/** 由业务 DAO 在同一 Room 事务内写入主记录与分块，不暴露存储引用给领域层。 */
internal interface ChunkedTextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTextChunk(chunk: AgentTextChunkEntity)

    @Query("DELETE FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field")
    suspend fun deleteTextChunks(table: String, owner: String, field: String)

    @Query("SELECT * FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field ORDER BY chunk_index LIMIT :limit OFFSET :offset")
    suspend fun textChunks(table: String, owner: String, field: String, limit: Int, offset: Int): List<AgentTextChunkEntity>

    suspend fun storeText(table: String, owner: String, field: String, text: String): String {
        deleteTextChunks(table, owner, field)
        if (text.length <= CHUNK_CHARS && !text.startsWith(REFERENCE_PREFIX)) return text
        var count = 0
        var offset = 0
        while (offset < text.length) {
            // 不把 UTF-16 代理对拆到两个 SQLite TEXT 中。
            var end = minOf(offset + CHUNK_CHARS, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            insertTextChunk(AgentTextChunkEntity(table, owner, field, count++, text.substring(offset, end)))
            offset = end
        }
        return "$REFERENCE_PREFIX$count:${text.length}"
    }

    suspend fun restoreText(table: String, owner: String, field: String, stored: String): String {
        // 只有严格等于 "@eta:chunks:v1:<count>:<length>"（两段非负整数、无多余冒号或空白）才按分块引用恢复；
        // 其余以 REFERENCE_PREFIX 开头的历史文本一律原样返回，避免 ≤16 KiB 的遗留正文被误判为引用。
        val match = REFERENCE_TEXT_PATTERN.matchEntire(stored) ?: return stored
        val count = match.groupValues[1].toIntOrNull()
        val length = match.groupValues[2].toIntOrNull()
        if (count == null || length == null) {
            warnChunkRestoreFailure(table, owner, field, "引用数字超出 Int 范围")
            return ""
        }
        val result = StringBuilder()
        var offset = 0
        while (offset < count) {
            val page = textChunks(table, owner, field, minOf(32, count - offset), offset)
            if (page.isEmpty()) {
                warnChunkRestoreFailure(table, owner, field, "分块缺失")
                return ""
            }
            for (chunk in page) {
                if (chunk.chunkIndex != offset) {
                    warnChunkRestoreFailure(table, owner, field, "分块顺序异常")
                    return ""
                }
                offset++
                result.append(chunk.content)
            }
        }
        if (result.length != length) {
            warnChunkRestoreFailure(table, owner, field, "恢复长度不符")
            return ""
        }
        return result.toString()
    }

    companion object {
        private const val CHUNK_CHARS = 16_384
        const val REFERENCE_PREFIX = "@eta:chunks:v1:"
    }
}

/**
 * 分块引用已损坏时降级为空串：宁可少显示正文，也不抛出硬异常导致整个历史页面加载失败；
 * 同时留一条限流日志作为诊断线索。
 */
private fun warnChunkRestoreFailure(table: String, owner: String, field: String, reason: String) {
    AndroidAgentLogger.warnThrottled("chunked_text_restore_failed") {
        "文本分块恢复失败（$reason）：table=$table, owner=$owner, field=$field"
    }
}

/** 只匹配写侧 storeText 生成的引用形态；matchEntire 要求整串匹配，禁止多余冒号或空白。 */
private val REFERENCE_TEXT_PATTERN = Regex("${Regex.escape(ChunkedTextDao.REFERENCE_PREFIX)}(\\d+):(\\d+)")
