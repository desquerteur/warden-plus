package com.warden.blocker.block

import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.data.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictLockTest {

    private val now = 1_000_000L
    private val endsAt = now + 25 * 60_000L

    private fun state(
        focusEndsAt: Long = 0L,
        focusStrict: Boolean = false,
        strictMode: Boolean = false,
        blockingActiveNow: Boolean = false,
    ) = StrictLockState(focusEndsAt, focusStrict, strictMode, blockingActiveNow)

    @Test fun strictFocusRefusesEveryLoosening() {
        val s = state(focusEndsAt = endsAt, focusStrict = true)
        Loosening.entries.forEach { change ->
            assertEquals(change.name, StrictLockReason.StrictFocus(endsAt), StrictLock.check(change, s, now))
        }
    }

    @Test fun normalFocusRefusesNothing() {
        val s = state(focusEndsAt = endsAt, focusStrict = false, blockingActiveNow = true)
        Loosening.entries.forEach { assertNull(it.name, StrictLock.check(it, s, now)) }
    }

    @Test fun strictFocusThatHasEndedRefusesNothing() {
        val s = state(focusEndsAt = now, focusStrict = true)
        Loosening.entries.forEach { assertNull(it.name, StrictLock.check(it, s, now)) }
    }

    @Test fun nothingRunningRefusesNothing() {
        Loosening.entries.forEach { assertNull(it.name, StrictLock.check(it, state(), now)) }
    }

    @Test fun strictModeLocksOnlyTheMainSwitchWhileBlocking() {
        val blocking = state(strictMode = true, blockingActiveNow = true)
        assertEquals(StrictLockReason.StrictMode, StrictLock.check(Loosening.TURN_OFF_BLOCKING, blocking, now))
        assertNull(StrictLock.check(Loosening.REMOVE_ITEM, blocking, now))
        assertNull(StrictLock.check(Loosening.SETTING_OFF, blocking, now)) // the way out: Settings (PIN)
    }

    @Test fun strictModeOutsideABlockLetsTheSwitchGo() {
        assertNull(StrictLock.check(Loosening.TURN_OFF_BLOCKING, state(strictMode = true), now))
    }

    @Test fun strictFocusWinsOverStrictMode() {
        val s = state(focusEndsAt = endsAt, focusStrict = true, strictMode = true, blockingActiveNow = true)
        assertEquals(StrictLockReason.StrictFocus(endsAt), StrictLock.check(Loosening.TURN_OFF_BLOCKING, s, now))
    }

    // --- item edits ---

    private val pauseApp = BlockedItem(
        id = 1, type = BlockType.APP, value = "com.example", label = "Example",
        interceptMode = InterceptMode.PAUSE, pauseSeconds = 15,
        openLimitPerDay = 3, dailyLimitMinutes = 30, cooldownMinutes = 10,
    )

    @Test fun hardBlockToPauseLoosens() {
        val hard = pauseApp.copy(interceptMode = InterceptMode.BLOCK)
        assertTrue(StrictLock.itemEditLoosens(hard, hard.copy(interceptMode = InterceptMode.PAUSE)))
        assertFalse(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(interceptMode = InterceptMode.BLOCK)))
    }

    @Test fun looserLimitsLoosen() {
        assertTrue(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(pauseSeconds = 10)))
        assertTrue(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(openLimitPerDay = 5)))
        assertTrue(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(openLimitPerDay = 0))) // 0 = unlimited
        assertTrue(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(dailyLimitMinutes = 0)))
        assertTrue(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(cooldownMinutes = 5)))
        assertTrue(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(enabled = false)))
    }

    @Test fun stricterOrEqualEditsDoNotLoosen() {
        assertFalse(StrictLock.itemEditLoosens(pauseApp, pauseApp))
        assertFalse(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(pauseSeconds = 30, openLimitPerDay = 2, cooldownMinutes = 20)))
        assertFalse(StrictLock.itemEditLoosens(pauseApp, pauseApp.copy(customPrompt = "Really?")))
        val unlimited = pauseApp.copy(openLimitPerDay = 0)
        assertFalse(StrictLock.itemEditLoosens(unlimited, unlimited.copy(openLimitPerDay = 4))) // adding a limit
    }

    // --- schedule edits ---

    private val work = Schedule(id = 1, name = "Work", daysMask = 0b0011111, startMinute = 9 * 60, endMinute = 17 * 60)

    @Test fun scheduleEdits() {
        assertTrue(StrictLock.scheduleEditLoosens(work, work.copy(enabled = false)))
        assertTrue(StrictLock.scheduleEditLoosens(work, work.copy(daysMask = 0b0001111))) // Friday dropped
        assertTrue(StrictLock.scheduleEditLoosens(work, work.copy(startMinute = 10 * 60)))
        assertTrue(StrictLock.scheduleEditLoosens(work, work.copy(endMinute = 16 * 60)))
        assertFalse(StrictLock.scheduleEditLoosens(work, work.copy(name = "Office")))
        assertFalse(StrictLock.scheduleEditLoosens(work, work.copy(daysMask = 0b1111111))) // weekend added
        val off = work.copy(enabled = false)
        assertFalse(StrictLock.scheduleEditLoosens(off, off.copy(enabled = true)))
    }
}
