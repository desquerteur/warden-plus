package com.warden.blocker.guard

import android.content.Context

/** What the guard asks Block protection for permission to do. */
enum class GuardLoosening {
    /** Switch "Settings guard" off. */
    TURN_OFF_GUARD,
}

/**
 * The one place the guard asks whether it may be weakened. Switching the guard *on* never asks.
 *
 * TODO(0.2.4 merge): wire to Block protection. With `ProtectionGuard` from 0.2.4 this becomes
 * `verdict(Loosening.SETTING_OFF) == Verdict.Allow`; for the Delay / PIN modes the switch should
 * instead go through `ProtectionGuard.request(ProtectedChange.SettingOff(Setting.SETTINGS_GUARD))`
 * whose apply step calls `SettingsGuardStore.setEnabled(false)`.
 */
object ProtectionHook {
    @Suppress("UNUSED_PARAMETER")
    suspend fun isLooseningAllowed(context: Context, action: GuardLoosening): Boolean = true // TODO wire to Block protection
}
