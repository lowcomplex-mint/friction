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
 * Instant curtain architecture:
 *
 * 1. On guarded package window event → **show overlay immediately** (same callback)
 * 2. Then Home + kill **under** the black screen
 * 3. No 200ms delay, no GateActivity cold-start for the first frame
 */
class FrictionAccessibilityService : AccessibilityService() {

    private lateinit var overlay: OverlayController
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastBeginElapsed: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = OverlayController(this, accessibility = this)
        GateSession.clear()
        GraceTracker.clear()

        serviceInfo = serviceInfo?.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.i(TAG, "onServiceConnected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isBlank()) return

        ensureOverlay()

        val ignored =
            packageName == this.packageName ||
                packageName in IGNORED_PACKAGES ||
                isLauncher(packageName)

        GraceTracker.onForegroundPackage(packageName, ignored = ignored)

        // Curtain already up
        if (overlay.isShowing || GateSession.isShowing) {
            val gated = GateSession.activePackage
            if (gated != null && packageName == gated) {
                overlay.onTargetResurfaced(packageName)
            }
            return
        }

        if (ignored) return
        if (GraceTracker.isInGrace(packageName)) {
            Log.d(TAG, "skip $packageName — grace")
            return
        }
        if (!Prefs.isGuarded(this, packageName)) return

        // Debounce only true double-fires (same package within 400ms)
        val now = SystemClock.elapsedRealtime()
        if (now - lastBeginElapsed < 400L) {
            Log.d(TAG, "debounce $packageName")
            return
        }

        Log.i(TAG, "GUARDED: $packageName — curtain first")
        beginGate(packageName)
    }

    private fun beginGate(packageName: String) {
        lastBeginElapsed = SystemClock.elapsedRealtime()
        Prefs.recordAttempt(this, packageName)
        val label = resolveLabel(packageName)

        // 1) Curtain NOW — same call stack as the a11y event
        val shown = overlay.show(packageName, label)
        if (!shown) {
            Log.e(TAG, "overlay failed — falling back to activity path")
            fallbackActivityGate(packageName, label)
            return
        }

        // 2) Dispose target under the curtain (next frame — UI already up)
        mainHandler.post {
            overlay.disposeTargetUnderCurtain(packageName)
        }
    }

    private fun fallbackActivityGate(packageName: String, label: String) {
        // Last resort if overlay permission missing
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
        if (::overlay.isInitialized) overlay.removeOverlay()
        instance = null
        GateSession.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (::overlay.isInitialized) overlay.removeOverlay()
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
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolve?.activityInfo?.packageName == packageName
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
        )
    }
}
