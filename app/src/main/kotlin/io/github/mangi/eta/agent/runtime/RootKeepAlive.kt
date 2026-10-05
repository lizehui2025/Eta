package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Process
import androidx.core.content.ContextCompat
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.DaemonStartResult
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root 后台留存：不是"祈祷系统不杀"，而是三层兜底。
 *
 * 1. 进程保护：把应用进程的 `oom_score_adj` 写到 -1000（LMKD 最低优先级），
 *    前台服务提供 base 优先级；
 * 2. 系统策略：电池优化白名单 + `RUN_ANY_IN_BACKGROUND` / `START_FOREGROUND` appop +
 *    固定 `active` 待机桶，避免 Doze / App Standby / 后台限制降级；
 * 3. Root 守护进程：脱离应用进程组（setsid）的 watchdog 常驻 root 侧，持续复写
 *    `oom_score_adj`，并在应用被杀后通过 `am start-foreground-service` 拉起；
 *    另安装 `/data/adb/service.d` 开机脚本，重启后自动恢复。
 *
 * 守护进程经 [DetachedTaskSupervisor] 托管：记录落盘（App 重启后认领）、日志可查、
 * 可在终端守护任务面板中看到与停止。所有命令由 Eta 内部构造，不接受模型输入。
 */
internal object RootKeepAlive {

    /** 守护脚本标记：用于在托管记录里识别留存守护，升级/关闭时避免误杀其他守护任务。 */
    const val WATCHDOG_MARKER = "# eta-root-keepalive-watchdog"

    /** 开机脚本与守卫文件：守卫文件存在才拉起；应用关闭留存时删除两者。 */
    const val BOOT_SCRIPT_PATH = "/data/adb/service.d/eta-keepalive.sh"
    const val BOOT_GUARD_PATH = "/data/adb/eta-keepalive.enabled"

    /** watchdog 轮询间隔：被杀后最迟一个间隔内被发现并拉起。 */
    const val WATCHDOG_INTERVAL_SECONDS = 5

    /** 拉起失败的冷却轮数：避免应用崩溃循环期间疯狂 exec am。 */
    const val WATCHDOG_COOLDOWN_LOOPS = 2

    private const val POLICY_REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
    private const val WATCHDOG_CHECK_INTERVAL_MS = 60L * 1000L
    private const val MANAGER_PREFS = "eta_root_keepalive"
    private const val KEY_WATCHDOG_TASK_ID = "watchdog_task_id"

    /** 工作线程串行化：root 命令可能各花数秒，必须避免并发 ensure/disable 交叉。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-root-keepalive").apply { isDaemon = true }
    }
    private val ensureQueued = AtomicBoolean(false)
    @Volatile private var watchdogCheckedAt = 0L
    @Volatile private var policiesAppliedAt = 0L
    @Volatile private var bootScriptEnsured = false

    fun isEnabled(context: Context): Boolean = runCatching {
        Prefs.initLocal(context.applicationContext)
        Prefs.isEnabled(Prefs.Keys.AGENT_ROOT_KEEP_ALIVE)
    }.getOrDefault(false)

    /** 设置页开关变化：开启时启动留存前台服务，关闭时停止服务并回收全部留存措施。 */
    fun onPreferenceChanged(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        if (enabled) {
            // 未授权 Root 时用户也可以先开启：周期脉冲会在授权后自动补配，
            // 避免"先开关后授权"的次序问题导致留存一直未生效。
            KeepAliveScheduler.scheduleKeepAlive(app)
            ensureServiceRunning(app)
            ensure(app)
        } else {
            stopService(app)
            disable(app)
        }
    }

    /**
     * 确保留存前台服务在运行（已开启留存且 Root 已授权时）。
     * Job/闹钟脉冲与 App 进程启动都会调用；后台启动受限时静默降级，
     * 真正的兜底由 root watchdog 完成。
     */
    fun ensureServiceRunning(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || !RootAccess.isGranted) return
        if (AgentKeepAliveService.isRunning()) {
            ensure(app)
            return
        }
        val started = runCatching {
            ContextCompat.startForegroundService(app, AgentKeepAliveService.intent(app))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("root_keepalive_service_start_failed") {
                "Root keep-alive service start failed: type=${throwable.javaClass.simpleName}"
            }
        }.isSuccess
        if (!started) {
            // 前台服务起不来也要把守护进程补上：watchdog 是留存链的最后一环。
            ensure(app)
        }
    }

    fun stopService(context: Context) {
        runCatching { context.applicationContext.stopService(AgentKeepAliveService.intent(context.applicationContext)) }
    }

    /** 异步补齐留存措施；重复调用合并为一次（队列不堆叠）。 */
    fun ensure(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app) || !RootAccess.isGranted) return
        if (!ensureQueued.compareAndSet(false, true)) return
        worker.execute {
            try {
                if (!isEnabled(app) || !RootAccess.isGranted) return@execute
                protectSelf(app)
                val now = System.currentTimeMillis()
                if (now - policiesAppliedAt >= POLICY_REFRESH_INTERVAL_MS) {
                    if (applyDevicePolicies(app)) policiesAppliedAt = now
                }
                if (now - watchdogCheckedAt >= WATCHDOG_CHECK_INTERVAL_MS) {
                    if (ensureWatchdog(app)) watchdogCheckedAt = now
                }
                if (!bootScriptEnsured) {
                    bootScriptEnsured = ensureBootScript(app)
                }
                // 周期脉冲是留存链的最后一道网：前台服务与守护进程都被清掉时，
                // 15 分钟内的 Job/闹钟会把它们重新拉起来。
                KeepAliveScheduler.scheduleKeepAlive(app)
            } catch (failure: RuntimeException) {
                AndroidAgentLogger.warn(
                    "Root keep-alive ensure failed: type=${failure.javaClass.simpleName}"
                )
            } finally {
                ensureQueued.set(false)
            }
        }
    }

    /** 关闭留存：停止守护进程、删除开机脚本、回滚系统策略。 */
    fun disable(context: Context) {
        val app = context.applicationContext
        worker.execute {
            try {
                val supervisor = supervisor(app)
                supervisor.list()
                    .filter { it.running && it.task.command.contains(WATCHDOG_MARKER) }
                    .forEach { status ->
                        supervisor.stop(status.task.id)
                    }
                runCatching {
                    BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
                        executor.execute(
                            "rm -f ${shellQuote(BOOT_SCRIPT_PATH)} ${shellQuote(BOOT_GUARD_PATH)}",
                            timeoutMillis = 10_000L,
                        )
                    }
                }
                revertDevicePolicies(app)
                managerPrefs(app).edit().remove(KEY_WATCHDOG_TASK_ID).apply()
                watchdogCheckedAt = 0L
                policiesAppliedAt = 0L
                bootScriptEnsured = false
                // 留存关闭后恢复常规脉冲调度：仅在"常驻"开关仍开启时保留 Job/闹钟。
                KeepAliveScheduler.scheduleIfNeeded(
                    context = app,
                    activeRun = false,
                    pendingResults = false,
                    alwaysOn = runCatching { Prefs.isEnabled(Prefs.Keys.AGENT_ALWAYS_ON_KEEP_ALIVE) }
                        .getOrDefault(false),
                )
                AndroidAgentLogger.info("Root keep-alive outcome=disabled")
            } catch (failure: RuntimeException) {
                AndroidAgentLogger.warn(
                    "Root keep-alive disable failed: type=${failure.javaClass.simpleName}"
                )
            }
        }
    }

    /** oom 保护只针对本进程做一次即时生效；其余进程由 watchdog 循环复写。 */
    private fun protectSelf(context: Context) {
        val pid = Process.myPid()
        runCatching {
            BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
                val result = executor.execute(
                    "echo -1000 > /proc/$pid/oom_score_adj",
                    timeoutMillis = 8_000L,
                    maxOutputBytes = 256,
                )
                if (!result.ok) {
                    AndroidAgentLogger.warnThrottled("root_keepalive_protect_failed") {
                        "Root keep-alive oom protect failed: code=${result.errorCode}"
                    }
                }
            }
        }
    }

    private fun applyDevicePolicies(context: Context): Boolean = runCatching {
        BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
            var applied = false
            buildRootKeepAliveEnableCommands(context.packageName).forEach { command ->
                val result = executor.execute(command, timeoutMillis = 12_000L, maxOutputBytes = 512)
                applied = applied || result.ok
            }
            if (applied) AndroidAgentLogger.info("Root keep-alive outcome=policies_applied")
            applied
        }
    }.getOrDefault(false)

    private fun revertDevicePolicies(context: Context) {
        runCatching {
            BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
                buildRootKeepAliveDisableCommands(context.packageName).forEach { command ->
                    executor.execute(command, timeoutMillis = 12_000L, maxOutputBytes = 512)
                }
            }
        }
    }

    /**
     * 确保 watchdog 守护进程唯一且存活：先查托管记录（跨 App 重启仍有效），
     * 仅有标记缺失时才启动新实例，并清理意外重复的旧实例。
     */
    private fun ensureWatchdog(context: Context): Boolean {
        val supervisor = supervisor(context)
        val existing = runCatching { supervisor.list() }
            .getOrDefault(emptyList())
            .filter { it.task.command.contains(WATCHDOG_MARKER) }
        val running = existing.filter { it.running }
        running.drop(1).forEach { duplicate -> supervisor.stop(duplicate.task.id) }
        val current = running.firstOrNull()
        if (current != null) {
            managerPrefs(context).edit().putString(KEY_WATCHDOG_TASK_ID, current.task.id).apply()
            return true
        }
        // 记录仍在但进程已退出（如被手动停止）：先清记录再启动，避免误认为已在运行。
        existing.forEach { stale -> supervisor.stop(stale.task.id) }
        val script = buildRootKeepAliveWatchdogScript(
            packageName = context.packageName,
            foregroundComponent = AgentKeepAliveService.componentName(context),
            activityComponent = mainActivityComponent(context),
        )
        val result = supervisor.start(
            command = script,
            cwd = TerminalRuntime.HOST_WORKSPACE_PATH,
            identity = "root",
            environment = TerminalEnvironment.ANDROID,
        )
        return when (result) {
            is DaemonStartResult.Started -> {
                managerPrefs(context).edit().putString(KEY_WATCHDOG_TASK_ID, result.task.id).apply()
                AndroidAgentLogger.info("Root keep-alive outcome=watchdog_started taskId=${result.task.id}")
                true
            }
            is DaemonStartResult.Failed -> {
                AndroidAgentLogger.warnThrottled("root_keepalive_watchdog_failed") {
                    "Root keep-alive watchdog start failed: code=${result.code}"
                }
                false
            }
        }
    }

    /**
     * 安装开机脚本：脚本本体落在 /data/adb/service.d（Magisk/KernelSU/APatch 通用），
     * 由守卫文件控制是否生效；/data/adb 不存在（无 Root 管理器）时跳过。
     */
    private fun ensureBootScript(context: Context): Boolean {
        val source = runCatching {
            val dir = File(context.filesDir, "root-keepalive").apply { mkdirs() }
            val file = File(dir, "eta-keepalive-boot.sh")
            file.writeText(
                buildRootKeepAliveBootScript(AgentKeepAliveService.componentName(context)),
            )
            file
        }.getOrNull() ?: return false
        val command = "if [ -d /data/adb ]; then " +
            "mkdir -p /data/adb/service.d && " +
            "cp ${shellQuote(source.absolutePath)} ${shellQuote(BOOT_SCRIPT_PATH)} && " +
            "chmod 0755 ${shellQuote(BOOT_SCRIPT_PATH)} && " +
            "touch ${shellQuote(BOOT_GUARD_PATH)}; else exit 7; fi"
        return runCatching {
            BoundedRootCommandExecutor(AndroidAgentLogger).use { executor ->
                val result = executor.execute(command, timeoutMillis = 15_000L, maxOutputBytes = 512)
                if (result.ok) {
                    AndroidAgentLogger.info("Root keep-alive outcome=boot_script_installed")
                } else {
                    AndroidAgentLogger.warnThrottled("root_keepalive_boot_script_failed") {
                        "Root keep-alive boot script install failed: code=${result.errorCode}"
                    }
                }
                result.ok
            }
        }.getOrDefault(false)
    }

    private fun supervisor(context: Context): DetachedTaskSupervisor = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
    )

    private fun managerPrefs(context: Context) =
        context.getSharedPreferences(MANAGER_PREFS, Context.MODE_PRIVATE)

    private fun mainActivityComponent(context: Context): String =
        "${context.packageName}/${MainActivity::class.java.name}"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * watchdog 守护脚本（纯函数，便于单测与 sh -n 语法校验）。
 *
 * 循环语义：存活 → 复写全部应用进程的 `oom_score_adj`；死亡 → 冷却窗口内拉起前台服务，
 * 前台服务失败再退普通服务与界面。守护自身同样写 -1000 防清理。
 */
internal fun buildRootKeepAliveWatchdogScript(
    packageName: String,
    foregroundComponent: String,
    activityComponent: String,
): String = """
#!/system/bin/sh
# eta-root-keepalive-watchdog: Eta Root 后台留存守护进程（由 Eta 安装，可在 Eta 设置中关闭）
PKG='$packageName'
FGS='$foregroundComponent'
ACT='$activityComponent'
INTERVAL=${RootKeepAlive.WATCHDOG_INTERVAL_SECONDS}
COOLDOWN_LOOPS=${RootKeepAlive.WATCHDOG_COOLDOWN_LOOPS}

# 守护自身防清理：守护被杀则一切保护失效。
echo -1000 > /proc/${'$'}${'$'}/oom_score_adj 2>/dev/null

app_pids() {
  if command -v pidof >/dev/null 2>&1; then
    pidof "${'$'}PKG" 2>/dev/null
  else
    for d in /proc/[0-9]*; do
      n=${'$'}(tr '\000' '\n' < "${'$'}d/cmdline" 2>/dev/null | head -n 1)
      [ "${'$'}n" = "${'$'}PKG" ] && echo "${'$'}{d#/proc/}"
    done
  fi
}

cooldown=0
while true; do
  pids=${'$'}(app_pids)
  if [ -n "${'$'}pids" ]; then
    cooldown=0
    for pid in ${'$'}pids; do
      [ -d "/proc/${'$'}pid" ] || continue
      cur=${'$'}(cat "/proc/${'$'}pid/oom_score_adj" 2>/dev/null)
      if [ "${'$'}cur" != "-1000" ]; then
        echo -1000 > "/proc/${'$'}pid/oom_score_adj" 2>/dev/null
      fi
    done
  elif [ "${'$'}cooldown" -le 0 ]; then
    # 冷却窗口避免崩溃循环期疯狂拉起；前台服务优先，失败再退普通服务与界面。
    cooldown=${'$'}COOLDOWN_LOOPS
    am start-foreground-service -n "${'$'}FGS" >/dev/null 2>&1 \
      || am start-service -n "${'$'}FGS" >/dev/null 2>&1 \
      || am start -n "${'$'}ACT" >/dev/null 2>&1
  else
    cooldown=${'$'}((cooldown - 1))
  fi
  sleep ${'$'}INTERVAL
done
""".trimIndent()

/**
 * 开机自启脚本（纯函数）：等待开机完成与用户解锁后拉起留存前台服务；
 * 守卫文件由应用维护，关闭留存后脚本即使残留也不会生效。
 */
internal fun buildRootKeepAliveBootScript(
    foregroundComponent: String,
): String = """
#!/system/bin/sh
# eta-root-keepalive-boot: Eta Root 后台留存开机自启（由 Eta 安装，可在 Eta 设置中关闭）
FGS='$foregroundComponent'
GUARD='${RootKeepAlive.BOOT_GUARD_PATH}'

# 用户关闭留存后脚本可能仍残留在 /data/adb/service.d：以守卫文件为准。
[ -f "${'$'}GUARD" ] || exit 0

i=0
while [ "${'$'}(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
  i=${'$'}((i + 1))
  [ ${'$'}i -gt 300 ] && exit 0
done

# 等凭证加密存储解锁：未解锁时应用组件不可达，启动会失败。
i=0
while [ ${'$'}i -lt 900 ]; do
  dumpsys user 2>/dev/null | grep -q 'RUNNING_UNLOCKED' && break
  sleep 2
  i=${'$'}((i + 1))
done

i=0
while [ ${'$'}i -lt 12 ]; do
  [ -f "${'$'}GUARD" ] || exit 0
  if am start-foreground-service -n "${'$'}FGS" >/dev/null 2>&1; then
    exit 0
  fi
  sleep 5
  i=${'$'}((i + 1))
done
exit 0
""".trimIndent()

/** 开启留存的系统策略命令（纯函数，便于单测核对口径）。 */
internal fun buildRootKeepAliveEnableCommands(packageName: String): List<String> = listOf(
    "dumpsys deviceidle whitelist +$packageName",
    "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow",
    "cmd appops set $packageName START_FOREGROUND allow",
    "am set-standby-bucket $packageName active",
)

/** 关闭留存的策略回滚命令（纯函数）。待机桶由系统按使用情况自行收敛，无需回写。 */
internal fun buildRootKeepAliveDisableCommands(packageName: String): List<String> = listOf(
    "dumpsys deviceidle whitelist -$packageName",
    "cmd appops set $packageName RUN_ANY_IN_BACKGROUND default",
    "cmd appops set $packageName START_FOREGROUND default",
)
