package com.warden.blocker.stats

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/** The window the stats dashboard looks at. */
enum class StatsPeriod(val label: String) {
    TODAY("Today"),
    WEEK("Week"),
    MONTH("Month"),
}

/** Half-open time range [start, end) in epoch millis. */
data class TimeRange(val start: Long, val end: Long) {
    val length: Long get() = (end - start).coerceAtLeast(0)
    operator fun contains(t: Long): Boolean = t in start until end
}

/**
 * The current period, the matching previous period (same span shifted back one period, so
 * "today so far" compares with "yesterday up to the same time"), and the chart buckets.
 * Buckets are hours for [StatsPeriod.TODAY] and calendar days otherwise.
 */
data class PeriodWindow(
    val period: StatsPeriod,
    val current: TimeRange,
    val previous: TimeRange,
    val buckets: List<TimeRange>,
)

/** Foreground transitions distilled from UsageEvents, so the logic stays Android-free. */
enum class FgEventType { RESUMED, PAUSED, SCREEN_OFF, UNLOCK }

data class FgEvent(val packageName: String, val time: Long, val type: FgEventType)

/** One continuous stretch of an app in the foreground. */
data class Session(val packageName: String, val start: Long, val end: Long)

data class AppTotal(val packageName: String, val millis: Long)

/** A donut slice; [packageName] is null for the folded "Other" slice. */
data class Slice(val packageName: String?, val millis: Long, val fraction: Float)

/** Pure aggregation for the stats dashboard: period bounds, sessions, bucketing, comparisons. */
object UsageAggregator {

    fun windowFor(period: StatsPeriod, now: Long, zone: ZoneId): PeriodWindow {
        val nowZ = Instant.ofEpochMilli(now).atZone(zone)
        val today = nowZ.toLocalDate().atStartOfDay(zone)
        return when (period) {
            StatsPeriod.TODAY -> {
                val date = nowZ.toLocalDate()
                val buckets = (0 until 24).map { h ->
                    // Wall-clock hours: on DST days the skipped hour collapses (dropped below)
                    // and the repeated hour simply holds two real hours.
                    val s = date.atTime(h, 0).atZone(zone)
                    val e = if (h == 23) date.plusDays(1).atStartOfDay(zone) else date.atTime(h + 1, 0).atZone(zone)
                    TimeRange(s.millis(), e.millis())
                }.filter { it.start < it.end }
                PeriodWindow(
                    period,
                    current = TimeRange(today.millis(), now),
                    previous = TimeRange(today.minusDays(1).millis(), nowZ.minusDays(1).millis()),
                    buckets = buckets,
                )
            }
            StatsPeriod.WEEK, StatsPeriod.MONTH -> {
                val days = if (period == StatsPeriod.WEEK) 7L else 30L
                val first = today.minusDays(days - 1)
                val buckets = (0 until days).map { d ->
                    val s = first.plusDays(d)
                    TimeRange(s.millis(), s.plusDays(1).millis())
                }
                PeriodWindow(
                    period,
                    current = TimeRange(first.millis(), now),
                    previous = TimeRange(first.minusDays(days).millis(), nowZ.minusDays(days).millis()),
                    buckets = buckets,
                )
            }
        }
    }

    /**
     * Turns ordered foreground events into sessions, assuming one foreground app at a time.
     * A new app resuming closes the previous one; screen-off closes whatever was open; an app
     * still open at the end is closed at [end]. Packages in [excluded] (launcher, system UI)
     * end the previous session but are not counted themselves.
     */
    fun sessions(events: List<FgEvent>, end: Long, excluded: Set<String> = emptySet()): List<Session> {
        val out = ArrayList<Session>()
        var openPkg: String? = null
        var openSince = 0L
        fun close(at: Long) {
            val pkg = openPkg ?: return
            if (at > openSince && pkg !in excluded) out += Session(pkg, openSince, at)
            openPkg = null
        }
        for (e in events.sortedBy { it.time }) {
            when (e.type) {
                FgEventType.RESUMED -> if (e.packageName != openPkg) {
                    close(e.time)
                    openPkg = e.packageName
                    openSince = e.time
                }
                FgEventType.PAUSED -> if (e.packageName == openPkg) close(e.time)
                FgEventType.SCREEN_OFF -> close(e.time)
                FgEventType.UNLOCK -> Unit
            }
        }
        close(end)
        return out
    }

    /** How often the user started using an app: one per session start inside [range]. */
    fun opens(sessions: List<Session>, range: TimeRange): Int = sessions.count { it.start in range }

    fun unlocks(events: List<FgEvent>, range: TimeRange): Int =
        events.count { it.type == FgEventType.UNLOCK && it.time in range }

    /** Milliseconds of [session] that fall inside [range]. */
    fun overlap(session: Session, range: TimeRange): Long =
        (minOf(session.end, range.end) - maxOf(session.start, range.start)).coerceAtLeast(0)

    fun total(sessions: List<Session>, range: TimeRange): Long = sessions.sumOf { overlap(it, range) }

    /** Per-app foreground time inside [range], most-used first, zero entries dropped. */
    fun perApp(sessions: List<Session>, range: TimeRange): List<AppTotal> =
        sessions.groupBy { it.packageName }
            .map { (pkg, list) -> AppTotal(pkg, list.sumOf { overlap(it, range) }) }
            .filter { it.millis > 0 }
            .sortedWith(compareByDescending<AppTotal> { it.millis }.thenBy { it.packageName })

    /** Total foreground time per bucket (same order as [buckets]). */
    fun perBucket(sessions: List<Session>, buckets: List<TimeRange>): LongArray =
        LongArray(buckets.size) { i -> total(sessions, buckets[i]) }

    /**
     * Whole-number percent change from [previous] to [current], or null when there is nothing
     * meaningful to compare against (no previous data).
     */
    fun percentChange(current: Long, previous: Long?): Int? {
        if (previous == null || previous <= 0) return null
        return ((current - previous) * 100.0 / previous).roundToInt()
    }

    /** The [n] biggest apps as slices plus one "Other" slice for the rest (if any). */
    fun topSlices(apps: List<AppTotal>, n: Int = 5): List<Slice> {
        val sum = apps.sumOf { it.millis }
        if (sum <= 0) return emptyList()
        val sorted = apps.filter { it.millis > 0 }.sortedByDescending { it.millis }
        val top = sorted.take(n).map { Slice(it.packageName, it.millis, it.millis.toFloat() / sum) }
        val rest = sorted.drop(n).sumOf { it.millis }
        return if (rest > 0) top + Slice(null, rest, rest.toFloat() / sum) else top
    }

    private fun ZonedDateTime.millis(): Long = toInstant().toEpochMilli()

    /** Calendar days (epoch day numbers) touched by [range]; used to sum daily block counters. */
    fun epochDays(range: TimeRange, zone: ZoneId): LongRange {
        if (range.length == 0L) return LongRange.EMPTY
        val first = Instant.ofEpochMilli(range.start).atZone(zone).toLocalDate().toEpochDay()
        val last = Instant.ofEpochMilli(range.end - 1).atZone(zone).toLocalDate().toEpochDay()
        return first..last
    }
}
