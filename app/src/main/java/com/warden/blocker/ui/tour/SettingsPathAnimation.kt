package com.warden.blocker.ui.tour

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Fixed geometry of the mini phone, in dp, so the finger knows where each row is.
private const val W = 150f
private const val H = 190f
private const val BAR = 28f
private const val ROW = 26f
private const val DIALOG_TOP = 44f
private const val DIALOG_BUTTON_Y = DIALOG_TOP + 104f

private fun rowPoint(frame: MiniFrame, row: Int): Offset = when {
    frame.dialog -> Offset(W - 34f, DIALOG_BUTTON_Y)
    frame.toggle == row -> Offset(W - 20f, BAR + ROW * row + ROW / 2)
    else -> Offset(W * 0.42f, BAR + ROW * row + ROW / 2)
}
private val MENU_BUTTON = Offset(W - 12f, BAR / 2)
private val MENU_ITEM = Offset(W - 60f, BAR + 12f)

/**
 * A plain, self-drawn phone screen: a finger taps through the settings pages of [guide] one by
 * one and the pages slide on, in a loop. With [animate] false (system animations off) the same
 * way is shown as numbered sentences.
 */
@Composable
fun SettingsPathAnimation(guide: SettingsGuide, animate: Boolean, modifier: Modifier = Modifier) {
    val steps = guide.instructions()
    if (!animate) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            steps.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        return
    }
    var frameIndex by remember(guide) { mutableIntStateOf(0) }
    var pressed by remember(guide) { mutableStateOf<Int?>(null) }
    var menuOpen by remember(guide) { mutableStateOf(false) }
    val finger = remember(guide) { Animatable(Offset(W * 0.6f, H * 0.9f), Offset.VectorConverter) }
    val ripple = remember(guide) { Animatable(1f) }

    LaunchedEffect(guide) {
        suspend fun tapAt(point: Offset) {
            finger.animateTo(point, tween(650, easing = FastOutSlowInEasing))
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(420))
        }
        while (true) {
            guide.frames.forEachIndexed { i, frame ->
                frameIndex = i
                pressed = null
                menuOpen = false
                delay(700)
                when (frame.tap) {
                    MiniFrame.TAP_NONE -> delay(900)
                    MiniFrame.TAP_MENU -> {
                        tapAt(MENU_BUTTON)
                        menuOpen = true
                        delay(350)
                        tapAt(MENU_ITEM)
                        delay(350)
                    }
                    else -> {
                        tapAt(rowPoint(frame, frame.tap))
                        pressed = frame.tap
                        delay(500)
                    }
                }
            }
            delay(1400)
            finger.animateTo(Offset(W * 0.6f, H * 0.9f), tween(400))
        }
    }

    val outline = MaterialTheme.colorScheme.outline
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(W.dp, H.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, outline, RoundedCornerShape(14.dp))
                .clearAndSetSemantics { contentDescription = steps.joinToString(" ") },
        ) {
            AnimatedContent(
                targetState = frameIndex,
                transitionSpec = {
                    (slideInHorizontally(tween(350)) { it } + fadeIn(tween(350))) togetherWith
                        (slideOutHorizontally(tween(350)) { -it / 3 } + fadeOut(tween(250)))
                },
                label = "mini settings page",
            ) { i ->
                val frame = guide.frames[i]
                MiniPage(frame, pressed = if (i == frameIndex) pressed else null, menuOpen = i == frameIndex && menuOpen)
            }
            val primary = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val r = ripple.value
                if (r < 1f) {
                    drawCircle(
                        primary.copy(alpha = 0.45f * (1f - r)),
                        radius = (6 + 14 * r).dp.toPx(),
                        center = Offset(finger.value.x.dp.toPx(), finger.value.y.dp.toPx()),
                    )
                }
            }
            Icon(
                Icons.Filled.TouchApp,
                contentDescription = null,
                tint = primary,
                modifier = Modifier
                    // The TouchApp glyph's fingertip sits at (11.5, 7.5) of 24.
                    .offset { IntOffset((finger.value.x - 11.5f).dp.roundToPx(), (finger.value.y - 7.5f).dp.roundToPx()) }
                    .size(24.dp)
                    .background(Color.White.copy(alpha = 0.7f), CircleShape),
            )
        }
    }
}

@Composable
private fun MiniPage(frame: MiniFrame, pressed: Int?, menuOpen: Boolean) {
    val colors = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
    Box(Modifier.size(W.dp, H.dp)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(BAR.dp).background(colors.surfaceVariant).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("‹", style = small, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
                Text(
                    if (frame.dialog) "" else frame.title,
                    style = small, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (frame.menu != null) Text("⋮", style = small, fontWeight = FontWeight.Bold)
            }
            if (!frame.dialog) {
                frame.rows.forEachIndexed { i, label ->
                    val on = i == frame.toggle && (pressed == i) == frame.toggleTo
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW.dp)
                            .background(if (pressed == i && frame.toggle != i) colors.primaryContainer else Color.Transparent)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (i == frame.toggle) MiniSwitch(on)
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                }
            }
        }
        if (frame.dialog) MiniDialog(frame, pressed != null)
        if (menuOpen && frame.menu != null) {
            Box(
                Modifier
                    .offset(x = (W - 118f).dp, y = (BAR - 2f).dp)
                    .width(112.dp)
                    .background(colors.surfaceVariant, RoundedCornerShape(6.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) { Text(frame.menu, style = small, maxLines = 2) }
        }
    }
}

@Composable
private fun MiniDialog(frame: MiniFrame, pressed: Boolean) {
    val colors = MaterialTheme.colorScheme
    val small = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        Column(
            Modifier
                .offset(y = DIALOG_TOP.dp)
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            Text(frame.title, style = small, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(16.dp))
            Text(frame.rows.first(), style = small, maxLines = 4, modifier = Modifier.height(64.dp))
            Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    frame.rows.last(),
                    style = small,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary,
                    modifier = Modifier
                        .background(if (pressed) colors.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MiniSwitch(on: Boolean) {
    val colors = MaterialTheme.colorScheme
    val knob by animateFloatAsState(if (on) 1f else 0f, tween(250), label = "mini switch")
    Box(
        Modifier
            .size(24.dp, 13.dp)
            .background(if (on) colors.primary else colors.outline, RoundedCornerShape(7.dp)),
    ) {
        Box(
            Modifier
                .offset { IntOffset((1.5f + 11f * knob).dp.roundToPx(), 1.5f.dp.roundToPx()) }
                .size(10.dp)
                .background(Color.White, CircleShape),
        )
    }
}
