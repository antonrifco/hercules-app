package com.hercules.mobile.ui

import androidx.compose.animation.animateColorAsState
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
 * Herc, the Hercules mascot: an adult male lion, designed with Higgsfield and traced into `tools/mascot/mascot.py`
 * (which writes [HercArt] and the launcher icon, so the app and the icon are the same drawing).
 *
 * His eyes carry today's band colour: green for Prime, lime for Good, amber for Fair, orange for Low, red for
 * Drained. With no score yet they narrow to dim slits: he is resting until there is something to read.
 */
enum class HercMood(val label: String, val eyes: Color) {
    PRIME("Prime", Color(0xFF2EE6A0)),
    GOOD("Good", Color(0xFF9BE35A)),
    FAIR("Fair", Color(0xFFFFC857)),
    LOW("Low", Color(0xFFFF8C4C)),
    DRAINED("Drained", Color(0xFFFF4D6D)),
    SLEEPING("No score yet", Color(0xFF8A7A66)),
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
    /** Vertical offset in canvas units (the head is about 64 tall); the launch animation lifts with it. */
    hop: Float = 0f,
) {
    val eyes by animateColorAsState(mood.eyes, tween(700), label = "eyes")
    val open by animateFloatAsState(if (mood == HercMood.SLEEPING) 0.3f else 1f, tween(500), label = "open")
    val t = rememberInfiniteTransition(label = "herc")
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 5200; 1f at 0; 1f at 4700; 0.1f at 4800; 1f at 4920 }),
        label = "blink",
    )
    val glow by t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    Canvas(modifier.size(size).semantics { contentDescription = "Herc the lion, ${mood.label.lowercase()}" }) {
        val box = if (pose == HercPose.HEAD) HercArt.HEAD_BOX else IconBox
        val bw = box[2] - box[0]
        val bh = box[3] - box[1]
        val k = minOf(this.size.width / bw, this.size.height / bh)
        val ox = (this.size.width - bw * k) / 2f
        val oy = (this.size.height - bh * k) / 2f
        withTransform({
            translate(ox, oy)
            scale(k, k, Offset.Zero)
            translate(-box[0], -box[1] + hop)
            scale(mane, mane, Offset(54f, 54f))
        }) {
            val sleeping = mood == HercMood.SLEEPING
            drawLion(eyes, open * (if (sleeping) 1f else blink), if (sleeping) 0f else glow)
        }
    }
}

private val IconBox = floatArrayOf(0f, 0f, 108f, 108f)

private val Mane = Color(0xFFFFB627)
private val ManeDark = Color(0xFFE8871E)
private val Face = Color(0xFFFFE3B0)
private val FaceDark = Color(0xFFF5C77E)
private val Nose = Color(0xFF3B2414)
private val EyeRim = Color(0xFF2A1608)

private val paths = HashMap<String, Path>()
private fun pathOf(d: String): Path = paths.getOrPut(d) { PathParser().parsePathString(d).toPath() }

private fun DrawScope.drawLion(eyes: Color, open: Float, glow: Float) {
    HercArt.HEAD.forEach { p ->
        val c = when (p.ink) {
            HercInk.MANE -> Mane
            HercInk.MANE_DARK -> ManeDark
            HercInk.FACE -> Face
            HercInk.FACE_DARK -> FaceDark
            HercInk.NOSE -> Nose
            HercInk.EYE -> eyes
        }
        drawPath(pathOf(p.d), c)
    }
    // A soft glow in the band colour, so the eyes read even when Herc is small.
    if (glow > 0f) {
        HercArt.EYES.forEach { p ->
            val path = pathOf(p.d)
            val b = path.getBounds()
            for ((s, a) in listOf(2.4f to 0.10f, 1.7f to 0.18f)) {
                withTransform({ scale(s, s * 1.4f, b.center) }) { drawPath(path, eyes, alpha = a * glow) }
            }
        }
    }
    withTransform({ scale(1f, open, Offset(54f, HercArt.EYE_Y)) }) {
        HercArt.EYES.forEach { p ->
            val path = pathOf(p.d)
            drawPath(path, EyeRim, style = Stroke(1.6f, join = StrokeJoin.Round))
            drawPath(path, EyeRim)
            drawPath(path, eyes)
        }
    }
}
