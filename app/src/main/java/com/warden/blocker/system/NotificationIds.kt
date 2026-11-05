package com.warden.blocker.system

/**
 * Every notification id Warden+ uses, in one place so two features never share one: a shared
 * id replaces the other feature's notification, and (used as the PendingIntent request code)
 * mixes up where tapping it leads. Add new ones here.
 */
object NotificationIds {
    /** The VPN's foreground notification. */
    const val VPN = 1001
    /** App blocking stopped because accessibility was switched off (e.g. by an update). */
    const val ACCESSIBILITY = 1002
    /** Protection was switched off from outside (force stop, battery saver …). */
    const val PROTECTION_WATCH = 1003
    /** An app was paused after it kept re-opening the block screen (hotfix 0.2.3). */
    const val BLOCK_LOOP = 1004
    /** A schedule started but Android didn't let the filter start in the background. */
    const val FILTER_PAUSED = 1005
    /** "Another VPN took over – website blocking is off" (VPN bypass protection). */
    const val VPN_TAKEOVER = 1006
    /** Request code of that notification's "VPN settings" action (not a notification). */
    const val VPN_TAKEOVER_SETTINGS = 1007
    /** "Settings guard is on – managed by Warden+" (ongoing while the guard is on). */
    const val SETTINGS_GUARD = 1010
    /** The clock or time zone was changed by hand (Settings guard). */
    const val CLOCK_CHANGED = 1011
    /** "Warden+ x.y.z is available". */
    const val UPDATE = 4201
    /** "New game blocked": this plus a hash of the app's label, so each app gets its own. */
    const val NEW_APP_BASE = 20_000
    const val NEW_APP_RANGE = 0x10000

    /** The fixed ids, for the uniqueness test. */
    val fixed = listOf(
        VPN, ACCESSIBILITY, PROTECTION_WATCH, BLOCK_LOOP, FILTER_PAUSED, VPN_TAKEOVER, VPN_TAKEOVER_SETTINGS,
        SETTINGS_GUARD, CLOCK_CHANGED, UPDATE,
    )

    fun newApp(label: String): Int = NEW_APP_BASE + (label.hashCode() and (NEW_APP_RANGE - 1))
}
