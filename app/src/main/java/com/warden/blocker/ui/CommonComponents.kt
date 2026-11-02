package com.warden.blocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@Composable
internal fun FocusCard(focusEndsAt: Long, strict: Boolean, onStart: (Int, Boolean) -> Unit, onCancel: () -> Unit) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(focusEndsAt) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val active = focusEndsAt > now

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active) {
                Text("Focus session", style = MaterialTheme.typography.titleMedium)
                Text(formatCountdown(focusEndsAt - now), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    if (strict) "Strict — this can't be ended early. Deep work, locked in." else "Blocking stays on until the timer ends.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!strict) OutlinedButton(onClick = onCancel) { Text("End session") }
            } else {
                Text("Start a focus session", style = MaterialTheme.typography.titleMedium)
                Text("Force blocking on for a set time — great for deep work.", style = MaterialTheme.typography.bodySmall)
                var strictChoice by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = strictChoice, onCheckedChange = { strictChoice = it })
                    Text("Strict (no early exit)", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 50).forEach { m ->
                        Button(onClick = { onStart(m, strictChoice) }) { Text("$m min") }
                    }
                }
            }
        }
    }
}

internal fun formatCountdown(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
internal fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// --- small formatting helpers ---
internal fun fmt(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

internal fun daysLabel(mask: Int): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return names.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.joinToString(" ")
}

internal fun fmtDuration(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
