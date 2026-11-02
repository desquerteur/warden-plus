package com.warden.blocker.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlarmAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.block.TimeWindows
import com.warden.blocker.data.Schedule
import com.warden.blocker.ui.times.OnceBlockDialog
import com.warden.blocker.ui.times.scheduleLine
import com.warden.blocker.ui.tour.TourIds
import com.warden.blocker.ui.tour.tourTarget
import java.time.ZoneId

@Composable
fun SchedulesScreen(vm: WardenViewModel) {
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var creating by remember { mutableStateOf(false) }
    var creatingOnce by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    val now = System.currentTimeMillis()
    ScrollToTopOnReselect(listState)
    val gutter = pageGutter()

    // Header and schedules scroll together (like Blocklist), so the list isn't squeezed on short screens.
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(gutter),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(CardSpacing)) {
                PageTitle("Schedules")
                IntroText("With 'always-on' off, the blocklist is only enforced during these windows.")
                ActionGroup {
                    ActionRow(
                        Icons.Outlined.AlarmAdd, "New schedule", "Every week, e.g. Mon–Fri 9:00–17:00",
                        onClick = { creating = true }, modifier = Modifier.tourTarget(TourIds.SCHEDULES_NEW),
                    )
                    ActionRow(
                        Icons.Outlined.EventAvailable, "One-time block", "From one date and time to another",
                        onClick = { creatingOnce = true }, divider = true,
                    )
                }
                if (schedules.isEmpty()) {
                    EmptyState("No schedules yet. Add one, then turn off “Always on” in Settings to use them.")
                } else {
                    SectionHeader("Your schedules")
                }
            }
        }
        items(schedules, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth().animateItem().clickable { editing = s }) {
                Row(
                    Modifier.fillMaxWidth().padding(start = CardPadding, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            if (TimeWindows.isOnce(s)) Icons.Outlined.Event else Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        SupportingText(scheduleLine(s, now, zone, is24Hour, daysLabel(s.daysMask)))
                    }
                    Switch(checked = s.enabled, onCheckedChange = { vm.addSchedule(s.copy(enabled = it)) })
                    IconButton(onClick = { vm.removeSchedule(s) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (creating) {
        ScheduleEditorDialog(initial = null, onSave = { vm.addSchedule(it) }, onDismiss = { creating = false })
    }
    if (creatingOnce) {
        OnceBlockDialog(initial = null, onSave = { vm.addSchedule(it) }, onDismiss = { creatingOnce = false })
    }
    editing?.let { current ->
        // Shorter one-time blocks or narrower dates ask Block protection first (in addSchedule).
        val save: (Schedule) -> Unit = { vm.addSchedule(it) }
        if (TimeWindows.isOnce(current)) {
            OnceBlockDialog(initial = current, onSave = save, onDismiss = { editing = null })
        } else {
            ScheduleEditorDialog(initial = current, onSave = save, onDismiss = { editing = null })
        }
    }
}
