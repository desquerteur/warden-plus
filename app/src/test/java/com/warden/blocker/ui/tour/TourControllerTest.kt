package com.warden.blocker.ui.tour

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TourControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tour = Tour(
        "demo", "Demo",
        listOf(
            step("a", TourRoutes.HOME, "A", "a"),
            step("b", TourRoutes.BLOCKLIST, "B", "b"),
            step("c", TourRoutes.SETTINGS, "C", "c"),
        ),
    )

    private fun newStore() = TourStore(MemoryDataStore())

    private fun controller(store: TourStore) = TourController(store, scope) { id -> if (id == tour.id) tour else null }

    @After fun tearDown() = scope.cancel()

    // Says what it saw last when the expected value never comes.
    private fun <T> await(flow: Flow<T>, until: (T) -> Boolean): T = runBlocking {
        var last: Any? = "nothing"
        val found = withTimeoutOrNull(5_000) { flow.first { last = it; until(it) }.let { listOf(it) } }
        (found ?: throw AssertionError("Timed out; last value: $last")).single()
    }

    @Test fun completingSavesTheStatus() {
        val store = newStore()
        val c = controller(store)
        c.start("demo")
        await(c.run) { it != null }
        c.next(); c.next()
        assertEquals(2, c.run.value!!.index)
        c.next()
        assertNull(c.run.value)
        val saved = await(store.records) { it["demo"]?.status == TourStatus.COMPLETED }
        assertEquals(TourRecord(TourStatus.COMPLETED, 2), saved["demo"])
    }

    @Test fun skippingSavesSkipped() {
        val store = newStore()
        val c = controller(store)
        c.start("demo")
        await(c.run) { it != null }
        c.skip()
        assertNull(c.run.value)
        await(store.records) { it["demo"]?.status == TourStatus.SKIPPED }
    }

    @Test fun halfDoneTourResumesWhereItStopped() {
        val store = newStore()
        val first = controller(store)
        first.start("demo")
        await(first.run) { it != null }
        first.next()
        await(store.records) { it["demo"] == TourRecord(TourStatus.IN_PROGRESS, 1) }

        // Next app start: a new controller on the same file.
        val second = controller(store)
        second.start("demo", resume = true)
        assertEquals(1, await(second.run) { it != null }!!.index)
        // A restart from Settings begins at step 1 again.
        second.skip()
        second.start("demo")
        assertEquals(0, await(second.run) { it != null }!!.index)
    }

    @Test fun missingTargetsAreSkippedAndAllMissingShowsANotice() {
        val c = controller(newStore())
        c.start("demo")
        await(c.run) { it != null }
        c.targetMissing()
        assertEquals(1, c.run.value!!.index)
        assertEquals(2 to 3, TourLogic.progress(c.run.value!!, 3))
        c.targetMissing(); c.targetMissing()
        assertNull(c.run.value)
        assertNotNull("nothing was shown, so the user is told why", c.emptyNotice.value)
    }

    @Test fun pagesSeenAndResetAll() {
        val store = newStore()
        val c = controller(store)
        c.markPageSeen(TourRoutes.BLOCKLIST)
        assertTrue(TourRoutes.BLOCKLIST in await(store.pagesSeen) { it.isNotEmpty() })
        c.skip() // no tour running: nothing happens
        c.start("demo")
        await(c.run) { it != null }
        c.skip()
        await(store.records) { it.isNotEmpty() }
        c.resetAll()
        await(store.pagesSeen) { it.isEmpty() }
        // Page tips follow a skipped "Getting started", so they show again.
        val records = await(store.records) { it[Tours.GETTING_STARTED]?.status == TourStatus.SKIPPED }
        assertNull(records["demo"])
        await(c.records) { it?.get("demo") == null }
        assertEquals(TourStatus.NEW, c.status("demo"))
    }

    @Test fun unknownTourDoesNothing() {
        val c = controller(newStore())
        c.start("nope")
        runBlocking { kotlinx.coroutines.delay(200) }
        assertNull(c.run.value)
    }
}
