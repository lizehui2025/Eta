package io.github.mangi.eta.agent.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger

class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            // AgentRuntimeService 不调用 startForeground：必须使用 startService。
            // startForegroundService 要求服务在 5 秒内进入前台，否则系统抛出
            // RemoteServiceException 杀死整个进程；闹钟触发时应用处于临时允许名单，startService 可用。
            context.startService(
                Intent(context, AgentRuntimeService::class.java)
                    .setAction(AgentRuntimeService.ACTION_KEEP_ALIVE),
            )
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("KeepAlive receiver failed: type=${failure.javaClass.simpleName}")
        }
        // 闹钟脉冲同样用于补配 Root 留存（应用处于临时允许名单，可启动前台服务）。
        RootKeepAlive.ensureServiceRunning(context)
        // setAndAllowWhileIdle 是一次性闹钟：本次触发后重新排下一次，否则保活只生效一次。
        // 周期 Job 已排上时不再续订闹钟，避免两套保活重复唤醒。
        if (!KeepAliveScheduler.isJobScheduled(context)) {
            KeepAliveScheduler.scheduleAlarmFallback(context)
        }
    }
}
