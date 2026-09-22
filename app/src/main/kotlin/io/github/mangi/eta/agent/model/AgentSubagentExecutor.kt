package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * 并发子代理：一次 spawn_agents 调用内部扇出并行。
 * 只允许一层，子代理复用父配置与 Provider；工具集按模式白名单裁剪并由 guardedExecutor 兜底拦截：
 * research 只读；code 额外放行 write_file/edit_file，但写操作必须命中任务声明的 write_paths，
 * 同一 canonical 文件由 fanout 级注册表互斥，成功写入记入 changed_files。
 *
 * 并发说明：子线程直接调用父 onEvent，不再经额外全局锁串行化。
 * onEvent 下游（session.emit / checkpointRecorder / timing / archivedEvents）均已做线程安全处理，
 * 子代理的模型请求与工具执行可真正重叠。事件分发顺序可能交错，但终态边界仍由 session 锁保证。
 *
 * UI 说明：每个子代理独立上报 SubagentStarted / SubagentToolStarted-Finished /
 * SubagentFinished，UI 按 subIndex 分别展示一行（复用 ToolActivity 卡片），可展开查看步骤；
 * 子 loop 内部的原始 ToolStarted/Finished 不再直接透出，避免与主流程工具卡片混杂。
 */
internal class AgentSubagentExecutor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val parentRunController: AgentRunController,
    private val parentOperationId: String,
    private val parentTools: JSONArray,
    private val systemMessages: JSONArray,
    private val baseToolExecutor: AgentModelClient.ToolExecutor,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val depth: Int = 0,
    private val parentMessagesProvider: () -> JSONArray = { JSONArray() },
    private val parentSystemCount: Int = 0,
    private val sharedMountsProvider: () -> List<Pair<String, String>> = {
        runCatching { SharedFolderMounts.current().map { it.name to it.sourcePath } }.getOrDefault(emptyList())
    },
) {
    data class SubTask(
        val label: String,
        val prompt: String,
        val writePaths: List<String> = emptyList(),
    )

    fun fanout(round: Int, call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (depth > 0) {
            return err("NESTED_SPAWN_NOT_ALLOWED", "子代理不可再派生子代理，本次调用已拒绝；请由主代理直接派发新的子任务")
        }
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrElse {
            return err("INVALID_ARGUMENT", "spawn_agents 参数不是 JSON object")
        }
        val tasksJson = args.optJSONArray("tasks")
            ?: return err("INVALID_ARGUMENT", "缺少必填字段 tasks")
        if (tasksJson.length() < 1) {
            return err("INVALID_ARGUMENT", "tasks 至少需要 1 个任务")
        }
        val modeRaw = args.optString("mode").trim()
        val explicitMode: SubagentMode? = if (modeRaw.isEmpty()) {
            null
        } else {
            SubagentMode.parse(modeRaw)
                ?: return err("INVALID_ARGUMENT", "mode 仅支持 research 或 code")
        }
        val tasks = mutableListOf<SubTask>()
        for (i in 0 until tasksJson.length()) {
            val o = tasksJson.optJSONObject(i) ?: return err("INVALID_ARGUMENT", "tasks[$i] 不是 object")
            val prompt = o.optString("prompt").trim()
            if (prompt.isBlank()) return err("INVALID_ARGUMENT", "tasks[$i].prompt 不能为空")
            if (prompt.length > AgentSubagentPolicy.MAX_PROMPT_CHARS) {
                return err("INVALID_ARGUMENT", "tasks[$i].prompt 超过 4000 字符")
            }
            val label = o.optString("label").trim().take(AgentSubagentPolicy.MAX_LABEL_CHARS)
                .ifBlank { "task-${i + 1}" }
            val writePaths = mutableListOf<String>()
            val writeJson = o.optJSONArray("write_paths")
            if (writeJson != null) {
                for (j in 0 until writeJson.length()) {
                    val value = writeJson.optString(j).trim()
                    if (value.isEmpty()) return err("INVALID_ARGUMENT", "tasks[$i].write_paths[$j] 不能为空")
                    if (value.length > AgentSubagentPolicy.MAX_WRITE_PATH_CHARS) {
                        return err(
                            "INVALID_ARGUMENT",
                            "tasks[$i].write_paths[$j] 超过 ${AgentSubagentPolicy.MAX_WRITE_PATH_CHARS} 字符",
                        )
                    }
                    writePaths += value
                }
            }
            tasks += SubTask(label, prompt, writePaths)
        }
        // 显式模式与 write_paths 必须自洽：research 不接受写范围，code 至少需要一个写范围。
        val anyWritePaths = tasks.any { it.writePaths.isNotEmpty() }
        if (explicitMode == SubagentMode.RESEARCH && anyWritePaths) {
            return err("INVALID_ARGUMENT", "mode=research 不允许声明 write_paths：请移除各任务的 write_paths，或改用 mode=code")
        }
        if (explicitMode == SubagentMode.CODE && !anyWritePaths) {
            return err("INVALID_ARGUMENT", "mode=code 要求至少一个任务声明非空 write_paths：请补充写入范围，或改用 mode=research")
        }
        // 自动配置：mode 可省略；任务声明 write_paths 或点名写工具时按 code 推断，否则默认 research。
        val rawRequestedAllowedTools = AgentSubagentPolicy.parseRawRequestedAllowedTools(args)
        val mode = explicitMode ?: if (
            tasks.any { it.writePaths.isNotEmpty() } ||
            rawRequestedAllowedTools?.any { it in AgentSubagentPolicy.writeTools } == true
        ) {
            SubagentMode.CODE
        } else {
            SubagentMode.RESEARCH
        }
        // 上下文模式：pure 纯净隔离（默认）；shared 非纯净共享主窗口快照但独立运行。
        val contextModeRaw = args.optString("context_mode").trim()
        val contextMode = if (contextModeRaw.isEmpty()) {
            SubagentContextMode.PURE
        } else {
            SubagentContextMode.parse(contextModeRaw)
                ?: return err("INVALID_ARGUMENT", "context_mode 仅支持 pure 或 shared")
        }
        // 默认护栏：max_rounds / timeout_ms 省略时按默认值执行，避免一个卡死拖住整批；
        // 显式提供时按调用方给定值执行，仅做有效性下限兜底（>= 1），不设上限（超大值等价于放开）。
        val maxRounds: Int = if (args.has("max_rounds")) {
            args.optInt("max_rounds", 1).coerceAtLeast(1)
        } else {
            AgentSubagentPolicy.DEFAULT_MAX_ROUNDS
        }
        val timeoutMs: Int = if (args.has("timeout_ms")) {
            args.optInt("timeout_ms", 1).coerceAtLeast(1)
        } else {
            AgentSubagentPolicy.DEFAULT_FANOUT_TIMEOUT_MS
        }
        val timeoutIsDefault = !args.has("timeout_ms")
        val requested = AgentSubagentPolicy.parseRequestedAllowedTools(args, mode)
        val subTools = AgentSubagentPolicy.filterTools(parentTools, requested, mode)
        if (subTools.length() == 0) {
            // 非空白名单过滤后为空：保留专用错误码，并列出被过滤掉的名称，便于模型自我纠正。
            val filteredOut = AgentSubagentPolicy.parseRawRequestedAllowedTools(args).orEmpty()
            val message = if (filteredOut.isEmpty()) {
                "按白名单过滤后子代理无可用工具；请省略 allowed_tools 使用默认白名单，或检查父工具集是否为空"
            } else {
                "按白名单过滤后子代理无可用工具；被过滤名称：" + filteredOut.joinToString(", ") +
                    "；请移除其中的 GUI/终端/浏览器/写/安装类禁入工具，或省略 allowed_tools 使用默认白名单"
            }
            return err("NO_ALLOWED_TOOLS", message)
        }

        val mounts = if (mode == SubagentMode.CODE) sharedMountsProvider() else emptyList()
        val declaredByIndex: List<List<String>> = if (mode == SubagentMode.CODE) {
            val declared = tasks.map { task -> task.writePaths.map { AgentSubagentWritePaths.canonical(it, mounts) } }
            for (i in tasks.indices) {
                for (j in i + 1 until tasks.size) {
                    for (a in declared[i]) {
                        for (b in declared[j]) {
                            if (AgentSubagentWritePaths.overlaps(a, b)) {
                                return err(
                                    "WRITE_CONFLICT",
                                    "子代理[${tasks[i].label}]与[${tasks[j].label}]的写入范围重叠：$a / $b；请按文件或目录分区后再派发",
                                )
                            }
                        }
                    }
                }
            }
            declared
        } else {
            emptyList()
        }

        onEvent(
            AgentEvent.SubagentsStarted(
                round = round,
                toolCallId = call.id,
                count = tasks.size,
                labels = tasks.map { it.label },
            ),
        )

        // 取证时钟：wall clock 用于展示起止，nano 用于精确耗时。超时任务没有正常返回，
        // 用整体 elapsed 近似其 duration，保证 sum(duration) 与 fanout_elapsed 可对比。
        val fanoutStartWallMs = System.currentTimeMillis()
        val fanoutStartNano = System.nanoTime()
        // 各子任务的控制器：超时后显式 cancel，中断 OkHttp 等阻塞调用，避免只靠线程
        // interrupt 停不掉网络请求而泄漏子线程。下标与 tasks 一一对应，各线程只写自己下标。
        // code 模式的动态写互斥与改动记录：下标与 tasks 一一对应，各线程只写自己的下标。
        val writeRegistry = SubagentWriteRegistry()
        val changedFilesByIndex = tasks.indices.map { linkedSetOf<String>() }
        // 终态单次提交：同一 subIndex 只允许一个终态（成功/失败/超时/取消）上报，迟到结果不得覆盖。
        val terminalClaimed = Array(tasks.size) { AtomicBoolean(false) }
        val terminalResults = arrayOfNulls<JSONObject>(tasks.size)
        val subControllers = arrayOfNulls<AgentRunController>(tasks.size)
        // 并行封顶：一次扇出最多同时跑 MAX_PARALLEL_TASKS 个，超出的排队等待；
        // 避免任务一多就线程暴涨导致切换与内存争抢。任务数本身不限。
        val poolSize = minOf(tasks.size, AgentSubagentPolicy.MAX_PARALLEL_TASKS)
        val pool = Executors.newFixedThreadPool(poolSize) { r ->
            Thread(r, "agent-subagent-${parentOperationId.takeLast(6)}").apply { isDaemon = true }
        }
        // 父取消时联动取消所有已创建的子控制器。
        val parentBinding = parentRunController.register {
            subControllers.forEach { runCatching { it?.cancel() } }
        }
        try {
            parentRunController.throwIfCancelled()
            val callables = tasks.mapIndexed { index, task ->
                Callable<JSONObject> {
                    runSingle(
                        parentRound = round,
                        parentToolCallId = call.id,
                        index = index,
                        task = task,
                        maxRounds = maxRounds,
                        subTools = subTools,
                        holders = subControllers,
                        mode = mode,
                        contextMode = contextMode,
                        mounts = mounts,
                        declaredWritePaths = declaredByIndex.getOrElse(index) { emptyList() },
                        writeRegistry = writeRegistry,
                        changedFiles = changedFilesByIndex[index],
                        terminalClaimed = terminalClaimed,
                        terminalResults = terminalResults,
                    )
                }
            }
            // 整体超时默认即生效：到点取消未完成任务，走下方超时收敛路径。
            val futures = pool.invokeAll(callables, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            val results = JSONArray()
            var okCount = 0
            var timeoutCount = 0
            // 超时后有界等待只做一次，避免等待时长随超时任务数叠加。
            var poolStopRequested = false
            for (i in tasks.indices) {
                val f = futures[i]
                val label = tasks[i].label
                if (f.isCancelled) {
                    runCatching { subControllers[i]?.cancel() }
                    if (!poolStopRequested) {
                        poolStopRequested = true
                        // 有界等待收尾线程把已成功的写入记完；线程仍活则如实标记 still_running。
                        runCatching {
                            pool.shutdown()
                            pool.awaitTermination(STILL_RUNNING_WAIT_MS, TimeUnit.MILLISECONDS)
                        }
                    }
                    val stillRunning = !pool.isTerminated
                    // 超时汇总完整回填：changed_files 不截断，主窗口容量由 compact 统一裁决。
                    val changedAll = synchronized(changedFilesByIndex[i]) { changedFilesByIndex[i].toList() }
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fanoutStartNano)
                    val timeoutSource = if (timeoutIsDefault) {
                        "默认 timeout_ms=${timeoutMs}"
                    } else {
                        "显式 timeout_ms=${timeoutMs}"
                    }
                    val timeoutContent = "子代理超时未完成（${timeoutSource} 到点取消）；" +
                        "执行状态未知、可能已部分写入：请先按 changed_files 读取文件核实落盘，再决定缩小任务重试或继续；不要直接重放写操作"
                    val timeoutJson = JSONObject()
                        .put("label", label)
                        .put("ok", false)
                        .put("code", "SUBAGENT_TIMEOUT")
                        .put("content", timeoutContent)
                        .put("verify_hint", "先读 changed_files 核实，再重试；仍超时的任务请拆小或显式加大 timeout_ms")
                        .put("timeout_ms", timeoutMs)
                        .put("thread", "timeout")
                        .put("started_ms", fanoutStartWallMs)
                        .put("finished_ms", fanoutStartWallMs + elapsedMs)
                        .put("duration_ms", elapsedMs)
                        .put("changed_files", JSONArray(changedAll))
                        .apply {
                            if (stillRunning) put("still_running", true)
                        }
                    val won = terminalClaimed[i].compareAndSet(false, true)
                    val committed = terminalResults[i]
                    if (won) {
                        timeoutCount++
                        results.put(timeoutJson)
                        // 超时任务的 runSingle 可能仍在收尾，其迟到 SubagentFinished 会被 compareAndSet 拦下；
                        // 这里补发一行，保证 UI 独立行收敛到终态。
                        onEvent(
                            AgentEvent.SubagentFinished(
                                round = round,
                                toolCallId = call.id,
                                subIndex = i,
                                label = label,
                                ok = false,
                                content = timeoutContent,
                                durationMs = elapsedMs,
                                code = "SUBAGENT_TIMEOUT",
                            ),
                        )
                    } else if (committed != null) {
                        // 超时边界上子代理已抢先上报终态：采用其已提交结果，不再重复上报或覆盖。
                        if (committed.optBoolean("ok", false)) okCount++
                        results.put(committed)
                    } else {
                        timeoutCount++
                        results.put(timeoutJson)
                    }
                } else {
                    val r = runCatching { f.get() }.getOrElse { t ->
                        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fanoutStartNano)
                        // 异常完整回填，不截断：主窗口容量由 compact 统一裁决。
                        val rawMsg = t.message ?: t.javaClass.simpleName
                        JSONObject()
                            .put("label", label)
                            .put("ok", false)
                            .put("code", "SUBAGENT_ERROR")
                            .put("content", rawMsg)
                            .put("thread", Thread.currentThread().name)
                            .put("started_ms", fanoutStartWallMs)
                            .put("finished_ms", fanoutStartWallMs + elapsedMs)
                            .put("duration_ms", elapsedMs)
                            .put("changed_files", JSONArray())
                    }
                    if (r.optBoolean("ok", false)) okCount++
                    results.put(r)
                }
            }
            // 汇总前再检查父取消：若父已取消则抛取消而非返回汇总，避免取消后继续消费结果。
            parentRunController.throwIfCancelled()
            val fanoutElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - fanoutStartNano)
            // 被模式白名单静默滤掉的请求工具名：让主代理可见，避免“点名了却没生效”的困惑。
            val rawRequested = AgentSubagentPolicy.parseRawRequestedAllowedTools(args).orEmpty()
            val effectiveRequested = AgentSubagentPolicy.parseRequestedAllowedTools(args, mode).orEmpty()
            val filteredTools = (rawRequested - effectiveRequested).toList().sorted()
            val summary = JSONObject()
                .put("ok", true)
                .put("mode", mode.wireName)
                .put("context_mode", contextMode.wireName)
                .put("total", tasks.size)
                .put("succeeded", okCount)
                .put("timed_out", timeoutCount)
                .put("fanout_elapsed_ms", fanoutElapsedMs)
                .put("max_rounds", maxRounds)
                .put("timeout_ms", timeoutMs)
                .put("results", results)
                .apply {
                    if (filteredTools.isNotEmpty()) put("filtered_tools", JSONArray(filteredTools))
                }
                .toString()
            val result = AgentModelClient.ToolResult(content = summary)
            onEvent(
                AgentEvent.SubagentsFinished(
                    round = round,
                    toolCallId = call.id,
                    total = tasks.size,
                    succeeded = okCount,
                ),
            )
            return result
        } catch (t: Throwable) {
            subControllers.forEach { runCatching { it?.cancel() } }
            parentRunController.throwIfCancelled()
            return err("SUBAGENT_FANOUT_FAILED", t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { parentBinding.close() }
            pool.shutdownNow()
        }
    }

    private fun runSingle(
        parentRound: Int,
        parentToolCallId: String,
        index: Int,
        task: SubTask,
        maxRounds: Int,
        subTools: JSONArray,
        holders: Array<AgentRunController?>,
        mode: SubagentMode,
        contextMode: SubagentContextMode,
        mounts: List<Pair<String, String>>,
        declaredWritePaths: List<String>,
        writeRegistry: SubagentWriteRegistry,
        changedFiles: MutableSet<String>,
        terminalClaimed: Array<AtomicBoolean>,
        terminalResults: Array<JSONObject?>,
    ): JSONObject {
        val startWallMs = System.currentTimeMillis()
        val startNano = System.nanoTime()
        val threadName = Thread.currentThread().name
        val subController = AgentRunController()
        holders[index] = subController
        val binding = parentRunController.register { subController.cancel() }
        // 子 loop 的 ToolStarted/Finished 转译为 SubagentTool* 事件（带 subIndex/label），
        // 其余子内部事件（Round/Provider/Assistant 流）不再透出，避免悬浮窗与主时间线乱跳。
        // 子工具的真实执行仍走 guarded baseToolExecutor，保证隔离。
        val subOnEvent: (AgentEvent) -> Unit = { e ->
            when (e) {
                is AgentEvent.ToolStarted -> onEvent(
                    AgentEvent.SubagentToolStarted(
                        round = parentRound,
                        toolCallId = parentToolCallId,
                        subIndex = index,
                        label = task.label,
                        innerToolName = e.name,
                        innerToolCallId = e.toolCallId,
                        argsPreview = e.argsPreview,
                    ),
                )
                is AgentEvent.ToolFinished -> onEvent(
                    AgentEvent.SubagentToolFinished(
                        round = parentRound,
                        toolCallId = parentToolCallId,
                        subIndex = index,
                        label = task.label,
                        innerToolName = e.name,
                        innerToolCallId = e.toolCallId,
                        resultSummary = e.resultSummary,
                        success = e.success,
                        detail = e.detail,
                    ),
                )
                else -> Unit
            }
        }
        fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano)
        fun timed(base: JSONObject): JSONObject {
            val elapsed = elapsedMs()
            return base
                .put("thread", threadName)
                .put("started_ms", startWallMs)
                .put("finished_ms", startWallMs + elapsed)
                .put("duration_ms", elapsed)
        }
        // 先上报独立行，UI 立即可见“ label 已启动”，不再只有一个总步骤。
        onEvent(
            AgentEvent.SubagentStarted(
                round = parentRound,
                toolCallId = parentToolCallId,
                subIndex = index,
                label = task.label,
            ),
        )
        fun finishUi(ok: Boolean, content: String, code: String?): JSONObject {
            val elapsed = elapsedMs()
            val changedAll = synchronized(changedFiles) { changedFiles.toList() }
            // 完整回填：不截断 content 与 changed_files，主窗口容量由 compact 统一裁决。
            val resultJson = timed(
                JSONObject()
                    .put("label", task.label)
                    .put("ok", ok)
                    .put("content", content)
                    .put("changed_files", JSONArray(changedAll))
                    .let {
                        var o = it
                        if (code != null) o = o.put("code", code)
                        o
                    },
            )
            // 终态单次提交：先落结果再抢标记；未抢到说明 fanout 已按超时收敛，迟到结果不得覆盖事件语义。
            terminalResults[index] = resultJson
            if (!terminalClaimed[index].compareAndSet(false, true)) return resultJson
            onEvent(
                AgentEvent.SubagentFinished(
                    round = parentRound,
                    toolCallId = parentToolCallId,
                    subIndex = index,
                    label = task.label,
                    ok = ok,
                    content = content,
                    durationMs = elapsed,
                    code = code,
                    changedFiles = changedAll,
                ),
            )
            return resultJson
        }
        try {
            parentRunController.throwIfCancelled()
            val guarded = AgentSubagentPolicy.guardedExecutor(baseToolExecutor, mode)
            val scopedExecutor = if (mode == SubagentMode.CODE) {
                codeScopedExecutor(guarded, index, task.label, mounts, declaredWritePaths, writeRegistry, changedFiles)
            } else {
                guarded
            }
            val subMessages: JSONArray
            val subSystemCount: Int
            if (contextMode == SubagentContextMode.SHARED) {
                // 非纯净：共享主窗口快照作为前缀（只读复制），独立 controller/loop/transcript 运行，
                // 不回写主 messages，终态只经 fanout 汇总返回。
                subMessages = runCatching { JSONArray(parentMessagesProvider().toString()) }.getOrElse { JSONArray() }
                if (subMessages.length() == 0) {
                    for (i in 0 until systemMessages.length()) subMessages.put(systemMessages.getJSONObject(i))
                    subSystemCount = systemMessages.length()
                } else {
                    subSystemCount = parentSystemCount.coerceIn(0, subMessages.length())
                }
            } else {
                subMessages = JSONArray(systemMessages.toString())
                subSystemCount = systemMessages.length()
            }
            val isolatedPrompt = buildString {
                append("你是 Eta 主代理派生的")
                append(if (mode == SubagentMode.CODE) "编码子代理" else "只读子代理")
                append("（label=")
                append(task.label)
                append("，context_mode=")
                append(contextMode.wireName)
                append("）。\n")
                if (contextMode == SubagentContextMode.SHARED) {
                    append("你已获得主 Agent 窗口快照作为前缀上下文（只读，不可改写主会话）；")
                    append("基于该背景独立执行本子任务，仍用独立轮次与工具调用完成，最后汇总返回。\n")
                } else {
                    append("你是纯净隔离执行：仅凭系统提示与下方子任务独立完成，不要假设可见主会话。\n")
                }
                if (mode == SubagentMode.CODE) {
                    if (task.writePaths.isEmpty()) {
                        append("未声明写入范围，禁止编辑任何文件；若任务需要修改文件，请如实返回缺失并说明需要主代理补充 write_paths。\n")
                    } else {
                        append("只允许修改以下范围：")
                        append(task.writePaths.joinToString(", "))
                        append("；超出范围的写操作会被拒绝。\n")
                    }
                    append("编辑用 edit_file 做精确替换（原子写入），新建或整体重写用 write_file；")
                    append("禁止 shell/终端、GUI、浏览器与敏感写操作，禁止再调用 spawn_agents；\n")
                    append("不要运行构建或测试，不要改动与任务无关的文件；由主代理统一验证。\n")
                    append("最后用简洁中文汇报：改了哪些文件、每处改动目的、未完成或存疑之处。\n")
                } else {
                    append("只做事实搜集与整理，不做最终决策；只使用本轮公开的只读工具；主上下文稳定性靠你保护：")
                    append("在隔离窗口内消化原文，只回填蒸馏后的事实摘要，不转储文件全文、长列表或原始长文本；")
                    append("给出结论、关键依据（含文件路径与行范围/记录时间/来源名）与不确定性，缺失直说缺失；")

                    append("禁止 GUI/浏览器/前台操作与任何写操作（含 shell、文件写入），禁止再调用 spawn_agents；")
                    append("如需的工具不可用，直接如实返回缺失，不要编造。\n")
                }
                append("请在最多 ").append(maxRounds).append(" 轮模型调用内完成，")
                append("最后用简洁中文给出")
                append(if (mode == SubagentMode.CODE) "改动结果。" else "事实结果。")
                append("\n<subtask>\n")
                append(task.prompt)
                append("\n</subtask>")
            }
            subMessages.put(AgentConversationCodec.userTextMessage(isolatedPrompt))
            val toolsCopy = JSONArray(subTools.toString())
            val loop = AgentLoop(
                config = config,
                messages = subMessages,
                tools = toolsCopy,
                provider = provider,
                toolExecutor = scopedExecutor,
                runController = subController,
                traceFormatter = traceFormatter,
                onEvent = subOnEvent,
                sessionId = UUID.randomUUID().toString(),
                transcript = JSONArray(),
                systemCount = subSystemCount,
                operationId = "$parentOperationId-sub-$index",
                maxRounds = maxRounds,
            )
            val r = loop.run()
            if (r.roundLimited) {
                // 轮数触顶不丢弃：完整部分结果直接可用（ok=true），主代理可继续使用而非整体重试。
                val partial = r.content
                    .removePrefix(AgentLoop.ROUND_LIMIT_MARKER_PREFIX + maxRounds)
                    .trim()
                val content = if (partial.isNotBlank()) {
                    partial + "\n\n（说明：子代理达到 max_rounds=$maxRounds 上限停止，以上为已完成的部分结果，可直接使用；" +
                        "如需继续请派发新的子任务。）"
                } else {
                    "（子代理在 max_rounds=$maxRounds 内未产生可保留文本）"
                }
                return finishUi(
                    ok = partial.isNotBlank(),
                    content = content,
                    code = "SUBAGENT_ROUND_LIMIT",
                )
            }
            // 完整结果直接回填，不截断。
            val content = r.content.trim()
            return finishUi(
                ok = content.isNotBlank(),
                content = content.ifBlank { "(子代理返回为空)" },
                code = null,
            )
        } catch (t: Throwable) {
            val raw = if (subController.isCancelled || parentRunController.isCancelled) {
                "子代理已取消"
            } else {
                t.message ?: t.javaClass.simpleName
            }
            // 异常完整回填，不截断。
            val resultJson = finishUi(ok = false, content = raw, code = "SUBAGENT_ERROR")
            return resultJson
        } finally {
            runCatching { binding.close() }
        }
    }

    /**
     * code 模式的任务级写入闸门：写工具必须命中本任务声明的范围，
     * 并通过 fanout 级互斥（同一 canonical 文件同时只允许一个子代理写入）；
     * 成功写入会记入 changed_files，供结果与 UI 展示。
     */
    private fun codeScopedExecutor(
        base: AgentModelClient.ToolExecutor,
        index: Int,
        label: String,
        mounts: List<Pair<String, String>>,
        declaredWritePaths: List<String>,
        writeRegistry: SubagentWriteRegistry,
        changedFiles: MutableSet<String>,
    ): AgentModelClient.ToolExecutor =
        AgentModelClient.ToolExecutor { call ->
            if (call.name !in AgentSubagentPolicy.writeTools) {
                base.execute(call)
            } else {
                val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull()
                val path = args?.optString("path")?.trim().orEmpty()
                val canonical = AgentSubagentWritePaths.canonical(path, mounts)
                val declared = declaredWritePaths.any { AgentSubagentWritePaths.contains(it, canonical) }
                when {
                    path.isEmpty() -> reject("INVALID_ARGUMENT", "写入工具缺少 path")
                    !declared -> reject(
                        "WRITE_NOT_DECLARED",
                        "写入超出声明范围：$path；请先在 spawn_agents 的 write_paths 中声明，或在主代理执行",
                    )
                    else -> {
                        val owner = "$label#$index"
                        val holder = writeRegistry.tryAcquire(canonical, owner)
                        if (holder != null) {
                            reject("FILE_BUSY", "文件正被其他子代理写入：$path（占用者 $holder）；请重排任务或稍后重试")
                        } else {
                            try {
                                val result = base.execute(call)
                                if (writeSucceeded(result)) {
                                    synchronized(changedFiles) { changedFiles.add(path) }
                                }
                                result
                            } finally {
                                writeRegistry.release(canonical, owner)
                            }
                        }
                    }
                }
            }
        }

    private fun writeSucceeded(result: AgentModelClient.ToolResult): Boolean =
        runCatching { JSONObject(result.content).optBoolean("ok", false) }.getOrDefault(false)

    private fun reject(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject().put("ok", false).put("code", code).put("message", message).toString(),
        )

    private fun err(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    private companion object {
        /** 超时取消后等待执行池收尾的上限；等待总时长不随超时任务数叠加。 */
        const val STILL_RUNNING_WAIT_MS = 2_000L
    }
}
