package com.warden.blocker.block

import android.content.Context
import android.widget.Toast
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A change to custom times that blocks less (see [TimeWindows] for the rules). */
enum class TimeChange(val describe: String) {
    /** "Block until …" moved to an earlier end. */
    SHORTEN_BLOCK_UNTIL("The block will end sooner"),
    /** A one-time block starts later, ends sooner or became a weekly schedule. */
    CHANGE_ONE_TIME_BLOCK("The one-time block will be shorter"),
    /** A weekly schedule's "valid from … to …" dates were narrowed. */
    NARROW_DATE_RANGE("The schedule will apply on fewer dates"),
    /** A blocklist entry's "from … / until …" span was shortened. */
    SHORTEN_ITEM_SPAN("The entry will be blocked for less time"),
    /** A hosts list's "from … / until …" span was shortened. */
    SHORTEN_LIST_SPAN("The list will be blocked for less time"),
}

/**
 * The one place custom-time changes ask "may this loosen blocking right now?". Extending or
 * adding never asks; shortening, ending early or narrowing does.
 *
 * TODO wire: connect to Block protection from 0.2.4 (PIN / Delay / Locked). For Delay the change
 * has to become a queued ProtectedChange (UpdateItem / UpdateSchedule — their JSON then needs
 * the new time fields, see BlockedItem.activeFrom/activeUntil and Schedule.onceStartAt…).
 * Until then only a strict focus session refuses, like every other loosening.
 */
object TimesProtectionHook {

    suspend fun isLooseningAllowed(context: Context, change: TimeChange): Boolean {
        val settings = context.wardenContainer.settings
        val endsAt = settings.focusEndsAt.first()
        if (settings.focusStrict.first() && endsAt > System.currentTimeMillis()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Strict focus session – this can't be shortened now", Toast.LENGTH_LONG).show()
            }
            return false
        }
        return true // TODO wire: Block protection (0.2.4)
    }
}
