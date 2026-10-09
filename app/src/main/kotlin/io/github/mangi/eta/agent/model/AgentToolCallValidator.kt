package io.github.mangi.eta.agent.model

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/** 在工具执行前校验模型参数；这里只检查调用合同，不承担权限审批或安全策略。 */
internal class AgentToolCallValidator(tools: JSONArray) {
    private data class ToolSchema(
        val parameters: JSONObject,
        val root: JSONObject,
    )

    private val schemasByName: Map<String, ToolSchema> = buildMap {
        for (index in 0 until tools.length()) {
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
            val name = function.optString("name").trim()
            val parameters = function.optJSONObject("parameters") ?: continue
            if (name.isNotBlank()) put(name, ToolSchema(parameters, parameters))
        }
    }

    /**
     * 同一 (validator 实例, 调用 id) 只完整校验一次：拒绝路径用同一结果产出
     * error message 与结构化 metadata，避免重复遍历 schema，也不会出现两份互相矛盾的提示。
     * 调用 id 为空（旧调用方）时不缓存。
     */
    private val cache = HashMap<String, ValidationOutcome>()
    private val instanceKey = System.identityHashCode(this)

    fun validate(call: AgentModelClient.ToolCall): String? =
        validateDetailed(call).message.ifBlank { null }

    fun validateDetailed(call: AgentModelClient.ToolCall): ValidationOutcome {
        // 键里必须包含参数：回放/测试会复用同一调用 id 探测不同参数，只看 id 会错误复用旧结论。
        val key = if (call.id.isBlank()) {
            null
        } else {
            "$instanceKey:${call.id}:${call.name}:${call.argumentsJson.hashCode()}"
        }
        key?.let { cache[it] }?.let { return it }
        val outcome = computeValidation(call)
        key?.let { cache[it] = outcome }
        return outcome
    }

    /** 测试用：已缓存（即已实际完成校验）的调用数。 */
    internal fun cachedValidationCount(): Int = cache.size

    /** 同一份 schema 推导出的最小合法示例；operation 目前只用于兼容调用点。 */
    fun minimalExample(name: String, operation: String = ""): JSONObject =
        minimalExampleFor(schemasByName[name]?.parameters)

    /** 工具参数的 JSON Schema；供参数宽容化（[ToolCallArgumentSanitizer]）使用。 */
    fun schemaFor(name: String): JSONObject? = schemasByName[name]?.parameters

    private fun computeValidation(call: AgentModelClient.ToolCall): ValidationOutcome {
        val example = minimalExample(
            call.name,
            call.parsedArgsOrNull()?.let { args ->
                args.optString("action").ifBlank { args.optString("operation") }
            }.orEmpty(),
        )
        val toolSchema = schemasByName[call.name]
        if (toolSchema == null) {
            if (call.name !in LEGACY_TRANSCRIPT_TOOLS) {
                return ValidationFailure.of("工具未在本次运行的能力目录中声明").toOutcome(example)
            }
            val legacyFailure = validateLegacy(call)
            return legacyFailure?.toOutcome(example) ?: ValidationOutcome.OK
        }
        val arguments = call.parsedArgs().getOrNull()
            ?: return ValidationFailure.of(
                message = "参数不是有效的 JSON object",
                expectedSchema = JSONObject().put("type", "object"),
                received = call.argumentsJson.take(200),
            ).toOutcome(example)
        validateValue(
            value = arguments,
            schema = toolSchema.parameters,
            root = toolSchema.root,
            path = "arguments",
            depth = 0,
        )?.let { return it.toOutcome(example) }
        validateCanonicalOperation(call.name, arguments)?.let { return it.toOutcome(example) }
        return ValidationOutcome.OK.copy(example = example)
    }

    private fun validateLegacy(call: AgentModelClient.ToolCall): ValidationFailure? {
        val args = call.parsedArgs().getOrNull() ?: return ValidationFailure.of("参数不是有效的 JSON object")
        fun missing(vararg fields: String): ValidationFailure? {
            val absent = fields.filter {
                !args.has(it) || args.isNull(it) || (args.opt(it) is String && args.optString(it).isBlank())
            }
            return absent.takeIf { it.isNotEmpty() }?.let {
                ValidationFailure.of(
                    message = "${call.name} 缺少必填字段 ${it.joinToString(", ")}",
                    missing = it,
                )
            }
        }
        return when (call.name) {
            "tap", "long_press" -> if (args.has("index")) missing("index", "observation_id") else missing("x", "y")
            "tap_element", "long_press_element" -> missing("index", "observation_id")
            "input_text", "replace_text", "paste_text" -> missing("text")
            "press_key" -> missing("button")
            "read_file", "write_file", "edit_file", "list_directory", "search_code" -> missing("path")
            else -> null
        }
    }

    /**
     * JSON Schema validates the shape, while these operation contracts validate the relationship
     * between an operation and the fields it makes meaningful. Keeping this check here means old
     * transcript adapters cannot accidentally dispatch a canonical call with a legacy default.
     */
    private fun validateCanonicalOperation(name: String, args: JSONObject): ValidationFailure? {
        fun missing(vararg fields: String): ValidationFailure? {
            val absent = fields.filter { field ->
                !args.has(field) || args.isNull(field) ||
                    (args.opt(field) is String && args.optString(field).isBlank())
            }
            return absent.takeIf { it.isNotEmpty() }?.let {
                ValidationFailure.of(
                    message = "$name operation=${args.optString("action", args.optString("operation"))} " +
                        "缺少必填字段 ${it.joinToString(", ")}; 请先提供完整参数",
                    missing = it,
                )
            }
        }
        fun anyOf(vararg fields: String): ValidationFailure? =
            if (fields.any { field ->
                    args.has(field) && !args.isNull(field) &&
                        !(args.opt(field) is String && args.optString(field).isBlank())
                }
            ) null else missing(*fields)
        fun observationIfIndexed(): ValidationFailure? =
            if (args.has("index")) missing("index", "observation_id") else null

        return when (name) {
            "web_search" -> when (args.optString("operation")) {
                "search" -> missing("query")
                "read" -> missing("url")
                else -> ValidationFailure.of("web_search 的 operation 无效")
            }
            "ui_action" -> when (val action = args.optString("action")) {
                "tap", "long_press" ->
                    observationIfIndexed() ?: if (args.has("index")) null else missing("x", "y")
                "swipe" -> missing("x1", "y1", "x2", "y2")
                "scroll" ->
                    observationIfIndexed() ?: if (args.has("index")) missing("direction") else missing("direction")
                "input" -> missing("text") ?: observationIfIndexed()
                "clear" -> observationIfIndexed()
                "key" -> missing("button")
                "wait" -> when (args.optString("condition", "duration")) {
                    "duration" -> missing("timeout_ms")
                    "text" -> missing("text", "timeout_ms")
                    "package" -> missing("package_name", "timeout_ms")
                    else -> ValidationFailure.of("ui_action operation=wait 的 condition 不受支持")
                }
                "open_system_panel" -> missing("panel")
                else -> ValidationFailure.of("ui_action 的 action 无效：$action")
            }
            "app_action" -> when (args.optString("action")) {
                "search" -> missing("query")
                "launch" -> anyOf("package_name", "app_name")
                "open_uri" -> missing("uri")
                else -> ValidationFailure.of("app_action 的 action 无效")
            }
            "device_control" -> when (args.optString("operation")) {
                "alarm" -> missing("hour", "minute")
                "timer" -> missing("duration_seconds")
                "media" -> missing("media_action")
                "volume" -> missing("stream", "percent")
                else -> ValidationFailure.of("device_control 的 operation 无效")
            }
            "clipboard" -> when (args.optString("operation")) {
                "get" -> null
                "set", "paste" -> missing("text")
                else -> ValidationFailure.of("clipboard 的 operation 无效")
            }
            "file_ops" -> when (args.optString("operation")) {
                "read", "list" -> missing("path")
                // delete 与 read/list 同样只需要 path；recursive 为可选参数，不在此校验。
                "delete" -> missing("path")
                "write" -> missing("path", "content")
                "edit" -> missing("path", "old_string", "new_string")
                "search" -> missing("path") ?: anyOf("query", "pattern")
                else -> ValidationFailure.of("file_ops 的 operation 无效")
            }
            "read_image" -> missing("path")
            "skill" -> when (args.optString("operation")) {
                "list", "curated" -> null
                "read" -> missing("skill_id")
                "resource" -> missing("skill_id", "relative_path")
                else -> ValidationFailure.of("skill 的 operation 无效")
            }
            "skill_github" -> when (args.optString("operation")) {
                "inspect" -> missing("repository")
                "install" -> missing("repository") ?: anyOf("path", "paths")
                else -> ValidationFailure.of("skill_github 的 operation 无效")
            }
            "memory" -> when (args.optString("operation")) {
                "get" -> null
                "write" -> when (args.optString("mode")) {
                    "replace_range" -> missing("revision", "start_line", "end_line", "content")
                    "append" -> missing("revision", "content")
                    "clear" -> missing("revision")
                    else -> ValidationFailure.of("memory operation=write 缺少有效 mode")
                }
                else -> ValidationFailure.of("memory 的 operation 无效")
            }
            else -> null
        }
    }

    /** 用 schema 的 required 字段与 enum 候选生成最小合法示例，避免再手写每个工具的分支。 */
    private fun minimalExampleFor(schema: JSONObject?): JSONObject {
        val required = schema?.optJSONArray("required") ?: return JSONObject()
        val properties = schema.optJSONObject("properties")
        val example = JSONObject()
        for (index in 0 until required.length()) {
            val name = required.optString(index)
            if (name.isBlank()) continue
            example.put(name, exampleValue(properties?.optJSONObject(name) ?: JSONObject()))
        }
        return example
    }

    private fun exampleValue(schema: JSONObject): Any {
        schema.optJSONArray("enum")?.let { values ->
            if (values.length() > 0) return values.opt(0) ?: ""
        }
        return when (schema.optString("type")) {
            "boolean" -> true
            "integer" -> 1
            "number" -> 1
            "array" -> JSONArray()
            "object" -> JSONObject()
            else -> "示例"
        }
    }

    private fun validateValue(
        value: Any?,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): ValidationFailure? {
        if (depth > MAX_SCHEMA_DEPTH) return ValidationFailure.of("$path 的 Schema 引用层级过深")

        schema.optString("${'$'}ref").takeIf { it.isNotBlank() }?.let { reference ->
            val referenced = resolveReference(root, reference)
                ?: return ValidationFailure.of("$path 的 Schema 引用无法解析：$reference")
            validateSchema(value, referenced, root, path, depth + 1)?.let { return it }
        }

        validateComposition(value, schema, root, path, depth)?.let { return it }

        if (schema.optBoolean("nullable", false) && isJsonNull(value)) return null
        val type = schema.opt("type")
        if (type != null && type != JSONObject.NULL && !matchesType(value, type)) {
            return ValidationFailure.of(
                message = "$path 类型应为 ${describeType(type)}",
                expectedSchema = type,
                received = value,
            )
        }

        if (schema.has("const") && !jsonEquals(schema.opt("const"), value)) {
            return ValidationFailure.of(
                message = "$path 必须等于 Schema 声明的固定值",
                expectedSchema = schema.opt("const"),
                received = value,
            )
        }
        val enum = schema.optJSONArray("enum")
        if (enum != null && (0 until enum.length()).none { jsonEquals(enum.opt(it), value) }) {
            return ValidationFailure.of(
                message = "$path 不在允许值集合中",
                expectedSchema = enum,
                received = value,
            )
        }

        return when (value) {
            is JSONObject -> validateObject(value, schema, root, path, depth)
            is JSONArray -> validateArray(value, schema, root, path, depth)
            is String -> validateString(value, schema, path)
            is Number -> validateNumber(value, schema, path)
            else -> null
        }
    }

    private fun validateComposition(
        value: Any?,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): ValidationFailure? {
        schema.optJSONArray("allOf")?.let { branches ->
            for (index in 0 until branches.length()) {
                validateSchema(value, branches.opt(index), root, path, depth + 1)?.let { return it }
            }
        }
        schema.optJSONArray("anyOf")?.let { branches ->
            if (!matchesBranchCount(value, branches, root, path, depth, minimum = 1)) {
                return ValidationFailure.of(
                    message = "$path 不符合 anyOf 中的任何 Schema",
                    expectedSchema = branches,
                    received = value,
                )
            }
        }
        schema.optJSONArray("oneOf")?.let { branches ->
            if (!matchesBranchCount(value, branches, root, path, depth, minimum = 1, maximum = 1)) {
                return ValidationFailure.of(
                    message = "$path 必须且只能符合 oneOf 中的一个 Schema",
                    expectedSchema = branches,
                    received = value,
                )
            }
        }
        schema.opt("not").takeUnless { it == null || it == JSONObject.NULL }?.let { rejected ->
            if (validateSchema(value, rejected, root, path, depth + 1) == null) {
                return ValidationFailure.of("$path 符合了 not 禁止的 Schema")
            }
        }
        schema.opt("if").takeUnless { it == null || it == JSONObject.NULL }?.let { condition ->
            val branch = if (validateSchema(value, condition, root, path, depth + 1) == null) {
                schema.opt("then")
            } else {
                schema.opt("else")
            }
            if (branch != null && branch != JSONObject.NULL) {
                validateSchema(value, branch, root, path, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun matchesBranchCount(
        value: Any?,
        branches: JSONArray,
        root: JSONObject,
        path: String,
        depth: Int,
        minimum: Int,
        maximum: Int = Int.MAX_VALUE,
    ): Boolean {
        var matches = 0
        for (index in 0 until branches.length()) {
            if (validateSchema(value, branches.opt(index), root, path, depth + 1) == null) matches += 1
            if (matches > maximum) return false
        }
        return matches in minimum..maximum
    }

    private fun validateObject(
        value: JSONObject,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): ValidationFailure? {
        val size = value.length()
        schema.optInteger("minProperties")?.let { if (size < it) return ValidationFailure.of("$path 的字段数不能少于 $it") }
        schema.optInteger("maxProperties")?.let { if (size > it) return ValidationFailure.of("$path 的字段数不能超过 $it") }

        schema.optJSONArray("required")?.let { required ->
            // 键缺失与显式 null 都算缺失（与 operation 契约的 missing 语义一致）；
            // 空白串不在此拦截——部分字段（如写文件的 content=""）合法，交给 enum/契约层裁决。
            val absent = (0 until required.length())
                .map { required.optString(it) }
                .filter { it.isNotBlank() && (!value.has(it) || value.isNull(it)) }
            if (absent.isNotEmpty()) {
                return ValidationFailure.of(
                    message = "$path 缺少必填字段 ${absent.joinToString(", ")}",
                    missing = absent,
                    expectedSchema = schema,
                    received = value,
                )
            }
        }

        schema.optJSONObject("dependentRequired")?.let { dependencies ->
            for (key in dependencies.keys()) {
                if (!value.has(key)) continue
                val required = dependencies.optJSONArray(key) ?: continue
                for (index in 0 until required.length()) {
                    val dependent = required.optString(index)
                    if (!value.has(dependent)) {
                        return ValidationFailure.of(
                            message = "$path.$key 要求同时提供字段 $dependent",
                            missing = listOf(dependent),
                        )
                    }
                }
            }
        }

        val properties = schema.optJSONObject("properties")
        val patternProperties = schema.optJSONObject("patternProperties")
        val additionalProperties = schema.opt("additionalProperties")
        for (key in value.keys()) {
            val childPath = "$path.$key"
            val childValue = value.opt(key)
            var matched = false
            properties?.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { childSchema ->
                matched = true
                validateSchema(childValue, childSchema, root, childPath, depth + 1)?.let { return it }
            }
            if (patternProperties != null) {
                for (pattern in patternProperties.keys()) {
                    val regex = runCatching { Regex(pattern) }.getOrNull() ?: continue
                    if (!regex.containsMatchIn(key)) continue
                    matched = true
                    val childSchema = patternProperties.opt(pattern) ?: continue
                    validateSchema(childValue, childSchema, root, childPath, depth + 1)?.let { return it }
                }
            }
            if (!matched) {
                when (additionalProperties) {
                    // 额外字段不再整单拒绝：参数宽容化（ToolCallArgumentSanitizer）会剔除并向模型
                    // 说明被忽略的字段；校验层只对显式声明的形状负责。
                    false -> Unit
                    is JSONObject, is Boolean ->
                        validateSchema(childValue, additionalProperties, root, childPath, depth + 1)?.let { return it }
                }
            }
        }

        schema.opt("propertyNames").takeUnless { it == null || it == JSONObject.NULL }?.let { nameSchema ->
            for (key in value.keys()) {
                validateSchema(key, nameSchema, root, "$path 的字段名 $key", depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun validateArray(
        value: JSONArray,
        schema: JSONObject,
        root: JSONObject,
        path: String,
        depth: Int,
    ): ValidationFailure? {
        schema.optInteger("minItems")?.let {
            if (value.length() < it) return ValidationFailure.of("$path 项目数不能少于 $it")
        }
        schema.optInteger("maxItems")?.let {
            if (value.length() > it) return ValidationFailure.of("$path 项目数不能超过 $it")
        }
        if (schema.optBoolean("uniqueItems", false)) {
            for (left in 0 until value.length()) {
                for (right in left + 1 until value.length()) {
                    if (jsonEquals(value.opt(left), value.opt(right))) {
                        return ValidationFailure.of("$path 不允许重复项目")
                    }
                }
            }
        }

        val prefixItems = schema.optJSONArray("prefixItems")
        if (prefixItems != null) {
            for (index in 0 until minOf(prefixItems.length(), value.length())) {
                validateSchema(value.opt(index), prefixItems.opt(index), root, "$path[$index]", depth + 1)
                    ?.let { return it }
            }
        }
        when (val items = schema.opt("items")) {
            is JSONObject -> {
                val start = prefixItems?.length() ?: 0
                for (index in start until value.length()) {
                    validateValue(value.opt(index), items, root, "$path[$index]", depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (index in 0 until minOf(items.length(), value.length())) {
                    validateSchema(value.opt(index), items.opt(index), root, "$path[$index]", depth + 1)
                        ?.let { return it }
                }
            }
            false -> if (value.length() > (prefixItems?.length() ?: 0)) {
                return ValidationFailure.of("$path 不允许更多项目")
            }
        }

        schema.opt("contains").takeUnless { it == null || it == JSONObject.NULL }?.let { contains ->
            val matches = (0 until value.length()).count { index ->
                validateSchema(value.opt(index), contains, root, "$path[$index]", depth + 1) == null
            }
            val minimum = schema.optInteger("minContains") ?: 1
            val maximum = schema.optInteger("maxContains") ?: Int.MAX_VALUE
            if (matches !in minimum..maximum) {
                return ValidationFailure.of("$path 中符合 contains 的项目数必须在 $minimum..$maximum 之间")
            }
        }
        return null
    }

    private fun validateString(value: String, schema: JSONObject, path: String): ValidationFailure? {
        schema.optInteger("minLength")?.let {
            if (value.codePointCount(0, value.length) < it) {
                return ValidationFailure.of(
                    message = "$path 长度不能少于 $it",
                    expectedSchema = schema,
                    received = value,
                )
            }
        }
        schema.optInteger("maxLength")?.let {
            if (value.codePointCount(0, value.length) > it) {
                return ValidationFailure.of(
                    message = "$path 长度不能超过 $it",
                    expectedSchema = schema,
                    received = value,
                )
            }
        }
        schema.optString("pattern").takeIf { it.isNotBlank() }?.let { pattern ->
            val regex = runCatching { Regex(pattern) }.getOrNull()
                ?: return ValidationFailure.of("$path 的 Schema pattern 无效")
            if (!regex.containsMatchIn(value)) {
                return ValidationFailure.of(
                    message = "$path 不符合 pattern $pattern",
                    expectedSchema = schema,
                    received = value,
                )
            }
        }
        return null
    }

    private fun validateNumber(value: Number, schema: JSONObject, path: String): ValidationFailure? {
        val number = value.toBigDecimal() ?: return ValidationFailure.of("$path 不是有效数字")
        schema.optBigDecimal("minimum")?.let {
            if (number < it) return ValidationFailure.of("$path 不能小于 $it").copy(
                expectedSchema = schema,
                received = value,
            )
        }
        schema.optBigDecimal("maximum")?.let {
            if (number > it) return ValidationFailure.of("$path 不能大于 $it").copy(
                expectedSchema = schema,
                received = value,
            )
        }
        schema.optBigDecimal("exclusiveMinimum")?.let {
            if (number <= it) return ValidationFailure.of("$path 必须大于 $it").copy(
                expectedSchema = schema,
                received = value,
            )
        }
        schema.optBigDecimal("exclusiveMaximum")?.let {
            if (number >= it) return ValidationFailure.of("$path 必须小于 $it").copy(
                expectedSchema = schema,
                received = value,
            )
        }
        schema.optBigDecimal("multipleOf")?.takeIf { it.signum() != 0 }?.let { divisor ->
            if (number.remainder(divisor).compareTo(BigDecimal.ZERO) != 0) {
                return ValidationFailure.of("$path 必须是 $divisor 的倍数").copy(
                    expectedSchema = schema,
                    received = value,
                )
            }
        }
        return null
    }

    private fun matchesType(value: Any?, declared: Any): Boolean {
        if (declared is JSONArray) {
            return (0 until declared.length()).any { matchesType(value, declared.optString(it)) }
        }
        val type = declared as? String ?: return true
        return when (type) {
            "object" -> value is JSONObject
            "array" -> value is JSONArray
            "string" -> value is String
            "boolean" -> value is Boolean
            "number" -> value is Number
            "integer" -> value is Number && value.toBigDecimal()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
            "null" -> isJsonNull(value)
            else -> true
        }
    }

    private fun validateSchema(
        value: Any?,
        schema: Any?,
        root: JSONObject,
        path: String,
        depth: Int,
    ): ValidationFailure? = when (schema) {
        true -> null
        false -> ValidationFailure.of("$path 被 false Schema 拒绝")
        is JSONObject -> validateValue(value, schema, root, path, depth)
        else -> ValidationFailure.of("$path 的 Schema 节点无效")
    }

    private fun resolveReference(root: JSONObject, reference: String): Any? {
        if (!reference.startsWith("#")) return null
        if (reference == "#") return root
        if (!reference.startsWith("#/")) return null
        var current: Any? = root
        for (rawToken in reference.removePrefix("#/").split('/')) {
            val token = rawToken.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JSONObject -> if (current.has(token)) current.opt(token) else return null
                is JSONArray -> token.toIntOrNull()?.let { current.opt(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    private fun jsonEquals(left: Any?, right: Any?): Boolean = canonicalJson(left) == canonicalJson(right)

    private fun canonicalJson(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonicalJson(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { canonicalJson(value.opt(it)) }
        is Number -> value.toBigDecimal()?.stripTrailingZeros()
        else -> value
    }

    private fun Number.toBigDecimal(): BigDecimal? = runCatching { BigDecimal(toString()) }.getOrNull()

    private fun JSONObject.optInteger(name: String): Int? =
        opt(name).takeIf { it is Number }?.let { (it as Number).toInt() }

    private fun JSONObject.optBigDecimal(name: String): BigDecimal? =
        (opt(name) as? Number)?.toBigDecimal()

    private fun isJsonNull(value: Any?): Boolean = value == null || value == JSONObject.NULL

    private companion object {
        const val MAX_SCHEMA_DEPTH = 256
        /** Accepted only for restored transcripts/checkpoints; never published in new schemas. */
        val LEGACY_TRANSCRIPT_TOOLS = setOf(
            "get_current_context", "search_apps", "launch_app", "open_uri", "tap", "tap_element",
            "long_press", "long_press_element", "swipe", "scroll", "scroll_element", "input_text",
            "replace_text", "clear_text", "set_clipboard", "get_clipboard", "paste_text", "press_key",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel", "read_file", "write_file",
            "edit_file", "search_code", "list_directory", "memory_get", "memory_write", "skills_list",
            "skills_read", "skills_read_resource", "skills_list_curated", "skills_inspect_github",
            "skills_install_from_github", "run_command", "get_setting", "network_info", "device_status",
        )
    }
}
