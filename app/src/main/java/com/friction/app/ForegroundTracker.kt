package com.friction.app

import android.util.Log

/**
 * Tracks the last non-ignored app package so we only gate **entries** into an app,
 * not in-app navigation (e.g. Instagram feed → Messages).
 */
object ForegroundTracker {
    private const val TAG = "FrictionFg"

    /** Last user-facing app package (not launcher/systemui). */
    @Volatile
    var currentApp: String? = null
        private set

    /**
     * @return true if [packageName] is a **new** foreground app (should consider gating).
     *         false if this is still the same app (in-app activity change).
     */
    fun onUserApp(packageName: String): Boolean {
        if (currentApp == packageName) {
            Log.d(TAG, "same app nav: $packageName")
            return false
        }
        val prev = currentApp
        currentApp = packageName
        Log.d(TAG, "app switch: $prev → $packageName")
        return true
    }

    /** User went home / left all apps. Next open of a guarded app should gate again. */
    fun onLeftApps() {
        if (currentApp != null) {
            Log.d(TAG, "left apps (was $currentApp)")
        }
        currentApp = null
    }

    fun clear() {
        currentApp = null
    }
}
