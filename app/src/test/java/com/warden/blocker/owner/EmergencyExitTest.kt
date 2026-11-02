package com.warden.blocker.owner

import com.warden.blocker.owner.EmergencyExit.MAX_WAIT_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class EmergencyExitTest {

    private val now = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000

    @Test fun allowedWithoutWaitIsImmediate() {
        assertEquals(0L, EmergencyExit.waitMillis(looseningAllowed = true, protectionWaitMs = 0, pinBypassed = false))
    }

    @Test fun protectionDelayIsKept() {
        assertEquals(2 * hour, EmergencyExit.waitMillis(true, 2 * hour, false))
    }

    @Test fun longLockIsCappedAt24Hours() {
        // "Locked until" next week still lets the user out after a day.
        assertEquals(MAX_WAIT_MS, EmergencyExit.waitMillis(true, 7 * 24 * hour, false))
    }

    @Test fun refusedLooseningWaitsTheMaximum() {
        assertEquals(MAX_WAIT_MS, EmergencyExit.waitMillis(looseningAllowed = false, protectionWaitMs = 0, pinBypassed = false))
    }

    @Test fun forgottenPinWaitsTheMaximum() {
        assertEquals(MAX_WAIT_MS, EmergencyExit.waitMillis(true, 0, pinBypassed = true))
    }

    @Test fun negativeWaitIsZero() {
        assertEquals(0L, EmergencyExit.waitMillis(true, -5, false))
    }

    @Test fun readyAtNeverBeyondMaximum() {
        assertEquals(now + MAX_WAIT_MS, EmergencyExit.readyAt(now, 3 * MAX_WAIT_MS))
    }

    @Test fun statusFollowsTheClock() {
        assertEquals(ExitStatus.NotRequested, EmergencyExit.status(0L, now))
        assertEquals(ExitStatus.Waiting(now + hour), EmergencyExit.status(now + hour, now))
        assertEquals(ExitStatus.Ready, EmergencyExit.status(now + hour, now + hour))
        assertEquals(ExitStatus.Ready, EmergencyExit.status(now - 1, now))
    }

    @Test fun fullWaitIsStillWaiting() {
        assertEquals(ExitStatus.Waiting(now + MAX_WAIT_MS), EmergencyExit.status(now + MAX_WAIT_MS, now))
    }

    @Test fun clockSetBackNeitherExtendsNorSkipsTheWait() {
        // Requested with a full day to go, then the clock is turned back two days.
        val readyAt = now + MAX_WAIT_MS
        val setBack = now - 2 * MAX_WAIT_MS
        assertEquals(setBack + MAX_WAIT_MS, EmergencyExit.normalizedReadyAt(readyAt, setBack))
        assertEquals(ExitStatus.Waiting(setBack + MAX_WAIT_MS), EmergencyExit.status(readyAt, setBack))
    }

    @Test fun normalizedLeavesNormalRequestsAlone() {
        assertEquals(0L, EmergencyExit.normalizedReadyAt(0L, now))
        assertEquals(now + hour, EmergencyExit.normalizedReadyAt(now + hour, now))
    }
}
