package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity

/**
 * Root 后台留存前台服务：留存开启期间的进程锚点。
 *
 * 前台服务把进程固定在 foreground-service 优先级（LMKD 高优先），配合
 * [RootKeepAlive] 的 oom 复写、系统策略与 root watchdog，构成"杀掉也能恢复"的留存链。
 * 服务自身 START_STICKY：系统回收后会主动重建；root watchdog 则负责它被强杀的场景。
 *
 * 非用户任务的执行生命周期不经过这里；关闭留存开关（设置页或通知按钮）即停止服务并回收。
 */
internal class AgentKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.keepalive_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Prefs.initLocal(this)
        if (intent?.action == ACTION_STOP) {
            // 通知里的"停止留存"：同步关闭开关，设置页开关监听会跟随刷新。
            runCatching {
                Prefs.localAgentPreferences()
                    ?.edit()
                    ?.putBoolean(Prefs.Keys.AGENT_ROOT_KEEP_ALIVE, false)
                    ?.apply()
            }
            RootKeepAlive.disable(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!RootKeepAlive.isEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val promoted = runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.isSuccess
        if (!promoted) {
            // 后台启动前台服务被系统拒绝（少见）：不留半个服务，交给 Job/闹钟与 watchdog 兜底。
            AndroidAgentLogger.warnThrottled("root_keepalive_foreground_failed") {
                "Root keep-alive foreground promotion failed"
            }
            stopSelf()
            return START_NOT_STICKY
        }
        RootKeepAlive.ensure(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(getString(R.string.keepalive_summary))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.keepalive_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "eta_keepalive"
        private const val NOTIFICATION_ID = 1108
        const val ACTION_STOP = "io.github.mangi.eta.action.STOP_ROOT_KEEPALIVE"

        @Volatile
        private var instance: AgentKeepAliveService? = null

        fun isRunning(): Boolean = instance != null

        fun intent(context: Context): Intent = Intent(context, AgentKeepAliveService::class.java)

        /** watchdog/开机脚本使用的组件名：`<包名>/<服务类名>`。 */
        fun componentName(context: Context): String =
            "${context.packageName}/${AgentKeepAliveService::class.java.name}"
    }
}
