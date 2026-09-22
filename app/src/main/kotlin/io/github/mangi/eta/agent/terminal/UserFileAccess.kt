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
        // 在 Android 侧不存在，不翻译会让子代理批量看不见文件。
        val mounts = runCatching { SharedFolderMounts.current().map { it.name to it.sourcePath } }
            .getOrDefault(emptyList())
        val translated = AgentFilePathMapper.toAndroidPath(path, mounts, TerminalRuntime.userWorkspacePath)
        val workspace = File(TerminalRuntime.userWorkspacePath)
        val raw = translated.trim().ifBlank { workspace.absolutePath }
        val file = when {
            raw == "~" -> workspace
            raw.startsWith("~/") -> File(workspace, raw.removePrefix("~/"))
            raw.startsWith('/') -> File(raw)
            else -> File(workspace, raw)
        }.canonicalFile
        val roots = listOf(workspace, File(workspace.parentFile, "proot"), File("/storage/emulated/0"))
        require(roots.any { root -> file.toPath().startsWith(root.canonicalFile.toPath()) }) { "路径不在普通终端可访问范围内" }
        return file
    }

    fun read(path: String, offsetBytes: Int, maxBytes: Int): String = operation {
        val file = resolve(path)
        require(file.isFile && file.canRead()) { "文件不可读取" }
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
        require(bytes.size <= 512 * 1024) { "写入内容过大" }
        require(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory) { "目录不可创建" }
        require(!file.exists() || file.isFile) { "目标不是普通文件" }
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
        val directory = resolve(path)
        val globs = AgentCodeSearch.compileGlobs(glob)
        val skip = offset.coerceAtLeast(0)
        val max = limit.coerceIn(1, 200)
        // 与 Root 实现同一口径：按名排序、一行一个、“d /- ”前缀、total/count/offset/truncated。
        val collected = if (!recursive) {
            (directory.listFiles() ?: throw IllegalArgumentException("目录不可读取"))
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
        require(file.isFile && file.canRead()) { "文件不可读取" }
        // 大文件错误码与 root 路径对齐：恰 512KB 允许，超过才报 FILE_TOO_LARGE，避免经 require 落到 INVALID_PATH。
        if (file.length() > 512 * 1024) {
            return@operation failure("FILE_TOO_LARGE", "文件超过 512KB，请改用终端命令处理")
        }
        val content = file.readText()
        when (val outcome = AgentFileEdit.apply(content, oldText, newText, replaceAll)) {
            is AgentFileEdit.Outcome.Rejected ->
                JSONObject().put("ok", false).put("tool", "edit_file").put("code", outcome.code)
                    .put("message", outcome.message)
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
        require(root.isDirectory) { "目录不可读取" }
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
    } catch (_: IllegalArgumentException) {
        error("INVALID_PATH", "路径或文件参数不在允许范围内")
    }

    /** 统一错误 JSON 构造；operation 内的提前返回复用它，保证字段与统一错误出口一致。 */
    private fun failure(code: String, message: String): JSONObject =
        JSONObject().put("ok", false).put("code", code).put("message", message)

    private fun error(code: String, message: String): String = failure(code, message).toString()
}
