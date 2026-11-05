package com.hercules.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SentimentNeutral
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.SentimentVeryDissatisfied
import androidx.compose.material.icons.rounded.SentimentVerySatisfied
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hercules.shared.cognitive.ActionKind
import com.hercules.shared.cognitive.DailyBrief
import com.hercules.shared.cognitive.Provenance
import com.hercules.shared.cognitive.RecommendedAction
import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.SleepAdvice
import com.hercules.shared.cognitive.TimeWindow
import com.hercules.shared.readiness.PeakResult
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.ReadinessCalculation
import com.hercules.shared.readiness.insufficientDetail
import com.hercules.shared.readiness.confidenceBasis
import com.hercules.shared.readiness.ReadinessConfidence
import androidx.compose.material.icons.rounded.Favorite
import kotlin.math.roundToInt
import com.hercules.shared.cognitive.learningNote

/** What a recommendation looks like: icon + the verb on its button. Short, action-oriented labels. */
private val ActionKind.icon: ImageVector
    get() = when (this) {
        ActionKind.ASSESS -> Icons.Rounded.Timer
        ActionKind.PREPARE -> Icons.Rounded.Flag
        ActionKind.REFLECT -> Icons.Rounded.RateReview
        ActionKind.SLEEP -> Icons.Rounded.Bedtime
        ActionKind.PEAK -> Icons.Rounded.Bolt
    }

private val ActionKind.button: String
    get() = when (this) {
        ActionKind.ASSESS -> "PLAY"
        ActionKind.PREPARE -> "GEAR UP"
        ActionKind.REFLECT -> "RATE IT"
        ActionKind.SLEEP -> "TONIGHT'S GAME PLAN"
        ActionKind.PEAK -> "SEE YOUR DAY"
    }

/** Answers "how am I doing?" in three seconds: the dial, one word, how sure it is, one sentence, one reason. */
@Composable
fun Briefing(r: Readiness, brief: DailyBrief, onWhy: () -> Unit, onLearnMore: () -> Unit) {
    val c = r.band.color
    Column(Modifier.fillMaxWidth().background(glow(c)), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreGauge(r.score, c, size = 208.dp, caption = "Your cognitive state")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(r.band.icon, null, tint = c, modifier = Modifier.size(16.dp))
            HSpace(8.dp)
            Text(r.band.label.uppercase(UiLocale), color = c, fontSize = 15.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
        }
        VSpace(6.dp)
        ConfidenceLine(r.calc)
        VSpace(10.dp)
        WhyChip(c, onWhy)
        VSpace(8.dp)
        Text(brief.headline, style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
        brief.reason?.let {
            VSpace(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(it.dimension.icon, null, tint = if (it.good) Cyan else r.band.color, modifier = Modifier.size(15.dp))
                HSpace(8.dp)
                Text(it.text, style = MaterialTheme.typography.bodyMedium, color = Text2)
            }
        }
        r.learningNote?.let {
            VSpace(10.dp)
            Row(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onLearnMore)) { Pill(it.trimEnd('.'), Text2) }
        }
    }
}

/** "High confidence · 100% of data" under the score, plus why when confidence is low. */
@Composable
fun ConfidenceLine(calc: ReadinessCalculation) {
    val confidence = calc.confidence
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(confidence.color, 7.dp)
            HSpace(6.dp)
            Text(confidence.label, style = MaterialTheme.typography.bodySmall, color = confidence.color, fontWeight = FontWeight.SemiBold)
            Text(" · ${calc.confidenceBasis}", style = MaterialTheme.typography.bodySmall, color = Text2)
        }
    }
}

/** Opens the score breakdown; sits right under the score it explains. */
@Composable
fun WhyChip(c: androidx.compose.ui.graphics.Color, onWhy: () -> Unit, label: String = "WHY THIS SCORE") {
    Row(
        Modifier.clip(RoundedCornerShape(50)).border(BorderStroke(1.dp, c.copy(alpha = 0.5f)), RoundedCornerShape(50))
            .clickable(onClickLabel = "See why", onClick = onWhy).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Insights, null, tint = c, modifier = Modifier.size(15.dp))
        HSpace(6.dp)
        Text(label, color = Text1, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
        HSpace(2.dp)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Text2, modifier = Modifier.size(16.dp))
    }
}

/**
 * No published score (INSUFFICIENT_DATA, spec §6): Herc wears the Fair face with grey eyes, says what is missing and
 * how much of the data is in. Never a number that looks like a score.
 */
@Composable
fun InsufficientCard(calc: ReadinessCalculation?, onWhy: () -> Unit, onSetup: () -> Unit, healthConnected: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Herc(HercMood.SLEEPING, size = 170.dp)
        Eyebrow("Insufficient data", color = InsufficientColor)
        VSpace(8.dp)
        Text(calc?.insufficientReason ?: "Waiting on the first reading", style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
        VSpace(8.dp)
        Text(
            calc?.insufficientDetail ?: "Every score starts with last night's sleep. Connect Health Connect or wear the watch to bed.",
            style = MaterialTheme.typography.bodyMedium, color = Text2, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (calc != null) {
            VSpace(12.dp)
            WhyChip(Text2, onWhy, "WHAT'S MISSING")
        }
        VSpace(14.dp)
        if (healthConnected) {
            // Linked but nothing arrives: the tracker app itself has to write to Health Connect.
            EmptyStep(Icons.Rounded.Favorite, "Health Connect is linked, but empty", "Turn on Health Connect sync in your tracker app (Mi Fitness, Samsung Health, Fitbit…). Herc picks it up from there.", onSetup)
        } else {
            EmptyStep(Icons.Rounded.Favorite, "Connect Health Connect", "Bring in sleep, HRV, resting heart rate, steps and workouts.", onSetup)
        }
        EmptyStep(Icons.Rounded.Watch, "Or wear the Hercules watch to bed", "It tracks your sleep for you.", onSetup)
    }
}

@Composable
private fun EmptyStep(icon: ImageVector, title: String, hint: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, Cyan, size = 32.dp, iconSize = 18.dp)
        HSpace(12.dp)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Text1)
            Muted(hint)
        }
    }
}

/**
 * The predicted peak cognitive window (spec §5). Only shown as a time when the prediction is PREDICTED; otherwise it
 * says how much is still needed, or that no window clearly stands out.
 */
@Composable
fun PeakCard(peak: PeakWindowPrediction?, today: TimeWindow?, now: Boolean, nowMs: Long) {
    PanelBox(accent = if (today != null) Cyan else Hairline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(if (now) "Peak cognitive window · now" else "Peak cognitive window", color = if (today != null) Cyan else Text2)
                VSpace(6.dp)
                when {
                    today != null -> {
                        Text("${clockOf(today.startMs)} – ${clockOf(today.endMs)}", fontFamily = Mono, fontSize = 26.sp, color = Text1)
                        VSpace(2.dp)
                        Muted(
                            (if (now) "Hit your hardest task now. About ${minutesLeft(today.endMs - nowMs)} left." else "Starts in ${minutesLeft(today.startMs - nowMs)}.") +
                                " ${peak!!.confidence.label} · ${today.window.label.lowercase(UiLocale)}.",
                        )
                    }
                    peak?.result == PeakResult.PREDICTED -> {
                        Text("${peak.window!!.label} window", style = MaterialTheme.typography.titleLarge, color = Text1)
                        Muted("Today's has passed. Back at it tomorrow.")
                    }
                    peak?.result == PeakResult.NO_CLEAR_PEAK -> {
                        Text("No clear peak", style = MaterialTheme.typography.titleLarge, color = Text1)
                        Muted("You're steady across the day. Keep playing Beat the Clock to sharpen the picture.")
                    }
                    else -> {
                        Text("Herc is still learning", style = MaterialTheme.typography.titleLarge, color = Text1)
                        val days = peak?.observationDays ?: 0
                        val weakest = peak?.performance?.minByOrNull { it.sessions }
                        Muted(
                            "Needs ${PeakWindowPrediction.MIN_OBSERVATION_DAYS} days and ${PeakWindowPrediction.MIN_SESSIONS_PER_WINDOW} sessions in each window. " +
                                "So far: $days days" + (weakest?.let { ", ${it.sessions} in the ${it.window.label.lowercase(UiLocale)}." } ?: "."),
                        )
                    }
                }
            }
            Icon(Icons.Rounded.Bolt, null, tint = if (today != null) Cyan else Text2, modifier = Modifier.size(28.dp))
        }
    }
}

private fun minutesLeft(ms: Long): String {
    val m = (ms / 60_000L).toInt().coerceAtLeast(1)
    return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"
}

/** The one thing to do next. This is the primary button on the screen. */
@Composable
fun ActionCard(action: RecommendedAction, mood: HercMood = HercMood.GOOD, onAct: (RecommendedAction) -> Unit) {
    PanelBox(Modifier.clickable { onAct(action) }, accent = Cyan) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Herc(mood, size = 56.dp)
                HSpace(8.dp)
                Column(Modifier.weight(1f)) {
                    Eyebrow("Herc suggests", color = Cyan)
                    Text(action.title, style = MaterialTheme.typography.titleMedium, color = Text1)
                    action.detail?.let { Muted(it) }
                }
                Icon(action.kind.icon, null, tint = Cyan, modifier = Modifier.size(20.dp))
            }
            VSpace(14.dp)
            Button(
                onClick = { onAct(action) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink),
            ) {
                Text(action.kind.button, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
                HSpace(8.dp)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Tonight: when to be in bed, and what sleep could mean for tomorrow (clearly an estimate). */
@Composable
fun TonightCard(advice: SleepAdvice, r: Readiness?) {
    Section("Tonight", trailing = { Pill("Estimate", Text2) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, null, tint = Brand, modifier = Modifier.size(28.dp))
            HSpace(14.dp)
            Column(Modifier.weight(1f)) {
                Text("Lights out ${clockOf(advice.bedtimeMs)}", fontFamily = Mono, fontSize = 24.sp, color = Text1)
                Muted(advice.shiftEarlierMinutes?.let { "Aim $it min earlier than last night." } ?: "Your usual bedtime is working. Keep it.")
            }
        }
        advice.tomorrowNote?.let { VSpace(8.dp); Muted(it) }
        if (r != null && advice.ifShort != null && advice.ifTarget != null) {
            VSpace(16.dp)
            Eyebrow("Tomorrow's readiness")
            VSpace(8.dp)
            ProjectionBar("Sleep 6h", advice.ifShort!!)
            VSpace(8.dp)
            ProjectionBar("Sleep ${formatHM(advice.targetMinutes)}", advice.ifTarget!!)
        }
    }
}

private fun formatHM(min: Int) = if (min % 60 == 0) "${min / 60}h" else "${min / 60}h ${min % 60}m"

@Composable
private fun ProjectionBar(label: String, score: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Text2, modifier = Modifier.weight(0.38f))
        Box(Modifier.weight(0.5f)) { ScoreBar(score, scoreColor(score), 8.dp) }
        Text("~$score", fontFamily = Mono, fontSize = 14.sp, color = Text1, textAlign = TextAlign.End, modifier = Modifier.weight(0.12f))
    }
}

/** First-week checklist. Shows what is still missing and takes you there. Disappears when done. */
@Composable
fun GettingStarted(healthConnected: Boolean, watchLinked: Boolean, practiced: Boolean, onSetup: () -> Unit, onPlay: () -> Unit) {
    val done = listOf(healthConnected, watchLinked, practiced).count { it }
    Section("Get started", trailing = { Text("$done/3", fontFamily = Mono, fontSize = 12.sp, color = Cyan) }) {
        StartRow(healthConnected, Icons.Rounded.Favorite, "Connect Health Connect", "Feed your score with sleep, heart and activity.", onSetup)
        StartRow(practiced, Icons.Rounded.Timer, "Play Beat the Clock", "Your first game counts. About 2 minutes.", onPlay)
        StartRow(watchLinked, Icons.Rounded.Watch, "Add your watch (optional)", "Sleep tracking and Beat the Clock on your wrist.", onSetup)
    }
}

@Composable
private fun StartRow(done: Boolean, icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = !done, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (done) Cyan else Text2, modifier = Modifier.size(22.dp))
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (done) Text2 else Text1)
            if (!done) Muted(hint)
        }
        if (!done) Icon(icon, null, tint = Cyan, modifier = Modifier.size(18.dp))
    }
}

/** Five faces from rough to great. No score is implied; it is only compared with readiness afterwards. */
@Composable
fun ReflectContent(title: String, readinessThatDay: Int?, current: Int?, onRate: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow("After the Moment")
                VSpace(6.dp)
                Text("How did you do?", style = MaterialTheme.typography.headlineSmall, color = Text1)
                Muted(title)
            }
            Herc(HercMood.FAIR, size = 64.dp)
        }
        VSpace(20.dp)
        FacesRow(current, onRate)
        if (current != null && readinessThatDay != null) {
            VSpace(20.dp)
            Text("You felt ${Faces[current - 1].second.lowercase()} · readiness that day $readinessThatDay.", style = MaterialTheme.typography.bodyMedium, color = Text1)
            Muted("Over time you'll see how your readiness and results line up. It shows a pattern, not the cause.")
        }
    }
}

private val Faces = listOf(
    Icons.Rounded.SentimentVeryDissatisfied to "Rough",
    Icons.Rounded.SentimentDissatisfied to "Meh",
    Icons.Rounded.SentimentNeutral to "Okay",
    Icons.Rounded.SentimentSatisfied to "Good",
    Icons.Rounded.SentimentVerySatisfied to "Great",
)

/** Five faces from rough to great; big touch targets. */
@Composable
fun FacesRow(current: Int?, onRate: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Faces.forEachIndexed { i, (icon, label) ->
            val on = current == i + 1
            Column(
                Modifier.size(width = 60.dp, height = 78.dp).clip(RoundedCornerShape(8.dp))
                    .border(BorderStroke(1.dp, if (on) Cyan else Hairline), RoundedCornerShape(8.dp))
                    .clickable { onRate(i + 1) }.padding(top = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, label, tint = if (on) Cyan else Text2, modifier = Modifier.size(30.dp))
                VSpace(6.dp)
                Text(label.uppercase(UiLocale), fontSize = 10.sp, letterSpacing = 1.sp, color = if (on) Text1 else Text2)
            }
        }
    }
}

/** Where a reading comes from: measured, phone sensor, assessed or estimated. */
@Composable
fun ProvenanceTag(p: Provenance) {
    val color = when (p) {
        Provenance.MEASURED, Provenance.PHONE_SENSOR -> Cyan
        Provenance.ASSESSED -> Brand
        else -> Text2
    }
    Pill(p.label, color)
}
