package com.warden.blocker.accessibility

import com.warden.blocker.accessibility.WindowControls.Box
import com.warden.blocker.accessibility.WindowControls.Control
import com.warden.blocker.accessibility.WindowControls.ControlNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowControlsTest {

    private class Node(
        override val viewId: String? = null,
        override val text: String? = null,
        override val desc: String? = null,
        override val clickable: Boolean = true,
        override val visible: Boolean = true,
        override val box: Box = Box(0, 0, 10, 10),
        val kids: List<Node> = emptyList(),
    ) : ControlNode {
        override val childCount get() = kids.size
        override fun child(index: Int) = kids.getOrNull(index)
    }

    /** A pop-up at x 478..1560, y 270..2082 (the AOSP emulator, Fold inner screen size). */
    private val window = Box(478, 270, 1560, 2082)
    private val strip = 189 // 72 dp at 2.625

    private fun caption(vararg buttons: Node) =
        Node(viewId = "com.android.systemui:id/desktop_mode_caption", clickable = false, box = Box(478, 270, 1560, 380), kids = buttons.toList())

    private fun button(id: String? = null, desc: String? = null, text: String? = null, x: Int = 1400, clickable: Boolean = true) =
        Node(viewId = id, desc = desc, text = text, clickable = clickable, box = Box(x, 290, x + 100, 360))

    // --- AOSP ids ---

    @Test fun aospCaptionWindowPrefersMaximize() {
        val close = button("com.android.systemui:id/close_window", x = 1450)
        val max = button("com.android.systemui:id/maximize_window", x = 1340)
        val min = button("com.android.systemui:id/minimize_window", x = 1230)
        val hit = WindowControls.find(listOf(caption(close, max, min)), window, strip)!!
        assertEquals(Control.MAXIMIZE, hit.control)
        assertSame(max, hit.target)
    }

    @Test fun olderAndroidDecorCaptionInTheAppsOwnWindow() {
        // Android 10–13: DecorCaptionView inside the app window, framework ids.
        val close = button("android:id/close_window")
        val app = Node(viewId = "com.example:id/root", clickable = false, box = window, kids = listOf(close))
        assertEquals(Control.CLOSE, WindowControls.find(listOf(app), window, strip)?.control)
    }

    @Test fun closeWhenThereIsNoMaximize() {
        val hit = WindowControls.find(listOf(caption(button("com.android.systemui:id/close_window"))), window, strip)
        assertEquals(Control.CLOSE, hit?.control)
    }

    @Test fun desktopModeHandleOpensTheMenu() {
        val handle = button("com.android.systemui:id/caption_handle", x = 950)
        assertEquals(Control.MENU, WindowControls.find(listOf(caption(handle)), window, strip)?.control)
    }

    @Test fun realButtonWinsOverTheMenuHandle() {
        val handle = button("com.android.systemui:id/open_menu_button", x = 500)
        val max = button("com.android.systemui:id/maximize_window")
        assertEquals(Control.MAXIMIZE, WindowControls.find(listOf(caption(handle, max)), window, strip)?.control)
    }

    // --- Samsung: unknown ids, labels ---

    @Test fun samsungButtonsByEnglishLabel() {
        val hit = WindowControls.find(
            listOf(caption(button(desc = "Minimize", x = 1200), button(desc = "Full screen", x = 1300), button(desc = "Close", x = 1400))),
            window, strip,
        )
        assertEquals(Control.MAXIMIZE, hit?.control)
    }

    @Test fun samsungButtonsByGermanLabel() {
        assertEquals(Control.MAXIMIZE, WindowControls.classify(null, null, "Vollbildansicht"))
        assertEquals(Control.MAXIMIZE, WindowControls.classify(null, null, "Maximieren"))
        assertEquals(Control.CLOSE, WindowControls.classify(null, null, "Schließen"))
        assertEquals(Control.CLOSE, WindowControls.classify(null, "schliessen", null))
        assertEquals(Control.MINIMIZE, WindowControls.classify(null, null, "Minimieren"))
    }

    @Test fun samsungIdsInItsOwnNamespace() {
        assertEquals(Control.MAXIMIZE, WindowControls.classify("com.samsung.android.multiwindow:id/maximize_button", null, null))
        assertEquals(Control.CLOSE, WindowControls.classify("android:id/sem_decor_close_button", null, null))
        assertEquals(Control.MINIMIZE, WindowControls.classify("com.sec.android.app.multiwindow:id/minimize", null, null))
    }

    @Test fun labelsAreMatchedWhole() {
        assertNull(WindowControls.classify(null, null, "Close tab"))
        assertNull(WindowControls.classify(null, null, "Close all tabs"))
        assertNull(WindowControls.classify(null, "Full screen video player", null))
        assertEquals(Control.CLOSE, WindowControls.classify(null, null, "  CLOSE  "))
        assertEquals(Control.MAXIMIZE, WindowControls.classify(null, null, "Maximize."))
    }

    @Test fun appsOwnIdsAreNotWindowControls() {
        // Chrome's "close_button" closes a tab; an app's id never counts, only its exact label.
        assertNull(WindowControls.classify("com.android.chrome:id/close_button", null, null))
        assertNull(WindowControls.classify("com.example:id/fullscreen_toggle", null, null))
        assertNull(WindowControls.classify("android:id/closed_caption", null, null))
        assertNull(WindowControls.classify("close_window", null, null)) // no namespace
    }

    // --- Where and what may be pressed ---

    @Test fun controlsFarFromTheTopEdgeAreIgnored() {
        // A "Close" button in the middle of the app (a dialog) is not the window's.
        val dialogClose = Node(desc = "Close", box = Box(900, 1200, 1000, 1300))
        assertNull(WindowControls.find(listOf(Node(clickable = false, box = window, kids = listOf(dialogClose))), window, strip))
    }

    @Test fun controlsOutsideTheWindowsWidthAreIgnored() {
        // Another pop-up's caption at the same height, further right.
        val other = Node(viewId = "com.android.systemui:id/close_window", box = Box(1600, 290, 1700, 360))
        assertNull(WindowControls.find(listOf(other), window, strip))
    }

    @Test fun captionJustAboveTheWindowCounts() {
        // Samsung draws its handle / toolbar above the pop-up's top edge.
        val above = Node(desc = "Close", box = Box(1400, 150, 1500, 230))
        assertEquals(Control.CLOSE, WindowControls.find(listOf(above), window, strip)?.control)
    }

    @Test fun invisibleButtonsAreIgnored() {
        val hidden = Node(viewId = "com.android.systemui:id/maximize_window", visible = false, box = Box(1400, 290, 1500, 360))
        assertNull(WindowControls.find(listOf(caption(hidden)), window, strip))
    }

    @Test fun iconWithoutClickPressesItsClickableParent() {
        val icon = Node(desc = "Maximize", clickable = false, box = Box(1420, 300, 1480, 350))
        val parent = Node(clickable = true, box = Box(1400, 290, 1500, 360), kids = listOf(icon))
        val hit = WindowControls.find(listOf(caption(parent)), window, strip)
        assertEquals(Control.MAXIMIZE, hit?.control)
        assertSame(parent, hit?.target)
    }

    @Test fun nothingClickableMeansNoHit() {
        val icon = Node(desc = "Maximize", clickable = false, box = Box(1420, 300, 1480, 350))
        assertNull(WindowControls.find(listOf(caption(icon)), window, strip))
    }

    @Test fun hugeTreesAreNotWalkedToTheEnd() {
        // A deep app tree with the button beyond the budget: found nothing, but didn't hang.
        var node = Node(desc = "Close", box = Box(1400, 290, 1500, 360))
        repeat(WindowControls.MAX_NODES + 50) { node = Node(clickable = false, box = window, kids = listOf(node)) }
        assertNull(WindowControls.find(listOf(node), window, strip))
    }

    @Test fun stripGeometry() {
        assertTrue(WindowControls.inStrip(Box(1400, 290, 1500, 360), window, strip))
        assertFalse(WindowControls.inStrip(Box(1400, 600, 1500, 700), window, strip))
        assertFalse(WindowControls.inStrip(Box(1400, 290, 1400, 360), window, strip)) // empty
    }

    // --- Which caption belongs to which pop-up ---

    @Test fun captionIsTheSystemUiWindowRightAboveTheApp() {
        // As logged on the emulator: status bar, caption, Warden+'s window, caption, Clock, launcher.
        val owners = listOf(
            "com.android.systemui", "com.android.systemui", "com.warden.blocker",
            "com.android.systemui", "com.google.android.deskclock", "com.google.android.apps.nexuslauncher",
        )
        // Clock's caption is index 3 — not index 1, the caption of the block screen above it.
        assertEquals(listOf(3), WindowControls.decorationsOf(4, owners))
    }

    @Test fun unknownWindowStopsTheCaptionSearch() {
        val owners = listOf("com.android.systemui", null, "com.android.systemui", "com.example.blocked")
        assertEquals(listOf(2), WindowControls.decorationsOf(3, owners))
    }

    @Test fun topmostPopUpWithItsCaptionAndTheStatusBar() {
        val owners = listOf("com.android.systemui", "android", "com.example.blocked", "com.sec.android.app.launcher")
        assertEquals(listOf(1, 0), WindowControls.decorationsOf(2, owners))
        // No caption window at all (Samsung draws it inside the app's own window).
        assertEquals(emptyList<Int>(), WindowControls.decorationsOf(0, listOf("com.example.blocked")))
    }

    @Test fun systemNamespaces() {
        assertTrue(WindowControls.isSystemNamespace("android"))
        assertTrue(WindowControls.isSystemNamespace("com.android.systemui"))
        assertTrue(WindowControls.isSystemNamespace("com.samsung.android.multiwindow"))
        assertFalse(WindowControls.isSystemNamespace("com.android.chrome"))
        assertFalse(WindowControls.isSystemNamespace("com.samsungx.evil"))
    }
}
