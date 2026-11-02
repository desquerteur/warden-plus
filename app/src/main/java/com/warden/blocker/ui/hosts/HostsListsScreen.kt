package com.warden.blocker.ui.hosts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.hosts.HostsListRepository
import com.warden.blocker.hosts.HostsSource
import com.warden.blocker.hosts.HostsTemplates
import com.warden.blocker.hosts.ImportProgress
import com.warden.blocker.ui.SubScreenTopBar
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Entry point for the Blocklist page: opens [HostsListsScreen]. */
@Composable
fun HostsListsEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text("Import blocklists (hosts files)") }
}

/**
 * Import hosts files / domain blocklists (e.g. StevenBlack) from a file, a link or a
 * ready-made template. Every domain in an enabled list is blocked by the website filter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostsListsScreen(onBack: () -> Unit, vm: HostsViewModel = viewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showUrlDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<HostsSource?>(null) }
    val busy = progress != null

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importFile)
    }

    // Show on a separate scope: clearing the message re-keys this effect, which would
    // otherwise cancel the snackbar right away.
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        message?.let {
            vm.messageShown()
            scope.launch { snackbar.showSnackbar(it) }
        }
    }

    // Same top bar and 20dp side margins as the other sub-screens (app picker, feeds, setup).
    Column(Modifier.fillMaxSize()) {
        SubScreenTopBar("Import blocklists", onBack = onBack)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Import hosts files or domain lists. Every domain in an enabled list — and its " +
                            "subdomains — is blocked while blocking is on.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickFile.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = !busy) {
                            Icon(Icons.Filled.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Import file")
                        }
                        OutlinedButton(onClick = { showUrlDialog = true }, enabled = !busy) {
                            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add from URL")
                        }
                    }
                }

                progress?.let { p -> item { ProgressCard(p) } }

                item {
                    Text("Ready-made lists", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "From github.com/StevenBlack/hosts. The add-ons work on top of Unified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.size(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HostsTemplates.ALL.forEach { t ->
                            val added = sources.any { it.url == t.url }
                            FilterChip(
                                selected = added,
                                onClick = { if (!added) vm.addTemplate(t) },
                                enabled = added || !busy,
                                label = { Text(t.name) },
                                leadingIcon = {
                                    Icon(if (added) Icons.Filled.Check else Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                },
                            )
                        }
                    }
                }

                item {
                    val active = sources.filter { it.enabled }
                    Text("Your lists", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (sources.isEmpty()) "No lists yet — import a file, add a link or tap a ready-made list."
                        else "${active.size} of ${sources.size} enabled · ${formatCount(active.sumOf { it.domainCount })} domains",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                items(sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        busy = busy,
                        onToggle = { vm.setEnabled(source, it) },
                        onRefresh = { vm.refresh(source) },
                        onDelete = { confirmDelete = source },
                    )
                }
            }

            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
        }
    }

    if (showUrlDialog) {
        UrlDialog(
            onAdd = { url -> showUrlDialog = false; vm.addUrl(url) },
            onDismiss = { showUrlDialog = false },
        )
    }

    confirmDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove list?") },
            text = { Text("“${source.name}” and its ${formatCount(source.domainCount)} domains will no longer be blocked.") },
            confirmButton = { TextButton(onClick = { vm.delete(source); confirmDelete = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProgressCard(p: ImportProgress) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Importing “${p.sourceName}”…", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.size(8.dp))
            if (p.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { (p.bytesRead.toFloat() / p.totalBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.size(6.dp))
            Text(
                "${formatCount(p.domains)} domains · ${formatMegabytes(p.bytesRead)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SourceCard(
    source: HostsSource,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatCount(source.domainCount)} domains · ${if (source.url != null) "Link" else "File"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onToggle)
            }
            source.lastError?.let {
                Text(
                    "Last update failed: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Updated ${formatDate(source.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                if (source.url != null) {
                    IconButton(onClick = onRefresh, enabled = !busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Update ${source.name}")
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove ${source.name}")
                }
            }
        }
    }
}

@Composable
private fun UrlDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("https://") }
    val valid = HostsListRepository.normalizeUrl(url) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add from URL") },
        text = {
            Column {
                Text("Link to a hosts file or a domain list (https only, max 30 MB).", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    isError = url.length > "https://".length && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(url) }, enabled = valid) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun formatCount(n: Int): String = "%,d".format(n)

private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

private fun formatDate(millis: Long): String =
    if (millis <= 0) "never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
