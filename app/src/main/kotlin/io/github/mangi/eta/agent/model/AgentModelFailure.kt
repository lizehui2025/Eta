package io.github.mangi.eta.agent.model

import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    val recoveryAllowed: Boolean = true,
) : IllegalStateException(message, cause) {
    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
        )

        fun http(status: Int, body: String): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。",
            )
            val permanent = isPermanent(error, body)
            logServerRejection(status, body, error)
            val base = if (permanent) {
                "模型接口额度或计费受限（HTTP $status），请检查服务商账户。"
            } else {
                when (status) {
                    400 -> "模型请求参数无效（HTTP 400），请检查模型配置。"
                    401 -> "模型接口认证失败（HTTP 401），请检查 API Key。"
                    403 -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。"
                    else -> "模型接口返回 HTTP $status"
                }
            }
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                // 参数类 4xx 的原有文案保持不变，只在末尾附加一段服务端原因短摘要，
                // 让用户知道是哪个字段被拒；响应体为空时等于没有附加。
                message = if (status == 400 || status == 422) base + serverDetailSuffix(body) else base,
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, "模型上下文超过容量限制。",
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        /**
         * The endpoint returned data, but none of it is shaped for the current protocol: the baseUrl
         * most likely points at a different API.
         *
         * Distinct from [incompleteStream]: truncation is transient (a retry may succeed), whereas a
         * protocol mismatch would fail again against the same endpoint. This one is therefore
         * non-retryable, and OpenAiMixedEndpointProvider retries the other endpoint once instead.
         */
        fun endpointProtocolMismatch(message: String) =
            AgentModelFailure(CODE_ENDPOINT_PROTOCOL_MISMATCH, false, message)

        const val CODE_ENDPOINT_PROTOCOL_MISMATCH = "ENDPOINT_PROTOCOL_MISMATCH"

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> null
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds")
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }

        /**
         * 服务端拒绝请求时的完整失败现场只进日志：状态码 + 响应体摘要 + 字段名线索。
         *
         * 摘要去换行、脱敏、限长，绝不写完整请求体、请求头或 apiKey；同一状态码按窗口限流，
         * 避免退避重试把日志刷满。
         */
        private fun logServerRejection(status: Int, body: String, error: JSONObject?) {
            // 日志失败不影响分类语义；纯 JVM 单测里 Android 日志可能未被 mock。
            runCatching {
                AndroidAgentLogger.warnThrottled("model-http-$status") {
                    val hints = fieldHints(body, error)
                    "模型接口拒绝请求 HTTP $status，字段线索：${hints.ifEmpty { "无" }}，" +
                        "响应体摘要：${summarize(body, LOG_BODY_LIMIT)}"
                }
            }
        }

        /** 400/422 在用户可见文案末尾附加服务端原因摘要；响应体为空时保持原有文案。 */
        private fun serverDetailSuffix(body: String): String =
            summarize(body, MESSAGE_DETAIL_LIMIT).let { detail ->
                if (detail.isEmpty()) "" else " 服务端原因：$detail"
            }

        /**
         * 供重试等路径复用的失败摘要：去换行、脱敏、限长。
         *
         * 只接收已经分类好的错误文案，调用方不得传入请求体或响应头。
         */
        fun failureSummary(message: String?, limit: Int = RETRY_SUMMARY_LIMIT): String =
            summarize(message.orEmpty(), limit)

        /**
         * 只回传“可能是哪个字段被拒”的线索，不从响应体里搬运任意文本。
         *
         * 线索来自两部分：`error.param`（部分服务商直接给出出错参数名）与已知请求字段名在
         * 响应体中的命中。命中只证明该字段名出现在错误文本里，不代表一定是它被拒。
         */
        private fun fieldHints(body: String, error: JSONObject?): String {
            val hints = linkedSetOf<String>()
            error?.optString("param").orEmpty().trim()
                .takeIf { it.isNotEmpty() }
                ?.let { hints += it.take(MAX_FIELD_HINT_CHARS) }
            val lower = body.lowercase()
            TRACKED_REQUEST_FIELDS.forEach { field ->
                if (hints.size < MAX_FIELD_HINTS && lower.contains(field)) hints += field
            }
            return hints.joinToString(",")
        }

        /**
         * 去换行、压缩空白、脱敏后截断，结果长度不超过 [limit]。
         *
         * 服务端有时会把收到的凭据回显进错误体（如 “Incorrect API key provided: sk-...”），
         * 因此摘要先过一遍脱敏，再进入日志或用户文案。
         */
        private fun summarize(raw: String, limit: Int): String {
            if (limit <= 0) return ""
            val normalized = raw.replace(WHITESPACE, " ").trim()
            if (normalized.isEmpty()) return ""
            val redacted = SECRET_PATTERNS.fold(normalized) { current, pattern ->
                pattern.replace(current, SECRET_PLACEHOLDER)
            }
            return if (redacted.length <= limit) redacted else redacted.take(limit - 1).trimEnd() + "…"
        }

        private val WHITESPACE = Regex("\\s+")

        private const val SECRET_PLACEHOLDER = "***"

        /** 常见凭据直传形态：Bearer 头、sk- 前缀、key/token 赋值、超长不透明串。 */
        private val SECRET_PATTERNS = listOf(
            Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{4,}"),
            Regex("(?i)sk-[A-Za-z0-9._\\-]{4,}"),
            Regex("(?i)\\b(api[_-]?key|apikey|authorization|token|secret|password)\\b[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9._\\-]{4,}"),
            Regex("[A-Za-z0-9_\\-]{40,}"),
        )

        /**
         * Eta 自己会写进请求体的字段名。服务端的“未知字段 / 不支持”类报错通常点名其中之一，
         * 命中即作为定位线索写进日志。
         */
        private val TRACKED_REQUEST_FIELDS = listOf(
            "reasoning_content",
            "reasoning_effort",
            "prompt_cache_key",
            "cache_control",
            "tool_choice",
            "tools",
            "stream_options",
            "include_usage",
            "response_format",
            "max_completion_tokens",
            "max_tokens",
            "parallel_tool_calls",
            "temperature",
            "top_p",
            "thinking",
        )

        /** 日志里的响应体摘要上限。 */
        private const val LOG_BODY_LIMIT = 512

        /** 用户可见文案里的服务端原因摘要上限。 */
        private const val MESSAGE_DETAIL_LIMIT = 160

        /** 重试日志里的上次失败原因摘要上限。 */
        private const val RETRY_SUMMARY_LIMIT = 200

        private const val MAX_FIELD_HINTS = 6
        private const val MAX_FIELD_HINT_CHARS = 64
    }
}
