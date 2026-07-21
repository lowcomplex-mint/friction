package com.friction.app

/**
 * Minimal session: either the curtain is up for a package, or it isn't.
 * No "opening" half-state — the overlay is shown synchronously before flags flip.
 */
object GateSession {
    @Volatile
    var activePackage: String? = null
        private set

    @Volatile
    var isShowing: Boolean = false
        private set

    fun begin(packageName: String) {
        activePackage = packageName
        isShowing = true
    }

    fun clear() {
        activePackage = null
        isShowing = false
    }

    fun isShowingFor(packageName: String): Boolean =
        isShowing && activePackage == packageName
}
