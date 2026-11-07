package com.warden.blocker.accessibility

/**
 * Finds the window controls of a pop-up (freeform) window — "Maximize", "Close", "Minimize" —
 * in the accessibility tree, so the service can press one for a blocked app's pop-up.
 *
 * Why (issue #3, second report): on Samsung phones a pop-up window floats *above*
 * full-screen apps. Neither the full-screen block screen nor the home screen covers it, so the
 * blocked app in a pop-up stayed usable over the block screen. An app can't close another
 * app's window, but the accessibility service can press the window's own buttons:
 *  - **Maximize** turns the pop-up into a normal full-screen app, which the block screen covers;
 *  - **Close** / **Minimize** take it off the screen.
 *
 * Where the buttons live differs:
 *  - AOSP Android 10–13: in the app's own window (`android:id/maximize_window`, `close_window`).
 *  - AOSP Android 14+: a separate caption window drawn by System UI (Window Manager Shell),
 *    `com.android.systemui:id/maximize_window`, `close_window`, `minimize_window`; in desktop
 *    mode first behind a handle (`caption_handle`, `open_menu_button`).
 *  - Samsung One UI: its own caption at the top of the pop-up; the ids aren't public, so the
 *    match also goes by label (English and German, as on the Fold): "Maximize", "Full screen",
 *    "Vollbild", "Close", "Schließen", "Minimize", "Minimieren"…
 *
 * Only nodes in a strip around the top edge of the blocked app's window count, so a "Close"
 * button somewhere in another app is never pressed. Labels must match exactly (a "Close tab"
 * button is not a window control). Pure logic over [ControlNode] so it is unit-tested.
 */
object WindowControls {

    enum class Control { MAXIMIZE, CLOSE, MINIMIZE, MENU }

    /** Screen rectangle in pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
    }

    /** What the finder needs to know about one accessibility node. */
    interface ControlNode {
        val viewId: String?
        val text: String?
        val desc: String?
        val clickable: Boolean
        val visible: Boolean
        val box: Box
        val childCount: Int
        fun child(index: Int): ControlNode?
        /** Press it; true if Android took the click. */
        fun click(): Boolean = false
    }

    /** A control was found: press [target] (the node itself or its clickable parent). */
    data class Hit(val control: Control, val target: ControlNode, val label: String)

    /** Preferred order: full screen (the block screen covers it, the app keeps its state), then away. */
    private val PRIORITY = listOf(Control.MAXIMIZE, Control.CLOSE, Control.MINIMIZE, Control.MENU)

    /** Enough for any real caption bar; a whole app's tree is never walked to the end. */
    const val MAX_NODES = 600
    private const val MAX_DEPTH = 40

    /**
     * The best control for the window at [window] in [roots] (trees of the app's own window and
     * of System UI / caption windows), or null. [stripPx]: how far above and below the window's
     * top edge a control may sit (caption bars are about 32–56 dp high).
     */
    fun find(roots: List<ControlNode>, window: Box, stripPx: Int): Hit? {
        val found = HashMap<Control, Hit>()
        var budget = MAX_NODES
        fun walk(node: ControlNode, clickableParent: ControlNode?, depth: Int) {
            if (budget-- <= 0 || depth > MAX_DEPTH) return
            val clickTarget = if (node.clickable) node else clickableParent
            if (node.visible && inStrip(node.box, window, stripPx)) {
                val control = classify(node.viewId, node.text, node.desc)
                if (control != null && clickTarget != null && control !in found) {
                    found[control] = Hit(control, clickTarget, node.viewId ?: node.desc ?: node.text ?: "")
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.child(i) ?: continue
                walk(child, clickTarget, depth + 1)
            }
        }
        for (root in roots) walk(root, null, 0)
        return PRIORITY.firstNotNullOfOrNull { found[it] }
    }

    /**
     * Which windows are the caption / decoration of the app window at [targetIndex]: [owners] are
     * the packages of all windows in z-order, top first (as Android lists them), null when unknown.
     * A caption window lies directly above its app window, so these are the System UI / Samsung
     * windows right above the target, up to the next window of anyone else — never the caption of
     * another pop-up (or of Warden+'s own block screen) that happens to be at the same place.
     */
    fun decorationsOf(targetIndex: Int, owners: List<String?>): List<Int> {
        val result = ArrayList<Int>()
        var i = targetIndex - 1
        while (i >= 0) {
            val owner = owners[i] ?: break
            if (!isSystemNamespace(owner)) break
            result += i
            i--
        }
        return result
    }

    /** Within the window's width, and near its top edge (a caption sits on it, above or inside). */
    fun inStrip(node: Box, window: Box, stripPx: Int): Boolean {
        if (node.right <= node.left || node.bottom <= node.top) return false
        val x = node.centerX
        val y = node.centerY
        return x >= window.left && x <= window.right &&
            y >= window.top - stripPx && y <= window.top + stripPx
    }

    /** Which window control a node is, by its view id or (exactly) by its label. */
    fun classify(viewId: String?, text: String?, desc: String?): Control? {
        byId(viewId)?.let { return it }
        return byLabel(desc) ?: byLabel(text)
    }

    private fun byId(viewId: String?): Control? {
        if (viewId == null || !viewId.contains(":id/")) return null
        // Only the system's own window controls: an app's "close_button" may close a tab.
        if (!isSystemNamespace(viewId.substringBefore(":id/"))) return null
        val name = viewId.substringAfter(":id/").lowercase()
        if (name.isEmpty()) return null
        return when {
            name.contains("maximize") || name.contains("maximise") ||
                name.contains("fullscreen") || name.contains("full_screen") -> Control.MAXIMIZE
            name.contains("minimize") || name.contains("minimise") -> Control.MINIMIZE
            name.contains("close") && !name.contains("closed") -> Control.CLOSE
            name in MENU_IDS -> Control.MENU
            else -> null
        }
    }

    private fun byLabel(label: String?): Control? {
        val l = label?.trim()?.lowercase()?.replace(' ', ' ')?.trimEnd('.') ?: return null
        if (l.isEmpty()) return null
        return when (l) {
            in MAXIMIZE_LABELS -> Control.MAXIMIZE
            in CLOSE_LABELS -> Control.CLOSE
            in MINIMIZE_LABELS -> Control.MINIMIZE
            in MENU_LABELS -> Control.MENU
            else -> null
        }
    }

    /** Resource namespaces of the framework, System UI and Samsung's system parts. */
    fun isSystemNamespace(ns: String): Boolean =
        ns == "android" || ns == "com.android.internal" || ns == "com.android.systemui" ||
            ns == "com.android.wm.shell" || ns.startsWith("com.samsung.") || ns.startsWith("com.sec.")

    private val MENU_IDS = setOf("caption_handle", "open_menu_button", "handle_menu", "app_handle", "caption_menu")

    private val MAXIMIZE_LABELS = setOf(
        "maximize", "maximise", "maximize window", "maximise window", "maximize button",
        "full screen", "fullscreen", "full screen view", "open in full screen view", "open in full screen",
        "switch to full screen", "full-screen", "enter full screen",
        "maximieren", "fenster maximieren", "vollbild", "vollbildansicht", "in vollbildansicht öffnen",
        "vollbildmodus", "im vollbild öffnen",
    )
    private val CLOSE_LABELS = setOf(
        "close", "close window", "close app", "close button", "close pop-up", "close pop-up view",
        "schließen", "schliessen", "fenster schließen", "app schließen", "pop-up schließen",
    )
    private val MINIMIZE_LABELS = setOf(
        "minimize", "minimise", "minimize window", "minimise window", "minimize button",
        "minimieren", "fenster minimieren",
    )
    private val MENU_LABELS = setOf("app handle", "handle menu", "caption handle")
}
