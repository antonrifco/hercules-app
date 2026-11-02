package com.hercules.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.Verdict
import com.hercules.shared.cognitive.WatchState
import com.hercules.shared.cognitive.WindowKind
import com.hercules.shared.cognitive.formatDuration
import com.hercules.shared.demo.DemoData
import com.hercules.shared.model.MetricType
import com.hercules.shared.sensors.SensorKind
import com.hercules.wear.BuildConfig
import com.hercules.wear.graph
import com.hercules.wear.health.PassiveRegistrar
import com.hercules.wear.health.Permissions
import com.hercules.wear.health.SensorSettings
import com.hercules.wear.sync.SyncStatus
import com.hercules.wear.sync.SyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The watch does three things only:
 *   1. today's readiness (score, band, peak window),
 *   2. what it is tracking (sleep, heart, steps) and whether that data reached the phone,
 *   3. readiness for the big moments planned in the next 7 days.
 * Planning, check-ins and sleep corrections live on the phone.
 */

private val WCyan = Color(0xFF3FE0FF)
private val WText2 = Color(0xFF8B90A8)
private val WLine = Color(0xFF262838)
private val WPanel = Color(0xFF10111B)

/** Brand palette for the stock Wear components (switches, buttons): black, deep panels and cyan, no default purple. */
private val HerculesWearColors = ColorScheme(
    primary = WCyan,
    primaryDim = Color(0xFF2BB8D4),
    primaryContainer = Color(0xFF0D2A33),
    onPrimary = Color.Black,
    onPrimaryContainer = Color.White,
    secondary = WCyan,
    secondaryContainer = WPanel,
    onSecondaryContainer = Color.White,
    surfaceContainerLow = Color(0xFF0B0C14),
    surfaceContainer = WPanel,
    surfaceContainerHigh = Color(0xFF181A28),
    onSurface = Color.White,
    onSurfaceVariant = WText2,
    outline = Color(0xFF3A3D55),
    outlineVariant = WLine,
    background = Color.Black,
    onBackground = Color.White,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Debug only: `adb shell am start -n com.hercules.app/com.hercules.wear.ui.MainActivity --ez demo true`
        // loads the same 14-day demo as the phone, so the watch UI can be checked without pairing.
        if (BuildConfig.DEBUG && savedInstanceState == null && intent.getBooleanExtra("demo", false)) {
            lifecycleScope.launch {
                val g = graph
                DemoData.load(g.repo, g.insights, g.cognitive, System.currentTimeMillis())
                recreate()
            }
        }
        setContent {
            MaterialTheme(colorScheme = HerculesWearColors) {
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

    if (!permissions.canTrack) {
        PermissionScreen(onResult = { permissions = Permissions.state(ctx) })
        return
    }

    LaunchedEffect(Unit) {
        if (!PassiveRegistrar.isEnabled(ctx)) runCatching { PassiveRegistrar.enable(ctx) }
        // While the app is visible: keep summaries fresh and hand tracked data to the phone.
        while (true) {
            runCatching { ctx.graph.insights.refresh() }
            SyncWorker.runNow(ctx)
            delay(60_000)
        }
    }

    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") { HomeScreen(onSensors = { nav.navigate("sensors") }) }
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
            item { PulseRings(Modifier.size(64.dp)) }
            item {
                Text(
                    "Hercules reads your heart and sleep to score your day. Choose \"all the time\" so nights are tracked.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = WText2,
                    modifier = Modifier.padding(horizontal = 8.dp),
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

// ------------------------------------------------------------------ home

@Composable
private fun HomeScreen(onSensors: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val today = remember { g.cognitive.today() }
    val latestHr by remember { g.repo.observeLatest(MetricType.HEART_RATE) }.collectAsStateWithLifecycle(null)
    val summary by remember { g.repo.observeDaily(today) }.collectAsStateWithLifecycle(null)
    val sleep by remember { g.cognitive.store.observeSleep(today) }.collectAsStateWithLifecycle(null)
    val pending by remember { g.repo.observePendingSampleCount() }.collectAsStateWithLifecycle(0L)
    val sync by remember { SyncStatus.flow(ctx) }.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf<WatchState?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            if (n++ % 5 == 0) state = runCatching { g.watchState.current() }.getOrNull()
            now = System.currentTimeMillis()
            delay(60_000L)
        }
    }
    val r = state?.readiness
    val color = r?.band?.color ?: WText2
    val schedule = state?.let { s -> s.upcoming.ifEmpty { listOfNotNull(s.nextEvent) } }.orEmpty()
    // Open on the score, not centred on the second item (the ScalingLazyColumn default).
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val dk = g.cognitive.dayKeys

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The bezel follows the round display edge and frames everything as you scroll.
        Bezel(r?.score, color)
        ScreenScaffold(scrollState = listState) { padding ->
            ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                // 1 · Today
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 18.dp)) {
                        if (r == null) {
                            SleepOrbit(Modifier.size(58.dp))
                            WEyebrow("Calibrating", WCyan)
                            Text(
                                "Sleep with the watch on",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = WText2,
                            )
                        } else {
                            CountUp(r.score)
                            WEyebrow("${r.band.label} today", color)
                        }
                    }
                }
                r?.windows?.firstOrNull { it.kind == WindowKind.PEAK }?.let { peak ->
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Box(Modifier.size(width = 10.dp, height = 3.dp).background(WCyan))
                            Spacer(Modifier.width(6.dp))
                            WEyebrow("Peak ${dk.clock(peak.startMs)}–${dk.clock(peak.endMs)}", WCyan)
                        }
                    }
                }

                // 2 · Tracking
                item { SectionLabel("Tracking") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                        SleepStat(sleep?.let { formatDuration(it.asleepMinutes) } ?: "--")
                        HeartStat(latestHr?.value?.toInt())
                        StepsStat(summary?.steps ?: 0)
                    }
                }
                item { SyncLine(sync, pending, now) }

                // 3 · Planned moments
                item { SectionLabel("Big moments") }
                if (schedule.isEmpty()) {
                    item {
                        Text(
                            "Plan one on your phone",
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = WText2,
                        )
                    }
                }
                items(schedule) { ev -> MomentRow(ev, todayScore = r?.score) }

                item { WAction("Sensors", onSensors) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) {
        Box(Modifier.size(width = 3.dp, height = 9.dp).background(WCyan))
        Spacer(Modifier.width(6.dp))
        WEyebrow(text, Color.White)
    }
}

/** One planned moment: countdown, title, and the readiness forecast for that day as a small ring. */
@Composable
private fun MomentRow(ev: EventStatus, todayScore: Int?) {
    val forecast = ev.projectedScore ?: todayScore.takeIf { ev.daysUntil <= 0 }
    val accent = ev.advice?.verdict?.color ?: forecast?.let { scoreColor(it) } ?: WCyan
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .border(1.dp, WLine, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (ev.daysUntil <= 0) "NOW" else "D-${ev.daysUntil}",
            fontSize = if (ev.daysUntil <= 0) 11.sp else 18.sp,
            fontWeight = if (ev.daysUntil <= 0) FontWeight.Medium else FontWeight.Thin,
            letterSpacing = if (ev.daysUntil <= 0) 1.2.sp else 0.sp,
            color = accent,
            modifier = Modifier.width(34.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(ev.event.title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WEyebrow(ev.advice?.verdict?.label ?: dayTime(ev.event.startMs), accent, center = false)
        }
        Spacer(Modifier.width(6.dp))
        ForecastRing(forecast, accent, Modifier.size(30.dp))
    }
}

@Composable
private fun SyncLine(s: SyncStatus.State?, pending: Long, now: Long) {
    val problem = s?.problem
    val (icon, text, tint) = when {
        problem != null -> Triple(Icons.Rounded.CloudOff, "Phone not reachable · $pending waiting", Color(0xFFFFC857))
        s?.lastOkMs != null -> Triple(Icons.Rounded.CloudDone, "Sent to phone · ${ago(now - s.lastOkMs)}", WText2)
        else -> Triple(Icons.Rounded.CloudDone, if (pending > 0) "$pending readings to send" else "Nothing to send yet", WText2)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 10.sp, color = tint)
    }
}

// ------------------------------------------------------------------ animated illustrations

/** 100 ticks around the edge; they sweep up to the score on open. Without a score a comet circles while calibrating. */
@Composable
private fun Bezel(score: Int?, color: Color) {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(score) { target = (score ?: 0).toFloat() }
    val fill by animateFloatAsState(target, tween(1400, easing = FastOutSlowInEasing), label = "bezel")
    val spin = rememberInfiniteTransition(label = "comet")
    val head by spin.animateFloat(0f, 100f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "head")
    val calibrating = score == null
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = size.minDimension / 2
        for (i in 0..100) {
            val a = Math.toRadians(135.0 + 270.0 * i / 100)
            val major = i % 10 == 0
            val r0 = r - if (major) 9.dp.toPx() else 5.dp.toPx()
            val r1 = r - 1.5.dp.toPx()
            val tick = if (calibrating) {
                val behind = ((head - i) + 100f) % 100f // 0 at the head, growing behind it
                if (behind < 18f) WCyan.copy(alpha = 1f - behind / 18f) else WLine
            } else if (i <= fill) color else WLine
            drawLine(
                tick,
                Offset(c.x + (r0 * cos(a)).toFloat(), c.y + (r0 * sin(a)).toFloat()),
                Offset(c.x + (r1 * cos(a)).toFloat(), c.y + (r1 * sin(a)).toFloat()),
                if (major) 2.dp.toPx() else 1.dp.toPx(),
            )
        }
    }
}

/** The score counts up in step with the bezel. */
@Composable
private fun CountUp(score: Int) {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(score) { target = score.toFloat() }
    val v by animateFloatAsState(target, tween(1400, easing = FastOutSlowInEasing), label = "count")
    Text("${v.roundToInt()}", fontSize = 54.sp, fontWeight = FontWeight.Thin, color = Color.White, letterSpacing = (-2).sp)
}

/** Breathing crescent moon with twinkling stars: "waiting for a night of data". */
@Composable
private fun SleepOrbit(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "orbit")
    val breath by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "twinkle")
    val orbit by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * 0.26f * breath
        drawCircle(WCyan.copy(alpha = 0.10f), radius = r * 1.9f, center = c)
        drawCircle(WCyan, radius = r, center = c)
        drawCircle(Color.Black, radius = r * 0.86f, center = Offset(c.x + r * 0.45f, c.y - r * 0.32f))
        listOf(0f, 120f, 240f).forEachIndexed { i, base ->
            val a = Math.toRadians((base + orbit).toDouble())
            val d = size.minDimension * 0.44f
            val phase = (twinkle + i / 3f) % 1f
            val alpha = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
            drawCircle(Color.White.copy(alpha = 0.25f + 0.75f * alpha), radius = 1.6.dp.toPx(), center = Offset(c.x + (d * cos(a)).toFloat(), c.y + (d * sin(a)).toFloat()))
        }
    }
}

/** Heart with rings expanding outward, used on the permission screen. */
@Composable
private fun PulseRings(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "rings")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "p")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (k in 0..1) {
                val q = (p + k * 0.5f) % 1f
                drawCircle(WCyan.copy(alpha = (1f - q) * 0.6f), radius = size.minDimension * (0.2f + 0.3f * q), style = Stroke(1.5.dp.toPx()))
            }
        }
        BeatingIcon(Icons.Rounded.Favorite, bpm = 64, size = 22)
    }
}

/** An icon that beats like a heart at [bpm]. */
@Composable
private fun BeatingIcon(icon: ImageVector, bpm: Int, size: Int, tint: Color = WCyan) {
    val period = (60_000 / bpm.coerceIn(40, 180))
    val t = rememberInfiniteTransition(label = "beat")
    val s by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = period
                1f at 0
                1.22f at (period * 0.12f).toInt()
                1f at (period * 0.28f).toInt()
                1.1f at (period * 0.38f).toInt()
                1f at (period * 0.55f).toInt()
            },
        ),
        label = "scale",
    )
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size.dp).graphicsLayer { scaleX = s; scaleY = s })
}

@Composable
private fun HeartStat(bpm: Int?) {
    StatColumn(bpm?.toString() ?: "--", "bpm") { BeatingIcon(Icons.Rounded.Favorite, bpm ?: 60, 15) }
}

@Composable
private fun SleepStat(value: String) {
    val t = rememberInfiniteTransition(label = "moon")
    val a by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "glow")
    val tilt by t.animateFloat(-8f, 8f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "tilt")
    StatColumn(value, "sleep") {
        Icon(Icons.Rounded.Bedtime, contentDescription = null, tint = WCyan.copy(alpha = a), modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = tilt })
    }
}

@Composable
private fun StepsStat(steps: Long) {
    val t = rememberInfiniteTransition(label = "walk")
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse), label = "bob")
    StatColumn("$steps", "steps") {
        Icon(
            Icons.AutoMirrored.Rounded.DirectionsWalk,
            contentDescription = null,
            tint = WCyan,
            modifier = Modifier.size(15.dp).graphicsLayer { translationY = -bob * 2.dp.toPx(); rotationZ = (bob - 0.5f) * 6f },
        )
    }
}

@Composable
private fun StatColumn(value: String, label: String, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { icon() }
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White)
        Text(label.uppercase(), fontSize = 8.sp, letterSpacing = 1.sp, color = WText2)
    }
}

/** Forecast readiness for the moment's day; the arc fills in when shown. */
@Composable
private fun ForecastRing(score: Int?, color: Color, modifier: Modifier) {
    var target by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(score) { target = (score ?: 0) / 100f }
    val sweep by animateFloatAsState(target, tween(1100, easing = FastOutSlowInEasing), label = "ring")
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 2.5.dp.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(WLine, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(color, -90f, 360f * sweep, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Text(score?.toString() ?: "–", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color.White)
    }
}

// ------------------------------------------------------------------ sensors

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

// ------------------------------------------------------------------ small parts

@Composable
private fun WAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(top = 10.dp).border(1.dp, WLine, RoundedCornerShape(50)).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label.uppercase(), fontSize = 11.sp, letterSpacing = 1.5.sp, color = Color.White) }
}

@Composable
private fun WEyebrow(text: String, color: Color = WText2, center: Boolean = true) {
    Text(
        text.uppercase(),
        fontSize = 9.sp,
        letterSpacing = 1.4.sp,
        color = color,
        fontWeight = FontWeight.Medium,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun dayTime(ms: Long): String = SimpleDateFormat("EEE HH:mm", Locale.ENGLISH).format(Date(ms))

private fun ago(ms: Long): String {
    val min = (ms / 60_000L).coerceAtLeast(0)
    return when {
        min < 1 -> "just now"
        min < 60 -> "${min}m ago"
        min < 24 * 60 -> "${min / 60}h ago"
        else -> "${min / (24 * 60)}d ago"
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= ReadinessBand.PRIMA.minScore -> ReadinessBand.PRIMA.color
    score >= ReadinessBand.BAIK.minScore -> ReadinessBand.BAIK.color
    score >= ReadinessBand.CUKUP.minScore -> ReadinessBand.CUKUP.color
    score >= ReadinessBand.RENDAH.minScore -> ReadinessBand.RENDAH.color
    else -> ReadinessBand.TERKURAS.color
}

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

private val SensorKind.icon: ImageVector
    get() = when (this) {
        SensorKind.HEART_RATE -> Icons.Rounded.MonitorHeart
        SensorKind.SLEEP -> Icons.Rounded.Bedtime
        SensorKind.STEPS -> Icons.AutoMirrored.Rounded.DirectionsWalk
        SensorKind.CALORIES -> Icons.Rounded.LocalFireDepartment
        SensorKind.DISTANCE -> Icons.Rounded.Straighten
        SensorKind.FLOORS -> Icons.Rounded.Stairs
    }
