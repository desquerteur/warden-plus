package com.warden.blocker.accessibility

/**
 * Decides whether a window event really means "a blocked app is now in front" — so the block
 * screen only ever covers an app the user actually opened, never the home screen.
 *
 * Why this exists (0.2.2 lockout): a home-screen widget is drawn by the launcher, but its
 * views carry the widget owner's package (e.g. the Google search bar reports
 * `com.google.android.googlequicksearchbox`). Those views send window events too, so going
 * Home looked like "Google opened" and the block screen appeared — and "Go home" led straight
 * back into it. Overlays, keyboards, notifications, bubbles, picture-in-picture and the
 * launcher's Discover page do the same.
 *
 * The rules, in order:
 *  1. The event's package must own the *active application window* (what the user is looking
 *     at). Anything else — a widget inside the launcher, an overlay, a keyboard — is ignored.
 *  2. Home screens and other critical apps are never blocked, whatever the event says.
 *  3. A window that isn't one of the app's own activities (a [confirmed] activity) is only
 *     trusted when the user is coming from another regular app, never over the home screen.
 *  4. Right after the block screen sent the user Home there is a short grace period; an event
 *     in it is re-checked once it is over instead of blocking straight away.
 *  5. Safety net ([allowIntercept]): if the block screen keeps coming back for the same app in
 *     a few seconds, stop and tell the user instead of locking them out.
 *
 * Pure logic with an injected clock (`now`) so it is unit-tested without Android.
 */
class ForegroundGate(
    private val graceMs: Long = GRACE_MS,
    private val settleMs: Long = SETTLE_MS,
    private val maxRechecks: Int = MAX_RECHECKS,
    private val loopWindowMs: Long = LOOP_WINDOW_MS,
    private val loopLimitUnconfirmed: Int = LOOP_LIMIT_UNCONFIRMED,
    private val loopLimitConfirmed: Int = LOOP_LIMIT_CONFIRMED,
    private val loopPauseMs: Long = LOOP_PAUSE_MS,
) {

    sealed interface Verdict {
        /** Not a reason to block (widget, overlay, home screen, same app as before, ...). */
        data object Ignore : Verdict
        /** [pkg] really came to the front: ask the access controller. */
        data class Check(val pkg: String, val confirmed: Boolean) : Verdict
        /** Not sure yet (window still switching, grace period): evaluate again at [atMs]. */
        data class RecheckAt(val pkg: String, val confirmed: Boolean, val atMs: Long, val attempt: Int) : Verdict
    }

    enum class Intercept { SHOW, SUPPRESS, SUPPRESS_AND_NOTIFY }

    /** The app the user is in, as far as we last decided. Starts unknown. */
    var foreground: String? = null
        private set

    @Volatile private var graceUntil = 0L
    private val intercepts = HashMap<String, ArrayDeque<Long>>()
    private val pausedUnconfirmed = HashMap<String, Long>()
    private val pausedConfirmed = HashMap<String, Long>()

    /**
     * A `TYPE_WINDOW_STATE_CHANGED` event from [eventPkg].
     *
     * @param activeAppPkg package of the active (focused) application window, or null if
     *   Android didn't tell us. Never the package of a widget, overlay or keyboard.
     * @param confirmed the event names one of [eventPkg]'s own activities — the app was
     *   really opened, not just one of its views shown somewhere else.
     * @param attempt 0 for a fresh event, > 0 when re-checking a [Verdict.RecheckAt].
     */
    @Synchronized
    fun onWindowStateChanged(
        eventPkg: String,
        activeAppPkg: String?,
        confirmed: Boolean,
        isCritical: (String) -> Boolean,
        now: Long,
        attempt: Int = 0,
    ): Verdict {
        if (activeAppPkg != null && isCritical(activeAppPkg)) {
            // Home screen, Settings, phone, Warden+ itself in front: the user has left whatever
            // app was there before.
            foreground = activeAppPkg
        } else if (activeAppPkg == null && isCritical(eventPkg)) {
            // Android often can't name the focused window mid-switch. The home screen or the
            // block screen coming up still means the previous app is gone — otherwise reopening
            // it would look like "same app as before" and slip through.
            foreground = eventPkg
        }
        if (activeAppPkg != eventPkg) {
            // A widget on the home screen, an overlay, a keyboard... — or a real app whose
            // window hasn't taken focus yet. Only the latter is worth a second look.
            return if (confirmed && !isCritical(eventPkg) && attempt < maxRechecks) {
                Verdict.RecheckAt(eventPkg, confirmed, now + settleMs, attempt + 1)
            } else {
                Verdict.Ignore
            }
        }
        // Never blocked, whatever the event says.
        if (isCritical(eventPkg)) return Verdict.Ignore
        val pkg: String = eventPkg
        if (pkg == foreground) return Verdict.Ignore // already decided for this app
        if (!confirmed) {
            // Not one of the app's own activities. Over the home screen this is the launcher's
            // Discover page, an assistant panel and the like — never block those.
            val from = foreground
            if (from == null || isCritical(from)) return Verdict.Ignore
        }
        if (now < graceUntil) {
            // Just sent Home from the block screen. Look again when the dust has settled; if the
            // app is really in front then, it is blocked then.
            return if (confirmed) Verdict.RecheckAt(pkg, true, graceUntil, attempt) else Verdict.Ignore
        }
        foreground = pkg
        return Verdict.Check(pkg, confirmed)
    }

    /** The block screen sent the user to the home screen (Go home / Back). */
    fun noteWentHome(now: Long) {
        graceUntil = now + graceMs
    }

    fun inGrace(now: Long): Boolean = now < graceUntil

    /** [pkg] turned out not to be in front after all: check it again when it next shows up. */
    @Synchronized
    fun forget(pkg: String) {
        if (foreground == pkg) foreground = null
    }

    /**
     * Whether in-app feature detection (Reels, Shorts, ...) may look at the screen for
     * [eventPkg]: only inside that app's own active window, never on the home screen
     * (a YouTube widget is not YouTube) and not right after the user was sent Home.
     */
    fun mayScanFeatures(eventPkg: String, activeWindowPkg: String?, isCritical: (String) -> Boolean, now: Long): Boolean =
        activeWindowPkg == eventPkg && !isCritical(eventPkg) && !inGrace(now)

    /**
     * Call right before showing the block screen for [key] (a package, or a feature key).
     * Allows it, unless the block screen keeps reappearing for the same thing — then we're in a
     * loop the user can't get out of, so let them through for a minute and say so (once).
     *
     * A [confirmed] app open gets a much higher limit: a person reopening a blocked app on
     * purpose must not be able to wear the block down.
     */
    @Synchronized
    fun allowIntercept(key: String, confirmed: Boolean, now: Long): Intercept {
        val paused = if (confirmed) pausedConfirmed else pausedUnconfirmed
        if (now < (paused[key] ?: 0L)) {
            foreground = null // so the next real open is checked again once the pause ends
            return Intercept.SUPPRESS
        }
        val times = intercepts.getOrPut(key) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() >= loopWindowMs) times.removeFirst()
        val limit = if (confirmed) loopLimitConfirmed else loopLimitUnconfirmed
        if (times.size >= limit) {
            times.clear()
            pausedUnconfirmed[key] = now + loopPauseMs
            if (confirmed) pausedConfirmed[key] = now + loopPauseMs
            foreground = null
            return Intercept.SUPPRESS_AND_NOTIFY
        }
        times.addLast(now)
        return Intercept.SHOW
    }

    companion object {
        const val GRACE_MS = 2_500L
        const val SETTLE_MS = 400L
        const val MAX_RECHECKS = 3
        const val LOOP_WINDOW_MS = 10_000L
        const val LOOP_LIMIT_UNCONFIRMED = 3
        const val LOOP_LIMIT_CONFIRMED = 6
        const val LOOP_PAUSE_MS = 60_000L

        /** One gate per process: the service decides, the block screen reports "went Home". */
        val shared = ForegroundGate()
    }
}
