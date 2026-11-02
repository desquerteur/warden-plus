package com.warden.blocker.owner

/** Where a "Remove device owner" request stands. */
sealed interface ExitStatus {
    data object NotRequested : ExitStatus
    /** Requested; removal is possible from [readyAt] on. */
    data class Waiting(val readyAt: Long) : ExitStatus
    /** The wait is over — removal can happen now. */
    data object Ready : ExitStatus
}

/**
 * The mandatory way out of device-owner mode — pure, so the rules are unit-tested. Removing
 * Warden+ as device owner is always possible: it follows the Block protection, but never takes
 * longer than [MAX_WAIT_MS], not even with "Locked until" or a forgotten PIN. A state that only a
 * factory reset could undo must never exist.
 */
object EmergencyExit {

    const val MAX_WAIT_MS = 24L * 60 * 60 * 1000

    /**
     * How long the user has to wait. [looseningAllowed] / [protectionWaitMs] come from the Block
     * protection ([ProtectionHook]); refused or unknown means the full [MAX_WAIT_MS]. Without the
     * PIN ([pinBypassed], the "Forgot your PIN?" path on the locked Settings) it is always the full wait.
     */
    fun waitMillis(looseningAllowed: Boolean, protectionWaitMs: Long, pinBypassed: Boolean): Long = when {
        pinBypassed || !looseningAllowed -> MAX_WAIT_MS
        else -> protectionWaitMs.coerceIn(0L, MAX_WAIT_MS)
    }

    /** When a request made at [requestedAt] becomes ready. */
    fun readyAt(requestedAt: Long, waitMillis: Long): Long =
        requestedAt + waitMillis.coerceIn(0L, MAX_WAIT_MS)

    /**
     * A stored ready time further away than the maximum (the clock was set back) is pulled in to
     * [now] + [MAX_WAIT_MS] — store the result, so the wait never grows past 24 h, and setting the
     * clock back doesn't skip it either.
     */
    fun normalizedReadyAt(readyAt: Long, now: Long): Long =
        if (readyAt > 0L && readyAt - now > MAX_WAIT_MS) now + MAX_WAIT_MS else readyAt

    /** Status of a stored request ([readyAt] 0 = none). */
    fun status(readyAt: Long, now: Long): ExitStatus {
        val at = normalizedReadyAt(readyAt, now)
        return when {
            at <= 0L -> ExitStatus.NotRequested
            at <= now -> ExitStatus.Ready
            else -> ExitStatus.Waiting(at)
        }
    }
}
