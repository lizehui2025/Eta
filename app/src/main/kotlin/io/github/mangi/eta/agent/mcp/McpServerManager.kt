package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository

internal object McpServerManager {
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpServerSetting = if (server.isLocal) {
        discoverLocal(server)
    } else {
        discoverRemote(server, bearerToken)
    }

    /** 按传输类型创建调用客户端：供 run 内复用，调用方负责 close。 */
    fun clientFor(server: McpServerSetting, bearerToken: String?): McpServerClient =
        if (server.isLocal) McpStdioClient(server) else McpHttpClient(server, bearerToken)

    private fun discoverLocal(server: McpServerSetting): McpServerSetting =
        McpStdioClient(server).use { client ->
            val discovery = client.discoverTools()
            val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
            val refreshedAt = System.currentTimeMillis()
            server.copy(
                tools = discovery.tools,
                enabledToolNames = server.enabledToolNames.intersect(availableNames),
                lastRefreshedAt = refreshedAt,
                lastProtocolVersion = discovery.protocolVersion,
                toolsExpireAt = null,
            )
        }

    private fun discoverRemote(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpServerSetting = McpHttpClient(server, bearerToken).use { client ->
        val discovery = client.discoverTools()
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        // 手动刷新立即解除失败抑制，用户不必等抑制窗口过期。
        McpDiscoveryBackoff.clear(serverId)
        val refreshed = discover(server, McpServerRepository.bearerToken(serverId))
        McpServerRepository.update(refreshed)
        return refreshed
    }
}
