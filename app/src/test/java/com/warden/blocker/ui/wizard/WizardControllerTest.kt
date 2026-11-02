package com.warden.blocker.ui.wizard

import com.warden.blocker.ui.tour.MemoryDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WizardControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sdk = 34

    private fun newStore() = WizardStore(MemoryDataStore())

    @After fun tearDown() = scope.cancel()

    // Says what it saw last when the expected value never comes.
    private fun <T> await(flow: Flow<T>, until: (T) -> Boolean): T = runBlocking {
        var last: Any? = "nothing"
        val found = withTimeoutOrNull(5_000) { flow.first { last = it; until(it) }.let { listOf(it) } }
        (found ?: throw AssertionError("Timed out; last value: $last")).single()
    }

    @Test fun movesAreSavedInOrder() {
        val store = newStore()
        val c = WizardController(store, scope, sdk)
        c.opened()
        c.next(WizardStep.WELCOME)
        c.next(WizardStep.NOTIFICATIONS)
        c.later(WizardStep.APP_BLOCKING)
        val s = await(store.state) { it.step == WizardStep.WEBSITE_BLOCKING }
        assertEquals(WizardStatus.IN_PROGRESS, s.status)
        assertEquals(setOf(WizardStep.APP_BLOCKING), s.skipped)
        c.back(WizardStep.WEBSITE_BLOCKING)
        await(store.state) { it.step == WizardStep.APP_BLOCKING }
    }

    @Test fun reopeningContinuesAndAFinishedWizardOpensOnTheSummary() {
        val store = newStore()
        val first = WizardController(store, scope, sdk)
        first.opened()
        first.goTo(WizardStep.BATTERY)
        await(store.state) { it.step == WizardStep.BATTERY }

        // Next app start.
        val second = WizardController(store, scope, sdk)
        second.opened()
        assertEquals(WizardStep.BATTERY, WizardLogic.currentStep(await(second.state) { it?.status == WizardStatus.IN_PROGRESS }!!, sdk))
        second.finish()
        await(store.state) { it.status == WizardStatus.FINISHED }
        second.goTo(WizardStep.USAGE) // tapped a line in the summary
        second.opened()
        assertEquals(WizardStep.DONE, await(store.state) { it.step == WizardStep.DONE }.step)
        // Home card: straight to the open page, even in a finished wizard.
        second.resumeAt(WizardStep.USAGE)
        second.opened()
        await(store.state) { it.step == WizardStep.USAGE && it.status == WizardStatus.IN_PROGRESS }
    }

    @Test fun dismissKeepsAFinishedWizardFinished() {
        val store = newStore()
        val c = WizardController(store, scope, sdk)
        c.dismiss()
        await(store.state) { it.status == WizardStatus.DISMISSED }
        c.finish()
        c.dismiss()
        runBlocking { kotlinx.coroutines.delay(200) }
        assertEquals(WizardStatus.FINISHED, await(store.state) { true }.status)
    }

    @Test fun protectionChoiceAndHidingTheHomeCard() {
        val store = newStore()
        val c = WizardController(store, scope, sdk)
        c.chooseProtection()
        c.skipAll(listOf(WizardStep.USAGE, WizardStep.BATTERY))
        val s = await(store.state) { it.protectionChosen && it.skipped.size == 2 }
        assertTrue(WizardStep.BATTERY in s.skipped)
        val done = WizardLogic.tasks(sdk).toSet() - WizardStep.USAGE - WizardStep.BATTERY
        assertTrue(!WizardLogic.showHomeCard(sdk, done, s.skipped))
    }
}
