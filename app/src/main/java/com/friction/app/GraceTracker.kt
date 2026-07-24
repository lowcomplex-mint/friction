package com.friction.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Suppress re-intercept after Yes / No.
 *
 * **Yes (proceed):** stays active until the user really leaves to the **launcher**.
 * Brief launcher flashes right after Yes (while the app is cold-starting) must
 * NOT clear grace — that caused a gate loop on X/Twitter.
 *
 * **No (decline):** short settle timer only.
 */
object GraceTracker {
    private const val TAG = "FrictionGrace"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var packageName: String? = null

    @Volatile
    private var clearOnLeave: Boolean = false

    /** Ignore launcher events that clear proceed grace until this elapsed time. */
    @Volatile
    private var proceedSettleUntilElapsed: Long = 0L

    private var safetyRunnable: Runnable? = null

    private const val PROCEED_SAFETY_MS = 12L * 60L * 60L * 1000L
    private const val PROCEED_SETTLE_MS = 4_000L
    private const val DECLINE_SETTLE_MS = 3_000L

    fun isInGrace(pkg: String): Boolean = packageName == pkg

    fun grantProceed(pkg: String) {
        proceedSettleUntilElapsed = SystemClock.elapsedRealtime() + PROCEED_SETTLE_MS
        set(pkg, clearOnLeave = true, maxMs = PROCEED_SAFETY_MS)
        Log.i(TAG, "proceed grace for $pkg (until leave, settle ${PROCEED_SETTLE_MS}ms)")
    }

    fun grantDecline(pkg: String) {
        proceedSettleUntilElapsed = 0L
        set(pkg, clearOnLeave = false, maxMs = DECLINE_SETTLE_MS)
        Log.i(TAG, "decline grace for $pkg (${DECLINE_SETTLE_MS}ms)")
    }

    /**
     * @param isLauncher true when [pkg] is the home launcher
     * @param systemNoise true for systemui/settings/etc. — never ends proceed grace
     */
    fun onForegroundPackage(pkg: String, isLauncher: Boolean, systemNoise: Boolean) {
        val g = packageName ?: return
        if (!clearOnLeave) return
        if (systemNoise && !isLauncher) return
        if (isLauncher) {
            val now = SystemClock.elapsedRealtime()
            if (now < proceedSettleUntilElapsed) {
                Log.d(TAG, "launcher during proceed settle — keep grace for $g")
                return
            }
            Log.i(TAG, "left $g via launcher — clearing proceed grace")
            clear()
            return
        }
        if (pkg == g) return
        // Another real app: do not clear proceed for g — grace is package-scoped.
        // (Instagram can still gate; X's grace only protects X.)
    }

    fun clear() {
        packageName = null
        clearOnLeave = false
        proceedSettleUntilElapsed = 0L
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
