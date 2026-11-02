package com.hercules.wear.ui

import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.hercules.shared.cognitive.ActionKind
import com.hercules.shared.cognitive.CheckIn
import com.hercules.shared.cognitive.Level
import com.hercules.shared.cognitive.RecommendedAction
import com.hercules.shared.cognitive.SleepAdvice
import com.hercules.shared.cognitive.level
import com.hercules.wear.notify.WatchReminderWorker
import kotlinx.coroutines.guava.await
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
internal val HerculesWearColors = ColorScheme(
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
    /** The reminder (or phone hand-off) that opened the app, until the nav host has acted on it. */
    private var launch by mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) launch = intent
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
                AppScaffold { HerculesWearNav(launch) { launch = null } }
            }
        }
    }
}

@Composable
private fun HerculesWearNav(launch: Intent?, onLaunchHandled: () -> Unit) {
    val ctx = LocalContext.current
    var permissions by remember { mutableStateOf(Permissions.state(ctx)) }
    val nav = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<WatchState?>(null) }
    var eventId by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

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
    // One shared view of "today" for every screen, refreshed each minute and after a check-in.
    LaunchedEffect(reload) {
        while (true) {
            state = runCatching { ctx.graph.watchState.current() }.getOrNull() ?: state
            delay(60_000L)
        }
    }
    LaunchedEffect(launch) {
        val i = launch ?: return@LaunchedEffect
        val target = i.getStringExtra(WatchReminderWorker.EXTRA_TARGET) ?: return@LaunchedEffect
        eventId = i.getStringExtra(WatchReminderWorker.EXTRA_EVENT)
        when (target) {
            "CHECK_IN" -> nav.navigate("checkin")
            "EVENT", "REFLECT" -> if (eventId != null) nav.navigate("event")
            "SLEEP" -> nav.navigate("tonight")
        }
        onLaunchHandled()
    }

    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                state,
                onAction = { a ->
                    when (a.kind) {
                        ActionKind.CHECK_IN -> nav.navigate("checkin")
                        ActionKind.PREPARE -> { eventId = a.eventId; nav.navigate("event") }
                        ActionKind.REFLECT -> { eventId = a.eventId; nav.navigate("event") }
                        ActionKind.SLEEP -> nav.navigate("tonight")
                        ActionKind.PLAN_EVENT -> scope.launch { openOnPhone(ctx, "TODAY", null) }
                        ActionKind.FOCUS -> Unit
                    }
                },
                onCheckIn = { nav.navigate("checkin") },
                onTonight = { nav.navigate("tonight") },
                onStatus = { nav.navigate("status") },
                onEvent = { id -> eventId = id; nav.navigate("event") },
            )
        }
        composable("checkin") {
            CheckInScreen(
                existing = state?.checkIn,
                onSave = { e, s -> ctx.graph.watchState.saveCheckIn(e, s); reload++ },
                onDone = { nav.popBackStack() },
            )
        }
        composable("event") { EventScreen(state?.let { st -> (st.upcoming + listOfNotNull(st.nextEvent)).firstOrNull { it.event.id == eventId } }) }
        composable("tonight") { TonightScreen(state?.brief?.sleep, state?.readiness != null) }
        composable("status") { StatusScreen(state, onSensors = { nav.navigate("sensors") }, onPermissions = { permissions = Permissions.state(ctx) }) }
        composable("sensors") { SensorsScreen() }
    }
}

/** Why the watch needs access, in two short lines, before the system asks. */
@Composable
internal fun PermissionScreen(onResult: () -> Unit) {
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
            item { Herc(HercMood.HAPPY, size = 60.dp) }
            item { Text("Allow access", style = MaterialTheme.typography.titleSmall, color = Color.White) }
            item { WhyRow(Icons.Rounded.Favorite, "Heart rate", "to score your recovery") }
            item { WhyRow(Icons.Rounded.Bedtime, "Sleep & motion", "to track your nights") }
            item { WEyebrow("Choose \"all the time\"", WText2) }
            item {
                Button(onClick = { foregroundLauncher.launch(Permissions.foreground()) }, modifier = Modifier.fillMaxWidth()) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun WhyRow(icon: ImageVector, title: String, why: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = WCyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color.White)
            Text(why, fontSize = 10.sp, color = WText2)
        }
    }
}

// ------------------------------------------------------------------ home

private val Level.color: Color
    get() = when (this) {
        Level.GOOD -> Color(0xFF2EE6A0)
        Level.MODERATE -> Color(0xFFFFC857)
        Level.LOW -> Color(0xFFFF8C4C)
    }

/** Glance: score, one word, best focus time, the one next step, then the next event and quick actions. */
@Composable
internal fun HomeScreen(
    state: WatchState?,
    onAction: (RecommendedAction) -> Unit,
    onCheckIn: () -> Unit,
    onTonight: () -> Unit,
    onStatus: () -> Unit,
    onEvent: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val pending by remember { g.repo.observePendingSampleCount() }.collectAsStateWithLifecycle(0L)
    val sync by remember { SyncStatus.flow(ctx) }.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000L) } }
    val r = state?.readiness
    val brief = state?.brief
    val level = r?.band?.level
    val color = level?.color ?: WText2
    val schedule = state?.let { s -> s.upcoming.ifEmpty { listOfNotNull(s.nextEvent) } }.orEmpty()
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val dk = g.cognitive.dayKeys

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Bezel(r?.score, r?.band?.color ?: WText2)
        ScreenScaffold(scrollState = listState) { padding ->
            ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
                // 1 · How am I doing?
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 6.dp)) {
                        if (r == null) {
                            Herc(HercMood.SLEEPING, size = 52.dp, lit = 0f)
                            WEyebrow("No score yet", WCyan)
                        } else {
                            CountUp(r.score)
                            Text(
                                level!!.label.uppercase(), color = color, fontSize = 13.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.semantics { contentDescription = "Readiness ${r.score}, ${level.label}" },
                            )
                        }
                        brief?.focus?.let { w ->
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Timer, null, tint = WCyan, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                WEyebrow(if (brief.focusNow) "Focus now · until ${dk.clock(w.endMs)}" else "Focus ${dk.clock(w.startMs)}–${dk.clock(w.endMs)}", WCyan)
                            }
                        }
                    }
                }
                // 2 · What should I do next?
                item {
                    if (brief != null) ActionButton(brief.action, onAction)
                    else Button(onClick = onCheckIn, modifier = Modifier.fillMaxWidth(), icon = { Icon(Icons.Rounded.Bolt, null) }) { Text("Check in") }
                }
                // 3 · Am I ready for what is coming?
                schedule.firstOrNull()?.let { ev -> item { MomentRow(ev, r?.score, now, onClick = { onEvent(ev.event.id) }) } }
                // Quick actions
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                        QuickAction(Icons.Rounded.Bolt, "Check-in", done = state?.checkIn != null, onClick = onCheckIn)
                        QuickAction(Icons.Rounded.Bedtime, "Tonight", onClick = onTonight)
                        QuickAction(Icons.Rounded.MonitorHeart, "Tracking", warn = sync?.problem != null, onClick = onStatus)
                    }
                }
                if (schedule.size > 1) {
                    item { SectionLabel("Later") }
                    items(schedule.drop(1)) { ev -> MomentRow(ev, r?.score, now, onClick = { onEvent(ev.event.id) }) }
                }
                item { SyncLine(sync, pending, now, onClick = onStatus) }
            }
        }
    }
}

/** The one recommended step: icon, short title, one line of detail. */
@Composable
private fun ActionButton(a: RecommendedAction, onAction: (RecommendedAction) -> Unit) {
    val icon = when (a.kind) {
        ActionKind.CHECK_IN -> Icons.Rounded.Bolt
        ActionKind.PREPARE -> Icons.Rounded.Flag
        ActionKind.REFLECT -> Icons.Rounded.RateReview
        ActionKind.SLEEP -> Icons.Rounded.Bedtime
        ActionKind.PLAN_EVENT -> Icons.Rounded.Event
        ActionKind.FOCUS -> Icons.Rounded.Timer
    }
    val tappable = a.kind != ActionKind.FOCUS
    // "Get ready: Pitch to investors" is two lines on a watch: keep the verb as the title, the subject as the detail.
    val title = a.title.substringBefore(": ")
    val subject = a.title.substringAfter(": ", "")
    val detail = listOf(subject, a.detail.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
    Button(
        onClick = { onAction(a) },
        enabled = tappable,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        icon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
        secondaryLabel = detail?.let { d -> { Text(d, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        colors = if (tappable) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
    ) { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, done: Boolean = false, warn: Boolean = false, onClick: () -> Unit) {
    val tint = when { warn -> Color(0xFFFFC857); done -> WCyan; else -> Color.White }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(WPanel).border(1.dp, if (done) WCyan.copy(alpha = 0.6f) else WLine, CircleShape)
                .clickable(onClick = onClick).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) { Icon(if (done) Icons.Rounded.CheckCircle else icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.height(3.dp))
        Text(label.uppercase(), fontSize = 8.sp, letterSpacing = 0.8.sp, color = WText2, maxLines = 1)
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

/** "3d", "tomorrow", "in 2h 10m": how far away a moment is, in the words people use. */
private fun countdown(ev: EventStatus, now: Long): String = when {
    ev.daysUntil >= 2 -> "${ev.daysUntil} days"
    ev.daysUntil == 1 -> "Tomorrow"
    else -> {
        val m = ((ev.event.startMs - now) / 60_000L).toInt()
        when {
            m >= 60 -> "in ${m / 60}h ${m % 60}m"
            m > 0 -> "in $m min"
            else -> "Now"
        }
    }
}

/** One planned moment: title, countdown, prep progress, and the readiness forecast as a small ring. */
@Composable
private fun MomentRow(ev: EventStatus, todayScore: Int?, now: Long, onClick: () -> Unit) {
    val forecast = ev.advice?.expectedScore ?: ev.projectedScore ?: todayScore.takeIf { ev.daysUntil <= 0 }
    val accent = ev.advice?.verdict?.color ?: forecast?.let { scoreColor(it) } ?: WCyan
    val steps = if (ev.todayTasks.isNotEmpty()) "${ev.doneTaskIds.size}/${ev.todayTasks.size} steps" else null
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(14.dp))
            .border(1.dp, WLine, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ev.event.title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(countdown(ev, now), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = accent, maxLines = 1)
            if (steps != null) Text(steps, fontSize = 10.sp, color = WText2, maxLines = 1)
        }
        Spacer(Modifier.width(6.dp))
        ForecastRing(forecast, accent, Modifier.size(32.dp))
    }
}

/** Is data flowing? One line, amber with a hint when not. Tap for the details. */
@Composable
private fun SyncLine(s: SyncStatus.State?, pending: Long, now: Long, onClick: () -> Unit) {
    val problem = s?.problem
    val (icon, text, tint) = when {
        problem != null -> Triple(Icons.Rounded.CloudOff, "Phone out of reach", Color(0xFFFFC857))
        s?.lastOkMs != null -> Triple(Icons.Rounded.CloudDone, "Synced ${ago(now - s.lastOkMs)}", WText2)
        else -> Triple(Icons.Rounded.CloudDone, if (pending > 0) "Waiting to sync" else "Tracking starts now", WText2)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(6.dp)) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 10.sp, color = tint)
    }
}

// ------------------------------------------------------------------ check-in

/** Two taps: energy, then stress. Big targets, nothing to type. */
@Composable
internal fun CheckInScreen(existing: CheckIn?, onSave: suspend (Int, Int) -> Unit, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var energy by remember { mutableIntStateOf(existing?.energy ?: 3) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    LaunchedEffect(saved) { if (saved) { delay(1400); onDone() } }

    ScreenScaffold(scrollState = listState) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                saved -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Herc(HercMood.HAPPY, size = 76.dp, lit = 0.8f)
                    Text("Saved", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    WEyebrow("Score updated", WText2)
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    WEyebrow(if (step == 0) "Energy · 1 of 2" else "Stress · 2 of 2", WCyan)
                    Text(if (step == 0) "How energetic?" else "How stressed?", fontSize = 14.sp, color = Color.White)
                    Text(if (step == 0) "1 drained · 5 charged" else "1 calm · 5 tense", fontSize = 9.sp, color = WText2)
                    Spacer(Modifier.height(6.dp))
                    RatingGrid(good = { v -> if (step == 0) scoreColor(v * 20) else scoreColor(120 - v * 20) }) { v ->
                        if (step == 0) { energy = v; step = 1 } else scope.launch { onSave(energy, v); saved = true }
                    }
                }
            }
        }
    }
}

/** 3 + 2 big round buttons: comfortable to hit on a small round screen. */
@Composable
private fun RatingGrid(good: (Int) -> Color, onPick: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1..3, 4..5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { v ->
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(good(v).copy(alpha = 0.16f)).border(1.5.dp, good(v), CircleShape)
                            .clickable { onPick(v) }.semantics { contentDescription = "$v of 5" },
                        contentAlignment = Alignment.Center,
                    ) { Text("$v", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ event

@Composable
internal fun EventScreen(ev: EventStatus?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    var phoneNote by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000L) } }
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (ev == null) {
                item { Text("Open the event on your phone to see it here.", fontSize = 12.sp, color = WText2, textAlign = TextAlign.Center) }
                return@ScalingLazyColumn
            }
            val forecast = ev.advice?.expectedScore ?: ev.projectedScore
            val accent = ev.advice?.verdict?.color ?: forecast?.let { scoreColor(it) } ?: WCyan
            item { Text(ev.event.title, style = MaterialTheme.typography.titleSmall, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(countdown(ev, now), fontSize = 20.sp, fontWeight = FontWeight.Light, color = accent)
                        WEyebrow(dayTime(ev.event.startMs), WText2)
                    }
                    forecast?.let { ForecastRing(it, accent, Modifier.size(38.dp)) }
                }
            }
            ev.advice?.let { a -> item { Pill(a.verdict.label, a.verdict.color) } }
            if (ev.todayTasks.isNotEmpty()) {
                item { SectionLabel("Today · ${ev.doneTaskIds.size}/${ev.todayTasks.size} steps") }
                items(ev.todayTasks.take(5)) { t ->
                    val done = t.id in ev.doneTaskIds
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (done) WCyan else WText2, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t.title, fontSize = 12.sp, color = if (done) WText2 else Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        phoneNote = "Opening on phone…"
                        scope.launch { phoneNote = if (openOnPhone(ctx, "EVENT", ev.event.id)) "Check your phone" else "Phone out of reach" }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    icon = { Icon(Icons.Rounded.PhoneAndroid, null, modifier = Modifier.size(18.dp)) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) { Text("Continue on phone", maxLines = 1) }
            }
            phoneNote?.let { n -> item { Text(n, fontSize = 10.sp, color = WText2) } }
        }
    }
}

/** Opens the same screen on the paired phone, so a task begun on the wrist can continue there. */
private suspend fun openOnPhone(ctx: Context, target: String, eventId: String?): Boolean = runCatching {
    val uri = Uri.parse("hercules://open?target=$target" + (eventId?.let { "&event=$it" } ?: ""))
    val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(uri)
    RemoteActivityHelper(ctx).startRemoteActivity(intent).await()
}.isSuccess

// ------------------------------------------------------------------ tonight

@Composable
internal fun TonightScreen(advice: SleepAdvice?, hasScore: Boolean) {
    val listState = rememberScalingLazyListState()
    val dk = LocalContext.current.graph.cognitive.dayKeys
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Herc(HercMood.SLEEPING, size = 44.dp, lit = 0.3f) }
            if (advice == null) {
                item { Text("Sleep plan appears once your day is scored.", fontSize = 12.sp, color = WText2, textAlign = TextAlign.Center) }
                return@ScalingLazyColumn
            }
            item { WEyebrow("Be in bed by", WText2) }
            item { Text(dk.clock(advice.bedtimeMs), fontSize = 36.sp, fontWeight = FontWeight.Thin, color = Color.White) }
            advice.shiftEarlierMinutes?.let { item { Text("Try ${it} min earlier than last night.", fontSize = 12.sp, color = Color.White, textAlign = TextAlign.Center) } }
            advice.tomorrowNote?.let { item { Text(it, fontSize = 11.sp, color = WText2, textAlign = TextAlign.Center) } }
            if (advice.ifShort != null && advice.ifTarget != null) {
                item { SectionLabel("Tomorrow, estimated") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ForecastRing(advice.ifShort, scoreColor(advice.ifShort!!), Modifier.size(38.dp))
                            Text("6h sleep", fontSize = 9.sp, color = WText2)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ForecastRing(advice.ifTarget, scoreColor(advice.ifTarget!!), Modifier.size(38.dp))
                            Text("${advice.targetMinutes / 60}h${if (advice.targetMinutes % 60 > 0) " ${advice.targetMinutes % 60}m" else ""} sleep", fontSize = 9.sp, color = WText2)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ tracking status

/** Is the watch doing its job? Each line says yes, or what to do about it. */
@Composable
internal fun StatusScreen(state: WatchState?, onSensors: () -> Unit, onPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val latestHr by remember { g.repo.observeLatest(MetricType.HEART_RATE) }.collectAsStateWithLifecycle(null)
    val sync by remember { SyncStatus.flow(ctx) }.collectAsStateWithLifecycle()
    val perms = Permissions.state(ctx)
    val tracking = PassiveRegistrar.isEnabled(ctx)
    val now = System.currentTimeMillis()
    val hrFresh = latestHr != null && now - latestHr!!.startMs < 30 * 60_000L
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPermissions() }
    val bg = Permissions.background()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { ListHeader { Text("Tracking") } }
            item {
                StatusLine(
                    ok = tracking && hrFresh, icon = Icons.Rounded.Favorite,
                    title = if (tracking && hrFresh) "Heart rate on" else if (!tracking) "Tracking is off" else "No heart reading yet",
                    hint = when {
                        !tracking -> "Tap Turn on below."
                        !hrFresh -> "Wear the watch snugly."
                        else -> "${latestHr!!.value.toInt()} bpm · ${ago(now - latestHr!!.startMs)}"
                    },
                )
            }
            if (!tracking) item {
                Button(onClick = { scope.launch { runCatching { PassiveRegistrar.enable(ctx) }; onPermissions() } }, modifier = Modifier.fillMaxWidth()) { Text("Turn on") }
            }
            item {
                val mins = state?.sleepMinutes
                StatusLine(
                    ok = mins != null, icon = Icons.Rounded.Bedtime,
                    title = if (mins != null) "Last night ${formatDuration(mins)}" else "No sleep yet",
                    hint = if (mins != null) "Measured by this watch." else "Sleep with the watch on.",
                )
            }
            if (bg != null && !perms.background) {
                item {
                    StatusLine(ok = false, icon = Icons.Rounded.Lock, title = "Nights may be missed", hint = "Allow \"all the time\".")
                }
                item { Button(onClick = { backgroundLauncher.launch(bg) }, modifier = Modifier.fillMaxWidth()) { Text("Fix") } }
            }
            item {
                val problem = sync?.problem
                StatusLine(
                    ok = problem == null && sync?.lastOkMs != null, icon = if (problem == null) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                    title = when {
                        problem != null -> "Phone out of reach"
                        sync?.lastOkMs != null -> "Synced ${ago(now - sync!!.lastOkMs!!)}"
                        else -> "Not synced yet"
                    },
                    hint = if (problem != null) "Bring your phone close and open Hercules." else "Sends to your phone about every 15 min.",
                )
            }
            item { WAction("Sensors", onSensors) }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, icon: ImageVector, title: String, hint: String) {
    val tint = if (ok) WCyan else Color(0xFFFFC857)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, fontSize = 10.sp, color = WText2, maxLines = 2)
        }
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber, null, tint = tint, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text.uppercase(), fontSize = 9.sp, letterSpacing = 1.2.sp, color = color, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
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
    Text("${v.roundToInt()}", fontSize = 46.sp, fontWeight = FontWeight.Thin, color = Color.White, letterSpacing = (-2).sp)
}

/** Breathing crescent moon with twinkling stars: "waiting for a night of data". */
/** Heart with rings expanding outward, used on the permission screen. */
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
