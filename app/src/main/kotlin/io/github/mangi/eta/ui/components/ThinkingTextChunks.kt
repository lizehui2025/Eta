package io.github.mangi.eta.ui.components

/** Keep visual line breaks exact while limiting the size of each Compose text layout. */
internal fun thinkingTextChunks(content: String, targetChars: Int = 2_048): List<String> {
    if (content.isEmpty()) return emptyList()
    require(targetChars > 0)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < content.length) {
        val targetEnd = (start + targetChars).coerceAtMost(content.length)
        if (targetEnd == content.length) {
            chunks += content.substring(start)
            break
        }
        val newline = content.lastIndexOf('\n', targetEnd - 1)
        if (newline < start) {
            val nextNewline = content.indexOf('\n', targetEnd)
            if (nextNewline < 0) {
                chunks += content.substring(start)
                break
            }
            chunks += content.substring(start, nextNewline)
            start = nextNewline + 1
        } else {
            chunks += content.substring(start, newline)
            start = newline + 1
        }
    }
    if (content.last() == '\n' && chunks.lastOrNull()?.endsWith('\n') != true) chunks += ""
    return chunks
}

/**
 * Incrementally splits streaming thinking text. Completed chunks are retained while only the
 * unfinished tail is inspected when a new provider delta arrives.
 */
internal class ThinkingTextChunkAccumulator(
    private val targetChars: Int = 2_048,
) {
    private var source = ""
    private val completed = mutableListOf<String>()
    private var tail = ""

    fun update(content: String): List<String> {
        require(targetChars > 0)
        if (!content.startsWith(source) || content.length < source.length) {
            source = ""
            completed.clear()
            tail = ""
        }
        val delta = content.substring(source.length)
        source = content
        if (delta.isNotEmpty()) tail += delta

        while (tail.length >= targetChars) {
            val targetEnd = targetChars.coerceAtMost(tail.length)
            val newlineBefore = tail.lastIndexOf('\n', targetEnd - 1)
            val splitAt = when {
                newlineBefore >= 0 -> newlineBefore
                else -> tail.indexOf('\n', targetEnd).takeIf { it >= 0 } ?: targetEnd
            }
            completed += tail.substring(0, splitAt)
            tail = tail.substring((splitAt + 1).coerceAtMost(tail.length))
        }
        return buildList(completed.size + 1) {
            addAll(completed)
            if (tail.isNotEmpty() || content.endsWith('\n')) add(tail)
        }
    }
}
