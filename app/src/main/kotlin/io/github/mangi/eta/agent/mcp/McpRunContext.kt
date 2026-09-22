package io.github.mangi.eta.agent.mcp

import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal class McpRunTool(
    val modelName: String,
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
)

/** 一次 run 使用冻结的 MCP 工具目录，设置变更从下一次 run 生效。 */
internal class McpRunSnapshot(
    val tools: List<McpRunTool>,
) {
    private val byModelName = tools.associateBy(McpRunTool::modelName)

    fun resolve(modelName: String): McpRunTool? = byModelName[modelName]

    fun appendModelTools(destination: JSONArray) {
        tools.forEach { tool ->
            val description = buildString {
                append("MCP 服务器「").append(tool.server.name).append("」提供的工具")
                tool.definition.description.trim().takeIf { it.isNotBlank() }?.let {
                    append("。 ").append(it)
                }
            }
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.modelName)
                            .put("description", description)
                            .put("parameters", JSONObject(tool.definition.inputSchemaJson)),
                    ),
            )
        }
    }

    companion object {
        val EMPTY = McpRunSnapshot(emptyList())

        suspend fun load(): McpRunSnapshot = coroutineScope {
            val now = System.currentTimeMillis()
            val projected = mutableListOf<McpRunTool>()
            // 并发发现 + 单服务器超时：旧实现串行遍历所有待刷新服务器，一个不响应的服务器
            // 就能把"第一个模型请求"推迟到它自己的完整超时（HTTP 路径最坏可达分钟级）。
            val resolved = McpServerRepository.enabledServers()
                .map { configured -> async(Dispatchers.IO) { resolve(configured, now) } }
                .awaitAll()
            resolved.forEach { entry ->
                val (server, bearerToken) = entry ?: return@forEach
                server.activeTools.forEach { tool ->
                    if (projected.size >= MAX_RUN_TOOLS) return@forEach
                    projected += McpRunTool(
                        modelName = modelToolName(server.id, tool.name),
                        server = server,
                        definition = tool,
                        bearerToken = bearerToken,
                    )
                }
            }
            McpRunSnapshot(projected)
        }

        /** 返回 null 表示本次运行不提供该服务器的工具（未刷新、发现失败或被抑制）。 */
        private suspend fun resolve(configured: McpServerSetting, now: Long): Pair<McpServerSetting, String?>? {
            val bearerToken = McpServerRepository.bearerToken(configured.id)
            val needsRefresh = configured.lastProtocolVersion == null ||
                configured.lastProtocolVersion == McpProtocolMode.LATEST &&
                (configured.toolsExpireAt == null || configured.toolsExpireAt <= now)
            if (!needsRefresh) return configured to bearerToken
            if (McpDiscoveryBackoff.isSuppressed(configured.id, now)) return null
            val discovered = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                runCatching { McpServerManager.discover(configured, bearerToken) }.getOrNull()
            }
            if (discovered == null) {
                // 失败不再静默吞掉，也不再每次运行都重付一遍等待：抑制窗口内跳过，之后照常重试。
                McpDiscoveryBackoff.recordFailure(configured.id, System.currentTimeMillis())
                AndroidAgentLogger.warnThrottled("agent_mcp_discovery_failed") {
                    "MCP server discovery failed or timed out: id=${configured.id.take(16)}"
                }
                return null
            }
            McpDiscoveryBackoff.clear(configured.id)
            runCatching { McpServerRepository.update(discovered) }
            return discovered to bearerToken
        }

        private fun modelToolName(serverId: String, toolName: String): String {
            val serverPart = serverId.filter(Char::isLetterOrDigit).take(8).ifBlank { "server" }
            val normalized = toolName
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(30)
                .ifBlank { "tool" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$serverId\u0000$toolName".toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "mcp_${serverPart}_${normalized}_$digest"
        }

        private const val MAX_RUN_TOOLS = 64

        /** 单个服务器的发现超时：宁可本次运行暂时没有它的工具，也不让首步一直等。 */
        private const val DISCOVERY_TIMEOUT_MS = 8_000L
    }
}

/**
 * MCP 发现失败的短时抑制：失败不再每次 run 都完整重付一遍超时等待。
 * 进程内缓存即可——运行服务随进程存活；手动刷新会清掉对应条目。
 */
internal object McpDiscoveryBackoff {
    private const val SUPPRESS_MS = 60_000L
    private val failuresUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isSuppressed(serverId: String, now: Long): Boolean = (failuresUntil[serverId] ?: 0L) > now

    fun recordFailure(serverId: String, now: Long) {
        failuresUntil[serverId] = now + SUPPRESS_MS
    }

    fun clear(serverId: String) {
        failuresUntil.remove(serverId)
    }
}

internal class McpToolExecutor(
    private val snapshot: McpRunSnapshot,
    private val clientFactory: (McpServerSetting, String?) -> McpServerClient = McpServerManager::clientFor,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val lifecycleLock = Any()
    private val clients = mutableMapOf<String, McpServerClient>()
    private var closed = false

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = snapshot.resolve(toolCall.name) ?: return failure(
            code = "UNKNOWN_MCP_TOOL",
            message = "MCP 工具未在本次运行中启用",
        )
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return failure("INVALID_ARGUMENT", "MCP 工具参数不是 JSON object") }
        return runCatching {
            val client = synchronized(lifecycleLock) {
                if (closed) return failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭")
                clients[tool.server.id] ?: clientFactory(tool.server, tool.bearerToken)
                    .also { clients[tool.server.id] = it }
            }
            adaptResult(tool, client.callTool(tool.definition, arguments))
        }.getOrElse {
            failure("MCP_CALL_FAILED", "MCP 工具调用失败")
        }
    }

    fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }

    private fun adaptResult(tool: McpRunTool, result: JSONObject): AgentModelClient.ToolResult {
        val resultType = result.optString("resultType")
        if (resultType == "input_required") {
            return failure(
                code = "MCP_INPUT_REQUIRED_UNSUPPORTED",
                message = "当前版本暂不支持 MCP 工具在执行中请求补充输入",
            )
        }
        if (tool.server.lastProtocolVersion == McpProtocolMode.LATEST && resultType != "complete") {
            return failure(
                code = "MCP_RESULT_TYPE_UNSUPPORTED",
                message = "MCP 工具返回了不支持的结果类型",
            )
        }
        val content = result.optJSONArray("content") ?: JSONArray()
        val textItems = JSONArray()
        val images = mutableListOf<AgentModelClient.ModelImage>()
        var textBytes = 0
        var imageBytes = 0
        var truncated = false
        var omittedItems = 0
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    val text = item.optString("text")
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (remaining > 0) {
                        val bounded = text.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < text.length) truncated = true
                    } else if (text.isNotEmpty()) {
                        truncated = true
                    }
                }
                "image" -> {
                    val mimeType = item.optString("mimeType")
                    val data = item.optString("data")
                    if (!mimeType.startsWith("image/") || data.isBlank()) {
                        omittedItems += 1
                        continue
                    }
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT).size }.getOrNull()
                    if (bytes == null || bytes <= 0 || imageBytes + bytes > MAX_IMAGE_BYTES) {
                        omittedItems += 1
                        continue
                    }
                    images += AgentModelClient.ModelImage(
                        reference = "data:$mimeType;base64,$data",
                        mimeType = mimeType,
                        bytes = bytes,
                        source = "mcp",
                    )
                    imageBytes += bytes
                }
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val resourceText = resource?.optString("text").orEmpty()
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (resourceText.isNotBlank() && remaining > 0) {
                        val bounded = resourceText.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < resourceText.length) truncated = true
                    } else if (resource?.has("blob") == true || resourceText.isNotBlank()) {
                        omittedItems += 1
                    }
                }
                "resource_link" -> {
                    val uri = item.optString("uri")
                    if (uri.isNotBlank()) {
                        val label = item.optString("name").ifBlank { item.optString("title") }
                        val rendered = if (label.isBlank()) uri else "$label: $uri"
                        val remaining = MAX_TEXT_BYTES - textBytes
                        if (remaining > 0) {
                            val bounded = rendered.takeUtf8Bytes(remaining)
                            textItems.put(bounded)
                            textBytes += bounded.toByteArray().size
                            if (bounded.length < rendered.length) truncated = true
                        } else {
                            truncated = true
                        }
                    } else {
                        omittedItems += 1
                    }
                }
                "audio" -> omittedItems += 1
                else -> omittedItems += 1
            }
        }
        val payload = JSONObject()
            .put("ok", !result.optBoolean("isError", false))
            .put("server", tool.server.name)
            .put("tool", tool.definition.name)
            .put("content", textItems)
        result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.let { structured ->
            val encoded = structured.toString()
            if (encoded.toByteArray().size <= MAX_STRUCTURED_BYTES) {
                payload.put("structured_content", structured)
            } else {
                truncated = true
            }
        }
        if (truncated) payload.put("truncated", true)
        if (omittedItems > 0) payload.put("omitted_items", omittedItems)
        if (result.optBoolean("isError", false)) payload.put("code", "MCP_TOOL_ERROR")
        return AgentModelClient.ToolResult(
            content = payload.toString(),
            images = images,
            sensitive = true,
        )
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (substring(0, middle).toByteArray().size <= maxBytes) low = middle else high = middle - 1
        }
        return substring(0, low)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_STRUCTURED_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}

internal class RoutingToolExecutor(
    private val local: AgentModelClient.ToolExecutor,
    private val mcp: McpToolExecutor,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        if (mcp.contains(toolCall.name)) mcp.execute(toolCall) else local.execute(toolCall)

    override fun close() {
        runCatching { mcp.close() }
        (local as? AutoCloseable)?.let { runCatching { it.close() } }
    }
}
