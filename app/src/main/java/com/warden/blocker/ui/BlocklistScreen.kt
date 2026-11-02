package com.warden.blocker.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.data.BlockType
import com.warden.blocker.data.BlockedItem
import com.warden.blocker.data.InterceptMode
import com.warden.blocker.ui.allow.AllowEntryRows
import com.warden.blocker.ui.times.SpanLabel
import com.warden.blocker.ui.tour.TourIds
import com.warden.blocker.ui.tour.tourTarget
import kotlinx.coroutines.delay

@Composable
fun BlocklistScreen(
    vm: WardenViewModel,
    onOpenAppPicker: () -> Unit,
    onOpenFeatures: () -> Unit,
    onOpenHostsLists: () -> Unit,
    onOpenAllowOnly: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
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
    val gutter = pageGutter()
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(gutter),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
                PageTitle("Blocklist")
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
                // Everything else that blocks: one quiet card of entries instead of a wall of buttons.
                ActionGroup {
                    ActionRow(
                        Icons.Outlined.Apps, "Add apps to block", "Fully, or with a pause before they open",
                        onOpenAppPicker, Modifier.tourTarget(TourIds.BLOCKLIST_ADD_APPS),
                    )
                    ActionRow(
                        Icons.Outlined.Slideshow, "Block in-app feeds", "Reels, Shorts, For You …",
                        onOpenFeatures, Modifier.tourTarget(TourIds.BLOCKLIST_FEEDS), divider = true,
                    )
                    ActionRow(
                        Icons.Outlined.FileDownload, "Import blocklists", "Hosts files — thousands of sites at once",
                        onOpenHostsLists, Modifier.tourTarget(TourIds.BLOCKLIST_HOSTS), divider = true,
                    )
                    Column(Modifier.tourTarget(TourIds.BLOCKLIST_ALLOW)) { AllowEntryRows(onOpenAllowOnly, onOpenCategories) }
                }
                if (items.isEmpty()) {
                    EmptyState("Nothing blocked yet — add a website above or tap “Add apps”.")
                } else {
                    SectionHeader("Blocked · ${items.size}")
                }
            }
        }
        items(items, key = { it.id }) { item ->
            // The entry just added glows briefly in the theme colour, then fades back.
            val container by animateColorAsState(
                if (item.id == highlighted) MaterialTheme.colorScheme.primaryContainer else CardDefaults.cardColors().containerColor,
                tween(Motion.MEDIUM * 2),
                label = "highlight",
            )
            Card(
                Modifier.fillMaxWidth().animateItem().clickable { editing = item },
                colors = CardDefaults.cardColors(containerColor = container),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = CardPadding, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.type == BlockType.APP) {
                        AppIcon(item.value, modifier = Modifier.size(36.dp))
                    } else {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.titleMedium)
                        SupportingText(itemSummary(item))
                        SpanLabel(item.activeFrom, item.activeUntil)
                    }
                    Switch(checked = item.enabled, onCheckedChange = { vm.toggleItem(item) })
                    IconButton(onClick = { vm.removeItem(item) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    editing?.let { current ->
        ItemEditorSheet(
            item = current,
            // A shorter "until …" or a later "from …" asks Block protection first (in saveItem).
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
