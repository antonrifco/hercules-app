package com.hercules.mobile.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.hercules.mobile.notify.Notifier
import com.hercules.mobile.notify.ReminderSettings
import com.hercules.shared.cognitive.NudgePrefs
import com.hercules.shared.cognitive.SleepNight
import com.hercules.shared.cognitive.formatDuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.wearable.Wearable
import com.hercules.mobile.BuildConfig
import com.hercules.mobile.MobileGraph
import com.hercules.mobile.R
import com.hercules.mobile.SERVER_SYNC_ENABLED
import com.hercules.shared.model.RecordText
import com.hercules.shared.sensors.SensorConfig
import com.hercules.shared.sensors.SensorKind
import com.hercules.shared.sensors.SensorReport
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Setup: watch link, sensor toggles, personal bests and server sync. Today's stats live on the home screen. */
@Composable
fun DataScreen(g: MobileGraph, today: String, padding: PaddingValues, onLoadDemo: () -> Unit, onOpenLegal: (LegalDoc) -> Unit = {}) {
    val ctx = LocalContext.current
    val records by remember { g.repo.observeRecords() }.collectAsStateWithLifecycle(emptyList())
    val pending by remember { g.repo.observePendingSampleCount() }.collectAsStateWithLifecycle(0L)
    val lastSync by g.lastWatchSync.collectAsStateWithLifecycle()
    var watches by remember { mutableStateOf<List<String>?>(null) }
    var config by remember { mutableStateOf(SensorConfig.DEFAULT) }
    var report by remember { mutableStateOf<SensorReport?>(null) }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sleepLast by remember(today) { g.cognitive.store.observeSleep(today) }.collectAsStateWithLifecycle(null)

    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            runCatching { g.sensorState() }.getOrNull()?.let { (c, r) -> config = c; report = r }
            if (n++ % 6 == 0) {
                watches = runCatching { Wearable.getNodeClient(ctx).connectedNodes.await().map { it.displayName } }.getOrElse { emptyList() }
            }
            delay(5_000)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = CardGap,
    ) {
        item {
            Column {
                Eyebrow("Watch, sensors & sync")
                VSpace(6.dp)
                Text("Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
            }
        }
        // Is it working? first, then the things people change (sensors, reminders), then results and the fine print.
        item { StatusBlock(watches, report, sleepLast, lastSync) }
        item { Link(watches, lastSync, pending) }
        item {
            SensorGrid(config, report) { kind, on ->
                val next = config.with(kind, on, System.currentTimeMillis())
                config = next
                scope.launch { runCatching { g.setSensors(next) } }
            }
        }
        item { RemindersBlock(g) }
        item {
            Section("Personal bests") {
                if (records.isEmpty()) Muted("None yet. They appear as your watch records more days.")
                records.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(RecordText.label(r.kind), style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
                        Text(RecordText.value(r), fontFamily = Mono, fontSize = 14.sp, color = Cyan)
                    }
                    if (i < records.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                }
            }
        }
        item {
            Section("About") {
                LegalRow(Icons.Rounded.PrivacyTip, "Privacy Policy", "What Hercules collects and where it stays") { onOpenLegal(LegalDoc.PRIVACY) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                LegalRow(Icons.Rounded.Gavel, "Terms & Conditions", "The rules for using the app") { onOpenLegal(LegalDoc.TERMS) }
                VSpace(10.dp)
                Muted("Hercules ${BuildConfig.VERSION_NAME}")
            }
        }
        // Cloud sync isn't live yet; only developers see the upload queue.
        if (SERVER_SYNC_ENABLED || BuildConfig.DEBUG) item {
            Section("Server") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$pending", fontFamily = Mono, fontSize = 22.sp, color = Text1)
                    HSpace(10.dp)
                    Eyebrow("readings queued", modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                serverMessage = if (!SERVER_SYNC_ENABLED) {
                                    "No server yet, data stays queued."
                                } else {
                                    val out = g.serverSync.syncOnce()
                                    "Sent ${out.samplesSynced}" + (out.stoppedReason?.let { " ($it)" } ?: "")
                                }
                            }
                        },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, Hairline),
                    ) { Text("UPLOAD", letterSpacing = 1.5.sp, color = Text1, fontSize = 12.sp) }
                }
                serverMessage?.let { VSpace(6.dp); Muted(it) }
            }
        }
        if (BuildConfig.DEBUG) {
            item {
                Section("Developer") {
                    OutlinedButton(onClick = onLoadDemo, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Hairline)) {
                        Text("LOAD 14 DAYS OF DEMO DATA", letterSpacing = 1.2.sp, color = Text1, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegalRow(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(20.dp))
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Text1)
            Muted(hint)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open", tint = Text2, modifier = Modifier.size(20.dp))
    }
}

/** Watch link status. Illustrated when no watch is connected. */
@Composable
private fun Link(watches: List<String>?, lastSync: Long?, pending: Long) {
    val connected = !watches.isNullOrEmpty()
    Section("Watch link", trailing = { Pill(if (connected) "Linked" else "Offline", if (connected) Cyan else Text2) }) {
        if (!connected) {
            Illustration(R.drawable.ill_no_watch, Modifier.fillMaxWidth().height(170.dp))
            VSpace(4.dp)
            Muted("Open Hercules on your watch. It syncs every 15 minutes over Bluetooth.")
        } else {
            Text(watches!!.joinToString(), style = MaterialTheme.typography.titleMedium, color = Text1)
            Muted("Last data " + (lastSync?.let { DateUtils.getRelativeTimeSpanString(it).toString().lowercase() } ?: "never"))
        }
    }
}

/** Two-column grid of sensor tiles; a lit tile is recording. */
@Composable
private fun SensorGrid(config: SensorConfig, report: SensorReport?, onToggle: (SensorKind, Boolean) -> Unit) {
    val active = report?.active
    var confirmOff by remember { mutableStateOf<SensorKind?>(null) }
    confirmOff?.let { k ->
        AlertDialog(
            onDismissRequest = { confirmOff = null },
            containerColor = Panel,
            title = { Text("Turn off ${k.label.lowercase()}?", color = Text1) },
            text = { Text(sensorOffEffect(k), color = Text2) },
            confirmButton = { TextButton(onClick = { onToggle(k, false); confirmOff = null }) { Text("TURN OFF", color = Color(0xFFFF8C4C), letterSpacing = 1.sp) } },
            dismissButton = { TextButton(onClick = { confirmOff = null }) { Text("KEEP ON", color = Cyan, letterSpacing = 1.sp) } },
        )
    }
    Section("Sensors", trailing = {
        Text(
            when {
                report == null -> "${config.enabled.size}/6 on"
                report.active != (config.enabled intersect report.supported) -> "syncing"
                else -> "${report.active.size}/6 live"
            },
            fontFamily = Mono, fontSize = 12.sp, color = Text2,
        )
    }) {
        SensorKind.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { k ->
                    val supported = report?.supported?.contains(k) ?: true
                    val on = k in config.enabled && supported
                    val live = active?.contains(k) == true
                    val tint = if (on) Cyan else Text2
                    Column(
                        Modifier.weight(1f).padding(bottom = 10.dp).clip(RoundedCornerShape(6.dp))
                            .background(if (on) Cyan.copy(alpha = 0.07f) else Panel)
                            .border(BorderStroke(1.dp, if (on) Cyan.copy(alpha = 0.5f) else Hairline), RoundedCornerShape(6.dp))
                            .clickable(enabled = supported) { if (on) confirmOff = k else onToggle(k, true) }
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(k.icon, null, tint = tint, modifier = Modifier.size(20.dp))
                            Box(Modifier.weight(1f))
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) Cyan else if (on) Cyan.copy(alpha = 0.35f) else Hairline))
                        }
                        VSpace(14.dp)
                        Text(k.label, style = MaterialTheme.typography.titleSmall, color = if (supported) Text1 else Text2)
                        VSpace(2.dp)
                        Eyebrow(
                            when {
                                !supported -> "n/a"
                                live -> "live · ${k.feeds}"
                                on -> "on · ${k.feeds}"
                                else -> "off"
                            },
                        )
                    }
                }
            }
        }
    }
}

/** What switching a sensor off costs, said before it happens. */
private fun sensorOffEffect(k: SensorKind): String = when (k) {
    SensorKind.HEART_RATE -> "Hercules stops reading your heart rate. Recovery drops out of your score, so it becomes less accurate."
    SensorKind.SLEEP -> "Hercules stops tracking your sleep. Sleep is the biggest part of your score, so most days you won't get one."
    SensorKind.STEPS -> "Hercules stops counting steps. Activity drops out of your score."
    else -> "Hercules stops recording ${k.label.lowercase()}. Only your daily stats lose it; your score is not affected."
} + " You can turn it back on at any time."

/** One honest row: green when working, amber with the fix when not. */
@Composable
private fun StatusRow(ok: Boolean, icon: ImageVector, title: String, detail: String) {
    val tint = if (ok) Cyan else Color(0xFFFFC857)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tint, size = 34.dp, iconSize = 18.dp)
        HSpace(12.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Text1)
            Muted(detail)
        }
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** Is tracking working? Answers it in four lines, with what to do when it is not. */
@Composable
private fun StatusBlock(watches: List<String>?, report: SensorReport?, sleep: SleepNight?, lastSync: Long?) {
    val linked = !watches.isNullOrEmpty()
    val tracking = report?.active.orEmpty()
    val sleepOn = SensorKind.SLEEP in tracking
    val heartOn = SensorKind.HEART_RATE in tracking
    val syncAge = lastSync?.let { System.currentTimeMillis() - it }
    val syncOk = syncAge != null && syncAge < 6 * 3_600_000L
    val allGood = linked && sleepOn && heartOn && syncOk
    Section("Status", trailing = { Pill(if (allGood) "All good" else "Needs attention", if (allGood) Cyan else Color(0xFFFFC857)) }) {
        StatusRow(linked, Icons.Rounded.Watch, if (linked) "Watch connected" else "Watch not connected", if (linked) watches!!.joinToString() else "Open Hercules on your watch and keep it near your phone.")
        StatusRow(
            sleepOn && heartOn, Icons.Rounded.MonitorHeart,
            if (sleepOn && heartOn) "Tracking sleep and heart" else if (report == null) "Waiting for the watch" else "Tracking is partly off",
            when {
                sleepOn && heartOn -> "Recording in the background."
                report == null -> "Appears once the watch reports in."
                else -> "Turn on Sleep and Heart rate below."
            },
        )
        StatusRow(
            sleep != null, Icons.Rounded.Bedtime,
            if (sleep != null) "Last night: ${formatDuration(sleep.asleepMinutes)}" else "No sleep recorded yet",
            if (sleep != null) "Measured by your watch." else "Wear the watch to bed. Or add it by hand on Today.",
        )
        StatusRow(
            syncOk, Icons.Rounded.Sync,
            if (lastSync == null) "Nothing synced yet" else "Synced " + DateUtils.getRelativeTimeSpanString(lastSync).toString().lowercase(),
            if (syncOk) "Your watch sends data about every 15 minutes." else "Open Hercules on the watch to sync now.",
        )
    }
}

/** Reminders are useful or they are noise, so each one can be switched off. */
@Composable
private fun RemindersBlock(g: MobileGraph) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf(ReminderSettings.load(ctx)) }
    var allowed by remember { mutableStateOf(Notifier.canPost(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = Notifier.canPost(ctx) }
    fun update(next: NudgePrefs) { prefs = next; ReminderSettings.save(ctx, next); scope.launch { g.pushToWatch() } }
    Section("Reminders") {
        if (!allowed) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cyan.copy(alpha = 0.07f))
                    .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.NotificationsOff, null, tint = Cyan, modifier = Modifier.size(20.dp))
                HSpace(10.dp)
                Column(Modifier.weight(1f)) {
                    Text("Reminders are off", style = MaterialTheme.typography.bodyMedium, color = Text1)
                    Muted("Allow notifications to get your morning score and bedtime.")
                }
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }) { Text("ALLOW", letterSpacing = 1.2.sp, fontSize = 12.sp) }
            }
            VSpace(8.dp)
        }
        ReminderRow(Icons.Rounded.WbSunny, "Morning score", "Your readiness and best focus time.", prefs.morning) { update(prefs.copy(morning = it)) }
        ReminderRow(Icons.Rounded.Flag, "Moment prep", "The evening before, and 2 hours ahead.", prefs.event) { update(prefs.copy(event = it)) }
        ReminderRow(Icons.Rounded.Bedtime, "Bedtime", "An hour before you should be in bed.", prefs.evening) { update(prefs.copy(evening = it)) }
        ReminderRow(Icons.Rounded.RateReview, "After a moment", "One tap to say how it went.", prefs.reflect) { update(prefs.copy(reflect = it)) }
        ReminderRow(Icons.Rounded.Timer, "Focus time", "When your best hours start.", prefs.focus) { update(prefs.copy(focus = it)) }
        VSpace(6.dp)
        Muted("If you wear your watch, reminders come to your wrist instead of your phone.")
    }
}

@Composable
private fun ReminderRow(icon: ImageVector, title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (on) Cyan else Text2, modifier = Modifier.size(20.dp))
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Text1)
            Muted(hint)
        }
        Switch(
            checked = on, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Cyan, uncheckedThumbColor = Text2, uncheckedTrackColor = Panel, uncheckedBorderColor = Hairline),
        )
    }
}
