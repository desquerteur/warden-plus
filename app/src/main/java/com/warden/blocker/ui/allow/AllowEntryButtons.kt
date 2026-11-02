package com.warden.blocker.ui.allow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.allow.AllowConfig
import com.warden.blocker.allow.ListMode

/** The two entries on the Blocklist screen, each saying what's switched on. */
@Composable
fun AllowEntryButtons(onOpenAllowOnly: () -> Unit, onOpenCategories: () -> Unit, vm: AllowViewModel = viewModel()) {
    val config by vm.config.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpenAllowOnly, modifier = Modifier.fillMaxWidth()) {
            EntryText("Allow only (block everything except…)", allowOnlySummary(config))
        }
        OutlinedButton(onClick = onOpenCategories, modifier = Modifier.fillMaxWidth()) {
            EntryText(
                "Block categories (games, social…)",
                config.blockedCategories.takeIf { it.isNotEmpty() }
                    ?.sortedBy { it.ordinal }?.joinToString { it.label } ?: "Off",
            )
        }
    }
}

@Composable
private fun EntryText(title: String, state: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            state,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun allowOnlySummary(config: AllowConfig): String {
    val parts = buildList {
        if (config.appMode == ListMode.ALLOW_ONLY) add("apps")
        if (config.webMode == ListMode.ALLOW_ONLY) add("websites")
    }
    return if (parts.isEmpty()) "Off" else "On for " + parts.joinToString(" and ")
}
