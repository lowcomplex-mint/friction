package com.friction.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Soft-terminate a guarded app so it cannot keep playing video/audio or sit in PiP.
 *
 * We cannot [ActivityManager.forceStopPackage] without a privileged permission, so we:
 * 1. Send the user Home (caller)
 * 2. [ActivityManager.killBackgroundProcesses] once the app is no longer foreground
 * 3. Optionally re-assert Home
 *
 * Call repeatedly while the gate is open — YouTube/TikTok often respawn a moment after Home.
 */
object AppTerminator {
    private const val TAG = "FrictionTerminator"

    fun goHome(context: Context) {
        try {
            val home = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(home)
        } catch (e: Exception) {
            Log.w(TAG, "goHome failed", e)
        }
    }

    fun killBackground(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == context.packageName) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            Log.i(TAG, "killBackgroundProcesses($packageName)")
        } catch (e: Exception) {
            Log.w(TAG, "killBackground failed for $packageName", e)
        }
    }

    /** Home + kill — primary exit path for No / Not now / pre-gate. */
    fun bounceOut(context: Context, packageName: String) {
        goHome(context)
        killBackground(context, packageName)
    }
}
