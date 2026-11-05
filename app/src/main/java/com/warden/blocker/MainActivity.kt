package com.warden.blocker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.warden.blocker.ui.AppPickerScreen
import com.warden.blocker.ui.BlocklistScreen
import com.warden.blocker.ui.FeaturesScreen
import com.warden.blocker.ui.HomeScreen
import com.warden.blocker.ui.LocalMotion
import com.warden.blocker.ui.Motion
import com.warden.blocker.ui.LocalTabReselected
import com.warden.blocker.ui.navEnter
import com.warden.blocker.ui.navExit
import com.warden.blocker.ui.ProtectionScreen
import com.warden.blocker.ui.VerifyPinDialog
import com.warden.blocker.system.ProtectionWatch
import com.warden.blocker.vpn.WardenVpnService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.ui.SchedulesScreen
import com.warden.blocker.ui.SettingsScreen
import com.warden.blocker.ui.WardenViewModel
import com.warden.blocker.ui.allow.AllowOnlyScreen
import com.warden.blocker.ui.allow.CategoriesScreen
import com.warden.blocker.ui.hosts.HostsListsScreen
import com.warden.blocker.ui.stats.StatsDashboard
import com.warden.blocker.ui.theme.WardenTheme
import com.warden.blocker.ui.tour.TourHost
import com.warden.blocker.ui.tour.TourPath
import com.warden.blocker.ui.tour.tourTarget
import com.warden.blocker.ui.wizard.AutoOpenSetupWizard
import com.warden.blocker.ui.wizard.SetupWizardScreen
import com.warden.blocker.vpn.VpnController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    /** Set by a [requestIntent] asking to enable blocking; Compose does it and clears it. */
    private var enableRequested by mutableStateOf(false)

    /** Set by a [requestIntent] asking for Setup; the scaffold navigates there and clears it. */
    private var openSetupRequested by mutableStateOf(false)

    /** Set by a [requestIntent] from "Another VPN took over": take the VPN slot back (consent dialog). */
    private var restoreRequested by mutableStateOf(false)

    /** Id of the last [requestIntent] acted on, saved so a recreation doesn't repeat it. */
    private var handledRequest = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledRequest = savedInstanceState?.getLong(STATE_HANDLED_REQUEST) ?: 0L
        // Also when restored from saved state: after an update the old screen is restored but
        // carries the new notification's intent.
        handleRequest(intent)
        setContent {
            WardenTheme {
                val vm: WardenViewModel = viewModel()

                // Notifications are asked for in the setup wizard (and its Home card), not at once.
                LaunchedEffect(Unit) {
                    vm.onAppOpened()
                    // After "Force stop" or a battery saver killed it: bring the filter back on its
                    // own (it then reports the gap), instead of waiting for "Tap to restart".
                    val c = wardenContainer
                    if (!WardenVpnService.running && VpnController.consentIntent(this@MainActivity) == null &&
                        c.blockEngine.filterShouldRun()
                    ) {
                        runCatching { VpnController.start(this@MainActivity) }
                    }
                    runCatching { ProtectionWatch.checkAccessibility(this@MainActivity) }
                }

                fun runAfterConsent(action: AfterConsent) {
                    VpnController.start(this@MainActivity)
                    if (action == AfterConsent.ENABLE_BLOCKING) vm.setMasterEnabled(true)
                }

                // Android refused the VPN — with another VPN app on "Always-on" (e.g. Tailscale)
                // it closes the dialog at once, so nothing at all seemed to happen. Turn on what
                // doesn't need the VPN (app blocking via accessibility) and let Home explain how
                // to get website blocking back.
                fun onVpnRefused(action: AfterConsent) {
                    VpnController.reportRefused()
                    if (action == AfterConsent.ENABLE_BLOCKING) vm.setMasterEnabled(true)
                    showVpnRefused()
                }

                // What waits for the system VPN dialog. Saved (it used to be a lambda in plain
                // `remember`), so it survives the activity being recreated while the dialog is
                // open — folding/unfolding, rotating or a theme switch did that, and tapping
                // "OK" then silently did nothing: blocking stayed off.
                var pendingAfterConsent by rememberSaveable { mutableStateOf<AfterConsent?>(null) }
                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val action = pendingAfterConsent
                    pendingAfterConsent = null
                    if (action == null) return@rememberLauncherForActivityResult
                    if (result.resultCode == Activity.RESULT_OK) runAfterConsent(action) else onVpnRefused(action)
                }

                fun withVpn(action: AfterConsent) {
                    val consent = VpnController.consentIntent(this@MainActivity)
                    if (consent == null) { runAfterConsent(action); return }
                    pendingAfterConsent = action
                    runCatching { consentLauncher.launch(consent) }.onFailure {
                        pendingAfterConsent = null
                        onVpnRefused(action)
                    }
                }

                fun toggleBlocking(enabled: Boolean) {
                    if (enabled) withVpn(AfterConsent.ENABLE_BLOCKING)
                    else vm.turnOffBlocking() // Block protection may ask for the PIN, wait, or refuse
                }

                fun startFocus(minutes: Int, strict: Boolean) {
                    vm.startFocus(minutes, strict)
                    withVpn(AfterConsent.START_FOCUS)
                }

                // The Quick Settings tile sends people here when the VPN still needs consent.
                LaunchedEffect(enableRequested) {
                    if (enableRequested) {
                        enableRequested = false
                        toggleBlocking(true)
                    }
                }

                // Retry from Home: same consent flow; with the main switch off only the focus session needs it.
                fun retryVpn() = withVpn(if (vm.masterEnabled.value) AfterConsent.ENABLE_BLOCKING else AfterConsent.START_FOCUS)

                // "Restore" in the VPN-takeover notification: like Try again on Home.
                LaunchedEffect(restoreRequested) {
                    if (restoreRequested) {
                        restoreRequested = false
                        retryVpn()
                    }
                }

                // Stops the VPN too when nothing else needs it (see ProtectionGuard.apply).
                fun cancelFocus() { vm.cancelFocus() }

                WardenScaffold(
                    vm, ::toggleBlocking, ::startFocus, ::cancelFocus, ::retryVpn,
                    openSetup = openSetupRequested,
                    onSetupOpened = { openSetupRequested = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRequest(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_HANDLED_REQUEST, handledRequest)
    }

    private fun handleRequest(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_REQUEST_ID, 0L) ?: 0L
        if (id == 0L || id == handledRequest) return
        handledRequest = id
        if (intent?.getBooleanExtra(EXTRA_ENABLE_BLOCKING, false) == true) enableRequested = true
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETUP, false) == true) openSetupRequested = true
        if (intent?.getBooleanExtra(EXTRA_RESTORE_FILTER, false) == true) restoreRequested = true
    }

    /** The VPN dialog was cancelled or couldn't be shown; Home has the details. */
    private fun showVpnRefused() {
        // Short: Android cuts toasts off after two lines.
        Toast.makeText(
            this,
            "Website blocking couldn't start (another VPN app?). Details on Home.",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** What to do once the user has allowed the VPN. */
    private enum class AfterConsent { ENABLE_BLOCKING, START_FOCUS }

    companion object {
        /** Intent extra: turn blocking on right away (asking for VPN consent if needed). */
        private const val EXTRA_ENABLE_BLOCKING = "com.warden.blocker.ENABLE_BLOCKING"

        /** Intent extra: open the Setup screen (e.g. accessibility was switched off by an update). */
        private const val EXTRA_OPEN_SETUP = "com.warden.blocker.OPEN_SETUP"

        /** Intent extra: ask for the VPN slot back after another VPN took over. */
        private const val EXTRA_RESTORE_FILTER = "com.warden.blocker.RESTORE_FILTER"
        private const val EXTRA_REQUEST_ID = "com.warden.blocker.REQUEST_ID"
        private const val STATE_HANDLED_REQUEST = "handled_request"

        /**
         * Opens Warden from outside (tile, notification) and asks it to turn blocking on and/or
         * show Setup. An already open Warden gets it via onNewIntent; each request runs once.
         */
        fun requestIntent(
            context: Context,
            enableBlocking: Boolean = false,
            openSetup: Boolean = false,
            restoreFilter: Boolean = false,
        ): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ENABLE_BLOCKING, enableBlocking)
                .putExtra(EXTRA_OPEN_SETUP, openSetup)
                .putExtra(EXTRA_RESTORE_FILTER, restoreFilter)
                .putExtra(EXTRA_REQUEST_ID, System.currentTimeMillis())
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    BLOCKLIST("blocklist", "Blocklist", Icons.Filled.Block),
    SCHEDULES("schedules", "Schedules", Icons.Filled.Schedule),
    STATS("stats", "Usage", Icons.Filled.Timeline),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

/**
 * Same look as Material 3's NavigationBar, but without its fixed 8dp gaps between items. With
 * five tabs those gaps leave each item narrower than the 64dp selection pill below ~360dp, so
 * the pill of the last tab stuck out past the screen edge on 320–344dp fold cover displays.
 */
@Composable
private fun CompactNavigationBar(content: @Composable RowScope.() -> Unit) {
    Surface(color = NavigationBarDefaults.containerColor, tonalElevation = NavigationBarDefaults.Elevation) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets)
                // Keeps the outer tabs' pills clear of the (often rounded) screen edge.
                .padding(horizontal = 8.dp)
                .defaultMinSize(minHeight = 80.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Hands [tab]'s screen the "tapped again" events, see [ScrollToTopOnReselect]. */
@Composable
private fun TabContent(tab: Tab, reselected: SharedFlow<String>, content: @Composable () -> Unit) {
    val events = remember(tab, reselected) { reselected.filter { it == tab.route }.map { } }
    CompositionLocalProvider(LocalTabReselected provides events, content = content)
}

@Composable
private fun WardenScaffold(
    vm: WardenViewModel,
    onToggleBlocking: (Boolean) -> Unit,
    onStartFocus: (Int, Boolean) -> Unit,
    onCancelFocus: () -> Unit,
    onRetryVpn: () -> Unit,
    openSetup: Boolean,
    onSetupOpened: () -> Unit,
) {
    val navController = rememberNavController()
    val reselected = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    LaunchedEffect(openSetup) {
        if (openSetup) {
            navController.currentBackStackEntryFlow.first() // wait until the NavHost has its graph
            onSetupOpened()
            if (navController.currentDestination?.route != "setup") navController.navigate("setup")
        }
    }
    // Block protection in PIN mode: any screen's weakening lands here and waits for the PIN.
    val pinRequest by vm.pinRequest.collectAsStateWithLifecycle()
    pinRequest?.let { change ->
        VerifyPinDialog(
            title = "Enter PIN: ${change.describe().replaceFirstChar { it.lowercase() }}",
            verify = vm::verifyPin,
            onSuccess = { vm.confirmPinRequest() },
            onDismiss = { vm.cancelPinRequest() },
        )
    }
    // First start after installing: straight into the setup wizard.
    AutoOpenSetupWizard {
        navController.currentBackStackEntryFlow.first()
        if (navController.currentDestination?.route != "setup") navController.navigate("setup")
    }
    fun openTab(route: String) {
        val dest = navController.currentDestination
        // Tapping the tab that is already open scrolls it back to the top.
        if (dest?.route == route) {
            reselected.tryEmit(route)
            return
        }
        // Leave a sub-screen (app picker, feeds, hosts lists, setup) first. Otherwise it is
        // saved into the tab's state and restored, so tapping "Blocklist" from
        // the app picker would land right back in the app picker.
        if (dest != null && Tab.entries.none { it.route == dest.route }) navController.popBackStack()
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    // The guides drive the app like a user: tabs like a tab tap, sub-screens like their button.
    TourHost(
        currentRoute = currentEntry?.destination?.route,
        navigate = { route ->
            if (Tab.entries.any { it.route == route }) openTab(route)
            else if (navController.currentDestination?.route != route) navController.navigate(route)
        },
    ) {
    Scaffold(
        bottomBar = {
            // The setup wizard is full-page, with its own Back / Next at the bottom: the bar
            // slides away for it and comes back after.
            AnimatedVisibility(
                visible = currentEntry?.destination?.route != "setup",
                enter = expandVertically(tween(Motion.MEDIUM), expandFrom = Alignment.Top),
                exit = shrinkVertically(tween(Motion.MEDIUM), shrinkTowards = Alignment.Top),
            ) {
            CompactNavigationBar {
                val dest = currentEntry?.destination
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        modifier = Modifier.tourTarget(TourPath.tabTarget(tab.route)),
                        selected = dest?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = { openTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        // One line, never wrapped ("Schedules" broke onto two lines at 344dp);
                        // ellipsis is only a fallback for very large font scales.
                        label = {
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            }
        },
    ) { padding ->
        // Pops only while [route] is still on the stack, so a double tap on back/"Done"
        // can't also pop the screen underneath.
        fun leave(route: String) { navController.popBackStack(route, inclusive = true) }

        // Tabs fade into each other, sub-screens slide in and back out (none with "Remove animations").
        val tabRoutes = remember { Tab.entries.map { it.route }.toSet() }
        val motion = LocalMotion.current
        NavHost(
            navController,
            startDestination = Tab.HOME.route,
            modifier = Modifier.padding(padding),
            enterTransition = { navEnter(tabRoutes, motion, popping = false) },
            exitTransition = { navExit(tabRoutes, motion, popping = false) },
            popEnterTransition = { navEnter(tabRoutes, motion, popping = true) },
            popExitTransition = { navExit(tabRoutes, motion, popping = true) },
        ) {
            composable(Tab.HOME.route) {
                TabContent(Tab.HOME, reselected) {
                    HomeScreen(vm, onToggleBlocking, onStartFocus, onCancelFocus, onOpenSetup = { navController.navigate("setup") }, onRetryVpn = onRetryVpn)
                }
            }
            composable("setup") {
                SetupWizardScreen(
                    vm,
                    onClose = { leave("setup") },
                    onOpenAppPicker = { navController.navigate("apppicker") },
                    onEnableBlocking = { onToggleBlocking(true) },
                    onOpenProtection = { navController.navigate("protection") },
                )
            }
            composable("protection") {
                // From the wizard (setup → protection) "Open setup" goes back to it instead of stacking a second one.
                ProtectionScreen(vm, onBack = { leave("protection") }, onOpenSetup = {
                    if (!navController.popBackStack("setup", inclusive = false)) navController.navigate("setup")
                })
            }
            composable(Tab.BLOCKLIST.route) {
                TabContent(Tab.BLOCKLIST, reselected) {
                    BlocklistScreen(
                        vm,
                        onOpenAppPicker = { navController.navigate("apppicker") },
                        onOpenFeatures = { navController.navigate("features") },
                        onOpenHostsLists = { navController.navigate("hostslists") },
                        onOpenAllowOnly = { navController.navigate("allowonly") },
                        onOpenCategories = { navController.navigate("categories") },
                    )
                }
            }
            composable("apppicker") { AppPickerScreen(vm, onBack = { leave("apppicker") }) }
            composable("features") { FeaturesScreen(vm, onBack = { leave("features") }) }
            composable("hostslists") { HostsListsScreen(onBack = { leave("hostslists") }) }
            composable("allowonly") { AllowOnlyScreen(onBack = { leave("allowonly") }) }
            composable("categories") { CategoriesScreen(onBack = { leave("categories") }) }
            composable(Tab.SCHEDULES.route) { TabContent(Tab.SCHEDULES, reselected) { SchedulesScreen(vm) } }
            composable(Tab.STATS.route) { TabContent(Tab.STATS, reselected) { StatsDashboard() } }
            composable(Tab.SETTINGS.route) {
                TabContent(Tab.SETTINGS, reselected) {
                    SettingsScreen(
                        vm,
                        onOpenSetup = { navController.navigate("setup") },
                        onOpenProtection = { navController.navigate("protection") },
                    )
                }
            }
        }
    }
    }
}
