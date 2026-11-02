package com.warden.blocker.guard

import com.warden.blocker.feature.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Node(
    override val text: String? = null,
    override val contentDesc: String? = null,
    override val viewId: String? = null,
    val children: List<Node> = emptyList(),
) : UiNode {
    override val childCount get() = children.size
    override fun child(index: Int): UiNode? = children.getOrNull(index)
}

class ScreenSnapshotTest {
    @Test fun collectsTextsInScreenOrderAndNormalizes() {
        val tree = Node(children = listOf(Node(text = "App INFO"), Node(contentDesc = "Force  stop", viewId = "x:id/button")))
        val s = ScreenSnapshot.fromTree("p", null, "Title", tree)
        assertEquals(listOf("title", "app info", "force stop"), s.allTexts)
        assertTrue(s.hasViewIdContaining("button"))
    }

    @Test fun nodeLimitIsRespected() {
        val tree = Node(children = List(50) { Node(text = "t$it") })
        assertEquals(9, ScreenSnapshot.fromTree("p", null, null, tree, maxNodes = 10).allTexts.size)
    }

    @Test fun exactPhraseNeedsWholeText() {
        val s = ScreenSnapshot("p", texts = listOf("Not optimized"))
        assertFalse(s.hasAny(exactly("optimized")))
        assertTrue(s.hasAny(containing("optimized")))
    }
}

class GuardThrottleTest {
    @Test fun oneHintPerVisit() {
        val t = GuardThrottle(quietMs = 5_000, repeatMs = 60_000)
        assertTrue(t.shouldWarn(GuardTarget.APP_INFO, 0))
        assertFalse(t.shouldWarn(GuardTarget.APP_INFO, 300))
        assertFalse(t.shouldWarn(GuardTarget.APP_INFO, 4_000))
        assertTrue(t.shouldWarn(GuardTarget.VPN, 4_100)) // another page
    }

    @Test fun newVisitAfterQuietTime() {
        val t = GuardThrottle(quietMs = 5_000, repeatMs = 60_000)
        assertTrue(t.shouldWarn(GuardTarget.APP_INFO, 0))
        assertTrue(t.shouldWarn(GuardTarget.APP_INFO, 6_000))
    }

    @Test fun longStayRepeatsOncePerMinute() {
        val t = GuardThrottle(quietMs = 5_000, repeatMs = 60_000)
        assertTrue(t.shouldWarn(GuardTarget.APP_INFO, 0))
        var now = 0L
        var hints = 0
        while (now < 59_000) { now += 1_000; if (t.shouldWarn(GuardTarget.APP_INFO, now)) hints++ }
        assertEquals(0, hints)
        assertTrue(t.shouldWarn(GuardTarget.APP_INFO, 60_000))
    }
}

class ClockWatchTest {
    private fun reading(wall: Long, elapsed: Long, boot: Int = 7, zone: String = "Europe/Berlin", offset: Int = 3_600_000, auto: Boolean = false) =
        ClockReading(wall, elapsed, boot, zone, offset, autoTime = auto, autoZone = auto)

    private val start = reading(1_700_000_000_000, 10_000)
    private val anchor = ClockWatch.check(null, start).anchor

    @Test fun firstReadingOnlyAnchors() = assertNull(ClockWatch.check(null, start).change)

    @Test fun normalTimePassingIsNoChange() =
        assertNull(ClockWatch.check(anchor, reading(start.wallMs + 3_600_000, start.elapsedMs + 3_600_000)).change)

    @Test fun smallNetworkCorrectionIsIgnored() =
        assertNull(ClockWatch.check(anchor, reading(start.wallMs + 60_000 + 30_000, start.elapsedMs + 60_000)).change)

    @Test fun clockSetForwardByHand() {
        val c = ClockWatch.check(anchor, reading(start.wallMs + 3 * 3_600_000, start.elapsedMs + 60_000)).change!!
        assertEquals(ClockChangeKind.FORWARD, c.kind)
        assertTrue(c.suspicious)
        assertEquals(3 * 3_600_000L - 60_000, c.deltaMs)
    }

    @Test fun clockSetBackWithAutoTimeIsNotSuspicious() {
        val c = ClockWatch.check(anchor, reading(start.wallMs - 600_000, start.elapsedMs + 1_000, auto = true)).change!!
        assertEquals(ClockChangeKind.BACKWARD, c.kind)
        assertFalse(c.suspicious)
    }

    @Test fun timeZoneChangedByHand() {
        val c = ClockWatch.check(anchor, reading(start.wallMs + 1_000, start.elapsedMs + 1_000, zone = "America/New_York", offset = -18_000_000)).change!!
        assertEquals(ClockChangeKind.TIME_ZONE, c.kind)
        assertEquals("Europe/Berlin", c.fromZone)
        assertTrue(c.suspicious)
    }

    @Test fun clockEarlierThanEverSeenAfterReboot() {
        val c = ClockWatch.check(anchor, reading(start.wallMs - 86_400_000, 5_000, boot = 8)).change!!
        assertEquals(ClockChangeKind.BACKWARD, c.kind)
    }

    @Test fun normalRebootIsNoChange() {
        val r = ClockWatch.check(anchor, reading(start.wallMs + 120_000, 5_000, boot = 8))
        assertNull(r.change)
        assertEquals(8, r.anchor.bootCount)
    }

    @Test fun reportedOnlyOnce() {
        val first = ClockWatch.check(anchor, reading(start.wallMs + 3_600_000, start.elapsedMs + 1_000))
        val again = ClockWatch.check(first.anchor, reading(start.wallMs + 3_600_000 + 5_000, start.elapsedMs + 6_000))
        assertNull(again.change)
    }

    @Test fun trustedNowIgnoresTheWallClock() {
        assertEquals(start.wallMs + 5_000, ClockWatch.trustedNow(anchor, start.elapsedMs + 5_000, 7))
        assertNull(ClockWatch.trustedNow(anchor, 1_000, 8))
    }

    @Test fun describesChanges() {
        assertEquals("Clock set 2 h forward", describeClockChange(ClockChangeKind.FORWARD, 7_200_000))
        assertEquals("Clock set 1 h 30 min back", describeClockChange(ClockChangeKind.BACKWARD, -5_400_000))
        assertEquals("3 days", formatDuration(3 * 86_400_000L))
    }
}
