package io.github.mangi.eta.agent.tool

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** 读取经过真机验证的固定数据库；路径、表、字段与查询均不接受模型输入。 */
internal class AgentPrivateDatabaseTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "list_alarms" -> sensitive(readDatabase(CLOCK_DATABASE, "CLOCK_DATA_UNAVAILABLE") { listAlarms(it, args) })
        "list_active_timers" -> sensitive(readDatabase(CLOCK_DATABASE, "CLOCK_DATA_UNAVAILABLE") { listTimers(it, args) })
        "search_clipboard_history" -> sensitive(readDatabase(CLIPBOARD_DATABASE, "CLIPBOARD_HISTORY_UNAVAILABLE") { searchClipboard(it, args) })
        "get_health_summary" -> sensitive(readDatabase(HEALTH_DATABASE, "HEALTH_DATA_UNAVAILABLE") { healthSummary(it, args) })
        else -> null
    }

    private fun listAlarms(database: SQLiteDatabase, args: JSONObject): String {
        if (!database.hasColumns("alarms", setOf("_id", "hour", "minutes", "enabled"))) {
            return error("CLOCK_SCHEMA_UNSUPPORTED", "当前时钟数据库结构与已知 ColorOS 版本不一致；可改用 set_alarm/set_timer 直接创建")
        }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val items = database.rows(
            table = "alarms",
            columns = listOf(
                "_id", "hour", "minutes", "daysofweek", "alarmtime", "enabled", "message",
                "vibrate", "deleteAfterUse", "workdaySwitch", "holidaySwitch", "snoozeTime",
            ),
            selection = if (args.optBoolean("enabled_only", true)) "enabled=1" else null,
            order = "enabled DESC, alarmtime ASC",
            limit = limit,
        )
        return ok("list_alarms", items, limit)
    }

    private fun listTimers(database: SQLiteDatabase, args: JSONObject): String {
        if (!database.hasColumns("timer_schedule", setOf("_id", "duration", "state"))) {
            return error("CLOCK_SCHEMA_UNSUPPORTED", "当前时钟数据库结构与已知 ColorOS 版本不一致；可改用 set_alarm/set_timer 直接创建")
        }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val items = database.rows(
            table = "timer_schedule",
            columns = listOf(
                "_id", "description", "duration", "state", "first_start_time", "start_time",
                "remain_time", "pause_remain_time", "alert_time",
            ),
            selection = "state<>0",
            order = "alert_time ASC",
            limit = limit,
        )
        return ok("list_active_timers", items, limit)
    }

    private fun searchClipboard(database: SQLiteDatabase, args: JSONObject): String {
        if (!database.hasColumns("CLIPBOARD_ITEM", setOf("TIME", "CONTENT"))) {
            return error("CLIPBOARD_SCHEMA_UNSUPPORTED", "当前剪贴板历史结构与已知版本不一致：可能输入法已升级；可重新复制后再查询")
        }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val query = args.optString("query").trim()
        val items = database.rows(
            table = "CLIPBOARD_ITEM",
            columns = listOf("TIME", "CONTENT"),
            selection = query.takeIf(String::isNotBlank)?.let { "CONTENT LIKE ? ESCAPE '\\' COLLATE NOCASE" },
            selectionArgs = query.takeIf(String::isNotBlank)?.let { arrayOf("%${it.escapeLike()}%") },
            order = "TIME DESC",
            limit = limit,
        )
        return ok("search_clipboard_history", items, limit)
    }

    /**
     * 汇总健康数据。除汇总值外还记录数据表是否存在/是否读取失败：
     * records 全为 0 时附加 permission/readable，区分“未授权或不可读”与“窗口内确实无数据”。
     * 内部可见以便单测直接注入内存数据库（不触发 Root 快照）；有数据时不改变既有结构。
     */
    internal fun healthSummary(database: SQLiteDatabase, args: JSONObject): String {
        val days = args.optInt("days", 7).coerceIn(1, 30)
        val cutoff = System.currentTimeMillis() - days * DAY_MS
        val summary = JSONObject()
        var dataPoints = 0L
        val missingTables = mutableListOf<String>()
        var readFailed = false

        fun readAggregate(table: String, query: String): LongArray? {
            if (database.tableColumns(table).isEmpty()) {
                missingTables += table
                return null
            }
            val values = database.aggregate(table, query, cutoff)
            if (values == null) readFailed = true
            return values
        }

        readAggregate(
            "steps_record_table",
            "SELECT COUNT(*), COALESCE(SUM(count),0) FROM steps_record_table WHERE end_time>=?",
        )?.let {
            summary.put("steps", JSONObject().put("records", it[0]).put("count", it[1]))
            dataPoints += it[0]
        }
        readAggregate(
            "sleep_session_record_table",
            "SELECT COUNT(*), COALESCE(SUM(end_time-start_time),0) FROM sleep_session_record_table WHERE end_time>=?",
        )?.let {
            summary.put("sleep", JSONObject().put("sessions", it[0]).put("duration_ms", it[1]))
            dataPoints += it[0]
        }
        readAggregate(
            "exercise_session_record_table",
            "SELECT COUNT(*), COALESCE(SUM(end_time-start_time),0) FROM exercise_session_record_table WHERE end_time>=?",
        )?.let {
            summary.put("exercise", JSONObject().put("sessions", it[0]).put("duration_ms", it[1]))
            dataPoints += it[0]
        }
        if (
            database.hasColumns("heart_rate_record_table", setOf("row_id", "end_time")) &&
            database.hasColumns("heart_rate_record_series_table", setOf("parent_key", "beats_per_minute"))
        ) {
            database.rawQuery(
                "SELECT COUNT(*), MIN(beats_per_minute), MAX(beats_per_minute), AVG(beats_per_minute) " +
                    "FROM heart_rate_record_series_table WHERE parent_key IN " +
                    "(SELECT row_id FROM heart_rate_record_table WHERE end_time>=?)",
                arrayOf(cutoff.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst() && cursor.getLong(0) > 0) {
                    summary.put(
                        "heart_rate",
                        JSONObject()
                            .put("samples", cursor.getLong(0))
                            .put("min_bpm", cursor.getLong(1))
                            .put("max_bpm", cursor.getLong(2))
                            .put("avg_bpm", cursor.getDouble(3)),
                    )
                    dataPoints += cursor.getLong(0)
                }
            }
        }
        database.latestMeasurement("weight_record_table", "time", "weight", cutoff)
            ?.let {
                summary.put("latest_weight_kg", it / 1_000.0)
                dataPoints += 1
            }
        database.latestMeasurement("oxygen_saturation_record_table", "time", "percentage", cutoff)
            ?.let {
                summary.put("latest_oxygen_saturation", it)
                dataPoints += 1
            }
        val result = JSONObject()
            .put("ok", true)
            .put("tool", "get_health_summary")
            .put("window_days", days)
            .put("summary", summary)
        if (dataPoints == 0L) {
            // 全部为 0：显式给出权限/可读性判定，区分“未授权或不可读”与“窗口内确实无数据”。
            val corePresent = CORE_HEALTH_TABLES.count { it !in missingTables }
            val readable = corePresent > 0 && !readFailed
            result
                .put("permission", if (readable) "granted" else "unavailable")
                .put("readable", readable)
            if (missingTables.isNotEmpty()) result.put("missing_tables", JSONArray(missingTables))
            result.put(
                "note",
                if (readable) {
                    "健康数据来源可读，最近 $days 天确实没有记录：数据可能尚未同步；" +
                        "可在系统健康应用中确认数据来源，或增大 days 后重试"
                } else {
                    "健康数据来源不可读或结构不匹配：可能未授予健康数据访问权限、" +
                        "设备没有 Health Connect 数据或记录尚未同步；请检查权限健康页授权，" +
                        "并先在系统健康应用中产生数据后重试"
                },
            )
        }
        return result.toString()
    }

    private fun readDatabase(source: DatabaseSource, unavailableCode: String, block: (SQLiteDatabase) -> String): String =
        synchronized(snapshotLock) {
            val userId = context.dataDir.parentFile?.name?.toIntOrNull()
                ?: return@synchronized error(
                    code = unavailableCode,
                    message = "无法确定当前 Android 用户",
                    reason = SNAPSHOT_REASON_USER_ID_UNKNOWN,
                    note = "无法从数据目录推断当前 Android 用户：请在受支持的多用户环境中重试",
                )
            val sourcePath = source.path.replace("{user}", userId.toString())
            val outcome = createSnapshot(sourcePath, source.maxBytes)
            val failure = outcome.failure
            if (failure != null) {
                // 快照失败原因透出：退出码映射到可区分码；未声明前缀的数据源仍回退旧码（兼容）。
                return@synchronized error(
                    code = snapshotFailureErrorCode(failure.reason, source.errorPrefix, unavailableCode),
                    message = source.unavailableMessage,
                    reason = failure.reason,
                    exitCode = failure.exitCode,
                    note = failure.note,
                )
            }
            val snapshot = outcome.snapshot
                ?: return@synchronized error(
                    code = unavailableCode,
                    message = source.unavailableMessage,
                    reason = SNAPSHOT_REASON_COPY_FAILED,
                )
            try {
                runCatching {
                    SQLiteDatabase.openDatabase(
                        snapshot.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    ).use(block)
                }.getOrElse {
                    // 快照复制成功却打不开：与“库不存在”区分，提示结构不匹配或文件损坏。
                    error(
                        code = unavailableCode,
                        message = source.unavailableMessage,
                        reason = SNAPSHOT_REASON_OPEN_FAILED,
                        note = "快照复制成功但数据库无法打开：可能结构与已知版本不一致或文件损坏",
                    )
                }
            } finally {
                deleteSnapshot(snapshot)
            }
        }

    private fun createSnapshot(source: String, maxBytes: Long): SnapshotOutcome {
        cleanupStaleSnapshots()
        val snapshot = runCatching { File.createTempFile(SNAPSHOT_PREFIX, ".db", context.cacheDir) }.getOrNull()
            ?: return SnapshotOutcome(
                snapshot = null,
                failure = SnapshotFailure(
                    reason = SNAPSHOT_REASON_COPY_FAILED,
                    exitCode = null,
                    note = "无法在应用缓存目录创建快照文件：请检查可用存储空间后重试",
                ),
            )
        val command = buildString {
            append("[ -f ").append(shellQuote(source)).append(" ] ").append(snapshotFail(21))
            append("[ ! -L ").append(shellQuote(source)).append(" ] ").append(snapshotFail(22))
            append("[ \"\$(stat -c %s ").append(shellQuote(source)).append(")\" -le ").append(maxBytes).append(" ] ").append(snapshotFail(23))
            append("cp ").append(shellQuote(source)).append(' ').append(shellQuote(snapshot.absolutePath)).append(' ').append(snapshotFail(24))
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val extraSource = source + suffix
                val extraTarget = snapshot.absolutePath + suffix
                append("if [ -f ").append(shellQuote(extraSource)).append(" ]; then ")
                append("[ ! -L ").append(shellQuote(extraSource)).append(" ] ").append(snapshotFail(25))
                append("[ \"\$(stat -c %s ").append(shellQuote(extraSource)).append(")\" -le ").append(maxBytes).append(" ] ").append(snapshotFail(26))
                append("cp ").append(shellQuote(extraSource)).append(' ').append(shellQuote(extraTarget)).append(' ').append(snapshotFail(25)).append("fi; ")
            }
        }
        val result = root.execute(command, timeoutMillis = 15_000L, maxOutputBytes = 8 * 1024)
        if (result.ok) return SnapshotOutcome(snapshot, null)
        deleteSnapshot(snapshot)
        return SnapshotOutcome(null, snapshotFailure(result))
    }

    /**
     * 失败分支片段：先把原始退出码写入 stderr（[SNAPSHOT_EXIT_MARKER]NN）再以该码退出，
     * 让上层能区分“库缺失/超出上限/复制失败”，而不是把所有失败折叠成一个不可用码。
     */
    private fun snapshotFail(code: Int): String = "|| { echo \"$SNAPSHOT_EXIT_MARKER$code\" >&2; exit $code; }; "

    private fun snapshotFailure(result: BoundedRootCommandExecutor.Result): SnapshotFailure {
        val exitCode = SNAPSHOT_EXIT_PATTERN.find(result.stderr)?.groupValues?.get(1)?.toIntOrNull()
            ?: result.errorCode.toIntOrNull()
        val reason = when {
            exitCode == 21 -> SNAPSHOT_REASON_SOURCE_MISSING
            exitCode == 23 || exitCode == 26 -> SNAPSHOT_REASON_SOURCE_TOO_LARGE
            exitCode == 22 || exitCode == 24 || exitCode == 25 -> SNAPSHOT_REASON_COPY_FAILED
            result.errorCode in SNAPSHOT_ROOT_ERROR_CODES -> SNAPSHOT_REASON_ROOT_REQUIRED
            result.timedOut -> SNAPSHOT_REASON_TIMEOUT
            else -> SNAPSHOT_REASON_COPY_FAILED
        }
        return SnapshotFailure(reason = reason, exitCode = exitCode, note = snapshotFailureNote(reason))
    }

    /** 每个失败原因对应的可行动提示，避免调用方只拿到一个笼统的“不可用”。 */
    private fun snapshotFailureNote(reason: String): String = when (reason) {
        SNAPSHOT_REASON_ROOT_REQUIRED ->
            "数据库快照需要 Root 权限：请先授予 Root 或改用系统接口后重试"
        SNAPSHOT_REASON_SOURCE_MISSING ->
            "源数据库文件不存在：对应应用可能未安装或尚未生成数据；请先打开对应应用产生数据后重试"
        SNAPSHOT_REASON_SOURCE_TOO_LARGE ->
            "源数据库超过允许的快照上限：请清理历史记录或改用聚合查询后重试"
        SNAPSHOT_REASON_TIMEOUT ->
            "数据库快照超时：存储响应过慢，可稍后重试"
        else ->
            "数据库快照复制失败：文件可能被占用、权限不足或被安全策略拒绝"
    }

    private fun SQLiteDatabase.rows(
        table: String,
        columns: List<String>,
        selection: String?,
        selectionArgs: Array<String>? = null,
        order: String,
        limit: Int,
    ): JSONArray {
        val available = tableColumns(table)
        val projection = columns.filter(available::contains)
        return JSONArray().also { rows ->
            query(table, projection.toTypedArray(), selection, selectionArgs, null, null, order, limit.toString()).use { cursor ->
                while (cursor.moveToNext()) rows.put(cursor.toJson())
            }
        }
    }

    private fun SQLiteDatabase.aggregate(table: String, query: String, cutoff: Long): LongArray? {
        if (tableColumns(table).isEmpty()) return null
        return runCatching {
            rawQuery(query, arrayOf(cutoff.toString())).use { cursor ->
                if (!cursor.moveToFirst()) null else longArrayOf(cursor.getLong(0), cursor.getLong(1))
            }
        }.getOrNull()
    }

    private fun SQLiteDatabase.latestMeasurement(table: String, timeColumn: String, valueColumn: String, cutoff: Long): Double? {
        if (!hasColumns(table, setOf(timeColumn, valueColumn))) return null
        return rawQuery(
            "SELECT $valueColumn FROM $table WHERE $timeColumn>=? ORDER BY $timeColumn DESC LIMIT 1",
            arrayOf(cutoff.toString()),
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getDouble(0) else null }
    }

    private fun SQLiteDatabase.hasColumns(table: String, required: Set<String>): Boolean =
        tableColumns(table).containsAll(required)

    private fun SQLiteDatabase.tableColumns(table: String): Set<String> = runCatching {
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val name = cursor.getColumnIndex("name")
            buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
        }
    }.getOrDefault(emptySet())

    private fun Cursor.toJson(): JSONObject = JSONObject().also { row ->
        for (index in 0 until columnCount) {
            if (isNull(index)) continue
            val value: Any = when (getType(index)) {
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_STRING -> getString(index).take(MAX_FIELD_CHARS)
                else -> continue
            }
            row.put(getColumnName(index), value)
        }
    }

    private fun ok(tool: String, items: JSONArray, limit: Int): String = JSONObject()
        .put("ok", true)
        .put("tool", tool)
        .put("items", items)
        .put("count", items.length())
        .put("truncated", items.length() == limit)
        .toString()

    private fun cleanupStaleSnapshots() {
        context.cacheDir.listFiles()?.filter { it.name.startsWith(SNAPSHOT_PREFIX) }?.forEach(File::delete)
    }

    private fun deleteSnapshot(snapshot: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(snapshot.absolutePath + it).delete() }
    }

    private fun String.escapeLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    /** 失败返回体：除 code/message 外按需透出原始退出码、失败原因与可行动提示。 */
    private fun error(
        code: String,
        message: String,
        reason: String? = null,
        exitCode: Int? = null,
        note: String? = null,
    ): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .apply {
            if (reason != null) {
                put("reason", reason)
                put("exit_code", exitCode ?: JSONObject.NULL)
            }
            if (note != null) put("note", note)
        }
        .toString()
    private fun sensitive(content: String) = AgentModelClient.ToolResult(content = content, sensitive = true)

    /** errorPrefix 非空的数据源把快照失败原因映射到 <prefix>_MISSING/_TOO_LARGE/_COPY_FAILED。 */
    private data class DatabaseSource(
        val path: String,
        val maxBytes: Long,
        val unavailableMessage: String,
        val errorPrefix: String? = null,
    )

    /** 快照结果：必然有一侧为空；失败侧携带原因、原始退出码与可行动提示。 */
    private data class SnapshotOutcome(val snapshot: File?, val failure: SnapshotFailure?)

    private data class SnapshotFailure(val reason: String, val exitCode: Int?, val note: String)

    private companion object {
        val snapshotLock = Any()
        const val SNAPSHOT_PREFIX = "eta-private-data-"

        /** 快照失败时 stderr 里的退出码标记，用于把失败原因映射到可区分错误码。 */
        const val SNAPSHOT_EXIT_MARKER = "eta-private-data-exit:"
        val SNAPSHOT_EXIT_PATTERN = Regex("eta-private-data-exit:(\\d+)")

        /** Root 相关的执行器错误码：快照失败时按 ROOT_REQUIRED 上报。 */
        val SNAPSHOT_ROOT_ERROR_CODES =
            setOf("ROOT_UNAVAILABLE", "ROOT_REQUIRED", "ROOT_DENIED", "PERMISSION_DENIED")
        const val MAX_FIELD_CHARS = 4_000
        const val DAY_MS = 24L * 60 * 60 * 1_000

        /** 核心健康数据表：全为 0 时用它们判断数据源是否存在（缺失=结构不匹配或未初始化）。 */
        val CORE_HEALTH_TABLES = listOf(
            "steps_record_table",
            "sleep_session_record_table",
            "exercise_session_record_table",
        )
        val CLOCK_DATABASE = DatabaseSource(
            "/data/user_de/{user}/com.coloros.alarmclock/databases/alarms.db",
            32L * 1024 * 1024,
            "无法读取 ColorOS 时钟数据库：可能时钟应用数据不存在、结构不匹配或文件权限不足；" +
                "请确认设备使用 ColorOS 时钟并已授予 Root",
            // 快照失败原因映射到 CLOCK_DB_MISSING/_TOO_LARGE/_COPY_FAILED，便于区分是缺库还是复制失败。
            errorPrefix = "CLOCK_DB",
        )
        val CLIPBOARD_DATABASE = DatabaseSource(
            "/data/user/{user}/com.sohu.inputmethod.sogouoem/databases/clipboard_db",
            32L * 1024 * 1024,
            "当前输入法没有可访问的剪贴板历史：可能输入法不是受支持版本、未开启剪贴板历史功能或数据库不存在",
        )
        val HEALTH_DATABASE = DatabaseSource(
            "/data/system_ce/{user}/healthconnect/healthconnect.db",
            256L * 1024 * 1024,
            "无法读取系统健康数据库：可能设备没有 Health Connect 数据、记录尚未同步或文件不可读；" +
                "请在系统健康应用中确认数据后再试",
        )
    }
}

/** 私有数据库快照的失败原因：透出到返回体的 reason 字段，并与 shell 退出码一一对应。 */
internal const val SNAPSHOT_REASON_SOURCE_MISSING = "SOURCE_MISSING"
internal const val SNAPSHOT_REASON_SOURCE_TOO_LARGE = "SOURCE_TOO_LARGE"
internal const val SNAPSHOT_REASON_COPY_FAILED = "COPY_FAILED"
internal const val SNAPSHOT_REASON_ROOT_REQUIRED = "ROOT_REQUIRED"
internal const val SNAPSHOT_REASON_TIMEOUT = "TIMEOUT"
internal const val SNAPSHOT_REASON_USER_ID_UNKNOWN = "USER_ID_UNKNOWN"
internal const val SNAPSHOT_REASON_OPEN_FAILED = "OPEN_FAILED"

/**
 * 快照失败原因 → 工具错误码。errorPrefix 为空的数据源沿用旧 unavailableCode（兼容既有调用方）；
 * 已迁移的数据源（时钟库 CLOCK_DB）按原因区分缺库、超出上限与复制失败。
 */
internal fun snapshotFailureErrorCode(reason: String, errorPrefix: String?, unavailableCode: String): String = when {
    errorPrefix == null -> unavailableCode
    reason == SNAPSHOT_REASON_ROOT_REQUIRED -> "ROOT_REQUIRED"
    reason == SNAPSHOT_REASON_SOURCE_MISSING -> "${errorPrefix}_MISSING"
    reason == SNAPSHOT_REASON_SOURCE_TOO_LARGE -> "${errorPrefix}_TOO_LARGE"
    else -> "${errorPrefix}_COPY_FAILED"
}
