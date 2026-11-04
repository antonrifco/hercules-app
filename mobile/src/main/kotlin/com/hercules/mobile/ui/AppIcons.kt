package com.hercules.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CoPresent
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hercules.shared.readiness.Dimension
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Timer
import com.hercules.shared.cognitive.EventType
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.Tone
import com.hercules.shared.sensors.SensorKind

/** One icon language for the whole app: Material "Rounded" line icons, no emoji. */
val ReadinessBand.icon: ImageVector
    get() = when (this) {
        ReadinessBand.PRIMA -> Icons.Rounded.Bolt
        ReadinessBand.BAIK -> Icons.Rounded.CheckCircle
        ReadinessBand.CUKUP -> Icons.Rounded.Balance
        ReadinessBand.RENDAH -> Icons.AutoMirrored.Rounded.TrendingDown
        ReadinessBand.TERKURAS -> Icons.Rounded.BatteryAlert
    }

val Tone.icon: ImageVector
    get() = when (this) {
        Tone.POSITIVE -> Icons.Rounded.CheckCircle
        Tone.CAUTION -> Icons.Rounded.WarningAmber
        Tone.ACTION -> Icons.Rounded.TipsAndUpdates
    }

val Dimension.icon: ImageVector
    get() = when (this) {
        Dimension.SLEEP -> Icons.Rounded.Bedtime
        Dimension.CARDIO -> Icons.Rounded.MonitorHeart
        Dimension.ACTIVITY -> Icons.AutoMirrored.Rounded.DirectionsWalk
        Dimension.CIRCADIAN -> Icons.Rounded.WbSunny
        Dimension.COGNITIVE -> Icons.Rounded.Timer
    }

val EventType.icon: ImageVector
    get() = when (this) {
        EventType.INTERVIEW -> Icons.Rounded.Work
        EventType.NEGOTIATION -> Icons.Rounded.Handshake
        EventType.PRESENTATION -> Icons.Rounded.CoPresent
        EventType.EXAM -> Icons.Rounded.School
        EventType.DECISION -> Icons.Rounded.AltRoute
        EventType.OTHER -> Icons.Rounded.Star
    }

val SensorKind.icon: ImageVector
    get() = when (this) {
        SensorKind.HEART_RATE -> Icons.Rounded.MonitorHeart
        SensorKind.SLEEP -> Icons.Rounded.Bedtime
        SensorKind.STEPS -> Icons.AutoMirrored.Rounded.DirectionsWalk
        SensorKind.CALORIES -> Icons.Rounded.LocalFireDepartment
        SensorKind.DISTANCE -> Icons.Rounded.Straighten
        SensorKind.FLOORS -> Icons.Rounded.Stairs
    }

/** Icon on a softly tinted rounded square. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 36.dp, iconSize: Dp = 20.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize)) }
}
