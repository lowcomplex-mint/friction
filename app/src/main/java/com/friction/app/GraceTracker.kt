package com.friction.app

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Package-scoped suppress windows so intercept does not loop.
 *
 * - [grantProceed]: after Yes — ignore P until the user leaves P (or safety max).
 * - [grantDecline]: after No / Not now — ignore P for a solid settle window so
 *   dying window-state events / kill bounce do not re-open the gate.
 */
object GraceTracker {
    private const val TAG = "FrictionGrace"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var packageName: String? = null

    /** If true, grace ends when user leaves the package; if false, only by timer. */
    @Volatile
    private var clearOnLeave: Boolean = false

    private var safetyRunnable: Runnable? = null

    private const val PROCEED_SAFETY_MS = 60_000L
    /** Short: only absorb kill bounce after No — long values blocked re-gate (TikTok free). */
    private const val DECLINE_SETTLE_MS = 2_000L

    fun isInGrace(pkg: String): Boolean = packageName == pkg

    fun grantProceed(pkg: String) {
        set(pkg, clearOnLeave = true, maxMs = PROCEED_SAFETY_MS)
        Log.i(TAG, "proceed grace for $pkg")
    }

    fun grantDecline(pkg: String) {
        // Fixed settle window — do NOT clear on leave immediately (leave is intentional on No)
        set(pkg, clearOnLeave = false, maxMs = DECLINE_SETTLE_MS)
        Log.i(TAG, "decline grace for $pkg (${DECLINE_SETTLE_MS}ms)")
    }

    fun onForegroundPackage(pkg: String, ignored: Boolean) {
        val g = packageName ?: return
        if (!clearOnLeave) return
        if (ignored) return
        if (pkg == g) return
        Log.i(TAG, "left $g → foreground $pkg — clearing proceed grace")
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
            Log.i(TAG, "grace expired for $pkg")
            if (packageName == pkg) clear()
        }
        safetyRunnable = r
        mainHandler.postDelayed(r, maxMs)
    }
}
