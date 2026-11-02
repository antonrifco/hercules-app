package com.hercules.wear.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hercules.shared.cognitive.Level
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Herc, the Hercules mascot: a round cub whose mane is the readiness dial. The ticks light up to the score
 * and his face follows how you are doing. Same geometry as `tools/mascot/mascot.py` (108 x 108 viewport),
 * which also generates the launcher icon, so the app and the icon are the same drawing. Kept identical to the
 * phone's `Mascot.kt`.
 */
enum class HercMood { HAPPY, CALM, TIRED, SLEEPING }

fun Level?.mood(): HercMood = when (this) {
    Level.GOOD -> HercMood.HAPPY
    Level.MODERATE -> HercMood.CALM
    Level.LOW -> HercMood.TIRED
    null -> HercMood.SLEEPING
}

private val Cyan = Color(0xFF3FE0FF)
private val HercIndigo = Color(0xFF6E6BFF)
private val HercDim = Color(0xFF2A2D45)
private const val Ticks = 19

@Composable
fun Herc(
    mood: HercMood,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    /** 0..1: how much of the mane is lit. */
    lit: Float = 0.78f,
    /** Trim the empty margin of the icon canvas, so [size] is the size of the character. Off for the icon-sized splash. */
    crop: Boolean = true,
    background: Color = Color(0xFF08080F),
) {
    val litTicks by animateFloatAsState(lit.coerceIn(0f, 1f) * Ticks, tween(900, easing = FastOutSlowInEasing), label = "mane")
    val t = rememberInfiniteTransition(label = "herc")
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 4800; 1f at 0; 1f at 4400; 0.06f at 4520; 1f at 4660 }),
        label = "blink",
    )
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "z")
    val desc = when (mood) {
        HercMood.HAPPY -> "Herc, happy"
        HercMood.CALM -> "Herc, calm"
        HercMood.TIRED -> "Herc, tired"
        HercMood.SLEEPING -> "Herc, asleep"
    }
    Canvas(modifier.size(size).semantics { contentDescription = desc }) {
        val span = if (crop) 70f else 108f
        val off = if (crop) -19f else 0f
        val k = this.size.minDimension / span
        withTransform({ scale(k, k, Offset.Zero); translate(off, off) }) { drawHerc(mood, litTicks, blink, bob, background) }
    }
}

private fun DrawScope.drawHerc(mood: HercMood, litTicks: Float, blink: Float, bob: Float, ink: Color) {
    val c = Offset(54f, 54f)
    // mane: a 270 degree dial
    for (i in 0 until Ticks) {
        val a = Math.toRadians(135.0 + 270.0 * i / (Ticks - 1))
        val r0 = if (i % 3 == 0) 24.5f else 26f
        val on = i + 1 <= litTicks + 0.001f
        drawLine(
            if (on) Cyan else HercDim,
            Offset(c.x + r0 * cos(a).toFloat(), c.y + r0 * sin(a).toFloat()),
            Offset(c.x + 31f * cos(a).toFloat(), c.y + 31f * sin(a).toFloat()),
            strokeWidth = if (i % 3 == 0) 2.8f else 2.2f,
            cap = StrokeCap.Round,
        )
    }
    // ears
    for (deg in listOf(225.0, 315.0)) {
        val a = Math.toRadians(deg)
        val e = Offset(c.x + 18.5f * cos(a).toFloat(), c.y + 18.5f * sin(a).toFloat())
        drawCircle(HercIndigo, 5.5f, e)
        drawCircle(Cyan, 5.5f * 0.46f, e)
    }
    // head and gem
    drawCircle(Brush.linearGradient(listOf(Cyan, HercIndigo), Offset(46f, 37f), Offset(62f, 71f)), 17f, c)
    drawPath(Path().apply { moveTo(54f, 38.4f); lineTo(56.3f, 41f); lineTo(54f, 43.6f); lineTo(51.7f, 41f); close() }, Color.White.copy(alpha = 0.92f))

    val eyeY = 52f
    when (mood) {
        HercMood.HAPPY, HercMood.CALM -> for (s in listOf(-1f, 1f)) {
            val ex = 54f + s * 6.4f
            val h = 3.3f * blink
            drawOval(ink, Offset(ex - 2.3f, eyeY - h), Size(4.6f, 2 * h))
            if (blink > 0.5f) drawCircle(Color.White, 0.9f, Offset(ex + 0.8f, eyeY - 1.1f))
        }
        HercMood.TIRED -> for (s in listOf(-1f, 1f)) {
            val ex = 54f + s * 6.4f
            val outer = 0.9f
            val inner = -0.5f
            val left = if (s < 0) outer else inner
            val right = if (s < 0) inner else outer
            drawPath(
                Path().apply {
                    moveTo(ex - 2.6f, eyeY - 0.4f + left); lineTo(ex + 2.6f, eyeY - 0.4f + right)
                    lineTo(ex + 2.4f, eyeY + 1.8f); lineTo(ex, eyeY + 3f); lineTo(ex - 2.4f, eyeY + 1.8f); close()
                },
                ink,
            )
        }
        HercMood.SLEEPING -> for (s in listOf(-1f, 1f)) {
            val ex = 54f + s * 6.4f
            drawPath(Path().apply { moveTo(ex - 2.8f, eyeY + 0.3f); quadraticTo(ex, eyeY + 3.4f, ex + 2.8f, eyeY + 0.3f) }, ink, style = Stroke(1.5f, cap = StrokeCap.Round))
        }
    }
    when (mood) {
        HercMood.HAPPY -> drawPath(Path().apply { moveTo(49f, 58.4f); quadraticTo(54f, 64.2f, 59f, 58.4f); quadraticTo(54f, 60.7f, 49f, 58.4f); close() }, ink)
        HercMood.CALM -> drawRoundRect(ink, Offset(50.2f, 58.85f), Size(7.6f, 1.5f), CornerRadius(0.75f))
        HercMood.TIRED -> drawRoundRect(ink, Offset(51.6f, 59.5f), Size(4.8f, 1.4f), CornerRadius(0.7f))
        HercMood.SLEEPING -> {
            drawOval(ink, Offset(52.8f, 59.2f), Size(2.4f, 2.0f))
            // drifting z's
            for ((i, p) in listOf(Offset(72f, 36f) to 4f, Offset(78f, 28f) to 3f).withIndex()) {
                val (o, s) = p
                val dy = -bob * (2f + i)
                drawPath(
                    Path().apply { moveTo(o.x, o.y + dy); lineTo(o.x + s, o.y + dy); lineTo(o.x, o.y + s + dy); lineTo(o.x + s, o.y + s + dy) },
                    Cyan, alpha = 0.55f + 0.45f * bob, style = Stroke(1.2f, cap = StrokeCap.Round),
                )
            }
        }
    }
}
