package com.warden.blocker.ui

import com.warden.blocker.block.ProtectionMode
import com.warden.blocker.block.ProtectionSettings
import com.warden.blocker.block.ScheduleEvaluator
import com.warden.blocker.data.Schedule
import com.warden.blocker.ui.stats.shownFocusStreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BlockingHeadlineTest {

    /** Monday 3 Nov 2025, [hour]:[minute]. */
    private fun monday(hour: Int, minute: Int = 0): Calendar = Calendar.getInstance().apply {
        clear()
        set(2025, Calendar.NOVEMBER, 3, hour, minute, 0)
    }

    private val work = Schedule(id = 1, name = "Work", daysMask = 0b0011111, startMinute = 9 * 60, endMinute = 17 * 60)

    private fun headline(
        enabled: Boolean = true,
        alwaysOn: Boolean = true,
        focusEndsAt: Long = 0L,
        schedules: List<Schedule> = emptyList(),
        now: Calendar = monday(12),
        is24Hour: Boolean = true,
    ) = blockingHeadline(enabled, alwaysOn, focusEndsAt, schedules, now, is24Hour)

    @Test fun switchOnOrOff() {
        assertEquals("Blocking is ON", headline())
        assertEquals("Blocking is OFF", headline(enabled = false))
    }

    @Test fun focusSessionWinsEvenWithTheSwitchOff() {
        val now = monday(12)
        val endsAt = now.timeInMillis + 23 * 60_000L
        assertEquals("Focus session – 23 min left", headline(enabled = false, focusEndsAt = endsAt, now = now))
        assertEquals("Focus session – 23 min left", headline(enabled = true, focusEndsAt = endsAt - 30_000, now = now))
        assertEquals("Blocking is OFF", headline(enabled = false, focusEndsAt = now.timeInMillis, now = now))
    }

    @Test fun duringAScheduleItSaysUntilWhen() {
        assertEquals("Blocked until today 17:00", headline(alwaysOn = false, schedules = listOf(work), now = monday(10)))
        // "Always on" has no end.
        assertEquals("Blocking is ON", headline(alwaysOn = true, schedules = listOf(work), now = monday(10)))
    }

    @Test fun longBlockUntilShowsTheEnd() {
        val now = monday(12)
        val sunday18 = monday(18).apply { add(Calendar.DAY_OF_YEAR, 6) }.timeInMillis
        assertEquals("Blocked until Sun 18:00", headline(enabled = false, focusEndsAt = sunday18, now = now))
    }

    @Test fun oneTimeBlockFarAheadShowsTheDate() {
        val start = monday(9).apply { add(Calendar.DAY_OF_YEAR, 14) }.timeInMillis
        val trip = Schedule(id = 5, name = "Trip", daysMask = 0, startMinute = 0, endMinute = 0, onceStartAt = start, onceEndAt = start + 3_600_000L)
        assertEquals(
            "On – waiting for schedule (next: Mon 17 Nov 09:00)",
            headline(alwaysOn = false, schedules = listOf(trip), now = monday(12)),
        )
    }

    @Test fun outsideTheSchedulesItWaits() {
        assertEquals(
            "On – waiting for schedule (next: today 09:00)",
            headline(alwaysOn = false, schedules = listOf(work), now = monday(7, 30)),
        )
        assertEquals(
            "On – waiting for schedule (next: Tue 09:00)",
            headline(alwaysOn = false, schedules = listOf(work), now = monday(18)),
        )
        assertEquals(
            "On – waiting for schedule (next: Tue 9:00 AM)",
            headline(alwaysOn = false, schedules = listOf(work), now = monday(18), is24Hour = false),
        )
    }

    @Test fun noScheduleAtAll() {
        assertEquals("On – no schedule set", headline(alwaysOn = false))
        assertEquals("On – no schedule set", headline(alwaysOn = false, schedules = listOf(work.copy(enabled = false))))
    }

    @Test fun nextStartSkipsTheWeekend() {
        val friday = monday(18).apply { add(Calendar.DAY_OF_YEAR, 4) }
        val next = ScheduleEvaluator.nextStart(listOf(work), friday)!!
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(9, next.get(Calendar.HOUR_OF_DAY))
        assertNull(ScheduleEvaluator.nextStart(emptyList(), friday))
    }

    @Test fun nextStartAtTheStartMinuteIsTheNextOne() {
        // At exactly 09:00 the window is running; "next" is tomorrow's.
        val next = ScheduleEvaluator.nextStart(listOf(work), monday(9))!!
        assertEquals(Calendar.TUESDAY, next.get(Calendar.DAY_OF_WEEK))
    }

    @Test fun durations() {
        assertEquals("1 min", durationLeft(5_000))
        assertEquals("23 min", durationLeft(22 * 60_000L + 1))
        assertEquals("1 h", durationLeft(60 * 60_000L))
        assertEquals("1 h 5 min", durationLeft(65 * 60_000L))
    }

    @Test fun clockTimes() {
        assertEquals("17:00", clockTime(17 * 60, true))
        assertEquals("5:00 PM", clockTime(17 * 60, false))
        assertEquals("12:05 AM", clockTime(5, false))
        assertEquals("12:00 PM", clockTime(12 * 60, false))
    }

    @Test fun newScheduleEndsAnHourLater() {
        assertEquals(10 * 60, defaultEnd(9 * 60))
        assertEquals(30, defaultEnd(23 * 60 + 30))
    }

    @Test fun focusStreakNeedsASession() {
        assertEquals(0, shownFocusStreak(days = 1, sessionsDone = 0))
        assertEquals(3, shownFocusStreak(days = 3, sessionsDone = 1))
    }

    @Test fun whenLabels() {
        val now = monday(12)
        assertEquals("15:40", whenLabel(monday(15, 40), now, true))
        assertEquals("Fri 18:00", whenLabel(monday(18).apply { add(Calendar.DAY_OF_YEAR, 4) }, now, true))
    }

    @Test fun protectionSummaries() {
        val now = monday(12).timeInMillis
        val at = { _: Long -> "Fri 18:00" }
        assertEquals("Off – changes apply right away.", protectionSummary(ProtectionSettings(), now, at))
        assertEquals(
            "Delay – weakening waits 15 min (you can cancel).",
            protectionSummary(ProtectionSettings(ProtectionMode.DELAY, 15), now, at),
        )
        assertEquals(
            "Locked until Fri 18:00 – nothing can be weakened.",
            protectionSummary(ProtectionSettings(ProtectionMode.LOCKED, 60, now + 1), now, at),
        )
        // A lock that ran out reads as off.
        assertEquals("Off – changes apply right away.", protectionSummary(ProtectionSettings(ProtectionMode.LOCKED, 60, now), now, at))
    }

    @Test fun switchOffsThisWeek() {
        val now = monday(12).timeInMillis
        val day = 24 * 60 * 60_000L
        assertEquals(2, offCountSince(listOf(now - 8 * day, now - 6 * day, now - 1000), now))
    }
}
