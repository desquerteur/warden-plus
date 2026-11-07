package com.warden.blocker.time

import com.warden.blocker.block.BlockProtection
import com.warden.blocker.block.Loosening
import com.warden.blocker.block.ProtectionMode
import com.warden.blocker.block.ProtectionSettings
import com.warden.blocker.block.ProtectionState
import com.warden.blocker.block.TimeWindows
import com.warden.blocker.block.Verdict
import com.warden.blocker.data.Schedule
import com.warden.blocker.guard.ClockChangeKind
import com.warden.blocker.guard.ClockReading
import com.warden.blocker.owner.EmergencyExit
import com.warden.blocker.owner.ExitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

/**
 * A pretend phone: real time passes for everyone, the phone's clock can be set, it can reboot.
 * The app's [state] is driven exactly like TrustedClock does it.
 */
private class FakePhone(startReal: Long, var autoTime: Boolean = false, var autoZone: Boolean = true, var zone: String = "Europe/Berlin") {
    var real = startReal          // the real time (what the world agrees on)
    var wall = startReal          // the phone's clock
    var elapsed = 10 * MIN        // time since boot
    var boot = 7
    var state: TrustState = TrustedTime.restore(null, reading())
    val manipulations = mutableListOf<ClockChangeKind>()

    fun reading() = ClockReading(
        wallMs = wall, elapsedMs = elapsed, bootCount = boot, zoneId = zone,
        zoneOffsetMs = TimeZone.getTimeZone(ZoneId.of(zone)).getOffset(wall), autoTime = autoTime, autoZone = autoZone,
    )

    val trusted: Long get() = TrustedTime.now(state, elapsed)

    /** Time passes; the app looks every minute (observe + zone + checkpoint). */
    fun pass(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(MIN, left)
            real += step; wall += step; elapsed += step; left -= step
            tick()
        }
    }

    fun tick() {
        TrustedTime.observe(state, reading()).let { state = it.state; it.manipulation?.let { m -> manipulations += m.kind } }
        TrustedTime.onZone(state, reading()).let { state = it.state; it.manipulation?.let { m -> manipulations += m.kind } }
        state = TrustedTime.checkpoint(state, reading())
    }

    /** The user sets the clock (Android sends ACTION_TIME_CHANGED). */
    fun setClock(newWall: Long) {
        wall = newWall
        TrustedTime.onTimeSet(state, reading()).let { state = it.state; it.manipulation?.let { m -> manipulations += m.kind } }
        state = TrustedTime.checkpoint(state, reading())
    }

    /** Android sets the clock itself (automatic time on). */
    fun androidSetsClock(newWall: Long) {
        check(autoTime)
        setClock(newWall)
    }

    fun changeZone(newZone: String) {
        zone = newZone
        TrustedTime.onZone(state, reading()).let { state = it.state; it.manipulation?.let { m -> manipulations += m.kind } }
    }

    /** Switched off for [offMs]; the phone's clock (RTC) runs on meanwhile, the time since boot starts again. */
    fun reboot(offMs: Long) {
        real += offMs; wall += offMs
        boot += 1
        elapsed = 30_000L
        state = TrustedTime.restore(state, reading())
        tick()
    }

    fun network() {
        state = TrustedTime.onNetworkTime(state, reading(), real, elapsed)
    }

    fun zoneId(): ZoneId = ZoneId.of(TrustedTime.zone(state, reading()))
}

class TrustedTimeTest {

    private val start = LocalDateTime.of(2026, 11, 7, 14, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
    private lateinit var phone: FakePhone

    @Before fun setUp() { phone = FakePhone(start) }

    // --- Focus session (a running duration) ---

    @Test fun focusKeepsRunningWhenClockIsSetForward() {
        val focusEndsAt = phone.trusted + 10 * MIN
        phone.pass(2 * MIN)
        phone.setClock(phone.wall + HOUR)
        assertTrue("still in focus", focusEndsAt > phone.trusted)
        assertEquals(phone.real, phone.trusted)
        assertEquals(listOf(ClockChangeKind.FORWARD), phone.manipulations)
        phone.pass(7 * MIN)
        assertTrue(focusEndsAt > phone.trusted)
        phone.pass(2 * MIN)
        assertFalse("ends on the real time", focusEndsAt > phone.trusted)
    }

    @Test fun strictFocusCantBeEndedBySettingTheClock() {
        val state = ProtectionState(ProtectionSettings(), hasPin = false, focusEndsAt = phone.trusted + 10 * MIN, focusStrict = true)
        phone.setClock(phone.wall + DAY)
        assertTrue(BlockProtection.check(Loosening.END_FOCUS, state, phone.trusted) is Verdict.Locked)
    }

    @Test fun settingTheClockBackDoesntMakeAFocusLonger() {
        val focusEndsAt = phone.trusted + 10 * MIN
        phone.setClock(phone.wall - 3 * HOUR)
        assertEquals(listOf(ClockChangeKind.BACKWARD), phone.manipulations)
        phone.pass(10 * MIN)
        assertFalse(focusEndsAt > phone.trusted)
    }

    @Test fun smallJumpsAreNeitherFollowedNorReported() {
        val focusEndsAt = phone.trusted + 10 * MIN
        repeat(5) { phone.setClock(phone.wall + 90_000L) } // 7.5 min in 1.5-min steps
        assertTrue(phone.manipulations.isEmpty())
        assertEquals(phone.real, phone.trusted)
        assertTrue(focusEndsAt > phone.trusted + 9 * MIN)
    }

    // --- "Block until", Locked until, Delay ---

    @Test fun blockUntilStaysWhenClockIsSetPastIt() {
        val until = phone.trusted + 5 * MIN
        phone.setClock(phone.wall + 2 * HOUR)
        assertTrue(TimeWindows.inEffect(null, until, phone.trusted))
        phone.pass(5 * MIN)
        assertFalse(TimeWindows.inEffect(null, until, phone.trusted))
    }

    @Test fun lockedUntilHoldsAgainstClockForward() {
        val p = ProtectionSettings(ProtectionMode.LOCKED, lockedUntil = phone.trusted + DAY)
        phone.setClock(phone.wall + 2 * DAY)
        val v = BlockProtection.check(Loosening.TURN_OFF_BLOCKING, ProtectionState(p, hasPin = false), phone.trusted)
        assertTrue(v is Verdict.Locked)
    }

    @Test fun delayWaitDoesntRunOutByClockForward() {
        val p = ProtectionSettings(ProtectionMode.DELAY, delayMinutes = 15)
        val v = BlockProtection.check(Loosening.TURN_OFF_BLOCKING, ProtectionState(p, hasPin = false), phone.trusted)
        val dueAt = (v as Verdict.Delayed).at
        phone.setClock(phone.wall + HOUR)
        assertTrue("not due yet", dueAt > phone.trusted)
        phone.pass(15 * MIN)
        assertFalse(dueAt > phone.trusted)
    }

    @Test fun emergencyExitWaitIsntSkipped() {
        val readyAt = EmergencyExit.readyAt(phone.trusted, EmergencyExit.MAX_WAIT_MS)
        phone.setClock(phone.wall + 2 * DAY)
        assertTrue(EmergencyExit.status(readyAt, phone.trusted) is ExitStatus.Waiting)
    }

    // --- Schedules and time zones ---

    @Test fun scheduleStaysActiveWhenClockIsSetPastItsEnd() {
        // 14:00–16:00 every day.
        val s = Schedule(name = "afternoon", daysMask = 0x7F, startMinute = 14 * 60, endMinute = 16 * 60)
        phone.pass(30 * MIN)
        phone.setClock(phone.wall + 3 * HOUR)
        assertTrue(TimeWindows.isActive(s, phone.trusted, phone.zoneId()))
        phone.pass(90 * MIN)
        assertFalse(TimeWindows.isActive(s, phone.trusted, phone.zoneId()))
    }

    @Test fun manualZoneChangeIsNotFollowed() {
        phone.autoZone = false
        val s = Schedule(name = "afternoon", daysMask = 0x7F, startMinute = 14 * 60, endMinute = 16 * 60)
        phone.pass(30 * MIN) // 14:30 in Berlin
        phone.changeZone("America/New_York") // 08:30 there: would be outside the schedule
        assertEquals(listOf(ClockChangeKind.TIME_ZONE), phone.manipulations)
        assertEquals(ZoneId.of("Europe/Berlin"), phone.zoneId())
        assertTrue(TimeWindows.isActive(s, phone.trusted, phone.zoneId()))
        phone.pass(5 * MIN) // regular looks don't report it again nor follow it
        assertEquals(1, phone.manipulations.size)
        assertEquals(ZoneId.of("Europe/Berlin"), phone.zoneId())
    }

    @Test fun travellingWithAutomaticZoneShiftsSchedules() {
        phone.autoZone = true
        phone.changeZone("America/New_York")
        assertTrue(phone.manipulations.isEmpty())
        assertEquals(ZoneId.of("America/New_York"), phone.zoneId())
    }

    @Test fun heldZoneIsReleasedWhenAndroidSetsTheZoneAgain() {
        phone.autoZone = false
        phone.changeZone("Asia/Tokyo")
        phone.autoZone = true
        phone.changeZone("Europe/Berlin") // automatic zone back on: Android sets the real one
        assertFalse(phone.state.zoneHeld)
        assertEquals(ZoneId.of("Europe/Berlin"), phone.zoneId())
    }

    @Test fun zoneWithSameOffsetIsHarmless() {
        phone.autoZone = false
        phone.changeZone("Europe/Paris")
        assertTrue(phone.manipulations.isEmpty())
        assertFalse(phone.state.zoneHeld)
    }

    // --- Automatic time ---

    @Test fun automaticTimeIsFollowed() {
        phone.autoTime = true
        phone.pass(MIN)
        // Android corrects a clock that ran 5 min fast (network time): followed, nothing reported.
        phone.wall += 5 * MIN
        phone.androidSetsClock(phone.wall)
        assertTrue(phone.manipulations.isEmpty())
        assertEquals(phone.wall, phone.trusted)
    }

    @Test fun automaticTimeBackOnWithoutNetworkDoesNotAdoptTheSetClock() {
        phone.setClock(phone.wall + DAY)            // by hand, automatic time off
        phone.autoTime = true                        // switched on in flight mode: Android changes nothing
        phone.pass(10 * MIN)
        assertEquals(phone.real, phone.trusted)
        assertEquals(1, phone.manipulations.size)    // the regular looks don't count it again
    }

    @Test fun missedBroadcastIsCaughtByTheRegularLook() {
        phone.wall += 3 * HOUR // e.g. set while the app's process wasn't running
        phone.tick()
        assertEquals(listOf(ClockChangeKind.FORWARD), phone.manipulations)
        assertEquals(phone.real, phone.trusted)
    }

    // --- Reboots ---

    @Test fun rebootWithoutNetworkNeverGoesBackwards() {
        phone.pass(30 * MIN)
        val checkpoint = phone.state.checkpointWallMs
        // The phone's clock is wrong after the reboot (dead RTC battery, or set back by hand before).
        phone.real += MIN; phone.boot += 1; phone.elapsed = 30_000L
        phone.wall = start - 5 * DAY
        phone.state = TrustedTime.restore(phone.state, phone.reading())
        assertTrue(phone.trusted >= checkpoint)
    }

    @Test fun rebootKeepsTheKnownOffsetOfASetClock() {
        val focusEndsAt = phone.trusted + 2 * HOUR
        phone.setClock(phone.wall + DAY)
        phone.reboot(offMs = 2 * MIN)
        // Still a day ahead after the reboot; the trusted time knows it.
        assertEquals(phone.real.toDouble(), phone.trusted.toDouble(), 60_000.0)
        assertTrue(focusEndsAt > phone.trusted)
    }

    @Test fun rebootWithNetworkSettlesIt() {
        val until = phone.trusted + HOUR
        phone.reboot(offMs = 10 * MIN)
        phone.wall += 3 * DAY // set by hand right after the reboot, before the app looked
        phone.state = TrustedTime.restore(phone.state, phone.reading())
        assertTrue(TrustedTime.wantsNetwork(phone.state, phone.reading(), 6 * HOUR))
        phone.network()
        assertEquals(phone.real, phone.trusted)
        assertTrue(TimeWindows.inEffect(null, until, phone.trusted))
        assertEquals(TimeSource.NETWORK, phone.state.source)
    }

    @Test fun rebootWithAutomaticTimeAndRightClockUsesTheClock() {
        phone.autoTime = true
        phone.pass(MIN)
        phone.reboot(offMs = 8 * HOUR)
        assertEquals(phone.real, phone.trusted)
        assertEquals(TimeSource.SYSTEM, phone.state.source)
        assertFalse(TrustedTime.wantsNetwork(phone.state, phone.reading(), 6 * HOUR))
    }

    @Test fun offTimeCountsAsRealTime() {
        val focusEndsAt = phone.trusted + 30 * MIN
        phone.autoTime = true
        phone.reboot(offMs = HOUR)
        phone.network()
        assertFalse("the phone was off for longer than the session", focusEndsAt > phone.trusted)
    }

    // --- Checkpoint ---

    @Test fun checkpointNeverMovesBackwards() {
        phone.pass(10 * MIN)
        val cp = phone.state.checkpointWallMs
        phone.setClock(phone.wall - 2 * DAY)
        phone.pass(MIN)
        assertTrue(phone.state.checkpointWallMs >= cp)
        val cp2 = phone.state.checkpointWallMs
        phone.state = TrustedTime.checkpoint(phone.state, phone.reading())
        assertTrue(phone.state.checkpointWallMs >= cp2)
    }

    @Test fun networkMayCorrectAWrongCheckpointDownwards() {
        // After a reboot with a clock far ahead (and no knowledge of it), the network sets it right.
        phone.boot += 1; phone.elapsed = 30_000L
        phone.wall = phone.real + 10 * DAY
        phone.state = TrustedTime.restore(phone.state, phone.reading())
        phone.network()
        assertEquals(phone.real, phone.trusted)
        assertEquals(phone.real, phone.state.checkpointWallMs)
    }

    // --- Network wanted? ---

    @Test fun networkIsAskedOnlyWhileTheClockCantBeTrusted() {
        phone.autoTime = true
        phone.tick()
        assertFalse(TrustedTime.wantsNetwork(phone.state, phone.reading(), 6 * HOUR))
        phone.autoTime = false
        assertTrue(TrustedTime.wantsNetwork(phone.state, phone.reading(), 6 * HOUR))
        phone.network()
        assertFalse(TrustedTime.wantsNetwork(phone.state, phone.reading(), 6 * HOUR))
        phone.pass(6 * HOUR)
        assertTrue("refresh after a while", TrustedTime.wantsNetwork(phone.state, phone.reading(), 6 * HOUR))
    }

    @Test fun freshStateStartsFromTheClock() {
        val r = phone.reading()
        val s = TrustedTime.restore(null, r)
        assertEquals(r.wallMs, TrustedTime.now(s, r.elapsedMs))
        assertNull(TrustedTime.onTimeSet(s, r).manipulation)
        assertNotNull(TrustedTime.onTimeSet(s, r.copy(wallMs = r.wallMs + HOUR)).manipulation)
    }
}
