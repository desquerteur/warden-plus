package com.warden.blocker.ui.vpnguard

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.allow.AppCategory
import com.warden.blocker.allow.VpnApps
import com.warden.blocker.owner.OwnerPolicy
import com.warden.blocker.owner.title
import com.warden.blocker.system.PrivateDns
import com.warden.blocker.ui.CardPadding
import com.warden.blocker.ui.InfoNote
import com.warden.blocker.ui.Motion
import com.warden.blocker.ui.StatusIcon
import com.warden.blocker.ui.SupportingText
import com.warden.blocker.ui.offCountSince
import com.warden.blocker.ui.owner.OwnerViewModel
import com.warden.blocker.ui.owner.explanation

/** Owner rules that belong to VPN bypass protection (shown here and under Maximum protection). */
private val OWNER_RULES = listOf(
    OwnerPolicy.SUSPEND_VPN_APPS, OwnerPolicy.BLOCK_VPN_SETTINGS, OwnerPolicy.ALWAYS_ON_VPN, OwnerPolicy.LOCK_PRIVATE_DNS,
)

/**
 * "VPN bypass protection": Android allows one VPN at a time, so switching on another VPN (a free
 * VPN, Tailscale …) pushes Warden's website filter out. One card with what can be done about it,
 * and what can't without device owner.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VpnGuardSection(modifier: Modifier = Modifier, vm: VpnGuardViewModel = viewModel(), owner: OwnerViewModel = viewModel()) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val config by vm.config.collectAsStateWithLifecycle()
    val list by vm.bypassList.collectAsStateWithLifecycle()
    val browserMode by vm.browserMode.collectAsStateWithLifecycle()
    val events by vm.switchEvents.collectAsStateWithLifecycle()
    val vpnApps by vm.vpnApps.collectAsStateWithLifecycle()
    val privateDns by vm.privateDns.collectAsStateWithLifecycle()
    val downloading by vm.downloading.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val isOwner by owner.isOwner.collectAsStateWithLifecycle()
    val policies by owner.policies.collectAsStateWithLifecycle()

    val vpnBlocked = AppCategory.VPN in config.blockedCategories
    val trusted = VpnApps.trusted(vpnApps.keys, config)
    val switches = offCountSince(events, System.currentTimeMillis())

    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM)).padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("VPN bypass protection", style = MaterialTheme.typography.titleMedium)
            SupportingText(
                "Android allows one VPN at a time. Turning on another VPN app pushes Warden's website filter out — " +
                    "these make that harder.",
            )

            GuardSwitch(
                "Block VPN apps",
                when {
                    !vpnBlocked -> "Off. ${vpnApps.size} VPN app(s) installed — any of them can push the filter out."
                    vpnApps.isEmpty() -> "No VPN app installed. New ones are blocked as soon as they're installed."
                    else -> "${vpnApps.size - trusted.size} of ${vpnApps.size} installed VPN apps blocked. New ones are blocked too."
                },
                vpnBlocked, vm::setVpnAppsBlocked,
            )
            // Trusted VPN (A): a VPN the user needs, e.g. Tailscale — allowed, Browser mode covers browsers.
            vpnApps.forEach { (pkg, label) ->
                val isTrusted = pkg in trusted
                Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        SupportingText(
                            when {
                                isTrusted -> "Trusted VPN — may run"
                                pkg == VpnApps.TAILSCALE -> "Tailscale found. Need it? Trust it."
                                vpnBlocked -> "Blocked"
                                else -> "Not blocked"
                            },
                        )
                    }
                    TextButton(onClick = { vm.setTrusted(pkg, !isTrusted) }) { Text(if (isTrusted) "Block again" else "Trust") }
                }
            }

            GuardSwitch(
                "Block VPN & proxy sites",
                when {
                    downloading -> "Downloading the list…"
                    list != null -> "${list!!.domainCount} VPN, proxy & encrypted-DNS sites (list by hagezi, GPL-3.0). " +
                        "Warden's own updates stay reachable."
                    else -> "Downloads hagezi's bypass list (~17k sites, GPL-3.0) as a hosts list."
                },
                list?.enabled == true, vm::setBypassList,
            )
            // Right under the switch it belongs to — at the bottom of this long card nobody saw it.
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                FlowRow {
                    TextButton(onClick = { vm.clearMessage(); vm.setBypassList(true) }) { Text("Try again") }
                    TextButton(onClick = vm::clearMessage) { Text("OK") }
                }
            }

            GuardSwitch(
                "Browser mode",
                "While another VPN (e.g. a trusted Tailscale) holds the VPN slot, blocked websites are blocked in " +
                    "the address bar of Chrome, Samsung Internet, Firefox, Edge, Brave, Opera & co. Needs accessibility.",
                browserMode, vm::setBrowserMode,
            )

            Row(verticalAlignment = Alignment.Top) {
                StatusIcon(privateDns == null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (privateDns == null) "Private DNS: fine" else "Private DNS goes around the filter",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (privateDns == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                    SupportingText(
                        if (privateDns == null) "Not set to a provider, so DNS goes through Warden's filter."
                        else "It's set to $privateDns. Set it to Automatic: Network & internet → Private DNS.",
                    )
                    if (privateDns != null) {
                        OutlinedButton(onClick = { runCatching { context.startActivity(PrivateDns.settingsIntent()) } }) { Text("Open settings") }
                    }
                }
            }

            Text(
                if (switches == 0) "No VPN switches in the last 7 days. If another VPN takes over, you get an alert with Restore."
                else "VPN switched $switches× in the last 7 days.",
                style = MaterialTheme.typography.bodyMedium,
            )

            HorizontalDivider()
            if (isOwner) {
                Text("Device owner rules", style = MaterialTheme.typography.titleSmall)
                OWNER_RULES.forEach { policy ->
                    GuardSwitch(policy.title, policy.explanation, policy in policies) { owner.setPolicy(policy, it) }
                }
            } else {
                InfoNote(
                    "Honest limits without device owner: Warden+ can block VPN apps when they're opened and warns at " +
                        "once when another VPN takes over — but it can't stop a VPN from being switched on in Android's " +
                        "settings or a quick-settings tile, and Android always lets the newest VPN win. With Maximum " +
                        "protection (device owner, below) Android itself switches VPN apps off and locks VPN and " +
                        "Private DNS settings.",
                )
            }
        }
    }
}

@Composable
private fun GuardSwitch(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            SupportingText(text)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
