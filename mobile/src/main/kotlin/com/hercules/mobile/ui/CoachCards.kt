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
import com.hercules.shared.cognitive.ComponentKind
import com.hercules.shared.cognitive.DailyBrief
import com.hercules.shared.cognitive.Provenance
import com.hercules.shared.cognitive.RecommendedAction
import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.SleepAdvice
import com.hercules.shared.cognitive.TimeWindow
import com.hercules.shared.cognitive.learningNote

/** What a recommendation looks like: icon + the verb on its button. Short, action-oriented labels. */
private val ActionKind.icon: ImageVector
    get() = when (this) {
        ActionKind.CHECK_IN -> Icons.Rounded.Bolt
        ActionKind.PREPARE -> Icons.Rounded.Flag
        ActionKind.REFLECT -> Icons.Rounded.RateReview
        ActionKind.SLEEP -> Icons.Rounded.Bedtime
        ActionKind.FOCUS -> Icons.Rounded.Timer
    }

private val ActionKind.button: String
    get() = when (this) {
        ActionKind.CHECK_IN -> "CHECK IN"
        ActionKind.PREPARE -> "GET READY"
        ActionKind.REFLECT -> "RATE IT"
        ActionKind.SLEEP -> "TONIGHT'S PLAN"
        ActionKind.FOCUS -> "SEE YOUR DAY"
    }

/** Answers "how am I doing?" in three seconds: the dial, one word, one sentence, one reason. */
@Composable
fun Briefing(r: Readiness, brief: DailyBrief, onLearnMore: () -> Unit) {
    val c = r.band.color
    Column(Modifier.fillMaxWidth().background(glow(c)), horizontalAlignment = Alignment.CenterHorizontally) {
        ScoreGauge(r.score, c, size = 208.dp, caption = "readiness")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(r.band.icon, null, tint = c, modifier = Modifier.size(16.dp))
            HSpace(8.dp)
            Text(r.band.label.uppercase(UiLocale), color = c, fontSize = 15.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
        }
        VSpace(8.dp)
        Text(brief.headline, style = MaterialTheme.typography.headlineSmall, color = Text1, textAlign = TextAlign.Center)
        brief.reason?.let {
            VSpace(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(it.kind.icon, null, tint = if (it.good) Cyan else r.band.color, modifier = Modifier.size(15.dp))
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

/** Best hours for demanding work, as one big readable range. */
@Composable
fun FocusCard(window: TimeWindow?, now: Boolean, nowMs: Long) {
    PanelBox(accent = Cyan) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(if (now) "Best focus · now" else "Best focus time", color = Cyan)
                VSpace(6.dp)
                if (window == null) {
                    Text("Done for today", style = MaterialTheme.typography.titleLarge, color = Text1)
                    Muted("Your best hours have passed. Tomorrow is a fresh start.")
                } else {
                    Text("${clockOf(window.startMs)} – ${clockOf(window.endMs)}", fontFamily = Mono, fontSize = 26.sp, color = Text1)
                    VSpace(2.dp)
                    Muted(if (now) "Hardest task first. About ${minutesLeft(window.endMs - nowMs)} left." else "Starts in ${minutesLeft(window.startMs - nowMs)}.")
                }
            }
            Icon(Icons.Rounded.Timer, null, tint = Cyan, modifier = Modifier.size(28.dp))
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

/** When there is no score yet: what is missing, why it matters, what to do. */
@Composable
fun NoScoreCard(onCheckIn: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Herc(HercMood.SLEEPING, size = 170.dp)
        Eyebrow("No score yet", color = Cyan)
        VSpace(8.dp)
        Text("Your score starts with sleep", style = MaterialTheme.typography.headlineSmall, color = Text1)
        VSpace(14.dp)
        EmptyStep(Icons.Rounded.Watch, "Wear your watch to bed", "Sleep is recorded automatically.")
        EmptyStep(Icons.Rounded.Bolt, "Or check in now", "Two taps give you a first score.")
    }
}

@Composable
private fun EmptyStep(icon: ImageVector, title: String, hint: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, Cyan, size = 32.dp, iconSize = 18.dp)
        HSpace(12.dp)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Text1)
            Muted(hint)
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
                Text("Bed by ${clockOf(advice.bedtimeMs)}", fontFamily = Mono, fontSize = 24.sp, color = Text1)
                Muted(advice.shiftEarlierMinutes?.let { "Try sleeping $it min earlier." } ?: "Your usual bedtime works well.")
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
fun GettingStarted(watchLinked: Boolean, checkedIn: Boolean, onWatch: () -> Unit, onCheckIn: () -> Unit) {
    val done = listOf(watchLinked, checkedIn).count { it }
    Section("Get started", trailing = { Text("$done/2", fontFamily = Mono, fontSize = 12.sp, color = Cyan) }) {
        StartRow(watchLinked, Icons.Rounded.Watch, "Connect your watch", "So sleep is tracked for you.", onWatch)
        StartRow(checkedIn, Icons.Rounded.Bolt, "Do your first check-in", "Takes 10 seconds.", onCheckIn)
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
                Eyebrow("After the event")
                VSpace(6.dp)
                Text("How did it go?", style = MaterialTheme.typography.headlineSmall, color = Text1)
                Muted(title)
            }
            Herc(HercMood.FAIR, size = 64.dp)
        }
        VSpace(20.dp)
        FacesRow(current, onRate)
        if (current != null && readinessThatDay != null) {
            VSpace(20.dp)
            Text("You felt ${Faces[current - 1].second.lowercase()} · readiness that day $readinessThatDay.", style = MaterialTheme.typography.bodyMedium, color = Text1)
            Muted("Over time this shows how your readiness and your results line up. It can't tell what caused what.")
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

/** Where a readiness reading comes from: measured, reported or estimated. */
@Composable
fun ProvenanceTag(p: Provenance) {
    val color = when (p) {
        Provenance.MEASURED -> Cyan
        Provenance.REPORTED -> Brand
        else -> Text2
    }
    Pill(p.label, color)
}

val ComponentKind.plainName: String
    get() = when (this) {
        ComponentKind.SLEEP -> "Sleep"
        ComponentKind.RECOVERY -> "Body recovery"
        ComponentKind.SELF_REPORT -> "How you feel"
        ComponentKind.ACTIVITY -> "Activity"
    }
