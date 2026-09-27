package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentChatUiState

/**
 * 会话落盘判定（纯逻辑，便于单测）。
 *
 * 一次保存只写真正变化的会话，其余会话的消息、检查点与文本分块原样保留。
 * 指纹只覆盖会写入数据库的字段：输入框草稿、流式/压缩标志、可选推理档位这类不落盘的状态
 * 不参与，否则用户每敲一个字都会触发一次整库重写。
 *
 * 指纹判定在"库中已有该会话"时才允许跳过；首次保存、或上一次保存在事务里失败过（缓存未推进）
 * 都会照常整份写入，因此不会出现"该写的没写"。
 */
internal object AgentConversationPersistence {
    /** 已成功落盘的会话：内容指纹 + 当时真正写入的时间戳，以及落盘后库里该行的 updated_at。 */
    data class Saved(
        val fingerprint: Long,
        val createdAt: Long,
        val updatedAt: Long,
        val storedUpdatedAt: Long,
        /**
         * 只覆盖 messages/history/journal 的签名。
         * 这三块是整份序列化 + 分块的代价所在；单独记它才能区分"只改了时间戳"与"正文真的变了"。
         */
        val contentSignature: Long = 0L,
    )

    /**
     * 已存在的会话沿用库里的创建时间；否则沿用上次写过的值。
     * 两边都没有时用调用方上报的时间戳，而不是当次写盘时刻：上报值就是会话生成/导入时
     * 记下的时间，用它才能让 created_at 与进程无关地保持稳定。
     */
    fun createdAt(existing: Long?, previous: Saved?, reported: Long?, now: Long): Long =
        existing ?: previous?.createdAt ?: reported ?: now

    fun updatedAt(reported: Long?, previous: Saved?, now: Long): Long =
        reported ?: previous?.updatedAt ?: now

    /**
     * 只有「库中已有该会话」且「本次指纹与上次落盘一致」且「库里该行的 updated_at 仍是上次写的值」
     * 才跳过。第三个条件用来发现绕过本对象的整库写入（例如备份恢复直接调用 DAO），
     * 一旦发现就重新整份写入，等价于旧的全量覆盖行为。
     */
    fun shouldWrite(fingerprint: Long, previous: Saved?, storedUpdatedAt: Long?): Boolean {
        if (storedUpdatedAt == null || previous == null) return true
        return previous.fingerprint != fingerprint || previous.storedUpdatedAt != storedUpdatedAt
    }

    /**
     * 消息表与上下文检查点是否要整份重写。
     *
     * 这两块要先把整份正文序列化成一个大字符串再分块，长会话下是单次几十 MB 的堆分配，
     * 而"手动压缩""切换会话"这类保存只改时间戳、正文一字未动；条件不成立时跳过，
     * 库里已有的消息/分块原样保留（内容相同，无需刷新）。
     *
     * 与 [shouldWrite] 同一套保守条件：首次落盘、或库被别人整份改写过，都要照常重写。
     */
    fun shouldWriteContent(contentSignature: Long, previous: Saved?, storedUpdatedAt: Long?): Boolean {
        if (previous == null || storedUpdatedAt == null) return true
        if (previous.storedUpdatedAt != storedUpdatedAt) return true
        return previous.contentSignature != contentSignature
    }

    fun fingerprint(
        state: AgentChatUiState,
        title: String,
        createdAt: Long,
        updatedAt: Long,
        appliedRuntimeRunIdsJson: String,
        roleplayJson: String,
        revisionsJson: String,
    ): Long {
        var primary = 17
        primary = primary * 31 + title.hashCode()
        primary = primary * 31 + createdAt.hashCode()
        primary = primary * 31 + updatedAt.hashCode()
        primary = primary * 31 + state.reasoningEffort.wireValue.hashCode()
        primary = primary * 31 + (if (state.reasoningEffort.enablesReasoning) 1 else 0)
        primary = primary * 31 + appliedRuntimeRunIdsJson.hashCode()
        primary = primary * 31 + roleplayJson.hashCode()
        primary = primary * 31 + revisionsJson.hashCode()
        return payloadSignature(state, primary)
    }

    /** 只覆盖 messages/history/journal 的签名，与 [fingerprint] 的载荷折叠口径完全一致。 */
    fun contentSignature(state: AgentChatUiState): Long = payloadSignature(state, 11)

    /**
     * messages/history/journal 的双路折叠：
     * 单个 List.hashCode 只有 32 位；第二路按反序折叠，两路同时误判为相同才可以忽略。
     */
    private fun payloadSignature(state: AgentChatUiState, seed: Int): Long {
        val history = state.history
        val journal = state.journal.ifEmpty { history }
        var primary = seed
        primary = primary * 31 + state.messages.hashCode()
        primary = primary * 31 + history.hashCode()
        primary = primary * 31 + journal.hashCode()
        var secondary = 1
        secondary = secondary * 33 + reverseHash(state.messages)
        secondary = secondary * 33 + reverseHash(history)
        secondary = secondary * 33 + reverseHash(journal)
        secondary = secondary * 33 + state.messages.size
        secondary = secondary * 33 + history.size
        secondary = secondary * 33 + journal.size
        return ((primary.toLong() and 0xffffffffL) shl 32) or (secondary.toLong() and 0xffffffffL)
    }

    private fun reverseHash(values: List<*>): Int {
        var hash = 1
        for (index in values.indices.reversed()) hash = hash * 33 + (values[index]?.hashCode() ?: 0)
        return hash
    }
}
