package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.model.AgentTextDiff
import io.github.mangi.eta.core.AgentLogger

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

internal class RootShellTerminalController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val detachedSupervisor: DetachedTaskSupervisor? = null,
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val selectedLinuxEnvironmentProvider: () -> TerminalEnvironment = {
        TerminalEnvironment.ALPINE
    },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
) : AutoCloseable {
    private companion object {
        // 隔离日志目录 /data/local/tmp/eta 下有 daemon 日志与 mounts 挂载点，直接作为文件扫描默认会污染结果；
        // 文件类工具的空白/相对路径默认改用终端工作区（Eta 私有 workspace），与免 Root 路径一致。
        const val DEFAULT_CWD = "/data/local/tmp/eta"
        const val LINUX_DEFAULT_CWD = "/workspace"
        const val USER_STORAGE = "/storage/emulated/0"
        const val DEFAULT_TIMEOUT_SECONDS = 30
        const val MAX_TIMEOUT_SECONDS = 180
        const val MAX_COMMAND_CHARS = 4_000
        const val MAX_OUTPUT_CHARS = 16_000
        const val MAX_READ_BYTES = 256 * 1024
        const val MAX_WRITE_BYTES = 512 * 1024
        const val MAX_LIST_ENTRIES = 200
        const val MAX_LIST_SCAN = 5_000
        const val MAX_ASYNC_OUTPUT_CHARS = 64_000

        /** daemon_list 的默认/最大条数与命令摘要长度：默认只回 10 条，命令不再整段吐出（实测 P1-1）。 */
        const val DAEMON_LIST_DEFAULT_LIMIT = 10
        const val DAEMON_LIST_MAX_LIMIT = 50
        const val DAEMON_COMMAND_SUMMARY_CHARS = 120

        /** daemon_list 的默认分页偏移：0 表示从排序后的第一条开始（配合 limit 翻页）。 */
        const val DAEMON_LIST_DEFAULT_OFFSET = 0

        /** daemon_list 的 state 过滤取值（空串表示未指定）。 */
        val DAEMON_LIST_STATES = setOf("", "running", "exited", "all")

        /** 命令摘要用：把内联脚本等多行文本折叠成单行再截断。 */
        val WHITESPACE_RUN = Regex("\\s+")

        /**
         * 路径状态退出码：文件类命令用它们把「目录 / 不存在 / 非普通文件」与执行失败区分开，
         * 调用方据此返回精确错误码与恢复建议，而不是笼统的 exit=N。
         */
        const val PATH_EXIT_DIRECTORY = 3
        const val PATH_EXIT_MISSING = 4
        const val PATH_EXIT_NOT_FILE = 5

        /** 缺失路径提示里父目录不存在时的标记（ls 分支输出）。 */
        const val NO_PARENT_MARKER = "__ETA_NO_PARENT__"

        /** 缺失路径提示中列出的父目录条目上限。 */
        const val MISSING_SUGGESTION_ENTRIES = 24

        /**
         * 常驻会话的输出上限（读取线程持续排空、只保留前 N 字节）。
         * 会话命令靠 stdout 里的状态标记判定结束：超出上限时标记会丢失，命令按超时收场而不是打爆内存。
         */
        const val SESSION_STDOUT_LIMIT_BYTES = 8 * 1024 * 1024
        const val SESSION_STDERR_LIMIT_BYTES = 1024 * 1024

        /**
         * 进程级 grep 能力缓存：控制器实例每个 run 重建，缓存若随实例走，
         * 每个 run 的首次 search_code 都要重跑最多 5 次 su 探测。
         * 读写统一用 [GREP_TOOL_CACHE_LOCK] 保护，探测在锁内完成。
         */
        val GREP_TOOL_CACHE_LOCK = Any()

        @Volatile
        var grepToolCache: GrepTool? = null
    }

    private val sessions = linkedMapOf<String, TerminalSession>()
    private val asyncJobs = linkedMapOf<String, AsyncCommand>()
    private val cleanupStarted = AtomicBoolean(false)

    fun runCommand(command: String, cwd: String?, timeoutSeconds: Int): String {
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = defaultIdentity(TerminalEnvironment.ANDROID),
            environment = TerminalEnvironment.ANDROID,
            mergeStderr = false,
            toolName = "run_command"
        )
    }

    fun terminalOpenAndExec(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        environment: String = TerminalEnvironment.ANDROID.wireName,
    ): String {
        val timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        return runCommand(
            command = command,
            cwd = cwd,
            timeoutSeconds = timeoutSeconds,
            identity = identity.ifBlank { defaultIdentity(normalizeEnvironment(environment)) },
            environment = normalizeEnvironment(environment),
            mergeStderr = mergeStderr,
            toolName = "terminal"
        )
    }

    fun terminalAction(
        action: String,
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        mergeStderr: Boolean,
        sessionId: String?,
        jobId: String?,
        async: Boolean,
        offsetChars: Int,
        maxChars: Int,
        closeIfDone: Boolean,
        environment: String = TerminalEnvironment.ANDROID.wireName,
        taskId: String? = null,
        daemonLimit: Int = DAEMON_LIST_DEFAULT_LIMIT,
        daemonRunningOnly: Boolean = false,
        daemonState: String? = null,
        daemonOffset: Int = DAEMON_LIST_DEFAULT_OFFSET,
    ): String {
        return when (action.lowercase()) {
            "open" -> openSession(identity = identity, cwd = cwd, environment = environment)
            "exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                environment = environment,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "open_and_exec" -> execInTerminal(
                command = command,
                cwd = cwd,
                timeoutMs = timeoutMs,
                identity = identity,
                environment = environment,
                mergeStderr = mergeStderr,
                sessionId = sessionId,
                async = async
            )
            "read_async_result" -> readAsyncResult(
                jobId = jobId.orEmpty(),
                offsetChars = offsetChars,
                maxChars = maxChars,
                closeIfDone = closeIfDone
            )
            "close" -> closeTerminal(sessionId = sessionId, jobId = jobId)
            "daemon_start" -> daemonStart(
                command = command,
                cwd = cwd,
                identity = identity,
                environment = environment,
            )
            "daemon_list" -> daemonList(
                limit = daemonLimit,
                runningOnly = daemonRunningOnly,
                state = daemonState,
                offset = daemonOffset,
            )
            "daemon_logs" -> daemonLogs(taskId = taskId.orEmpty())
            "daemon_stop" -> daemonStop(taskId = taskId.orEmpty())
            else -> errorJson(
                "UNSUPPORTED_TERMINAL_ACTION",
                "terminal action 仅支持 open/exec/open_and_exec/read_async_result/close/daemon_start/daemon_list/daemon_logs/daemon_stop"
            )
        }
    }

    private fun openSession(identity: String, cwd: String?, environment: String): String {
        val normalizedEnvironment = normalizeEnvironment(environment)
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { defaultIdentity(normalizedEnvironment) })
        val sessionRootfs = rootfsPathFor(normalizedEnvironment)
        environmentPreflight(normalizedIdentity, normalizedEnvironment, sessionRootfs)?.let { return it }
        val safeCwd = normalizeCwd(cwd, normalizedEnvironment, normalizedIdentity)
        val id = "term_" + UUID.randomUUID().toString().take(8)
        val process = startSessionProcess(normalizedIdentity, normalizedEnvironment, sessionRootfs)
            ?: return errorJson(
                "PROCESS_START_FAILED",
                "无法启动 ${normalizedEnvironment.wireName}/$normalizedIdentity terminal session",
            )
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val session = TerminalSession(
            id = id,
            identity = normalizedIdentity,
            environment = normalizedEnvironment,
            rootfsPath = sessionRootfs,
            cwd = safeCwd,
            createdAt = System.currentTimeMillis(),
            process = process,
            stdout = stdout,
            stderr = stderr
        )
        session.stdoutThread = thread(name = "agent-terminal-session-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input, SESSION_STDOUT_LIMIT_BYTES) }
        }
        session.stderrThread = thread(name = "agent-terminal-session-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input, SESSION_STDERR_LIMIT_BYTES) }
        }
        session.waiterThread = thread(name = "agent-terminal-session-waiter-$id", isDaemon = true) {
            runCatching { process.waitFor() }
            processSupervisor.retireExitedProcess(process)
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(sessions) { sessions[id] = session }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }

        val mkdirDefault = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val setup = "${mkdirDefault}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1"
        val setupResult = runSessionCommand(session, setup, timeoutMs = 5_000)
        if (setupResult.exitCode != 0 || setupResult.timedOut) {
            closeSession(id)
            return errorJson("SESSION_OPEN_FAILED", setupResult.stderr.ifBlank { "exit=${setupResult.exitCode}" })
        }
        session.cwd = setupResult.cwd ?: safeCwd
        session.stdout.clear()
        session.stderr.clear()
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open")
            .put("session_id", id)
            .put("identity", normalizedIdentity)
            .put("environment", normalizedEnvironment.wireName)
            .put("cwd", session.cwd)
            .toString()
    }

    private fun execInTerminal(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: String,
        mergeStderr: Boolean,
        sessionId: String?,
        async: Boolean
    ): String {
        val session = sessionId?.takeIf { it.isNotBlank() }?.let { id ->
            synchronized(sessions) { sessions[id] }
                ?: return errorJson("SESSION_NOT_FOUND", "未找到 terminal session：$id")
        }
        val effectiveEnvironment = session?.environment ?: normalizeEnvironment(environment)
        val effectiveIdentity = session?.identity ?: normalizeIdentity(identity.ifBlank { defaultIdentity(effectiveEnvironment) })
        environmentPreflight(effectiveIdentity, effectiveEnvironment, session?.rootfsPath ?: rootfsPathFor(effectiveEnvironment))?.let { return it }
        val effectiveCwd = cwd?.takeIf { it.isNotBlank() } ?: session?.cwd
        if (async) {
            if (session != null) {
                return errorJson(
                    "ASYNC_SESSION_UNSUPPORTED",
                    "async terminal job 不复用持久 session；请省略 session_id，并用 cwd/identity 启动后台命令"
                )
            }
            return startAsyncCommand(
                command = command,
                cwd = effectiveCwd,
                timeoutMs = timeoutMs,
                identity = effectiveIdentity,
                environment = effectiveEnvironment,
                mergeStderr = mergeStderr,
                sessionId = session?.id
            )
        }
        if (session != null) {
            return execInSession(
                session = session,
                command = command,
                timeoutMs = timeoutMs,
                mergeStderr = mergeStderr
            )
        }
        val result = runCommand(
            command = command,
            cwd = effectiveCwd,
            timeoutSeconds = ((timeoutMs.coerceIn(1, MAX_TIMEOUT_SECONDS * 1000) + 999) / 1000)
                .coerceIn(1, MAX_TIMEOUT_SECONDS),
            identity = effectiveIdentity,
            environment = effectiveEnvironment,
            mergeStderr = mergeStderr,
            toolName = "terminal"
        )
        return result
    }

    private fun startAsyncCommand(
        command: String,
        cwd: String?,
        timeoutMs: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
        sessionId: String?
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, environment, normalizedIdentity)
        val setup = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val process = processSupervisor.startShellProcess(
            identity = normalizedIdentity,
            command = fullCommand,
            mergeStderr = mergeStderr,
            environment = environment,
            linuxRootfsPath = rootfsPathFor(environment),
            linuxSharedMounts = sharedMountsFor(environment),
        ) ?: return errorJson(
            if (processSupervisor.isClosing) "TERMINAL_CLOSED" else "PROCESS_START_FAILED",
            if (processSupervisor.isClosing) "terminal controller 已关闭" else "无法启动 terminal process",
        )
        val id = "job_" + UUID.randomUUID().toString().take(8)
        val stdout = ByteArrayOutputCollector()
        val stderr = ByteArrayOutputCollector()
        val job = AsyncCommand(
            id = id,
            process = process,
            stdout = stdout,
            stderr = stderr,
            command = trimmed,
            cwd = safeCwd,
            identity = normalizedIdentity,
            environment = environment,
            mergeStderr = mergeStderr,
            sessionId = sessionId,
            startedAt = System.currentTimeMillis(),
            timeoutMs = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        )
        job.stdoutThread = thread(name = "agent-terminal-async-stdout-$id", isDaemon = true) {
            process.inputStream.use { input -> stdout.readFrom(input, MAX_ASYNC_OUTPUT_CHARS) }
        }
        job.stderrThread = thread(name = "agent-terminal-async-stderr-$id", isDaemon = true) {
            process.errorStream.use { input -> stderr.readFrom(input, MAX_ASYNC_OUTPUT_CHARS) }
        }
        job.waiterThread = thread(name = "agent-terminal-async-waiter-$id", isDaemon = true) {
            try {
                val finished = process.waitFor(job.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                if (!finished) {
                    job.timedOut = true
                    processSupervisor.terminateProcessTree(process)
                }
                job.exitCode = runCatching { process.exitValue() }.getOrDefault(-2)
                job.completedAt = System.currentTimeMillis()
            } finally {
                processSupervisor.retireExitedProcess(process)
            }
        }
        if (!processSupervisor.transferActiveProcess(process) {
                synchronized(asyncJobs) { asyncJobs[id] = job }
            }
        ) {
            processSupervisor.terminateProcessTree(process)
            return errorJson("TERMINAL_CLOSED", "terminal controller 已关闭")
        }
        logger.info(
            "Agent terminal action=open_and_exec outcome=started async=true " +
                "identity=$normalizedIdentity environment=${environment.wireName} " +
                "timeoutMs=${job.timeoutMs} commandChars=${trimmed.length}"
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "open_and_exec")
            .put("async", true)
            .put("job_id", id)
            .put("session_id", sessionId ?: JSONObject.NULL)
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("running", true)
            .toString()
    }

    private fun readAsyncResult(
        jobId: String,
        offsetChars: Int,
        maxChars: Int,
        closeIfDone: Boolean
    ): String {
        val job = synchronized(asyncJobs) { asyncJobs[jobId] }
            ?: return errorJson("JOB_NOT_FOUND", "未找到 async terminal job：$jobId")
        if (job.identity == "root" && !rootAvailable()) return errorJson("ROOT_REQUIRED", "Root 授权不可用")
        val stdoutRaw = job.stdout.text()
        val stderrRaw = job.stderr.text()
        val merged = stdoutRaw
        val offset = offsetChars.coerceAtLeast(0).coerceAtMost(merged.length)
        val limit = maxChars.coerceIn(1, MAX_OUTPUT_CHARS)
        val slice = merged.substring(offset, (offset + limit).coerceAtMost(merged.length))
        val done = job.exitCode != null
        if (done && closeIfDone) {
            synchronized(asyncJobs) { asyncJobs.remove(jobId) }?.let(::closeJob)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "read_async_result")
            .put("job_id", job.id)
            .put("session_id", job.sessionId ?: JSONObject.NULL)
            .put("environment", job.environment.wireName)
            .put("running", !done)
            .put("exit_code", job.exitCode ?: JSONObject.NULL)
            .put("timed_out", job.timedOut)
            .put("stdout", slice)
            .put("next_offset_chars", offset + slice.length)
            .put("total_chars", merged.length)
            .put("retained_chars", merged.length)
            .put("stdout_total_bytes", job.stdout.totalBytesRead())
            .put("stderr_total_bytes", job.stderr.totalBytesRead())
            .put("truncated", offset + slice.length < merged.length)
            .put("output_truncated", job.stdout.isTruncated() || job.stderr.isTruncated())
            .put("stderr", if (job.mergeStderr) "" else stderrRaw.truncateForJson())
            .put("stdout_truncated", job.stdout.isTruncated())
            .put("stderr_truncated", !job.mergeStderr && job.stderr.isTruncated())
            .toString()
    }

    private fun daemonStart(
        command: String,
        cwd: String?,
        identity: String,
        environment: String,
    ): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedEnvironment = normalizeEnvironment(environment)
        val normalizedIdentity = normalizeIdentity(identity.ifBlank { defaultIdentity(normalizedEnvironment) })
        environmentPreflight(normalizedIdentity, normalizedEnvironment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, normalizedEnvironment, normalizedIdentity)
        return when (val result = supervisor.start(trimmed, safeCwd, normalizedIdentity, normalizedEnvironment)) {
            is DaemonStartResult.Started -> JSONObject()
                .put("ok", true)
                .put("tool", "terminal")
                .put("action", "daemon_start")
                .put("task_id", result.task.id)
                .put("pid", result.task.pid)
                .put("identity", result.task.identity)
                .put("environment", result.task.environment.wireName)
                .put("cwd", result.task.cwd)
                .toString()
            is DaemonStartResult.Failed -> errorJson(result.code, result.message)
        }
    }

    /**
     * 守护任务列表：默认只回 10 条（上限 50），running 排前；命令文本改为单行截断摘要，
     * 不再把 keepalive 之类的内联脚本整段吐出（实测 P1-1）。已退出的陈旧记录显式计入
     * stale_count 并提示用 daemon_stop 清理，不静默吞。
     *
     * 支持 offset 分页（跳过排序后的前 offset 条，越界时返回空页），返回体补 offset/next_offset/has_more；
     * offset=0 时字段与旧版一致，hidden/truncated 仍表示“本次未显示的条数”。
     */
    private fun daemonList(limit: Int, runningOnly: Boolean, state: String?, offset: Int): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        val normalizedState = state?.trim()?.lowercase().orEmpty()
        if (normalizedState !in DAEMON_LIST_STATES) {
            return errorJson("INVALID_ARGUMENT", "state 仅支持 running/exited/all")
        }
        val statuses = supervisor.list()
        val runningCount = statuses.count { it.running }
        // state 显式指定时优先；未指定再看 running_only 便捷开关。
        val runningFilter = when {
            normalizedState == "running" -> true
            normalizedState == "exited" -> false
            runningOnly -> true
            else -> null
        }
        val matched = statuses
            .filter { runningFilter == null || it.running == runningFilter }
            .sortedWith(compareByDescending<DetachedTaskStatus> { it.running }.thenByDescending { it.task.startedAt })
        val max = limit.coerceIn(1, DAEMON_LIST_MAX_LIMIT)
        val skip = offset.coerceAtLeast(0)
        val page = matched.drop(skip).take(max)
        val hasMore = skip + page.size < matched.size
        val tasks = JSONArray()
        page.forEach { status ->
            val summary = daemonCommandSummary(status.task.command)
            tasks.put(
                JSONObject()
                    .put("task_id", status.task.id)
                    .put("pid", status.task.pid)
                    .put("running", status.running)
                    .put("command", summary.text)
                    .put("command_chars", status.task.command.length)
                    .put("command_truncated", summary.truncated)
                    .put("cwd", status.task.cwd)
                    .put("identity", status.task.identity)
                    .put("environment", status.task.environment.wireName)
                    .put("started_at", status.task.startedAt)
            )
        }
        val exitedCount = statuses.size - runningCount
        val hiddenCount = matched.size - page.size
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_list")
            .put("total", statuses.size)
            .put("running", runningCount)
            .put("exited", exitedCount)
            .put("shown", page.size)
            .put("matched", matched.size)
            .put("hidden", hiddenCount)
            .put("limit", max)
            .put("offset", skip)
            .put("next_offset", if (hasMore) skip + page.size else JSONObject.NULL)
            .put("has_more", hasMore)
            .put("filter", when (runningFilter) { true -> "running"; false -> "exited"; null -> "all" })
            .put("truncated", hiddenCount > 0)
            .put("stale_count", exitedCount)
            .put("stale_visible_count", page.count { !it.running })
            .put("tasks", tasks)
            .put(
                "message",
                buildString {
                    append("共 ").append(statuses.size).append(" 个守护任务，运行中 ").append(runningCount).append(" 个")
                    append("；本次显示 ").append(page.size).append(" 个")
                    if (hasMore) {
                        append("（已从 offset=").append(skip).append(" 开始，后面还有 ")
                            .append(matched.size - skip - page.size)
                            .append(" 个未显示，可用 offset=").append(skip + page.size).append(" 翻页或提高 limit）")
                    } else if (hiddenCount > 0) {
                        append("（还有 ").append(hiddenCount).append(" 个未显示，可提高 limit 或调整 offset=").append(skip).append("）")
                    }
                    append("。")
                    if (exitedCount > 0) {
                        append("有 ").append(exitedCount).append(" 个已退出的陈旧记录，可用 daemon_stop task_id=<id> 清理记录。")
                    }
                },
            )
            .toString()
    }

    /** 命令摘要：多行折叠为单行后截断（≤120 字符加省略号），避免脚本原文随列表泄漏观感。 */
    private fun daemonCommandSummary(command: String): CommandSummary {
        val singleLine = command.replace(WHITESPACE_RUN, " ").trim()
        return if (singleLine.length <= DAEMON_COMMAND_SUMMARY_CHARS) {
            CommandSummary(singleLine, false)
        } else {
            CommandSummary(singleLine.take(DAEMON_COMMAND_SUMMARY_CHARS) + "…", true)
        }
    }

    private data class CommandSummary(val text: String, val truncated: Boolean)

    private fun daemonLogs(taskId: String): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        if (taskId.isBlank()) return errorJson("INVALID_ARGUMENT", "task_id 不能为空")
        val result = supervisor.readLogs(taskId)
        if (!result.ok) {
            return errorJson(result.code.ifBlank { "LOGS_UNAVAILABLE" }, result.message)
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_logs")
            .put("task_id", taskId)
            .put("log", result.text.truncateForJson())
            .put("log_truncated", result.truncated || result.text.length > MAX_OUTPUT_CHARS)
            .toString()
    }

    private fun daemonStop(taskId: String): String {
        val supervisor = detachedSupervisor
            ?: return errorJson("DAEMON_UNAVAILABLE", "守护任务宿主不可用")
        if (taskId.isBlank()) return errorJson("INVALID_ARGUMENT", "task_id 不能为空")
        val task = supervisor.findTask(taskId) ?: return errorJson("TASK_NOT_FOUND", "未找到守护任务：$taskId")
        if (task.identity == "root" && !rootAvailable()) return errorJson("ROOT_REQUIRED", "Root 授权不可用")
        if (!supervisor.stop(taskId)) {
            return errorJson("DAEMON_STOP_FAILED", "守护任务停止失败，请重试")
        }
        return JSONObject()
            .put("ok", true)
            .put("tool", "terminal")
            .put("action", "daemon_stop")
            .put("task_id", taskId)
            .toString()
    }

    private fun closeTerminal(sessionId: String?, jobId: String?): String {
        var closedSession = false
        var closedJob = false
        sessionId?.takeIf { it.isNotBlank() }?.let { id ->
            closedSession = closeSession(id)
        }
        jobId?.takeIf { it.isNotBlank() }?.let { id ->
            closedJob = closeJob(id)
        }
        return JSONObject()
            .put("ok", closedSession || closedJob)
            .put("tool", "terminal")
            .put("action", "close")
            .put("closed_session", closedSession)
            .put("closed_job", closedJob)
            .toString()
    }

    override fun close() {
        closeAll()
    }

    /** 取消热路径只封闭新进程接纳；进程树终止和 reader/waiter 回收在后台完成。 */
    fun interruptAll() {
        beginClosing()
        if (cleanupStarted.compareAndSet(false, true)) {
            thread(name = "agent-terminal-cleanup", isDaemon = true) {
                closeAllInternal()
            }
        }
    }

    fun closeAll() {
        beginClosing()
        cleanupStarted.set(true)
        closeAllInternal()
    }

    private fun beginClosing() {
        processSupervisor.beginClosing()
        synchronized(sessions) {
            sessions.values.forEach { session -> session.closed = true }
        }
    }

    private fun closeAllInternal() {
        val sessionIds = synchronized(sessions) { sessions.keys.toList() }
        sessionIds.forEach(::closeSession)

        val jobs = synchronized(asyncJobs) {
            asyncJobs.values.toList().also { asyncJobs.clear() }
        }
        jobs.forEach(::closeJob)

        val remainingProcesses = processSupervisor.takeRemainingProcesses()
        remainingProcesses.forEach { process ->
            processSupervisor.terminateAndReap(process)
            processSupervisor.unregisterProcess(process)
        }
    }

    private fun closeSession(id: String): Boolean {
        val session = synchronized(sessions) { sessions.remove(id) } ?: return false
        session.closed = true
        runCatching { session.process.outputStream.close() }
        processSupervisor.terminateAndReap(session.process)
        runCatching { session.stdoutThread.join(500) }
        runCatching { session.stderrThread.join(500) }
        runCatching { session.waiterThread.join(500) }
        processSupervisor.unregisterProcess(session.process)
        return true
    }

    private fun closeJob(id: String): Boolean {
        val job = synchronized(asyncJobs) { asyncJobs.remove(id) } ?: return false
        closeJob(job)
        return true
    }

    private fun closeJob(job: AsyncCommand) {
        processSupervisor.terminateAndReap(job.process)
        runCatching { job.stdoutThread.join(500) }
        runCatching { job.stderrThread.join(500) }
        runCatching { job.waiterThread.join(500) }
        processSupervisor.unregisterProcess(job.process)
    }

    private fun execInSession(
        session: TerminalSession,
        command: String,
        timeoutMs: Int,
        mergeStderr: Boolean
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val timeout = timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
        val result = runSessionCommand(session, trimmed, timeout)
        val outcome = when {
            result.timedOut -> "timed_out"
            result.exitCode == 0 -> "succeeded"
            else -> "failed"
        }
        val logMessage =
            "Agent terminal action=exec outcome=$outcome session=true " +
                "identity=${session.identity} environment=${session.environment.wireName} " +
                "timeoutMs=$timeout commandChars=${trimmed.length} " +
                "exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        if (result.cwd != null) session.cwd = result.cwd
        if (result.timedOut) {
            closeSession(session.id)
        }
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            result.stdout + "\n[stderr]\n" + result.stderr
        } else {
            result.stdout
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", "terminal")
            .put("action", "exec")
            .put("session_id", session.id)
            .put("identity", session.identity)
            .put("environment", session.environment.wireName)
            .put("cwd", session.cwd)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.timedOut)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", rawStdout.length > stdout.length)
            .put("stderr_truncated", !mergeStderr && result.stderr.length > stderr.length)
            .put("session_closed", result.timedOut || session.closed)
            .toString()
    }

    private fun runSessionCommand(
        session: TerminalSession,
        command: String,
        timeoutMs: Int
    ): SessionCommandResult {
        synchronized(session.lock) {
            if (session.closed || !session.process.isAlive) {
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "terminal session 已关闭",
                    cwd = session.cwd,
                    timedOut = false
                )
            }
            val marker = SessionStatusProtocol.newMarker()
            val stdoutStart = session.stdout.text().length
            val stderrStart = session.stderr.text().length
            val commandBlock = buildString {
                append(command)
                append('\n')
                append(SessionStatusProtocol.statusCommand(marker))
                append('\n')
            }
            runCatching {
                session.process.outputStream.write(commandBlock.toByteArray(Charsets.UTF_8))
                session.process.outputStream.flush()
            }.getOrElse {
                session.closed = true
                return SessionCommandResult(
                    exitCode = -1,
                    stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                    stderr = it.message ?: it.javaClass.simpleName,
                    cwd = session.cwd,
                    timedOut = false
                )
            }

            val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000, MAX_TIMEOUT_SECONDS * 1000)
            while (System.currentTimeMillis() < deadline) {
                val stdoutDelta = session.stdout.text().drop(stdoutStart)
                if (session.closed || !session.process.isAlive) {
                    return SessionCommandResult(
                        exitCode = -1,
                        stdout = stdoutDelta.trimEnd(),
                        stderr = session.stderr.text().drop(stderrStart).ifBlank { "terminal session 已关闭" }.trimEnd(),
                        cwd = session.cwd,
                        timedOut = false
                    )
                }
                val status = stdoutDelta.lineSequence()
                    .firstOrNull { SessionStatusProtocol.isStatusLine(it, marker) }
                    ?.let { SessionStatusProtocol.parseStatusLine(it, marker) }
                if (status != null) {
                    val exitCode = status.exitCode
                    val cwd = status.cwd ?: session.cwd
                    val cleanedStdout = stdoutDelta
                        .lineSequence()
                        .filterNot { SessionStatusProtocol.isStatusLine(it, marker) }
                        .joinToString("\n")
                        .trimEnd()
                    val stderrDelta = session.stderr.text().drop(stderrStart).trimEnd()
                    session.stdout.clear()
                    session.stderr.clear()
                    return SessionCommandResult(
                        exitCode = exitCode,
                        stdout = cleanedStdout,
                        stderr = stderrDelta,
                        cwd = cwd,
                        timedOut = false
                    )
                }
                Thread.sleep(50)
            }

            session.closed = true
            processSupervisor.terminateProcessTree(session.process)
            return SessionCommandResult(
                exitCode = -2,
                stdout = session.stdout.text().drop(stdoutStart).trimEnd(),
                stderr = session.stderr.text().drop(stderrStart).ifBlank { "命令执行超时" }.trimEnd(),
                cwd = session.cwd,
                timedOut = true
            )
        }
    }

    private fun runCommand(
        command: String,
        cwd: String?,
        timeoutSeconds: Int,
        identity: String,
        environment: TerminalEnvironment,
        mergeStderr: Boolean,
        toolName: String
    ): String {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return errorJson("INVALID_ARGUMENT", "command 不能为空")
        require(trimmed.length <= MAX_COMMAND_CHARS) { "command 过长：${trimmed.length}" }
        val normalizedIdentity = normalizeIdentity(identity)
        environmentPreflight(normalizedIdentity, environment)?.let { return it }
        val safeCwd = normalizeCwd(cwd, environment, normalizedIdentity)
        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val setup = if (safeCwd == TerminalRuntime.workspace(normalizedIdentity)) "mkdir -p ${shellQuote(safeCwd)} && " else ""
        val fullCommand = "${setup}cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1 && $trimmed"
        val result = runText(
            identity = normalizedIdentity,
            command = fullCommand,
            timeoutSeconds = timeout.toLong(),
            environment = environment,
        )
        val outcome = when (result.exitCode) {
            0 -> "succeeded"
            -2 -> "timed_out"
            else -> "failed"
        }
        val action = if (toolName == "terminal") "open_and_exec" else "run_command"
        val logMessage =
            "Agent terminal action=$action outcome=$outcome identity=$normalizedIdentity " +
                "environment=${environment.wireName} " +
                "timeoutSeconds=$timeout commandChars=${trimmed.length} exitCode=${result.exitCode}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        val rawStdout = if (mergeStderr && result.stderr.isNotBlank()) {
            result.output + "\n[stderr]\n" + result.stderr
        } else {
            result.output
        }
        val stdout = rawStdout.truncateForJson()
        val stderr = if (mergeStderr) "" else result.stderr.truncateForJson()
        return JSONObject()
            .put("ok", result.exitCode == 0)
            .put("tool", toolName)
            .put("action", if (toolName == "terminal") "open_and_exec" else JSONObject.NULL)
            .put("identity", normalizedIdentity)
            .put("environment", environment.wireName)
            .put("cwd", safeCwd)
            .put("exit_code", result.exitCode)
            .put("timed_out", result.exitCode == -2)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .put("stdout_truncated", rawStdout.length > stdout.length)
            .put("stderr_truncated", !mergeStderr && result.stderr.length > stderr.length)
            .toString()
    }

    fun readFile(path: String, offsetBytes: Int, maxBytes: Int): String {
        if (!rootAvailable()) return UserFileAccess.read(path, offsetBytes, maxBytes)
        val safePath = normalizePath(path)
        val offset = offsetBytes.coerceAtLeast(0)
        val limit = maxBytes.coerceIn(1, MAX_READ_BYTES)
        // 性能：用 tail/head 大块读取替代 dd bs=1；管道退出码来自 head。
        // 目录/不存在/非普通文件分别用退出码 3/4/5 区分：模型拿不到具体原因时
        // 只能盲目重试同一路径，失败率居高不下；这里直接给出可修正的结论。
        val quoted = shellQuote(safePath)
        val command = "if [ -d $quoted ]; then exit $PATH_EXIT_DIRECTORY; " +
            "elif [ ! -e $quoted ]; then exit $PATH_EXIT_MISSING; " +
            "elif [ ! -f $quoted ]; then exit $PATH_EXIT_NOT_FILE; " +
            "else tail -c +${offset + 1} $quoted | head -c $limit; fi"
        val result = runSuBytes(command, timeoutSeconds = 20)
        pathStatusFailure(result.exitCode, safePath, "read_file")?.let { return it }
        val readFailed = result.exitCode != 0 || (result.output.isEmpty() && result.stderr.isNotBlank())
        if (readFailed) {
            logger.warn(
                "Agent terminal action=read_file outcome=failed offsetBytes=$offset " +
                    "maxBytes=$limit exitCode=${result.exitCode} errorChars=${result.stderr.length} " +
                    "error=${stderrExcerpt(result.stderr)}"
            )
            return errorJson("READ_FAILED", result.stderr.ifBlank { "exit=${result.exitCode}" })
        }
        logger.info(
            "Agent terminal action=read_file outcome=succeeded offsetBytes=$offset " +
                "maxBytes=$limit bytesRead=${result.output.size} exitCode=${result.exitCode}"
        )
        val text = result.output.decodeToString()
        // truncated 统一口径：读满 limit（可能还有后续）或内容被 16000 字符上限截断，都视为用户可见内容被截断。
        val truncated = result.output.size >= limit || text.length > MAX_OUTPUT_CHARS
        return JSONObject()
            .put("ok", true)
            .put("tool", "read_file")
            .put("path", safePath)
            .put("offset_bytes", offset)
            .put("bytes_read", result.output.size)
            .put("truncated", truncated)
            .put("content", text.truncateForJson())
            .toString()
    }

    fun writeFile(path: String, content: String, append: Boolean): String {
        if (!rootAvailable()) return UserFileAccess.write(path, content, append)
        val safePath = normalizePath(path)
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            return errorJson("FILE_TOO_LARGE", "写入内容过大（${bytes.size} 字节，上限 $MAX_WRITE_BYTES）")
        }
        // 覆盖已存在的文本文件前先读取旧内容，供 UI 展示具体的变更摘要。
        // 超过 MAX_WRITE_BYTES 的文件按截断处理并跳过差异计算。
        val previousBytes = if (!append) {
            val previousRead = runSuBytes(
                "dd if=${shellQuote(safePath)} bs=65536 count=${MAX_WRITE_BYTES / 65_536} 2>/dev/null",
                timeoutSeconds = 20,
            )
            if (previousRead.exitCode == 0 && previousRead.output.size < MAX_WRITE_BYTES) {
                previousRead.output
            } else {
                null
            }
        } else {
            null
        }
        val diff = previousBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { previous ->
                runCatching {
                    AgentTextDiff.summarize(previous.decodeToString(), content)
                }.getOrNull()
            }
        val parent = shellQuote(File(safePath).parent ?: "/")
        val quotedPath = shellQuote(safePath)
        // 目标是目录时给出 IS_DIRECTORY，而不是让 mv/cat 的原始报错透给模型。
        val command = if (append) {
            "[ -d $quotedPath ] && exit $PATH_EXIT_DIRECTORY; mkdir -p $parent && cat >> $quotedPath"
        } else {
            // 临时文件名带随机后缀，避免并发写入相互覆盖；失败时清理残留临时文件。
            val temp = shellQuote("$safePath.eta-write-tmp-${UUID.randomUUID().toString().take(8)}")
            "[ -d $quotedPath ] && exit $PATH_EXIT_DIRECTORY; " +
                "mkdir -p $parent && cat > $temp && mv -f $temp $quotedPath || { rm -f $temp; false; }"
        }
        val result = runSuTextWithStdin(command, bytes, timeoutSeconds = 20)
        pathStatusFailure(result.exitCode, safePath, "write_file")?.let { return it }
        return if (result.exitCode == 0) {
            logger.info(
                "Agent terminal action=write_file outcome=succeeded append=$append " +
                    "bytesWritten=${bytes.size} exitCode=${result.exitCode}"
            )
            JSONObject()
                .put("ok", true)
                .put("tool", "write_file")
                .put("path", safePath)
                .put("mode", if (append) "append" else "overwrite")
                .put("bytes_written", bytes.size)
                .also { json ->
                    if (previousBytes != null) json.put("previous_bytes", previousBytes.size)
                    if (!diff.isNullOrEmpty()) json.put("diff", diff)
                }
                .toString()
        } else {
            logger.warn(
                "Agent terminal action=write_file outcome=failed append=$append " +
                    "inputBytes=${bytes.size} exitCode=${result.exitCode} " +
                    "outputChars=${result.output.length} errorChars=${result.stderr.length}"
            )
            errorJson("WRITE_FAILED", result.stderr.ifBlank { result.output.ifBlank { "exit=${result.exitCode}" } })
        }
    }

    fun listDirectory(
        path: String,
        showHidden: Boolean,
        limit: Int,
        offset: Int = 0,
        glob: String = "",
        recursive: Boolean = false,
    ): String {
        if (!rootAvailable()) return UserFileAccess.list(path, showHidden, limit, offset, glob, recursive)
        // `/workspace/mounts` 在 Android 命名空间不是真实目录（各子目录是独立 bind 源），
        // 这里合成枚举视图：让主代理与子代理先发现挂载、再进入 /workspace/mounts/<name>/...
        if (path.trim().trimEnd('/') == AgentFilePathMapper.LINUX_MOUNTS_ROOT) {
            return mountsListingJson(linuxMountPairs(), limit, offset)
        }
        val safePath = normalizePath(path.ifBlank { defaultScanRoot() })
        val maxEntries = limit.coerceIn(1, MAX_LIST_ENTRIES)
        val skip = offset.coerceAtLeast(0)
        // Shell 只负责“全部列出+排序”，过滤/翻页/截断标记统一在 Kotlin 侧处理，
        // 与免 Root 实现保持同一口径；find 天然包含隐藏文件（含 . 开头），不含 . 和 ..。
        val depth = if (recursive) "" else "-maxdepth 1 "
        // 递归时剪掉依赖与构建目录：否则 workspace 下一次 list 就扫几万文件，排序截断全挤在 5000 行里，翻页越翻越慢。
        val prune = if (recursive) {
            "'(' -name .git -o -name node_modules -o -name build -o -name .gradle -o -name .idea ')' -prune -o "
        } else {
            ""
        }
        val command = "[ -d ${shellQuote(safePath)} ] || exit $PATH_EXIT_DIRECTORY; " +
            "cd ${shellQuote(safePath)} && find . -mindepth 1 ${depth}${prune}-print 2>/dev/null" +
            " | sort | head -n $MAX_LIST_SCAN" +
            " | while IFS= read -r n; do name=\"\${n#./}\";" +
            " if [ -d \"\$n\" ]; then printf 'd %s\\n' \"\$name\";" +
            " else printf -- '- %s\\n' \"\$name\"; fi; done"
        val result = runSuText(command, timeoutSeconds = 15)
        val logMessage =
            "Agent terminal action=list_directory " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "showHidden=$showHidden limit=$maxEntries offset=$skip recursive=$recursive " +
                "exitCode=${result.exitCode} " +
                "outputChars=${result.output.length} errorChars=${result.stderr.length}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn("$logMessage error=${stderrExcerpt(result.stderr)}")
        }
        if (result.exitCode != 0) {
            // 目录不存在（退出码 3）与执行失败区分开：前者给父目录建议，后者透出 stderr。
            val missing = result.exitCode == PATH_EXIT_DIRECTORY
            return JSONObject()
                .put("ok", false)
                .put("tool", "list_directory")
                .put("path", safePath)
                .put("exit_code", result.exitCode)
                .put("code", if (missing) "MISSING_DIRECTORY" else "LIST_FAILED")
                .put(
                    "message",
                    if (missing) {
                        "目录不存在或不是目录：$safePath" + missingPathSuggestion(safePath)
                    } else {
                        result.stderr.ifBlank { "exit=${result.exitCode}" }
                    },
                )
                .put("entries_text", "")
                .put("stderr", result.stderr.truncateForJson())
                .toString()
        }
        val globs = AgentCodeSearch.compileGlobs(glob)
        val filtered = result.output.lineSequence()
            .filter { it.isNotBlank() }
            .filter { line ->
                val name = if (line.length > 2) line.substring(2) else ""
                val base = name.substringAfterLast('/')
                (showHidden || !base.startsWith('.')) &&
                    AgentCodeSearch.matchesGlobs(globs, base)
            }.toList()
        val total = filtered.size
        val page = filtered.drop(skip).take(maxEntries)
        val text = page.joinToString("\n")
        val truncated = skip + page.size < total || text.length > MAX_OUTPUT_CHARS
        return JSONObject()
            .put("ok", true)
            .put("tool", "list_directory")
            .put("path", safePath)
            .put("exit_code", 0)
            .put("total", total)
            .put("offset", skip)
            .put("count", page.size)
            .put("truncated", truncated)
            .put("entries_text", text.truncateForJson())
            .put("stderr", "")
            .toString()
    }

    fun editFile(path: String, oldText: String, newText: String, replaceAll: Boolean): String {
        if (!rootAvailable()) return UserFileAccess.edit(path, oldText, newText, replaceAll)
        val safePath = normalizePath(path)
        // 性能：用 head 大块读取替代 dd bs=1，并多读 1 字节以区分“恰好 512KB”与“超过 512KB”；
        // 目录/不存在/非普通文件用退出码 3/4/5 区分，避免编辑失败时只看到 exit=1 无从修正。
        val quoted = shellQuote(safePath)
        val command = "if [ -d $quoted ]; then exit $PATH_EXIT_DIRECTORY; " +
            "elif [ ! -e $quoted ]; then exit $PATH_EXIT_MISSING; " +
            "elif [ ! -f $quoted ]; then exit $PATH_EXIT_NOT_FILE; " +
            "else head -c ${MAX_WRITE_BYTES + 1} $quoted; fi"
        val read = runSuBytes(command, timeoutSeconds = 20)
        pathStatusFailure(read.exitCode, safePath, "edit_file")?.let { return it }
        if (read.exitCode != 0) {
            logger.warn(
                "Agent terminal action=edit_file outcome=read_failed exitCode=${read.exitCode} " +
                    "error=${stderrExcerpt(read.stderr)}"
            )
            return errorJson("READ_FAILED", read.stderr.ifBlank { "exit=${read.exitCode}" })
        }
        if (read.output.size > MAX_WRITE_BYTES) {
            return errorJson("FILE_TOO_LARGE", "文件超过 $MAX_WRITE_BYTES 字节，请拆分后编辑或改用终端命令")
        }
        val outcome = AgentFileEdit.apply(read.output.decodeToString(), oldText, newText, replaceAll)
        return when (outcome) {
            is AgentFileEdit.Outcome.Rejected -> {
                logger.info("Agent terminal action=edit_file outcome=rejected code=${outcome.code}")
                JSONObject()
                    .put("ok", false)
                    .put("tool", "edit_file")
                    .put("code", outcome.code)
                    .put("message", outcome.message)
                    .also { json -> outcome.context?.let { json.put("context", it) } }
                    .toString()
            }
            is AgentFileEdit.Outcome.Applied -> {
                val bytes = outcome.content.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_WRITE_BYTES) {
                    return errorJson("FILE_TOO_LARGE", "编辑结果超过 $MAX_WRITE_BYTES 字节")
                }
                val parent = shellQuote(File(safePath).parent ?: "/")
                // 临时文件名带随机后缀，避免并发编辑相互覆盖；失败时清理残留临时文件。
                val temp = shellQuote("$safePath.eta-edit-tmp-${UUID.randomUUID().toString().take(8)}")
                val write = runSuTextWithStdin(
                    "mkdir -p $parent && cat > $temp && mv -f $temp ${shellQuote(safePath)} || { rm -f $temp; false; }",
                    bytes,
                    timeoutSeconds = 20,
                )
                if (write.exitCode == 0) {
                    logger.info(
                        "Agent terminal action=edit_file outcome=succeeded replacements=${outcome.replacements} " +
                            "bytesWritten=${bytes.size}"
                    )
                    JSONObject()
                        .put("ok", true)
                        .put("tool", "edit_file")
                        .put("path", safePath)
                        .put("replacements", outcome.replacements)
                        .put("bytes_written", bytes.size)
                        .toString()
                } else {
                    logger.warn(
                        "Agent terminal action=edit_file outcome=failed replacements=${outcome.replacements} " +
                            "inputBytes=${bytes.size} exitCode=${write.exitCode} errorChars=${write.stderr.length}"
                    )
                    errorJson("WRITE_FAILED", write.stderr.ifBlank { write.output.ifBlank { "exit=${write.exitCode}" } })
                }
            }
        }
    }

    /**
     * 删除文件 / 空目录（P2-8）：
     * - 非空目录必须显式 `recursive=true`，否则拒绝并回报直接子项数；
     * - 工作区根、外部存储根与系统关键目录一律拒绝（[TerminalDeletePolicy] 保护判定）；
     * - 结果带 resolved_path / kind / recursive / deleted_entries，便于调用方核对影响面；
     * - 符号链接按文件删除（rm -f / File.delete），不跟随链接删除目标内容。
     */
    fun deletePath(path: String, recursive: Boolean): String {
        if (!rootAvailable()) return deletePathWithoutRoot(path, recursive)
        val raw = path.trim()
        if (raw.isBlank()) return errorJson("INVALID_ARGUMENT", "path 不能为空")
        val safePath = normalizePath(raw)
        val workspaceRoots = protectedWorkspaceRoots()
        val probe = runSuText(deleteProbeCommand(safePath), timeoutSeconds = 15)
        if (probe.exitCode == PATH_EXIT_MISSING) {
            return errorJson("NOT_FOUND", missingPathMessage(safePath), path = safePath)
        }
        if (probe.exitCode != 0) {
            logger.warn(
                "Agent terminal action=delete_path outcome=probe_failed exitCode=${probe.exitCode} " +
                    "error=${stderrExcerpt(probe.stderr)}"
            )
            return errorJson("DELETE_PROBE_FAILED", probe.stderr.ifBlank { "exit=${probe.exitCode}" }, path = safePath)
        }
        val probeText = probe.output.trim()
        val kind = when {
            probeText.startsWith("file") -> TerminalEntryKind.FILE
            probeText.startsWith("directory") -> TerminalEntryKind.DIRECTORY
            else -> TerminalEntryKind.OTHER
        }
        val counts = probeText.split(' ').mapNotNull { it.trim().toIntOrNull() }
        val directEntries = counts.getOrElse(0) { 0 }
        val totalEntries = counts.getOrElse(1) { directEntries }
        return when (val decision = TerminalDeletePolicy.decide(
            kind = kind,
            directEntries = directEntries,
            totalEntries = totalEntries,
            recursive = recursive,
            normalizedPath = safePath,
            workspaceRoots = workspaceRoots,
        )) {
            is TerminalDeletePolicy.Decision.Reject -> errorJson(decision.code, decision.message, path = safePath)
            is TerminalDeletePolicy.Decision.Delete -> {
                val remove = runSuText(deleteExecCommand(safePath, decision), timeoutSeconds = 20)
                if (remove.exitCode != 0) {
                    logger.warn(
                        "Agent terminal action=delete_path outcome=failed kind=${decision.kind.wireName} " +
                            "recursive=${decision.recursive} exitCode=${remove.exitCode} error=${stderrExcerpt(remove.stderr)}"
                    )
                    return errorJson("DELETE_FAILED", remove.stderr.ifBlank { "exit=${remove.exitCode}" }, path = safePath)
                }
                logger.info(
                    "Agent terminal action=delete_path outcome=succeeded kind=${decision.kind.wireName} " +
                        "recursive=${decision.recursive} deletedEntries=${decision.deletedEntries}"
                )
                JSONObject()
                    .put("ok", true)
                    .put("tool", "delete_path")
                    .put("path", safePath)
                    .put("resolved_path", safePath)
                    .put("kind", decision.kind.wireName)
                    .put("recursive", decision.recursive)
                    .put("deleted", true)
                    .put("deleted_entries", decision.deletedEntries)
                    .toString()
            }
        }
    }

    /** 免 Root 删除：只在应用可访问范围内使用 File API，保护判定与 Root 分支同一套纯逻辑。 */
    private fun deletePathWithoutRoot(path: String, recursive: Boolean): String {
        val file = try {
            UserFileAccess.resolve(path)
        } catch (error: IllegalArgumentException) {
            return errorJson("INVALID_ARGUMENT", error.message ?: "路径不可访问")
        }
        val absolute = file.absolutePath
        val kind = when {
            // 符号链接先于 isDirectory 判定：File.isDirectory 会跟随链接，误判会递归进目标目录。
            java.nio.file.Files.isSymbolicLink(file.toPath()) -> TerminalEntryKind.FILE
            file.isDirectory -> TerminalEntryKind.DIRECTORY
            file.isFile -> TerminalEntryKind.FILE
            file.exists() -> TerminalEntryKind.OTHER
            else -> return errorJson(
                "NOT_FOUND",
                "文件不存在：$absolute；请先用 list_directory 确认路径",
                path = absolute,
            )
        }
        val counts = if (kind == TerminalEntryKind.DIRECTORY) countDirectoryEntries(file) else 1 to 1
        return when (val decision = TerminalDeletePolicy.decide(
            kind = kind,
            directEntries = counts.first,
            totalEntries = counts.second,
            recursive = recursive,
            normalizedPath = absolute,
            workspaceRoots = protectedWorkspaceRoots(),
        )) {
            is TerminalDeletePolicy.Decision.Reject -> errorJson(decision.code, decision.message, path = absolute)
            is TerminalDeletePolicy.Decision.Delete -> {
                val deleted = try {
                    if (kind == TerminalEntryKind.DIRECTORY) {
                        deleteEntryRecursively(file)
                    } else if (file.delete()) {
                        1
                    } else {
                        return errorJson("DELETE_FAILED", "删除失败（无权限或文件被占用）：$absolute", path = absolute)
                    }
                } catch (error: Exception) {
                    return errorJson("DELETE_FAILED", error.message ?: "删除失败", path = absolute)
                }
                logger.info(
                    "Agent terminal action=delete_path outcome=succeeded kind=${decision.kind.wireName} " +
                        "recursive=${decision.recursive} deletedEntries=$deleted noRoot=true"
                )
                JSONObject()
                    .put("ok", true)
                    .put("tool", "delete_path")
                    .put("path", absolute)
                    .put("resolved_path", absolute)
                    .put("kind", decision.kind.wireName)
                    .put("recursive", decision.recursive)
                    .put("deleted", true)
                    .put("deleted_entries", deleted)
                    .toString()
            }
        }
    }

    /** 目录直接子项数与全部后代条目数（不含目录自身），用于 delete 的非空判定与影响面回显。 */
    private fun countDirectoryEntries(directory: File): Pair<Int, Int> {
        val children = directory.listFiles().orEmpty()
        var total = 0
        children.forEach { child -> total += countDescendants(child) }
        return children.size to total
    }

    private fun countDescendants(file: File): Int =
        if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.isDirectory) {
            1
        } else {
            1 + file.listFiles().orEmpty().sumOf { countDescendants(it) }
        }

    /** 递归删除（无 Root）：目录自身计入返回值；任一条目失败即抛出，避免静默部分删除。 */
    private fun deleteEntryRecursively(file: File): Int {
        if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.isDirectory) {
            if (!file.delete()) throw IllegalStateException("删除失败（无权限或文件被占用）：${file.absolutePath}")
            return 1
        }
        var deleted = 0
        file.listFiles().orEmpty().forEach { child -> deleted += deleteEntryRecursively(child) }
        if (!file.delete()) throw IllegalStateException("删除失败（无权限或目录被占用）：${file.absolutePath}")
        return deleted + 1
    }

    /** 探测命令：区分缺失/符号链接/目录/普通文件，并回报直接子项数与后代总数。 */
    private fun deleteProbeCommand(safePath: String): String {
        val quoted = shellQuote(safePath)
        return "if [ ! -e $quoted ] && [ ! -L $quoted ]; then exit $PATH_EXIT_MISSING; fi; " +
            "if [ -L $quoted ]; then printf 'file 1 1'; " +
            "elif [ -d $quoted ]; then printf 'directory %s %s' " +
            "\"\$(find $quoted -mindepth 1 -maxdepth 1 2>/dev/null | wc -l)\" " +
            "\"\$(find $quoted -mindepth 1 2>/dev/null | wc -l)\"; " +
            "elif [ -f $quoted ]; then printf 'file 1 1'; " +
            "else printf 'other 0 0'; fi"
    }

    private fun deleteExecCommand(
        safePath: String,
        decision: TerminalDeletePolicy.Decision.Delete,
    ): String {
        val quoted = shellQuote(safePath)
        return when {
            decision.kind == TerminalEntryKind.FILE -> "rm -f $quoted"
            decision.recursive -> "rm -rf $quoted"
            else -> "rmdir $quoted"
        }
    }

    /** delete 保护用的工作区根（当前 Linux 工作区 + 私有工作区 + 宿主工作区常量）。 */
    private fun protectedWorkspaceRoots(): List<String> = listOf(
        runCatching { TerminalRuntime.currentLinuxWorkspaceRoot() }.getOrDefault(DEFAULT_CWD),
        runCatching { TerminalRuntime.userWorkspacePath }.getOrDefault(DEFAULT_CWD),
        DEFAULT_CWD,
    )

    fun searchCode(rootPath: String, pattern: String, glob: String, maxResults: Int): String {
        if (!rootAvailable()) return UserFileAccess.search(rootPath, pattern, glob, maxResults)
        val trimmedPattern = pattern.trim()
        if (trimmedPattern.isEmpty()) {
            return JSONObject()
                .put("ok", false).put("code", "INVALID_PATTERN").put("message", "pattern 不能为空")
                .toString()
        }
        if (trimmedPattern.length > AgentCodeSearch.MAX_PATTERN_CHARS) {
            return JSONObject()
                .put("ok", false).put("code", "INVALID_PATTERN")
                .put("message", "pattern 过长（最多 ${AgentCodeSearch.MAX_PATTERN_CHARS} 字符）")
                .toString()
        }
        // 先用本机正则做一次语法校验：非法直接报 INVALID_PATTERN，避免把 grep 原生报错透给模型。
        try {
            Regex(trimmedPattern)
        } catch (_: Exception) {
            return JSONObject()
                .put("ok", false).put("code", "INVALID_PATTERN").put("message", "pattern 不是合法正则")
                .toString()
        }
        val safeRoot = normalizePath(rootPath.ifBlank { defaultScanRoot() })
        val max = maxResults.coerceIn(1, AgentCodeSearch.MAX_RESULTS)
        val globTokens = glob.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // 单进程 grep -r 一轮出结果。旧实现是 find 列出 N 个文件再逐个 fork grep，大目录下
        // 进程数爆炸且 30 秒超时。
        // 兼容性：Root 会话默认进入 BusyBox ash，grep 是 BusyBox 版（不支持 --exclude-dir），
        // 直接套 GNU 参数会静默失败并被 `| head` 掩盖成“0 结果”。这里按运行期探测选择可用的
        // grep（优先 /system/bin/toybox grep），按真实能力拼接参数，错误不再吞掉。
        val includeExpr = globTokens.joinToString(" ") { "--include=${shellQuote(it)}" }
        val quotedRoot = shellQuote(safeRoot)
        fun buildSearchCommand(tool: GrepTool): String =
            // 目录不存在时用退出码 4 返回 MISSING_DIRECTORY：cd 失败的错误文本里
            // 既有 shell 前缀又有路径，模型难以利用，且与逐文件扫描警告混杂。
            "[ -d $quotedRoot ] || exit $PATH_EXIT_MISSING; " +
                "cd $quotedRoot && ${tool.prefix} -rInHE " + tool.flagArgs(includeExpr) +
                "-e ${shellQuote(trimmedPattern)} . | head -n $max"
        var result = runSuText(buildSearchCommand(grepTool()), timeoutSeconds = 30)
        if (looksLikeGrepOptionError(result.stderr)) {
            // 缓存的能力探测与实际环境不一致（例如 Root 会话外壳切换）：失效重探后重试一次。
            invalidateGrepToolCache()
            result = runSuText(buildSearchCommand(grepTool()), timeoutSeconds = 30)
        }
        val rawLines = result.output.lineSequence().filter { it.isNotBlank() }.toList()
        if (rawLines.isEmpty() && (result.exitCode != 0 || result.stderr.isNotBlank())) {
            if (result.exitCode == PATH_EXIT_MISSING) {
                logger.warn(
                    "Agent terminal action=search_code outcome=failed code=MISSING_DIRECTORY " +
                        "patternChars=${trimmedPattern.length}"
                )
                return errorJson("MISSING_DIRECTORY", "搜索目录不存在：$safeRoot" + missingPathSuggestion(safeRoot), path = safeRoot)
            }
            val warningLines = GrepScanWarnings.lines(result.stderr)
            // 逐文件的权限/IO 警告（Permission denied / Bad file descriptor 等）会让 grep
            // 以退出码 2 收场，但搜索实际已执行：无匹配就是“确实没有匹配”，
            // 应按成功返回并如实报告被跳过的文件数，而不是让模型把有效搜索当失败重试。
            if (result.exitCode != 0 && GrepScanWarnings.isScanWarningOnly(result.stderr)) {
                logger.info(
                    "Agent terminal action=search_code outcome=succeeded_with_skips " +
                        "patternChars=${trimmedPattern.length} skippedFiles=${warningLines.size} " +
                        "exitCode=${result.exitCode}"
                )
                return JSONObject()
                    .put("ok", true)
                    .put("tool", "search_code")
                    .put("path", safeRoot)
                    .put("pattern", trimmedPattern)
                    .put("glob", glob.orEmpty())
                    .put("count", 0)
                    .put("truncated", false)
                    .put("skipped_files", warningLines.size)
                    .put("warning", "有 ${warningLines.size} 个文件因权限或 IO 错误被跳过，搜索结果可能不完整")
                    .put("results", JSONArray())
                    .toString()
            }
            logger.warn(
                "Agent terminal action=search_code outcome=failed patternChars=${trimmedPattern.length} " +
                    "exitCode=${result.exitCode} errorChars=${result.stderr.length} " +
                    "error=${stderrExcerpt(result.stderr)}"
            )
            // grep ERE 与本机正则存在方言差异：本机能编译但 grep 拒绝（如前瞻断言）时，
            // 报 INVALID_PATTERN 而不是 SEARCH_FAILED，方便模型修正 pattern。
            val stderr = result.stderr
            val looksLikeRegexError = stderr.contains("nvalid", ignoreCase = true) ||
                stderr.contains("ad regex", ignoreCase = true) ||
                stderr.contains("regex", ignoreCase = true) ||
                stderr.contains("regular expression", ignoreCase = true) ||
                stderr.contains("error", ignoreCase = true)
            return errorJson(
                if (looksLikeRegexError) "INVALID_PATTERN" else "SEARCH_FAILED",
                stderr.ifBlank { "exit=${result.exitCode}" }
            )
        }
        var budget = AgentCodeSearch.MAX_ENTRIES_TEXT_CHARS
        val entries = mutableListOf<String>()
        val prefix = safeRoot.trimEnd('/')
        for (line in rawLines) {
            val parsed = AgentCodeSearch.parseGrepLine(line)
            val entry = if (parsed != null) {
                // 与免 Root 实现统一：entries 一律使用绝对路径，避免模型拿到 ./ 开头的相对路径后猜基址。
                val relative = parsed.first.removePrefix("./")
                val absolute = if (parsed.first.startsWith("./")) "$prefix/$relative" else parsed.first
                AgentCodeSearch.entry(absolute, parsed.second, parsed.third)
            } else {
                line.truncateByCodePoints(AgentCodeSearch.MAX_LINE_CHARS)
            }
            if (entry.length + 1 > budget) break
            entries += entry
            budget -= entry.length + 1
        }
        logger.info(
            "Agent terminal action=search_code outcome=succeeded patternChars=${trimmedPattern.length} " +
                "results=${entries.size} truncated=${entries.size < rawLines.size} exitCode=${result.exitCode}"
        )
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_code")
            .put("path", safeRoot)
            .put("pattern", trimmedPattern)
            .put("glob", glob.orEmpty())
            .put("count", entries.size)
            .put("truncated", entries.size < rawLines.size)
            .put("results", JSONArray(entries))
            .toString()
    }

    private fun normalizeIdentity(identity: String): String {
        val normalized = identity.ifBlank { "root" }.lowercase()
        require(normalized == "root" || normalized == "user") {
            "identity 仅支持 root/user"
        }
        return normalized
    }

    private fun normalizeEnvironment(environment: String): TerminalEnvironment =
        when (environment.ifBlank { TerminalEnvironment.ANDROID.wireName }.lowercase()) {
            TerminalEnvironment.ANDROID.wireName -> TerminalEnvironment.ANDROID
            SELECTED_LINUX_WIRE_NAME -> selectedLinuxEnvironmentProvider()
                .takeIf { it == TerminalEnvironment.ALPINE || it == TerminalEnvironment.DEBIAN }
                ?: TerminalEnvironment.ALPINE
            TerminalEnvironment.ALPINE.wireName -> TerminalEnvironment.ALPINE
            TerminalEnvironment.DEBIAN.wireName -> TerminalEnvironment.DEBIAN
            else -> throw IllegalArgumentException("environment 仅支持 android/linux")
        }

    private fun environmentPreflight(
        identity: String,
        environment: TerminalEnvironment,
        rootfsPath: String? = rootfsPathFor(environment),
    ): String? = when {
        environment.isLinux && identity != "root" && LinuxEnvironmentPaths.backendOf(rootfsPath) != LinuxExecutionBackend.PROOT ->
            errorJson("LINUX_ENVIRONMENT_REQUIRES_ROOT", "Linux 工具环境仅支持 root identity")
        environment.isLinux && !LinuxEnvironmentPaths.rootfsReady(rootfsPath) ->
            errorJson(
                "LINUX_ENVIRONMENT_NOT_READY",
                "Linux 工具环境尚未安装，请先在设置中完成环境配置",
            )
        identity == "root" && !rootAvailable() -> errorJson("ROOT_REQUIRED", "Root 授权不可用")
        environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT && identity == "root" ->
            errorJson("INVALID_IDENTITY", "免 Root Linux 使用普通应用身份，请使用 identity=user")
        else -> null
    }

    private fun defaultIdentity(environment: TerminalEnvironment): String = when {
        environment.isLinux -> TerminalRuntime.defaultIdentity(environment, rootfsPathFor(environment))
        rootAvailable() -> "root"
        else -> "user"
    }

    private fun normalizeCwd(cwd: String?, environment: TerminalEnvironment, identity: String): String {
        val defaultCwd = if (environment.isLinux) LINUX_DEFAULT_CWD else TerminalRuntime.workspace(identity)
        val requested = cwd?.trim().orEmpty().ifBlank { defaultCwd }
        val environmentPath = when {
            requested == "~" || requested.startsWith("~/") || requested.startsWith("/") -> requested
            else -> "$defaultCwd/$requested"
        }
        return if (environment.isLinux) {
            val value = when { environmentPath == "~" -> "/root"; environmentPath.startsWith("~/") -> "/root/${environmentPath.removePrefix("~/")}"; else -> environmentPath }
            File(value).toPath().normalize().toString()
        } else if (identity == "user") {
            val value = when { environmentPath == "~" -> defaultCwd; environmentPath.startsWith("~/") -> "$defaultCwd/${environmentPath.removePrefix("~/")}"; else -> environmentPath }
            File(value).canonicalPath
        } else normalizePath(environmentPath)
    }

    /**
     * 相对路径与空白路径的默认基准：与绝对 `/workspace` 的翻译根同源
     * （chroot 且环境就绪且有 Root 时为宿主工作区，其余模式为私有工作区）。
     * 不能再单独取 userWorkspacePath——那会让同一工具内出现两套基准（实测 P2-2）。
     */
    private fun defaultScanRoot(): String =
        runCatching { TerminalRuntime.currentLinuxWorkspaceRoot() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_CWD

    private fun linuxMountPairs(): List<Pair<String, String>> =
        runCatching { linuxSharedMountsProvider().map { it.name to it.sourcePath } }
            .getOrDefault(emptyList())

    /** 共享挂载枚举视图的合成列表：条目为 `d 挂载名 -> Android 侧源路径`。 */
    private fun mountsListingJson(mounts: List<Pair<String, String>>, limit: Int, offset: Int): String {
        val entries = mounts.map { (name, source) -> "d $name -> $source" }
        val maxEntries = limit.coerceIn(1, MAX_LIST_ENTRIES)
        val skip = offset.coerceAtLeast(0)
        val page = entries.drop(skip).take(maxEntries)
        return JSONObject()
            .put("ok", true)
            .put("tool", "list_directory")
            .put("path", AgentFilePathMapper.LINUX_MOUNTS_ROOT)
            .put("exit_code", 0)
            .put("virtual", true)
            .put("total", entries.size)
            .put("offset", skip)
            .put("count", page.size)
            .put("truncated", skip + page.size < entries.size)
            .put("entries_text", page.joinToString("\n"))
            .put(
                "note",
                if (entries.isEmpty()) {
                    "当前未配置共享文件夹（/workspace/mounts 下没有挂载）。"
                } else {
                    "共享挂载视图：进入子目录请用 /workspace/mounts/<name>/... 路径。"
                },
            )
            .put("stderr", "")
            .toString()
    }

    /**
     * 挂载视图的常见误用：把 /workspace/mounts 当普通目录，或访问未配置的挂载名。
     * 这里给可操作的错误，而不是让底层 cd/show 报出难以理解的失败。
     */
    private fun mountsViewIssueMessage(path: String): String? {
        val value = path.trim().trimEnd('/')
        if (value.isEmpty()) return null
        if (value == AgentFilePathMapper.LINUX_MOUNTS_ROOT) {
            return "这是共享挂载枚举视图，不是可访问目录；可先 list_directory /workspace/mounts 查看挂载名，" +
                "再用 /workspace/mounts/<name>/... 访问。" + mountNamesHint()
        }
        if (value.startsWith("${AgentFilePathMapper.LINUX_MOUNTS_ROOT}/")) {
            val name = value.removePrefix("${AgentFilePathMapper.LINUX_MOUNTS_ROOT}/").substringBefore('/')
            if (name.isNotEmpty() && linuxMountPairs().none { it.first == name }) {
                return "未配置名为 \"$name\" 的共享挂载。" + mountNamesHint()
            }
        }
        return null
    }

    /**
     * 路径状态退出码 → 结构化错误：目录 / 不存在 / 非普通文件分别给出可修正的结论与建议。
     * 返回 null 表示不是路径状态问题，调用方按原有执行失败路径处理。
     */
    private fun pathStatusFailure(exitCode: Int, safePath: String, tool: String): String? {
        val code = when (exitCode) {
            PATH_EXIT_DIRECTORY -> "IS_DIRECTORY"
            PATH_EXIT_MISSING -> "NOT_FOUND"
            PATH_EXIT_NOT_FILE -> "NOT_REGULAR_FILE"
            else -> return null
        }
        val message = when (exitCode) {
            PATH_EXIT_DIRECTORY ->
                "路径是目录：$safePath；请改用 list_directory 浏览目录，或提供具体文件路径"
            PATH_EXIT_MISSING -> missingPathMessage(safePath)
            else ->
                "路径不是普通文件：$safePath（可能是设备、管道或损坏的符号链接）"
        }
        logger.warn("Agent terminal action=$tool outcome=failed code=$code pathChars=${safePath.length}")
        return errorJson(code, message, path = safePath)
    }

    /**
     * 缺失文件的恢复提示：列出父目录现有条目，帮助模型一次修正路径拼写，
     * 而不是拿着 exit=1 反复重试同一路径。仅在失败路径调用（额外一次短 su 调用）。
     */
    private fun missingPathMessage(safePath: String): String =
        "文件不存在：$safePath" + missingPathSuggestion(safePath)

    /** 缺失路径的父目录建议正文（含前缀标点），供 read/edit/search 共用。 */
    private fun missingPathSuggestion(safePath: String): String {
        val parent = File(safePath).parent ?: "/"
        val quotedParent = shellQuote(parent)
        val listing = runCatching {
            runSuText(
                "[ -d $quotedParent ] && ls -1A $quotedParent 2>/dev/null | head -n $MISSING_SUGGESTION_ENTRIES " +
                    "|| printf '$NO_PARENT_MARKER\\n'",
                timeoutSeconds = 15,
            )
        }.getOrNull()
        val text = listing?.output?.trim().orEmpty()
        if (listing == null || text.isBlank()) {
            return "；请先用 list_directory 确认工作区中的真实路径"
        }
        if (text.contains(NO_PARENT_MARKER)) {
            return "；父目录 $parent 也不存在，请先用 list_directory 从工作区根目录逐层确认"
        }
        val names = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .take(MISSING_SUGGESTION_ENTRIES).toList()
        return "。父目录 $parent 下的条目：${names.joinToString(", ")}。" +
            "请核对文件名大小写与后缀，或先用 list_directory 浏览目录"
    }

    /** 日志用的 stderr 摘要：去换行、截断，避免单条日志被多行错误撑爆。 */
    private fun stderrExcerpt(stderr: String): String =
        stderr.replace('\n', ' ').replace('\r', ' ').trim().take(200).ifBlank { "-" }

    private fun mountNamesHint(): String {
        val names = linuxMountPairs().map { it.first }
        return if (names.isEmpty()) "当前未配置任何共享文件夹。" else "当前可用挂载：" + names.joinToString(", ")
    }

    /** 运行期探测到的可用 grep：BusyBox grep 不支持 --exclude-dir，参数必须按能力拼接。 */
    private data class GrepTool(
        val prefix: String,
        val excludeDirs: Boolean,
        val ignoreBinary: Boolean,
    ) {
        fun flagArgs(includeExpr: String): String = buildString {
            if (ignoreBinary) append("-I ")
            if (excludeDirs) {
                append("--exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build ")
                append("--exclude-dir=.gradle --exclude-dir=.idea ")
            }
            if (includeExpr.isNotEmpty()) append(includeExpr).append(' ')
        }
    }

    /**
     * 取当前可用的 grep 能力。缓存是进程级共享的（控制器实例每个 run 重建，
     * 探测本身要起若干次 su 进程，不能每个 run 重探），只在探测结论可信时写入。
     */
    private fun grepTool(): GrepTool {
        grepToolCache?.let { return it }
        return synchronized(GREP_TOOL_CACHE_LOCK) {
            grepToolCache?.let { return@synchronized it }
            val detection = detectGrepTool()
            // Root 会话临时不可用时得到的降级猜测不写缓存，避免后续 run 一直用错能力。
            if (detection.cacheable) grepToolCache = detection.tool
            detection.tool
        }
    }

    /** 能力缓存失效：下一次 searchCode 会重新探测。 */
    private fun invalidateGrepToolCache() {
        synchronized(GREP_TOOL_CACHE_LOCK) { grepToolCache = null }
    }

    /** 探测结论；[cacheable] 为 false 表示本轮有探测命令没能执行，结论不适合长期缓存。 */
    private class GrepToolDetection(val tool: GrepTool, val cacheable: Boolean)

    private fun detectGrepTool(): GrepToolDetection {
        val excludes = "--exclude-dir=.git --exclude-dir=node_modules --exclude-dir=build " +
            "--exclude-dir=.gradle --exclude-dir=.idea"
        var probesCompleted = true
        fun probe(prefix: String, flags: String): Boolean =
            when (val verdict = probeGrep(prefix, flags)) {
                null -> {
                    probesCompleted = false
                    false
                }
                else -> verdict
            }
        // 首选 Android 自带 toybox：不受 Root 会话 BusyBox 覆盖影响，参数完整。
        if (probe("/system/bin/toybox grep", "-I $excludes")) {
            return GrepToolDetection(
                GrepTool("/system/bin/toybox grep", excludeDirs = true, ignoreBinary = true),
                cacheable = probesCompleted,
            )
        }
        if (probe("toybox grep", "-I $excludes")) {
            return GrepToolDetection(
                GrepTool("toybox grep", excludeDirs = true, ignoreBinary = true),
                cacheable = probesCompleted,
            )
        }
        // 回退到 PATH 上的 grep，按实测能力降级（BusyBox 不认识 --exclude-dir）。
        if (probe("grep", "-I $excludes")) {
            return GrepToolDetection(
                GrepTool("grep", excludeDirs = true, ignoreBinary = true),
                cacheable = probesCompleted,
            )
        }
        if (probe("grep", "-I")) {
            return GrepToolDetection(
                GrepTool("grep", excludeDirs = false, ignoreBinary = true),
                cacheable = probesCompleted,
            )
        }
        if (probe("grep", "")) {
            return GrepToolDetection(
                GrepTool("grep", excludeDirs = false, ignoreBinary = false),
                cacheable = probesCompleted,
            )
        }
        return GrepToolDetection(
            GrepTool("grep", excludeDirs = false, ignoreBinary = false),
            cacheable = probesCompleted,
        )
    }

    /**
     * 空输入探测：二进制存在且参数被接受即通过；失败文案会带 not found / option 关键字。
     * 返回 null 表示这条探测命令本身没有正常执行完（例如 Root 会话不可用），
     * 这一轮的结论不可信，调用方不应把它写入进程级缓存。
     */
    private fun probeGrep(prefix: String, flags: String): Boolean? {
        val command = "echo | $prefix $flags -e eta_grep_probe 2>&1 | head -n 3"
        val result = runCatching { runSuText(command, timeoutSeconds = 8) }.getOrNull() ?: return null
        // 管道以 head 收尾，正常执行时退出码为 0；非 0 说明命令根本没跑起来（su 被拒/启动失败）。
        if (result.exitCode != 0) return null
        val text = (result.output + "\n" + result.stderr).lowercase()
        val markers = listOf("not found", "unrecognized", "unknown option", "invalid option", "unknown command")
        return markers.none { text.contains(it) }
    }

    private fun looksLikeGrepOptionError(stderr: String): Boolean {
        val text = stderr.lowercase()
        return text.contains("recognized option") || text.contains("unknown option") || text.contains("invalid option")
    }

    private fun normalizePath(path: String): String {
        val raw = path.trim()
        require(raw.isNotBlank()) { "path 不能为空" }
        mountsViewIssueMessage(raw)?.let { throw IllegalArgumentException(it) }
        // Linux 视图先翻译为 Android 视图：Root Shell 的挂载命名空间里没有 /workspace，
        // 不翻译则读、列、搜遇到 Linux 写法直接失败，子代理批量取证时尤其致命；
        // 翻译根与终端里 /workspace 的实际指向一致（chroot 为宿主工作区，PRoot 为私有工作区）。
        // 相对路径必须使用同一次解析出的同一个根：否则会出现“绝对路径走宿主工作区、
        // 相对路径落私有工作区”的双轨（实测 P2-2），模型传相对路径必然找不到文件。
        val workspaceRoot = defaultScanRoot()
        val translated = AgentFilePathMapper.toAndroidPath(raw, linuxMountPairs(), workspaceRoot)
        val effective = TerminalFilePathResolution.resolve(translated, workspaceRoot, USER_STORAGE)
        val normalized = File(effective).canonicalPath
        return normalized
    }

    private fun startSessionProcess(
        identity: String,
        environment: TerminalEnvironment,
        rootfsPath: String?,
    ): Process? =
        processSupervisor.startShellProcess(
            identity = identity,
            command = null,
            mergeStderr = false,
            environment = environment,
            linuxRootfsPath = rootfsPath,
            linuxSharedMounts = sharedMountsFor(environment),
        )

    /** 共享挂载只在 Linux 会话建立时解析；Android 环境不涉及。 */
    private fun sharedMountsFor(environment: TerminalEnvironment): List<SharedFolderMount> =
        if (environment.isLinux) linuxSharedMountsProvider() else emptyList()

    private fun rootfsPathFor(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private fun runText(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        environment: TerminalEnvironment,
    ): ShellTextResult {
        val result = runProcess(
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = environment,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd(),
        )
    }

    private fun runSuText(command: String, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runSuTextWithStdin(command: String, stdin: ByteArray, timeoutSeconds: Long): ShellTextResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = stdin,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellTextResult(
            exitCode = result.exitCode,
            output = result.output.decodeToString().trimEnd(),
            stderr = result.stderr.decodeToString().trimEnd()
        )
    }

    private fun runSuBytes(command: String, timeoutSeconds: Long): ShellBytesResult {
        val result = runProcess(
            identity = "root",
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = null,
            environment = TerminalEnvironment.ANDROID,
        )
        return ShellBytesResult(result.exitCode, result.output, result.stderr.decodeToString().trimEnd())
    }

    private fun runProcess(
        identity: String,
        command: String,
        timeoutSeconds: Long,
        stdin: ByteArray?,
        environment: TerminalEnvironment,
    ): OneShotShellResult =
        runOneShotShell(
            processSupervisor = processSupervisor,
            identity = identity,
            command = command,
            timeoutSeconds = timeoutSeconds,
            stdin = stdin,
            environment = environment,
            linuxRootfsPath = rootfsPathFor(environment),
            linuxSharedMounts = sharedMountsFor(environment),
        )

    private fun String.truncateForJson(): String =
        if (length <= MAX_OUTPUT_CHARS) this else truncateByCodePoints(MAX_OUTPUT_CHARS) + "\n...[truncated]"

    /** [path] 非空时把解析后的绝对路径写回结果，便于调用方核对相对路径基准（P2-2）。 */
    private fun errorJson(code: String, message: String, path: String? = null): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.truncateByCodePoints(300))
            .also { json ->
                if (path != null) {
                    json.put("path", path)
                    json.put("resolved_path", path)
                }
            }
            .toString()

    private data class ShellTextResult(val exitCode: Int, val output: String, val stderr: String)
    private data class ShellBytesResult(val exitCode: Int, val output: ByteArray, val stderr: String)
    private data class SessionCommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val cwd: String?,
        val timedOut: Boolean
    )

    private class TerminalSession(
        val id: String,
        val identity: String,
        val environment: TerminalEnvironment,
        val rootfsPath: String?,
        var cwd: String,
        val createdAt: Long,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector
    ) {
        val lock = Any()

        @Volatile
        var closed: Boolean = false

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }

    private class AsyncCommand(
        val id: String,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector,
        val command: String,
        val cwd: String,
        val identity: String,
        val environment: TerminalEnvironment,
        val mergeStderr: Boolean,
        val sessionId: String?,
        val startedAt: Long,
        val timeoutMs: Int
    ) {
        @Volatile
        var exitCode: Int? = null

        @Volatile
        var timedOut: Boolean = false

        @Volatile
        var completedAt: Long? = null

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }
}

/**
 * file 类工具的路径归一（纯函数，便于单测）：把 [AgentFilePathMapper.toAndroidPath] 翻译后的
 * 路径落到 Android 视图——`~` 指向用户存储、绝对路径原样保留、相对路径以 [workspaceRoot] 为基准。
 *
 * 关键约束：相对路径必须与绝对 `/workspace` 使用同一次解析出的同一个 [workspaceRoot]，
 * 否则会出现“绝对路径走宿主工作区、相对路径落私有工作区”的双轨（实测 P2-2），
 * 调用方传相对路径必然找不到文件。
 */
internal object TerminalFilePathResolution {
    fun resolve(translated: String, workspaceRoot: String, userStorage: String): String = when {
        translated == "~" -> userStorage
        translated.startsWith("~/") -> userStorage + "/" + translated.removePrefix("~/")
        translated.startsWith("/") -> translated
        else -> "$workspaceRoot/$translated"
    }
}

/** delete 探测出的条目类型。 */
internal enum class TerminalEntryKind(val wireName: String) {
    FILE("file"),
    DIRECTORY("directory"),
    OTHER("other"),
}

/**
 * delete 的保护判定与层级决策（纯函数，便于单测）：
 * - 根、系统关键目录、外部存储根与工作区根一律拒绝；
 * - 非空目录必须显式 recursive=true，否则拒绝并给出直接子项数；
 * - 其余情况返回删除计划（是否递归、影响条目数，目录自身计入）。
 */
internal object TerminalDeletePolicy {
    /** 与运行期工作区无关的固定保护路径：系统/存储关键点不接受删除。 */
    val fixedProtectedPaths = setOf(
        "/",
        "/data",
        "/data/data",
        "/data/local/tmp",
        "/system",
        "/vendor",
        "/odm",
        "/product",
        "/sdcard",
        "/storage",
        "/storage/emulated",
        "/storage/emulated/0",
    )

    sealed interface Decision {
        data class Delete(
            val kind: TerminalEntryKind,
            val recursive: Boolean,
            val deletedEntries: Int,
        ) : Decision

        data class Reject(val code: String, val message: String) : Decision
    }

    /** 工作区/存储根与固定保护路径判定；返回 null 表示允许继续走层级决策。 */
    fun protectionIssue(normalizedPath: String, workspaceRoots: Collection<String>): String? {
        val path = normalizeRoot(normalizedPath)
        if (path in fixedProtectedPaths) return "不允许删除受保护路径：$path"
        if (workspaceRoots.any { normalizeRoot(it) == path }) {
            return "不允许删除工作区/存储根目录：$path；请先进入其子目录或逐个删除子项"
        }
        return null
    }

    fun decide(
        kind: TerminalEntryKind,
        directEntries: Int,
        totalEntries: Int,
        recursive: Boolean,
        normalizedPath: String,
        workspaceRoots: Collection<String>,
    ): Decision {
        protectionIssue(normalizedPath, workspaceRoots)?.let { return Decision.Reject("PROTECTED_PATH", it) }
        return when (kind) {
            TerminalEntryKind.OTHER -> Decision.Reject(
                "NOT_REGULAR_FILE",
                "路径不是普通文件或目录：$normalizedPath（可能是设备、管道或损坏的符号链接），不支持删除",
            )
            TerminalEntryKind.FILE -> Decision.Delete(TerminalEntryKind.FILE, recursive = false, deletedEntries = 1)
            TerminalEntryKind.DIRECTORY -> when {
                directEntries > 0 && !recursive -> Decision.Reject(
                    "DIRECTORY_NOT_EMPTY",
                    "目录非空（直接子项 $directEntries 个）；如需删除目录及其内容请显式传 recursive=true",
                )
                recursive -> Decision.Delete(
                    TerminalEntryKind.DIRECTORY,
                    recursive = true,
                    deletedEntries = totalEntries + 1,
                )
                else -> Decision.Delete(TerminalEntryKind.DIRECTORY, recursive = false, deletedEntries = 1)
            }
        }
    }

    private fun normalizeRoot(path: String): String = path.trim().trimEnd('/').ifBlank { "/" }
}
