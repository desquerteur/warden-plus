package com.warden.blocker.ui

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.Shield
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
import com.warden.blocker.time.TrustedClock
import com.warden.blocker.ui.stats.shownFocusStreak
import com.warden.blocker.ui.times.BlockUntilCard
import com.warden.blocker.ui.time.AutoTimeCard
import com.warden.blocker.ui.tour.TourIds
import com.warden.blocker.ui.tour.tourTarget
import com.warden.blocker.ui.wizard.SetupProgressCard
import com.warden.blocker.ui.wizard.WizardStep
import com.warden.blocker.ui.wizard.rememberOpenWizardAt
import com.warden.blocker.util.PermissionsHelper
import com.warden.blocker.vpn.VpnController
import com.warden.blocker.system.PrivateDns
import com.warden.blocker.vpn.WardenVpnService
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
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
    val focusActive = focusEndsAt > TrustedClock.now()
    val protecting = enabled || focusActive
    var showPaused by remember { mutableStateOf(false) }
    LaunchedEffect(protecting, vpnRunning) {
        showPaused = false
        // Give a just-started tunnel a moment, so switching on doesn't flash the warning.
        if (protecting && !vpnRunning) { delay(1500); showPaused = true }
    }
    val vpnRevoked by VpnController.revoked.collectAsStateWithLifecycle()
    // Re-checked when the tunnel comes or goes, not only on resume.
    val otherVpnActive = remember(resumes, vpnRunning, vpnRevoked) { VpnController.otherVpnActive(context) }
    val otherVpn = vpnRefused || otherVpnActive
    val problem = if (showPaused) filterProblem(protecting, vpnRunning, vpnRefused, vpnRevoked, otherVpnActive) else FilterProblem.NONE
    val scroll = rememberScrollState()
    ScrollToTopOnReselect(scroll)

    val gutter = pageGutter()

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(gutter),
        verticalArrangement = Arrangement.spacedBy(CardSpacing),
    ) {
        PageTitle(stringResource(R.string.app_name))
        // Both accessibility cards open the wizard right on "App blocking", not where it was left.
        val openWizardAt = rememberOpenWizardAt(onOpenSetup)
        if (needsSetup && AccessibilityAlert.shouldWarn(false, accessibilityUsed, enabled, items.any { it.type == BlockType.APP && it.enabled })) {
            // It worked before (or is clearly relied on) — typically Android switched it off after
            // Warden+ was updated from an APK file. Make that impossible to miss.
            WarningCard(
                title = "App blocking is off",
                text = "Warden+ has no accessibility access, so apps aren't blocked. Android switches it off " +
                    "after an update from an APK file and after \"Force stop\". Tap to turn it on.",
                modifier = Modifier.tourTarget(TourIds.HOME_SETUP),
                onClick = { openWizardAt(WizardStep.APP_BLOCKING) },
            )
        } else {
            // One setup card at a time: the wizard's progress, or else the plain "Finish setup".
            SetupProgressCard(vm, onOpen = onOpenSetup, modifier = Modifier.tourTarget(TourIds.HOME_SETUP)) {
                if (needsSetup) {
                    Card(Modifier.fillMaxWidth().clickable { openWizardAt(WizardStep.APP_BLOCKING) }.tourTarget(TourIds.HOME_SETUP)) {
                        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Finish setup", style = MaterialTheme.typography.titleMedium)
                            SupportingText("Grant accessibility so Warden+ can block apps and feeds. Tap to fix.")
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                SupportingText(stringResource(R.string.accessibility_help_hint))
                            }
                        }
                    }
                }
            }
        }
        // Android allows one VPN at a time; another app (Tailscale & co.) holds it, often as
        // "Always-on VPN", which makes Android close our consent dialog without showing it.
        AnimatedVisibility(problem == FilterProblem.OTHER_VPN) {
            WarningCard(
                title = "Website blocking is off",
                text = "Another VPN app (for example Tailscale) is on or set to \"Always-on VPN\". Android " +
                    "allows only one VPN at a time. Turn the other VPN off — and its \"Always-on VPN\" " +
                    "option — in VPN settings, then tap Try again." +
                    if (needsSetup) "" else " App blocking still works.",
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { runCatching { context.startActivity(VpnController.settingsIntent()) } }) { Text("VPN settings") }
                    OutlinedButton(onClick = onRetryVpn) { Text("Try again") }
                }
            }
        }
        // Android took the tunnel away (VPN settings, or Always-on cleared when device owner was
        // removed) and no other VPN is there: one tap brings it back (D6).
        AnimatedVisibility(problem == FilterProblem.DISCONNECTED) {
            WarningCard(
                title = "Website filter was disconnected",
                text = "Android disconnected Warden+'s website filter. Tap Try again to turn it back on." +
                    if (needsSetup) "" else " App blocking still works.",
            ) {
                OutlinedButton(onClick = onRetryVpn) { Text("Try again") }
            }
        }
        // Private DNS with a provider (DNS over TLS) goes past the filter: websites aren't blocked.
        val privateDns = remember(resumes) { PrivateDns.currentBypass(context) }
        AnimatedVisibility(protecting && privateDns != null) {
            WarningCard(
                title = "Private DNS goes around the filter",
                text = "Android's Private DNS is set to ${privateDns.orEmpty()}, so websites aren't blocked. Set it to " +
                    "Automatic: Network & internet → Private DNS (Samsung: Connections → More connection settings).",
            ) {
                OutlinedButton(onClick = { runCatching { context.startActivity(PrivateDns.settingsIntent()) } }) { Text("Open settings") }
            }
        }
        // Automatic time off or the clock set by hand: blocks are safe (TrustedClock), but say it.
        AutoTimeCard()
        // Blocking is on but the filter isn't running (OS killed it). Never leave the user
        // silently unprotected — surface it and offer a one-tap re-arm.
        AnimatedVisibility(problem == FilterProblem.PAUSED) {
            WarningCard(
                title = "Protection paused",
                text = "Blocking is on but the filter isn't running — your phone may have stopped it. Tap to restart.",
                onClick = onRetryVpn,
            )
        }
        // The main switch: a shield that lights up (with a few soft pulses) while protecting.
        Card(Modifier.fillMaxWidth().tourTarget(TourIds.HOME_MASTER)) {
            Column(
                Modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM)).padding(CardPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Shield, Icons.Outlined.Shield, active = protecting, pulse = true)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        // Off goes through Block protection: PIN, a wait, or "locked until …".
                        onCheckedChange = onToggleBlocking,
                    )
                }
                Spacer(Modifier.height(4.dp))
                BlockingHeadline(enabled, alwaysOn, focusEndsAt, schedules)
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                if (protecting) {
                    SupportingText(
                        protectionStatus(appsOn = !needsSetup, websitesOn = vpnRunning, otherVpn = otherVpn, disconnected = vpnRevoked),
                        color = if (needsSetup || showPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                SupportingText(
                    "Turning this on starts a local, on-device VPN that filters DNS. Warden+ adds no " +
                        "servers of its own — allowed lookups go to your normal DNS resolver.",
                )
            }
        }
        PendingChangesCard(vm)
        Box(Modifier.tourTarget(TourIds.HOME_FOCUS)) { FocusCard(focusEndsAt = focusEndsAt, strict = focusStrict, onStart = onStartFocus, onCancel = onCancelFocus) }
        // "Block until …" — saved first, then the same VPN start (and consent) as a retry.
        Box(Modifier.tourTarget(TourIds.HOME_BLOCK_UNTIL)) { BlockUntilCard(focusEndsAt = focusEndsAt, focusStrict = focusStrict, onStarted = onRetryVpn) }
        AnimatedVisibility(shownFocusStreak(streak, focusSessions) > 0) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(CardPadding), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Outlined.LocalFireDepartment, Icons.Outlined.LocalFireDepartment, active = true)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        val shown = rememberCountUp(streak.toLong())
                        Text("$shown-day focus streak", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        SupportingText("Keep Warden+ on each day to grow it.")
                    }
                }
            }
        }
        InfoNote("Add sites and apps in Blocklist, or set times in Schedules.")
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
    SectionCard("Pending changes") {
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

/**
 * "Blocking is ON/OFF", "Focus session – 23 min left" or "On – waiting for schedule (next: …)".
 * Ticks on its own so only this line recomposes while a countdown or a schedule is due.
 * A new text fades in over the old one.
 */
@Composable
private fun BlockingHeadline(enabled: Boolean, alwaysOn: Boolean, focusEndsAt: Long, schedules: List<Schedule>) {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    var now by remember { mutableLongStateOf(TrustedClock.now()) }
    LaunchedEffect(Unit) {
        while (true) { delay(5_000); now = TrustedClock.now() }
    }
    val text = blockingHeadline(
        enabled, alwaysOn, focusEndsAt, schedules,
        TrustedClock.calendar(now), is24Hour,
    )
    AnimatedContent(
        text,
        transitionSpec = { fadeIn(tween(Motion.MEDIUM)) togetherWith fadeOut(tween(Motion.SHORT)) },
        label = "headline",
    ) { shown ->
        Text(shown, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
