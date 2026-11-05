package com.warden.blocker.gray

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** When the screen turns gray (Settings › Gray mode). */
enum class GrayWhen(val label: String) {
    ALWAYS("Always"),
    WHILE_BLOCKING("While blocking"),
    SCHEDULE("On a schedule"),
}

/**
 * The user's gray-mode choice. [startMinute]/[endMinute] (minutes after midnight) only matter
 * for [GrayWhen.SCHEDULE]; an end before the start runs past midnight, equal means all day.
 */
data class GrayModeConfig(
    val enabled: Boolean = false,
    val whenMode: GrayWhen = GrayWhen.WHILE_BLOCKING,
    val startMinute: Int = DEFAULT_START,
    val endMinute: Int = DEFAULT_END,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled).put("when", whenMode.name).put("start", startMinute).put("end", endMinute)

    companion object {
        const val DEFAULT_START = 21 * 60
        const val DEFAULT_END = 7 * 60

        fun fromJson(o: JSONObject) = GrayModeConfig(
            enabled = o.getBoolean("enabled"),
            whenMode = GrayWhen.valueOf(o.getString("when")),
            startMinute = o.getInt("start"),
            endMinute = o.getInt("end"),
        )
    }
}

/**
 * Pure rules: when the screen should be gray, when to look again, and which changes weaken it
 * (those go through Block protection; anything that makes it gray more often is instant).
 */
object GrayModeLogic {

    private const val DAY_MINUTES = 24 * 60

    fun shouldBeGray(config: GrayModeConfig, blockingActive: Boolean, minuteOfDay: Int): Boolean {
        if (!config.enabled) return false
        return when (config.whenMode) {
            GrayWhen.ALWAYS -> true
            GrayWhen.WHILE_BLOCKING -> blockingActive
            GrayWhen.SCHEDULE -> inWindow(minuteOfDay, config.startMinute, config.endMinute)
        }
    }

    /** [start] inclusive, [end] exclusive; past midnight when end < start; all day when equal. */
    fun inWindow(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
    }

    /** True when every minute of the [inner] window is also in [outer]. */
    fun windowCovers(outerStart: Int, outerEnd: Int, innerStart: Int, innerEnd: Int): Boolean =
        (0 until DAY_MINUTES).all { m -> !inWindow(m, innerStart, innerEnd) || inWindow(m, outerStart, outerEnd) }

    /**
     * True when going from [old] to [new] could leave the screen in colour at a time it was gray
     * before: switched off, "Always" → anything else, blocking ⇄ schedule, or a shorter window.
     */
    fun loosens(old: GrayModeConfig, new: GrayModeConfig): Boolean {
        if (!old.enabled) return false
        if (!new.enabled) return true
        if (new.whenMode == GrayWhen.ALWAYS) return false
        if (old.whenMode != new.whenMode) return true
        if (new.whenMode == GrayWhen.SCHEDULE) {
            return !windowCovers(new.startMinute, new.endMinute, old.startMinute, old.endMinute)
        }
        return false
    }

    /**
     * The next moment the schedule window opens or closes after [now] (epoch millis, local clock
     * in [zone]), or null when it never changes (all day).
     */
    fun nextWindowEdge(now: Long, zone: ZoneId, start: Int, end: Int): Long? {
        if (start == end) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return listOf(today, today.plusDays(1), today.plusDays(2))
            .flatMap { day -> listOf(at(day, start, zone), at(day, end, zone)) }
            .filter { it > now }
            .minOrNull()
    }

    private fun at(day: LocalDate, minute: Int, zone: ZoneId): Long =
        day.atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()

    /** The earliest of [candidates] that lies after [now], or null. */
    fun earliestAfter(now: Long, candidates: List<Long?>): Long? = candidates.filterNotNull().filter { it > now }.minOrNull()

    /** Short "21:00–07:00" style text for the window (24 h; the UI uses the phone's format). */
    fun summary(config: GrayModeConfig, clock: (Int) -> String): String = when {
        !config.enabled -> "Off"
        config.whenMode == GrayWhen.SCHEDULE -> "${clock(config.startMinute)}–${clock(config.endMinute)}"
        config.whenMode == GrayWhen.ALWAYS -> "Always"
        else -> "While blocking"
    }
}

/**
 * Android's colour correction as raw `Settings.Secure` strings (null = never set).
 * Gray = enabled "1" with mode "0" (monochromacy).
 */
data class DaltonizerState(val enabled: String?, val mode: String?) {
    val isGray: Boolean get() = enabled == "1" && mode == GRAY_MODE

    companion object {
        const val GRAY_MODE = "0"
        val GRAY = DaltonizerState("1", GRAY_MODE)
    }
}

/** What Warden+ remembers: whether it made the screen gray, and how colour correction was before. */
data class GrayMemory(val applied: Boolean = false, val previous: DaltonizerState? = null)

/** What to do: [write] to Settings.Secure (null = leave it), then remember [memory]. */
data class GraySwitch(val write: DaltonizerState?, val memory: GrayMemory)

/**
 * Turning gray on and off without breaking the user's own colour correction: the state before
 * Warden+ touched it is kept, and put back only if it is still Warden+'s gray when it ends.
 */
object GrayRestore {

    /** Make it gray. The first time, remember what was there; later calls only re-apply. */
    fun turnOn(current: DaltonizerState, memory: GrayMemory): GraySwitch {
        val write = if (current.isGray) null else DaltonizerState.GRAY
        val keep = if (memory.applied) memory else GrayMemory(applied = true, previous = current)
        return GraySwitch(write, keep)
    }

    /**
     * End gray. Put the earlier state back — unless the user changed colour correction
     * themselves in the meantime (then theirs stays), or it was gray already before.
     */
    fun turnOff(current: DaltonizerState, memory: GrayMemory): GraySwitch {
        if (!memory.applied) return GraySwitch(null, memory)
        val previous = memory.previous ?: DaltonizerState(null, null)
        val write = if (current.isGray && !previous.isGray) restoreOf(previous) else null
        return GraySwitch(write, GrayMemory())
    }

    /** "Never set" switches colour correction off; the mode is kept as it was (null = default). */
    private fun restoreOf(previous: DaltonizerState) = DaltonizerState(previous.enabled ?: "0", previous.mode)
}
