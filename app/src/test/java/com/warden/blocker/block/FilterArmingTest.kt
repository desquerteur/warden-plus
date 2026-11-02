package com.warden.blocker.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** H1/M1: the website filter must keep running between windows, and be re-armed at a window's start. */
class FilterArmingTest {

    private val now = 1_700_000_000_000L

    @Test fun runsWheneverTheMainSwitchIsOn() {
        // Outside any schedule (blocking not enforced right now) it still runs, letting everything through.
        assertTrue(BlockStateClock.filterShouldRun(masterEnabled = true, focusEndsAt = 0L, nowMillis = now))
    }

    @Test fun focusKeepsItRunningEvenWithTheSwitchOff() {
        assertTrue(BlockStateClock.filterShouldRun(masterEnabled = false, focusEndsAt = now + 60_000L, nowMillis = now))
    }

    @Test fun stopsOnlyWhenNothingNeedsIt() {
        // A focus session that just ended with the main switch on must not stop the filter (H1).
        assertTrue(BlockStateClock.filterShouldRun(masterEnabled = true, focusEndsAt = now - 1, nowMillis = now))
        assertFalse(BlockStateClock.filterShouldRun(masterEnabled = false, focusEndsAt = now - 1, nowMillis = now))
    }

    @Test fun nextCheckIsTheNextScheduleStart() {
        assertEquals(now + 3_600_000L, BlockStateClock.nextFilterCheck(true, alwaysOn = false, nextScheduleStart = now + 3_600_000L, nowMillis = now))
    }

    @Test fun noCheckWhenNotNeeded() {
        assertNull(BlockStateClock.nextFilterCheck(false, alwaysOn = false, nextScheduleStart = now + 1, nowMillis = now)) // switch off
        assertNull(BlockStateClock.nextFilterCheck(true, alwaysOn = true, nextScheduleStart = now + 1, nowMillis = now)) // runs anyway
        assertNull(BlockStateClock.nextFilterCheck(true, alwaysOn = false, nextScheduleStart = null, nowMillis = now)) // no schedule
        assertNull(BlockStateClock.nextFilterCheck(true, alwaysOn = false, nextScheduleStart = now, nowMillis = now)) // not in the future
    }
}
