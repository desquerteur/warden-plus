package com.warden.blocker.block

import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure time logic for schedules (weekly, one-time, with a date range) and for entries that only
 * block for a while. Weekly windows are built from local dates and times in the phone's zone,
 * so they follow daylight-saving changes: "09:00–17:00" is 09:00–17:00 on the clock even on the
 * day the clocks change. A time inside the skipped spring hour moves forward by that hour
 * (02:30 → 03:30); a time in the repeated autumn hour is its first occurrence.
 */
object TimeWindows {

    /** [start] inclusive, [end] exclusive, epoch millis. */
    data class Window(val start: Long, val end: Long) {
        operator fun contains(t: Long) = t >= start && t < end
    }

    /** How far ahead [nextStart] and [activeUntil] look. Covers every weekday twice. */
    private const val HORIZON_DAYS = 15L

    fun isOnce(s: Schedule): Boolean = s.onceStartAt != null && s.onceEndAt != null

    /** True when an entry with this span blocks at [now]; null ends mean "no limit". */
    fun inEffect(from: Long?, until: Long?, now: Long): Boolean =
        (from == null || now >= from) && (until == null || now < until)

    fun inEffect(item: BlockedItem, now: Long): Boolean = inEffect(item.activeFrom, item.activeUntil, now)

    /** True when the weekly schedule [s] runs on the local date [day] (weekday and date range). */
    fun runsOn(s: Schedule, day: LocalDate): Boolean {
        val dow = day.dayOfWeek.value - 1 // Mon=0 … Sun=6
        if ((s.daysMask shr dow) and 1 == 0) return false
        val epochDay = day.toEpochDay()
        return (s.validFromDay == null || epochDay >= s.validFromDay) &&
            (s.validUntilDay == null || epochDay <= s.validUntilDay)
    }

    /**
     * The windows of [s] that start on the local dates [from]..[to] (one-time blocks: its
     * window, whatever the dates). Empty windows are dropped.
     */
    fun windows(s: Schedule, from: LocalDate, to: LocalDate, zone: ZoneId): List<Window> {
        if (isOnce(s)) {
            val w = Window(s.onceStartAt!!, s.onceEndAt!!)
            return if (w.end > w.start) listOf(w) else emptyList()
        }
        if (s.daysMask and 0x7F == 0) return emptyList()
        val out = ArrayList<Window>()
        var day = from
        while (!day.isAfter(to)) {
            if (runsOn(s, day)) {
                val endDay = if (s.endMinute <= s.startMinute) day.plusDays(1) else day
                val start = day.atStartOfDay().plusMinutes(s.startMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
                val end = endDay.atStartOfDay().plusMinutes(s.endMinute.toLong()).atZone(zone).toInstant().toEpochMilli()
                if (end > start) out += Window(start, end)
            }
            day = day.plusDays(1)
        }
        return out
    }

    private fun today(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    fun isActive(s: Schedule, now: Long, zone: ZoneId): Boolean {
        val today = today(now, zone)
        // A window that wraps past midnight belongs to the day before.
        return windows(s, today.minusDays(1), today, zone).any { now in it }
    }

    fun anyActive(schedules: List<Schedule>, now: Long, zone: ZoneId): Boolean =
        schedules.any { it.enabled && isActive(it, now, zone) }

    /** The next moment after [now] an enabled schedule starts, or null when none ever will. */
    fun nextStart(schedules: List<Schedule>, now: Long, zone: ZoneId): Long? {
        val today = today(now, zone)
        var best: Long? = null
        for (s in schedules) {
            if (!s.enabled) continue
            // A date range far ahead: start looking where it begins.
            val from = s.validFromDay?.let { maxOf(today, LocalDate.ofEpochDay(it)) } ?: today
            val start = windows(s, from, from.plusDays(HORIZON_DAYS), zone)
                .map { it.start }.filter { it > now }.minOrNull() ?: continue
            if (best == null || start < best) best = start
        }
        return best
    }

    /**
     * When blocking by schedule stops, if a schedule is running at [now]: the end of the
     * running window, extended by any window that overlaps or directly follows it. Null when
     * none is running, or when blocking goes on past the look-ahead (e.g. every day, all day).
     */
    fun activeUntil(schedules: List<Schedule>, now: Long, zone: ZoneId): Long? {
        val today = today(now, zone)
        val lastDay = today.plusDays(HORIZON_DAYS)
        val all = schedules.filter { it.enabled }
            .flatMap { windows(it, today.minusDays(1), lastDay, zone) }
        // Weekly windows are only generated up to the last day; reaching one of those means
        // the chain may well go on.
        val lastDayStart = lastDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekly = schedules.filter { it.enabled && !isOnce(it) }
            .flatMap { windows(it, lastDay, lastDay, zone) }.toSet()
        var chain = all.filter { now in it }.maxByOrNull { it.end } ?: return null
        var end = chain.end
        while (true) {
            if (chain.start >= lastDayStart && chain in weekly) return null
            chain = all.filter { it.start <= end && it.end > end }.maxByOrNull { it.end } ?: break
            end = chain.end
        }
        return end
    }

    /** True for a one-time block that is over at [now] (kept in the list until deleted). */
    fun isOver(s: Schedule, now: Long): Boolean = isOnce(s) && s.onceEndAt!! <= now

    // --- What counts as blocking less (for Block protection) ---

    /**
     * True when the span [newFrom]..[newUntil] blocks less than [oldFrom]..[oldUntil]: it starts
     * later or ends sooner (null = open-ended). Starting sooner or ending later never does.
     */
    fun spanLoosens(oldFrom: Long?, oldUntil: Long?, newFrom: Long?, newUntil: Long?): Boolean {
        val startsLater = newFrom != null && (oldFrom == null || newFrom > oldFrom)
        val endsSooner = newUntil != null && (oldUntil == null || newUntil < oldUntil)
        return startsLater || endsSooner
    }

    fun itemTimesLoosen(old: BlockedItem, new: BlockedItem): Boolean =
        spanLoosens(old.activeFrom, old.activeUntil, new.activeFrom, new.activeUntil)

    /**
     * True when [new] could block less than [old] in its new time fields: a one-time block
     * starts later or ends sooner (unless it is already over), it changes kind, or a weekly
     * schedule's date range shrinks. Days and clock times are compared elsewhere (StrictLock).
     */
    fun scheduleTimesLoosen(old: Schedule, new: Schedule, now: Long): Boolean {
        val oldOnce = isOnce(old)
        if (oldOnce && isOver(old, now)) return false
        if (oldOnce != isOnce(new)) return true
        if (oldOnce) return spanLoosens(old.onceStartAt, old.onceEndAt, new.onceStartAt, new.onceEndAt)
        return spanLoosens(old.validFromDay, old.validUntilDay?.plus(1), new.validFromDay, new.validUntilDay?.plus(1))
    }
}
