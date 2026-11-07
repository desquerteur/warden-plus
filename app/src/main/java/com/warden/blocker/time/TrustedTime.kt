package com.warden.blocker.time

import com.warden.blocker.guard.ClockChange
import com.warden.blocker.guard.ClockChangeKind
import com.warden.blocker.guard.ClockReading
import kotlin.math.abs

/** Where the trusted time last came from. */
enum class TimeSource {
    /** The phone's clock, while "automatic date & time" is on and it agrees with us. */
    SYSTEM,
    /** Network time (Android's NTP time or an HTTPS server's Date header). */
    NETWORK,
    /** After a reboot without network: the last saved trusted time, moved on by the system clock. */
    CHECKPOINT,
}

/**
 * What the trusted clock knows, saved across process restarts and reboots.
 *
 * Within one boot the trusted time is [anchorWallMs] plus the time since boot that passed since
 * [anchorElapsedMs] — `SystemClock.elapsedRealtime()` can't be changed by anyone, so setting the
 * phone's clock moves nothing here.
 */
data class TrustState(
    val anchorWallMs: Long,
    val anchorElapsedMs: Long,
    /** `Settings.Global.BOOT_COUNT` of the anchor; elapsed times of other boots can't be compared. */
    val bootCount: Int,
    val source: TimeSource,
    /** The latest trusted time saved ("checkpoint"). After a reboot time never starts earlier than this. */
    val checkpointWallMs: Long,
    /** Phone clock minus trusted time when last looked at: ~0 while the clock is right, else how far it was set. */
    val offsetMs: Long,
    /** Elapsed time of the last network confirmation in this boot, or -1. */
    val networkElapsedMs: Long = -1,
    /** The time zone schedules follow. */
    val zoneId: String,
    /** The zone was changed by hand: [zoneId] is kept until Android sets the zone itself again. */
    val zoneHeld: Boolean = false,
)

/** Result of looking at the phone's clock: the new state and, if it was set by hand, what changed. */
data class TrustUpdate(val state: TrustState, val manipulation: ClockChange? = null)

/**
 * The "trusted clock" — pure logic, unit-tested with fake readings ([ClockReading] from
 * [com.warden.blocker.guard.ClockWatch]). Blocks, focus sessions, "Block until", "Locked until",
 * waits and grants are all decided on [now], so setting the date, time or time zone by hand
 * neither ends nor shortens them.
 *
 * Rules:
 * - Within a boot only the time since boot counts (see [TrustState]).
 * - The phone's clock is followed only while "automatic date & time" is on and it agrees with
 *   the trusted time (within [TOLERANCE_MS]), or when Android itself set it ([onTimeSet]).
 * - Network time always wins ([onNetworkTime]).
 * - After a reboot without network: the phone clock, minus how far it was known to be set
 *   ([TrustState.offsetMs]), but never earlier than the last checkpoint.
 * - A time zone changed by hand is not followed; travelling with "automatic time zone" is.
 */
object TrustedTime {

    /** Smaller differences are drift or network-time corrections, not someone setting the clock. */
    const val TOLERANCE_MS = 2 * 60_000L

    fun now(s: TrustState, elapsedMs: Long): Long = s.anchorWallMs + (elapsedMs - s.anchorElapsedMs)

    fun sameBoot(s: TrustState, r: ClockReading): Boolean =
        s.bootCount == r.bootCount && r.elapsedMs >= s.anchorElapsedMs

    /** First start ever: the phone's clock is all there is (network time corrects it soon). */
    fun fresh(r: ClockReading): TrustState = TrustState(
        anchorWallMs = r.wallMs, anchorElapsedMs = r.elapsedMs, bootCount = r.bootCount,
        source = TimeSource.SYSTEM, checkpointWallMs = r.wallMs, offsetMs = 0L, zoneId = r.zoneId,
    )

    /** When the app process starts: continue the saved state, or pick up after a reboot. */
    fun restore(saved: TrustState?, r: ClockReading): TrustState {
        if (saved == null) return fresh(r)
        if (sameBoot(saved, r)) return saved
        // Rebooted. The clock was right before (and is still kept right by Android) — or it was set
        // by hand, and then it most likely still is set by the same amount.
        val systemRight = r.autoTime && abs(saved.offsetMs) <= TOLERANCE_MS
        val candidate = if (systemRight) r.wallMs else r.wallMs - saved.offsetMs
        val wall = maxOf(candidate, saved.checkpointWallMs) // never backwards
        return saved.copy(
            anchorWallMs = wall,
            anchorElapsedMs = r.elapsedMs,
            bootCount = r.bootCount,
            source = if (systemRight && wall == candidate) TimeSource.SYSTEM else TimeSource.CHECKPOINT,
            checkpointWallMs = wall,
            offsetMs = r.wallMs - wall,
            networkElapsedMs = -1,
        )
    }

    /**
     * Android says the clock was set (ACTION_TIME_CHANGED). With automatic time on, Android did it
     * itself (network time, or the user switched automatic time on): follow it. Otherwise it was set
     * by hand: the trusted time stays, and a jump bigger than [TOLERANCE_MS] is reported.
     */
    fun onTimeSet(s: TrustState, r: ClockReading): TrustUpdate {
        if (!sameBoot(s, r)) return TrustUpdate(restore(s, r))
        val trusted = now(s, r.elapsedMs)
        val offset = r.wallMs - trusted
        if (r.autoTime) {
            return TrustUpdate(anchorToSystem(s, r))
        }
        return TrustUpdate(s.copy(offsetMs = offset), manipulationOf(offset - s.offsetMs))
    }

    /**
     * The regular look at the clock (every minute, and at process start), also catching changes
     * whose broadcast was missed. Follows the phone's clock only while it is automatic and agrees.
     */
    fun observe(s: TrustState, r: ClockReading): TrustUpdate {
        if (!sameBoot(s, r)) return TrustUpdate(restore(s, r))
        val trusted = now(s, r.elapsedMs)
        val offset = r.wallMs - trusted
        if (r.autoTime && abs(offset) <= TOLERANCE_MS) {
            // Normal case: the clock is right — follow it, so no drift builds up.
            return TrustUpdate(anchorToSystem(s, r))
        }
        // Automatic time on but the clock disagrees without Android having said so: keep the
        // trusted time (network time settles it). By hand: count it if it's a new jump.
        val manipulation = if (r.autoTime) null else manipulationOf(offset - s.offsetMs)
        return TrustUpdate(s.copy(offsetMs = offset), manipulation)
    }

    /** Network time [networkWallMs], read at [atElapsedMs]: the most reliable source there is. */
    fun onNetworkTime(s: TrustState, r: ClockReading, networkWallMs: Long, atElapsedMs: Long): TrustState {
        if (!sameBoot(s, r)) return onNetworkTime(restore(s, r), r, networkWallMs, atElapsedMs)
        val anchored = s.copy(
            anchorWallMs = networkWallMs, anchorElapsedMs = atElapsedMs,
            source = TimeSource.NETWORK, networkElapsedMs = atElapsedMs,
        )
        val trusted = now(anchored, r.elapsedMs)
        // Network time may also correct a wrong checkpoint downwards.
        return anchored.copy(checkpointWallMs = trusted, offsetMs = r.wallMs - trusted)
    }

    /** Saves progress: the checkpoint only ever moves forward (except by [onNetworkTime]). */
    fun checkpoint(s: TrustState, r: ClockReading): TrustState {
        if (!sameBoot(s, r)) return restore(s, r)
        val trusted = now(s, r.elapsedMs)
        return s.copy(checkpointWallMs = maxOf(s.checkpointWallMs, trusted))
    }

    /**
     * Whether network time should be asked: the phone's clock can't be relied on (automatic time
     * off, or it disagrees) and network time hasn't confirmed the time in this boot within
     * [refreshMs].
     */
    fun wantsNetwork(s: TrustState, r: ClockReading, refreshMs: Long): Boolean {
        val clockReliable = r.autoTime && abs(r.wallMs - now(s, r.elapsedMs)) <= TOLERANCE_MS
        if (clockReliable && s.source != TimeSource.CHECKPOINT) return false
        if (!sameBoot(s, r) || s.networkElapsedMs < 0) return true
        return r.elapsedMs - s.networkElapsedMs >= refreshMs
    }

    // --- Time zone ---

    /** The zone schedules follow. */
    fun zone(s: TrustState, r: ClockReading): String = if (s.zoneHeld) s.zoneId else r.zoneId

    /**
     * The zone may have changed (ACTION_TIMEZONE_CHANGED, or the regular look). With automatic time
     * zone on (travelling) it is followed; by hand it isn't — unless the offset stays the same.
     */
    fun onZone(s: TrustState, r: ClockReading): TrustUpdate {
        if (r.zoneId == s.zoneId) return TrustUpdate(if (s.zoneHeld) s.copy(zoneHeld = false) else s)
        if (r.autoZone) return TrustUpdate(s.copy(zoneId = r.zoneId, zoneHeld = false))
        if (s.zoneHeld) return TrustUpdate(s) // already noticed
        val delta = (r.zoneOffsetMs - r.offsetIn(s.zoneId)).toLong()
        if (delta == 0L) return TrustUpdate(s.copy(zoneId = r.zoneId)) // same local time: harmless
        return TrustUpdate(
            s.copy(zoneHeld = true),
            ClockChange(ClockChangeKind.TIME_ZONE, delta, suspicious = true, fromZone = s.zoneId, toZone = r.zoneId),
        )
    }

    private fun anchorToSystem(s: TrustState, r: ClockReading) = s.copy(
        anchorWallMs = r.wallMs, anchorElapsedMs = r.elapsedMs, source = TimeSource.SYSTEM,
        checkpointWallMs = maxOf(s.checkpointWallMs, r.wallMs).let { cp ->
            // Android's own time also corrects a checkpoint that ran ahead (e.g. a wrong clock at boot).
            if (cp - r.wallMs > TOLERANCE_MS) r.wallMs else cp
        },
        offsetMs = 0L,
    )

    private fun manipulationOf(jumpMs: Long): ClockChange? = when {
        jumpMs > TOLERANCE_MS -> ClockChange(ClockChangeKind.FORWARD, jumpMs, suspicious = true)
        jumpMs < -TOLERANCE_MS -> ClockChange(ClockChangeKind.BACKWARD, jumpMs, suspicious = true)
        else -> null
    }

    /** Offset of [zoneId] from UTC at the reading's wall time, ms (0 for an unknown zone). */
    private fun ClockReading.offsetIn(zoneId: String): Int = runCatching {
        java.util.TimeZone.getTimeZone(java.time.ZoneId.of(zoneId)).getOffset(wallMs)
    }.getOrDefault(0)
}
