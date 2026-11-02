package com.warden.blocker.ui.allow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.allow.AlwaysAllowed
import com.warden.blocker.allow.CategorizedApp
import com.warden.blocker.ui.AppIcon
import com.warden.blocker.ui.SubScreenTopBar

/** Tick the apps that stay open in "Allow only". Saved on every tap. */
@Composable
fun AllowedAppsScreen(vm: AllowViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val alwaysAllowed by vm.alwaysAllowed.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.refreshApps() }

    Column(Modifier.fillMaxSize()) {
        SubScreenTopBar("Allowed apps", onBack = onBack, showDone = true)
        val list = apps
        if (list == null) {
            Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Column
        }
        val matches = list.filter { it.label.contains(query, ignoreCase = true) }
        val (always, choosable) = matches.partition { it.packageName in alwaysAllowed }
        val suggested = if (query.isBlank()) {
            AlwaysAllowed.SUGGESTIONS.mapNotNull { pkg -> choosable.find { it.packageName == pkg } }
                .filter { it.packageName !in config.allowedApps }
        } else emptyList()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ticked apps open normally in “Allow only”. Everything else is blocked.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        label = { Text("Search apps") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (suggested.isNotEmpty()) {
                item { Heading("Suggested") }
                items(suggested, key = { "s:" + it.packageName }) { app ->
                    AppRow(app, checked = false, onToggle = { vm.setAppAllowed(app.packageName, true) })
                }
            }
            item { Heading("Your apps") }
            items(choosable, key = { it.packageName }) { app ->
                val allowed = app.packageName in config.allowedApps
                AppRow(app, checked = allowed, onToggle = { vm.setAppAllowed(app.packageName, !allowed) })
            }
            if (always.isNotEmpty()) {
                item { Heading("Always allowed") }
                items(always, key = { "a:" + it.packageName }) { app -> AppRow(app, checked = true, onToggle = null) }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

/** [onToggle] null: always allowed, shown with a lock and not changeable. */
@Composable
private fun AppRow(app: CategorizedApp, checked: Boolean, onToggle: (() -> Unit)?) {
    val base = Modifier.fillMaxWidth()
    val clickable = if (onToggle != null) base.toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }) else base
    Row(clickable.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app.packageName, modifier = Modifier.size(40.dp).padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge)
            if (onToggle == null) Hint("Always allowed — needed to use the phone")
        }
        if (onToggle == null) {
            Icon(Icons.Filled.Lock, contentDescription = "Always allowed", tint = MaterialTheme.colorScheme.outline)
        } else {
            Checkbox(checked = checked, onCheckedChange = null)
        }
    }
}
