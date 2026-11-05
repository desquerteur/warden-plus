package com.warden.blocker.accessibility

import com.warden.blocker.accessibility.WindowShield.AppWindow
import com.warden.blocker.accessibility.WindowShield.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowShieldTest {

    private val launcher = "com.sec.android.app.launcher"
    private val google = "com.google.android.googlequicksearchbox"
    private val untis = "com.grupet.web.app"
    private val chrome = "com.android.chrome"
    private val warden = "com.warden.blocker"
    private val critical = { p: String -> p == launcher || p == warden || p == "com.android.systemui" }

    private fun full(pkg: String, active: Boolean = false) = AppWindow(pkg, active, coversScreen = true, area = 1f)
    private fun popup(pkg: String, active: Boolean = false) = AppWindow(pkg, active, coversScreen = false, area = 0.25f)
    private fun half(pkg: String, active: Boolean = false) = AppWindow(pkg, active, coversScreen = false, area = 0.5f)

    private fun WindowShield.at(now: Long, vararg windows: AppWindow) = assess(windows.toList(), warden, critical, now)

    // --- Issue #3: the block screen pushed aside in a pop-up ---

    @Test fun blockScreenMovedIntoPopUpOverBlockedAppGoesHome() {
        val shield = WindowShield()
        shield.shield(untis)
        // Untis full screen underneath, the block screen as a small pop-up on top of it.
        assertEquals(Step.GoHome(untis), shield.at(1_000, full(untis), popup(warden, active = true)))
    }

    @Test fun userTouchingTheBlockedAppBehindAMovedBlockScreenGoesHome() {
        val shield = WindowShield()
        shield.shield(untis)
        assertEquals(Step.GoHome(untis), shield.at(1_000, full(untis, active = true), popup(warden)))
    }

    @Test fun blockedAppVisibleWithoutBlockScreenShowsItAgain() {
        val shield = WindowShield()
        shield.shield(untis)
        assertEquals(Step.ShowBlockScreen(untis), shield.at(1_000, full(launcher, active = true), popup(untis)))
    }

    @Test fun blockScreenCoveringTheAppIsLeftAlone() {
        val shield = WindowShield()
        shield.shield(untis)
        // The blocked app's window is hidden behind the full-screen block screen.
        assertEquals(Step.None, shield.at(1_000, full(warden, active = true)))
        assertEquals(untis, shield.shielded)
    }

    @Test fun leavingForHomeEndsTheShieldAfterAWhile() {
        val shield = WindowShield(lingerMs = 60_000)
        shield.shield(untis)
        // Back on the home screen with widgets: nothing happens.
        assertEquals(Step.None, shield.at(1_000, full(launcher, active = true)))
        assertEquals(Step.None, shield.at(30_000, full(launcher, active = true)))
        assertEquals(untis, shield.shielded)
        assertEquals(Step.None, shield.at(61_000, full(launcher, active = true)))
        assertNull(shield.shielded)
    }

    @Test fun reopenedSoonWithoutBlockScreenShowsItAgain() {
        val shield = WindowShield(lingerMs = 60_000)
        shield.shield(untis)
        assertEquals(Step.None, shield.at(1_000, full(launcher, active = true)))
        // Untis back full screen and no block screen (the open event got lost, the app hung...).
        assertEquals(Step.ShowBlockScreen(untis), shield.at(5_000, full(untis, active = true)))
    }

    @Test fun lingerClockStartsOverWhileTheBlockScreenIsUp() {
        val shield = WindowShield(lingerMs = 10_000)
        shield.shield(untis)
        assertEquals(Step.None, shield.at(1_000, full(launcher, active = true)))
        assertEquals(Step.None, shield.at(5_000, full(warden, active = true)))
        assertEquals(Step.None, shield.at(9_000, full(launcher, active = true)))
        assertEquals(Step.None, shield.at(15_000, full(launcher, active = true)))
        assertEquals(untis, shield.shielded)
        assertEquals(Step.None, shield.at(19_000, full(launcher, active = true)))
        assertNull(shield.shielded)
    }

    @Test fun grantOrEndOfBlockEndsTheShield() {
        val shield = WindowShield()
        shield.shield(untis)
        shield.allowed(untis, 1_000)
        assertNull(shield.shielded)
        // "Continue for 5 min": Untis in front, block screen gone. Not touched again.
        assertEquals(Step.None, shield.at(1_500, full(untis, active = true)))
    }

    @Test fun windowThatCantBeClosedIsNotHammered() {
        val shield = WindowShield(maxQuietAttempts = 2)
        shield.shield(untis)
        // A floating pop-up that neither Home nor the block screen gets rid of.
        assertEquals(Step.GoHome(untis), shield.at(1_000, popup(untis), full(warden, active = true)))
        assertEquals(Step.ShowBlockScreen(untis), shield.at(2_000, popup(untis), full(launcher, active = true)))
        assertEquals(Step.None, shield.at(3_000, popup(untis), full(warden, active = true)))
        assertEquals(Step.None, shield.at(4_000, popup(untis), full(launcher, active = true)))
        // ...until the user reaches for it again.
        assertEquals(Step.ShowBlockScreen(untis), shield.at(5_000, popup(untis, active = true), full(launcher)))
    }

    @Test fun attemptsStartOverOnceTheAppWasGone() {
        val shield = WindowShield(maxQuietAttempts = 1)
        shield.shield(untis)
        assertEquals(Step.GoHome(untis), shield.at(1_000, popup(untis), full(warden, active = true)))
        assertEquals(Step.None, shield.at(2_000, popup(untis), full(warden, active = true)))
        assertEquals(Step.None, shield.at(3_000, full(warden, active = true)))
        assertEquals(Step.GoHome(untis), shield.at(4_000, popup(untis), full(warden, active = true)))
    }

    @Test fun pictureInPictureIsNotAnUse() {
        val shield = WindowShield()
        shield.shield(untis)
        val pip = AppWindow(untis, active = false, coversScreen = false, area = 0.1f, pip = true)
        assertEquals(Step.None, shield.at(1_000, pip, full(warden, active = true)))
    }

    @Test fun criticalAppIsNeverShielded() {
        val shield = WindowShield()
        shield.shield(launcher)
        assertEquals(Step.None, shield.at(1_000, full(launcher, active = true), popup(warden)))
        assertNull(shield.shielded)
    }

    // --- Apps in split screens / pop-ups that never had focus ---

    @Test fun appInUnfocusedSplitHalfIsCheckedOnceSettled() {
        val shield = WindowShield(sideSettleMs = 700)
        assertEquals(Step.Later(1_700), shield.at(1_000, half(chrome, active = true), half(untis)))
        assertEquals(Step.Later(1_700), shield.at(1_300, half(chrome, active = true), half(untis)))
        assertEquals(Step.CheckSide(untis), shield.at(1_700, half(chrome, active = true), half(untis)))
    }

    @Test fun appUnderAPopUpIsChecked() {
        val shield = WindowShield(sideSettleMs = 0)
        // Untis full screen, another app's pop-up focused on top of it.
        assertEquals(Step.CheckSide(untis), shield.at(1_000, full(untis), popup(chrome, active = true)))
    }

    @Test fun popUpOverTheHomeScreenIsChecked() {
        val shield = WindowShield(sideSettleMs = 0)
        assertEquals(Step.CheckSide(untis), shield.at(1_000, full(launcher, active = true), popup(untis)))
    }

    @Test fun sideCheckIsAskedOnceThenAfterAWhile() {
        val shield = WindowShield(sideSettleMs = 0, sideRecheckMs = 5_000)
        assertEquals(Step.CheckSide(untis), shield.at(1_000, half(chrome, active = true), half(untis)))
        // Answer pending / allowed: not asked again right away.
        shield.allowed(untis, 1_100)
        assertEquals(Step.Later(6_100), shield.at(2_000, half(chrome, active = true), half(untis)))
        assertEquals(Step.CheckSide(untis), shield.at(6_100, half(chrome, active = true), half(untis)))
    }

    @Test fun sideCheckStartsOverWhenTheAppComesBack() {
        val shield = WindowShield(sideSettleMs = 0, sideRecheckMs = 60_000)
        assertEquals(Step.CheckSide(untis), shield.at(1_000, half(chrome, active = true), half(untis)))
        shield.allowed(untis, 1_000)
        assertEquals(Step.None, shield.at(2_000, full(chrome, active = true)))
        assertEquals(Step.CheckSide(untis), shield.at(3_000, half(chrome, active = true), half(untis)))
    }

    @Test fun blockedSideAppBecomesShielded() {
        val shield = WindowShield(sideSettleMs = 0)
        assertEquals(Step.CheckSide(untis), shield.at(1_000, half(chrome, active = true), half(untis)))
        shield.shield(untis) // the service found it blocked and showed the block screen
        assertEquals(Step.ShowBlockScreen(untis), shield.at(2_000, half(chrome, active = true), half(untis)))
    }

    @Test fun focusedWindowIsLeftToTheForegroundGate() {
        val shield = WindowShield(sideSettleMs = 0)
        assertEquals(Step.CheckSide(chrome), shield.at(1_000, half(untis, active = true), half(chrome)))
        assertFalse(shield.at(2_000, half(untis, active = true), half(chrome)) == Step.CheckSide(untis))
    }

    // --- Must never touch the home screen (0.2.2 / 0.2.3 lockout) ---

    @Test fun homeScreenWithWidgetsIsNeverChecked() {
        val shield = WindowShield(sideSettleMs = 0)
        // Widgets are views inside the launcher's window: only the launcher's window is listed.
        assertEquals(Step.None, shield.at(1_000, full(launcher, active = true)))
    }

    @Test fun discoverPageOverTheHomeScreenIsNeverChecked() {
        val shield = WindowShield(sideSettleMs = 0)
        // Google's Discover page lies full screen over the launcher; not a split arrangement.
        assertEquals(Step.None, shield.at(1_000, full(launcher), full(google, active = true)))
        assertEquals(Step.None, shield.at(2_000, full(launcher, active = true), full(google)))
    }

    @Test fun appSwitchStillAnimatingIsNeverChecked() {
        val shield = WindowShield(sideSettleMs = 0)
        assertEquals(Step.None, shield.at(1_000, full(untis), full(chrome, active = true)))
    }

    @Test fun toastsAndMenusAreIgnored() {
        val shield = WindowShield(sideSettleMs = 0)
        val toast = AppWindow(untis, active = false, coversScreen = false, area = 0.02f)
        assertEquals(Step.None, shield.at(1_000, full(launcher, active = true), toast))
    }

    @Test fun launcherAndWardenInSplitAreNeverChecked() {
        val shield = WindowShield(sideSettleMs = 0)
        assertEquals(Step.None, shield.at(1_000, half(launcher), half(warden), half(chrome, active = true)))
    }

    // --- Helpers ---

    @Test fun coverMeasuresAgainstTheScreen() {
        // Galaxy Fold 4 inner screen 1812 x 2176.
        assertTrue(WindowShield.covers(1812, 2176, 1812, 2176))
        assertTrue(WindowShield.covers(1812, 2050, 1812, 2176)) // without the status bar
        assertFalse(WindowShield.covers(900, 2176, 1812, 2176)) // split half
        assertFalse(WindowShield.covers(1000, 1200, 1812, 2176)) // pop-up
        assertFalse(WindowShield.covers(1812, 2176, 0, 0)) // screen unknown
        assertEquals(0.25f, WindowShield.area(906, 1088, 1812, 2176), 0.01f)
    }
}
