package com.warden.blocker.ui.stats

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.stats.StatsPeriod
import com.warden.blocker.stats.TimeRange
import com.warden.blocker.stats.UsageEventsSource
import com.warden.blocker.ui.AppIcon
import java.time.ZoneId

/**
 * The stats dashboard: period switcher, headline tiles, screen-time bar chart, top-apps donut
 * and a per-app list with trends and tap-to-expand history. One column on narrow screens
 * (Fold cover display, 320 dp+), two columns from 600 dp (Fold inner display, tablets).
 */
@Composable
fun StatsDashboard(vm: StatsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Re-read on every return, e.g. after the user granted usage access in Settings.
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    var expanded by rememberSaveable(state.period) { mutableStateOf<String?>(null) }
    val onToggle: (String) -> Unit = { pkg -> expanded = if (expanded == pkg) null else pkg }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gutter = if (maxWidth < 360.dp) 12.dp else 20.dp
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxSize().padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(gutter)) {
                Column(
                    Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = gutter),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(state.period, vm::selectPeriod)
                    Tiles(state)
                    UsageSections(state)
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(vertical = gutter),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    appList(state, expanded, onToggle)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(gutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { Header(state.period, vm::selectPeriod) }
                item(key = "tiles") { Tiles(state) }
                item(key = "usage") { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { UsageSections(state) } }
                appList(state, expanded, onToggle)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Header(period: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Your stats",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val all = StatsPeriod.entries
            all.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == period,
                    onClick = { onSelect(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, all.size),
                    icon = {},
                ) { Text(p.label, maxLines = 1) }
            }
        }
    }
}

// --- Tiles ---

private enum class Tone { Good, Bad, Neutral }

@Composable
private fun Tiles(state: StatsUiState) {
    val usage = (state.usage as? UsageState.Ready)?.data
    val loading = state.usage is UsageState.Loading
    val noPermission = state.usage is UsageState.NoPermission
    val dash = if (loading) "…" else "—"
    val vs = previousLabel(state.period)

    val screen = TileModel(
        icon = Icons.Outlined.Schedule,
        label = "Screen time",
        value = usage?.let { formatDuration(it.totalMillis) } ?: dash,
        detail = when {
            noPermission -> "Needs usage access"
            usage == null -> ""
            usage.changePct != null -> "${formatChange(usage.changePct)} $vs"
            else -> "No earlier data to compare"
        },
        tone = usage?.changePct?.let { if (it > 0) Tone.Bad else if (it < 0) Tone.Good else Tone.Neutral } ?: Tone.Neutral,
    )
    // Android 9+ reports unlocks; older versions fall back to counting app opens.
    val unlocksTile = if (UsageEventsSource.supportsUnlocks) {
        TileModel(
            icon = Icons.Outlined.LockOpen,
            label = "Unlocks",
            value = usage?.unlocks?.toString() ?: dash,
            detail = when {
                noPermission -> "Needs usage access"
                usage == null -> ""
                usage.unlocksChangePct != null -> "${formatChange(usage.unlocksChangePct)} $vs"
                else -> "${usage.opens} app opens"
            },
            tone = usage?.unlocksChangePct?.let { if (it > 0) Tone.Bad else if (it < 0) Tone.Good else Tone.Neutral } ?: Tone.Neutral,
        )
    } else {
        TileModel(
            icon = Icons.Outlined.TouchApp,
            label = "App opens",
            value = usage?.opens?.toString() ?: dash,
            detail = if (noPermission) "Needs usage access" else "",
            tone = Tone.Neutral,
        )
    }
    val b = state.blocks
    val blocksTile = TileModel(
        icon = Icons.Outlined.Block,
        label = "Blocks",
        value = b?.current?.total?.toString() ?: "…",
        detail = b?.let { "${it.current.apps} apps · ${it.current.domains} sites" } ?: "",
        tone = Tone.Neutral,
    )
    val streakTile = TileModel(
        icon = Icons.Outlined.LocalFireDepartment,
        label = "Focus streak",
        value = "${state.streak} ${if (state.streak == 1) "day" else "days"}",
        detail = "Best ${state.longestStreak} ${if (state.longestStreak == 1) "day" else "days"} · " +
            "${state.focusSessions} ${if (state.focusSessions == 1) "session" else "sessions"}",
        tone = Tone.Neutral,
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(screen, Modifier.weight(1f))
            StatTile(unlocksTile, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(blocksTile, Modifier.weight(1f))
            StatTile(streakTile, Modifier.weight(1f))
        }
    }
}

private data class TileModel(val icon: ImageVector, val label: String, val value: String, val detail: String, val tone: Tone)

@Composable
private fun StatTile(t: TileModel, modifier: Modifier = Modifier) {
    Card(
        modifier.semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(t.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    t.label, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(t.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            if (t.detail.isNotEmpty()) {
                Text(
                    t.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = toneColor(t.tone),
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Bad -> MaterialTheme.colorScheme.error
    Tone.Good -> MaterialTheme.colorScheme.primary
    Tone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
}

// --- Charts ---

@Composable
private fun UsageSections(state: StatsUiState) {
    when (val u = state.usage) {
        UsageState.Loading -> SectionCard("Screen time") { LoadingBox(168.dp) }
        UsageState.NoPermission -> PermissionCard()
        is UsageState.Ready -> {
            if (u.data.partialHistory) {
                Note("Android only keeps about 10 days of detailed history, so older days are empty.")
            }
            ScreenTimeCard(state.period, u.data)
            if (u.data.slices.isNotEmpty()) TopAppsCard(u.data)
        }
    }
}

@Composable
private fun ScreenTimeCard(period: StatsPeriod, data: UsageData) {
    val zone = remember { ZoneId.systemDefault() }
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val buckets = data.window.buckets
    val futureFrom = buckets.indexOfFirst { it.start > data.now }.let { if (it < 0) buckets.size else it }
    val title = if (period == StatsPeriod.TODAY) "Screen time per hour" else "Screen time per day"
    SectionCard(title) {
        if (data.totalMillis == 0L) EmptyText("No screen time recorded in this period yet.")
        else ScreenTimeChart(period, data, buckets, futureFrom, selected, zone, title) { selected = it }
    }
}

@Composable
private fun ScreenTimeChart(
    period: StatsPeriod,
    data: UsageData,
    buckets: List<TimeRange>,
    futureFrom: Int,
    selected: Int?,
    zone: ZoneId,
    title: String,
    onSelect: (Int?) -> Unit,
) {
    Column {
        val sel = selected
        val info = when {
            sel != null -> "${bucketTitle(period, buckets[sel], zone)} · ${formatDuration(data.buckets[sel])}"
            period == StatsPeriod.TODAY -> "Tap a bar for details"
            else -> {
                val days = (futureFrom - data.firstBucketWithData).coerceAtLeast(1)
                "Daily average ${formatDuration(data.totalMillis / days)}"
            }
        }
        Text(info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        BarChart(
            values = data.buckets,
            xLabel = { i -> bucketAxisLabel(period, buckets[i], i, buckets.size, zone) },
            selected = selected,
            onSelect = onSelect,
            description = "$title. Total ${formatDuration(data.totalMillis)}. " +
                buckets.indices.filter { data.buckets[it] > 0 }.joinToString("; ") {
                    "${bucketTitle(period, buckets[it], zone)}: ${formatDuration(data.buckets[it])}"
                },
            firstWithData = data.firstBucketWithData,
            futureFrom = futureFrom,
        )
    }
}

@Composable
private fun TopAppsCard(data: UsageData) {
    val palette = seriesColors()
    val other = otherColor()
    val colors = data.slices.mapIndexed { i, s -> if (s.packageName == null) other else palette[i % palette.size] }
    SectionCard("Top apps") {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val donut: @Composable () -> Unit = {
                DonutChart(
                    fractions = data.slices.map { it.fraction },
                    colors = colors,
                    centerTop = formatDuration(data.totalMillis),
                    centerBottom = "total",
                    description = "Share of screen time: " + data.slices.joinToString("; ") {
                        "${it.label} ${(it.fraction * 100).toInt()} percent"
                    },
                    diameter = if (maxWidth < 300.dp) 132.dp else 148.dp,
                )
            }
            val legend: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.slices.forEachIndexed { i, s -> LegendRow(colors[i], s.label, s.fraction) }
                }
            }
            if (maxWidth < 300.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    donut()
                    Spacer(Modifier.height(12.dp))
                    legend(Modifier.fillMaxWidth())
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    donut()
                    Spacer(Modifier.width(16.dp))
                    legend(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, fraction: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            label, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Text(
            "${(fraction * 100).toInt().coerceAtLeast(if (fraction > 0f) 1 else 0)}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- App list ---

private fun LazyListScope.appList(state: StatsUiState, expanded: String?, onToggle: (String) -> Unit) {
    val data = (state.usage as? UsageState.Ready)?.data ?: return
    item(key = "apps-title") {
        Text(
            "Apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )
    }
    if (data.apps.isEmpty()) {
        item(key = "apps-empty") { EmptyText("No apps used in this period.") }
        return
    }
    val slicePkgs = data.slices.mapNotNull { it.packageName }
    items(data.apps, key = { "app-" + it.packageName }) { app ->
        AppRowItem(
            app = app,
            period = state.period,
            data = data,
            colorIndex = slicePkgs.indexOf(app.packageName),
            blocks = state.blocks?.current?.perApp?.get(app.packageName) ?: 0,
            expanded = expanded == app.packageName,
            onClick = { onToggle(app.packageName) },
        )
    }
}

@Composable
private fun AppRowItem(
    app: AppRow,
    period: StatsPeriod,
    data: UsageData,
    colorIndex: Int,
    blocks: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val palette = seriesColors()
    val barColor = if (colorIndex >= 0) palette[colorIndex % palette.size] else MaterialTheme.colorScheme.outline
    val zone = remember { ZoneId.systemDefault() }
    Card(
        Modifier.fillMaxWidth().clickable(
            onClickLabel = if (expanded) "Hide history" else "Show history",
            role = Role.Button,
            onClick = onClick,
        ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName, modifier = Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(formatDuration(app.millis), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    ShareBar(app.share, barColor)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${(app.share * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        app.changePct?.let { pct ->
                            Text(
                                formatChange(pct),
                                style = MaterialTheme.typography.labelSmall,
                                color = toneColor(if (pct > 0) Tone.Bad else if (pct < 0) Tone.Good else Tone.Neutral),
                            )
                        }
                        if (blocks > 0) {
                            Text(
                                "blocked ${blocks}×",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
                var selected by remember { mutableStateOf<Int?>(null) }
                val buckets = data.window.buckets
                val futureFrom = buckets.indexOfFirst { it.start > data.now }.let { if (it < 0) buckets.size else it }
                Column(Modifier.padding(top = 12.dp)) {
                    val sel = selected
                    Text(
                        if (sel != null) "${bucketTitle(period, buckets[sel], zone)} · ${formatDuration(app.perBucket[sel])}"
                        else if (period == StatsPeriod.TODAY) "Today by hour" else "By day",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    BarChart(
                        values = app.perBucket,
                        xLabel = { i -> bucketAxisLabel(period, buckets[i], i, buckets.size, zone) },
                        selected = selected,
                        onSelect = { selected = it },
                        description = "${app.label} history",
                        firstWithData = data.firstBucketWithData,
                        futureFrom = futureFrom,
                        barColor = barColor,
                        height = 112.dp,
                    )
                }
            }
        }
    }
}

// --- Building blocks ---

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun PermissionCard() {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("See where your time goes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Warden needs \"Usage access\" to show screen time, unlocks and your top apps. " +
                    "Everything stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            }) { Text("Grant usage access") }
        }
    }
}

@Composable
private fun Note(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun LoadingBox(height: Dp) {
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
