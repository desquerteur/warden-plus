package com.warden.blocker.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotionTest {

    private val tabs = setOf("home", "blocklist", "schedules", "stats", "settings")

    @Test fun removeAnimationsTurnsMotionOff() {
        assertFalse(motionAllowed(0f))
        assertTrue(motionAllowed(1f))
        assertTrue(motionAllowed(0.5f))
        assertTrue(motionAllowed(10f))
    }

    @Test fun tabsFadeSubScreensSlide() {
        // Tab to tab, also when leaving a sub-screen through the bottom bar.
        assertEquals(NavMotion.FADE, navMotion("home", "blocklist", popping = false, tabs = tabs, motion = true))
        assertEquals(NavMotion.FADE, navMotion("apppicker", "settings", popping = false, tabs = tabs, motion = true))
        // Back from a tab to Home fades too (no sideways slide between tabs).
        assertEquals(NavMotion.FADE, navMotion("stats", "home", popping = true, tabs = tabs, motion = true))
        // Into a sub-screen and back out of it.
        assertEquals(NavMotion.FORWARD, navMotion("blocklist", "apppicker", popping = false, tabs = tabs, motion = true))
        assertEquals(NavMotion.FORWARD, navMotion("setup", "protection", popping = false, tabs = tabs, motion = true))
        assertEquals(NavMotion.BACK, navMotion("apppicker", "blocklist", popping = true, tabs = tabs, motion = true))
        assertEquals(NavMotion.BACK, navMotion("protection", "setup", popping = true, tabs = tabs, motion = true))
    }

    @Test fun noScreenMotionWithAnimationsOff() {
        listOf(true, false).forEach { popping ->
            assertEquals(NavMotion.NONE, navMotion("home", "blocklist", popping, tabs, motion = false))
            assertEquals(NavMotion.NONE, navMotion("blocklist", "apppicker", popping, tabs, motion = false))
        }
    }

    @Test fun countUpNeverOvershootsAndLandsOnTheTarget() {
        assertEquals(0L, countUpValue(0f, 42))
        assertEquals(21L, countUpValue(21.4f, 42))
        assertEquals(42L, countUpValue(42f, 42))
        // Float rounding of big millis values must not show more than the real total.
        val millis = 7_654_321L
        assertEquals(millis, countUpValue(millis.toFloat(), millis))
        assertEquals(millis, countUpValue(millis.toFloat() + 5f, millis))
        assertEquals(0L, countUpValue(-3f, 10))
    }

    @Test fun tickIsDrawnFromShortArmToLongArm() {
        assertTrue(checkStroke(0f).isEmpty())
        assertTrue(checkStroke(-1f).isEmpty())
        // Early on only the short arm, growing from its start.
        val early = checkStroke(0.1f)
        assertEquals(2, early.size)
        assertEquals(0.27f to 0.52f, early[0])
        // Complete: start, corner, end; never past the end.
        val full = checkStroke(1f)
        assertEquals(3, full.size)
        assertClose(0.75f, full[2].first)
        assertClose(0.35f, full[2].second)
        assertEquals(full, checkStroke(2f))
        // The drawn length grows with progress.
        var last = 0f
        for (i in 1..20) {
            val len = length(checkStroke(i / 20f))
            assertTrue("length grows at ${i / 20f}", len >= last)
            last = len
        }
    }

    @Test fun pulseRingGrowsAndFades() {
        val (startScale, startAlpha) = pulseRing(0f)
        val (endScale, endAlpha) = pulseRing(1f)
        assertEquals(1f, startScale)
        assertTrue(startAlpha > 0f)
        assertTrue(endScale > startScale)
        assertEquals(0f, endAlpha)
        assertEquals(pulseRing(1f), pulseRing(3f))
    }

    @Test fun narrowScreensGetNarrowMargins() {
        assertEquals(12.dp, gutterFor(320))
        assertEquals(12.dp, gutterFor(344)) // Galaxy Z Fold cover display
        assertEquals(20.dp, gutterFor(360))
        assertEquals(20.dp, gutterFor(600))
    }

    private fun length(points: List<Pair<Float, Float>>): Float =
        points.zipWithNext().sumOf { (a, b) ->
            val dx = b.first - a.first
            val dy = b.second - a.second
            kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
        }.toFloat()

    private fun assertClose(expected: Float, actual: Float) = assertTrue("$actual ≈ $expected", abs(expected - actual) < 1e-4f)
}
