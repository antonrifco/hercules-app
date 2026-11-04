package com.hercules.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hercules.mobile.BuildConfig
import com.hercules.mobile.MobileGraph
import com.hercules.mobile.graph
import com.hercules.shared.cognitive.DailyBrief
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.demo.DemoData
import androidx.lifecycle.repeatOnLifecycle
import com.hercules.mobile.health.HealthPermissions
import com.hercules.shared.cognitive.CombinedTask
import com.hercules.shared.cognitive.PlanEvent
import com.hercules.shared.readiness.DefaultScoring
import com.hercules.shared.readiness.LightContext
import com.hercules.shared.readiness.MomentStatus
import com.hercules.shared.readiness.PeakWindowPrediction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val label: String) { HOME("Home"), MOMENTS("Moments"), SETUP("Setup") }

class MainActivity : ComponentActivity() {
    /** The reminder that opened (or re-opened) the app, until the screen has acted on it. */
    private var launch by mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen() // system splash: Herc wakes up (splash_anim)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val coldStart = savedInstanceState == null
        if (coldStart && SplashPlays) {
            // Let the wake-up animation finish before the app draws over it.
            val until = android.os.SystemClock.uptimeMillis() + 900
            splash.setKeepOnScreenCondition { android.os.SystemClock.uptimeMillis() < until }
        }
        if (savedInstanceState == null) launch = intent
        // Debug only: `adb shell am start -n com.hercules.app/com.hercules.mobile.ui.MainActivity --ez demo true`
        val loadDemo = BuildConfig.DEBUG && savedInstanceState == null && intent.getBooleanExtra("demo", false)
        setContent {
            HerculesTheme(isSystemInDarkTheme()) {
                val (onboarded, finishOnboarding) = rememberOnboarded()
                Box {
                    if (onboarded) HerculesApp(loadDemo, launch) { launch = null } else Onboarding(finishOnboarding)
                    LaunchArt(coldStart)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HerculesApp(loadDemoOnStart: Boolean, launch: android.content.Intent?, onLaunchHandled: () -> Unit) {
    val g: MobileGraph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var openEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlanEvent?>(null) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var legal by rememberSaveable { mutableStateOf<LegalDoc?>(null) }
    var todayRequest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(launch) {
        val i = launch ?: return@LaunchedEffect
        legal = null
        val target = i.getStringExtra(com.hercules.mobile.notify.Notifier.EXTRA_TARGET) ?: i.data?.getQueryParameter("target") ?: return@LaunchedEffect
        val ev = i.getStringExtra(com.hercules.mobile.notify.Notifier.EXTRA_EVENT) ?: i.data?.getQueryParameter("event")
        when (target) {
            "EVENT", "REFLECT" -> if (ev != null) openEventId = ev else tab = Tab.HOME
            "ASSESS" -> { openEventId = null; tab = Tab.HOME; playing = true }
            "SLEEP" -> { openEventId = null; tab = Tab.HOME; todayRequest = "tonight" }
            else -> { openEventId = null; tab = Tab.HOME }
        }
        onLaunchHandled()
    }
    var guide by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) } // bump to recompute event statuses
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun loadDemo() = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        DemoData.load(g.repo, g.insights, g.cognitive, System.currentTimeMillis())
        g.pushToWatch()
        tick++
        tab = Tab.HOME
    }

    fun removeDemo() = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        DemoData.remove(g.insights, g.cognitive, System.currentTimeMillis())
        g.pushToWatch()
        tick++
        tab = Tab.HOME
    }

    LaunchedEffect(Unit) {
        if (loadDemoOnStart) loadDemo().join()
        runCatching { g.retention.apply(System.currentTimeMillis()) }
    }
    // While the app is open, every 5 minutes: one ambient-light reading (spec §6, never continuous), a Health Connect
    // sync and a recalculation. It runs at once whenever the app comes to the front, so syncing is automatic;
    // the hourly background job covers the rest, and "Sync now" in Setup is optional.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            while (true) {
                runCatching { g.light.sampleOnce(LightContext.FOREGROUND) }
                g.importHealthAndRefresh()
                nowMs = System.currentTimeMillis()
                tick++
                delay(5 * 60_000L)
            }
        }
    }

    val dk = g.cognitive.dayKeys
    val today = dk.of(nowMs)
    val store = g.cognitive.store
    val evaluation by remember(today) { store.observeEvaluation(today) }.collectAsStateWithLifecycle(null)
    val sleep by remember(today) { store.observeSleep(today) }.collectAsStateWithLifecycle(null)
    val sleepWeek by remember(today) { store.observeSleepRange(dk.plusDays(today, -6), today) }.collectAsStateWithLifecycle(emptyList())
    val trend by remember(today) { store.observeReadinessRange(dk.plusDays(today, -13), today) }.collectAsStateWithLifecycle(emptyList())
    val sessionsToday by remember(today) { store.observeSessionsFrom(dk.startOf(today)) }.collectAsStateWithLifecycle(emptyList())
    val config by remember { store.observeActiveConfig() }.collectAsStateWithLifecycle(DefaultScoring.DEFAULT)
    val events by remember { store.observeEvents() }.collectAsStateWithLifecycle(emptyList())
    val peak by produceState<PeakWindowPrediction?>(null, evaluation, tick) { value = store.latestPeak() }
    val all by produceState(emptyList<EventStatus>(), events, evaluation, peak, tick) {
        value = events.sortedBy { it.startMs }.map { g.cognitive.eventStatus(it, evaluation = evaluation, peak = peak) }
    }
    val statuses = all.filter { it.event.status == MomentStatus.ACTIVE }
    val history = all.filter { it.event.status != MomentStatus.ACTIVE }.sortedByDescending { it.event.startMs }
    val combined by produceState(emptyList<CombinedTask>(), all) { value = runCatching { g.cognitive.combinedTasks(statuses) }.getOrDefault(emptyList()) }
    val healthConnected by produceState(false, tick, tab) {
        value = g.healthConnect.granted().containsAll(HealthPermissions.required)
    }

    val reflections by remember { store.observeReflections() }.collectAsStateWithLifecycle(emptyList())
    val brief by produceState<DailyBrief?>(null, evaluation, sleep, all, reflections, sessionsToday, nowMs) {
        value = runCatching { g.cognitive.brief(nowMs) }.onFailure { android.util.Log.w("Hercules", "brief failed", it) }.getOrNull()
    }

    val detail = openEventId?.let { id -> all.firstOrNull { it.event.id == id } }
    BackHandler(enabled = openEventId != null || legal != null || playing) {
        when {
            playing -> playing = false
            legal != null -> legal = null
            else -> openEventId = null
        }
    }
    // A notification can point at a moment that has since been deleted: don't leave an empty page.
    LaunchedEffect(openEventId, detail == null) {
        if (openEventId != null && detail == null) { delay(2_000); if (all.none { it.event.id == openEventId }) openEventId = null }
    }

    if (playing) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
            BeatTheClockScreen(g, inner) { playing = false; tick++ }
        }
        return
    }

    Scaffold(
        topBar = {
            if (openEventId != null || legal != null) {
                TopAppBar(
                    title = { Eyebrow(if (legal != null) "Setup" else "Moment", color = Text1) },
                    navigationIcon = { IconButton(onClick = { if (legal != null) legal = null else openEventId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Text1) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink),
                )
            }
        },
        bottomBar = {
            if (openEventId == null && legal == null) {
                HerculesNavBar(
                    items = Tab.entries,
                    selected = tab,
                    icon = {
                        when (it) {
                            Tab.HOME -> Icons.Rounded.Speed
                            Tab.MOMENTS -> Icons.AutoMirrored.Rounded.EventNote
                            Tab.SETUP -> Icons.Rounded.Tune
                        }
                    },
                    label = { it.label },
                    onSelect = { tab = it },
                )
            }
        },
        floatingActionButton = {
            if (openEventId == null && legal == null && tab == Tab.MOMENTS) {
                MomentFab(empty = statuses.isEmpty()) { adding = true }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        when {
            legal != null -> LegalScreen(legal!!, inner)
            openEventId != null && detail != null -> EventDetail(
                g, detail, inner,
                onChanged = { tick++ },
                onEdit = { editing = detail.event },
            )
            openEventId != null -> Unit // statuses still loading
            tab == Tab.HOME -> TodayScreen(
                g,
                TodayData(
                    today, evaluation, sleep, sleepWeek, trend, statuses.nextRelevant(nowMs), statuses.filter { it.daysUntil == 0 },
                    brief, all, reflections, peak, sessionsToday, healthConnected,
                ),
                nowMs, inner,
                onOpenEvent = { openEventId = it },
                onOpenGuide = { guide = true },
                onOpenSetup = { tab = Tab.SETUP },
                onPlay = { playing = true },
                request = todayRequest,
                onRequestHandled = { todayRequest = null },
                onSynced = { nowMs = System.currentTimeMillis(); tick++ },
            )
            tab == Tab.MOMENTS -> PlanScreen(g, statuses, history, combined, inner) { openEventId = it }
            else -> DataScreen(g, today, inner, onLoadDemo = { loadDemo() }, onRemoveDemo = { removeDemo() }, onOpenLegal = { legal = it }, onCleared = { tick++; tab = Tab.HOME }, onPlay = { playing = true }, healthConnected = healthConnected)
        }
    }

    if (adding) MomentDialog(g, nowMs) { adding = false; tick++ }
    editing?.let { e -> MomentDialog(g, nowMs, existing = e) { editing = null; tick++ } }
    if (guide) {
        ModalBottomSheet(onDismissRequest = { guide = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
            GuideContent(config.weights)
        }
    }
}
