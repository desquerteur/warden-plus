package com.warden.blocker.ui.guard

import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import com.warden.blocker.ui.CardPadding
import com.warden.blocker.ui.Motion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.warden.blocker.block.ProtectedChange
import com.warden.blocker.guard.SettingsGuardStore
import com.warden.blocker.guard.describeClockChange
import com.warden.blocker.util.PermissionsHelper
import com.warden.blocker.wardenContainer
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Settings card for "Settings guard" (mode: warn only). Off by default; switching it on needs
 * a confirmation that says exactly what it does. Switching it off goes through Block protection.
 */
@Composable
fun SettingsGuardSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { SettingsGuardStore.get(context) }
    val scope = rememberCoroutineScope()
    val enabled by store.enabled.collectAsState(initial = false)
    val watchDateTime by store.watchDateTime.collectAsState(initial = false)
    val warned by store.warnedCount.collectAsState(initial = 0)
    val clockChanges by store.clockChanges.collectAsState(initial = 0)
    val lastClock by store.lastClockChange.collectAsState(initial = null)
    var showConfirm by remember { mutableStateOf(false) }
    val accessibilityOn = remember(enabled) { PermissionsHelper.isAccessibilityEnabled(context) }

    // Switching off goes through Block protection (PIN, a wait or a lock) like every other weakening.
    fun loosen(setting: ProtectedChange.Setting) {
        scope.launch { context.wardenContainer.protection.request(ProtectedChange.SettingOff(setting)) }
    }

    Card(modifier.fillMaxWidth()) {
        // Grows and shrinks smoothly when parts appear (switched on, update found, …).
        Column(Modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM)).padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Settings guard", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (enabled) {
                            "On (warn only) — you get a hint when a page that could switch Warden+ off is opened."
                        } else {
                            "Off — get a hint when a system page that could switch Warden+ off is opened."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { on ->
                    if (on) showConfirm = true else loosen(ProtectedChange.Setting.SETTINGS_GUARD)
                })
            }
            if (enabled) {
                if (!accessibilityOn) {
                    Text(
                        "Needs Warden+'s accessibility access, which is off right now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = watchDateTime, onCheckedChange = { on ->
                        if (on) scope.launch { store.setWatchDateTime(true) } else loosen(ProtectedChange.Setting.GUARD_DATE_TIME)
                    })
                    Text("Also Date & time", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Hints shown: $warned", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "Clock changed by hand: $clockChanges" + (lastClock?.let { c ->
                    " (last: ${describeClockChange(c.kind, c.deltaMs).lowercase()}, " +
                        "${DateFormat.getDateFormat(context).format(Date(c.at))})"
                } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Turn on Settings guard?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Warden+ will show a short hint whenever one of these is opened:")
                    Text(
                        "• Warden+'s App info (Force stop, Clear storage, Uninstall, Battery)\n" +
                            "• Uninstalling Warden+\n" +
                            "• Warden+'s Accessibility page\n" +
                            "• Deactivating Warden+ as device admin\n" +
                            "• VPN settings, or another app taking over the VPN\n" +
                            "• Samsung Device care / Active apps for Warden+\n" +
                            "• Date & time — only if you tick it",
                    )
                    Text(
                        "Warn only: the page stays open and works as usual. Nothing else in Settings " +
                            "is affected — calls, Wi-Fi, Bluetooth, display and sound are never touched.",
                    )
                    Text(
                        "While it is on, a notification says so. Turn it off here at any time " +
                            "(Block protection rules apply, once set up).",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; scope.launch { store.setEnabled(true) } }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Cancel") } },
        )
    }
}
