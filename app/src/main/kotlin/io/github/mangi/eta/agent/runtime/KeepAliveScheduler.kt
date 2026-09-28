package io.github.mangi.eta.agent.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

object KeepAliveScheduler {

    private const val JOB_ID = 1001
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /**
     * 按场景刷新保活：只有活跃 run、待处理结果或用户显式开启常驻时才保留
     * Job/闹钟；空闲且未开启常驻时主动取消，避免无任务时每 15 分钟唤醒。
     */
    fun scheduleIfNeeded(
        context: Context,
        activeRun: Boolean,
        pendingResults: Boolean,
        alwaysOn: Boolean,
    ) {
        if (shouldSchedule(activeRun, pendingResults, alwaysOn)) {
            scheduleKeepAlive(context)
        } else {
            cancelKeepAlive(context)
        }
    }

    internal fun shouldSchedule(
        activeRun: Boolean,
        pendingResults: Boolean,
        alwaysOn: Boolean,
    ): Boolean = alwaysOn || activeRun || pendingResults

    /**
     * 保活只是优化：任何调度失败都必须退化为"没有保活"，绝不能把调用方（服务 onCreate，在启动路径上）带崩。
     * 旧实现在 setPersisted(true) 缺权限时让 JobScheduler 抛 IllegalArgumentException，导致整个 Runtime 服务创建失败。
     */
    fun scheduleKeepAlive(context: Context) {
        runCatching { scheduleJobOrAlarm(context) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("keep_alive_schedule_failed") {
                "Keep-alive scheduling failed: type=${throwable.safeLogType()}"
            }
            runCatching { scheduleAlarmFallback(context) }
        }
    }

    private fun scheduleJobOrAlarm(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        if (jobScheduler == null) {
            scheduleAlarmFallback(context)
            return
        }

        val component = ComponentName(context, KeepAliveJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setPersisted(true)
            .setPeriodic(INTERVAL_MS)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()

        val result = jobScheduler.schedule(jobInfo)
        if (result == JobScheduler.RESULT_SUCCESS) {
            AndroidAgentLogger.debug { "KeepAlive job scheduled successfully" }
        } else {
            AndroidAgentLogger.warn("Failed to schedule keep-alive job, falling back to alarm")
            scheduleAlarmFallback(context)
        }
    }

    fun cancelKeepAlive(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
        jobScheduler?.cancel(JOB_ID)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.cancel(createKeepAlivePendingIntent(context))
    }

    /** 周期 Job 是否仍在系统排程中；闹钟兜底续订前先确认，避免两套保活重复唤醒。 */
    fun isJobScheduled(context: Context): Boolean =
        (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)
            ?.getPendingJob(JOB_ID) != null

    internal fun scheduleAlarmFallback(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pendingIntent = createKeepAlivePendingIntent(context)
        alarmManager?.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            pendingIntent
        )
    }

    private fun createKeepAlivePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java).apply {
            action = "io.github.mangi.eta.KEEP_ALIVE"
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
