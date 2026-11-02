package com.warden.blocker.block

import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import com.warden.blocker.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Applies [StrictLock] to the live settings. Every path that can weaken blocking (Home switch,
 * Quick Settings tile, Blocklist, Schedules, hosts lists, Settings) asks [refuse] first.
 */
class StrictGuard(
    private val context: Context,
    private val settings: SettingsStore,
    private val engine: BlockEngine,
) {
    suspend fun reason(change: Loosening): StrictLockReason? {
        val state = StrictLockState(
            focusEndsAt = settings.focusEndsAt.first(),
            focusStrict = settings.focusStrict.first(),
            strictMode = settings.strictMode.first(),
            // Only the main switch depends on it; skip the schedule lookup otherwise.
            blockingActiveNow = change == Loosening.TURN_OFF_BLOCKING && engine.isBlockingActiveNow(),
        )
        return StrictLock.check(change, state, System.currentTimeMillis())
    }

    /** True (and a short explanation is shown) when [change] isn't allowed right now. */
    suspend fun refuse(change: Loosening): Boolean {
        val reason = reason(change) ?: return false
        withContext(Dispatchers.Main) { Toast.makeText(context, message(reason), Toast.LENGTH_LONG).show() }
        return true
    }

    fun message(reason: StrictLockReason): String = when (reason) {
        // The user's 12/24-hour setting, e.g. "ends at 14:25" or "ends at 2:25 PM".
        is StrictLockReason.StrictFocus ->
            "Strict focus session – ends at ${DateFormat.getTimeFormat(context).format(Date(reason.endsAt))}"
        // Short: Android cuts toasts off after two lines.
        StrictLockReason.StrictMode -> "Strict mode is on – turn it off in Settings first."
    }
}
