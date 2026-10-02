package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.context.ContextEpochEvent
import io.github.mangi.eta.agent.context.ContextEpochStore
import io.github.mangi.eta.agent.context.StoredContextEpoch
import io.github.mangi.eta.data.db.ConversationContextEpochEntity
import io.github.mangi.eta.data.db.ConversationContextEventEntity
import io.github.mangi.eta.data.db.ConversationDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Blocking adapter used by the synchronous runtime executor. */
internal class RoomContextEpochStore(
    private val dao: ConversationDao,
) : ContextEpochStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(conversationId: String, scopeHash: String): StoredContextEpoch? = runBlocking(Dispatchers.IO) {
        dao.contextEpochRow(conversationId)?.takeIf { it.scopeHash == scopeHash }?.toDomain()
    }

    override fun activeEvents(conversationId: String, afterSeq: Long): List<ContextEpochEvent> =
        runBlocking(Dispatchers.IO) {
            dao.contextEventsAfter(conversationId, afterSeq).map { row ->
                ContextEpochEvent(row.conversationId, row.seq, row.text)
            }
        }

    override fun commit(epoch: StoredContextEpoch, event: ContextEpochEvent?) {
        runBlocking(Dispatchers.IO) {
            dao.commitContextEpoch(epoch.toEntity(), event?.toEntity())
        }
    }

    override fun markCompaction(conversationId: String, scopeHash: String): Long = runBlocking(Dispatchers.IO) {
        val current = dao.contextEpochRow(conversationId)?.takeIf { it.scopeHash == scopeHash } ?: return@runBlocking 0L
        val next = maxOf(current.nextEventSeq, current.replacementSeq + 1)
        dao.updateContextReplacementSeq(conversationId, next)
        next
    }

    override fun reset(conversationId: String, scopeHash: String) {
        runBlocking(Dispatchers.IO) {
            dao.deleteContextEventsThrough(conversationId, Long.MAX_VALUE)
            dao.deleteContextEpoch(conversationId)
        }
    }

    private fun ConversationContextEpochEntity.toDomain(): StoredContextEpoch = StoredContextEpoch(
        conversationId = conversationId,
        scopeHash = scopeHash,
        baseline = baseline,
        snapshot = runCatching { json.parseToJsonElement(snapshotJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap())),
        baselineSeq = baselineSeq,
        replacementSeq = replacementSeq,
        nextEventSeq = nextEventSeq,
    )

    private fun StoredContextEpoch.toEntity() = ConversationContextEpochEntity(
        conversationId = conversationId,
        scopeHash = scopeHash,
        baseline = baseline,
        snapshotJson = json.encodeToString(JsonObject.serializer(), snapshot),
        baselineSeq = baselineSeq,
        replacementSeq = replacementSeq,
        nextEventSeq = nextEventSeq,
    )

    private fun ContextEpochEvent.toEntity() = ConversationContextEventEntity(
        conversationId = conversationId,
        seq = seq,
        sourceKey = "core/ambient",
        messageId = "context-$conversationId-$seq",
        text = sourceText,
        createdAt = System.currentTimeMillis(),
    )
}
