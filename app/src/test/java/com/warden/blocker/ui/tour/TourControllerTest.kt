package com.warden.blocker.ui.tour

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TourControllerTest {

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val tour = Tour(
        "demo", "Demo",
        listOf(
            step("a", TourRoutes.HOME, "A", "a"),
            step("b", TourRoutes.BLOCKLIST, "B", "b"),
            step("c", TourRoutes.SETTINGS, "C", "c"),
        ),
    )

    private fun newStore(name: String = "tours") =
        TourStore(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("$name.preferences_pb") })

    private fun controller(store: TourStore) = TourController(store, scope) { id -> if (id == tour.id) tour else null }

    @After fun tearDown() = scope.cancel()

    private fun <T> await(flow: Flow<T>, until: (T) -> Boolean): T = runBlocking { withTimeout(5_000) { flow.first(until) } }

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
        assertEquals(1 to 2, TourLogic.progress(c.run.value!!, 3))
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
        assertTrue(await(store.records) { it.isEmpty() }.isEmpty())
        await(c.records) { it != null && it.isEmpty() }
        assertEquals(TourStatus.NEW, c.status("demo"))
    }

    @Test fun unknownTourDoesNothing() {
        val c = controller(newStore())
        c.start("nope")
        runBlocking { kotlinx.coroutines.delay(200) }
        assertNull(c.run.value)
    }
}
