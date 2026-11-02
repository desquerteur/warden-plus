package com.warden.blocker.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavBackStackEntry

/**
 * Motion rules (docs/STYLE.md): short, calm, never looping for long. Everything here respects
 * Android's "Remove animations" (animator duration scale 0): then screens and states just switch.
 */
object Motion {
    /** Small things: a fade, a colour. */
    const val SHORT = 150

    /** Screens and cards: slide, expand. */
    const val MEDIUM = 260

    /** Numbers counting up, charts growing, a check being drawn. */
    const val LONG = 700
}

/** True unless the animator duration scale is 0 ("Remove animations" in Android's settings). */
internal fun motionAllowed(animatorScale: Float): Boolean = animatorScale > 0f

/** False when "Remove animations" / animator scale 0 is set: then guides jump and screens switch without motion. */
fun animationsEnabled(context: Context): Boolean =
    motionAllowed(Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))

/** Provided by [com.warden.blocker.ui.theme.WardenTheme]; false = no motion at all. */
val LocalMotion = staticCompositionLocalOf { true }

/** How a screen change moves. */
internal enum class NavMotion { NONE, FADE, FORWARD, BACK }

/**
 * Tab to tab (also leaving a sub-screen through the bottom bar, or Back from one tab to Home)
 * fades; opening a sub-screen slides it in from the end, going back slides it out again.
 */
internal fun navMotion(initialRoute: String?, targetRoute: String?, popping: Boolean, tabs: Set<String>, motion: Boolean): NavMotion = when {
    !motion -> NavMotion.NONE
    targetRoute in tabs && (!popping || initialRoute in tabs) -> NavMotion.FADE
    popping -> NavMotion.BACK
    else -> NavMotion.FORWARD
}

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navEnter(tabs: Set<String>, motion: Boolean, popping: Boolean): EnterTransition =
    when (navMotion(initialState.destination.route, targetState.destination.route, popping, tabs, motion)) {
        NavMotion.NONE -> EnterTransition.None
        NavMotion.FADE -> fadeIn(tween(Motion.MEDIUM - 40, delayMillis = 60)) +
            scaleIn(tween(Motion.MEDIUM - 40, delayMillis = 60), initialScale = 0.98f)
        NavMotion.FORWARD -> slideInHorizontally(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { it / 5 } +
            fadeIn(tween(Motion.MEDIUM))
        NavMotion.BACK -> slideInHorizontally(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { -it / 10 } +
            fadeIn(tween(Motion.MEDIUM))
    }

internal fun AnimatedContentTransitionScope<NavBackStackEntry>.navExit(tabs: Set<String>, motion: Boolean, popping: Boolean): ExitTransition =
    when (navMotion(initialState.destination.route, targetState.destination.route, popping, tabs, motion)) {
        NavMotion.NONE -> ExitTransition.None
        NavMotion.FADE -> fadeOut(tween(90))
        NavMotion.FORWARD -> slideOutHorizontally(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { -it / 10 } +
            fadeOut(tween(Motion.SHORT))
        NavMotion.BACK -> slideOutHorizontally(tween(Motion.MEDIUM, easing = FastOutSlowInEasing)) { it / 5 } +
            fadeOut(tween(Motion.SHORT))
    }

/**
 * [target] counting up from 0 the first time it is shown, and gliding to a new value after
 * that. Without motion it is just [target].
 */
@Composable
fun rememberCountUp(target: Long): Long {
    val motion = LocalMotion.current
    val value = remember { Animatable(if (motion) 0f else target.toFloat()) }
    LaunchedEffect(target, motion) {
        if (motion) value.animateTo(target.toFloat(), tween(Motion.LONG, easing = FastOutSlowInEasing))
        else value.snapTo(target.toFloat())
    }
    return countUpValue(value.value, target)
}

/** The animated value rounded, never past [target] (float rounding of large millis). */
internal fun countUpValue(animated: Float, target: Long): Long {
    val v = Math.round(animated.toDouble())
    return if (target >= 0) v.coerceIn(0, target) else v
}

/**
 * The visible part of a tick mark drawn from its short arm to its long arm, in a unit square
 * (0..1). [progress] 0 = nothing, 1 = the whole tick. Points of a polyline.
 */
internal fun checkStroke(progress: Float): List<Pair<Float, Float>> {
    val p = progress.coerceIn(0f, 1f)
    if (p == 0f) return emptyList()
    val a = 0.27f to 0.52f
    val b = 0.43f to 0.68f
    val c = 0.75f to 0.35f
    val first = dist(a, b)
    val total = first + dist(b, c)
    val len = total * p
    return if (len <= first) listOf(a, lerp(a, b, len / first))
    else listOf(a, b, lerp(b, c, (len - first) / (total - first)))
}

private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
    val dx = b.first - a.first
    val dy = b.second - a.second
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

private fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, t: Float) =
    (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
