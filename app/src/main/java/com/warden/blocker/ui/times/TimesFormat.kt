package com.warden.blocker.ui.times

import com.warden.blocker.block.TimeWindows
import com.warden.blocker.data.Schedule
import com.warden.blocker.ui.clockTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Same language as the rest of the app's texts ("today", "Mon").
private val LOCALE = Locale.ENGLISH

/**
 * "today 18:00", "Sun 18:00" (within the coming week), "Sun 16 Nov 18:00" or, in another year,
 * "Sun 3 Jan 2027 18:00". The clock follows the phone's 12/24-hour setting.
 */
internal fun dateTimeLabel(at: Long, now: Long, zone: ZoneId, is24Hour: Boolean): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val ahead = ChronoUnit.DAYS.between(today, t.toLocalDate())
    val day = when {
        ahead == 0L -> "today"
        ahead in 1..6 -> weekday(t.dayOfWeek)
        else -> dateLabel(t.toLocalDate(), today)
    }
    return "$day ${clockTime(t.hour * 60 + t.minute, is24Hour)}"
}

/** "Sun 16 Nov", with the year when it isn't this year: "Sun 3 Jan 2027". */
internal fun dateLabel(day: LocalDate, today: LocalDate): String {
    val base = "${weekday(day.dayOfWeek)} ${day.dayOfMonth} ${day.month.getDisplayName(TextStyle.SHORT, LOCALE)}"
    return if (day.year == today.year) base else "$base ${day.year}"
}

private fun weekday(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, LOCALE)

/** "18:00" or "6:00 PM". */
internal fun timeLabel(at: Long, zone: ZoneId, is24Hour: Boolean): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    return clockTime(t.hour * 60 + t.minute, is24Hour)
}

/** One "Block until" shortcut. */
internal data class QuickUntil(val label: String, val at: Long)

/**
 * Shortcuts for "Block until": tonight 22:00 (while at least 15 minutes away), tomorrow 07:00
 * and Sunday 18:00 (this Sunday, or next week's once it has passed).
 */
internal fun quickUntilChoices(now: Long, zone: ZoneId, is24Hour: Boolean): List<QuickUntil> {
    val nowZ = Instant.ofEpochMilli(now).atZone(zone)
    val today = nowZ.toLocalDate()
    fun at(day: LocalDate, hour: Int) = ZonedDateTime.of(day, LocalTime.of(hour, 0), zone).toInstant().toEpochMilli()
    val out = ArrayList<QuickUntil>()
    val tonight = at(today, 22)
    if (tonight - now >= 15 * 60_000L) out += QuickUntil("Until ${clockTime(22 * 60, is24Hour)}", tonight)
    out += QuickUntil("Until tomorrow ${clockTime(7 * 60, is24Hour)}", at(today.plusDays(1), 7))
    var sunday = today.plusDays(((DayOfWeek.SUNDAY.value - today.dayOfWeek.value + 7) % 7).toLong())
    if (at(sunday, 18) - now < 15 * 60_000L) sunday = sunday.plusWeeks(1)
    val sundayAt = at(sunday, 18)
    out += QuickUntil("Until ${dateTimeLabel(sundayAt, now, zone, is24Hour)}", sundayAt)
    return out
}

/** The line under a schedule's name: times and days, a one-time span, and any date range. */
internal fun scheduleLine(s: Schedule, now: Long, zone: ZoneId, is24Hour: Boolean, daysLabel: String): String {
    if (TimeWindows.isOnce(s)) {
        val span = "${dateTimeLabel(s.onceStartAt!!, now, zone, is24Hour)} – ${dateTimeLabel(s.onceEndAt!!, now, zone, is24Hour)}"
        return if (TimeWindows.isOver(s, now)) "One-time · ended · $span" else "One-time · $span"
    }
    val base = "${clockTime(s.startMinute, is24Hour)}–${clockTime(s.endMinute, is24Hour)}  •  $daysLabel"
    return base + rangeSuffix(s.validFromDay, s.validUntilDay, Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
}

/** "  •  from Mon 1 Dec", "  •  until Wed 24 Dec", "  •  1 Dec – 24 Dec" or "". */
internal fun rangeSuffix(fromDay: Long?, untilDay: Long?, today: LocalDate): String {
    val from = fromDay?.let { dateLabel(LocalDate.ofEpochDay(it), today) }
    val until = untilDay?.let { dateLabel(LocalDate.ofEpochDay(it), today) }
    return when {
        from != null && until != null -> "  •  $from – $until"
        from != null -> "  •  from $from"
        until != null -> "  •  until $until"
        else -> ""
    }
}

/** "until Sun 18:00", "from Mon 08:00", "from … until …", "ended Sun 18:00" or null without a span. */
internal fun spanLabel(from: Long?, until: Long?, now: Long, zone: ZoneId, is24Hour: Boolean): String? {
    if (until != null && until <= now) return "ended ${dateTimeLabel(until, now, zone, is24Hour)}"
    val f = from?.takeIf { it > now }?.let { "from ${dateTimeLabel(it, now, zone, is24Hour)}" }
    val u = until?.let { "until ${dateTimeLabel(it, now, zone, is24Hour)}" }
    return listOfNotNull(f, u).joinToString(" ").ifEmpty { null }
}
