package com.hercules.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hercules.shared.cognitive.ActionKind
import com.hercules.shared.cognitive.DailyBrief
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.EventReflection
import com.hercules.shared.cognitive.Moment
import com.hercules.shared.cognitive.learningNote
import com.hercules.shared.cognitive.provenance
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TimeInput
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hercules.shared.model.DailySummary
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.edit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hercules.mobile.MobileGraph
import com.hercules.mobile.R
import com.hercules.shared.cognitive.CheckIn
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.PrepPhase
import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.SleepNight
import com.hercules.shared.cognitive.Suggestion
import com.hercules.shared.cognitive.WindowKind
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.cognitive.formatDuration
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class TodayData(
    val today: String,
    val readiness: Readiness?,
    val sleep: SleepNight?,
    val sleepWeek: List<SleepNight>,
    val trend: List<Readiness>,
    val checkIn: CheckIn?,
    val nextEvent: EventStatus?,
    val todayEvents: List<EventStatus> = emptyList(),
    val brief: DailyBrief? = null,
    val allEvents: List<EventStatus> = emptyList(),
    val reflections: List<EventReflection> = emptyList(),
)

private const val HOUR = 3_600_000L

private enum class Sheet { CHECK_IN, REFLECT, LEARN, WHY }

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
    request: String? = null,
    onRequestHandled: () -> Unit = {},
) {
    val r = data.readiness
    val brief = data.brief
    val summary by remember(data.today) { g.repo.observeDaily(data.today) }.collectAsStateWithLifecycle(null)
    val lastSync by g.lastWatchSync.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var reflectId by remember { mutableStateOf<String?>(null) }
    val watchDone = stickyFlag("start_watch", lastSync != null)
    val checkInDone = stickyFlag("start_checkin", data.checkIn != null)
    val moment = g.cognitive.daily.moment(nowMs)
    val hour = g.cognitive.dayKeys.minuteOfDay(nowMs) / 60
    val showTonight = brief?.sleep != null && (moment == Moment.EVENING || moment == Moment.NIGHT || hour >= 16)
    val actionEvent = brief?.action?.eventId
    var scrollTo by remember { mutableStateOf<String?>(null) }

    // Order follows the questions people ask, most urgent first: How am I? (score) · What unlocks it? (setup, first
    // week only) · What do I do now? (action, check-in) · What's coming? (tonight in the evening, the next moment, best focus time, the day)
    // · sleep and the week · activity counts. Tips sit just under the next step; the score breakdown opens from the score.
    val sections = buildList<Pair<String, @Composable () -> Unit>> {
        add("top" to { TopLine(nowMs, r?.band, onOpenGuide) })
        add("hero" to {
            when {
                brief == null -> Box(Modifier.fillMaxWidth().height(320.dp)) // first brief still computing: no "no score" flash
                r != null -> Briefing(r, brief, onWhy = { sheet = Sheet.WHY }) { sheet = Sheet.LEARN }
                else -> NoScoreCard { sheet = Sheet.CHECK_IN }
            }
        })
        val showStart = !(watchDone && checkInDone)
        if (showStart) {
            add("start" to {
                GettingStarted(watchDone, checkInDone, onWatch = onOpenSetup, onCheckIn = { sheet = Sheet.CHECK_IN })
            })
        }
        if (brief != null) {
            // Getting started already offers the first check-in; don't suggest it twice.
            if (!(showStart && !checkInDone && brief.action.kind == ActionKind.CHECK_IN)) add("action" to {
                ActionCard(brief.action, r?.band.mood()) { a ->
                    when (a.kind) {
                        ActionKind.CHECK_IN -> sheet = Sheet.CHECK_IN
                        ActionKind.PREPARE -> a.eventId?.let(onOpenEvent)
                        ActionKind.REFLECT -> { reflectId = a.eventId; sheet = Sheet.REFLECT }
                        ActionKind.SLEEP -> scrollTo = "tonight"
                        ActionKind.FOCUS -> scrollTo = "day"
                    }
                }
            })
        }
        if (r != null) add("tips" to { Tips(r.suggestions) })
        // Today's check-in: the summary once done; the prompt only when nothing above already asks for it.
        val askedAbove = (showStart && !checkInDone) || brief?.action?.kind == ActionKind.CHECK_IN
        when {
            data.checkIn != null -> add("checkin" to { CheckInSummary(data.checkIn) { sheet = Sheet.CHECK_IN } })
            !askedAbove -> add("checkin" to { CheckInPrompt { sheet = Sheet.CHECK_IN } })
        }
        if (showTonight) add("tonight" to { TonightCard(brief!!.sleep!!, r) })
        data.nextEvent?.takeIf { it.event.id != actionEvent }?.let { ev -> add("next" to { NextEvent(ev, onOpenEvent) }) }
        if (r != null && brief?.focus != null) add("focus" to { FocusCard(brief.focus, brief.focusNow, nowMs) })
        add("day" to { DayStrip(g.cognitive.dayKeys, data, r, nowMs, onOpenEvent) })
        add("sleep" to { SleepBlock(data.sleep, data.sleepWeek, data.today, g) })
        if (data.trend.size >= 2) add("week" to { WeekBlock(data.trend, data.sleepWeek, g) })
        add("stats" to { TodayStats(summary) })
    }
    LaunchedEffect(request) {
        when (request) {
            "checkin" -> sheet = Sheet.CHECK_IN
            "tonight" -> scrollTo = "tonight"
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
                Sheet.CHECK_IN -> CheckInForm(g, data.checkIn) { sheet = null }
                Sheet.REFLECT -> {
                    val ev = data.allEvents.firstOrNull { it.event.id == reflectId }
                    var rating by remember(reflectId) { mutableStateOf(data.reflections.firstOrNull { it.eventId == reflectId }?.rating) }
                    ReflectContent(ev?.event?.title ?: "Your moment", r?.score, rating) { v ->
                        rating = v
                        reflectId?.let { id -> scope.launch { g.cognitive.store.saveReflection(EventReflection(id, v, System.currentTimeMillis())) } }
                    }
                }
                Sheet.LEARN -> LearnContent(r)
                Sheet.WHY -> r?.let { Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) { Readouts(it) } }
            }
        }
    }
}

/** Before today's check-in: what it is and one button. */
@Composable
internal fun CheckInPrompt(onCheckIn: () -> Unit) {
    Section("Today's check-in") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Illustration(R.drawable.ill_checkin, Modifier.size(72.dp))
            HSpace(12.dp)
            Muted("Rate your energy and stress. It takes 10 seconds and makes today's score more accurate.", Modifier.weight(1f))
        }
        VSpace(12.dp)
        OutlinedButton(onClick = onCheckIn, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Cyan)) {
            Text("CHECK IN", letterSpacing = 1.5.sp, color = Cyan, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A "done" flag that stays done, so first-week hints never come back once completed. */
@Composable
private fun stickyFlag(key: String, now: Boolean): Boolean {
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
        Eyebrow("Learning your patterns")
        VSpace(6.dp)
        Text(r?.learningNote ?: "Your score is fully calibrated.", style = MaterialTheme.typography.headlineSmall, color = Text1)
        VSpace(12.dp)
        Muted("Hercules compares you with your own normal. It needs about 7 nights to learn it. Until then the score is a rougher estimate.")
    }
}

@Composable
private fun TopLine(nowMs: Long, band: ReadinessBand?, onOpenGuide: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Herc(band.mood(), size = 40.dp)
        HSpace(8.dp)
        Text("HERCULES", color = Text1, fontSize = 13.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Medium)
        HSpace(12.dp)
        Box(Modifier.size(width = 18.dp, height = 1.dp).background(Hairline))
        HSpace(12.dp)
        Eyebrow(shortDate(nowMs), modifier = Modifier.weight(1f))
        IconButton(onClick = onOpenGuide) { Icon(Icons.Rounded.Info, "How to read the score", tint = Text2) }
    }
}


/**
 * The signature view: one line for the whole day. Last night's sleep, focus windows,
 * the current moment and today's events, on a single time axis.
 *
 * Built for narrow phones and large system font sizes: the canvas draws shapes plus a few short
 * numerals only; anything wordy (event titles, legend, "right now") is normal layout that wraps.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DayStrip(dk: DayKeys, data: TodayData, r: Readiness?, nowMs: Long, onOpenEvent: (String) -> Unit) {
    val dayStart = dk.startOf(data.today)
    val start = minOf(data.sleep?.startMs ?: (dayStart - 2 * HOUR), dayStart - HOUR)
    val end = dayStart + 24 * HOUR
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val events = data.todayEvents.filter { it.event.startMs in start..end }.sortedBy { it.event.startMs }
    val tickStyle = TextStyle(color = Text2, fontSize = 10.sp)
    val pinStyle = TextStyle(color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    val peak = r?.windows?.firstOrNull { it.kind == WindowKind.PEAK }
    val current = r?.windows?.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }

    val tickH = with(density) { measurer.measure("00:00", tickStyle).size.height.toDp() }
    val pinZone = if (events.isEmpty()) 0.dp else 26.dp
    val sleepH = 28.dp
    val bandH = 12.dp
    val gap = 8.dp
    val canvasH = pinZone + sleepH + gap + bandH + 6.dp + tickH + 4.dp

    Section("Your day", trailing = { peak?.let { Eyebrow("peak ${clockOf(it.startMs)}", color = Cyan) } }) {
        val upcoming = r?.windows?.filter { it.startMs > nowMs }?.minByOrNull { it.startMs }
        val caption = when {
            current != null -> "Right now: ${current.kind.label.lowercase(UiLocale)} until ${clockOf(current.endMs)}"
            upcoming != null -> "Next up: ${upcoming.kind.label.lowercase(UiLocale)} from ${clockOf(upcoming.startMs)}"
            r != null -> "Your best hours are over for today."
            else -> "Your day fills in once sleep or a check-in is recorded."
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(current?.kind?.color ?: Text2))
            HSpace(8.dp)
            Text(caption, style = MaterialTheme.typography.bodyMedium, color = Text1)
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

            // Hour grid: a faint line through the lanes and a label under the axis, every 6 hours.
            var t = dayStart - 6 * HOUR
            while (t <= end) {
                if (t >= start) {
                    val gx = x(t)
                    drawLine(Hairline, Offset(gx, top), Offset(gx, axisY), 1f)
                    val txt = measurer.measure(clockOf(t), tickStyle)
                    drawText(txt, topLeft = Offset((gx - txt.size.width / 2f).coerceIn(0f, size.width - txt.size.width), axisY + 2.dp.toPx()))
                }
                t += 6 * HOUR
            }
            // Sleep lane
            val sleep = data.sleep
            if (sleep != null) {
                val x0 = x(sleep.startMs); val x1 = x(sleep.endMs)
                drawRoundRect(Brand.copy(alpha = 0.4f), Offset(x0, top), Size(x1 - x0, sleepPx), CornerRadius(4.dp.toPx()))
                val txt = measurer.measure(formatDuration(sleep.asleepMinutes), tickStyle.copy(color = Text1))
                if (txt.size.width < x1 - x0 - 12.dp.toPx()) drawText(txt, topLeft = Offset(x0 + 6.dp.toPx(), top + (sleepPx - txt.size.height) / 2))
            } else {
                val w = maxOf(x(dayStart + 7 * HOUR), 0f)
                drawRoundRect(Hairline, Offset(0f, top), Size(w, sleepPx), CornerRadius(4.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                val txt = measurer.measure("No sleep tracked", tickStyle)
                val lx = w - txt.size.width - 8.dp.toPx()
                val nowCrosses = nowMs in start..end && x(nowMs) in (lx - 6.dp.toPx())..(lx + txt.size.width + 6.dp.toPx())
                if (lx > 6.dp.toPx() && !nowCrosses) drawText(txt, topLeft = Offset(lx, top + (sleepPx - txt.size.height) / 2))
            }
            // Focus windows on a visible track, so dim phases (dip, evening) still read.
            drawRoundRect(Hairline, Offset(0f, bandY), Size(size.width, bandPx), CornerRadius(3.dp.toPx()))
            r?.windows?.forEach { w ->
                val x0 = x(w.startMs); val x1 = x(w.endMs)
                if (x1 > x0) drawRect(w.kind.color, Offset(x0, bandY), Size(x1 - x0 - 2f, bandPx))
            }
            // Events: numbered pins on a stem; the matching list sits below.
            var lastPin = -1e9f
            events.forEachIndexed { i, e ->
                val ex = x(e.event.startMs)
                val col = e.advice?.verdict?.color ?: Cyan
                val rad = 9.dp.toPx()
                // Nudge neighbours apart so numbered pins never overlap; the stem still marks the true time.
                val cx = ex.coerceIn(rad, size.width - rad).coerceAtLeast(lastPin + 2 * rad + 2.dp.toPx())
                lastPin = cx
                val cy = rad + 1.dp.toPx()
                drawLine(Text2, Offset(ex, cy + rad), Offset(ex, bandY + bandPx), 1f)
                drawCircle(col, rad, Offset(cx, cy))
                val n = measurer.measure("${i + 1}", pinStyle)
                drawText(n, topLeft = Offset(cx - n.size.width / 2f, cy - n.size.height / 2f))
            }
            // Now marker
            if (nowMs in start..end) {
                val nx = x(nowMs)
                drawLine(Ink, Offset(nx, top - 2.dp.toPx()), Offset(nx, axisY), 4.dp.toPx())
                drawLine(NowColor, Offset(nx, top - 2.dp.toPx()), Offset(nx, axisY), 2.dp.toPx())
                drawCircle(NowColor, 4.dp.toPx(), Offset(nx, axisY))
            }
        }
        if (events.isNotEmpty()) {
            VSpace(8.dp)
            events.forEachIndexed { i, e ->
                val col = e.advice?.verdict?.color ?: Cyan
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onOpenEvent(e.event.id) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
                        val badgeSp = with(density) { 11.dp.toSp() } // stays inside the circle at any system font size
                        Text("${i + 1}", fontSize = badgeSp, lineHeight = badgeSp, fontWeight = FontWeight.Bold, color = Ink)
                    }
                    HSpace(10.dp)
                    Text(clockOf(e.event.startMs), fontFamily = Mono, fontSize = 13.sp, color = Text2)
                    HSpace(10.dp)
                    Text(e.event.title, style = MaterialTheme.typography.bodyMedium, color = Text1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Open", tint = Text2, modifier = Modifier.size(18.dp))
                }
            }
        }
        VSpace(10.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Legend(Brand.copy(alpha = 0.6f), "Sleep")
            // Only the parts of the day that are actually on the strip, in time order.
            r?.windows?.map { it.kind }?.distinct()?.forEach { Legend(it.color, it.label) }
            Legend(NowColor, "Now")
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 6.dp).background(color))
        HSpace(6.dp)
        Text(text.uppercase(UiLocale), fontSize = 10.sp, letterSpacing = 1.sp, color = Text2)
    }
}

/** Four instrument readouts; tap one to see what it measured. */
@Composable
internal fun Readouts(r: Readiness) {
    var selected by remember { mutableStateOf<Int?>(null) }
    var technical by remember { mutableStateOf(false) }
    Section("Why this score", trailing = { Pill("Estimate", Text2) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            r.components.forEachIndexed { i, c ->
                val col = scoreColor(c.score)
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                        .border(BorderStroke(1.dp, if (selected == i) col else Hairline), RoundedCornerShape(6.dp))
                        .clickable { selected = if (selected == i) null else i }.padding(10.dp),
                ) {
                    Icon(c.kind.icon, null, tint = col, modifier = Modifier.size(16.dp))
                    VSpace(10.dp)
                    Text("${c.score}", fontFamily = Mono, fontSize = 22.sp, color = Text1)
                    VSpace(6.dp)
                    ScoreBar(c.score, col, 2.dp)
                    VSpace(6.dp)
                    Text(c.kind.plainName.uppercase(UiLocale), fontSize = 10.sp, letterSpacing = 1.sp, color = Text2, maxLines = 2, minLines = 2)
                }
            }
        }
        if (selected == null) {
            VSpace(8.dp)
            Muted("Tap a reading to see where it comes from.")
        }
        selected?.let { i ->
            val c = r.components[i]
            VSpace(10.dp)
            Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = Text1)
            VSpace(8.dp)
            ProvenanceTag(c.kind.provenance)
            TextButton(onClick = { technical = !technical }, contentPadding = PaddingValues(0.dp)) {
                Text(if (technical) "HIDE DETAILS" else "HOW IS THIS USED?", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Cyan)
            }
            AnimatedVisibility(technical) { Muted("${(c.kind.weight * 100).roundToInt()}% of the score. ${c.kind.explanation}") }
        }
    }
}

@Composable
private fun NextEvent(s: EventStatus, onOpen: (String) -> Unit) {
    val a = s.advice
    Section("Next") {
        PanelBox(Modifier.clickable { onOpen(s.event.id) }, accent = a?.verdict?.color ?: Cyan) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayTag(s.daysUntil), fontSize = 40.sp, fontWeight = FontWeight.Thin, color = a?.verdict?.color ?: Cyan)
                HSpace(16.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.event.type.icon, null, tint = Text2, modifier = Modifier.size(14.dp))
                        HSpace(6.dp)
                        Eyebrow("${s.event.importance.label} · ${clockOf(s.event.startMs)}")
                    }
                    VSpace(4.dp)
                    Text(s.event.title, style = MaterialTheme.typography.titleMedium, color = Text1, maxLines = 2)
                    VSpace(8.dp)
                    if (a != null) Pill(a.verdict.label, a.verdict.color) else Pill(s.phase.label, Cyan)
                }
                when {
                    a != null -> ScoreRing(a.expectedScore, "at ${clockOf(s.event.startMs)}", 58.dp)
                    s.prepScore != null -> ScoreRing(s.prepScore!!, "prep", 58.dp)
                }
            }
        }
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
                    Icon(s.tone.icon, null, tint = s.tone.color.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                }
                AnimatedVisibility(open) {
                    Text(s.detail, style = MaterialTheme.typography.bodyMedium, color = Text2, modifier = Modifier.padding(start = 34.dp, top = 6.dp))
                }
            }
            if (i < tips.take(3).lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        }
    }
}

@Composable
private fun SleepBlock(sleep: SleepNight?, week: List<SleepNight>, today: String, g: MobileGraph) {
    val dk = g.cognitive.dayKeys
    var correcting by remember { mutableStateOf(false) }
    Section("Sleep", trailing = {
        TextButton(onClick = { correcting = true }) { Text(if (sleep == null) "Add" else "Correct", fontSize = 12.sp) }
    }) {
        if (sleep == null) {
            Muted("Nothing tracked last night. Wear the watch to bed and sleep is recorded automatically.")
        } else {
            Row(Modifier.fillMaxWidth()) {
                FactTile(Icons.Rounded.Bedtime, formatDuration(sleep.asleepMinutes), "asleep", Modifier.weight(1f), scoreColor(g.cognitive.engine.sleepScore(sleep.asleepMinutes / 60.0)))
                FactTile(Icons.Rounded.NightsStay, clockOf(sleep.startMs), "in bed", Modifier.weight(1f), Brand)
                FactTile(Icons.Rounded.WbSunny, clockOf(sleep.endMs), "awake", Modifier.weight(1f), Brand)
                FactTile(Icons.Rounded.Favorite, sleep.sleepingHrAvg?.roundToInt()?.toString() ?: "--", "bpm", Modifier.weight(1f), Brand)
            }
            VSpace(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (sleep.corrected) Icons.Rounded.Edit else Icons.Rounded.Watch, null, tint = Text2, modifier = Modifier.size(14.dp))
                HSpace(6.dp)
                Eyebrow(if (sleep.corrected) "Corrected by you" else "Auto-tracked by your watch")
            }
        }
        VSpace(18.dp)
        val keys = (-6..0).map { dk.plusDays(today, it) }
        val byKey = week.associateBy { it.dayKey }
        MiniBars(
            values = keys.map { k -> byKey[k]?.let { it.asleepMinutes / 60f } },
            max = 10f,
            labels = keys.map { weekdayShort(dk.startOf(it) + 12 * HOUR) },
            colorOf = { scoreColor(g.cognitive.engine.sleepScore(it.toDouble())) },
            target = 7f,
        )
        VSpace(6.dp)
        Muted("Hours asleep per night. The dashed line is a 7h target.")
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
        containerColor = Panel,
        shape = RoundedCornerShape(8.dp),
        title = { Text(if (sleep == null) "Add last night" else "Correct last night", fontWeight = FontWeight.Light) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Muted("Your watch records sleep on its own. Only fix the times if they look wrong.")
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

/** The two-tap check-in: used from the action button and from "Edit". */
@Composable
internal fun CheckInForm(g: MobileGraph, existing: CheckIn?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var energy by remember(existing) { mutableIntStateOf(existing?.energy ?: 3) }
    var stress by remember(existing) { mutableIntStateOf(existing?.stress ?: 3) }
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Eyebrow("Check-in")
        VSpace(6.dp)
        Text("How do you feel right now?", style = MaterialTheme.typography.headlineSmall, color = Text1)
        VSpace(20.dp)
        SegmentScale("Energy", Icons.Rounded.Bolt, "Drained", "Charged", energy, higherIsBetter = true) { energy = it }
        VSpace(18.dp)
        SegmentScale("Stress", Icons.Rounded.Psychology, "Calm", "Tense", stress, higherIsBetter = false) { stress = it }
        VSpace(20.dp)
        Button(
            enabled = !saving,
            onClick = {
                saving = true
                scope.launch {
                    g.cognitive.saveCheckIn(energy, stress)
                    g.pushToWatch()
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
        ) { Text(if (saving) "SAVING…" else "SAVE", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** Today's check-in, once done: two numbers and an edit button. */
@Composable
internal fun CheckInSummary(c: CheckIn, onEdit: () -> Unit) {
    Section("Today's check-in", trailing = { TextButton(onClick = onEdit) { Text("Edit", fontSize = 12.sp) } }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Illustration(R.drawable.ill_checkin, Modifier.size(80.dp))
            HSpace(12.dp)
            FactTile(Icons.Rounded.Bolt, "${c.energy}/5", "energy", Modifier.weight(1f), scoreColor(c.energy * 20))
            FactTile(Icons.Rounded.Psychology, "${c.stress}/5", "stress", Modifier.weight(1f), scoreColor(120 - c.stress * 20))
        }
    }
}

/** Five tappable segments that fill up to the chosen value, coloured good→bad. */
@Composable
private fun SegmentScale(title: String, icon: ImageVector, low: String, high: String, value: Int, higherIsBetter: Boolean, onChange: (Int) -> Unit) {
    val color = scoreColor(if (higherIsBetter) value * 20 else 120 - value * 20)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        HSpace(8.dp)
        Eyebrow(title, color = Text1, modifier = Modifier.weight(1f))
        Text("$value/5", fontFamily = Mono, fontSize = 14.sp, color = color)
    }
    VSpace(8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..5).forEach { v ->
            Box(
                Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (v <= value) color.copy(alpha = 0.35f + 0.13f * v) else Panel)
                    .border(BorderStroke(1.dp, if (v <= value) Color.Transparent else Hairline), RoundedCornerShape(3.dp))
                    .clickable { onChange(v) },
            )
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(low.uppercase(UiLocale), fontSize = 10.sp, letterSpacing = 1.sp, color = Text2, modifier = Modifier.weight(1f))
        Text(high.uppercase(UiLocale), fontSize = 10.sp, letterSpacing = 1.sp, color = Text2)
    }
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
    Section("This week", trailing = { Pill("Estimate", Text2) }) {
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
        )
        VSpace(6.dp)
        Muted("Daily score, last 14 days. Dashed line: 70 is a good day.")
    }
}

/** The nearest event that isn't over yet. */
fun List<EventStatus>.nextRelevant(nowMs: Long): EventStatus? =
    firstOrNull { it.phase != PrepPhase.DONE && it.event.startMs > nowMs - 2 * 3_600_000L }

@Composable
internal fun TodayStats(s: DailySummary?) {
    val stats = listOf(
        Stat(Icons.AutoMirrored.Rounded.DirectionsWalk, "Steps", s?.steps?.let { "%,d".format(UiLocale, it) } ?: "--", "", "Movement today"),
        Stat(Icons.Rounded.Straighten, "Distance", s?.let { "%.1f".format(UiLocale, it.distanceMeters / 1000) } ?: "--", "km", "Walked and run"),
        Stat(Icons.Rounded.Favorite, "Resting pulse", s?.restingHr?.toString() ?: "--", "bpm", "Lower means better recovered"),
        Stat(Icons.Rounded.MonitorHeart, "Peak heart rate", s?.maxHr?.toString() ?: "--", "bpm", "Highest reading today"),
        Stat(Icons.Rounded.LocalFireDepartment, "Energy burned", s?.calories?.roundToInt()?.toString() ?: "--", "kcal", "Calories from activity"),
        Stat(Icons.Rounded.Stairs, "Floors climbed", s?.floors?.toString() ?: "--", "", "Flights of stairs"),
    )
    Section("Today") {
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
