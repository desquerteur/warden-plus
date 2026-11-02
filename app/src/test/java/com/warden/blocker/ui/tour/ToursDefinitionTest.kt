package com.warden.blocker.ui.tour

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class ToursDefinitionTest {

    private val knownTargets: Set<String> = TourIds::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.get(null) as String }
        .toSet()

    @Test fun toursAreShortAndUnique() {
        assertEquals(Tours.all.size, Tours.all.map { it.id }.toSet().size)
        Tours.all.forEach { tour ->
            assertTrue("${tour.id}: 1–10 steps", tour.steps.size in 1..10)
            assertTrue("${tour.id} has a title", tour.title.isNotBlank())
        }
    }

    @Test fun everyStepPointsAtAKnownScreenAndTarget() {
        Tours.all.flatMap { it.steps }.forEach { s ->
            s.route?.let { assertTrue("route $it of “${s.title}”", TourPath.knows(it)) }
            s.target?.let { assertTrue("target $it of “${s.title}” is in TourIds", it in knownTargets) }
            assertTrue("“${s.title}” has a short text", s.text.isNotBlank() && s.text.length <= 140)
        }
    }

    @Test fun everyStepCanBeReachedFromHome() {
        Tours.all.flatMap { it.steps }.mapNotNull { it.route }.distinct().forEach { route ->
            val hops = TourPath.plan(TourRoutes.HOME, route)
            if (route != TourRoutes.HOME) assertEquals("way to $route", route, hops.last().route)
        }
    }

    @Test fun futureProtectionStepsAreDefinedAndSkippable() {
        val ids = Tours.protection.steps.mapNotNull { it.target }
        listOf(
            TourIds.SETTINGS_BLOCK_PROTECTION,
            TourIds.SETTINGS_PROTECTION_CHECKLIST,
            TourIds.SETTINGS_GUARD_SYSTEM,
            TourIds.SETTINGS_DEVICE_OWNER,
        ).forEach { future ->
            assertTrue(future in ids)
            val s = Tours.protection.steps.first { it.target == future }
            assertEquals(StepDisplay.SKIP, TourLogic.display(s, targetFound = false))
        }
    }

    @Test fun setupTourCoversEveryAndroidSettingsGuide() {
        val used = Tours.setup.steps.mapNotNull { it.guide }.toSet()
        assertEquals(SettingsGuide.entries.toSet(), used)
    }

    @Test fun settingsGuidesDescribeEveryTap() {
        SettingsGuide.entries.forEach { g ->
            val lines = g.instructions()
            assertEquals(g.frames.size, lines.size)
            g.frames.forEach { f ->
                if (f.tap >= 0) assertTrue("${g.name}: tap row exists", f.tap < f.rows.size)
                if (f.tap == MiniFrame.TAP_MENU) assertTrue("${g.name}: menu needs an entry", f.menu != null)
                assertTrue("${g.name}: fits the mini screen", f.rows.size <= 5)
            }
        }
        val accessibility = SettingsGuide.ACCESSIBILITY.instructions()
        assertTrue(accessibility[1].contains("Installed apps"))
        assertTrue(accessibility[3].contains("switch “On” on"))
        assertTrue(SettingsGuide.RESTRICTED.instructions()[2].contains("Allow restricted settings"))
        assertTrue(SettingsGuide.OTHER_VPN.instructions().last().contains("off"))
        assertEquals("Settings › Accessibility › Installed apps › Warden+ App Blocker › Allow full control?", SettingsGuide.ACCESSIBILITY.path)
    }

    // --- card placement ---

    private val phone = Size(1080f, 2200f)
    private val card = Size(1040f, 700f)

    @Test fun narrowSheetAvoidsTheTarget() {
        val top = Rect(20f, 200f, 1060f, 400f)
        assertEquals(CardMode.SHEET_BOTTOM, placeCard(top, phone, card, wide = false, margin = 20f, gap = 20f).mode)
        val bottomBar = Rect(0f, 2000f, 1080f, 2200f)
        val placement = placeCard(bottomBar, phone, card, wide = false, margin = 20f, gap = 20f)
        assertEquals(CardMode.SHEET_TOP, placement.mode)
        assertEquals(20f, placement.offset.y)
        assertEquals(CardMode.SHEET_BOTTOM, placeCard(null, phone, card, wide = false, margin = 20f, gap = 20f).mode)
    }

    @Test fun wideCardStaysOnScreen() {
        val screen = Size(1800f, 2100f)
        val small = Size(800f, 600f)
        val nearRightEdge = Rect(1600f, 300f, 1780f, 400f)
        val below = placeCard(nearRightEdge, screen, small, wide = true, margin = 24f, gap = 16f)
        assertEquals(CardMode.FLOATING, below.mode)
        assertEquals(416f, below.offset.y)
        assertEquals(1800f - 800f - 24f, below.offset.x)
        // Near the bottom: above the target.
        val nearBottom = Rect(100f, 1900f, 300f, 2000f)
        assertEquals(1900f - 16f - 600f, placeCard(nearBottom, screen, small, true, 24f, 16f).offset.y)
        // Too tall for above or below: beside it, inside the screen.
        val tall = Rect(100f, 100f, 700f, 2000f)
        val beside = placeCard(tall, screen, small, true, 24f, 16f)
        assertEquals(716f, beside.offset.x)
        assertTrue(beside.offset.y in 24f..(2100f - 600f - 24f))
    }
}
