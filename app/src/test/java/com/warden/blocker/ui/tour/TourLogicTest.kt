package com.warden.blocker.ui.tour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TourLogicTest {

    private val withTarget = step("a", TourRoutes.HOME, "A", "a")
    private val centered = step(null, TourRoutes.HOME, "B", "b")
    private val centerIfMissing = step("c", TourRoutes.SETUP, "C", "c", ifMissing = MissingTarget.CENTER)

    @Test fun displayChoosesSpotlightCenterOrSkip() {
        assertEquals(StepDisplay.SPOTLIGHT, TourLogic.display(withTarget, targetFound = true))
        assertEquals(StepDisplay.SKIP, TourLogic.display(withTarget, targetFound = false))
        assertEquals(StepDisplay.CENTER, TourLogic.display(centered, targetFound = false))
        assertEquals(StepDisplay.CENTER, TourLogic.display(centerIfMissing, targetFound = false))
        assertEquals(StepDisplay.SPOTLIGHT, TourLogic.display(centerIfMissing, targetFound = true))
    }

    @Test fun nextAndBackWalkTheSteps() {
        val run = TourRun("t", index = 0)
        val second = TourLogic.next(run, 3)!!
        assertEquals(1, second.index)
        assertTrue(second.forward)
        assertEquals(2, TourLogic.next(second, 3)!!.index)
        assertNull("past the last step the tour is done", TourLogic.next(TourRun("t", 2), 3))
        val back = TourLogic.back(TourRun("t", 2))!!
        assertEquals(1, back.index)
        assertFalse(back.forward)
        assertNull("no step before the first", TourLogic.back(TourRun("t", 0)))
    }

    @Test fun missingTargetsAreSkippedInTheDirectionOfTravel() {
        // Forward: 1 is missing → 2.
        val forward = TourLogic.skipMissing(TourRun("t", 1, forward = true), 4)!!
        assertEquals(2, forward.index)
        assertEquals(setOf(1), forward.missing)
        // Back from 3 onto missing 2 → 1, and later moves step over 2 too.
        val back = TourLogic.skipMissing(TourRun("t", 2, forward = false), 4)!!
        assertEquals(1, back.index)
        assertEquals(3, TourLogic.next(back, 4)!!.index)
        // Back onto a missing first step turns around instead of getting stuck.
        assertEquals(1, TourLogic.skipMissing(TourRun("t", 0, forward = false), 4)!!.index)
        // Last step missing → tour is done.
        assertNull(TourLogic.skipMissing(TourRun("t", 3, forward = true), 4))
    }

    @Test fun backSkipsStepsFoundMissing() {
        val run = TourRun("t", index = 3, missing = setOf(1, 2))
        assertEquals(0, TourLogic.back(run)!!.index)
        assertNull(TourLogic.back(TourRun("t", 2, missing = setOf(0, 1))))
    }

    @Test fun progressCountsOnlyStepsThatShow() {
        assertEquals(1 to 8, TourLogic.progress(TourRun("t", 0), 8))
        assertEquals(3 to 8, TourLogic.progress(TourRun("t", 2), 8))
        // Steps 0 and 1 missing: step 2 is the first shown of 6.
        assertEquals(1 to 6, TourLogic.progress(TourRun("t", 2, missing = setOf(0, 1)), 8))
    }

    @Test fun missingAheadOnlyJudgesStepsOnTheOpenScreen() {
        val steps = listOf(
            step("s.a", TourRoutes.SETTINGS, "A", "a"),
            step("s.future", TourRoutes.SETTINGS, "F", "f"),
            step("h.x", TourRoutes.HOME, "X", "x"),
            step("s.b", TourRoutes.SETTINGS, "B", "b"),
            step("s.center", TourRoutes.SETTINGS, "C", "c", ifMissing = MissingTarget.CENTER),
        )
        val onScreen = setOf("s.a", "s.b")
        // The Home step can't be judged from Settings; a CENTER step is never missing.
        assertEquals(setOf(1), TourLogic.missingAhead(steps, 0, TourRoutes.SETTINGS) { it in onScreen })
        assertEquals(emptySet<Int>(), TourLogic.missingAhead(steps, 3, TourRoutes.SETTINGS) { it in onScreen })
        val run = TourRun("t", index = 0, missing = setOf(1))
        assertEquals(1 to 4, TourLogic.progress(run, steps.size))
        assertEquals(2, TourLogic.next(run, steps.size)!!.index)
    }

    @Test fun startIndexResumesOnlyUnfinishedTours() {
        assertEquals(4, TourLogic.startIndex(TourRecord(TourStatus.IN_PROGRESS, 4), 8, resume = true))
        assertEquals(0, TourLogic.startIndex(TourRecord(TourStatus.IN_PROGRESS, 4), 8, resume = false))
        assertEquals(0, TourLogic.startIndex(TourRecord(TourStatus.COMPLETED, 4), 8, resume = true))
        assertEquals("saved step beyond a shorter tour", 0, TourLogic.startIndex(TourRecord(TourStatus.IN_PROGRESS, 12), 8, resume = true))
    }

    @Test fun pageTipsOnlyAfterSkippingTheFullTour() {
        assertTrue(TourLogic.shouldOfferPageTips(TourStatus.SKIPPED, pageSeen = false, tourRunning = false, hasPageTour = true))
        assertFalse(TourLogic.shouldOfferPageTips(TourStatus.COMPLETED, false, false, true))
        assertFalse(TourLogic.shouldOfferPageTips(TourStatus.NEW, false, false, true))
        assertFalse(TourLogic.shouldOfferPageTips(TourStatus.SKIPPED, pageSeen = true, tourRunning = false, hasPageTour = true))
        assertFalse(TourLogic.shouldOfferPageTips(TourStatus.SKIPPED, false, tourRunning = true, hasPageTour = true))
        assertFalse(TourLogic.shouldOfferPageTips(TourStatus.SKIPPED, false, false, hasPageTour = false))
    }

    @Test fun recordRoundTripsAndToleratesJunk() {
        val record = TourRecord(TourStatus.IN_PROGRESS, 5)
        assertEquals(record, TourRecord.decode(record.encode()))
        assertEquals(TourRecord(), TourRecord.decode(null))
        assertEquals(TourRecord(), TourRecord.decode("WHATEVER:3"))
        assertEquals(TourRecord(TourStatus.SKIPPED, 0), TourRecord.decode("SKIPPED:x"))
        assertEquals(TourRecord(TourStatus.COMPLETED, 0), TourRecord.decode("COMPLETED"))
    }
}
