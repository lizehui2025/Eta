package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Responses 协议 output items 的携带字段：稳定会话 codec 会随消息持久化该字段，
 * 用于跨 run 命中前缀缓存、续接推理链；超过 [MAX_PERSISTED_ITEMS_CHARS] 时按缺失处理，
 * 敏感工具消息的 items 仍在脱敏阶段剥离。
 */
internal object ResponsesEphemeralState {
    internal const val OUTPUT_ITEMS_KEY = "_eta_responses_output_items"

    /** 持久化 output items 的尺寸护栏：超限即丢弃，避免单条推理链把历史撑爆。 */
    const val MAX_PERSISTED_ITEMS_CHARS = 262_144

    fun outputItems(message: JSONObject): JSONArray? =
        message.optJSONArray(OUTPUT_ITEMS_KEY)

    fun attachOutputItems(message: JSONObject, items: JSONArray) {
        message.put(OUTPUT_ITEMS_KEY, JSONArray(items.toString()))
    }

    fun copyOutputItems(source: JSONObject, target: JSONObject) {
        outputItems(source)?.let { attachOutputItems(target, it) }
    }

    /** 可持久化的 output items JSON；字段缺失或超过尺寸护栏时返回空串。 */
    fun persistedItemsJson(message: JSONObject): String {
        val raw = outputItems(message)?.toString() ?: return ""
        return if (raw.length <= MAX_PERSISTED_ITEMS_CHARS) raw else ""
    }
}
