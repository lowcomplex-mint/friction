package com.friction.app

import android.accessibilityservice.AccessibilityService
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.friction.app.databinding.ActivityGateBinding

/**
 * Full-screen pause. Session lifecycle is owned with [GateSession] so Home →
 * re-open still runs a full beginGate (not a weak kill-only path).
 */
class GateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGateBinding
    private var timer: CountDownTimer? = null
    private var breathAnimator: AnimatorSet? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rekillRunnable: Runnable? = null
    private var leaveCheckRunnable: Runnable? = null

    private lateinit var targetPackage: String
    private lateinit var appLabel: String
    private var finishedCleanly = false
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        targetPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        appLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        if (targetPackage.isBlank()) {
            finish()
            return
        }
        if (appLabel.isBlank()) appLabel = resolveLabel(targetPackage)

        GateSession.begin(targetPackage)
        bindUi()
        // Reuse same layout as overlay via activity_gate (fallback path only)
        startBreathing()
        startCountdown(Prefs.getDelaySeconds(this))
        startRekillLoop()
        Log.i(TAG, "onCreate fallback GateActivity for $appLabel")
    }

    private fun bindUi() {
        val attempts = Prefs.countAttemptsLast24h(this, targetPackage).coerceAtLeast(1)
        val attemptsText = resources.getQuantityString(R.plurals.attempts_last_24h, attempts, attempts)
        binding.countdownText.text = Prefs.getDelaySeconds(this).toString()
        binding.targetAppLabel.text = getString(R.string.pausing_before, appLabel)
        binding.attemptsBreathe.text = attemptsText
        binding.confirmQuestion.text = getString(R.string.overlay_really_want, appLabel)
        binding.attemptsConfirm.text = attemptsText
        binding.phaseBreathe.visibility = View.VISIBLE
        binding.phaseConfirm.visibility = View.GONE
        binding.notNowButton.setOnClickListener { decline() }
        binding.yesButton.setOnClickListener { proceed() }
        binding.noButton.setOnClickListener { decline() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        GateSession.begin(targetPackage)
        cancelLeaveCheck()
        stopped = false
        Log.i(TAG, "onNewIntent — fallback re-fronted")
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        GateSession.begin(targetPackage)
        cancelLeaveCheck()
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        GateSession.begin(targetPackage)
        cancelLeaveCheck()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        if (!finishedCleanly) scheduleLeaveCheck()
    }

    private fun scheduleLeaveCheck() {
        cancelLeaveCheck()
        val r = Runnable {
            if (finishedCleanly || isFinishing || !stopped) return@Runnable
            if (GateSession.isShowing && !stopped) return@Runnable
            Log.w(TAG, "user left fallback gate — clear session")
            abandonGate()
        }
        leaveCheckRunnable = r
        handler.postDelayed(r, 400L)
    }

    private fun cancelLeaveCheck() {
        leaveCheckRunnable?.let { handler.removeCallbacks(it) }
        leaveCheckRunnable = null
    }

    private fun abandonGate() {
        if (finishedCleanly) return
        finishedCleanly = true
        stopRekillLoop()
        timer?.cancel()
        AppTerminator.killBackground(applicationContext, targetPackage)
        if (GateSession.activePackage == targetPackage) {
            GateSession.clear()
        }
        if (!isFinishing) finish()
    }

    private fun startRekillLoop() {
        val r = object : Runnable {
            override fun run() {
                if (isFinishing || finishedCleanly) return
                AppTerminator.killBackground(this@GateActivity, targetPackage)
                handler.postDelayed(this, 1000L)
            }
        }
        rekillRunnable = r
        handler.postDelayed(r, 500L)
    }

    private fun stopRekillLoop() {
        rekillRunnable?.let { handler.removeCallbacks(it) }
        rekillRunnable = null
    }

    private fun startCountdown(seconds: Int) {
        timer?.cancel()
        timer = object : CountDownTimer(seconds * 1000L, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.countdownText.text =
                    ((millisUntilFinished + 999) / 1000).toInt().coerceAtLeast(0).toString()
            }

            override fun onFinish() {
                binding.countdownText.text = "0"
                breathAnimator?.cancel()
                breathAnimator = null
                binding.phaseBreathe.visibility = View.GONE
                binding.phaseConfirm.visibility = View.VISIBLE
            }
        }.start()
    }

    private fun startBreathing() {
        val inner = binding.breathInner
        val outer = binding.breathOuter
        fun pulse(target: View, prop: android.util.Property<View, Float>, from: Float, to: Float) =
            ObjectAnimator.ofFloat(target, prop, from, to).apply {
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

    private fun proceed() {
        finishedCleanly = true
        cancelLeaveCheck()
        stopRekillLoop()
        timer?.cancel()
        GraceTracker.grantProceed(targetPackage)
        GateSession.clear()
        launchTarget()
        finish()
    }

    private fun decline() {
        finishedCleanly = true
        cancelLeaveCheck()
        stopRekillLoop()
        timer?.cancel()
        // Short settle only — long grace made TikTok open free after No
        GraceTracker.grantDecline(targetPackage)
        AppTerminator.killBackground(this, targetPackage)
        FrictionAccessibilityService.instance?.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_HOME
        )
        AppTerminator.goHome(this)
        val appCtx = applicationContext
        val pkg = targetPackage
        handler.postDelayed({ AppTerminator.killBackground(appCtx, pkg) }, 250)
        handler.postDelayed({ AppTerminator.killBackground(appCtx, pkg) }, 700)
        GateSession.clear()
        finish()
    }

    private fun launchTarget() {
        try {
            val launch = packageManager.getLaunchIntentForPackage(targetPackage) ?: return
            launch.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            startActivity(launch)
            Log.i(TAG, "launched $targetPackage after Yes")
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
        }
    }

    private fun resolveLabel(packageName: String): String {
        return try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* block */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        cancelLeaveCheck()
        stopRekillLoop()
        timer?.cancel()
        breathAnimator?.cancel()
        if (!finishedCleanly && GateSession.activePackage == targetPackage) {
            GateSession.clear()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FrictionGate"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
    }
}
