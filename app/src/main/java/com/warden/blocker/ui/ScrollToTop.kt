package com.warden.blocker.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Emits each time the user taps the bottom-bar tab that is already selected. Tab screens
 * answer it with [ScrollToTopOnReselect], like most Android apps do.
 */
val LocalTabReselected = compositionLocalOf<Flow<Unit>> { emptyFlow() }

@Composable
fun ScrollToTopOnReselect(state: ScrollState) {
    val reselected = LocalTabReselected.current
    LaunchedEffect(state, reselected) { reselected.collect { state.animateScrollTo(0) } }
}

@Composable
fun ScrollToTopOnReselect(state: LazyListState) {
    val reselected = LocalTabReselected.current
    LaunchedEffect(state, reselected) { reselected.collect { state.animateScrollToItem(0) } }
}
