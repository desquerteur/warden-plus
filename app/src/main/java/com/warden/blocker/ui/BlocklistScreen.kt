package com.warden.blocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode

@Composable
fun BlocklistScreen(vm: WardenViewModel, onOpenAppPicker: () -> Unit, onOpenFeatures: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<BlockedItem?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Blocklist", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Website, e.g. instagram.com") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { vm.addWebsite(input); input = "" }, modifier = Modifier.padding(start = 8.dp)) { Text("Add") }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenAppPicker, modifier = Modifier.fillMaxWidth()) { Text("+ Add apps to block") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenFeatures, modifier = Modifier.fillMaxWidth()) { Text("Block in-app feeds (Reels, Shorts…)") }
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) {
            Text("Nothing blocked yet — add a website above or tap “Add apps”.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                Card(Modifier.fillMaxWidth().clickable { editing = item }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.type == BlockType.APP) {
                            AppIcon(item.value, modifier = Modifier.size(36.dp).padding(end = 12.dp))
                        } else {
                            Text("🌐", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.titleMedium)
                            Text(itemSummary(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(checked = item.enabled, onCheckedChange = { vm.toggleItem(item) })
                        IconButton(onClick = { vm.removeItem(item) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                    }
                }
            }
        }
    }

    editing?.let { current ->
        ItemEditorSheet(
            item = current,
            onSave = { vm.saveItem(it) },
            onDismiss = { editing = null },
        )
    }
}

private fun itemSummary(item: BlockedItem): String {
    if (item.type == BlockType.WEBSITE) return "Website · hard block"
    return when (item.interceptMode) {
        InterceptMode.BLOCK -> "App · hard block"
        InterceptMode.PAUSE -> buildString {
            append("App · pause ${item.pauseSeconds}s")
            if (item.openLimitPerDay > 0) append(" · ${item.openLimitPerDay}/day")
            if (item.dailyLimitMinutes > 0) append(" · ${item.dailyLimitMinutes}m limit")
        }
    }
}
