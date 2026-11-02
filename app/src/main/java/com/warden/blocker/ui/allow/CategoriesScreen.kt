package com.warden.blocker.ui.allow

import androidx.compose.foundation.clickable
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
import com.warden.blocker.ui.SubScreenTopBar

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

    Column(Modifier.fillMaxSize()) {
        SubScreenTopBar("Block categories", onBack = onBack, showDone = true)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Block every app of a kind — also ones you install later. Apps say themselves what they are; " +
                        "if one is wrong, correct it below. Your own blocklist settings for an app (e.g. a pause) win.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(AppCategory.entries, key = { it.name }) { category ->
                val members = list.filter { effective(it) == category }
                val excluded = list.filter { it.detected == category && config.categoryOverrides[it.packageName] == AppCategories.NONE }
                val open = category.name in expanded
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Block all ${category.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
                                Hint(if (apps == null) "Looking for apps…" else "${members.size} installed")
                            }
                            Switch(
                                checked = category in config.blockedCategories,
                                onCheckedChange = { vm.setCategoryBlocked(category, it) },
                            )
                        }
                        TextButton(onClick = { expanded = if (open) expanded - category.name else expanded + category.name }) {
                            Text(if (open) "Hide apps" else "Show apps")
                        }
                        if (open) {
                            if (members.isEmpty()) Hint("None found.")
                            members.forEach { app ->
                                val byHand = config.categoryOverrides[app.packageName] == category.name
                                CategoryAppRow(
                                    app,
                                    status = if (byHand) "Added by you" else "Detected",
                                    action = if (byHand) "Remove" else "Not a ${category.singular}",
                                    onAction = { vm.setOverride(app.packageName, if (byHand) null else AppCategories.NONE) },
                                )
                            }
                            excluded.forEach { app ->
                                CategoryAppRow(
                                    app,
                                    status = "You said: not a ${category.singular}",
                                    action = "Count again",
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
