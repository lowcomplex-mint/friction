package com.friction.app

import android.accessibilityservice.AccessibilityService
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.friction.app.databinding.OverlayFrictionBinding

/**
 * Instant full-screen curtain ([TYPE_APPLICATION_OVERLAY]).
 *
 * **Order (OneSec-style):**
 * 1. addView black Friction UI on the same a11y callback (no delay)
 * 2. Then Home + kill the target *under* the curtain
 *
 * Never call Home before the overlay is up — that caused the visible exit jank.
 */
class OverlayController(
    private val context: Context,
    private val accessibility: AccessibilityService? = null,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var binding: OverlayFrictionBinding? = null
    private var timer: CountDownTimer? = null
    private var breathAnimator: AnimatorSet? = null
    private var rekillRunnable: Runnable? = null
    private var targetPackage: String? = null

    @Volatile
    var isShowing: Boolean = false
        private set

    @SuppressLint("InflateParams")
    fun show(packageName: String, appLabel: String): Boolean {
        if (isShowing) return true
        if (!PermissionHelper.isOverlayAllowed(context)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot show curtain")
            return false
        }

        val themed = ContextThemeWrapper(context, R.style.Theme_Friction)
        val b = OverlayFrictionBinding.inflate(LayoutInflater.from(themed))
        binding = b
        targetPackage = packageName

        val delay = Prefs.getDelaySeconds(context)
        val attempts = Prefs.countAttemptsLast24h(context, packageName).coerceAtLeast(1)
        val attemptsText = context.resources.getQuantityString(
            R.plurals.attempts_last_24h, attempts, attempts
        )

        b.countdownText.text = delay.toString()
        b.targetAppLabel.text = context.getString(R.string.pausing_before, appLabel)
        b.attemptsBreathe.text = attemptsText
        b.confirmQuestion.text = context.getString(R.string.overlay_really_want, appLabel)
        b.attemptsConfirm.text = attemptsText
        b.phaseBreathe.visibility = View.VISIBLE
        b.phaseConfirm.visibility = View.GONE

        b.notNowButton.setOnClickListener { decline(packageName) }
        b.noButton.setOnClickListener { decline(packageName) }
        b.yesButton.setOnClickListener { proceed(packageName) }

        b.overlayRoot.isFocusableInTouchMode = true
        b.overlayRoot.requestFocus()
        b.overlayRoot.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Draw as high as a normal app overlay can
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        return try {
            windowManager.addView(b.root, params)
            isShowing = true
            GateSession.begin(packageName)
            startBreathing(b.breathInner, b.breathOuter)
            startCountdown(delay, appLabel)
            startRekill(packageName)
            Log.i(TAG, "curtain up for $appLabel ($packageName)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "addView failed", e)
            isShowing = false
            binding = null
            targetPackage = null
            GateSession.clear()
            false
        }
    }

    /** Call after curtain is up — dispose target under the black screen. */
    fun disposeTargetUnderCurtain(packageName: String) {
        accessibility?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        AppTerminator.killBackground(context, packageName)
        mainHandler.postDelayed({ AppTerminator.killBackground(context, packageName) }, 120)
        mainHandler.postDelayed({ AppTerminator.killBackground(context, packageName) }, 400)
    }

    private fun startRekill(packageName: String) {
        stopRekill()
        val r = object : Runnable {
            override fun run() {
                if (!isShowing) return
                AppTerminator.killBackground(context, packageName)
                mainHandler.postDelayed(this, 900L)
            }
        }
        rekillRunnable = r
        mainHandler.postDelayed(r, 300L)
    }

    private fun stopRekill() {
        rekillRunnable?.let { mainHandler.removeCallbacks(it) }
        rekillRunnable = null
    }

    private fun startCountdown(seconds: Int, appLabel: String) {
        timer?.cancel()
        timer = object : CountDownTimer(seconds * 1000L, 200L) {
            override fun onTick(millisUntilFinished: Long) {
                binding?.countdownText?.text =
                    ((millisUntilFinished + 999) / 1000).toInt().coerceAtLeast(0).toString()
            }

            override fun onFinish() {
                binding?.countdownText?.text = "0"
                showConfirm(appLabel)
            }
        }.start()
    }

    private fun showConfirm(appLabel: String) {
        val b = binding ?: return
        breathAnimator?.cancel()
        breathAnimator = null
        b.confirmQuestion.text = context.getString(R.string.overlay_really_want, appLabel)
        b.phaseBreathe.visibility = View.GONE
        b.phaseConfirm.visibility = View.VISIBLE
    }

    private fun startBreathing(inner: View, outer: View) {
        fun pulse(v: View, p: android.util.Property<View, Float>, a: Float, b: Float) =
            ObjectAnimator.ofFloat(v, p, a, b).apply {
                duration = 4000
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        breathAnimator = AnimatorSet().apply {
            playTogether(
                pulse(inner, View.SCALE_X, 0.85f, 1.15f),
                pulse(inner, View.SCALE_Y, 0.85f, 1.15f),
                pulse(outer, View.SCALE_X, 0.92f, 1.08f),
                pulse(outer, View.SCALE_Y, 0.92f, 1.08f),
                pulse(inner, View.ALPHA, 0.55f, 1f),
            )
            start()
        }
    }

    private fun proceed(packageName: String) {
        stopRekill()
        timer?.cancel()
        // Stay in this app without re-gating until user leaves (DMs, etc.)
        ForegroundTracker.onUserApp(packageName)
        GraceTracker.grantProceed(packageName)
        removeOverlay()
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launch != null) {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                context.startActivity(launch)
                Log.i(TAG, "Yes — launched $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
        }
    }

    private fun decline(packageName: String) {
        stopRekill()
        timer?.cancel()
        // Left the app intentionally — next open should gate again after settle
        ForegroundTracker.onLeftApps()
        GraceTracker.grantDecline(packageName)
        removeOverlay()
        accessibility?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        AppTerminator.goHome(context)
        AppTerminator.killBackground(context, packageName)
        mainHandler.postDelayed({ AppTerminator.killBackground(context, packageName) }, 200)
        mainHandler.postDelayed({ AppTerminator.killBackground(context, packageName) }, 600)
        Log.i(TAG, "No/Not now — declined $packageName")
    }

    fun removeOverlay() {
        stopRekill()
        timer?.cancel()
        timer = null
        breathAnimator?.cancel()
        breathAnimator = null
        binding?.let { b ->
            try {
                windowManager.removeView(b.root)
            } catch (_: Exception) { /* gone */ }
        }
        binding = null
        isShowing = false
        targetPackage = null
        GateSession.clear()
    }

    /** Target clawed on top while curtain should be up — re-stomp only. */
    fun onTargetResurfaced(packageName: String) {
        if (!isShowing) return
        if (targetPackage != packageName) return
        AppTerminator.killBackground(context, packageName)
        accessibility?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    companion object {
        private const val TAG = "FrictionOverlay"
    }
}
