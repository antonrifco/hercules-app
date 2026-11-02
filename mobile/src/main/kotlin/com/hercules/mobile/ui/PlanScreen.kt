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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hercules.mobile.MobileGraph
import com.hercules.mobile.R
import com.hercules.shared.cognitive.ComponentKind
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.EventType
import com.hercules.shared.cognitive.Importance
import com.hercules.shared.cognitive.PlanEvent
import com.hercules.shared.cognitive.PrepPhase
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.Verdict
import com.hercules.shared.cognitive.WindowKind
import kotlinx.coroutines.launch
import java.util.UUID

private const val HOUR = 3_600_000L
private const val MAX_DAYS_AHEAD = 7

@Composable
fun PlanScreen(statuses: List<EventStatus>, padding: PaddingValues, onOpen: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = padding.calculateBottomPadding() + 96.dp),
    ) {
        item {
            Eyebrow("Countdown")
            VSpace(6.dp)
            Text("Big moments ahead", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
            VSpace(20.dp)
        }
        if (statuses.isEmpty()) {
            item {
                Illustration(R.drawable.ill_plan, Modifier.fillMaxWidth().height(240.dp))
                VSpace(8.dp)
                Text("Nothing on the runway", style = MaterialTheme.typography.titleMedium, color = Text1)
                Muted("Add an interview, pitch or exam up to 7 days out. Hercules builds the run-up for you.")
            }
        }
        itemsIndexed(statuses, key = { _, s -> s.event.id }) { i, s -> RailRow(s, first = i == 0, last = i == statuses.lastIndex) { onOpen(s.event.id) } }
    }
}

/** One stop on the countdown rail: node + D-x on the left, the event on the right. */
@Composable
private fun RailRow(s: EventStatus, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val accent = s.advice?.verdict?.color ?: if (s.phase == PrepPhase.DONE) Text2 else Cyan
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick)) {
        Box(Modifier.width(64.dp).fillMaxHeight()) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(s.event.type.icon, null, tint = Text2, modifier = Modifier.size(14.dp))
                            Spacer6()
                            Eyebrow("${shortDate(s.event.startMs)} · ${clockOf(s.event.startMs)}")
                        }
                        VSpace(6.dp)
                        Text(s.event.title, style = MaterialTheme.typography.titleMedium, color = Text1, maxLines = 2)
                        VSpace(10.dp)
                        when {
                            s.advice != null -> Pill(s.advice!!.verdict.label, s.advice!!.verdict.color)
                            s.phase == PrepPhase.DONE -> Pill("Done", Text2)
                            else -> Pill("${s.phase.label} · ${s.doneTaskIds.size}/${s.todayTasks.size}", Cyan, icon = Icons.Rounded.TaskAlt)
                        }
                    }
                    s.prepScore?.let { ScoreRing(it, "prep", 54.dp) }
                }
            }
        }
    }
}

@Composable private fun Spacer6() = HSpace(6.dp)

// ------------------------------------------------------------------ add event

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEventDialog(g: MobileGraph, nowMs: Long, onDismiss: () -> Unit) {
    val dk = g.cognitive.dayKeys
    val today = dk.of(nowMs)
    var title by remember { mutableStateOf("") }
    var importance by remember { mutableStateOf(Importance.HIGH) }
    var dayOffset by remember { mutableIntStateOf(3) }
    var hour by remember { mutableIntStateOf(10) }
    var minute by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val titleFocus = remember { FocusRequester() }
    val startMs = dk.startOf(dk.plusDays(today, dayOffset)) + hour * HOUR + minute * 60_000L
    val valid = title.isNotBlank() && startMs > nowMs
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        // Type the title straight away. The dialog window needs a frame before it can take focus.
        delay(200)
        runCatching { titleFocus.requestFocus() }
        keyboard?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Wider than the platform default so the three stakes chips fit on one line.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        containerColor = Panel,
        shape = RoundedCornerShape(8.dp),
        title = { Text("New big moment", fontWeight = FontWeight.Light) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    title, { title = it },
                    label = { Text("Title") },
                    placeholder = { Text("e.g. Interview at Acme") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                )
                VSpace(14.dp)
                Eyebrow("Stakes")
                VSpace(6.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Importance.entries.forEach { i ->
                        FilterChip(
                            importance == i, { importance = i },
                            label = { Text(i.label, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                VSpace(14.dp)
                Eyebrow("When")
                VSpace(8.dp)
                // Make the 7-day promise explicit right where the user picks the date.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cyan.copy(alpha = 0.07f))
                        .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp)).padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(Icons.Rounded.TrackChanges, null, tint = Cyan, modifier = Modifier.size(18.dp))
                    HSpace(10.dp)
                    Text(
                        "Pick a day up to 7 days out. Hercules tracks your readiness every day until then and guides your sleep and prep, so your mind is at its best on the big day.",
                        style = MaterialTheme.typography.bodySmall, color = Text1,
                    )
                }
                VSpace(10.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..MAX_DAYS_AHEAD).forEach { d ->
                        val label = when (d) {
                            0 -> "Today"
                            1 -> "Tomorrow"
                            else -> shortDate(dk.startOf(dk.plusDays(today, d)) + 12 * HOUR)
                        }
                        FilterChip(dayOffset == d, { dayOffset = d }, { Text(label) })
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
                val ws = g.cognitive.engine.windows(dk.startOf(dk.plusDays(today, dayOffset)) + 7 * HOUR)
                val kind = ws.firstOrNull { startMs >= it.startMs && startMs < it.endMs }?.kind
                when {
                    startMs <= nowMs -> Muted("That time has already passed.")
                    kind != null -> TimingHint(kind, clockOf(startMs))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                scope.launch {
                    g.cognitive.store.upsertEvent(PlanEvent(UUID.randomUUID().toString(), title.trim(), EventType.fromTitle(title), startMs, importance, "", nowMs))
                    g.pushToWatch()
                    onDismiss()
                }
            }) { Text("SAVE", letterSpacing = 1.5.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", letterSpacing = 1.5.sp, color = Text2) } },
    )
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

/** Says in plain words whether the chosen time is a good one for a demanding moment. */
@Composable
private fun TimingHint(kind: WindowKind, at: String) {
    val (good, text) = when (kind) {
        WindowKind.PEAK -> true to "Good timing. Around $at your focus is usually at its sharpest of the day."
        WindowKind.SECOND_PEAK -> true to "Good timing. $at is usually your second wind, when focus comes back."
        WindowKind.STEADY -> true to "Fine timing. Around $at your focus is usually steady."
        WindowKind.WARMUP -> false to "A bit early. At $at your brain is usually still waking up; late morning is sharper."
        WindowKind.DIP -> false to "Tricky timing. $at usually falls in the afternoon dip; the morning is sharper."
        WindowKind.EVENING, WindowKind.LATE -> false to "Late. By $at focus usually drops; earlier in the day is sharper."
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(width = 10.dp, height = 4.dp).background(kind.color))
        HSpace(8.dp)
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (good) Text1 else Text2)
    }
}

// ------------------------------------------------------------------ event detail

@Composable
fun EventDetail(
    g: MobileGraph,
    s: EventStatus,
    padding: PaddingValues,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    val today = g.cognitive.today()
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
                    Eyebrow("Stakes · ${s.event.importance.label}", color = Cyan)
                }
                VSpace(8.dp)
                Text(s.event.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Light, color = Text1)
                Muted("${longDate(s.event.startMs)} · ${clockOf(s.event.startMs)}")
                VSpace(22.dp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                s.daysUntil < 0 -> "Done"
                                s.daysUntil == 0 -> "Today"
                                else -> "D-${s.daysUntil}"
                            },
                            fontSize = 64.sp, fontWeight = FontWeight.Thin, color = Text1, letterSpacing = (-2).sp,
                        )
                        Eyebrow(s.phase.label, color = Cyan)
                    }
                    s.prepScore?.let { ScoreRing(it, "prep") }
                    s.projectedScore?.let { HSpace(14.dp); ScoreRing(it, "forecast") }
                }
            }
        }
        s.advice?.let { a ->
            item {
                Section("Event-day call") {
                    Illustration(if (a.verdict == Verdict.GO || a.verdict == Verdict.GO_WITH_PREP) R.drawable.ill_go else R.drawable.ill_wait, Modifier.fillMaxWidth().height(180.dp))
                    VSpace(8.dp)
                    PanelBox(accent = a.verdict.color) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.verdict.label, style = MaterialTheme.typography.titleLarge, color = a.verdict.color)
                                    VSpace(4.dp)
                                    Text(a.headline, style = MaterialTheme.typography.bodyMedium, color = Text1)
                                }
                                HSpace(12.dp)
                                ScoreRing(a.expectedScore, "at ${clockOf(s.event.startMs)}", 60.dp)
                            }
                            VSpace(12.dp)
                            a.actions.take(3).forEach {
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = a.verdict.color, modifier = Modifier.size(14.dp).padding(top = 3.dp))
                                    HSpace(10.dp)
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Text1)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Section("Run-up") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    s.history.forEachIndexed { i, d ->
                        val offset = s.history.size - 1 - i
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
            }
        }
        if (s.todayTasks.isNotEmpty()) {
            item {
                Section("Today's checklist", trailing = { Text("${s.doneTaskIds.size}/${s.todayTasks.size}", fontFamily = Mono, fontSize = 12.sp, color = Cyan) }) {
                    s.todayTasks.forEachIndexed { i, t ->
                        val done = t.id in s.doneTaskIds
                        var open by remember(t.id) { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = done,
                                onCheckedChange = {
                                    scope.launch {
                                        g.cognitive.store.setTaskDone(s.event.id, today, t.id, !done, System.currentTimeMillis())
                                        onChanged()
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Cyan, checkmarkColor = Ink, uncheckedColor = Text2),
                            )
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), color = if (done) Text2 else Text1)
                        }
                        AnimatedVisibility(open) { Muted(t.detail, Modifier.padding(start = 48.dp, bottom = 8.dp)) }
                        if (i < s.todayTasks.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Hairline)) {
                Text("DELETE", letterSpacing = 1.5.sp, color = Text2)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Panel,
            title = { Text("Delete \"${s.event.title}\"?") },
            text = { Text("Its checklist progress is deleted too.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        g.cognitive.store.deleteEvent(s.event.id)
                        g.pushToWatch()
                        confirmDelete = false
                        onDeleted()
                    }
                }) { Text("DELETE", letterSpacing = 1.5.sp) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL", letterSpacing = 1.5.sp, color = Text2) } },
        )
    }
}

/** On-demand guide: the score scale, what feeds it, and its limits. */
@Composable
fun GuideContent() {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState())) {
        Eyebrow("Guide")
        VSpace(6.dp)
        Text("Reading the dial", style = MaterialTheme.typography.headlineSmall, color = Text1)
        Muted("How ready your brain is for focus and judgement today, against your own normal.")
        VSpace(18.dp)
        ReadinessBand.entries.forEachIndexed { i, b ->
            val upper = if (i == 0) 100 else ReadinessBand.entries[i - 1].minScore - 1
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${b.minScore}–$upper".padEnd(7), fontFamily = Mono, fontSize = 13.sp, color = b.color, modifier = Modifier.width(72.dp))
                Icon(b.icon, null, tint = b.color, modifier = Modifier.size(18.dp))
                HSpace(12.dp)
                Column {
                    Eyebrow(b.label, color = b.color)
                    Text(b.headline, style = MaterialTheme.typography.bodyMedium, color = Text1)
                }
            }
        }
        VSpace(18.dp)
        Eyebrow("Ingredients", color = Text1)
        VSpace(8.dp)
        ComponentKind.entries.forEach { c ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(c.icon, null, tint = Cyan, modifier = Modifier.size(16.dp))
                HSpace(10.dp)
                Text(c.label, style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
                Text("${(c.weight * 100).toInt()}%", fontFamily = Mono, color = Text1)
            }
            ScoreBar((c.weight * 250).toInt(), Cyan, 2.dp)
        }
        VSpace(18.dp)
        Eyebrow("Daily rhythm", color = Text1)
        VSpace(8.dp)
        listOf(WindowKind.WARMUP, WindowKind.PEAK, WindowKind.DIP, WindowKind.SECOND_PEAK, WindowKind.LATE).forEach { k ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 6.dp).background(k.color))
                HSpace(10.dp)
                Text(k.label, style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
                Text(if (k.adjustment > 0) "+${k.adjustment}" else "${k.adjustment}", fontFamily = Mono, color = Text2)
            }
        }
        VSpace(18.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Info, null, tint = Text2, modifier = Modifier.size(14.dp))
            HSpace(8.dp)
            Muted("A heuristic guide, not a medical device. The first 7 days are calibration.")
        }
    }
}

