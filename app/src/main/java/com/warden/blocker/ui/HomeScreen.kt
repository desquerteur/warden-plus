package com.warden.blocker.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.R
import com.warden.blocker.util.PermissionsHelper
import com.warden.blocker.vpn.WardenVpnService

@Composable
fun HomeScreen(
    vm: WardenViewModel,
    onToggleBlocking: (Boolean) -> Unit,
    onStartFocus: (Int, Boolean) -> Unit,
    onCancelFocus: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val context = LocalContext.current
    val needsSetup = !PermissionsHelper.essentialsGranted(context)
    val enabled by vm.masterEnabled.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val streak by vm.currentStreak.collectAsStateWithLifecycle()
    val focusEndsAt by vm.focusEndsAt.collectAsStateWithLifecycle()
    val focusStrict by vm.focusStrict.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val activeCount = items.count { it.enabled }
    var showVerify by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Warden", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (needsSetup) {
            ElevatedCard(Modifier.fillMaxWidth().clickable { onOpenSetup() }) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Grant accessibility so Warden can block apps and feeds. Tap to fix.", style = MaterialTheme.typography.bodySmall)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Text(stringResource(R.string.accessibility_help_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else if (enabled && !WardenVpnService.running) {
            // Blocking is on but the filter isn't running (OS killed it). Never leave the user
            // silently unprotected — surface it and offer a one-tap re-arm.
            ElevatedCard(
                Modifier.fillMaxWidth().clickable { onToggleBlocking(true) },
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("⚠ Protection paused", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Blocking is on but the filter isn't running — your phone may have stopped it. Tap to restart.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(if (enabled) "Blocking is ON" else "Blocking is OFF", style = MaterialTheme.typography.titleLarge)
                        Text("$activeCount item(s) protected", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { turnOn ->
                            // Disabling while a PIN is set requires the PIN (strict-mode friction).
                            if (!turnOn && hasPin) showVerify = true else onToggleBlocking(turnOn)
                        },
                    )
                }
                Text(
                    "Turning this on starts a local, on-device VPN that filters DNS. Warden adds no " +
                        "servers of its own — allowed lookups go to your normal DNS resolver.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        FocusCard(focusEndsAt = focusEndsAt, strict = focusStrict, onStart = onStartFocus, onCancel = onCancelFocus)
        if (streak > 0) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔥", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(0.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text("$streak-day focus streak", style = MaterialTheme.typography.titleLarge)
                        Text("Keep Warden on each day to grow it.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Add sites and apps in Blocklist, or set times in Schedules.", style = MaterialTheme.typography.bodyMedium)
    }

    if (showVerify) {
        VerifyPinDialog(
            title = "Enter PIN to stop blocking",
            verify = vm::verifyPin,
            onSuccess = { showVerify = false; onToggleBlocking(false) },
            onDismiss = { showVerify = false },
        )
    }
}
