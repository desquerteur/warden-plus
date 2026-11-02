package com.warden.blocker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.ui.hosts.HostsListsEntryButton
import com.warden.blocker.ui.tour.TourIds
import com.warden.blocker.ui.tour.tourTarget
import kotlinx.coroutines.delay

@Composable
fun BlocklistScreen(
    vm: WardenViewModel,
    onOpenAppPicker: () -> Unit,
    onOpenFeatures: () -> Unit,
    onOpenHostsLists: () -> Unit,
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val justAdded by vm.justAdded.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var inputInvalid by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BlockedItem?>(null) }
    var highlighted by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    ScrollToTopOnReselect(listState)

    fun submit() {
        if (vm.addWebsite(input)) {
            input = ""
            // Make room for the list so the new entry can be seen (the keyboard covered it).
            keyboard?.hide()
            focusManager.clearFocus()
        } else {
            inputInvalid = input.isNotBlank()
        }
    }

    // The list is sorted by name, so a new entry (typed here or picked in "Add apps") can land
    // below the visible part. Scroll to it once Room has delivered it and flash it briefly.
    LaunchedEffect(justAdded, items) {
        val id = justAdded ?: return@LaunchedEffect
        if (items.none { it.id == id }) return@LaunchedEffect // runs again once Room delivers it
        vm.consumeJustAdded(id)
        highlighted = id
    }
    LaunchedEffect(highlighted) {
        val id = highlighted ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) listState.animateScrollToItem(HEADER_ITEMS + index)
        delay(1500)
        highlighted = null
    }

    // Header and entries scroll together, so on short screens (fold cover display, open
    // keyboard) the list isn't squeezed into the little space left under the buttons.
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column {
                Text("Blocklist", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.tourTarget(TourIds.BLOCKLIST_ADD_SITE), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; inputInvalid = false },
                        label = { Text("Website, e.g. instagram.com") },
                        singleLine = true,
                        isError = inputInvalid,
                        supportingText = if (inputInvalid) {
                            { Text("Enter a website name like example.com") }
                        } else null,
                        // The keyboard's ✓ adds the site too, not only the button.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { submit() }, modifier = Modifier.padding(start = 8.dp)) { Text("Add") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenAppPicker, modifier = Modifier.fillMaxWidth().tourTarget(TourIds.BLOCKLIST_ADD_APPS)) { Text("+ Add apps to block") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenFeatures, modifier = Modifier.fillMaxWidth().tourTarget(TourIds.BLOCKLIST_FEEDS)) { Text("Block in-app feeds (Reels, Shorts…)") }
                Spacer(Modifier.height(8.dp))
                HostsListsEntryButton(onClick = onOpenHostsLists, modifier = Modifier.fillMaxWidth().tourTarget(TourIds.BLOCKLIST_HOSTS))
                Spacer(Modifier.height(8.dp))
                if (items.isEmpty()) {
                    Text("Nothing blocked yet — add a website above or tap “Add apps”.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        items(items, key = { it.id }) { item ->
            val colors = if (item.id == highlighted) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else CardDefaults.cardColors()
            Card(Modifier.fillMaxWidth().clickable { editing = item }, colors = colors) {
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

    editing?.let { current ->
        ItemEditorSheet(
            item = current,
            onSave = { vm.saveItem(it) },
            onDismiss = { editing = null },
        )
    }
}

/** Lazy items above the first entry (the header block). */
private const val HEADER_ITEMS = 1

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
