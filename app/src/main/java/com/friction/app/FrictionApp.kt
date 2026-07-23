package com.friction.app

import android.app.Application
import android.util.Log

/**
 * Application entry — keeps process init predictable for boot + a11y binding.
 */
class FrictionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "FrictionApp onCreate")
    }

    companion object {
        private const val TAG = "FrictionApp"
    }
}
