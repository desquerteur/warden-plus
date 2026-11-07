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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.warden.blocker.data.Schedule
import com.warden.blocker.hosts.HostsSource
import com.warden.blocker.time.TrustedClock
import java.time.LocalDate
import java.time.ZoneId

private const val HOUR = 3_600_000L

/** The next full hour after [now]. */
private fun nextHour(now: Long) = (now / HOUR + 1) * HOUR

/**
 * Create/edit a one-time block: start and end date + time, which may be days apart. Saved as a
 * [Schedule] with [Schedule.onceStartAt]/[Schedule.onceEndAt].
 */
@Composable
fun OnceBlockDialog(initial: Schedule?, onSave: (Schedule) -> Unit, onDismiss: () -> Unit) {
    val now = remember { TrustedClock.now() }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var start by remember { mutableLongStateOf(initial?.onceStartAt ?: nextHour(now)) }
    var end by remember { mutableLongStateOf(initial?.onceEndAt ?: (nextHour(now) + 2 * HOUR)) }
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    val problem = when {
        end <= start -> "The end must be after the start"
        end <= TrustedClock.now() -> "The end is in the past"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && problem == null,
                onClick = {
                    onSave(
                        (initial ?: Schedule(name = "", daysMask = 0, startMinute = 0, endMinute = 0)).copy(
                            name = name.trim(), daysMask = 0, onceStartAt = start, onceEndAt = end,
                            validFromDay = null, validUntilDay = null,
                        ),
                    )
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New one-time block" else "Edit one-time block") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = if (name.isBlank()) { { Text("Enter a name to save") } } else null,
                )
                DateTimeRow("Starts", start, { new ->
                    // Moving the start keeps the length, so a later start doesn't flip the order.
                    end = new + (end - start).coerceAtLeast(HOUR)
                    start = new
                })
                DateTimeRow("Ends", end, { end = it }, minDate = TrustedClock.today())
                Text(
                    problem ?: "Blocks from ${dateTimeLabel(start, TrustedClock.now(), zone, is24Hour)} " +
                        "to ${dateTimeLabel(end, TrustedClock.now(), zone, is24Hour)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/**
 * "Only on certain dates" for a weekly schedule: valid from / until (local dates, inclusive;
 * either may be left open). [onChange] gets epoch days.
 */
@Composable
fun DateRangeSection(fromDay: Long?, untilDay: Long?, onChange: (Long?, Long?) -> Unit) {
    val today = TrustedClock.today()
    val on = fromDay != null || untilDay != null
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Checkbox, onValueChange = { checked ->
            if (checked) onChange(today.toEpochDay(), today.plusWeeks(1).toEpochDay()) else onChange(null, null)
        }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = on, onCheckedChange = null)
        Text("Only on certain dates", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
    if (!on) return
    DateBound("Valid from", fromDay, onSet = { onChange(it, untilDay) }, onClear = { onChange(null, untilDay) })
    DateBound("Valid until (incl.)", untilDay, onSet = { onChange(fromDay, it) }, onClear = { onChange(fromDay, null) })
    if (!dateRangeValid(fromDay, untilDay)) {
        Text("The end date must not be before the start date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** True unless both dates are set and the end is before the start. */
fun dateRangeValid(fromDay: Long?, untilDay: Long?): Boolean =
    fromDay == null || untilDay == null || untilDay >= fromDay

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DateBound(label: String, day: Long?, onSet: (Long) -> Unit, onClear: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val today = TrustedClock.today()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { picking = true }) {
                Text(day?.let { dateLabel(LocalDate.ofEpochDay(it), today) } ?: "Any date")
            }
            if (day != null) TextButton(onClick = onClear) { Text("Clear") }
        }
    }
    if (picking) {
        PickDateDialog(day?.let(LocalDate::ofEpochDay) ?: today, null, onPick = { onSet(it.toEpochDay()) }, onDismiss = { picking = false })
    }
}

/**
 * "Block from … / until …" for a blocklist entry or hosts list (epoch millis, null = no limit).
 * Shortcuts set the end to tonight, tomorrow morning or Sunday.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpanSection(from: Long?, until: Long?, onChange: (Long?, Long?) -> Unit) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    val now = TrustedClock.now()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Time limit", style = MaterialTheme.typography.labelLarge)
        Text(
            spanLabel(from, until, now, zone, is24Hour)?.let { "Blocks $it" } ?: "Blocks with no end date",
            style = MaterialTheme.typography.bodySmall,
            color = if (until != null && until <= now) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (until != null) {
            DateTimeRow("Block until", until, { onChange(from, it) }, minDate = TrustedClock.today())
            TextButton(onClick = { onChange(from, null) }) { Text("No end date") }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                quickUntilChoices(now, zone, is24Hour).forEach { q ->
                    OutlinedButton(onClick = { onChange(from, q.at) }) { Text(q.label) }
                }
                OutlinedButton(onClick = { onChange(from, nextHour(now) + 2 * HOUR) }) { Text("Pick an end…") }
            }
        }
        if (from != null) {
            DateTimeRow("Start blocking at", from, { onChange(it, until) })
            TextButton(onClick = { onChange(null, until) }) { Text("Start right away") }
        } else {
            TextButton(onClick = { onChange(nextHour(now), until) }) { Text("Start later…") }
        }
        if (from != null && until != null && until <= from) {
            Text("The end must be after the start", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** "until Sun 18:00" (or "from …", "ended …") under an entry's name; nothing without a span. */
@Composable
fun SpanLabel(from: Long?, until: Long?) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val now = TrustedClock.now()
    val text = spanLabel(from, until, now, ZoneId.systemDefault(), is24Hour) ?: return
    val over = until != null && until <= now
    Text(
        "⏱ ${text.replaceFirstChar { it.uppercase() }}",
        style = MaterialTheme.typography.bodySmall,
        color = if (over) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
    )
}

/** True unless both ends are set and the end is not after the start. */
fun spanValid(from: Long?, until: Long?): Boolean = from == null || until == null || until > from

/** Time limit for an imported hosts list. */
@Composable
fun ListSpanDialog(source: HostsSource, onSave: (Long?, Long?) -> Unit, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf(source.activeFrom) }
    var until by remember { mutableStateOf(source.activeUntil) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        confirmButton = {
            TextButton(enabled = spanValid(from, until), onClick = { onSave(from, until); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(source.name, maxLines = 2) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SpanSection(from, until) { f, u -> from = f; until = u }
            }
        },
    )
}
