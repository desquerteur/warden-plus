package com.warden.blocker.ui.tour

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Starts [tourId] from the beginning, e.g. "Show me how" on the Setup screen. */
@Composable
fun TourLaunchButton(tourId: String, label: String, modifier: Modifier = Modifier.fillMaxWidth()) {
    val controller = LocalTourController.current ?: return
    OutlinedButton(onClick = { controller.start(tourId) }, modifier = modifier) {
        Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** "Restart guides" in Settings: pick a guide to see again, or reset all of them. */
@Composable
fun RestartGuidesButton(modifier: Modifier = Modifier.fillMaxWidth()) {
    val controller = LocalTourController.current ?: return
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier.tourTarget(TourIds.SETTINGS_GUIDES)) {
        Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Restart guides")
    }
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Guides") },
        text = {
            Column {
                listOf(Tours.gettingStarted, Tours.protection).forEach { tour ->
                    TextButton(onClick = { open = false; controller.start(tour.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(tour.title, modifier = Modifier.fillMaxWidth())
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { open = false; controller.resetAll() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Show page tips again", modifier = Modifier.fillMaxWidth())
                }
                Text(
                    "A small hint then appears the next time you open each page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
