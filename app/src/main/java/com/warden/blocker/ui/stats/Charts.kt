package com.warden.blocker.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Categorical colours for the donut (validated order, colour-blind safe for adjacent slices);
 * the dark set is stepped for dark surfaces. "Other" always uses a neutral grey.
 */
private val SeriesLight = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFFE87BA4))
private val SeriesDark = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFFD55181))

@Composable
fun seriesColors(): List<Color> =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) SeriesDark else SeriesLight

@Composable
fun otherColor(): Color = MaterialTheme.colorScheme.outline

/**
 * Bar chart drawn on a Canvas: one bar per bucket, a light grid with duration labels, sparse
 * x labels from [xLabel], tap to select a bar. Buckets before [firstWithData] are marked as
 * "no data"; buckets from [futureFrom] on are left empty.
 */
@Composable
fun BarChart(
    values: LongArray,
    xLabel: (Int) -> String?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    firstWithData: Int = 0,
    futureFrom: Int = values.size,
    barColor: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 168.dp,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val noDataColor = MaterialTheme.colorScheme.outlineVariant
    val selectedLabelStyle = labelStyle.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values.contentHashCode()) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(450))
    }
    val axisMax = niceAxisMax(values.maxOrNull() ?: 0L)
    val gridLabels = listOf(axisMax, axisMax / 2).map { formatAxis(it) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(values, selected, futureFrom) {
                detectTapGestures { pos ->
                    val gutter = gutterWidth(measurer, gridLabels, labelStyle)
                    val slot = (size.width - gutter) / values.size
                    val i = if (pos.x < gutter) -1 else ((pos.x - gutter) / slot).toInt()
                    onSelect(if (i in values.indices && i != selected && i < futureFrom) i else null)
                }
            },
    ) {
        val gutter = gutterWidth(measurer, gridLabels, labelStyle)
        val xLabelHeight = 18.dp.toPx()
        val top = 8.dp.toPx()
        val plotH = size.height - xLabelHeight - top
        val baseY = top + plotH
        val plotW = size.width - gutter
        val slot = plotW / values.size

        // Grid: baseline + half + top, recessive.
        listOf(0f, 0.5f, 1f).forEachIndexed { k, f ->
            val y = baseY - plotH * f
            drawLine(
                gridColor, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                pathEffect = if (k == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            if (k > 0) {
                val text = gridLabels[if (k == 2) 0 else 1]
                val layout = measurer.measure(text, labelStyle)
                drawText(layout, topLeft = Offset(0f, y - layout.size.height / 2f))
            }
        }

        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx()).coerceAtLeast(2f)
        val radius = minOf(4.dp.toPx(), barW / 2)
        values.forEachIndexed { i, v ->
            val cx = gutter + slot * i + slot / 2
            when {
                i >= futureFrom -> Unit
                i < firstWithData -> drawLine(
                    noDataColor, Offset(cx - barW / 2, baseY - 2.dp.toPx()), Offset(cx + barW / 2, baseY - 2.dp.toPx()),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
                v > 0 -> {
                    val h = (plotH * v / axisMax.toFloat() * grow.value).coerceAtLeast(2.dp.toPx())
                    val alpha = if (selected == null || selected == i) 1f else 0.35f
                    drawTopRoundedBar(Rect(cx - barW / 2, baseY - h, cx + barW / 2, baseY), radius, barColor.copy(alpha = alpha))
                }
            }
            xLabel(i)?.let { text ->
                val layout = measurer.measure(
                    text,
                    if (i == selected) selectedLabelStyle else labelStyle,
                )
                val x = (cx - layout.size.width / 2f).coerceIn(gutter, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, baseY + 3.dp.toPx()))
            }
        }
    }
}

private fun DrawScope.drawTopRoundedBar(rect: Rect, radius: Float, color: Color) {
    val r = CornerRadius(radius, radius)
    val path = Path().apply {
        addRoundRect(RoundRect(rect, topLeft = r, topRight = r, bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero))
    }
    drawPath(path, color)
}

private fun gutterWidth(
    measurer: androidx.compose.ui.text.TextMeasurer,
    labels: List<String>,
    style: TextStyle,
): Float = labels.maxOf { measurer.measure(it, style).size.width } + 12f

/**
 * Ring chart of [fractions] (summing to ~1) with a 2° gap between slices and centre text.
 */
@Composable
fun DonutChart(
    fractions: List<Float>,
    colors: List<Color>,
    centerTop: String,
    centerBottom: String,
    description: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 148.dp,
) {
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(fractions) {
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(600))
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(diameter).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = diameter.toPx() * 0.13f
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            val gap = if (fractions.count { it > 0f } > 1) 2f else 0f
            var start = -90f
            fractions.forEachIndexed { i, f ->
                val full = 360f * f
                val s = (full - gap).coerceAtLeast(0.5f) * sweep.value
                drawArc(colors[i % colors.size], start + gap / 2, s, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += full
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerTop, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(centerBottom, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Thin horizontal share bar (0..1) used in the app list; grows in when it first shows. */
@Composable
fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(600)) }
    Canvas(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
        drawRect(track)
        val w = size.width * fraction.coerceIn(0f, 1f) * grow.value
        if (w > 0f) drawRoundRect(color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

/** Rounds the largest value up to a friendly axis maximum (15m, 30m, 1h, 2h …). */
internal fun niceAxisMax(maxMillis: Long): Long {
    val min = 60_000L
    val steps = longArrayOf(10, 20, 30, 60, 120, 180, 240, 360, 480, 600, 720, 960, 1200, 1440)
    val needed = maxMillis.coerceAtLeast(1)
    return steps.firstOrNull { it * min >= needed }?.let { it * min } ?: needed
}
