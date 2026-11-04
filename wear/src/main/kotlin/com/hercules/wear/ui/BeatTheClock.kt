package com.hercules.wear.ui

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.hercules.shared.cognitive.WatchState
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.RoundValidity
import com.hercules.shared.readiness.SessionStatus
import com.hercules.wear.graph
import com.hercules.wear.sync.SyncWorker
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

private data class BtcInfo(val playsToday: Int, val wakeMs: Long)

private enum class WStage { INTRO, ROUND, RUNNING, FEEL, SAVING, RESULT }

private val BCyan = Color(0xFF3FE0FF)
private val BOrange = Color(0xFFFF8C4C)
private val BText2 = Color(0xFF8B90A8)
private val BLine = Color(0xFF262838)

/**
 * Beat the Clock on the wrist: the same three rounds and rules as the phone (spec §4), timed with
 * `SystemClock.elapsedRealtimeNanos()`. Optional and any time; the day counts the median of its runs. The wake time
 * comes from the phone's state when fresh, so both devices file runs under the same part of the day. Stored unsynced and handed to the phone by the next sync.
 */
@Composable
internal fun BeatTheClockScreen(state: WatchState?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val sessionId = remember { UUID.randomUUID().toString() }
    val plan = remember { BeatTheClock.plan() }
    val rounds = remember { mutableStateListOf<CognitiveRound>() }
    var stage by remember { mutableStateOf(WStage.INTRO) }
    var startNanos by remember { mutableLongStateOf(0L) }
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    var lastInterrupted by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CognitiveSession?>(null) }
    // Optional check before the last round, preset to the middle so skipping it is one tap. Never part of the score.
    var energy by remember { mutableIntStateOf(3) }
    var stress by remember { mutableIntStateOf(3) }
    val index = rounds.size

    val info by produceState<BtcInfo?>(null, state) {
        val c = g.cognitive
        val wake = state?.wakeMs ?: c.wakeMs(c.today())
        value = BtcInfo(maxOf(state?.playsToday ?: 0, c.playsToday()), wake)
    }

    fun finishRound(interrupted: Boolean) {
        val stop = SystemClock.elapsedRealtimeNanos()
        rounds += BeatTheClock.round("$sessionId:$index", sessionId, index, plan[index], startNanos, stop, interrupted)
        lastInterrupted = interrupted
        if (rounds.size < plan.size) { stage = if (rounds.size == BeatTheClock.ROUNDS - 1) WStage.FEEL else WStage.ROUND; return }
        stage = WStage.SAVING
        scope.launch {
            val c = g.cognitive
            val s = c.recordAssessment(
                sessionId, DeviceKind.WATCH, g.deviceId, sessionStartMs, System.currentTimeMillis(), rounds.toList(),
                wakeMs = info?.wakeMs, energy = energy, stress = stress,
            )
            result = s
            stage = WStage.RESULT
            SyncWorker.runNow(ctx)
        }
    }

    // Leaving the app during a running round makes it not count (spec §4.5).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE && stage == WStage.RUNNING) finishRound(interrupted = true) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    when (stage) {
        WStage.INTRO -> Intro(info) { sessionStartMs = System.currentTimeMillis(); stage = WStage.ROUND }
        WStage.ROUND, WStage.RUNNING -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val running = stage == WStage.RUNNING
            val color = if (running) BOrange else BCyan
            val t = rememberInfiniteTransition(label = "btc")
            val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(plan.size) { i ->
                        val r = rounds.getOrNull(i)
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (r == null) BLine else if (r.validity == RoundValidity.VALID) BCyan else BText2))
                    }
                }
                Spacer(Modifier.height(6.dp))
                var shown by remember(index) { mutableIntStateOf(0) }
                LaunchedEffect(running, index) {
                    shown = 0
                    if (!running) return@LaunchedEffect
                    for (n in 1..BeatTheClock.COUNT_SHOWN_SECONDS) { shown = n; delay(1_000) }
                    shown = 0
                }
                Text("${plan[index] / 1000} seconds", fontFamily = FontFamily.Monospace, fontSize = 20.sp, color = Color.White)
                val prev = rounds.lastOrNull()
                Text(
                    when {
                        running && shown > 0 -> "$shown…"
                        running -> "Keep counting…"
                        lastInterrupted -> "Last round didn't count"
                        prev != null -> "Stopped at ${offBy(prev)}"
                        else -> "Round ${index + 1} of ${plan.size}"
                    },
                    fontSize = if (running && shown > 0) 16.sp else 10.sp,
                    color = if (running && shown > 0) BOrange else if (!running && prev != null && !lastInterrupted) Color.White else BText2,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.size(96.dp)
                        .graphicsLayer { if (running) { val k = 1f + 0.05f * pulse; scaleX = k; scaleY = k } }
                        .clip(CircleShape).background(color.copy(alpha = 0.14f)).border(2.dp, color, CircleShape)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (running) finishRound(false) else { startNanos = SystemClock.elapsedRealtimeNanos(); stage = WStage.RUNNING }
                        }
                        .semantics { contentDescription = if (running) "Stop" else "Start" },
                    contentAlignment = Alignment.Center,
                ) { Text(if (running) "STOP" else "START", color = color, fontSize = 16.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        WStage.FEEL -> FeelCheck(energy, stress, rounds.lastOrNull(), { energy = it }, { stress = it }) { stage = WStage.ROUND }
        WStage.SAVING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Saving…", color = BText2, fontSize = 12.sp) }
        WStage.RESULT -> result?.let { Result(it, onDone) }
    }
}

@Composable
private fun Intro(i: BtcInfo?, onStart: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val plays = i?.playsToday ?: 0
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Herc(HercMood.GOOD, size = 48.dp) }
            item { Text("Beat the Clock", style = MaterialTheme.typography.titleSmall, color = Color.White) }
            item { Text("How well can you feel time?", fontSize = 12.sp, color = Color.White, textAlign = TextAlign.Center) }
            item { HowStep(1, "See a time: 15 to 30 s, longer each round") }
            item { HowStep(2, "Tap START. We count 1, 2, 3 with you") }
            item { HowStep(3, "Keep counting. Tap STOP when it's up") }
            item { Text("3 rounds · about 2 minutes", fontSize = 10.sp, color = BText2, textAlign = TextAlign.Center) }
            item {
                Text(
                    when {
                        i == null -> ""
                        plays == 0 -> "PLAY ANY TIME"
                        else -> "GAME ${plays + 1} TODAY"
                    },
                    fontSize = 9.sp, letterSpacing = 1.2.sp, color = BCyan, textAlign = TextAlign.Center,
                )
            }
            item { Button(onClick = onStart, enabled = i != null, modifier = Modifier.fillMaxWidth()) { Text("Play") } }
        }
    }
}

/** Check before the last round, on the wrist: two steppers, preset to 3. "Next round" skips or confirms. */
@Composable
private fun FeelCheck(energy: Int, stress: Int, last: CognitiveRound?, onEnergy: (Int) -> Unit, onStress: (Int) -> Unit, onNext: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (last != null && last.validity == RoundValidity.VALID) item { Text("Stopped at ${offBy(last)}", fontSize = 10.sp, color = Color.White, textAlign = TextAlign.Center) }
            item { Text("QUICK CHECK · OPTIONAL", fontSize = 9.sp, letterSpacing = 1.2.sp, color = BCyan) }
            item { Text("How do you feel?", style = MaterialTheme.typography.titleSmall, color = Color.White) }
            item { Stepper("Energy", energy, onEnergy) }
            item { Stepper("Stress", stress, onStress) }
            item { Button(onClick = onNext, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Next round") } }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    // Centred label over a centred stepper: nothing sits near the round edge.
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 9.sp, letterSpacing = 1.2.sp, color = BText2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", "Less $label", enabled = value > 1) { onChange(value - 1) }
            Text("$value", fontFamily = FontFamily.Monospace, fontSize = 18.sp, color = BCyan, modifier = Modifier.padding(horizontal = 14.dp))
            StepButton("+", "More $label", enabled = value < 5) { onChange(value + 1) }
        }
    }
}

@Composable
private fun StepButton(sign: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).border(1.dp, if (enabled) BCyan else BLine, CircleShape)
            .clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(sign, fontSize = 18.sp, color = if (enabled) Color.White else BText2) }
}

/** "12.8 s (0.8 late)": the real time of a round and how far off it was. */
private fun offBy(r: CognitiveRound): String {
    val diff = (r.actualMs - r.targetMs) / 1000.0
    val off = when {
        abs(diff) < 0.1 -> "spot on"
        diff < 0 -> "%.1f early".format(-diff)
        else -> "%.1f late".format(diff)
    }
    return "%.1f s (%s)".format(r.actualMs / 1000.0, off)
}

/** One numbered step of "how to play": number in a cyan ring, then a short line. */
@Composable
private fun HowStep(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, BCyan, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 10.sp, color = BCyan)
        }
        Spacer(Modifier.size(8.dp))
        Text(text, fontSize = 11.sp, color = Color.White, maxLines = 2)
    }
}

@Composable
private fun Result(s: CognitiveSession, onDone: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Herc(if (s.status == SessionStatus.INVALID) HercMood.FAIR else HercMood.GOOD, size = 44.dp) }
            item {
                Text(
                    when {
                        s.status == SessionStatus.INVALID -> "Not enough rounds counted"
                        else -> "${s.cognitiveScore ?: "--"}"
                    },
                    fontSize = if (s.cognitiveScore != null) 36.sp else 15.sp, fontWeight = if (s.cognitiveScore != null) FontWeight.Thin else FontWeight.Medium,
                    color = Color.White, textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    when {
                        s.status == SessionStatus.INVALID -> "2 of the 3 rounds must count."
                        else -> "50 is average. Higher is sharper."
                    },
                    fontSize = 10.sp, color = BText2, textAlign = TextAlign.Center,
                )
            }
            items(s.rounds.sortedBy { it.order }) { r ->
                val pct = (r.relativeError * 100).roundToInt()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.targetMs / 1000}s", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = BText2, modifier = Modifier.weight(0.25f))
                    Text("%.1fs".format(r.actualMs / 1000.0), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.White, modifier = Modifier.weight(0.3f))
                    Text(
                        if (r.validity != RoundValidity.VALID) r.validity.label else if (abs(pct) <= 1) "spot on" else if (pct < 0) "${-pct}% early" else "$pct% late",
                        fontSize = 10.sp, color = if (r.validity == RoundValidity.VALID) Color.White else BText2, modifier = Modifier.weight(0.45f), maxLines = 1,
                    )
                }
            }
            item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Done") } }
        }
    }
}
