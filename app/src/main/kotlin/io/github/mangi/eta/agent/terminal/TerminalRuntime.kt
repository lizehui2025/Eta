package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.io.File

internal enum class LinuxExecutionBackend(val wireName: String) {
    CHROOT("chroot"),
    PROOT("proot"),
}

/** App 进程的终端运行条件；读取能力不会触发 su 授权。 */
internal object TerminalRuntime {
    /** chroot 后端 Linux 环境里 `/workspace` 的宿主映射；ShellProcessSupervisor 把宿主工作区 bind 到 rootfs/workspace。 */
    const val HOST_WORKSPACE_PATH = "/data/local/tmp/eta"

    @Volatile private var appContext: Context? = null
    fun acquireUserTask(id: String, onStop: () -> Unit): Boolean =
        appContext?.let { AgentExecutionService.acquire(it, id, onStop = onStop) } ?: true

    fun releaseUserTask(id: String) {
        if (appContext != null) AgentExecutionService.release(id)
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        File(userWorkspacePath).mkdirs()
    }

    val rootAvailable: Boolean get() = RootAccess.isGranted
    val publicStorageGranted: Boolean get() = appContext != null && android.os.Environment.isExternalStorageManager()
    val nativeLibraryDir: File? get() = appContext?.applicationInfo?.nativeLibraryDir?.let(::File)
    val userWorkspacePath: String get() = appContext?.let {
        TerminalPrivateStorage.workspace(it.filesDir).absolutePath
    } ?: File(System.getProperty("java.io.tmpdir"), "eta-terminal-workspace").absolutePath
    val temporaryDirectory: File get() = appContext?.let { File(it.cacheDir, "terminal/proot") }
        ?: File(System.getProperty("java.io.tmpdir"), "eta-proot")

    fun defaultIdentity(environment: TerminalEnvironment, rootfsPath: String? = null): String = when {
        environment.isLinux -> if (LinuxEnvironmentPaths.backendOf(rootfsPath) == LinuxExecutionBackend.PROOT) "user" else "root"
        rootAvailable -> "root"
        else -> "user"
    }

    fun workspace(identity: String): String =
        if (identity == "root") HOST_WORKSPACE_PATH else userWorkspacePath

    /** 每个 Agent run 的独立工作区目录名：`<工作区根>/sessions/<runId>`。 */
    const val SESSIONS_DIR = "sessions"

    /** 保留的最近 run 工作区数量；更旧的在下一次开 run 时清理。 */
    const val MAX_RETAINED_SESSION_WORKSPACES = 8

    @Volatile private var agentWorkspace: String? = null

    /** 当前 run 的干净工作区；没有活跃 run 时为 null。 */
    fun activeAgentWorkspace(): String? = agentWorkspace

    /**
     * 工具的相对路径基准根：有活跃 run 时用它的干净工作区，否则回退全局工作区根。
     * 回退保证没有 run 上下文（如设置页自检、旧调用点）时行为与过去一致。
     */
    fun currentToolWorkspaceRoot(): String = agentWorkspace ?: currentLinuxWorkspaceRoot()

    /**
     * 开 run：算出该 run 的工作区、尽力建目录（宿主工作区由 Root 通道的 payload 补建，
     * App 进程对 /data/local/tmp 无写权限）并清理更旧的工作区，随后把工具基准切到它。
     */
    fun beginAgentWorkspace(runId: String): String {
        val workspaceRoot = currentLinuxWorkspaceRoot()
        val path = sessionWorkspacePath(workspaceRoot, runId)
        runCatching {
            val dir = File(path)
            dir.mkdirs()
            pruneSessionWorkspaceDirs(File(sessionsDirPath(workspaceRoot)), dir.name)
        }
        agentWorkspace = path
        return path
    }

    /** run 结束：只清理属于该 run 的基准，避免误清已经切到新 run 的状态。 */
    fun endAgentWorkspace(runId: String) {
        val current = agentWorkspace ?: return
        if (current == sessionWorkspacePath(currentLinuxWorkspaceRoot(), runId)) agentWorkspace = null
    }

    /** `<工作区根>/sessions`；根为空时退化为 `/sessions`。 */
    fun sessionsDirPath(workspaceRoot: String): String {
        val base = workspaceRoot.trimEnd('/')
        return if (base.isEmpty()) "/$SESSIONS_DIR" else "$base/$SESSIONS_DIR"
    }

    /** run 工作区绝对路径（纯函数）。 */
    fun sessionWorkspacePath(workspaceRoot: String, runId: String): String =
        "${sessionsDirPath(workspaceRoot)}/${safeRunId(runId)}"

    /** runId 只保留文件系统安全字符再截断：防路径穿越、空白与超长目录名。 */
    fun safeRunId(runId: String): String =
        runId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(64)
            .ifBlank { "run" }

    /**
     * 纯判定：除当前工作区外按 mtime 倒序保留 `keep - 1` 个，返回待删除目录名。
     * 当前工作区永不删除；`keep` 不为正时不返回当前工作区。
     */
    fun pruneSessionWorkspaces(
        entries: List<Pair<String, Long>>,
        keep: Int,
        activeName: String,
    ): List<String> {
        // 现存目录数已在保留预算内（含当前工作区那一个名额）：不需要清理。
        if (entries.size <= keep) return emptyList()
        return entries.asSequence()
            .filter { it.first != activeName }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
            .drop((keep - 1).coerceAtLeast(0))
            .map { it.first }
            .toList()
    }

    /** 按数量上限清理旧工作区；目录不可写（宿主工作区属 Root）时静默跳过，由 Root 通道处理。 */
    private fun pruneSessionWorkspaceDirs(sessionsDir: File, activeName: String) {
        val entries = sessionsDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { it.name to it.lastModified() }
        if (entries.isEmpty()) return
        pruneSessionWorkspaces(entries, MAX_RETAINED_SESSION_WORKSPACES, activeName)
            .forEach { name -> File(sessionsDir, name).deleteRecursively() }
    }

    /**
     * 当前 Linux 工具环境里 `/workspace` 对应的 Android 侧根路径。
     * 与终端“选择的环境”同来源（LinuxEnvironmentSettingsRepository + LinuxEnvironmentPaths 的
     * backend/rootfs 判定）：chroot 且环境就绪、有 Root 时为宿主工作区；PRoot、未安装、未选择或
     * 读不到设置时保持私有工作区。只有少量设置读取与 stat，随用随算，不做额外缓存。
     */
    fun currentLinuxWorkspaceRoot(): String {
        val context = appContext ?: return userWorkspacePath
        return runCatching {
            val distribution = LinuxEnvironmentSettingsRepository.current(context)
            val backend = LinuxEnvironmentSettingsRepository.backend(context, distribution)
            val environmentReady = LinuxEnvironmentPaths.rootfsReady(
                LinuxEnvironmentPaths.rootfsDir(context, distribution, backend).absolutePath,
            )
            resolveLinuxWorkspaceRoot(backend, environmentReady, rootAvailable, userWorkspacePath)
        }.getOrDefault(userWorkspacePath)
    }

    /** 纯判定：仅 chroot 后端、环境就绪且有 Root 时 `/workspace` 指向宿主工作区，其余保持私有工作区。 */
    fun resolveLinuxWorkspaceRoot(
        backend: LinuxExecutionBackend,
        environmentReady: Boolean,
        rootGranted: Boolean,
        userWorkspace: String,
    ): String =
        if (backend == LinuxExecutionBackend.CHROOT && environmentReady && rootGranted) {
            HOST_WORKSPACE_PATH
        } else {
            userWorkspace
        }

    fun nativeExecutable(name: String): File? = nativeLibraryDir?.let { File(it, name) }
        ?.takeIf { it.isFile && it.canExecute() }
}
