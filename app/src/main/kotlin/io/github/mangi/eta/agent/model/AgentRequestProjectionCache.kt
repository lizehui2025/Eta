package io.github.mangi.eta.agent.model

import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider 请求投影缓存。
 *
 * Agent 每轮请求都会把完整 messages 投影到协议格式；已经发出的历史对象不会改写，
 * 因而可以按对象身份复用上一轮的投影结果。缓存只在单次 run 内有效，有上限，溢出时
 * 整体丢弃，不改变请求内容。
 */
internal class AgentRequestProjectionCache(
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val chatMessages = IdentityHashMap<JSONObject, JSONObject>()
    private val chatMessagesWithoutReasoning = IdentityHashMap<JSONObject, JSONObject>()
    private val responsesInputs = IdentityHashMap<JSONObject, JSONArray>()
    private val anthropicMessages = IdentityHashMap<JSONObject, JSONObject>()
    private val anthropicMessagesWithoutCacheControl = IdentityHashMap<JSONObject, JSONObject>()

    fun chatMessage(
        source: JSONObject,
        stripReasoning: Boolean,
        project: (JSONObject) -> JSONObject,
    ): JSONObject {
        val target = if (stripReasoning) chatMessagesWithoutReasoning else chatMessages
        target[source]?.let { return it }
        return project(source).also { projected ->
            evictOverflow(target)
            target[source] = projected
        }
    }

    fun responsesInput(
        source: JSONObject,
        project: (JSONObject) -> JSONArray,
    ): JSONArray {
        responsesInputs[source]?.let { return it }
        return project(source).also { projected ->
            evictOverflow(responsesInputs)
            responsesInputs[source] = projected
        }
    }

    fun anthropicMessage(
        source: JSONObject,
        cacheControl: Boolean,
        project: (JSONObject) -> JSONObject,
    ): JSONObject {
        val target = if (cacheControl) anthropicMessages else anthropicMessagesWithoutCacheControl
        target[source]?.let { return it }
        return project(source).also { projected ->
            evictOverflow(target)
            target[source] = projected
        }
    }

    /**
     * 溢出淘汰降到 3/4 水位（与 AgentContextBudget 同策略）：整表清空会让越界后的
     * 下一轮把整份历史重新投影，长 run 上呈现周期性全量重算。
     */
    private fun <V> evictOverflow(cache: IdentityHashMap<JSONObject, V>) {
        if (cache.size < limit) return
        val targetSize = limit * 3 / 4
        val entries = cache.keys.iterator()
        while (cache.size > targetSize && entries.hasNext()) {
            entries.next()
            entries.remove()
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 4_096
    }
}
