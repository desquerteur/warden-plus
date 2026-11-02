package com.warden.blocker.owner

import android.os.UserManager

/**
 * The rules Warden+ can enforce while it is the device owner. Each one is switched on and off
 * by itself (all off until the user picks them). Deliberately missing: DISALLOW_FACTORY_RESET
 * and DISALLOW_DEBUGGING_FEATURES — with those there'd be no way back except the in-app exit.
 */
enum class OwnerPolicy(
    /** User restriction this policy sets, if it is one (constants are inlined, so pure). */
    val restriction: String? = null,
) {
    /** Hard-blocked apps (and apps at their limit) are suspended by Android while blocking is on. */
    SUSPEND_BLOCKED_APPS,
    /** Warden+'s VPN is the always-on VPN: Android keeps it connected. */
    ALWAYS_ON_VPN,
    /** No VPN changes in Settings — no other VPN can replace the filter. */
    BLOCK_VPN_SETTINGS(UserManager.DISALLOW_CONFIG_VPN),
    /** Warden+ itself can't be uninstalled. */
    BLOCK_UNINSTALL,
    /** No Force stop / Clear data / Uninstall / Disable for any app. */
    BLOCK_APP_CONTROLS(UserManager.DISALLOW_APPS_CONTROL),
    /** Date, time and time zone can't be changed (schedules can't be tricked). */
    LOCK_DATE_TIME(UserManager.DISALLOW_CONFIG_DATE_TIME),
    /** No safe mode (it starts the phone without Warden+). */
    BLOCK_SAFE_BOOT(UserManager.DISALLOW_SAFE_BOOT),
    ;

    companion object {
        /** Every user restriction Warden+ manages — and therefore lifts again. */
        val MANAGED_RESTRICTIONS: Set<String> = entries.mapNotNull { it.restriction }.toSet()

        /** Stored names → policies; unknown names (from a newer/older version) are ignored. */
        fun parse(names: Collection<String>): Set<OwnerPolicy> =
            names.mapNotNull { name -> entries.firstOrNull { it.name == name } }.toSet()
    }
}
