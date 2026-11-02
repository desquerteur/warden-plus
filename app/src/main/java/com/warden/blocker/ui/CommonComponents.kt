package com.warden.blocker.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// --- Layout tokens (docs/STYLE.md) ---

/** Space between cards on a page. */
val CardSpacing = 12.dp

/** Inside every card. */
val CardPadding = 16.dp

/** Side margin of a page: 12 dp on narrow screens (fold cover display, 320–359 dp), else 20 dp. */
internal fun gutterFor(screenWidthDp: Int): Dp = if (screenWidthDp < 360) 12.dp else 20.dp

@Composable
fun pageGutter(): Dp = gutterFor(LocalConfiguration.current.screenWidthDp)

// --- Text ---

/** The big bold title at the top of a tab ("Blocklist", "Your stats"). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        modifier = modifier.semantics { heading() },
    )
}

/** Small heading above a group of cards, e.g. "Protection" or "Help" in Settings. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** The quiet second line under a title: small and in the muted text colour. */
@Composable
fun SupportingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Intro sentence under a page title or top bar. */
@Composable
fun IntroText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A quiet hint with an info icon, e.g. "Android only keeps about 10 days of history". */
@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What an empty list says ("Nothing blocked yet …"). */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(vertical = 12.dp),
    )
}

// --- Cards ---

/**
 * The basic card of every page (same as Usage and Settings): filled, 16 dp inside, an optional
 * title, content 8 dp apart. Its height follows its content smoothly.
 */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM)).padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() }) }
            content()
        }
    }
}

/** A setting with a switch: title, one muted line, the switch on the right, extras below. */
@Composable
fun SwitchCard(
    title: String,
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM)).padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    SupportingText(text)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            extra()
        }
    }
}

/** A card that opens another page: icon, title, muted summary, chevron. */
@Composable
fun ActionCard(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) { ActionRow(icon, title, summary, onClick) }
}

/** Several [ActionRow]s in one card. */
@Composable
fun ActionGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth(), content = content) }
}

/** One line of an [ActionGroup] (or an [ActionCard]); [divider] draws the thin line above it. */
@Composable
fun ActionRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = CardPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                summary?.let { SupportingText(it) }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Red card for something that leaves the user less protected than it seems. */
@Composable
fun WarningCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            actions()
        }
    }
}

// --- Status ---

/**
 * Round icon that shows a state: tinted when [active], muted when not, with a smooth colour and
 * icon change. [pulse]: a few soft rings when it turns active (then it stops, no endless loop).
 */
@Composable
fun IconBadge(
    activeIcon: ImageVector,
    inactiveIcon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    pulse: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (active) colors.primaryContainer else colors.surface, tween(Motion.MEDIUM), label = "badge")
    val tint by animateColorAsState(if (active) colors.onPrimaryContainer else colors.onSurfaceVariant, tween(Motion.MEDIUM), label = "badgeTint")
    val motion = LocalMotion.current
    val ring = remember { Animatable(1f) }
    LaunchedEffect(active && pulse, motion) {
        if (!(active && pulse) || !motion) { ring.snapTo(1f); return@LaunchedEffect }
        repeat(PULSES) {
            ring.snapTo(0f)
            ring.animateTo(1f, tween(1400, easing = LinearOutSlowInEasing))
        }
    }
    val ringColor = colors.primary
    Box(
        modifier
            .size(size)
            .drawBehind {
                val p = ring.value
                if (p < 1f) {
                    val (scale, alpha) = pulseRing(p)
                    drawCircle(ringColor.copy(alpha = alpha), radius = this.size.minDimension / 2 * scale)
                }
            }
            .background(container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(active, animationSpec = tween(Motion.MEDIUM), label = "badgeIcon") { on ->
            Icon(if (on) activeIcon else inactiveIcon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
        }
    }
}

/** How many soft rings [IconBadge] sends out when it turns active. */
private const val PULSES = 3

/** Scale and alpha of a pulse ring at [progress] 0..1: grows from the badge and fades out. */
internal fun pulseRing(progress: Float): Pair<Float, Float> {
    val p = progress.coerceIn(0f, 1f)
    return (1f + 0.7f * p) to (0.35f * (1f - p))
}

/**
 * A round tick: the circle fills, then the tick is drawn. Shown at once when it starts out
 * checked (unless [animateIn]); animates when it becomes checked while on screen, after
 * [delayMillis]. Empty ring when unchecked.
 */
@Composable
fun AnimatedCheck(
    checked: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    uncheckedColor: Color = MaterialTheme.colorScheme.outline,
    animateIn: Boolean = false,
    delayMillis: Int = 0,
) {
    val motion = LocalMotion.current
    val progress = remember { Animatable(if (checked && !animateIn) 1f else 0f) }
    LaunchedEffect(checked, motion) {
        val target = if (checked) 1f else 0f
        if (motion) progress.animateTo(target, tween(if (checked) 520 else Motion.SHORT, delayMillis = if (checked) delayMillis else 0, easing = FastOutSlowInEasing))
        else progress.snapTo(target)
    }
    val tick = MaterialTheme.colorScheme.onPrimary
    Canvas(modifier.size(size)) {
        val p = progress.value
        val r = this.size.minDimension / 2
        val ringWidth = 2.dp.toPx()
        if (p < 1f) {
            drawCircle(uncheckedColor.copy(alpha = 1f - p), radius = r - ringWidth / 2 - 1.dp.toPx(), style = Stroke(ringWidth))
        }
        val fill = (p / 0.45f).coerceAtMost(1f)
        if (fill > 0f) drawCircle(color, radius = (r - 1.dp.toPx()) * fill)
        val points = checkStroke(((p - 0.35f) / 0.65f).coerceIn(0f, 1f))
        if (points.size >= 2) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(points[0].first * w, points[0].second * h)
                points.drop(1).forEach { (x, y) -> lineTo(x * w, y * h) }
            }
            drawPath(path, tick, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Done (animated tick) or missing (red outline icon), e.g. in the protection checklist. */
@Composable
fun StatusIcon(ok: Boolean, modifier: Modifier = Modifier) {
    if (ok) {
        AnimatedCheck(true, modifier)
    } else {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = modifier.size(24.dp))
    }
}

// --- Focus session (Home) ---

@Composable
internal fun FocusCard(focusEndsAt: Long, strict: Boolean, onStart: (Int, Boolean) -> Unit, onCancel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Ticks only while a session runs, not all the time Home is open.
    LaunchedEffect(focusEndsAt) {
        now = System.currentTimeMillis()
        while (now < focusEndsAt) { delay(1000); now = System.currentTimeMillis() }
    }
    val active = focusEndsAt > now

    Card(Modifier.fillMaxWidth()) {
        AnimatedContent(
            active,
            transitionSpec = { fadeIn(tween(Motion.MEDIUM)) togetherWith fadeOut(tween(Motion.SHORT)) },
            label = "focus",
        ) { running ->
            Column(Modifier.fillMaxWidth().padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Focus session" else "Start a focus session", style = MaterialTheme.typography.titleMedium)
                }
                if (running) {
                    Text(formatCountdown(focusEndsAt - now), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    SupportingText(if (strict) "Strict — this can't be ended early. Deep work, locked in." else "Blocking stays on until the timer ends.")
                    if (!strict) OutlinedButton(onClick = onCancel) { Text("End session") }
                } else {
                    SupportingText("Force blocking on for a set time — great for deep work.")
                    var strictChoice by remember { mutableStateOf(false) }
                    CheckboxRow("Strict (no early exit)", strictChoice) { strictChoice = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(25, 50).forEach { m ->
                            Button(onClick = { onStart(m, strictChoice) }) { Text("$m min") }
                        }
                    }
                }
            }
        }
    }
}

/** A checkbox whose label toggles it too (tapping only the box used to be needed). */
@Composable
fun CheckboxRow(text: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

internal fun formatCountdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * Top bar for screens pushed on top of a tab (app picker, feeds, setup): a visible back arrow
 * and, optionally, a "Done" button that leaves the screen the same way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubScreenTopBar(title: String, onBack: () -> Unit, showDone: Boolean = false) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = { if (showDone) TextButton(onClick = onBack) { Text("Done") } },
        // The surrounding Scaffold already handles system bars.
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

// --- small formatting helpers ---

internal fun daysLabel(mask: Int): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return names.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.joinToString(" ")
}

