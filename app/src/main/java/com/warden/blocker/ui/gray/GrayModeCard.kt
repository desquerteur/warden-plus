package com.warden.blocker.ui.gray

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.gray.GrayMode
import com.warden.blocker.gray.GrayModeConfig
import com.warden.blocker.gray.GrayWhen
import com.warden.blocker.ui.SupportingText
import com.warden.blocker.ui.SwitchCard
import com.warden.blocker.ui.animationsEnabled
import com.warden.blocker.ui.clockTime
import com.warden.blocker.ui.times.PickTimeDialog
import com.warden.blocker.ui.tour.SettingsGuide
import com.warden.blocker.ui.tour.SettingsPathAnimation
import com.warden.blocker.ui.tour.openSettingsGuide
import java.time.LocalTime

/**
 * Settings › Gray mode: the switch, when to go gray, and — while the one-time ADB permission is
 * missing — how to grant it, or how to switch Android's grayscale on by hand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrayModeCard(modifier: Modifier = Modifier, vm: GrayModeViewModel = viewModel()) {
    val context = LocalContext.current
    val config by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val is24Hour = DateFormat.is24HourFormat(context)
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    SwitchCard(
        "Gray mode",
        "Colours make phones more tempting – gray makes them boring.",
        checked = config.enabled,
        onCheckedChange = vm::setEnabled,
        modifier = modifier,
    ) {
        AnimatedVisibility(config.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GrayWhen.entries.forEach { w ->
                        FilterChip(selected = config.whenMode == w, onClick = { vm.setWhen(w) }, label = { Text(w.label) })
                    }
                }
                if (config.whenMode == GrayWhen.SCHEDULE) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picking = true }) { Text("From ${clockTime(config.startMinute, is24Hour)}") }
                        OutlinedButton(onClick = { picking = false }) { Text("Until ${clockTime(config.endMinute, is24Hour)}") }
                    }
                }
                SupportingText(explain(config, is24Hour))
                if (status.permission) {
                    SupportingText(if (status.grayNow) "The screen is gray right now." else "The screen is in colour right now.")
                } else {
                    PermissionHelp()
                }
                SupportingText("Switching it off or making it shorter follows Block protection.")
            }
        }
    }

    picking?.let { start ->
        val minute = if (start) config.startMinute else config.endMinute
        PickTimeDialog(
            title = if (start) "Gray from" else "Gray until",
            initial = LocalTime.of(minute / 60, minute % 60),
            is24Hour = is24Hour,
            onPick = { t -> (t.hour * 60 + t.minute).let { if (start) vm.setStart(it) else vm.setEnd(it) } },
            onDismiss = { picking = null },
        )
    }
}

private fun explain(config: GrayModeConfig, is24Hour: Boolean): String = when (config.whenMode) {
    GrayWhen.ALWAYS -> "Gray all the time."
    GrayWhen.WHILE_BLOCKING -> "Gray whenever blocking is on: main switch, schedules, focus and “Block until”."
    GrayWhen.SCHEDULE ->
        if (config.startMinute == config.endMinute) "Gray all day."
        else "Gray every day from ${clockTime(config.startMinute, is24Hour)} to ${clockTime(config.endMinute, is24Hour)}."
}

/** The one-time ADB step, and the manual way through Android's colour correction. */
@Composable
private fun PermissionHelp() {
    val context = LocalContext.current
    val animate = remember { animationsEnabled(context) }
    Text("One more step", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    SupportingText(
        "Android lets Warden+ turn the screen gray only after a one-time permission from a computer. " +
            "Connect the phone with USB debugging on and run (or use scripts/enable-gray-mode.ps1):",
    )
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                GrayMode.ADB_GRANT,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
    TextButton(onClick = {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("ADB command", GrayMode.ADB_GRANT))
        Toast.makeText(context, "Command copied", Toast.LENGTH_SHORT).show()
    }) { Text("Copy command") }
    SupportingText("No computer? Switch grayscale on yourself — Warden+ then can't switch it by itself:")
    SettingsPathAnimation(SettingsGuide.GRAYSCALE, animate, Modifier.fillMaxWidth())
    SettingsGuide.GRAYSCALE.note?.let { SupportingText(it) }
    OutlinedButton(onClick = { openSettingsGuide(context, SettingsGuide.GRAYSCALE) }) {
        Text(SettingsGuide.GRAYSCALE.openLabel ?: "Open settings")
    }
}
