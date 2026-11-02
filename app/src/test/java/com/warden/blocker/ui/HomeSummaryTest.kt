package com.warden.blocker.ui

import com.warden.blocker.hosts.HostsSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSummaryTest {

    private fun list(domains: Int, enabled: Boolean = true) =
        HostsSource(id = "id$domains", name = "List", url = null, enabled = enabled, domainCount = domains)

    private fun n(value: Long) = "%,d".format(value)

    @Test fun itemsOnly() {
        assertEquals("3 items protected", protectedSummary(3, emptyList()))
        assertEquals("1 item protected", protectedSummary(1, emptyList()))
        assertEquals("0 items protected", protectedSummary(0, emptyList()))
    }

    @Test fun enabledListsAreCounted() {
        assertEquals("3 items · ${n(77_758)} domains", protectedSummary(3, listOf(list(77_758))))
        assertEquals("1 item · ${n(1_100)} domains", protectedSummary(1, listOf(list(1_000), list(100))))
    }

    @Test fun disabledOrEmptyListsAreIgnored() {
        assertEquals("2 items protected", protectedSummary(2, listOf(list(5_000, enabled = false), list(0))))
    }

    @Test fun listsWithoutItems() {
        assertEquals("${n(77_758)} domains from lists", protectedSummary(0, listOf(list(77_758))))
    }

    @Test fun statusEverythingOn() =
        assertEquals("Apps: on · Websites: on", protectionStatus(appsOn = true, websitesOn = true, otherVpn = false))

    @Test fun statusOtherVpnBlocksWebsitesOnly() =
        assertEquals(
            "Apps: on · Websites: off (another VPN is active)",
            protectionStatus(appsOn = true, websitesOn = false, otherVpn = true),
        )

    @Test fun statusRunningTunnelWinsOverStaleOtherVpnFlag() =
        assertEquals("Apps: off · Websites: on", protectionStatus(appsOn = false, websitesOn = true, otherVpn = true))

    @Test fun statusBothOff() =
        assertEquals("Apps: off · Websites: off", protectionStatus(appsOn = false, websitesOn = false, otherVpn = false))

    @Test fun disconnectedIsNotBlamedOnAnotherVpn() {
        // D6: removing device owner (or "Disconnect" in VPN settings) takes our tunnel away; no other VPN.
        assertEquals(FilterProblem.DISCONNECTED, filterProblem(protecting = true, running = false, refused = false, revoked = true, otherVpnActive = false))
        assertEquals("Apps: on · Websites: off (disconnected)", protectionStatus(appsOn = true, websitesOn = false, otherVpn = false, disconnected = true))
    }

    @Test fun anotherVpnIsNamedOnlyWhenItIsThere() {
        assertEquals(FilterProblem.OTHER_VPN, filterProblem(protecting = true, running = false, refused = false, revoked = true, otherVpnActive = true))
        // Our consent dialog closed at once: what Android does when another app holds Always-on VPN.
        assertEquals(FilterProblem.OTHER_VPN, filterProblem(protecting = true, running = false, refused = true, revoked = false, otherVpnActive = false))
        assertEquals(FilterProblem.PAUSED, filterProblem(protecting = true, running = false, refused = false, revoked = false, otherVpnActive = false))
        assertEquals(FilterProblem.NONE, filterProblem(protecting = true, running = true, refused = true, revoked = true, otherVpnActive = true))
        assertEquals(FilterProblem.NONE, filterProblem(protecting = false, running = false, refused = false, revoked = true, otherVpnActive = false))
    }
}
