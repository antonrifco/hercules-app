package com.hercules.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.hercules.mobile.graph
import com.hercules.mobile.ui.Cyan
import com.hercules.mobile.ui.Hairline
import com.hercules.mobile.ui.Ink
import com.hercules.mobile.ui.MainActivity
import com.hercules.mobile.ui.Text1
import com.hercules.mobile.ui.Text2
import com.hercules.mobile.ui.TodayNumbers
import com.hercules.mobile.ui.UiLocale
import com.hercules.mobile.ui.color
import com.hercules.mobile.ui.todayNumbers
import com.hercules.shared.cognitive.Readiness
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Three home-screen widgets (Jetpack Glance): today's score with a Reaction Test button, today's stats, and this week's
 * scores. They read the same database as the app and are redrawn after every recalculation ([Widgets.refresh]).
 */
object Widgets {
    enum class Kind(val title: String, val size: String, val blurb: String, val receiver: Class<*>) {
        SCORE("Score", "2×2", "Today's score and a button to take the Reaction Test.", ScoreWidgetReceiver::class.java),
        TODAY("Today", "2×2", "Steps, distance, resting pulse and energy burned.", TodayWidgetReceiver::class.java),
        WEEK("This week", "4×2", "Your score for each of the last 7 days.", WeekWidgetReceiver::class.java),
    }

    suspend fun refresh(context: Context) {
        runCatching {
            ScoreWidget().updateAll(context)
            TodayWidget().updateAll(context)
            WeekWidget().updateAll(context)
        }
    }

    /** Asks the launcher to add a widget. False when the launcher can't (then add it from the widget list by hand). */
    fun requestPin(context: Context, kind: Kind): Boolean {
        val m = AppWidgetManager.getInstance(context)
        if (!m.isRequestPinAppWidgetSupported) return false
        return runCatching { m.requestPinAppWidget(ComponentName(context, kind.receiver), null, null) }.getOrDefault(false)
    }
}

class ScoreWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ScoreWidget() }
class TodayWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = TodayWidget() }
class WeekWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = WeekWidget() }

private fun open(context: Context, target: String? = null): Action = actionStartActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse("hercules://open" + (target?.let { "?target=$it" } ?: "")))
        .setClass(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
)

private fun c(color: Color) = ColorProvider(color)

@Composable
private fun Card(onClick: Action, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().background(Ink).cornerRadius(20.dp).padding(14.dp).clickable(onClick),
        contentAlignment = Alignment.TopStart,
    ) { content() }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = TextStyle(color = c(Text2), fontSize = 10.sp, fontWeight = FontWeight.Medium))

// ------------------------------------------------------------------ 1. score + play

class ScoreWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val g = context.graph
        val r: Readiness? = runCatching { g.cognitive.store.readiness(g.cognitive.today()) }.getOrNull()
        provideContent {
            Card(open(context)) {
                Column(GlanceModifier.fillMaxSize()) {
                    Label("Hercules · today")
                    Spacer(GlanceModifier.defaultWeight())
                    if (r == null) {
                        Text("--", style = TextStyle(color = c(Text2), fontSize = 40.sp))
                        Text("No score yet", style = TextStyle(color = c(Text2), fontSize = 12.sp))
                    } else {
                        Text("${r.score}", style = TextStyle(color = c(r.band.color), fontSize = 44.sp, fontWeight = FontWeight.Bold))
                        Text("${r.band.label} · ${r.confidence.label.substringBefore(" ")}", style = TextStyle(color = c(Text1), fontSize = 12.sp), maxLines = 1)
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Box(
                        GlanceModifier.fillMaxWidth().height(34.dp).background(Cyan).cornerRadius(17.dp).clickable(open(context, "ASSESS")),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Test Reaction", style = TextStyle(color = c(Ink), fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 2. today's stats

class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val g = context.graph
        val summary = runCatching { g.repo.observeDaily(g.cognitive.today()).firstOrNull() }.getOrNull()
        val hc = runCatching { g.healthConnect.todayTotals() }.getOrNull()
        val n: TodayNumbers = todayNumbers(summary, hc)
        provideContent {
            Card(open(context)) {
                Column(GlanceModifier.fillMaxSize()) {
                    Label("Today")
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth()) {
                        Stat("Steps", n.steps?.let { "%,d".format(UiLocale, it) }, "", GlanceModifier.defaultWeight())
                        Stat("Distance", n.km?.let { "%.1f".format(UiLocale, it) }, "km", GlanceModifier.defaultWeight())
                    }
                    Spacer(GlanceModifier.height(10.dp))
                    Row(GlanceModifier.fillMaxWidth()) {
                        Stat("Resting", n.resting?.toString(), "bpm", GlanceModifier.defaultWeight())
                        Stat("Burned", n.kcal?.let { "%,d".format(UiLocale, it.roundToInt()) }, "kcal", GlanceModifier.defaultWeight())
                    }
                    Spacer(GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String?, unit: String, modifier: GlanceModifier) {
    Column(modifier) {
        Text(label, style = TextStyle(color = c(Text2), fontSize = 10.sp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value ?: "--", style = TextStyle(color = c(Text1), fontSize = 18.sp, fontWeight = FontWeight.Medium))
            if (unit.isNotEmpty() && value != null) Text(" $unit", style = TextStyle(color = c(Text2), fontSize = 10.sp))
        }
    }
}

// ------------------------------------------------------------------ 3. this week

class WeekWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val g = context.graph
        val today = g.cognitive.today()
        val dk = g.cognitive.dayKeys
        val keys = (-6..0).map { dk.plusDays(today, it) }
        val scores = runCatching { g.cognitive.store.readinessRange(keys.first(), today) }.getOrDefault(emptyList()).associateBy { it.dayKey }
        val days = keys.map { k -> Triple(k, scores[k], LocalDate.parse(k).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, UiLocale)) }
        val avg = days.mapNotNull { it.second?.score }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        provideContent {
            Card(open(context)) {
                Column(GlanceModifier.fillMaxSize()) {
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(GlanceModifier.defaultWeight()) { Label("This week") }
                        Text(avg?.let { "avg $it" } ?: "", style = TextStyle(color = c(Text1), fontSize = 11.sp))
                    }
                    Spacer(GlanceModifier.defaultWeight())
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        days.forEach { (key, r, letter) -> Bar(r, letter, key == today, GlanceModifier.defaultWeight()) }
                    }
                }
            }
        }
    }
}

private const val BAR_MAX_DP = 70

@Composable
private fun Bar(r: Readiness?, letter: String, isToday: Boolean, modifier: GlanceModifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.Bottom) {
        Text(r?.score?.toString() ?: "", style = TextStyle(color = c(Text2), fontSize = 11.sp))
        Spacer(GlanceModifier.height(2.dp))
        // A missing day stays a stub: missing data is never shown as a low score.
        val h = r?.score?.let { (it * BAR_MAX_DP / 100).coerceAtLeast(4) } ?: 3
        Box(GlanceModifier.width(18.dp).height(h.dp).background(r?.band?.color ?: Hairline).cornerRadius(3.dp)) {}
        Spacer(GlanceModifier.height(4.dp))
        Text(letter, style = TextStyle(color = c(if (isToday) Cyan else Text2), fontSize = 10.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal))
    }
}
