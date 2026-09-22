package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.model.McpTransport
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpStdioValidationTest {
    // ---------- 配置解析 ----------

    @Test
    fun parseArgsHandlesQuotingAndEscapes() {
        assertEquals(emptyList<String>(), McpLocalConfig.parseArgs(""))
        assertEquals(listOf("a", "b"), McpLocalConfig.parseArgs("  a   b  "))
        assertEquals(
            listOf("--flag", "hello world", "a  b"),
            McpLocalConfig.parseArgs("--flag 'hello world' \"a  b\""),
        )
        assertEquals(listOf("a b"), McpLocalConfig.parseArgs("a\\ b"))
        assertEquals(listOf(""), McpLocalConfig.parseArgs("''"))
    }

    @Test
    fun parseArgsRejectsBadInput() {
        try {
            McpLocalConfig.parseArgs("--flag 'oops")
            fail("引号未闭合应抛错")
        } catch (_: IllegalArgumentException) {
        }
        try {
            McpLocalConfig.parseArgs((1..33).joinToString(" ") { "a$it" })
            fail("超量参数应抛错")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun parseEnvHandlesCommentsAndBlanks() {
        assertEquals(emptyMap<String, String>(), McpLocalConfig.parseEnv("  \n"))
        assertEquals(
            mapOf("A" to "1", "B" to "hello world", "EMPTY" to ""),
            McpLocalConfig.parseEnv("A=1\n# comment\n\nB=hello world\nEMPTY="),
        )
    }

    @Test
    fun parseEnvRejectsBadInput() {
        try {
            McpLocalConfig.parseEnv("NOEQUALS")
            fail("缺 = 应抛错")
        } catch (_: IllegalArgumentException) {
        }
        try {
            McpLocalConfig.parseEnv("1BAD=x")
            fail("非法变量名应抛错")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun validateCommandRejectsBadInput() {
        assertEquals("/bin/fake-mcp", McpLocalConfig.validateCommand("  /bin/fake-mcp  "))
        try {
            McpLocalConfig.validateCommand("   ")
            fail("空命令应抛错")
        } catch (_: IllegalArgumentException) {
        }
        try {
            McpLocalConfig.validateCommand("a".repeat(513))
            fail("超长命令应抛错")
        } catch (_: IllegalArgumentException) {
        }
        try {
            McpLocalConfig.validateCommand("cmd\ninjected")
            fail("换行应抛错")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------- 模型与持久化 ----------

    @Test
    fun localSettingRoundTripsThroughEntity() {
        val setting = McpServerSetting(
            id = "local-1",
            name = "Local",
            url = "",
            transport = McpTransport.STDIO,
            command = "/bin/fake-mcp",
            args = listOf("--flag", "hello world"),
            env = mapOf("A" to "1"),
            workingDir = "/tmp",
        )
        assertTrue(setting.isLocal)
        assertEquals("/bin/fake-mcp --flag hello world", setting.displayEndpoint())
        assertEquals(setting, setting.toEntity().toDomain())
    }

    @Test
    fun remoteSettingKeepsUrlEndpoint() {
        val setting = McpServerSetting(id = "r", name = "R", url = "https://example.com/mcp")
        assertFalse(setting.isLocal)
        assertEquals("https://example.com/mcp", setting.displayEndpoint())
    }

    @Test
    fun clientForRoutesByTransport() {
        val remote = McpServerSetting(id = "r", name = "R", url = "https://example.com/mcp")
        val local = McpServerSetting(
            id = "l", name = "L", url = "", transport = McpTransport.STDIO, command = "/bin/x",
        )
        assertTrue(McpServerManager.clientFor(remote, null) is McpHttpClient)
        McpServerManager.clientFor(local, null).use { client ->
            assertTrue(client is McpStdioClient)
        }
    }

    // ---------- stdio 协议 ----------

    @Test
    fun discoverListsToolsOverStdio() {
        val tools = listOf(
            toolJson("hello", "打招呼"),
            toolJson("add", "加法"),
        )
        val factory = scriptedFactory(tools = tools)
        val setting = localSetting()
        McpStdioClient(setting, factory).use { client ->
            val discovery = client.discoverTools()
            assertEquals(McpProtocolMode.LEGACY, discovery.protocolVersion)
            assertEquals(listOf("hello", "add"), discovery.tools.map { it.name })
            assertEquals("打招呼", discovery.tools.first().description)
        }
    }

    @Test
    fun callToolReturnsRawResult() {
        val factory = scriptedFactory(onCall = { name, args ->
            JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "$name:${args.optInt("x")}")))
        })
        val setting = localSetting()
        val definition = McpToolDefinition("add", inputSchemaJson = "{\"type\":\"object\"}")
        McpStdioClient(setting, factory).use { client ->
            val result = client.callTool(definition, JSONObject().put("x", 2))
            assertEquals("add:2", result.getJSONArray("content").getJSONObject(0).getString("text"))
        }
    }

    @Test
    fun serverInitiatedRequestGetsMethodNotFoundAndFlowContinues() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        val factory = scriptedFactory(
            received = received,
            prelude = listOf(
                "{\"jsonrpc\":\"2.0\",\"id\":9000,\"method\":\"roots/list\",\"params\":{}}",
            ),
        )
        McpStdioClient(localSetting(), factory).use { client ->
            val discovery = client.discoverTools()
            assertEquals(listOf("hello"), discovery.tools.map { it.name })
        }
        val reply = received.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.opt("id")?.toString() == "9000" }
        assertTrue("服务端请求应收到 -32601 拒绝", reply?.optJSONObject("error")?.optInt("code") == -32_601)
    }

    @Test
    fun exitedProcessSurfacesStderrTail() {
        val factory = McpStdioProcessFactory { _, _, _ ->
            FakeProcess(
                stdin = ByteArrayOutputStream(),
                stdout = ByteArrayInputStream(ByteArray(0)),
                stderr = ByteArrayInputStream("boom happened".toByteArray(StandardCharsets.UTF_8)),
            )
        }
        try {
            McpStdioClient(localSetting(), factory).use { it.discoverTools() }
            fail("进程已退出应抛错")
        } catch (failure: Exception) {
            assertTrue("应携带 stderr，实际=${failure.message}", failure.message?.contains("boom happened") == true)
        }
    }

    @Test
    fun executorAdaptsLocalToolResult() {
        val factory = scriptedFactory(onCall = { _, _ ->
            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok")))
        })
        val definition = McpToolDefinition("hello", inputSchemaJson = "{\"type\":\"object\"}")
        val setting = localSetting().copy(
            lastProtocolVersion = McpProtocolMode.LEGACY,
            tools = listOf(definition),
            enabledToolNames = setOf("hello"),
        )
        val executor = McpToolExecutor(
            McpRunSnapshot(listOf(McpRunTool("mcp_local_hello", setting, definition, null))),
        ) { server, _ -> McpStdioClient(server, factory) }
        val result = executor.execute(AgentModelClient.ToolCall("c1", "mcp_local_hello", "{}"))
        executor.close()
        val payload = JSONObject(result.content)
        assertTrue(payload.getBoolean("ok"))
        assertEquals("ok", payload.getJSONArray("content").getString(0))
    }

    @Test
    fun executorReportsLocalToolError() {
        val factory = scriptedFactory(onCall = { _, _ ->
            JSONObject()
                .put("isError", true)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "bad")))
        })
        val definition = McpToolDefinition("hello", inputSchemaJson = "{\"type\":\"object\"}")
        val setting = localSetting().copy(
            lastProtocolVersion = McpProtocolMode.LEGACY,
            tools = listOf(definition),
            enabledToolNames = setOf("hello"),
        )
        val executor = McpToolExecutor(
            McpRunSnapshot(listOf(McpRunTool("mcp_local_hello", setting, definition, null))),
        ) { server, _ -> McpStdioClient(server, factory) }
        val result = executor.execute(AgentModelClient.ToolCall("c1", "mcp_local_hello", "{}"))
        executor.close()
        val payload = JSONObject(result.content)
        assertFalse(payload.getBoolean("ok"))
        assertEquals("MCP_TOOL_ERROR", payload.getString("code"))
    }

    // ---------- 假服务端 ----------

    private fun localSetting() = McpServerSetting(
        id = "local",
        name = "Local",
        url = "",
        transport = McpTransport.STDIO,
        command = "/bin/fake-mcp",
    )

    private fun toolJson(name: String, description: String = "") = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", JSONObject().put("type", "object"))

    private fun scriptedFactory(
        tools: List<JSONObject> = listOf(toolJson("hello")),
        received: MutableList<String>? = null,
        prelude: List<String> = emptyList(),
        onCall: (String, JSONObject) -> JSONObject = { _, _ ->
            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "hi")))
        },
    ): McpStdioProcessFactory = McpStdioProcessFactory { _, _, _ ->
        // 客户端写 -> 服务端读；服务端写 -> 客户端读。
        val clientToServer = PipedOutputStream()
        val serverIn = PipedInputStream(clientToServer, 8_192)
        val serverToClient = PipedOutputStream()
        val clientIn = PipedInputStream(serverToClient, 8_192)
        val proc = FakeProcess(clientToServer, clientIn, ByteArrayInputStream(ByteArray(0)))
        thread(name = "fake-mcp-server", isDaemon = true) {
            val reader = BufferedReader(InputStreamReader(serverIn, StandardCharsets.UTF_8))
            val writer = OutputStreamWriter(serverToClient, StandardCharsets.UTF_8)
            try {
                prelude.forEach { writer.write(it + "\n") }
                writer.flush()
                while (true) {
                    val line = reader.readLine() ?: break
                    received?.add(line)
                    val request = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (!request.has("id")) continue
                    val id = request.opt("id")
                    val response = when (request.optString("method")) {
                        "initialize" -> JSONObject()
                            .put("protocolVersion", McpProtocolMode.LEGACY)
                            .put("capabilities", JSONObject())
                            .let { result -> envelope(id, result) }
                        "tools/list" -> envelope(
                            id,
                            JSONObject()
                                .put("tools", JSONArray(tools))
                                .put("nextCursor", JSONObject.NULL),
                        )
                        "tools/call" -> {
                            val params = request.optJSONObject("params") ?: JSONObject()
                            envelope(id, onCall(params.optString("name"), params.optJSONObject("arguments") ?: JSONObject()))
                        }
                        else -> JSONObject()
                            .put("jsonrpc", "2.0")
                            .put("id", id)
                            .put("error", JSONObject().put("code", -32_601).put("message", "Method not found"))
                    }
                    writer.write(response.toString() + "\n")
                    writer.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { serverToClient.close() }
                runCatching { serverIn.close() }
            }
        }
        proc
    }

    private fun envelope(id: Any?, result: JSONObject) = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)

    private class FakeProcess(
        override val stdin: OutputStream,
        override val stdout: InputStream,
        override val stderr: InputStream,
    ) : McpStdioProcess {
        override val isAlive: Boolean = true
        override fun destroy() {
            runCatching { stdin.close() }
            runCatching { stdout.close() }
            runCatching { stderr.close() }
        }
    }
}
