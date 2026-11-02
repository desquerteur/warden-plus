package com.warden.blocker.ui.wizard

import com.warden.blocker.ui.tour.SettingsGuide
import com.warden.blocker.ui.tour.Tours
import com.warden.blocker.ui.wizard.WizardStep.APP_BLOCKING
import com.warden.blocker.ui.wizard.WizardStep.BATTERY
import com.warden.blocker.ui.wizard.WizardStep.DONE
import com.warden.blocker.ui.wizard.WizardStep.FIRST_BLOCKS
import com.warden.blocker.ui.wizard.WizardStep.NOTIFICATIONS
import com.warden.blocker.ui.wizard.WizardStep.PROTECTION
import com.warden.blocker.ui.wizard.WizardStep.USAGE
import com.warden.blocker.ui.wizard.WizardStep.WEBSITE_BLOCKING
import com.warden.blocker.ui.wizard.WizardStep.WELCOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardLogicTest {

    private val android14 = 34
    private val android12 = 31

    @Test fun notificationsPageOnlyOnAndroid13AndUp() {
        assertEquals(7, WizardLogic.tasks(android14).size)
        assertEquals(NOTIFICATIONS, WizardLogic.tasks(android14).first())
        assertEquals(6, WizardLogic.tasks(android12).size)
        assertFalse(NOTIFICATIONS in WizardLogic.steps(android12))
        assertEquals(APP_BLOCKING, WizardLogic.next(WELCOME, android12))
    }

    @Test fun walkingForwardAndBack() {
        assertEquals(NOTIFICATIONS, WizardLogic.next(WELCOME, android14))
        assertEquals(DONE, WizardLogic.next(FIRST_BLOCKS, android14))
        assertEquals(DONE, WizardLogic.next(DONE, android14))
        assertEquals(WEBSITE_BLOCKING, WizardLogic.back(USAGE, android14))
        assertNull("nothing before Welcome", WizardLogic.back(WELCOME, android14))
    }

    @Test fun stepXOfY() {
        assertNull(WizardLogic.position(WELCOME, android14))
        assertNull(WizardLogic.position(DONE, android14))
        assertEquals(1 to 7, WizardLogic.position(NOTIFICATIONS, android14))
        assertEquals(3 to 7, WizardLogic.position(WEBSITE_BLOCKING, android14))
        assertEquals(2 to 6, WizardLogic.position(WEBSITE_BLOCKING, android12))
    }

    @Test fun openProgressAndSkipped() {
        val done = setOf(NOTIFICATIONS, APP_BLOCKING, WEBSITE_BLOCKING)
        assertEquals(3 to 7, WizardLogic.progress(android14, done))
        assertEquals(listOf(USAGE, BATTERY, PROTECTION, FIRST_BLOCKS), WizardLogic.open(android14, done, emptySet()))
        // "Later" takes a page off the open list, but it still isn't done.
        assertEquals(listOf(BATTERY, PROTECTION, FIRST_BLOCKS), WizardLogic.open(android14, done, setOf(USAGE)))
        assertEquals(3 to 7, WizardLogic.progress(android14, done))
        // Done wins over skipped.
        assertTrue(WizardLogic.open(android14, done + USAGE, setOf(USAGE)).none { it == USAGE })
    }

    @Test fun resumesWhereItWasLeft() {
        assertEquals(BATTERY, WizardLogic.currentStep(WizardState(WizardStatus.IN_PROGRESS, BATTERY), android14))
        // Saved on a newer Android, opened on an older one.
        assertEquals(WELCOME, WizardLogic.currentStep(WizardState(step = NOTIFICATIONS), android12))
    }

    @Test fun opensByItselfOnlyAfterAFreshInstall() {
        assertTrue(WizardLogic.shouldAutoOpen(WizardStatus.NEW, freshInstall = true))
        assertTrue("killed half-way: opens again", WizardLogic.shouldAutoOpen(WizardStatus.IN_PROGRESS, freshInstall = true))
        assertFalse("update of an older version", WizardLogic.shouldAutoOpen(WizardStatus.NEW, freshInstall = false))
        assertFalse(WizardLogic.shouldAutoOpen(WizardStatus.DISMISSED, freshInstall = true))
        assertFalse(WizardLogic.shouldAutoOpen(WizardStatus.FINISHED, freshInstall = true))
    }

    @Test fun homeCardWhileSomethingIsOpen() {
        val all = WizardLogic.tasks(android14).toSet()
        assertFalse("everything done", WizardLogic.showHomeCard(android14, all, emptySet()))
        assertTrue(WizardLogic.showHomeCard(android14, all - BATTERY, emptySet()))
        assertFalse("battery put off", WizardLogic.showHomeCard(android14, all - BATTERY, setOf(BATTERY)))
        // App blocking missing: Home's own "Finish setup" card is shown instead.
        assertFalse(WizardLogic.showHomeCard(android14, all - APP_BLOCKING - BATTERY, emptySet()))
    }

    @Test fun autoAdvanceOnlyWhenItBecameDone() {
        assertTrue(WizardLogic.shouldAutoAdvance(APP_BLOCKING, doneOnEnter = false, doneNow = true))
        assertFalse("already done when the page opened", WizardLogic.shouldAutoAdvance(APP_BLOCKING, doneOnEnter = true, doneNow = true))
        assertFalse(WizardLogic.shouldAutoAdvance(APP_BLOCKING, doneOnEnter = false, doneNow = false))
        assertFalse("choices are made on the page itself", WizardLogic.shouldAutoAdvance(PROTECTION, doneOnEnter = false, doneNow = true))
    }

    @Test fun requiredPagesAndAndroidGuides() {
        assertEquals(setOf(APP_BLOCKING, WEBSITE_BLOCKING), WizardStep.entries.filter { it.task && !it.optional }.toSet())
        // Every Android-settings walk-through is used by the wizard or the Protection guide.
        val used = WizardStep.entries.flatMap { it.guides } + Tours.protection.steps.mapNotNull { it.guide }
        assertEquals(SettingsGuide.entries.toSet(), used.toSet())
    }
}
