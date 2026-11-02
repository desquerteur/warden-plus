package com.warden.blocker.ui.allow

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.warden.blocker.allow.AllowConfig
import com.warden.blocker.allow.ListMode
import com.warden.blocker.ui.ActionRow

/** The two entries in the Blocklist screen's card of entries, each saying what's switched on. */
@Composable
fun AllowEntryRows(onOpenAllowOnly: () -> Unit, onOpenCategories: () -> Unit, vm: AllowViewModel = viewModel()) {
    val config by vm.config.collectAsStateWithLifecycle()
    ActionRow(
        Icons.Outlined.DoneAll,
        "Allow only",
        "Block everything except… · ${allowOnlySummary(config)}",
        onOpenAllowOnly,
        divider = true,
    )
    ActionRow(
        Icons.Outlined.Category,
        "Block categories",
        "Games, social… · " + (
            config.blockedCategories.takeIf { it.isNotEmpty() }
                ?.sortedBy { it.ordinal }?.joinToString { it.label } ?: "Off"
            ),
        onOpenCategories,
        divider = true,
    )
}

private fun allowOnlySummary(config: AllowConfig): String {
    val parts = buildList {
        if (config.appMode == ListMode.ALLOW_ONLY) add("apps")
        if (config.webMode == ListMode.ALLOW_ONLY) add("websites")
    }
    return if (parts.isEmpty()) "Off" else "On for " + parts.joinToString(" and ")
}
