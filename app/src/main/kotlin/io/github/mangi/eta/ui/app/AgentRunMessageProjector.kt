package io.github.mangi.eta.ui.app

import android.os.SystemClock
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolStepUi
import io.github.mangi.eta.ui.model.UserMessageUi

internal class AgentRunMessageProjector(
    private val nowElapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val thinkingStartedAt = mutableMapOf<String, Long>()

    /** 回放从该 run 的空轨迹重建；仅重排有回放事件的补充输入，旧 handoff 独有的输入必须保留。 */
    fun resetForReplay(
        runId: String,
        messages: List<AgentChatMessageUi>,
        replaySupplementIndexes: Set<Int> = emptySet(),
    ): List<AgentChatMessageUi> {
        if (runId.isBlank()) return messages
        clearRun(runId)
        val replaySupplementIds = replaySupplementIndexes.mapTo(mutableSetOf()) { index ->
            AgentPendingResultRecovery.supplementMessageId(runId, index)
        }
        return messages.filterNot { message ->
            when (message) {
                is AgentMessageUi -> isAssistantMessageForRun(message.id, runId)
                is ThinkingMessageUi -> message.id.startsWith("$runId-thinking-")
                is ToolActivityMessageUi -> message.id.startsWith("$runId-tool-") ||
                    message.id.startsWith(subagentMessagePrefix(runId))
                is SystemNoticeMessageUi ->
                    isAssistantMessageForRun(message.id, runId) || message.id == "interrupted-$runId"
                is UserMessageUi -> message.id in replaySupplementIds
                else -> false
            }
        }
    }

    fun finishContextCompaction(
        runId: String,
        messages: List<AgentChatMessageUi>,
        detail: String,
    ): List<AgentChatMessageUi> = messages.map { message ->
        if (message is SystemNoticeMessageUi && message.code == SystemNoticeCode.ContextCompaction &&
            message.id.startsWith("assistant-$runId-compaction-") && message.running) {
            message.copy(detail = detail, running = false)
        } else message
    }

    fun scheduleModelRetry(
        runId: String,
        event: AgentEvent.ModelRetryScheduled,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val finalized = finalizeTextRound(
            runId, event.round, finalizeThinkingRound(runId, event.round, messages),
        )
        val notice = SystemNoticeMessageUi(
            id = "assistant-$runId-retry-${event.round}",
            code = SystemNoticeCode.ModelRetry,
            detail = event.displayMessage,
        )
        return finalized.filterNot { it.id == notice.id } + notice
    }

    fun startAssistantBlock(
        runId: String,
        event: AgentEvent.AssistantBlockStart,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> = transitionVisibleBlock(
        runId = runId,
        round = event.round,
        kind = event.kind,
        index = event.index,
        messages = messages,
    )

    fun appendTextDelta(
        runId: String,
        round: Int,
        index: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        val transitioned = transitionVisibleBlock(
            runId = runId,
            round = round,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = index,
            messages = messages,
        )
        val assistantId = assistantMessageId(runId, round, index)
        var updated = false
        val next = transitioned.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    renderMarkdown = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next + AgentMessageUi(
            id = assistantId,
            content = delta,
            isStreaming = true,
            renderMarkdown = false,
        )
    }

    fun appendReasoningDelta(
        runId: String,
        round: Int,
        index: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty()) return messages

        val transitioned = transitionVisibleBlock(
            runId = runId,
            round = round,
            kind = AgentEvent.AssistantBlockKind.THINKING,
            index = index,
            messages = messages,
        )
        val thinkingId = thinkingMessageId(runId, round, index)
        val elapsedSeconds = elapsedSeconds(thinkingId)
        var updated = false
        val next = transitioned.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                updated = true
                message.copy(
                    content = message.content + delta,
                    isStreaming = true,
                    elapsedSeconds = elapsedSeconds,
                    collapsed = false,
                )
            } else {
                message
            }
        }
        if (updated) return next

        return next + ThinkingMessageUi(
            id = thinkingId,
            content = delta,
            isStreaming = true,
            elapsedSeconds = elapsedSeconds,
            collapsed = false,
        )
    }

    fun ensureCompletedThinking(
        runId: String,
        round: Int,
        content: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (messages.any {
                it is ThinkingMessageUi && isThinkingMessageForRound(it.id, runId, round)
            }
        ) {
            return finalizeThinkingRound(runId, round, messages)
        }

        val thinkingId = thinkingFallbackMessageId(runId, round)
        return messages.insertBeforeFirstAssistant(
            runId = runId,
            round = round,
            message = ThinkingMessageUi(
                id = thinkingId,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds(thinkingId),
                collapsed = true,
            )
        )
    }

    fun finalizeThinking(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ThinkingMessageUi && message.id.startsWith("$runId-thinking-")) {
                message.finished()
            } else {
                message
            }
        }

    /** 终态不依赖各块结束事件全部到齐；缺少工具结果时只能标为未知，不能推断执行成功。 */
    fun finalizeRun(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        finalizeText(runId, finalizeThinking(runId, messages)).map { message ->
            if (
                message is ToolActivityMessageUi &&
                (message.id.startsWith("$runId-tool-") ||
                    message.id.startsWith(subagentMessagePrefix(runId))) &&
                message.status == ToolActivityStatusUi.Running
            ) {
                message.copy(status = ToolActivityStatusUi.Unknown)
            } else {
                message
            }
        }

    fun finalizeThinkingRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        return messages.map { message ->
            if (message is ThinkingMessageUi && isThinkingMessageForRound(message.id, runId, round)) {
                message.finished()
            } else {
                message
            }
        }
    }

    fun finalizeThinkingBlock(
        runId: String,
        round: Int,
        index: Int,
        replacementContent: String?,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val thinkingId = thinkingMessageId(runId, round, index)
        return messages.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                message.finished(replacementContent)
            } else {
                message
            }
        }
    }

    fun finalizeText(
        runId: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is AgentMessageUi && isAssistantMessageForRun(message.id, runId)) {
                message.copy(
                    content = message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }

    fun finalizeTextRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 定稿时裁掉尾部空白：模型输出常以换行收尾，Markdown 渲染会把每个尾部
        // 换行节点变成一段固定间距，在正文与后续工具卡片之间形成莫名的空行。
        return messages.map { message ->
            if (message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)) {
                message.copy(
                    content = message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }
    }

    fun finalizeTextBlock(
        runId: String,
        round: Int,
        index: Int,
        replacementContent: String?,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val assistantId = assistantMessageId(runId, round, index)
        return messages.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                message.copy(
                    content = replacementContent?.trimEnd() ?: message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }
    }

    fun startTool(
        runId: String,
        event: AgentEvent.ToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 工具执行发生在对应 assistant 工具块完整返回之后；此时直接追加即可保留
        // 工具前说明、工具活动与下一轮结果的真实时间顺序。
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = event.argsPreview,
            command = event.command,
        )
        if (messages.any { it.id == message.id }) return messages
        return messages + message
    }

    fun finishTool(
        runId: String,
        event: AgentEvent.ToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        // success 字段优先；旧版本 Runtime/归档事件缺省时回退到摘要文本判断
        val status = when (event.success) {
            true -> ToolActivityStatusUi.Success
            false -> ToolActivityStatusUi.Failed
            null -> if (event.resultSummary.contains("ok=false", ignoreCase = true)) {
                ToolActivityStatusUi.Failed
            } else {
                ToolActivityStatusUi.Success
            }
        }
        val targetIndex = messages.indexOfLast { it is ToolActivityMessageUi && it.id == targetId }
        if (targetIndex < 0) return messages

        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is ToolActivityMessageUi) {
                message.copy(
                    status = status,
                    resultSummary = event.resultSummary,
                    imageCount = event.imageCount,
                    detail = event.detail.takeIf(String::isNotBlank),
                )
            } else {
                message
            }
        }
    }

    fun startHostedTool(
        runId: String,
        event: AgentEvent.HostedToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "",
        )
        return if (messages.any { it.id == message.id }) messages else messages + message
    }

    fun finishHostedTool(
        runId: String,
        event: AgentEvent.HostedToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        return messages.map { message ->
            if (message is ToolActivityMessageUi && message.id == targetId) {
                message.copy(
                    status = if (event.success) ToolActivityStatusUi.Success else ToolActivityStatusUi.Failed,
                    resultSummary = null,
                )
            } else {
                message
            }
        }
    }

    /**
     * 子代理独立行：每个子代理一行 ToolActivity 卡片（toolName="subagent"，标题取 label），
     * 可展开查看该子代理的工具步骤与最终结果。步骤文本累积在 resultSummary 中，按行追加。
     */
    fun startSubagents(
        runId: String,
        event: AgentEvent.SubagentsStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        var next = messages;
        for (index in event.labels.indices) {
            val label = event.labels[index];
            val id = subagentMessageId(runId, event.round, event.toolCallId, index);
            if (next.any { it.id == id }) continue;
            next = next + ToolActivityMessageUi(
                id = id,
                toolName = SUBAGENT_TOOL_NAME,
                status = ToolActivityStatusUi.Running,
                argumentsSummary = label,
                resultSummary = "已派生，等待执行",
            );
        }
        return next;
    }

    fun startSubagent(
        runId: String,
        event: AgentEvent.SubagentStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val id = subagentMessageId(runId, event.round, event.toolCallId, event.subIndex);
        val existing = messages.firstOrNull { it.id == id };
        if (existing is ToolActivityMessageUi) {
            return messages.map { m ->
                if (m.id == id && m is ToolActivityMessageUi && m.status == ToolActivityStatusUi.Running) m
                else m
            };
        }
        // SubagentsStarted 可能因事件乱序晚到：先建行占位，后续 startSubagents 会跳过已存在行。
        return messages + ToolActivityMessageUi(
            id = id,
            toolName = SUBAGENT_TOOL_NAME,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = event.label,
            resultSummary = "执行中",
        );
    }

    fun startSubagentTool(
        runId: String,
        event: AgentEvent.SubagentToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val id = subagentMessageId(runId, event.round, event.toolCallId, event.subIndex);
        val line = "… " + event.innerToolName + event.argsPreview.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty();
        val step = ToolStepUi(
            id = event.innerToolCallId,
            toolName = event.innerToolName,
            status = ToolActivityStatusUi.Running,
            summary = event.argsPreview,
        )
        var found = false;
        val next = messages.map { m ->
            if (m.id == id && m is ToolActivityMessageUi) {
                found = true;
                m.copy(
                    status = ToolActivityStatusUi.Running,
                    argumentsSummary = m.argumentsSummary.takeIf { it.isNotBlank() } ?: event.label,
                    resultSummary = appendSubagentLine(m.resultSummary, line),
                    steps = m.steps.upsertRunningStep(step),
                );
            } else m;
        };
        if (found) return next;
        return next + ToolActivityMessageUi(
            id = id,
            toolName = SUBAGENT_TOOL_NAME,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = event.label,
            resultSummary = line,
            steps = listOf(step),
        );
    }

    fun finishSubagentTool(
        runId: String,
        event: AgentEvent.SubagentToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val id = subagentMessageId(runId, event.round, event.toolCallId, event.subIndex);
        val mark = if (event.success == false) "✗ " else "✓ ";
        val firstLine = event.resultSummary.lineSequence().firstOrNull().orEmpty().take(120);
        val line = mark + event.innerToolName + firstLine.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty();
        val stepStatus = if (event.success == false) {
            ToolActivityStatusUi.Failed
        } else {
            ToolActivityStatusUi.Success
        }
        return messages.map { m ->
            if (m.id == id && m is ToolActivityMessageUi) {
                m.copy(
                    status = ToolActivityStatusUi.Running,
                    resultSummary = replaceLastRunningLine(m.resultSummary, line),
                    steps = m.steps.finishStep(
                        id = event.innerToolCallId,
                        toolName = event.innerToolName,
                        status = stepStatus,
                        detail = event.detail,
                    ),
                );
            } else m;
        };
    }

    fun finishSubagent(
        runId: String,
        event: AgentEvent.SubagentFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val id = subagentMessageId(runId, event.round, event.toolCallId, event.subIndex);
        val status = if (event.ok) ToolActivityStatusUi.Success else ToolActivityStatusUi.Failed;
        val tail = event.content.lineSequence().firstOrNull().orEmpty().take(300);
        val duration = if (event.durationMs >= 0) "（" + event.durationMs + "ms）" else "";
        val changedNote = if (event.changedFiles.isNotEmpty()) "已改 " + event.changedFiles.size + " 个文件" else "";
        val baseLine = if (tail.isNotBlank()) tail + duration else (if (event.ok) "完成" else "失败") + duration;
        val summary = if (changedNote.isNotBlank()) baseLine + " · " + changedNote else baseLine;
        val resultDetail = buildSubagentDetail(event).takeIf { it.isNotBlank() };
        var found = false;
        val next = messages.map { m ->
            if (m.id == id && m is ToolActivityMessageUi) {
                found = true;
                m.copy(
                    status = status,
                    resultSummary = appendSubagentLine(m.resultSummary, summary),
                    steps = m.steps.settleRunningSteps(),
                    detail = resultDetail,
                );
            } else m;
        };
        if (found) return next;
        return next + ToolActivityMessageUi(
            id = id,
            toolName = SUBAGENT_TOOL_NAME,
            status = status,
            argumentsSummary = event.label,
            resultSummary = summary,
            detail = resultDetail,
        );
    }

    fun finishSubagents(
        runId: String,
        event: AgentEvent.SubagentsFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 汇总事件只做兜底：正常路径每个子行已由 SubagentFinished 收敛；
        // 超时/异常导致缺失时，未终态的子行按 Unknown 处理，避免永久 Running。
        return messages.map { m ->
            if (m is ToolActivityMessageUi && m.toolName == SUBAGENT_TOOL_NAME &&
                m.status == ToolActivityStatusUi.Running &&
                m.id.startsWith(subagentMessagePrefix(runId)) &&
                m.id.contains("-${event.round}-${event.toolCallId}-")
            ) {
                m.copy(
                    status = ToolActivityStatusUi.Unknown,
                    steps = m.steps.settleRunningSteps(),
                );
            } else m;
        };
    }

    private fun appendSubagentLine(previous: String?, line: String): String {
        val prev = previous.orEmpty();
        // 去掉占位文案，步骤开始累积。
        val base = if (prev == "已派生，等待执行" || prev == "执行中") "" else prev;
        val appended = if (base.isBlank()) line else base + "\n" + line;
        return appended.takeLast(MAX_SUBAGENT_TRACE_CHARS);
    }

    private fun replaceLastRunningLine(previous: String?, line: String): String {
        val prev = previous.orEmpty();
        if (prev.isBlank() || prev == "已派生，等待执行" || prev == "执行中") return line;
        val lines = prev.lines().toMutableList();
        val idx = lines.indexOfLast { it.startsWith("… ") };
        if (idx >= 0) lines[idx] = line; else lines += line;
        return lines.joinToString("\n").takeLast(MAX_SUBAGENT_TRACE_CHARS);
    }

    /** 子代理步骤按 innerToolCallId 幂等更新：完成态不覆盖，其余替换；不存在则追加。 */
    private fun List<ToolStepUi>.upsertRunningStep(step: ToolStepUi): List<ToolStepUi> {
        val index = indexOfLast { it.id == step.id }
        if (index < 0) return this + step
        if (this[index].status != ToolActivityStatusUi.Running) return this
        return toMutableList().apply { set(index, step) }
    }

    /** 子代理步骤完成：更新状态与详情；步骤缺失（乱序/重放）时按完成态补一条。 */
    private fun List<ToolStepUi>.finishStep(
        id: String,
        toolName: String,
        status: ToolActivityStatusUi,
        detail: String,
    ): List<ToolStepUi> {
        val boundedDetail = detail.take(MAX_TOOL_DETAIL_CHARS)
        val index = indexOfLast { it.id == id }
        if (index < 0) {
            return this + ToolStepUi(
                id = id,
                toolName = toolName,
                status = status,
                summary = "",
                detail = boundedDetail,
            )
        }
        val existing = this[index]
        return toMutableList().apply {
            set(
                index,
                existing.copy(
                    status = status,
                    detail = boundedDetail.takeIf { it.isNotBlank() } ?: existing.detail,
                ),
            )
        }
    }

    /** 子代理行收敛时把仍处于 Running 的步骤统一落到终止状态。 */
    private fun List<ToolStepUi>.settleRunningSteps(
        status: ToolActivityStatusUi = ToolActivityStatusUi.Unknown,
    ): List<ToolStepUi> =
        map { step ->
            if (step.status == ToolActivityStatusUi.Running) step.copy(status = status) else step
        }

    /** 子代理详情弹窗的最终结果：完整内容 + 耗时与改动文件注记。 */
    private fun buildSubagentDetail(event: AgentEvent.SubagentFinished): String {
        val builder = StringBuilder(event.content.trim())
        val notes = buildList {
            if (event.durationMs >= 0) add("耗时 ${event.durationMs}ms")
            if (event.changedFiles.isNotEmpty()) {
                add(
                    "已修改 ${event.changedFiles.size} 个文件：\n" +
                        event.changedFiles.take(MAX_SUBAGENT_DETAIL_FILES).joinToString("\n"),
                )
            }
        }
        if (notes.isNotEmpty()) {
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(notes.joinToString("\n"))
        }
        return builder.toString().take(MAX_SUBAGENT_RESULT_DETAIL_CHARS)
    }

    fun failRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Failed,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                    steps = message.steps.settleRunningSteps(ToolActivityStatusUi.Failed),
                )
            } else {
                message
            }
        }

    fun interruptRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Unknown,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                    steps = message.steps.settleRunningSteps(),
                )
            } else {
                message
            }
        }

    fun clearRun(runId: String) {
        thinkingStartedAt.keys.removeAll { it.startsWith("$runId-thinking-") }
    }

    private fun ThinkingMessageUi.finished(authoritativeContent: String? = null): ThinkingMessageUi =
        copy(
            content = authoritativeContent ?: content,
            isStreaming = false,
            elapsedSeconds = thinkingStartedAt[id]?.let { startedAt ->
                ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
            } ?: elapsedSeconds,
            collapsed = true,
        )

    private fun elapsedSeconds(thinkingId: String): Int {
        val startedAt = thinkingStartedAt.getOrPut(thinkingId, nowElapsedRealtime)
        return ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
    }

    private fun transitionVisibleBlock(
        runId: String,
        round: Int,
        kind: AgentEvent.AssistantBlockKind,
        index: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val activeAssistantId = if (kind == AgentEvent.AssistantBlockKind.TEXT) {
            assistantMessageId(runId, round, index)
        } else {
            null
        }
        val activeThinkingId = if (kind == AgentEvent.AssistantBlockKind.THINKING) {
            thinkingMessageId(runId, round, index)
        } else {
            null
        }
        return messages.map { message ->
            when {
                message is AgentMessageUi &&
                    message.isStreaming &&
                    isAssistantMessageForRound(message.id, runId, round) &&
                    message.id != activeAssistantId ->
                    message.copy(
                        content = message.content.trimEnd(),
                        isStreaming = false,
                        renderMarkdown = true,
                    )

                message is ThinkingMessageUi &&
                    message.isStreaming &&
                    isThinkingMessageForRound(message.id, runId, round) &&
                    message.id != activeThinkingId ->
                    message.finished()

                else -> message
            }
        }
    }

    private fun List<AgentChatMessageUi>.insertBeforeFirstAssistant(
        runId: String,
        round: Int,
        message: AgentChatMessageUi,
    ): List<AgentChatMessageUi> {
        val assistantIndex = indexOfFirst {
            it is AgentMessageUi && isAssistantMessageForRound(it.id, runId, round)
        }
        return if (assistantIndex >= 0) {
            toMutableList().apply { add(assistantIndex, message) }
        } else {
            this + message
        }
    }

    private fun assistantMessageId(runId: String, round: Int, index: Int): String =
        "${assistantMessagePrefix(runId)}$round-$index"

    private fun thinkingMessageId(runId: String, round: Int, index: Int): String =
        "$runId-thinking-$round-$index"

    private fun thinkingFallbackMessageId(runId: String, round: Int): String =
        "$runId-thinking-$round-fallback"

    private fun isAssistantMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val legacyId = "${assistantMessagePrefix(runId)}$round"
        return messageId == legacyId || messageId.startsWith("$legacyId-")
    }

    private fun isThinkingMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val prefix = "$runId-thinking-$round"
        return messageId == prefix || messageId.startsWith("$prefix-")
    }

    private fun toolActivityMessageId(runId: String, round: Int, toolCallId: String): String =
        "$runId-tool-$round-${toolCallId.ifBlank { "unknown" }}"

    private fun subagentMessagePrefix(runId: String): String = "$runId-subagent-"

    private fun subagentMessageId(runId: String, round: Int, toolCallId: String, subIndex: Int): String =
        "$runId-subagent-$round-${toolCallId.ifBlank { "unknown" }}-$subIndex"

    companion object {
        /**
         * 手动压缩 run 的结果与压缩事件共用同一条时间线标记：事件标记已携带压缩前后的
         * token 信息，成功时原样保留；仍在进行中、失败或事件缺失时才改写或补写。
         */
        fun mergeCompactionResultNotice(
            runId: String,
            messages: List<AgentChatMessageUi>,
            ok: Boolean,
            detail: String,
        ): List<AgentChatMessageUi> {
            val index = messages.indexOfLast {
                it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ContextCompaction &&
                    it.id.startsWith("assistant-$runId-compaction-")
            }
            if (index < 0) {
                return messages + SystemNoticeMessageUi(
                    id = "assistant-$runId-compaction-result",
                    code = SystemNoticeCode.ContextCompaction,
                    detail = detail,
                )
            }
            val notice = messages[index] as SystemNoticeMessageUi
            if (ok && !notice.running) return messages
            return messages.mapIndexed { i, message ->
                if (i == index && message is SystemNoticeMessageUi) {
                    message.copy(detail = detail, running = false)
                } else {
                    message
                }
            }
        }

        /** 终态只能补全最后一次重试之后的回答，不能覆盖已标记失败的半截输出。 */
        fun resultTargetIndex(
            runId: String,
            messages: List<AgentChatMessageUi>,
            includeNotices: Boolean = false,
        ): Int {
            val retryIndex = lastRetryIndex(runId, messages)
            return messages.indices.lastOrNull { index ->
                val message = messages[index]
                index > retryIndex &&
                    (message is AgentMessageUi || includeNotices && message is SystemNoticeMessageUi) &&
                    isAssistantMessageForRun(message.id, runId)
            } ?: -1
        }

        fun resultFallbackId(runId: String, messages: List<AgentChatMessageUi>): String {
            val retry = messages.getOrNull(lastRetryIndex(runId, messages))
            if (retry == null) return "assistant-$runId-1"
            val round = retry.id.substringAfterLast('-').toIntOrNull()?.plus(1) ?: 1
            return "assistant-$runId-$round-result"
        }

        private fun lastRetryIndex(runId: String, messages: List<AgentChatMessageUi>): Int =
            messages.indexOfLast {
                it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ModelRetry &&
                    it.id.startsWith("assistant-$runId-retry-")
            }

        private fun assistantMessagePrefix(runId: String): String =
            "assistant-$runId-"

        private fun isAssistantMessageForRun(messageId: String, runId: String): Boolean =
            messageId == "assistant-$runId" ||
                messageId.startsWith(assistantMessagePrefix(runId))
    }

}

private const val MAX_TOOL_RESULT_PREVIEW_CHARS = 48
/** 子代理独立行的步骤累积上限：保留尾部（最终结果），头部超长截断。 */
private const val MAX_SUBAGENT_TRACE_CHARS = 1500
/** 子代理独立行的 toolName：复用工具卡片渲染，标题取子代理 label。 */
internal const val SUBAGENT_TOOL_NAME = "subagent"
/** 单个工具详情（文件/终端）的字符预算。 */
private const val MAX_TOOL_DETAIL_CHARS = 1600
/** 子代理最终结果的字符预算。 */
private const val MAX_SUBAGENT_RESULT_DETAIL_CHARS = 2000
/** 子代理最终结果里最多列出的改动文件数。 */
private const val MAX_SUBAGENT_DETAIL_FILES = 8
