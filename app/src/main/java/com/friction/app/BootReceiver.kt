package com.friction.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Warms the app process after boot so OEMs (esp. Xiaomi) are more likely to
 * keep the accessibility service bound without the user opening Friction first.
 *
 * We cannot enable Accessibility ourselves — that stays a one-time Settings toggle.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        Log.i(TAG, "boot warm-up: $action")
        // Touch prefs / permission checks to pull the process up
        try {
            Prefs.getGuardedPackages(context)
            val a11y = PermissionHelper.isAccessibilityEnabled(context)
            val overlay = PermissionHelper.isOverlayAllowed(context)
            Log.i(TAG, "post-boot a11yListed=$a11y overlay=$overlay")
        } catch (t: Throwable) {
            Log.e(TAG, "boot warm-up failed", t)
        }
    }

    companion object {
        private const val TAG = "FrictionBoot"
    }
}
