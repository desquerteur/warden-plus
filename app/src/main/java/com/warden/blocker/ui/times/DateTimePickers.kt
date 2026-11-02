package com.warden.blocker.ui.times

import android.text.format.DateFormat
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// The material clock dial is 256dp wide; below that the keyboard input is used instead.
private val CLOCK_DIAL_MIN_WIDTH = 256.dp

// The material calendar needs 360dp; narrower screens (fold cover display) type the date.
private const val CALENDAR_MIN_SCREEN_DP = 380

/** Local date and time of [millis] in the phone's zone. */
internal fun localParts(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<LocalDate, LocalTime> {
    val z = Instant.ofEpochMilli(millis).atZone(zone)
    return z.toLocalDate() to z.toLocalTime().withSecond(0).withNano(0)
}

/** [date] at [time] in the phone's zone; a time in the skipped DST hour moves past it. */
internal fun toMillis(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atTime(time).atZone(zone).toInstant().toEpochMilli()

/**
 * "Label  [Sun 9 Nov] [18:00]": two buttons that open a date and a time picker. [minDate]
 * greys out earlier dates in the calendar and refuses them when typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DateTimeRow(label: String, value: Long, onChange: (Long) -> Unit, minDate: LocalDate? = null) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    val (date, time) = localParts(value, zone)
    var picking by remember { mutableStateOf<String?>(null) } // "date" | "time"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { picking = "date" }) { Text(dateLabel(date, LocalDate.now(zone))) }
            OutlinedButton(onClick = { picking = "time" }) { Text(timeLabel(value, zone, is24Hour)) }
        }
    }
    when (picking) {
        "date" -> PickDateDialog(date, minDate, onPick = { onChange(toMillis(it, time, zone)) }, onDismiss = { picking = null })
        "time" -> PickTimeDialog(label, time, is24Hour, onPick = { onChange(toMillis(date, it, zone)) }, onDismiss = { picking = null })
    }
}

/** A date: the material calendar (with its own "type it" mode) where it fits, else typed fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickDateDialog(initial: LocalDate, minDate: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    if (LocalConfiguration.current.screenWidthDp < CALENDAR_MIN_SCREEN_DP) {
        TypedDateDialog(initial, minDate, onPick, onDismiss)
        return
    }
    val minUtc = minDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    // The material date picker works in UTC midnights.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialDisplayMode = DisplayMode.Picker,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = minUtc == null || utcTimeMillis >= minUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state)
    }
}

/** Day / month / year typed in, plus shortcuts — fits a 320dp screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypedDateDialog(initial: LocalDate, minDate: LocalDate?, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    var day by remember { mutableStateOf(initial.dayOfMonth.toString()) }
    var month by remember { mutableStateOf(initial.monthValue.toString()) }
    var year by remember { mutableStateOf(initial.year.toString()) }
    val parsed = parseDate(day, month, year)
    val tooEarly = parsed != null && minDate != null && parsed.isBefore(minDate)
    val today = LocalDate.now()
    fun set(d: LocalDate) { day = d.dayOfMonth.toString(); month = d.monthValue.toString(); year = d.year.toString() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        confirmButton = {
            TextButton(enabled = parsed != null && !tooEarly, onClick = { onPick(parsed!!); onDismiss() }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Date") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NumberField("Day", day, { day = it }, Modifier.weight(1f))
                    NumberField("Month", month, { month = it }, Modifier.weight(1f))
                    NumberField("Year", year, { year = it }, Modifier.weight(1.4f))
                }
                Text(
                    when {
                        parsed == null -> "Not a valid date"
                        tooEarly -> "Pick ${dateLabel(minDate!!, today)} or later"
                        else -> dateLabel(parsed, today)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed == null || tooEarly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { set(today) }, label = { Text("Today") })
                    AssistChip(onClick = { set(today.plusDays(1)) }, label = { Text("Tomorrow") })
                    AssistChip(onClick = { set(today.plusWeeks(1)) }, label = { Text("In a week") })
                }
            }
        },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(4)) },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}

/** A real calendar date from typed fields, or null (e.g. 31 Feb). */
internal fun parseDate(day: String, month: String, year: String): LocalDate? = runCatching {
    val y = year.toInt()
    if (y !in 2000..2100) return null
    LocalDate.of(y, month.toInt(), day.toInt())
}.getOrNull()

/** A time: typed by default (exact minutes are fiddly on the dial); the dial is one tap away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickTimeDialog(title: String, initial: LocalTime, is24Hour: Boolean, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    var dial by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            BoxWithConstraints(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                val dialFits = maxWidth >= CLOCK_DIAL_MIN_WIDTH
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (dial && dialFits) {
                        TimePicker(state = state)
                    } else {
                        // Horizontal scroll is only a last-resort guard for very narrow screens.
                        TimeInput(state = state, modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                    if (dialFits) {
                        TextButton(onClick = { dial = !dial }) { Text(if (dial) "Type the time" else "Use clock dial") }
                    }
                }
            }
        },
    )
}
