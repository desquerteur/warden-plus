package com.warden.blocker.guard

/**
 * Keeps the guard's warning from repeating: a page sends many content events while it is open,
 * but the user gets one hint per visit. The same page counts as a new visit once it has been
 * out of sight for [quietMs], or at the latest after [repeatMs] of staying on it.
 *
 * Pure logic with an injected clock (`now`), unit-tested without Android.
 */
class GuardThrottle(
    private val quietMs: Long = QUIET_MS,
    private val repeatMs: Long = REPEAT_MS,
) {
    private val lastSeen = HashMap<GuardTarget, Long>()
    private val lastWarned = HashMap<GuardTarget, Long>()

    /** [target] is on screen now. True if the user should be told (and it is logged). */
    @Synchronized
    fun shouldWarn(target: GuardTarget, now: Long): Boolean {
        val seen = lastSeen[target]
        lastSeen[target] = now
        val warned = lastWarned[target]
        val newVisit = seen == null || now - seen >= quietMs
        val longStay = warned == null || now - warned >= repeatMs
        if (!newVisit && !longStay) return false
        lastWarned[target] = now
        return true
    }

    companion object {
        const val QUIET_MS = 5_000L
        const val REPEAT_MS = 60_000L
    }
}
