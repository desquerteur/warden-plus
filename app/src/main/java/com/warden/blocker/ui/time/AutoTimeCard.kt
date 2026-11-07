package com.warden.blocker.ui.time

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warden.blocker.guard.formatDuration
import com.warden.blocker.time.ClockStatus
import com.warden.blocker.time.TrustedClock
import com.warden.blocker.ui.WarningCard
import kotlinx.coroutines.delay

/** Android's "Date & time" settings page. */
fun dateSettingsIntent(): Intent = Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun openDateSettings(context: Context) {
    runCatching { context.startActivity(dateSettingsIntent()) }
}

/** Whether "automatic date & time" is on, re-read every few seconds (coming back from Settings). */
@Composable
fun rememberAutoTimeOn(): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(TrustedClock.isAutoTimeOn(context)) }
    LaunchedEffect(Unit) {
        while (true) { on = TrustedClock.isAutoTimeOn(context); delay(3_000) }
    }
    return on
}

/** The card's text, or null when there's nothing to warn about. */
internal fun autoTimeWarning(autoTime: Boolean, status: ClockStatus): String? {
    val off = status.offsetMs
    return when {
        status.clockOff -> "The phone's clock is ${formatDuration(off)} " + (if (off > 0) "ahead" else "behind") +
            ". Your blocks keep running on the real time, so they may end later than the clock says. " +
            "Turn on automatic date & time to set it right."
        status.zoneHeld -> "The time zone was changed by hand. Schedules keep following the old one. " +
            "Turn on automatic time zone to set it right."
        !autoTime -> "Automatic date & time is off. Warden+ keeps your blocks on the real time even when the " +
            "clock is changed — keep it on so the phone's clock stays right."
        else -> null
    }
}

/** Red card on Home and Protection while automatic time is off or the clock was set by hand. */
@Composable
fun AutoTimeCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val autoTime = rememberAutoTimeOn()
    val status by TrustedClock.status.collectAsStateWithLifecycle()
    val text = autoTimeWarning(autoTime, status) ?: return
    WarningCard(
        title = if (status.zoneHeld && !status.clockOff && autoTime) "Turn on automatic time zone" else "Turn on automatic date & time",
        text = text,
        modifier = modifier,
    ) {
        OutlinedButton(onClick = { openDateSettings(context) }) { Text("Date & time settings") }
    }
}
