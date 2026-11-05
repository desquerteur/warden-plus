package com.warden.blocker.ui.allow

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.allow.AppCategories
import com.warden.blocker.allow.AppCategory
import com.warden.blocker.allow.CategorizedApp
import com.warden.blocker.ui.AppIcon
import com.warden.blocker.ui.CardPadding
import com.warden.blocker.ui.IntroText
import com.warden.blocker.ui.SubScreenTopBar
import com.warden.blocker.ui.pageGutter

/**
 * Block whole categories (all games, all social apps …) as apps declare them, with a manual
 * correction per app. New installs of a blocked category are blocked automatically.
 */
@Composable
fun CategoriesScreen(onBack: () -> Unit, vm: AllowViewModel = viewModel()) {
    val config by vm.config.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val alwaysAllowed by vm.alwaysAllowed.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    // Apps installed while this screen was in the background show up when coming back.
    LaunchedEffect(Unit) { vm.refreshApps() }

    val list = apps.orEmpty().filter { it.packageName !in alwaysAllowed }
    fun effective(app: CategorizedApp) = AppCategories.effective(app.packageName, app.detected, config.categoryOverrides)

    val gutter = pageGutter()
    Column(Modifier.fillMaxSize()) {
        SubScreenTopBar("Block categories", onBack = onBack, showDone = true)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, bottom = gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                IntroText(
                    "Block every app of a kind — also ones you install later. Apps say themselves what they are; " +
                        "if one is wrong, correct it below. Your own blocklist settings for an app (e.g. a pause) win.",
                )
            }
            items(AppCategory.entries, key = { it.name }) { category ->
                val members = list.filter { effective(it) == category }
                val excluded = list.filter { it.detected == category && config.categoryOverrides[it.packageName] == AppCategories.NONE }
                val open = category.name in expanded
                // VPN apps: taking one out of the category is "Trust" (Trusted VPN), see VpnApps.trusted.
                val vpn = category == AppCategory.VPN
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(CardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (vpn) "Block VPN apps" else "Block all ${category.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
                                Hint(if (apps == null) "Looking for apps…" else "${members.size} installed")
                                if (vpn) {
                                    Hint(
                                        "Another VPN pushes Warden's website filter out. Need one (e.g. Tailscale)? " +
                                            "Tap Trust — Browser mode then still blocks websites in browsers.",
                                    )
                                }
                            }
                            Switch(
                                checked = category in config.blockedCategories,
                                onCheckedChange = { vm.setCategoryBlocked(category, it) },
                            )
                        }
                        TextButton(onClick = { expanded = if (open) expanded - category.name else expanded + category.name }) {
                            Text(if (open) "Hide apps" else "Show apps")
                        }
                        // The apps fold open and closed.
                        AnimatedVisibility(open) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (members.isEmpty()) Hint("None found.")
                                members.forEach { app ->
                                    val byHand = config.categoryOverrides[app.packageName] == category.name
                                    CategoryAppRow(
                                        app,
                                        status = if (byHand) "Added by you" else "Detected",
                                        action = when {
                                            byHand -> "Remove"
                                            vpn -> "Trust"
                                            else -> "Not a ${category.singular}"
                                        },
                                        onAction = { vm.setOverride(app.packageName, if (byHand) null else AppCategories.NONE) },
                                    )
                                }
                                excluded.forEach { app ->
                                    CategoryAppRow(
                                        app,
                                        status = if (vpn) "Trusted VPN — not blocked" else "You said: not a ${category.singular}",
                                        action = if (vpn) "Block again" else "Count again",
                                        onAction = { vm.setOverride(app.packageName, null) },
                                    )
                                }
                                TextButton(onClick = { addingTo = category.name }) { Text("Not detected? Add an app") }
                            }
                        }
                    }
                }
            }
        }
    }

    addingTo?.let { name ->
        val category = AppCategories.parse(name) ?: return@let
        AddToCategoryDialog(
            category = category,
            candidates = list.filter { effective(it) != category },
            onPick = { vm.setOverride(it.packageName, category.name); addingTo = null },
            onDismiss = { addingTo = null },
        )
    }
}

@Composable
private fun CategoryAppRow(app: CategorizedApp, status: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.packageName, modifier = Modifier.size(36.dp).padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            Hint(status)
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun AddToCategoryDialog(
    category: AppCategory,
    candidates: List<CategorizedApp>,
    onPick: (CategorizedApp) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Treat as ${category.singular}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search apps") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates.filter { it.label.contains(query, ignoreCase = true) }, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(app) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName, modifier = Modifier.size(32.dp).padding(end = 10.dp))
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
