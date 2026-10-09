package io.github.mangi.eta.agent.tool

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.device.DeviceControlUnavailableException
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentMode
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.GestureIndicator
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillInstallErrorCode
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillLoader
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.agent.skill.SkillResourceReadResult
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.GitHubSkillInspection
import io.github.mangi.eta.agent.skill.GitHubSkillRepository
import io.github.mangi.eta.agent.skill.GitHubSkillSourceException
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.terminal.AgentCodeSearch
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.data.repository.AgentMemoryException
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

/** memory_write 在角色会话中的只读原因：现实记忆只读，剧情走角色记忆工具。 */
internal const val ROLEPLAY_MEMORY_READ_ONLY_REASON = "角色会话的现实记忆只读；剧情请使用角色记忆工具"

/** memory_write 在编码模式中的只读原因：不主动保存，需要时提示切换聊天模式。 */
internal const val CODING_MEMORY_READ_ONLY_REASON =
    "编码模式下不主动保存记忆；如需长期记住某项内容，请提示用户切换到聊天模式后再保存"

/**
 * 记忆写入拒绝策略：角色会话始终只读；编码模式不主动保存；聊天模式可写。
 * 执行器在每次 memory_write 调用前重新求值，中途切换模式立即生效。
 */
internal fun memoryWriteBlockedReasonFor(roleplay: Boolean, mode: AgentMode): String? =
    when {
        roleplay -> ROLEPLAY_MEMORY_READ_ONLY_REASON
        mode != AgentMode.CHAT -> CODING_MEMORY_READ_ONLY_REASON
        else -> null
    }

internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val browserRunId: String = "",
    private val browserToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
    },
    private val terminalToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
    },
    private val deviceDirectToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS)
    },
    private val deviceSensitiveReadToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS)
    },
    private val deviceSensitiveActionToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
    },
    private val memoryToolsEnabled: () -> Boolean = {
        runBlocking { AgentMemoryRepository.isEnabled() }
    },
    /**
     * memory_write 的拒绝原因；返回 null 表示允许写入。
     * 每次调用时重读（与工具权限同样的“执行期复查”语义）：编码模式中途切换后，
     * 进行中的 run 也会立即停止保存记忆；角色会话在该回调里始终返回只读。
     */
    private val memoryWriteBlockedReason: () -> String? = { null },
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
    )? = null,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    private val skillIndexService: SkillIndexService? = null,
    private val skillLoader: SkillLoader? = null,
    private val skillResourceReader: SkillResourceReader? = null,
    private val githubSkillSource: PublicGitHubSkillSource? = null,
    private val skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
) : AgentModelClient.ToolExecutor, AutoCloseable {

    private val webSearch = AgentWebSearch(
        context = context,
        runId = browserRunId,
        browserEnabled = browserToolsEnabled,
    )

    private val closed = AtomicBoolean(false)
    private val deviceController = RootShellDeviceController(logger, screenshotExcludedPackages, rootAvailable)
    private val rootCommandExecutor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    private val structuredDeviceTools = AgentStructuredDeviceTools(
        context = context,
        logger = logger,
        root = rootCommandExecutor,
        rootAvailable = rootAvailable,
    )
    private val imageTools = AgentImageTools(context, rootCommandExecutor, rootAvailable)
    private val terminalController = RootShellTerminalController(
        logger = logger,
        rootAvailable = rootAvailable,
        linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(context).absolutePath,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
            }
        },
        detachedSupervisor = DetachedTaskSupervisor(
            logger = logger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(context).absolutePath,
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
        selectedLinuxEnvironmentProvider = {
            LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
        },
    )
    private val publishedObservation = AtomicReference(PublishedObservation())
    private val runAvailableSkillIds = runAvailableSkillIds
        .mapTo(mutableSetOf(), SkillParser::normalizeSkillLookup)
    private val mutatedSkillIds = ConcurrentHashMap.newKeySet<String>()
    private val skillTreeMutationUncertain = AtomicBoolean(false)
    private val pendingSkillConflict = AtomicReference(pendingSkillConflict)
    private val inspectedGitHubSnapshots =
        ConcurrentHashMap<String, GitHubInspectionSnapshot>()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        publishedObservation.set(PublishedObservation())
        AgentBrowserSession.interruptAgentAction(browserRunId)
        terminalController.interruptAll()
        rootCommandExecutor.close()
        githubSkillSource?.close()
        inspectedGitHubSnapshots.clear()
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val dispatchCall = canonicalDispatch(toolCall)
        var autoResolvedFrom: String? = null
        var autoResolvedPath: String? = null
        return runCatching {
            val args = toolCall.parsedArgs().getOrThrow()
            var dispatchArgs = dispatchCall.parsedArgs().getOrThrow()
            // 自动查找（find/no_fail）：仅读写类工具、仅显式开关时生效；默认关闭，不影响既有行为。
            when (val lookup = resolvePathWithAutoFind(dispatchCall.name, dispatchArgs)) {
                is AutoFindLookup.Candidates -> return@runCatching textResult(lookup.json)
                is AutoFindLookup.Proceed -> {
                    dispatchArgs = lookup.args
                    autoResolvedFrom = lookup.resolvedFrom
                    autoResolvedPath = lookup.resolvedPath
                }
            }
            if (AgentToolRequirements.find(dispatchCall.name) != null &&
                AgentToolRequirements.rootDenied(dispatchCall.name, dispatchArgs, rootAvailable())
            ) {
                return@runCatching textResult(errorResult("ROOT_REQUIRED", "此操作需要 Root 授权，本次未执行"))
            }
            deviceToolPermissionError(dispatchCall.name)?.let { return@runCatching it }
            memoryToolPermissionError(dispatchCall.name)?.let { return@runCatching it }
            when (val decision = beforeToolExecution(dispatchCall.name)) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    if (decision.code.startsWith("ACCESSIBILITY_")) publishedObservation.set(PublishedObservation())
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            when (dispatchCall.name) {
                "web_search" -> textResult(webSearch.execute(dispatchArgs))
                "get_current_context" -> textResult(
                    DeviceContextTool.current(context, includeLocation = deviceContextLocationEnabled()),
                )
                "search_apps" -> textResult(searchApps(dispatchArgs))
                "launch_app" -> textResult(launchApp(dispatchArgs))
                "open_uri" -> textResult(openUri(dispatchArgs))
                "browser_use" -> browserUse(dispatchArgs, toolCall.id)
                "observe_screen" -> observeScreen(dispatchArgs)
                "tap" -> textResult(tap(dispatchArgs))
                "tap_area" -> textResult(tapArea(dispatchArgs))
                "tap_element" -> textResult(tapElement(dispatchArgs))
                "long_press" -> textResult(longPress(dispatchArgs))
                "long_press_element" -> textResult(longPressElement(dispatchArgs))
                "swipe" -> textResult(swipe(dispatchArgs))
                "scroll" -> textResult(deviceController.scroll(dispatchArgs.optString("direction")))
                "scroll_element" -> textResult(scrollElement(dispatchArgs))
                "input_text" -> textResult(inputText(dispatchArgs))
                "replace_text" -> textResult(replaceText(dispatchArgs))
                "clear_text" -> textResult(clearText(dispatchArgs))
                "set_clipboard" -> textResult(setClipboard(dispatchArgs))
                "get_clipboard" -> textResult(getClipboard())
                "paste_text" -> textResult(pasteText(dispatchArgs))
                "press_key" -> textResult(deviceController.pressKey(dispatchArgs.optString("button")))
                // 契约/schema 用 timeout_ms 表达时长；duration_ms 仅为旧调用的兼容回退。
                "wait" -> textResult(
                    deviceController.waitMs(
                        dispatchArgs.optInt("timeout_ms", dispatchArgs.optInt("duration_ms", 1_000)),
                    ),
                )
                "wait_for_text" -> textResult(waitForText(dispatchArgs))
                "wait_for_package" -> textResult(waitForPackage(dispatchArgs))
                "open_system_panel" -> textResult(deviceController.openSystemPanel(dispatchArgs.optString("panel")))
                in DEVICE_TOOL_NAMES ->
                    structuredDeviceTools.execute(dispatchCall.name, dispatchArgs)
                        ?: textResult(errorResult("UNKNOWN_TOOL", "未知设备工具"))
                "read_image" -> fileVisionTool { imageTools.readImage(dispatchArgs) }
                "terminal" -> textResult(terminalTool { terminal(dispatchArgs) })
                "run_command" -> textResult(terminalTool { runCommand(dispatchArgs) })
                "read_file" -> textResult(terminalTool { readFile(dispatchArgs) })
                "write_file" -> textResult(terminalTool { writeFile(dispatchArgs) })
                "edit_file" -> textResult(terminalTool { editFile(dispatchArgs) })
                "delete_path" -> textResult(terminalTool { deletePath(dispatchArgs) })
                "search_code" -> textResult(terminalTool { searchCode(dispatchArgs) })
                "list_directory" -> textResult(terminalTool { listDirectory(dispatchArgs) })
                "memory_get" -> textResult(memoryGet(dispatchArgs))
                "memory_write" -> textResult(memoryWrite(dispatchArgs))
                "skills_list" -> textResult(skillsList(dispatchArgs))
                "skills_read" -> textResult(skillsRead(dispatchArgs))
                "skills_read_resource" -> textResult(skillsReadResource(dispatchArgs))
                "skills_list_curated" -> textResult(skillsListCurated())
                "skills_inspect_github" -> textResult(skillsInspectGitHub(dispatchArgs))
                "skills_install_from_github" -> textResult(skillsInstallFromGitHub(dispatchArgs))
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${dispatchCall.name}"
                    )
                )
            }
        }.getOrElse { throwable ->
            textResult(
                errorResult(
                    code = when (throwable) {
                        is InvalidToolArgumentException -> "INVALID_ARGUMENT"
                        is DeviceControlUnavailableException -> "ACCESSIBILITY_UNAVAILABLE"
                        else -> "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName
                )
            )
        }.let { result ->
            val annotated = autoResolvedFrom?.let { original ->
                annotateAutoResolved(result, original, autoResolvedPath)
            } ?: result
            annotated.copy(
                sensitive = annotated.sensitive ||
                    AgentSensitiveToolPolicy.isSensitive(toolCall.name) ||
                    AgentSensitiveToolPolicy.isSensitive(dispatchCall.name),
            )
        }
    }

    /** Translate the compact model-facing contract to the existing, well-tested primitives. */
    private fun canonicalDispatch(call: AgentModelClient.ToolCall): AgentModelClient.ToolCall {
        val input = call.parsedArgs().getOrNull() ?: return call
        fun legacy(name: String, args: JSONObject): AgentModelClient.ToolCall =
            AgentModelClient.ToolCall(call.id, name, args.toString())
        return when (call.name) {
            "ui_action" -> {
                val action = input.optString("action").lowercase(Locale.ROOT)
                val args = JSONObject(input.toString()).apply { remove("action") }
                when (action) {
                    "tap" -> legacy(if (input.has("index")) "tap_element" else "tap", args)
                    "long_press" -> legacy(if (input.has("index")) "long_press_element" else "long_press", args)
                    "swipe" -> legacy("swipe", args)
                    "scroll" -> legacy(if (input.has("index")) "scroll_element" else "scroll", args)
                    "input" -> legacy("input_text", args)
                    "clear" -> legacy("clear_text", args)
                    "key" -> legacy("press_key", args)
                    "wait" -> legacy(
                        when (input.optString("condition", "duration")) {
                            "text" -> "wait_for_text"
                            "package" -> "wait_for_package"
                            else -> "wait"
                        },
                        args,
                    )
                    "open_system_panel" -> legacy("open_system_panel", args)
                    else -> call
                }
            }
            "app_action" -> {
                val args = JSONObject(input.toString()).apply { remove("action") }
                legacy(
                    when (input.optString("action")) {
                        "search" -> "search_apps"
                        "launch" -> "launch_app"
                        "open_uri" -> "open_uri"
                        else -> return call
                    },
                    args,
                )
            }
            "device_info" -> {
                val operation = input.optString("operation")
                val args = JSONObject(input.toString()).apply {
                    // get_device_environment 依赖 operation 区分调用入口与身份回显（实测 P2-1），
                    // 只有这条需要保留；其余 operation 的下游按旧行为去掉该键。
                    if (operation != "environment") remove("operation")
                }
                legacy(
                    when (operation) {
                        "context" -> "get_current_context"
                        "status" -> "device_status"
                        "network" -> "network_info"
                        "environment" -> "get_device_environment"
                        "top_memory" -> "top_memory_apps"
                        "top_storage" -> "top_storage_apps"
                        else -> return call
                    },
                    args,
                )
            }
            "device_control" -> {
                val operation = input.optString("operation")
                val args = JSONObject(input.toString()).apply {
                    remove("operation")
                    if (has("media_action")) put("action", optString("media_action"))
                    remove("media_action")
                }
                legacy(
                    when (operation) {
                        "alarm" -> "set_alarm"
                        "timer" -> "set_timer"
                        "media" -> "media_control"
                        "volume" -> "set_volume"
                        else -> return call
                    },
                    args,
                )
            }
            "clipboard" -> {
                val args = JSONObject(input.toString()).apply { remove("operation") }
                legacy(
                    when (input.optString("operation")) {
                        "get" -> "get_clipboard"
                        "set" -> "set_clipboard"
                        "paste" -> "paste_text"
                        else -> return call
                    },
                    args,
                )
            }
            "terminal" -> call
            "file_ops" -> {
                val args = JSONObject(input.toString()).apply {
                    remove("operation")
                    if (!has("pattern") && has("query")) put("pattern", opt("query"))
                }
                legacy(
                    when (input.optString("operation")) {
                        "read" -> "read_file"
                        "write" -> "write_file"
                        "edit" -> "edit_file"
                        "search" -> "search_code"
                        "list" -> "list_directory"
                        "delete" -> "delete_path"
                        else -> return call
                    },
                    args,
                )
            }
            "skill" -> {
                val args = JSONObject(input.toString()).apply {
                    remove("operation")
                    if (has("skill_id")) put("skillId", opt("skill_id"))
                    if (has("relative_path")) put("relativePath", opt("relative_path"))
                    if (has("max_chars")) put("maxChars", opt("max_chars"))
                }
                legacy(
                    when (input.optString("operation")) {
                        "list" -> "skills_list"
                        "read" -> "skills_read"
                        "resource" -> "skills_read_resource"
                        "curated" -> "skills_list_curated"
                        else -> return call
                    },
                    args,
                )
            }
            "skill_github" -> {
                val args = JSONObject(input.toString()).apply {
                    remove("operation")
                    if (has("replace_existing")) put("replaceExisting", opt("replace_existing"))
                    if (has("expected_replacement_id")) put("expectedReplacementId", opt("expected_replacement_id"))
                }
                legacy(
                    if (input.optString("operation") == "inspect") "skills_inspect_github" else "skills_install_from_github",
                    args,
                )
            }
            "memory" -> {
                val args = JSONObject(input.toString()).apply { remove("operation") }
                legacy(if (input.optString("operation") == "write") "memory_write" else "memory_get", args)
            }
            else -> call
        }
    }

    private fun deviceToolPermissionError(
        toolName: String,
    ): AgentModelClient.ToolResult? {
        val error = when {
            toolName in DEVICE_DIRECT_TOOL_NAMES && !deviceDirectToolsEnabled() ->
                "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
            toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES && !deviceSensitiveReadToolsEnabled() ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES && !deviceSensitiveActionToolsEnabled() ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            else -> null
        } ?: return null
        return AgentModelClient.ToolResult(
            content = errorResult(error.first, error.second),
            sensitive = toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES ||
                toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES,
        )
    }

    private fun terminalTool(block: () -> String): String {
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        return block()
    }

    private fun fileVisionTool(block: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): AgentModelClient.ToolResult? {
        if (toolName == "memory_write") {
            memoryWriteBlockedReason()?.let { reason ->
                return AgentModelClient.ToolResult(
                    content = errorResult("REAL_MEMORY_READ_ONLY", reason),
                    sensitive = true,
                )
            }
        }
        if (toolName !in MEMORY_TOOL_NAMES || memoryToolsEnabled()) return null
        return AgentModelClient.ToolResult(
            content = errorResult("MEMORY_DISABLED", "记忆已在设置中关闭"),
            sensitive = true,
        )
    }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.getString("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.getString("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        when (val result = AgentMemoryRepository.mutate(mutation)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val result = AgentBrowserSession.execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
        )
        return AgentModelClient.ToolResult(
            content = result.content,
            images = result.images.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = "agent_browser",
                    preserveOriginal = true,
                )
            },
        )
    }

    /**
     * get_current_context 的位置字段与 get_current_location 同一门槛：敏感读取开关 + 后台定位授权。
     * 否则这里会成为绕过敏感读授权的定位读取入口。
     */
    private fun deviceContextLocationEnabled(): Boolean =
        deviceSensitiveReadToolsEnabled() && AgentToolCapabilities.locationAccessGranted(context)

    private fun observeScreen(args: JSONObject): AgentModelClient.ToolResult {
        publishedObservation.set(PublishedObservation())
        val startedAt = SystemClock.elapsedRealtime()
        val options = AgentScreenObservationContract.resolve(args)
        val observation = screenObservationProvider?.invoke(options)
            ?: deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
            )
        publishedObservation.set(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed " +
                "observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return AgentModelClient.ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image)
        )
    }

    private fun tap(args: JSONObject): String {
        val point = convertPoint(
            x = args.optInt("x"),
            y = args.optInt("y"),
            coordinateSpace = args.optString("coordinate_space")
        )
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
        showTap(point.x, point.y)
        return deviceController.tap(point.x, point.y)
    }

    private fun tapArea(args: JSONObject): String {
        val x1 = args.optInt("x1")
        val y1 = args.optInt("y1")
        val x2 = args.optInt("x2")
        val y2 = args.optInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace)
        val second = convertPoint(x2, y2, coordinateSpace)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
        showTap(point.x, point.y)
        return deviceController.tap(point.x, point.y)
    }

    private fun tapElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = deviceController.tapElement(observation, index)
        if (result.isOkJson()) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            showTap(node.centerX, node.centerY)
        }
        return result
    }

    private fun longPressElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = deviceController.longPressElement(observation, index, durationMs)
        if (result.isOkJson()) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            showLongPress(node.centerX, node.centerY, durationMs)
        }
        return result
    }

    private fun longPress(args: JSONObject): String {
        val point = convertPoint(
            x = args.optInt("x"),
            y = args.optInt("y"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val durationMs = args.optInt("duration_ms", 800)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
        showLongPress(point.x, point.y, durationMs)
        return deviceController.longPress(point.x, point.y, durationMs)
    }

    private fun swipe(args: JSONObject): String {
        val start = convertPoint(
            x = args.optInt("x1"),
            y = args.optInt("y1"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val end = convertPoint(
            x = args.optInt("x2"),
            y = args.optInt("y2"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val durationMs = args.optInt("duration_ms", 500)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
        showSwipe(start.x, start.y, end.x, end.y, durationMs)
        return deviceController.swipe(
            start.x,
            start.y,
            end.x,
            end.y,
            durationMs
        )
    }

    private fun scrollElement(args: JSONObject): String {
        val observation = requireElementObservation(args) ?: return observationError(args)
        return deviceController.scrollElement(
            observation = observation,
            index = args.optInt("index", -1),
            direction = args.optString("direction")
        )
    }

    private fun inputText(args: JSONObject): String {
        val text = args.optString("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text)
        }
    }

    private fun replaceText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.replaceText(
            text = args.optString("text"),
            index = index,
            observation = observation,
        )
    }

    private fun clearText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.clearText(index = index, observation = observation)
    }

    private fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.optString("text"))

    private fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    private fun pasteText(args: JSONObject): String =
        deviceController.pasteText(args.optString("text"))

    private fun waitForText(args: JSONObject): String =
        deviceController.waitForText(
            text = args.optString("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains")
        )

    private fun waitForPackage(args: JSONObject): String =
        deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000)
        )

    private fun convertPoint(x: Int, y: Int, coordinateSpace: String): ScreenPoint {
        val space = publishedObservation.get().coordinateSpace
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace == "screen" || (requestedSpace.isBlank() && space == null)) {
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions()
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (space == null) {
            throw InvalidToolArgumentException(
                "当前没有可用的截图坐标系；请先 observe_screen，或明确设置 coordinate_space=screen",
            )
        }
        val point = runCatching { space.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    private fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }

    private fun launchApp(args: JSONObject): String {
        val packageName = args.optString("package_name").trim().ifBlank { null }
        val appName = args.optString("app_name").trim().ifBlank { null }

        val app = if (packageName != null) {
            findAppByPackage(packageName) ?: AppInfo(packageName = packageName, appName = appName ?: packageName)
        } else {
            if (appName == null) {
                return errorResult("INVALID_ARGUMENT", "package_name 和 app_name 至少提供一个")
            }
            val matches = findAppsByName(appName, includeSystem = false)
            val exactMatches = matches.filter { it.appName.equals(appName, ignoreCase = true) }
            when {
                exactMatches.size == 1 -> exactMatches.single()
                matches.size == 1 -> matches.single()
                matches.isEmpty() -> return errorResult(
                    code = "APP_NOT_FOUND",
                    message = "未找到应用：$appName"
                )
                else -> return JSONObject()
                    .put("ok", false)
                    .put("code", "AMBIGUOUS_APP")
                    .put("message", "匹配到多个应用，请指定 package_name")
                    .put("candidates", matches.take(10).toJsonArray())
                    .toString()
            }
        }

        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            return errorResult(
                code = "APP_NOT_LAUNCHABLE",
                message = "应用不可启动或未安装：${app.packageName}"
            )
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
        logger.info("Agent local tool action=launch_app outcome=started")
        return JSONObject()
            .put("ok", true)
            .put("tool", "launch_app")
            .put("app_name", app.appName)
            .put("package_name", app.packageName)
            .toString()
    }

    private fun openUri(args: JSONObject): String {
        val uriText = args.optString("uri").trim()
        if (uriText.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 不能为空")
        }
        val uri = Uri.parse(uriText)
        if (uri.scheme.isNullOrBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 缺少 scheme")
        }
        val context = requireContext()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!HookSupport.resolvesActivity(context, intent)) {
            return errorResult("NO_ACTIVITY", "没有应用可以处理该 URI")
        }
        context.startActivity(intent)
        logger.info("Agent local tool action=open_uri outcome=started")
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme?.lowercase(Locale.ROOT))
            .also { result ->
                if (uri.scheme.equals("https", true)) {
                    result.put("display_uri", uriText)
                }
            }
            .toString()
    }

    private fun runCommand(args: JSONObject): String =
        terminalController.runCommand(
            command = args.optString("command"),
            cwd = args.optString("cwd").ifBlank { null },
            timeoutSeconds = args.optInt("timeout_seconds", 30)
        )

    private fun terminal(args: JSONObject): String {
        return terminalController.terminalAction(
            action = args.optString("action", "open_and_exec"),
            command = args.optString("command"),
            cwd = args.optString("cwd").ifBlank { null },
            timeoutMs = args.optInt("timeout_ms", 30_000),
            identity = args.optString("identity"),
            mergeStderr = args.optBoolean("merge_stderr", false),
            sessionId = args.optString("session_id").ifBlank { null },
            jobId = args.optString("job_id").ifBlank { null },
            async = args.optBoolean("async", false),
            offsetChars = args.optInt("offset_chars", 0),
            maxChars = args.optInt("max_chars", 8_000),
            closeIfDone = args.optBoolean("close_if_done", false),
            environment = args.optString("environment").ifBlank { defaultTerminalEnvironment() },
            taskId = args.optString("task_id").ifBlank { null },
            daemonLimit = args.optInt("limit", 10),
            daemonRunningOnly = args.optBoolean("running_only", false),
            daemonState = args.optString("state").ifBlank { null },
            // daemon_list 翻页：schema 已声明 offset，缺省 0 时行为与旧版一致。
            daemonOffset = args.optInt("offset", 0),
        )
    }

    /**
     * 未显式指定 environment 时的默认环境，运行期决定，不写死：
     * Linux 工具环境已安装即默认 linux（找代码/处理数据的默认工作环境），否则回退 android。
     * 设备数据获取应由模型按提示显式传 environment=android。
     */
    private fun defaultTerminalEnvironment(): String =
        if (runCatching { LinuxEnvironmentSettingsRepository.isEnvironmentReady(context) }.getOrDefault(false)) {
            "linux"
        } else {
            "android"
        }

    private fun readFile(args: JSONObject): String =
        withResolvedPathEcho(
            terminalController.readFile(
                path = args.optString("path"),
                offsetBytes = args.optInt("offset_bytes", 0),
                maxBytes = args.optInt("max_bytes", 65_536)
            ),
            args.optString("path"),
        )

    private fun writeFile(args: JSONObject): String =
        withResolvedPathEcho(
            terminalController.writeFile(
                path = args.optString("path"),
                content = args.optString("content"),
                append = args.optBoolean("append", false)
            ),
            args.optString("path"),
        )

    private fun listDirectory(args: JSONObject): String =
        withWorkspaceMountsView(
            withResolvedPathEcho(
                terminalController.listDirectory(
                    path = args.optString("path"),
                    showHidden = args.optBoolean("show_hidden", false),
                    limit = args.optInt("limit", 80),
                    offset = args.optInt("offset", 0),
                    glob = args.optString("glob"),
                    recursive = args.optBoolean("recursive", false),
                ),
                args.optString("path"),
            ),
        )

    /**
     * 列目录或搜索落在工作区根（当前 run 的干净工作区 / 全局工作区）时，把共享挂载一并暴露出来：
     * 结果里直接给出 /workspace/mounts/<name> 与对应 Android 源，模型不必猜挂载里有什么。
     */
    private fun withWorkspaceMountsView(content: String): String {
        val json = runCatching { JSONObject(content) }.getOrNull() ?: return content
        if (!json.optBoolean("ok")) return content
        val resolved = json.optString("resolved_path").ifBlank { json.optString("path") }.trimEnd('/')
        if (resolved.isBlank() || !isWorkspaceRoot(resolved)) return content
        val mounts = runCatching { SharedFolderMounts.current() }.getOrDefault(emptyList())
        val view = JSONArray()
        mounts.forEach { mount ->
            view.put(
                JSONObject()
                    .put("name", mount.name)
                    .put("path", "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}")
                    .put("source", mount.sourcePath),
            )
        }
        json.put("mounts", view)
            .put(
                "mounts_note",
                if (mounts.isEmpty()) {
                    "当前未配置共享文件夹，/workspace/mounts 下没有挂载。"
                } else {
                    "共享挂载用 /workspace/mounts/<name>/... 访问（代码库通常就在这里）；" +
                        "要连同挂载一起搜索，在 file_ops search 传 include_mounts=true。"
                },
            )
        return json.toString()
    }

    private fun isWorkspaceRoot(path: String): Boolean =
        listOf(
            runCatching { TerminalRuntime.currentToolWorkspaceRoot() }.getOrNull(),
            runCatching { TerminalRuntime.currentLinuxWorkspaceRoot() }.getOrNull(),
        ).filterNotNull().any { it.trimEnd('/') == path }

    private fun editFile(args: JSONObject): String =
        withResolvedPathEcho(
            terminalController.editFile(
                path = args.optString("path"),
                oldText = args.optString("old_string"),
                newText = args.optString("new_string"),
                replaceAll = args.optBoolean("replace_all", false),
            ),
            args.optString("path"),
        )

    private fun searchCode(args: JSONObject): String =
        withWorkspaceMountsView(
            withResolvedPathEcho(
                terminalController.searchCode(
                    rootPath = args.optString("path"),
                    pattern = args.optString("pattern"),
                    glob = args.optString("glob"),
                    maxResults = args.optInt("max_results", AgentCodeSearch.MAX_RESULTS),
                    includeMounts = args.optBoolean("include_mounts", false),
                ),
                args.optString("path"),
            ),
        )

    private fun deletePath(args: JSONObject): String =
        withResolvedPathEcho(
            terminalController.deletePath(
                path = args.optString("path"),
                recursive = args.optBoolean("recursive", false),
            ),
            args.optString("path"),
        )

    /**
     * file 类工具的结果回显：把结果 JSON 中解析后的绝对路径复制为 resolved_path；
     * 输入是相对路径（或为空）时追加 resolved_note 说明基准，避免调用方按错误基准继续调用（实测 P2-2）。
     */
    private fun withResolvedPathEcho(result: String, requestedPath: String): String {
        val json = runCatching { JSONObject(result) }.getOrNull() ?: return result
        val resolved = json.optString("path").takeIf { it.isNotBlank() } ?: return result
        json.put("resolved_path", resolved)
        val requested = requestedPath.trim()
        when {
            requested.isEmpty() -> json.put("resolved_note", "未指定路径，已使用当前工作区根：$resolved")
            !requested.startsWith("/") && requested != "~" && !requested.startsWith("~/") ->
                json.put("resolved_note", "相对路径已按当前工作区根解析：$requested → $resolved")
        }
        return json.toString()
    }

    // ── 读写工具的自动查找（find / no_fail，默认关闭） ──────────────────────

    /** 支持自动查找的工具：只覆盖读写路径的工具，不触碰 search_code/list_directory 等检索工具。 */
    private val autoFindTools = setOf("read_file", "write_file", "edit_file", "read_image")

    private sealed interface AutoFindLookup {
        /** 继续执行；[resolvedFrom] 非空表示路径由自动查找改写而来。 */
        data class Proceed(
            val args: JSONObject,
            val resolvedFrom: String? = null,
            val resolvedPath: String? = null,
        ) : AutoFindLookup

        /** 不执行本次读写，直接返回候选列表。 */
        data class Candidates(val json: String) : AutoFindLookup
    }

    /**
     * `find` / `no_fail` 的路径解析：目标文件不存在时按名在工作区查找。
     *
     * - `find=true`：唯一命中（读类到档位 2、写/编辑只到档位 1）直接采用；其余返回候选；
     * - `no_fail=true`：只返回候选、不自动采用（优先级高于 find）；
     * - 目标已存在、URI、工具不在 [autoFindTools]、查找不可用时一律保持原参数走既有流程。
     */
    private fun resolvePathWithAutoFind(toolName: String, args: JSONObject): AutoFindLookup {
        if (toolName !in autoFindTools) return AutoFindLookup.Proceed(args)
        val find = args.optBoolean("find", false)
        val noFail = args.optBoolean("no_fail", false)
        if (!find && !noFail) return AutoFindLookup.Proceed(args)
        val rawPath = args.optString("path").trim()
        if (rawPath.isEmpty() || rawPath.startsWith("content://") || rawPath.startsWith("file://")) {
            return AutoFindLookup.Proceed(args)
        }
        val requestedName = rawPath.substringAfterLast('/')
        if (requestedName.isEmpty()) return AutoFindLookup.Proceed(args)
        val showHidden = requestedName.startsWith('.')
        val workspaceRoot = listingOf("", recursive = false, glob = "", showHidden = false, limit = 1)
            ?.takeIf { it.ok }
            ?.root
            ?.takeIf { it.isNotBlank() }
        if (workspaceRoot == null) {
            // 工作区根本列不出来：no_fail=true 时给出结构化结论，不允许裸 NOT_FOUND（P2-3）。
            return if (noFail) {
                autoFindUnavailableResult(toolName, rawPath, requestedName, scanRoot = "")
            } else {
                AutoFindLookup.Proceed(args)
            }
        }
        val parent = rawPath.substringBeforeLast('/', "").ifBlank { workspaceRoot }
        // 目标已存在（含同名目录）时不介入：既有流程会正常读取或给出 IS_DIRECTORY。
        val parentListing = listingOf(parent, recursive = false, glob = requestedName, showHidden = showHidden, limit = 50)
        if (parentListing?.ok == true &&
            parentListing.entriesText.lineSequence().any { line ->
                val trimmed = line.trim()
                trimmed.length > 2 && trimmed.substring(2).trim() == requestedName
            }
        ) {
            return AutoFindLookup.Proceed(args)
        }
        // 目标缺失：父目录可能整体不存在，向上找最近存在的目录作为扫描根（找不到退回工作区根）。
        val scanRoot = WorkspaceAutoFind.nearestExistingAncestor(
            path = parent,
            dirExists = { candidate ->
                listingOf(candidate, recursive = false, glob = "", showHidden = false, limit = 1)?.ok == true
            },
        ) ?: workspaceRoot
        val outcome = WorkspaceAutoFind.findIn(scanRoot, requestedName) { root, glob ->
            listingOf(root, recursive = true, glob = glob, showHidden = showHidden, limit = 200)
        }
        if (outcome == null) {
            // 枚举完全不可用（list_directory 抛错）：no_fail=true 时仍要给出结构化结论，
            // 不能退化成裸 NOT_FOUND；find 单独开启时保持既有行为走正常错误流程（P2-3）。
            return if (noFail) {
                autoFindUnavailableResult(toolName, rawPath, requestedName, scanRoot)
            } else {
                AutoFindLookup.Proceed(args)
            }
        }
        val adoptTier = when (toolName) {
            "write_file", "edit_file" -> WorkspaceAutoFind.WRITE_ADOPT_TIER
            else -> WorkspaceAutoFind.READ_ADOPT_TIER
        }
        val candidates = outcome.candidates.take(WorkspaceAutoFind.MAX_CANDIDATES)
        WorkspaceAutoFind.adoptionDecision(candidates, requestedName, adoptTier, find, noFail)?.let { adopted ->
            return AutoFindLookup.Proceed(
                args = JSONObject(args.toString()).apply { put("path", adopted) },
                resolvedFrom = rawPath,
                resolvedPath = adopted,
            )
        }
        if (!WorkspaceAutoFind.shouldReturnCandidateList(candidates.size, noFail)) {
            return AutoFindLookup.Proceed(args)
        }
        return AutoFindLookup.Candidates(
            autoFindCandidatesJson(
                toolName = toolName,
                requestedPath = rawPath,
                scanRoot = outcome.scanRoot,
                truncated = outcome.truncated,
                candidates = candidates,
                requestedName = requestedName,
                adoptTier = adoptTier,
                noFail = noFail,
            ),
        )
    }

    /** 复用 list_directory（两种身份同一口径）作为自动查找的枚举后端。 */
    private fun listingOf(
        path: String,
        recursive: Boolean,
        glob: String,
        showHidden: Boolean,
        limit: Int,
    ): WorkspaceAutoFind.Listing? = runCatching {
        JSONObject(terminalController.listDirectory(path, showHidden, limit, 0, glob, recursive))
    }.getOrNull()?.let { json ->
        WorkspaceAutoFind.Listing(
            ok = json.optBoolean("ok", false),
            root = json.optString("path"),
            entriesText = json.optString("entries_text"),
            truncated = json.optBoolean("truncated", false),
        )
    }

    /** no_fail 下工作区不可枚举时的结构化结论：FILE_CANDIDATES + count=0 + search_unavailable（P2-3）。 */
    private fun autoFindUnavailableResult(
        toolName: String,
        requestedPath: String,
        requestedName: String,
        scanRoot: String,
    ): AutoFindLookup.Candidates = AutoFindLookup.Candidates(
        autoFindCandidatesJson(
            toolName = toolName,
            requestedPath = requestedPath,
            scanRoot = scanRoot,
            truncated = false,
            candidates = emptyList(),
            requestedName = requestedName,
            adoptTier = WorkspaceAutoFind.READ_ADOPT_TIER,
            noFail = true,
            searchUnavailable = true,
        ),
    )

    /**
     * 候选列表（no_fail / 多候选）：每条候选带匹配层级与未自动采用原因，便于模型直接选择；
     * no_fail=true 且零候选时同样返回该结构（count=0），不允许裸 NOT_FOUND（实测 P2-3）。
     */
    private fun autoFindCandidatesJson(
        toolName: String,
        requestedPath: String,
        scanRoot: String,
        truncated: Boolean,
        candidates: List<String>,
        requestedName: String,
        adoptTier: Int,
        noFail: Boolean,
        searchUnavailable: Boolean = false,
    ): String {
        val ranked = WorkspaceAutoFind.rankCandidates(candidates, requestedName)
        val tierCounts = ranked
            .mapNotNull { path -> WorkspaceAutoFind.nameTier(path.substringAfterLast('/'), requestedName) }
            .groupingBy { it }
            .eachCount()
        val candidateDetails = JSONArray()
        ranked.forEach { path ->
            val tier = WorkspaceAutoFind.nameTier(path.substringAfterLast('/'), requestedName) ?: 0
            candidateDetails.put(
                JSONObject()
                    .put("path", path)
                    .put("tier", WorkspaceAutoFind.tierName(tier))
                    .put(
                        "reason_not_adopted",
                        WorkspaceAutoFind.nonAdoptionReason(tier, tierCounts[tier] ?: 1, adoptTier, noFail),
                    ),
            )
        }
        return JSONObject()
            .put("ok", false)
            .put("tool", toolName)
            .put("code", "FILE_CANDIDATES")
            .put("requested_path", requestedPath)
            .put("scan_root", scanRoot)
            .put("count", ranked.size)
            .put("candidates", candidateDetails)
            .put("truncated", truncated)
            .put("search_unavailable", searchUnavailable)
            .put(
                "message",
                buildString {
                    append("目标不存在：").append(requestedPath).append("。")
                    when {
                        searchUnavailable ->
                            append(
                                "工作区枚举不可用（Root 会话或目录读取失败），无法给出候选；" +
                                    "请先用 list_directory 确认工作区可访问后重试，或直接传绝对路径。",
                            )
                        ranked.isEmpty() ->
                            append("已按 no_fail 返回候选：未找到匹配的文件；请核对文件名，或先用 list_directory/search_code 确认工作区内容。")
                        else -> {
                            append(
                                if (noFail) {
                                    "已按 no_fail 返回工作区候选（不自动采用）："
                                } else {
                                    "自动查找命中候选但无法唯一确定（未自动采用）："
                                },
                            )
                            append(ranked.joinToString("；"))
                            append("。请选择正确路径后重新调用。")
                        }
                    }
                    if (truncated) append("枚举达到上限，候选可能不完整。")
                },
            )
            .toString()
    }

    /** 自动采用后把"原路径 → 实际采用路径"回填进结果，避免模型继续按错误路径理解。 */
    private fun annotateAutoResolved(
        result: AgentModelClient.ToolResult,
        originalPath: String,
        resolvedPath: String?,
    ): AgentModelClient.ToolResult {
        val note = "目标路径不存在，已按 find 自动解析：" + originalPath +
            (resolvedPath?.let { " → $it" } ?: "")
        val content = runCatching {
            JSONObject(result.content)
                .put("resolved_from", originalPath)
                .put("resolved_note", note)
                .toString()
        }.getOrElse { result.content + "\n" + note }
        return result.copy(content = content)
    }

    private fun findAppByPackage(packageName: String): AppInfo? =
        installedLauncherApps().firstOrNull { it.packageName == packageName }

    private fun findAppsByName(query: String, includeSystem: Boolean): List<AppInfo> {
        val normalizedQuery = query.normalized()
        return installedLauncherApps()
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .mapNotNull { app ->
                val score = app.matchScore(query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppInfo>> { it.first }.thenBy { it.second.appName })
            .map { it.second }
            .toList()
    }

    private fun AppInfo.matchScore(rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = appName.normalized()
        val normalizedPackage = packageName.normalized()
        return when {
            packageName.equals(rawQuery, ignoreCase = true) -> 0
            appName.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun installedLauncherApps(): List<AppInfo> {
        val context = requireContext()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
        val apps = linkedMapOf<String, AppInfo>()
        resolveInfos.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val applicationInfo = activityInfo.applicationInfo ?: return@forEach
            val packageName = applicationInfo.packageName ?: return@forEach
            val appName = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf { it.isNotBlank() }
                ?: packageName
            apps.putIfAbsent(
                packageName,
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            )
        }
        return apps.values.toList()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun List<AppInfo>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { app ->
                array.put(
                    JSONObject()
                        .put("app_name", app.appName)
                        .put("package_name", app.packageName)
                        .put("is_system_app", app.isSystemApp)
                )
            }
        }

    private fun String.normalized(): String =
        trim().lowercase(Locale.ROOT)

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun requireElementObservation(
        args: JSONObject,
    ): RootShellDeviceController.ElementObservation? {
        val current = publishedObservation.get().elements ?: return null
        val requestedId = args.optString("observation_id").trim()
        return current.takeIf {
            ObservationReferencePolicy.validate(current.id, requestedId) ==
                ObservationReferencePolicy.Status.MATCH
        }
    }

    private fun observationError(args: JSONObject): String {
        val current = publishedObservation.get().elements
        val requestedId = args.optString("observation_id").trim()
        return when (ObservationReferencePolicy.validate(current?.id, requestedId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                errorResult("NO_OBSERVATION", "请先调用 observe_screen 获取 UI 节点")
            ObservationReferencePolicy.Status.ID_REQUIRED -> errorResult(
                "OBSERVATION_ID_REQUIRED",
                "节点动作必须携带同一次 observe_screen 返回的 observation_id",
            )
            ObservationReferencePolicy.Status.STALE -> errorResult(
                "STALE_OBSERVATION",
                "observation_id=$requestedId 已过期；当前为 ${current?.id}，请重新观察屏幕",
            )
            ObservationReferencePolicy.Status.MATCH -> errorResult(
                "OBSERVATION_ERROR",
                "观察快照状态异常，请重新观察屏幕",
            )
        }
    }

    // ==================== Skills tools ====================

    private fun skillsList(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val entries = indexService.listInstalledSkills()
            .filter { entry -> SkillCompatibilityChecker.evaluate(entry).available }
            .filter { entry -> isVisibleInCurrentRun(entry.id) }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", entry.rootPath)
                    .put("skillFilePath", entry.skillFilePath)
                    .put("capabilities", capabilities)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val loader = skillLoader
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能加载器未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .toString()
    }

    private fun skillsReadResource(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val reader = skillResourceReader
            ?: return errorResult("SKILLS_UNAVAILABLE", "Skill 资源读取器未初始化")
        val skillId = args.getString("skillId").trim()
        val relativePath = args.getString("relativePath").trim()
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到已启用 Skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult(
                "INCOMPATIBLE",
                compatibility.reason ?: "当前环境不可用",
            )
        }
        return when (val result = reader.readText(entry, relativePath)) {
            is SkillResourceReadResult.Success -> {
                val truncated = result.text.length > maxChars
                val visibleText = if (truncated) {
                    result.text.take(maxChars).let { prefix ->
                        if (prefix.lastOrNull()?.isHighSurrogate() == true) {
                            prefix.dropLast(1)
                        } else {
                            prefix
                        }
                    }
                } else {
                    result.text
                }
                JSONObject()
                    .put("ok", true)
                    .put("skillId", entry.id)
                    .put("relativePath", result.relativePath)
                    .put("text", visibleText)
                    .put("truncated", truncated)
                    .put("totalChars", result.text.length)
                    .toString()
            }
            is SkillResourceReadResult.Failure -> errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private fun skillsListCurated(): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val inspection = source.listCurated()
            rememberInspection(
                repository = GitHubSkillRepositoryParser.parse(inspection.repository),
                inspection = inspection,
                rememberDefault = true,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInspectGitHub(args: JSONObject): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val repository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = args.optString("path").takeIf { args.has("path") },
            )
            val inspection = source.inspect(repository)
            rememberInspection(
                repository = repository,
                inspection = inspection,
                rememberDefault = repository.ref == null,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInstallFromGitHub(args: JSONObject): String {
        val replaceExisting = args.optBoolean("replaceExisting", false)
        return skillSourceResult {
            val requestedRepository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = null,
            )
            val pathsJson = args.getJSONArray("paths")
            val selectedPaths = (0 until pathsJson.length()).map { index ->
                GitHubSkillRepositoryParser.normalizeRelativePath(pathsJson.getString(index))
            }
            if (replaceExisting && selectedPaths.size != 1) {
                return@skillSourceResult errorResult(
                    "SKILL_REPLACE_SCOPE_TOO_BROAD",
                    "一次只能替换一个 Skill 路径；请逐个重试",
                )
            }
            val expectedReplacementId = args.optString("expectedReplacementId").trim()
            val repository = if (replaceExisting) {
                validateReplacementReplay(
                    requestedRepository = requestedRepository,
                    selectedPaths = selectedPaths,
                    expectedReplacementId = expectedReplacementId,
                )?.let { return@skillSourceResult it }
                requestedRepository.copy(ref = pendingSkillConflict.get()!!.commitSha)
            } else {
                val snapshot = inspectedGitHubSnapshots[
                    inspectionKey(requestedRepository.slug, requestedRepository.ref)
                ] ?: return@skillSourceResult errorResult(
                    "SKILL_INSPECTION_REQUIRED",
                    "安装前必须在本轮先检查同一仓库与 ref 的 Skill 候选",
                )
                val invalidSelection = selectedPaths.firstOrNull {
                    it !in snapshot.candidatesByPath
                }
                if (invalidSelection != null) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查返回的候选中：$invalidSelection",
                    )
                }
                val snapshotPrefix = snapshot.prefix
                if (
                    snapshotPrefix != null &&
                    selectedPaths.any {
                        it != snapshotPrefix && !it.startsWith("$snapshotPrefix/")
                    }
                ) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查的目录范围内",
                    )
                }
                requestedRepository.copy(ref = snapshot.commitSha)
            }
            val prefix = requestedRepository.path?.takeUnless { it == "." }
            if (
                prefix != null &&
                selectedPaths.any { it != prefix && !it.startsWith("$prefix/") }
            ) {
                return@skillSourceResult errorResult(
                    "INVALID_SKILL_SELECTION",
                    "所选路径不在 GitHub URL 指定目录内",
                )
            }
            val source = githubSkillSource
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "GitHub Skill 服务未初始化",
                )
            val installer = skillPackageInstaller
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "Skill 安装器未初始化",
                )
            source.downloadArchive(repository).use { archive ->
                if (closed.get()) {
                    return@skillSourceResult errorResult(
                        "SKILL_INSTALL_CANCELLED",
                        "Skill 安装已取消，未提交文件",
                    )
                }
                val result = installer.installRepositoryZip(
                    openStream = { archive.file.inputStream() },
                    selectedPaths = selectedPaths,
                    replaceUserSkills = replaceExisting,
                    expectedReplacementIds = if (replaceExisting) {
                        setOf(expectedReplacementId)
                    } else {
                        emptySet()
                    },
                    isCancelled = closed::get,
                )
                installResult(
                    result = result,
                    repository = archive.repository,
                    ref = archive.ref,
                    commitSha = archive.commitSha,
                    selectedPaths = selectedPaths,
                )
            }
        }
    }

    private fun inspectionResult(
        inspection: io.github.mangi.eta.agent.skill.GitHubSkillInspection,
    ): String {
        val installedIds = skillIndexService
            ?.listSkillsForManagement()
            .orEmpty()
            .filter { it.installed }
            .mapTo(mutableSetOf()) { SkillParser.normalizeSkillLookup(it.id) }
        val items = JSONArray()
        inspection.candidates.forEach { candidate ->
            items.put(
                JSONObject()
                    .put("name", candidate.name)
                    .put("path", candidate.path)
                    .put(
                        "installed",
                        SkillParser.normalizeSkillLookup(candidate.name) in installedIds,
                    ),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("repository", inspection.repository)
            .put("ref", inspection.ref)
            .put("commitSha", inspection.commitSha)
            .put("prefix", inspection.prefix ?: JSONObject.NULL)
            .put("count", inspection.candidates.size)
            .put("items", items)
            .toString()
    }

    private fun rememberInspection(
        repository: GitHubSkillRepository,
        inspection: GitHubSkillInspection,
        rememberDefault: Boolean,
    ) {
        val snapshot = GitHubInspectionSnapshot(
            commitSha = inspection.commitSha,
            prefix = inspection.prefix,
            candidatesByPath = inspection.candidates.associate { it.path to it.name },
        )
        inspectedGitHubSnapshots[inspectionKey(repository.slug, repository.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.commitSha)] = snapshot
        if (rememberDefault) {
            inspectedGitHubSnapshots[inspectionKey(repository.slug, null)] = snapshot
        }
    }

    private fun inspectionKey(repository: String, ref: String?): String =
        "${repository.lowercase(Locale.ROOT)}@${ref.orEmpty()}"

    private fun validateReplacementReplay(
        requestedRepository: GitHubSkillRepository,
        selectedPaths: List<String>,
        expectedReplacementId: String,
    ): String? {
        val pending = pendingSkillConflict.get() ?: return errorResult(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            "没有可供精确重放的 Skill 冲突",
        )
        if (
            !requestedRepository.slug.equals(pending.repository, ignoreCase = true) ||
            requestedRepository.ref != pending.commitSha ||
            selectedPaths.singleOrNull() != pending.selectedPath ||
            expectedReplacementId != pending.expectedReplacementId
        ) {
            return errorResult(
                "SKILL_REPLACE_CAPABILITY_MISMATCH",
                "覆盖参数必须精确重放冲突结果中的仓库、commitSha、路径与 Skill ID",
            )
        }
        return null
    }

    private fun isVisibleInCurrentRun(skillId: String): Boolean {
        val normalized = SkillParser.normalizeSkillLookup(skillId)
        return normalized in runAvailableSkillIds && normalized !in mutatedSkillIds
    }

    private fun nextTurnRequired(skillId: String): String = errorResult(
        "NEXT_TURN_REQUIRED",
        "Skill $skillId 在本轮已安装或变更，将从下一轮对话开始可用",
    )

    private fun installResult(
        result: SkillInstallResult,
        repository: String,
        ref: String,
        commitSha: String,
        selectedPaths: List<String>,
    ): String = when (result) {
        is SkillInstallResult.Success -> {
            pendingSkillConflict.set(null)
            val installed = JSONArray()
            result.installed.forEach { skill ->
                mutatedSkillIds += SkillParser.normalizeSkillLookup(skill.id)
                installed.put(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name),
                )
            }
            JSONObject()
                .put("ok", true)
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("installed", installed)
                .put("available", "next_turn")
                .put("scriptsExecuted", false)
                .put("message", "Skill 已安装并启用，将从下一轮对话开始可用；安装过程未执行脚本")
                .toString()
        }
        is SkillInstallResult.Conflict -> {
            val conflicts = JSONArray()
            result.conflicts.forEach { conflict ->
                conflicts.put(
                    JSONObject()
                        .put("id", conflict.id)
                        .put("name", conflict.name)
                        .put("replaceAllowed", conflict.replaceAllowed),
                )
            }
            pendingSkillConflict.set(
                result.conflicts.singleOrNull()
                    ?.takeIf { it.replaceAllowed && selectedPaths.size == 1 }
                    ?.let { conflict ->
                        PendingSkillConflictCapability(
                            repository = repository,
                            commitSha = commitSha,
                            selectedPath = selectedPaths.single(),
                            expectedReplacementId = conflict.id,
                            expectedReplacementName = conflict.name,
                        )
                    },
            )
            JSONObject()
                .put("ok", false)
                .put("code", "SKILL_CONFLICT")
                .put("message", "Skill 已存在；可替换的单个用户 Skill 可按返回参数直接重试，内置 Skill 不可覆盖")
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("conflicts", conflicts)
                .toString()
        }
        is SkillInstallResult.Failure -> {
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                skillTreeMutationUncertain.set(true)
            }
            errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private inline fun skillSourceResult(block: () -> String): String = try {
        block()
    } catch (failure: GitHubSkillSourceException) {
        errorResult(failure.code, failure.message ?: "GitHub Skill 请求失败")
    }

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private data class ScreenPoint(val x: Int, val y: Int)

    private class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

    private data class PublishedObservation(
        val elements: RootShellDeviceController.ElementObservation? = null,
        val coordinateSpace: RootShellDeviceController.CoordinateSpace? = null,
    )

    private data class GitHubInspectionSnapshot(
        val commitSha: String,
        val prefix: String?,
        val candidatesByPath: Map<String, String>,
    )

    private fun showTap(x: Int, y: Int) {
        GestureIndicator.showTap(context, x, y)
    }

    private fun showLongPress(x: Int, y: Int, durationMs: Int) {
        GestureIndicator.showLongPress(context, x, y, durationMs)
    }

    private fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        GestureIndicator.showSwipe(context, x1, y1, x2, y2, durationMs)
    }

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean = false
    )

    private companion object {
        val DEVICE_DIRECT_TOOL_NAMES = setOf(
            "set_alarm",
            "set_timer",
            "device_status",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "media_control",
            "set_volume",
        )
        val DEVICE_SENSITIVE_READ_TOOL_NAMES = setOf(
            "get_setting",
            "wifi_credentials",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "search_clipboard_history",
            "get_health_summary",
            "read_sms_code",
            "get_logcat",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_coloros_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_coloros_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
        )
        val DEVICE_SENSITIVE_ACTION_TOOL_NAMES = setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
        )
        val DEVICE_TOOL_NAMES =
            DEVICE_DIRECT_TOOL_NAMES + DEVICE_SENSITIVE_READ_TOOL_NAMES +
                DEVICE_SENSITIVE_ACTION_TOOL_NAMES
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
    }
}
