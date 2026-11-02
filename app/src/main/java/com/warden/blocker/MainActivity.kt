package com.warden.blocker

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.warden.blocker.ui.LocalTabReselected
import com.warden.blocker.ui.SchedulesScreen
import com.warden.blocker.ui.SettingsScreen
import com.warden.blocker.ui.SetupScreen
import com.warden.blocker.ui.WardenViewModel
import com.warden.blocker.ui.hosts.HostsListsScreen
import com.warden.blocker.ui.stats.StatsDashboard
import com.warden.blocker.ui.theme.WardenTheme
import com.warden.blocker.vpn.VpnController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    /** Set when opened with [EXTRA_ENABLE_BLOCKING]; Compose turns blocking on and clears it. */
    private var enableRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only for a fresh start: after a recreation the request was already handled.
        if (savedInstanceState == null) enableRequested = intent.getBooleanExtra(EXTRA_ENABLE_BLOCKING, false)
        setContent {
            WardenTheme {
                val vm: WardenViewModel = viewModel()

                val notifPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* result ignored; the app still works without it */ }
                LaunchedEffect(Unit) {
                    vm.onAppOpened()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                fun runAfterConsent(action: AfterConsent) {
                    VpnController.start(this@MainActivity)
                    if (action == AfterConsent.ENABLE_BLOCKING) vm.setMasterEnabled(true)
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
                    if (result.resultCode == Activity.RESULT_OK) runAfterConsent(action) else showConsentMissing()
                }

                fun withVpn(action: AfterConsent) {
                    val consent = VpnController.consentIntent(this@MainActivity)
                    if (consent == null) { runAfterConsent(action); return }
                    pendingAfterConsent = action
                    runCatching { consentLauncher.launch(consent) }.onFailure {
                        pendingAfterConsent = null
                        showConsentMissing()
                    }
                }

                fun toggleBlocking(enabled: Boolean) {
                    if (enabled) withVpn(AfterConsent.ENABLE_BLOCKING)
                    else { VpnController.stop(this@MainActivity); vm.setMasterEnabled(false) }
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

                fun cancelFocus() {
                    vm.cancelFocus()
                    if (!vm.masterEnabled.value) VpnController.stop(this@MainActivity)
                }

                WardenScaffold(vm, ::toggleBlocking, ::startFocus, ::cancelFocus)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ENABLE_BLOCKING, false)) enableRequested = true
    }

    /** The VPN dialog was cancelled or couldn't be shown — say why blocking stayed off. */
    private fun showConsentMissing() {
        // Short: Android cuts toasts off after two lines.
        Toast.makeText(
            this,
            "Blocking stays off: no VPN permission. Is another VPN app set to Always-on?",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** What to do once the user has allowed the VPN. */
    private enum class AfterConsent { ENABLE_BLOCKING, START_FOCUS }

    companion object {
        /** Intent extra: turn blocking on right away (asking for VPN consent if needed). */
        const val EXTRA_ENABLE_BLOCKING = "com.warden.blocker.ENABLE_BLOCKING"
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
) {
    val navController = rememberNavController()
    val reselected = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    Scaffold(
        bottomBar = {
            CompactNavigationBar {
                val current by navController.currentBackStackEntryAsState()
                val dest = current?.destination
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = dest?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            // Tapping the tab that is already open scrolls it back to the top.
                            if (dest?.route == tab.route) {
                                reselected.tryEmit(tab.route)
                                return@NavigationBarItem
                            }
                            // Leave a sub-screen (app picker, feeds, hosts lists, setup) first. Otherwise it is
                            // saved into the tab's state and restored, so tapping "Blocklist" from
                            // the app picker would land right back in the app picker.
                            if (dest != null && Tab.entries.none { it.route == dest.route }) navController.popBackStack()
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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
        },
    ) { padding ->
        // Pops only while [route] is still on the stack, so a double tap on back/"Done"
        // can't also pop the screen underneath.
        fun leave(route: String) { navController.popBackStack(route, inclusive = true) }

        NavHost(navController, startDestination = Tab.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Tab.HOME.route) {
                TabContent(Tab.HOME, reselected) {
                    HomeScreen(vm, onToggleBlocking, onStartFocus, onCancelFocus, onOpenSetup = { navController.navigate("setup") })
                }
            }
            composable("setup") { SetupScreen(onBack = { leave("setup") }) }
            composable(Tab.BLOCKLIST.route) {
                TabContent(Tab.BLOCKLIST, reselected) {
                    BlocklistScreen(
                        vm,
                        onOpenAppPicker = { navController.navigate("apppicker") },
                        onOpenFeatures = { navController.navigate("features") },
                        onOpenHostsLists = { navController.navigate("hostslists") },
                    )
                }
            }
            composable("apppicker") { AppPickerScreen(vm, onBack = { leave("apppicker") }) }
            composable("features") { FeaturesScreen(vm, onBack = { leave("features") }) }
            composable("hostslists") { HostsListsScreen(onBack = { leave("hostslists") }) }
            composable(Tab.SCHEDULES.route) { TabContent(Tab.SCHEDULES, reselected) { SchedulesScreen(vm) } }
            composable(Tab.STATS.route) { TabContent(Tab.STATS, reselected) { StatsDashboard() } }
            composable(Tab.SETTINGS.route) {
                TabContent(Tab.SETTINGS, reselected) { SettingsScreen(vm, onOpenSetup = { navController.navigate("setup") }) }
            }
        }
    }
}
