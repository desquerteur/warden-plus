package com.warden.blocker.ui

import com.warden.blocker.block.ScheduleEvaluator
import com.warden.blocker.data.Schedule
import com.warden.blocker.hosts.HostsSource
import java.util.Calendar

/**
 * The title of the Home card: what is really happening right now, not just the switch.
 * A focus session blocks even with the switch off; with "Always on" off the switch alone
 * blocks nothing outside the schedules.
 */
internal fun blockingHeadline(
    masterEnabled: Boolean,
    alwaysOn: Boolean,
    focusEndsAt: Long,
    schedules: List<Schedule>,
    now: Calendar,
    is24Hour: Boolean,
): String {
    val nowMillis = now.timeInMillis
    if (focusEndsAt > nowMillis) return "Focus session – ${durationLeft(focusEndsAt - nowMillis)} left"
    if (!masterEnabled) return "Blocking is OFF"
    if (alwaysOn || ScheduleEvaluator.anyActive(schedules, now)) return "Blocking is ON"
    val next = ScheduleEvaluator.nextStart(schedules, now) ?: return "On – no schedule set"
    return "On – waiting for schedule (next: ${dayAndTime(next, now, is24Hour)})"
}

/** Whole minutes, rounded up so the last seconds still read "1 min": "23 min", "1 h 5 min". */
internal fun durationLeft(millis: Long): String {
    val minutes = ((millis + 59_999) / 60_000).coerceAtLeast(1)
    return when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "today 09:00", "Mon 09:00" or, with a 12-hour clock, "Mon 9:00 AM". */
internal fun dayAndTime(at: Calendar, now: Calendar, is24Hour: Boolean): String {
    val sameDay = at.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        at.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val day = if (sameDay) "today" else WEEKDAYS[(at.get(Calendar.DAY_OF_WEEK) + 5) % 7]
    return "$day ${clockTime(at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE), is24Hour)}"
}

/** Minutes since midnight as "17:00" or "5:00 PM", following the phone's 12/24-hour setting. */
internal fun clockTime(minuteOfDay: Int, is24Hour: Boolean): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    if (is24Hour) return "%02d:%02d".format(h, m)
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "%d:%02d %s".format(h12, m, if (h < 12) "AM" else "PM")
}

/**
 * The one-line count under "Blocking is ON/OFF": blocklist items plus the domains from enabled
 * hosts lists, e.g. "3 items · 77,758 domains". Short enough for one line on a 320dp screen.
 */
/**
 * What is actually enforced while blocking (or a focus session) is on: apps need the
 * accessibility service, websites need Warden's VPN. E.g. "Apps: on · Websites: off (another
 * VPN is active)" when Tailscale & co. hold the only VPN slot.
 */
internal fun protectionStatus(appsOn: Boolean, websitesOn: Boolean, otherVpn: Boolean): String {
    val websites = when {
        websitesOn -> "on"
        otherVpn -> "off (another VPN is active)"
        else -> "off"
    }
    return "Apps: ${if (appsOn) "on" else "off"} · Websites: $websites"
}

internal fun protectedSummary(activeItems: Int, hostsLists: List<HostsSource>): String {
    val listDomains = hostsLists.filter { it.enabled }.sumOf { it.domainCount.toLong() }
    val items = if (activeItems == 1) "1 item" else "$activeItems items"
    return when {
        listDomains == 0L -> "$items protected"
        activeItems == 0 -> "${"%,d".format(listDomains)} domains from lists"
        else -> "$items · ${"%,d".format(listDomains)} domains"
    }
}
