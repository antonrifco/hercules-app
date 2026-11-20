package com.hercules.mobile.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
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
import com.hercules.shared.readiness.ReactionRun
import com.hercules.shared.readiness.SessionStatus
import com.hercules.shared.readiness.TrialPhase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

private enum class Stage { INTRO, RUNNING, STOPPED, FEEL, SAVING, RESULT }

/**
 * Reaction Test (spec §4): a 3-minute reaction test. Herc appears after a random 1–4 s wait; tap anywhere as fast as
 * you can. The rules and loop live in the shared [ReactionRun], so the watch plays exactly the same test. Taps use the
 * touch-down time and Herc's first frame on screen, both on `SystemClock.uptimeMillis()`. Leaving the app stops the
 * test without saving it.
 */
@Composable
fun BeatTheClockScreen(g: MobileGraph, padding: PaddingValues, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sessionId = remember { UUID.randomUUID().toString() }
    var stage by remember { mutableStateOf(Stage.INTRO) }
    var trials by remember { mutableStateOf<List<CognitiveRound>>(emptyList()) }
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<CognitiveSession?>(null) }
    var playsAfter by remember { mutableIntStateOf(0) }
    var energy by remember { mutableIntStateOf(DEFAULT_FEEL) }
    var stress by remember { mutableIntStateOf(DEFAULT_FEEL) }
    val playsToday by produceState<Int?>(null) { value = g.cognitive.playsToday() }

    fun save(feel: Boolean) {
        stage = Stage.SAVING
        scope.launch {
            val c = g.cognitive
            try {
                val s = c.recordAssessment(
                    sessionId, DeviceKind.PHONE, g.deviceId, sessionStartMs, System.currentTimeMillis(), trials,
                    energy = energy.takeIf { feel }, stress = stress.takeIf { feel },
                )
                playsAfter = c.playsToday()
                result = s
                stage = Stage.RESULT
                g.pushToWatch()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("Hercules", "could not save Reaction Test", e)
                onClose()
            }
        }
    }

    // Leaving the app mid-test stops it: a reaction test only means something with full attention.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE && stage == Stage.RUNNING) stage = Stage.STOPPED }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    BackHandler(enabled = stage != Stage.SAVING) { onClose() }
    val view = LocalView.current
    DisposableEffect(stage == Stage.RUNNING) {
        view.keepScreenOn = stage == Stage.RUNNING
        onDispose { view.keepScreenOn = false }
    }

    if (stage == Stage.RUNNING) {
        Run(sessionId, padding, onDone = { trials = it; stage = Stage.FEEL }, onClose = onClose)
        return
    }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(padding).padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Reaction Test", color = Cyan, modifier = Modifier.weight(1f))
            if (stage != Stage.SAVING) IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Text2) }
        }
        when (stage) {
            Stage.INTRO -> Intro(playsToday) { sessionStartMs = System.currentTimeMillis(); stage = Stage.RUNNING }
            Stage.STOPPED -> {
                VSpace(40.dp)
                Herc(HercMood.FAIR, size = 96.dp)
                VSpace(8.dp)
                Text("Test stopped", style = MaterialTheme.typography.headlineSmall, color = Text1)
                VSpace(6.dp)
                Muted("You left the app, so this test wasn't saved. Stay on this screen for the full 3 minutes.", Modifier.padding(horizontal = 4.dp))
                VSpace(20.dp)
                PrimaryButton("CLOSE", onClose)
            }
            Stage.FEEL -> {
                VSpace(16.dp)
                Text("Done! One last thing", style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
                VSpace(16.dp)
                FeelCheck(energy, stress, onEnergy = { energy = it }, onStress = { stress = it })
                VSpace(20.dp)
                PrimaryButton("SEE RESULT") { save(feel = true) }
                VSpace(8.dp)
                OutlinedButton(onClick = { save(feel = false) }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp)) {
                    Text("SKIP", color = Text2, letterSpacing = 1.5.sp)
                }
            }
            Stage.SAVING -> { VSpace(80.dp); Muted("Saving…") }
            Stage.RESULT -> result?.let { Result(it, playsAfter, onClose) }
            Stage.RUNNING -> Unit
        }
        VSpace(32.dp)
    }
}

/** The test itself: the whole screen is the tap target. Herc shows up, you tap, your time shows for a second. */
@Composable
private fun Run(sessionId: String, padding: PaddingValues, onDone: (List<CognitiveRound>) -> Unit, onClose: () -> Unit) {
    val taps = remember { Channel<Long>(Channel.UNLIMITED) }
    var phase by remember { mutableStateOf(TrialPhase.WAIT) }
    var lastMs by remember { mutableStateOf<Long?>(null) }
    var trial by remember { mutableIntStateOf(1) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var liveMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val trials = ReactionRun.play(
            sessionId, taps, SystemClock::uptimeMillis,
            onPhase = { p, ms, n -> phase = p; lastMs = ms; trial = n },
            onScreen = { withFrameMillis { }; SystemClock.uptimeMillis().also { shownAt = it; liveMs = 0 } },
        )
        onDone(trials)
    }
    // The timer runs from the moment Herc is on screen and freezes on the tap.
    LaunchedEffect(phase) {
        if (phase == TrialPhase.SHOW) while (true) withFrameMillis { liveMs = (SystemClock.uptimeMillis() - shownAt).coerceIn(0, BeatTheClock.TIMEOUT_MS) }
    }
    Column(
        Modifier.fillMaxSize().background(Ink).padding(padding)
            .pointerInput(Unit) { awaitEachGesture { taps.trySend(awaitFirstDown().uptimeMillis) } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Reaction Test · tap when Herc appears", color = Cyan, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Stop test", tint = Text2) }
        }
        Text("$trial of ${BeatTheClock.TRIALS}", fontFamily = Mono, fontSize = 16.sp, color = Text2)
        VSpace(6.dp)
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(4.dp)) {
            val y = size.height / 2
            drawLine(Hairline, Offset(0f, y), Offset(size.width, y), size.height, cap = StrokeCap.Round)
            drawLine(Cyan, Offset(0f, y), Offset(size.width * (trial - 1) / BeatTheClock.TRIALS, y), size.height, cap = StrokeCap.Round)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
                    when (phase) {
                        TrialPhase.WAIT -> Box(Modifier.size(14.dp).clip(CircleShape).background(Hairline))
                        TrialPhase.SHOW -> Herc(HercMood.PRIME, size = 180.dp)
                        TrialPhase.REACTED -> Herc(HercMood.PRIME, Modifier.alpha(0.35f), size = 180.dp)
                        TrialPhase.TOO_SOON -> Text("Too soon", fontSize = 32.sp, color = MomentOrange, fontWeight = FontWeight.SemiBold)
                    }
                }
                VSpace(16.dp)
                // Same place for the running and the stopped time, so it reads as one stopwatch.
                val shown = when (phase) { TrialPhase.SHOW -> liveMs; TrialPhase.REACTED -> lastMs; else -> null }
                Text(
                    shown?.let { "$it ms" } ?: " ",
                    fontFamily = Mono, fontSize = 44.sp,
                    color = when {
                        phase == TrialPhase.SHOW -> Text1
                        (lastMs ?: 0) < BeatTheClock.LAPSE_MS -> Cyan
                        else -> MomentOrange
                    },
                )
            }
        }
        Muted("Wait for Herc. Tap anywhere.", Modifier.padding(bottom = 32.dp))
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
    ) { Text(label, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Intro(playsToday: Int?, onStart: () -> Unit) {
    val plays = playsToday ?: 0
    VSpace(12.dp)
    Herc(HercMood.GOOD, size = 110.dp)
    VSpace(8.dp)
    Text("How quick are you right now?", style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
    VSpace(6.dp)
    Muted("A 3-minute reaction test. Tired brains react slower and miss more, so it shows how sharp you are today.", Modifier.padding(horizontal = 4.dp))
    VSpace(18.dp)
    PanelBox(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow("How to play", color = Cyan)
            VSpace(10.dp)
            HowStep(1, "Herc pops up at random moments, every 1 to 4 seconds.")
            HowStep(2, "Tap anywhere as fast as you can when you see him.")
            HowStep(3, "Don't tap before he appears: that's a false start.")
            VSpace(4.dp)
            Muted("${BeatTheClock.TRIALS} taps · about 3 minutes. Scored against your own usual speed on this phone.")
        }
    }
    VSpace(12.dp)
    Muted(
        if (plays == 0) "Play any time, as often as you like." else "Test ${plays + 1} today. Today's score is the median of your tests.",
        Modifier.padding(horizontal = 4.dp),
    )
    VSpace(20.dp)
    PrimaryButton("START", onStart)
    VSpace(10.dp)
    Muted("Find a quiet moment and stay on this screen until it ends.")
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

/** Energy 1 (drained) to 5 (charged) as one of Herc's five faces. */
private fun energyMood(v: Int) = listOf(HercMood.DRAINED, HercMood.LOW, HercMood.FAIR, HercMood.GOOD, HercMood.PRIME)[v - 1]

private const val DEFAULT_FEEL = 3

/** Check after the test: energy and stress, 1–5, preset to 3. Optional, and never part of the score. */
@Composable
private fun FeelCheck(energy: Int, stress: Int, onEnergy: (Int) -> Unit, onStress: (Int) -> Unit) {
    PanelBox(Modifier.fillMaxWidth()) {
        Column {
            Eyebrow("Quick check · optional", color = Cyan)
            VSpace(4.dp)
            Muted("How are you feeling right now? Skip it if you like.")
            VSpace(12.dp)
            FeelRow("Energy", BeatTheClock.ENERGY_LABELS, null, energy, onEnergy)
            VSpace(16.dp)
            FeelRow("Stress", BeatTheClock.STRESS_LABELS, BeatTheClock.STRESS_FACES, stress, onStress)
        }
    }
}

@Composable
private fun FeelRow(label: String, words: List<String>, faces: List<String>?, value: Int, onPick: (Int) -> Unit) {
    // The label says what the chosen face means, so nobody has to guess a face.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Text1, modifier = Modifier.weight(1f))
        Text(words[value - 1], style = MaterialTheme.typography.bodyMedium, color = Cyan, fontWeight = FontWeight.SemiBold)
    }
    VSpace(8.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { v ->
            val on = v == value
            Box(
                Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) Cyan.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent)
                    .border(BorderStroke(1.dp, if (on) Cyan else Hairline), RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = "$label: ${words[v - 1]}") { onPick(v) },
                contentAlignment = Alignment.Center,
            ) {
                // Energy is Herc himself, from drained to prime; stress uses plain faces.
                if (faces == null) Herc(energyMood(v), Modifier.alpha(if (on) 1f else 0.5f), size = 52.dp)
                else Text(faces[v - 1], fontSize = 30.sp, modifier = Modifier.alpha(if (on) 1f else 0.5f))
            }
        }
    }
    VSpace(4.dp)
    Row(Modifier.fillMaxWidth()) {
        Text(words.first(), fontSize = 10.sp, color = Text2, modifier = Modifier.weight(1f))
        Text(words.last(), fontSize = 10.sp, color = Text2)
    }
}

private val MomentOrange = androidx.compose.ui.graphics.Color(0xFFFF8C4C)

/** Where a game score sits from 0 to 100, with the "usual" mark at 50. */
@Composable
private fun ScoreScale(score: Int) {
    val color = scoreColor(score)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val y = size.height / 2
            drawLine(Hairline, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Text2, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
            drawCircle(color, 6.dp.toPx(), Offset(size.width * score / 100f, y))
        }
        VSpace(2.dp)
        Row(Modifier.fillMaxWidth()) {
            Text("0 · much slower", fontSize = 10.sp, color = Text2, modifier = Modifier.weight(1f))
            Text("50 · usual", fontSize = 10.sp, color = Text2, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Text("100 · faster", fontSize = 10.sp, color = Text2, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun Result(s: CognitiveSession, playsAfter: Int, onClose: () -> Unit) {
    VSpace(12.dp)
    Herc(if (s.status == SessionStatus.INVALID) HercMood.FAIR else HercMood.GOOD, size = 96.dp)
    VSpace(6.dp)
    val score = s.cognitiveScore
    if (s.status == SessionStatus.INVALID || score == null) {
        Text("This test didn't count", style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
        VSpace(6.dp)
        Muted(
            "A test needs at least ${BeatTheClock.MIN_TRIALS} reactions, with no more than a quarter of taps before Herc appears.",
            Modifier.padding(horizontal = 4.dp),
        )
    } else {
        Text(BeatTheClock.verdict(score), style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
        VSpace(4.dp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$score", fontFamily = Mono, fontSize = 40.sp, color = scoreColor(score))
            Text(" / 100", fontFamily = Mono, fontSize = 16.sp, color = Text2, modifier = Modifier.padding(bottom = 7.dp))
        }
        VSpace(10.dp)
        ScoreScale(score)
        VSpace(10.dp)
        val usual = BeatTheClock.usualMs(s)?.roundToInt()
        Muted(
            buildString {
                append("50 means your usual speed, higher means faster.")
                append(if (playsAfter > 1) " Today counts the middle score of your $playsAfter tests." else " Until Herc knows your speed, usual means a typical player.")
                if (usual != null) append(" Your usual is about $usual ms.")
            },
            Modifier.padding(horizontal = 4.dp),
        )
    }
    VSpace(18.dp)
    Section("Your test") {
        Stat("Typical reaction", s.medianMs?.let { "${it.roundToInt()} ms" } ?: "–")
        Stat("Reactions", "${s.rounds.count { it.validity == com.hercules.shared.readiness.RoundValidity.VALID }}")
        Stat("Slow reactions (${BeatTheClock.LAPSE_MS} ms+)", s.lapses?.toString() ?: "–")
        Stat("False starts", "${s.falseStarts ?: 0}")
        if (s.energy != null || s.stress != null) {
            Stat("You felt", "energy ${s.energy?.let { "$it/5" } ?: "–"} · stress ${s.stress?.let { BeatTheClock.STRESS_FACES[it - 1] } ?: "–"}")
        }
        VSpace(6.dp)
        Muted("Slow reactions and false starts are what tiredness looks like: attention slips, then you rush.")
    }
    VSpace(18.dp)
    PrimaryButton("DONE", onClose)
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Text2, modifier = Modifier.weight(1f))
        Text(value, fontFamily = Mono, fontSize = 14.sp, color = Text1)
    }
}
