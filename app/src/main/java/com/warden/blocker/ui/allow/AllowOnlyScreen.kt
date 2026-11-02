package com.warden.blocker.ui.allow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.allow.AllowScope
import com.warden.blocker.allow.ListMode
import com.warden.blocker.allow.SystemDomains
import com.warden.blocker.ui.CardSpacing
import com.warden.blocker.ui.IntroText
import com.warden.blocker.ui.SectionCard
import com.warden.blocker.ui.SubScreenTopBar
import com.warden.blocker.ui.pageGutter

/**
 * "Block everything except …": the mode for apps and websites, when it applies, the allowed
 * websites and (as an inner page) the allowed apps.
 */
@Composable
fun AllowOnlyScreen(onBack: () -> Unit, vm: AllowViewModel = viewModel()) {
    var pickingApps by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = pickingApps) { pickingApps = false }
    if (pickingApps) {
        AllowedAppsScreen(vm, onBack = { pickingApps = false })
        return
    }

    val config by vm.config.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var inputInvalid by rememberSaveable { mutableStateOf(false) }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    fun submit() {
        if (vm.addDomain(input)) {
            input = ""
            focusManager.clearFocus()
        } else {
            inputInvalid = input.isNotBlank()
        }
    }

    val gutter = pageGutter()
    Column(Modifier.fillMaxSize()) {
        SubScreenTopBar("Allow only", onBack = onBack, showDone = true)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = gutter),
            verticalArrangement = Arrangement.spacedBy(CardSpacing),
        ) {
            item {
                IntroText(
                    "Block everything except what you choose. Phone, emergency calls, Settings, your home screen, " +
                        "the keyboard, the clock and Warden+ always stay open.",
                )
            }

            item {
                Section("Apps") {
                    ModeChoice(
                        selected = config.appMode,
                        blockListText = "Block only the apps on your blocklist",
                        allowOnlyText = "Block every app except the allowed ones",
                        onSelect = vm::setAppMode,
                    )
                    OutlinedButton(onClick = { pickingApps = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose allowed apps (${config.allowedApps.size})")
                    }
                    if (config.appMode == ListMode.ALLOW_ONLY && config.allowedApps.isEmpty()) {
                        Hint("No apps allowed yet — only the always-allowed ones will open.")
                    }
                }
            }

            item {
                Section("Websites") {
                    ModeChoice(
                        selected = config.webMode,
                        blockListText = "Block only the websites on your blocklist",
                        allowOnlyText = "Block every website except the allowed ones",
                        onSelect = vm::setWebMode,
                    )
                    AnimatedVisibility(config.webMode == ListMode.ALLOW_ONLY) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(
                                "Everything online that isn't allowed here is blocked — in the browser and in apps. " +
                                    "Apps whose servers aren't on the list will look offline (e.g. a banking app needs " +
                                    "its bank's website). Needs website blocking (the VPN) switched on.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it; inputInvalid = false },
                            label = { Text("Allow a website, e.g. wikipedia.org") },
                            singleLine = true,
                            isError = inputInvalid,
                            supportingText = if (inputInvalid) {
                                { Text("Enter a website name like example.com") }
                            } else null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { submit() }, modifier = Modifier.padding(start = 8.dp)) { Text("Add") }
                    }
                    Hint("Subdomains count too: wikipedia.org also allows de.wikipedia.org.")
                }
            }

            items(config.allowedDomains.sorted(), key = { "domain:$it" }) { domain ->
                Card(Modifier.fillMaxWidth().animateItem()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(domain, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        IconButton(onClick = { vm.removeDomain(domain) }) { Icon(Icons.Outlined.Delete, contentDescription = "Remove $domain", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }

            item {
                Section("Always reachable") {
                    Hint("So the phone keeps working, these system addresses are never blocked: internet check, push messages, clock time, Google/Samsung sign-in and Warden+ updates.")
                    TextButton(onClick = { showSystem = !showSystem }) {
                        Text(if (showSystem) "Hide the list" else "Show the list (${SystemDomains.ALL.size})")
                    }
                    AnimatedVisibility(showSystem) {
                        Text(SystemDomains.ALL.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                Section("When") {
                    Column(Modifier.selectableGroup()) {
                        AllowScope.entries.forEach { scope ->
                            RadioRow(scope.label, selected = config.scope == scope) { vm.setScope(scope) }
                        }
                    }
                    Hint(
                        "Outside these times your normal blocklist applies. Example “exam time”: a schedule " +
                            "Mon–Fri 8–12 with only Phone, Maps and wikipedia.org allowed.",
                    )
                }
            }

            item {
                Section("Also keep open") {
                    SwitchRow("Camera", "The built-in camera app", config.allowCamera, vm::setAllowCamera)
                    SwitchRow("Messages (SMS)", "Your default SMS app — for login codes", config.allowSms, vm::setAllowSms)
                }
            }
        }
    }
}

@Composable
private fun ModeChoice(selected: ListMode, blockListText: String, allowOnlyText: String, onSelect: (ListMode) -> Unit) {
    Column(Modifier.selectableGroup()) {
        RadioRow("Block list — $blockListText", selected == ListMode.BLOCK_LIST) { onSelect(ListMode.BLOCK_LIST) }
        RadioRow("Allow only — $allowOnlyText", selected == ListMode.ALLOW_ONLY) { onSelect(ListMode.ALLOW_ONLY) }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionCard(title, content = content)
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
