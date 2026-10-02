package io.github.mangi.eta.agent.context

import kotlinx.serialization.json.JsonObject

internal data class StoredContextEpoch(
    val conversationId: String,
    val scopeHash: String,
    val baseline: String,
    val snapshot: JsonObject,
    val baselineSeq: Long = 0,
    val replacementSeq: Long = 0,
    val nextEventSeq: Long = 1,
)

internal data class ContextEpochEvent(
    val conversationId: String,
    val seq: Long,
    val sourceText: String,
)

internal interface ContextEpochStore {
    fun load(conversationId: String, scopeHash: String): StoredContextEpoch?
    fun activeEvents(conversationId: String, afterSeq: Long): List<ContextEpochEvent>
    fun commit(
        epoch: StoredContextEpoch,
        event: ContextEpochEvent?,
    )
    fun markCompaction(conversationId: String, scopeHash: String): Long
    fun reset(conversationId: String, scopeHash: String)
}

internal data class PreparedContextEpoch(
    val baseline: String,
    val snapshot: JsonObject,
    val pendingEvents: List<ContextEpochEvent>,
    val replacement: Boolean,
)

/** Coordinates the pure source state machine with a durable store. */
internal class ContextEpoch(
    private val conversationId: String,
    private val scopeHash: String,
    private val store: ContextEpochStore,
) {
    private var stored: StoredContextEpoch? = store.load(conversationId, scopeHash)

    fun prepare(sources: List<ContextSource<*>>): PreparedContextEpoch? {
        val current = stored
        if (current == null) {
            val initialized = SystemContext.initialize(sources)
            if (initialized !is ContextInitialization.Ready) return null
            val epoch = StoredContextEpoch(
                conversationId = conversationId,
                scopeHash = scopeHash,
                baseline = initialized.generation.baseline,
                snapshot = initialized.generation.snapshot,
            )
            store.commit(epoch, event = null)
            stored = epoch
            return PreparedContextEpoch(epoch.baseline, epoch.snapshot, emptyList(), replacement = false)
        }

        val replacementRequested = current.replacementSeq > current.baselineSeq
        val result = if (replacementRequested) {
            SystemContext.replace(sources)
        } else {
            SystemContext.reconcile(sources, current.snapshot)
        }
        return when (result) {
            ContextReconcileResult.Unchanged,
            ContextReconcileResult.ReplacementBlocked,
            -> PreparedContextEpoch(
                baseline = current.baseline,
                snapshot = current.snapshot,
                pendingEvents = store.activeEvents(conversationId, current.baselineSeq),
                replacement = false,
            )
            is ContextReconcileResult.Updated -> {
                val event = ContextEpochEvent(conversationId, current.nextEventSeq, result.text)
                val next = current.copy(
                    snapshot = result.snapshot,
                    nextEventSeq = current.nextEventSeq + 1,
                )
                store.commit(next, event)
                stored = next
                PreparedContextEpoch(
                    baseline = next.baseline,
                    snapshot = next.snapshot,
                    pendingEvents = store.activeEvents(conversationId, next.baselineSeq),
                    replacement = false,
                )
            }
            is ContextReconcileResult.ReplacementReady -> {
                val seq = maxOf(current.nextEventSeq, current.replacementSeq)
                val next = StoredContextEpoch(
                    conversationId = conversationId,
                    scopeHash = scopeHash,
                    baseline = result.generation.baseline,
                    snapshot = result.generation.snapshot,
                    baselineSeq = seq,
                    replacementSeq = current.replacementSeq,
                    nextEventSeq = seq + 1,
                )
                store.commit(next, event = null)
                stored = next
                PreparedContextEpoch(next.baseline, next.snapshot, emptyList(), replacement = true)
            }
        }
    }

    fun onCompactionCompleted() {
        val next = store.markCompaction(conversationId, scopeHash)
        stored = stored?.copy(replacementSeq = next)
    }
}

internal class InMemoryContextEpochStore : ContextEpochStore {
    private val epochs = mutableMapOf<String, StoredContextEpoch>()
    private val events = mutableMapOf<String, MutableList<ContextEpochEvent>>()

    override fun load(conversationId: String, scopeHash: String): StoredContextEpoch? =
        epochs[conversationId]?.takeIf { it.scopeHash == scopeHash }

    override fun activeEvents(conversationId: String, afterSeq: Long): List<ContextEpochEvent> =
        events[conversationId].orEmpty().filter { it.seq > afterSeq }.sortedBy { it.seq }

    override fun commit(epoch: StoredContextEpoch, event: ContextEpochEvent?) {
        epochs[epoch.conversationId] = epoch
        if (event != null) events.getOrPut(epoch.conversationId) { mutableListOf() }
            .removeAll { it.seq == event.seq }
        if (event != null) events.getOrPut(epoch.conversationId) { mutableListOf() }.add(event)
    }

    override fun markCompaction(conversationId: String, scopeHash: String): Long {
        val current = epochs[conversationId]?.takeIf { it.scopeHash == scopeHash } ?: return 0
        val next = maxOf(current.nextEventSeq, current.replacementSeq + 1)
        epochs[conversationId] = current.copy(replacementSeq = next)
        return next
    }

    override fun reset(conversationId: String, scopeHash: String) {
        epochs.remove(conversationId)
        events.remove(conversationId)
    }
}
