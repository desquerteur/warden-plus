package com.warden.blocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.system.AdminManager
import com.warden.blocker.ui.update.UpdateSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: WardenViewModel, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val strict by vm.strictMode.collectAsStateWithLifecycle()
    val alwaysOn by vm.alwaysOn.collectAsStateWithLifecycle()
    val blockDoh by vm.blockDoh.collectAsStateWithLifecycle()
    val blockNotifications by vm.blockNotifications.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()

    var unlocked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var showSetPin by remember { mutableStateOf(false) }
    var showRemovePin by remember { mutableStateOf(false) }
    var adminActive by remember { mutableStateOf(AdminManager.isActive(context)) }

    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        adminActive = AdminManager.isActive(context)
    }

    // PIN gate: settings are locked until the PIN is entered.
    if (hasPin && !unlocked) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Settings are locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showUnlock = true }) { Text("Unlock with PIN") }
        }
        if (showUnlock) {
            VerifyPinDialog("Enter PIN", vm::verifyPin, onSuccess = { unlocked = true; showUnlock = false }, onDismiss = { showUnlock = false })
        }
        return
    }

    val scroll = rememberScrollState()
    ScrollToTopOnReselect(scroll)
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // Blocking mode: always-on vs schedule-driven
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Always on", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (alwaysOn) "Blocking is enforced whenever it's switched on." else "Blocking is enforced only during your schedules.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = alwaysOn, onCheckedChange = { vm.setAlwaysOn(it) })
            }
        }

        // Strict mode
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Strict mode", style = MaterialTheme.typography.titleMedium)
                    Text("Locks the master switch while a block is active. Pair with a PIN + uninstall protection.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = strict, onCheckedChange = { on ->
                    vm.setStrictMode(on)
                    if (on && !adminActive) adminLauncher.launch(AdminManager.enableIntent(context))
                })
            }
        }

        // Close the DNS-over-HTTPS bypass
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Block DNS-over-HTTPS", style = MaterialTheme.typography.titleMedium)
                    Text("Also blocks encrypted-DNS resolvers so browsers can't bypass website filtering. Applies next time blocking starts.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = blockDoh, onCheckedChange = { vm.setBlockDoh(it) })
            }
        }

        // Notification blocking
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Block notifications", style = MaterialTheme.typography.titleMedium)
                        Text("Silences notifications from blocked apps while blocking is active.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = blockNotifications, onCheckedChange = { vm.setBlockNotifications(it) })
                }
                if (blockNotifications) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                        Text("Grant notification access")
                    }
                }
            }
        }

        // Uninstall protection (device admin)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Uninstall protection", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (adminActive) "On — Warden+ can't be uninstalled until you turn this off." else "Off — Warden+ can be uninstalled anytime.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                if (adminActive) {
                    OutlinedButton(
                        onClick = { if (!strict) { AdminManager.disable(context); adminActive = AdminManager.isActive(context) } },
                        enabled = !strict,
                    ) { Text(if (strict) "Turn off strict mode first" else "Turn off protection") }
                } else {
                    Button(onClick = { adminLauncher.launch(AdminManager.enableIntent(context)) }) { Text("Turn on protection") }
                }
            }
        }

        // PIN
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("PIN lock", style = MaterialTheme.typography.titleMedium)
                Text(if (hasPin) "A PIN is required to change settings or stop blocking." else "Protect settings and disabling with a 4-digit PIN.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showSetPin = true }) { Text(if (hasPin) "Change PIN" else "Set PIN") }
                    if (hasPin) OutlinedButton(onClick = { showRemovePin = true }) { Text("Remove PIN") }
                }
            }
        }

        UpdateSection()

        HorizontalDivider()
        OutlinedButton(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
            Text("Setup & permissions")
        }
    }

    if (showSetPin) {
        SetPinDialog(onDone = { vm.setPin(it) }, onDismiss = { showSetPin = false })
    }
    if (showRemovePin) {
        VerifyPinDialog("Enter PIN to remove", vm::verifyPin, onSuccess = { vm.clearPin(); showRemovePin = false }, onDismiss = { showRemovePin = false })
    }
}
