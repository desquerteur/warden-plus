package com.warden.blocker.accessibility

import com.warden.blocker.accessibility.ForegroundGate.Intercept
import com.warden.blocker.accessibility.ForegroundGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundGateTest {

    private val launcher = "com.sec.android.app.launcher"
    private val google = "com.google.android.googlequicksearchbox"
    private val insta = "com.instagram.android"
    private val chrome = "com.android.chrome"
    private val warden = "com.warden.blocker"
    private val critical = { p: String -> p == launcher || p == warden || p == "com.android.systemui" }

    private fun ForegroundGate.event(pkg: String, active: String?, confirmed: Boolean, now: Long = 1_000L, attempt: Int = 0) =
        onWindowStateChanged(pkg, active, confirmed, critical, now, attempt)

    /** The 0.2.2 lockout: the Google search widget on the home screen reports Google's package. */
    @Test fun widgetOnHomeScreenNeverBlocks() {
        val gate = ForegroundGate()
        gate.event(launcher, launcher, confirmed = true)
        assertEquals(Verdict.Ignore, gate.event(google, launcher, confirmed = false))
        assertEquals(launcher, gate.foreground)
    }

    @Test fun launcherInFrontIsNeverBlockedWhateverTheEventSays() {
        val gate = ForegroundGate()
        // Even an event that looks like a real Google activity, while the launcher holds focus,
        // only gets a bounded re-check — and on re-check the launcher is still in front.
        val first = gate.event(google, launcher, confirmed = true)
        assertTrue(first is Verdict.RecheckAt)
        first as Verdict.RecheckAt
        assertEquals(Verdict.Ignore, gate.event(google, launcher, true, first.atMs, first.attempt).let {
            // keeps re-checking at most MAX_RECHECKS times, never Check
            var v = it; var n = 0
            while (v is Verdict.RecheckAt && n < 10) { v = gate.event(google, launcher, true, v.atMs, v.attempt); n++ }
            v
        })
        assertEquals(launcher, gate.foreground)
    }

    @Test fun launcherEventItselfIsIgnored() {
        val gate = ForegroundGate()
        assertEquals(Verdict.Ignore, gate.event(launcher, launcher, confirmed = true))
        assertEquals(Verdict.Ignore, gate.event(launcher, launcher, confirmed = false))
    }

    @Test fun reallyOpenedAppIsChecked() {
        val gate = ForegroundGate()
        gate.event(launcher, launcher, true)
        assertEquals(Verdict.Check(google, true), gate.event(google, google, confirmed = true))
        assertEquals(google, gate.foreground)
    }

    @Test fun sameAppTwiceIsCheckedOnce() {
        val gate = ForegroundGate()
        gate.event(launcher, launcher, true)
        assertTrue(gate.event(insta, insta, true) is Verdict.Check)
        assertEquals(Verdict.Ignore, gate.event(insta, insta, true, now = 1_500L))
        assertEquals(Verdict.Ignore, gate.event(insta, insta, false, now = 1_600L))
    }

    @Test fun windowNotFocusedYetIsRecheckedThenChecked() {
        val gate = ForegroundGate(settleMs = 400)
        gate.event(launcher, launcher, true)
        val v = gate.event(insta, launcher, confirmed = true, now = 1_000L)
        assertEquals(Verdict.RecheckAt(insta, true, 1_400L, 1), v)
        assertEquals(Verdict.Check(insta, true), gate.event(insta, insta, true, now = 1_400L, attempt = 1))
    }

    @Test fun unknownActiveWindowIsRecheckedOnlyForRealActivities() {
        val gate = ForegroundGate()
        gate.event(launcher, launcher, true)
        assertTrue(gate.event(insta, null, confirmed = true) is Verdict.RecheckAt)
        assertEquals(Verdict.Ignore, gate.event(insta, null, confirmed = false))
    }

    @Test fun overlaysKeyboardsAndNotificationsDontCount() {
        val gate = ForegroundGate()
        gate.event(chrome, chrome, true)
        // keyboard / bubble / PiP of a blocked app on top of Chrome: not the active app window
        assertEquals(Verdict.Ignore, gate.event(insta, chrome, confirmed = false))
        assertEquals(Verdict.Ignore, gate.event("com.android.systemui", chrome, confirmed = false))
        assertEquals(chrome, gate.foreground)
    }

    /** E.g. the launcher's Discover page: a Google window over the home screen, no Google activity. */
    @Test fun nonActivityWindowOverHomeScreenNeverBlocks() {
        val gate = ForegroundGate()
        gate.event(launcher, launcher, true)
        assertEquals(Verdict.Ignore, gate.event(google, google, confirmed = false))
    }

    @Test fun nonActivityWindowWhenServiceJustStartedNeverBlocks() {
        assertEquals(Verdict.Ignore, ForegroundGate().event(google, google, confirmed = false))
    }

    @Test fun nonActivityWindowComingFromAnotherAppIsChecked() {
        val gate = ForegroundGate()
        gate.event(chrome, chrome, true)
        assertEquals(Verdict.Check(insta, false), gate.event(insta, insta, confirmed = false))
    }

    @Test fun graceAfterGoHomeDefersThenStillBlocksARealOpen() {
        val gate = ForegroundGate(graceMs = 2_500)
        gate.event(launcher, launcher, true, now = 0L)
        gate.noteWentHome(now = 1_000L)
        assertTrue(gate.inGrace(1_000L))
        // the user taps the blocked app right away: not blocked yet, but looked at again
        assertEquals(Verdict.RecheckAt(insta, true, 3_500L, 0), gate.event(insta, insta, true, now = 1_200L))
        assertEquals(Verdict.Check(insta, true), gate.event(insta, insta, true, now = 3_500L))
        assertFalse(gate.inGrace(3_500L))
    }

    @Test fun graceAfterGoHomeDropsLooseEvents() {
        val gate = ForegroundGate(graceMs = 2_500)
        gate.event(chrome, chrome, true, now = 0L)
        gate.noteWentHome(now = 1_000L)
        assertEquals(Verdict.Ignore, gate.event(insta, insta, false, now = 1_200L))
    }

    @Test fun graceRecheckFindsHomeScreenAndDoesNothing() {
        val gate = ForegroundGate(graceMs = 2_500)
        gate.noteWentHome(now = 0L)
        gate.event(chrome, chrome, true, now = 100L).let { v ->
            v as Verdict.RecheckAt
            // by then the user is on the home screen
            assertEquals(Verdict.Ignore, gate.event(chrome, launcher, true, now = v.atMs, attempt = v.attempt).let {
                if (it is Verdict.RecheckAt) gate.event(chrome, launcher, true, it.atMs, ForegroundGate.MAX_RECHECKS) else it
            })
        }
    }

    @Test fun blockScreenItselfResetsForegroundSoReopeningIsCheckedAgain() {
        val gate = ForegroundGate()
        gate.event(launcher, launcher, true)
        assertTrue(gate.event(insta, insta, true) is Verdict.Check)
        gate.event(warden, warden, true) // block screen shown
        assertTrue(gate.event(insta, insta, true, now = 5_000L) is Verdict.Check) // back via Recents
    }

    /** Seen in the emulator: mid-switch Android reports no focused window at all. */
    @Test fun homeOrBlockScreenWithUnknownActiveWindowStillMeansTheAppWasLeft() {
        val gate = ForegroundGate()
        gate.event(launcher, launcher, true)
        assertTrue(gate.event(insta, insta, true) is Verdict.Check)
        gate.event(warden, null, false) // block screen, window not named yet
        assertEquals(warden, gate.foreground)
        gate.event(launcher, null, false) // home, window not named yet
        assertEquals(launcher, gate.foreground)
        assertTrue(gate.event(insta, insta, true, now = 9_000L) is Verdict.Check)
    }

    @Test fun widgetEventWithUnknownActiveWindowChangesNothing() {
        val gate = ForegroundGate()
        gate.event(launcher, launcher, true)
        assertEquals(Verdict.Ignore, gate.event(google, null, confirmed = false))
        assertEquals(launcher, gate.foreground)
    }

    @Test fun featureScanOnlyInsideTheAppsOwnWindow() {
        val gate = ForegroundGate()
        val yt = "com.google.android.youtube"
        assertTrue(gate.mayScanFeatures(yt, yt, critical, now = 1_000L))
        assertFalse(gate.mayScanFeatures(yt, launcher, critical, now = 1_000L)) // YouTube widget
        assertFalse(gate.mayScanFeatures(launcher, launcher, critical, now = 1_000L))
        gate.noteWentHome(now = 1_000L)
        assertFalse(gate.mayScanFeatures(yt, yt, critical, now = 1_500L))
    }

    @Test fun loopBreakerStopsAfterThreeUnconfirmedInTenSeconds() {
        val gate = ForegroundGate()
        assertEquals(Intercept.SHOW, gate.allowIntercept(google, false, 0L))
        assertEquals(Intercept.SHOW, gate.allowIntercept(google, false, 2_000L))
        assertEquals(Intercept.SHOW, gate.allowIntercept(google, false, 4_000L))
        assertEquals(Intercept.SUPPRESS_AND_NOTIFY, gate.allowIntercept(google, false, 6_000L))
        // notified once, then quietly let through for the pause
        assertEquals(Intercept.SUPPRESS, gate.allowIntercept(google, false, 7_000L))
        assertEquals(Intercept.SUPPRESS, gate.allowIntercept(google, false, 60_000L))
        // pause over: blocks again
        assertEquals(Intercept.SHOW, gate.allowIntercept(google, false, 66_000L))
    }

    @Test fun loopBreakerIgnoresSlowRepeats() {
        val gate = ForegroundGate()
        for (i in 0 until 10) assertEquals(Intercept.SHOW, gate.allowIntercept(google, false, i * 6_000L))
    }

    @Test fun loopBreakerIsPerApp() {
        val gate = ForegroundGate()
        repeat(3) { gate.allowIntercept(google, false, it * 100L) }
        assertEquals(Intercept.SHOW, gate.allowIntercept(insta, false, 400L))
    }

    @Test fun deliberateOpensGetAMuchHigherLimit() {
        val gate = ForegroundGate()
        // Someone reopening a blocked app on purpose doesn't wear the block down quickly...
        for (i in 0 until 6) assertEquals(Intercept.SHOW, gate.allowIntercept(insta, true, i * 1_000L))
        // ...but a genuine machine-speed loop still can't lock the user out.
        assertEquals(Intercept.SUPPRESS_AND_NOTIFY, gate.allowIntercept(insta, true, 6_500L))
    }

    @Test fun pauseFromUnconfirmedLoopStillBlocksDeliberateOpens() {
        val gate = ForegroundGate()
        repeat(3) { gate.allowIntercept(google, false, it * 100L) }
        assertEquals(Intercept.SUPPRESS_AND_NOTIFY, gate.allowIntercept(google, false, 400L))
        assertEquals(Intercept.SHOW, gate.allowIntercept(google, true, 500L))
    }

    @Test fun loopBreakResetsForegroundSoTheNextOpenIsCheckedAgain() {
        val gate = ForegroundGate()
        gate.event(chrome, chrome, true)
        gate.event(insta, insta, true)
        repeat(3) { gate.allowIntercept(insta, false, it * 100L) }
        gate.allowIntercept(insta, false, 400L)
        assertEquals(null, gate.foreground)
    }

    @Test fun forgetOnlyClearsThatApp() {
        val gate = ForegroundGate()
        gate.event(chrome, chrome, true)
        gate.forget(insta)
        assertEquals(chrome, gate.foreground)
        gate.forget(chrome)
        assertEquals(null, gate.foreground)
    }
}
