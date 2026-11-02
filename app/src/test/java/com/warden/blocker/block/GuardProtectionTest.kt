package com.warden.blocker.block

import org.junit.Assert.assertEquals
import org.junit.Test

/** Switching Settings guard off is a weakening like any other setting. */
class GuardProtectionTest {

    private val now = 1_700_000_000_000L
    private val off = ProtectedChange.SettingOff(ProtectedChange.Setting.SETTINGS_GUARD)
    private val dateTimeOff = ProtectedChange.SettingOff(ProtectedChange.Setting.GUARD_DATE_TIME)

    @Test fun survivesTheDelayQueue() {
        val queue = listOf(PendingChange(1, now, off), PendingChange(2, now + 1, dateTimeOff))
        assertEquals(queue, PendingChange.decode(PendingChange.encode(queue)))
    }

    @Test fun describesForTheUser() {
        assertEquals("“Settings guard” will be switched off", off.describe())
    }

    @Test fun followsBlockProtection() {
        fun verdict(mode: ProtectionMode) = BlockProtection.check(
            off.kind, ProtectionState(ProtectionSettings(mode, 20, now + 60_000L), hasPin = true), now,
        )
        assertEquals(Verdict.Allow, verdict(ProtectionMode.OFF))
        assertEquals(Verdict.NeedsPin, verdict(ProtectionMode.PIN))
        assertEquals(Verdict.Delayed(now + 20 * 60_000L), verdict(ProtectionMode.DELAY))
        assertEquals(Verdict.Locked(now + 60_000L, byFocus = false), verdict(ProtectionMode.LOCKED))
    }
}
