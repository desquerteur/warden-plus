package com.warden.blocker.owner

import android.content.Context

/** Device-owner changes that make protection weaker. Switching a rule on is never one. */
enum class OwnerLoosening {
    /** One device-owner rule switched off. */
    POLICY_OFF,
    /** "Remove device owner" (the emergency exit). */
    REMOVE_DEVICE_OWNER,
}

/**
 * Adapter to the "Block protection" (Off / PIN / Delay / Locked until) from 0.2.4, which isn't in
 * this branch yet. Until it is wired, every loosening is allowed at once — the Settings screen
 * itself is still PIN-locked, and the emergency exit keeps its 24 h ceiling either way.
 */
object ProtectionHook {

    /** May [change] happen right now? */
    @Suppress("UNUSED_PARAMETER")
    suspend fun isLooseningAllowed(context: Context, change: OwnerLoosening): Boolean = true // TODO wire

    /**
     * How long the Block protection makes the user wait before [change] (the "Delay" setting, or
     * the time left of "Locked until"). 0 = no wait. [EmergencyExit] caps it at 24 h.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun waitMillisBefore(context: Context, change: OwnerLoosening, now: Long): Long = 0L // TODO wire
}
