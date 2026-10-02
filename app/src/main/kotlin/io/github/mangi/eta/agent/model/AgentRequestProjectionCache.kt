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
            if (target.size >= limit) target.clear()
            target[source] = projected
        }
    }

    fun responsesInput(
        source: JSONObject,
        project: (JSONObject) -> JSONArray,
    ): JSONArray {
        responsesInputs[source]?.let { return it }
        return project(source).also { projected ->
            if (responsesInputs.size >= limit) responsesInputs.clear()
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
            if (target.size >= limit) target.clear()
            target[source] = projected
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 4_096
    }
}
