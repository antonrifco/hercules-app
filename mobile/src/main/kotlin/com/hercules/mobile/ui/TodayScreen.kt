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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hercules.mobile.MobileGraph
import com.hercules.mobile.R
import com.hercules.shared.cognitive.CheckIn
import com.hercules.shared.cognitive.Confidence
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.PrepPhase
import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.SleepNight
import com.hercules.shared.cognitive.Suggestion
import com.hercules.shared.cognitive.WindowKind
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
)

private const val HOUR = 3_600_000L

@Composable
fun TodayScreen(
    g: MobileGraph,
    data: TodayData,
    nowMs: Long,
    padding: PaddingValues,
    onOpenEvent: (String) -> Unit,
    onOpenGuide: () -> Unit,
) {
    val r = data.readiness
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 32.dp),
        verticalArrangement = CardGap,
    ) {
        item { TopLine(nowMs, onOpenGuide) }
        item { if (r != null) Hero(r) else EmptyHero() }
        item { DayStrip(g, data, r, nowMs, onOpenEvent) }
        if (r != null) item { Readouts(r) }
        data.nextEvent?.let { ev -> item { NextEvent(ev, onOpenEvent) } }
        if (r != null) item { Tips(r.suggestions) }
        item { SleepBlock(data.sleep, data.sleepWeek, data.today, g) }
        item { CheckInBlock(g, data.checkIn) }
        if (data.trend.size >= 2) item { TrendBlock(data.trend, g) }
    }
}

@Composable
private fun TopLine(nowMs: Long, onOpenGuide: () -> Unit) {
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
private fun Hero(r: Readiness) {
    val c = r.band.color
    Column(Modifier.fillMaxWidth().background(glow(c)), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreGauge(r.score, c)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(r.band.icon, null, tint = c, modifier = Modifier.size(16.dp))
            HSpace(8.dp)
            Eyebrow(r.band.label, color = c)
        }
        VSpace(10.dp)
        Text(r.band.headline, style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
        if (r.confidence != Confidence.HIGH) {
            VSpace(10.dp)
            Pill("${r.confidence.label} ${r.baselineDays.coerceAtMost(7)}/7", Text2)
        }
    }
}

@Composable
private fun EmptyHero() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Illustration(R.drawable.ill_sleep, Modifier.fillMaxWidth().height(260.dp))
        Eyebrow("No reading yet", color = Cyan)
        VSpace(8.dp)
        Text("Sleep sets the dial", style = MaterialTheme.typography.headlineSmall, color = Text1)
        Muted("Wear the watch to bed tonight, or log a check-in below.")
    }
}

/**
 * The signature view: one line for the whole day. Last night's sleep, focus windows,
 * the current moment and today's events, on a single time axis.
 */
@Composable
private fun DayStrip(g: MobileGraph, data: TodayData, r: Readiness?, nowMs: Long, onOpenEvent: (String) -> Unit) {
    val dk = g.cognitive.dayKeys
    val dayStart = dk.startOf(data.today)
    val start = minOf(data.sleep?.startMs ?: (dayStart - 2 * HOUR), dayStart - HOUR)
    val end = dayStart + 24 * HOUR
    val measurer = rememberTextMeasurer()
    val events = data.todayEvents.filter { it.event.startMs in start..end }
    val label = TextStyle(color = Text2, fontSize = 9.sp, letterSpacing = 1.sp)
    val eventLabel = TextStyle(color = Text1, fontSize = 10.sp)

    Section("Your day", trailing = { r?.let { Eyebrow("peak ${clockOf(it.windows.first { w -> w.kind == WindowKind.PEAK }.startMs)}", color = Cyan) } }) {
        Canvas(Modifier.fillMaxWidth().height(if (events.isEmpty()) 92.dp else 120.dp)) {
            val span = (end - start).toFloat()
            fun x(t: Long) = ((t - start) / span).coerceIn(0f, 1f) * size.width
            val top = if (events.isEmpty()) 8.dp.toPx() else 36.dp.toPx()
            val sleepH = 26.dp.toPx()
            val bandY = top + sleepH + 8.dp.toPx()
            val bandH = 8.dp.toPx()

            // hour grid
            var t = dayStart - 6 * HOUR
            while (t <= end) {
                if (t >= start) {
                    val gx = x(t)
                    drawLine(Hairline, Offset(gx, top - 4.dp.toPx()), Offset(gx, bandY + bandH + 6.dp.toPx()), 1f)
                    val txt = measurer.measure(clockOf(t).take(2), label)
                    drawText(txt, topLeft = Offset((gx - txt.size.width / 2).coerceIn(0f, size.width - txt.size.width), bandY + bandH + 10.dp.toPx()))
                }
                t += 6 * HOUR
            }
            // sleep block
            data.sleep?.let { s ->
                val x0 = x(s.startMs); val x1 = x(s.endMs)
                drawRoundRect(Brand.copy(alpha = 0.35f), Offset(x0, top), Size(x1 - x0, sleepH), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                val txt = measurer.measure("SLEEP ${formatDuration(s.asleepMinutes)}", label.copy(color = Text1))
                if (txt.size.width < x1 - x0 - 8) drawText(txt, topLeft = Offset(x0 + 6.dp.toPx(), top + (sleepH - txt.size.height) / 2))
            }
            // focus windows
            r?.windows?.forEach { w ->
                val x0 = x(w.startMs); val x1 = x(w.endMs)
                if (x1 > x0) drawRect(w.kind.color, Offset(x0, bandY), Size(x1 - x0 - 1f, bandH))
            }
            // events: diamond pins with labels
            events.forEach { e ->
                val ex = x(e.event.startMs)
                val d = 5.dp.toPx()
                val y = top - 14.dp.toPx()
                drawPath(Path().apply { moveTo(ex, y - d); lineTo(ex + d, y); lineTo(ex, y + d); lineTo(ex - d, y); close() }, e.advice?.verdict?.color ?: Cyan)
                drawLine(Text2, Offset(ex, y + d), Offset(ex, bandY + bandH), 1f)
                // Up to 27 title characters, then "…"; and never wider than the strip (ellipsized by width too).
                val txt = measurer.measure(
                    "${clockOf(e.event.startMs)} ${e.event.title.clip(27)}",
                    eventLabel,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    constraints = Constraints(maxWidth = size.width.toInt()),
                )
                val lx = (ex + 8.dp.toPx()).coerceAtMost(size.width - txt.size.width).coerceAtLeast(0f)
                drawText(txt, topLeft = Offset(lx, y - txt.size.height / 2 - 12.dp.toPx()))
            }
            // now
            if (nowMs in start..end) {
                val nx = x(nowMs)
                drawLine(Cyan, Offset(nx, top - 6.dp.toPx()), Offset(nx, bandY + bandH + 4.dp.toPx()), 2.dp.toPx())
                drawCircle(Cyan, 4.dp.toPx(), Offset(nx, bandY + bandH + 4.dp.toPx()))
            }
        }
        if (events.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                events.forEach { e -> TextButton(onClick = { onOpenEvent(e.event.id) }) { Text("Open ${e.event.title.clip(27)}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(Brand.copy(alpha = 0.6f), "Sleep")
            Legend(WindowKind.PEAK.color, "Peak")
            Legend(WindowKind.SECOND_PEAK.color, "2nd wind")
            Legend(WindowKind.DIP.color, "Dip")
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 10.dp, height = 4.dp).background(color))
        HSpace(6.dp)
        Text(text.uppercase(UiLocale), fontSize = 9.sp, letterSpacing = 1.sp, color = Text2)
    }
}

/** Four instrument readouts; tap one to see what it measured. */
@Composable
private fun Readouts(r: Readiness) {
    var selected by remember { mutableStateOf<Int?>(null) }
    Section("Readout") {
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
                    Text(c.kind.label.uppercase(UiLocale), fontSize = 9.sp, letterSpacing = 1.sp, color = Text2, maxLines = 1)
                }
            }
        }
        selected?.let { i ->
            val c = r.components[i]
            VSpace(10.dp)
            Text(c.detail, style = MaterialTheme.typography.bodyMedium, color = Text1)
            Muted("${(c.kind.weight * 100).roundToInt()}% of the score. ${c.kind.explanation}")
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
    Section("Today's moves") {
        tips.take(4).forEachIndexed { i, s ->
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
            if (i < tips.take(4).lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
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
                Muted("Your watch tracks sleep on its own. Fix the times only if they look wrong.")
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

@Composable
private fun CheckInBlock(g: MobileGraph, existing: CheckIn?) {
    val scope = rememberCoroutineScope()
    var editing by remember(existing) { mutableStateOf(existing == null) }
    var energy by remember(existing) { mutableIntStateOf(existing?.energy ?: 3) }
    var stress by remember(existing) { mutableIntStateOf(existing?.stress ?: 3) }
    Section("Check-in", trailing = { if (!editing && existing != null) TextButton(onClick = { editing = true }) { Text("Edit", fontSize = 12.sp) } }) {
        if (!editing && existing != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Illustration(R.drawable.ill_checkin, Modifier.size(96.dp))
                HSpace(12.dp)
                FactTile(Icons.Rounded.Bolt, "${existing.energy}/5", "energy", Modifier.weight(1f), scoreColor(existing.energy * 20))
                FactTile(Icons.Rounded.Psychology, "${existing.stress}/5", "stress", Modifier.weight(1f), scoreColor(120 - existing.stress * 20))
            }
            return@Section
        }
        SegmentScale("Energy", Icons.Rounded.Bolt, "Drained", "Charged", energy, higherIsBetter = true) { energy = it }
        VSpace(16.dp)
        SegmentScale("Stress", Icons.Rounded.Psychology, "Calm", "Tense", stress, higherIsBetter = false) { stress = it }
        VSpace(14.dp)
        Button(
            onClick = {
                scope.launch {
                    g.cognitive.saveCheckIn(energy, stress)
                    g.pushToWatch()
                    editing = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
        ) { Text("LOG CHECK-IN", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) }
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
        Text(low.uppercase(UiLocale), fontSize = 9.sp, letterSpacing = 1.sp, color = Text2, modifier = Modifier.weight(1f))
        Text(high.uppercase(UiLocale), fontSize = 9.sp, letterSpacing = 1.sp, color = Text2)
    }
}

@Composable
private fun TrendBlock(trend: List<Readiness>, g: MobileGraph) {
    val dk = g.cognitive.dayKeys
    val today = trend.last().dayKey
    val keys = (-13..0).map { dk.plusDays(today, it) }
    val byKey = trend.associateBy { it.dayKey }
    val avg = trend.map { it.score }.average().roundToInt()
    Section("14 days", trailing = { Text("avg $avg", fontFamily = Mono, fontSize = 12.sp, color = Text2) }) {
        MiniBars(
            values = keys.map { k -> byKey[k]?.score?.toFloat() },
            max = 100f,
            labels = keys.mapIndexed { i, k -> if (i % 2 == 1) k.takeLast(2) else "" },
            colorOf = { scoreColor(it.roundToInt()) },
            target = 70f,
        )
    }
}

/** The nearest event that isn't over yet. */
fun List<EventStatus>.nextRelevant(nowMs: Long): EventStatus? =
    firstOrNull { it.phase != PrepPhase.DONE && it.event.startMs > nowMs - 2 * 3_600_000L }
