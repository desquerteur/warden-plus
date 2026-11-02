package com.warden.blocker.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.warden.blocker.data.Schedule

// Two letters so Tue/Thu and Sat/Sun are distinguishable.
private val DAY_SHORT = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

// The material clock dial is 256dp wide; below that the keyboard input is used instead.
private val CLOCK_DIAL_MIN_WIDTH = 256.dp

/** Create/edit dialog for a [Schedule]: name, days, and start/end times. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleEditorDialog(initial: Schedule?, onSave: (Schedule) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var daysMask by remember { mutableIntStateOf(initial?.daysMask ?: 0b0011111) }
    var start by remember { mutableIntStateOf(initial?.startMinute ?: 9 * 60) }
    var end by remember { mutableIntStateOf(initial?.endMinute ?: 17 * 60) }
    var picking by remember { mutableStateOf<String?>(null) } // "start" | "end" | null

    AlertDialog(
        onDismissRequest = onDismiss,
        // Own width instead of the platform default, so narrow screens (e.g. 320dp fold cover
        // displays) still get as much room as possible.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && daysMask != 0,
                onClick = {
                    onSave(
                        (initial ?: Schedule(name = "", daysMask = 0, startMinute = 0, endMinute = 0)).copy(
                            name = name.trim(), daysMask = daysMask, startMinute = start, endMinute = end,
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
                )
                Text("Days", style = MaterialTheme.typography.labelLarge)
                // Wraps onto a second line when 7 circles don't fit, so every day stays tappable.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DAY_SHORT.forEachIndexed { i, short ->
                        DayToggle(
                            label = short,
                            description = DAY_NAMES[i],
                            selected = (daysMask shr i) and 1 == 1,
                            onToggle = { daysMask = daysMask xor (1 shl i) },
                        )
                    }
                }
                OutlinedButton(onClick = { picking = "start" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start ${fmtTime(start)}")
                }
                OutlinedButton(onClick = { picking = "end" }, modifier = Modifier.fillMaxWidth()) {
                    Text("End ${fmtTime(end)}")
                }
                if (end <= start) {
                    Text("Ends next day (overnight window)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )

    picking?.let { which ->
        val minutes = if (which == "start") start else end
        val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = is24Hour)
        AlertDialog(
            onDismissRequest = { picking = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
            confirmButton = {
                TextButton(onClick = {
                    val v = state.hour * 60 + state.minute
                    if (which == "start") start = v else end = v
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            title = { Text(if (which == "start") "Start time" else "End time") },
            text = {
                BoxWithConstraints(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    if (maxWidth >= CLOCK_DIAL_MIN_WIDTH) {
                        TimePicker(state = state)
                    } else {
                        // Keyboard entry fits narrow screens; horizontal scroll is only a last-resort guard.
                        TimeInput(state = state, modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                }
            },
        )
    }
}

/** A 40dp round on/off toggle for one weekday. */
@Composable
private fun DayToggle(label: String, description: String, selected: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.outline, CircleShape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimary else colors.onSurface)
    }
}

private fun fmtTime(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
