package com.hercules.mobile.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.wearable.Wearable
import com.hercules.mobile.BuildConfig
import com.hercules.mobile.MobileGraph
import com.hercules.mobile.R
import com.hercules.mobile.health.HealthConnectState
import com.hercules.mobile.health.HealthPermissions
import com.hercules.mobile.health.HealthWorker
import com.hercules.mobile.notify.Notifier
import com.hercules.mobile.notify.ReminderSettings
import com.hercules.shared.cognitive.grouped
import com.hercules.shared.cognitive.PersonalBest
import com.hercules.shared.cognitive.NudgePrefs
import com.hercules.shared.cognitive.StoredBaseline
import com.hercules.shared.model.RecordKind
import com.hercules.shared.model.RecordText
import com.hercules.shared.readiness.CapabilityCategory
import com.hercules.shared.readiness.CapabilityState
import com.hercules.shared.readiness.CapabilityStatus
import com.hercules.shared.readiness.ConfigurationType
import com.hercules.shared.readiness.RETENTION_DAYS
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.MetricKey
import com.hercules.shared.readiness.Calibration
import com.hercules.shared.readiness.Calibrator
import com.hercules.shared.readiness.ScoreSettings
import com.hercules.shared.readiness.SensorCapability
import com.hercules.shared.sensors.SensorConfig
import com.hercules.shared.sensors.SensorKind
import com.hercules.shared.sensors.SensorReport
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.math.roundToInt

/**
 * Setup. Order: is my data coming in (Health Connect, phone sensors, watch) · how it's used (assessments, weights,
 * baselines) · reminders and results · data and the fine print.
 */
@Composable
fun DataScreen(g: MobileGraph, today: String, padding: PaddingValues, onOpenLegal: (LegalDoc) -> Unit = {}, onCleared: () -> Unit = {}, onPlay: () -> Unit = {}, healthConnected: Boolean = false) {
    val ctx = LocalContext.current
    val records by remember { g.repo.observeRecords() }.collectAsStateWithLifecycle(emptyList())
    val lastSync by g.lastWatchSync.collectAsStateWithLifecycle()
    var watches by remember { mutableStateOf<List<String>?>(null) }
    // True when a watch is reachable directly over Bluetooth; false when only through Wi-Fi (Wear OS cloud sync).
    var nearby by remember { mutableStateOf(true) }
    var config by remember { mutableStateOf(SensorConfig.DEFAULT) }
    var report by remember { mutableStateOf<SensorReport?>(null) }
    var caps by remember { mutableStateOf<List<CapabilityState>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<SetupPage?>(null) }
    var signal by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<SignalKind?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { if (signal != null) signal = null else page = null }
    var playedOnce by remember { mutableStateOf(false) }
    // "Played once" on either device completes the step (there are no practice games any more).
    LaunchedEffect(Unit) {
        val played = g.cognitive.store.completedOnDevice(com.hercules.shared.readiness.DeviceKind.PHONE) + g.cognitive.store.completedOnDevice(com.hercules.shared.readiness.DeviceKind.WATCH)
        playedOnce = played > 0
    }
    // Once done, a step stays done (a watch that is briefly out of reach doesn't undo it).
    val watchDone = stickyFlag("start_watch", lastSync != null)
    val hcDone = stickyFlag("start_hc", healthConnected)
    val practiced = stickyFlag("start_btc", playedOnce)
    val showStart = !(hcDone && practiced && watchDone)

    LaunchedEffect(refreshTick) {
        var n = 0
        while (true) {
            runCatching { caps = g.capabilities.states() }
            runCatching { g.sensorState() }.getOrNull()?.let { (c, r) -> config = c; report = r }
            // Checked every pass (cheap), so turning Bluetooth off or walking away shows within seconds.
            n++
            runCatching { Wearable.getNodeClient(ctx).connectedNodes.await() }.getOrElse { emptyList() }.let { nodes ->
                watches = nodes.map { it.displayName }
                nearby = nodes.any { it.isNearby } && bluetoothOn(ctx)
            }
            delay(5_000)
        }
    }

    val active by remember { g.cognitive.store.observeScoreSettings() }.collectAsStateWithLifecycle(ScoreSettings.DEFAULT)
    val reminders = remember(page) { ReminderSettings.load(ctx) }

    fun toggle(kind: SensorKind, on: Boolean) {
        val next = config.with(kind, on, System.currentTimeMillis())
        config = next
        scope.launch { runCatching { g.setSensors(next) } }
    }
    signal?.let { k ->
        SetupSubPage(k.title, "The last 7 days, from every source Hercules reads.", "Sensors & data", padding, onBack = { signal = null }) {
            Column {
                val kinds = watchSensorsFor(k)
                if (kinds.isNotEmpty() && (!watches.isNullOrEmpty() || lastSync != null)) {
                    WatchSwitches(kinds, config, report, ::toggle)
                    VSpace(18.dp)
                }
                SignalHistoryTable(g, k)
            }
        }
        return
    }
    page?.let { p ->
        SetupSubPage(p, padding, onBack = { page = null }) {
            when (p) {
                SetupPage.SENSORS -> {
                    HealthBlock(g, caps.filter { it.capability.category == CapabilityCategory.HEALTH_CONNECT_RECORD }) { scope.launch { delay(1500); refreshTick++ } }
                    VSpace(18.dp)
                    SensorsBlock(g, caps, watches, nearby, lastSync, report, onOpen = { signal = it })
                }
                SetupPage.WEIGHTS -> WeightsBlock(g)
                SetupPage.REMINDERS -> RemindersBlock(g)
                SetupPage.WIDGETS -> WidgetsBlock()
                SetupPage.DATA -> DataBlock(g) { page = null; onCleared() }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = CardGap,
    ) {
        item {
            Column {
                Eyebrow("Data, sensors & scoring")
                VSpace(6.dp)
                Text("Setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
            }
        }
        if (showStart) item { GettingStarted(hcDone, watchDone, practiced, onSetup = { page = SetupPage.SENSORS }, onPlay = onPlay) }
        item {
            Section("Settings") {
                val hcCaps = caps.filter { it.capability.category == CapabilityCategory.HEALTH_CONNECT_RECORD }
                val needs = hcCaps.any { it.capability.required && it.status == CapabilityStatus.PERMISSION_REQUIRED }
                val watchCap = caps.firstOrNull { it.capability == SensorCapability.PAIRED_WATCH }
                val liveSignals = sensorSignalsLive(caps, lastSync, report)
                val hcOk = healthConnected && !needs
                MenuRow(
                    Icons.Rounded.Sensors, "Sensors & data",
                    "$liveSignals of 6 signals live · " + when {
                        !hcOk -> "connect Health Connect"
                        !watches.isNullOrEmpty() && !nearby -> "watch over Wi-Fi"
                        !watches.isNullOrEmpty() -> "watch linked"
                        watchCap?.status == CapabilityStatus.HARDWARE_UNAVAILABLE -> "no watch"
                        else -> "watch offline"
                    },
                    if (hcOk) null else Amber,
                ) { page = SetupPage.SENSORS }
                MenuDivider()
                MenuRow(Icons.Rounded.Tune, "Score settings", if (active.isDefault) "Recommended" else "Your custom settings") { page = SetupPage.WEIGHTS }
                MenuDivider()
                val on = listOf(reminders.morning, reminders.assess, reminders.event, reminders.evening, reminders.reflect, reminders.focus).count { it }
                MenuRow(Icons.Rounded.Notifications, "Reminders", if (on == 0) "All off" else "$on of 6 on") { page = SetupPage.REMINDERS }
                MenuDivider()
                MenuRow(Icons.Rounded.Widgets, "Home screen widgets", "Score, today and the last 10 days") { page = SetupPage.WIDGETS }
                MenuDivider()
                MenuRow(Icons.Rounded.DeleteForever, "Clear data", "Wipe everything Hercules stored on this phone", tint = Color(0xFFFF4D6D)) { page = SetupPage.DATA }
            }
        }
        item { AssessmentsBlock(g, today, onPlay) }
        item { BaselinesBlock(g) }
        item {
            // Bests from any source (Health Connect, watch, games), then the workout ones only the watch records.
            val bests by produceState(emptyList<PersonalBest>(), today, records) { value = runCatching { g.cognitive.personalBests() }.getOrDefault(emptyList()) }
            val covered = setOf(RecordKind.MAX_DAILY_STEPS, RecordKind.LOWEST_RESTING_HR, RecordKind.HIGHEST_HRV_RMSSD)
            val rows = bests.map { Triple(it.label, it.value, shortDate(g.cognitive.dayKeys.startOf(it.dayKey) + 12 * 3_600_000L)) } +
                records.filter { it.kind !in covered }.map { Triple(RecordText.label(it.kind), RecordText.value(it), null) }
            Section("Personal bests") {
                if (rows.isEmpty()) Muted("None yet. Your best score, sleep, steps and Reaction Test appear after your first full day.")
                rows.forEachIndexed { i, (label, value, date) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = Text1)
                            date?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Text2) }
                        }
                        Text(value, fontFamily = Mono, fontSize = 14.sp, color = Cyan)
                    }
                    if (i < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                }
                if (rows.isNotEmpty()) { VSpace(6.dp); Muted("Your bests from the last 10 days.") }
            }
        }
        item {
            Section("About") {
                LegalRow(Icons.Rounded.Info, "About Hercules") { onOpenLegal(LegalDoc.ABOUT) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                LegalRow(Icons.Rounded.PrivacyTip, "Privacy Policy") { onOpenLegal(LegalDoc.PRIVACY) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                LegalRow(Icons.Rounded.Gavel, "Terms & Conditions") { onOpenLegal(LegalDoc.TERMS) }
                VSpace(10.dp)
                Muted("Hercules ${BuildConfig.VERSION_NAME} · all data stays on your devices")
            }
        }
    }
}

/** Setup sub-pages: the deeper settings live one tap away so the main list stays short. */
private enum class SetupPage(val title: String, val blurb: String) {
    SENSORS("Sensors & data", "Every signal Herc uses and where it comes from: Health Connect, this phone, your watch."),
    WEIGHTS("Score settings", "Tune how strongly each part moves your score."),
    REMINDERS("Reminders", "Pick the nudges you want. Nothing more."),
    WIDGETS("Home screen widgets", "Herc on your home screen, without opening the app."),
    DATA("Clear data", "Erase everything and start fresh."),
}

@Composable
private fun SetupSubPage(page: SetupPage, padding: PaddingValues, onBack: () -> Unit, content: @Composable () -> Unit) =
    SetupSubPage(page.title, page.blurb, "Setup", padding, onBack, content)

@Composable
private fun SetupSubPage(title: String, blurb: String, back: String, padding: PaddingValues, onBack: () -> Unit, content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = CardGap,
    ) {
        item {
            Column {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Cyan, modifier = Modifier.size(20.dp))
                    HSpace(8.dp)
                    Eyebrow(back, color = Cyan)
                }
                VSpace(6.dp)
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
                VSpace(4.dp)
                Muted(blurb)
            }
        }
        item { content() }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, hint: String, hintTint: Color? = null, tint: Color = Cyan, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Text1)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = hintTint ?: Text2)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open", tint = Text2, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun MenuDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))


private val Amber = Color(0xFFFFC857)

private val CapabilityStatus.tint: Color
    get() = when (this) {
        CapabilityStatus.AVAILABLE -> Cyan
        CapabilityStatus.NO_RECENT_DATA, CapabilityStatus.TEMPORARILY_UNAVAILABLE -> Amber
        CapabilityStatus.PERMISSION_REQUIRED, CapabilityStatus.INVALID_DATA -> Color(0xFFFF8C4C)
        CapabilityStatus.HARDWARE_UNAVAILABLE, CapabilityStatus.PROVIDER_UNAVAILABLE -> Text2
    }


/** Health Connect: install / permission state, background reading and a manual sync. Per-type status shows in the signal rows. */
@Composable
private fun HealthBlock(g: MobileGraph, caps: List<CapabilityState>, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(caps) { g.healthConnect.state() }
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lastImport by remember { mutableStateOf<Long?>(null) }
    var bgMissing by remember { mutableStateOf(false) }
    var moreMissing by remember { mutableStateOf(false) }
    // Checked whenever this screen comes back to the front: permissions only change in Health Connect's prompt or
    // its settings, and returning from either resumes the screen.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            lastImport = g.healthConnect.lastImportMs()
            bgMissing = g.healthConnect.backgroundMissing()
            moreMissing = g.healthConnect.extrasMissing()
        }
    }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.isNotEmpty()) HealthWorker.runNow(ctx)
        onChanged()
    }
    val missingRequired = caps.filter { it.capability.required && it.status == CapabilityStatus.PERMISSION_REQUIRED }
    val missingOptional = caps.filter { !it.capability.required && it.status == CapabilityStatus.PERMISSION_REQUIRED }
    val allGood = state == HealthConnectState.AVAILABLE && missingRequired.isEmpty()
    Section("Health Connect", trailing = { Pill(if (allGood) "Connected" else "Needs attention", if (allGood) Cyan else Amber) }) {
        when (state) {
            HealthConnectState.AVAILABLE -> {
                Muted("Health Connect brings in sleep, heart and activity from your other apps and devices. Hercules only reads from it and never writes anything back.")
                VSpace(10.dp)
                if (missingRequired.isNotEmpty() || missingOptional.isNotEmpty() || moreMissing) {
                    Button(
                        onClick = { launcher.launch(g.healthConnect.permissionsToRequest()) },
                        modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    ) { Text(if (missingRequired.size == HealthPermissions.required.size) "CONNECT HEALTH CONNECT" else "REVIEW PERMISSIONS", letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold) }
                    VSpace(6.dp)
                    Muted("Declined twice? Android stops asking. Tap Open Health Connect below and allow Hercules there.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                    }) { Text("OPEN HEALTH CONNECT", letterSpacing = 1.2.sp, fontSize = 12.sp) }
                    Box(Modifier.weight(1f))
                    TextButton(enabled = !importing && missingRequired.size < HealthPermissions.required.size, onClick = {
                        importing = true
                        scope.launch {
                            val r = g.healthConnect.import(if (lastImport == null) RETENTION_DAYS else 7)
                            g.refreshAndPushToWatch()
                            message = r.error ?: "Synced ${r.records} records."
                            lastImport = g.healthConnect.lastImportMs()
                            importing = false
                            onChanged()
                        }
                    }) { Text(if (importing) "SYNCING…" else "SYNC NOW", letterSpacing = 1.2.sp, fontSize = 12.sp) }
                }
                Muted(
                    if (bgMissing) "New data comes in each time you open Hercules. To keep it coming while Hercules is closed, allow background reading under Review permissions."
                    else "New data comes in automatically: each time you open Hercules, and about every 15 minutes in the background.",
                )
                VSpace(6.dp)
                LastText("Last sync: " + (lastImport?.let { DateUtils.getRelativeTimeSpanString(it).toString().lowercase() } ?: "never"))
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Cyan) }
            }
            HealthConnectState.NOT_INSTALLED, HealthConnectState.UPDATE_REQUIRED -> {
                Muted(if (state == HealthConnectState.NOT_INSTALLED) "Health Connect isn't installed yet. It pulls sleep, heart and activity from your other apps into Hercules." else "Health Connect needs an update before Hercules can read from it.")
                VSpace(10.dp)
                Button(
                    onClick = {
                        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending")) }
                    },
                    modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                ) { Text(if (state == HealthConnectState.NOT_INSTALLED) "INSTALL HEALTH CONNECT" else "UPDATE HEALTH CONNECT", letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold) }
            }
            HealthConnectState.UNSUPPORTED -> Muted("Health Connect isn't available on this phone. The Hercules watch can still cover sleep and heart rate.")
        }
    }
}

/**
 * Phone-only sleep from screen use (Usage Access): a fallback for nights without watch sleep. Off until allowed; the
 * system page is the only place to grant or revoke it, so the card re-checks whenever the app comes back to the front.
 */
@Composable
private fun ScreenSleepCard(g: MobileGraph) {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(g.screenSleep.allowed()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            val now = g.screenSleep.allowed()
            if (now && !allowed) { g.screenSleep.update(); g.refreshAndPushToWatch() }
            allowed = now
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(6.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Smartphone, null, tint = if (allowed) Cyan else Text2, modifier = Modifier.size(20.dp))
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text("Sleep from screen use", style = MaterialTheme.typography.bodyLarge, color = Text1)
                Muted("For nights without watch sleep.")
            }
            Pill(if (allowed) "On" else "Off", if (allowed) Cyan else Text2)
        }
        VSpace(10.dp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        VSpace(8.dp)
        ScreenFact("How", "Between screen off at night → first screen unlock in the morning = estimated sleep duration.")
        ScreenFact("When", "Only when your watch has no sleep for that night.")
        VSpace(10.dp)
        if (allowed) {
            OutlinedButton(
                onClick = { runCatching { ctx.startActivity(g.screenSleep.settingsIntent()) } },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
            ) { Text("MANAGE IN ANDROID SETTINGS", color = Cyan, letterSpacing = 1.2.sp, fontSize = 12.sp) }
        } else {
            Button(
                onClick = { runCatching { ctx.startActivity(g.screenSleep.settingsIntent()) } },
                modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) { Text("TURN ON", letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold) }
            VSpace(6.dp)
            Muted("In the list that opens, find Hercules and switch it on.")
        }
    }
}

/** One short labelled line: a fixed-width label, then the text. */
@Composable
private fun ScreenFact(label: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label.uppercase(), fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Cyan, modifier = Modifier.width(64.dp).padding(top = 2.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Text2, modifier = Modifier.weight(1f))
    }
}

/**
 * Every signal the score listens to, wherever it comes from: Health Connect, this phone, or the Hercules watch.
 * A signal is live when any of its sources delivered recently. Watch on/off switches only show once a watch is linked.
 */
@Composable
private fun SensorsBlock(
    g: MobileGraph,
    caps: List<CapabilityState>,
    watches: List<String>?,
    nearby: Boolean,
    lastSync: Long?,
    report: SensorReport?,
    onOpen: (SignalKind) -> Unit,
) {
    val now = System.currentTimeMillis()
    // Newest reading per signal, from any source.
    val latest by androidx.compose.runtime.produceState(emptyMap<SignalKind, Latest>(), caps, lastSync) {
        value = SignalKind.entries.mapNotNull { k -> runCatching { latestReading(g, k) }.getOrNull()?.let { k to it } }.toMap()
    }
    fun cap(c: SensorCapability) = caps.firstOrNull { it.capability == c }
    val watchFresh = lastSync != null && now - lastSync < 86_400_000L
    fun watchLive(k: SensorKind) = watchFresh && report?.active?.contains(k) == true
    var luxCount by remember { mutableStateOf<Long?>(null) }
    var lux by remember { mutableStateOf<Double?>(null) }
    val light = cap(SensorCapability.PHONE_LIGHT)
    LaunchedEffect(light) {
        luxCount = g.cognitive.store.countLightSince(now - 86_400_000L)
        lux = g.cognitive.store.latestLight()?.lux
    }

    val signals = listOf(
        Signal(SignalKind.SLEEP, Icons.Rounded.Bedtime, "Sleep", listOf(Src.Hc(cap(SensorCapability.HC_SLEEP)), Src.Watch(watchLive(SensorKind.SLEEP)))),
        Signal(SignalKind.HRV, Icons.Rounded.MonitorHeart, "Heart rate variability", listOf(Src.Hc(cap(SensorCapability.HC_HRV)))),
        Signal(SignalKind.RESTING_HR, Icons.Rounded.Favorite, "Resting heart rate", listOf(Src.Hc(cap(SensorCapability.HC_RESTING_HR)), Src.Watch(watchLive(SensorKind.HEART_RATE)))),
        Signal(SignalKind.STEPS, Icons.AutoMirrored.Rounded.DirectionsWalk, "Steps", listOf(Src.Hc(cap(SensorCapability.HC_STEPS)), Src.Watch(watchLive(SensorKind.STEPS)))),
        Signal(SignalKind.WORKOUTS, Icons.Rounded.FitnessCenter, "Workouts", listOf(Src.Hc(cap(SensorCapability.HC_EXERCISE)))),
        Signal(SignalKind.LIGHT, Icons.Rounded.LightMode, "Ambient light", listOf(Src.Phone(light, lux))),
    )
    val live = signals.count { it.live }
    Section("Signals", trailing = { Text("$live/${signals.size} live", fontFamily = Mono, fontSize = 12.sp, color = if (live > 0) Cyan else Text2) }) {
        Muted("What feeds your score. One live source per signal is enough. Tap a signal to see its last 7 days and its watch settings.")
        VSpace(6.dp)
        signals.forEach { s ->
            SignalRow(s, latest[s.kind]) { onOpen(s.kind) }
        }
        if (light?.status == CapabilityStatus.AVAILABLE || light?.status == CapabilityStatus.NO_RECENT_DATA) {
            Muted("Light: ${(luxCount ?: 0L).grouped()} readings in the last 24 h, taken while Hercules is open plus the odd background check. Gaps never count as darkness.")
        }
        VSpace(14.dp)
        WatchBlock(watches, nearby, lastSync, cap(SensorCapability.PAIRED_WATCH))
        VSpace(10.dp)
        ScreenSleepCard(g)
    }
    VSpace(18.dp)
    // Optional extras: they can only lower the score when clearly off your usual (spec §6.1). Ordinary heart rate is
    // stored but used for nothing, so it isn't listed.
    val extras = listOf(
        Signal(SignalKind.RESPIRATORY, Icons.Rounded.Air, "Respiratory rate", listOf(Src.Hc(cap(SensorCapability.HC_RESPIRATORY_RATE)))),
        Signal(SignalKind.SKIN_TEMP, Icons.Rounded.Thermostat, "Skin temperature", listOf(Src.Hc(cap(SensorCapability.HC_SKIN_TEMPERATURE)))),
        Signal(SignalKind.SPO2, Icons.Rounded.WaterDrop, "Blood oxygen", listOf(Src.Hc(cap(SensorCapability.HC_SPO2)))),
        Signal(SignalKind.GLUCOSE, Icons.Rounded.Bloodtype, "Glucose", listOf(Src.Hc(cap(SensorCapability.HC_BLOOD_GLUCOSE)))),
    )
    val extrasLive = extras.count { it.live }
    Section("Optional extras", trailing = { Text("$extrasLive/${extras.size} live", fontFamily = Mono, fontSize = 12.sp, color = if (extrasLive > 0) Cyan else Text2) }) {
        Muted("These never add points. They lower the score only when clearly off your usual, such as a raised breathing rate or low glucose. Low glucose also shows a warning on Home.")
        VSpace(6.dp)
        extras.forEach { SignalRow(it, latest[it.kind]) { onOpen(it.kind) } }
    }
}

/** How many of the six score signals have a live source right now (same rule as [SensorsBlock]). */
private fun sensorSignalsLive(caps: List<CapabilityState>, lastSync: Long?, report: SensorReport?): Int {
    fun ok(c: SensorCapability) = caps.firstOrNull { it.capability == c }?.status == CapabilityStatus.AVAILABLE
    val fresh = lastSync != null && System.currentTimeMillis() - lastSync < 86_400_000L
    fun w(k: SensorKind) = fresh && report?.active?.contains(k) == true
    return listOf(
        ok(SensorCapability.HC_SLEEP) || w(SensorKind.SLEEP),
        ok(SensorCapability.HC_HRV),
        ok(SensorCapability.HC_RESTING_HR) || w(SensorKind.HEART_RATE),
        ok(SensorCapability.HC_STEPS) || w(SensorKind.STEPS),
        ok(SensorCapability.HC_EXERCISE),
        ok(SensorCapability.PHONE_LIGHT),
    ).count { it }
}

private sealed interface Src {
    val name: String
    val live: Boolean
    val note: String
    data class Hc(val state: CapabilityState?) : Src {
        override val name = "Health Connect"
        override val live get() = state?.status == CapabilityStatus.AVAILABLE
        override val note get() = state?.status?.label ?: "Checking…"
    }
    data class Watch(override val live: Boolean) : Src {
        override val name = "Watch"
        override val note get() = if (live) "Live" else "Off"
    }
    data class Phone(val state: CapabilityState?, val lux: Double?) : Src {
        override val name = "This phone"
        override val live get() = state?.status == CapabilityStatus.AVAILABLE
        override val note get() = if (live && lux != null) "${lux.grouped()} lx" else state?.status?.label ?: "Checking…"
    }
}

private class Signal(val kind: SignalKind, val icon: ImageVector, val name: String, val sources: List<Src>) {
    val live get() = sources.any { it.live }
}

@Composable
private fun SignalRow(s: Signal, latest: Latest?, onClick: (() -> Unit)?) {
    val tint = if (s.live) Cyan else Text2
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(s.icon, null, tint = tint, modifier = Modifier.size(20.dp))
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, color = Text1)
            // Show the live source; otherwise every source with why it's quiet.
            val shown = s.sources.filter { it.live }.ifEmpty { s.sources }
            Text(
                shown.joinToString("  ·  ") { if (it is Src.Watch && !it.live) "Watch" else "${it.name}: ${it.note.lowercase()}" }
                    .replace("Watch: live", "Watch"),
                style = MaterialTheme.typography.bodySmall, color = if (s.live) Cyan else Amber,
            )
            LastText(latest?.let { "Last: ${it.text} · ${stampOf(it.atMs)}" } ?: "No reading yet")
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Text2, modifier = Modifier.size(18.dp))
    }
}

/** The Hercules watch: whether it is linked and delivering. What it records is set on each signal's page. */
@Composable
private fun WatchBlock(watches: List<String>?, nearby: Boolean, lastSync: Long?, cap: CapabilityState?) {
    val overWifi = !watches.isNullOrEmpty() && !nearby
    val linked = !watches.isNullOrEmpty() || lastSync != null
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(6.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Watch, null, tint = if (linked) Cyan else Text2, modifier = Modifier.size(20.dp))
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text(watches?.takeIf { it.isNotEmpty() }?.joinToString() ?: "Hercules watch", style = MaterialTheme.typography.bodyLarge, color = Text1)
                Muted(if (linked) "Its readings show under each signal above. Choose what it records on each signal's page." else "Optional. Adds sleep tracking and the Reaction Test on your wrist.")
            }
            Pill(
                when {
                    cap?.status == CapabilityStatus.HARDWARE_UNAVAILABLE -> "No watch"
                    overWifi -> "Over Wi-Fi"
                    !watches.isNullOrEmpty() -> "Linked"
                    cap != null -> cap.status.label
                    else -> "Offline"
                },
                if (overWifi) Amber else if (!watches.isNullOrEmpty()) Cyan else cap?.status?.tint ?: Text2,
            )
        }
        if (overWifi) {
            VSpace(8.dp)
            Text(
                "Bluetooth is off or the watch is out of range. Data still syncs through Wi-Fi, but it can take longer. Turn Bluetooth on for the quickest sync.",
                style = MaterialTheme.typography.bodySmall, color = Amber,
            )
        }
        // Linked over Bluetooth, yet nothing arrived for a day: on Xiaomi and similar phones, background starts are blocked.
        val stale = lastSync == null || System.currentTimeMillis() - lastSync > 86_400_000L
        if (!watches.isNullOrEmpty() && stale) {
            VSpace(8.dp)
            Text(
                "No data from the Hercules watch app yet. Open Hercules on the watch once, and keep this app open for a minute." +
                    if (android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true))
                        " On this phone, also turn on Autostart for Hercules and set its battery saver to No restrictions (Settings › Apps › Hercules)."
                    else " If it stays empty, allow Hercules to run in the background (Settings › Apps › Hercules › Battery).",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFC857),
            )
        }
    }
}

private fun bluetoothOn(ctx: android.content.Context): Boolean =
    runCatching { ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled != false }.getOrDefault(true)

/** Watch sensors that feed each signal, switched on its page. */
private fun watchSensorsFor(k: SignalKind): List<SensorKind> = when (k) {
    SignalKind.SLEEP -> listOf(SensorKind.SLEEP)
    SignalKind.RESTING_HR -> listOf(SensorKind.HEART_RATE)
    SignalKind.STEPS -> listOf(SensorKind.STEPS, SensorKind.DISTANCE, SensorKind.CALORIES, SensorKind.FLOORS)
    else -> emptyList()
}

/** "On your watch": the sensors behind one signal, each with a switch. Turning one off asks first. */
@Composable
private fun WatchSwitches(kinds: List<SensorKind>, config: SensorConfig, report: SensorReport?, onToggle: (SensorKind, Boolean) -> Unit) {
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
    Section("On your watch") {
        kinds.forEachIndexed { i, k ->
            if (i > 0) MenuDivider()
            val supported = report?.supported?.contains(k) ?: true
            val on = k in config.enabled && supported
            val rec = report?.active?.contains(k) == true
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Watch, null, tint = if (rec) Cyan else Text2, modifier = Modifier.size(18.dp))
                HSpace(12.dp)
                Column(Modifier.weight(1f)) {
                    Text(k.label, style = MaterialTheme.typography.bodyLarge, color = if (supported) Text1 else Text2)
                    Text(
                        when {
                            !supported -> "Not on this watch"
                            rec -> "Recording · ${k.feeds}"
                            on -> "Starting… · ${k.feeds}"
                            else -> "Off · ${k.feeds}"
                        },
                        style = MaterialTheme.typography.bodySmall, color = if (rec) Cyan else Text2,
                    )
                }
                Switch(
                    checked = on, enabled = supported,
                    onCheckedChange = { if (it) onToggle(k, true) else confirmOff = k },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink, checkedTrackColor = Cyan, uncheckedThumbColor = Text2, uncheckedTrackColor = Panel, uncheckedBorderColor = Hairline),
                )
            }
        }
    }
}

/** What switching a sensor off costs, said before it happens. */
private fun sensorOffEffect(k: SensorKind): String = when (k) {
    SensorKind.HEART_RATE -> "The watch stops reading your heart rate. Without Health Connect, resting heart rate drops out of the recovery part of your score."
    SensorKind.SLEEP -> "The watch stops tracking your sleep. Health Connect or your screen use then stand in, and your score's confidence drops."
    SensorKind.STEPS -> "The watch stops counting steps. Health Connect steps still show when available. Steps aren't part of your score."
    else -> "The watch stops recording ${k.label.lowercase()}. Only your daily stats lose it; your score isn't affected."
} + " You can turn it back on at any time."

/**
 * Reaction Test: where today stands (tests played, whether one is in the score now), tests per device,
 * and a way to play. The rules themselves are fixed, so there is nothing to set.
 */
/** Adds Hercules widgets to the home screen. The launcher asks to confirm and lets you place it. */
@Composable
private fun WidgetsBlock() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var manual by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<com.hercules.mobile.widget.Widgets.Kind?>(null) }
    val xiaomi = android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    Section("Widgets") {
        com.hercules.mobile.widget.Widgets.Kind.entries.forEachIndexed { i, k ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k.title, style = MaterialTheme.typography.bodyMedium, color = Text1)
                    Text("${k.size} · ${k.blurb}", style = MaterialTheme.typography.bodySmall, color = Text2)
                }
                HSpace(12.dp)
                androidx.compose.material3.OutlinedButton(
                    onClick = { confirm = k },
                    shape = RoundedCornerShape(6.dp), contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text("ADD", color = Cyan, letterSpacing = 1.2.sp, fontSize = 12.sp) }
            }
        }
        VSpace(4.dp)
        Muted(
            if (manual) "Your launcher can't add widgets from an app. Long-press an empty spot on your home screen, tap Widgets, and find Hercules."
            else "Your phone asks you to confirm and pick a spot. Widgets update whenever your score does.",
        )
        confirm?.let { k ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("Add ${k.title} widget?") },
                text = { Text("A ${k.size} widget on your home screen: ${k.blurb.replaceFirstChar { it.lowercase() }} Your phone then asks where to place it.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        confirm = null
                        tried = true
                        if (!com.hercules.mobile.widget.Widgets.requestPin(ctx, k)) manual = true
                    }) { Text("Add", color = Cyan) }
                },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null }) { Text("Cancel", color = Text2) } },
                containerColor = Panel,
            )
        }
        // Xiaomi's launcher silently refuses until Hercules may add to the home screen ("Home screen shortcuts").
        if (tried && xiaomi) {
            VSpace(10.dp)
            Muted("Nothing appeared? Xiaomi blocks this until you allow it: Other permissions › Home screen shortcuts › Allow. Then tap ADD again.")
            VSpace(8.dp)
            androidx.compose.material3.OutlinedButton(
                onClick = { openXiaomiPermissions(ctx) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp),
            ) { Text("OPEN PERMISSIONS", color = Cyan, letterSpacing = 1.2.sp, fontSize = 12.sp) }
        }
    }
}

/** Xiaomi's own per-app permission page (where "Home screen shortcuts" lives), or the standard app settings. */
private fun openXiaomiPermissions(ctx: android.content.Context) {
    val miui = android.content.Intent("miui.intent.action.APP_PERM_EDITOR")
        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
        .putExtra("extra_pkgname", ctx.packageName)
    val plain = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", ctx.packageName, null))
    runCatching { ctx.startActivity(miui) }.recoverCatching { ctx.startActivity(plain) }
}

@Composable
private fun AssessmentsBlock(g: MobileGraph, today: String, onPlay: () -> Unit) {
    val dk = g.cognitive.dayKeys
    val sessions by remember(today) { g.cognitive.store.observeSessionsFrom(dk.startOf(today)) }.collectAsStateWithLifecycle(emptyList())
    val todays = sessions.filter { it.dayKey == today && it.scored }
    var totals by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(sessions.size) {
        totals = runCatching {
            g.cognitive.store.completedOnDevice(com.hercules.shared.readiness.DeviceKind.PHONE) to
                g.cognitive.store.completedOnDevice(com.hercules.shared.readiness.DeviceKind.WATCH)
        }.getOrDefault(0 to 0)
    }
    val n = todays.size
    val now = System.currentTimeMillis()
    val last = todays.maxByOrNull { it.endMs }
    val counting = todays.filter { now - it.endMs in 0..com.hercules.shared.readiness.GAME_FRESH_MS }.maxByOrNull { it.endMs }
    Section("Reaction Test", trailing = {
        Pill(if (counting != null) "In your score" else "Not in score", if (counting != null) Cyan else Text2)
    }) {
        Text(
            if (counting != null) "Counting until ${clockOf(counting.endMs + com.hercules.shared.readiness.GAME_FRESH_MS)}"
            else "3 minutes to measure how sharp you are right now",
            style = MaterialTheme.typography.bodyLarge, color = Text1,
        )
        VSpace(12.dp)
        Row(Modifier.fillMaxWidth()) {
            LabeledValue("Today", "$n", Modifier.weight(1f))
            LabeledValue("Last score", last?.cognitiveScore?.toString() ?: "--", Modifier.weight(1f), color = if (last != null) Cyan else Text1)
            LabeledValue("All time", "${totals.first + totals.second}", Modifier.weight(1f))
        }
        last?.let {
            VSpace(4.dp)
            LastText("Last test: ${clockOf(it.endMs)} on your ${it.deviceKind.label.lowercase()}")
            Muted("All time: ${totals.first} on the phone, ${totals.second} on the watch.")
        }
        VSpace(12.dp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        VSpace(8.dp)
        ScreenFact("Play", "Tap the moment Herc appears, 45 times. Phone or watch.")
        ScreenFact("Counts", "For 2 hours afterwards it makes up 60% of your score: the one direct measure of how you're doing.")
        ScreenFact("Score", "50 is your usual speed. From 5 tests, each is ranked against your own last 10 days.")
        if (counting == null) {
            VSpace(12.dp)
            Button(
                onClick = onPlay, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) { Text("TEST NOW", letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun TuningBlock(g: MobileGraph) {
    val cal by remember { g.cognitive.store.observeCalibration() }.collectAsStateWithLifecycle(Calibration())
    Section("Personal tuning") {
        Muted("After ${Calibrator.MIN_SESSIONS} Reaction Tests, Hercules learns when your body clock peaks and how much each recovery signal matters for you, using your tests from the last 10 days.")
        VSpace(10.dp)
        Row(Modifier.fillMaxWidth()) {
            LabeledValue("Tests", "${minOf(cal.sessions, Calibrator.MIN_SESSIONS)} / ${Calibrator.MIN_SESSIONS}", Modifier.weight(1f))
            LabeledValue("Peak", cal.peakHour?.let { "%02d:%02d".format(UiLocale, it.toInt(), ((it % 1) * 60).roundToInt()) } ?: "--", Modifier.weight(1f))
            LabeledValue("Status", if (cal.fitted) "Tuned" else "Learning", Modifier.weight(1f))
        }
        if (cal.fitted) {
            VSpace(8.dp)
            Muted("Weights: HRV %.2f · resting HR %.2f · temperature %.2f · body clock %.2f×".format(UiLocale, cal.hrvWeight, cal.restingHrWeight, cal.temperatureWeight, cal.amplitude))
        }
    }
}

/**
 * Score settings (spec §2.9): sliders that scale the model's own parameters, a confirmed reset, and a one-time
 * notice when older percentage weights were dropped. Any non-default setting costs 5 confidence points.
 */
@Composable
private fun WeightsBlock(g: MobileGraph) {
    val scope = rememberCoroutineScope()
    val active by remember { g.cognitive.store.observeScoreSettings() }.collectAsStateWithLifecycle(ScoreSettings.DEFAULT)
    var draft by remember { mutableStateOf(ScoreSettings.DEFAULT) }
    LaunchedEffect(active) { draft = active }
    var confirmReset by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val notice by produceState(false) { value = g.cognitive.store.takeWeightsResetNotice() }
    val changed = draft != active

    if (notice) {
        Section("Your old weights were reset") {
            Muted("The score now works differently: sleep and your body clock predict alertness, recovery signals can lower it, and a recent Reaction Test is blended in. Percentage weights no longer apply, so you start from the recommended settings.")
        }
        VSpace(18.dp)
    }
    ModelFormula()
    VSpace(18.dp)
    Section("Settings", trailing = { Pill(if (active.isDefault) "Recommended" else "Custom", if (active.isDefault) Cyan else Brand) }) {
        Muted("Each slider turns one part of the formula up or down; the model itself stays the same. 1.0× is the research default. Any change lowers confidence by 5 points, since the settings are no longer the tested ones.")
        VSpace(12.dp)
        @Composable
        fun row(label: String, symbol: String, hint: String, value: Double, range: ClosedFloatingPointRange<Float>, steps: Int, text: (Double) -> String, set: (Double) -> ScoreSettings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Text1)
                HSpace(8.dp)
                Text(subscripts(symbol), fontFamily = Mono, fontSize = 12.sp, color = Cyan, modifier = Modifier.weight(1f))
                Text(text(value), fontFamily = Mono, fontSize = 14.sp, color = Text1)
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Text2)
            Slider(
                value = value.toFloat(), onValueChange = { draft = set((it * 10).roundToInt() / 10.0); saved = null }, valueRange = range, steps = steps,
                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Hairline, activeTickColor = Ink, inactiveTickColor = Hairline),
            )
        }
        val x = { v: Double -> "%.1f×".format(UiLocale, v) }
        row("Sleep influence", "k_s", "How hard short sleep and long days pull the score down. 1.5× makes a rough night count half again as much; 0.5× softens it.", draft.sleepInfluence, 0.5f..1.5f, 9, x) { draft.copy(sleepInfluence = it) }
        row("Body clock strength", "κ", "How much the time of day matters: your afternoon high and early-morning low. 0× ignores the clock.", draft.bodyClock, 0f..2f, 19, x) { draft.copy(bodyClock = it) }
        row("HRV sensitivity", "w_hrv", "How much an HRV below your usual lowers the score. 0× ignores HRV.", draft.hrv, 0f..2f, 19, x) { draft.copy(hrv = it) }
        row("Resting heart rate sensitivity", "w_rhr", "How much a resting heart rate above your usual lowers the score.", draft.restingHr, 0f..2f, 19, x) { draft.copy(restingHr = it) }
        row("Temperature sensitivity", "w_temp", "How much skin temperature above your usual (often a sign of illness) lowers the score.", draft.temperature, 0f..2f, 19, x) { draft.copy(temperature = it) }
        row("Reaction Test weight", "g", "How much a test from the last 2 hours counts. 0% leaves tests out of the score; 80% lets the test nearly decide it.", draft.gameWeight, 0f..0.8f, 7, { "${(it * 100).roundToInt()}%" }) { draft.copy(gameWeight = it) }
        VSpace(6.dp)
        Eyebrow("Optional sensors", color = Text1)
        VSpace(2.dp)
        Muted("Used only when they send data. Like the others, they can only lower the score.")
        VSpace(8.dp)
        row("Glucose sensitivity", "w_glu", "How much glucose below your usual lowers the score. A low under 3.9 mmol/L always counts.", draft.glucose, 0f..2f, 19, x) { draft.copy(glucose = it) }
        row("Blood oxygen sensitivity", "w_spo2", "How much overnight blood oxygen below your usual lowers the score.", draft.oxygen, 0f..2f, 19, x) { draft.copy(oxygen = it) }
        row("Breathing rate sensitivity", "w_resp", "How much a breathing rate above your usual lowers the score.", draft.breathing, 0f..2f, 19, x) { draft.copy(breathing = it) }
        VSpace(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = changed,
                onClick = { scope.launch { g.cognitive.saveSettings(draft); g.pushToWatch(); saved = "Saved. Today's score is updated; earlier days keep their settings." } },
                modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) { Text("SAVE", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                enabled = !active.isDefault || !draft.isDefault,
                onClick = { confirmReset = true },
                modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Hairline),
            ) { Text("RESET", letterSpacing = 1.5.sp, color = Text1) }
        }
        saved?.let { VSpace(6.dp); Muted(it) }
    }
    VSpace(18.dp)
    TuningBlock(g)
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Panel,
            title = { Text("Reset to defaults?", color = Text1) },
            text = { Text("Every slider goes back to 1.0× and the Reaction Test weight to 60%. Today's score is recalculated; earlier days keep their settings.", color = Text2) },
            confirmButton = {
                TextButton(onClick = { scope.launch { g.cognitive.restoreDefaultSettings(); g.pushToWatch(); confirmReset = false } }) { Text("RESET", letterSpacing = 1.sp, color = Cyan) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("CANCEL", letterSpacing = 1.sp, color = Text2) } },
        )
    }
}

/** The model in one panel: the formula, then what each symbol means. The sliders below name the symbol they change. */
/** "k_s" → k with a real subscript s: the monospace font has no subscript letters, so they showed as boxes. */
private fun subscripts(f: String) = androidx.compose.ui.text.buildAnnotatedString {
    var i = 0
    while (i < f.length) {
        if (f[i] == '_' && i + 1 < f.length) {
            pushStyle(androidx.compose.ui.text.SpanStyle(baselineShift = androidx.compose.ui.text.style.BaselineShift.Subscript, fontSize = 10.sp)); append(f[i + 1]); pop()
            i += 2
        } else { append(f[i]); i++ }
    }
}

@Composable
private fun ModelFormula() {
    Section("How your score is made") {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(6.dp)).padding(12.dp),
        ) {
            listOf(
                "Score = g·G + (1 − g)·P·M" to "with a Reaction Test in the last 2 h",
                "Score = P·M" to "otherwise",
                "P = 100 − k_s·4·(lapses − 2)" to "predicted alertness",
                "M = 1 − Σ w_i·max(0, z_i)" to "recovery, kept between 0.7 and 1",
            ).forEach { (f, note) ->
                Text(subscripts(f), fontFamily = Mono, fontSize = 13.sp, color = Text1)
                Text(note, style = MaterialTheme.typography.bodySmall, color = Text2)
                VSpace(6.dp)
            }
        }
        VSpace(10.dp)
        ScreenFact("lapses", "Slow reactions predicted by a published sleep model (McCauley 2013) from your last 10 days of sleep, naps and time awake, with the body clock's daily rise and dip (strength κ).")
        ScreenFact("G", "Your Reaction Test score. 50 is your usual.")
        ScreenFact("z (each sensor)", "How far HRV, resting heart rate, temperature and optional sensors are from your own last 10 days, counted only when worse than usual.")
        ScreenFact("w (each sensor)", "How much each of those counts: 0.05 per step to start, tuned to you after 20 tests.")
        VSpace(4.dp)
        Muted("Clamped to 1–100. Missing data is left out, never counted as zero.")
    }
}

private fun baselineLabel(b: StoredBaseline): String {
    val key = runCatching { MetricKey.valueOf(b.metric) }.getOrNull()
    val name = when (key) {
        MetricKey.BTC_SPEED -> "Reaction Test"
        null -> b.metric
        else -> key.label
    }
    return listOfNotNull(name, b.device?.label, b.window?.label?.lowercase()).joinToString(" · ")
}

private fun baselineValue(b: StoredBaseline): String = when (b.metric) {
    MetricKey.HRV.name -> "${b.value.median.roundToInt()} ms ±${b.value.mad.roundToInt()}"
    MetricKey.RESTING_HR.name -> "${b.value.median.roundToInt()} bpm ±${"%.1f".format(UiLocale, b.value.mad)}"
    MetricKey.STEPS.name -> "${b.value.median.grouped()} ±${b.value.mad.grouped()}"
    MetricKey.BTC_SPEED.name -> "${(1000 / b.value.median).roundToInt()} ms"
    else -> "${"%.2f".format(UiLocale, b.value.median)} ±${"%.2f".format(UiLocale, b.value.mad)}"
}

/** Personal baselines (spec §7): median, spread, how many observations and how personal they already are. */
@Composable
private fun BaselinesBlock(g: MobileGraph) {
    val list by remember { g.cognitive.store.observeBaselines() }.collectAsStateWithLifecycle(emptyList())
    Section("Personal baselines") {
        if (list.isEmpty()) Muted("These appear after your first score.")
        list.forEachIndexed { i, b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(baselineLabel(b), style = MaterialTheme.typography.bodyMedium, color = Text1)
                    Text(
                        "${b.value.sampleCount.grouped()} ${if (b.value.sampleCount == 1) "day" else if (b.metric == MetricKey.BTC_SPEED.name) "tests" else "days"}",
                        style = MaterialTheme.typography.bodySmall, color = Text2,
                    )
                }
                Text(baselineValue(b), fontFamily = Mono, fontSize = 13.sp, color = Cyan)
            }
            if (i < list.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        }
        VSpace(6.dp)
        Muted("Your own average and spread over the last 10 days. A signal is compared once it has 5 days. Each source (watch or phone) and each device's Reaction Test keeps its own baseline. Changing settings never resets them.")
    }
}

/** Clear all local data, with confirmation. */
@Composable
private fun DataBlock(g: MobileGraph, onCleared: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    Section("Local data") {
        Muted("Everything stays on this phone and your watch. Nothing goes to the cloud.")
        VSpace(10.dp)
        OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Color(0xFFFF4D6D).copy(alpha = 0.6f))) {
            Icon(Icons.Rounded.DeleteForever, null, tint = Color(0xFFFF4D6D), modifier = Modifier.size(18.dp))
            HSpace(8.dp)
            Text("CLEAR ALL LOCAL DATA", letterSpacing = 1.2.sp, color = Color(0xFFFF4D6D))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Panel,
            title = { Text("Delete all Hercules data?", color = Text1) },
            text = { Text("Scores, sleep, reaction tests, baselines, Moments and settings on this phone are deleted. Health Connect keeps its own data. This can't be undone.", color = Text2) },
            confirmButton = {
                TextButton(onClick = { scope.launch { g.cognitive.clearAll(); g.pushToWatch(); confirm = false; onCleared() } }) { Text("DELETE", letterSpacing = 1.sp, color = Color(0xFFFF4D6D)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("CANCEL", letterSpacing = 1.sp, color = Cyan) } },
        )
    }
}

@Composable
private fun LegalRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(20.dp))
        HSpace(14.dp)
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Text1, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open", tint = Text2, modifier = Modifier.size(20.dp))
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
    Section("Notifications") {
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
                    Muted("Turn on notifications to get your morning score and bedtime nudge.")
                }
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }) { Text("ALLOW", letterSpacing = 1.2.sp, fontSize = 12.sp) }
            }
            VSpace(8.dp)
        }
        ReminderRow(Icons.Rounded.WbSunny, "Morning score", "Your score and when you'll peak.", prefs.morning) { update(prefs.copy(morning = it)) }
        ReminderRow(Icons.Rounded.Timer, "Reaction Test", "When a quick test would sharpen your score.", prefs.assess) { update(prefs.copy(assess = it)) }
        ReminderRow(Icons.Rounded.Flag, "Moment prep", "The evening before and 2 hours ahead.", prefs.event) { update(prefs.copy(event = it)) }
        ReminderRow(Icons.Rounded.Bedtime, "Bedtime", "An hour before lights out.", prefs.evening) { update(prefs.copy(evening = it)) }
        ReminderRow(Icons.Rounded.RateReview, "After a Moment", "One tap to say how you did.", prefs.reflect) { update(prefs.copy(reflect = it)) }
        ReminderRow(Icons.Rounded.Bolt, "Peak window", "When your sharpest hours begin.", prefs.focus) { update(prefs.copy(focus = it)) }
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
