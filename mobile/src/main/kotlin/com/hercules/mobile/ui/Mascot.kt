package com.hercules.mobile.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hercules.shared.cognitive.ReadinessBand

/**
 * Herc, the Hercules mascot: an adult male lion. Each band has its own face and eye colour, all drawn with
 * Higgsfield and vectorized by `tools/mascot/mascot.py` into [HercArt]:
 * Prime fierce and focused (green), Good calm and assured (lime), Fair neutral (amber), Low weary (orange),
 * Drained worn down (red). With no score yet he wears the Fair face with dim, narrowed eyes.
 */
enum class HercMood(val label: String, val eyes: Color, val face: Int, val eyesOpen: Float = 1f) {
    PRIME("Prime", Color(0xFF2EE6A0), 0),
    GOOD("Good", Color(0xFF9BE35A), 1),
    FAIR("Fair", Color(0xFFFFC857), 2),
    LOW("Low", Color(0xFFFF8C4C), 3),
    DRAINED("Drained", Color(0xFFFF4D6D), 4),
    SLEEPING("No score yet", Color(0xFF8A7A66), 2, eyesOpen = 0.3f),
}

fun ReadinessBand?.mood(): HercMood = when (this) {
    ReadinessBand.PRIMA -> HercMood.PRIME
    ReadinessBand.BAIK -> HercMood.GOOD
    ReadinessBand.CUKUP -> HercMood.FAIR
    ReadinessBand.RENDAH -> HercMood.LOW
    ReadinessBand.TERKURAS -> HercMood.DRAINED
    null -> HercMood.SLEEPING
}

/** HEAD crops to the lion; ICON keeps the launcher icon's framing (used where the splash hands over). */
enum class HercPose { HEAD, ICON }

@Composable
fun Herc(
    mood: HercMood,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    pose: HercPose = HercPose.HEAD,
    /** Scale of the head; the launch animation pulses it. */
    mane: Float = 1f,
    /** Overrides for the launch animation, which opens the eyes from the splash's resting look. */
    eyesOpen: Float? = null,
    eyeColor: Color? = null,
) {
    val t = rememberInfiniteTransition(label = "herc")
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 5200; 1f at 0; 1f at 4700; 0.1f at 4800; 1f at 4920 }),
        label = "blink",
    )
    val glow by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    // Faces crossfade when the band changes; the launch animation keeps one face and opens the eyes instead.
    Crossfade(mood, animationSpec = tween(450), label = "face", modifier = modifier.size(size)) { m ->
        Canvas(Modifier.size(size).semantics { contentDescription = "Herc the lion, ${m.label.lowercase()}" }) {
            val box = if (pose == HercPose.HEAD) HercArt.HEAD_BOX else IconBox
            val bw = box[2] - box[0]
            val bh = box[3] - box[1]
            val k = minOf(this.size.width / bw, this.size.height / bh)
            val ox = (this.size.width - bw * k) / 2f
            val oy = (this.size.height - bh * k) / 2f
            withTransform({
                translate(ox, oy)
                scale(k, k, Offset.Zero)
                translate(-box[0], -box[1])
                scale(mane, mane, Offset(54f, 54f))
            }) {
                val open = eyesOpen ?: (m.eyesOpen * if (m == HercMood.SLEEPING) 1f else blink)
                drawLion(m.face, eyeColor ?: m.eyes, open, if (m == HercMood.SLEEPING && eyeColor == null) 0f else glow)
            }
        }
    }
}

private val IconBox = floatArrayOf(0f, 0f, 108f, 108f)

private val Mane = Color(0xFFFEB221)
private val ManeDark = Color(0xFFE5841B)
private val Face = Color(0xFFFEE1AD)
private val FaceDark = Color(0xFFF3C378)
private val Nose = Color(0xFF1A0F08)
private val EyeRim = Color(0xFF2A1608)

private val paths = HashMap<String, Path>()
private fun pathOf(d: String): Path = paths.getOrPut(d) { PathParser().parsePathString(d).toPath() }

private fun DrawScope.drawLion(face: Int, eyes: Color, open: Float, glow: Float) {
    HercArt.HEADS[face].forEach { p ->
        val c = when (p.ink) {
            HercInk.MANE -> Mane
            HercInk.MANE_DARK -> ManeDark
            HercInk.FACE -> Face
            HercInk.FACE_DARK -> FaceDark
            HercInk.NOSE, HercInk.EYE -> Nose
        }
        drawPath(pathOf(p.d), c)
    }
    // A soft glow in the band colour, so the eyes read even when Herc is small.
    if (glow > 0f) {
        HercArt.EYES[face].forEach { p ->
            val path = pathOf(p.d)
            val b = path.getBounds()
            for ((s, a) in listOf(2.2f to 0.10f, 1.6f to 0.18f)) {
                withTransform({ scale(s, s * 1.3f, b.center) }) { drawPath(path, eyes, alpha = a * glow) }
            }
        }
    }
    withTransform({ scale(1f, open, Offset(54f, HercArt.EYE_Y[face])) }) {
        HercArt.EYES[face].forEach { p ->
            val path = pathOf(p.d)
            drawPath(path, EyeRim, style = Stroke(1.4f, join = StrokeJoin.Round))
            drawPath(path, EyeRim)
            drawPath(path, eyes)
        }
    }
}
