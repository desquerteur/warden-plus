package com.warden.blocker.block

import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockProtectionTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun state(
        mode: ProtectionMode = ProtectionMode.OFF,
        delayMinutes: Int = 60,
        lockedUntil: Long = 0L,
        hasPin: Boolean = false,
        focusEndsAt: Long = 0L,
        focusStrict: Boolean = false,
    ) = ProtectionState(ProtectionSettings(mode, delayMinutes, lockedUntil), hasPin, focusEndsAt, focusStrict)

    private fun every(s: ProtectionState, expected: Verdict) =
        Loosening.entries.forEach { assertEquals(it.name, expected, BlockProtection.check(it, s, now)) }

    // --- mode × change ---

    @Test fun offAllowsEverything() = every(state(), Verdict.Allow)

    @Test fun offWithPinStillAsksToStopBlocking() {
        val s = state(hasPin = true)
        assertEquals(Verdict.NeedsPin, BlockProtection.check(Loosening.TURN_OFF_BLOCKING, s, now))
        Loosening.entries.filter { it != Loosening.TURN_OFF_BLOCKING }
            .forEach { assertEquals(it.name, Verdict.Allow, BlockProtection.check(it, s, now)) }
    }

    @Test fun pinModeAsksForEveryWeakening() = every(state(ProtectionMode.PIN, hasPin = true), Verdict.NeedsPin)

    @Test fun pinModeWithoutAPinProtectsNothing() = every(state(ProtectionMode.PIN, hasPin = false), Verdict.Allow)

    @Test fun delayModeQueuesEveryWeakening() {
        every(state(ProtectionMode.DELAY, delayMinutes = 15), Verdict.Delayed(now + 15 * 60_000L))
        every(state(ProtectionMode.DELAY, delayMinutes = 24 * 60), Verdict.Delayed(now + 24 * hour))
    }

    @Test fun lockedRefusesEverythingUntilTheEnd() {
        val until = now + 5 * hour
        every(state(ProtectionMode.LOCKED, lockedUntil = until, hasPin = true), Verdict.Locked(until, byFocus = false))
    }

    // --- time ---

    @Test fun anExpiredLockIsOff() {
        every(state(ProtectionMode.LOCKED, lockedUntil = now), Verdict.Allow)
        every(state(ProtectionMode.LOCKED, lockedUntil = now - 1), Verdict.Allow)
        assertEquals(ProtectionMode.OFF, BlockProtection.effectiveMode(ProtectionSettings(ProtectionMode.LOCKED, 60, now), now))
    }

    @Test fun strictFocusLocksUntilTheSessionEndsWhateverTheMode() {
        val ends = now + 25 * 60_000L
        ProtectionMode.entries.forEach { mode ->
            every(state(mode, lockedUntil = now + hour, hasPin = true, focusEndsAt = ends, focusStrict = true), Verdict.Locked(ends, byFocus = true))
        }
    }

    @Test fun aNormalFocusSessionChangesNothing() {
        every(state(focusEndsAt = now + hour, focusStrict = false), Verdict.Allow)
        every(state(ProtectionMode.DELAY, focusEndsAt = now + hour), Verdict.Delayed(now + hour))
    }

    @Test fun anEndedStrictFocusChangesNothing() = every(state(focusEndsAt = now, focusStrict = true), Verdict.Allow)

    // --- changing the protection itself ---

    private fun p(mode: ProtectionMode, delay: Int = 60, until: Long = 0L) = ProtectionSettings(mode, delay, until)

    @Test fun fromOffEverythingIsTightening() {
        ProtectionMode.entries.forEach { assertFalse(it.name, BlockProtection.protectionChangeLoosens(p(ProtectionMode.OFF), p(it, until = now + hour), now)) }
    }

    @Test fun droppingOrSwitchingASafeguardLoosens() {
        assertTrue(BlockProtection.protectionChangeLoosens(p(ProtectionMode.PIN), p(ProtectionMode.OFF), now))
        assertTrue(BlockProtection.protectionChangeLoosens(p(ProtectionMode.DELAY), p(ProtectionMode.OFF), now))
        assertTrue(BlockProtection.protectionChangeLoosens(p(ProtectionMode.PIN), p(ProtectionMode.DELAY), now))
        assertTrue(BlockProtection.protectionChangeLoosens(p(ProtectionMode.DELAY), p(ProtectionMode.PIN), now))
        assertTrue(BlockProtection.protectionChangeLoosens(p(ProtectionMode.LOCKED, until = now + hour), p(ProtectionMode.PIN), now))
    }

    @Test fun waitsAndLocks() {
        assertTrue(BlockProtection.protectionChangeLoosens(p(ProtectionMode.DELAY, 60), p(ProtectionMode.DELAY, 15), now))
        assertFalse(BlockProtection.protectionChangeLoosens(p(ProtectionMode.DELAY, 15), p(ProtectionMode.DELAY, 60), now))
        assertFalse(BlockProtection.protectionChangeLoosens(p(ProtectionMode.PIN), p(ProtectionMode.LOCKED, until = now + hour), now))
        val lock = p(ProtectionMode.LOCKED, until = now + 2 * hour)
        assertTrue(BlockProtection.protectionChangeLoosens(lock, p(ProtectionMode.LOCKED, until = now + hour), now))
        assertFalse(BlockProtection.protectionChangeLoosens(lock, p(ProtectionMode.LOCKED, until = now + 3 * hour), now))
        // A lock that already ran out is off: anything goes.
        assertFalse(BlockProtection.protectionChangeLoosens(p(ProtectionMode.LOCKED, until = now - 1), p(ProtectionMode.OFF), now))
    }

    @Test fun locksAreCappedAtThirtyDays() {
        assertEquals(now + 30L * 24 * hour, BlockProtection.maxLockedUntil(now))
    }

    // --- what counts as weakening an entry ---

    private val pauseApp = BlockedItem(
        id = 1, type = BlockType.APP, value = "com.example", label = "Example",
        interceptMode = InterceptMode.PAUSE, pauseSeconds = 15,
        openLimitPerDay = 3, dailyLimitMinutes = 30, cooldownMinutes = 10,
    )

    @Test fun hardBlockToPauseLoosens() {
        val hard = pauseApp.copy(interceptMode = InterceptMode.BLOCK)
        assertTrue(BlockProtection.itemEditLoosens(hard, hard.copy(interceptMode = InterceptMode.PAUSE)))
        assertFalse(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(interceptMode = InterceptMode.BLOCK)))
    }

    @Test fun looserLimitsLoosen() {
        assertTrue(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(pauseSeconds = 10)))
        assertTrue(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(openLimitPerDay = 5)))
        assertTrue(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(openLimitPerDay = 0))) // 0 = unlimited
        assertTrue(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(dailyLimitMinutes = 0)))
        assertTrue(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(cooldownMinutes = 5)))
        assertTrue(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(enabled = false)))
    }

    @Test fun stricterOrEqualEditsDoNotLoosen() {
        assertFalse(BlockProtection.itemEditLoosens(pauseApp, pauseApp))
        assertFalse(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(pauseSeconds = 30, openLimitPerDay = 2, cooldownMinutes = 20)))
        assertFalse(BlockProtection.itemEditLoosens(pauseApp, pauseApp.copy(customPrompt = "Really?")))
        val unlimited = pauseApp.copy(openLimitPerDay = 0)
        assertFalse(BlockProtection.itemEditLoosens(unlimited, unlimited.copy(openLimitPerDay = 4))) // adding a limit
        val off = pauseApp.copy(enabled = false)
        assertFalse(BlockProtection.itemEditLoosens(off, off.copy(enabled = true)))
    }

    private val work = Schedule(id = 1, name = "Work", daysMask = 0b0011111, startMinute = 9 * 60, endMinute = 17 * 60)

    @Test fun scheduleEdits() {
        assertTrue(BlockProtection.scheduleEditLoosens(work, work.copy(enabled = false)))
        assertTrue(BlockProtection.scheduleEditLoosens(work, work.copy(daysMask = 0b0001111))) // Friday dropped
        assertTrue(BlockProtection.scheduleEditLoosens(work, work.copy(startMinute = 10 * 60)))
        assertTrue(BlockProtection.scheduleEditLoosens(work, work.copy(endMinute = 16 * 60)))
        assertFalse(BlockProtection.scheduleEditLoosens(work, work.copy(name = "Office")))
        assertFalse(BlockProtection.scheduleEditLoosens(work, work.copy(daysMask = 0b1111111))) // weekend added
        val off = work.copy(enabled = false)
        assertFalse(BlockProtection.scheduleEditLoosens(off, off.copy(enabled = true)))
    }
}
