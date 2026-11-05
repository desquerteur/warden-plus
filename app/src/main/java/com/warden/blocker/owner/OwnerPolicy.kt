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
    /** Detected VPN apps (not trusted ones) are suspended while blocking is on: none can push the filter out. */
    SUSPEND_VPN_APPS,
    /** Private DNS can't be set to a provider (DNS over TLS would go around the filter); it's set to Automatic. */
    LOCK_PRIVATE_DNS(UserManager.DISALLOW_CONFIG_PRIVATE_DNS),
    ;

    companion object {
        /** Every user restriction Warden+ manages — and therefore lifts again. */
        val MANAGED_RESTRICTIONS: Set<String> = entries.mapNotNull { it.restriction }.toSet()

        /** Stored names → policies; unknown names (from a newer/older version) are ignored. */
        fun parse(names: Collection<String>): Set<OwnerPolicy> =
            names.mapNotNull { name -> entries.firstOrNull { it.name == name } }.toSet()
    }
}

/** Short name, for the Settings switch and "… will be switched off". */
val OwnerPolicy.title: String
    get() = when (this) {
        OwnerPolicy.SUSPEND_BLOCKED_APPS -> "Switch off blocked apps"
        OwnerPolicy.ALWAYS_ON_VPN -> "Always-on website filter"
        OwnerPolicy.BLOCK_VPN_SETTINGS -> "Lock VPN settings"
        OwnerPolicy.BLOCK_UNINSTALL -> "Block uninstalling Warden+"
        OwnerPolicy.BLOCK_APP_CONTROLS -> "Lock app controls"
        OwnerPolicy.LOCK_DATE_TIME -> "Lock date & time"
        OwnerPolicy.BLOCK_SAFE_BOOT -> "Block safe mode"
        OwnerPolicy.SUSPEND_VPN_APPS -> "Switch off VPN apps"
        OwnerPolicy.LOCK_PRIVATE_DNS -> "Lock Private DNS"
    }
