package com.warden.blocker.ui.tour

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/** Navigation routes the tours know about. Must match the routes in MainActivity's NavHost. */
object TourRoutes {
    const val HOME = "home"
    const val BLOCKLIST = "blocklist"
    const val SCHEDULES = "schedules"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
    const val HOSTS_LISTS = "hostslists"
    const val APP_PICKER = "apppicker"
    const val FEATURES = "features"
    const val PROTECTION = "protection"
}

/** What a step does when its target isn't on screen (empty list, PIN-locked Settings, later version). */
enum class MissingTarget {
    /** Leave the step out (default). */
    SKIP,

    /** Show the text in the middle, without a spotlight. */
    CENTER,
}

/**
 * One stop of a tour. [target] is the id passed to `Modifier.tourTarget(...)` (null = a card in
 * the middle), [route] the screen it lives on (null = wherever the user is). [guide] adds an
 * animated mini settings screen for steps that happen outside Warden+.
 */
data class TourStep(
    val target: String?,
    val route: String?,
    val title: String,
    val text: String,
    val guide: SettingsGuide? = null,
    val ifMissing: MissingTarget = MissingTarget.SKIP,
)

data class Tour(val id: String, val title: String, val steps: List<TourStep>)

/** Shorthand used by the tour definitions: one line per step. */
fun step(
    target: String?,
    route: String?,
    title: String,
    text: String,
    guide: SettingsGuide? = null,
    ifMissing: MissingTarget = MissingTarget.SKIP,
) = TourStep(target, route, title, text, guide, ifMissing)

enum class TourStatus { NEW, IN_PROGRESS, COMPLETED, SKIPPED }

/** Saved state of one tour; [step] is where an unfinished tour continues. */
data class TourRecord(val status: TourStatus = TourStatus.NEW, val step: Int = 0) {
    fun encode(): String = "${status.name}:$step"

    companion object {
        fun decode(raw: String?): TourRecord {
            if (raw.isNullOrBlank()) return TourRecord()
            val status = TourStatus.entries.firstOrNull { it.name == raw.substringBefore(':') } ?: return TourRecord()
            return TourRecord(status, raw.substringAfter(':', "0").toIntOrNull()?.coerceAtLeast(0) ?: 0)
        }
    }
}

/**
 * A tour that is running. [forward] is the direction of the last move, so a step whose target
 * is missing is skipped the same way; [missing] are steps found missing in this run.
 */
data class TourRun(
    val tourId: String,
    val index: Int = 0,
    val forward: Boolean = true,
    val missing: Set<Int> = emptySet(),
)

enum class StepDisplay { SPOTLIGHT, CENTER, SKIP }

object TourLogic {

    fun display(step: TourStep, targetFound: Boolean): StepDisplay = when {
        step.target == null -> StepDisplay.CENTER
        targetFound -> StepDisplay.SPOTLIGHT
        step.ifMissing == MissingTarget.CENTER -> StepDisplay.CENTER
        else -> StepDisplay.SKIP
    }

    /** The run after "Next"; null once the last step is done. */
    fun next(run: TourRun, size: Int): TourRun? {
        val i = ((run.index + 1) until size).firstOrNull { it !in run.missing } ?: return null
        return run.copy(index = i, forward = true)
    }

    /** The run after "Back"; null on the first shown step. */
    fun back(run: TourRun): TourRun? {
        val i = (run.index - 1 downTo 0).firstOrNull { it !in run.missing } ?: return null
        return run.copy(index = i, forward = false)
    }

    /**
     * The current step's target isn't there: go on in the same direction. Going back past the
     * start turns around; null = nothing left to show.
     */
    fun skipMissing(run: TourRun, size: Int): TourRun? {
        val marked = run.copy(missing = run.missing + run.index)
        return if (marked.forward) next(marked, size) else back(marked) ?: next(marked, size)
    }

    /** "3/8": position among the steps not known to be missing, and their count. */
    fun progress(run: TourRun, size: Int): Pair<Int, Int> {
        val position = (0..run.index.coerceAtMost(size - 1)).count { it !in run.missing }
        return position to (size - run.missing.count { it < size })
    }

    /**
     * Later steps on the screen that is open now whose target isn't on it: known to be missing
     * already, so "1/4" is right from the start instead of shrinking from "1/7".
     */
    fun missingAhead(steps: List<TourStep>, from: Int, route: String?, isOnScreen: (String) -> Boolean): Set<Int> =
        ((from + 1) until steps.size).filter { i ->
            val s = steps[i]
            s.route == route && s.target != null && s.ifMissing == MissingTarget.SKIP && !isOnScreen(s.target)
        }.toSet()

    /** Where to start a tour: a half-done tour continues, anything else starts over. */
    fun startIndex(record: TourRecord, size: Int, resume: Boolean): Int =
        if (resume && record.status == TourStatus.IN_PROGRESS && record.step in 0 until size) record.step else 0

    /**
     * Small "tips for this page?" offer on the first visit of a page — only for people who
     * skipped the full tour (it already covers every page) and never over a running tour.
     */
    fun shouldOfferPageTips(gettingStarted: TourStatus, pageSeen: Boolean, tourRunning: Boolean, hasPageTour: Boolean): Boolean =
        hasPageTour && !pageSeen && !tourRunning && gettingStarted == TourStatus.SKIPPED
}

/** One tap on the way to a step's screen: [target] is the tab/button, [route] where it leads. */
data class Hop(val target: String, val route: String, val hint: String)

/** How to get from one screen to another by tapping, for the "this is how you get there" animation. */
object TourPath {

    private val tabs = linkedMapOf(
        TourRoutes.HOME to "Home",
        TourRoutes.BLOCKLIST to "Blocklist",
        TourRoutes.SCHEDULES to "Schedules",
        TourRoutes.STATS to "Usage",
        TourRoutes.SETTINGS to "Settings",
    )

    private data class Sub(val parent: String, val via: String, val label: String)

    private val subScreens = mapOf(
        TourRoutes.SETUP to Sub(TourRoutes.SETTINGS, TourIds.SETTINGS_SETUP, "Setup wizard"),
        TourRoutes.HOSTS_LISTS to Sub(TourRoutes.BLOCKLIST, TourIds.BLOCKLIST_HOSTS, "Import blocklists"),
        TourRoutes.APP_PICKER to Sub(TourRoutes.BLOCKLIST, TourIds.BLOCKLIST_ADD_APPS, "Add apps to block"),
        TourRoutes.FEATURES to Sub(TourRoutes.BLOCKLIST, TourIds.BLOCKLIST_FEEDS, "Block in-app feeds"),
        TourRoutes.PROTECTION to Sub(TourRoutes.SETTINGS, TourIds.SETTINGS_BLOCK_PROTECTION, "Block protection"),
    )

    fun isTab(route: String?) = route in tabs

    fun knows(route: String) = route in tabs || route in subScreens

    /** Tab id of the bottom-bar item for [route]. */
    fun tabTarget(route: String) = "nav.$route"

    /** Taps leading from [from] to [to]; empty when already there or [to] is unknown. */
    fun plan(from: String?, to: String): List<Hop> {
        if (from == to) return emptyList()
        tabs[to]?.let { label -> return listOf(Hop(tabTarget(to), to, "Tap “$label” in the bar at the bottom.")) }
        val sub = subScreens[to] ?: return emptyList()
        return plan(from, sub.parent) + Hop(sub.via, to, "Then tap “${sub.label}”.")
    }
}

/** Where the guide card goes. */
enum class CardMode { SHEET_BOTTOM, SHEET_TOP, FLOATING }

data class CardPlacement(val mode: CardMode, val offset: Offset)

/**
 * Narrow screens get a sheet along the bottom (or the top, if the bottom would cover the
 * target). Wide screens get a card next to the target that stays inside the screen.
 */
fun placeCard(target: Rect?, screen: Size, card: Size, wide: Boolean, margin: Float, gap: Float): CardPlacement {
    val bottomY = screen.height - card.height - margin
    if (!wide) {
        if (target == null) return CardPlacement(CardMode.SHEET_BOTTOM, Offset(margin, bottomY))
        val bottomFree = bottomY - gap >= target.bottom
        val topFree = margin + card.height + gap <= target.top
        val top = when {
            bottomFree -> false
            topFree -> true
            else -> target.center.y > screen.height / 2
        }
        return if (top) CardPlacement(CardMode.SHEET_TOP, Offset(margin, margin)) else CardPlacement(CardMode.SHEET_BOTTOM, Offset(margin, bottomY))
    }
    if (target == null) {
        return CardPlacement(CardMode.FLOATING, Offset((screen.width - card.width) / 2, (screen.height - card.height) / 2))
    }
    val maxX = (screen.width - card.width - margin).coerceAtLeast(margin)
    val maxY = (screen.height - card.height - margin).coerceAtLeast(margin)
    val centeredX = (target.center.x - card.width / 2).coerceIn(margin, maxX)
    val below = target.bottom + gap
    val above = target.top - gap - card.height
    val offset = when {
        below + card.height <= screen.height - margin -> Offset(centeredX, below)
        above >= margin -> Offset(centeredX, above)
        // Neither fits (tall target): beside it, on the roomier side.
        target.left >= screen.width - target.right ->
            Offset((target.left - gap - card.width).coerceIn(margin, maxX), (target.center.y - card.height / 2).coerceIn(margin, maxY))
        else -> Offset((target.right + gap).coerceIn(margin, maxX), (target.center.y - card.height / 2).coerceIn(margin, maxY))
    }
    return CardPlacement(CardMode.FLOATING, offset)
}
