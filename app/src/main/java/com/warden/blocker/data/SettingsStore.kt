package com.warden.blocker.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.warden.blocker.block.DEFAULT_DELAY_MINUTES
import com.warden.blocker.block.PendingChange
import com.warden.blocker.block.ProtectionMode
import com.warden.blocker.block.ProtectionSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "warden_settings")

/**
 * User settings & protection state. The PIN is stored as a salted hash — never plaintext.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val ALWAYS_ON = booleanPreferencesKey("always_on")
        val STRICT_MODE = booleanPreferencesKey("strict_mode")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val CURRENT_STREAK = intPreferencesKey("current_streak")
        val LONGEST_STREAK = intPreferencesKey("longest_streak")
        val LAST_ACTIVE_DAY = longPreferencesKey("last_active_day")
        val ENABLED_FEATURES = stringSetPreferencesKey("enabled_features")
        val FOCUS_ENDS_AT = longPreferencesKey("focus_ends_at")
        val FOCUS_STRICT = booleanPreferencesKey("focus_strict")
        val BLOCK_DOH = booleanPreferencesKey("block_doh")
        val BLOCK_NOTIFICATIONS = booleanPreferencesKey("block_notifications")
        val FOCUS_SESSIONS_DONE = intPreferencesKey("focus_sessions_done")
        val PIN_FAILS = intPreferencesKey("pin_fails")
        val PIN_LOCK_UNTIL = longPreferencesKey("pin_lock_until")
        val ACCESSIBILITY_USED = booleanPreferencesKey("accessibility_used")
        val PROTECTION_MODE = stringPreferencesKey("protection_mode")
        val PROTECTION_DELAY = intPreferencesKey("protection_delay_minutes")
        val PROTECTION_LOCKED_UNTIL = longPreferencesKey("protection_locked_until")
        val PENDING_CHANGES = stringPreferencesKey("pending_changes")
        val PROTECTION_OFF_EVENTS = stringSetPreferencesKey("protection_off_events")
    }

    /**
     * Block protection (see BlockProtection). Before 0.2.4 there was only "Strict mode", which
     * worked only together with a PIN: such users start out in PIN mode, nothing is lost.
     */
    val protection: Flow<ProtectionSettings> = context.dataStore.data.map { p ->
        val mode = p[Keys.PROTECTION_MODE]?.let { runCatching { ProtectionMode.valueOf(it) }.getOrNull() }
            ?: if (p[Keys.STRICT_MODE] == true && p[Keys.PIN_HASH] != null) ProtectionMode.PIN else ProtectionMode.OFF
        ProtectionSettings(mode, p[Keys.PROTECTION_DELAY] ?: DEFAULT_DELAY_MINUTES, p[Keys.PROTECTION_LOCKED_UNTIL] ?: 0L)
    }

    suspend fun setProtection(value: ProtectionSettings) =
        context.dataStore.edit {
            it[Keys.PROTECTION_MODE] = value.mode.name
            it[Keys.PROTECTION_DELAY] = value.delayMinutes
            it[Keys.PROTECTION_LOCKED_UNTIL] = value.lockedUntil
            it.remove(Keys.STRICT_MODE) // replaced by the mode above
        }.let { }

    /** Weakenings waiting in "Delay" mode, oldest first. */
    val pendingChanges: Flow<List<PendingChange>> =
        context.dataStore.data.map { PendingChange.decode(it[Keys.PENDING_CHANGES]) }

    /** Atomically rewrites the pending list; returns the new one. */
    suspend fun updatePendingChanges(transform: (List<PendingChange>) -> List<PendingChange>): List<PendingChange> {
        var result = emptyList<PendingChange>()
        context.dataStore.edit {
            result = transform(PendingChange.decode(it[Keys.PENDING_CHANGES]))
            if (result.isEmpty()) it.remove(Keys.PENDING_CHANGES) else it[Keys.PENDING_CHANGES] = PendingChange.encode(result)
        }
        return result
    }

    /** When protection was found switched off from outside (epoch millis, last ~5 weeks). */
    val protectionOffEvents: Flow<List<Long>> = context.dataStore.data.map { p ->
        p[Keys.PROTECTION_OFF_EVENTS].orEmpty().mapNotNull { it.toLongOrNull() }.sorted()
    }

    suspend fun recordProtectionOff(at: Long) =
        context.dataStore.edit { p ->
            val keep = at - 35L * 24 * 60 * 60_000L
            p[Keys.PROTECTION_OFF_EVENTS] = (p[Keys.PROTECTION_OFF_EVENTS].orEmpty()
                .filter { (it.toLongOrNull() ?: 0L) >= keep } + at.toString()).toSet()
        }.let { }

    /** Whether Warden's accessibility service has ever been connected — so its later absence
     *  (Android turns it off when the app is updated from an APK file) can be flagged. */
    val accessibilityUsed: Flow<Boolean> = context.dataStore.data.map { it[Keys.ACCESSIBILITY_USED] ?: false }

    suspend fun markAccessibilityUsed() =
        context.dataStore.edit { it[Keys.ACCESSIBILITY_USED] = true }.let { }

    val masterEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.MASTER_ENABLED] ?: false }
    val alwaysOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.ALWAYS_ON] ?: true }
    val blockDoh: Flow<Boolean> = context.dataStore.data.map { it[Keys.BLOCK_DOH] ?: false }
    val blockNotifications: Flow<Boolean> = context.dataStore.data.map { it[Keys.BLOCK_NOTIFICATIONS] ?: false }

    suspend fun setBlockDoh(value: Boolean) =
        context.dataStore.edit { it[Keys.BLOCK_DOH] = value }.let { }

    suspend fun setBlockNotifications(value: Boolean) =
        context.dataStore.edit { it[Keys.BLOCK_NOTIFICATIONS] = value }.let { }
    val hasPin: Flow<Boolean> = context.dataStore.data.map { it[Keys.PIN_HASH] != null }
    val currentStreak: Flow<Int> = context.dataStore.data.map { it[Keys.CURRENT_STREAK] ?: 0 }
    val longestStreak: Flow<Int> = context.dataStore.data.map { it[Keys.LONGEST_STREAK] ?: 0 }
    val enabledFeatureKeys: Flow<Set<String>> = context.dataStore.data.map { it[Keys.ENABLED_FEATURES] ?: emptySet() }

    /** Epoch millis a focus session ends (0 = none), and whether it can be cancelled early. */
    val focusEndsAt: Flow<Long> = context.dataStore.data.map { it[Keys.FOCUS_ENDS_AT] ?: 0L }
    val focusStrict: Flow<Boolean> = context.dataStore.data.map { it[Keys.FOCUS_STRICT] ?: false }

    suspend fun startFocus(endsAt: Long, strict: Boolean) =
        context.dataStore.edit { it[Keys.FOCUS_ENDS_AT] = endsAt; it[Keys.FOCUS_STRICT] = strict }.let { }

    suspend fun clearFocus() =
        context.dataStore.edit { it.remove(Keys.FOCUS_ENDS_AT); it.remove(Keys.FOCUS_STRICT) }.let { }

    val focusSessionsDone: Flow<Int> = context.dataStore.data.map { it[Keys.FOCUS_SESSIONS_DONE] ?: 0 }

    suspend fun incrementFocusSessions() =
        context.dataStore.edit { it[Keys.FOCUS_SESSIONS_DONE] = (it[Keys.FOCUS_SESSIONS_DONE] ?: 0) + 1 }.let { }

    suspend fun setFeatureEnabled(key: String, enabled: Boolean) =
        context.dataStore.edit { p ->
            val current = (p[Keys.ENABLED_FEATURES] ?: emptySet()).toMutableSet()
            if (enabled) current.add(key) else current.remove(key)
            p[Keys.ENABLED_FEATURES] = current
        }.let { }

    /**
     * Mark today as a protected day and update the focus streak. Called once per app open
     * while blocking is enabled. A gap of more than a day resets the streak to 1.
     */
    suspend fun recordActiveDay() {
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { p ->
            val last = p[Keys.LAST_ACTIVE_DAY] ?: -1L
            if (last == today) return@edit
            val current = p[Keys.CURRENT_STREAK] ?: 0
            val next = if (last == today - 1) current + 1 else 1
            p[Keys.CURRENT_STREAK] = next
            p[Keys.LONGEST_STREAK] = maxOf(p[Keys.LONGEST_STREAK] ?: 0, next)
            p[Keys.LAST_ACTIVE_DAY] = today
        }
    }

    suspend fun setMasterEnabled(value: Boolean) =
        context.dataStore.edit { it[Keys.MASTER_ENABLED] = value }.let { }

    suspend fun setAlwaysOn(value: Boolean) =
        context.dataStore.edit { it[Keys.ALWAYS_ON] = value }.let { }

    suspend fun setPin(hash: String, salt: String) =
        context.dataStore.edit {
            it[Keys.PIN_HASH] = hash
            it[Keys.PIN_SALT] = salt
        }.let { }

    suspend fun clearPin() =
        context.dataStore.edit {
            it.remove(Keys.PIN_HASH)
            it.remove(Keys.PIN_SALT)
        }.let { }

    // --- PIN brute-force protection ---
    suspend fun pinLockUntil(): Long = context.dataStore.data.first()[Keys.PIN_LOCK_UNTIL] ?: 0L

    suspend fun recordPinSuccess() =
        context.dataStore.edit { it.remove(Keys.PIN_FAILS); it.remove(Keys.PIN_LOCK_UNTIL) }.let { }

    /**
     * Record a failed attempt; after 5 failures apply a compounding lockout (30s, 60s, 120s…
     * capped at 1h). Returns the epoch-millis the lock lasts until (0 if not yet locked).
     */
    suspend fun recordPinFailure(): Long {
        var lockUntil = 0L
        context.dataStore.edit { p ->
            val fails = (p[Keys.PIN_FAILS] ?: 0) + 1
            p[Keys.PIN_FAILS] = fails
            if (fails >= 5) {
                val step = (fails - 5).coerceAtMost(7) // 0..7
                val seconds = (30L shl step).coerceAtMost(3600L)
                lockUntil = System.currentTimeMillis() + seconds * 1000
                p[Keys.PIN_LOCK_UNTIL] = lockUntil
            }
        }
        return lockUntil
    }

    /** Returns (hash, salt) or null if no PIN configured. */
    suspend fun pinCredentials(): Pair<String, String>? {
        val prefs = context.dataStore.data.first()
        val h = prefs[Keys.PIN_HASH]
        val s = prefs[Keys.PIN_SALT]
        return if (h != null && s != null) h to s else null
    }
}
