package io.github.mangi.eta.agent.model

import org.json.JSONObject

internal data class PromptCacheBreakpoints(
    var remaining: Int,
    var dropped: Int = 0,
) {
    fun take(ttlSeconds: Int? = null): JSONObject? {
        if (remaining <= 0) {
            dropped++
            return null
        }
        remaining--
        return JSONObject()
            .put("type", "ephemeral")
            .also { if (ttlSeconds != null && ttlSeconds >= 3_600) it.put("ttl", "1h") }
    }

    companion object {
        fun of(capacity: Int): PromptCacheBreakpoints = PromptCacheBreakpoints(capacity.coerceAtLeast(0))
    }
}
