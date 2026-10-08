package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 个人数据读取只绑定已验证的系统或厂商 Provider，绝不向模型开放 URI、表名或 SQL。
 * Root 用于调用受签名权限保护的 Provider；查询条件始终由固定列和受控关键词组成。
 */
internal class AgentPersonalDataTools(
    private val root: BoundedRootCommandExecutor,
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? =
        when (name) {
            "search_media" -> searchMedia(args)
            "search_audio" -> searchAudio(args, recordingsOnly = false)
            "search_recordings" -> searchAudio(args, recordingsOnly = true)
            "search_files" -> searchFiles(args)
            "search_calendar_events" -> searchCalendarEvents(args)
            "search_contacts" -> searchContacts(args)
            "search_call_history" -> searchCallHistory(args)
            "search_messages" -> searchMessages(args)
            "search_downloads" -> searchDownloads(args)
            "search_coloros_notes" -> searchColorOsNotes(args)
            "search_coloros_recordings" -> searchColorOsRecordings(args)
            "search_recording_summaries" -> searchRecordingSummaries(args)
            "search_qq_chat_images" -> searchQqChatImages(args)
            "search_wechat_chat_images" -> searchWechatChatImages(args)
            else -> null
        }

    private fun searchMedia(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_media",
        uri = "content://media/external/file",
        projection = listOf("_id", "_display_name", "mime_type", "relative_path", "datetaken", "date_modified", "_size"),
        sort = "date_modified DESC",
        searchableColumns = listOf("_display_name", "relative_path"),
        fixedWhere = "media_type=1",
        args = args,
    ) { row ->
        row.put("uri", "content://media/external/file/${row.optString("_id")}")
    }

    private fun searchAudio(args: JSONObject, recordingsOnly: Boolean): AgentModelClient.ToolResult = query(
        tool = if (recordingsOnly) "search_recordings" else "search_audio",
        uri = "content://media/external/audio/media",
        projection = listOf("_id", "title", "_display_name", "artist", "album", "relative_path", "duration", "date_modified", "_size"),
        sort = "date_modified DESC",
        searchableColumns = listOf("title", "_display_name", "artist", "relative_path"),
        // 录音应用产物路径含 Record/录音；两工具用互补筛选：search_audio 排除录音目录，search_recordings 只取录音目录。
        fixedWhere = if (recordingsOnly) AUDIO_RECORDING_PATH_FILTER else AUDIO_NON_RECORDING_PATH_FILTER,
        args = args,
    ) { row ->
        row.put("uri", "content://media/external/audio/media/${row.optString("_id")}")
        // 标注命中类别与数据源，便于调用方区分音乐/语音与录音应用产物（两者共用媒体库数据源）。
        row.put("kind", if (recordingsOnly) "recording" else "audio")
        row.put("source", "media_store")
    }

    private fun searchFiles(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_files",
        uri = "content://media/external/file",
        projection = listOf("_id", "_display_name", "mime_type", "relative_path", "date_modified", "_size"),
        sort = "date_modified DESC",
        searchableColumns = listOf("_display_name", "relative_path"),
        fixedWhere = "media_type=0",
        args = args,
    ) { row ->
        row.put("uri", "content://media/external/file/${row.optString("_id")}")
    }

    private fun searchCalendarEvents(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_calendar_events",
        uri = "content://com.android.calendar/events",
        projection = listOf("_id", "title", "description", "eventLocation", "dtstart", "dtend", "allDay", "calendar_displayName"),
        sort = "dtstart DESC",
        searchableColumns = listOf("title", "description", "eventLocation"),
        fixedWhere = "deleted=0",
        args = args,
    )

    private fun searchContacts(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_contacts",
        uri = "content://com.android.contacts/contacts",
        projection = listOf("_id", "display_name", "lookup", "has_phone_number", "contact_last_updated_timestamp"),
        sort = "display_name COLLATE LOCALIZED ASC",
        searchableColumns = listOf("display_name"),
        fixedWhere = null,
        args = args,
    ) { row ->
        row.put("uri", "content://com.android.contacts/contacts/${row.optString("_id")}")
    }

    private fun searchCallHistory(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_call_history",
        uri = "content://call_log/calls",
        projection = listOf("_id", "number", "name", "date", "duration", "type", "geocoded_location"),
        sort = "date DESC",
        searchableColumns = listOf("number", "name"),
        fixedWhere = null,
        args = args,
    )

    private fun searchMessages(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_messages",
        uri = "content://sms",
        projection = listOf("_id", "thread_id", "address", "body", "date", "type", "read"),
        sort = "date DESC",
        searchableColumns = listOf("address", "body"),
        fixedWhere = null,
        args = args,
    )

    private fun searchDownloads(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_downloads",
        uri = "content://downloads/my_downloads",
        projection = listOf("_id", "title", "description", "mime_type", "total_size", "lastmod", "status", "local_uri"),
        sort = "lastmod DESC",
        searchableColumns = listOf("title", "description"),
        fixedWhere = null,
        args = args,
    )

    private fun searchColorOsNotes(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_coloros_notes",
        uri = "content://com.nearme.note/rich_notes",
        projection = listOf("local_id", "raw_title", "raw_text", "update_time", "create_time", "folder_id", "deleted", "recycle_time"),
        sort = "update_time DESC",
        searchableColumns = listOf("raw_title", "raw_text"),
        fixedWhere = "deleted=0 AND recycle_time=0",
        args = args,
    )

    private fun searchColorOsRecordings(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_coloros_recordings",
        uri = "content://com.coloros.soundrecorder.provider/records",
        projection = listOf("_id", "display_name", "_data", "duration", "date_modified", "record_type", "relative_path"),
        sort = "date_modified DESC",
        searchableColumns = listOf("display_name", "_data", "relative_path"),
        fixedWhere = "deleted=0 AND is_recycle=0",
        args = args,
    )

    private fun searchRecordingSummaries(args: JSONObject): AgentModelClient.ToolResult = query(
        tool = "search_recording_summaries",
        uri = "content://com.coloros.soundrecorder.provider/summary",
        projection = listOf("_id", "record_uuid", "record_type", "note_content", "note_state", "media_id", "media_path", "note_id"),
        sort = "_id DESC",
        searchableColumns = listOf("note_content", "media_path"),
        fixedWhere = null,
        args = args,
    )

    private fun searchQqChatImages(args: JSONObject): AgentModelClient.ToolResult = searchPrivateChatImages(
        tool = "search_qq_chat_images",
        directory = QQ_CHAT_IMAGES_DIRECTORY,
        pathFilter = "\\( -path '*/chatimg/*' -o -path '*/chatraw/*' -o -path '*/chatthumb/*' \\)",
        unavailableCode = "QQ_CHAT_IMAGES_UNAVAILABLE",
        unavailableMessage = "QQ 聊天图片缓存不可访问：可能未安装 QQ、缓存目录不存在或 Root 权限不足；请确认 QQ 已接收过图片后重试",
        args = args,
        kind = ::qqImageKind,
    )

    private fun searchWechatChatImages(args: JSONObject): AgentModelClient.ToolResult = searchPrivateChatImages(
        tool = "search_wechat_chat_images",
        directory = WECHAT_CHAT_IMAGES_DIRECTORY,
        pathFilter = "-path '*/image/*'",
        unavailableCode = "WECHAT_CHAT_IMAGES_UNAVAILABLE",
        unavailableMessage = "微信聊天图片缓存不可访问：可能未安装微信、缓存目录不存在或 Root 权限不足；请确认微信已接收过图片后重试",
        args = args,
        kind = { "image" },
    )

    private fun searchPrivateChatImages(
        tool: String,
        directory: String,
        pathFilter: String,
        unavailableCode: String,
        unavailableMessage: String,
        args: JSONObject,
        kind: (String) -> String,
    ): AgentModelClient.ToolResult {
        val result = root.execute(
            "test -d $directory && " +
                "find $directory -type f $pathFilter -size -${CHAT_IMAGE_MAX_FILE_BYTES}c " +
                "-printf '%T@|%s|%p\\n' 2>/dev/null | sort -rn | head -n $CHAT_IMAGE_CANDIDATE_LIMIT",
            timeoutMillis = QUERY_TIMEOUT_MS,
            maxOutputBytes = MAX_OUTPUT_BYTES,
        )
        if (!result.ok) return sensitive(error(unavailableCode, unavailableMessage))

        val keyword = args.optString("query").trim().lowercase(Locale.ROOT)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val candidates = result.stdout.lineSequence()
            .mapNotNull { privateChatImageRow(it, directory, kind) }
            .toList()
        val items = candidates.asSequence()
            .filter { keyword.isBlank() || it.optString("path").lowercase(Locale.ROOT).contains(keyword) }
            .take(limit)
            .toList()
        return sensitive(
            JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .put("items", JSONArray(items))
                .put("count", items.size)
                .put("truncated", result.truncated || candidates.size == CHAT_IMAGE_CANDIDATE_LIMIT || items.size == limit)
                .toString(),
        )
    }

    private fun query(
        tool: String,
        uri: String,
        projection: List<String>,
        sort: String,
        searchableColumns: List<String>,
        fixedWhere: String?,
        args: JSONObject,
        transform: (JSONObject) -> Unit = {},
    ): AgentModelClient.ToolResult {
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val keyword = args.optString("query").trim()
        val where = combineWhere(fixedWhere, keyword.takeIf(String::isNotBlank)?.let {
            searchableColumns.likeClause(it)
        })
        val command = buildString {
            append("content query --uri ").append(shellQuote(uri))
            append(" --projection ").append(shellQuote(projection.joinToString(":")))
            where?.let { append(" --where ").append(shellQuote(it)) }
            append(" --sort ").append(shellQuote(sort))
        }
        val result = root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS, maxOutputBytes = MAX_OUTPUT_BYTES)
        if (!result.ok || PersonalDataContentParser.hasProviderFailure(result.stdout, result.stderr)) {
            // 错误码保持不变；message 说明可能原因与补救方向（权限/来源不存在/结构不匹配/超时）。
            return sensitive(error(rootErrorCode(result), personalDataFailureMessage(result)))
        }
        val items = PersonalDataContentParser.parseRows(result.stdout, projection)
            .take(limit)
            .map { row -> row.also(transform) }
        return sensitive(
            JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .put("items", JSONArray(items))
                .put("count", items.size)
                .put("truncated", result.truncated || items.size == limit)
                .toString(),
        )
    }

    private fun List<String>.likeClause(keyword: String): String {
        val escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("'", "''")
        val value = "'%$escaped%'"
        return joinToString(" OR ", prefix = "(", postfix = ")") { column ->
            "LOWER($column) LIKE LOWER($value) ESCAPE '\\'"
        }
    }

    private fun combineWhere(first: String?, second: String?): String? = when {
        first == null -> second
        second == null -> first
        else -> "($first) AND ($second)"
    }

    private fun privateChatImageRow(
        line: String,
        directory: String,
        kind: (String) -> String,
    ): JSONObject? {
        val fields = line.split('|', limit = 3)
        if (fields.size != 3) return null
        val modifiedAt = fields[0].toDoubleOrNull()?.toLong() ?: return null
        val size = fields[1].toLongOrNull() ?: return null
        val path = fields[2]
        if (!path.startsWith("$directory/")) return null
        return JSONObject()
            .put("path", path)
            .put("name", path.substringAfterLast('/'))
            .put("kind", kind(path))
            .put("modified_at_epoch_seconds", modifiedAt)
            .put("size_bytes", size)
    }

    private fun qqImageKind(path: String): String = when {
        "/chatraw/" in path -> "original"
        "/chatimg/" in path -> "image"
        "/chatthumb/" in path -> "thumbnail"
        else -> "other"
    }

    private fun rootErrorCode(result: BoundedRootCommandExecutor.Result): String = when {
        result.errorCode.isNotBlank() -> result.errorCode
        result.timedOut -> "PERSONAL_DATA_QUERY_TIMEOUT"
        else -> "PERSONAL_DATA_UNAVAILABLE"
    }

    /** 个人数据源失败时的可行动提示：区分 Root 缺失、查询超时与来源不存在/未授权/结构不匹配。 */
    private fun personalDataFailureMessage(result: BoundedRootCommandExecutor.Result): String = when {
        result.errorCode == "ROOT_UNAVAILABLE" || result.errorCode == "ROOT_REQUIRED" ->
            "个人数据源需要 Root 权限：请先授予 Root 后重试"
        result.timedOut ->
            "个人数据查询超时：数据源响应过慢，可缩小 query 或降低 limit 后重试"
        result.errorCode.isNotBlank() ->
            "个人数据源执行失败（${result.errorCode}）：请确认数据来源应用可用后重试"
        else ->
            "个人数据源暂时不可访问：可能对应应用未安装或数据不存在、未授权或系统数据结构不匹配；" +
                "请确认来源应用与权限后重试"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        const val QUERY_TIMEOUT_MS = 15_000L
        const val MAX_OUTPUT_BYTES = 512 * 1024
        const val QQ_CHAT_IMAGES_DIRECTORY = "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"
        const val WECHAT_CHAT_IMAGES_DIRECTORY = "/storage/emulated/0/Android/data/com.tencent.mm/MicroMsg"
        const val CHAT_IMAGE_CANDIDATE_LIMIT = 120
        const val CHAT_IMAGE_MAX_FILE_BYTES = MAX_AGENT_IMAGE_BYTES.toLong()
    }

}

/**
 * search_audio / search_recordings 的路径筛选（媒体库 relative_path）：
 * 录音应用产物（ColorOS 录音、通话录音等）路径含 Record/录音；两工具取互补条件，语义不再重叠。
 */
internal const val AUDIO_RECORDING_PATH_FILTER =
    "relative_path LIKE '%Record%' OR relative_path LIKE '%录音%'"
internal const val AUDIO_NON_RECORDING_PATH_FILTER =
    "COALESCE(relative_path,'') NOT LIKE '%Record%' AND COALESCE(relative_path,'') NOT LIKE '%录音%'"

internal object PersonalDataContentParser {
    fun hasProviderFailure(stdout: String, stderr: String): Boolean =
        sequenceOf(stdout, stderr).any { output ->
            output.contains("Error while accessing provider:") ||
                output.contains("java.lang.IllegalArgumentException:") ||
                output.contains("java.lang.SecurityException:")
        }

    fun parseRows(source: String, columns: List<String>): List<JSONObject> =
        source.lineSequence()
            .filter { it.startsWith("Row:") }
            .map { line ->
                JSONObject().also { row ->
                    columns.forEach { column -> value(line, column, columns)?.let { row.put(column, it) } }
                }
            }
            .toList()

    private fun value(line: String, column: String, columns: List<String>): String? {
        val following = columns.filterNot { it == column }.joinToString("|") { Regex.escape(it) }
        return Regex("(?:^|,\\s*|\\s)${Regex.escape(column)}=(.*?)(?=,\\s*(?:$following)=|$)")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.takeUnless { it == "null" }
    }
}
