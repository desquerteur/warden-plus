package com.warden.blocker.ui.owner

import android.content.Context
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.owner.DeviceOwnerManager
import com.warden.blocker.owner.EmergencyExit
import com.warden.blocker.owner.ExitStatus
import com.warden.blocker.owner.OwnerPolicy
import com.warden.blocker.owner.title
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Settings card for device-owner mode ("Maximum protection"): how to set it up, a switch per
 * rule, and the emergency exit that always leads back out (24 h wait at most).
 */
@Composable
fun MaxProtectionSection(modifier: Modifier = Modifier, vm: OwnerViewModel = viewModel()) {
    val context = LocalContext.current
    val isOwner by vm.isOwner.collectAsStateWithLifecycle()
    val policies by vm.policies.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Card(modifier.fillMaxWidth()) {
        // Grows and shrinks smoothly when parts appear (switched on, update found, …).
        Column(Modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM)).padding(CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Maximum protection (device owner)", style = MaterialTheme.typography.titleMedium)
            if (!isOwner) {
                Text(
                    "Expert mode. Connect the phone to a computer once and make Warden+ the device owner. " +
                        "Then Android itself enforces the rules you pick here — blocked apps are switched off, " +
                        "the filter can't be disconnected, the clock can't be changed. For the setup all accounts " +
                        "(Google, Samsung) have to be signed out for a moment. There's always a way back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainer {
                    Text(
                        DeviceOwnerManager.SET_OWNER_COMMAND,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                // Inside the app: a browser link could itself be blocked by Warden+ (S3).
                var showGuide by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showGuide = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Step-by-step guide")
                }
                if (showGuide) DeviceOwnerGuideScreen(onClose = { showGuide = false })
            } else {
                Text(
                    "On — Warden+ is the device owner. Pick the rules Android should enforce:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OwnerPolicy.entries.forEach { policy ->
                    PolicyRow(policy, checked = policy in policies, onChange = { vm.setPolicy(policy, it) })
                }
                if (policies.size < OwnerPolicy.entries.size) {
                    TextButton(onClick = vm::enableAll) { Text("Turn all on") }
                }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = vm::clearMessage) { Text("OK") }
            }
            if (isOwner) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                EmergencyExitBlock(vm, pinBypassed = false)
            }
        }
    }
}

/**
 * For the PIN-locked Settings: "Forgot your PIN?" must not trap anyone in device-owner mode.
 * Shown only while Warden+ is device owner; always waits the full 24 h.
 */
@Composable
fun OwnerLockedExit(modifier: Modifier = Modifier, vm: OwnerViewModel = viewModel()) {
    val isOwner by vm.isOwner.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    if (!isOwner && message == null) return
    Column(modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isOwner) EmergencyExitBlock(vm, pinBypassed = true)
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PolicyRow(policy: OwnerPolicy, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(policy.title, style = MaterialTheme.typography.bodyLarge)
            Text(policy.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EmergencyExitBlock(vm: OwnerViewModel, pinBypassed: Boolean) {
    val context = LocalContext.current
    val readyAt by vm.exitReadyAt.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(readyAt) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }

    Text("Emergency exit", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    when (val status = EmergencyExit.status(readyAt, now)) {
        ExitStatus.NotRequested -> {
            Text(
                if (pinBypassed) {
                    "Forgot your PIN? You can still end device-owner mode — after a 24-hour wait."
                } else {
                    "Lifts every rule above and ends device-owner mode. Always possible — if Block protection " +
                        "asks for a wait, it's 24 hours at most."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Remove device owner")
            }
        }
        is ExitStatus.Waiting -> {
            Text(
                "Removal requested. Possible from ${formatDateTime(context, status.readyAt)} " +
                    "(in ${formatWait(status.readyAt - now)}).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = vm::cancelExit, modifier = Modifier.fillMaxWidth()) { Text("Cancel request") }
        }
        ExitStatus.Ready -> {
            Text("The wait is over. Device owner can be removed now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = vm::removeNow, modifier = Modifier.fillMaxWidth()) { Text("Remove device owner now") }
            TextButton(onClick = vm::cancelExit) { Text("Keep device owner") }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove device owner?") },
            text = {
                Text(
                    "All device-owner rules are lifted: blocked apps are switched back on, the VPN and clock " +
                        "are free again. Warden+ keeps blocking like before (with the accessibility service). " +
                        "To make it device owner again you need the computer and ADB." +
                        if (pinBypassed) "\n\nWithout the PIN this takes 24 hours." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; vm.requestExit(pinBypassed) }) {
                    Text(if (pinBypassed) "Start 24 h wait" else "Remove")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

internal val OwnerPolicy.explanation: String
    get() = when (this) {
        OwnerPolicy.SUSPEND_BLOCKED_APPS ->
            "While blocking is on, hard-blocked apps (and apps at their limit) are greyed out by Android — " +
                "even without the accessibility service. Apps with a pause stay usable."
        OwnerPolicy.ALWAYS_ON_VPN -> "Android keeps Warden+'s filter connected; it can't be disconnected."
        OwnerPolicy.BLOCK_VPN_SETTINGS -> "No VPN changes in Settings — no other VPN app can replace the filter."
        OwnerPolicy.BLOCK_UNINSTALL -> "Warden+ can't be uninstalled, not even with a computer."
        OwnerPolicy.BLOCK_APP_CONTROLS -> "No Force stop, Clear data or Uninstall for any app in Settings."
        OwnerPolicy.LOCK_DATE_TIME -> "Time is set automatically and can't be changed, so schedules can't be tricked."
        OwnerPolicy.BLOCK_SAFE_BOOT -> "Safe mode would start the phone without Warden+."
        OwnerPolicy.SUSPEND_VPN_APPS ->
            "While blocking is on, VPN apps (not the ones you trust) are greyed out, so none can push the filter out."
        OwnerPolicy.LOCK_PRIVATE_DNS -> "Private DNS stays on Automatic: no encrypted DNS around the filter."
    }

private fun formatDateTime(context: Context, millis: Long): String {
    val date = Date(millis)
    return DateFormat.getMediumDateFormat(context).format(date) + " " + DateFormat.getTimeFormat(context).format(date)
}

private fun formatWait(ms: Long): String {
    val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(1)
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h h $m min" else "$m min"
}
