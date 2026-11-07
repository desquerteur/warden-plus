package com.warden.blocker.time

import android.os.Build
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL

/** A network time and the time since boot it belongs to. */
data class NetworkReading(val wallMs: Long, val elapsedMs: Long)

/**
 * Where the real time comes from when the phone's clock can't be trusted. Sends nothing about
 * the user: Android's own network time, or a single HEAD request to the GitHub API (which the
 * updater talks to anyway) for its `Date` header.
 */
object NetworkTime {

    private const val URL_HEAD = "https://api.github.com/"
    private const val TIMEOUT_MS = 5_000

    /** Answers slower than this are too vague to be useful. */
    private const val MAX_ROUND_TRIP_MS = 10_000L

    /** Times before this are certainly wrong (a broken server or a proxy). */
    private const val SANE_AFTER_MS = 1_735_689_600_000L // 2025-01-01

    /**
     * Android's network (NTP) time, Android 13+. Kept by the system independent of the phone's
     * clock, so setting the clock by hand doesn't change it. Null if there is none yet.
     */
    fun fromSystem(): NetworkReading? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            val clock = SystemClock.currentNetworkTimeClock()
            val elapsed = SystemClock.elapsedRealtime()
            NetworkReading(clock.millis(), elapsed)
        }.getOrNull()?.takeIf { it.wallMs > SANE_AFTER_MS }
    }

    /** The `Date` header of an HTTPS answer (blocking — call off the main thread). Null when offline. */
    fun fromHttps(): NetworkReading? = runCatching {
        val conn = URL(URL_HEAD).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "WardenPlus")
            val before = SystemClock.elapsedRealtime()
            conn.responseCode // any status carries a Date header, also "rate limited"
            val after = SystemClock.elapsedRealtime()
            val date = conn.getHeaderFieldDate("Date", 0L)
            if (date < SANE_AFTER_MS || after - before > MAX_ROUND_TRIP_MS) return@runCatching null
            // The header has whole seconds: the middle of that second, at the middle of the round trip.
            NetworkReading(date + 500, (before + after) / 2)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
