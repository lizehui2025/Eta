package io.github.mangi.eta.agent.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.NotificationHistoryRepository
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AgentNotificationHistoryService : NotificationListenerService() {
    private val repository by lazy { NotificationHistoryRepository(this) }

    /**
     * 回调运行在主线程：字段提取留在回调线程，SQLite 写入串行放到后台。
     * 通知密集时同步写库会直接阻塞主线程（ANR），队列满时丢弃最旧的待写记录（历史本来就是尽力而为）。
     */
    private val recordExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(RECORD_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "eta-notification-history").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        record(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedService = this
        activeNotifications?.forEach(::record)
    }

    override fun onListenerDisconnected() {
        if (connectedService === this) connectedService = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        recordExecutor.shutdown()
        super.onDestroy()
    }

    private fun record(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val entry = RecordEntry(
            key = sbn.key,
            packageName = sbn.packageName,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            postedAt = sbn.postTime,
        )
        runCatching {
            recordExecutor.execute {
                runCatching {
                    repository.record(
                        key = entry.key,
                        packageName = entry.packageName,
                        title = entry.title,
                        text = entry.text,
                        subText = entry.subText,
                        postedAt = entry.postedAt,
                    )
                }.onFailure { failure ->
                    AndroidAgentLogger.warnThrottled("notification_history_record_failed") {
                        "Notification history record failed: type=${failure.safeLogType()}"
                    }
                }
            }
        }
    }

    private data class RecordEntry(
        val key: String,
        val packageName: String,
        val title: String?,
        val text: String?,
        val subText: String?,
        val postedAt: Long,
    )

    companion object {
        private const val RECORD_QUEUE_CAPACITY = 128

        @Volatile
        private var connectedService: AgentNotificationHistoryService? = null

        internal fun currentNotifications(): Array<StatusBarNotification>? {
            val service = connectedService ?: return null
            return try {
                service.activeNotifications
            } catch (_: RuntimeException) {
                null
            }
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return false
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, AgentNotificationHistoryService::class.java),
            )
        }
    }
}
