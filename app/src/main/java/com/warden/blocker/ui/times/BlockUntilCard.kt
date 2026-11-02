package com.warden.blocker.ui.times

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/**
 * Home card "Block until …": blocking stays on until a chosen time today or any date and time
 * (a focus session with a fixed end). Shortcuts for tonight, tomorrow morning and Sunday.
 * [onStarted] runs after it is saved, so Home can start the VPN (asking for consent if needed).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockUntilCard(
    focusEndsAt: Long,
    focusStrict: Boolean,
    onStarted: () -> Unit,
    vm: TimesViewModel = viewModel(),
) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val running = focusEndsAt > now
    var strict by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running) {
                Text("Blocked until ${dateTimeLabel(focusEndsAt, now, zone, is24Hour)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (focusStrict) "Strict — the end can be moved later, not sooner." else "You can move the end later at any time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { picking = true }) { Text("Change end…") }
            } else {
                Text("Block until …", style = MaterialTheme.typography.titleMedium)
                Text("Keep blocking on until a time today or any date you pick.", style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.toggleable(value = strict, role = Role.Checkbox, onValueChange = { strict = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = strict, onCheckedChange = null)
                    Text("Strict (no early end)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                // Wraps on narrow screens (320dp fold cover display) instead of cutting buttons off.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    quickUntilChoices(now, zone, is24Hour).forEach { q ->
                        OutlinedButton(onClick = { vm.blockUntil(q.at, strict, onStarted) }) { Text(q.label) }
                    }
                    OutlinedButton(onClick = { picking = true }) { Text("Pick date & time…") }
                }
            }
        }
    }

    if (picking) {
        BlockUntilDialog(
            initial = if (running) focusEndsAt else defaultUntil(now),
            onConfirm = { at -> vm.blockUntil(at, strict, onStarted) },
            onDismiss = { picking = false },
        )
    }
}

/** Two hours from now, on the next full hour. */
private fun defaultUntil(now: Long): Long = ((now + 2 * 3_600_000L) / 3_600_000L + 1) * 3_600_000L

@Composable
private fun BlockUntilDialog(initial: Long, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    var at by remember { mutableLongStateOf(initial) }
    val now = System.currentTimeMillis()
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        confirmButton = {
            TextButton(enabled = at > now, onClick = { onConfirm(at); onDismiss() }) { Text("Block") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Block until") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DateTimeRow("Blocking ends", at, { at = it }, minDate = LocalDate.now())
                Text(
                    if (at > now) "Blocked until ${dateTimeLabel(at, now, ZoneId.systemDefault(), is24Hour)}"
                    else "Pick a time in the future",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (at > now) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
