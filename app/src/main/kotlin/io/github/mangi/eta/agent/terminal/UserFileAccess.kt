package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.model.AgentTextDiff
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** 普通身份只访问终端工作区、免 Root 环境和 Android 已授权的共享存储。 */
internal object UserFileAccess {
    private const val MAX_LIST_SCAN = 5_000
    fun resolve(path: String): File {
        // Linux 视图先翻译为 Android 视图：/workspace/... 与 /workspace/mounts/<name>/...
        // 在 Android 侧不存在，不翻译会让子代理批量看不见文件；翻译根随当前后端解析
        // （chroot 为宿主工作区），普通模式（无 Root）下解析结果仍是私有工作区，与既有行为一致。
        val mounts = runCatching { SharedFolderMounts.current().map { it.name to it.sourcePath } }
            .getOrDefault(emptyList())
        val translated = AgentFilePathMapper.toAndroidPath(path, mounts, TerminalRuntime.currentLinuxWorkspaceRoot())
        val workspace = File(TerminalRuntime.userWorkspacePath)
        val raw = translated.trim().ifBlank { workspace.absolutePath }
        val file = when {
            raw == "~" -> workspace
            raw.startsWith("~/") -> File(workspace, raw.removePrefix("~/"))
            raw.startsWith('/') -> File(raw)
            else -> File(workspace, raw)
        }.canonicalFile
        val roots = listOf(workspace, File(workspace.parentFile, "proot"), File("/storage/emulated/0"))
        require(roots.any { root -> file.toPath().startsWith(root.canonicalFile.toPath()) }) {
            "路径不在普通终端的可访问范围内（无 Root 时只能访问终端工作区与 /storage/emulated/0）：$path"
        }
        return file
    }

    fun read(path: String, offsetBytes: Int, maxBytes: Int): String = operation {
        val file = resolve(path)
        // 与 Root 侧同一口径：目录/不存在/无权限分别给结论，避免模型只拿到笼统的失败。
        if (!file.exists()) return@operation failure("NOT_FOUND", missingPathMessage(file))
        if (file.isDirectory) {
            return@operation failure(
                "IS_DIRECTORY",
                "路径是目录：${file.absolutePath}；请改用 list_directory 浏览目录，或提供具体文件路径",
            )
        }
        if (!file.isFile) {
            return@operation failure(
                "NOT_REGULAR_FILE",
                "路径不是普通文件：${file.absolutePath}（可能是设备、管道或损坏的符号链接）",
            )
        }
        if (!file.canRead()) {
            return@operation failure("READ_FAILED", "文件不可读取（无读取权限）：${file.absolutePath}")
        }
        val offset = offsetBytes.coerceAtLeast(0)
        val limit = maxBytes.coerceIn(1, 16_000)
        // 文件不存在或读到 EOF/短读统一映射 READ_FAILED；其余 IOException 保持 FILE_ACCESS_DENIED。
        val bytes = try {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset.toLong())
                ByteArray(minOf(limit.toLong(), (input.length() - offset).coerceAtLeast(0)).toInt()).also { input.readFully(it) }
            }
        } catch (_: java.io.FileNotFoundException) {
            return@operation failure("READ_FAILED", "文件不存在或无法读取")
        } catch (_: java.io.EOFException) {
            return@operation failure("READ_FAILED", "读取文件时意外到达文件末尾")
        }
        val content = bytes.decodeToString()
        // truncated 与 root 路径口径一致：读满 limit（可能还有后续）或内容被 16000 字符上限截断。
        JSONObject().put("ok", true).put("tool", "read_file").put("path", file.absolutePath)
            .put("offset_bytes", offset).put("bytes_read", bytes.size)
            .put("truncated", bytes.size >= limit || content.length > 16_000)
            .put("content", content.truncateByCodePoints(16_000))
    }

    fun write(path: String, content: String, append: Boolean): String = operation {
        val file = resolve(path)
        val bytes = content.toByteArray()
        if (bytes.size > 512 * 1024) {
            return@operation failure("FILE_TOO_LARGE", "写入内容过大（${bytes.size} 字节，上限 512KB）")
        }
        if (file.isDirectory) {
            return@operation failure("IS_DIRECTORY", "目标路径是目录：${file.absolutePath}；请提供文件路径")
        }
        if (!file.parentFile!!.mkdirs() && !file.parentFile!!.isDirectory) {
            return@operation failure("WRITE_FAILED", "目录不可创建（权限不足）：${file.parentFile!!.absolutePath}")
        }
        // 覆盖已存在的文本文件前先读取旧内容，供 UI 展示具体的变更摘要。
        val previousText = if (!append && file.isFile && file.length() in 1..(512L * 1024)) {
            runCatching { file.readText() }.getOrNull()
        } else {
            null
        }
        val diff = previousText?.let { previous ->
            runCatching { AgentTextDiff.summarize(previous, content) }.getOrNull()
        }
        if (append) {
            java.io.FileOutputStream(file, true).use { it.write(bytes) }
        } else {
            // 临时文件名加随机后缀，避免并发写入互相踩踏；失败时在 finally 中清理残留。
            val temp = File(file.parentFile, "." + file.name + ".eta-tmp-" + UUID.randomUUID().toString().take(8))
            try {
                java.io.FileOutputStream(temp).use { it.write(bytes) }
                require(temp.renameTo(file)) { "替换失败：无法重命名临时文件" }
            } finally {
                temp.delete()
            }
        }
        JSONObject().put("ok", true).put("tool", "write_file").put("path", file.absolutePath)
            .put("mode", if (append) "append" else "overwrite").put("bytes_written", bytes.size)
            .also { result ->
                if (previousText != null) result.put("previous_bytes", previousText.toByteArray().size)
                if (!diff.isNullOrEmpty()) result.put("diff", diff)
            }
    }

    fun list(
        path: String,
        showHidden: Boolean,
        limit: Int,
        offset: Int = 0,
        glob: String = "",
        recursive: Boolean = false,
    ): String = operation {
        // `/workspace/mounts` 是合成的枚举视图（Android 命名空间不存在该目录）：按当前共享
        // 配置合成列表，与 Root 实现同一口径，便于发现挂载后再深入 /workspace/mounts/<name>/...
        if (path.trim().trimEnd('/') == AgentFilePathMapper.LINUX_MOUNTS_ROOT) {
            return@operation mountsListing(limit, offset)
        }
        val directory = resolve(path)
        if (!directory.isDirectory) {
            return@operation failure(
                "MISSING_DIRECTORY",
                "目录不存在或不是目录：${directory.absolutePath}" + missingPathSuggestion(directory),
            )
        }
        val globs = AgentCodeSearch.compileGlobs(glob)
        val skip = offset.coerceAtLeast(0)
        val max = limit.coerceIn(1, 200)
        // 与 Root 实现同一口径：按名排序、一行一个、“d /- ”前缀、total/count/offset/truncated。
        val collected = if (!recursive) {
            (directory.listFiles() ?: return@operation failure("LIST_FAILED", "目录不可读取（权限不足）：${directory.absolutePath}"))
                .sortedBy { it.name }
                .map { file -> (if (file.isDirectory) "d " else "- ") + file.name to file.name }
        } else {
            val out = mutableListOf<Pair<String, String>>()
            val seen = HashSet<String>()
            fun visit(dir: File, prefix: String) {
                if (out.size >= MAX_LIST_SCAN) return
                val children = dir.listFiles() ?: return
                for (child in children.sortedBy { it.name }) {
                    if (out.size >= MAX_LIST_SCAN) return
                    // 符号链接按规范路径去重，避免循环展开。
                    val canonical = runCatching { child.canonicalPath }.getOrNull() ?: child.absolutePath
                    if (!seen.add(canonical)) continue
                    val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                    out += ((if (child.isDirectory) "d " else "- ") + relative to child.name)
                    if (child.isDirectory) visit(child, relative)
                }
            }
            seen += runCatching { directory.canonicalPath }.getOrNull() ?: directory.absolutePath
            visit(directory, "")
            out.sortBy { it.first.substring(2) }
            out
        }
        val filtered = collected.filter { (line, name) ->
            val base = name.substringAfterLast('/')
            (showHidden || !base.startsWith('.')) &&
                AgentCodeSearch.matchesGlobs(globs, base) &&
                // 递归时行内是相对路径，隐藏判断与 glob 都按 basename，与 Root 侧一致。
                (showHidden || !line.substring(2).substringAfterLast('/').startsWith('.'))
        }
        val total = filtered.size
        val page = filtered.drop(skip).take(max)
        val text = page.joinToString("\n") { it.first }
        JSONObject().put("ok", true).put("tool", "list_directory").put("path", directory.absolutePath)
            .put("exit_code", 0).put("stderr", "")
            .put("total", total).put("offset", skip).put("count", page.size)
            .put("truncated", skip + page.size < total || text.length > 16_000)
            .put("entries_text", text.truncateByCodePoints(16_000))
    }

    fun edit(path: String, oldText: String, newText: String, replaceAll: Boolean): String = operation {
        val file = resolve(path)
        if (!file.exists()) return@operation failure("NOT_FOUND", missingPathMessage(file))
        if (file.isDirectory) {
            return@operation failure(
                "IS_DIRECTORY",
                "路径是目录：${file.absolutePath}；请改用 list_directory 浏览目录，或提供具体文件路径",
            )
        }
        if (!file.isFile || !file.canRead()) {
            return@operation failure("READ_FAILED", "文件不可读取：${file.absolutePath}")
        }
        // 大文件错误码与 root 路径对齐：恰 512KB 允许，超过才报 FILE_TOO_LARGE，避免经 require 落到 INVALID_PATH。
        if (file.length() > 512 * 1024) {
            return@operation failure("FILE_TOO_LARGE", "文件超过 512KB，请改用终端命令处理")
        }
        val content = file.readText()
        when (val outcome = AgentFileEdit.apply(content, oldText, newText, replaceAll)) {
            is AgentFileEdit.Outcome.Rejected ->
                JSONObject().put("ok", false).put("tool", "edit_file").put("code", outcome.code)
                    .put("message", outcome.message)
                    .also { json -> outcome.context?.let { json.put("context", it) } }
            is AgentFileEdit.Outcome.Applied -> {
                val bytes = outcome.content.toByteArray()
                if (bytes.size > 512 * 1024) {
                    return@operation failure("FILE_TOO_LARGE", "编辑结果超过 512KB，请改用终端命令处理")
                }
                val parent = file.parentFile!!
                require(parent.mkdirs() || parent.isDirectory) { "目录不可创建" }
                // 临时文件名加随机后缀，避免并发编辑互相踩踏；失败时在 finally 中清理残留。
                val temp = File(parent, "." + file.name + ".eta-tmp-" + UUID.randomUUID().toString().take(8))
                try {
                    java.io.FileOutputStream(temp).use { it.write(bytes) }
                    require(temp.renameTo(file)) { "替换失败：无法重命名临时文件" }
                } finally {
                    temp.delete()
                }
                JSONObject().put("ok", true).put("tool", "edit_file").put("path", file.absolutePath)
                    .put("replacements", outcome.replacements).put("bytes_written", bytes.size)
            }
        }
    }

    fun search(rootPath: String, pattern: String, glob: String, maxResults: Int): String = operation {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return@operation failure("INVALID_PATTERN", "pattern 不能为空")
        }
        if (trimmed.length > AgentCodeSearch.MAX_PATTERN_CHARS) {
            return@operation failure("INVALID_PATTERN", "pattern 过长（最多 ${AgentCodeSearch.MAX_PATTERN_CHARS} 字符）")
        }
        val regex = try {
            Regex(trimmed)
        } catch (_: Exception) {
            return@operation failure("INVALID_PATTERN", "pattern 不是合法正则")
        }
        val root = resolve(rootPath)
        if (!root.exists()) {
            return@operation failure(
                "MISSING_DIRECTORY",
                "搜索目录不存在：${root.absolutePath}" + missingPathSuggestion(root),
            )
        }
        if (!root.isDirectory) {
            return@operation failure("NOT_A_DIRECTORY", "搜索根路径不是目录：${root.absolutePath}；请提供目录路径")
        }
        val globs = AgentCodeSearch.compileGlobs(glob)
        val max = maxResults.coerceIn(1, AgentCodeSearch.MAX_RESULTS)
        val entries = collectMatches(root, regex, globs, max)
        var budget = AgentCodeSearch.MAX_ENTRIES_TEXT_CHARS
        val capped = mutableListOf<String>()
        for (entry in entries) {
            if (entry.length + 1 > budget) break
            capped += entry
            budget -= entry.length + 1
        }
        JSONObject().put("ok", true).put("tool", "search_code").put("path", root.absolutePath)
            .put("pattern", trimmed).put("glob", glob.orEmpty()).put("count", capped.size)
            .put("truncated", capped.size < entries.size)
            .put("results", JSONArray(capped))
    }

    /** 共享挂载枚举视图：只暴露挂载名与 Android 侧源路径，不做文件系统访问。 */
    private fun mountsListing(limit: Int, offset: Int): JSONObject {
        val mounts = runCatching { SharedFolderMounts.current() }.getOrDefault(emptyList())
        val entries = mounts.map { "d ${it.name} -> ${it.sourcePath}" }
        val max = limit.coerceIn(1, 200)
        val skip = offset.coerceAtLeast(0)
        val page = entries.drop(skip).take(max)
        return JSONObject().put("ok", true).put("tool", "list_directory")
            .put("path", AgentFilePathMapper.LINUX_MOUNTS_ROOT)
            .put("exit_code", 0)
            .put("virtual", true)
            .put("total", entries.size).put("offset", skip).put("count", page.size)
            .put("truncated", skip + page.size < entries.size)
            .put("entries_text", page.joinToString("\n")).put("stderr", "")
    }

    private fun collectMatches(root: File, regex: Regex, globs: List<Regex>, max: Int): List<String> {
        val results = mutableListOf<String>()
        var visited = 0
        var scanned = 0
        val skipDirs = setOf(".git", "node_modules", "build", ".gradle", ".idea")
        val seenDirs = HashSet<String>()
        fun visit(dir: File) {
            if (results.size >= max || scanned >= AgentCodeSearch.MAX_SCAN_FILES ||
                visited >= AgentCodeSearch.MAX_VISITED_FILES
            ) {
                return
            }
            // 符号链接目录按规范路径去重，避免循环展开导致无限递归。
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: dir.absolutePath
            if (!seenDirs.add(canonical)) return
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (results.size >= max || scanned >= AgentCodeSearch.MAX_SCAN_FILES ||
                    visited >= AgentCodeSearch.MAX_VISITED_FILES
                ) {
                    return
                }
                if (child.isDirectory) {
                    if (child.name in skipDirs) continue
                    visited++
                    visit(child)
                } else if (child.isFile) {
                    visited++
                    if (!AgentCodeSearch.matchesGlobs(globs, child.name)) continue
                    if (child.length() > AgentCodeSearch.MAX_FILE_BYTES) continue
                    if (isProbablyBinary(child)) continue
                    scanned++
                    // 流式逐行读：readLines 会把整个文件一次性装进内存，命中靠前的记录
                    // 也要等全文件读完；逐行读凑满 max 即停，大文件提前收尾。
                    val reader = runCatching { child.bufferedReader() }.getOrNull() ?: continue
                    reader.use { input ->
                        var index = 0
                        while (results.size < max) {
                            val line = input.readLine() ?: break
                            if (regex.containsMatchIn(line)) {
                                results += AgentCodeSearch.entry(child.absolutePath, index + 1, line)
                            }
                            index++
                        }
                    }
                    if (results.size >= max) return
                }
            }
        }
        visit(root)
        return results
    }

    /** 前 4KB 含 NUL 字节即视为二进制，与 Root 侧 grep -I 口径对齐。 */
    private fun isProbablyBinary(file: File): Boolean {
        if (file.length() == 0L) return false
        return try {
            java.io.FileInputStream(file).use { input ->
                val head = ByteArray(4096)
                val read = input.read(head)
                if (read <= 0) return false
                for (i in 0 until read) if (head[i] == 0.toByte()) return true
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private inline fun operation(block: () -> JSONObject): String = try {
        block().toString()
    } catch (_: java.io.IOException) {
        error("FILE_ACCESS_DENIED", "文件不可访问，请检查路径和文件授权")
    } catch (_: SecurityException) {
        error("FILE_ACCESS_DENIED", "文件访问未授权")
    } catch (invalid: IllegalArgumentException) {
        // resolve() 的范围说明本身就可操作（含可访问范围），保留原文而不是替换成笼统提示。
        error("INVALID_PATH", invalid.message ?: "路径或文件参数不在允许范围内")
    }

    /** 统一错误 JSON 构造；operation 内的提前返回复用它，保证字段与统一错误出口一致。 */
    private fun failure(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    private fun error(code: String, message: String): String = failure(code, message).toString()

    /** 缺失文件的恢复提示（与 Root 侧同一口径）：给出父目录现有条目，便于一次修正路径。 */
    private fun missingPathMessage(file: File): String =
        "文件不存在：${file.absolutePath}" + missingPathSuggestion(file)

    private fun missingPathSuggestion(file: File): String {
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) {
            return "；父目录不存在，请先用 list_directory 从工作区根目录逐层确认"
        }
        val names = parent.listFiles()
            ?.sortedBy { it.name }
            ?.take(MISSING_SUGGESTION_LIMIT)
            ?.joinToString(", ") { it.name }
            .orEmpty()
        if (names.isBlank()) {
            return "；请先用 list_directory 确认工作区中的真实路径"
        }
        return "。父目录 ${parent.absolutePath} 下的条目：$names。" +
            "请核对文件名大小写与后缀，或先用 list_directory 浏览目录"
    }

    private const val MISSING_SUGGESTION_LIMIT = 24
}
