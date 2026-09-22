package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * run 内 transcript 的发布器：把「每次都整份重新转换」改为「只转换新增部分」。
 *
 * 旧实现每次发布都调用一次完整的 `AgentConversationCodec.transcript(transcript, 0, ids)`，
 * 而 transcript 在一轮里会增长多次，累计就是 O(轮数 × 历史长度)，长任务越跑越慢。
 *
 * 增量转换的正确性依赖两条不变量，这里都做显式守卫而不是靠约定：
 * 1. 已发布的消息不会被改写（transcript 只追加）。
 * 2. 一条消息的脱敏结果只取决于"发布它时已知的敏感集合"，之后新增的敏感 id 不会反过来
 *    作用于更早的消息——只有新变敏感的调用 id 已经落在已发布前缀里时才不成立，此时整份重建。
 * 另外前缀里若留有未闭合的工具调用（调用消息已发布、结果尚未到达），发布边界就落在工具批次
 * 内部，同样整份重建。
 *
 * 计数器用于观测增量命中率：正常流程下 `convertedMessages` 应约等于消息总数（每条只转换一次），
 * 而不是随轮数平方增长。
 */
internal class AgentTranscriptPublisher(
    private val sensitiveIds: () -> Set<String>,
) {
    private val published = mutableListOf<AgentModelClient.ConversationMessage>()
    private var consumedCount = 0
    private val openCallIds = linkedSetOf<String>()
    private val publishedCallIds = mutableSetOf<String>()
    private var knownSensitiveIds: Set<String> = emptySet()
    private var requiresFullRebuild = false

    /** 完成增量发布的次数。 */
    var incrementalPublishes = 0
        private set

    /** 退回整份重建的次数；正常流程应当为 0。 */
    var fullPublishes = 0
        private set

    /** 累计转换的消息条数，用来直接观测总工作量。 */
    var convertedMessages = 0
        private set

    /** 返回完整的脱敏 transcript；没有新增内容时直接返回上次结果，不做任何转换。 */
    fun publish(transcript: JSONArray): List<AgentModelClient.ConversationMessage> {
        val ids = sensitiveIds()
        val newlySensitive = if (ids == knownSensitiveIds) emptySet() else ids - knownSensitiveIds
        if (newlySensitive.any { it in publishedCallIds }) requiresFullRebuild = true
        // transcript 只追加；一旦长度回退（当前实现不会发生）也必须重建，否则会漏内容。
        if (consumedCount > transcript.length()) requiresFullRebuild = true
        if (consumedCount == transcript.length() && !requiresFullRebuild) {
            knownSensitiveIds = ids
            return published
        }
        if (requiresFullRebuild) {
            published.clear()
            consumedCount = 0
            openCallIds.clear()
            publishedCallIds.clear()
            requiresFullRebuild = false
            fullPublishes++
        } else {
            incrementalPublishes++
        }
        val from = consumedCount
        val converted = AgentConversationCodec.transcript(transcript, from, ids)
        published += converted
        convertedMessages += converted.size
        for (index in from until transcript.length()) {
            transcript.optJSONObject(index)?.let(::trackCalls)
        }
        consumedCount = transcript.length()
        // 前缀仍有未闭合调用时，下一次发布必须整份重建（见类注释）。
        requiresFullRebuild = openCallIds.isNotEmpty()
        knownSensitiveIds = ids
        return published
    }

    /** 记录该消息引入/闭合的工具调用 id，用于判断发布边界是否落在工具批次内部。 */
    private fun trackCalls(message: JSONObject) {
        if (message.optString("role") == "tool") {
            openCallIds.remove(message.optString("tool_call_id"))
            return
        }
        AgentConversationCodec.parseToolCalls(message).forEach { call ->
            openCallIds += call.id
            publishedCallIds += call.id
        }
    }
}
