package com.warden.blocker.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.warden.blocker.data.Schedule
import com.warden.blocker.ui.times.DateRangeSection
import com.warden.blocker.ui.times.PickTimeDialog
import com.warden.blocker.ui.times.dateRangeValid
import java.time.LocalTime

// Two letters so Tue/Thu and Sat/Sun are distinguishable.
private val DAY_SHORT = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** Create/edit dialog for a [Schedule]: name, days, and start/end times. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorDialog(initial: Schedule?, onSave: (Schedule) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var daysMask by remember { mutableIntStateOf(initial?.daysMask ?: 0b0011111) }
    var start by remember { mutableIntStateOf(initial?.startMinute ?: 9 * 60) }
    var end by remember { mutableIntStateOf(initial?.endMinute ?: defaultEnd(9 * 60)) }
    // A new schedule's end follows its start (start + 1 h) until the end is picked by hand.
    var endPicked by remember { mutableStateOf(initial != null) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end" | null
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    // Optional "valid from … until …" dates (epoch days).
    var fromDay by remember { mutableStateOf(initial?.validFromDay) }
    var untilDay by remember { mutableStateOf(initial?.validUntilDay) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Own width instead of the platform default, so narrow screens (e.g. 320dp fold cover
        // displays) still get as much room as possible.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && daysMask != 0 && dateRangeValid(fromDay, untilDay),
                onClick = {
                    onSave(
                        (initial ?: Schedule(name = "", daysMask = 0, startMinute = 0, endMinute = 0)).copy(
                            name = name.trim(), daysMask = daysMask, startMinute = start, endMinute = end,
                            validFromDay = fromDay, validUntilDay = untilDay,
                        ),
                    )
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (initial == null) "New schedule" else "Edit schedule") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    // Explains why "Save" is greyed out.
                    supportingText = if (name.isBlank()) { { Text("Enter a name to save") } } else null,
                )
                Text("Days", style = MaterialTheme.typography.labelLarge)
                // Wraps onto a second line when 7 chips don't fit, so every day stays tappable.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DAY_SHORT.forEachIndexed { i, short ->
                        FilterChip(
                            selected = (daysMask shr i) and 1 == 1,
                            onClick = { daysMask = daysMask xor (1 shl i) },
                            label = { Text(short) },
                            modifier = Modifier.semantics { contentDescription = DAY_NAMES[i] },
                        )
                    }
                }
                if (daysMask == 0) {
                    Text(
                        "Pick at least one day to save",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start ${clockTime(start, is24Hour)}")
                }
                OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.fillMaxWidth()) {
                    Text("End ${clockTime(end, is24Hour)}")
                }
                if (end <= start) {
                    Text("Ends next day (overnight window)", style = MaterialTheme.typography.bodySmall)
                }
                DateRangeSection(fromDay, untilDay) { f, u -> fromDay = f; untilDay = u }
            }
        },
    )

    picking?.let { which ->
        val minutes = if (which == "start") start else end
        PickTimeDialog(
            title = if (which == "start") "Start time" else "End time",
            initial = LocalTime.of(minutes / 60, minutes % 60),
            is24Hour = is24Hour,
            onPick = { t ->
                val v = t.hour * 60 + t.minute
                if (which == "start") {
                    start = v
                    if (!endPicked) end = defaultEnd(v)
                } else {
                    end = v
                    endPicked = true
                }
            },
            onDismiss = { picking = null },
        )
    }
}

/** A new schedule's end: one hour after [startMinute], wrapping past midnight. */
internal fun defaultEnd(startMinute: Int) = (startMinute + 60) % (24 * 60)
