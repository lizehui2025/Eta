package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.config.Prefs
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Root 留存脚本与策略命令：内容契约 + POSIX 语法校验（sh -n）。
 * 真机行为（oom 复写、拉起、白名单）由设备验证覆盖，这里保证脚本本身正确、可执行。
 */
class RootKeepAliveScriptTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val packageName = "io.github.mangi.eta.subagents"
    private val fgs = "$packageName/io.github.mangi.eta.agent.runtime.AgentKeepAliveService"
    private val activity = "$packageName/io.github.mangi.eta.ui.MainActivity"

    @Test
    fun watchdogScriptCarriesRetentionContract() {
        val script = buildRootKeepAliveWatchdogScript(packageName, fgs, activity)
        assertTrue("应带守护标记：$script", script.contains(RootKeepAlive.WATCHDOG_MARKER))
        assertTrue(script.contains("PKG='$packageName'"))
        assertTrue(script.contains("FGS='$fgs'"))
        assertTrue(script.contains("ACT='$activity'"))
        // 守护自身与应用进程都必须写 -1000。
        assertTrue(script.contains("echo -1000 > /proc/\$\$/oom_score_adj"))
        assertTrue(script.contains("echo -1000 > \"/proc/\$pid/oom_score_adj\""))
        // 无 pidof 时的 /proc 扫描兜底。
        assertTrue(script.contains("cmdline"))
        // 拉起链：前台服务 → 普通服务 → 界面。
        assertTrue(script.contains("am start-foreground-service -n \"\$FGS\""))
        assertTrue(script.contains("am start-service -n \"\$FGS\""))
        assertTrue(script.contains("am start -n \"\$ACT\""))
        assertTrue(script.contains("COOLDOWN_LOOPS=${RootKeepAlive.WATCHDOG_COOLDOWN_LOOPS}"))
        assertTrue(script.contains("INTERVAL=${RootKeepAlive.WATCHDOG_INTERVAL_SECONDS}"))
        assertPosixSyntax(script)
    }

    @Test
    fun bootScriptWaitsForUnlockAndStartsForegroundService() {
        val script = buildRootKeepAliveBootScript(fgs)
        assertTrue(script.contains("FGS='$fgs'"))
        assertTrue(script.contains("GUARD='${RootKeepAlive.BOOT_GUARD_PATH}'"))
        assertTrue(script.contains("sys.boot_completed"))
        assertTrue(script.contains("RUNNING_UNLOCKED"))
        assertTrue(script.contains("am start-foreground-service -n \"\$FGS\""))
        assertPosixSyntax(script)
    }

    @Test
    fun enableCommandsCoverWhitelistAppOpsAndStandbyBucket() {
        assertEquals(
            listOf(
                "dumpsys deviceidle whitelist +$packageName",
                "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow",
                "cmd appops set $packageName START_FOREGROUND allow",
                "am set-standby-bucket $packageName active",
            ),
            buildRootKeepAliveEnableCommands(packageName),
        )
    }

    @Test
    fun disableCommandsRevertWhitelistAndAppOps() {
        assertEquals(
            listOf(
                "dumpsys deviceidle whitelist -$packageName",
                "cmd appops set $packageName RUN_ANY_IN_BACKGROUND default",
                "cmd appops set $packageName START_FOREGROUND default",
            ),
            buildRootKeepAliveDisableCommands(packageName),
        )
    }

    @Test
    fun rootKeepAlivePreferenceDefaultsOffAndStaysLocal() {
        assertEquals(false, Prefs.Keys.BOOLEAN_DEFAULTS[Prefs.Keys.AGENT_ROOT_KEEP_ALIVE])
        assertTrue(Prefs.Keys.AGENT_ROOT_KEEP_ALIVE in Prefs.Keys.LOCAL_AGENT_KEYS)
        assertFalse(Prefs.Keys.AGENT_ROOT_KEEP_ALIVE == Prefs.Keys.AGENT_ALWAYS_ON_KEEP_ALIVE)
    }

    /** sh -n 只解析不执行：拦截语法错误、缺失 done/fi 与未闭合引号。 */
    private fun assertPosixSyntax(script: String) {
        val file = temporary.newFile("script-${System.nanoTime()}.sh")
        file.writeText(script)
        val process = ProcessBuilder("sh", "-n", file.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        assertTrue("sh -n 超时", finished)
        assertEquals("sh -n 报错：$output", 0, process.exitValue())
    }
}
