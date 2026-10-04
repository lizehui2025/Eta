package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具参数校验的完整结果：错误文案与结构化元数据来自同一次校验。
 *
 * [missing] / [expected] / [received] 直接回填给模型，[example] 是同一份 schema 推导出的
 * 最小合法示例；调用方不需要再对错误文案做正则解析。
 */
internal data class ValidationOutcome(
    val message: String,
    val missing: List<String> = emptyList(),
    val expected: String = "",
    val received: String = "",
    val example: JSONObject = JSONObject(),
) {
    companion object {
        val OK = ValidationOutcome("")
    }
}

/**
 * 失败路径的 retry_hint 分类：让模型区分"改参数重试""换策略"与"不要重放副作用"。
 *
 * 只出现在拒绝类 tool result 中，不新增模型调用；未知错误码统一按需向用户交代处理。
 */
internal object AgentToolRetryHints {
    const val FIX_ARGUMENTS = "fix_arguments"
    const val CHANGE_STRATEGY = "change_strategy"
    const val RETRY_OR_REPORT = "retry_or_report"
    const val REPORT_ONLY = "report_only"

    fun forCode(code: String): String = when (code) {
        "INVALID_TOOL_ARGUMENTS", "TRUNCATED_TOOL_CALL" -> FIX_ARGUMENTS
        "NO_PROGRESS_LOOP", "UNEXPECTED_TOOL_CALL", "REPLY_REWRITE_TOOL_CALL",
        "WRITE_NOT_DECLARED", "WRITE_CONFLICT", "FILE_BUSY", "NESTED_SPAWN_NOT_ALLOWED",
        "EXCLUSIVE_TOOL_BUSY", -> CHANGE_STRATEGY
        "TOOL_REVIEW_REJECTED", "NO_ALLOWED_TOOLS", "INVALID_ARGUMENT",
        "ROOT_REQUIRED", "PERMISSION_DENIED", "SERVICE_TIMEOUT", "TOOL_ERROR",
        "ENDPOINT_PROTOCOL_MISMATCH", "ENDPOINT_UNAVAILABLE",
        -> RETRY_OR_REPORT
        else -> REPORT_ONLY
    }

    /** 给模型的简短指令，随 tool result 回传。 */
    fun instruction(code: String): String = when (forCode(code)) {
        FIX_ARGUMENTS -> "按 example 或 schema 补齐后重新调用；不要重复同一份无效参数。"
        CHANGE_STRATEGY -> "不要原样重试；先换参数、观察或执行方式，再决定下一步。"
        RETRY_OR_REPORT -> "若属临时故障可按需重试一次；否则如实向用户报告失败原因，不要继续重放。"
        else -> "向用户如实报告该错误与已完成的进度；不要自动重放可能有副作用的操作。"
    }
}

/** 校验失败的结构化细节；[message]、[missing]、[expectedSchema]、[received] 同源产生。 */
internal data class ValidationFailure(
    val message: String,
    val missing: List<String> = emptyList(),
    val expectedSchema: Any? = null,
    val received: Any? = null,
) {
    fun toOutcome(example: JSONObject): ValidationOutcome = ValidationOutcome(
        message = message,
        missing = missing,
        expected = describeExpectation(expectedSchema),
        received = describeValue(received),
        example = example,
    )

    companion object {
        fun of(
            message: String,
            missing: List<String> = emptyList(),
            expectedSchema: Any? = null,
            received: Any? = null,
        ): ValidationFailure = ValidationFailure(message, missing, expectedSchema, received)
    }
}

/** 把 schema 片段压成模型可读的一行期望值描述。 */
internal fun describeExpectation(schema: Any?): String = when (schema) {
    null, JSONObject.NULL -> ""
    is Boolean -> if (schema) "任意值" else "不可提供该字段"
    is JSONObject -> {
        val enum = schema.optJSONArray("enum")
        when {
            enum != null -> enum.toString()
            schema.opt("type") != null -> describeType(schema.opt("type"))
            schema.optString("${'$'}ref").isNotBlank() -> schema.optString("${'$'}ref")
            else -> ""
        }
    }
    else -> describeType(schema)
}

/** 把模型实际传入的值压成一行描述；超长/嵌套值只报类型，不转储内容。 */
internal fun describeValue(value: Any?): String = when (value) {
    null -> "缺失"
    JSONObject.NULL -> "null"
    is JSONObject -> "object"
    is JSONArray -> "array"
    is String -> JSONObject.quote(value.take(80))
    is Boolean -> value.toString()
    is Int, is Long, is Double, is Float -> value.toString()
    else -> value.javaClass.simpleName
}

/** 与校验器内部保持一致的 schema 类型描述，供元数据复用。 */
internal fun describeType(type: Any?): String = when (type) {
    is JSONArray -> (0 until type.length()).joinToString("或") { describeType(type.opt(it)) }
    is String -> when (type) {
        "string" -> "字符串"
        "integer" -> "整数"
        "number" -> "数字"
        "boolean" -> "布尔值"
        "array" -> "数组"
        "object" -> "对象"
        "null" -> "null"
        else -> type
    }
    else -> type?.toString().orEmpty()
}
