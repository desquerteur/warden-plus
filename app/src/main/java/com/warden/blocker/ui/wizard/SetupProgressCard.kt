package com.warden.blocker.ui.wizard

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.ui.WardenViewModel

/**
 * "Setup 5/7 – continue" on Home while a setup page is neither done nor put off; otherwise
 * [orElse] (Home's plain "Finish setup" card), so the two never show together.
 */
@Composable
fun SetupProgressCard(
    vm: WardenViewModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    wizardVm: WizardViewModel = viewModel(),
    orElse: @Composable () -> Unit = {},
) {
    val wizard = wizardVm.controller
    val state by wizard.state.collectAsStateWithLifecycle()
    val sdk = Build.VERSION.SDK_INT
    val done = rememberWizardDone(vm, state)
    val skipped = state?.skipped ?: return
    if (!WizardLogic.showHomeCard(sdk, done, skipped)) { orElse(); return }
    val open = WizardLogic.open(sdk, done, skipped)
    val (count, total) = WizardLogic.progress(sdk, done)
    ElevatedCard(
        modifier.fillMaxWidth().clickable {
            wizard.resumeAt(open.first())
            onOpen()
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text("Setup $count/$total – continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Next: ${open.first().title}. Tap to go on.", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { count / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            // Hides the card: the open pages count as "later" (still in Settings › Setup wizard).
            IconButton(onClick = { wizard.skipAll(open) }) { Icon(Icons.Filled.Close, contentDescription = "Hide setup reminder") }
        }
    }
}

/**
 * Opens the wizard by itself on the first start after installing (see
 * [WizardLogic.shouldAutoOpen]); once per app run, so rotating doesn't open it again.
 */
@Composable
fun AutoOpenSetupWizard(wizardVm: WizardViewModel = viewModel(), open: suspend () -> Unit) {
    val state by wizardVm.controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var handled by rememberSaveable { mutableStateOf(false) }
    val status = state?.status
    LaunchedEffect(status != null) {
        if (status == null || handled) return@LaunchedEffect
        handled = true
        if (WizardLogic.shouldAutoOpen(status, WizardChecks.freshInstall(context))) open()
    }
}
