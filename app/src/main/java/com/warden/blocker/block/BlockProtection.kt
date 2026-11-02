package com.warden.blocker.block

import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.data.Schedule

/** Changes that make blocking weaker. Adding things (or making them stricter) is never one. */
enum class Loosening {
    TURN_OFF_BLOCKING,
    END_FOCUS,
    REMOVE_ITEM,
    DISABLE_ITEM,
    WEAKEN_ITEM,
    DISABLE_HOSTS_LIST,
    REMOVE_HOSTS_LIST,
    DISABLE_SCHEDULE,
    REMOVE_SCHEDULE,
    CHANGE_SCHEDULE,
    DISABLE_FEED_BLOCK,
    /** "Always on", DoH blocking or notification blocking switched off. */
    SETTING_OFF,
    WEAKEN_PROTECTION,
    DISABLE_UNINSTALL_PROTECTION,
    CHANGE_PIN,
    REMOVE_PIN,
    /** "Allow only" back to the block list, adding to an allow list, a category off, "not a game" … */
    LOOSEN_ALLOW,
    /** "Block until …" ended sooner, or a hosts list's "from … / until …" span shortened. */
    SHORTEN_TIMES,
}

/**
 * How hard it is to weaken blocking (Settings → Block protection).
 * - [OFF]: right away (the main switch still asks for the PIN when one is set, as before).
 * - [PIN]: every weakening asks for the PIN.
 * - [DELAY]: weakening only takes effect after a wait; it can be cancelled until then.
 * - [LOCKED]: no weakening at all until a chosen time (at most [MAX_LOCK_DAYS] days ahead).
 */
enum class ProtectionMode { OFF, PIN, DELAY, LOCKED }

data class ProtectionSettings(
    val mode: ProtectionMode = ProtectionMode.OFF,
    val delayMinutes: Int = DEFAULT_DELAY_MINUTES,
    val lockedUntil: Long = 0L,
)

/** Everything the decision depends on, read from the live settings. */
data class ProtectionState(
    val protection: ProtectionSettings,
    val hasPin: Boolean,
    val focusEndsAt: Long = 0L,
    val focusStrict: Boolean = false,
)

/** The answer to "may this weakening happen right now?". */
sealed interface Verdict {
    data object Allow : Verdict
    data object NeedsPin : Verdict
    /** Takes effect at [at] unless cancelled before. */
    data class Delayed(val at: Long) : Verdict
    /** Refused until [until]; [byFocus] when a strict focus session is the reason. */
    data class Locked(val until: Long, val byFocus: Boolean) : Verdict
}

const val DEFAULT_DELAY_MINUTES = 60
const val MAX_LOCK_DAYS = 30

/**
 * One rule for every weakening, wherever it comes from (Home, Quick Settings tile, Blocklist,
 * Schedules, hosts lists, Settings). Pure, so it's tested mode × change × time.
 */
object BlockProtection {

    fun check(change: Loosening, state: ProtectionState, now: Long): Verdict {
        // A strict focus session ("no early exit") is a lock that ends with the session.
        if (state.focusStrict && state.focusEndsAt > now) return Verdict.Locked(state.focusEndsAt, byFocus = true)
        val p = state.protection
        return when (effectiveMode(p, now)) {
            ProtectionMode.LOCKED -> Verdict.Locked(p.lockedUntil, byFocus = false)
            ProtectionMode.DELAY -> Verdict.Delayed(now + p.delayMinutes.coerceAtLeast(1) * 60_000L)
            ProtectionMode.PIN -> if (state.hasPin) Verdict.NeedsPin else Verdict.Allow
            // Unchanged from before: with a PIN set, stopping blocking asks for it.
            ProtectionMode.OFF ->
                if (state.hasPin && change == Loosening.TURN_OFF_BLOCKING) Verdict.NeedsPin else Verdict.Allow
        }
    }

    /** A lock that has run out counts as off. */
    fun effectiveMode(p: ProtectionSettings, now: Long): ProtectionMode =
        if (p.mode == ProtectionMode.LOCKED && p.lockedUntil <= now) ProtectionMode.OFF else p.mode

    /** True when going from [old] to [new] makes weakening easier (and so is itself protected). */
    fun protectionChangeLoosens(old: ProtectionSettings, new: ProtectionSettings, now: Long): Boolean {
        val from = effectiveMode(old, now)
        val to = effectiveMode(new, now)
        return when {
            from == ProtectionMode.OFF -> false
            to == ProtectionMode.LOCKED -> from == ProtectionMode.LOCKED && new.lockedUntil < old.lockedUntil
            from != to -> true // PIN ⇄ delay counts too: either way one safeguard is dropped
            to == ProtectionMode.DELAY -> new.delayMinutes < old.delayMinutes
            else -> false
        }
    }

    /** The latest "Locked until" a user may pick: [MAX_LOCK_DAYS] days, so nobody locks themselves out for good. */
    fun maxLockedUntil(now: Long): Long = now + MAX_LOCK_DAYS * 24 * 60 * 60_000L

    /** True when [updated] blocks [old] less: on → off, hard block → pause, a shorter pause or looser limits. */
    fun itemEditLoosens(old: BlockedItem, updated: BlockedItem): Boolean {
        if (old.enabled && !updated.enabled) return true
        if (TimeWindows.itemTimesLoosen(old, updated)) return true // a later "from" or an earlier "until"
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
     * True when [updated] could block less than [old]: switched off, a day dropped, the times
     * moved, a one-time block shortened or a date range narrowed (see [TimeWindows.scheduleTimesLoosen]).
     * Renaming, adding days or extending is fine.
     */
    fun scheduleEditLoosens(old: Schedule, updated: Schedule, now: Long = System.currentTimeMillis()): Boolean {
        if (old.enabled && !updated.enabled) return true
        if (TimeWindows.scheduleTimesLoosen(old, updated, now)) return true
        // A one-time block only has its start and end; its weekday/clock fields mean nothing.
        if (TimeWindows.isOnce(old)) return false
        if (old.daysMask and updated.daysMask.inv() != 0) return true
        return old.startMinute != updated.startMinute || old.endMinute != updated.endMinute
    }
}
