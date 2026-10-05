package io.github.mangi.eta.agent.tool

/**
 * workspace 文件按名自动查找（读写工具的 `find` / `no_fail` 开关后端）。
 *
 * 定位：这不是 search 工具，也不替代 search_code——它只做"路径修正"级别的保守按名匹配，
 * 复用 list_directory 的递归枚举能力，不涉及内容检索。默认关闭，仅当调用方在参数里显式
 * 传入开关时启用：
 * - `find=true`：目标不存在时在工作区自动查找；完全一致/忽略大小写/归一化（忽略 -_ 空格）
 *   三个档位内唯一命中则直接采用（写/编辑只允许前两档，降低误写风险），否则返回候选列表；
 * - `no_fail=true`：只返回候选列表，不自动采用、不直接以 NOT_FOUND 收场（优先级高于 find）。
 *
 * 匹配只按文件名（basename），候选排序为"档位 → 路径更短 → 字典序"，保证确定性可单测。
 */
internal object WorkspaceAutoFind {

    /** 返回给模型的候选上限。 */
    const val MAX_CANDIDATES = 12

    /** 读类工具允许直接采用的档位上限（档位 2 = 忽略大小写与 -_ 空格差异）。 */
    const val READ_ADOPT_TIER = 2

    /** 写/编辑类工具允许直接采用的档位上限：只认"完全一致 / 忽略大小写"，宁可多一次确认。 */
    const val WRITE_ADOPT_TIER = 1

    /** 向上探测存在目录的最大层数。 */
    const val MAX_ANCESTOR_LEVELS = 6

    /** list_directory 结果的精简视图（[ok] 为 false 表示目录不存在或不可读取）。 */
    data class Listing(
        val ok: Boolean,
        val root: String,
        val entriesText: String,
        val truncated: Boolean,
    )

    data class Outcome(
        val candidates: List<String>,
        val scanRoot: String,
        val truncated: Boolean,
    )

    /** 名称匹配档位：0 完全一致；1 忽略大小写；2 归一化（大小写与 -_ 空格）；null 不匹配。 */
    fun nameTier(candidateName: String, requestedName: String): Int? {
        if (candidateName == requestedName) return 0
        if (candidateName.equals(requestedName, ignoreCase = true)) return 1
        if (normalize(candidateName) == normalize(requestedName)) return 2
        return null
    }

    /** 归一化：仅消除大小写与 -、_、空格差异，不做编辑距离等易误判的模糊匹配。 */
    fun normalize(name: String): String =
        name.lowercase().filterNot { it == '-' || it == '_' || it == ' ' }

    /** 从 list_directory 的 entries_text 解析文件条目（`- ` 前缀）为绝对路径。 */
    fun parseListingFiles(root: String, entriesText: String): List<String> {
        if (entriesText.isBlank()) return emptyList()
        val base = root.trimEnd('/')
        return entriesText.lineSequence()
            .map { it.trim() }
            .filter { it.length > 2 && it.startsWith("- ") }
            .map { line -> line.substring(2).trim() }
            .filter { it.isNotEmpty() }
            .map { relative -> if (base.isEmpty()) "/$relative" else "$base/$relative" }
            .toList()
    }

    /** 候选排序：先剔除不匹配的名字（宽筛会带入同扩展名的无关文件），再按档位 → 路径更短 → 字典序。 */
    fun rankCandidates(candidates: List<String>, requestedName: String): List<String> =
        candidates.distinct()
            .mapNotNull { path ->
                nameTier(path.substringAfterLast('/'), requestedName)?.let { tier -> tier to path }
            }
            .sortedWith(compareBy({ it.first }, { it.second.length }, { it.second }))
            .map { it.second }

    /**
     * 唯一采用：最佳档位不超过 [maxTier] 且该档位只有一个候选时返回它；
     * 其余情况（同档多个、无候选、只匹配到更低档位）返回 null，交给候选列表由模型选择。
     */
    fun chooseAdoption(candidates: List<String>, requestedName: String, maxTier: Int): String? {
        val ranked = rankCandidates(candidates, requestedName)
        val best = ranked.firstOrNull() ?: return null
        val bestTier = nameTier(best.substringAfterLast('/'), requestedName) ?: return null
        if (bestTier > maxTier) return null
        val sameTier = ranked.filter { nameTier(it.substringAfterLast('/'), requestedName) == bestTier }
        return sameTier.singleOrNull()
    }

    /** 从 [path] 起向上找最近存在的目录（最多 [maxLevels] 层）；都不存在返回 null。 */
    fun nearestExistingAncestor(
        path: String,
        dirExists: (String) -> Boolean,
        maxLevels: Int = MAX_ANCESTOR_LEVELS,
    ): String? {
        var current = path.trimEnd('/')
        if (current.isEmpty()) return null
        var level = 0
        while (level <= maxLevels) {
            if (dirExists(current)) return current
            val parent = current.substringBeforeLast('/', "")
            if (parent.isEmpty() || parent == current) break
            current = parent
            level++
        }
        return null
    }

    /**
     * 在 [root] 子树按名查找：先精确名一轮；未命中改用扩展名粗筛（无扩展名则 `*`）后由档位精排。
     * [list] 返回 null 表示列举不可用（查询失败），调用方应退化为原始 NOT_FOUND 流程。
     */
    fun findIn(
        root: String,
        requestedName: String,
        list: (root: String, glob: String) -> Listing?,
    ): Outcome? {
        val exact = list(root, requestedName)
        val exactCandidates = exact
            ?.takeIf { it.ok }
            ?.let { parseListingFiles(it.root.ifBlank { root }, it.entriesText) }
            .orEmpty()
        var scanRoot = exact?.root?.takeIf { it.isNotBlank() } ?: root
        var truncated = exact?.truncated == true
        if (exactCandidates.isNotEmpty()) {
            return Outcome(rankCandidates(exactCandidates, requestedName), scanRoot, truncated)
        }
        val extension = requestedName.substringAfterLast('.', "")
        val broadGlob = if (extension.isNotEmpty() && extension != requestedName) "*.$extension" else "*"
        val broad = list(root, broadGlob) ?: return if (exact == null) null else Outcome(emptyList(), scanRoot, false)
        if (!broad.ok) return if (exact == null) null else Outcome(emptyList(), scanRoot, false)
        if (broad.root.isNotBlank()) scanRoot = broad.root
        truncated = truncated || broad.truncated
        val candidates = parseListingFiles(broad.root.ifBlank { root }, broad.entriesText)
        return Outcome(rankCandidates(candidates, requestedName), scanRoot, truncated)
    }
}
