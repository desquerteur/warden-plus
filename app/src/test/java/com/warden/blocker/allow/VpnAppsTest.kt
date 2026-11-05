package com.warden.blocker.allow

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnAppsTest {

    private val own = "com.warden.blocker"

    /** What PackageManager lists for the VpnService action on a typical phone. */
    private val services = listOf(
        ServiceEntry(VpnApps.TAILSCALE, VpnApps.BIND_PERMISSION),
        ServiceEntry("com.free.vpn", VpnApps.BIND_PERMISSION),
        ServiceEntry("com.free.vpn", VpnApps.BIND_PERMISSION), // two services, one app
        ServiceEntry(own, VpnApps.BIND_PERMISSION), // Warden's own filter
        ServiceEntry("com.fake.vpn", null), // claims the action but can't be bound as a VPN
        ServiceEntry("com.other", "android.permission.BIND_JOB_SERVICE"),
    )

    @Test fun detectsAppsWithABoundVpnService() {
        assertEquals(setOf(VpnApps.TAILSCALE, "com.free.vpn"), VpnApps.detect(services, own))
    }

    @Test fun wardenItselfIsNeverAVpnApp() {
        assertTrue(own !in VpnApps.detect(services, own))
    }

    @Test fun vpnWinsOverWhatTheAppDeclares() {
        assertEquals(AppCategory.VPN, AppCategories.detect(ApplicationInfo.CATEGORY_GAME, 0, isVpn = true))
        assertEquals(AppCategory.GAME, AppCategories.detect(ApplicationInfo.CATEGORY_GAME, 0, isVpn = false))
    }

    @Test fun vpnCategoryBlocksVpnAppsOnly() {
        val config = AllowConfig(blockedCategories = setOf(AppCategory.VPN))
        val facts = AppFacts(AppCategory.VPN, launchable = true, alwaysAllowed = false)
        assertEquals(AppVerdict.Category(AppCategory.VPN), AllowPolicy.decideApp("com.free.vpn", config, facts, false, false))
        val game = facts.copy(detectedCategory = AppCategory.GAME)
        assertEquals(AppVerdict.FollowBlocklist, AllowPolicy.decideApp("com.game", config, game, false, false))
    }

    @Test fun newlyInstalledVpnAppIsReported() {
        val config = AllowConfig(blockedCategories = setOf(AppCategory.VPN))
        val installed = mapOf("old.vpn" to AppCategory.VPN, "new.vpn" to AppCategory.VPN)
        assertEquals(mapOf("new.vpn" to AppCategory.VPN), AllowPolicy.newlyBlocked(setOf("old.vpn"), installed, config))
    }

    @Test fun trustingAVpnIsALoosening() {
        val base = AllowConfig(blockedCategories = setOf(AppCategory.VPN))
        val trusted = AllowEdit.SetOverride(VpnApps.TAILSCALE, "Tailscale", AppCategories.NONE).applyTo(base)
        assertEquals(setOf(AllowLoosening.CATEGORY_OVERRIDE), AllowPolicy.loosenings(base, trusted))
        assertEquals(setOf(VpnApps.TAILSCALE), VpnApps.trusted(setOf(VpnApps.TAILSCALE, "com.free.vpn"), trusted))
        // A trusted VPN isn't blocked any more; blocking it again tightens at once.
        assertNull(AllowPolicy.blockedCategoryOf(VpnApps.TAILSCALE, AppCategory.VPN, trusted))
        assertTrue(AllowPolicy.loosenings(trusted, base).isEmpty())
    }

    @Test fun switchingTheCategoryOffIsALoosening() {
        val on = AllowConfig(blockedCategories = setOf(AppCategory.VPN))
        assertEquals(setOf(AllowLoosening.CATEGORY_OFF), AllowPolicy.loosenings(on, AllowConfig()))
        assertTrue(AllowPolicy.loosenings(AllowConfig(), on).isEmpty())
    }

    @Test fun ownerSuspendsUntrustedVpnAppsWhileBlocking() {
        val apps = setOf(VpnApps.TAILSCALE, "com.free.vpn", "com.critical.vpn")
        val config = AllowConfig(categoryOverrides = mapOf(VpnApps.TAILSCALE to AppCategories.NONE))
        assertEquals(setOf("com.free.vpn"), VpnApps.suspendTargets(apps, config, setOf("com.critical.vpn"), enforced = true))
        assertTrue(VpnApps.suspendTargets(apps, config, emptySet(), enforced = false).isEmpty())
    }

    @Test fun takeoverSuspect() {
        val apps = setOf(VpnApps.TAILSCALE)
        // Always-on names it for sure.
        assertEquals("com.x.vpn", VpnApps.takeoverSuspect("com.x.vpn", VpnApps.TAILSCALE, apps, own))
        // Else a VPN app that was just in front.
        assertEquals(VpnApps.TAILSCALE, VpnApps.takeoverSuspect(null, VpnApps.TAILSCALE, apps, own))
        assertEquals(VpnApps.TAILSCALE, VpnApps.takeoverSuspect(own, VpnApps.TAILSCALE, apps, own))
        // A non-VPN app in front says nothing.
        assertNull(VpnApps.takeoverSuspect("", "com.browser", apps, own))
    }
}
