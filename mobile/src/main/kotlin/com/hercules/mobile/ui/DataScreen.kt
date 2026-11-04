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
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
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
import com.hercules.shared.cognitive.NudgePrefs
import com.hercules.shared.cognitive.StoredBaseline
import com.hercules.shared.model.RecordText
import com.hercules.shared.readiness.CapabilityCategory
import com.hercules.shared.readiness.CapabilityState
import com.hercules.shared.readiness.CapabilityStatus
import com.hercules.shared.readiness.ConfigurationType
import com.hercules.shared.readiness.DefaultScoring
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.MetricKey
import com.hercules.shared.readiness.ScoringConfiguration
import com.hercules.shared.readiness.SensorCapability
import com.hercules.shared.readiness.WeightValidation
import com.hercules.shared.readiness.validateWeights
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
fun DataScreen(g: MobileGraph, today: String, padding: PaddingValues, onLoadDemo: () -> Unit, onRemoveDemo: () -> Unit = {}, onOpenLegal: (LegalDoc) -> Unit = {}, onCleared: () -> Unit = {}, onPlay: () -> Unit = {}, healthConnected: Boolean = false) {
    val ctx = LocalContext.current
    val records by remember { g.repo.observeRecords() }.collectAsStateWithLifecycle(emptyList())
    val lastSync by g.lastWatchSync.collectAsStateWithLifecycle()
    var watches by remember { mutableStateOf<List<String>?>(null) }
    var config by remember { mutableStateOf(SensorConfig.DEFAULT) }
    var report by remember { mutableStateOf<SensorReport?>(null) }
    var caps by remember { mutableStateOf<List<CapabilityState>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<SetupPage?>(null) }
    androidx.activity.compose.BackHandler(enabled = page != null) { page = null }
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
            if (n++ % 6 == 0) watches = runCatching { Wearable.getNodeClient(ctx).connectedNodes.await().map { it.displayName } }.getOrElse { emptyList() }
            delay(5_000)
        }
    }

    val active by remember { g.cognitive.store.observeActiveConfig() }.collectAsStateWithLifecycle(DefaultScoring.DEFAULT)
    val reminders = remember(page) { ReminderSettings.load(ctx) }

    page?.let { p ->
        SetupSubPage(p, padding, onBack = { page = null }) {
            when (p) {
                SetupPage.SENSORS -> {
                    HealthBlock(g, caps.filter { it.capability.category == CapabilityCategory.HEALTH_CONNECT_RECORD }) { scope.launch { delay(1500); refreshTick++ } }
                    VSpace(18.dp)
                    SensorsBlock(g, caps, watches, lastSync, config, report) { kind, on ->
                        val next = config.with(kind, on, System.currentTimeMillis())
                        config = next
                        scope.launch { runCatching { g.setSensors(next) } }
                    }
                }
                SetupPage.WEIGHTS -> WeightsBlock(g)
                SetupPage.REMINDERS -> RemindersBlock(g)
                SetupPage.DATA -> DataBlock(g) { page = null; onCleared() }
                SetupPage.DEVELOPER -> DeveloperBlock(onLoad = { page = null; onLoadDemo() }, onRemove = { page = null; onRemoveDemo() })
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
                        !watches.isNullOrEmpty() -> "watch linked"
                        watchCap?.status == CapabilityStatus.HARDWARE_UNAVAILABLE -> "no watch"
                        else -> "watch offline"
                    },
                    if (hcOk) null else Amber,
                ) { page = SetupPage.SENSORS }
                MenuDivider()
                MenuRow(Icons.Rounded.Tune, "Modify scoring weights", if (active.type == ConfigurationType.DEFAULT) "Recommended mix" else "Your custom mix") { page = SetupPage.WEIGHTS }
                MenuDivider()
                val on = listOf(reminders.morning, reminders.assess, reminders.event, reminders.evening, reminders.reflect, reminders.focus).count { it }
                MenuRow(Icons.Rounded.Notifications, "Reminders", if (on == 0) "All off" else "$on of 6 on") { page = SetupPage.REMINDERS }
                MenuDivider()
                MenuRow(Icons.Rounded.DeleteForever, "Clear data", "Wipe everything Hercules stored on this phone", tint = Color(0xFFFF4D6D)) { page = SetupPage.DATA }
                if (BuildConfig.DEBUG) {
                    MenuDivider()
                    MenuRow(Icons.Rounded.Code, "Developers", "Demo data for testing") { page = SetupPage.DEVELOPER }
                }
            }
        }
        item { AssessmentsBlock() }
        item { BaselinesBlock(g) }
        item {
            Section("Personal bests") {
                if (records.isEmpty()) Muted("None yet. Keep wearing your watch and they'll show up.")
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
                Muted("Hercules ${BuildConfig.VERSION_NAME} · all data stays on your devices")
            }
        }
    }
}

/** Setup sub-pages: the deeper settings live one tap away so the main list stays short. */
private enum class SetupPage(val title: String, val blurb: String) {
    SENSORS("Sensors & data", "Every signal Herc uses and where it comes from: Health Connect, this phone, your watch."),
    WEIGHTS("Scoring weights", "Decide what drives your score."),
    REMINDERS("Reminders", "Only the nudges you want."),
    DATA("Clear data", "Start over from zero."),
    DEVELOPER("Developers", "Tools for testing. Debug builds only."),
}

@Composable
private fun SetupSubPage(page: SetupPage, padding: PaddingValues, onBack: () -> Unit, content: @Composable () -> Unit) {
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
                    Eyebrow("Setup", color = Cyan)
                }
                VSpace(6.dp)
                Text(page.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
                VSpace(4.dp)
                Muted(page.blurb)
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

@Composable
private fun DeveloperBlock(onLoad: () -> Unit, onRemove: () -> Unit) {
    var confirm by remember { mutableStateOf<Boolean?>(null) } // true = load, false = remove
    Section("Demo data") {
        Muted("Fills the app with three weeks of made-up sleep, scores, Beat the Clock games and two Moments, so every screen has something to show. It's added next to your own data.")
        VSpace(12.dp)
        Button(
            onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
        ) { Text("LOAD DEMO DATA", letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold) }
        VSpace(10.dp)
        OutlinedButton(
            onClick = { confirm = false }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, Color(0xFFFF4D6D).copy(alpha = 0.7f)),
        ) { Text("RESET: REMOVE DEMO DATA", letterSpacing = 1.2.sp, color = Color(0xFFFF4D6D), fontWeight = FontWeight.SemiBold) }
        VSpace(8.dp)
        Muted("Reset deletes only the demo data and rebuilds your history from your own data. To wipe everything, use Clear data.")
    }
    confirm?.let { load ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Panel,
            title = { Text(if (load) "Load demo data?" else "Remove demo data?", color = Text1) },
            text = {
                Text(
                    if (load) "Three weeks of made-up data and two Moments are added next to your own. Scores and history will include them until you reset."
                    else "All demo data is deleted and your scores and history are rebuilt from your own data. Your own records stay.",
                    color = Text2,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; if (load) onLoad() else onRemove() }) {
                    Text(if (load) "LOAD" else "REMOVE", letterSpacing = 1.sp, color = if (load) Cyan else Color(0xFFFF4D6D))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL", letterSpacing = 1.sp, color = Text2) } },
        )
    }
}

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
                Muted("Herc only reads from Health Connect, never writes. What each type is used for is listed below.")
                VSpace(10.dp)
                if (missingRequired.isNotEmpty() || missingOptional.isNotEmpty() || moreMissing) {
                    Button(
                        onClick = { launcher.launch(g.healthConnect.permissionsToRequest()) },
                        modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                    ) { Text(if (missingRequired.size == HealthPermissions.required.size) "CONNECT HEALTH CONNECT" else "REVIEW PERMISSIONS", letterSpacing = 1.3.sp, fontWeight = FontWeight.SemiBold) }
                    VSpace(6.dp)
                    Muted("Said no twice? Android stops asking. Open Health Connect below and allow Hercules there.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        runCatching { ctx.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                    }) { Text("OPEN HEALTH CONNECT", letterSpacing = 1.2.sp, fontSize = 12.sp) }
                    Box(Modifier.weight(1f))
                    TextButton(enabled = !importing && missingRequired.size < HealthPermissions.required.size, onClick = {
                        importing = true
                        scope.launch {
                            val r = g.healthConnect.import(if (lastImport == null) 30 else 7)
                            g.refreshAndPushToWatch()
                            message = r.error ?: "Synced ${r.records} records."
                            lastImport = g.healthConnect.lastImportMs()
                            importing = false
                            onChanged()
                        }
                    }) { Text(if (importing) "SYNCING…" else "SYNC NOW", letterSpacing = 1.2.sp, fontSize = 12.sp) }
                }
                Muted(
                    (message?.let { "$it " } ?: "") +
                        "Syncs automatically when you open Hercules" + (if (bgMissing) "" else " and about every hour in the background") +
                        ". Last sync " + (lastImport?.let { DateUtils.getRelativeTimeSpanString(it).toString().lowercase() } ?: "never") + ".",
                )
                if (bgMissing) {
                    VSpace(4.dp)
                    Muted("Allow background reading under Review permissions to keep syncing while Hercules is closed.")
                }
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

/** The phone's light sensor: whether it exists, and whether recent readings came in. */
/**
 * Every signal the score listens to, wherever it comes from: Health Connect, this phone, or the Hercules watch.
 * A signal is live when any of its sources delivered recently. Watch on/off switches only show once a watch is linked.
 */
@Composable
private fun SensorsBlock(
    g: MobileGraph,
    caps: List<CapabilityState>,
    watches: List<String>?,
    lastSync: Long?,
    config: SensorConfig,
    report: SensorReport?,
    onToggle: (SensorKind, Boolean) -> Unit,
) {
    val now = System.currentTimeMillis()
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
        Signal(Icons.Rounded.Bedtime, "Sleep", listOf(Src.Hc(cap(SensorCapability.HC_SLEEP)), Src.Watch(watchLive(SensorKind.SLEEP)))),
        Signal(Icons.Rounded.MonitorHeart, "Heart rate variability", listOf(Src.Hc(cap(SensorCapability.HC_HRV)))),
        Signal(Icons.Rounded.Favorite, "Resting heart rate", listOf(Src.Hc(cap(SensorCapability.HC_RESTING_HR)), Src.Watch(watchLive(SensorKind.HEART_RATE)))),
        Signal(Icons.AutoMirrored.Rounded.DirectionsWalk, "Steps", listOf(Src.Hc(cap(SensorCapability.HC_STEPS)), Src.Watch(watchLive(SensorKind.STEPS)))),
        Signal(Icons.Rounded.FitnessCenter, "Workouts", listOf(Src.Hc(cap(SensorCapability.HC_EXERCISE)))),
        Signal(Icons.Rounded.LightMode, "Ambient light", listOf(Src.Phone(light, lux))),
    )
    val live = signals.count { it.live }
    Section("Signals", trailing = { Text("$live/${signals.size} live", fontFamily = Mono, fontSize = 12.sp, color = if (live > 0) Cyan else Text2) }) {
        Muted("These make up your score. One live source per signal is enough.")
        VSpace(6.dp)
        signals.forEach { s ->
            SignalRow(s, onClick = null)
        }
        if (light?.status == CapabilityStatus.AVAILABLE || light?.status == CapabilityStatus.NO_RECENT_DATA) {
            Muted("Light: ${luxCount ?: 0} readings in the last 24 h, taken while Hercules is open plus the odd background check. Gaps never count as darkness.")
        }
        VSpace(14.dp)
        WatchBlock(watches, lastSync, cap(SensorCapability.PAIRED_WATCH), config, report, onToggle)
    }
    VSpace(18.dp)
    // Optional extras: they can only lower the score when clearly off your usual (spec §6.1). Ordinary heart rate is
    // stored but used for nothing, so it isn't listed.
    val extras = listOf(
        Signal(Icons.Rounded.Air, "Respiratory rate", listOf(Src.Hc(cap(SensorCapability.HC_RESPIRATORY_RATE)))),
        Signal(Icons.Rounded.Thermostat, "Skin temperature", listOf(Src.Hc(cap(SensorCapability.HC_SKIN_TEMPERATURE)))),
        Signal(Icons.Rounded.WaterDrop, "Blood oxygen", listOf(Src.Hc(cap(SensorCapability.HC_SPO2)))),
        Signal(Icons.Rounded.Bloodtype, "Glucose", listOf(Src.Hc(cap(SensorCapability.HC_BLOOD_GLUCOSE)))),
    )
    val extrasLive = extras.count { it.live }
    Section("Optional extras", trailing = { Text("$extrasLive/${extras.size} live", fontFamily = Mono, fontSize = 12.sp, color = if (extrasLive > 0) Cyan else Text2) }) {
        Muted("Never add points. They only lower the score when clearly off your usual, like a raised breathing rate or a glucose low. Low glucose also shows a warning on Home.")
        VSpace(6.dp)
        extras.forEach { SignalRow(it, onClick = null) }
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
        override val note get() = if (live && lux != null) "${lux.roundToInt()} lx" else state?.status?.label ?: "Checking…"
    }
}

private class Signal(val icon: ImageVector, val name: String, val sources: List<Src>) {
    val live get() = sources.any { it.live }
}

@Composable
private fun SignalRow(s: Signal, onClick: (() -> Unit)?) {
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
        }
    }
}

/** The Hercules watch: link state, and (only once linked) what it records. Turning a sensor off asks first. */
@Composable
private fun WatchBlock(watches: List<String>?, lastSync: Long?, cap: CapabilityState?, config: SensorConfig, report: SensorReport?, onToggle: (SensorKind, Boolean) -> Unit) {
    val linked = !watches.isNullOrEmpty() || lastSync != null
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
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Panel).border(1.dp, Hairline, RoundedCornerShape(6.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Watch, null, tint = if (linked) Cyan else Text2, modifier = Modifier.size(20.dp))
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text(watches?.takeIf { it.isNotEmpty() }?.joinToString() ?: "Hercules watch", style = MaterialTheme.typography.bodyLarge, color = Text1)
                Muted(
                    if (linked) "Last data " + (lastSync?.let { DateUtils.getRelativeTimeSpanString(it).toString().lowercase() } ?: "never")
                    else "Optional. Adds sleep tracking and Beat the Clock on your wrist.",
                )
            }
            Pill(
                when {
                    cap?.status == CapabilityStatus.HARDWARE_UNAVAILABLE -> "No watch"
                    !watches.isNullOrEmpty() -> "Linked"
                    cap != null -> cap.status.label
                    else -> "Offline"
                },
                if (!watches.isNullOrEmpty()) Cyan else cap?.status?.tint ?: Text2,
            )
        }
        if (linked) {
            VSpace(10.dp)
            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
            VSpace(4.dp)
            SensorKind.entries.forEach { k ->
                val supported = report?.supported?.contains(k) ?: true
                val on = k in config.enabled && supported
                val rec = report?.active?.contains(k) == true
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(k.label, style = MaterialTheme.typography.bodyMedium, color = if (supported) Text1 else Text2)
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
}

/** What switching a sensor off costs, said before it happens. */
private fun sensorOffEffect(k: SensorKind): String = when (k) {
    SensorKind.HEART_RATE -> "The watch stops reading your heart rate. Without Health Connect, Cardiovascular Readiness may drop out of your score."
    SensorKind.SLEEP -> "The watch stops tracking your sleep. Without another sleep source in Health Connect, most days you won't get a score."
    SensorKind.STEPS -> "The watch stops counting steps. Health Connect steps are still used when available."
    else -> "The watch stops recording ${k.label.lowercase()}. Only your daily stats lose it; your score is not affected."
} + " You can turn it back on at any time."

/** Assessment settings: the usual wake time (used when no sleep was recorded) and the fixed game rules. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssessmentsBlock() {
    Section("Beat the Clock") {
        Muted("Nothing to set. Play any time, as often as you like; today's score is the median of your games. Each game is 3 rounds of 15 to 30 seconds, getting longer each round. Your first game is scored against a typical player; after 3 games it is all about your own normal. Phone and watch use the same rules.")
    }
}

/**
 * Scoring weights (spec §3): the active configuration, five editable weights that must add up to 100%, and a
 * confirmed reset to the recommended defaults. Saving creates a new configuration; past scores keep theirs.
 */
@Composable
private fun WeightsBlock(g: MobileGraph) {
    val scope = rememberCoroutineScope()
    val active by remember { g.cognitive.store.observeActiveConfig() }.collectAsStateWithLifecycle(DefaultScoring.DEFAULT)
    val draft = remember { mutableStateMapOf<Dimension, Int>() }
    LaunchedEffect(active) { Dimension.entries.forEach { draft[it] = (active.weight(it) * 100).roundToInt() } }
    var confirmReset by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val weights = Dimension.entries.associateWith { (draft[it] ?: 0) / 100.0 }
    val validation = validateWeights(weights)
    val total = draft.values.sum()
    val changed = Dimension.entries.any { (draft[it] ?: 0) != (active.weight(it) * 100).roundToInt() }

    Section("Your mix", trailing = { Pill(if (active.type == ConfigurationType.DEFAULT) "Recommended" else "Custom", if (active.type == ConfigurationType.DEFAULT) Cyan else Brand) }) {
        Muted("Your mix, your call. Set how much each part counts; Herc keeps the math behind it fixed.")
        VSpace(10.dp)
        Dimension.entries.forEach { d ->
            val v = draft[d] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(d.icon, null, tint = if (v > 0) Cyan else Text2, modifier = Modifier.size(16.dp))
                HSpace(8.dp)
                Text(d.label, style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
                Text("$v%", fontFamily = Mono, fontSize = 14.sp, color = if (v > 0) Text1 else Text2, modifier = Modifier.width(48.dp))
            }
            Slider(
                value = v.toFloat(), onValueChange = { draft[d] = it.roundToInt(); saved = null }, valueRange = 0f..100f, steps = 19,
                colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Hairline, activeTickColor = Ink, inactiveTickColor = Hairline),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Total $total%", fontFamily = Mono, fontSize = 14.sp, color = if (total == 100) Cyan else Color(0xFFFF8C4C), modifier = Modifier.weight(1f))
            if (validation is WeightValidation.Invalid) Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFFF8C4C), modifier = Modifier.size(16.dp))
        }
        (validation as? WeightValidation.Invalid)?.let { Text(it.reason, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8C4C)) }
        if (validation is WeightValidation.Valid && draft[Dimension.SLEEP] == 0) {
            Muted("Heads up: at 0%, last night's sleep won't count toward your score.")
        }
        VSpace(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = changed && validation is WeightValidation.Valid,
                onClick = { scope.launch { g.cognitive.saveWeights(weights); g.pushToWatch(); saved = "Locked in. Today's score is updated; earlier days keep their weights." } },
                modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) { Text("SAVE", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) }
            OutlinedButton(
                enabled = active.differsFromDefault || changed,
                onClick = { confirmReset = true },
                modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Hairline),
            ) { Text("RESET", letterSpacing = 1.5.sp, color = Text1) }
        }
        saved?.let { VSpace(6.dp); Muted(it) }
        VSpace(8.dp)
        Muted(
            "Active: ${if (active.type == ConfigurationType.DEFAULT) "recommended" else "custom"} · v${active.version}" +
                (if (active.updatedAtMs > 0) " · updated " + DateUtils.getRelativeTimeSpanString(active.updatedAtMs).toString().lowercase() else ""),
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = Panel,
            title = { Text("Reset to recommended weights?", color = Text1) },
            text = {
                val mix = DefaultScoring.DEFAULT.weights.entries.joinToString(", ") { (d, w) -> "${d.short} ${(w * 100).roundToInt()}%" }
                Text("$mix. Today's score is recalculated; earlier days keep the weights they were calculated with.", color = Text2)
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { g.cognitive.restoreDefaultWeights(); g.pushToWatch(); confirmReset = false } }) { Text("RESET", letterSpacing = 1.sp, color = Cyan) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("CANCEL", letterSpacing = 1.sp, color = Text2) } },
        )
    }
}

private fun baselineLabel(b: StoredBaseline): String {
    val key = runCatching { MetricKey.valueOf(b.metric) }.getOrNull()
    val name = when (key) {
        MetricKey.BTC_ACCURACY -> "Beat the Clock"
        null -> b.metric
        else -> key.label
    }
    return listOfNotNull(name, b.device?.label, b.window?.label?.lowercase()).joinToString(" · ")
}

private fun baselineValue(b: StoredBaseline): String = when (b.metric) {
    MetricKey.HRV.name -> "${b.value.median.roundToInt()} ms ±${b.value.mad.roundToInt()}"
    MetricKey.RESTING_HR.name -> "${b.value.median.roundToInt()} bpm ±${"%.1f".format(b.value.mad)}"
    MetricKey.STEPS.name -> "${b.value.median.roundToInt()} ±${b.value.mad.roundToInt()}"
    MetricKey.BTC_ACCURACY.name -> "${"%.1f".format(b.value.median * 100)}% error"
    else -> "${"%.2f".format(b.value.median)} ±${"%.2f".format(b.value.mad)}"
}

/** Personal baselines (spec §7): median, spread, how many observations and how personal they already are. */
@Composable
private fun BaselinesBlock(g: MobileGraph) {
    val list by remember { g.cognitive.store.observeBaselines() }.collectAsStateWithLifecycle(emptyList())
    Section("Personal baselines") {
        if (list.isEmpty()) Muted("They show up after your first score.")
        list.forEachIndexed { i, b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(baselineLabel(b), style = MaterialTheme.typography.bodyMedium, color = Text1)
                    Text(
                        "${b.value.sampleCount} ${if (b.value.sampleCount == 1) "day" else "days"} · ${(b.value.personalShare * 100).roundToInt()}% personal",
                        style = MaterialTheme.typography.bodySmall, color = Text2,
                    )
                }
                Text(baselineValue(b), fontFamily = Mono, fontSize = 13.sp, color = Cyan)
            }
            if (i < list.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        }
        VSpace(6.dp)
        Muted("Reference values for the first 7 days, then more of your own history each day (80% after 3 weeks). Beat the Clock baselines are separate per device and window. Changing weights never resets them.")
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
            text = { Text("Scores, sleep, Beat the Clock runs, baselines, Moments and settings on this phone are deleted. Health Connect keeps its own data. This can't be undone.", color = Text2) },
            confirmButton = {
                TextButton(onClick = { scope.launch { g.cognitive.clearAll(); g.pushToWatch(); confirm = false; onCleared() } }) { Text("DELETE", letterSpacing = 1.sp, color = Color(0xFFFF4D6D)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("CANCEL", letterSpacing = 1.sp, color = Cyan) } },
        )
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
        ReminderRow(Icons.Rounded.WbSunny, "Morning score", "Your cognitive state and when you'll peak.", prefs.morning) { update(prefs.copy(morning = it)) }
        ReminderRow(Icons.Rounded.Timer, "Beat the Clock", "When a window opens to test your edge.", prefs.assess) { update(prefs.copy(assess = it)) }
        ReminderRow(Icons.Rounded.Flag, "Moment prep", "The evening before, and 2 hours ahead.", prefs.event) { update(prefs.copy(event = it)) }
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
