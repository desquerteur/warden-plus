package com.warden.blocker.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnGuardLogicTest {

    @Test fun onlyHostnameModeBypassesTheFilter() {
        assertTrue(PrivateDns.bypasses("hostname"))
        assertTrue(PrivateDns.bypasses(" Hostname "))
        assertFalse(PrivateDns.bypasses("opportunistic"))
        assertFalse(PrivateDns.bypasses("off"))
        assertFalse(PrivateDns.bypasses(null))
    }

    @Test fun bypassNamesTheProvider() {
        assertEquals("dns.google", PrivateDns.bypassProvider("hostname", "dns.google"))
        assertEquals("a custom provider", PrivateDns.bypassProvider("hostname", " "))
        assertNull(PrivateDns.bypassProvider("opportunistic", "dns.google"))
    }

    @Test fun takeoverMessage() {
        val anon = VpnTakeover.message(otherVpn = true, label = null, blockedApp = false, trusted = false, browserMode = false)
        assertEquals("Another VPN took over – website blocking is off", anon.title)
        val blocked = VpnTakeover.message(true, "FreeVPN", blockedApp = true, trusted = false, browserMode = false)
        assertEquals("FreeVPN (a blocked VPN app) took over – website blocking is off", blocked.title)
        val trusted = VpnTakeover.message(true, "Tailscale", blockedApp = false, trusted = true, browserMode = true)
        assertTrue(trusted.title.startsWith("Tailscale (trusted VPN)"))
        assertTrue("Browser mode" in trusted.text)
        val disconnected = VpnTakeover.message(false, null, false, false, false)
        assertEquals("Website filter was disconnected", disconnected.title)
    }

    @Test fun takeoverIdsAreUnique() {
        assertEquals(NotificationIds.fixed.size, NotificationIds.fixed.toSet().size)
    }
}
