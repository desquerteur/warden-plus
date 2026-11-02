package com.warden.blocker.guard

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// A file of its own, so this feature doesn't touch the main settings or the database.
private val Context.guardDataStore by preferencesDataStore(name = "warden_settings_guard")

/** Last clock change Warden+ noticed, for the Settings card. */
data class ClockChangeRecord(val at: Long, val kind: ClockChangeKind, val deltaMs: Long)

/** "Settings guard": the switch (off by default), what it warned about, and the clock watch. */
class SettingsGuardStore private constructor(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("guard_enabled")
        val WARNED_COUNT = intPreferencesKey("guard_warned_count")
        val LAST_WARNED_AT = longPreferencesKey("guard_last_warned_at")
        val LAST_WARNED_TARGET = stringPreferencesKey("guard_last_warned_target")
        val WATCH_DATE_TIME = booleanPreferencesKey("guard_watch_date_time")
        val CLOCK_CHANGES = intPreferencesKey("clock_changes")
        val LAST_CLOCK_AT = longPreferencesKey("clock_last_at")
        val LAST_CLOCK_KIND = stringPreferencesKey("clock_last_kind")
        val LAST_CLOCK_DELTA = longPreferencesKey("clock_last_delta")
        val ANCHOR_WALL = longPreferencesKey("clock_anchor_wall")
        val ANCHOR_ELAPSED = longPreferencesKey("clock_anchor_elapsed")
        val ANCHOR_BOOT = intPreferencesKey("clock_anchor_boot")
        val ANCHOR_ZONE = stringPreferencesKey("clock_anchor_zone")
        val ANCHOR_OFFSET = intPreferencesKey("clock_anchor_offset")
        val ANCHOR_LAST_SEEN = longPreferencesKey("clock_anchor_last_seen")
    }

    private val data get() = context.guardDataStore.data

    val enabled: Flow<Boolean> = data.map { it[Keys.ENABLED] ?: false }

    /** Switching on is always allowed; switching off goes through Block protection first (ProtectedChange.SettingOff). */
    suspend fun setEnabled(value: Boolean) = context.guardDataStore.edit { it[Keys.ENABLED] = value }.let { }

    /** Also warn on Date & time — only if the user ticks it. */
    val watchDateTime: Flow<Boolean> = data.map { it[Keys.WATCH_DATE_TIME] ?: false }

    suspend fun setWatchDateTime(value: Boolean) = context.guardDataStore.edit { it[Keys.WATCH_DATE_TIME] = value }.let { }

    val warnedCount: Flow<Int> = data.map { it[Keys.WARNED_COUNT] ?: 0 }

    val lastWarned: Flow<Pair<GuardTarget, Long>?> = data.map { p ->
        val at = p[Keys.LAST_WARNED_AT] ?: return@map null
        val target = p[Keys.LAST_WARNED_TARGET]?.let { n -> GuardTarget.entries.firstOrNull { it.name == n } }
            ?: return@map null
        target to at
    }

    suspend fun recordWarned(target: GuardTarget, at: Long) = context.guardDataStore.edit {
        it[Keys.WARNED_COUNT] = (it[Keys.WARNED_COUNT] ?: 0) + 1
        it[Keys.LAST_WARNED_AT] = at
        it[Keys.LAST_WARNED_TARGET] = target.name
    }.let { }

    /** Clock or time zone changed by hand (not by network time). */
    val clockChanges: Flow<Int> = data.map { it[Keys.CLOCK_CHANGES] ?: 0 }

    val lastClockChange: Flow<ClockChangeRecord?> = data.map { p ->
        val at = p[Keys.LAST_CLOCK_AT] ?: return@map null
        val kind = p[Keys.LAST_CLOCK_KIND]?.let { n -> ClockChangeKind.entries.firstOrNull { it.name == n } }
            ?: return@map null
        ClockChangeRecord(at, kind, p[Keys.LAST_CLOCK_DELTA] ?: 0L)
    }

    suspend fun recordClockChange(change: ClockChange, at: Long) = context.guardDataStore.edit {
        it[Keys.CLOCK_CHANGES] = (it[Keys.CLOCK_CHANGES] ?: 0) + 1
        it[Keys.LAST_CLOCK_AT] = at
        it[Keys.LAST_CLOCK_KIND] = change.kind.name
        it[Keys.LAST_CLOCK_DELTA] = change.deltaMs
    }.let { }

    suspend fun anchor(): ClockAnchor? = anchorOf(data.first())

    suspend fun saveAnchor(a: ClockAnchor) = context.guardDataStore.edit {
        it[Keys.ANCHOR_WALL] = a.wallMs
        it[Keys.ANCHOR_ELAPSED] = a.elapsedMs
        it[Keys.ANCHOR_BOOT] = a.bootCount
        it[Keys.ANCHOR_ZONE] = a.zoneId
        it[Keys.ANCHOR_OFFSET] = a.zoneOffsetMs
        it[Keys.ANCHOR_LAST_SEEN] = a.lastSeenWallMs
    }.let { }

    private fun anchorOf(p: Preferences): ClockAnchor? {
        val wall = p[Keys.ANCHOR_WALL] ?: return null
        return ClockAnchor(
            wallMs = wall,
            elapsedMs = p[Keys.ANCHOR_ELAPSED] ?: return null,
            bootCount = p[Keys.ANCHOR_BOOT] ?: return null,
            zoneId = p[Keys.ANCHOR_ZONE] ?: return null,
            zoneOffsetMs = p[Keys.ANCHOR_OFFSET] ?: return null,
            lastSeenWallMs = p[Keys.ANCHOR_LAST_SEEN] ?: wall,
        )
    }

    companion object {
        @Volatile private var instance: SettingsGuardStore? = null

        fun get(context: Context): SettingsGuardStore =
            instance ?: synchronized(this) {
                instance ?: SettingsGuardStore(context.applicationContext).also { instance = it }
            }
    }
}
