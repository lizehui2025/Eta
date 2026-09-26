package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    /** 会话路由键：压缩请求与主对话共享服务端缓存路由。 */
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
) {
    val budget = AgentContextBudget(config.contextWindow)
    private var compacted = false
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null
    private var publishedSignature: String? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    private fun publishSnapshot(candidate: JSONArray = messages) {
        if (!compacted) return
        // 压缩一旦发生，之后每轮都会走到这里；若上下文与已提交快照一致就不必再全量转换 + 落库。
        val signature = listOf(
            candidate.length(), consumedUserTurns, consumedSupplementCount, transcriptSize(),
            System.identityHashCode(candidate.opt(candidate.length() - 1)),
        ).joinToString(":")
        if (signature == publishedSignature && committedSnapshot != null) return
        val snapshot = createSnapshot(candidate)
        snapshot.encode()
        onContextSnapshot(snapshot)
        committedSnapshot = snapshot
        publishedSignature = signature
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = durableHistory(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
        )
    }

    fun compact(roundTools: JSONArray, force: Boolean = false, final: Boolean = false) {
        // 实时口径：有上轮服务商真实 input 即按“真实+增量”投影判压缩，
        // 无真实值才回退校准估算；日志同时记录分类明细，便于对账窗口内容。
        val before = budget.effectiveTokens(messages, roundTools)
        val shouldCompact = force || budget.shouldCompact(before)
        // 分类明细是第二遍全量扫描（含整份工具 schema），只在真正要压缩时才算；未触发时只留一行计数日志。
        logWindowBreakdown("压缩检查", roundTools, before, detailed = shouldCompact)
        if (!shouldCompact) {
            try {
                publishSnapshot()
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!final) throw failure
                committedSnapshot = createSnapshot(messages)
                onEvent(AgentEvent.ContextCompaction(operationId, "failed", before,
                    reasonCode = "CONTEXT_CHECKPOINT_FAILED"))
            }
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before))
        try {
            var candidate = messages
            // 不设尝试次数上限：只要每次摘要都在缩小就继续，直到达标；
            // 某次不再缩小即停并报错，避免无进展时无限消耗模型调用。
            var previousTokens = before
            do {
                candidate = AgentContextCompactor(
                    config, provider, runController,
                    estimate = { candidate -> budget.rawEstimateCached(candidate) },
                    roleplay = roleplay,
                    sessionId = sessionId,
                ).compact(candidate, systemCount, sensitiveIds(), force)
                val tokens = budget.effectiveTokens(candidate, roundTools)
                if (!budget.shouldCompact(tokens)) break
                if (tokens >= previousTokens) {
                    throw AgentContextCompactor.failure("CONTEXT_NO_REDUCTION", "摘要后上下文仍超过容量预算。")
                }
                previousTokens = tokens
            } while (true)
            runController.throwIfCancelled()
            val wasCompacted = compacted
            compacted = true
            try {
                publishSnapshot(candidate)
            } catch (failure: Exception) {
                compacted = wasCompacted
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            val after = budget.effectiveTokens(messages, roundTools)
            logWindowBreakdown("压缩完成", roundTools, after, detailed = true)
            onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_COMPLETED, before,
                after))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final && (force || budget.exceedsWindow(before))) throw failure
            if (final) {
                // 已完成的回答仍成功交付；完整快照随终态 outbox 保存，不依赖先前检查点写入成功。
                committedSnapshot = createSnapshot(messages)
            }
        }
    }

    /** 窗口分类日志：每次判压缩一行；明细是第二遍全量分类统计，只在真要压缩时才计算。 */
    private fun logWindowBreakdown(stage: String, roundTools: JSONArray, tokens: Int, detailed: Boolean) {
        runCatching {
            val breakdown = if (detailed) {
                // 超大历史下明细统计本身就是一次全量扫描，直接跳过，省下一遍大文本遍历。
                if (isHugeHistory()) " 明细跳过(超大历史)"
                else " 分类[${AgentContextBreakdownCounter.breakdown(messages, roundTools).summaryLine()}]"
            } else {
                ""
            }
            val real = budget.lastRealInputTokens()
            val stats = budget.stats()
            val cacheLine = " 估算缓存[消息=" + "%.0f".format(stats.messageHitRate * 100) + "%(未命中" +
                stats.messageMisses + "次), 工具表=" + "%.0f".format(stats.toolSchemaHitRate * 100) + "%]"
            AndroidAgentLogger.info(
                "Agent context $stage: effective=$tokens" +
                    (real?.let { " (实时锚点=$it, 校准=${"%.2f".format(budget.calibrationFactor())})" }
                        ?: " (无实时锚点, 校准=${"%.2f".format(budget.calibrationFactor())})") +
                    cacheLine +
                    breakdown,
            )
        }
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    /**
     * 超大历史快判：只加长度不扫正文，O(消息数)。超过阈值即跳过分类明细的第二遍全量扫描，
     * 把压缩准备从“估算+明细”两遍降为一遍。
     */
    private fun isHugeHistory(): Boolean {
        var total = 0L
        for (index in systemCount until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (val content = message.opt("content")) {
                is String -> total += content.length
                is JSONArray -> total += content.length() * 64L
                else -> Unit
            }
            total += message.optString("reasoning_content").length
            message.optJSONArray("tool_calls")?.let { total += it.length() * 256L }
            if (total > HUGE_HISTORY_CHARS) return true
        }
        return false
    }

    companion object {
        /** 明细跳过阈值：约 500K token 量级，超过即认为第二遍扫描不值得。 */
        private const val HUGE_HISTORY_CHARS = 1_500_000L
    }
}
