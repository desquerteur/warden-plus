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
        assertEquals(setOf("no_config_vpn", "no_control_apps", "no_safe_boot"), plan.clearRestrictions)
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

    @Test fun parseIgnoresUnknownNames() {
        assertEquals(setOf(OwnerPolicy.BLOCK_SAFE_BOOT), OwnerPolicy.parse(listOf("BLOCK_SAFE_BOOT", "SOMETHING_NEW")))
    }
}
