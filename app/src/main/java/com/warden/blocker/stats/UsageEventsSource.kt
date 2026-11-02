package com.warden.blocker.stats

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Reads raw foreground events from [UsageStatsManager] and maps them to [FgEvent]s.
 * Blocking I/O — call from a background dispatcher. Requires usage access.
 */
object UsageEventsSource {

    /** Events in [start, end), plus the first event's time (null when Android returned none). */
    data class Result(val events: List<FgEvent>, val firstEventAt: Long?)

    fun query(context: Context, start: Long, end: Long): Result {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val raw = usm.queryEvents(start, end) ?: return Result(emptyList(), null)
        val out = ArrayList<FgEvent>(4096)
        val e = UsageEvents.Event()
        var first: Long? = null
        while (raw.hasNextEvent()) {
            raw.getNextEvent(e)
            if (first == null) first = e.timeStamp
            val type = mapType(e.eventType) ?: continue
            out += FgEvent(e.packageName ?: continue, e.timeStamp, type)
        }
        return Result(out, first)
    }

    @Suppress("DEPRECATION") // MOVE_TO_* share their values with ACTIVITY_RESUMED/PAUSED.
    private fun mapType(type: Int): FgEventType? = when (type) {
        UsageEvents.Event.MOVE_TO_FOREGROUND -> FgEventType.RESUMED
        UsageEvents.Event.MOVE_TO_BACKGROUND -> FgEventType.PAUSED
        else -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == UsageEvents.Event.KEYGUARD_HIDDEN -> FgEventType.UNLOCK
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && type == UsageEvents.Event.SCREEN_NON_INTERACTIVE -> FgEventType.SCREEN_OFF
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && type == UsageEvents.Event.DEVICE_SHUTDOWN -> FgEventType.SCREEN_OFF
            else -> null
        }
    }

    /** True when the OS reports keyguard events, i.e. unlock counts are meaningful. */
    val supportsUnlocks: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** Packages that are "the phone itself" rather than an app: launchers and the system UI. */
    fun excludedPackages(context: Context): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = runCatching {
            context.packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList())
        return launchers.toSet() + "com.android.systemui"
    }
}
