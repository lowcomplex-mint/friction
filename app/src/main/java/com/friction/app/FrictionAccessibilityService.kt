package com.friction.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Instant curtain: show overlay first, then dispose target under it.
 *
 * Only gates when the user **enters** a guarded app from outside (launcher /
 * another app). Same-package activity changes (e.g. Instagram → DMs) are ignored.
 */
class FrictionAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastBeginElapsed: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            overlay = OverlayController(this, accessibility = this)
            // Do not clear ForegroundTracker / GraceTracker on reconnect mid-session —
            // only clear gate UI state.
            if (overlay.isShowing) {
                overlay.removeOverlay()
            }
            GateSession.clear()

            serviceInfo = serviceInfo?.apply {
                // WINDOW_STATE_CHANGED is enough for activity switches; WINDOWS_CHANGED
                // is very noisy on modern Android and caused extra work / OEM kill risk.
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                notificationTimeout = 100
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            Log.i(TAG, "onServiceConnected")
        } catch (t: Throwable) {
            Log.e(TAG, "onServiceConnected failed", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Never throw out of here — uncaught exceptions put the service in Crashed state
        // and force the user to re-toggle Accessibility (especially on HyperOS).
        try {
            handleEvent(event)
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent error (swallowed)", t)
        }
    }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isBlank()) return

        ensureOverlay()

        val isOwn = packageName == this.packageName
        val isIgnored = packageName in IGNORED_PACKAGES
        val launcher = isLauncher(packageName)
        val systemNoise = isOwn || isIgnored || launcher

        if (systemNoise) {
            if (launcher) {
                ForegroundTracker.onLeftApps()
            }
            GraceTracker.onForegroundPackage(
                packageName,
                isLauncher = launcher,
                systemNoise = !launcher,
            )
            return
        }

        GraceTracker.onForegroundPackage(
            packageName,
            isLauncher = false,
            systemNoise = false,
        )

        // Curtain already up
        if (overlay.isShowing || GateSession.isShowing) {
            val gated = GateSession.activePackage
            if (gated != null && packageName == gated) {
                overlay.onTargetResurfaced(packageName)
            }
            // Still "in" this app for session tracking after Yes
            ForegroundTracker.onUserApp(packageName)
            return
        }

        // In-app navigation (Instagram feed → messages): same package, no new gate
        val isNewAppEntry = ForegroundTracker.onUserApp(packageName)
        if (!isNewAppEntry) {
            return
        }

        if (GraceTracker.isInGrace(packageName)) {
            Log.d(TAG, "skip $packageName — grace")
            return
        }
        if (!Prefs.isGuarded(this, packageName)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastBeginElapsed < 500L) {
            Log.d(TAG, "debounce $packageName")
            return
        }

        Log.i(TAG, "GUARDED entry: $packageName — curtain first")
        beginGate(packageName)
    }

    private fun beginGate(packageName: String) {
        lastBeginElapsed = SystemClock.elapsedRealtime()
        Prefs.recordAttempt(this, packageName)
        val label = resolveLabel(packageName)

        val shown = overlay.show(packageName, label)
        if (!shown) {
            Log.e(TAG, "overlay failed — falling back to activity path")
            fallbackActivityGate(packageName, label)
            return
        }

        mainHandler.post {
            try {
                overlay.disposeTargetUnderCurtain(packageName)
            } catch (t: Throwable) {
                Log.e(TAG, "dispose under curtain failed", t)
            }
        }
    }

    private fun fallbackActivityGate(packageName: String, label: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        AppTerminator.killBackground(this, packageName)
        GateSession.begin(packageName)
        val gate = Intent(this, GateActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(GateActivity.EXTRA_PACKAGE, packageName)
            putExtra(GateActivity.EXTRA_LABEL, label)
        }
        try {
            startActivity(gate)
        } catch (t: Throwable) {
            Log.e(TAG, "fallback GateActivity failed", t)
            GateSession.clear()
        }
    }

    private fun ensureOverlay() {
        if (!::overlay.isInitialized) {
            overlay = OverlayController(this, accessibility = this)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        try {
            if (::overlay.isInitialized) overlay.removeOverlay()
        } catch (_: Throwable) { }
        instance = null
        GateSession.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try {
            if (::overlay.isInitialized) overlay.removeOverlay()
        } catch (_: Throwable) { }
        instance = null
        GateSession.clear()
        super.onDestroy()
    }

    private fun resolveLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun isLauncher(packageName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolve = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolve?.activityInfo?.packageName == packageName
        } catch (_: Throwable) {
            packageName == "com.miui.home" || packageName.contains("launcher")
        }
    }

    companion object {
        private const val TAG = "FrictionA11y"

        @Volatile
        var instance: FrictionAccessibilityService? = null
            private set

        val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.gms",
            "com.android.phone",
            "com.android.systemui.notetask",
            "com.miui.home",
            "com.mi.android.globallauncher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.miui.securitycenter",
            "com.miui.powerkeeper",
        )
    }
}
