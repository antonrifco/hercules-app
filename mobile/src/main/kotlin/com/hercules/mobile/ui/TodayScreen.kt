package com.hercules.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.Sync
import com.hercules.mobile.health.HealthConnectImporter
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hercules.mobile.MobileGraph
import com.hercules.shared.cognitive.ActionKind
import com.hercules.shared.cognitive.DailyBrief
import com.hercules.shared.cognitive.Evaluation
import com.hercules.shared.cognitive.EventReflection
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.SleepNight
import com.hercules.shared.cognitive.Suggestion
import com.hercules.shared.cognitive.formatDuration
import com.hercules.shared.cognitive.learningNote
import com.hercules.shared.cognitive.provenance
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.DailySummary
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.HIGH_CONFIDENCE_DAYS
import com.hercules.shared.readiness.MIN_CONFIDENT_DAYS
import com.hercules.shared.readiness.Availability
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.ConfigurationType
import com.hercules.shared.readiness.DimensionScorer
import com.hercules.shared.readiness.PeakResult
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.ReadinessCalculation
import com.hercules.shared.readiness.insufficientDetail
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class TodayData(
    val today: String,
    val evaluation: Evaluation?,
    val sleep: SleepNight?,
    val sleepWeek: List<SleepNight>,
    val trend: List<Readiness>,
    val nextEvent: EventStatus?,
    val todayEvents: List<EventStatus> = emptyList(),
    val brief: DailyBrief? = null,
    val allEvents: List<EventStatus> = emptyList(),
    val reflections: List<EventReflection> = emptyList(),
    val peak: PeakWindowPrediction? = null,
    val sessionsToday: List<CognitiveSession> = emptyList(),
    val healthConnected: Boolean = false,
) {
    val readiness: Readiness? get() = evaluation?.readiness
}

private const val HOUR = 3_600_000L
private val sleepScorer = DimensionScorer()
private val BtcColor = Color(0xFFFFD166)
private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private enum class Sheet { REFLECT, LEARN, WHY }

/** Lighter surface for the sections that should stand out on Home (Tips, Your day). */
private val BrightPanel = Color(0xFF232B40)
/** Lines, tracks and secondary text that stay readable on [BrightPanel]. */
private val BrightRule = Color(0xFF4A5678)
private val BrightSubtle = Color(0xFFB8BED6)

/**
 * How sure the score is, in two plain parts: how much of today's score has data (coverage) and how many days of
 * history back it. Confidence needs both; the hint says what would raise it.
 */
@Composable
private fun ConfidenceExplainer(calc: ReadinessCalculation) {
    val c = calc.confidence.color
    val pct = (calc.coverage * 100).roundToInt()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(BrightPanel).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(c, 8.dp)
            HSpace(8.dp)
            Text(calc.confidence.label, style = MaterialTheme.typography.titleMedium, color = c)
        }
        VSpace(4.dp)
        Text("How much to trust today's number. It rests on two things:", style = MaterialTheme.typography.bodyMedium, color = LocalSubtle.current)
        VSpace(12.dp)
        Text("Coverage · $pct%", style = MaterialTheme.typography.bodyLarge, color = Text1)
        Text(
            "${calc.signalsIn} of ${calc.signalsTotal} signals have data today, carrying $pct% of the score's weight. " +
                "Missing ones are left out, not counted as zero.",
            style = MaterialTheme.typography.bodySmall, color = LocalSubtle.current,
        )
        VSpace(6.dp); ScoreBar(pct, c, 3.dp)
        VSpace(12.dp)
        val days = calc.historyDays
        Text("History · ${if (days == 0) "first day" else "$days ${if (days == 1) "day" else "days"}"}", style = MaterialTheme.typography.bodyLarge, color = Text1)
        Text(
            "Earlier days let Herc compare today with your usual. Medium needs $MIN_CONFIDENT_DAYS days, High $HIGH_CONFIDENCE_DAYS.",
            style = MaterialTheme.typography.bodySmall, color = LocalSubtle.current,
        )
        VSpace(6.dp); ScoreBar((days.coerceAtMost(HIGH_CONFIDENCE_DAYS) * 100) / HIGH_CONFIDENCE_DAYS, c, 3.dp)
        VSpace(12.dp)
        Text("High: 90%+ coverage and $HIGH_CONFIDENCE_DAYS+ days · Medium: 70%+ and $MIN_CONFIDENT_DAYS+ days", style = MaterialTheme.typography.bodySmall, color = LocalSubtle.current)
        calc.confidenceHint?.let { VSpace(8.dp); Text(it, style = MaterialTheme.typography.bodyMedium, color = Text1) }
    }
}

/** A live low-glucose warning. Below 3.0 mmol/L thinking is likely impaired, so it says to treat first. */
@Composable
private fun GlucoseBanner(mmol: Double) {
    val severe = mmol < 3.0
    val c = if (severe) Color(0xFFFF6B6B) else Color(0xFFFFC857)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(BorderStroke(1.dp, c), RoundedCornerShape(10.dp))
            .background(c.copy(alpha = 0.10f)).padding(14.dp),
    ) {
        Text("Glucose ${"%.1f".format(UiLocale, mmol)} mmol/L", style = MaterialTheme.typography.titleMedium, color = c)
        VSpace(4.dp)
        Text(
            if (severe) "Thinking likely impaired. Treat first, then recheck before anything that needs focus."
            else "On the low side. A snack may help before demanding work.",
            style = MaterialTheme.typography.bodyMedium, color = Text1,
        )
    }
}

/**
 * A lifted panel behind a section, alternating with plain ones so a long Home reads in clear blocks.
 * [bright] uses a clearly lighter, cyan-edged surface for the sections that matter most.
 */
@Composable
private fun Banded(bright: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (bright) BrightPanel else Panel)
            .border(BorderStroke(1.dp, if (bright) Cyan.copy(alpha = 0.35f) else Hairline), RoundedCornerShape(10.dp)).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
    ) {
        if (bright) CompositionLocalProvider(LocalRule provides BrightRule, LocalSubtle provides BrightSubtle) { content() }
        else content()
    }
}

/**
 * Home. Order follows the questions people ask, most urgent first: How am I? (score, or what's missing) · What
 * unlocks it? (first week) · What do I do now? (next step, tips, Beat the Clock) · What's coming? (tonight in the
 * evening, the next moment, the peak window, the day) · sleep and the week · today's counts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    g: MobileGraph,
    data: TodayData,
    nowMs: Long,
    padding: PaddingValues,
    onOpenEvent: (String) -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSetup: () -> Unit = {},
    onPlay: () -> Unit = {},
    request: String? = null,
    onRequestHandled: () -> Unit = {},
    onSynced: () -> Unit = {},
) {
    val r = data.readiness
    val brief = data.brief
    val summary by remember(data.today) { g.repo.observeDaily(data.today) }.collectAsStateWithLifecycle(null)
    val lastSync by g.lastWatchSync.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var reflectId by remember { mutableStateOf<String?>(null) }
    val hour = g.cognitive.dayKeys.minuteOfDay(nowMs) / 60
    // Also after midnight: the next step then says "Time to rest" and points here.
    val showTonight = brief?.sleep != null && (hour >= 16 || hour < 4)
    var scrollTo by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var totalsTick by remember { mutableIntStateOf(0) }
    val totals by produceState<HealthConnectImporter.DayTotals?>(null, nowMs, totalsTick) { value = g.healthConnect.todayTotals() }
    val lowGlucose by produceState<Double?>(null, nowMs) { value = g.cognitive.lowGlucose(nowMs) }

    val sections = buildList<Pair<String, @Composable () -> Unit>> {
        add("top" to { TopLine(nowMs, r?.band, onOpenGuide) })
        add("hero" to {
            when {
                brief == null -> Box(Modifier.fillMaxWidth().height(320.dp)) // first brief still computing: no flash
                r != null -> Briefing(r, brief, onWhy = { sheet = Sheet.WHY }) { sheet = Sheet.LEARN }
                else -> InsufficientCard(data.evaluation?.calc, onWhy = { sheet = Sheet.WHY }, onSetup = onOpenSetup, healthConnected = data.healthConnected)
            }
        })
        lowGlucose?.let { mmol -> add("glucose" to { GlucoseBanner(mmol) }) }
        if (brief != null) {
            add("action" to {
                ActionCard(brief.action, r?.band.mood()) { a ->
                    when (a.kind) {
                        ActionKind.ASSESS -> onPlay()
                        ActionKind.PREPARE -> a.eventId?.let(onOpenEvent)
                        ActionKind.REFLECT -> { reflectId = a.eventId; sheet = Sheet.REFLECT }
                        ActionKind.SLEEP -> scrollTo = "tonight"
                        ActionKind.PEAK -> scrollTo = "day"
                    }
                }
            })
        }
        if (r != null && r.suggestions.isNotEmpty()) add("tips" to { Banded(bright = true) { Tips(r.suggestions) } })
        if (brief?.assess != null) add("assess" to { AssessCard(brief, data.sessionsToday, onPlay) })
        if (showTonight) add("tonight" to { TonightCard(brief!!.sleep!!, r) })
        add("peak" to { PeakCard(data.peak, brief?.peak, brief?.peakNow == true, nowMs) })
        add("day" to { Banded(bright = true) { DayStrip(g.cognitive.dayKeys, data, nowMs) } })
        add("sleep" to { SleepBlock(data.sleep, data.sleepWeek, data.today, g) })
        if (data.trend.size >= 2) add("week" to { Banded { WeekBlock(data.trend, data.sleepWeek, g) } })
        add("stats" to {
            TodayStats(summary, totals, syncing) {
                syncing = true
                scope.launch {
                    g.importHealthAndRefresh()
                    totalsTick++
                    syncing = false
                    onSynced()
                }
            }
        })
    }
    LaunchedEffect(request) {
        when (request) {
            "tonight" -> scrollTo = "tonight"
            "assess" -> onPlay()
        }
        if (request != null) onRequestHandled()
    }
    LaunchedEffect(scrollTo) {
        scrollTo?.let { k -> listState.animateScrollToItem(sections.indexOfFirst { it.first == k }.coerceAtLeast(0)) }
        scrollTo = null
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = CardGap,
    ) {
        items(sections, key = { it.first }) { it.second() }
    }

    sheet?.let { which ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Panel) {
            when (which) {
                Sheet.REFLECT -> {
                    val ev = data.allEvents.firstOrNull { it.event.id == reflectId }
                    var rating by remember(reflectId) { mutableStateOf(data.reflections.firstOrNull { it.eventId == reflectId }?.rating) }
                    ReflectContent(ev?.event?.title ?: "Your Moment", r?.score, rating) { v ->
                        rating = v
                        reflectId?.let { id -> scope.launch { g.cognitive.store.saveReflection(EventReflection(id, v, System.currentTimeMillis())) } }
                    }
                }
                Sheet.LEARN -> LearnContent(r)
                Sheet.WHY -> data.evaluation?.calc?.let { Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) { ScoreBreakdown(it) } }
            }
        }
    }
}

/** Beat the Clock today: optional, any time, as often as you like. Shows today's runs and the median that counts. */
@Composable
private fun AssessCard(brief: DailyBrief, sessions: List<CognitiveSession>, onPlay: () -> Unit) {
    val a = brief.assess ?: return
    val runs = sessions.filter { it.valid && !it.practice }.sortedBy { it.endMs }
    val scored = runs.mapNotNull { it.cognitiveScore }
    val median = scored.takeIf { it.isNotEmpty() }?.sorted()?.let { l -> if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2 }
    Section("Beat the Clock", trailing = {
        Text(
            "${runs.size} ${if (runs.size == 1) "game" else "games"} today",
            fontFamily = Mono, fontSize = 12.sp, color = Cyan,
        )
    }) {
        Muted(
            when {
                runs.isEmpty() -> "A 2-minute game that shows how sharp you are. Play any time, as often as you like."
                else -> "Play as often as you like. Today's score is the median of your games."
            },
        )
        if (runs.isNotEmpty()) {
            VSpace(12.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).border(BorderStroke(1.dp, Cyan.copy(alpha = 0.6f)), RoundedCornerShape(6.dp)).padding(10.dp),
                ) {
                    Text("TODAY", fontSize = 10.sp, letterSpacing = 1.sp, color = Text2, maxLines = 1, softWrap = false)
                    VSpace(8.dp)
                    Text(median?.toString() ?: "--", fontFamily = Mono, fontSize = 20.sp, color = Text1)
                    Text(
                        when {
                            median == null -> "not counted"
                            scored.size == 1 -> "1 game"
                            else -> "Median"
                        },
                        style = MaterialTheme.typography.bodySmall, color = Cyan, maxLines = 1,
                    )
                }
                runs.takeLast(3).forEach { s ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).border(BorderStroke(1.dp, Hairline), RoundedCornerShape(6.dp)).padding(10.dp),
                    ) {
                        Text(clockOf(s.endMs), fontSize = 10.sp, letterSpacing = 1.sp, color = Text2)
                        VSpace(8.dp)
                        Text(s.cognitiveScore?.toString() ?: "✓", fontFamily = Mono, fontSize = 20.sp, color = Text1)
                        Text(if (s.deviceKind == com.hercules.shared.readiness.DeviceKind.WATCH) "on watch" else "", style = MaterialTheme.typography.bodySmall, color = Text2, maxLines = 1)
                    }
                }
                repeat(3 - runs.takeLast(3).size) { Box(Modifier.weight(1f)) }
            }
        }
        VSpace(12.dp)
        Button(
            onClick = onPlay, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
        ) {
            Icon(Icons.Rounded.Timer, null, modifier = Modifier.size(18.dp))
            HSpace(8.dp)
            Text(
                "PLAY",
                letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * The full breakdown of a calculation: coverage and confidence, every dimension with its weight, score or why it
 * is missing, the metrics behind it, and the configuration it was calculated with.
 */
@Composable
internal fun ScoreBreakdown(calc: ReadinessCalculation) {
    var open by remember { mutableStateOf<com.hercules.shared.readiness.Dimension?>(null) }
    Eyebrow(if (calc.score != null) "Why this score" else "What's missing")
    VSpace(6.dp)
    Text(calc.score?.toString() ?: "--", fontFamily = Mono, fontSize = 34.sp, color = calc.score?.let { scoreColor(it) } ?: InsufficientColor)
    if (calc.score != null) {
        VSpace(10.dp)
        CompositionLocalProvider(LocalRule provides BrightRule, LocalSubtle provides BrightSubtle) { ConfidenceExplainer(calc) }
    }
    calc.insufficientDetail?.let { VSpace(8.dp); Text(it, style = MaterialTheme.typography.bodyMedium, color = Text1) }
    calc.modifiers.forEach { m ->
        VSpace(6.dp)
        Text("${m.label}: ${m.points} points", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFC857))
    }
    VSpace(14.dp)
    calc.dimensions.forEach { d ->
        val col = d.score?.let { scoreColor(it) } ?: InsufficientColor
        val expanded = open == d.dimension
        Column(
            Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(6.dp))
                .border(BorderStroke(1.dp, if (expanded) col else Hairline), RoundedCornerShape(6.dp))
                .clickable { open = if (expanded) null else d.dimension }.padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(d.dimension.icon, null, tint = col, modifier = Modifier.size(18.dp))
                HSpace(10.dp)
                Column(Modifier.weight(1f)) {
                    Text(d.dimension.label, style = MaterialTheme.typography.bodyLarge, color = Text1)
                    Text(
                        "${(d.weight * 100).roundToInt()}% weight" + if (!d.available) " · ${d.availability.label.lowercase(UiLocale)}" else "",
                        style = MaterialTheme.typography.bodySmall, color = Text2,
                    )
                }
                Text(d.score?.toString() ?: "--", fontFamily = Mono, fontSize = 20.sp, color = if (d.available) Text1 else Text2)
            }
            if (d.score != null) { VSpace(8.dp); ScoreBar(d.score!!, col, 2.dp) }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 10.dp)) {
                    d.metrics.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(m.key.label + (m.value?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = Text1)
                                Text(m.detail, style = MaterialTheme.typography.bodySmall, color = Text2)
                            }
                            HSpace(8.dp)
                            Text(
                                m.score?.toString() ?: if (m.availability == Availability.AVAILABLE) "--" else m.availability.label.lowercase(UiLocale),
                                fontFamily = if (m.score != null) Mono else null, fontSize = if (m.score != null) 15.sp else 11.sp,
                                color = m.score?.let { scoreColor(it) } ?: Text2,
                            )
                        }
                    }
                    VSpace(6.dp)
                    ProvenanceTag(d.dimension.provenance)
                }
            }
        }
    }
    VSpace(8.dp)
    val s = calc.stamp
    Muted(
        (if (s.configType == ConfigurationType.DEFAULT) "Recommended weights" else "Your custom weights") +
            ". Missing data is left out, never counted as zero.",
    )
}

/**
 * The signature view: one line for the whole day. Last night's sleep, the predicted peak focus window, today's
 * Beat the Clock runs and now, on a single time axis. Moments stay on the Moments tab. The canvas draws shapes plus a few numerals;
 * anything wordy is normal layout that wraps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DayStrip(dk: DayKeys, data: TodayData, nowMs: Long) {
    val dayStart = dk.startOf(data.today)
    val start = minOf(data.sleep?.startMs ?: (dayStart - 2 * HOUR), dayStart - HOUR)
    val end = dayStart + 24 * HOUR
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rule = LocalRule.current
    val subtle = LocalSubtle.current
    val tickStyle = TextStyle(color = subtle, fontSize = 10.sp)
    val wake = data.evaluation?.wakeMs
    val peakWindow = data.peak?.takeIf { it.result == PeakResult.PREDICTED }?.window
    val runs = data.sessionsToday.filter { it.valid && !it.practice } // same runs the Beat the Clock card counts

    val tickH = with(density) { measurer.measure("00:00", tickStyle).size.height.toDp() }
    val pinZone = 0.dp // Moments live on the Moments tab only
    val sleepH = 28.dp
    val bandH = 12.dp
    val gap = 8.dp
    val canvasH = pinZone + sleepH + gap + bandH + 6.dp + tickH + 4.dp

    Section("Your day") {
        if (peakWindow != null && wake != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Cyan))
                HSpace(8.dp)
                Text(
                    "Peak focus ${clockOf(peakWindow.startMs(wake))}–${clockOf(peakWindow.endMs(wake))}",
                    style = MaterialTheme.typography.bodyMedium, color = Text1,
                )
            }
        }
        VSpace(12.dp)
        Canvas(Modifier.fillMaxWidth().height(canvasH)) {
            val span = (end - start).toFloat()
            fun x(t: Long) = ((t - start) / span).coerceIn(0f, 1f) * size.width
            val top = pinZone.toPx()
            val sleepPx = sleepH.toPx()
            val bandY = top + sleepPx + gap.toPx()
            val bandPx = bandH.toPx()
            val axisY = bandY + bandPx + 6.dp.toPx()

            var t = dayStart - 6 * HOUR
            while (t <= end) {
                if (t >= start) {
                    val gx = x(t)
                    drawLine(rule.copy(alpha = 0.6f), Offset(gx, top), Offset(gx, axisY), 1f)
                    val txt = measurer.measure(clockOf(t), tickStyle)
                    drawText(txt, topLeft = Offset((gx - txt.size.width / 2f).coerceIn(0f, size.width - txt.size.width), axisY + 2.dp.toPx()))
                }
                t += 6 * HOUR
            }
            val sleep = data.sleep
            if (sleep != null) {
                val x0 = x(sleep.startMs); val x1 = x(sleep.endMs)
                drawRoundRect(Brand.copy(alpha = 0.75f), Offset(x0, top), Size(x1 - x0, sleepPx), CornerRadius(4.dp.toPx()))
                val txt = measurer.measure(formatDuration(sleep.asleepMinutes), tickStyle.copy(color = Text1))
                if (txt.size.width < x1 - x0 - 12.dp.toPx()) drawText(txt, topLeft = Offset(x0 + 6.dp.toPx(), top + (sleepPx - txt.size.height) / 2))
            } else {
                val w = maxOf(x(dayStart + 7 * HOUR), 0f)
                drawRoundRect(rule, Offset(0f, top), Size(w, sleepPx), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                val txt = measurer.measure("No sleep recorded", tickStyle)
                val lx = w - txt.size.width - 8.dp.toPx()
                val nowCrosses = nowMs in start..end && x(nowMs) in (lx - 6.dp.toPx())..(lx + txt.size.width + 6.dp.toPx())
                if (lx > 6.dp.toPx() && !nowCrosses) drawText(txt, topLeft = Offset(lx, top + (sleepPx - txt.size.height) / 2))
            }
            // The predicted peak glows on the band; each Beat the Clock run today is a dot.
            drawRoundRect(rule, Offset(0f, bandY), Size(size.width, bandPx), CornerRadius(3.dp.toPx()))
            if (wake != null && peakWindow != null) {
                val x0 = x(peakWindow.startMs(wake)); val x1 = x(peakWindow.endMs(wake))
                if (x1 > x0) drawRoundRect(Cyan, Offset(x0, bandY), Size(x1 - x0, bandPx), CornerRadius(3.dp.toPx()))
            }
            runs.forEach { r ->
                val rx = x(r.endMs)
                drawCircle(Ink, bandPx / 2 + 2.dp.toPx(), Offset(rx, bandY + bandPx / 2))
                drawCircle(BtcColor, bandPx / 2, Offset(rx, bandY + bandPx / 2))
            }
            if (nowMs in start..end) {
                val nx = x(nowMs)
                drawLine(Ink, Offset(nx, top - 2.dp.toPx()), Offset(nx, axisY), 4.dp.toPx())
                drawLine(NowColor, Offset(nx, top - 2.dp.toPx()), Offset(nx, axisY), 2.dp.toPx())
                drawCircle(NowColor, 4.dp.toPx(), Offset(nx, axisY))
            }
        }
        VSpace(10.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Legend(Brand.copy(alpha = 0.75f), "Sleep")
            if (peakWindow != null) Legend(Cyan, "Peak focus")
            if (runs.isNotEmpty()) Legend(BtcColor, "Beat the Clock")
            Legend(NowColor, "Now")
        }
    }
}

/** A "done" flag that stays done, so first-week hints never come back once completed. */
@Composable
internal fun stickyFlag(key: String, now: Boolean): Boolean {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hercules_ui", android.content.Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(prefs.getBoolean(key, false)) }
    if (now && !saved) { prefs.edit { putBoolean(key, true) }; saved = true }
    return saved || now
}

/** Why the score is still "learning". Short, then done. */
@Composable
private fun LearnContent(r: Readiness?) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Eyebrow("Learning your rhythm")
        VSpace(6.dp)
        Text(r?.learningNote ?: "Herc knows your normal. Fully calibrated.", style = MaterialTheme.typography.headlineSmall, color = Text1)
        VSpace(12.dp)
        Muted("Hercules compares you with your own normal. For the first 7 days it uses reference values; from day 7 your own history counts more each day, up to 80% after 3 weeks.")
    }
}


@Composable
private fun TopLine(nowMs: Long, band: ReadinessBand?, onOpenGuide: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("HERCULES", color = Text1, fontSize = 13.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Medium)
        HSpace(12.dp)
        Box(Modifier.size(width = 18.dp, height = 1.dp).background(Hairline))
        HSpace(12.dp)
        Eyebrow(shortDate(nowMs), modifier = Modifier.weight(1f))
        IconButton(onClick = onOpenGuide) { Icon(Icons.Rounded.Info, "How to read the score", tint = Text2) }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 6.dp).background(color))
        HSpace(6.dp)
        Text(text.uppercase(UiLocale), fontSize = 10.sp, letterSpacing = 1.sp, color = LocalSubtle.current)
    }
}


@Composable
private fun Tips(tips: List<Suggestion>) {
    Section("Tips", trailing = { Eyebrow("general advice") }) {
        tips.take(3).forEachIndexed { i, s ->
            var open by remember(s.title) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d".format(i + 1), fontFamily = Mono, fontSize = 13.sp, color = s.tone.color)
                    HSpace(14.dp)
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, color = Text1, modifier = Modifier.weight(1f))
                    Icon(s.tone.icon, null, tint = s.tone.color, modifier = Modifier.size(18.dp))
                }
                AnimatedVisibility(open) {
                    Text(s.detail, style = MaterialTheme.typography.bodyMedium, color = LocalSubtle.current, modifier = Modifier.padding(start = 34.dp, top = 6.dp))
                }
            }
            if (i < tips.take(3).lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(LocalRule.current))
        }
    }
}


@Composable
private fun SleepBlock(sleep: SleepNight?, week: List<SleepNight>, today: String, g: MobileGraph) {
    val dk = g.cognitive.dayKeys
    var correcting by remember { mutableStateOf(false) }
    Section("Sleep", trailing = {
        TextButton(onClick = { correcting = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Icon(if (sleep == null) Icons.Rounded.Add else Icons.Rounded.Edit, null, modifier = Modifier.size(14.dp))
            HSpace(4.dp)
            Text(if (sleep == null) "Add sleep" else "Edit times", fontSize = 12.sp)
        }
    }) {
        if (sleep == null) {
            Muted("No sleep logged last night. It comes from Health Connect or the Hercules watch, or add it yourself.")
        } else {
            Row(Modifier.fillMaxWidth()) {
                FactTile(Icons.Rounded.Bedtime, formatDuration(sleep.asleepMinutes), "asleep", Modifier.weight(1f), scoreColor(sleepScorer.sleepDurationScore(sleep.asleepMinutes / 60.0)))
                FactTile(Icons.Rounded.NightsStay, clockOf(sleep.startMs), "bedtime", Modifier.weight(1f), Brand)
                FactTile(Icons.Rounded.WbSunny, clockOf(sleep.endMs), "woke up", Modifier.weight(1f), Brand)
                FactTile(Icons.Rounded.Favorite, sleep.awakeMinutes?.let { "${it}m" } ?: "--", "awake", Modifier.weight(1f), Brand)
            }
            VSpace(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (sleep.corrected) Icons.Rounded.Edit else if (sleep.source == SleepNight.SOURCE_HEALTH_CONNECT) Icons.Rounded.Favorite else Icons.Rounded.Watch, null, tint = Text2, modifier = Modifier.size(14.dp))
                HSpace(6.dp)
                Eyebrow(
                    when {
                        sleep.corrected -> "Times edited by you"
                        sleep.source == SleepNight.SOURCE_HEALTH_CONNECT -> "Health Connect · ${sleep.provider ?: "provider"}"
                        else -> "Auto-tracked by your watch"
                    },
                )
            }
        }
        VSpace(18.dp)
        val keys = (-6..0).map { dk.plusDays(today, it) }
        val byKey = week.associateBy { it.dayKey }
        MiniBars(
            values = keys.map { k -> byKey[k]?.let { it.asleepMinutes / 60f } },
            max = 10f,
            labels = keys.map { weekdayShort(dk.startOf(it) + 12 * HOUR) },
            colorOf = { scoreColor(sleepScorer.sleepDurationScore(it.toDouble())) },
            target = 7f,
        )
        VSpace(6.dp)
        Muted("Hours asleep per night. The dashed line is the 7h target.")
    }
    if (correcting) CorrectSleepDialog(g, today, sleep) { correcting = false }
}

/**
 * Fix the times the watch detected (or add a night it missed). Bedtime after noon belongs to the previous
 * evening; the wake time is on the morning of [today].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectSleepDialog(g: MobileGraph, today: String, sleep: SleepNight?, onDismiss: () -> Unit) {
    val dk = g.cognitive.dayKeys
    val scope = rememberCoroutineScope()
    val midnight = dk.startOf(today)
    fun hm(ms: Long?, fallbackH: Int): Pair<Int, Int> =
        ms?.let { val m = ((it - midnight).mod(24 * HOUR)) / 60_000L; (m / 60).toInt() to (m % 60).toInt() } ?: (fallbackH to 0)
    val (bh, bm) = hm(sleep?.startMs, 23)
    val (wh, wm) = hm(sleep?.endMs, 7)
    val bed = rememberTimePickerState(initialHour = bh, initialMinute = bm, is24Hour = true)
    val wake = rememberTimePickerState(initialHour = wh, initialMinute = wm, is24Hour = true)
    val bedMs = midnight + bed.hour * HOUR + bed.minute * 60_000L - if (bed.hour >= 12) 24 * HOUR else 0
    val wakeMs = midnight + wake.hour * HOUR + wake.minute * 60_000L
    val minutes = ((wakeMs - bedMs) / 60_000L).toInt()
    val future = wakeMs > System.currentTimeMillis()
    val valid = minutes in 60..16 * 60 && !future

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BrightPanel,
        shape = RoundedCornerShape(8.dp),
        title = { Text(if (sleep == null) "Add last night's sleep" else "Edit last night's sleep", fontWeight = FontWeight.Light) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Muted("Sleep is tracked for you. Only change the times if they look wrong. Your times replace the tracked ones.")
                VSpace(14.dp)
                Eyebrow("Went to sleep")
                VSpace(6.dp)
                TimeInput(bed)
                Eyebrow("Woke up")
                VSpace(6.dp)
                TimeInput(wake)
                Text(
                    when {
                        future -> "Wake time is still ahead"
                        valid -> "${formatDuration(minutes)} in bed"
                        else -> "Check the times"
                    },
                    fontFamily = Mono, fontSize = 14.sp, color = if (valid) Cyan else Text2,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                scope.launch {
                    runCatching { g.cognitive.correctSleep(today, bedMs, wakeMs) }
                    g.pushToWatch()
                    onDismiss()
                }
            }) { Text("SAVE", letterSpacing = 1.5.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", letterSpacing = 1.5.sp, color = Text2) } },
    )
}


/** This week against last week, then the 14-day shape. All of it is measured or reported, only the score is an estimate. */
@Composable
private fun WeekBlock(trend: List<Readiness>, sleepWeek: List<SleepNight>, g: MobileGraph) {
    val dk = g.cognitive.dayKeys
    val today = trend.last().dayKey
    val keys = (-13..0).map { dk.plusDays(today, it) }
    val byKey = trend.associateBy { it.dayKey }
    val thisWeek = keys.takeLast(7).mapNotNull { byKey[it] }
    val lastWeek = keys.take(7).mapNotNull { byKey[it] }
    val avg = thisWeek.map { it.score }.average().takeIf { !it.isNaN() }?.roundToInt()
    val prev = lastWeek.map { it.score }.average().takeIf { !it.isNaN() }?.roundToInt()
    val best = thisWeek.maxByOrNull { it.score }
    val sleepAvg = sleepWeek.map { it.asleepMinutes }.average().takeIf { !it.isNaN() }?.roundToInt()
    Section("This week") {
        Row(Modifier.fillMaxWidth()) {
            FactTile(Icons.Rounded.Insights, avg?.toString() ?: "--", "avg score", Modifier.weight(1f), avg?.let { scoreColor(it) } ?: Cyan)
            FactTile(
                if ((avg ?: 0) >= (prev ?: 0)) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                if (avg != null && prev != null) "%+d".format(avg - prev) else "--", "vs last week", Modifier.weight(1f), Cyan,
            )
            FactTile(Icons.Rounded.Star, best?.let { weekdayShort(dk.startOf(it.dayKey) + 12 * HOUR) } ?: "--", "best day", Modifier.weight(1f), Cyan)
            FactTile(Icons.Rounded.Bedtime, sleepAvg?.let { formatDuration(it) } ?: "--", "avg sleep", Modifier.weight(1f), Brand)
        }
        VSpace(16.dp)
        MiniBars(
            values = keys.map { k -> byKey[k]?.score?.toFloat() },
            max = 100f,
            labels = keys.mapIndexed { i, k -> if (i % 2 == 1) k.takeLast(2) else "" },
            colorOf = { scoreColor(it.roundToInt()) },
            target = 70f,
            // The month only under the first date shown and wherever it changes.
            subLabels = run {
                var last = ""
                keys.mapIndexed { i, k ->
                    if (i % 2 == 0) return@mapIndexed ""
                    val m = MONTHS.getOrElse(k.substring(5, 7).toInt() - 1) { "" }
                    if (m == last) "" else m.also { last = it }
                }
            },
        )
        VSpace(6.dp)
        Muted("Daily score, last 14 days. Days without enough data stay empty. Dashed line: 70 is Good.")
    }
}

/** The nearest event that isn't over yet. */
fun List<EventStatus>.nextRelevant(nowMs: Long): EventStatus? =
    firstOrNull { it.phase != PrepPhase.DONE && it.event.startMs > nowMs - 2 * 3_600_000L }

@Composable
internal fun TodayStats(s: DailySummary?, hc: HealthConnectImporter.DayTotals? = null, syncing: Boolean = false, onSync: (() -> Unit)? = null) {
    // Same order as the score: the Hercules watch when it was worn today, otherwise the phone's Health Connect.
    // Never summed. The watch's summary row starts at zero, so a watch zero means "not recorded".
    val worn = s != null && s.hrSampleCount > 0
    fun Long.orNull() = takeIf { it > 0 }
    fun Double.orNull() = takeIf { it > 0.0 }
    val steps = s?.steps?.orNull()?.takeIf { worn } ?: hc?.steps
    val km = (s?.distanceMeters?.orNull()?.takeIf { worn } ?: hc?.distanceM)?.div(1000)
    val kcal = s?.calories?.orNull()?.takeIf { worn } ?: hc?.activeKcal
    val floors = s?.floors?.orNull()?.takeIf { worn } ?: hc?.floors?.roundToInt()?.toLong()
    val resting = s?.restingHr ?: hc?.restingHr
    val peak = s?.maxHr ?: hc?.maxHr
    val stats = listOf(
        Stat(Icons.AutoMirrored.Rounded.DirectionsWalk, "Steps", steps?.let { "%,d".format(UiLocale, it) } ?: "--", "", "Movement today"),
        Stat(Icons.Rounded.Straighten, "Distance", km?.let { "%.1f".format(UiLocale, it) } ?: "--", "km", "Walked and run"),
        Stat(Icons.Rounded.Favorite, "Resting pulse", resting?.toString() ?: "--", "bpm", "Lower usually means more recovered"),
        Stat(Icons.Rounded.MonitorHeart, "Peak heart rate", peak?.toString() ?: "--", "bpm", "Highest reading today"),
        Stat(Icons.Rounded.LocalFireDepartment, "Energy burned", kcal?.roundToInt()?.toString() ?: "--", "kcal", "Calories from activity"),
        Stat(Icons.Rounded.Stairs, "Floors climbed", floors?.toString() ?: "--", "", "Flights of stairs"),
    )
    Section("Today", trailing = onSync?.let { sync ->
        {
            TextButton(onClick = sync, enabled = !syncing, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(14.dp))
                HSpace(4.dp)
                Text(if (syncing) "Syncing…" else "Sync", fontSize = 12.sp)
            }
        }
    }) {
        stats.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) VSpace(10.dp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { StatTile(it, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

private data class Stat(val icon: ImageVector, val title: String, val value: String, val unit: String, val hint: String)

/** A stat that explains itself: what it is, the number with its unit, and what it means. */
@Composable
private fun StatTile(st: Stat, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(Panel)
            .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(6.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(st.icon, null, tint = Cyan, modifier = Modifier.size(16.dp))
            HSpace(8.dp)
            Text(st.title, style = MaterialTheme.typography.labelMedium, color = Text2, maxLines = 1)
        }
        VSpace(10.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(st.value, fontFamily = Mono, fontSize = 22.sp, color = Text1)
            if (st.unit.isNotEmpty()) {
                HSpace(4.dp)
                Text(st.unit, fontSize = 12.sp, color = Text2, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        VSpace(4.dp)
        Text(st.hint, style = MaterialTheme.typography.bodySmall, color = Text2, maxLines = 2)
    }
}
