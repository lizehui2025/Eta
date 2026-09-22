package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpToolDefinition
import org.json.JSONObject

/**
 * MCP 服务器客户端统一接口：远程 Streamable HTTP 与本地 stdio 共用调用语义。
 *
 * 发现（tools/list）因两种传输的协商与缓存语义不同，仍由各自实现暴露；
 * 进入 run 的工具调用一律走本接口，便于 [McpToolExecutor] 按服务器缓存客户端。
 */
internal interface McpServerClient : AutoCloseable {
    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject
}
