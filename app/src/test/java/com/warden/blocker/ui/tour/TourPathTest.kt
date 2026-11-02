package com.warden.blocker.ui.tour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TourPathTest {

    private fun List<Hop>.routes() = map { it.route }
    private fun List<Hop>.targets() = map { it.target }

    @Test fun sameScreenNeedsNoTaps() {
        assertTrue(TourPath.plan(TourRoutes.HOME, TourRoutes.HOME).isEmpty())
        assertTrue(TourPath.plan(TourRoutes.SETUP, TourRoutes.SETUP).isEmpty())
    }

    @Test fun tabToTabTapsTheBottomBar() {
        val hops = TourPath.plan(TourRoutes.HOME, TourRoutes.BLOCKLIST)
        assertEquals(listOf("nav.blocklist"), hops.targets())
        assertEquals(listOf(TourRoutes.BLOCKLIST), hops.routes())
        assertTrue(hops.single().hint.contains("Blocklist"))
    }

    @Test fun subScreenGoesThroughItsTabAndButton() {
        val hops = TourPath.plan(TourRoutes.HOME, TourRoutes.SETUP)
        assertEquals(listOf(TourPath.tabTarget(TourRoutes.SETTINGS), TourIds.SETTINGS_SETUP), hops.targets())
        assertEquals(listOf(TourRoutes.SETTINGS, TourRoutes.SETUP), hops.routes())
        // Already on the parent tab: only the button.
        assertEquals(listOf(TourIds.SETTINGS_SETUP), TourPath.plan(TourRoutes.SETTINGS, TourRoutes.SETUP).targets())
    }

    @Test fun fromSubScreenToTabOrAnotherSubScreen() {
        assertEquals(listOf("nav.settings"), TourPath.plan(TourRoutes.SETUP, TourRoutes.SETTINGS).targets())
        assertEquals(
            listOf("nav.blocklist", TourIds.BLOCKLIST_HOSTS),
            TourPath.plan(TourRoutes.SETUP, TourRoutes.HOSTS_LISTS).targets(),
        )
    }

    @Test fun blockProtectionIsReachedThroughSettings() {
        assertEquals(
            listOf("nav.settings", TourIds.SETTINGS_BLOCK_PROTECTION),
            TourPath.plan(TourRoutes.HOME, TourRoutes.PROTECTION).targets(),
        )
        assertTrue(TourPath.knows(TourRoutes.PROTECTION))
    }

    @Test fun unknownStartStillFindsTheWayAndUnknownTargetHasNone() {
        assertEquals(listOf(TourRoutes.SETTINGS, TourRoutes.SETUP), TourPath.plan(null, TourRoutes.SETUP).routes())
        assertTrue(TourPath.plan(TourRoutes.HOME, "nowhere").isEmpty())
    }
}
