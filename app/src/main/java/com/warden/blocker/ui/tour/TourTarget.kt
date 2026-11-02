package com.warden.blocker.ui.tour

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/** Where every `tourTarget` currently is on screen (root coordinates, clipped to what is visible). */
@OptIn(ExperimentalFoundationApi::class)
class TourRegistry {
    private val bounds = mutableStateMapOf<String, Rect>()
    private val owners = HashMap<String, BringIntoViewRequester>()

    /** Visible bounds of [id], or null when it isn't composed or is scrolled out of sight. */
    fun rect(id: String): Rect? = bounds[id]?.takeIf { it.width > 1f && it.height > 1f }

    /** Whether [id] is on the current screen at all, even if scrolled out of sight. */
    fun isComposed(id: String): Boolean = bounds.containsKey(id)

    internal fun update(id: String, owner: BringIntoViewRequester, rect: Rect) {
        if (owners[id] !== owner) owners[id] = owner
        bounds[id] = rect
    }

    internal fun remove(id: String, owner: BringIntoViewRequester) {
        if (owners[id] === owner) { owners.remove(id); bounds.remove(id) }
    }

    /** Scrolls [id] into view if a scrolling parent can. */
    suspend fun bringIntoView(id: String) { runCatching { owners[id]?.bringIntoView() } }
}

val LocalTourRegistry = staticCompositionLocalOf<TourRegistry?> { null }

val LocalTourController = staticCompositionLocalOf<TourController?> { null }

/**
 * Marks this element as something a guide can point at. Ids live in [TourIds]. Does nothing
 * outside a [TourHost] (previews, other activities).
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tourTarget(id: String): Modifier = composed {
    val registry = LocalTourRegistry.current ?: return@composed Modifier
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(registry, id) { onDispose { registry.remove(id, requester) } }
    Modifier
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { if (it.isAttached) registry.update(id, requester, it.boundsInRoot()) }
}
