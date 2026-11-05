package com.warden.blocker.gray

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Gray-mode settings and what Warden+ changed in colour correction, kept apart from SettingsStore. */
class GrayModePrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("warden_gray", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<GrayModeConfig> = _config.asStateFlow()

    /** Only call after Block protection allowed it (or when it makes gray mode stricter). */
    fun setConfig(value: GrayModeConfig) {
        prefs.edit {
            putBoolean(KEY_ENABLED, value.enabled)
            putString(KEY_WHEN, value.whenMode.name)
            putInt(KEY_START, value.startMinute)
            putInt(KEY_END, value.endMinute)
        }
        _config.value = value
    }

    var memory: GrayMemory
        get() = GrayMemory(
            applied = prefs.getBoolean(KEY_APPLIED, false),
            previous = if (prefs.getBoolean(KEY_HAS_PREVIOUS, false)) {
                DaltonizerState(prefs.getString(KEY_PREV_ENABLED, null), prefs.getString(KEY_PREV_MODE, null))
            } else {
                null
            },
        )
        set(value) = prefs.edit(commit = true) {
            putBoolean(KEY_APPLIED, value.applied)
            putBoolean(KEY_HAS_PREVIOUS, value.previous != null)
            putString(KEY_PREV_ENABLED, value.previous?.enabled)
            putString(KEY_PREV_MODE, value.previous?.mode)
        }

    private fun readConfig() = GrayModeConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        whenMode = prefs.getString(KEY_WHEN, null)?.let { runCatching { GrayWhen.valueOf(it) }.getOrNull() } ?: GrayWhen.WHILE_BLOCKING,
        startMinute = prefs.getInt(KEY_START, GrayModeConfig.DEFAULT_START),
        endMinute = prefs.getInt(KEY_END, GrayModeConfig.DEFAULT_END),
    )

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WHEN = "when"
        private const val KEY_START = "start_minute"
        private const val KEY_END = "end_minute"
        private const val KEY_APPLIED = "applied"
        private const val KEY_HAS_PREVIOUS = "has_previous"
        private const val KEY_PREV_ENABLED = "previous_enabled"
        private const val KEY_PREV_MODE = "previous_mode"

        @Volatile private var instance: GrayModePrefs? = null

        fun get(context: Context): GrayModePrefs =
            instance ?: synchronized(this) {
                instance ?: GrayModePrefs(context.applicationContext).also { instance = it }
            }
    }
}
