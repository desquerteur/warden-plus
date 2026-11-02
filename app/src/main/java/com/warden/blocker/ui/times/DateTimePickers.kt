package com.warden.blocker.ui.times

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

// Natural widths of the material pickers; narrower dialogs shrink them (see ShrinkToFit).
private val CALENDAR_WIDTH = 360.dp
private val CLOCK_WIDTH_24H = 256.dp
private val CLOCK_WIDTH_12H = 280.dp // AM/PM box next to the hour and minute

/** Local date and time of [millis] in the phone's zone. */
internal fun localParts(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<LocalDate, LocalTime> {
    val z = Instant.ofEpochMilli(millis).atZone(zone)
    return z.toLocalDate() to z.toLocalTime().withSecond(0).withNano(0)
}

/** [date] at [time] in the phone's zone; a time in the skipped DST hour moves past it. */
internal fun toMillis(date: LocalDate, time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atTime(time).atZone(zone).toInstant().toEpochMilli()

/**
 * "Label  [Sun 9 Nov] [18:00]": two buttons that open the calendar and the clock. [minDate]
 * greys out earlier dates in the calendar.
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

/**
 * A date: always the material calendar. It is laid out at its normal 360dp and shrunk to fit
 * narrower screens (fold cover display, 320-344dp), so every day stays visible and tappable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickDateDialog(
    initial: LocalDate,
    minDate: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    maxDate: LocalDate? = null,
    confirmLabel: String = "OK",
) {
    val minUtc = minDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val maxUtc = maxDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    // The material date picker works in UTC midnights.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        initialDisplayMode = DisplayMode.Picker,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                (minUtc == null || utcTimeMillis >= minUtc) && (maxUtc == null || utcTimeMillis <= maxUtc)

            override fun isSelectableYear(year: Int) =
                (minDate == null || year >= minDate.year) && (maxDate == null || year <= maxDate.year)
        },
    )
    PickerDialog(
        onDismiss = onDismiss,
        confirm = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    onDismiss()
                },
            ) { Text(confirmLabel) }
        },
    ) {
        ShrinkToFit(CALENDAR_WIDTH) { DatePicker(state = state) }
    }
}

/** A real calendar date from typed fields, or null (e.g. 31 Feb). */
internal fun parseDate(day: String, month: String, year: String): LocalDate? = runCatching {
    val y = year.toInt()
    if (y !in 2000..2100) return null
    LocalDate.of(y, month.toInt(), day.toInt())
}.getOrNull()

/**
 * A time: the material clock dial (tap or drag the hand). The small keyboard icon switches to
 * typing. 24h or 12h follows the phone's setting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickTimeDialog(
    title: String,
    initial: LocalTime,
    is24Hour: Boolean,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "OK",
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    var typing by remember { mutableStateOf(false) }
    PickerDialog(
        onDismiss = onDismiss,
        leading = {
            IconButton(onClick = { typing = !typing }) {
                Icon(
                    if (typing) Icons.Outlined.Schedule else Icons.Outlined.Keyboard,
                    contentDescription = if (typing) "Use the clock" else "Type the time",
                )
            }
        },
        confirm = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text(confirmLabel) } },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 16.dp),
            )
            if (typing) {
                TimeInput(state = state)
            } else {
                ShrinkToFit(if (is24Hour) CLOCK_WIDTH_24H else CLOCK_WIDTH_12H) {
                    TimePicker(state = state, layoutType = TimePickerLayoutType.Vertical)
                }
            }
        }
    }
}

/**
 * The frame shared by the clock and the calendar: a dialog as wide as the screen allows (8dp
 * margins, at most the calendar's width), scrollable on short screens, Cancel / OK below.
 */
@Composable
private fun PickerDialog(
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    leading: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(8.dp).widthIn(max = CALENDAR_WIDTH),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                content()
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leading()
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    confirm()
                }
            }
        }
    }
}

/**
 * Lays [content] out at [designWidth] and, where there is less room, scales it down to fit
 * (touches are scaled along). With enough room it keeps its normal size, centred.
 */
@Composable
internal fun ShrinkToFit(designWidth: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val design = designWidth.roundToPx()
        val scale = shrinkScale(constraints.maxWidth, design)
        val child = measurables.first().measure(
            if (scale < 1f) Constraints.fixedWidth(design) else constraints.copy(minWidth = 0, minHeight = 0),
        )
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else child.width
        layout(width, (child.height * scale).roundToInt()) {
            child.placeWithLayer(((width - child.width * scale) / 2).roundToInt(), 0) {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }
    }
}

/** 1 when [design] px fit into [available] px, else the factor that makes them fit. */
internal fun shrinkScale(available: Int, design: Int): Float =
    if (available == Constraints.Infinity || available >= design || design <= 0) 1f else available.toFloat() / design
