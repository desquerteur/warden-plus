package com.warden.blocker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.warden.blocker.block.RelatedDomains
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.ui.times.SpanSection
import com.warden.blocker.ui.times.spanValid

/**
 * Bottom-sheet editor for a blocked item's enforcement: hard block vs mindful pause, and
 * (for pause) the wait, the prompt, and the daily open/time/cooldown limits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditorSheet(
    item: BlockedItem,
    onSave: (BlockedItem) -> Unit,
    onDismiss: () -> Unit,
    relatedBlocked: Boolean = true,
    onRelatedChange: (Boolean) -> Unit = {},
) {
    var mode by remember { mutableStateOf(item.interceptMode) }
    var pauseSeconds by remember { mutableStateOf(item.pauseSeconds) }
    var prompt by remember { mutableStateOf(item.customPrompt ?: "") }
    var openLimit by remember { mutableStateOf(item.openLimitPerDay) }
    var timeLimit by remember { mutableStateOf(item.dailyLimitMinutes) }
    var cooldown by remember { mutableStateOf(item.cooldownMinutes) }
    var activeFrom by remember { mutableStateOf(item.activeFrom) }
    var activeUntil by remember { mutableStateOf(item.activeUntil) }

    // Fully open at once: half open, "Save" sat below the screen edge on a 344 dp cover display
    // and Back threw the change away unnoticed (M2).
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Fully open it would reach under the status bar, drag handle and all (N12): stop below it.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, modifier = Modifier.statusBarsPadding()) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                // Scrolls: with the time limit the sheet is taller than a fold cover display. The
                // buttons below stay in sight.
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(item.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Text("When opened", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == InterceptMode.PAUSE, onClick = { mode = InterceptMode.PAUSE }, label = { Text("Mindful pause") })
                    FilterChip(selected = mode == InterceptMode.BLOCK, onClick = { mode = InterceptMode.BLOCK }, label = { Text("Hard block") })
                }

                // The pause options fold open when "Mindful pause" is picked.
                AnimatedVisibility(mode == InterceptMode.PAUSE && item.type == BlockType.APP) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Stepper("Breathe for (seconds)", pauseSeconds, step = 5, min = 0, max = 120) { pauseSeconds = it }
                        OutlinedTextField(
                            value = prompt, onValueChange = { prompt = it },
                            label = { Text("Pause prompt (optional)") },
                            placeholder = { Text("Why are you opening this?") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Stepper("Opens per day (0 = unlimited)", openLimit, step = 1, min = 0, max = 30) { openLimit = it }
                        Stepper("Daily time limit, min (0 = off)", timeLimit, step = 5, min = 0, max = 240) { timeLimit = it }
                        Stepper("Cooldown between opens, min", cooldown, step = 5, min = 0, max = 240) { cooldown = it }
                    }
                }
                AnimatedVisibility(mode == InterceptMode.PAUSE && item.type != BlockType.APP) {
                    SupportingText("Mindful pause applies to apps. Websites are hard-blocked at the network layer.")
                }
                if (item.type == BlockType.WEBSITE) RelatedDomainsSection(item.value, relatedBlocked, onRelatedChange)

                SpanSection(activeFrom, activeUntil) { f, u -> activeFrom = f; activeUntil = u }
                Spacer(Modifier.height(4.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    enabled = spanValid(activeFrom, activeUntil),
                    onClick = {
                        onSave(
                            item.copy(
                                interceptMode = mode,
                                pauseSeconds = pauseSeconds,
                                customPrompt = prompt.ifBlank { null },
                                openLimitPerDay = openLimit,
                                dailyLimitMinutes = timeLimit,
                                cooldownMinutes = cooldown,
                                activeFrom = activeFrom,
                                activeUntil = activeUntil,
                            ),
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, step: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
        Spacer(Modifier.width(8.dp))
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp))
        Spacer(Modifier.width(8.dp))
        FilledTonalIconButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}

/**
 * Pictures and videos of big sites come from other domains (issue #4): what blocking this site
 * also blocks, a switch to leave them out (a loosening: applied in Block protection), and the
 * cases no DNS filter can catch.
 */
@Composable
private fun RelatedDomainsSection(domain: String, blocked: Boolean, onChange: (Boolean) -> Unit) {
    val related = remember(domain) { RelatedDomains.relatedTo(domain) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Pictures & videos", style = MaterialTheme.typography.labelLarge)
        if (related.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Also block its image & video servers", style = MaterialTheme.typography.bodyLarge)
                    SupportingText("Also blocks: " + related.joinToString(", ") { it.domain })
                    related.mapNotNull { r -> r.sharedWith?.let { "${r.domain} is shared with $it." } }.distinct().forEach { SupportingText(it) }
                }
                Switch(checked = blocked, onCheckedChange = onChange)
            }
        }
        InfoNote(
            "Still seeing pictures? A page that was open before the block can stay in the browser's " +
                "cache — close the tab or reload. Google image search thumbnails come from Google itself " +
                "(gstatic.com) and can't be blocked without breaking Google. Chrome's \"Secure DNS\" goes " +
                "around the filter: turn on Block DNS-over-HTTPS in Settings.",
        )
    }
}
