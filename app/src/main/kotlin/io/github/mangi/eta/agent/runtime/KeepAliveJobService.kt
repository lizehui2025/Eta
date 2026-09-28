package io.github.mangi.eta.agent.runtime

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger

class KeepAliveJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            // AgentRuntimeService 不调用 startForeground，不能用 startForegroundService（否则 5 秒后崩溃）。
            // JobScheduler 执行期间应用处于临时允许名单，startService 可用。
            startService(
                Intent(this, AgentRuntimeService::class.java)
                    .setAction(AgentRuntimeService.ACTION_KEEP_ALIVE),
            )
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("KeepAlive failed to restart runtime: type=${failure.javaClass.simpleName}")
        }
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
