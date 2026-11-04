package com.hercules.mobile.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hercules.mobile.MobileGraph
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.RoundValidity
import com.hercules.shared.readiness.SessionStatus
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Stage { INTRO, READY, RUNNING, BETWEEN, SAVING, RESULT }

/**
 * Beat the Clock (spec §4): three rounds that get longer, 15 to 30 s. The first three seconds are counted on screen. Start, then stop when you think the target
 * has passed; no clock is shown. Timing uses `SystemClock.elapsedRealtimeNanos()` only. Leaving the app while a round
 * runs marks that round interrupted. Only complete sessions are stored.
 */
@Composable
fun BeatTheClockScreen(g: MobileGraph, padding: PaddingValues, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val sessionId = remember { UUID.randomUUID().toString() }
    val plan = remember { BeatTheClock.plan() }
    val rounds = remember { mutableStateListOf<CognitiveRound>() }
    var stage by remember { mutableStateOf(Stage.INTRO) }
    var startNanos by remember { mutableLongStateOf(0L) }
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    var lastInterrupted by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CognitiveSession?>(null) }
    var playsAfter by remember { mutableIntStateOf(0) }
    // Optional self-report, asked in the break before the last round. Preset to the middle so skipping it is one tap.
    var energy by remember { mutableIntStateOf(DEFAULT_FEEL) }
    var stress by remember { mutableIntStateOf(DEFAULT_FEEL) }
    val index = rounds.size

    // Games played today on either device.
    val playsToday by produceState<Int?>(null) { value = g.cognitive.playsToday() }

    fun finishRound(interrupted: Boolean) {
        val stop = SystemClock.elapsedRealtimeNanos()
        rounds += BeatTheClock.round("$sessionId:$index", sessionId, index, plan[index], startNanos, stop, interrupted)
        lastInterrupted = interrupted
        if (rounds.size < plan.size) {
            stage = Stage.BETWEEN
            return
        }
        stage = Stage.SAVING
        scope.launch {
            val c = g.cognitive
            val s = c.recordAssessment(sessionId, DeviceKind.PHONE, g.deviceId, sessionStartMs, System.currentTimeMillis(), rounds.toList(), energy = energy, stress = stress)
            playsAfter = c.playsToday()
            result = s
            stage = Stage.RESULT
            g.pushToWatch()
        }
    }

    // Losing focus during a running round invalidates it (spec §4.5).
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE && stage == Stage.RUNNING) finishRound(interrupted = true) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = stage != Stage.SAVING) { onClose() }

    Column(
        Modifier.fillMaxSize().background(Ink).padding(padding).padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Beat the Clock", color = Cyan, modifier = Modifier.weight(1f))
            if (stage != Stage.SAVING) IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Text2) }
        }
        when (stage) {
            Stage.INTRO -> Intro(playsToday) {
                sessionStartMs = System.currentTimeMillis()
                stage = Stage.READY
            }
            Stage.READY, Stage.BETWEEN, Stage.RUNNING -> {
                VSpace(12.dp)
                RoundDots(plan.size, rounds)
                VSpace(28.dp)
                val target = plan[index] / 1000
                Text("Round ${index + 1} of ${plan.size}", style = MaterialTheme.typography.bodyMedium, color = Text2)
                VSpace(6.dp)
                Text("$target seconds", fontFamily = Mono, fontSize = 44.sp, color = Text1)
                VSpace(10.dp)
                // "1… 2… 3…" right after START, then the count disappears and the rest is up to you.
                var shown by remember(index) { mutableIntStateOf(0) }
                LaunchedEffect(stage, index) {
                    shown = 0
                    if (stage != Stage.RUNNING) return@LaunchedEffect
                    for (n in 1..BeatTheClock.COUNT_SHOWN_SECONDS) {
                        shown = n
                        delay(1_000)
                    }
                    shown = 0
                }
                Box(Modifier.height(68.dp), contentAlignment = Alignment.Center) {
                    when {
                        stage == Stage.RUNNING && shown > 0 -> Text("$shown…", fontFamily = Mono, fontSize = 40.sp, color = MomentOrange)
                        stage == Stage.RUNNING -> Text("Keep counting in your head", style = MaterialTheme.typography.titleMedium, color = Text1)
                        stage == Stage.BETWEEN && lastInterrupted -> Muted("That round didn't count because you left the app.", Modifier.padding(horizontal = 8.dp))
                        stage == Stage.BETWEEN && rounds.isNotEmpty() -> LastRound(rounds.last())
                        else -> Muted("Tap START. We count 1, 2, 3 with you, then you're on your own. Tap STOP at $target seconds.", Modifier.padding(horizontal = 8.dp))
                    }
                }
                if (stage == Stage.BETWEEN && index == FEEL_AFTER_ROUND) {
                    VSpace(20.dp)
                    FeelCheck(energy, stress, onEnergy = { energy = it }, onStress = { stress = it })
                }
                VSpace(36.dp)
                BigButton(running = stage == Stage.RUNNING) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (stage == Stage.RUNNING) finishRound(interrupted = false)
                    else { startNanos = SystemClock.elapsedRealtimeNanos(); stage = Stage.RUNNING }
                }
            }
            Stage.SAVING -> { VSpace(80.dp); Muted("Saving…") }
            Stage.RESULT -> result?.let { Result(it, playsAfter, onClose) }
        }
        VSpace(32.dp)
    }
}

@Composable
private fun Intro(playsToday: Int?, onStart: () -> Unit) {
    val plays = playsToday ?: 0
    VSpace(12.dp)
    Herc(HercMood.GOOD, size = 110.dp)
    VSpace(8.dp)
    Text("How well can you feel time?", style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
    VSpace(6.dp)
    Muted("A quick game that shows how sharp you are right now.", Modifier.padding(horizontal = 4.dp))
    VSpace(18.dp)
    PanelBox(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow("How to play", color = Cyan)
            VSpace(10.dp)
            HowStep(1, "You'll see a time between 15 and 30 seconds. Each round is a bit longer.")
            HowStep(2, "Tap START. We count 1, 2, 3 with you, then the count disappears.")
            HowStep(3, "Keep counting in your head and tap STOP when you think the time is up.")
            VSpace(4.dp)
            Muted("3 rounds · about 2 minutes. The closer you get, the sharper you are.")
        }
    }
    VSpace(12.dp)
    Muted(
        when {
            plays == 0 -> "Play any time, as often as you like."
            else -> "Game ${plays + 1} today. Today's score is the median of your games."
        },
        Modifier.padding(horizontal = 4.dp),
    )
    VSpace(20.dp)
    Button(
        onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
    ) { Text("PLAY", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) }
    VSpace(10.dp)
    Muted("Stay in the app during a round, or that round won't count.")
}

/** Right after STOP: the real time on the clock and how far off it was, so you can calibrate the next round. */
@Composable
private fun LastRound(r: CognitiveRound) {
    val diff = (r.actualMs - r.targetMs) / 1000.0
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Last round: you stopped at %.1f s".format(UiLocale, r.actualMs / 1000.0), style = MaterialTheme.typography.titleMedium, color = Text1)
        Text(
            when {
                r.validity != RoundValidity.VALID -> "${r.validity.label}: this round won't count"
                abs(diff) < 0.1 -> "Spot on the ${r.targetMs / 1000} s goal"
                diff < 0 -> "%.1f s early for the %d s goal".format(UiLocale, -diff, r.targetMs / 1000)
                else -> "%.1f s late for the %d s goal".format(UiLocale, diff, r.targetMs / 1000)
            },
            style = MaterialTheme.typography.bodyMedium, color = if (r.validity == RoundValidity.VALID && abs(diff) <= r.targetMs / 1000.0 * 0.1) Cyan else MomentOrange,
        )
    }
}

@Composable
private fun HowStep(n: Int, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(BorderStroke(1.dp, Cyan), CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 12.sp, color = Cyan)
        }
        HSpace(12.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
    }
}

private const val DEFAULT_FEEL = 3
/** The break after this many rounds asks how you feel, before the last round, never while a round is being timed. */
private const val FEEL_AFTER_ROUND = 2

/** Check before the last round: energy and stress, 1–5, preset to 3. Optional, and never part of the score. */
@Composable
private fun FeelCheck(energy: Int, stress: Int, onEnergy: (Int) -> Unit, onStress: (Int) -> Unit) {
    PanelBox(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow("Quick check · optional", color = Cyan)
            VSpace(4.dp)
            Muted("How are you feeling right now? Skip it if you like.")
            VSpace(12.dp)
            FeelRow("Energy", "Drained", "Charged", energy, onEnergy)
            VSpace(12.dp)
            FeelRow("Stress", "Calm", "Tense", stress, onStress)
        }
    }
}

@Composable
private fun FeelRow(label: String, low: String, high: String, value: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
        Text("$low · $high", style = MaterialTheme.typography.bodySmall, color = Text2)
    }
    VSpace(6.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..5).forEach { v ->
            val on = v == value
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) Cyan.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                    .border(BorderStroke(1.dp, if (on) Cyan else Hairline), RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "$label $v of 5") { onPick(v) },
                contentAlignment = Alignment.Center,
            ) { Text("$v", fontFamily = Mono, fontSize = 16.sp, color = if (on) Cyan else Text1) }
        }
    }
}

@Composable
private fun RoundDots(total: Int, rounds: List<CognitiveRound>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            val r = rounds.getOrNull(i)
            Box(
                Modifier.size(10.dp).clip(CircleShape).background(
                    when {
                        r == null -> Hairline
                        r.validity == RoundValidity.VALID -> Cyan
                        else -> Text2
                    },
                ),
            )
        }
    }
}

/** The round button. Breathes while counting so the screen visibly works, without showing time. */
@Composable
private fun BigButton(running: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "btc")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val color = if (running) MomentOrange else Cyan
    Box(contentAlignment = Alignment.Center) {
        if (running) {
            Box(Modifier.size(220.dp).graphicsLayer { val k = 1f + 0.06f * pulse; scaleX = k; scaleY = k; alpha = 0.35f * (1 - pulse) }.clip(CircleShape).background(color))
        }
        Box(
            Modifier.size(200.dp).clip(CircleShape).background(color.copy(alpha = 0.12f))
                .border(BorderStroke(2.dp, color), CircleShape).clickable(onClickLabel = if (running) "Stop" else "Start", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (running) "STOP" else "START", color = color, fontSize = 30.sp, letterSpacing = 4.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val MomentOrange = androidx.compose.ui.graphics.Color(0xFFFF8C4C)

@Composable
private fun Result(s: CognitiveSession, playsAfter: Int, onClose: () -> Unit) {
    VSpace(12.dp)
    Herc(if (s.status == SessionStatus.INVALID) HercMood.FAIR else HercMood.GOOD, size = 96.dp)
    VSpace(6.dp)
    Text(
        when {
            s.status == SessionStatus.INVALID -> "Not enough rounds counted"
            else -> "Your score: ${s.cognitiveScore ?: "--"}"
        },
        style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center,
    )
    VSpace(6.dp)
    Muted(
        when {
            s.status == SessionStatus.INVALID -> "At least 2 of the 3 rounds need to count. Stay in the app while a round runs."
            playsAfter > 1 -> "50 is average; higher means sharper. Today's score is the median of your $playsAfter games."
            else -> "50 is average; higher means sharper. After 3 games, average means your own usual."
        },
        Modifier.padding(horizontal = 4.dp),
    )
    if (s.cognitiveScore != null) {
        VSpace(16.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Accuracy" to s.accuracyScore, "Consistency" to s.consistencyScore, "Bias" to s.biasScore).forEach { (label, v) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).border(BorderStroke(1.dp, Hairline), RoundedCornerShape(6.dp)).padding(10.dp)) {
                    Text("${v ?: "--"}", fontFamily = Mono, fontSize = 22.sp, color = Text1)
                    Text(label.uppercase(UiLocale), fontSize = 10.sp, letterSpacing = 1.sp, color = Text2)
                }
            }
        }
    }
    if (s.energy != null || s.stress != null) {
        VSpace(10.dp)
        Muted("You felt: energy ${s.energy ?: "–"}/5 · stress ${s.stress ?: "–"}/5")
    }
    VSpace(18.dp)
    Section("How close you got") {
        s.rounds.sortedBy { it.order }.forEach { r ->
            val pct = (r.relativeError * 100).roundToInt()
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${r.targetMs / 1000}s goal", fontFamily = Mono, fontSize = 14.sp, color = Text2, modifier = Modifier.weight(0.25f))
                Text("%.1fs".format(UiLocale, r.actualMs / 1000.0), fontFamily = Mono, fontSize = 14.sp, color = Text1, modifier = Modifier.weight(0.22f))
                Text(
                    if (r.validity != RoundValidity.VALID) r.validity.label else if (abs(pct) <= 1) "spot on" else if (pct < 0) "${-pct}% early" else "$pct% late",
                    style = MaterialTheme.typography.bodySmall, color = if (r.validity == RoundValidity.VALID) Text1 else Text2, modifier = Modifier.weight(0.53f),
                )
            }
        }
        s.bias?.let {
            VSpace(6.dp)
            Muted(if (it < -0.02) "You tend to stop early: time feels faster to you right now." else if (it > 0.02) "You tend to stop late: time feels slower to you right now." else "No clear early or late habit.")
        }
    }
    VSpace(18.dp)
    Button(
        onClick = onClose, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
    ) { Text("DONE", letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) }
}
