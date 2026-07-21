package com.friction.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Local-only preferences: guarded packages, delay, 24h attempt timestamps.
 */
object Prefs {
    private const val NAME = "friction_prefs"
    private const val KEY_GUARDED = "guarded_packages"
    private const val KEY_DELAY_SECONDS = "delay_seconds"
    private const val KEY_ATTEMPTS_PREFIX = "attempts_"

    const val DEFAULT_DELAY_SECONDS = 10
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getGuardedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_GUARDED, emptySet())?.toSet() ?: emptySet()

    fun isGuarded(context: Context, packageName: String): Boolean =
        getGuardedPackages(context).contains(packageName)

    fun setGuarded(context: Context, packageName: String, guarded: Boolean) {
        val current = getGuardedPackages(context).toMutableSet()
        if (guarded) current.add(packageName) else current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_GUARDED, current).apply()
        if (!guarded) {
            prefs(context).edit().remove(KEY_ATTEMPTS_PREFIX + packageName).apply()
        }
    }

    fun getDelaySeconds(context: Context): Int =
        prefs(context).getInt(KEY_DELAY_SECONDS, DEFAULT_DELAY_SECONDS)

    fun setDelaySeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_DELAY_SECONDS, seconds.coerceIn(3, 60)).apply()
    }

    // ── 24h attempt stats ──────────────────────────────────────────────

    /** Record one open-attempt now; prunes entries older than 24h. */
    fun recordAttempt(context: Context, packageName: String) {
        val now = System.currentTimeMillis()
        val pruned = timestamps(context, packageName).filter { now - it <= DAY_MS }.toMutableList()
        pruned.add(now)
        prefs(context).edit()
            .putString(KEY_ATTEMPTS_PREFIX + packageName, pruned.joinToString(","))
            .apply()
    }

    fun countAttemptsLast24h(context: Context, packageName: String): Int {
        val now = System.currentTimeMillis()
        return timestamps(context, packageName).count { now - it <= DAY_MS }
    }

    private fun timestamps(context: Context, packageName: String): List<Long> {
        val raw = prefs(context).getString(KEY_ATTEMPTS_PREFIX + packageName, null) ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { it.toLongOrNull() }
    }
}
