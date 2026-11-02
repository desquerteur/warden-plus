package com.warden.blocker.guard

import kotlin.math.abs

/**
 * A reference point between the wall clock (which the user can change) and the time since boot
 * (`SystemClock.elapsedRealtime`, which nobody can change). Kept across process restarts;
 * worthless after a reboot, because the time since boot starts again at zero.
 */
data class ClockAnchor(
    val wallMs: Long,
    val elapsedMs: Long,
    /** `Settings.Global.BOOT_COUNT` when it was taken, to tell whether it is from this boot. */
    val bootCount: Int,
    val zoneId: String,
    /** The time zone's offset from UTC at that moment, ms. */
    val zoneOffsetMs: Int,
    /** Latest wall time ever seen — after a reboot the clock must not be earlier than this. */
    val lastSeenWallMs: Long = wallMs,
)

/** One reading of the clocks, taken together. */
data class ClockReading(
    val wallMs: Long,
    val elapsedMs: Long,
    val bootCount: Int,
    val zoneId: String,
    val zoneOffsetMs: Int,
    /** Android's "automatic date and time" (network time) is on. */
    val autoTime: Boolean,
    /** Android's "automatic time zone" is on. */
    val autoZone: Boolean,
)

enum class ClockChangeKind { FORWARD, BACKWARD, TIME_ZONE }

/**
 * What [ClockWatch.check] found. [suspicious] is false when Android set the time itself
 * (automatic time / time zone on), e.g. a network time correction or travelling.
 */
data class ClockChange(
    val kind: ClockChangeKind,
    /** Wall-clock jump (FORWARD / BACKWARD) or change of the zone offset (TIME_ZONE), ms. */
    val deltaMs: Long,
    val suspicious: Boolean,
    val fromZone: String? = null,
    val toZone: String? = null,
)

/**
 * Notices when the clock or time zone was changed: schedules and "Locked until" rely on the
 * local time, so moving the clock is a way around them.
 *
 * The wall clock and the time since boot advance together; if they drift apart by more than
 * [thresholdMs], the clock was set. Small corrections by network time are ignored.
 *
 * Pure logic, unit-tested without Android.
 */
object ClockWatch {

    const val THRESHOLD_MS = 2 * 60_000L

    data class Result(val change: ClockChange?, val anchor: ClockAnchor)

    fun check(anchor: ClockAnchor?, now: ClockReading, thresholdMs: Long = THRESHOLD_MS): Result {
        val fresh = anchorOf(now, lastSeen = now.wallMs)
        if (anchor == null) return Result(null, fresh)

        if (anchor.bootCount != now.bootCount || now.elapsedMs < anchor.elapsedMs) {
            // Rebooted: the time since boot can't be compared. Only "earlier than ever seen" is clear.
            val back = anchor.lastSeenWallMs - now.wallMs
            if (back > thresholdMs) {
                return Result(ClockChange(ClockChangeKind.BACKWARD, -back, suspicious = !now.autoTime), fresh)
            }
            return Result(null, fresh.copy(lastSeenWallMs = maxOf(now.wallMs, anchor.lastSeenWallMs)))
        }

        val expectedWall = anchor.wallMs + (now.elapsedMs - anchor.elapsedMs)
        val drift = now.wallMs - expectedWall
        val zoneDelta = (now.zoneOffsetMs - anchor.zoneOffsetMs).toLong()
        val change = when {
            abs(drift) > thresholdMs -> ClockChange(
                if (drift > 0) ClockChangeKind.FORWARD else ClockChangeKind.BACKWARD,
                drift,
                suspicious = !now.autoTime,
            )
            now.zoneId != anchor.zoneId && zoneDelta != 0L -> ClockChange(
                ClockChangeKind.TIME_ZONE,
                zoneDelta,
                suspicious = !now.autoZone,
                fromZone = anchor.zoneId,
                toZone = now.zoneId,
            )
            else -> null
        }
        // Without a change keep the old anchor (no slow drift creeping in), just note the time.
        val next = if (change == null && now.zoneId == anchor.zoneId) {
            anchor.copy(lastSeenWallMs = maxOf(anchor.lastSeenWallMs, now.wallMs))
        } else {
            fresh
        }
        return Result(change, next)
    }

    /**
     * Wall time that can't be moved by setting the clock: the anchor's time plus the time since
     * boot that passed since. Null if [anchor] is from another boot.
     */
    fun trustedNow(anchor: ClockAnchor?, elapsedMs: Long, bootCount: Int): Long? {
        if (anchor == null || anchor.bootCount != bootCount || elapsedMs < anchor.elapsedMs) return null
        return anchor.wallMs + (elapsedMs - anchor.elapsedMs)
    }

    private fun anchorOf(r: ClockReading, lastSeen: Long) =
        ClockAnchor(r.wallMs, r.elapsedMs, r.bootCount, r.zoneId, r.zoneOffsetMs, lastSeen)
}

/** "12 min", "3 h 5 min", "2 days" — for messages. */
fun formatDuration(ms: Long): String {
    val minutes = abs(ms) / 60_000L
    return when {
        minutes < 1 -> "less than a minute"
        minutes < 60 -> "$minutes min"
        minutes < 48 * 60 -> if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
        else -> "${minutes / (24 * 60)} days"
    }
}

/** One line for the notification and the Settings card. */
fun describeClockChange(kind: ClockChangeKind, deltaMs: Long): String = when (kind) {
    ClockChangeKind.FORWARD -> "Clock set ${formatDuration(deltaMs)} forward"
    ClockChangeKind.BACKWARD -> "Clock set ${formatDuration(deltaMs)} back"
    ClockChangeKind.TIME_ZONE -> "Time zone moved ${formatDuration(deltaMs)} " + if (deltaMs >= 0) "ahead" else "back"
}
