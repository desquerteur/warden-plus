package com.warden.blocker.gray

import com.warden.blocker.block.BlockProtection
import com.warden.blocker.block.Loosening
import com.warden.blocker.block.PendingChange
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.ProtectionMode
import com.warden.blocker.block.ProtectionSettings
import com.warden.blocker.block.ProtectionState
import com.warden.blocker.block.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GrayModeLogicTest {

    private val on = GrayModeConfig(enabled = true)
    private val always = on.copy(whenMode = GrayWhen.ALWAYS)
    private val blocking = on.copy(whenMode = GrayWhen.WHILE_BLOCKING)
    private val evening = on.copy(whenMode = GrayWhen.SCHEDULE, startMinute = 21 * 60, endMinute = 7 * 60)

    // --- When gray ---

    @Test fun offIsNeverGray() {
        GrayWhen.entries.forEach { w ->
            assertFalse(GrayModeLogic.shouldBeGray(GrayModeConfig(enabled = false, whenMode = w), blockingActive = true, minuteOfDay = 22 * 60))
        }
    }

    @Test fun alwaysIsGrayAllTheTime() {
        assertTrue(GrayModeLogic.shouldBeGray(always, blockingActive = false, minuteOfDay = 12 * 60))
    }

    @Test fun whileBlockingFollowsTheBlockingState() {
        assertTrue(GrayModeLogic.shouldBeGray(blocking, blockingActive = true, minuteOfDay = 12 * 60))
        assertFalse(GrayModeLogic.shouldBeGray(blocking, blockingActive = false, minuteOfDay = 12 * 60))
    }

    @Test fun scheduleRunsPastMidnight() {
        assertTrue(GrayModeLogic.shouldBeGray(evening, false, 21 * 60))
        assertTrue(GrayModeLogic.shouldBeGray(evening, false, 23 * 60 + 59))
        assertTrue(GrayModeLogic.shouldBeGray(evening, false, 0))
        assertTrue(GrayModeLogic.shouldBeGray(evening, false, 6 * 60 + 59))
        assertFalse("end is exclusive", GrayModeLogic.shouldBeGray(evening, false, 7 * 60))
        assertFalse(GrayModeLogic.shouldBeGray(evening, false, 20 * 60 + 59))
        assertFalse("blocking doesn't matter on a schedule", GrayModeLogic.shouldBeGray(evening, true, 12 * 60))
    }

    @Test fun windowsInsideADayAndAllDay() {
        assertTrue(GrayModeLogic.inWindow(9 * 60, 9 * 60, 17 * 60))
        assertFalse(GrayModeLogic.inWindow(17 * 60, 9 * 60, 17 * 60))
        assertFalse(GrayModeLogic.inWindow(8 * 60, 9 * 60, 17 * 60))
        assertTrue("equal start and end = all day", GrayModeLogic.inWindow(3 * 60, 600, 600))
    }

    // --- Loosening / tightening ---

    @Test fun switchingOnOrToAlwaysIsInstant() {
        assertFalse(GrayModeLogic.loosens(GrayModeConfig(enabled = false), blocking))
        assertFalse(GrayModeLogic.loosens(blocking, always))
        assertFalse(GrayModeLogic.loosens(evening, always))
        assertFalse("nothing changed", GrayModeLogic.loosens(evening, evening))
    }

    @Test fun switchingOffOrAwayFromAlwaysIsProtected() {
        assertTrue(GrayModeLogic.loosens(blocking, blocking.copy(enabled = false)))
        assertTrue(GrayModeLogic.loosens(always, blocking))
        assertTrue(GrayModeLogic.loosens(always, evening))
        assertTrue("blocking → schedule can leave blocks in colour", GrayModeLogic.loosens(blocking, evening))
        assertTrue(GrayModeLogic.loosens(evening, blocking))
    }

    @Test fun changesWhileOffAreFree() {
        val off = GrayModeConfig(enabled = false, whenMode = GrayWhen.ALWAYS)
        assertFalse(GrayModeLogic.loosens(off, off.copy(whenMode = GrayWhen.SCHEDULE, startMinute = 1, endMinute = 2)))
    }

    @Test fun aLongerWindowIsInstantAShorterOneProtected() {
        assertFalse("starts earlier", GrayModeLogic.loosens(evening, evening.copy(startMinute = 20 * 60)))
        assertFalse("ends later", GrayModeLogic.loosens(evening, evening.copy(endMinute = 8 * 60)))
        assertFalse("all day covers everything", GrayModeLogic.loosens(evening, evening.copy(startMinute = 0, endMinute = 0)))
        assertTrue("starts later", GrayModeLogic.loosens(evening, evening.copy(startMinute = 22 * 60)))
        assertTrue("ends sooner", GrayModeLogic.loosens(evening, evening.copy(endMinute = 6 * 60)))
        assertTrue("moved", GrayModeLogic.loosens(evening, evening.copy(startMinute = 9 * 60, endMinute = 17 * 60)))
    }

    @Test fun loosensFeedsBlockProtection() {
        val pin = ProtectionState(ProtectionSettings(ProtectionMode.PIN), hasPin = true)
        val off = ProtectionState(ProtectionSettings(ProtectionMode.OFF), hasPin = true)
        val change = ProtectedChange.SetGrayMode(blocking.copy(enabled = false))
        assertEquals(Loosening.WEAKEN_GRAY_MODE, change.kind)
        assertEquals(Verdict.NeedsPin, BlockProtection.check(change.kind, pin, 0L))
        assertEquals("only the main switch asks for the PIN in Off mode", Verdict.Allow, BlockProtection.check(change.kind, off, 0L))
        val delay = ProtectionState(ProtectionSettings(ProtectionMode.DELAY, delayMinutes = 30), hasPin = false)
        assertEquals(Verdict.Delayed(30 * 60_000L), BlockProtection.check(change.kind, delay, 0L))
    }

    @Test fun aQueuedGrayChangeSurvivesTheDelayQueue() {
        val queue = listOf(
            PendingChange(1, 100, ProtectedChange.SetGrayMode(evening)),
            PendingChange(2, 200, ProtectedChange.SetGrayMode(GrayModeConfig(enabled = false))),
        )
        assertEquals(queue, PendingChange.decode(PendingChange.encode(queue)))
        assertEquals("Gray mode will be switched off", queue[1].change.describe())
        assertEquals("Gray mode will cover less time", queue[0].change.describe())
    }

    // --- Next check ---

    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun nextEdgeOfTheWindow() {
        val noon = at(2026, 11, 5, 12, 0)
        assertEquals(at(2026, 11, 5, 21, 0), GrayModeLogic.nextWindowEdge(noon, zone, 21 * 60, 7 * 60))
        val night = at(2026, 11, 5, 23, 0)
        assertEquals(at(2026, 11, 6, 7, 0), GrayModeLogic.nextWindowEdge(night, zone, 21 * 60, 7 * 60))
        val exactlyAtStart = at(2026, 11, 5, 21, 0)
        assertEquals(at(2026, 11, 6, 7, 0), GrayModeLogic.nextWindowEdge(exactlyAtStart, zone, 21 * 60, 7 * 60))
        assertNull("all day never changes", GrayModeLogic.nextWindowEdge(noon, zone, 300, 300))
    }

    @Test fun earliestFutureCandidate() {
        assertEquals(50L, GrayModeLogic.earliestAfter(10L, listOf(null, 5L, 80L, 50L)))
        assertNull(GrayModeLogic.earliestAfter(10L, listOf(null, 0L, 10L)))
    }

    // --- Restoring the user's own colour correction ---

    private val never = DaltonizerState(null, null)
    private val userOff = DaltonizerState("0", "12")
    private val userRedGreen = DaltonizerState("1", "12")

    @Test fun turningOnRemembersWhatWasThere() {
        val s = GrayRestore.turnOn(userRedGreen, GrayMemory())
        assertEquals(DaltonizerState.GRAY, s.write)
        assertEquals(GrayMemory(applied = true, previous = userRedGreen), s.memory)
    }

    @Test fun reApplyingKeepsTheFirstMemory() {
        val memory = GrayMemory(applied = true, previous = userRedGreen)
        // Already gray: nothing to write.
        assertEquals(GraySwitch(null, memory), GrayRestore.turnOn(DaltonizerState.GRAY, memory))
        // Switched back to colour from outside while gray should be on: gray again, memory stays.
        assertEquals(GraySwitch(DaltonizerState.GRAY, memory), GrayRestore.turnOn(userOff, memory))
    }

    @Test fun turningOffPutsTheUsersFilterBack() {
        val memory = GrayMemory(applied = true, previous = userRedGreen)
        assertEquals(GraySwitch(userRedGreen, GrayMemory()), GrayRestore.turnOff(DaltonizerState.GRAY, memory))
    }

    @Test fun neverSetMeansOffAgain() {
        val memory = GrayRestore.turnOn(never, GrayMemory()).memory
        assertEquals(GraySwitch(DaltonizerState("0", null), GrayMemory()), GrayRestore.turnOff(DaltonizerState.GRAY, memory))
    }

    @Test fun theUsersOwnChangeInBetweenIsKept() {
        val memory = GrayMemory(applied = true, previous = userOff)
        // The user picked a red-green filter while Warden+'s gray was on: leave it.
        assertEquals(GraySwitch(null, GrayMemory()), GrayRestore.turnOff(userRedGreen, memory))
    }

    @Test fun grayBeforeStaysGray() {
        val memory = GrayRestore.turnOn(DaltonizerState.GRAY, GrayMemory()).memory
        assertEquals(GraySwitch(null, GrayMemory()), GrayRestore.turnOff(DaltonizerState.GRAY, memory))
    }

    @Test fun nothingToUndoWhenWardenDidntApplyIt() {
        assertEquals(GraySwitch(null, GrayMemory()), GrayRestore.turnOff(DaltonizerState.GRAY, GrayMemory()))
    }
}
