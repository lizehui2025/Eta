package io.github.mangi.eta.agent.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.agent.device.RootAccess
import java.util.Locale
import org.json.JSONArray

/**
 * Per-round frozen runtime conditions; contains no user switches and triggers no permission requests.
 *
 * Defaults are all "unavailable / not granted". Capability projection must fail closed: a caller
 * that forgets a condition publishes fewer tools rather than offering the model tools it cannot
 * execute. Production may only use [capture] to sample real device state; an offline scenario that
 * genuinely wants everything must say so with [full].
 */
internal data class AgentToolCapabilities(
    val rootAvailable: Boolean,
    val lsposedAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = false,
    val accessibilityRecoveryAvailable: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val usageAllowed: Boolean = false,
    val locationAllowed: Boolean = false,
    val colorOs: Boolean = false,
) {
    fun unavailableCode(name: String): String? {
        val requirement = AgentToolRequirements.find(name) ?: return "UNKNOWN_TOOL"
        if (requirement.rootRequirement == RootRequirement.REQUIRED && !rootAvailable) return "ROOT_REQUIRED"
        if (requirement.lsposedRequirement == LsposedRequirement.REQUIRED && !lsposedAvailable) return "LSPOSED_REQUIRED"
        if (requirement.colorOs && !colorOs) return "DEVICE_UNSUPPORTED"
        if (requirement.accessibility && !accessibilityAvailable && !accessibilityRecoveryAvailable) {
            return "ACCESSIBILITY_UNAVAILABLE"
        }
        return when (requirement.systemAccess) {
            ToolSystemAccess.NONE -> null
            ToolSystemAccess.NOTIFICATIONS -> if (notificationsAllowed ||
                (rootAvailable && (name == "recent_notifications" ||
                    (name == "search_personal_orders" && colorOs)))
            ) null else "NOTIFICATION_ACCESS_REQUIRED"
            ToolSystemAccess.USAGE -> if (usageAllowed) null else "APP_USAGE_ACCESS_REQUIRED"
            ToolSystemAccess.LOCATION -> if (locationAllowed) null else "LOCATION_PERMISSION_REQUIRED"
        }
    }

    fun project(tools: JSONArray): JSONArray {
        val rootProjected = AgentToolRequirements.project(tools, rootAvailable)
        return JSONArray().also { visible ->
            for (index in 0 until rootProjected.length()) {
                val tool = rootProjected.getJSONObject(index)
                val name = tool.getJSONObject("function").getString("name")
                if (unavailableCode(name) == null) visible.put(tool)
            }
        }
    }

    companion object {
        /**
         * Full-capability profile: every condition treated as available / granted.
         *
         * For tests, catalog assembly and explicitly declared offline scenarios only. Production must
         * never use it to bypass real device state — that offers the model tools it cannot execute,
         * and the user only finds out when execution fails.
         */
        fun full(
            rootAvailable: Boolean = true,
            lsposedAvailable: Boolean = false,
            accessibilityAvailable: Boolean = true,
            accessibilityRecoveryAvailable: Boolean = false,
            notificationsAllowed: Boolean = true,
            usageAllowed: Boolean = true,
            locationAllowed: Boolean = true,
            colorOs: Boolean = true,
        ): AgentToolCapabilities = AgentToolCapabilities(
            rootAvailable = rootAvailable,
            lsposedAvailable = lsposedAvailable,
            accessibilityAvailable = accessibilityAvailable,
            accessibilityRecoveryAvailable = accessibilityRecoveryAvailable,
            notificationsAllowed = notificationsAllowed,
            usageAllowed = usageAllowed,
            locationAllowed = locationAllowed,
            colorOs = colorOs,
        )

        fun isColorOsDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in
            setOf("oppo", "oneplus", "realme")

        /** 与 LOCATION 能力同一口径：后台定位与粗略/精确定位都要授予。 */
        fun locationAccessGranted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)

        fun capture(context: Context): AgentToolCapabilities = AgentToolCapabilities(
            rootAvailable = RootAccess.isGranted,
            lsposedAvailable = EtaApp.serviceInstance != null,
            accessibilityAvailable = AgentAccessibilityService.isAvailable(),
            accessibilityRecoveryAvailable = EtaApp.serviceInstance != null &&
                AccessibilityProtectionClient.isEnabled(context),
            notificationsAllowed = AgentNotificationHistoryService.isEnabled(context),
            usageAllowed = AgentPersonalContextTools.hasUsageAccess(context),
            locationAllowed = locationAccessGranted(context),
            colorOs = isColorOsDevice(),
        )
    }
}
