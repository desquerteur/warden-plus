package com.warden.blocker.owner

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-owner settings, kept separate from the blocking settings in SettingsStore. */
class OwnerPrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("warden_owner", Context.MODE_PRIVATE)

    private val _policies = MutableStateFlow(OwnerPolicy.parse(prefs.getStringSet(KEY_POLICIES, null).orEmpty()))
    /** The rules the user switched on (applied only while Warden+ is device owner). */
    val policies: StateFlow<Set<OwnerPolicy>> = _policies.asStateFlow()

    fun setPolicies(value: Set<OwnerPolicy>) {
        prefs.edit().putStringSet(KEY_POLICIES, value.map { it.name }.toSet()).apply()
        _policies.value = value
    }

    /** Apps Warden+ suspended itself — the only ones it ever un-suspends. */
    var suspendedByUs: Set<String>
        get() = prefs.getStringSet(KEY_SUSPENDED, null).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet(KEY_SUSPENDED, value).apply()

    /** Whether Warden+ itself (as device owner) made it the always-on VPN, see [OwnerPolicyPlanner.alwaysOnVpnAction]. */
    var alwaysOnVpnSet: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON_SET, false)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_ON_SET, value).apply()

    private val _exitReadyAt = MutableStateFlow(prefs.getLong(KEY_EXIT_READY_AT, 0L))
    /** When a requested "Remove device owner" may happen (0 = not requested), see [EmergencyExit]. */
    val exitReadyAt: StateFlow<Long> = _exitReadyAt.asStateFlow()

    fun setExitReadyAt(value: Long) {
        prefs.edit().putLong(KEY_EXIT_READY_AT, value).apply()
        _exitReadyAt.value = value
    }

    /** Forget everything — after Warden+ stopped being device owner. */
    fun clear() {
        prefs.edit().clear().apply()
        _policies.value = emptySet()
        _exitReadyAt.value = 0L
    }

    companion object {
        private const val KEY_POLICIES = "policies"
        private const val KEY_SUSPENDED = "suspended_by_us"
        private const val KEY_EXIT_READY_AT = "exit_ready_at"
        private const val KEY_ALWAYS_ON_SET = "always_on_vpn_set"

        @Volatile private var instance: OwnerPrefs? = null

        fun get(context: Context): OwnerPrefs =
            instance ?: synchronized(this) {
                instance ?: OwnerPrefs(context.applicationContext).also { instance = it }
            }
    }
}
