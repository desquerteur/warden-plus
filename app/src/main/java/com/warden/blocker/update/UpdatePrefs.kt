package com.warden.blocker.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Updater settings, kept separate from the blocking settings in SettingsStore. */
class UpdatePrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("warden_updates", Context.MODE_PRIVATE)

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CHECK, true))
    /** Daily background check with a notification when a new version is out. On by default. */
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    fun setAutoCheck(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        _autoCheck.value = enabled
    }

    /** Last version a notification was shown for, so each release is announced only once. */
    var lastNotifiedVersion: String?
        get() = prefs.getString(KEY_LAST_NOTIFIED, null)
        set(value) = prefs.edit().putString(KEY_LAST_NOTIFIED, value).apply()

    companion object {
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_LAST_NOTIFIED = "last_notified_version"

        @Volatile private var instance: UpdatePrefs? = null

        fun get(context: Context): UpdatePrefs =
            instance ?: synchronized(this) {
                instance ?: UpdatePrefs(context.applicationContext).also { instance = it }
            }
    }
}
