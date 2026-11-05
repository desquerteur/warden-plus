package com.warden.blocker.accessibility

/**
 * Keeps a blocked app blocked when it is on screen in a split screen or a pop-up (freeform)
 * window — not only when it is the one focused, full-screen app.
 *
 * Why this exists (issue #3, 0.3.7): on a Galaxy Fold the block screen could be turned into a
 * Samsung pop-up window, shrunk and pushed aside, and the blocked app underneath was usable.
 * [ForegroundGate] only looks at the *active* window and decides once per app, so a blocked app
 * that stays visible next to (or under) a moved block screen was never looked at again; and an
 * app opened in the unfocused half of a split screen was never checked at all.
 *
 * It looks at a snapshot of all visible application windows (after window changes have settled)
 * and answers with one [Step]:
 *  1. A *shielded* app (its block screen was shown) is still visible → the block screen is not
 *     covering it: go Home if the block screen is up next to it, else show the block screen again.
 *     After [maxQuietAttempts] tries it only acts again when the user reaches for the app (its
 *     window becomes active) — so a window that cannot be closed never makes the screen flicker.
 *     Once the app and the block screen are both gone (user went Home), it stays shielded for
 *     [lingerMs]: if it shows up again in that time without a block screen — an event the
 *     [ForegroundGate] never got, a hanging app — the block screen comes back.
 *  2. Another app is visible in a split / pop-up arrangement without being the focused window →
 *     ask whether it is blocked ([Step.CheckSide]), once it has been visible for [sideSettleMs].
 *
 * Never acts on home screens, Warden+ itself or other critical apps, on picture-in-picture, or on
 * small windows (toasts, popup menus). Plain full-screen use — a widget on the home screen, the
 * Discover page, an app switch still animating — never needs it: then no app window is split.
 * Every step is decided again by the access controller (a grant or the end of a schedule lets
 * the app through) and passes the loop guard in the service.
 *
 * Pure logic with an injected clock (`now`) so it is unit-tested without Android.
 */
class WindowShield(
    private val sideSettleMs: Long = SIDE_SETTLE_MS,
    private val sideRecheckMs: Long = SIDE_RECHECK_MS,
    private val maxQuietAttempts: Int = MAX_QUIET_ATTEMPTS,
    private val lingerMs: Long = LINGER_MS,
) {

    /** One visible application window, as the accessibility service sees it. */
    data class AppWindow(
        val pkg: String,
        /** The window the user is using (active or input focus). */
        val active: Boolean,
        /** Fills (nearly) the whole screen, i.e. not a split half or a pop-up. */
        val coversScreen: Boolean,
        /** Share of the screen it takes, 0..1. */
        val area: Float = if (coversScreen) 1f else 0.5f,
        val pip: Boolean = false,
    )

    sealed interface Step {
        data object None : Step
        /** A blocked app is on screen and the block screen isn't: show it again for [pkg]. */
        data class ShowBlockScreen(val pkg: String) : Step
        /** The block screen is up, but [pkg] is still visible beside or over it: go Home. */
        data class GoHome(val pkg: String) : Step
        /** [pkg] is visible in a split / pop-up window without focus: is it blocked? */
        data class CheckSide(val pkg: String) : Step
        /** Something may need a look once it has settled: assess again at [atMs]. */
        data class Later(val atMs: Long) : Step
    }

    /** The app whose block screen was shown last, while it may still be around. */
    var shielded: String? = null
        private set
    private var attempts = 0
    private var hiddenSince = 0L
    private val firstSeen = HashMap<String, Long>()
    private val allowedAt = HashMap<String, Long>()

    /** The block screen was shown for [pkg]. */
    @Synchronized
    fun shield(pkg: String) {
        if (shielded != pkg) {
            shielded = pkg
            attempts = 0
        }
        hiddenSince = 0L
    }

    /** [pkg] may be used right now (no block, a grant, schedule over): stop guarding it. */
    @Synchronized
    fun allowed(pkg: String, now: Long) {
        if (shielded == pkg) {
            shielded = null
            attempts = 0
        }
        allowedAt[pkg] = now
    }

    @Synchronized
    fun assess(windows: List<AppWindow>, ownPkg: String, isCritical: (String) -> Boolean, now: Long): Step {
        // Picture-in-picture and tiny windows (toasts, popup menus) are left alone.
        val shown = windows.filter { !it.pip && it.area >= MIN_AREA }
        val visible = shown.mapTo(HashSet()) { it.pkg }
        // Apps that left the screen start from scratch when they come back.
        firstSeen.keys.retainAll(visible)
        allowedAt.keys.retainAll(visible)
        for (pkg in visible) firstSeen.putIfAbsent(pkg, now)

        shieldStep(shown, visible, ownPkg, isCritical, now)?.let { return it }
        return sideStep(shown, ownPkg, isCritical, now)
    }

    private fun shieldStep(
        shown: List<AppWindow>,
        visible: Set<String>,
        ownPkg: String,
        isCritical: (String) -> Boolean,
        now: Long,
    ): Step? {
        val pkg = shielded ?: return null
        if (isCritical(pkg)) {
            shielded = null
            return null
        }
        if (pkg !in visible) {
            attempts = 0
            if (ownPkg in visible) {
                hiddenSince = 0L // hidden behind the block screen: as it should be
            } else if (hiddenSince == 0L) {
                hiddenSince = now // both gone: the user moved on, most likely
            } else if (now - hiddenSince >= lingerMs) {
                shielded = null
                hiddenSince = 0L
            }
            return null
        }
        hiddenSince = 0L
        val reachedFor = shown.any { it.pkg == pkg && it.active }
        if (attempts >= maxQuietAttempts && !reachedFor) return Step.None
        attempts++
        return if (ownPkg in visible) Step.GoHome(pkg) else Step.ShowBlockScreen(pkg)
    }

    private fun sideStep(shown: List<AppWindow>, ownPkg: String, isCritical: (String) -> Boolean, now: Long): Step {
        // Only a real multi-window arrangement: an app window that doesn't fill the screen.
        // Full-screen apps, the home screen with its widgets and Discover never get here.
        if (shown.all { it.coversScreen }) return Step.None
        var later = Long.MAX_VALUE
        for (w in shown) {
            if (w.active || w.pkg == ownPkg || w.pkg == shielded || isCritical(w.pkg)) continue
            // The focused app is ForegroundGate's job; this one is visible without focus.
            if (shown.any { it.pkg == w.pkg && it.active }) continue
            val allowed = allowedAt[w.pkg]
            val due = maxOf(
                (firstSeen[w.pkg] ?: now) + sideSettleMs,
                if (allowed != null) allowed + sideRecheckMs else 0L,
            )
            if (now >= due) {
                // Asked; until the answer comes, don't ask again.
                allowedAt[w.pkg] = now
                return Step.CheckSide(w.pkg)
            }
            later = minOf(later, due)
        }
        return if (later != Long.MAX_VALUE) Step.Later(later) else Step.None
    }

    companion object {
        const val SIDE_SETTLE_MS = 700L
        const val SIDE_RECHECK_MS = 5_000L
        const val MAX_QUIET_ATTEMPTS = 2
        const val LINGER_MS = 60_000L

        /** Windows smaller than this share of the screen are toasts and menus, not an app in use. */
        const val MIN_AREA = 0.08f

        /** A window covering at least this much of the screen's width and height counts as full screen. */
        const val COVER_WIDTH = 0.9f
        const val COVER_HEIGHT = 0.8f

        fun covers(width: Int, height: Int, screenWidth: Int, screenHeight: Int): Boolean =
            screenWidth > 0 && screenHeight > 0 &&
                width >= screenWidth * COVER_WIDTH && height >= screenHeight * COVER_HEIGHT

        fun area(width: Int, height: Int, screenWidth: Int, screenHeight: Int): Float =
            if (screenWidth <= 0 || screenHeight <= 0) 1f
            else (width.toFloat() * height / (screenWidth.toFloat() * screenHeight)).coerceIn(0f, 1f)

        /** One shield per process: the service guards, the block screen reports when it's left. */
        val shared = WindowShield()
    }
}
