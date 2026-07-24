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
 * Order: show curtain first, then dispose target under it.
 * On Yes: cancel all pending kills so we never kill the app we just allowed
 * (that caused a re-gate loop, especially on X).
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
    private var disposeRunnables: MutableList<Runnable> = mutableListOf()
    private var targetPackage: String? = null

    /** After Yes, ignore kill/home stomps for this package briefly. */
    @Volatile
    private var allowPackage: String? = null

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

        // New gate cancels any leftover dispose kills from a previous session
        cancelDisposeKills()
        allowPackage = null

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
        if (!isShowing || targetPackage != packageName) return
        if (allowPackage == packageName) return
        accessibility?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        safeKill(packageName)
        scheduleDisposeKill(packageName, 120L)
        scheduleDisposeKill(packageName, 400L)
    }

    private fun scheduleDisposeKill(packageName: String, delayMs: Long) {
        val r = Runnable {
            if (allowPackage == packageName) return@Runnable
            if (!isShowing && allowPackage != null) return@Runnable
            // Only kill while curtain still up
            if (isShowing && targetPackage == packageName) {
                safeKill(packageName)
            }
        }
        disposeRunnables.add(r)
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelDisposeKills() {
        disposeRunnables.forEach { mainHandler.removeCallbacks(it) }
        disposeRunnables.clear()
    }

    private fun safeKill(packageName: String) {
        if (allowPackage == packageName) {
            Log.d(TAG, "skip kill — package just allowed: $packageName")
            return
        }
        AppTerminator.killBackground(context, packageName)
    }

    private fun startRekill(packageName: String) {
        stopRekill()
        val r = object : Runnable {
            override fun run() {
                if (!isShowing) return
                if (allowPackage == packageName) return
                safeKill(packageName)
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
        // Critical: stop killing the app we're about to open
        allowPackage = packageName
        stopRekill()
        cancelDisposeKills()
        timer?.cancel()

        ForegroundTracker.onUserApp(packageName)
        GraceTracker.grantProceed(packageName)
        removeOverlay(keepAllow = true)

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

        // Keep allowPackage for a few seconds so late dispose/rekill callbacks no-op
        mainHandler.postDelayed({
            if (allowPackage == packageName) allowPackage = null
        }, 5_000L)
    }

    private fun decline(packageName: String) {
        allowPackage = null
        stopRekill()
        cancelDisposeKills()
        timer?.cancel()
        ForegroundTracker.onLeftApps()
        GraceTracker.grantDecline(packageName)
        removeOverlay(keepAllow = false)
        accessibility?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        AppTerminator.goHome(context)
        AppTerminator.killBackground(context, packageName)
        mainHandler.postDelayed({ AppTerminator.killBackground(context, packageName) }, 200)
        mainHandler.postDelayed({ AppTerminator.killBackground(context, packageName) }, 600)
        Log.i(TAG, "No/Not now — declined $packageName")
    }

    fun removeOverlay(keepAllow: Boolean = false) {
        stopRekill()
        cancelDisposeKills()
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
        if (!keepAllow) allowPackage = null
    }

    fun onTargetResurfaced(packageName: String) {
        if (!isShowing) return
        if (targetPackage != packageName) return
        if (allowPackage == packageName) return
        safeKill(packageName)
        accessibility?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    companion object {
        private const val TAG = "FrictionOverlay"
    }
}
