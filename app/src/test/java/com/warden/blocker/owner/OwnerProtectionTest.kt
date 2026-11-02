package com.warden.blocker.owner

import com.warden.blocker.block.Loosening
import com.warden.blocker.block.PendingChange
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.block.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Device owner under Block protection: rules off like any weakening, the exit never past 24 h. */
class OwnerProtectionTest {

    private val now = 1_700_000_000_000L
    private val h = 3_600_000L

    @Test fun exitFollowsBlockProtection() {
        assertEquals(0L, EmergencyExit.waitMillis(Verdict.Allow, now, pinBypassed = false))
        assertNull(EmergencyExit.waitMillis(Verdict.NeedsPin, now, pinBypassed = false)) // PIN first, then ready
        assertEquals(2 * h, EmergencyExit.waitMillis(Verdict.Delayed(now + 2 * h), now, pinBypassed = false))
        assertEquals(3 * h, EmergencyExit.waitMillis(Verdict.Locked(now + 3 * h, byFocus = false), now, pinBypassed = false))
        assertEquals(h, EmergencyExit.waitMillis(Verdict.Locked(now + h, byFocus = true), now, pinBypassed = false))
    }

    @Test fun exitNeverWaitsLongerThanADay() {
        val week = Verdict.Locked(now + 7 * 24 * h, byFocus = false)
        assertEquals(EmergencyExit.MAX_WAIT_MS, EmergencyExit.waitMillis(week, now, pinBypassed = false))
        assertEquals(EmergencyExit.MAX_WAIT_MS, EmergencyExit.waitMillis(Verdict.Delayed(now + 48 * h), now, pinBypassed = false))
    }

    @Test fun forgottenPinAlwaysWaitsTheFullDay() {
        listOf(Verdict.Allow, Verdict.NeedsPin, Verdict.Delayed(now + h)).forEach {
            assertEquals(EmergencyExit.MAX_WAIT_MS, EmergencyExit.waitMillis(it, now, pinBypassed = true))
        }
    }

    @Test fun changesSurviveTheDelayQueue() {
        val queue = OwnerPolicy.entries.mapIndexed { i, p -> PendingChange(i.toLong(), now + i, ProtectedChange.OwnerPolicyOff(p)) } +
            PendingChange(99, now, ProtectedChange.ConfirmOwnerExit)
        assertEquals(queue, PendingChange.decode(PendingChange.encode(queue)))
        assertEquals(Loosening.OWNER_POLICY_OFF, ProtectedChange.OwnerPolicyOff(OwnerPolicy.LOCK_DATE_TIME).kind)
        assertEquals(Loosening.REMOVE_DEVICE_OWNER, ProtectedChange.ConfirmOwnerExit.kind)
    }

    @Test fun describesForTheUser() {
        assertEquals("“Lock date & time” will be switched off", ProtectedChange.OwnerPolicyOff(OwnerPolicy.LOCK_DATE_TIME).describe())
    }
}
