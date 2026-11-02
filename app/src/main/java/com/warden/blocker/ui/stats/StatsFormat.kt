package com.warden.blocker.ui.stats

import com.warden.blocker.stats.StatsPeriod
import com.warden.blocker.stats.TimeRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** "2h 13m", "45m", "<1m". */
internal fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        ms in 1 until 60_000L -> "<1m"
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** Compact axis label: "2h", "1h30", "20m". */
internal fun formatAxis(ms: Long): String {
    val totalMin = ms / 60_000L
    return when {
        totalMin >= 60 && totalMin % 60 == 0L -> "${totalMin / 60}h"
        totalMin >= 60 -> "${totalMin / 60}h${"%02d".format(totalMin % 60)}"
        else -> "${totalMin}m"
    }
}

/** Sparse x-axis labels that never collide, even with 30 bars on a 320 dp screen. */
internal fun bucketAxisLabel(period: StatsPeriod, bucket: TimeRange, index: Int, count: Int, zone: ZoneId): String? {
    val t = Instant.ofEpochMilli(bucket.start).atZone(zone)
    return when (period) {
        StatsPeriod.TODAY -> if (t.hour % 6 == 0) "%02d".format(t.hour) else null
        StatsPeriod.WEEK -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        StatsPeriod.MONTH -> if ((count - 1 - index) % 7 == 0) t.dayOfMonth.toString() else null
    }
}

/** Long label for a selected bar: "14:00–15:00", "Tue, 28 Oct". */
internal fun bucketTitle(period: StatsPeriod, bucket: TimeRange, zone: ZoneId): String {
    val s = Instant.ofEpochMilli(bucket.start).atZone(zone)
    return when (period) {
        StatsPeriod.TODAY -> {
            val e = Instant.ofEpochMilli(bucket.end).atZone(zone)
            "%02d:00–%02d:00".format(s.hour, if (e.hour == 0) 24 else e.hour)
        }
        else -> s.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
    }
}

/** "↑ 12%" / "↓ 8%" / "± 0%". */
internal fun formatChange(pct: Int): String = when {
    pct > 0 -> "↑ $pct%"
    pct < 0 -> "↓ ${-pct}%"
    else -> "± 0%"
}

internal fun previousLabel(period: StatsPeriod): String = when (period) {
    StatsPeriod.TODAY -> "vs. yesterday"
    StatsPeriod.WEEK -> "vs. previous 7 days"
    StatsPeriod.MONTH -> "vs. previous 30 days"
}
