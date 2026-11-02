package com.warden.blocker.block

/**
 * Timing for the VPN's schedule watcher. Schedules and focus sessions start and end on their
 * own, without any setting changing, so the VPN re-checks [BlockEngine.isBlockingActiveNow]
 * on a clock and rebuilds its blocklist only when enforcement actually flipped.
 */
object BlockStateClock {

    private const val MINUTE_MS = 60_000L

    /** Wake just after the boundary so the minute has surely ticked over. */
    private const val MARGIN_MS = 50L

    /**
     * Millis until the next check: the next minute boundary (schedules are minute-precise;
     * all current time zones are whole-minute offsets from UTC), or a running focus
     * session's end if that comes sooner.
     */
    fun delayUntilNextCheck(nowMillis: Long, focusEndsAt: Long): Long {
        val toMinute = MINUTE_MS - Math.floorMod(nowMillis, MINUTE_MS) + MARGIN_MS
        if (focusEndsAt <= nowMillis) return toMinute
        return minOf(toMinute, focusEndsAt - nowMillis + MARGIN_MS)
    }

    /**
     * Whether the filter (VPN) should be running: whenever the main switch is on or a focus
     * session / "Block until" runs — also outside a schedule's window, where it lets everything
     * through. Then a window that starts later is enforced at once by the VPN's own clock, with
     * nothing having to start it in the background. (Same rule as Home's "Protection paused".)
     */
    fun filterShouldRun(masterEnabled: Boolean, focusEndsAt: Long, nowMillis: Long): Boolean =
        masterEnabled || focusEndsAt > nowMillis

    /**
     * When to check next that the filter runs: the next schedule start while the main switch is
     * on and blocking isn't "Always on" (then the filter runs anyway). Null = no check needed.
     */
    fun nextFilterCheck(masterEnabled: Boolean, alwaysOn: Boolean, nextScheduleStart: Long?, nowMillis: Long): Long? {
        if (!masterEnabled || alwaysOn) return null
        return nextScheduleStart?.takeIf { it > nowMillis }
    }

    /** True when the blocklist was built for a different state than [activeNow] (or never). */
    fun needsRebuild(builtFor: Boolean?, activeNow: Boolean): Boolean = builtFor != activeNow
}
