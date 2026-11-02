package com.warden.blocker.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class UsageAggregatorTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val utc = ZoneId.of("UTC")

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val min = 60_000L
    private val hour = 60 * min

    // --- period bounds ---

    @Test fun today_runsFromMidnightToNow_andComparesWithYesterdaySameTime() {
        val now = at(berlin, 2026, 11, 2, 14, 30)
        val w = UsageAggregator.windowFor(StatsPeriod.TODAY, now, berlin)
        assertEquals(at(berlin, 2026, 11, 2), w.current.start)
        assertEquals(now, w.current.end)
        assertEquals(at(berlin, 2026, 11, 1), w.previous.start)
        assertEquals(at(berlin, 2026, 11, 1, 14, 30), w.previous.end)
        assertEquals(24, w.buckets.size)
        assertEquals(at(berlin, 2026, 11, 2, 13), w.buckets[13].start)
        assertEquals(at(berlin, 2026, 11, 3), w.buckets.last().end)
    }

    @Test fun week_isSevenCalendarDaysEndingToday() {
        val now = at(utc, 2026, 11, 2, 9)
        val w = UsageAggregator.windowFor(StatsPeriod.WEEK, now, utc)
        assertEquals(at(utc, 2026, 10, 27), w.current.start)
        assertEquals(7, w.buckets.size)
        assertEquals(at(utc, 2026, 11, 2), w.buckets.last().start)
        assertEquals(at(utc, 2026, 10, 20), w.previous.start)
        assertEquals(at(utc, 2026, 10, 26, 9), w.previous.end)
    }

    @Test fun month_hasThirtyDayBuckets() {
        val now = at(utc, 2026, 11, 2, 9)
        val w = UsageAggregator.windowFor(StatsPeriod.MONTH, now, utc)
        assertEquals(30, w.buckets.size)
        assertEquals(at(utc, 2026, 10, 4), w.current.start)
        assertEquals(at(utc, 2026, 9, 4), w.previous.start)
    }

    @Test fun dstSpringForward_dropsTheSkippedHour() {
        val now = at(berlin, 2026, 3, 29, 12)
        val w = UsageAggregator.windowFor(StatsPeriod.TODAY, now, berlin)
        assertEquals(23, w.buckets.size)
        assertTrue(w.buckets.all { it.length == hour })
    }

    @Test fun dstWeek_dayBucketsFollowTheCalendar() {
        val now = at(berlin, 2026, 3, 31, 12)
        val w = UsageAggregator.windowFor(StatsPeriod.WEEK, now, berlin)
        val sunday = w.buckets.first { it.start == at(berlin, 2026, 3, 29) }
        assertEquals(23 * hour, sunday.length)
    }

    // --- sessions ---

    @Test fun sessions_switchingAppsClosesThePreviousOne() {
        val events = listOf(
            FgEvent("a", 0, FgEventType.RESUMED),
            FgEvent("b", 10 * min, FgEventType.RESUMED),
            FgEvent("b", 25 * min, FgEventType.PAUSED),
        )
        val s = UsageAggregator.sessions(events, end = hour)
        assertEquals(listOf(Session("a", 0, 10 * min), Session("b", 10 * min, 25 * min)), s)
    }

    @Test fun sessions_screenOffAndOpenEndAreHandled() {
        val events = listOf(
            FgEvent("a", 0, FgEventType.RESUMED),
            FgEvent("android", 5 * min, FgEventType.SCREEN_OFF),
            FgEvent("b", 30 * min, FgEventType.RESUMED),
        )
        val s = UsageAggregator.sessions(events, end = 40 * min)
        assertEquals(listOf(Session("a", 0, 5 * min), Session("b", 30 * min, 40 * min)), s)
    }

    @Test fun sessions_sameAppActivityHopIsOneSession_andLauncherIsExcluded() {
        val events = listOf(
            FgEvent("launcher", 0, FgEventType.RESUMED),
            FgEvent("a", 2 * min, FgEventType.RESUMED),
            FgEvent("a", 3 * min, FgEventType.RESUMED), // second activity of the same app
            FgEvent("a", 8 * min, FgEventType.PAUSED),
            FgEvent("b", 9 * min, FgEventType.PAUSED), // stray pause of an app not in front
        )
        val s = UsageAggregator.sessions(events, end = hour, excluded = setOf("launcher"))
        assertEquals(listOf(Session("a", 2 * min, 8 * min)), s)
    }

    @Test fun opensAndUnlocksAreCountedInsideTheRange() {
        val sessions = listOf(Session("a", 0, 5), Session("b", 10, 20), Session("a", 30, 40))
        assertEquals(2, UsageAggregator.opens(sessions, TimeRange(5, 35)))
        val events = listOf(
            FgEvent("android", 1, FgEventType.UNLOCK),
            FgEvent("android", 50, FgEventType.UNLOCK),
        )
        assertEquals(1, UsageAggregator.unlocks(events, TimeRange(0, 10)))
    }

    // --- sums & buckets ---

    @Test fun sessionAcrossBucketBoundaryIsSplit() {
        val buckets = listOf(TimeRange(0, hour), TimeRange(hour, 2 * hour))
        val s = listOf(Session("a", 50 * min, 70 * min))
        val per = UsageAggregator.perBucket(s, buckets)
        assertEquals(10 * min, per[0])
        assertEquals(10 * min, per[1])
        assertEquals(20 * min, UsageAggregator.total(s, TimeRange(0, 2 * hour)))
        assertEquals(10 * min, UsageAggregator.total(s, TimeRange(60 * min, 3 * hour)))
    }

    @Test fun perAppSumsAndSortsMostUsedFirst() {
        val s = listOf(
            Session("a", 0, 10), Session("b", 10, 40), Session("a", 40, 60), Session("c", 100, 200),
        )
        val apps = UsageAggregator.perApp(s, TimeRange(0, 100))
        assertEquals(listOf(AppTotal("b", 30), AppTotal("a", 30)).sortedBy { it.packageName }, apps.sortedBy { it.packageName })
        assertEquals(2, apps.size) // "c" lies outside the range
        assertEquals("a", apps.first().packageName) // tie broken by name
    }

    // --- comparison ---

    @Test fun percentChange() {
        assertEquals(50, UsageAggregator.percentChange(150, 100))
        assertEquals(-25, UsageAggregator.percentChange(75, 100))
        assertEquals(0, UsageAggregator.percentChange(100, 100))
        assertEquals(33, UsageAggregator.percentChange(4, 3))
        assertNull(UsageAggregator.percentChange(100, 0))
        assertNull(UsageAggregator.percentChange(100, null))
    }

    // --- top N + other ---

    @Test fun topSlicesFoldsTheRestIntoOther() {
        val apps = (1..8).map { AppTotal("p$it", (10 - it) * 10L) } // 90, 80, ..., 20 → sum 440
        val slices = UsageAggregator.topSlices(apps, n = 5)
        assertEquals(6, slices.size)
        assertEquals(listOf("p1", "p2", "p3", "p4", "p5", null), slices.map { it.packageName })
        assertEquals(30 + 20 + 40L, slices.last().millis) // p6=40, p7=30, p8=20
        assertEquals(1f, slices.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
    }

    @Test fun topSlicesWithoutRestHasNoOther_andEmptyStaysEmpty() {
        val slices = UsageAggregator.topSlices(listOf(AppTotal("a", 3), AppTotal("b", 1)), n = 5)
        assertEquals(listOf("a", "b"), slices.map { it.packageName })
        assertEquals(0.75f, slices[0].fraction, 0.0001f)
        assertTrue(UsageAggregator.topSlices(emptyList()).isEmpty())
    }

    @Test fun epochDaysCoversEveryTouchedDay() {
        val r = TimeRange(at(utc, 2026, 11, 1, 23), at(utc, 2026, 11, 2, 1))
        val days = UsageAggregator.epochDays(r, utc)
        assertEquals(2, days.count())
        assertTrue(UsageAggregator.epochDays(TimeRange(5, 5), utc).isEmpty())
    }
}
