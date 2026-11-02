package com.warden.blocker.block

import com.warden.blocker.data.Schedule
import java.util.Calendar

/** Pure functions deciding whether a schedule is active at a given instant. */
object ScheduleEvaluator {

    /** bit 0 = Monday … bit 6 = Sunday. */
    fun isActive(schedule: Schedule, cal: Calendar): Boolean {
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        // Calendar.MONDAY == 2 … SUNDAY == 1. Map to 0..6 with Monday = 0.
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0 … Sun=6
        val dayMatches = (schedule.daysMask shr dow) and 1 == 1
        if (!dayMatches && !wrapsFromPreviousDay(schedule, minuteOfDay, dow)) return false
        return withinWindow(schedule, minuteOfDay, dow)
    }

    private fun withinWindow(s: Schedule, minute: Int, dow: Int): Boolean {
        val todayOn = (s.daysMask shr dow) and 1 == 1
        return if (s.endMinute > s.startMinute) {
            todayOn && minute >= s.startMinute && minute < s.endMinute
        } else {
            // Wraps past midnight: active late today OR early "tomorrow" (owned by prev day).
            (todayOn && minute >= s.startMinute) ||
                (((s.daysMask shr ((dow + 6) % 7)) and 1 == 1) && minute < s.endMinute)
        }
    }

    private fun wrapsFromPreviousDay(s: Schedule, minute: Int, dow: Int): Boolean =
        s.endMinute <= s.startMinute &&
            ((s.daysMask shr ((dow + 6) % 7)) and 1 == 1) &&
            minute < s.endMinute

    fun anyActive(schedules: List<Schedule>, cal: Calendar): Boolean =
        schedules.any { it.enabled && isActive(it, cal) }

    /**
     * The next moment (whole minute, after [now]) one of the enabled [schedules] starts, or
     * null when none is enabled. Looks a week and a day ahead, which covers every weekday.
     */
    fun nextStart(schedules: List<Schedule>, now: Calendar): Calendar? {
        val enabled = schedules.filter { it.enabled && it.daysMask and 0x7F != 0 }
        if (enabled.isEmpty()) return null
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        for (offset in 0..7) {
            val day = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dow = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0 … Sun=6
            val start = enabled
                .filter { (it.daysMask shr dow) and 1 == 1 }
                .map { it.startMinute }
                .filter { offset > 0 || it > nowMinute }
                .minOrNull() ?: continue
            return day.apply { set(Calendar.HOUR_OF_DAY, start / 60); set(Calendar.MINUTE, start % 60) }
        }
        return null
    }
}
