package com.warden.blocker.owner

import com.warden.blocker.block.AccessDecision
import com.warden.blocker.block.LimitReason
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerPolicyPlannerTest {

    private fun item(pkg: String) = BlockedItem(type = BlockType.APP, value = pkg, label = pkg, interceptMode = InterceptMode.BLOCK)

    private val decisions = mapOf(
        "com.hard" to AccessDecision.HardBlock(item("com.hard")),
        "com.limit" to AccessDecision.LimitReached(item("com.limit"), LimitReason.TIME),
        "com.pause" to AccessDecision.Pause(item("com.pause")),
        "com.free" to AccessDecision.Allow,
    )

    @Test fun suspendsOnlyAppsThatCantBeOpened() {
        assertEquals(setOf("com.hard", "com.limit"), OwnerPolicyPlanner.suspendTargets(decisions, emptySet()))
    }

    @Test fun protectedAppsAreNeverSuspended() {
        val d = mapOf(
            "com.android.settings" to AccessDecision.HardBlock(item("com.android.settings")),
            "com.hard" to AccessDecision.HardBlock(item("com.hard")),
        )
        val targets = OwnerPolicyPlanner.suspendTargets(d, protected = setOf("com.android.settings"))
        assertEquals(setOf("com.hard"), targets)
    }

    @Test fun blockingOffMeansNothingToSuspend() {
        // AccessController answers Allow for everything while blocking isn't enforced.
        val d = mapOf("com.hard" to AccessDecision.Allow)
        assertTrue(OwnerPolicyPlanner.suspendTargets(d, emptySet()).isEmpty())
    }

    @Test fun planSuspendsNewAndReleasesOld() {
        val plan = OwnerPolicyPlanner.plan(
            enabled = setOf(OwnerPolicy.SUSPEND_BLOCKED_APPS),
            wantSuspended = setOf("a", "b"),
            suspendedByUs = setOf("b", "c"),
            protected = emptySet(),
        )
        assertEquals(setOf("a"), plan.suspend)
        assertEquals(setOf("c"), plan.unsuspend)
    }

    @Test fun suspendPolicyOffReleasesEverything() {
        val plan = OwnerPolicyPlanner.plan(emptySet(), setOf("a"), setOf("a", "b"), emptySet())
        assertTrue(plan.suspend.isEmpty())
        assertEquals(setOf("a", "b"), plan.unsuspend)
    }

    @Test fun protectedAppSuspendedEarlierIsReleased() {
        val plan = OwnerPolicyPlanner.plan(setOf(OwnerPolicy.SUSPEND_BLOCKED_APPS), setOf("kbd"), setOf("kbd"), setOf("kbd"))
        assertTrue(plan.suspend.isEmpty())
        assertEquals(setOf("kbd"), plan.unsuspend)
    }

    @Test fun eachRestrictionIsSwitchedOnItsOwn() {
        val plan = OwnerPolicyPlanner.plan(setOf(OwnerPolicy.LOCK_DATE_TIME), emptySet(), emptySet(), emptySet())
        assertEquals(setOf("no_config_date_time"), plan.addRestrictions)
        assertEquals(setOf("no_config_vpn", "no_control_apps", "no_safe_boot", "disallow_config_private_dns"), plan.clearRestrictions)
        assertFalse(plan.alwaysOnVpn)
        assertFalse(plan.uninstallBlocked)
    }

    @Test fun allPoliciesOn() {
        val plan = OwnerPolicyPlanner.plan(OwnerPolicy.entries.toSet(), emptySet(), emptySet(), emptySet())
        assertEquals(OwnerPolicy.MANAGED_RESTRICTIONS, plan.addRestrictions)
        assertTrue(plan.clearRestrictions.isEmpty())
        assertTrue(plan.alwaysOnVpn)
        assertTrue(plan.uninstallBlocked)
    }

    @Test fun releaseAllUndoesEverything() {
        val plan = OwnerPolicyPlanner.releaseAll(setOf("a"))
        assertEquals(setOf("a"), plan.unsuspend)
        assertTrue(plan.suspend.isEmpty())
        assertTrue(plan.addRestrictions.isEmpty())
        assertEquals(OwnerPolicy.MANAGED_RESTRICTIONS, plan.clearRestrictions)
        assertFalse(plan.alwaysOnVpn)
        assertFalse(plan.uninstallBlocked)
    }

    @Test fun neverManagesRestrictionsWithoutAWayBack() {
        // Factory reset and USB debugging must stay available (the way back without the app).
        assertFalse("no_factory_reset" in OwnerPolicy.MANAGED_RESTRICTIONS)
        assertFalse("no_debugging_features" in OwnerPolicy.MANAGED_RESTRICTIONS)
    }

    @Test fun vpnAppsAreSuspendedOnlyWithTheirRule() {
        val vpn = setOf("com.free.vpn")
        val off = OwnerPolicyPlanner.plan(setOf(OwnerPolicy.SUSPEND_BLOCKED_APPS), setOf("com.hard"), emptySet(), emptySet(), vpn)
        assertEquals(setOf("com.hard"), off.suspend)
        val on = OwnerPolicyPlanner.plan(setOf(OwnerPolicy.SUSPEND_VPN_APPS), setOf("com.hard"), emptySet(), emptySet(), vpn)
        assertEquals(vpn, on.suspend)
        val both = OwnerPolicyPlanner.plan(
            setOf(OwnerPolicy.SUSPEND_BLOCKED_APPS, OwnerPolicy.SUSPEND_VPN_APPS), setOf("com.hard"), emptySet(), setOf("com.free.vpn"), vpn,
        )
        assertEquals("protected apps never", setOf("com.hard"), both.suspend)
    }

    @Test fun vpnRuleOffReleasesThem() {
        val plan = OwnerPolicyPlanner.plan(emptySet(), emptySet(), suspendedByUs = setOf("com.free.vpn"), protected = emptySet(), vpnApps = setOf("com.free.vpn"))
        assertEquals(setOf("com.free.vpn"), plan.unsuspend)
    }

    @Test fun privateDnsLock() {
        val on = OwnerPolicyPlanner.plan(setOf(OwnerPolicy.LOCK_PRIVATE_DNS), emptySet(), emptySet(), emptySet())
        assertTrue(on.privateDnsAutomatic)
        assertEquals(setOf(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS), on.addRestrictions)
        // Emergency exit: the restriction is lifted, nothing is set any more.
        val release = OwnerPolicyPlanner.releaseAll(emptySet())
        assertFalse(release.privateDnsAutomatic)
        assertTrue(android.os.UserManager.DISALLOW_CONFIG_PRIVATE_DNS in release.clearRestrictions)
        assertTrue(android.os.UserManager.DISALLOW_CONFIG_VPN in release.clearRestrictions)
    }

    @Test fun parseIgnoresUnknownNames() {
        assertEquals(setOf(OwnerPolicy.BLOCK_SAFE_BOOT), OwnerPolicy.parse(listOf("BLOCK_SAFE_BOOT", "SOMETHING_NEW")))
    }
}
