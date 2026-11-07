package com.warden.blocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.time.TrustedClock
import com.warden.blocker.ui.gray.GrayModeCard
import com.warden.blocker.ui.owner.OwnerLockedExit
import com.warden.blocker.ui.tour.RestartGuideCard
import com.warden.blocker.ui.tour.TourIds
import com.warden.blocker.ui.tour.tourTarget
import com.warden.blocker.ui.update.UpdateSection

/**
 * Settings in the original card style: the blocking mode on top, then Protection (its own page
 * with block protection, PIN, uninstall protection, Settings guard and maximum protection),
 * Blocking, Help and About & updates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: WardenViewModel, onOpenSetup: () -> Unit, onOpenProtection: () -> Unit) {
    val context = LocalContext.current
    val alwaysOn by vm.alwaysOn.collectAsStateWithLifecycle()
    val blockDoh by vm.blockDoh.collectAsStateWithLifecycle()
    val blockNotifications by vm.blockNotifications.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val protection by vm.protection.collectAsStateWithLifecycle()

    var unlocked by remember { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    val gutter = pageGutter()

    // PIN gate: settings are locked until the PIN is entered.
    if (hasPin && !unlocked) {
        Column(
            Modifier.fillMaxSize().tourTarget(TourIds.SETTINGS_LOCKED).padding(gutter),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("Settings are locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showUnlock = true }) { Text("Unlock with PIN") }
            OwnerLockedExit()
        }
        if (showUnlock) {
            VerifyPinDialog("Enter PIN", vm::verifyPin, onSuccess = { unlocked = true; showUnlock = false }, onDismiss = { showUnlock = false })
        }
        return
    }

    val scroll = rememberScrollState()
    ScrollToTopOnReselect(scroll)
    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(gutter),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        PageTitle("Settings")

        // Blocking mode: always-on vs schedule-driven
        SwitchCard(
            "Always on",
            if (alwaysOn) "Blocking is enforced whenever it's switched on." else "Blocking is enforced only during your schedules.",
            checked = alwaysOn,
            onCheckedChange = { vm.setAlwaysOn(it) },
            modifier = Modifier.tourTarget(TourIds.SETTINGS_ALWAYS_ON),
        )

        SectionHeader("Protection")
        // Everything that makes blocking hard to switch off lives on its own page.
        ActionCard(
            Icons.Outlined.Shield,
            "Protection",
            protectionSummary(protection, TrustedClock.now(), vm::whenText) + "\n" +
                (if (hasPin) "PIN set." else "No PIN.") + " Also uninstall protection, Settings guard and maximum protection.",
            onClick = onOpenProtection,
            modifier = Modifier.tourTarget(TourIds.SETTINGS_BLOCK_PROTECTION),
        )

        SectionHeader("Blocking")
        // Close the DNS-over-HTTPS bypass
        SwitchCard(
            "Block DNS-over-HTTPS",
            "Also blocks encrypted-DNS resolvers so browsers can't bypass website filtering. Applies next time blocking starts.",
            checked = blockDoh,
            onCheckedChange = { vm.setBlockDoh(it) },
        )
        // Notification blocking
        SwitchCard(
            "Block notifications",
            "Silences notifications from blocked apps while blocking is active.",
            checked = blockNotifications,
            onCheckedChange = { vm.setBlockNotifications(it) },
        ) {
            AnimatedVisibility(blockNotifications) {
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text("Grant notification access")
                }
            }
        }

        GrayModeCard()

        SectionHeader("Help")
        RestartGuideCard()
        ActionCard(
            Icons.Outlined.TaskAlt,
            "Setup wizard",
            "Permissions and setup, step by step.",
            onClick = onOpenSetup,
            modifier = Modifier.tourTarget(TourIds.SETTINGS_SETUP),
        )

        SectionHeader("About & updates")
        UpdateSection(Modifier.tourTarget(TourIds.SETTINGS_UPDATES))
    }
}
