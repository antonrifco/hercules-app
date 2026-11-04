package com.hercules.shared.app

import com.hercules.shared.cognitive.CheckIn
import com.hercules.shared.cognitive.ComponentKind
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.Importance
import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.ReadinessEngine
import com.hercules.shared.cognitive.SleepNight
import com.hercules.shared.cognitive.TimeWindow
import com.hercules.shared.cognitive.Verdict
import com.hercules.shared.cognitive.WindowKind
import com.hercules.shared.cognitive.formatDuration
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.DailySummary
import com.hercules.shared.sensors.SensorKind

/*
 * Screen-ready values for the SwiftUI apps. Kept free of nullable primitives inside lists and of
 * Kotlin-only types, so they read naturally from Swift. Wording matches the Android screens.
 */

/** One bar of a small chart: a night of sleep (hours) or a day of readiness (score). */
class Bar(
    val dayKey: String,
    val label: String,
    val value: Double,
    val present: Boolean,
    /** Readiness-scale score that picks the bar colour. */
    val colorScore: Int,
)

class PhoneSnapshot(
    val nowMs: Long,
    val today: String,
    val dayStartMs: Long,
    val readiness: Readiness?,
    val peak: TimeWindow?,
    val sleep: SleepNight?,
    /** Last 7 nights, oldest first, label = weekday. */
    val sleepWeek: List<Bar>,
    /** Last 14 days of readiness, oldest first, label = day of month on every other bar. */
    val trend: List<Bar>,
    val trendDays: Int,
    val trendAverage: Int,
    val checkIn: CheckIn?,
    /** Every event from today on, soonest first. */
    val events: List<EventStatus>,
    val nextEvent: EventStatus?,
    val todayEvents: List<EventStatus>,
)

class RecordLine(val label: String, val value: String)

class DataSnapshot(
    val today: DailySummary?,
    val records: List<RecordLine>,
    val pending: Long,
)

class ClockTimes(val bedHour: Int, val bedMinute: Int, val wakeHour: Int, val wakeMinute: Int)

class SleepDraft(
    val bedMs: Long,
    val wakeMs: Long,
    val minutes: Int,
    val inFuture: Boolean,
    val valid: Boolean,
    val message: String,
)

class DayChoice(val offset: Int, val dayStartMs: Long)

class EventDraft(val startMs: Long, val valid: Boolean, val passed: Boolean, val hint: TimingHint?)

/** Says in plain words whether a time is a good one for a demanding moment. */
class TimingHint(val window: WindowKind, val good: Boolean, val text: String) {
    companion object {
        fun of(kind: WindowKind, at: String): TimingHint = when (kind) {
            WindowKind.PEAK -> TimingHint(kind, true, "Good timing. Around $at your focus is usually at its sharpest of the day.")
            WindowKind.SECOND_PEAK -> TimingHint(kind, true, "Good timing. $at is usually your second wind, when focus comes back.")
            WindowKind.STEADY -> TimingHint(kind, true, "Fine timing. Around $at your focus is usually steady.")
            WindowKind.WARMUP -> TimingHint(kind, false, "A bit early. At $at your brain is usually still waking up; late morning is sharper.")
            WindowKind.DIP -> TimingHint(kind, false, "Tricky timing. $at usually falls in the afternoon dip; the morning is sharper.")
            WindowKind.EVENING, WindowKind.LATE -> TimingHint(kind, false, "Late. By $at focus usually drops; earlier in the day is sharper.")
        }
    }
}

// ------------------------------------------------------------------ watch

class SyncLine(
    val text: String,
    /** True when the last thing that happened was a delivery (cloud-done icon), false for a problem. */
    val ok: Boolean,
    val warning: Boolean,
)

class WatchMoment(
    val id: String,
    val title: String,
    val daysUntil: Int,
    /** "NOW" on the day, else "D-3". */
    val tag: String,
    val isToday: Boolean,
    val startMs: Long,
    val verdict: Verdict?,
    /** Readiness forecast for that day, or -1 when there is none yet. */
    val forecast: Int,
) {
    companion object {
        fun of(ev: EventStatus, todayScore: Int?): WatchMoment {
            val forecast = ev.projectedScore ?: todayScore.takeIf { ev.daysUntil <= 0 }
            return WatchMoment(
                id = ev.event.id,
                title = ev.event.title,
                daysUntil = ev.daysUntil,
                tag = if (ev.daysUntil <= 0) "NOW" else "D-${ev.daysUntil}",
                isToday = ev.daysUntil <= 0,
                startMs = ev.event.startMs,
                verdict = ev.advice?.verdict,
                forecast = forecast ?: -1,
            )
        }
    }
}

class WatchHome(
    val readiness: Readiness?,
    val peak: TimeWindow?,
    val sleepText: String,
    /** Latest heart rate, 0 when none yet. */
    val heartRate: Int,
    val steps: Long,
    val pending: Long,
    val sync: SyncLine,
    val moments: List<WatchMoment>,
)

class SyncReport(val samples: Int, val sessions: Int, val problem: String?) {
    val delivered: Int get() = samples + sessions
}

object WatchText {
    /** Mirrors the Wear OS sync line: problem first, then the last delivery, then what is waiting. */
    fun syncLine(lastOkMs: Long, problem: String?, pending: Long, nowMs: Long): SyncLine = when {
        problem != null -> SyncLine("Phone not reachable · $pending waiting", ok = false, warning = true)
        lastOkMs > 0 -> SyncLine("Sent to phone · ${ago(nowMs - lastOkMs)}", ok = true, warning = false)
        pending > 0 -> SyncLine("$pending readings to send", ok = true, warning = false)
        else -> SyncLine("Nothing to send yet", ok = true, warning = false)
    }

    fun ago(ms: Long): String {
        val min = (ms / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "${min}m ago"
            min < 24 * 60 -> "${min / 60}h ago"
            else -> "${min / (24 * 60)}d ago"
        }
    }
}

// ------------------------------------------------------------------ catalog

class BandRow(val band: ReadinessBand, val range: String)

/** Enum lists and small helpers, so Swift never has to deal with Kotlin enum/companion plumbing. */
object Catalog {
    const val MAX_DAYS_AHEAD = 7

    private val engine = ReadinessEngine(DayKeys())

    /** "85–100", "70–84", … best band first. */
    val bandRows: List<BandRow> = ReadinessBand.entries.mapIndexed { i, b ->
        val upper = if (i == 0) 100 else ReadinessBand.entries[i - 1].minScore - 1
        BandRow(b, "${b.minScore}–$upper")
    }
    val components: List<ComponentKind> = ComponentKind.entries.toList()
    /** The phases worth explaining in the guide. */
    val rhythm: List<WindowKind> = listOf(WindowKind.WARMUP, WindowKind.PEAK, WindowKind.DIP, WindowKind.SECOND_PEAK, WindowKind.LATE)
    val importances: List<Importance> = Importance.entries.toList()
    val sensors: List<SensorKind> = SensorKind.entries.toList()

    fun bandOf(score: Int): ReadinessBand = ReadinessBand.of(score.coerceIn(0, 100))

    /** "6h 40m" */
    fun duration(minutes: Int): String = formatDuration(minutes)

    /** Sleep component score for a night of [hours], used to colour sleep readings. */
    fun sleepScore(hours: Double): Int = engine.sleepScore(hours)
}
