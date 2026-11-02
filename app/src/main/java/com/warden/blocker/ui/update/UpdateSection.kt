package com.warden.blocker.ui.update

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.BuildConfig
import com.warden.blocker.update.ReleaseInfo
import com.warden.blocker.update.UpdateInstaller

/**
 * Settings card for the in-app updater: shows the installed version, checks GitHub for a newer
 * release, downloads and installs it, and toggles the daily background check.
 */
@Composable
fun UpdateSection(modifier: Modifier = Modifier, vm: UpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val autoCheck by vm.autoCheck.collectAsStateWithLifecycle()
    // Set only if the confirmation arrived while Warden was in the background (otherwise
    // InstallResultReceiver opens it directly). Collected only while visible, so the system
    // dialog is launched from the foreground.
    val confirmation by vm.pendingConfirmation.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    LaunchedEffect(confirmation) {
        val intent = confirmation ?: return@LaunchedEffect
        try {
            context.startActivity(intent)
            vm.onConfirmationLaunched()
        } catch (e: ActivityNotFoundException) {
            vm.onConfirmationFailed()
        }
    }

    fun openUrl(url: String) {
        if (!url.startsWith("https://github.com/")) return
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            Text(
                "Installed version: ${BuildConfig.VERSION_NAME}" + if (BuildConfig.DEBUG) " (debug build)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            if (BuildConfig.DEBUG) {
                Text(
                    "Updates from GitHub are release builds signed with a different key. To use them, " +
                        "uninstall this debug build once and install the release version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (val s = state) {
                UpdateUiState.Idle -> CheckButton(vm::check)
                UpdateUiState.Checking -> {
                    Text("Checking GitHub…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is UpdateUiState.UpToDate -> {
                    Text("You're up to date (latest: ${s.latestVersion}).", style = MaterialTheme.typography.bodyMedium)
                    CheckButton(vm::check, again = true)
                }
                UpdateUiState.NoReleases -> {
                    Text("No release has been published yet.", style = MaterialTheme.typography.bodyMedium)
                    CheckButton(vm::check, again = true)
                }
                is UpdateUiState.Available -> {
                    ReleaseDetails(s.release)
                    Button(onClick = vm::downloadAndInstall, modifier = Modifier.fillMaxWidth()) { Text("Download & install") }
                    OutlinedButton(onClick = { openUrl(s.release.pageUrl) }, modifier = Modifier.fillMaxWidth()) { Text("View on GitHub") }
                }
                is UpdateUiState.NeedsPermission -> {
                    Text(
                        "To install updates, allow Warden to install apps. Turn on “Allow from this source”, then come back.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            try {
                                context.startActivity(UpdateInstaller.unknownSourcesSettingsIntent(context))
                            } catch (_: ActivityNotFoundException) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open settings") }
                    OutlinedButton(onClick = vm::downloadAndInstall, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                }
                is UpdateUiState.Downloading -> {
                    val progress = s.progress
                    Text(
                        "Downloading ${s.release.version}…" + if (progress != null) " ${(progress * 100).toInt()} %" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = vm::cancelDownload, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                }
                is UpdateUiState.Installing -> {
                    Text(
                        "Installing ${s.release.version}… Confirm in the system dialog. Warden restarts when it's done.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is UpdateUiState.Error -> {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    val release = s.release
                    if (release != null && !s.needsUninstall) {
                        Button(onClick = vm::downloadAndInstall, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
                    } else {
                        CheckButton(vm::check, again = true)
                    }
                    if (release != null) {
                        OutlinedButton(onClick = { openUrl(release.pageUrl) }, modifier = Modifier.fillMaxWidth()) { Text("View on GitHub") }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Check automatically", style = MaterialTheme.typography.bodyLarge)
                    Text("Once a day. You get a notification when a new version is out.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoCheck, onCheckedChange = vm::setAutoCheck)
            }
            if (autoCheck) NotificationPermissionHint()
        }
    }
}

@Composable
private fun CheckButton(onClick: () -> Unit, again: Boolean = false) {
    if (again) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
    } else {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
    }
}

@Composable
private fun ReleaseDetails(release: ReleaseInfo) {
    var expanded by rememberSaveable(release.tag) { mutableStateOf(false) }
    var overflowing by remember(release.tag) { mutableStateOf(false) }
    Text("New version: ${release.version}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
    if (release.title != release.tag && release.title.isNotBlank()) {
        Text(release.title, style = MaterialTheme.typography.bodyMedium)
    }
    if (release.notes.isNotBlank()) {
        Text(
            release.notes,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else 8,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
        )
        if (overflowing || expanded) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
        }
    }
}

/** Android 13+: the daily check can only notify if notifications are allowed. */
@Composable
private fun NotificationPermissionHint() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        Text("Notifications are off, so you won't be told about new versions.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
    }
}
