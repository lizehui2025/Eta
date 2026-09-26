package io.github.mangi.eta.agent.model

import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 服务端 prompt cache 的显式协作层。
 *
 * 两个协议族各有自己的“缓存指令”字段：Anthropic 用 `cache_control` 断点标注可复用前缀，
 * OpenAI 兼容端点用 `prompt_cache_key` 提示会话路由（负载均衡下让同一会话稳定命中同一实例）。
 * 二者都只是优化：部分兼容端点不认识这些字段并返回 400。这里集中做两件事：
 *
 * 1. 由会话 id 生成稳定的不透明路由键（内部 id 经 UUID v3 匿名化，不直接外发）；
 * 2. 记住“哪个地址拒绝过哪个字段”：拒绝一次后该地址后续请求直接不再携带该字段，
 *    由调用方（各 provider）在收到“未知字段”类 400/422 时回调标记。标记只活在进程内存里，
 *    重启后至多重试一次，不会持久化错误的结论。
 */
internal object ProviderPromptCache {
    const val CACHE_CONTROL_FIELD = "cache_control"
    const val PROMPT_CACHE_KEY_FIELD = "prompt_cache_key"
    const val REASONING_CONTENT_FIELD = "reasoning_content"

    private val anthropicCacheControlRejected = ConcurrentHashMap.newKeySet<String>()
    private val promptCacheKeyRejected = ConcurrentHashMap.newKeySet<String>()

    /** 同一会话跨 run 一致的稳定路由键；不泄露会话内部 id。 */
    fun promptCacheKey(sessionId: String): String =
        "eta-" + UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString()

    fun anthropicCacheControlAllowed(baseUrl: String): Boolean =
        hostKey(baseUrl) !in anthropicCacheControlRejected

    fun markAnthropicCacheControlRejected(baseUrl: String) {
        val host = hostKey(baseUrl)
        if (anthropicCacheControlRejected.add(host)) {
            // 日志失败不影响降级语义；纯 JVM 单测里 Android 日志可能未被 mock。
            runCatching {
                AndroidAgentLogger.info("Anthropic 端点拒绝 cache_control，已对该地址关闭缓存断点：$host")
            }
        }
    }

    fun openAiPromptCacheKeyAllowed(baseUrl: String): Boolean =
        hostKey(baseUrl) !in promptCacheKeyRejected

    fun markOpenAiPromptCacheKeyRejected(baseUrl: String) {
        val host = hostKey(baseUrl)
        if (promptCacheKeyRejected.add(host)) {
            // 日志失败不影响降级语义；纯 JVM 单测里 Android 日志可能未被 mock。
            runCatching {
                AndroidAgentLogger.info("端点拒绝 prompt_cache_key，已对该地址关闭缓存路由键：$host")
            }
        }
    }

    /**
     * 判断 HTTP 错误是否属于“不认识某个缓存字段”。要求：
     * 状态码 400/422、正文提到该字段名、且带常见“未知 / 不支持 / 多余字段”措辞。
     * 三者同时满足才允许摘字段重试，避免把额度、超限、参数类型等无关 400 误判成可降级。
     */
    fun isUnsupportedFieldRejection(code: Int, body: String, field: String): Boolean {
        if (code != 400 && code != 422) return false
        if (!body.contains(field, ignoreCase = true)) return false
        return UNSUPPORTED_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    private val UNSUPPORTED_MARKERS = listOf(
        "unknown",
        "unsupported",
        "not support",
        "unrecognized",
        "unexpected",
        "invalid",
        "not permitted",
        "not allowed",
        "extra",
        "additional",
    )

    /**
     * 判断 HTTP 错误是否属于“服务端不接受历史消息里的 reasoning_content”。
     *
     * Chat Completions 会把上一轮 assistant 的推理链原样回传（`AgentConversationCodec.toJsonObject`
     * 写入、`OpenAiRequestMessages` 不剥离），而部分服务商（如 DeepSeek 官方）明确拒绝该字段并直接 400。
     * 判定门槛与缓存字段一致：400/422 + 正文点名该字段 + “未知 / 不支持 / 多余字段”类措辞；
     * 另外接受少量明确指向该字段不被接受的写法，避免措辞差异导致自愈失效。
     */
    fun isReasoningContentRejection(code: Int, body: String): Boolean {
        if (code != 400 && code != 422) return false
        if (!body.contains(REASONING_CONTENT_FIELD, ignoreCase = true)) return false
        if (isUnsupportedFieldRejection(code, body, REASONING_CONTENT_FIELD)) return true
        return REASONING_REJECTION_MARKERS.any { body.contains(it, ignoreCase = true) }
    }

    private val REASONING_REJECTION_MARKERS = listOf(
        "only allowed",
        "must not",
        "should not",
        "cannot",
        "can not",
        "不支持",
        "不合法",
        "未知字段",
        "多余",
    )

    /**
     * 调用方在“剥离 reasoning 后重试一次”决策点调用，写一条限流 warn 日志。
     *
     * 这里刻意不记忆拒绝结论：与两个缓存字段不同，reasoning 属于模型行为语义，长期静默剥离会
     * 悄悄改变模型可用的上下文，因此只影响本次重试。一次性约束由调用方持有（见 provider 侧标志），
     * 本函数自身不做任何状态变更，重复调用只会被限流吞掉日志。
     */
    fun markReasoningContentRejected(baseUrl: String) {
        val host = hostKey(baseUrl)
        // 日志失败不影响重试语义；纯 JVM 单测里 Android 日志可能未被 mock。
        runCatching {
            AndroidAgentLogger.warnThrottled("reasoning_content_rejected:$host") {
                "服务端拒绝 reasoning_content（$host），已因服务端不支持而剥离历史 reasoning 重试一次"
            }
        }
    }

    /**
     * 拒绝记忆按“主机:端口”隔离：同主机不同端口的服务往往是不同部署；
     * 同时让测试里的随机 mock 端口互不污染。
     */
    private fun hostKey(baseUrl: String): String =
        baseUrl.toHttpUrlOrNull()?.let { url -> "${url.host.lowercase()}:${url.port}" }
            ?: baseUrl.lowercase()
}
