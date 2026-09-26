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
