package io.github.mangi.eta.agent.accessibility

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 在 GUI 工具执行前确认 Eta 无障碍服务已经真实连接。
 *
 * 持久保护、Secure Settings 写入与断连重绑均由 system_server 后端负责。这里不申请 Root。
 * 放行顺序：无障碍实例可用 → 保护后端恢复并等待真实重绑 → 设备已授权 Root 时降级放行
 * （GUI 动作改走 uiautomator 节点 + Root 截图/按键通道）；三个通道都不可用才 fail closed，
 * 且失败消息必须给出可执行的补救指引。
 */
object AgentAccessibilityKeeper {
    internal fun ensureEnabledForGuiOperation(
        context: Context,
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): AccessibilityEnableResult {
        val startedAt = SystemClock.elapsedRealtime()
        // 诊断探针：无论走到哪个分支，失败日志都要能说明每个通道当时的状态。
        var serviceAvailableProbe = false
        var protectionAvailableProbe = false
        var protectionEnabledProbe = false
        var recoveryState = "not_requested"
        var bindingState = "not_requested"
        var rootProbe = false
        val result = ensureAvailable(
            serviceAvailable = {
                AgentAccessibilityService.isAvailable().also { serviceAvailableProbe = it }
            },
            protectionEnabled = {
                AccessibilityProtectionClient.isEnabled(context).also { protectionEnabledProbe = it }
            },
            requestRecovery = {
                val status = AccessibilityProtectionClient.requestRecoveryBlocking(context)
                recoveryState = status.name
                status == AccessibilityProtectionClient.ControlStatus.APPLIED
            },
            awaitServiceBinding = {
                awaitServiceBinding().also { bindingState = if (it) "bound" else "timeout" }
            },
            protectionAvailable = {
                (EtaApp.serviceInstance != null).also { protectionAvailableProbe = it }
            },
            rootAvailable = { rootAvailable().also { rootProbe = it } },
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (result.available) {
            AndroidAgentLogger.info(
                "Agent accessibility action=ensure_for_gui outcome=completed " +
                    "degraded=${result.degraded} recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs" +
                    (if (result.degraded) " degraded_reason=${result.degradedReason}" else "")
            )
        } else {
            AndroidAgentLogger.warn(
                "Agent accessibility action=ensure_for_gui outcome=failed " +
                    "code=${result.code} recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs diagnostics{serviceAvailable=$serviceAvailableProbe " +
                    "protectionAvailable=$protectionAvailableProbe " +
                    "protectionEnabled=$protectionEnabledProbe recovery=$recoveryState " +
                    "binding=$bindingState rootAvailable=$rootProbe}"
            )
        }
        return result
    }

    internal fun ensureAvailable(
        serviceAvailable: () -> Boolean,
        protectionEnabled: () -> Boolean,
        requestRecovery: () -> Boolean,
        awaitServiceBinding: () -> Boolean,
        protectionAvailable: () -> Boolean = { true },
        rootAvailable: () -> Boolean = { RootAccess.isGranted },
    ): AccessibilityEnableResult {
        // 1) 无障碍实例已连接：完整能力，直接放行。
        if (serviceAvailable()) {
            return AccessibilityEnableResult.available(recoveryRequested = false)
        }

        var failureCode = "ACCESSIBILITY_UNAVAILABLE"
        var failureMessage: String
        var recoveryRequested = false

        // 2) 保护后端可用且开启：请求持久恢复并等待真实重绑。
        val protectionUsable = protectionAvailable()
        if (protectionUsable && protectionEnabled()) {
            if (requestRecovery()) {
                recoveryRequested = true
                if (awaitServiceBinding()) {
                    return AccessibilityEnableResult.available(recoveryRequested = true)
                }
                failureCode = "ACCESSIBILITY_REPAIR_TIMEOUT"
                failureMessage = "Eta 无障碍服务未在恢复时限内重新连接；本次 GUI 操作未执行。" +
                    "请在系统设置→无障碍中手动重新开启 Eta 服务后重试；" +
                    "或授予 Eta Root 权限（当前未授予）以自动降级到 Root 通道。"
            } else {
                recoveryRequested = true
                failureCode = "ACCESSIBILITY_PROTECTION_UNAVAILABLE"
                failureMessage = "无障碍保护后端未响应恢复请求（后端缺失、被拒绝或超时）；本次 GUI 操作未执行。" +
                    "请确认 LSPosed system 作用域保护模块已启用并重启设备，" +
                    "并在系统设置→无障碍中确认 Eta 已开启；" +
                    "或授予 Eta Root 权限（当前未授予）以自动降级到 Root 通道。"
            }
        } else {
            failureMessage = if (protectionUsable) {
                "Eta 无障碍服务未连接，且无障碍保护开关处于关闭状态；本次 GUI 操作未执行。" +
                    "请在系统设置→无障碍中开启 Eta，或在本应用中重新开启无障碍保护；" +
                    "或授予 Eta Root 权限（当前未授予）以自动降级到 Root 通道。"
            } else {
                "Eta 无障碍服务未连接，且保护后端不可用（未安装或未生效）；本次 GUI 操作未执行。" +
                    "请在系统设置→无障碍中开启 Eta；若使用 LSPosed 保护模块，" +
                    "请确认其 system 作用域已启用并重启设备；" +
                    "或授予 Eta Root 权限（当前未授予）以自动降级到 Root 通道。"
            }
        }

        // 3) Root 降级：设备已授权 su 时仍可执行 GUI 动作（uiautomator 节点 + Root 截图/按键）。
        if (rootAvailable()) {
            return AccessibilityEnableResult.available(
                recoveryRequested = recoveryRequested,
                degraded = true,
                degradedReason = when (failureCode) {
                    "ACCESSIBILITY_PROTECTION_UNAVAILABLE" -> "保护后端未响应恢复请求"
                    "ACCESSIBILITY_REPAIR_TIMEOUT" -> "无障碍服务未在恢复时限内重绑"
                    else -> "无障碍服务未开启"
                },
            )
        }

        // 4) 无障碍、保护恢复与 Root 都不可用：fail closed。
        return AccessibilityEnableResult.failure(
            code = failureCode,
            message = failureMessage,
            recoveryRequested = recoveryRequested,
        )
    }

    private fun awaitServiceBinding(): Boolean {
        repeat(SERVICE_BIND_ATTEMPTS) {
            if (AgentAccessibilityService.isAvailable()) return true
            SystemClock.sleep(SERVICE_BIND_POLL_MS)
        }
        return AgentAccessibilityService.isAvailable()
    }

    private const val SERVICE_BIND_ATTEMPTS = 60
    private const val SERVICE_BIND_POLL_MS = 100L
}

internal data class AccessibilityEnableResult(
    val available: Boolean,
    val code: String = "",
    val message: String = "",
    val recoveryRequested: Boolean,
    /** 无障碍不可用但已授权 Root：本次放行使用的是 uiautomator + Root 降级通道。 */
    val degraded: Boolean = false,
    val degradedReason: String = "",
) {
    companion object {
        fun available(
            recoveryRequested: Boolean,
            degraded: Boolean = false,
            degradedReason: String = "",
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = true,
            recoveryRequested = recoveryRequested,
            degraded = degraded,
            degradedReason = degradedReason,
        )

        fun failure(
            code: String,
            message: String,
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = false,
            code = code,
            message = message,
            recoveryRequested = recoveryRequested,
        )
    }
}
