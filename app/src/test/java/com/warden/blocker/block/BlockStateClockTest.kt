package com.warden.blocker.block

import com.warden.blocker.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BlockStateClockTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun millisAt(hour: Int, minute: Int, second: Int = 0): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 4) // a Wednesday
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
        }.timeInMillis

    private val weekdays9to5 = Schedule(
        id = 1, name = "work", enabled = true,
        daysMask = 0b0011111, // Mon..Fri
        startMinute = 9 * 60, endMinute = 17 * 60,
    )

    @Test fun wakesJustAfterTheNextMinuteBoundary() {
        val now = millisAt(8, 59, 30)
        val wake = now + BlockStateClock.delayUntilNextCheck(now, focusEndsAt = 0L)
        assertTrue(wake > millisAt(9, 0))
        assertTrue(wake < millisAt(9, 0, 1))
    }

    @Test fun exactlyOnABoundaryWaitsForTheNextOne() {
        val now = millisAt(9, 0)
        val delay = BlockStateClock.delayUntilNextCheck(now, focusEndsAt = 0L)
        assertTrue(delay in 60_000L..61_000L)
    }

    @Test fun focusEndSoonerThanBoundaryWakesAtFocusEnd() {
        val now = millisAt(10, 3, 10)
        val focusEnd = now + 20_000
        val wake = now + BlockStateClock.delayUntilNextCheck(now, focusEnd)
        assertTrue(wake > focusEnd)
        assertTrue(wake < focusEnd + 1_000)
    }

    @Test fun pastOrLaterFocusEndIsIgnored() {
        val now = millisAt(10, 3, 10)
        val plain = BlockStateClock.delayUntilNextCheck(now, 0L)
        assertEquals(plain, BlockStateClock.delayUntilNextCheck(now, now - 5_000))
        assertEquals(plain, BlockStateClock.delayUntilNextCheck(now, now + 10 * 60_000))
    }

    @Test fun rebuildOnlyWhenStateFlips() {
        assertTrue(BlockStateClock.needsRebuild(null, false))
        assertTrue(BlockStateClock.needsRebuild(null, true))
        assertTrue(BlockStateClock.needsRebuild(false, true))
        assertTrue(BlockStateClock.needsRebuild(true, false))
        assertFalse(BlockStateClock.needsRebuild(true, true))
        assertFalse(BlockStateClock.needsRebuild(false, false))
    }

    /** VPN started at 8:58:30 with a 9–17 schedule: follow the clock until 17:02 and rebuild
     *  exactly twice — once when the schedule starts, once when it ends. */
    @Test fun simulatedDayRebuildsExactlyAtScheduleEdges() {
        var now = millisAt(8, 58, 30)
        val end = millisAt(17, 2)
        val cal = Calendar.getInstance(utc)
        fun activeAt(t: Long) = ScheduleEvaluator.anyActive(listOf(weekdays9to5), cal.apply { timeInMillis = t })

        var builtFor: Boolean? = activeAt(now) // initial refresh at VPN start
        val rebuilds = mutableListOf<Long>()
        var checks = 0
        while (now < end) {
            now += BlockStateClock.delayUntilNextCheck(now, focusEndsAt = 0L)
            checks++
            val active = activeAt(now)
            if (BlockStateClock.needsRebuild(builtFor, active)) { rebuilds += now; builtFor = active }
        }

        assertEquals(2, rebuilds.size)
        assertEquals(millisAt(9, 0), rebuilds[0] / 60_000 * 60_000)
        assertEquals(millisAt(17, 0), rebuilds[1] / 60_000 * 60_000)
        assertFalse(builtFor!!)
        assertTrue("about one check per minute", checks in 483..485)
    }
}
