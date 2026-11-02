package com.hercules.wear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material3.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ProgressIndicatorDefaults
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.Verdict
import com.hercules.shared.cognitive.WatchState
import com.hercules.shared.cognitive.WindowKind
import com.hercules.shared.cognitive.formatDuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.RecordText
import com.hercules.wear.graph
import com.hercules.wear.health.ExerciseService
import com.hercules.wear.health.LiveWorkout
import com.hercules.wear.health.PassiveRegistrar
import com.hercules.wear.health.Permissions
import com.hercules.wear.health.SensorSettings
import com.hercules.shared.sensors.SensorKind
import androidx.wear.compose.material3.SwitchButton
import com.hercules.wear.health.WorkoutKind
import com.hercules.wear.sync.SyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Accent = Color(0xFF3FE0FF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = ColorScheme(primary = Accent, background = Color.Black)) {
                AppScaffold { HerculesWearNav() }
            }
        }
    }
}

@Composable
private fun HerculesWearNav() {
    val ctx = LocalContext.current
    var permissions by remember { mutableStateOf(Permissions.state(ctx)) }
    val nav = rememberSwipeDismissableNavController()
    val workoutPhase by LiveWorkout.phaseFlow.collectAsStateWithLifecycle()

    if (!permissions.canTrack) {
        PermissionScreen(onResult = { permissions = Permissions.state(ctx) })
        return
    }

    LaunchedEffect(Unit) {
        if (!PassiveRegistrar.isEnabled(ctx)) runCatching { PassiveRegistrar.enable(ctx) }
        // While the app is visible: keep summaries fresh and push to the phone.
        while (true) {
            runCatching { ctx.graph.insights.refresh() }
            SyncWorker.runNow(ctx)
            delay(60_000)
        }
    }

    val start = if (workoutPhase == LiveWorkout.Phase.IDLE) "home" else "workout"
    SwipeDismissableNavHost(navController = nav, startDestination = start) {
        composable("home") {
            HomeScreen(
                onWorkout = { nav.navigate("workout") },
                onRecords = { nav.navigate("records") },
                onSensors = { nav.navigate("sensors") },
            )
        }
        composable("workout") { WorkoutScreen() }
        composable("records") { RecordsScreen() }
        composable("sensors") { SensorsScreen() }
    }
}

@Composable
private fun PermissionScreen(onResult: () -> Unit) {
    val ctx = LocalContext.current
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }
    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val bg = Permissions.background()
        if (Permissions.state(ctx).canTrack && bg != null && !Permissions.has(ctx, bg)) {
            backgroundLauncher.launch(bg) // must be a separate prompt ("Allow all the time")
        } else {
            onResult()
        }
    }
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Hercules") } }
            item {
                Text(
                    "Allow heart rate & activity. Choose \"all the time\" so sleep is tracked in the background.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Button(onClick = { foregroundLauncher.launch(Permissions.foreground()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow")
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onWorkout: () -> Unit, onRecords: () -> Unit, onSensors: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val today = remember { g.cognitive.today() }
    val latestHr by remember { g.repo.observeLatest(MetricType.HEART_RATE) }.collectAsStateWithLifecycle(null)
    val summary by remember { g.repo.observeDaily(today) }.collectAsStateWithLifecycle(null)
    val sleep by remember { g.cognitive.store.observeSleep(today) }.collectAsStateWithLifecycle(null)
    var state by remember { mutableStateOf<WatchState?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            state = runCatching { g.watchState.current() }.getOrNull()
            delay(5 * 60_000L)
        }
    }
    val r = state?.readiness
    val color = r?.band?.color ?: WText2
    val listState = rememberScalingLazyListState()
    val dk = g.cognitive.dayKeys

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The bezel follows the round display edge and frames everything as you scroll.
        Bezel(r?.score ?: 0, color)
        ScreenScaffold(scrollState = listState) { padding ->
            ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 6.dp)) {
                        Text(r?.score?.toString() ?: "--", fontSize = 58.sp, fontWeight = FontWeight.Thin, color = Color.White, letterSpacing = (-2).sp)
                        WEyebrow(r?.band?.label ?: "Calibrating", color)
                    }
                }
                item {
                    Text(
                        r?.band?.headline ?: "Wear me to bed tonight",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = WText2,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                }
                r?.windows?.firstOrNull { it.kind == WindowKind.PEAK }?.let { peak ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Box(Modifier.size(width = 10.dp, height = 3.dp).background(WCyan))
                            Spacer(Modifier.width(6.dp))
                            WEyebrow("Peak ${dk.clock(peak.startMs)}–${dk.clock(peak.endMs)}", WCyan)
                        }
                    }
                }
                state?.nextEvent?.let { ev ->
                    item {
                        val accent = ev.advice?.verdict?.color ?: WCyan
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp).border(1.dp, WLine, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(dayLabel(ev.daysUntil), fontSize = 24.sp, fontWeight = FontWeight.Thin, color = accent)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ev.event.title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 2)
                                WEyebrow(ev.advice?.verdict?.label ?: "${dk.clock(ev.event.startMs)} · ${ev.doneTaskIds.size}/${ev.todayTasks.size} tasks", accent)
                            }
                        }
                    }
                }
                r?.suggestions?.drop(1)?.firstOrNull()?.let { tip ->
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp, start = 6.dp, end = 6.dp)) {
                            WEyebrow("Move", WCyan)
                            Text(tip.title, style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Stat(Icons.Rounded.Bedtime, sleep?.let { formatDuration(it.asleepMinutes) } ?: "--")
                        Stat(Icons.Rounded.Favorite, latestHr?.value?.toInt()?.toString() ?: "--")
                        Stat(Icons.AutoMirrored.Rounded.DirectionsWalk, "${summary?.steps ?: 0}")
                    }
                }
                item { WAction("Workout", onWorkout) }
                item { WAction("Personal bests", onRecords) }
                item { WAction("Sensors", onSensors) }
            }
        }
    }
}

/** 100 ticks around the edge of the round screen; lit up to the score. */
@Composable
private fun Bezel(score: Int, color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = size.minDimension / 2
        for (i in 0..100) {
            val a = Math.toRadians(135.0 + 270.0 * i / 100)
            val major = i % 10 == 0
            val r0 = r - if (major) 9.dp.toPx() else 5.dp.toPx()
            val r1 = r - 1.5.dp.toPx()
            drawLine(
                if (i <= score) color else WLine,
                Offset(c.x + (r0 * kotlin.math.cos(a)).toFloat(), c.y + (r0 * kotlin.math.sin(a)).toFloat()),
                Offset(c.x + (r1 * kotlin.math.cos(a)).toFloat(), c.y + (r1 * kotlin.math.sin(a)).toFloat()),
                if (major) 2.dp.toPx() else 1.dp.toPx(),
            )
        }
    }
}

@Composable
private fun WEyebrow(text: String, color: Color = WText2) {
    Text(text.uppercase(), fontSize = 9.sp, letterSpacing = 1.4.sp, color = color, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
}

@Composable
private fun WAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).border(1.dp, WLine, RoundedCornerShape(50)).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label.uppercase(), fontSize = 11.sp, letterSpacing = 1.5.sp, color = Color.White) }
}

@Composable
private fun Stat(icon: ImageVector, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = WCyan, modifier = Modifier.size(14.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White)
    }
}

private fun dayLabel(daysUntil: Int) = if (daysUntil <= 0) "D" else "D-$daysUntil"

private val WCyan = Color(0xFF3FE0FF)
private val WText2 = Color(0xFF8B90A8)
private val WLine = Color(0xFF262838)

private val ReadinessBand.color: Color
    get() = when (this) {
        ReadinessBand.PRIMA -> Color(0xFF2EE6A0)
        ReadinessBand.BAIK -> Color(0xFF9BE35A)
        ReadinessBand.CUKUP -> Color(0xFFFFC857)
        ReadinessBand.RENDAH -> Color(0xFFFF8C4C)
        ReadinessBand.TERKURAS -> Color(0xFFFF4D6D)
    }

private val Verdict.color: Color
    get() = when (this) {
        Verdict.GO -> Color(0xFF2EE6A0)
        Verdict.GO_WITH_PREP -> Color(0xFF9BE35A)
        Verdict.CONSIDER_RESCHEDULE -> Color(0xFFFFC857)
        Verdict.RESCHEDULE -> Color(0xFFFF4D6D)
    }

@Composable
private fun WorkoutScreen() {
    val ctx = LocalContext.current
    val phase by LiveWorkout.phaseFlow.collectAsStateWithLifecycle()
    val hr by LiveWorkout.heartRateFlow.collectAsStateWithLifecycle()
    val startedAt by LiveWorkout.startedAtFlow.collectAsStateWithLifecycle()
    val kind by LiveWorkout.kindFlow.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            when (phase) {
                LiveWorkout.Phase.IDLE -> {
                    item { ListHeader { Text("Start workout") } }
                    items(WorkoutKind.entries.toList()) { k ->
                        Button(onClick = { ExerciseService.start(ctx, k) }, modifier = Modifier.fillMaxWidth()) { Text(k.label) }
                    }
                }
                LiveWorkout.Phase.STARTING -> item { Text("Starting…") }
                LiveWorkout.Phase.ACTIVE -> {
                    item { Text(kind?.label ?: "Workout", style = MaterialTheme.typography.labelMedium) }
                    item { Text(hr?.toInt()?.toString() ?: "--", style = MaterialTheme.typography.displayLarge, color = Accent) }
                    item { Text("bpm", style = MaterialTheme.typography.labelSmall) }
                    item { Text(formatElapsed(now - (startedAt ?: now)), style = MaterialTheme.typography.titleMedium) }
                    item {
                        Button(
                            onClick = { ExerciseService.stop(ctx) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Stop") }
                    }
                }
                LiveWorkout.Phase.RECOVERY -> {
                    item { Text("Cool down", style = MaterialTheme.typography.titleMedium) }
                    item { Text(hr?.toInt()?.toString() ?: "--", style = MaterialTheme.typography.displayLarge) }
                    item {
                        Text(
                            "Measuring HR recovery. Keep still for a minute.",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordsScreen() {
    val ctx = LocalContext.current
    val records by remember { ctx.graph.repo.observeRecords() }.collectAsStateWithLifecycle(emptyList())
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { scope.launch { runCatching { ctx.graph.insights.refresh() } } }

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Personal bests") } }
            if (records.isEmpty()) {
                item { Text("No records yet.", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
            }
            items(records) { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(RecordText.label(r.kind), style = MaterialTheme.typography.labelSmall)
                    Text(RecordText.value(r), style = MaterialTheme.typography.titleMedium, color = Accent)
                    Text(r.dayKey, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SensorsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(SensorSettings.load(ctx)) }
    var supported by remember { mutableStateOf<Set<SensorKind>?>(null) }
    LaunchedEffect(Unit) { supported = runCatching { PassiveRegistrar.supported(ctx) }.getOrDefault(emptySet()) }
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Sensors") } }
            items(SensorKind.entries.toList()) { k ->
                val available = supported?.contains(k) ?: true
                SwitchButton(
                    checked = k in config.enabled && available,
                    onCheckedChange = { on ->
                        config = config.with(k, on, System.currentTimeMillis())
                        scope.launch { runCatching { SensorSettings.set(ctx, k, on) } }
                    },
                    enabled = available,
                    modifier = Modifier.fillMaxWidth(),
                    secondaryLabel = { Text(if (available) k.feeds else "Not on this watch") },
                    icon = { Icon(k.icon, contentDescription = null) },
                ) { Text(k.label) }
            }
        }
    }
}

private val SensorKind.icon: ImageVector
    get() = when (this) {
        SensorKind.HEART_RATE -> Icons.Rounded.MonitorHeart
        SensorKind.SLEEP -> Icons.Rounded.Bedtime
        SensorKind.STEPS -> Icons.AutoMirrored.Rounded.DirectionsWalk
        SensorKind.CALORIES -> Icons.Rounded.LocalFireDepartment
        SensorKind.DISTANCE -> Icons.Rounded.Straighten
        SensorKind.FLOORS -> Icons.Rounded.Stairs
    }
