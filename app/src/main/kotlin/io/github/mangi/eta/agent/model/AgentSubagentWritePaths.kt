package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.terminal.AgentFilePathMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * 子代理写入范围规范化与重叠判断（纯函数，便于单测）。
 * Linux 视图路径（`/workspace`、`/workspace/mounts/<name>/...`、`workspace/` 相对别名等）
 * 统一映射回 Android 侧真实路径，避免同一文件因两种写法绕过互斥或误报 WRITE_NOT_DECLARED；
 * 只比较规范化后的字符串，不访问文件系统。
 */
internal object AgentSubagentWritePaths {
    const val MOUNTS_ROOT = "/workspace/mounts"

    /**
     * [workspaceRoot] 是当前 Linux 环境 `/workspace` 指向的 Android 根路径（chroot 为宿主工作区，
     * 其他为私有工作区），与文件工具共用同一解析来源；声明与实际写入必须传同一值。
     */
    fun canonical(path: String, mounts: List<Pair<String, String>>, workspaceRoot: String): String =
        normalize(AgentFilePathMapper.toAndroidPath(path, mounts, workspaceRoot))

    fun contains(declared: String, path: String): Boolean {
        val root = normalize(declared)
        val target = normalize(path)
        if (root.isEmpty()) return false
        return target == root || target.startsWith("$root/")
    }

    fun overlaps(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.startsWith("$y/") || y.startsWith("$x/")
    }

    private fun normalize(path: String): String {
        var value = path.trim().replace(Regex("/{2,}"), "/")
        if (value.length > 1) value = value.trimEnd('/')
        return value
    }
}

/** 单次 fanout 内的写入互斥：同一 canonical 路径同时只允许一个子代理写入。 */
internal class SubagentWriteRegistry {
    private val holders = ConcurrentHashMap<String, String>()

    /** 返回当前占用者标识；null 表示获取成功（含同一 owner 重入）。 */
    fun tryAcquire(path: String, owner: String): String? {
        val existing = holders.putIfAbsent(path, owner)
        if (existing == null || existing == owner) return null
        return existing
    }

    fun release(path: String, owner: String) {
        holders.remove(path, owner)
    }
}
