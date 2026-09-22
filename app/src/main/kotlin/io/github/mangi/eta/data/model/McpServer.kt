package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

internal object McpProtocolMode {
    const val AUTO = "auto"
    const val LATEST = "2026-07-28"
    const val LEGACY = "2025-11-25"
}

internal object McpAuthorizationType {
    const val NONE = "none"
    const val BEARER = "bearer"
}

/**
 * MCP 服务器传输类型。
 * REMOTE：远程 Streamable HTTP（现有链路，HTTP/HTTPS/局域网/本机地址通用）。
 * STDIO：本地命令经 stdin/stdout 运行（MCP 标准 stdio 传输，一次 run 内复用进程）。
 */
internal object McpTransport {
    const val REMOTE = "remote"
    const val STDIO = "stdio"
}

@Serializable
internal data class McpToolDefinition(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchemaJson: String,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

@Serializable
internal data class McpServerSetting(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val protocolMode: String = McpProtocolMode.AUTO,
    val authorizationType: String = McpAuthorizationType.NONE,
    val tools: List<McpToolDefinition> = emptyList(),
    val enabledToolNames: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val lastRefreshedAt: Long? = null,
    val lastProtocolVersion: String? = null,
    val toolsExpireAt: Long? = null,
    /** 传输类型：remote（默认，远程 HTTP）或 stdio（本地命令）。 */
    val transport: String = McpTransport.REMOTE,
    /** 本地命令（可执行文件路径或 PATH 中的名称），仅 transport=stdio 时使用。 */
    val command: String = "",
    /** 本地命令参数，仅 transport=stdio 时使用。 */
    val args: List<String> = emptyList(),
    /** 本地进程环境变量覆盖，仅 transport=stdio 时使用。 */
    val env: Map<String, String> = emptyMap(),
    /** 本地进程工作目录（空表示继承应用默认），仅 transport=stdio 时使用。 */
    val workingDir: String = "",
) {
    val activeTools: List<McpToolDefinition>
        get() = if (!enabled) {
            emptyList()
        } else {
            tools.filter { it.name in enabledToolNames }
        }

    val isLocal: Boolean
        get() = transport == McpTransport.STDIO

    /** 列表与详情展示用：一行 endpoint 摘要（远程显示 URL，本地显示命令行）。 */
    fun displayEndpoint(): String =
        if (isLocal) {
            ((listOf(command) + args).filter { it.isNotBlank() }.joinToString(" ")).ifBlank { command }
        } else {
            url
        }
}
