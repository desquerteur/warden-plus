package com.warden.blocker.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.usage.AppsHelper
import com.warden.blocker.usage.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class UsageRow(val pkg: String, val label: String, val millis: Long)

@Composable
fun StatsScreen(vm: WardenViewModel) {
    val context = LocalContext.current
    val hasPermission = remember { UsageStatsHelper.hasPermission(context) }
    val streak by vm.currentStreak.collectAsStateWithLifecycle()
    val longest by vm.longestStreak.collectAsStateWithLifecycle()
    val sessions by vm.focusSessionsDone.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Your wins", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Streak", "$streak d", Modifier.weight(1f))
            StatCard("Best", "$longest d", Modifier.weight(1f))
            StatCard("Sessions", "$sessions", Modifier.weight(1f))
        }
        Spacer(Modifier.height(20.dp))
        Text("Screen time today", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (!hasPermission) {
            Text("Grant usage access to see screen-time.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                Text("Grant usage access")
            }
        } else {
            val rows by produceState<List<UsageRow>?>(initialValue = null) {
                value = withContext(Dispatchers.Default) {
                    UsageStatsHelper.todayUsage(context).take(25)
                        .map { UsageRow(it.packageName, AppsHelper.labelFor(context, it.packageName), it.totalMillis) }
                }
            }
            val list = rows
            if (list != null && list.isEmpty()) {
                Text("No screen time recorded yet today.", style = MaterialTheme.typography.bodyMedium)
            } else if (list != null) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(list, key = { it.pkg }) { u ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(u.pkg, modifier = Modifier.size(32.dp).padding(end = 12.dp))
                            Text(u.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(fmtDuration(u.millis), style = MaterialTheme.typography.bodyMedium)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
