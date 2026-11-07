package com.warden.blocker.accessibility

import com.warden.blocker.accessibility.WindowShield.AppWindow
import com.warden.blocker.accessibility.WindowShield.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #3, second report: on Samsung a blocked app's pop-up window floats above the full-screen
 * block screen (and above Home). The shield then walks a ladder: window button (Maximize /
 * Close), Back while the pop-up has focus, Home, block screen — then gives up and tells the user.
 */
class WindowShieldLadderTest {

    private val launcher = "com.sec.android.app.launcher"
    private val clock = "com.sec.android.app.clockpackage"
    private val chrome = "com.android.chrome"
    private val warden = "com.warden.blocker"
    private val critical = { p: String -> p == launcher || p == warden || p == "com.android.systemui" }

    private fun full(pkg: String, active: Boolean = false) = AppWindow(pkg, active, coversScreen = true, area = 1f)
    private fun popup(pkg: String, active: Boolean = false) = AppWindow(pkg, active, coversScreen = false, area = 0.3f)
    private fun half(pkg: String, active: Boolean = false) = AppWindow(pkg, active, coversScreen = false, area = 0.5f)

    private fun WindowShield.at(now: Long, vararg windows: AppWindow) = assess(windows.toList(), warden, critical, now)

    private fun shield() = WindowShield(remedyGapMs = 700, startingMs = 1_200, retryMs = 15_000)

    @Test fun popUpUsedOverTheBlockScreenWalksTheWholeLadder() {
        val shield = shield()
        shield.shield(clock)
        // The block screen full screen, the blocked Clock as a pop-up on top of it, in use.
        val screen = arrayOf(full(warden), popup(clock, active = true))
        assertEquals(Step.PressWindowButton(clock), shield.at(1_000, *screen))
        // Window events in between don't speed it up.
        assertEquals(Step.Later(1_700), shield.at(1_200, *screen))
        // Second press: the first one may only have opened the caption menu.
        assertEquals(Step.PressWindowButton(clock), shield.at(1_700, *screen))
        assertEquals(Step.Back(clock), shield.at(2_400, *screen))
        assertEquals(Step.Back(clock), shield.at(3_100, *screen))
        assertEquals(Step.GoHome(clock), shield.at(3_800, *screen))
        shield.shield(clock, 3_800) // the service pressed Home
        assertEquals(Step.Later(5_000), shield.at(4_000, *screen))
        // The block screen is already up: nothing left to try, tell the user once.
        assertEquals(Step.GaveUp(clock), shield.at(5_000, *screen))
        // Still being used: start over, but only after a while.
        assertEquals(Step.Later(20_000), shield.at(5_700, *screen))
        assertEquals(Step.PressWindowButton(clock), shield.at(20_000, *screen))
    }

    @Test fun backIsOnlyUsedWhileThePopUpHasFocus() {
        val shield = shield()
        shield.shield(clock)
        // Back would go to the block screen (= Home), not to the pop-up.
        val screen = arrayOf(full(warden, active = true), popup(clock))
        assertEquals(Step.PressWindowButton(clock), shield.at(1_000, *screen))
        assertEquals(Step.PressWindowButton(clock), shield.at(1_700, *screen))
        assertEquals(Step.GoHome(clock), shield.at(2_400, *screen))
        shield.shield(clock, 2_400)
        assertEquals(Step.GaveUp(clock), shield.at(3_600, *screen))
        // Left alone: quiet, no flicker.
        assertEquals(Step.None, shield.at(10_000, *screen))
        assertEquals(Step.None, shield.at(30_000, *screen))
    }

    @Test fun noWindowButtonGoesStraightOn() {
        val shield = shield()
        shield.shield(clock)
        val screen = arrayOf(full(warden), popup(clock, active = true))
        assertEquals(Step.PressWindowButton(clock), shield.at(1_000, *screen))
        shield.noWindowButton(clock) // e.g. a split half: no caption at all
        assertEquals(Step.Back(clock), shield.at(1_050, *screen))
    }

    @Test fun maximizedPopUpIsCoveredByTheBlockScreen() {
        val shield = shield()
        shield.shield(clock)
        assertEquals(Step.PressWindowButton(clock), shield.at(1_000, full(warden), popup(clock, active = true)))
        // "Maximize" worked: Clock full screen on top, the block screen behind it.
        assertEquals(Step.ShowBlockScreen(clock), shield.at(1_700, full(clock, active = true)))
        shield.shield(clock, 1_700)
        assertEquals(Step.None, shield.at(3_000, full(warden, active = true)))
        // Made a pop-up again later: the ladder starts from the top.
        assertEquals(Step.PressWindowButton(clock), shield.at(4_000, full(warden), popup(clock, active = true)))
    }

    @Test fun closedPopUpEndsIt() {
        val shield = shield()
        shield.shield(clock)
        assertEquals(Step.PressWindowButton(clock), shield.at(1_000, full(warden), popup(clock, active = true)))
        // "Close" worked: only the block screen is left.
        assertEquals(Step.None, shield.at(1_700, full(warden, active = true)))
        assertEquals(clock, shield.shielded)
    }

    @Test fun popUpOverTheHomeScreenEndsWithTheBlockScreen() {
        val shield = shield()
        shield.shield(clock)
        val screen = arrayOf(full(launcher, active = true), popup(clock))
        assertEquals(Step.PressWindowButton(clock), shield.at(1_000, *screen))
        assertEquals(Step.PressWindowButton(clock), shield.at(1_700, *screen))
        assertEquals(Step.GoHome(clock), shield.at(2_400, *screen))
        shield.shield(clock, 2_400)
        assertEquals(Step.ShowBlockScreen(clock), shield.at(3_600, *screen))
        shield.shield(clock, 3_600)
        assertEquals(Step.GaveUp(clock), shield.at(4_800, *screen))
    }

    @Test fun justLaunchedBlockScreenGetsTimeToCoverThePopUp() {
        val shield = shield()
        shield.shield(clock, 1_000)
        // On stock Android the new full-screen block screen covers the pop-up within a moment.
        assertEquals(Step.Later(2_200), shield.at(1_500, full(launcher, active = true), popup(clock)))
        assertEquals(Step.None, shield.at(2_200, full(warden, active = true)))
    }

    @Test fun splitHalfIsTreatedLikeAPopUp() {
        val shield = shield()
        shield.shield(clock)
        assertEquals(Step.PressWindowButton(clock), shield.at(1_000, half(warden, active = true), half(clock)))
    }

    @Test fun grantInTheMiddleStopsTheLadder() {
        val shield = shield()
        shield.shield(clock)
        assertEquals(Step.PressWindowButton(clock), shield.at(1_000, full(warden), popup(clock, active = true)))
        shield.allowed(clock, 1_200) // "Continue for 5 min" / the schedule ended
        assertNull(shield.shielded)
        val step = shield.at(1_700, full(chrome), popup(clock, active = true))
        assertFalse(step is Step.PressWindowButton || step is Step.Back || step is Step.GoHome)
    }

    @Test fun ladderStartsOverOnceThePopUpWasGone() {
        val shield = shield()
        shield.shield(clock)
        val screen = arrayOf(full(warden, active = true), popup(clock))
        shield.at(1_000, *screen)
        shield.at(1_700, *screen)
        shield.at(2_400, *screen)
        assertEquals(Step.GaveUp(clock), shield.at(3_100, *screen))
        assertEquals(Step.None, shield.at(4_000, full(warden, active = true)))
        assertEquals(Step.PressWindowButton(clock), shield.at(5_000, *screen))
    }

    @Test fun homeScreenAndWardenAreNeverPressed() {
        val shield = shield()
        shield.shield(launcher)
        assertEquals(Step.None, shield.at(1_000, full(chrome, active = true), popup(launcher)))
        assertNull(shield.shielded)
        // Warden+'s own pop-up (the block screen pushed aside) is not a blocked app.
        assertEquals(Step.None, shield.at(2_000, full(launcher, active = true), popup(warden)))
    }

    @Test fun pictureInPictureIsLeftAlone() {
        val shield = shield()
        shield.shield(clock)
        val pip = AppWindow(clock, active = false, coversScreen = false, area = 0.1f, pip = true)
        assertEquals(Step.None, shield.at(1_000, full(warden, active = true), pip))
    }

    @Test fun ladderIsInThePromisedOrder() {
        assertEquals(
            listOf(
                WindowShield.Remedy.WINDOW_BUTTON, WindowShield.Remedy.WINDOW_BUTTON,
                WindowShield.Remedy.BACK, WindowShield.Remedy.BACK,
                WindowShield.Remedy.HOME, WindowShield.Remedy.BLOCK_SCREEN,
            ),
            WindowShield.LADDER,
        )
        // At most 6 actions per ladder: within the loop guard's 6 per 10 s for confirmed opens.
        assertEquals(true, WindowShield.LADDER.size <= ForegroundGate.LOOP_LIMIT_CONFIRMED)
    }
}
