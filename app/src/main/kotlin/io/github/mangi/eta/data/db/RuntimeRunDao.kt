package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger

@Dao
internal interface RuntimeRunDao : ChunkedTextDao {
    suspend fun restoreRuntimeResult(row: RuntimeResultEntity): RuntimeResultEntity = row.copy(
        content = restoreText("runtime_results", row.runId, "content", row.content),
        reasoningContent = restoreText("runtime_results", row.runId, "reasoningContent", row.reasoningContent),
        transcriptJson = restoreText("runtime_results", row.runId, "transcriptJson", row.transcriptJson),
        contextSnapshotJson = restoreText("runtime_results", row.runId, "contextSnapshotJson", row.contextSnapshotJson),
    )

    suspend fun restoreArchivedRun(row: RuntimeArchiveRunEntity): RuntimeArchiveRunEntity = row.copy(
        content = restoreText("runtime_archive_runs", row.archiveRunId, "content", row.content),
        reasoningContent = restoreText("runtime_archive_runs", row.archiveRunId, "reasoningContent", row.reasoningContent),
        transcriptJson = restoreText("runtime_archive_runs", row.archiveRunId, "transcriptJson", row.transcriptJson),
        contextSnapshotJson = restoreText("runtime_archive_runs", row.archiveRunId, "contextSnapshotJson", row.contextSnapshotJson),
        userImagePreviewsJson = restoreText("runtime_archive_runs", row.archiveRunId, "userImagePreviewsJson", row.userImagePreviewsJson),
    )

    suspend fun restoreInFlightRun(row: RuntimeInFlightRunEntity): RuntimeInFlightRunEntity = row.copy(
        transcriptJson = restoreInFlightTranscript(row.runId, row.transcriptJson),
        contextSnapshotJson = restoreText("runtime_inflight_runs", row.runId, "contextSnapshotJson", row.contextSnapshotJson),
    )

    @Query("SELECT run_id, handoff_id, handoff_source, handoff_payload, dismiss_entry_surface, ok, operation, rewrite_target_message_id, created_at FROM runtime_results ORDER BY created_at ASC LIMIT :limit")
    suspend fun pendingResultHeaders(limit: Int): List<RuntimeResultHeader>

    @Query("SELECT * FROM runtime_results WHERE run_id = :runId AND handoff_payload = :owner")
    suspend fun ownedResultRow(runId: String, owner: String): RuntimeResultEntity?

    @Transaction
    suspend fun ownedResult(runId: String, owner: String): RuntimeResultEntity? =
        ownedResultRow(runId, owner)?.let { restoreRuntimeResult(it) }

    @Query("SELECT EXISTS(SELECT 1 FROM runtime_inflight_runs WHERE run_id = :runId)")
    suspend fun hasInFlightRun(runId: String): Boolean

    @Query("UPDATE runtime_inflight_runs SET transcript_json = :transcript WHERE run_id = :runId")
    suspend fun updateTranscriptRow(runId: String, transcript: String)

    @Query("UPDATE runtime_inflight_runs SET transcript_json = :transcript, updated_at = :updatedAt WHERE run_id = :runId")
    suspend fun updateTranscriptRow(runId: String, transcript: String, updatedAt: Long)

    @Query("SELECT transcript_json FROM runtime_inflight_runs WHERE run_id = :runId")
    suspend fun inFlightTranscriptReference(runId: String): String?

    @Transaction
    suspend fun updateTranscript(runId: String, transcript: String) {
        if (!hasInFlightRun(runId)) return
        updateTranscriptRow(runId, storeText("runtime_inflight_runs", runId, "transcriptJson", transcript))
    }

    /**
     * 追加式 transcript 写入。
     *
     * 正常路径只写新增消息对应的尾部分块；full rebuild、旧格式迁移和损坏/缺失引用才整体重写。
     * 这里使用新的 `@eta:transcript:v1:` 引用格式，它与旧 `@eta:chunks:v1:` 格式共存，
     * 因而旧在途 run 仍可按原路径恢复。
     */
    @Transaction
    suspend fun appendTranscript(
        runId: String,
        messages: List<AgentModelClient.ConversationMessage>,
        fullRebuild: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!hasInFlightRun(runId)) return
        val existingReference = inFlightTranscriptReference(runId)
        val existingMatch = existingReference?.let(TRANSCRIPT_REFERENCE_PATTERN::matchEntire)

        if (fullRebuild) {
            replaceTranscript(runId, messages, now)
            return
        }
        if (existingMatch == null) {
            val combined = if (existingReference.isNullOrBlank() || existingReference == "[]") {
                messages
            } else {
                val legacy = restoreText(
                    table = "runtime_inflight_runs",
                    owner = runId,
                    field = "transcriptJson",
                    stored = existingReference,
                )
                runCatching { AgentConversationCodec.decodeTranscript(legacy) }
                    .getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_transcript_legacy_migration_failed") {
                            "Runtime transcript legacy migration failed: type=${throwable.javaClass.simpleName}"
                        }
                        emptyList()
                    } + messages
            }
            replaceTranscript(runId, combined, now)
            return
        }

        if (messages.isEmpty()) return
        val previousChunks = existingMatch.groupValues[1].toIntOrNull() ?: return
        val previousLength = existingMatch.groupValues[2].toIntOrNull() ?: return
        val previousMessages = existingMatch.groupValues[3].toIntOrNull() ?: return
        val appended = appendTextChunks(
            table = IN_FLIGHT_TABLE,
            owner = runId,
            field = TRANSCRIPT_FIELD,
            startIndex = previousChunks,
            pieces = AgentConversationCodec.transcriptInnerPieces(
                messages = messages,
                prependComma = previousMessages > 0,
            ),
        )
        updateTranscriptRow(
            runId = runId,
            transcript = transcriptReference(
                chunks = previousChunks + appended.chunkCount,
                length = previousLength + appended.charCount,
                messages = previousMessages + messages.size,
            ),
            updatedAt = now,
        )
    }

    @Transaction
    suspend fun replaceTranscript(
        runId: String,
        messages: List<AgentModelClient.ConversationMessage>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!hasInFlightRun(runId)) return
        deleteTextChunks(IN_FLIGHT_TABLE, runId, TRANSCRIPT_FIELD)
        if (messages.isEmpty()) {
            updateTranscriptRow(runId, "[]", now)
            return
        }
        val appended = appendTextChunks(
            table = IN_FLIGHT_TABLE,
            owner = runId,
            field = TRANSCRIPT_FIELD,
            startIndex = 0,
            pieces = AgentConversationCodec.transcriptInnerPieces(messages, prependComma = false),
        )
        updateTranscriptRow(
            runId = runId,
            transcript = transcriptReference(
                chunks = appended.chunkCount,
                length = appended.charCount,
                messages = messages.size,
            ),
            updatedAt = now,
        )
    }

    /**
     * 读取 transcript：新格式补回 JSON 数组括号；旧格式交给现有 restoreText 兼容处理。
     * 校验失败时保留磁盘引用不覆盖，返回空数组并留下诊断日志。
     */
    private suspend fun restoreInFlightTranscript(runId: String, stored: String): String {
        val match = TRANSCRIPT_REFERENCE_PATTERN.matchEntire(stored) ?: return restoreText(
            table = IN_FLIGHT_TABLE,
            owner = runId,
            field = TRANSCRIPT_FIELD,
            stored = stored,
        )
        val expectedChunks = match.groupValues[1].toIntOrNull()
        val expectedLength = match.groupValues[2].toIntOrNull()
        val expectedMessages = match.groupValues[3].toIntOrNull()
        if (expectedChunks == null || expectedLength == null || expectedMessages == null) {
            warnTranscriptRestoreFailure(runId, "引用数字超出 Int 范围")
            return "[]"
        }
        val result = StringBuilder(expectedLength + 2)
        var offset = 0
        while (offset < expectedChunks) {
            val page = textChunks(
                table = IN_FLIGHT_TABLE,
                owner = runId,
                field = TRANSCRIPT_FIELD,
                limit = minOf(32, expectedChunks - offset),
                offset = offset,
            )
            if (page.isEmpty()) {
                warnTranscriptRestoreFailure(runId, "分块缺失")
                return "[]"
            }
            for (chunk in page) {
                if (chunk.chunkIndex != offset) {
                    warnTranscriptRestoreFailure(runId, "分块顺序异常")
                    return "[]"
                }
                offset++
                result.append(chunk.content)
            }
        }
        if (result.length != expectedLength) {
            warnTranscriptRestoreFailure(runId, "恢复长度不符")
            return "[]"
        }
        return buildString(result.length + 2) {
            append('[')
            append(result)
            append(']')
        }
    }

    private fun warnTranscriptRestoreFailure(runId: String, reason: String) {
        AndroidAgentLogger.warnThrottled("runtime_transcript_restore_failed") {
            "Runtime transcript restore failed ($reason): run=${runId.take(16)}"
        }
    }

    @Query("UPDATE runtime_inflight_runs SET context_snapshot_json = :snapshot WHERE run_id = :runId")
    suspend fun updateContextSnapshotRow(runId: String, snapshot: String)

    @Transaction
    suspend fun updateContextSnapshot(runId: String, snapshot: String) {
        if (!hasInFlightRun(runId)) return
        updateContextSnapshotRow(runId, storeText("runtime_inflight_runs", runId, "contextSnapshotJson", snapshot))
    }

    @Query("SELECT * FROM runtime_results ORDER BY created_at ASC")
    suspend fun runtimeResultRows(): List<RuntimeResultEntity>

    @Transaction
    suspend fun runtimeResults(): List<RuntimeResultEntity> = runtimeResultRows().map { restoreRuntimeResult(it) }

    /**
     * 保留策略所需的轻量统计：一次查询同时得到行数与“已过期”行数，不读 content / transcript 等大字段。
     * 过期判据与旧实现一致：now - createdAt > 阈值 等价于 createdAt < now - 阈值（严格小于）。
     */
    @Query("SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN created_at < :expiredBefore THEN 1 ELSE 0 END), 0) AS expired FROM runtime_results")
    suspend fun runtimeResultRetentionCounts(expiredBefore: Long): RuntimeResultRetentionCounts

    /** 只取主键，按 created_at 升序定位待淘汰行（等价于旧实现“先过期、再最旧”的删除顺序）。 */
    @Query("SELECT run_id FROM runtime_results ORDER BY created_at ASC LIMIT :limit")
    suspend fun oldestRuntimeResultIds(limit: Int): List<String>

    @Query("DELETE FROM runtime_results WHERE run_id IN (:runIds)")
    suspend fun deleteRuntimeResultsByIdsBatch(runIds: List<String>)

    /** 单次批量删除内部按 SQLite 绑定参数上限分片，保证一次调用即可删完给定集合。 */
    @Transaction
    suspend fun deleteRuntimeResultsByIds(runIds: List<String>) {
        if (runIds.isEmpty()) return
        runIds.chunked(MAX_IDS_PER_STATEMENT).forEach { batch ->
            deleteRuntimeResultsByIdsBatch(batch)
        }
    }

    @Upsert
    suspend fun upsertRuntimeResultRow(result: RuntimeResultEntity)

    @Transaction
    suspend fun upsertRuntimeResult(result: RuntimeResultEntity) {
        upsertRuntimeResultRow(result.copy(
            content = storeText("runtime_results", result.runId, "content", result.content),
            reasoningContent = storeText("runtime_results", result.runId, "reasoningContent", result.reasoningContent),
            transcriptJson = storeText("runtime_results", result.runId, "transcriptJson", result.transcriptJson),
            contextSnapshotJson = storeText("runtime_results", result.runId, "contextSnapshotJson", result.contextSnapshotJson),
        ))
    }

    @Query("DELETE FROM runtime_results WHERE run_id = :runId")
    suspend fun deleteRuntimeResult(runId: String)

    @Query("DELETE FROM runtime_results")
    suspend fun deleteRuntimeResults()

    @Transaction
    suspend fun insertRuntimeResults(results: List<RuntimeResultEntity>) {
        results.forEach { upsertRuntimeResult(it) }
    }

    @Transaction
    suspend fun replaceRuntimeResults(results: List<RuntimeResultEntity>) {
        val retainedRunIds = results.mapTo(mutableSetOf()) { it.runId }
        val removedRunIds = runtimeResults()
            .map { it.runId }
            .filterNot { it in retainedRunIds }
        deleteRuntimeResults()
        if (results.isNotEmpty()) {
            insertRuntimeResults(results)
        }
        removedRunIds.forEach { runId -> deleteInFlightRun(runId) }
    }

    @Transaction
    @Query("SELECT * FROM runtime_archive_runs ORDER BY created_at ASC")
    suspend fun archivedRunRows(): List<RuntimeArchiveRunWithEvents>

    @Transaction
    suspend fun archivedRuns(): List<RuntimeArchiveRunWithEvents> = archivedRunRows().map { stored ->
        stored.copy(run = restoreArchivedRun(stored.run), events = stored.events.map { event ->
            event.copy(eventJson = restoreText("runtime_archive_runs", event.archiveRunId, "event:${event.sortIndex}", event.eventJson))
        })
    }

    /** 归档保留上限判定只需要计数，不必加载归档主记录与事件 JSON。 */
    @Query("SELECT COUNT(*) FROM runtime_archive_runs")
    suspend fun archivedRunCount(): Int

    /** 只取主键，按 created_at 升序定位溢出的归档（与旧实现“淘汰最旧”顺序一致）。 */
    @Query("SELECT archive_run_id FROM runtime_archive_runs ORDER BY created_at ASC LIMIT :limit")
    suspend fun oldestArchivedRunIds(limit: Int): List<String>

    @Query("DELETE FROM runtime_archive_runs WHERE archive_run_id IN (:archiveRunIds)")
    suspend fun deleteArchivedRunsByArchiveIdsBatch(archiveRunIds: List<String>)

    @Query("DELETE FROM runtime_archive_events WHERE archive_run_id IN (:archiveRunIds)")
    suspend fun deleteArchivedEventsByArchiveIdsBatch(archiveRunIds: List<String>)

    /** 与旧实现相同：每个被淘汰的归档先删主记录再删事件；整批在同一事务内完成，避免只删一半。 */
    @Transaction
    suspend fun deleteArchivedRunsByArchiveIds(archiveRunIds: List<String>) {
        if (archiveRunIds.isEmpty()) return
        archiveRunIds.chunked(MAX_IDS_PER_STATEMENT).forEach { batch ->
            deleteArchivedRunsByArchiveIdsBatch(batch)
            deleteArchivedEventsByArchiveIdsBatch(batch)
        }
    }

    @Upsert
    suspend fun upsertArchivedRunRow(run: RuntimeArchiveRunEntity)

    @Transaction
    suspend fun upsertArchivedRun(run: RuntimeArchiveRunEntity) {
        upsertArchivedRunRow(run.copy(
            content = storeText("runtime_archive_runs", run.archiveRunId, "content", run.content),
            reasoningContent = storeText("runtime_archive_runs", run.archiveRunId, "reasoningContent", run.reasoningContent),
            transcriptJson = storeText("runtime_archive_runs", run.archiveRunId, "transcriptJson", run.transcriptJson),
            contextSnapshotJson = storeText("runtime_archive_runs", run.archiveRunId, "contextSnapshotJson", run.contextSnapshotJson),
            userImagePreviewsJson = storeText("runtime_archive_runs", run.archiveRunId, "userImagePreviewsJson", run.userImagePreviewsJson),
        ))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchivedEventRow(event: RuntimeArchiveEventEntity)

    @Transaction
    suspend fun insertArchivedEvents(events: List<RuntimeArchiveEventEntity>) {
        events.forEach { event ->
            insertArchivedEventRow(event.copy(eventJson = storeText(
                "runtime_archive_runs", event.archiveRunId, "event:${event.sortIndex}", event.eventJson,
            )))
        }
    }

    @Query("DELETE FROM runtime_archive_events WHERE archive_run_id = :archiveRunId")
    suspend fun deleteArchivedEvents(archiveRunId: String)

    @Query("DELETE FROM runtime_archive_runs WHERE archive_run_id = :archiveRunId")
    suspend fun deleteArchivedRunByArchiveId(archiveRunId: String)

    @Query("DELETE FROM runtime_archive_runs WHERE run_id = :runId OR handoff_id = :runId")
    suspend fun deleteArchivedRun(runId: String)

    @Query("DELETE FROM runtime_archive_events")
    suspend fun deleteAllArchivedEvents()

    @Query("DELETE FROM runtime_archive_runs")
    suspend fun deleteAllArchivedRuns()

    @Transaction
    suspend fun replaceArchivedRun(
        run: RuntimeArchiveRunEntity,
        events: List<RuntimeArchiveEventEntity>,
    ) {
        deleteArchivedEvents(run.archiveRunId)
        upsertArchivedRun(run)
        if (events.isNotEmpty()) {
            insertArchivedEvents(events)
        }
    }

    @Transaction
    suspend fun replaceArchivedRuns(runs: List<RuntimeArchiveRunWithEventsSeed>) {
        deleteAllArchivedEvents()
        deleteAllArchivedRuns()
        runs.forEach { seed ->
            upsertArchivedRun(seed.run)
            if (seed.events.isNotEmpty()) {
                insertArchivedEvents(seed.events)
            }
        }
    }

    @Transaction
    @Query("SELECT * FROM runtime_inflight_runs ORDER BY created_at ASC")
    suspend fun inFlightRunRows(): List<RuntimeInFlightRunWithEvents>

    @Transaction
    suspend fun inFlightRuns(): List<RuntimeInFlightRunWithEvents> = inFlightRunRows().map { it.copy(run = restoreInFlightRun(it.run)) }

    @Upsert
    suspend fun upsertInFlightRunRow(run: RuntimeInFlightRunEntity)

    @Transaction
    suspend fun upsertInFlightRun(run: RuntimeInFlightRunEntity) {
        upsertInFlightRunRow(run.copy(
            transcriptJson = storeText("runtime_inflight_runs", run.runId, "transcriptJson", run.transcriptJson),
            contextSnapshotJson = storeText("runtime_inflight_runs", run.runId, "contextSnapshotJson", run.contextSnapshotJson),
        ))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInFlightEvent(event: RuntimeInFlightEventEntity)

    @Query("SELECT * FROM runtime_inflight_events WHERE run_id = :runId ORDER BY sort_index ASC")
    suspend fun inFlightEvents(runId: String): List<RuntimeInFlightEventEntity>

    @Query("UPDATE runtime_inflight_runs SET updated_at = :updatedAt WHERE run_id = :runId")
    suspend fun touchInFlightRun(runId: String, updatedAt: Long)

    @Query("DELETE FROM runtime_inflight_events WHERE run_id = :runId")
    suspend fun deleteInFlightEvents(runId: String)

    @Query("DELETE FROM runtime_inflight_runs WHERE run_id = :runId")
    suspend fun deleteInFlightRun(runId: String)

    @Transaction
    suspend fun acknowledgeRuntimeResult(runId: String) {
        deleteRuntimeResult(runId)
        deleteInFlightRun(runId)
    }

    @Transaction
    suspend fun replaceInFlightRun(run: RuntimeInFlightRunEntity) {
        deleteInFlightEvents(run.runId)
        upsertInFlightRun(run)
    }

    @Transaction
    suspend fun appendInFlightEvents(events: List<RuntimeInFlightEventEntity>, updatedAt: Long) {
        events.forEach { insertInFlightEvent(it) }
        events.firstOrNull()?.let { touchInFlightRun(it.runId, updatedAt) }
    }

    @Transaction
    suspend fun appendInFlightEvent(event: RuntimeInFlightEventEntity, updatedAt: Long) {
        insertInFlightEvent(event)
        touchInFlightRun(event.runId, updatedAt)
    }

    companion object {
        private const val IN_FLIGHT_TABLE = "runtime_inflight_runs"
        private const val TRANSCRIPT_FIELD = "transcriptJson"
        private const val TRANSCRIPT_REFERENCE_PREFIX = "@eta:transcript:v1:"
        private val TRANSCRIPT_REFERENCE_PATTERN =
            Regex("${Regex.escape(TRANSCRIPT_REFERENCE_PREFIX)}(\\d+):(\\d+):(\\d+)")

        private fun transcriptReference(chunks: Int, length: Int, messages: Int): String =
            "$TRANSCRIPT_REFERENCE_PREFIX$chunks:$length:$messages"
    }
}

internal data class RuntimeArchiveRunWithEventsSeed(
    val run: RuntimeArchiveRunEntity,
    val events: List<RuntimeArchiveEventEntity>,
)

/** runtime_results 保留策略的轻量计数结果（不包含任何正文列）。 */
internal data class RuntimeResultRetentionCounts(
    val total: Int,
    val expired: Int,
)

/** Room 把 IN (:ids) 展开成等量绑定参数，按旧版 SQLite 的 999 变量上限留出余量分片。 */
private const val MAX_IDS_PER_STATEMENT = 200
