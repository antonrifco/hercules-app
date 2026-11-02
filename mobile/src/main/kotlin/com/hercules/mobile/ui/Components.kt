package com.hercules.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.Tone
import com.hercules.shared.cognitive.Verdict
import com.hercules.shared.cognitive.WindowKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ palette: an instrument, not a toy

val Ink = Color(0xFF05050E)          // background (matches illustrations + splash)
val Panel = Color(0xFF10111B)        // raised surfaces
val Hairline = Color(0xFF22243A)     // 1dp rules and borders
val Text1 = Color(0xFFE9EBF5)
val Text2 = Color(0xFF8B90A8)
val Cyan = Color(0xFF3FE0FF)
val Brand = Color(0xFF6E6BFF)        // indigo

private val Prima = Color(0xFF2EE6A0)
private val Baik = Color(0xFF9BE35A)
private val Cukup = Color(0xFFFFC857)
private val Rendah = Color(0xFFFF8C4C)
private val Terkuras = Color(0xFFFF4D6D)

val ReadinessBand.color: Color
    get() = when (this) {
        ReadinessBand.PRIMA -> Prima
        ReadinessBand.BAIK -> Baik
        ReadinessBand.CUKUP -> Cukup
        ReadinessBand.RENDAH -> Rendah
        ReadinessBand.TERKURAS -> Terkuras
    }

fun scoreColor(score: Int): Color = ReadinessBand.of(score.coerceIn(0, 100)).color

val Verdict.color: Color
    get() = when (this) {
        Verdict.GO -> Prima
        Verdict.GO_WITH_PREP -> Baik
        Verdict.CONSIDER_RESCHEDULE -> Cukup
        Verdict.RESCHEDULE -> Terkuras
    }

val Tone.color: Color
    get() = when (this) {
        Tone.POSITIVE -> Prima
        Tone.CAUTION -> Cukup
        Tone.ACTION -> Cyan
    }

val WindowKind.color: Color
    get() = when (this) {
        WindowKind.PEAK -> Cyan
        WindowKind.SECOND_PEAK -> Color(0xFF2AA7C9)
        WindowKind.STEADY -> Color(0xFF2B3A5C)
        WindowKind.WARMUP, WindowKind.DIP, WindowKind.EVENING -> Color(0xFF3A2F55)
        WindowKind.LATE -> Color(0xFF1A1B2A)
    }

/** Numerals: monospace = instrument readout. */
val Mono = FontFamily.Monospace

@Composable
fun HerculesTheme(@Suppress("UNUSED_PARAMETER") dark: Boolean, content: @Composable () -> Unit) {
    // Always dark: the product reads like an instrument panel, and matches icon and splash.
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan, onPrimary = Ink, secondary = Brand,
            background = Ink, surface = Ink, surfaceContainer = Panel, surfaceContainerLowest = Panel,
            surfaceVariant = Hairline, onSurface = Text1, onSurfaceVariant = Text2,
            outline = Text2, outlineVariant = Hairline, secondaryContainer = Color(0xFF16263A), onSecondaryContainer = Cyan,
        ),
        typography = base.copy(
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Normal),
        ),
        content = content,
    )
}

// ------------------------------------------------------------------ formatting

val UiLocale: Locale = Locale.ENGLISH
private val zone get() = ZoneId.systemDefault()
private fun fmt(pattern: String, ms: Long) = DateTimeFormatter.ofPattern(pattern, UiLocale).format(Instant.ofEpochMilli(ms).atZone(zone))

fun clockOf(ms: Long) = fmt("HH:mm", ms)
fun longDate(ms: Long) = fmt("EEEE, d MMMM", ms)
fun shortDate(ms: Long) = fmt("EEE, d MMM", ms)
fun weekdayShort(ms: Long) = fmt("EEE", ms)

fun dayTag(daysUntil: Int): String = when {
    daysUntil < 0 -> "Done"
    daysUntil == 0 -> "Today"
    else -> "D-$daysUntil"
}

fun countdownLabel(daysUntil: Int, startMs: Long): String = when {
    daysUntil < 0 -> "Done · ${shortDate(startMs)}"
    daysUntil == 0 -> "Today · ${clockOf(startMs)}"
    daysUntil == 1 -> "Tomorrow · ${clockOf(startMs)}"
    else -> "In $daysUntil days · ${shortDate(startMs)}"
}

// ------------------------------------------------------------------ building blocks

/** Small uppercase, letter-spaced label: the voice of every section. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Text2) {
    Text(text.uppercase(UiLocale), modifier = modifier, color = color, fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Medium)
}

/** Section = a cyan tick, an eyebrow title and a hairline. No card. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 3.dp, height = 12.dp).background(Cyan))
            HSpace(10.dp)
            Eyebrow(title, color = Text1, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        VSpace(10.dp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        VSpace(14.dp)
        content()
    }
}

/** A bordered panel with sharp corners, optionally with an accent edge on the left. */
@Composable
fun PanelBox(modifier: Modifier = Modifier, accent: Color? = null, content: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(6.dp)).background(Panel)
            .border(BorderStroke(1.dp, Hairline), RoundedCornerShape(6.dp)),
    ) {
        if (accent != null) Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Box(Modifier.weight(1f).padding(16.dp)) { content() }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = Text2)
}

/** Outlined tag; reads like a status light rather than a candy pill. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.border(BorderStroke(1.dp, color.copy(alpha = 0.55f)), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            HSpace(5.dp)
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            HSpace(6.dp)
        }
        Text(text.uppercase(UiLocale), color = color, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/**
 * The signature bezel: 100 ticks like a watch bezel. Ticks up to the score glow in the band colour,
 * every tenth tick is longer. Thin numerals in the middle.
 */
@Composable
fun ScoreGauge(score: Int, color: Color, size: Dp = 260.dp, caption: String = "readiness") {
    val p by animateFloatAsState(score / 100f, tween(1100), label = "dial")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = this.size.minDimension / 2
            val start = 135.0
            val sweep = 270.0
            for (i in 0..100) {
                val a = Math.toRadians(start + sweep * i / 100)
                val major = i % 10 == 0
                val on = i <= (p * 100).toInt()
                val r0 = r - if (major) 22.dp.toPx() else 14.dp.toPx()
                val r1 = r - 4.dp.toPx()
                drawLine(
                    color = if (on) color else Hairline,
                    start = Offset(c.x + (r0 * cos(a)).toFloat(), c.y + (r0 * sin(a)).toFloat()),
                    end = Offset(c.x + (r1 * cos(a)).toFloat(), c.y + (r1 * sin(a)).toFloat()),
                    strokeWidth = if (major) 2.5.dp.toPx() else 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // needle dot at the current value
            val a = Math.toRadians(start + sweep * p)
            val rn = r - 34.dp.toPx()
            drawCircle(color, 4.dp.toPx(), Offset(c.x + (rn * cos(a)).toFloat(), c.y + (rn * sin(a)).toFloat()))
            drawCircle(color.copy(alpha = 0.06f), r * 0.62f, c)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$score", fontSize = 88.sp, fontWeight = FontWeight.Thin, color = Text1, letterSpacing = (-4).sp)
            Eyebrow(caption)
        }
    }
}

@Composable
fun ScoreBar(score: Int, color: Color = scoreColor(score), height: Dp = 4.dp) {
    val p by animateFloatAsState(score / 100f, tween(700), label = "bar")
    Canvas(Modifier.fillMaxWidth().height(height)) {
        drawRect(Hairline)
        drawRect(color, size = Size(this.size.width * p, this.size.height))
    }
}

/** Thin vertical bars (nights of sleep, days of readiness). Null = no data. */
@Composable
fun MiniBars(
    values: List<Float?>,
    max: Float,
    labels: List<String>,
    colorOf: (Float) -> Color,
    target: Float? = null,
    barHeight: Dp = 84.dp,
) {
    Column {
        Canvas(Modifier.fillMaxWidth().height(barHeight)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val w = (slot * 0.34f).coerceAtMost(10.dp.toPx())
            values.forEachIndexed { i, v ->
                val x = i * slot + (slot - w) / 2
                drawRect(Hairline, Offset(x + w / 2 - 0.5f, 0f), Size(1f, size.height))
                if (v != null) {
                    val h = (v / max).coerceIn(0.03f, 1f) * size.height
                    drawRoundRect(colorOf(v), Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2))
                }
            }
            target?.let {
                val y = size.height - (it / max).coerceIn(0f, 1f) * size.height
                drawLine(Text2.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }
        }
        VSpace(6.dp)
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it.uppercase(UiLocale), Modifier.weight(1f), fontSize = 9.sp, letterSpacing = 1.sp, color = Text2, textAlign = TextAlign.Center) }
        }
    }
}

/** A labelled reading: eyebrow on top, monospace value below. */
@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, color: Color = Text1) {
    Column(modifier) {
        Eyebrow(label)
        VSpace(4.dp)
        Text(value, fontFamily = Mono, fontSize = 18.sp, color = color)
    }
}

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

val CardGap = Arrangement.spacedBy(22.dp)

/** Small dial for secondary scores (prep, forecast, event-time). */
@Composable
fun ScoreRing(score: Int, label: String, size: Dp = 68.dp, color: Color = scoreColor(score)) {
    val p by animateFloatAsState(score / 100f, tween(800), label = "ring")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = 2.dp.toPx()
                val sz = Size(this.size.width - st * 4, this.size.height - st * 4)
                val o = Offset(st * 2, st * 2)
                drawArc(Hairline, -90f, 360f, false, o, sz, style = Stroke(st))
                drawArc(color, -90f, 360f * p, false, o, sz, style = Stroke(st * 1.6f, cap = StrokeCap.Round))
            }
            Text("$score", fontFamily = Mono, fontSize = (size.value * 0.28f).sp, color = Text1)
        }
        VSpace(4.dp)
        Eyebrow(label)
    }
}

/** Icon + monospace value + eyebrow label. */
@Composable
fun FactTile(icon: ImageVector, value: String, label: String, modifier: Modifier = Modifier, tint: Color = Cyan) {
    Column(modifier) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        VSpace(8.dp)
        Text(value, fontFamily = Mono, fontSize = 17.sp, color = Text1)
        VSpace(2.dp)
        Eyebrow(label)
    }
}

/** Faint glow used behind hero elements. */
fun glow(color: Color) = Brush.radialGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent))

/** Journey illustration (generated artwork on the brand black, so it blends into the page). */
@Composable
fun Illustration(res: Int, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(res),
        contentDescription = null,
        modifier = modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}
