package com.warden.blocker.ui

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.R
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.Schedule
import com.warden.blocker.system.AccessibilityAlert
import com.warden.blocker.ui.stats.shownFocusStreak
import com.warden.blocker.ui.times.BlockUntilCard
import com.warden.blocker.ui.tour.TourIds
import com.warden.blocker.ui.tour.tourTarget
import com.warden.blocker.ui.wizard.SetupProgressCard
import com.warden.blocker.ui.wizard.WizardStep
import com.warden.blocker.ui.wizard.rememberOpenWizardAt
import com.warden.blocker.util.PermissionsHelper
import com.warden.blocker.vpn.VpnController
import com.warden.blocker.vpn.WardenVpnService
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun HomeScreen(
    vm: WardenViewModel,
    onToggleBlocking: (Boolean) -> Unit,
    onStartFocus: (Int, Boolean) -> Unit,
    onCancelFocus: () -> Unit,
    onOpenSetup: () -> Unit,
    onRetryVpn: () -> Unit,
) {
    val context = LocalContext.current
    // Re-check system state when coming back from Settings (accessibility, another VPN).
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val needsSetup = remember(resumes) { !PermissionsHelper.essentialsGranted(context) }
    val otherVpnActive = remember(resumes) { VpnController.otherVpnActive(context) }
    val vpnRefused by VpnController.refused.collectAsStateWithLifecycle()
    val accessibilityUsed by vm.accessibilityUsed.collectAsStateWithLifecycle()
    val enabled by vm.masterEnabled.collectAsStateWithLifecycle()
    val streak by vm.currentStreak.collectAsStateWithLifecycle()
    val focusSessions by vm.focusSessionsDone.collectAsStateWithLifecycle()
    val alwaysOn by vm.alwaysOn.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val focusEndsAt by vm.focusEndsAt.collectAsStateWithLifecycle()
    val focusStrict by vm.focusStrict.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val hostsLists by vm.hostsLists.collectAsStateWithLifecycle()
    val summary = protectedSummary(items.count { it.enabled }, hostsLists)
    // Observed, not read once: the card used to keep whatever the tunnel state was at the last
    // recomposition (e.g. "paused" right after switching on, before the VPN was up).
    val vpnRunning by WardenVpnService.runningState.collectAsStateWithLifecycle()
    // A focus session forces blocking on even while the main switch is off.
    val focusActive = focusEndsAt > System.currentTimeMillis()
    val protecting = enabled || focusActive
    var showPaused by remember { mutableStateOf(false) }
    LaunchedEffect(protecting, vpnRunning) {
        showPaused = false
        // Give a just-started tunnel a moment, so switching on doesn't flash the warning.
        if (protecting && !vpnRunning) { delay(1500); showPaused = true }
    }
    val otherVpn = vpnRefused || otherVpnActive
    val scroll = rememberScrollState()
    ScrollToTopOnReselect(scroll)

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        // Both accessibility cards open the wizard right on "App blocking", not where it was left.
        val openWizardAt = rememberOpenWizardAt(onOpenSetup)
        if (needsSetup && AccessibilityAlert.shouldWarn(false, accessibilityUsed, enabled, items.any { it.type == BlockType.APP && it.enabled })) {
            // It worked before (or is clearly relied on) — typically Android switched it off after
            // Warden+ was updated from an APK file. Make that impossible to miss.
            Box(Modifier.tourTarget(TourIds.HOME_SETUP)) {
            WarningCard(
                title = "⚠ App blocking is off",
                text = "Warden+ has no accessibility access, so apps aren't blocked. Android switches it off " +
                    "after an update from an APK file and after \"Force stop\". Tap to turn it on.",
                onClick = { openWizardAt(WizardStep.APP_BLOCKING) },
            )
            }
        } else {
            // One setup card at a time: the wizard's progress, or else the plain "Finish setup".
            SetupProgressCard(vm, onOpen = onOpenSetup, modifier = Modifier.tourTarget(TourIds.HOME_SETUP)) {
                if (needsSetup) {
                    ElevatedCard(Modifier.fillMaxWidth().clickable { openWizardAt(WizardStep.APP_BLOCKING) }.tourTarget(TourIds.HOME_SETUP)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Grant accessibility so Warden+ can block apps and feeds. Tap to fix.", style = MaterialTheme.typography.bodySmall)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                Text(stringResource(R.string.accessibility_help_hint), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        if (protecting && showPaused && otherVpn) {
            // Android allows one VPN at a time; another app (Tailscale & co.) holds it, often as
            // "Always-on VPN", which makes Android close our consent dialog without showing it.
            WarningCard(
                title = "⚠ Website blocking is off",
                text = "Another VPN app (for example Tailscale) is on or set to \"Always-on VPN\". Android " +
                    "allows only one VPN at a time. Turn the other VPN off — and its \"Always-on VPN\" " +
                    "option — in VPN settings, then tap Try again." +
                    if (needsSetup) "" else " App blocking still works.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runCatching { context.startActivity(VpnController.settingsIntent()) } }) { Text("VPN settings") }
                    OutlinedButton(onClick = onRetryVpn) { Text("Try again") }
                }
            }
        } else if (protecting && showPaused) {
            // Blocking is on but the filter isn't running (OS killed it). Never leave the user
            // silently unprotected — surface it and offer a one-tap re-arm.
            WarningCard(
                title = "⚠ Protection paused",
                text = "Blocking is on but the filter isn't running — your phone may have stopped it. Tap to restart.",
                onClick = onRetryVpn,
            )
        }
        ElevatedCard(Modifier.fillMaxWidth().tourTarget(TourIds.HOME_MASTER)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BlockingHeadline(enabled, alwaysOn, focusEndsAt, schedules)
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                        if (protecting) {
                            Text(
                                protectionStatus(appsOn = !needsSetup, websitesOn = vpnRunning, otherVpn = otherVpn),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (needsSetup || showPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = enabled,
                        // Off goes through Block protection: PIN, a wait, or "locked until …".
                        onCheckedChange = onToggleBlocking,
                    )
                }
                Text(
                    "Turning this on starts a local, on-device VPN that filters DNS. Warden+ adds no " +
                        "servers of its own — allowed lookups go to your normal DNS resolver.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        PendingChangesCard(vm)
        Box(Modifier.tourTarget(TourIds.HOME_FOCUS)) { FocusCard(focusEndsAt = focusEndsAt, strict = focusStrict, onStart = onStartFocus, onCancel = onCancelFocus) }
        // "Block until …" — saved first, then the same VPN start (and consent) as a retry.
        Box(Modifier.tourTarget(TourIds.HOME_BLOCK_UNTIL)) { BlockUntilCard(focusEndsAt = focusEndsAt, focusStrict = focusStrict, onStarted = onRetryVpn) }
        if (shownFocusStreak(streak, focusSessions) > 0) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("🔥", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(0.dp))
                    Column(Modifier.padding(start = 16.dp)) {
                        Text("$streak-day focus streak", style = MaterialTheme.typography.titleLarge)
                        Text("Keep Warden+ on each day to grow it.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Add sites and apps in Blocklist, or set times in Schedules.", style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Changes Block protection's "Delay" mode is holding back, e.g. "Instagram will be unblocked
 * at 15:40", each with Cancel (which always works right away). Nothing when the queue is empty.
 */
@Composable
internal fun PendingChangesCard(vm: WardenViewModel) {
    val pending by vm.pendingChanges.collectAsStateWithLifecycle()
    if (pending.isEmpty()) return
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pending changes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            pending.sortedBy { it.dueAt }.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${p.change.describe()} at ${vm.whenText(p.dueAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.cancelPending(p.id) }) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * "Blocking is ON/OFF", "Focus session – 23 min left" or "On – waiting for schedule (next: …)".
 * Ticks on its own so only this line recomposes while a countdown or a schedule is due.
 */
@Composable
private fun BlockingHeadline(enabled: Boolean, alwaysOn: Boolean, focusEndsAt: Long, schedules: List<Schedule>) {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { delay(5_000); now = System.currentTimeMillis() }
    }
    val text = blockingHeadline(
        enabled, alwaysOn, focusEndsAt, schedules,
        Calendar.getInstance().apply { timeInMillis = now }, is24Hour,
    )
    Text(text, style = MaterialTheme.typography.titleLarge)
}

/** Red card for something that leaves the user less protected than the switch suggests. */
@Composable
private fun WarningCard(
    title: String,
    text: String,
    onClick: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    ElevatedCard(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodySmall)
            actions()
        }
    }
}
