package com.friction.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

object PermissionHelper {

    private val serviceClassName: String =
        FrictionAccessibilityService::class.java.name

    /**
     * True when Friction's accessibility service is enabled and usable.
     *
     * HyperOS often reports [Settings.Secure.ACCESSIBILITY_ENABLED] as 0 even when
     * services are bound — do **not** require that master flag.
     *
     * Prefer live signals:
     * 1. [FrictionAccessibilityService.instance] (bound in this process)
     * 2. [AccessibilityManager] enabled list (usually empty if service crashed)
     * 3. Secure enabled_accessibility_services string (user toggled on; may be crashed)
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        if (FrictionAccessibilityService.instance != null) return true
        if (isEnabledViaAccessibilityManager(context)) return true
        // User has toggled us on in Settings — show On even if OEM left us "crashed"
        // until they re-open Settings; intercept still needs a live bind.
        if (isEnabledViaSecureSettings(context)) return true
        return false
    }

    /**
     * Stronger check for "will actually intercept launches right now".
     * Used for banner copy / debugging — setup row uses [isAccessibilityEnabled].
     */
    fun isAccessibilityLive(context: Context): Boolean {
        if (FrictionAccessibilityService.instance != null) return true
        return isEnabledViaAccessibilityManager(context)
    }

    /** Overlay is required for the instant-curtain path. */
    fun isInstantCurtainReady(context: Context): Boolean =
        isAccessibilityLive(context) && isOverlayAllowed(context)

    private fun isEnabledViaSecureSettings(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        if (enabled.isBlank()) return false

        val expected = ComponentName(context.packageName, serviceClassName)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val raw = splitter.next()
            val cn = ComponentName.unflattenFromString(raw) ?: continue
            if (cn.packageName == context.packageName &&
                (cn.className == expected.className ||
                    cn.className.endsWith(".FrictionAccessibilityService") ||
                    cn.className.contains("FrictionAccessibilityService"))
            ) {
                return true
            }
            if (cn == expected) return true
        }
        // Fallback substring — some OEMs use odd separators
        return enabled.contains(context.packageName) &&
            enabled.contains("FrictionAccessibilityService")
    }

    private fun isEnabledViaAccessibilityManager(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val pkg = context.packageName
        return enabled.any { info ->
            val id = info.id.orEmpty()
            val si = info.resolveInfo?.serviceInfo
            val component = if (si != null) "${si.packageName}/${si.name}" else ""
            (id.contains(pkg) && id.contains("FrictionAccessibilityService")) ||
                component.contains("FrictionAccessibilityService") ||
                component.contains(serviceClassName)
        }
    }

    fun isOverlayAllowed(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isBatteryOptimizationExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun allReady(context: Context): Boolean =
        isAccessibilityEnabled(context) &&
            isOverlayAllowed(context) &&
            isBatteryOptimizationExempt(context)

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun openBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }
    }
}
