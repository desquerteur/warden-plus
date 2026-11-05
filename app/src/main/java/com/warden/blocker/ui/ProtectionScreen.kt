package com.warden.blocker.ui

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.BuildConfig
import com.warden.blocker.block.BlockProtection
import com.warden.blocker.block.MAX_LOCK_DAYS
import com.warden.blocker.block.ProtectionMode
import com.warden.blocker.block.ProtectionSettings
import com.warden.blocker.system.AdminManager
import com.warden.blocker.owner.DeviceOwnerManager
import com.warden.blocker.owner.OwnerPolicy
import com.warden.blocker.owner.OwnerPrefs
import com.warden.blocker.ui.guard.SettingsGuardSection
import com.warden.blocker.ui.owner.MaxProtectionSection
import com.warden.blocker.ui.times.PickDateDialog
import com.warden.blocker.ui.times.PickTimeDialog
import com.warden.blocker.ui.tour.TourIds
import com.warden.blocker.ui.wizard.WizardStep
import com.warden.blocker.ui.wizard.rememberOpenWizardAt
import com.warden.blocker.ui.tour.tourTarget
import com.warden.blocker.system.Hardening
import com.warden.blocker.system.PrivateDns
import com.warden.blocker.allow.AppCategory
import com.warden.blocker.ui.vpnguard.VpnGuardSection
import com.warden.blocker.ui.vpnguard.VpnGuardViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.util.PermissionsHelper
import com.warden.blocker.vpn.VpnController
import com.warden.blocker.vpn.WardenVpnService
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One line for Settings: "PIN – weakening blocking asks for your PIN.", "Locked until Fri 18:00", … */
internal fun protectionSummary(p: ProtectionSettings, now: Long, whenText: (Long) -> String): String =
    when (BlockProtection.effectiveMode(p, now)) {
        ProtectionMode.OFF -> "Off – changes apply right away."
        ProtectionMode.PIN -> "PIN – weakening blocking asks for your PIN."
        ProtectionMode.DELAY -> "Delay – weakening waits ${durationLeft(p.delayMinutes * 60_000L)} (you can cancel)."
        ProtectionMode.LOCKED -> "Locked until ${whenText(p.lockedUntil)} – nothing can be weakened."
    }

/** How many times protection was found switched off from outside in the last [days] days. */
internal fun offCountSince(events: List<Long>, now: Long, days: Int = 7): Int =
    events.count { it > now - days * 24 * 60 * 60_000L }

private val DELAY_CHOICES = buildList {
    if (BuildConfig.DEBUG) add(1) // for testing only
    addAll(listOf(15, 60, 24 * 60))
}

/** Locked-until shortcuts, in hours. */
private val LOCK_CHOICES = listOf(1, 8, 24, 7 * 24)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProtectionScreen(vm: WardenViewModel, onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val context = LocalContext.current
    val protection by vm.protection.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    val focusEndsAt by vm.focusEndsAt.collectAsStateWithLifecycle()
    val focusStrict by vm.focusStrict.collectAsStateWithLifecycle()
    val offEvents by vm.protectionOffEvents.collectAsStateWithLifecycle()
    val alwaysOnVpn by WardenVpnService.alwaysOnState.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val mode = BlockProtection.effectiveMode(protection, now)

    // Re-check the Android settings when coming back from them.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumes++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val adminActive = remember(resumes, protection) { AdminManager.isActive(context) }
    val batteryOk = remember(resumes) { Hardening.ignoresBatteryOptimizations(context) }
    val accessibilityOn = remember(resumes) { PermissionsHelper.isAccessibilityEnabled(context) }
    // As device owner ("Maximum protection" below) uninstalling, Force stop and the always-on VPN
    // are handled there; the device-admin switch here would do nothing.
    val isOwner = remember(resumes) { DeviceOwnerManager.isDeviceOwner(context) }
    val openWizardAt = rememberOpenWizardAt(onOpenSetup)
    val ownerPolicies by OwnerPrefs.get(context).policies.collectAsStateWithLifecycle()
    val vpnGuard: VpnGuardViewModel = viewModel()
    val allowConfig by vpnGuard.config.collectAsStateWithLifecycle()
    val vpnAppsBlocked = AppCategory.VPN in allowConfig.blockedCategories
    val privateDnsBypass = remember(resumes) { PrivateDns.currentBypass(context) }

    // Delay / Locked show their choices first; the mode switches once one is picked.
    var expanded by remember { mutableStateOf<ProtectionMode?>(null) }
    var askPinFor by remember { mutableStateOf<ProtectionSettings?>(null) }
    var showAdminExplain by remember { mutableStateOf(false) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTimeOn by remember { mutableStateOf<LocalDate?>(null) }
    var showSetPin by remember { mutableStateOf(false) }
    var showRemovePin by remember { mutableStateOf(false) }

    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { resumes++ }

    fun choose(new: ProtectionSettings) {
        if (new.mode == ProtectionMode.PIN && !hasPin) { askPinFor = new; return }
        vm.setProtection(new)
        expanded = null
        // Protection that can simply be uninstalled away isn't much: offer it right after.
        if (new.mode != ProtectionMode.OFF && !adminActive) showAdminExplain = true
    }

    val gutter = pageGutter()
    Column(Modifier.fillMaxSize()) {
        SubScreenTopBar("Protection", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, bottom = gutter),
            verticalArrangement = Arrangement.spacedBy(CardSpacing),
        ) {
            IntroText("Makes it harder to weaken blocking on impulse. Adding or tightening always works right away.")

            SectionHeader("Block protection")
            SectionCard(null) {
                Text(
                    protectionSummary(protection, now, vm::whenText),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.tourTarget(TourIds.PROTECTION_MODES),
                )
                if (focusStrict && focusEndsAt > now) {
                    Text(
                        "A strict focus session locks all of this until ${vm.whenText(focusEndsAt)}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                ModeOption(
                    "Off", "Changes apply right away. With a PIN set, turning blocking off still asks for it.",
                    selected = mode == ProtectionMode.OFF,
                ) { choose(ProtectionSettings(ProtectionMode.OFF, protection.delayMinutes, 0L)) }
                ModeOption(
                    "PIN", "Every change that weakens blocking asks for your PIN." + if (hasPin) "" else " You'll set one now.",
                    selected = mode == ProtectionMode.PIN,
                ) { choose(ProtectionSettings(ProtectionMode.PIN, protection.delayMinutes, 0L)) }
                ModeOption(
                    "Delay", "Weakening waits a while and can be cancelled until then.",
                    selected = mode == ProtectionMode.DELAY || expanded == ProtectionMode.DELAY,
                ) { expanded = ProtectionMode.DELAY }
                // The choices fold open under their mode.
                AnimatedVisibility(mode == ProtectionMode.DELAY || expanded == ProtectionMode.DELAY) {
                    FlowRow(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DELAY_CHOICES.forEach { minutes ->
                            FilterChip(
                                selected = mode == ProtectionMode.DELAY && protection.delayMinutes == minutes,
                                onClick = { choose(ProtectionSettings(ProtectionMode.DELAY, minutes, 0L)) },
                                label = { Text(durationLeft(minutes * 60_000L) + if (minutes == 1) " (test)" else "") },
                            )
                        }
                    }
                }
                ModeOption(
                    "Locked until", "Nothing can be weakened until the time you pick (at most $MAX_LOCK_DAYS days ahead).",
                    selected = mode == ProtectionMode.LOCKED || expanded == ProtectionMode.LOCKED,
                ) { expanded = ProtectionMode.LOCKED }
                AnimatedVisibility(mode == ProtectionMode.LOCKED || expanded == ProtectionMode.LOCKED) {
                    FlowRow(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LOCK_CHOICES.forEach { hours ->
                            FilterChip(
                                selected = false,
                                onClick = { choose(ProtectionSettings(ProtectionMode.LOCKED, protection.delayMinutes, now + hours * 3_600_000L)) },
                                label = { Text(if (hours < 24) "$hours h" else "${hours / 24} day" + if (hours > 24) "s" else "") },
                            )
                        }
                        FilterChip(selected = false, onClick = { pickDate = true }, label = { Text("Pick date & time…") })
                    }
                }
            }

            PendingChangesCard(vm)

            // PIN
            SectionCard("PIN lock", Modifier.tourTarget(TourIds.SETTINGS_PIN)) {
                SupportingText(if (hasPin) "A PIN is required to change settings or stop blocking." else "Protect settings and disabling with a 4-digit PIN.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { showSetPin = true }) { Text(if (hasPin) "Change PIN" else "Set PIN") }
                    if (hasPin) OutlinedButton(onClick = { showRemovePin = true }) { Text("Remove PIN") }
                }
            }

            SectionHeader("Keep Warden+ running", Modifier.tourTarget(TourIds.SETTINGS_PROTECTION_CHECKLIST))
            Box(Modifier.tourTarget(TourIds.SETTINGS_UNINSTALL)) {
                if (isOwner) {
                    CheckRow(
                        ok = true,
                        title = "Uninstall protection",
                        text = "Warden+ is device owner: it can't be uninstalled and \"Force stop\" is greyed out. " +
                            "Managed under Maximum protection below (with its emergency exit).",
                        action = null,
                    )
                } else {
                    CheckRow(
                        ok = adminActive,
                        title = "Uninstall protection",
                        text = "Android won't uninstall Warden+, and \"Force stop\" in App info is greyed out.",
                        action = if (adminActive) "Turn off" else "Turn on",
                    ) { if (adminActive) vm.disableUninstallProtection() else showAdminExplain = true }
                }
            }
            CheckRow(
                ok = batteryOk,
                title = "Battery: never put to sleep",
                text = if (Hardening.isSamsung) {
                    "Also on Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add Warden+."
                } else {
                    "So battery savers don't stop Warden+ in the background."
                },
                action = if (batteryOk) "App info" else "Allow",
            ) {
                runCatching {
                    context.startActivity(if (batteryOk) Hardening.appInfoIntent(context) else Hardening.batteryExemptionIntent(context))
                }
            }
            if (isOwner && OwnerPolicy.ALWAYS_ON_VPN in ownerPolicies) {
                CheckRow(
                    ok = true,
                    title = "Always-on VPN",
                    text = "Kept on by Maximum protection below (device owner).",
                    action = null,
                )
            } else CheckRow(
                ok = alwaysOnVpn == true,
                title = "Always-on VPN",
                text = when (alwaysOnVpn) {
                    null -> "Turn blocking on to check. In VPN settings, tap ⚙ next to Warden+ and turn on \"Always-on VPN\": Android then restarts it by itself."
                    else -> "In VPN settings, tap ⚙ next to Warden+ and turn on \"Always-on VPN\": Android then restarts website blocking by itself."
                },
                action = "VPN settings",
            ) { runCatching { context.startActivity(VpnController.settingsIntent()) } }
            CheckRow(
                ok = accessibilityOn,
                title = "Accessibility (app blocking)",
                text = "Needed to block apps. Warden+ warns you if it is switched off.",
                action = "Open setup",
                onAction = { openWizardAt(WizardStep.APP_BLOCKING) },
            )
            // VPN bypass protection: VPN apps blocked and no Private DNS around the filter (card below).
            val vpnGuardOk = vpnAppsBlocked && privateDnsBypass == null
            CheckRow(
                ok = vpnGuardOk,
                title = "VPN bypass protection",
                text = when {
                    !vpnAppsBlocked -> "Block VPN apps, so another VPN can't push the website filter out."
                    privateDnsBypass != null -> "Private DNS is set to $privateDnsBypass and goes around the filter."
                    else -> "VPN apps are blocked and Private DNS is fine. More options in the card below."
                },
                action = when {
                    !vpnAppsBlocked -> "Turn on"
                    privateDnsBypass != null -> "Open settings"
                    else -> null
                },
            ) {
                if (!vpnAppsBlocked) vpnGuard.setVpnAppsBlocked(true)
                else runCatching { context.startActivity(PrivateDns.settingsIntent()) }
            }

            SettingsGuardSection(Modifier.tourTarget(TourIds.SETTINGS_GUARD_SYSTEM))
            VpnGuardSection(Modifier.tourTarget(TourIds.PROTECTION_VPN_GUARD), vm = vpnGuard)
            MaxProtectionSection(Modifier.tourTarget(TourIds.SETTINGS_DEVICE_OWNER))

            val offCount = offCountSince(offEvents, now)
            Text(
                if (offCount == 0) "No switch-offs noticed in the last 7 days."
                else "Protection was turned off $offCount× in the last 7 days.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (offCount > 0) FontWeight.Bold else FontWeight.Normal,
            )
            InfoNote(
                "Honest limits: no app can fully stop this. Turning off accessibility or the VPN in Android " +
                    "settings stays possible, uninstall protection only adds a \"Deactivate & uninstall\" step, " +
                    "and some phones (e.g. Samsung's \"Active apps → Stop\") can still stop Warden+. After a stop, " +
                    "Android also switches app blocking (accessibility) off until you turn it on again. Warden+ " +
                    "notices, tells you, and counts it here and in Usage. Phone, Settings and your home screen " +
                    "are never blocked.",
            )
        }
    }

    askPinFor?.let { pending ->
        SetPinDialog(
            onDone = { pin ->
                vm.setPinAndProtection(pin, pending)
                if (!adminActive) showAdminExplain = true
            },
            onDismiss = { askPinFor = null },
        )
    }
    if (showAdminExplain) {
        // Explained first: Android's own "Activate device admin app?" screen came out of nowhere.
        AlertDialog(
            onDismissRequest = { showAdminExplain = false },
            title = { Text("Uninstall protection?") },
            text = {
                Text(
                    "Android will ask to make Warden+ a device admin, so it can't be uninstalled or " +
                        "force-stopped while blocking is active. Warden+ uses this for nothing else — it " +
                        "can't read your data or lock your phone. Turning it off later is protected too.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAdminExplain = false
                    adminLauncher.launch(AdminManager.enableIntent(context))
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showAdminExplain = false }) { Text("Not now") } },
        )
    }
    if (pickDate) {
        val today = LocalDate.now(ZoneId.systemDefault())
        PickDateDialog(
            initial = today.plusDays(1),
            minDate = today,
            maxDate = today.plusDays(MAX_LOCK_DAYS.toLong()),
            onPick = { pickTimeOn = it },
            onDismiss = { pickDate = false },
            confirmLabel = "Next",
        )
    }
    pickTimeOn?.let { date ->
        PickTimeDialog(
            title = "Locked until (time)",
            initial = LocalTime.of(18, 0),
            is24Hour = DateFormat.is24HourFormat(context),
            onPick = { t ->
                val until = date.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (until > System.currentTimeMillis()) {
                    choose(ProtectionSettings(ProtectionMode.LOCKED, protection.delayMinutes, until))
                }
            },
            onDismiss = { pickTimeOn = null },
            confirmLabel = "Lock",
        )
    }
    if (showSetPin) {
        SetPinDialog(onDone = { vm.setPin(it) }, onDismiss = { showSetPin = false })
    }
    if (showRemovePin) {
        VerifyPinDialog(
            if (protection.mode == ProtectionMode.PIN) "Enter PIN to remove (Block protection goes off too)" else "Enter PIN to remove",
            vm::verifyPin,
            onSuccess = { vm.clearPin(); showRemovePin = false },
            onDismiss = { showRemovePin = false },
        )
    }
}

@Composable
private fun ModeOption(title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp, end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SupportingText(text)
        }
    }
}

/**
 * A checklist line: a tick (drawn when it turns done) or a red mark, what it is, and a button
 * to the right Android setting. A missing item gets its title in the theme's error colour.
 * [action] null: nothing to do here (e.g. managed by device-owner mode).
 */
@Composable
private fun CheckRow(ok: Boolean, title: String, text: String, action: String?, onAction: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(CardPadding), verticalAlignment = Alignment.Top) {
            StatusIcon(ok)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                SupportingText(text)
                if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
            }
        }
    }
}
