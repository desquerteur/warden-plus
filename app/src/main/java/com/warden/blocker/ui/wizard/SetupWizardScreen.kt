package com.warden.blocker.ui.wizard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.data.BlockType
import com.warden.blocker.hosts.HostsTemplates
import com.warden.blocker.system.AdminManager
import com.warden.blocker.system.Hardening
import com.warden.blocker.owner.DeviceOwnerManager
import com.warden.blocker.time.TrustedClock
import com.warden.blocker.ui.protectionSummary
import com.warden.blocker.ui.time.openDateSettings
import com.warden.blocker.ui.time.rememberAutoTimeOn
import com.warden.blocker.ui.AnimatedCheck
import com.warden.blocker.ui.InfoNote
import com.warden.blocker.ui.LocalMotion
import com.warden.blocker.ui.Motion
import com.warden.blocker.ui.SubScreenTopBar
import com.warden.blocker.ui.pageGutter
import com.warden.blocker.ui.WardenViewModel
import com.warden.blocker.ui.hosts.HostsViewModel
import com.warden.blocker.ui.tour.LocalTourController
import com.warden.blocker.ui.tour.SettingsGuide
import com.warden.blocker.ui.tour.SettingsPathAnimation
import com.warden.blocker.ui.tour.Tours
import com.warden.blocker.ui.animationsEnabled
import com.warden.blocker.ui.tour.openSettingsGuide
import com.warden.blocker.vpn.VpnController
import kotlinx.coroutines.delay

/**
 * First-start setup: one page per permission or choice, "Step 3 of 7", Back / Later / Next.
 * Every page checks by itself whether it's done and moves on when the user comes back from
 * Android's settings with it switched on. Also reachable later from Settings (opens on the
 * summary once finished).
 */
@Composable
fun SetupWizardScreen(
    vm: WardenViewModel,
    onClose: () -> Unit,
    onOpenAppPicker: () -> Unit,
    onEnableBlocking: () -> Unit,
    onOpenProtection: () -> Unit,
    wizardVm: WizardViewModel = viewModel(),
) {
    val wizard = wizardVm.controller
    val loaded by wizard.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { wizard.opened() }
    val state = loaded ?: return
    val sdk = Build.VERSION.SDK_INT
    val step = WizardLogic.currentStep(state, sdk)
    val done = rememberWizardDone(vm, state)
    val tours = LocalTourController.current

    fun leave() {
        wizard.dismiss()
        tours?.markSkippedIfNew(Tours.GETTING_STARTED)
        onClose()
    }

    // Came back from Android's settings with this page done: tick, then the next page.
    val doneNow = step in done
    val doneOnEnter = remember(step) { doneNow }
    LaunchedEffect(step, doneNow) {
        if (WizardLogic.shouldAutoAdvance(step, doneOnEnter, doneNow)) {
            delay(1200)
            wizard.next(step)
        }
    }

    BackHandler { if (WizardLogic.back(step, sdk) != null) wizard.back(step) else leave() }

    val gutter = pageGutter()
    Column(Modifier.fillMaxSize()) {
        SubScreenTopBar("Setup", onBack = ::leave)
        WizardLogic.position(step, sdk)?.let { (i, n) ->
            val shown by animateFloatAsState(i / n.toFloat(), tween(Motion.MEDIUM * 2), label = "wizardProgress")
            Column(Modifier.padding(horizontal = gutter)) {
                Text("Step $i of $n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        // Pages slide like a book: Next from the end, Back from the start.
        val motion = LocalMotion.current
        AnimatedContent(
            step,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                if (!motion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(Motion.MEDIUM)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(Motion.MEDIUM))) togetherWith
                        (slideOutHorizontally(tween(Motion.MEDIUM)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(Motion.SHORT)))
                }
            },
            label = "wizardPage",
        ) { page ->
            val pageDone = page in done
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(gutter),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (page) {
                        WizardStep.WELCOME -> WelcomePage()
                        WizardStep.NOTIFICATIONS -> NotificationsPage(pageDone)
                        WizardStep.APP_BLOCKING -> AppBlockingPage(pageDone)
                        WizardStep.WEBSITE_BLOCKING -> WebsiteBlockingPage(pageDone)
                        WizardStep.USAGE -> UsagePage(pageDone)
                        WizardStep.BATTERY -> BatteryPage(pageDone)
                        WizardStep.PROTECTION -> ProtectionPage(vm, pageDone, onChosen = wizard::chooseProtection, onOpenProtection)
                        WizardStep.FIRST_BLOCKS -> FirstBlocksPage(vm, onOpenAppPicker)
                        WizardStep.DONE -> DonePage(
                            vm, sdk, done, state.skipped,
                            onOpen = wizard::goTo,
                            onEnableBlocking = onEnableBlocking,
                            onTour = {
                                wizard.finish()
                                onClose()
                                tours?.start(Tours.GETTING_STARTED)
                            },
                            onFinish = {
                                wizard.finish()
                                tours?.markSkippedIfNew(Tours.GETTING_STARTED)
                                onClose()
                            },
                        )
                    }
                }
            }
        }
        if (step != WizardStep.DONE) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step == WizardStep.WELCOME) {
                    TextButton(onClick = ::leave) { Text("Skip setup") }
                } else {
                    TextButton(onClick = { wizard.back(step) }) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                when {
                    step == WizardStep.WELCOME -> Button(onClick = { wizard.next(step) }) { Text("Start") }
                    doneNow -> Button(onClick = { wizard.next(step) }) { Text("Next") }
                    else -> OutlinedButton(onClick = { wizard.later(step) }) { Text(if (step.optional) "Later" else "Skip for now") }
                }
            }
        }
    }
}

// --- pages ---

@Composable
private fun PageTitle(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

/**
 * Tick once Android reports the page as done. Coming back from Android's settings with it
 * switched on, the tick is drawn (then the wizard moves on); on a page that was already done it
 * just shows.
 */
@Composable
private fun DoneBadge(done: Boolean, text: String = "Done — on to the next step") {
    val doneOnEnter = remember { done }
    AnimatedVisibility(
        done,
        enter = fadeIn(tween(Motion.MEDIUM)) + expandVertically(tween(Motion.MEDIUM)),
        exit = fadeOut(tween(Motion.SHORT)) + shrinkVertically(tween(Motion.SHORT)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedCheck(true, size = 28.dp, animateIn = !doneOnEnter)
            Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}


/** One numbered part of a page: text, optional mini settings animation, a button. */
@Composable
private fun GuideBlock(
    title: String,
    text: String,
    guide: SettingsGuide? = null,
    button: String? = null,
    primary: Boolean = true,
    onClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val animate = remember { animationsEnabled(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            guide?.let {
                SettingsPathAnimation(it, animate, Modifier.fillMaxWidth())
                it.note?.let { note -> Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            button?.let {
                if (primary) Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(it) }
                else OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(it) }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Icon(Icons.Filled.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
    PageTitle(
        "Welcome to Warden+",
        "Warden+ blocks distracting apps and websites, right on your phone. This setup takes about 3 minutes — anything optional can wait.",
    )
    Text(
        "Unofficial fork of “Warden” by Christopher Laughlin — not affiliated with the original project.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NotificationsPage(done: Boolean) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> denied = !granted }
    PageTitle("Notifications", "Warden+ shows that blocking is on and warns you if protection stops. Recommended.")
    DoneBadge(done)
    if (!done) {
        if (!denied) {
            Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) { Text("Allow notifications") }
        } else {
            // Android doesn't ask a second time: the switch is in the app's notification settings.
            Text("Android won't ask again. Switch them on here:", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open notification settings") }
        }
    }
}

@Composable
private fun AppBlockingPage(done: Boolean) {
    val context = LocalContext.current
    fun openAccessibility() { openSettingsGuide(context, SettingsGuide.ACCESSIBILITY) }
    PageTitle(
        "App blocking",
        "Warden+ uses Accessibility to notice when a blocked app opens. It never reads or sends what's on your screen.",
    )
    DoneBadge(done)
    if (done) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // Android 13+ greys the switch out for apps installed from an APK file until it is
        // "tried" once and then allowed in App info.
        Text("Android 13+ locks this for apps from an APK file. Three short steps:", style = MaterialTheme.typography.bodyMedium)
        GuideBlock(
            "1. Try once",
            "Open Accessibility and tap “Warden+ App Blocker” (Samsung: under “Installed apps”). Android says it's restricted — tap OK and come back.",
            button = "Open Accessibility",
            primary = false,
            onClick = ::openAccessibility,
        )
        GuideBlock(
            "2. Allow restricted settings",
            "In App info, tap ⋮ (top right) › “Allow restricted settings” and confirm.",
            SettingsGuide.RESTRICTED,
            "Open app info",
            primary = false,
        ) { openSettingsGuide(context, SettingsGuide.RESTRICTED) }
    }
    GuideBlock(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "3. Turn it on" else "Turn it on",
        "Accessibility › Installed apps › Warden+ App Blocker › On, then Allow.",
        SettingsGuide.ACCESSIBILITY,
        "Open Accessibility",
        onClick = ::openAccessibility,
    )
}

@Composable
private fun WebsiteBlockingPage(done: Boolean) {
    val context = LocalContext.current
    var refused by rememberSaveable { mutableStateOf(false) }
    val otherVpn = remember(done, refused) { VpnController.otherVpnActive(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        refused = result.resultCode != Activity.RESULT_OK
    }
    PageTitle(
        "Website blocking",
        "Websites are filtered by a VPN that stays on your phone — nothing is sent to a server. Android asks once: tap OK.",
    )
    DoneBadge(done)
    if (!done) {
        GuideBlock("Allow the VPN", "Tap the button, then OK in Android's dialog.", SettingsGuide.VPN_CONSENT, "Allow VPN") {
            val consent = VpnController.consentIntent(context)
            if (consent != null) runCatching { launcher.launch(consent) }.onFailure { refused = true }
        }
    }
    if (refused || otherVpn) {
        GuideBlock(
            "Another VPN is on?",
            "Android allows one VPN at a time. Turn the other one (e.g. Tailscale) off — and its “Always-on VPN” — then try again.",
            SettingsGuide.OTHER_VPN,
            "Open VPN settings",
            primary = false,
        ) { openSettingsGuide(context, SettingsGuide.OTHER_VPN) }
    }
    if (done) {
        GuideBlock(
            "Recommended: Always-on VPN",
            "Once blocking is on: VPN settings › ⚙ next to Warden+ › “Always-on VPN”. Android then restarts the filter if it ever stops.",
            button = "Open VPN settings",
            primary = false,
        ) { openSettingsGuide(context, SettingsGuide.OTHER_VPN) }
    }
    // VPN bypass protection, in short: the details live on the Protection page.
    InfoNote(
        "Another VPN app (a free VPN, Tailscale …) would push this filter out. Protection › VPN bypass " +
            "protection blocks VPN apps, warns at once if one takes over, and has Browser mode for a VPN you trust.",
    )
}

@Composable
private fun UsagePage(done: Boolean) {
    val context = LocalContext.current
    PageTitle("Usage access", "Optional. Needed for screen-time stats and daily time limits. Find Warden+ in the list and switch it on.")
    DoneBadge(done)
    if (!done) {
        Button(
            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open usage access") }
    }
}

@Composable
private fun BatteryPage(done: Boolean) {
    val context = LocalContext.current
    PageTitle("Keep Warden+ awake", "Recommended. Phones put apps to sleep to save battery — then blocking can stop.")
    DoneBadge(done, "Allowed to run in the background")
    if (!done) {
        Button(
            onClick = {
                runCatching { context.startActivity(Hardening.batteryExemptionIntent(context)) }
                    .onFailure { openSettingsGuide(context, SettingsGuide.BATTERY) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Allow background use") }
    }
    GuideBlock(
        "Samsung: Never sleeping apps",
        "Samsung has its own sleep list on top. Add Warden+ to “Never sleeping apps”.",
        SettingsGuide.BATTERY,
        "Open battery settings",
        primary = false,
    ) { openSettingsGuide(context, SettingsGuide.BATTERY) }
}

/**
 * The real Block protection (Off / PIN / Delay / Locked until) lives on its own screen, with the
 * checklist; the wizard shows where it stands and opens it. Back from there returns here.
 */
@Composable
private fun ProtectionPage(vm: WardenViewModel, done: Boolean, onChosen: () -> Unit, onOpenProtection: () -> Unit) {
    val context = LocalContext.current
    val protection by vm.protection.collectAsStateWithLifecycle()
    PageTitle("Protection", "How hard should it be to loosen blocking on impulse? Adding or tightening always works right away.")
    Text(
        protectionSummary(protection, TrustedClock.now(), vm::whenText),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        "Off, a PIN, a waiting time (Delay) or Locked until a date you pick.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onOpenProtection, modifier = Modifier.fillMaxWidth()) { Text("Choose block protection") }
    if (!done) TextButton(onClick = onChosen) { Text("Keep it off") }

    val autoTime = rememberAutoTimeOn()
    GuideBlock(
        "Automatic date & time",
        (if (autoTime) "On. " else "Off — please turn it on. ") +
            "Changing the date or time doesn't end blocks: Warden+ keeps them running on the real time. " +
            "With automatic date & time on, the phone's clock stays right too.",
        button = if (autoTime) null else "Date & time settings",
        primary = false,
    ) { openDateSettings(context) }

    var adminChecks by remember { mutableIntStateOf(0) }
    val adminActive = remember(adminChecks) { AdminManager.isActive(context) }
    val isOwner = remember(adminChecks) { DeviceOwnerManager.isDeviceOwner(context) }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { adminChecks++ }
    if (!isOwner) {
        GuideBlock(
            "Uninstall protection",
            if (adminActive) "On — Warden+ can't be uninstalled and “Force stop” is greyed out. Turning it off is protected too."
            else "Makes Warden+ a device admin, so it can't be uninstalled on impulse and “Force stop” is greyed out. " +
                "It can't read your data or lock your phone. Android then asks to “Activate this device admin app”.",
            button = if (adminActive) null else "Turn on",
            primary = false,
        ) { runCatching { adminLauncher.launch(AdminManager.enableIntent(context)) } }
    }
    GuideBlock(
        "Maximum protection (expert)",
        if (isOwner) "On — Warden+ is device owner. Its rules and the emergency exit are in Settings › Protection › Maximum protection."
        else "Needs a computer once. Can switch blocked apps off and lock app controls, VPN and date & time — " +
            "with a 24-hour emergency exit. Later in Settings › Protection › Maximum protection.",
        primary = false,
    )
}

@Composable
private fun FirstBlocksPage(vm: WardenViewModel, onOpenAppPicker: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val hostsVm: HostsViewModel = viewModel()
    val sources by hostsVm.sources.collectAsStateWithLifecycle()
    val progress by hostsVm.progress.collectAsStateWithLifecycle()
    val template = HostsTemplates.ALL.first()
    val listAdded = sources.any { it.url == template.url }
    val apps = items.count { it.type == BlockType.APP }
    PageTitle("First blocks", "Optional — you can add more any time in Blocklist.")
    GuideBlock(
        "Apps",
        if (apps == 0) "Pick the apps that pull you in." else "$apps ${if (apps == 1) "app" else "apps"} blocked.",
        button = if (apps == 0) "Pick apps to block" else "Pick more apps",
        onClick = onOpenAppPicker,
    )
    GuideBlock(
        "Ads & malware list",
        when {
            listAdded -> "✓ “${template.name}” is on."
            progress != null -> "Downloading “${template.name}”…"
            else -> "${template.name}: ${template.description}. A download of a few MB."
        },
        button = if (listAdded || progress != null) null else "Add list",
        primary = false,
    ) { hostsVm.addTemplate(template) }
}

@Composable
private fun ColumnScope.DonePage(
    vm: WardenViewModel,
    sdk: Int,
    done: Set<WizardStep>,
    skipped: Set<WizardStep>,
    onOpen: (WizardStep) -> Unit,
    onEnableBlocking: () -> Unit,
    onTour: () -> Unit,
    onFinish: () -> Unit,
) {
    val enabled by vm.masterEnabled.collectAsStateWithLifecycle()
    val (count, total) = WizardLogic.progress(sdk, done)
    PageTitle(
        if (count == total) "You're all set" else "Almost there",
        "$count of $total done. Tap a line to change it — or come back any time: Settings › Setup wizard.",
    )
    Card(Modifier.fillMaxWidth()) {
        Column {
            WizardLogic.tasks(sdk).forEachIndexed { i, step ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val isDone = step in done
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(step) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The ticks are drawn one after another when the summary opens.
                    AnimatedCheck(isDone, animateIn = true, delayMillis = 120 + i * 90)
                    Text(step.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Text(
                        when {
                            isDone -> "Done"
                            step in skipped -> "Later"
                            else -> "Open"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isDone) MaterialTheme.colorScheme.primary else if (step.optional) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (!enabled) Button(onClick = onEnableBlocking, modifier = Modifier.fillMaxWidth()) { Text("Turn on blocking now") }
    OutlinedButton(onClick = onTour, modifier = Modifier.fillMaxWidth()) { Text("Take a 1-minute tour") }
    TextButton(onClick = onFinish, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Finish") }
}
