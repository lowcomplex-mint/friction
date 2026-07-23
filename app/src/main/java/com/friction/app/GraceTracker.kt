package com.friction.app

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Suppress re-intercept after Yes / No.
 *
 * - **Proceed (Yes):** until user leaves the app (launcher or different app).
 *   System UI noise does not end proceed grace.
 * - **Decline (No):** short timer only.
 */
object GraceTracker {
    private const val TAG = "FrictionGrace"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var packageName: String? = null

    @Volatile
    private var clearOnLeave: Boolean = false

    private var safetyRunnable: Runnable? = null

    private const val PROCEED_SAFETY_MS = 12L * 60L * 60L * 1000L
    private const val DECLINE_SETTLE_MS = 3_000L

    fun isInGrace(pkg: String): Boolean = packageName == pkg

    fun grantProceed(pkg: String) {
        set(pkg, clearOnLeave = true, maxMs = PROCEED_SAFETY_MS)
        Log.i(TAG, "proceed grace for $pkg (until leave)")
    }

    fun grantDecline(pkg: String) {
        set(pkg, clearOnLeave = false, maxMs = DECLINE_SETTLE_MS)
        Log.i(TAG, "decline grace for $pkg (${DECLINE_SETTLE_MS}ms)")
    }

    /**
     * @param isLauncher true when [pkg] is the home launcher (user left all apps)
     * @param systemNoise true for systemui/settings/etc. — does not end proceed grace
     */
    fun onForegroundPackage(pkg: String, isLauncher: Boolean, systemNoise: Boolean) {
        val g = packageName ?: return
        if (!clearOnLeave) return
        if (isLauncher) {
            Log.i(TAG, "left $g via launcher — clearing proceed grace")
            clear()
            return
        }
        if (systemNoise) return
        if (pkg == g) return
        Log.i(TAG, "left $g → $pkg — clearing proceed grace")
        clear()
    }

    fun clear() {
        packageName = null
        clearOnLeave = false
        safetyRunnable?.let { mainHandler.removeCallbacks(it) }
        safetyRunnable = null
    }

    private fun set(pkg: String, clearOnLeave: Boolean, maxMs: Long) {
        safetyRunnable?.let { mainHandler.removeCallbacks(it) }
        packageName = pkg
        this.clearOnLeave = clearOnLeave
        val r = Runnable {
            Log.i(TAG, "grace safety expired for $pkg")
            if (packageName == pkg) clear()
        }
        safetyRunnable = r
        mainHandler.postDelayed(r, maxMs)
    }
}
