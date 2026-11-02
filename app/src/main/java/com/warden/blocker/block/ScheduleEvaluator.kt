package com.warden.blocker.block

import com.warden.blocker.data.Schedule
import java.util.Calendar

/**
 * Pure functions deciding whether a schedule is active at a given instant. Calendar-based
 * front for [TimeWindows], which also knows one-time blocks and date ranges.
 */
object ScheduleEvaluator {

    /** bit 0 = Monday … bit 6 = Sunday. */
    fun isActive(schedule: Schedule, cal: Calendar): Boolean =
        TimeWindows.isActive(schedule, cal.timeInMillis, cal.timeZone.toZoneId())

    fun anyActive(schedules: List<Schedule>, cal: Calendar): Boolean =
        TimeWindows.anyActive(schedules, cal.timeInMillis, cal.timeZone.toZoneId())

    /**
     * The next moment (whole minute, after [now]) one of the enabled [schedules] starts, or
     * null when none will.
     */
    fun nextStart(schedules: List<Schedule>, now: Calendar): Calendar? {
        val next = TimeWindows.nextStart(schedules, now.timeInMillis, now.timeZone.toZoneId()) ?: return null
        return (now.clone() as Calendar).apply { timeInMillis = next }
    }

    /** When the running schedule block ends (see [TimeWindows.activeUntil]). */
    fun activeUntil(schedules: List<Schedule>, now: Calendar): Calendar? {
        val end = TimeWindows.activeUntil(schedules, now.timeInMillis, now.timeZone.toZoneId()) ?: return null
        return (now.clone() as Calendar).apply { timeInMillis = end }
    }
}
