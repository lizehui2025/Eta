package io.github.mangi.eta.agent.terminal

/**
 * Linux 视图与 Android 视图的只读路径翻译（纯函数，便于单测）。
 *
 * 背景：Agent 的文件工具跑在 Android 视图（App 进程或 Root Shell），而模型侧
 * 经常拿到 Linux 工具环境的路径写法（`/workspace/...`）。不翻译会直接导致
 * 文件不存在：`/workspace` 在 Android 挂载命名空间里根本不存在，读、列、搜
 * 全灭，子代理在隔离窗口里尤其高频踩坑。
 *
 * 翻译规则（只做字符串映射，不访问文件系统）：
 * - `/workspace/mounts/<name>/...` → 该共享目录的 Android 侧源路径；
 *   未配置的挂载名保持原样（调用方按不存在处理，不静默改道）。
 * - `/workspace/daemon/...` → `<userWorkspace>/daemon/...`（与
 *   DetachedTaskSupervisor 的宿主落盘位置对齐）。
 * - `/workspace` 或 `/workspace/...` → `<userWorkspace>[/...]`。
 * - 其余保持原样（含 `~`、`~/...`、相对路径与 `/storage` 绝对路径，交由各
 *   控制器的归一化逻辑处理）。
 */
internal object AgentFilePathMapper {
    const val LINUX_WORKSPACE = "/workspace"
    const val LINUX_MOUNTS_ROOT = "/workspace/mounts"
    const val LINUX_DAEMON_DIR = "/workspace/daemon"

    fun toAndroidPath(
        path: String,
        mounts: List<Pair<String, String>>,
        userWorkspace: String,
    ): String {
        val value = path.trim()
        if (value.isEmpty()) return value
        if (value == LINUX_MOUNTS_ROOT || value.startsWith("$LINUX_MOUNTS_ROOT/")) {
            val remainder = value.removePrefix(LINUX_MOUNTS_ROOT).trimStart('/')
            val name = remainder.substringBefore('/')
            val mount = mounts.firstOrNull { it.first == name }
            if (mount == null) return value
            val rest = remainder.removePrefix(name).trimStart('/')
            if (rest.isEmpty()) return mount.second
            return mount.second.trimEnd('/') + "/" + rest
        }
        val workspace = userWorkspace.trim()
        if (value == LINUX_DAEMON_DIR || value.startsWith("$LINUX_DAEMON_DIR/")) {
            if (workspace.isEmpty()) return value
            val rest = value.removePrefix(LINUX_DAEMON_DIR).trimStart('/')
            val base = workspace.trimEnd('/')
            return if (rest.isEmpty()) "$base/daemon" else "$base/daemon/$rest"
        }
        if (value == LINUX_WORKSPACE || value.startsWith("$LINUX_WORKSPACE/")) {
            if (workspace.isEmpty()) return value
            val rest = value.removePrefix(LINUX_WORKSPACE).trimStart('/')
            val base = workspace.trimEnd('/')
            return if (rest.isEmpty()) base else "$base/$rest"
        }
        return value
    }
}
