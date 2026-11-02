package com.warden.blocker.block

import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class TimeWindowsTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = berlin): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun day(y: Int, mo: Int, d: Int) = LocalDate.of(y, mo, d).toEpochDay()

    private val everyDay = 0b1111111
    private fun weekly(start: Int, end: Int, mask: Int = everyDay, from: Long? = null, until: Long? = null) =
        Schedule(id = 1, name = "w", daysMask = mask, startMinute = start, endMinute = end, validFromDay = from, validUntilDay = until)

    private fun once(start: Long, end: Long) =
        Schedule(id = 2, name = "o", daysMask = 0, startMinute = 0, endMinute = 0, onceStartAt = start, onceEndAt = end)

    // --- One-time blocks ---

    @Test fun oneTimeBlockSpansSeveralDays() {
        // Fri 6 Nov 2026 18:00 → Mon 9 Nov 08:00.
        val s = once(at(2026, 11, 6, 18), at(2026, 11, 9, 8))
        assertFalse(TimeWindows.isActive(s, at(2026, 11, 6, 17, 59), berlin))
        assertTrue(TimeWindows.isActive(s, at(2026, 11, 6, 18), berlin))
        assertTrue(TimeWindows.isActive(s, at(2026, 11, 8, 3), berlin)) // Sunday night
        assertFalse(TimeWindows.isActive(s, at(2026, 11, 9, 8), berlin)) // end is exclusive
    }

    @Test fun oneTimeBlockIsNextStartAndActiveUntil() {
        val s = once(at(2026, 11, 20, 9), at(2026, 11, 22, 18))
        assertEquals(at(2026, 11, 20, 9), TimeWindows.nextStart(listOf(s), at(2026, 11, 2, 12), berlin))
        assertEquals(at(2026, 11, 22, 18), TimeWindows.activeUntil(listOf(s), at(2026, 11, 21, 12), berlin))
        assertNull(TimeWindows.nextStart(listOf(s), at(2026, 11, 21, 12), berlin)) // already running
        assertTrue(TimeWindows.isOver(s, at(2026, 11, 22, 18)))
    }

    @Test fun oneTimeBlockMonthsAheadIsFound() {
        val s = once(at(2027, 3, 1, 9), at(2027, 3, 1, 10))
        assertEquals(at(2027, 3, 1, 9), TimeWindows.nextStart(listOf(s), at(2026, 11, 2, 12), berlin))
    }

    @Test fun disabledOrEmptyOneTimeBlockNeverRuns() {
        val s = once(at(2026, 11, 6, 18), at(2026, 11, 6, 18))
        assertFalse(TimeWindows.isActive(s, at(2026, 11, 6, 18), berlin))
        val off = once(at(2026, 11, 6, 18), at(2026, 11, 7, 18)).copy(enabled = false)
        assertFalse(TimeWindows.anyActive(listOf(off), at(2026, 11, 7, 12), berlin))
    }

    // --- Weekly with a date range ---

    @Test fun dateRangeLimitsAWeeklySchedule() {
        // Every day 09–17, valid 10–12 Nov 2026.
        val s = weekly(9 * 60, 17 * 60, from = day(2026, 11, 10), until = day(2026, 11, 12))
        assertFalse(TimeWindows.isActive(s, at(2026, 11, 9, 12), berlin))
        assertTrue(TimeWindows.isActive(s, at(2026, 11, 10, 12), berlin))
        assertTrue(TimeWindows.isActive(s, at(2026, 11, 12, 16, 59), berlin)) // last day is included
        assertFalse(TimeWindows.isActive(s, at(2026, 11, 13, 12), berlin))
    }

    @Test fun overnightWindowOnTheLastDayEndsNextMorning() {
        // 22:00–06:00, valid only on 10 Nov: the night belongs to the day it starts.
        val s = weekly(22 * 60, 6 * 60, from = day(2026, 11, 10), until = day(2026, 11, 10))
        assertTrue(TimeWindows.isActive(s, at(2026, 11, 11, 5), berlin))
        assertFalse(TimeWindows.isActive(s, at(2026, 11, 10, 5), berlin)) // night before: not in range
        assertFalse(TimeWindows.isActive(s, at(2026, 11, 11, 23), berlin))
    }

    @Test fun nextStartWaitsForTheDateRange() {
        val s = weekly(9 * 60, 17 * 60, from = day(2026, 12, 1))
        assertEquals(at(2026, 12, 1, 9), TimeWindows.nextStart(listOf(s), at(2026, 11, 2, 12), berlin))
        val over = weekly(9 * 60, 17 * 60, until = day(2026, 11, 1))
        assertNull(TimeWindows.nextStart(listOf(over), at(2026, 11, 2, 12), berlin))
    }

    // --- Midnight and "blocked until" ---

    @Test fun overnightWindowCrossesMidnight() {
        val s = weekly(22 * 60, 6 * 60, mask = 0b0000001) // Mondays 22:00 → Tue 06:00
        // Mon 2 Nov 2026 is a Monday.
        assertTrue(TimeWindows.isActive(s, at(2026, 11, 2, 23, 30), berlin))
        assertTrue(TimeWindows.isActive(s, at(2026, 11, 3, 0, 0), berlin))
        assertTrue(TimeWindows.isActive(s, at(2026, 11, 3, 5, 59), berlin))
        assertFalse(TimeWindows.isActive(s, at(2026, 11, 3, 23), berlin))
        assertEquals(at(2026, 11, 3, 6), TimeWindows.activeUntil(listOf(s), at(2026, 11, 2, 23), berlin))
    }

    @Test fun backToBackWindowsChainIntoOneEnd() {
        val morning = weekly(9 * 60, 12 * 60)
        val afternoon = weekly(12 * 60, 18 * 60).copy(id = 3)
        val now = at(2026, 11, 2, 10)
        assertEquals(at(2026, 11, 2, 18), TimeWindows.activeUntil(listOf(morning, afternoon), now, berlin))
    }

    @Test fun oneTimeBlockExtendsAWeeklyWindow() {
        val work = weekly(9 * 60, 17 * 60)
        val extra = once(at(2026, 11, 2, 16), at(2026, 11, 2, 21))
        assertEquals(at(2026, 11, 2, 21), TimeWindows.activeUntil(listOf(work, extra), at(2026, 11, 2, 10), berlin))
    }

    @Test fun allDayEveryDayHasNoEnd() {
        val always = weekly(0, 0) // start == end: the whole day
        assertTrue(TimeWindows.isActive(always, at(2026, 11, 2, 12), berlin))
        assertNull(TimeWindows.activeUntil(listOf(always), at(2026, 11, 2, 12), berlin))
    }

    @Test fun notRunningMeansNoEnd() {
        assertNull(TimeWindows.activeUntil(listOf(weekly(9 * 60, 17 * 60)), at(2026, 11, 2, 18), berlin))
    }

    // --- Daylight saving ---

    @Test fun springForwardKeepsWallClockTimes() {
        // Berlin: Sun 29 Mar 2026 02:00 → 03:00. A 01:00–04:00 window is two real hours long.
        val s = weekly(60, 4 * 60)
        val w = TimeWindows.windows(s, LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29), berlin).single()
        assertEquals(2 * 3_600_000L, w.end - w.start)
        assertTrue(TimeWindows.isActive(s, at(2026, 3, 29, 3, 30), berlin))
        assertFalse(TimeWindows.isActive(s, at(2026, 3, 29, 4, 0), berlin))
    }

    @Test fun windowInsideTheSkippedHourMovesForward() {
        // 02:15–02:45 doesn't exist on 29 Mar 2026 in Berlin: it runs an hour later instead.
        val s = weekly(2 * 60 + 15, 2 * 60 + 45)
        val w = TimeWindows.windows(s, LocalDate.of(2026, 3, 29), LocalDate.of(2026, 3, 29), berlin).single()
        assertEquals(at(2026, 3, 29, 3, 15), w.start)
        assertEquals(30 * 60_000L, w.end - w.start)
        assertTrue(TimeWindows.isActive(s, at(2026, 3, 30, 2, 30), berlin)) // normal again next day
    }

    @Test fun fallBackKeepsWallClockTimes() {
        // Berlin: Sun 25 Oct 2026 03:00 → 02:00. A 22:00–06:00 night is nine real hours long.
        val s = weekly(22 * 60, 6 * 60)
        val w = TimeWindows.windows(s, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 24), berlin).single()
        assertEquals(9 * 3_600_000L, w.end - w.start)
        assertEquals(at(2026, 10, 25, 6), TimeWindows.activeUntil(listOf(s), at(2026, 10, 25, 1), berlin))
    }

    @Test fun nextStartAcrossDst() {
        val s = weekly(9 * 60, 17 * 60)
        // Saturday evening before the spring change: next start is Sunday 09:00 summer time.
        assertEquals(at(2026, 3, 29, 9), TimeWindows.nextStart(listOf(s), at(2026, 3, 28, 20), berlin))
    }

    // --- Per-entry spans ---

    private val item = BlockedItem(id = 7, type = BlockType.APP, value = "com.example", label = "Example")

    @Test fun entryBlocksOnlyInsideItsSpan() {
        val until = item.copy(activeUntil = 1_000L)
        assertTrue(TimeWindows.inEffect(until, 999L))
        assertFalse(TimeWindows.inEffect(until, 1_000L))
        val from = item.copy(activeFrom = 500L)
        assertFalse(TimeWindows.inEffect(from, 499L))
        assertTrue(TimeWindows.inEffect(from, 500L))
        assertTrue(TimeWindows.inEffect(item, 0L)) // no span: always
    }

    // --- Loosening rules ---

    @Test fun spanLoosening() {
        assertTrue(TimeWindows.spanLoosens(null, null, null, 100L)) // an end where there was none
        assertTrue(TimeWindows.spanLoosens(null, 200L, null, 100L)) // ends sooner
        assertFalse(TimeWindows.spanLoosens(null, 100L, null, 200L)) // ends later
        assertFalse(TimeWindows.spanLoosens(null, 100L, null, null)) // end removed
        assertTrue(TimeWindows.spanLoosens(null, null, 50L, null)) // starts later
        assertFalse(TimeWindows.spanLoosens(50L, null, 10L, null)) // starts sooner
    }

    @Test fun scheduleTimeLoosening() {
        val now = at(2026, 11, 2, 12)
        val o = once(at(2026, 11, 2, 10), at(2026, 11, 2, 18))
        assertTrue(TimeWindows.scheduleTimesLoosen(o, o.copy(onceEndAt = at(2026, 11, 2, 14)), now))
        assertFalse(TimeWindows.scheduleTimesLoosen(o, o.copy(onceEndAt = at(2026, 11, 2, 20)), now))
        assertTrue(TimeWindows.scheduleTimesLoosen(o, weekly(9 * 60, 17 * 60), now)) // kind changed
        // A block that is over may be changed freely.
        assertFalse(TimeWindows.scheduleTimesLoosen(o, o.copy(onceEndAt = at(2026, 11, 2, 11)), at(2026, 11, 3, 0)))

        val w = weekly(9 * 60, 17 * 60, from = day(2026, 11, 1), until = day(2026, 11, 30))
        assertTrue(TimeWindows.scheduleTimesLoosen(w, w.copy(validUntilDay = day(2026, 11, 20)), now))
        assertTrue(TimeWindows.scheduleTimesLoosen(w, w.copy(validFromDay = day(2026, 11, 5)), now))
        assertFalse(TimeWindows.scheduleTimesLoosen(w, w.copy(validUntilDay = null), now))
        assertTrue(TimeWindows.scheduleTimesLoosen(weekly(9 * 60, 17 * 60), w, now)) // a range added
    }
}
