package com.hercules.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.hercules.shared.cognitive.WatchState
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.ReactionRun
import com.hercules.shared.readiness.RoundValidity
import com.hercules.shared.readiness.SessionStatus
import com.hercules.shared.readiness.TrialPhase
import com.hercules.wear.graph
import com.hercules.wear.sync.SyncWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

private data class BtcInfo(val playsToday: Int, val wakeMs: Long)

private enum class WStage { INTRO, RUNNING, STOPPED, FEEL, SAVING, RESULT }

private val BCyan = Color(0xFF3FE0FF)
private val BOrange = Color(0xFFFF8C4C)
private val BText2 = Color(0xFF8B90A8)
private val BLine = Color(0xFF262838)

/**
 * Reaction Test on the wrist: the same 3-minute reaction test as the phone, run by the shared [ReactionRun]. Tap
 * anywhere on the screen when Herc appears. The baseline is the watch's own (a watch screen reacts differently from a
 * phone). The wake time comes from the phone's state when fresh, so both devices file tests under the same part of
 * the day. Stored unsynced and handed to the phone by the next sync.
 */
@Composable
internal fun BeatTheClockScreen(state: WatchState?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val sessionId = remember { UUID.randomUUID().toString() }
    var stage by remember { mutableStateOf(WStage.INTRO) }
    var trials by remember { mutableStateOf<List<CognitiveRound>>(emptyList()) }
    var sessionStartMs by remember { mutableLongStateOf(0L) }
    var result by remember { mutableStateOf<CognitiveSession?>(null) }
    var energy by remember { mutableIntStateOf(3) }
    var stress by remember { mutableIntStateOf(3) }

    val info by produceState<BtcInfo?>(null, state) {
        val c = g.cognitive
        val wake = state?.wakeMs ?: c.wakeMs(c.today())
        value = BtcInfo(maxOf(state?.playsToday ?: 0, c.playsToday()), wake)
    }

    fun save(feel: Boolean) {
        stage = WStage.SAVING
        scope.launch {
            try {
                result = g.cognitive.recordAssessment(
                    sessionId, DeviceKind.WATCH, g.deviceId, sessionStartMs, System.currentTimeMillis(), trials,
                    wakeMs = info?.wakeMs, energy = energy.takeIf { feel }, stress = stress.takeIf { feel },
                )
                stage = WStage.RESULT
                SyncWorker.runNow(ctx)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("HerculesBtc", "could not save Reaction Test", e)
                onDone()
            }
        }
    }

    // Leaving the app mid-test stops it without saving.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE && stage == WStage.RUNNING) stage = WStage.STOPPED }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // Three minutes without a button press: keep the screen on, or the watch dims and the test stops.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(stage == WStage.RUNNING) {
        view.keepScreenOn = stage == WStage.RUNNING
        onDispose { view.keepScreenOn = false }
    }

    when (stage) {
        WStage.INTRO -> Intro(info) { sessionStartMs = System.currentTimeMillis(); stage = WStage.RUNNING }
        WStage.RUNNING -> Run(sessionId) { trials = it; stage = WStage.FEEL }
        WStage.STOPPED -> Message("Test stopped", "You left the app, so it wasn't saved.", onDone)
        WStage.FEEL -> FeelCheck(energy, stress, { energy = it }, { stress = it }, onSave = { save(true) }, onSkip = { save(false) })
        WStage.SAVING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Saving…", color = BText2, fontSize = 12.sp) }
        WStage.RESULT -> result?.let { Result(it, onDone) }
    }
}

/** The test: the whole round screen is the tap target, with time left as a ring around the edge. */
@Composable
private fun Run(sessionId: String, onDone: (List<CognitiveRound>) -> Unit) {
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
    val progress = (trial - 1).toFloat() / BeatTheClock.TRIALS
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { awaitEachGesture { taps.trySend(awaitFirstDown().uptimeMillis) } }
            .semantics { contentDescription = "Tap when Herc appears" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val w = 3.dp.toPx()
            val box = Size(size.width - w, size.height - w)
            drawArc(BLine, 0f, 360f, false, Offset(w / 2, w / 2), box, style = Stroke(w))
            drawArc(BCyan, -90f, 360f * progress, false, Offset(w / 2, w / 2), box, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$trial of ${BeatTheClock.TRIALS}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = BText2)
            Spacer(Modifier.size(4.dp))
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                when (phase) {
                    TrialPhase.WAIT -> Box(Modifier.size(8.dp).clip(CircleShape).background(BLine))
                    TrialPhase.SHOW -> Herc(HercMood.PRIME, size = 88.dp)
                    TrialPhase.REACTED -> Herc(HercMood.PRIME, Modifier.alpha(0.35f), size = 88.dp)
                    TrialPhase.TOO_SOON -> Text("Too soon", fontSize = 18.sp, color = BOrange, fontWeight = FontWeight.SemiBold)
                }
            }
            val shown = when (phase) { TrialPhase.SHOW -> liveMs; TrialPhase.REACTED -> lastMs; else -> null }
            Text(
                shown?.let { "$it ms" } ?: " ",
                fontFamily = FontFamily.Monospace, fontSize = 22.sp,
                color = when {
                    phase == TrialPhase.SHOW -> Color.White
                    (lastMs ?: 0) < BeatTheClock.LAPSE_MS -> BCyan
                    else -> BOrange
                },
            )
        }
    }
}

@Composable
private fun Intro(i: BtcInfo?, onStart: () -> Unit) {
    val listState = rememberScalingLazyListState()
    val plays = i?.playsToday ?: 0
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Herc(HercMood.GOOD, size = 48.dp) }
            item { Text("Reaction Test", style = MaterialTheme.typography.titleSmall, color = Color.White) }
            item { Text("How quick are you right now?", fontSize = 12.sp, color = Color.White, textAlign = TextAlign.Center) }
            item { HowStep(1, "Herc pops up every 1 to 4 s") }
            item { HowStep(2, "Tap anywhere, fast") }
            item { HowStep(3, "Don't tap before he's there") }
            item { Text("${BeatTheClock.TRIALS} taps · about 3 minutes", fontSize = 10.sp, color = BText2, textAlign = TextAlign.Center) }
            item {
                Text(
                    when {
                        i == null -> ""
                        plays == 0 -> "PLAY ANY TIME"
                        else -> "TEST ${plays + 1} TODAY"
                    },
                    fontSize = 9.sp, letterSpacing = 1.2.sp, color = BCyan, textAlign = TextAlign.Center,
                )
            }
            item { Button(onClick = onStart, enabled = i != null, modifier = Modifier.fillMaxWidth()) { Text("Start") } }
        }
    }
}

@Composable
private fun Message(title: String, body: String, onDone: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Herc(HercMood.FAIR, size = 44.dp) }
            item { Text(title, style = MaterialTheme.typography.titleSmall, color = Color.White) }
            item { Text(body, fontSize = 11.sp, color = BText2, textAlign = TextAlign.Center) }
            item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Close") } }
        }
    }
}

/** After the test, on the wrist: two steppers, preset to 3. Optional; never part of the score. */
@Composable
private fun FeelCheck(energy: Int, stress: Int, onEnergy: (Int) -> Unit, onStress: (Int) -> Unit, onSave: () -> Unit, onSkip: () -> Unit) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Text("DONE · QUICK CHECK", fontSize = 9.sp, letterSpacing = 1.2.sp, color = BCyan) }
            item { Text("How do you feel?", style = MaterialTheme.typography.titleSmall, color = Color.White) }
            item { Stepper("Energy", BeatTheClock.ENERGY_LABELS, null, energy, higherIsBetter = true, onEnergy) }
            item { Stepper("Stress", BeatTheClock.STRESS_LABELS, BeatTheClock.STRESS_FACES, stress, higherIsBetter = false, onStress) }
            item { Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("See result") } }
            item { OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Skip") } }
        }
    }
}

@Composable
private fun Stepper(label: String, words: List<String>, faces: List<String>?, value: Int, higherIsBetter: Boolean, onChange: (Int) -> Unit) {
    // Centred label over a centred stepper: nothing sits near the round edge. The word under the picture changes
    // with the value, coloured from red (bad) to green (good), so the scale reads without guessing.
    val good = if (higherIsBetter) value else 6 - value
    val tone = listOf(Color(0xFFFF4D6D), Color(0xFFFF8A3D), Color(0xFFFFC53D), Color(0xFFB4E05A), Color(0xFF4ADE80))[good - 1]
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), fontSize = 9.sp, letterSpacing = 1.2.sp, color = BText2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", "Less $label", enabled = value > 1) { onChange(value - 1) }
            // Energy is Herc himself, from drained to prime; stress uses plain faces.
            if (faces == null) Herc(listOf(HercMood.DRAINED, HercMood.LOW, HercMood.FAIR, HercMood.GOOD, HercMood.PRIME)[value - 1], Modifier.padding(horizontal = 6.dp), size = 54.dp)
            else Text(faces[value - 1], fontSize = 34.sp, modifier = Modifier.padding(horizontal = 10.dp))
            StepButton("+", "More $label", enabled = value < 5) { onChange(value + 1) }
        }
        Text(
            words[value - 1], fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tone,
            modifier = Modifier.semantics { contentDescription = "$label: ${words[value - 1]}" },
        )
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
    val score = s.cognitiveScore
    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { Herc(if (score == null) HercMood.FAIR else HercMood.GOOD, size = 44.dp) }
            item {
                Text(
                    score?.let { "$it/100" } ?: "Didn't count",
                    fontSize = if (score != null) 36.sp else 15.sp, fontWeight = if (score != null) FontWeight.Thin else FontWeight.Medium,
                    color = Color.White, textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    if (score == null) "Not counted: needs ${BeatTheClock.MIN_TRIALS}+ reactions and few false starts."
                    else "${BeatTheClock.verdict(score)}\n50 is your usual. Higher is faster.",
                    fontSize = 10.sp, color = BText2, textAlign = TextAlign.Center,
                )
            }
            if (s.status != SessionStatus.INVALID) {
                item { StatRow("Typical", s.medianMs?.let { "${it.roundToInt()} ms" } ?: "–") }
                item { StatRow("Slow (${BeatTheClock.LAPSE_MS}+ ms)", "${s.lapses ?: 0}") }
            }
            item { StatRow("False starts", "${s.falseStarts ?: 0}") }
            item { StatRow("Reactions", "${s.rounds.count { it.validity == RoundValidity.VALID }}") }
            item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Done") } }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, color = BText2, modifier = Modifier.weight(1f))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White)
    }
}
