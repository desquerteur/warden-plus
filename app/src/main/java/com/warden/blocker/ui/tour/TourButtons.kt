package com.warden.blocker.ui.tour

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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

/**
 * "Restart guide" in Settings: one tap starts the Getting started tour again. "Other guides"
 * picks a single guide or brings back the page tips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RestartGuideCard(modifier: Modifier = Modifier) {
    val controller = LocalTourController.current ?: return
    var open by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth().tourTarget(TourIds.SETTINGS_GUIDES)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Restart guide", style = MaterialTheme.typography.titleMedium)
            Text("Forgot how it works? Shows the Getting started tour again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { controller.start(Tours.GETTING_STARTED) }) { Text("Restart guide") }
                TextButton(onClick = { open = true }) { Text("Other guides") }
            }
        }
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
