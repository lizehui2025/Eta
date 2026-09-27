package io.github.mangi.eta.agent.model

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Protocol adaptation layer for OpenAI-compatible endpoints.
 *
 * Background: `chat_completions` and `responses` are two distinct wire paths under the same
 * `openai_compatible` provider, and third-party gateways often implement only one of them (or
 * contradict their own documentation). The path used to be decided by configuration, so choosing
 * wrong failed hard: a non-retryable 404 claiming the endpoint or model does not exist, or a
 * stream error after three wasted retries carrying no hint about the real cause.
 *
 * This layer does two things:
 *
 * 1. Remembers which protocol actually works for an address. It is written **only after a request
 *    succeeds**, so every recorded fact is backed by evidence and a speculative 404 can never
 *    poison it. The memory lives in process memory only, the same trade-off as
 *    [ProviderPromptCache]: nothing is persisted, so a repaired server is honoured after restart.
 * 2. Decides whether a failure means "this address is not this endpoint", which is the only kind
 *    of failure allowed to trigger a retry against the other protocol.
 *
 * Deliberately not done: guessing "endpoint unsupported" from a 400/422 response body. Failures
 * carry only a length-bounded reason summary (see AgentModelFailure) and the raw body never
 * reaches this layer; fuzzy matching would risk routing a request that could have succeeded to
 * the wrong endpoint, while 404/405/501 plus a mismatched stream shape are structured signals
 * that already cover the real cases.
 */
internal object ProviderEndpointFallback {

    /**
     * hostKey -> endpoint mode observed to work.
     *
     * Written only after a request has actually succeeded, and removed again once it agrees with
     * the declared preference, so the map keeps address that genuinely diverge.
     */
    private val workingModes = ConcurrentHashMap<String, String>()

    /**
     * Cap on tracked addresses: guards only against pathological fan-out at self-hosted gateways;
     * dropping the whole map on overflow is harmless because it is a pure cache.
     */
    private const val MAX_TRACKED_HOSTS = 64

    /** Failures that justify retrying the other endpoint: it does not exist, or answers in the other shape. */
    private val switchableCodes = setOf(
        "HTTP_404",
        "HTTP_405",
        "HTTP_501",
        AgentModelFailure.CODE_ENDPOINT_PROTOCOL_MISMATCH,
    )

    /**
     * Preferred protocol when no observed result exists yet: user intent outranks the stored value.
     *
     * Provider-hosted web search is served only over Responses, so turning that switch on means
     * "prefer Responses". With the protocol no longer selectable, it is the only protocol
     * preference the user can still express.
     */
    fun declaredPreference(configuredMode: String?, preferResponses: Boolean): String =
        if (preferResponses) OpenAiEndpointMode.RESPONSES else OpenAiEndpointMode.normalize(configuredMode)

    /**
     * Protocol to try for this request: observed result > user intent > stored value.
     *
     * The observed result is a fact and outranks everything, which stops later requests from
     * probing an endpoint already known to fail. The stored value is only a legacy first-attempt
     * hint and is no longer a user choice.
     */
    fun preferredMode(
        baseUrl: String,
        configuredMode: String?,
        preferResponses: Boolean = false,
    ): String {
        workingModes[hostKey(baseUrl)]?.let { remembered ->
            return OpenAiEndpointMode.normalize(remembered)
        }
        return declaredPreference(configuredMode, preferResponses)
    }

    /**
     * Records the protocol that actually succeeded, storing nothing when it matches the preference
     * that would have been tried anyway.
     *
     * [declaredPreference] must be the **preference used for this attempt** (intent already
     * applied), not the stored value. With "web search on + Chat Completions-only endpoint" the
     * successful mode equals the stored value, so comparing against the latter would discard the
     * finding and make every later request probe the Responses endpoint first.
     *
     * Called only after success — this is what guarantees the layer never records a guess.
     */
    fun rememberWorkingMode(baseUrl: String, mode: String, declaredPreference: String) {
        val host = hostKey(baseUrl)
        val resolved = OpenAiEndpointMode.normalize(mode)
        if (resolved == OpenAiEndpointMode.normalize(declaredPreference)) {
            workingModes.remove(host)
            return
        }
        if (workingModes.size > MAX_TRACKED_HOSTS) workingModes.clear()
        workingModes[host] = resolved
    }

    /**
     * The user enabled provider-hosted web search but only Chat Completions is available, so the
     * capability cannot be served.
     *
     * This must stay visible instead of degrading silently: the user turned the switch on
     * deliberately, and a silent failure would look like search is working.
     */
    fun warnWebSearchUnavailable(baseUrl: String, actualMode: String) {
        val host = hostKey(baseUrl)
        runCatching {
            AndroidAgentLogger.warnThrottled("web_search_unavailable:$host") {
                "$host 实际使用 ${describe(actualMode)}，无法提供 Provider 托管网页搜索；" +
                    "该能力只有 Responses API 支持，如需使用请改用支持它的端点"
            }
        }
    }

    /**
     * Whether this failure means "this address is not this endpoint", rather than a network, quota
     * or argument problem unrelated to the endpoint kind.
     */
    fun isSwitchable(failure: Throwable): Boolean =
        (failure as? AgentModelFailure)?.code in switchableCodes

    /** Logged before switching: the event stream has no type for this, so the log is the only place it is visible. */
    fun logSwitch(baseUrl: String, from: String, to: String, failure: Throwable) {
        val host = hostKey(baseUrl)
        val code = (failure as? AgentModelFailure)?.code.orEmpty()
        // Logging must never affect the switch; Android logging may be unmocked in plain JVM tests.
        runCatching {
            AndroidAgentLogger.warnThrottled("endpoint_switch:$host:$from->$to") {
                "$host 不支持 ${describe(from)}（$code），已改用 ${describe(to)} 重试一次"
            }
        }
    }

    /**
     * Logged after a successful switch: which protocol the address actually speaks, and what this
     * attempt preferred.
     *
     * No longer advises "fix it in settings" — the protocol is not user-selectable. The finding
     * lives only for this process, so the next launch probes the preferred protocol once more;
     * that is a fixed per-process cost and needs no user action.
     */
    fun logSwitched(baseUrl: String, declaredPreference: String?, actualMode: String) {
        val host = hostKey(baseUrl)
        runCatching {
            AndroidAgentLogger.info(
                "$host 实际使用 ${describe(actualMode)}（本次首选为 ${describe(declaredPreference)}）；" +
                    "已在本次运行中记住，后续请求直接使用它",
            )
        }
    }

    /**
     * Combined failure raised when neither protocol works.
     *
     * More useful than surfacing either endpoint's own error: it states that both protocols are
     * unavailable for this address, which lets the user distinguish a wrong baseUrl from a service
     * that actually speaks Anthropic, or one that does not support this call style at all.
     */
    fun bothEndpointsFailed(
        baseUrl: String,
        firstMode: String,
        firstFailure: Throwable,
        secondFailure: Throwable,
    ): AgentModelFailure {
        val host = hostKey(baseUrl)
        val secondMode = OpenAiEndpointMode.alternative(firstMode)
        val firstCode = (firstFailure as? AgentModelFailure)?.code.orEmpty()
        val secondCode = (secondFailure as? AgentModelFailure)?.code.orEmpty()
        return AgentModelFailure(
            // Neither endpoint working is a configuration/capability problem: retrying the same
            // address cannot change the outcome.
            code = "ENDPOINT_UNAVAILABLE",
            retryable = false,
            message = "$host 的 ${describe(firstMode)} 与 ${describe(secondMode)} 都不可用" +
                "（$firstCode / $secondCode）。请检查接口地址是否正确（通常填到 /v1 为止）；" +
                "若该服务使用 Anthropic 协议，请在供应商类型中选择 Anthropic。",
            cause = firstFailure,
        )
    }

    /** Human-readable protocol name, shared by logs and the message above. */
    private fun describe(mode: String?): String =
        when (OpenAiEndpointMode.normalize(mode)) {
            OpenAiEndpointMode.RESPONSES -> "Responses API"
            else -> "Chat Completions API"
        }

    /**
     * Memory is keyed by "host:port": the same host on another port is usually a different
     * deployment, and it keeps randomly assigned mock ports in tests from polluting each other
     * (same rule as ProviderPromptCache).
     */
    private fun hostKey(baseUrl: String): String =
        baseUrl.toHttpUrlOrNull()?.let { url -> "${url.host.lowercase()}:${url.port}" }
            ?: baseUrl.lowercase()
}
