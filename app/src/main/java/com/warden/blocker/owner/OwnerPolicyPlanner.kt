package com.warden.blocker.owner

import com.warden.blocker.block.AccessDecision

/** Everything [DeviceOwnerManager] has to change to reach the wanted state. */
data class OwnerPlan(
    val suspend: Set<String>,
    val unsuspend: Set<String>,
    val addRestrictions: Set<String>,
    val clearRestrictions: Set<String>,
    val alwaysOnVpn: Boolean,
    val uninstallBlocked: Boolean,
)

/**
 * "What should the device-owner rules be right now?" — pure, so the decisions are unit-tested.
 * Android calls happen only in [DeviceOwnerManager].
 */
object OwnerPolicyPlanner {

    /**
     * Apps to suspend: those the user can't get into right now (hard block or limit reached).
     * Apps with a mindful pause stay usable — they need Warden's pause screen, which a
     * suspended app never reaches. [protected] apps (phone, settings, launcher, keyboard,
     * Warden+) are never suspended, whatever the blocklist says.
     */
    fun suspendTargets(decisions: Map<String, AccessDecision>, protected: Set<String>): Set<String> =
        decisions.filter { (pkg, decision) ->
            pkg !in protected && (decision is AccessDecision.HardBlock || decision is AccessDecision.LimitReached)
        }.keys

    /**
     * The changes from today's state to the wanted one. [suspendedByUs] is what Warden+ suspended
     * earlier (it only ever lifts its own suspensions); [wantSuspended] comes from [suspendTargets].
     * Every managed restriction that isn't wanted is cleared, so switching a policy off undoes it.
     */
    fun plan(
        enabled: Set<OwnerPolicy>,
        wantSuspended: Set<String>,
        suspendedByUs: Set<String>,
        protected: Set<String>,
    ): OwnerPlan {
        val want = if (OwnerPolicy.SUSPEND_BLOCKED_APPS in enabled) wantSuspended - protected else emptySet()
        val restrictions = enabled.mapNotNull { it.restriction }.toSet()
        return OwnerPlan(
            suspend = want - suspendedByUs,
            // A protected app that somehow got suspended is always released.
            unsuspend = suspendedByUs - want,
            addRestrictions = restrictions,
            clearRestrictions = OwnerPolicy.MANAGED_RESTRICTIONS - restrictions,
            alwaysOnVpn = OwnerPolicy.ALWAYS_ON_VPN in enabled,
            uninstallBlocked = OwnerPolicy.BLOCK_UNINSTALL in enabled,
        )
    }

    /**
     * What to do with Android's always-on VPN. Being the always-on VPN isn't enough: if the user
     * switched it on by hand (Protection checklist), it can be switched off again in Android's VPN
     * settings. Only once Warden+ set it as device owner ([setByUs]) does Android lock it (D3).
     */
    fun alwaysOnVpnAction(want: Boolean, currentlyOurs: Boolean, setByUs: Boolean): AlwaysOnVpnAction = when {
        want && !(currentlyOurs && setByUs) -> AlwaysOnVpnAction.SET
        !want && (currentlyOurs || setByUs) -> AlwaysOnVpnAction.CLEAR
        else -> AlwaysOnVpnAction.NONE
    }

    /** Everything off: what has to happen before Warden+ stops being device owner. */
    fun releaseAll(suspendedByUs: Set<String>): OwnerPlan =
        plan(enabled = emptySet(), wantSuspended = emptySet(), suspendedByUs = suspendedByUs, protected = emptySet())
}

enum class AlwaysOnVpnAction { SET, CLEAR, NONE }
