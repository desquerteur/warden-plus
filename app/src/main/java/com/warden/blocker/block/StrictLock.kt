package com.warden.blocker.block

import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.data.Schedule

/** Changes that make blocking weaker. Adding things (or making them stricter) is never one. */
enum class Loosening {
    TURN_OFF_BLOCKING,
    REMOVE_ITEM,
    DISABLE_ITEM,
    WEAKEN_ITEM,
    DISABLE_HOSTS_LIST,
    REMOVE_HOSTS_LIST,
    DISABLE_SCHEDULE,
    REMOVE_SCHEDULE,
    CHANGE_SCHEDULE,
    DISABLE_FEED_BLOCK,
    /** A protection setting switched off: always on, strict mode, DoH, notifications, PIN, uninstall protection. */
    SETTING_OFF,
}

/** Why a [Loosening] is refused right now. */
sealed interface StrictLockReason {
    /** A strict focus session is running until [endsAt]: nothing may weaken blocking. */
    data class StrictFocus(val endsAt: Long) : StrictLockReason
    /** Strict mode (Settings) locks the main switch while a block is being enforced. */
    data object StrictMode : StrictLockReason
}

/** The protection state a [StrictLock] decision is made from. */
data class StrictLockState(
    val focusEndsAt: Long,
    val focusStrict: Boolean,
    val strictMode: Boolean,
    val blockingActiveNow: Boolean,
)

/**
 * "May this change happen right now?" — pure, so the rules are unit-tested. A strict focus
 * session ("no early exit") refuses every [Loosening] until it ends; a normal focus session
 * refuses nothing. Strict mode only locks the main switch, and only while a block is enforced
 * (turning strict mode off in the PIN-locked Settings is the way out).
 */
object StrictLock {

    fun check(change: Loosening, state: StrictLockState, now: Long): StrictLockReason? {
        if (state.focusStrict && state.focusEndsAt > now) return StrictLockReason.StrictFocus(state.focusEndsAt)
        if (change == Loosening.TURN_OFF_BLOCKING && state.strictMode && state.blockingActiveNow) {
            return StrictLockReason.StrictMode
        }
        return null
    }

    /** True when [updated] blocks [old] less: on → off, hard block → pause, a shorter pause or looser limits. */
    fun itemEditLoosens(old: BlockedItem, updated: BlockedItem): Boolean {
        if (old.enabled && !updated.enabled) return true
        if (old.interceptMode == InterceptMode.BLOCK && updated.interceptMode != InterceptMode.BLOCK) return true
        if (updated.interceptMode == InterceptMode.BLOCK) return false // limits don't apply to a hard block
        return updated.pauseSeconds < old.pauseSeconds ||
            limitLoosened(old.openLimitPerDay, updated.openLimitPerDay) ||
            limitLoosened(old.dailyLimitMinutes, updated.dailyLimitMinutes) ||
            updated.cooldownMinutes < old.cooldownMinutes
    }

    /** 0 means "no limit", so 0 is the loosest value of all. */
    private fun limitLoosened(old: Int, new: Int): Boolean =
        old > 0 && (new == 0 || new > old)

    /**
     * True when [updated] could block less than [old]: switched off, a day dropped or the times
     * moved. Renaming or adding days is fine.
     */
    fun scheduleEditLoosens(old: Schedule, updated: Schedule): Boolean {
        if (old.enabled && !updated.enabled) return true
        if (old.daysMask and updated.daysMask.inv() != 0) return true
        return old.startMinute != updated.startMinute || old.endMinute != updated.endMinute
    }
}
