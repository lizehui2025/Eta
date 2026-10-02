package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface ConversationDao : ChunkedTextDao {
    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at " +
            "FROM conversations ORDER BY updated_at DESC"
    )
    suspend fun conversationMetadataRows(): List<ConversationMetadata>

    @Transaction
    suspend fun conversations(): List<ConversationMetadata> = conversationMetadataRows().map { restoreMetadata(it) }

    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, roleplay_json, revisions_json, created_at, updated_at " +
            "FROM conversations ORDER BY updated_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun conversationMetadataPage(limit: Int, offset: Int): List<ConversationMetadata>

    @Transaction
    suspend fun conversationsPage(limit: Int, offset: Int): List<ConversationMetadata> =
        conversationMetadataPage(limit, offset).map { restoreMetadata(it) }

    suspend fun restoreMetadata(row: ConversationMetadata) = row.copy(
        appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
        roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
        revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
    )

    @Query("SELECT roleplay_json FROM conversations WHERE id = :conversationId")
    suspend fun roleplayJsonRow(conversationId: String): String?

    @Transaction
    suspend fun roleplayJson(conversationId: String): String? = roleplayJsonRow(conversationId)?.let {
        restoreText("conversations", conversationId, "roleplay", it)
    }

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC")
    suspend fun messageRows(): List<ConversationMessageEntity>

    @Transaction
    suspend fun messages(): List<ConversationMessageEntity> = messageRows().map { restoreMessage(it) }

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC")
    suspend fun conversationEntityRows(): List<ConversationEntity>

    @Transaction
    suspend fun conversationEntities(): List<ConversationEntity> = conversationEntityRows().map { row ->
        row.copy(
            appliedRuntimeRunIdsJson = restoreText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
            roleplayJson = restoreText("conversations", row.id, "roleplay", row.roleplayJson),
            revisionsJson = restoreText("conversations", row.id, "revisions", row.revisionsJson),
        )
    }

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC")
    suspend fun contextCheckpointRows(): List<ConversationContextCheckpointEntity>

    @Transaction
    suspend fun contextCheckpoints(): List<ConversationContextCheckpointEntity> = contextCheckpointRows().map { restoreCheckpoint(it) }

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun messageRowsPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity> =
        messageRowsPage(conversationId, limit, offset).map { restoreMessage(it) }

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND id = :messageId AND type = 'assistant')")
    suspend fun hasAssistantMessage(conversationId: String, messageId: String): Boolean

    @Query("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun contextCheckpointRow(conversationId: String): ConversationContextCheckpointEntity?

    @Query("SELECT * FROM conversation_context_epochs WHERE conversation_id = :conversationId")
    suspend fun contextEpochRow(conversationId: String): ConversationContextEpochEntity?

    @Query(
        "SELECT * FROM conversation_context_events " +
            "WHERE conversation_id = :conversationId AND seq > :afterSeq ORDER BY seq ASC"
    )
    suspend fun contextEventsAfter(conversationId: String, afterSeq: Long): List<ConversationContextEventEntity>

    @Upsert
    suspend fun upsertContextEpoch(epoch: ConversationContextEpochEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContextEvent(event: ConversationContextEventEntity): Long

    @Query("SELECT COALESCE(MAX(seq), 0) FROM conversation_context_events WHERE conversation_id = :conversationId")
    suspend fun latestContextEventSeq(conversationId: String): Long

    @Query(
        "UPDATE conversation_context_epochs SET replacement_seq = :replacementSeq " +
            "WHERE conversation_id = :conversationId"
    )
    suspend fun updateContextReplacementSeq(conversationId: String, replacementSeq: Long)

    @Query("DELETE FROM conversation_context_epochs WHERE conversation_id = :conversationId")
    suspend fun deleteContextEpoch(conversationId: String)

    @Query("DELETE FROM conversation_context_events WHERE conversation_id = :conversationId AND seq <= :throughSeq")
    suspend fun deleteContextEventsThrough(conversationId: String, throughSeq: Long)

    /** Epoch row and its event are committed together; an ignored duplicate is idempotent. */
    @Transaction
    suspend fun commitContextEpoch(
        epoch: ConversationContextEpochEntity,
        event: ConversationContextEventEntity?,
    ) {
        event?.let { insertContextEvent(it) }
        upsertContextEpoch(epoch)
    }

    @Transaction
    suspend fun contextCheckpoint(conversationId: String): ConversationContextCheckpointEntity? =
        contextCheckpointRow(conversationId)?.let { restoreCheckpoint(it) }

    suspend fun restoreCheckpoint(row: ConversationContextCheckpointEntity) = row.copy(
        historyJson = restoreText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
        journalJson = restoreText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
    )

    @Query("SELECT * FROM conversation_state WHERE id = :id")
    suspend fun state(id: String = ConversationStateEntity.SINGLETON_ID): ConversationStateEntity?

    @Upsert
    suspend fun insertConversationRow(conversation: ConversationEntity)

    @Transaction
    suspend fun insertConversations(conversations: List<ConversationEntity>) {
        conversations.forEach { row ->
            insertConversationRow(row.copy(
                appliedRuntimeRunIdsJson = storeText("conversations", row.id, "runs", row.appliedRuntimeRunIdsJson),
                roleplayJson = storeText("conversations", row.id, "roleplay", row.roleplayJson),
                revisionsJson = storeText("conversations", row.id, "revisions", row.revisionsJson),
            ))
        }
    }

    @Upsert
    suspend fun insertMessageRow(message: ConversationMessageEntity)

    @Transaction
    suspend fun insertMessages(messages: List<ConversationMessageEntity>) {
        messages.forEach { row ->
            insertMessageRow(row.copy(
                content = storeText("conversation_messages", row.id, "content", row.content),
                imagesJson = storeText("conversation_messages", row.id, "images", row.imagesJson),
                stepsJson = storeText("conversation_messages", row.id, "steps", row.stepsJson),
            ))
        }
    }

    suspend fun restoreMessage(row: ConversationMessageEntity) = row.copy(
        content = restoreText("conversation_messages", row.id, "content", row.content),
        imagesJson = restoreText("conversation_messages", row.id, "images", row.imagesJson),
        stepsJson = restoreText("conversation_messages", row.id, "steps", row.stepsJson),
    )

    @Upsert
    suspend fun insertContextCheckpointRow(checkpoint: ConversationContextCheckpointEntity)

    /**
     * 检查点正文的分片来源：惰性序列，拼接结果就是落库正文。
     * 长会话的 transcript 整份序列化是几十 MB 的堆分配，分片后任一刻只持有一个分片。
     */
    class StreamedCheckpoint(
        val history: Sequence<String>,
        val journal: Sequence<String>,
    )

    @Transaction
    suspend fun insertContextCheckpoints(checkpoints: List<ConversationContextCheckpointEntity>) {
        checkpoints.forEach { row ->
            insertContextCheckpointRow(row.copy(
                historyJson = storeText("conversation_context_checkpoints", row.conversationId, "history", row.historyJson),
                journalJson = storeText("conversation_context_checkpoints", row.conversationId, "journal", row.journalJson),
            ))
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ConversationStateEntity)

    @Query("SELECT id FROM conversations")
    suspend fun conversationRowIds(): List<String>

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteConversationRows(ids: List<String>)

    @Query("DELETE FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun deleteMessagesOf(conversationId: String)

    @Query("DELETE FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun deleteCheckpointOf(conversationId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("DELETE FROM conversation_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM conversation_context_checkpoints")
    suspend fun deleteContextCheckpoints()

    @Query("DELETE FROM conversation_state")
    suspend fun deleteState()

    /**
     * 增量保存：只覆盖本次传入的会话与其消息、检查点，未传入的会话按删除处理。
     *
     * 与 [replaceAll] 的最终状态一致（[replaceAll] 的语义是"以传入内容为准的整库覆盖"），
     * 但不再删除并重插未变化的会话——那会让每次保存都为全部历史重写一遍文本分块。
     * 删除的会话显式删消息与检查点，保证分块清理触发器照常触发，不依赖外键级联的触发语义。
     */
    @Transaction
    suspend fun saveIncremental(
        conversations: List<ConversationEntity>,
        messagesByConversation: Map<String, List<ConversationMessageEntity>>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        streamedCheckpoints: Map<String, StreamedCheckpoint> = emptyMap(),
        removedConversationIds: List<String> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        removedConversationIds.forEach { conversationId ->
            deleteMessagesOf(conversationId)
            deleteCheckpointOf(conversationId)
        }
        if (removedConversationIds.isNotEmpty()) deleteConversationRows(removedConversationIds)
        insertConversations(conversations)
        conversations.forEach { row ->
            val messages = messagesByConversation[row.id] ?: return@forEach
            deleteMessagesOf(row.id)
            if (messages.isNotEmpty()) insertMessages(messages)
        }
        insertContextCheckpoints(contextCheckpoints)
        streamedCheckpoints.forEach { (conversationId, transcript) ->
            insertContextCheckpointRow(
                ConversationContextCheckpointEntity(
                    conversationId = conversationId,
                    historyJson = storeTextPieces(
                        "conversation_context_checkpoints", conversationId, "history", transcript.history,
                    ),
                    journalJson = storeTextPieces(
                        "conversation_context_checkpoints", conversationId, "journal", transcript.journal,
                    ),
                )
            )
        }
        deleteState()
        state?.let { insertState(it) }
    }

    @Transaction
    suspend fun replaceAll(
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        deleteMessages()
        deleteContextCheckpoints()
        deleteConversations()
        deleteState()
        insertConversations(conversations)
        insertContextCheckpoints(contextCheckpoints)
        insertMessages(messages)
        state?.let { insertState(it) }
    }
}
