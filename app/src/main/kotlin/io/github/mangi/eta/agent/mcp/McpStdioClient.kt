package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * stdio 进程抽象：生产实现走 ProcessBuilder，单测注入内存管道。
 */
internal interface McpStdioProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val isAlive: Boolean
    fun destroy()
}

internal fun interface McpStdioProcessFactory {
    fun start(command: List<String>, env: Map<String, String>, workingDir: String): McpStdioProcess
}

internal class DefaultMcpStdioProcessFactory : McpStdioProcessFactory {
    override fun start(command: List<String>, env: Map<String, String>, workingDir: String): McpStdioProcess {
        val builder = ProcessBuilder(command)
        if (workingDir.isNotBlank()) {
            val dir = File(workingDir)
            require(dir.isDirectory) { "工作目录不存在：$workingDir" }
            builder.directory(dir)
        }
        if (env.isNotEmpty()) builder.environment().putAll(env)
        // stderr 独立管道：诊断用，不与 stdout 混流。
        builder.redirectErrorStream(false)
        val process = try {
            builder.start()
        } catch (failure: Exception) {
            throw IOException("本地 MCP 服务启动失败：${failure.message}")
        }
        return JavaMcpStdioProcess(process)
    }

    private class JavaMcpStdioProcess(private val process: Process) : McpStdioProcess {
        override val stdin: OutputStream get() = process.outputStream
        override val stdout: InputStream get() = process.inputStream
        override val stderr: InputStream get() = process.errorStream
        override val isAlive: Boolean get() = process.isAlive
        override fun destroy() {
            runCatching { process.destroy() }
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            if (process.isAlive) runCatching { process.destroyForcibly() }
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}

/**
 * 本地 stdio 传输的 MCP 客户端（MCP 标准行分隔 JSON-RPC）。
 *
 * 进程按需懒启动：discover 或首次工具调用时拉起，[close] 时销毁。
 * 同一实例同一时刻只执行一个请求（ioLock 串行），run 内由
 * [McpToolExecutor] 按服务器复用，run 结束随 executor 关闭。
 * 服务端发往客户端的请求一律回 -32601（当前不支持 roots/sampling），
 * 通知直接忽略，避免服务端阻塞。
 */
internal class McpStdioClient(
    private val server: McpServerSetting,
    private val processFactory: McpStdioProcessFactory = DefaultMcpStdioProcessFactory(),
    private val initTimeoutMs: Long = 15_000,
    private val listTimeoutMs: Long = 15_000,
    private val callTimeoutMs: Long = 120_000,
) : McpServerClient {
    data class Discovery(
        val protocolVersion: String,
        val tools: List<McpToolDefinition>,
        val cacheTtlMs: Long?,
    )

    private val ioLock = Any()
    private val requestIds = AtomicLong(1)
    private var process: McpStdioProcess? = null
    private var writer: OutputStreamWriter? = null
    private var lineQueue: LinkedBlockingQueue<String>? = null
    private val stderrBuffer = StringBuilder()
    private var negotiatedVersion: String? = null
    private var stderrDrained: CountDownLatch? = null
    private var closed = false

    fun discoverTools(): Discovery {
        try {
            return synchronized(ioLock) { discoverLocked() }
        } catch (exited: McpStdioExitedException) {
            throw enrichWithStderr(exited)
        }
    }

    private fun discoverLocked(): Discovery {
        check(!closed) { "MCP 客户端已关闭" }
        ensureStartedLocked()
        ensureInitializedLocked()
        val tools = listToolsLocked()
        return Discovery(requireNotNull(negotiatedVersion), tools, cacheTtlMs = null)
    }

    override fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject {
        try {
            return synchronized(ioLock) { callToolLocked(tool, arguments) }
        } catch (exited: McpStdioExitedException) {
            throw enrichWithStderr(exited)
        }
    }

    private fun callToolLocked(tool: McpToolDefinition, arguments: JSONObject): JSONObject {
        check(!closed) { "MCP 客户端已关闭" }
        ensureStartedLocked()
        ensureInitializedLocked()
        return requestLocked(
            method = "tools/call",
            params = JSONObject().put("name", tool.name).put("arguments", arguments),
            timeoutMs = callTimeoutMs,
        )
    }

    override fun close() {
        val proc: McpStdioProcess?
        val drained: CountDownLatch?
        synchronized(ioLock) {
            if (closed) return
            closed = true
            proc = process
            drained = stderrDrained
            process = null
            writer = null
            lineQueue = null
            negotiatedVersion = null
            stderrDrained = null
        }
        runCatching { proc?.destroy() }
        // 排空线程随流关闭自行退出；此处补计数，避免极端时序下等待者空等。
        runCatching { drained?.countDown() }
    }

    private fun ensureStartedLocked() {
        if (process != null) return
        val command = McpLocalConfig.validateCommand(server.command)
        require(server.args.size <= McpLocalConfig.MAX_ARGS) { "参数超过 ${McpLocalConfig.MAX_ARGS} 个" }
        require(server.env.size <= McpLocalConfig.MAX_ENV_ENTRIES) { "环境变量超过 ${McpLocalConfig.MAX_ENV_ENTRIES} 个" }
        val proc = processFactory.start(listOf(command) + server.args, server.env, server.workingDir)
        val queue = LinkedBlockingQueue<String>()
        lineQueue = queue
        writer = OutputStreamWriter(proc.stdin, StandardCharsets.UTF_8)
        val drained = CountDownLatch(1)
        stderrDrained = drained
        startStdoutPump(proc, queue)
        startStderrPump(proc, drained)
        process = proc
    }

    private fun startStdoutPump(proc: McpStdioProcess, queue: LinkedBlockingQueue<String>) {
        thread(name = "mcp-stdio-reader", isDaemon = true) {
            try {
                val reader = BufferedReader(InputStreamReader(proc.stdout, StandardCharsets.UTF_8))
                while (true) {
                    val line = try {
                        reader.readLine() ?: break
                    } catch (_: Exception) {
                        break
                    }
                    // 队列有界：服务端刷屏时丢弃多余行保内存，请求超时后会如实报错。
                    queue.offer(line)
                }
            } finally {
                queue.offer(EOF_SENTINEL)
            }
        }
    }

    private fun startStderrPump(proc: McpStdioProcess, drained: CountDownLatch) {
        thread(name = "mcp-stdio-stderr", isDaemon = true) {
            try {
                val buffer = ByteArray(4_096)
                while (true) {
                    val read = try {
                        proc.stderr.read(buffer)
                    } catch (_: Exception) {
                        break
                    }
                    if (read < 0) break
                    synchronized(ioLock) {
                        stderrBuffer.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                        if (stderrBuffer.length > MAX_STDERR_CHARS) {
                            stderrBuffer.delete(0, stderrBuffer.length - MAX_STDERR_CHARS)
                        }
                    }
                }
            } catch (_: Exception) {
                Unit
            } finally {
                drained.countDown()
            }
        }
    }

    private fun ensureInitializedLocked() {
        if (negotiatedVersion != null) return
        val result = requestLocked(
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", McpProtocolMode.LEGACY)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "Eta").put("version", "1")),
            timeoutMs = initTimeoutMs,
        )
        val version = result.optString("protocolVersion")
        require(version in SUPPORTED_STDIO_PROTOCOL_VERSIONS) {
            "MCP 本地服务返回了不支持的协议版本"
        }
        negotiatedVersion = version
        // initialized 是通知，无 id、无响应。
        runCatching {
            val notification = JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/initialized")
                .put("params", JSONObject())
            writeLineLocked(notification.toString())
        }
    }

    private fun listToolsLocked(): List<McpToolDefinition> {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val result = requestLocked(method = "tools/list", params = params, timeoutMs = listTimeoutMs)
            val page = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until page.length()) {
                if (tools.size >= MAX_DISCOVERED_TOOLS) break
                page.optJSONObject(index)?.let { tools += parseTool(it) }
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (cursor == null || tools.size >= MAX_DISCOVERED_TOOLS) return tools
        }
        return tools
    }

    private fun parseTool(source: JSONObject): McpToolDefinition {
        val name = source.optString("name").trim()
        val schema = source.optJSONObject("inputSchema")
        val annotations = source.optJSONObject("annotations")
        return McpToolDefinition(
            name = name,
            title = source.optString("title").ifBlank {
                annotations?.optString("title").orEmpty()
            }.take(MAX_TITLE_CHARS),
            description = source.optString("description").take(MAX_DESCRIPTION_CHARS),
            inputSchemaJson = (schema ?: JSONObject()).toString(),
            readOnlyHint = annotations?.nullableBoolean("readOnlyHint"),
            destructiveHint = annotations?.nullableBoolean("destructiveHint"),
            idempotentHint = annotations?.nullableBoolean("idempotentHint"),
            openWorldHint = annotations?.nullableBoolean("openWorldHint"),
        )
    }

    private fun requestLocked(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        val queue = requireNotNull(lineQueue) { "MCP 本地进程未启动" }
        val id = requestIds.getAndIncrement()
        val envelope = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        writeLineLocked(envelope.toString())
        val deadlineNanos = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IOException("MCP 本地调用超时：$method${stderrSuffix()}")
            }
            val line = try {
                queue.poll(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                throw IOException("MCP 本地调用被中断：$method")
            } ?: throw IOException("MCP 本地调用超时：$method${stderrSuffix()}")
            if (line == EOF_SENTINEL) throw McpStdioExitedException(method)
            if (line.length > MAX_MESSAGE_CHARS) {
                destroyLocked()
                throw IOException("MCP 本地输出超过大小限制")
            }
            val message = runCatching { JSONObject(line) }.getOrNull() ?: continue
            // 服务端发往客户端的请求：当前不支持，明确拒绝后继续等本请求的响应。
            if (message.has("method") && message.has("id") &&
                !message.has("result") && !message.has("error")
            ) {
                writeErrorResponseLocked(message.opt("id"), METHOD_NOT_FOUND, "Method not found")
                continue
            }
            if (message.opt("id")?.toString() != id.toString()) continue
            message.optJSONObject("error")?.let { error ->
                throw McpJsonRpcException(
                    code = error.optInt("code"),
                    safeMessage = error.optString("message").take(MAX_ERROR_CHARS),
                )
            }
            return message.optJSONObject("result") ?: throw IOException("MCP 本地响应缺少 result")
        }
    }

    private fun writeLineLocked(line: String) {
        try {
            val w = requireNotNull(writer) { "MCP 本地进程未启动" }
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (failure: Exception) {
            throw IOException("MCP 本地写入失败，可能进程已退出${stderrSuffix()}").apply {
                initCause(failure)
            }
        }
    }

    private fun writeErrorResponseLocked(id: Any?, code: Int, message: String) {
        runCatching {
            val response = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id ?: JSONObject.NULL)
                .put("error", JSONObject().put("code", code).put("message", message))
            writeLineLocked(response.toString())
        }
    }

    private fun destroyLocked() {
        val proc = process
        process = null
        writer = null
        lineQueue = null
        negotiatedVersion = null
        runCatching { proc?.destroy() }
    }

    /**
     * 进程退出错误的 stderr 富化：锁外等 stderr 排空（上限 400ms，仅失败路径），
     * 让崩溃的尾部输出有机会落盘进诊断信息。
     */
    private fun enrichWithStderr(failure: McpStdioExitedException): IOException {
        val drained = synchronized(ioLock) { stderrDrained }
        runCatching { drained?.await(400, TimeUnit.MILLISECONDS) }
        val tail = synchronized(ioLock) { stderrBuffer.toString().takeLast(MAX_STDERR_TAIL) }.trim()
        val suffix = if (tail.isBlank()) "" else "，stderr：$tail"
        return IOException("MCP 本地进程已退出（${failure.method}）$suffix").apply {
            initCause(failure)
        }
    }

    private fun stderrSuffix(): String {
        runCatching { drainStderrAvailableLocked() }
        val tail = synchronized(ioLock) { stderrBuffer.toString().takeLast(MAX_STDERR_TAIL) }.trim()
        return if (tail.isBlank()) "" else "，stderr：$tail"
    }

    /** 非阻塞捡漏：把管道里已到达的 stderr 直接收进诊断缓冲（调用方持有 ioLock）。 */
    private fun drainStderrAvailableLocked() {
        val stream = runCatching { process?.stderr }.getOrNull() ?: return
        while (true) {
            val available = runCatching { stream.available() }.getOrNull() ?: return
            if (available <= 0) return
            val chunk = ByteArray(minOf(available, 4_096))
            val read = runCatching { stream.read(chunk) }.getOrNull() ?: return
            if (read <= 0) return
            stderrBuffer.append(String(chunk, 0, read, StandardCharsets.UTF_8))
            if (stderrBuffer.length > MAX_STDERR_CHARS) {
                stderrBuffer.delete(0, stderrBuffer.length - MAX_STDERR_CHARS)
            }
        }
    }

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null

    private companion object {
        /** stdout 行队列哨兵：读到 EOF/管道断裂时投递；服务端正常 JSON 行不可能恰好等于该取值。 */
        const val EOF_SENTINEL = "MCP_STDIO_EOF_SENTINEL"
        const val METHOD_NOT_FOUND = -32_601
        const val MAX_MESSAGE_CHARS = 1_048_576
        const val MAX_ERROR_CHARS = 200
        const val MAX_TITLE_CHARS = 160
        const val MAX_DESCRIPTION_CHARS = 2_000
        const val MAX_DISCOVERED_TOOLS = 128
        const val MAX_LIST_PAGES = 8
        const val MAX_STDERR_CHARS = 8_192
        const val MAX_STDERR_TAIL = 500
        val SUPPORTED_STDIO_PROTOCOL_VERSIONS = setOf(
            "2024-11-05",
            "2025-03-26",
            "2025-06-18",
            McpProtocolMode.LEGACY,
        )
    }
}

/** 本地进程在某方法等待中途退出的轻量标记；stderr 富化由外层统一完成。 */
internal class McpStdioExitedException(val method: String) : IOException("MCP 本地进程已退出（$method）")
