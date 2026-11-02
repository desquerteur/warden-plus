package com.warden.blocker.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.block.TimeWindows
import com.warden.blocker.data.Schedule
import com.warden.blocker.ui.times.OnceBlockDialog
import com.warden.blocker.ui.times.scheduleLine
import java.time.ZoneId
import com.warden.blocker.ui.tour.TourIds
import com.warden.blocker.ui.tour.tourTarget

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

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Schedules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("With 'always-on' off, the blocklist is only enforced during these windows.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().tourTarget(TourIds.SCHEDULES_NEW)) { Text("+ New schedule") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { creatingOnce = true }, modifier = Modifier.fillMaxWidth()) { Text("+ One-time block (date & time)") }
        Spacer(Modifier.height(16.dp))
        if (schedules.isEmpty()) {
            Text("No schedules yet. Add one, then turn off “Always on” in Settings to use them.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(schedules, key = { it.id }) { s ->
                Card(Modifier.fillMaxWidth().clickable { editing = s }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text(scheduleLine(s, now, zone, is24Hour, daysLabel(s.daysMask)), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = s.enabled, onCheckedChange = { vm.addSchedule(s.copy(enabled = it)) })
                        IconButton(onClick = { vm.removeSchedule(s) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
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
