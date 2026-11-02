package com.warden.blocker.guard

import com.warden.blocker.feature.UiNode
import java.util.Locale

/**
 * What is on screen in one window, reduced to what [SettingsPageDetector] needs: the package,
 * the activity / dialog class from the last window-state event, the window title, and the
 * visible texts and view ids. All texts are [normalize]d, so matching ignores case, odd spaces
 * and curly apostrophes.
 */
data class ScreenSnapshot(
    val packageName: String,
    val className: String? = null,
    val windowTitle: String? = null,
    val texts: List<String> = emptyList(),
    val viewIds: List<String> = emptyList(),
) {
    /** Lower-cased class name, "" if unknown. */
    val classLower: String = className.orEmpty().lowercase(Locale.ROOT)

    /** Window title plus all texts and content descriptions, normalized and without duplicates. */
    val allTexts: List<String> = buildList {
        windowTitle?.let { add(normalize(it)) }
        texts.forEach { add(normalize(it)) }
    }.filter { it.isNotEmpty() }.distinct()

    fun hasContaining(phrase: String): Boolean = allTexts.any { it.contains(phrase) }

    fun hasAny(phrases: List<Phrase>): Boolean = phrases.any { p -> allTexts.any { p.matches(it) } }

    /** How many different texts on screen match any of [phrases] (one text counts once). */
    fun countTextsMatching(phrases: List<Phrase>): Int = allTexts.count { t -> phrases.any { it.matches(t) } }

    fun hasViewIdContaining(part: String): Boolean = viewIds.any { it.lowercase(Locale.ROOT).contains(part) }

    companion object {
        /** Bounds so a long list can't stall the accessibility thread. */
        const val MAX_NODES = 400
        const val MAX_TEXT_LENGTH = 200

        /**
         * Walks the window's node tree (depth first, at most [maxNodes] nodes) and collects
         * texts, content descriptions and view ids.
         */
        fun fromTree(
            packageName: String,
            className: String?,
            windowTitle: String?,
            root: UiNode?,
            maxNodes: Int = MAX_NODES,
        ): ScreenSnapshot {
            val texts = ArrayList<String>()
            val ids = ArrayList<String>()
            if (root != null) {
                val stack = ArrayDeque<UiNode>()
                stack.addLast(root)
                var visited = 0
                while (stack.isNotEmpty() && visited < maxNodes) {
                    val node = stack.removeLast()
                    visited++
                    node.text?.takeIf { it.isNotBlank() }?.let { texts.add(it.take(MAX_TEXT_LENGTH)) }
                    node.contentDesc?.takeIf { it.isNotBlank() }?.let { texts.add(it.take(MAX_TEXT_LENGTH)) }
                    node.viewId?.let { ids.add(it) }
                    // Children pushed in reverse so the walk reads top to bottom, like the screen.
                    for (i in node.childCount - 1 downTo 0) node.child(i)?.let { stack.addLast(it) }
                }
            }
            return ScreenSnapshot(packageName, className, windowTitle, texts, ids)
        }

        /** Lower case, plain spaces, straight apostrophes, no surrounding blanks. */
        fun normalize(s: String): String = s
            .replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
            .replace('’', '\'').replace('‘', '\'')
            .replace('„', '"').replace('“', '"').replace('”', '"')
            .replace('‑', '-').replace('‐', '-')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }
}

/** A text to look for: [exact] means the whole label must equal it (for short words like "Stop"). */
data class Phrase(val text: String, val exact: Boolean = false) {
    init {
        require(text == ScreenSnapshot.normalize(text)) { "Phrase must be normalized: $text" }
    }

    /** [normalizedText] is one text from the screen, already [ScreenSnapshot.normalize]d. */
    fun matches(normalizedText: String): Boolean =
        if (exact) normalizedText == text else normalizedText.contains(text)
}

internal fun containing(vararg texts: String) = texts.map { Phrase(it) }
internal fun exactly(vararg texts: String) = texts.map { Phrase(it, exact = true) }
