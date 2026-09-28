package io.github.mangi.eta.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") || toolName in sensitiveTools

    private val sensitiveTools = setOf(
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        // get_current_context 与 get_current_location 共用同一位置来源：带位置的原始结果同样不落库。
        "get_current_context",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        // Clipboard read/write is treated like clipboard history: a read returns what the user just
        // copied (passwords, OTPs and card numbers land there constantly) and a write carries a
        // credential the model just produced. Neither belongs in the persisted session.
        "search_clipboard_history",
        "get_clipboard",
        "set_clipboard",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        "read_image",
        "set_setting",
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",
    )
}
