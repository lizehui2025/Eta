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

    /** 数据分块只看 chunk_index >= 0：索引 -1 的哨兵行是损坏标记，不是正文。 */
    @Query("SELECT * FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field AND chunk_index >= 0 ORDER BY chunk_index LIMIT :limit OFFSET :offset")
    suspend fun textChunks(table: String, owner: String, field: String, limit: Int, offset: Int): List<AgentTextChunkEntity>

    /** 只探测该 (table, owner, field) 是否已有数据分块：走主键前缀索引，不读取任何分块正文；哨兵行不计入。 */
    @Query("SELECT EXISTS(SELECT 1 FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field AND chunk_index >= 0)")
    suspend fun hasTextChunks(table: String, owner: String, field: String): Boolean

    /**
     * 内联存储（≤ CHUNK_CHARS 且不带引用前缀）时，只有确认此前**没有**分块才跳过删除：
     * 长文本变短文本时 hasTextChunks 为 true，仍会先 deleteTextChunks 清掉旧分块再以内联方式写回；
     * 长文本路径先因短路判断不执行探测，语句数与顺序与旧实现完全一致。
     * 调用方均在 Room 事务内使用，探测与删除之间不存在其它写入者。
     *
     * 强保护：该字段若存在损坏哨兵行（见 [restoreText] 的四个失败分支），则退化值集合
     * （DEGENERATE_TEXT_WRITE_VALUES，见文件底部）中的写回一律被拒绝——旧实现会在
     * “读损坏 → 降级 → 整份写回”时删除残余分块并把主行引用覆盖为退化值，造成不可逆丢失；
     * 现在直接返回哨兵行保存的原始引用，使主行引用与残余分块全部保持原样。
     * 非退化值视为真实新内容：正常覆盖，并先清除损坏标记。
     */
    suspend fun storeText(table: String, owner: String, field: String, text: String): String {
        if (text in DEGENERATE_TEXT_WRITE_VALUES) {
            corruptedOriginalRef(table, owner, field)?.let { return it }
        } else {
            clearTextCorruption(table, owner, field)
        }
        val inline = text.length <= CHUNK_CHARS && !text.startsWith(REFERENCE_PREFIX)
        if (inline && !hasTextChunks(table, owner, field)) return text
        deleteTextChunks(table, owner, field)
        if (inline) return text
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

    /**
     * [storeText] 的分片版：正文由 [pieces] 依次产出，拼接结果就是正文。
     *
     * 语义与 [storeText] 完全一致——同样的内联阈值、同样的退化值保护与损坏哨兵行、
     * 同样的每块 ≤ [CHUNK_CHARS] 且不拆 UTF-16 代理对、返回同样的引用串、写同样的分块行
     * （拆点位置一致，所以对同一份正文两者产出的分块与引用逐字节相同）。
     *
     * 区别只在内存：内联判定与退化值保护都只可能命中短文本，因此先攒到刚越过 [CHUNK_CHARS]，
     * 确定走分块后就边收边冲、不再保留完整正文。长会话的 transcript 序列化后是几十 MB，
     * 整份构造会直接把堆打爆（实测单次分配 25.5 MB 触发 OutOfMemoryError），
     * 分片后任一刻只持有一个分片加一个 ≤ [CHUNK_CHARS] 的缓冲。
     */
    suspend fun storeTextPieces(
        table: String,
        owner: String,
        field: String,
        pieces: Sequence<String>,
    ): String {
        val buffer = StringBuilder()
        var length = 0
        var count = 0
        val iterator = pieces.iterator()

        // 攒到"确定不是短文本"为止：每块最多带进一个分片，缓冲不会超过 CHUNK_CHARS + 单个分片。
        var chunked = false
        while (iterator.hasNext()) {
            val piece = iterator.next()
            length += piece.length
            buffer.append(piece)
            if (buffer.length > CHUNK_CHARS) {
                chunked = true
                break
            }
        }

        if (!chunked) {
            // 短文本：与 storeText 同一段判定，此时完整正文就在缓冲里。
            val text = buffer.toString()
            if (text in DEGENERATE_TEXT_WRITE_VALUES) {
                corruptedOriginalRef(table, owner, field)?.let { return it }
            } else {
                clearTextCorruption(table, owner, field)
            }
            val inline = text.length <= CHUNK_CHARS && !text.startsWith(REFERENCE_PREFIX)
            if (inline && !hasTextChunks(table, owner, field)) return text
            deleteTextChunks(table, owner, field)
            if (inline) return text
            insertTextChunk(AgentTextChunkEntity(table, owner, field, count++, text))
            return "$REFERENCE_PREFIX$count:${text.length}"
        }

        // 长文本：必定不是退化值，按 storeText 的长文本分支处理（短路掉 hasTextChunks 探测）。
        clearTextCorruption(table, owner, field)
        deleteTextChunks(table, owner, field)
        suspend fun flush(force: Boolean) {
            while (buffer.length > CHUNK_CHARS || (force && buffer.isNotEmpty())) {
                // 不把 UTF-16 代理对拆到两个 SQLite TEXT 中；拆点与 storeText 完全一致。
                var end = minOf(CHUNK_CHARS, buffer.length)
                if (end < buffer.length && buffer[end - 1].isHighSurrogate()) end--
                insertTextChunk(AgentTextChunkEntity(table, owner, field, count++, buffer.substring(0, end)))
                buffer.delete(0, end)
            }
        }
        flush(force = false)
        while (iterator.hasNext()) {
            val piece = iterator.next()
            length += piece.length
            buffer.append(piece)
            flush(force = false)
        }
        flush(force = true)
        return "$REFERENCE_PREFIX$count:$length"
    }

    suspend fun restoreText(table: String, owner: String, field: String, stored: String): String {
        // 只有严格等于 "@eta:chunks:v1:<count>:<length>"（两段非负整数、无多余冒号或空白）才按分块引用恢复；
        // 其余以 REFERENCE_PREFIX 开头的历史文本一律原样返回，避免 ≤16 KiB 的遗留正文被误判为引用。
        // 匹配成功但校验失败的四个分支都会在 warn 之后写入损坏哨兵行（chunk_index = CORRUPTED_CHUNK_INDEX）：
        // content 保存损坏前的引用串，保证之后即使出现整份退化写回，也不会删光残余分块或覆盖主行引用。
        val match = REFERENCE_TEXT_PATTERN.matchEntire(stored) ?: return stored
        val count = match.groupValues[1].toIntOrNull()
        val length = match.groupValues[2].toIntOrNull()
        if (count == null || length == null) {
            warnChunkRestoreFailure(table, owner, field, "引用数字超出 Int 范围")
            markTextCorrupted(table, owner, field, stored, "引用数字超出 Int 范围")
            return ""
        }
        val result = StringBuilder()
        var offset = 0
        while (offset < count) {
            val page = textChunks(table, owner, field, minOf(32, count - offset), offset)
            if (page.isEmpty()) {
                warnChunkRestoreFailure(table, owner, field, "分块缺失")
                markTextCorrupted(table, owner, field, stored, "分块缺失")
                return ""
            }
            for (chunk in page) {
                if (chunk.chunkIndex != offset) {
                    warnChunkRestoreFailure(table, owner, field, "分块顺序异常")
                    markTextCorrupted(table, owner, field, stored, "分块顺序异常")
                    return ""
                }
                offset++
                result.append(chunk.content)
            }
        }
        if (result.length != length) {
            warnChunkRestoreFailure(table, owner, field, "恢复长度不符")
            markTextCorrupted(table, owner, field, stored, "恢复长度不符")
            return ""
        }
        return result.toString()
    }

    /** 清除损坏哨兵行：只删除 chunk_index = -1 的标记行，不触碰任何数据分块；写入非退化值前调用。 */
    @Query("DELETE FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field AND chunk_index = -1")
    suspend fun clearTextCorruption(table: String, owner: String, field: String)

    /** 只按主键探测 (table, owner, field) 是否带损坏标记，不读取任何正文；哨兵行 content 保存损坏前的引用串。 */
    @Query("SELECT EXISTS(SELECT 1 FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field AND chunk_index = -1)")
    suspend fun isTextCorrupted(table: String, owner: String, field: String): Boolean

    /** 读取哨兵行保存的原始引用串（很短，不会达到分块阈值）；不读取任何数据分块正文。 */
    @Query("SELECT content FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field AND chunk_index = -1")
    suspend fun corruptedOriginalRef(table: String, owner: String, field: String): String?

    companion object {
        private const val CHUNK_CHARS = 16_384
        const val REFERENCE_PREFIX = "@eta:chunks:v1:"

        /**
         * 损坏哨兵行的 chunk_index：真实数据分块只使用 0..n 索引，-1 专门表示“该字段的分块引用已损坏”。
         * 哨兵行复用 agent_text_chunks 表（不改表结构、不需要迁移），content 为损坏前的引用串，按主键前缀直达。
         */
        const val CORRUPTED_CHUNK_INDEX = -1
    }
}

/**
 * 分块引用已损坏时降级为空串：宁可少显示正文，也不抛出硬异常导致整个历史页面加载失败；
 * 同时留一条限流日志作为诊断线索。降级之外还会写入损坏哨兵行（见 [markTextCorrupted]），
 * 使该字段在后续整份写回时不再被退化值覆盖。
 */
private fun warnChunkRestoreFailure(table: String, owner: String, field: String, reason: String) {
    AndroidAgentLogger.warnThrottled("chunked_text_restore_failed") {
        "文本分块恢复失败（$reason）：table=$table, owner=$owner, field=$field"
    }
}

/**
 * 为 (table, owner, field) 写入损坏哨兵行：chunk_index = [ChunkedTextDao.CORRUPTED_CHUNK_INDEX]，
 * content 保存损坏前的引用串（写侧引用形态，很短，不会达到分块阈值）。
 * 已存在哨兵行时由 [ChunkedTextDao.insertTextChunk] 的 REPLACE 语义覆盖为最新现场；
 * 哨兵行不是正文：textChunks / hasTextChunks 都按 chunk_index >= 0 过滤。
 */
private suspend fun ChunkedTextDao.markTextCorrupted(
    table: String,
    owner: String,
    field: String,
    originalRef: String,
    reason: String,
) {
    insertTextChunk(AgentTextChunkEntity(table, owner, field, ChunkedTextDao.CORRUPTED_CHUNK_INDEX, originalRef))
    AndroidAgentLogger.warnThrottled("chunked_text_corruption_marked") {
        "文本分块已标记损坏（$reason），残余分块将拒绝退化写回：table=$table, owner=$owner, field=$field"
    }
}

/**
 * 退化写回值集合：字段读取失败后最常降级到的投影（空串），以及空集合/空对象的序列化默认值。
 * 选它们作为拒绝集合的理由：这些值几乎不可能与“用户真实新内容”冲突，却是“读损坏 → 降级 →
 * 整份写回”链路里覆盖主行引用、删光残余分块的元凶（如 content=""、transcriptJson="[]"、
 * 快照 "{}"、可空 "null"）；对已带损坏标记的字段一律拒绝以此类值写回主行，直到出现真正的非退化内容。
 */
private val DEGENERATE_TEXT_WRITE_VALUES: Set<String> = setOf("", "[]", "{}", "null")

/** 只匹配写侧 storeText 生成的引用形态；matchEntire 要求整串匹配，禁止多余冒号或空白。 */
private val REFERENCE_TEXT_PATTERN = Regex("${Regex.escape(ChunkedTextDao.REFERENCE_PREFIX)}(\\d+):(\\d+)")
