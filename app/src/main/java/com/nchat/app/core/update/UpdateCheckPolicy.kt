package com.nchat.app.core.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** How long a "no, nothing new" (or "yes, this one") answer is trusted while the app stays open. */
internal const val UPDATE_RECHECK_MS = 6L * 60 * 60 * 1000

/** A look that failed (no network, a server hiccup) is repeated sooner, but never in a loop. */
internal const val UPDATE_RETRY_MS = 60L * 1000

/**
 * Whether to read the release manifest again now.
 *
 * NChat keeps its process alive to deliver messages, so "on start-up" can be days apart: a phone
 * that never restarts the app would never hear about a release. Looking again when the app comes
 * back to the foreground closes that gap, and this is the rule that keeps it from asking on every
 * single return. Times are `SystemClock.elapsedRealtime()` values, in milliseconds.
 */
internal fun updateCheckDue(nowMs: Long, lastCheckMs: Long?, lastCheckSucceeded: Boolean): Boolean {
    if (lastCheckMs == null) return true
    val wait = if (lastCheckSucceeded) UPDATE_RECHECK_MS else UPDATE_RETRY_MS
    return nowMs - lastCheckMs >= wait
}

/**
 * "NChat is in front of the user again."
 *
 * `MainActivity.onResume` bumps the counter and the update page listens to it. It is a counter
 * rather than a flag so that two returns are always two different values.
 */
object UpdateForeground {
    private val _resumes = MutableStateFlow(0)
    val resumes: StateFlow<Int> = _resumes.asStateFlow()

    fun onResume() {
        _resumes.update { it + 1 }
    }
}
