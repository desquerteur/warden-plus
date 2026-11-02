package com.warden.blocker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.warden.blocker.security.PinResult
import kotlinx.coroutines.launch

private const val PIN_LEN = 4

/** Enter-and-confirm dialog for setting a new PIN. */
@Composable
fun SetPinDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableIntStateOf(0) }

    fun onComplete(pin: String) {
        if (first == null) {
            first = pin; entry = ""; error = null
        } else if (first == pin) {
            onDone(pin); onDismiss()
        } else {
            first = null; entry = ""; error = "PINs didn't match — start again"; errors++
        }
    }

    PinDialog(
        title = if (first == null) "Set a PIN" else "Confirm your PIN",
        subtitle = error,
        entry = entry,
        onDigit = { if (entry.length < PIN_LEN) { entry += it; if (entry.length == PIN_LEN) onComplete(entry) } },
        onBackspace = { if (entry.isNotEmpty()) entry = entry.dropLast(1) },
        onDismiss = onDismiss,
        shakeKey = errors,
    )
}

/** Verify dialog; [verify] runs off the DB and returns a [PinResult] (enforces lockout). */
@Composable
fun VerifyPinDialog(
    title: String,
    verify: suspend (String) -> PinResult,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var errors by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    PinDialog(
        title = title,
        subtitle = error,
        entry = entry,
        onDigit = {
            if (entry.length < PIN_LEN) {
                entry += it
                if (entry.length == PIN_LEN) {
                    val attempt = entry
                    scope.launch {
                        when (val result = verify(attempt)) {
                            PinResult.Ok -> onSuccess()
                            PinResult.Wrong -> { entry = ""; error = "Wrong PIN"; errors++ }
                            is PinResult.Locked -> { entry = ""; error = "Too many tries — locked for ${result.seconds}s"; errors++ }
                        }
                    }
                }
            }
        },
        onBackspace = { if (entry.isNotEmpty()) entry = entry.dropLast(1) },
        onDismiss = onDismiss,
        shakeKey = errors,
    )
}

@Composable
private fun PinDialog(
    title: String,
    subtitle: String?,
    entry: String,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onDismiss: () -> Unit,
    shakeKey: Int = 0,
) {
    SecureScreen() // FLAG_SECURE (release) + anti-tapjacking while the PIN pad is visible
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(8.dp))
                }
                // Dots fill with a small bounce; a wrong PIN shakes the row.
                val motion = LocalMotion.current
                val shake = remember { Animatable(0f) }
                LaunchedEffect(shakeKey) {
                    if (shakeKey == 0 || !motion) return@LaunchedEffect
                    for (x in listOf(-10f, 10f, -8f, 8f, -4f, 0f)) shake.animateTo(x, tween(45))
                }
                Row(
                    Modifier.offset { IntOffset(shake.value.dp.roundToPx(), 0) },
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    repeat(PIN_LEN) { i -> PinDot(filled = i < entry.length) }
                }
                Spacer(Modifier.size(20.dp))
                PinPad(onDigit = onDigit, onBackspace = onBackspace)
            }
        },
    )
}

@Composable
private fun PinPad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row {
                row.forEach { key ->
                    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                        when (key) {
                            "" -> Spacer(Modifier.width(64.dp))
                            "⌫" -> TextButton(onClick = onBackspace) { Text("⌫", style = MaterialTheme.typography.headlineSmall) }
                            else -> TextButton(onClick = { onDigit(key) }) {
                                Text(key, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDot(filled: Boolean) {
    val color by animateColorAsState(
        if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        tween(Motion.SHORT),
        label = "pinDot",
    )
    val scale by animateFloatAsState(
        if (filled) 1f else 0.8f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "pinDotScale",
    )
    Box(Modifier.size(16.dp).graphicsLayer { scaleX = scale; scaleY = scale }.background(color, CircleShape))
}
