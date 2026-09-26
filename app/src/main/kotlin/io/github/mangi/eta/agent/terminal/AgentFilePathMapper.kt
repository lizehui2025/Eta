package io.github.mangi.eta.agent.terminal

/**
 * Linux 视图与 Android 视图的只读路径翻译（纯函数，便于单测）。
 *
 * 背景：Agent 的文件工具跑在 Android 视图（App 进程或 Root Shell），而模型侧
 * 经常拿到 Linux 工具环境的路径写法（`/workspace/...`）。不翻译会直接导致
 * 文件不存在：`/workspace` 在 Android 挂载命名空间里根本不存在，读、列、搜
 * 全灭，子代理在隔离窗口里尤其高频踩坑。
 *
 * [workspaceRoot] 是“当前 Linux 环境里 `/workspace` 实际指向的 Android 根路径”
 * （由 TerminalRuntime.currentLinuxWorkspaceRoot() 解析）：chroot 为宿主工作区，
 * PRoot 与未安装/未选择时为私有工作区；调用方必须传解析后的值，文件层才与终端视图一致。
 *
 * 翻译规则（只做字符串映射，不访问文件系统）：
 * - `/workspace/mounts/<name>/...` → 该共享目录的 Android 侧源路径；
 *   未配置的挂载名保持原样（调用方按不存在处理，不静默改道）。
 * - `/workspace/daemon/...` → `<workspaceRoot>/daemon/...`（与
 *   DetachedTaskSupervisor 的宿主落盘位置对齐）。
 * - `/workspace` 或 `/workspace/...` → `<workspaceRoot>[/...]`。
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
        workspaceRoot: String,
    ): String {
        val value = path.trim()
        if (value.isEmpty()) return value
        // 相对写法别名：`workspace`、`workspace/...`（可带 `./` 前缀）一并视为工作区根。
        // 模型常把工作区写成相对名 workspace，旧实现会把它拼成 <workspace>/workspace 而失败，
        // 报错又只是“路径不在范围内”，排查成本高。这里统一兼容，避免“列出 workspace 失败”。
        val relativeWorkspace = value.removePrefix("./")
        if (relativeWorkspace == "workspace" || relativeWorkspace.startsWith("workspace/")) {
            val workspace = workspaceRoot.trim()
            if (workspace.isNotEmpty()) {
                val rest = relativeWorkspace.removePrefix("workspace").trimStart('/')
                val base = workspace.trimEnd('/')
                return if (rest.isEmpty()) base else "$base/$rest"
            }
        }
        if (value == LINUX_MOUNTS_ROOT || value.startsWith("$LINUX_MOUNTS_ROOT/")) {
            val remainder = value.removePrefix(LINUX_MOUNTS_ROOT).trimStart('/')
            val name = remainder.substringBefore('/')
            val mount = mounts.firstOrNull { it.first == name }
            if (mount == null) return value
            val rest = remainder.removePrefix(name).trimStart('/')
            if (rest.isEmpty()) return mount.second
            return mount.second.trimEnd('/') + "/" + rest
        }
        val workspace = workspaceRoot.trim()
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
