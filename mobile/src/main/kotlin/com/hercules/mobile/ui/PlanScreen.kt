package com.hercules.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hercules.mobile.MobileGraph
import com.hercules.mobile.R
import com.hercules.shared.cognitive.CombinedTask
import com.hercules.shared.cognitive.EventReflection
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.PlanEvent
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.DefaultScoring
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.MomentRules
import com.hercules.shared.readiness.MomentStatus
import com.hercules.shared.readiness.PeakAlignment
import com.hercules.shared.readiness.PeakResult
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.Urgency
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt

private const val HOUR = 3_600_000L

/**
 * Moments: up to five upcoming ones on a countdown rail, today's combined preparation when there are several,
 * and the history of completed and cancelled ones.
 */
@Composable
fun PlanScreen(
    g: MobileGraph,
    statuses: List<EventStatus>,
    history: List<EventStatus>,
    combined: List<CombinedTask>,
    padding: PaddingValues,
    onOpen: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 96.dp),
    ) {
        item {
            Eyebrow("Moments")
            VSpace(6.dp)
            Text("Own what's ahead", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
            VSpace(20.dp)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Eyebrow("Upcoming", color = Text1, modifier = Modifier.weight(1f))
                Text("${statuses.size}/${MomentRules.MAX_ACTIVE}", fontFamily = Mono, fontSize = 12.sp, color = Text2)
            }
            VSpace(14.dp)
        }
        if (statuses.isEmpty()) {
            item {
                Illustration(R.drawable.ill_plan, Modifier.fillMaxWidth().height(220.dp))
                VSpace(8.dp)
                Text("No Moments yet", style = MaterialTheme.typography.titleLarge, color = Text1)
                VSpace(4.dp)
                Muted("Got an interview, exam or pitch coming up? Add it and Herc gets you ready for it.")
            }
        }
        itemsIndexed(statuses, key = { _, s -> s.event.id }) { i, s -> RailRow(s, first = i == 0, last = i == statuses.lastIndex, primary = i == 0) { onOpen(s.event.id) } }
        if (statuses.size >= 2 && combined.isNotEmpty()) {
            item { VSpace(8.dp); CombinedPrep(combined) }
        }
        if (history.isNotEmpty()) {
            item { VSpace(28.dp); Eyebrow("History", color = Text1); VSpace(10.dp) }
            items(history, key = { "h-" + it.event.id }) { s -> HistoryRow(s) { onOpen(s.event.id) } }
        }
    }
}

/** Several Moments at once (spec §8.10): Critical and sooner first, shared advice once, tagged with its moments. */
@Composable
private fun CombinedPrep(items: List<CombinedTask>) {
    Section("Today, across your Moments") {
        items.take(5).forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                Text("%02d".format(i + 1), fontFamily = Mono, fontSize = 13.sp, color = Cyan)
                HSpace(12.dp)
                Column(Modifier.weight(1f)) {
                    Text(c.task.title, style = MaterialTheme.typography.bodyLarge, color = Text1)
                    Muted("For " + c.moments.joinToString(", "))
                }
            }
            if (i < minOf(items.size, 5) - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        }
    }
}

@Composable
private fun HistoryRow(s: EventStatus, onClick: () -> Unit) {
    val cancelled = s.event.status == MomentStatus.CANCELLED
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(s.event.type.icon, null, tint = Text2, modifier = Modifier.size(16.dp))
        HSpace(12.dp)
        Column(Modifier.weight(1f)) {
            Text(s.event.title, style = MaterialTheme.typography.bodyLarge, color = if (cancelled) Text2 else Text1, maxLines = 1)
            Muted("${shortDate(s.event.startMs)} · ${clockOf(s.event.startMs)}")
        }
        Pill(if (cancelled) "Cancelled" else "Completed", Text2)
    }
}

/** One stop on the countdown rail: node + D-x on the left, the moment on the right. */
@Composable
private fun RailRow(s: EventStatus, first: Boolean, last: Boolean, primary: Boolean, onClick: () -> Unit) {
    val accent = s.preparation.status.color
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick)) {
        Box(Modifier.width(76.dp).fillMaxHeight()) {
            Canvas(Modifier.fillMaxSize()) {
                val x = 10.dp.toPx()
                val y = 22.dp.toPx()
                if (!first) drawLine(Hairline, Offset(x, 0f), Offset(x, y), 2.dp.toPx())
                if (!last) drawLine(Hairline, Offset(x, y), Offset(x, size.height), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawCircle(accent, 6.dp.toPx(), Offset(x, y))
                drawCircle(Ink, 3.dp.toPx(), Offset(x, y))
            }
            Text(dayTag(s.daysUntil), fontFamily = Mono, fontSize = 12.sp, color = accent, modifier = Modifier.padding(start = 24.dp, top = 14.dp))
        }
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 22.dp)) {
            PanelBox {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.event.type.icon, null, tint = Text2, modifier = Modifier.size(14.dp))
                        HSpace(6.dp)
                        Eyebrow("${shortDate(s.event.startMs)} · ${clockOf(s.event.startMs)}", modifier = Modifier.weight(1f))
                        if (s.event.urgency == Urgency.CRITICAL) UrgencyTag()
                    }
                    VSpace(6.dp)
                    Text(s.event.title, style = MaterialTheme.typography.titleMedium, color = Text1, maxLines = 2)
                    VSpace(10.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(s.preparation.status.label, s.preparation.status.color)
                        HSpace(8.dp)
                        Text(daysLeft(s.daysUntil), style = MaterialTheme.typography.bodySmall, color = Text2)
                    }
                    if (s.todayTasks.isNotEmpty()) {
                        val done = s.todayTasks.count { it.id in s.doneTaskIds }
                        VSpace(12.dp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.TaskAlt, null, tint = Cyan, modifier = Modifier.size(14.dp))
                            HSpace(6.dp)
                            Text("$done/${s.todayTasks.size} steps done today", style = MaterialTheme.typography.bodySmall, color = Text2)
                        }
                        VSpace(6.dp)
                        ScoreBar(done * 100 / s.todayTasks.size, Cyan, 3.dp)
                        if (primary) {
                            VSpace(12.dp)
                            Button(
                                onClick = onClick, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
                            ) { Text(if (done == 0) "START PREP" else "KEEP GOING", letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UrgencyTag() {
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).background(CriticalRed.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PriorityHigh, null, tint = CriticalRed, modifier = Modifier.size(12.dp))
        Text("CRITICAL", fontSize = 10.sp, letterSpacing = 1.sp, color = CriticalRed, fontWeight = FontWeight.SemiBold)
    }
}

private val CriticalRed = Color(0xFFFF4D6D)

private fun daysLeft(d: Int) = when { d < 0 -> "Passed"; d == 0 -> "Today"; d == 1 -> "Tomorrow"; else -> "In $d days" }

// ------------------------------------------------------------------ add / edit

/**
 * New or edited Moment: title, urgency, date and hour. At most seven days ahead and never in the past; the time
 * zone is captured automatically. Rule violations come back from the engine in plain words.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MomentDialog(g: MobileGraph, nowMs: Long, existing: PlanEvent? = null, onDismiss: () -> Unit) {
    val dk = g.cognitive.dayKeys
    val today = dk.of(nowMs)
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var urgency by remember { mutableStateOf(existing?.urgency ?: Urgency.NORMAL) }
    var dayOffset by remember { mutableIntStateOf(existing?.let { dk.daysBetween(today, dk.of(it.startMs)) } ?: 3) }
    var hour by remember { mutableIntStateOf(existing?.let { dk.minuteOfDay(it.startMs) / 60 } ?: 10) }
    var minute by remember { mutableIntStateOf(existing?.let { dk.minuteOfDay(it.startMs) % 60 } ?: 0) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val titleFocus = remember { FocusRequester() }
    val startMs = dk.startOf(dk.plusDays(today, dayOffset)) + hour * HOUR + minute * 60_000L
    val tooFar = startMs - nowMs > MomentRules.MAX_DAYS_AHEAD * 24 * HOUR
    val valid = title.isNotBlank() && startMs > nowMs && !tooFar
    val keyboard = LocalSoftwareKeyboardController.current
    val peakInfo by produceState<Pair<AssessmentWindow, Long>?>(null) {
        val p = g.cognitive.store.latestPeak()
        val w = p?.takeIf { it.result == PeakResult.PREDICTED }?.window
        val wakeMinute = g.cognitive.typicalWakeMinute() ?: dk.minuteOfDay(g.cognitive.wakeMs(today))
        value = w?.let { it to wakeMinute * 60_000L }
    }
    LaunchedEffect(Unit) {
        if (existing != null) return@LaunchedEffect
        delay(200)
        runCatching { titleFocus.requestFocus() }
        keyboard?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        containerColor = Panel,
        shape = RoundedCornerShape(8.dp),
        title = { Text(if (existing == null) "New Moment" else "Edit Moment", fontWeight = FontWeight.Light) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    title, { title = it; error = null },
                    label = { Text("Title") },
                    placeholder = { Text("e.g. Interview at Acme") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                )
                VSpace(14.dp)
                Eyebrow("Urgency")
                VSpace(6.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Urgency.entries.forEach { u ->
                        FilterChip(
                            urgency == u, { urgency = u },
                            label = { Text(u.label, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Muted(if (urgency == Urgency.CRITICAL) "Its prep comes first. Your score itself doesn't change." else "Prepared for as usual.")
                VSpace(14.dp)
                Eyebrow("When")
                VSpace(8.dp)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cyan.copy(alpha = 0.07f))
                        .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Rounded.TrackChanges, null, tint = Cyan, modifier = Modifier.size(18.dp))
                    HSpace(10.dp)
                    Text("Pick a time in the next 7 days. Until then, Herc coaches your sleep and recovery.", style = MaterialTheme.typography.bodySmall, color = Text1)
                }
                VSpace(10.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..MomentRules.MAX_DAYS_AHEAD).forEach { d ->
                        val label = when (d) {
                            0 -> "Today"
                            1 -> "Tomorrow"
                            else -> shortDate(dk.startOf(dk.plusDays(today, d)) + 12 * HOUR)
                        }
                        FilterChip(dayOffset == d, { dayOffset = d; error = null }, { Text(label) })
                    }
                }
                VSpace(8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Time", modifier = Modifier.weight(1f))
                    Stepper(hour.toString().padStart(2, '0'), onMinus = { hour = (hour + 23) % 24 }, onPlus = { hour = (hour + 1) % 24 })
                    Text(":", fontFamily = Mono, fontSize = 20.sp, color = Text2, modifier = Modifier.padding(horizontal = 4.dp))
                    Stepper(minute.toString().padStart(2, '0'), onMinus = { minute = (minute + 45) % 60 }, onPlus = { minute = (minute + 15) % 60 })
                }
                VSpace(10.dp)
                when {
                    startMs <= nowMs -> Muted("That time's already gone.")
                    tooFar -> Muted("That's more than 7 days out.")
                    peakInfo != null -> PeakHint(peakInfo!!.first, dk.startOf(dk.plusDays(today, dayOffset)) + peakInfo!!.second, startMs)
                }
                error?.let { VSpace(8.dp); Text(it, style = MaterialTheme.typography.bodySmall, color = CriticalRed) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                scope.launch {
                    when (val r = g.cognitive.saveMoment(existing?.id, title, startMs, urgency, existing?.notes ?: "", ZoneId.systemDefault().id)) {
                        is MomentRules.Check.Rejected -> error = r.reason
                        MomentRules.Check.Ok -> { g.pushToWatch(); onDismiss() }
                    }
                }
            }) { Text("SAVE", letterSpacing = 1.5.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", letterSpacing = 1.5.sp, color = Text2) } },
    )
}

/** Where the chosen time falls against the predicted peak window. The time itself is the user's to decide. */
@Composable
private fun PeakHint(window: AssessmentWindow, wakeMs: Long, startMs: Long) {
    val from = window.startMs(wakeMs)
    val to = window.endMs(wakeMs)
    val near = 90 * 60_000L
    val text = when {
        startMs in from until to -> "Right in your peak window (${clockOf(from)}–${clockOf(to)}): when you're at your sharpest."
        startMs in (from - near) until (to + near) -> "Close to your peak window (${clockOf(from)}–${clockOf(to)})."
        else -> "Outside your peak window (${clockOf(from)}–${clockOf(to)}). No problem: Herc will get you ready for the time you picked."
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Bolt, null, tint = Cyan, modifier = Modifier.size(14.dp).padding(top = 2.dp))
        HSpace(8.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = Text1)
    }
}

// ------------------------------------------------------------------ moment detail

@Composable
fun EventDetail(
    g: MobileGraph,
    s: EventStatus,
    padding: PaddingValues,
    onChanged: () -> Unit,
    onEdit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf(false) }
    val today = g.cognitive.today()
    val reflections by remember { g.cognitive.store.observeReflections() }.collectAsStateWithLifecycle(emptyList())
    val myReflection = reflections.firstOrNull { it.eventId == s.event.id }
    val active = s.event.status == MomentStatus.ACTIVE
    val p = s.preparation
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = CardGap,
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(s.event.type.icon, null, tint = Cyan, modifier = Modifier.size(16.dp))
                    HSpace(8.dp)
                    Eyebrow(s.event.urgency.label, color = if (s.event.urgency == Urgency.CRITICAL) CriticalRed else Cyan, modifier = Modifier.weight(1f))
                    if (!active) Pill(if (s.event.status == MomentStatus.CANCELLED) "Cancelled" else "Completed", Text2)
                }
                VSpace(8.dp)
                Text(s.event.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
                Muted("${longDate(s.event.startMs)} · ${clockOf(s.event.startMs)}")
                if (active) {
                    VSpace(22.dp)
                    Text(if (s.daysUntil == 0) "Today" else "${s.daysUntil}", fontSize = 64.sp, fontWeight = FontWeight.Thin, color = Text1, letterSpacing = (-2).sp)
                    Eyebrow(when { s.daysUntil > 1 -> "days remaining · ${s.phase.label}"; s.daysUntil == 1 -> "day remaining · ${s.phase.label}"; else -> s.phase.label }, color = Cyan)
                }
            }
        }
        if (active) item {
            Section("Preparation", trailing = { Pill(p.status.label, p.status.color) }) {
                p.reasons.forEach {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = p.status.color, modifier = Modifier.size(14.dp).padding(top = 3.dp))
                        HSpace(10.dp)
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = Text1)
                    }
                }
                VSpace(12.dp)
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue("Latest score", p.latestScore?.toString() ?: "--", Modifier.weight(1f), p.latestScore?.let { scoreColor(it) } ?: Text2)
                    LabeledValue("Confidence", p.confidence?.label?.substringBefore(" ") ?: "--", Modifier.weight(1f))
                    LabeledValue("Coverage", p.coverage?.let { "${(it * 100).roundToInt()}%" } ?: "--", Modifier.weight(1f))
                }
                VSpace(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bolt, null, tint = if (p.peak == PeakAlignment.WITHIN_PEAK) Cyan else Text2, modifier = Modifier.size(16.dp))
                    HSpace(8.dp)
                    Text(p.peak.label, style = MaterialTheme.typography.bodyMedium, color = Text1)
                }
                VSpace(8.dp)
                Muted("Prep status reads your latest score, sleep and recovery. It's not a prediction of how the Moment will go.")
            }
        }
        if (s.event.startMs < System.currentTimeMillis() && s.event.status != MomentStatus.CANCELLED) {
            item {
                Section("How did you do?") {
                    FacesRow(myReflection?.rating) { v ->
                        scope.launch { g.cognitive.store.saveReflection(EventReflection(s.event.id, v, System.currentTimeMillis())); onChanged() }
                    }
                    myReflection?.let { rf ->
                        val score = s.history.lastOrNull { it.score != null }?.score
                        VSpace(14.dp)
                        Text("You rated it ${rf.rating}/5" + (score?.let { " · readiness that day $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = Text1)
                        Muted("Over time you'll see how readiness and results line up. It shows a pattern, not the cause.")
                    }
                }
            }
        }
        if (s.todayTasks.isNotEmpty()) {
            item {
                val done = s.todayTasks.count { it.id in s.doneTaskIds }
                Section("Today's prep", trailing = { Text("$done/${s.todayTasks.size}", fontFamily = Mono, fontSize = 12.sp, color = Cyan) }) {
                    s.todayTasks.forEachIndexed { i, t ->
                        val checked = t.id in s.doneTaskIds
                        var open by remember(t.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    scope.launch {
                                        g.cognitive.store.setTaskDone(s.event.id, today, t.id, !checked, System.currentTimeMillis())
                                        onChanged()
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Cyan, checkmarkColor = Ink, uncheckedColor = Text2),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.bodyLarge, color = if (checked) Text2 else Text1)
                                Text(t.category.label.uppercase(java.util.Locale.ENGLISH), fontSize = 10.sp, letterSpacing = 1.sp, color = Text2)
                            }
                        }
                        AnimatedVisibility(open) {
                            Column(Modifier.padding(start = 48.dp, bottom = 8.dp)) {
                                Muted(t.detail)
                                t.evidence?.let { VSpace(4.dp); Text("Why: $it", style = MaterialTheme.typography.bodySmall, color = Cyan) }
                            }
                        }
                        if (i < s.todayTasks.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                    }
                }
            }
        }
        if (s.history.isNotEmpty()) item {
            Section("Run-up") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    val eventDay = g.cognitive.dayKeys.of(s.event.startMs)
                    s.history.forEach { d ->
                        val offset = g.cognitive.dayKeys.daysBetween(d.dayKey, eventDay)
                        val isToday = d.dayKey == today
                        val c = d.score?.let { scoreColor(it) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.score?.toString() ?: "·", fontFamily = Mono, fontSize = 11.sp, color = c ?: Text2)
                            VSpace(4.dp)
                            Box(
                                Modifier.width(16.dp).height(((d.score ?: 6) * 0.7f).dp).clip(RoundedCornerShape(2.dp))
                                    .background(c ?: Hairline)
                                    .border(BorderStroke(if (isToday) 1.5.dp else 0.dp, if (isToday) Text1 else Color.Transparent), RoundedCornerShape(2.dp)),
                            )
                            VSpace(6.dp)
                            Text(if (offset == 0) "D" else "-$offset", fontFamily = Mono, fontSize = 10.sp, color = if (isToday) Cyan else Text2)
                        }
                    }
                }
                VSpace(8.dp)
                Muted("Your daily score in the run-up. Blank days had too little data.")
            }
        }
        if (active) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onEdit, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Cyan),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("EDIT", letterSpacing = 1.5.sp, color = Cyan, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = { confirmCancel = true }, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, CriticalRed.copy(alpha = 0.8f)),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = CriticalRed.copy(alpha = 0.08f), contentColor = CriticalRed),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Icon(Icons.Rounded.EventBusy, null, modifier = Modifier.size(16.dp))
                    HSpace(6.dp)
                    Text("CANCEL MOMENT", letterSpacing = 0.8.sp, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            containerColor = Panel,
            title = { Text("Cancel \"${s.event.title}\"?") },
            text = { Text("It moves to your history and frees up a slot. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        g.cognitive.cancelMoment(s.event.id)
                        g.pushToWatch()
                        confirmCancel = false
                        onChanged()
                    }
                }) { Text("CANCEL MOMENT", letterSpacing = 1.2.sp, color = CriticalRed) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("KEEP IT", letterSpacing = 1.5.sp, color = Cyan) } },
        )
    }
}

/** On-demand guide: the score scale, the five dimensions, Beat the Clock windows, and the limits. */
@Composable
fun GuideContent(weights: Map<Dimension, Double> = DefaultScoring.DEFAULT.weights) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
        Eyebrow("Guide")
        VSpace(6.dp)
        Text("Reading your score", style = MaterialTheme.typography.headlineSmall, color = Text1)
        Muted("Your cognitive state (0–100) shows how ready you are to focus and make sharp calls today, measured against your own normal.")
        VSpace(18.dp)
        ReadinessBand.entries.forEachIndexed { i, b ->
            val upper = if (i == 0) 100 else ReadinessBand.entries[i - 1].minScore - 1
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${b.minScore}–$upper".padEnd(7), fontFamily = Mono, fontSize = 13.sp, color = b.color, modifier = Modifier.width(72.dp))
                Herc(b.mood(), size = 30.dp)
                HSpace(10.dp)
                Column {
                    Eyebrow(b.label, color = b.color)
                    Text(b.headline, style = MaterialTheme.typography.bodyMedium, color = Text1)
                }
            }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("--".padEnd(7), fontFamily = Mono, fontSize = 13.sp, color = InsufficientColor, modifier = Modifier.width(72.dp))
            Herc(HercMood.SLEEPING, size = 30.dp)
            HSpace(10.dp)
            Column {
                Eyebrow("Insufficient data", color = InsufficientColor)
                Text("No sleep yet, or under 70% of the data. Herc won't guess, so no number is shown.", style = MaterialTheme.typography.bodyMedium, color = Text1)
            }
        }
        VSpace(18.dp)
        Eyebrow("Five dimensions", color = Text1)
        VSpace(8.dp)
        Dimension.entries.forEach { d ->
            val w = weights[d] ?: 0.0
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(d.icon, null, tint = Cyan, modifier = Modifier.size(16.dp))
                HSpace(10.dp)
                Text(d.label, style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
                Text("${(w * 100).roundToInt()}%", fontFamily = Mono, color = Text1)
            }
            ScoreBar((w * 250).toInt().coerceAtMost(100), Cyan, 2.dp)
        }
        VSpace(8.dp)
        Muted("Missing dimensions are left out and the rest re-weighted. Coverage says how much of the weight had data; confidence follows from it.")
        VSpace(18.dp)
        Eyebrow("Beat the Clock", color = Text1)
        VSpace(6.dp)
        Muted("Optional. Play any time, once or several times a day; the day counts the median of your runs. Herc files each run under a part of your day:")
        VSpace(8.dp)
        AssessmentWindow.entries.forEach { w ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 6.dp).background(w.color))
                HSpace(10.dp)
                Text(w.label, style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
                Text("${w.fromHours.roundToInt()}–${w.toHours.roundToInt()} h after waking", style = MaterialTheme.typography.bodySmall, color = Text2)
            }
        }
        VSpace(8.dp)
        Muted("After 14 days with enough runs in every part of the day, Herc predicts your peak focus window.")
        VSpace(18.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = Text2, modifier = Modifier.size(14.dp))
            HSpace(8.dp)
            Muted("An estimate for everyday decisions, not a medical device.")
        }
    }
}

/** Compact − value + control (hours step by 1, minutes by 15). */
@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, Hairline, RoundedCornerShape(6.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clickable(onClick = onMinus), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Remove, "Less", tint = Text2, modifier = Modifier.size(16.dp))
        }
        Text(value, fontFamily = Mono, fontSize = 18.sp, color = Text1, modifier = Modifier.width(30.dp), textAlign = TextAlign.Center)
        Box(Modifier.size(32.dp).clickable(onClick = onPlus), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, "More", tint = Text2, modifier = Modifier.size(16.dp))
        }
    }
}


/** The Plan tab's call to action: a glowing pill that breathes harder while there is nothing planned yet. */
@Composable
fun MomentFab(empty: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "fab")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (empty) 1400 else 2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val pill = RoundedCornerShape(50)
    Box(contentAlignment = Alignment.Center) {
        // Soft halo behind the pill.
        Box(
            Modifier.matchParentSize().graphicsLayer {
                val k = 1f + (if (empty) 0.10f else 0.05f) * pulse
                scaleX = k; scaleY = k + 0.12f * pulse
                alpha = (if (empty) 0.45f else 0.28f) * (1f - 0.6f * pulse)
            }.clip(pill).background(Brush.horizontalGradient(listOf(Cyan, MomentGreen))),
        )
        Row(
            Modifier.clip(pill).background(Brush.horizontalGradient(listOf(Cyan, MomentGreen)))
                .clickable(onClickLabel = "Add a Moment", onClick = onClick)
                .padding(start = 8.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Ink), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, null, tint = MomentGreen, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = 90f * pulse * if (empty) 1f else 0f })
            }
            HSpace(12.dp)
            Text("Add a Moment", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val MomentGreen = Color(0xFF2EE6A0)
