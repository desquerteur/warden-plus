package com.warden.blocker.owner

import org.junit.Assert.assertEquals
import org.junit.Test

/** D3: "Always-on website filter" must be held by the device owner, not just look switched on. */
class AlwaysOnVpnTest {

    private fun action(want: Boolean, ours: Boolean, setByUs: Boolean) = OwnerPolicyPlanner.alwaysOnVpnAction(want, ours, setByUs)

    @Test fun setByHandIsTakenOverByTheDeviceOwner() {
        // The user had switched Always-on VPN on in Android's settings: it is ours, but not locked.
        assertEquals(AlwaysOnVpnAction.SET, action(want = true, ours = true, setByUs = false))
    }

    @Test fun setWhenMissing() {
        assertEquals(AlwaysOnVpnAction.SET, action(want = true, ours = false, setByUs = false))
        // Someone changed it although we had set it: set it again.
        assertEquals(AlwaysOnVpnAction.SET, action(want = true, ours = false, setByUs = true))
    }

    @Test fun nothingToDoOnceHeld() {
        assertEquals(AlwaysOnVpnAction.NONE, action(want = true, ours = true, setByUs = true))
        assertEquals(AlwaysOnVpnAction.NONE, action(want = false, ours = false, setByUs = false))
    }

    @Test fun clearedWhenSwitchedOffOrLeaving() {
        assertEquals(AlwaysOnVpnAction.CLEAR, action(want = false, ours = true, setByUs = true))
        assertEquals(AlwaysOnVpnAction.CLEAR, action(want = false, ours = true, setByUs = false))
        assertEquals(AlwaysOnVpnAction.CLEAR, action(want = false, ours = false, setByUs = true))
    }
}
