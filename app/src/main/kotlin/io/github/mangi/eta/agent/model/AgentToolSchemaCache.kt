package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray

/**
 * 工具 schema 缓存：能力不变时复用同一份 JSONArray 实例。
 *
 * 两处收益都不改变请求内容：
 * 1. 不再每轮重建全部工具的 schema；
 * 2. 窗口预算按数组实例缓存"工具表 token"，同一实例才能命中——否则每轮都是新对象，
 *    缓存形同虚设，每轮都要把整份 schema 重新序列化一遍来估算。
 *
 * 能力组合是有限枚举，但为了不让异常抖动把缓存撑大，这里给上限，超出即整体丢弃
 * （丢弃只影响命中率，不影响正确性；下一次调用会重新构建）。
 */
internal class AgentToolSchemaCache(
    private val limit: Int = DEFAULT_LIMIT,
    private val build: (AgentToolCapabilities) -> JSONArray,
) {
    private val entries = LinkedHashMap<AgentToolCapabilities, JSONArray>()

    var hits = 0
        private set

    var builds = 0
        private set

    val distinctCapabilities: Int get() = entries.size

    /** 缓存尺寸只用于观测；命中率低说明能力在逐轮抖动，需要查采集来源。 */
    val hitRate: Double
        get() = if (hits + builds == 0) 0.0 else hits.toDouble() / (hits + builds)

    fun tools(capabilities: AgentToolCapabilities): JSONArray {
        entries[capabilities]?.let { cached ->
            hits++
            return cached
        }
        builds++
        if (entries.size >= limit) entries.clear()
        return build(capabilities).also { built -> entries[capabilities] = built }
    }

    private companion object {
        const val DEFAULT_LIMIT = 8
    }
}
