package com.hercules.mobile.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hercules.mobile.BuildConfig
import com.hercules.mobile.MobileGraph
import com.hercules.mobile.graph
import com.hercules.shared.cognitive.DailyBrief
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.demo.DemoData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val label: String) { TODAY("Today"), PLAN("Plan"), DATA("Setup") }

class MainActivity : ComponentActivity() {
    /** The reminder that opened (or re-opened) the app, until the screen has acted on it. */
    private var launch by mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launch = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen() // system splash: the mark on the brand background
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val coldStart = savedInstanceState == null
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
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var openEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var legal by rememberSaveable { mutableStateOf<LegalDoc?>(null) }
    var todayRequest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(launch) {
        val i = launch ?: return@LaunchedEffect
        legal = null
        val target = i.getStringExtra(com.hercules.mobile.notify.Notifier.EXTRA_TARGET) ?: i.data?.getQueryParameter("target") ?: return@LaunchedEffect
        val ev = i.getStringExtra(com.hercules.mobile.notify.Notifier.EXTRA_EVENT) ?: i.data?.getQueryParameter("event")
        when (target) {
            "EVENT", "REFLECT" -> if (ev != null) openEventId = ev else tab = Tab.TODAY
            "CHECK_IN" -> { openEventId = null; tab = Tab.TODAY; todayRequest = "checkin" }
            "SLEEP" -> { openEventId = null; tab = Tab.TODAY; todayRequest = "tonight" }
            else -> { openEventId = null; tab = Tab.TODAY }
        }
        onLaunchHandled()
    }
    var guide by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) } // bump to recompute event statuses
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun loadDemo() = scope.launch {
        DemoData.load(g.repo, g.insights, g.cognitive, System.currentTimeMillis())
        g.pushToWatch()
        tick++
        tab = Tab.TODAY
    }

    LaunchedEffect(Unit) {
        if (loadDemoOnStart) loadDemo().join()
        runCatching { g.retention.apply(System.currentTimeMillis()) }
        while (true) {
            g.refreshAndPushToWatch()
            nowMs = System.currentTimeMillis()
            tick++
            delay(5 * 60_000L)
        }
    }

    val dk = g.cognitive.dayKeys
    val today = dk.of(nowMs)
    val store = g.cognitive.store
    val readiness by remember(today) { store.observeReadiness(today) }.collectAsStateWithLifecycle(null)
    val sleep by remember(today) { store.observeSleep(today) }.collectAsStateWithLifecycle(null)
    val sleepWeek by remember(today) { store.observeSleepRange(dk.plusDays(today, -6), today) }.collectAsStateWithLifecycle(emptyList())
    val trend by remember(today) { store.observeReadinessRange(dk.plusDays(today, -13), today) }.collectAsStateWithLifecycle(emptyList())
    val checkIn by remember(today) { store.observeCheckIn(today) }.collectAsStateWithLifecycle(null)
    val events by remember(today) { store.observeEventsFrom(dk.startOf(today)) }.collectAsStateWithLifecycle(emptyList())
    val statuses by produceState(emptyList<EventStatus>(), events, readiness, tick) {
        value = events.map { g.cognitive.eventStatus(it) }
    }

    val reflections by remember { store.observeReflections() }.collectAsStateWithLifecycle(emptyList())
    val brief by produceState<DailyBrief?>(null, readiness, checkIn, sleep, statuses, reflections, nowMs) {
        value = runCatching { g.cognitive.brief(nowMs) }.onFailure { android.util.Log.w("Hercules", "brief failed", it) }.getOrNull()
    }

    val detail = openEventId?.let { id -> statuses.firstOrNull { it.event.id == id } }
    BackHandler(enabled = openEventId != null || legal != null) { if (legal != null) legal = null else openEventId = null }
    // A notification can point at an event that has since been deleted: don't leave an empty page.
    LaunchedEffect(openEventId, detail == null) {
        if (openEventId != null && detail == null) { delay(2_000); if (statuses.none { it.event.id == openEventId }) openEventId = null }
    }

    Scaffold(
        topBar = {
            if (openEventId != null || legal != null) {
                TopAppBar(
                    title = { Eyebrow(if (legal != null) "Setup" else "Event", color = Text1) },
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
                            Tab.TODAY -> Icons.Rounded.Speed
                            Tab.PLAN -> Icons.AutoMirrored.Rounded.EventNote
                            Tab.DATA -> Icons.Rounded.Tune
                        }
                    },
                    label = { it.label },
                    onSelect = { tab = it },
                )
            }
        },
        floatingActionButton = {
            if (openEventId == null && legal == null && tab == Tab.PLAN) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("NEW EVENT", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) },
                    shape = RoundedCornerShape(6.dp),
                    containerColor = Cyan,
                    contentColor = Ink,
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        when {
            legal != null -> LegalScreen(legal!!, inner)
            openEventId != null && detail != null -> EventDetail(
                g, detail, inner,
                onChanged = { tick++ },
                onDeleted = { openEventId = null; tick++ },
            )
            openEventId != null -> Unit // statuses still loading
            tab == Tab.TODAY -> TodayScreen(
                g,
                TodayData(
                    today, readiness, sleep, sleepWeek, trend, checkIn, statuses.nextRelevant(nowMs), statuses.filter { it.daysUntil == 0 },
                    brief, statuses, reflections,
                ),
                nowMs, inner,
                onOpenEvent = { openEventId = it },
                onOpenGuide = { guide = true },
                onOpenSetup = { tab = Tab.DATA },
                request = todayRequest,
                onRequestHandled = { todayRequest = null },
            )
            tab == Tab.PLAN -> PlanScreen(g, checkIn, statuses, inner, onAdd = { adding = true }) { openEventId = it }
            else -> DataScreen(g, today, inner, onLoadDemo = { loadDemo() }, onOpenLegal = { legal = it })
        }
    }

    if (adding) AddEventDialog(g, nowMs) { adding = false; tick++ }
    if (guide) {
        ModalBottomSheet(onDismissRequest = { guide = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
            GuideContent()
        }
    }
}

