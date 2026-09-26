package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 仅为不认识完整载荷字段的旧客户端提供内联预览；禁止用于存储或新模型请求。 */
internal object AgentLegacyConversationProjection {
    const val DIRECT_CHARS = 96_000
    const val DRAIN_CHARS = 16_000
    private val json = Json { encodeDefaults = false }

    /** 紧凑数组编码的外层括号字符数（`[` 与 `]`）；相邻元素之间另有 1 个逗号，见 [predictShrink]。 */
    private const val ARRAY_BRACKETS_CHARS = 2

    /** 探针长度：`{"role":""}` 的字符数，见 [lengthModelIsExact]。 */
    private const val PROBE_ELEMENT_CHARS = 11

    private const val COMPACTION_NOTICE =
        "[Eta 上下文提示：此前部分 assistant/tool 记录因跨进程或持久化容量上限已压缩，请勿假定缺失步骤未执行。]"

    /**
     * 长度模型是否与 `json` 的真实编码一致：`[元素]` 恰好等于元素再加 [ARRAY_BRACKETS_CHARS] 个括号字符。
     * 探针只用 `role`：其余字段都取默认值，`encodeDefaults = false` 会省略它们，
     * 因此紧凑编码必然恰好是 `{"role":""}`（11 字符 = [PROBE_ELEMENT_CHARS]）。
     * 若 DTO 结构变化导致探测失败，[encode] 会退回逐次重编码的旧路径，只损失速度、不改变结果。
     */
    private val lengthModelIsExact: Boolean by lazy {
        json.encodeToString(listOf(AgentModelClient.ConversationMessage(role = ""))).length ==
            PROBE_ELEMENT_CHARS + ARRAY_BRACKETS_CHARS
    }

    fun encode(
        messages: List<AgentModelClient.ConversationMessage>,
        maxChars: Int,
    ): String {
        val bounded = sanitizedMessages(messages)
        var encoded = json.encodeToString(bounded)
        if (encoded.length <= maxChars) return encoded

        val notice = AgentModelClient.ConversationMessage(
            role = "system",
            content = COMPACTION_NOTICE,
        )
        // 旧实现每丢掉一条消息就把剩余部分整段重新编码，长历史下是 O(候选数 × 剩余长度) 的二次热点。
        // 这里先用长度模型定位保留窗口，再用一次真实编码确认；候选序列与返回条件与旧实现逐条对齐。
        if (lengthModelIsExact) {
            val shrink = predictShrink(bounded, notice, maxChars)
            if (shrink.fits) {
                val candidate = json.encodeToString(
                    listOf(notice) + bounded.subList(shrink.start, bounded.size),
                )
                if (candidate.length <= maxChars) return candidate
            } else {
                // 长度模型（恒不高于真实长度）判定连 `[notice]` 都超限：旧实现同样会在
                // 循环结束后进入末条压缩，落点是同一个 start。
                return encodeCompactedTail(bounded, notice, maxChars, shrink.start)
            }
        }
        // 长度模型不可用，或预测装得下但真实编码超限时，退回旧实现的逐次收缩，保证返回值逐字符一致。
        return encodeByShrinking(bounded, notice, maxChars)
    }

    /**
     * 入参规范化：旧实现用 `decodeTranscript(encodeTranscriptForStorage(messages))` 做脱敏
     * （去掉结构化 content 里的图片、按 JSON 规范化 contentJson）并顺带拷贝一份列表。
     *
     * [AgentConversationCodec] 的 `sanitizeMessage` 只重写 `contentJson`（空串仍写成空串，
     * 其余字段原样复制），而 `ConversationMessage` 是字段全有默认值的普通 `@Serializable` 数据类，
     * 编码再解码是恒等变换；因此当所有消息的 `contentJson` 都为空时，这个往返等价于直接拷贝列表，
     * 可以省掉两趟全量序列化。只要有一条非空 `contentJson`，就仍然走原来的往返，脱敏结果不变。
     */
    private fun sanitizedMessages(
        messages: List<AgentModelClient.ConversationMessage>,
    ): MutableList<AgentModelClient.ConversationMessage> =
        // 注意：此处必须是 isNotEmpty（而非 isNotBlank）。sanitizeContentJson 会把 " "/"\n" 这类
        // 空白串规范成 ""，若快速路径放行它们，编码结果就会保留原空白串，与旧实现不一致。
        if (messages.any { it.contentJson.isNotEmpty() }) {
            AgentConversationCodec.decodeTranscript(
                AgentConversationCodec.encodeTranscriptForStorage(messages),
            ).toMutableList()
        } else {
            messages.toMutableList()
        }

    /** [predictShrink] 的落点：[start] 为剩余切片下标，[fits] 表示该切片是否预测装得下。 */
    private data class ShrinkOutcome(val start: Int, val fits: Boolean)

    /**
     * 线性预测旧循环的落点，避免逐个候选整段重编码。
     *
     * 旧循环每轮：丢掉当前首条，再丢掉紧随其后的所有 `role == "tool"` 消息，然后编码
     * `[notice] + 剩余消息`，长度 ≤ maxChars 立即返回，否则继续；循环条件是“当前剩余 > 1 条”。
     * 因此候选切片只能是后缀，起点单调递增，整轮下来每条消息最多被扫描一次。
     *
     * 长度模型：紧凑编码下 `[notice] + messages[i..]` 的长度为
     * `len([notice]) + Σ_{j=i..}(1 + len(messages[j]))`——外层括号 [ARRAY_BRACKETS_CHARS] 个字符、
     * 相邻元素之间 1 个逗号，且单个元素单独编码的长度与它在数组里的长度一致
     * （由 [lengthModelIsExact] 探测保证）。该值随起点 i 减小而严格增大，
     * 所以“候选序列里第一个装得下”等价于“起点 ≥ 满足上限的最小起点”。
     *
     * 模型只会低估真实长度：真实编码多出的任何字符（额外的分隔符、缩进、换行）都只会让它更长。
     * 因此预测“装不下”可以直接跳过，预测“装得下”必须由 [encode] 用真实编码复核后才可返回。
     *
     * 边界规则（与旧实现逐条对齐）：
     * - 首个候选是“丢掉下标 0 的条目 + 连带丢掉其后的 tool 消息”，所以剩余切片不会以 tool 开头；
     * - 循环只在“上一轮剩余 > 1 条”时才产生新候选，剩余 1 条时不会再尝试丢掉它（直接走末条压缩）；
     * - 反向累加从尾部开始，只序列化“可能保留的那段 + 越界的一条”，不必编码整个历史。
     *
     * @return [ShrinkOutcome.fits] 为 true 时 [ShrinkOutcome.start] 是第一个预测装得下的候选起点；
     * 为 false 时是循环自然结束时的起点（可能已无剩余消息），调用方应进入末条压缩分支。
     */
    private fun predictShrink(
        messages: List<AgentModelClient.ConversationMessage>,
        notice: AgentModelClient.ConversationMessage,
        maxChars: Int,
    ): ShrinkOutcome {
        val size = messages.size
        var accumulated = json.encodeToString(listOf(notice)).length
        // 满足上限的最小起点；Int.MAX_VALUE 表示连 `[notice]` 都超限，没有任何候选装得下。
        var minFittingStart = if (accumulated <= maxChars) size else Int.MAX_VALUE
        var index = size - 1
        while (index >= 0 && minFittingStart != Int.MAX_VALUE) {
            accumulated += 1 + encodedElementLength(messages[index])
            if (accumulated > maxChars) break
            minFittingStart = index
            index--
        }
        var start = 0
        while (size - start > 1) {
            start = skipShrunkHead(messages, start + 1)
            if (start >= minFittingStart) return ShrinkOutcome(start, fits = true)
        }
        return ShrinkOutcome(start, fits = false)
    }

    /** 与逐次收缩一致：丢掉一条后，继续丢掉紧随其后的所有 `role == "tool"` 消息。 */
    private fun skipShrunkHead(
        messages: List<AgentModelClient.ConversationMessage>,
        from: Int,
    ): Int {
        var index = from
        while (index < messages.size && messages[index].role == "tool") index++
        return index
    }

    /** 单个元素的紧凑 JSON 长度；`[` + `]` 固定占 [ARRAY_BRACKETS_CHARS] 个字符。 */
    private fun encodedElementLength(message: AgentModelClient.ConversationMessage): Int =
        json.encodeToString(listOf(message)).length - ARRAY_BRACKETS_CHARS

    /**
     * 旧实现的逐次收缩：每轮把候选整段重新编码，直到某次装得下或剩余 ≤ 1 条。
     * 仅在长度模型不可用、或预测与真实编码不一致时兜底使用，落点与返回值与旧实现完全一致。
     */
    private fun encodeByShrinking(
        messages: List<AgentModelClient.ConversationMessage>,
        notice: AgentModelClient.ConversationMessage,
        maxChars: Int,
    ): String {
        val bounded = messages.toMutableList()
        while (bounded.size > 1) {
            bounded.removeAt(0)
            while (bounded.firstOrNull()?.role == "tool") bounded.removeAt(0)
            val encoded = json.encodeToString(listOf(notice) + bounded)
            if (encoded.length <= maxChars) return encoded
        }
        return encodeCompactedTail(messages, notice, maxChars, messages.size - bounded.size)
    }

    /**
     * 末条压缩分支：收缩后只剩最后一条（或已无剩余）时裁剪末条内容再编码。
     * [remainingStart] 为收缩后剩余切片的起点；`>= messages.size` 表示剩余为空。
     */
    private fun encodeCompactedTail(
        messages: List<AgentModelClient.ConversationMessage>,
        notice: AgentModelClient.ConversationMessage,
        maxChars: Int,
        remainingStart: Int,
    ): String {
        if (remainingStart >= messages.size) return "[]"
        val last = messages[messages.size - 1]
        val compacted = last.copy(
            content = last.content.take(maxChars / 4),
            contentJson = "",
            reasoningContent = last.reasoningContent.take(maxChars / 4),
            toolCallsJson = "",
        )
        return json.encodeToString(listOf(notice, compacted))
            .takeIf { it.length <= maxChars }
            ?: json.encodeToString(listOf(notice)).takeIf { it.length <= maxChars }
            ?: "[]"
    }

}
