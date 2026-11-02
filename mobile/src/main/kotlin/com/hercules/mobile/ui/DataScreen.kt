package com.hercules.mobile.ui

import android.text.format.DateUtils
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
fun DataScreen(g: MobileGraph, today: String, padding: PaddingValues, onLoadDemo: () -> Unit) {
    val ctx = LocalContext.current
    val records by remember { g.repo.observeRecords() }.collectAsStateWithLifecycle(emptyList())
    val pending by remember { g.repo.observePendingSampleCount() }.collectAsStateWithLifecycle(0L)
    val lastSync by g.lastWatchSync.collectAsStateWithLifecycle()
    var watches by remember { mutableStateOf<List<String>?>(null) }
    var config by remember { mutableStateOf(SensorConfig.DEFAULT) }
    var report by remember { mutableStateOf<SensorReport?>(null) }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
        item { Link(watches, lastSync, pending) }
        item {
            SensorGrid(config, report) { kind, on ->
                val next = config.with(kind, on, System.currentTimeMillis())
                config = next
                scope.launch { runCatching { g.setSensors(next) } }
            }
        }
        item {
            Section("Personal bests") {
                if (records.isEmpty()) Muted("None yet")
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
                            .clickable(enabled = supported) { onToggle(k, !on) }
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
