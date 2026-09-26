package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeResultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** 终态先落盘，入口成功提交后 ACK；未确认结果按数量与年龄淘汰，避免 DB 无限膨胀。 */
internal object AgentRuntimeResultStore {
    private const val MAX_AGE_MS = 12L * 60L * 60L * 1000L
    private const val MAX_RECENT_ACKNOWLEDGEMENTS = 32
    /** 未确认结果保留上限与最大年龄：超限删最旧，避免外部唤起堆积。 */
    private const val MAX_RETAINED_RESULTS = 50
    private const val MAX_RESULT_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    private val deliveryLock = Any()
    private val recentlyAcknowledgedRunIds = LinkedHashMap<String, Long>()

    /**
     * 返回 false 表示同一 run 已先收到 ACK，不应在 ACK 之后重新写回待交付队列。
     */
    fun add(context: Context, completedRun: AgentRuntimeWire.CompletedRun): Boolean {
        val appContext = context.applicationContext
        val entity = completedRun.toEntity()
        synchronized(deliveryLock) {
            pruneAcknowledgements(System.currentTimeMillis())
            if (recentlyAcknowledgedRunIds.containsKey(entity.runId)) return false
            runBlocking(Dispatchers.IO) {
                val dao = EtaDatabase.get(appContext).runtimeRunDao()
                dao.upsertRuntimeResult(entity)
                pruneOldResultsLocked(dao)
            }
            return true
        }
    }

    /**
     * 保留语义（与旧实现逐条对应，删除集合保持一致）：
     * 1) 年龄淘汰：`now - createdAt > MAX_RESULT_AGE_MS` 的行全部删除（SQL 侧即 `created_at < now - MAX_RESULT_AGE_MS`，严格小于）；
     * 2) 数量淘汰：年龄未过期的行里只保留最新的 MAX_RETAINED_RESULTS 条，更旧的按 created_at 升序删除；
     * 3) 不淘汰过新结果：数量淘汰只删“最旧”的溢出部分，刚写入的最新结果永远不在候选内；
     * 4) 不额外淘汰未确认结果：删除候选严格是上述两条规则的交集（该表保存的就是尚未 ACK 的待交付结果，
     *    ACK 由 remove()/acknowledgeRuntimeResult 负责），不存在由此之外的额外删除。
     *
     * 过期行必然比未过期行旧，因此 (1)(2) 合并后等价于“按 created_at 升序删最旧的 K 条”，
     * K = max(expired, total - MAX_RETAINED_RESULTS)（推导：K = expired + max(0, retained - 50) 且 total = expired + retained）。
     */
    private suspend fun pruneOldResultsLocked(dao: io.github.mangi.eta.data.db.RuntimeRunDao) {
        runCatching {
            val now = System.currentTimeMillis()
            // 只有计数与主键查询，不再把含 transcript/content 的全表行读进内存。
            val counts = dao.runtimeResultRetentionCounts(now - MAX_RESULT_AGE_MS)
            val deleteCount = maxOf(counts.expired, counts.total - MAX_RETAINED_RESULTS)
            if (deleteCount <= 0) return@runCatching
            val runIds = dao.oldestRuntimeResultIds(deleteCount)
            if (runIds.isEmpty()) return@runCatching
            // 单次批量删除（内部按 IN 参数上限分片）；仍然保留在 deliveryLock 内，
            // 使“计数 → 取主键 → 删除”与 add()/remove() 的写入互斥，不引入锁外删除的竞态。
            dao.deleteRuntimeResultsByIds(runIds)
        }.onFailure { throwable ->
            // 裁剪失败保持原有语义（异常不外抛、返回值不变），但必须可见：
            // 失败意味着 MAX_RETAINED_RESULTS / MAX_RESULT_AGE_MS 保留上限可能暂时失效。
            AndroidAgentLogger.warnThrottled("runtime_result_prune_failed") {
                "Agent runtime result prune failed; retention may be exceeded " +
                    "(maxRetained=$MAX_RETAINED_RESULTS, maxAgeMs=$MAX_RESULT_AGE_MS): " +
                    "type=${throwable.safeLogType()}"
            }
        }
    }

    fun list(context: Context): List<AgentRuntimeWire.CompletedRun> {
        val appContext = context.applicationContext
        return synchronized(deliveryLock) {
            runBlocking(Dispatchers.IO) {
                EtaDatabase.get(appContext).runtimeRunDao().runtimeResults()
                    .map { it.toDomain() }
            }
        }
    }

    fun pendingPage(context: Context): List<AgentRuntimeWire.CompletedRun> = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context.applicationContext).runtimeRunDao().pendingResultHeaders(8).map { header ->
            AgentRuntimeWire.CompletedRun(
                AgentRuntimeWire.EntryHandoff(header.handoffId, header.handoffSource, header.handoffPayload, header.dismissEntrySurface),
                AgentRuntimeWire.RunResult(header.runId, header.ok, "", contextSnapshotRef = header.runId,
                    operation = header.operation, rewriteTargetMessageId = header.rewriteTargetMessageId),
                header.createdAt,
            )
        }
    }

    fun readOwned(context: Context, runId: String, owner: String): AgentRuntimeWire.CompletedRun? = runBlocking(Dispatchers.IO) {
        EtaDatabase.get(context.applicationContext).runtimeRunDao().ownedResult(runId, owner)?.toDomain()
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        val appContext = context.applicationContext
        synchronized(deliveryLock) {
            runBlocking(Dispatchers.IO) {
                EtaDatabase.get(appContext)
                    .runtimeRunDao()
                    .acknowledgeRuntimeResult(runId)
            }
            rememberAcknowledgement(runId, System.currentTimeMillis())
        }
    }

    private fun rememberAcknowledgement(runId: String, now: Long) {
        pruneAcknowledgements(now)
        recentlyAcknowledgedRunIds.remove(runId)
        while (recentlyAcknowledgedRunIds.size >= MAX_RECENT_ACKNOWLEDGEMENTS) {
            val oldestRunId = recentlyAcknowledgedRunIds.keys.firstOrNull() ?: break
            recentlyAcknowledgedRunIds.remove(oldestRunId)
        }
        recentlyAcknowledgedRunIds[runId] = now
    }

    private fun pruneAcknowledgements(now: Long) {
        recentlyAcknowledgedRunIds.entries.removeAll { (_, acknowledgedAt) ->
            now - acknowledgedAt > MAX_AGE_MS
        }
    }

    private fun AgentRuntimeWire.CompletedRun.toEntity(): RuntimeResultEntity {
        val stableRunId = result.runId.ifBlank { handoff.id }
        return RuntimeResultEntity(
            runId = stableRunId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            ok = result.ok,
            content = result.content,
            error = result.error,
            reasoningContent = result.reasoningContent,
            transcriptJson = AgentConversationCodec.encodeTranscriptForStorage(result.transcript),
            contextSnapshotJson = result.contextSnapshot?.encode().orEmpty(),
            operation = result.operation,
            rewriteTargetMessageId = result.rewriteTargetMessageId,
            createdAt = createdAt,
        )
    }

    private fun RuntimeResultEntity.toDomain(): AgentRuntimeWire.CompletedRun =
        AgentRuntimeWire.CompletedRun(
            handoff = AgentRuntimeWire.EntryHandoff(
                id = handoffId,
                source = handoffSource,
                payload = handoffPayload,
                dismissEntrySurfaceOnForegroundOperation = dismissEntrySurface,
            ),
            result = AgentRuntimeWire.RunResult(
                runId = runId,
                ok = ok,
                content = content,
                error = error,
                reasoningContent = reasoningContent,
                contextSnapshot = AgentContextSnapshot.decode(contextSnapshotJson),
                operation = operation,
                rewriteTargetMessageId = rewriteTargetMessageId,
                transcript = legacyCompatibleTranscript(
                    raw = transcriptJson,
                    ok = ok,
                    content = content,
                    reasoningContent = reasoningContent,
                ),
            ),
            createdAt = createdAt,
        )

    private fun legacyCompatibleTranscript(
        raw: String,
        ok: Boolean,
        content: String,
        reasoningContent: String,
    ): List<AgentModelClient.ConversationMessage> =
        AgentConversationCodec.decodeTranscript(raw).ifEmpty {
            if (!ok || content.isBlank()) return@ifEmpty emptyList()
            listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = content,
                    reasoningContent = reasoningContent,
                )
            )
        }
}
