package com.hercules.shared.cognitive

import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.DimensionScore
import com.hercules.shared.readiness.MomentStatus
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.PrepTask
import com.hercules.shared.readiness.Preparation
import com.hercules.shared.readiness.ReadinessCalculation
import com.hercules.shared.readiness.ReadinessConfidence
import com.hercules.shared.readiness.Urgency
import kotlinx.serialization.Serializable

/** The resolved main sleep that ended on the morning of [dayKey], from the best available provider. */
@Serializable
data class SleepNight(
    val dayKey: String,
    val startMs: Long,
    val endMs: Long,
    val asleepMinutes: Int,
    val sleepingHrAvg: Double? = null,
    val sleepingHrLow: Double? = null,
    val source: String = SOURCE_SENSOR,
    /** Time awake in bed; null when the provider reports neither awake time nor stages. */
    val awakeMinutes: Int? = null,
    val deepMinutes: Int? = null,
    val remMinutes: Int? = null,
    val lightMinutes: Int? = null,
    /** Package (Health Connect data origin) or device that recorded it. */
    val provider: String? = null,
) {
    val corrected: Boolean get() = source == SOURCE_USER

    companion object {
        /** Detected by the Hercules watch. */
        const val SOURCE_SENSOR = "sensor"
        /** Read from Health Connect. */
        const val SOURCE_HEALTH_CONNECT = "health_connect"
        /** Times the user corrected; automatic detection never overwrites these. */
        const val SOURCE_USER = "user"
    }
}

/** Readiness classifications (spec §2.2). Enum names are kept from earlier builds for stored data. */
@Serializable
enum class ReadinessBand(val label: String, val minScore: Int, val headline: String, val meaning: String) {
    PRIMA("Prime", 85, "Prime: ready for your hardest work", "Every signal is firing. Take on the work that matters most."),
    BAIK("Good", 70, "Good: ready for important work", "You're in good shape. Point your best hours at what matters."),
    CUKUP("Fair", 55, "Fair: choose your battles", "You can still win today. Save demanding work for your stronger hours."),
    RENDAH("Low", 40, "Low: keep it lean", "Running below your usual. Keep big tasks short and well prepared."),
    TERKURAS("Drained", 0, "Drained: recharge first", "Body and mind need a reset. Go light today and come back stronger.");

    companion object {
        fun of(score: Int): ReadinessBand = entries.first { score >= it.minScore }
    }
}

@Serializable
enum class Tone { POSITIVE, CAUTION, ACTION }

@Serializable
data class Suggestion(val title: String, val detail: String, val tone: Tone)

/** A clock range for an assessment window on a given day. */
@Serializable
data class TimeWindow(val window: AssessmentWindow, val startMs: Long, val endMs: Long)

/** A published score (coverage ≥ 70% and sleep available). Everything it was calculated with travels along. */
@Serializable
data class Readiness(
    val dayKey: String,
    val score: Int,
    val band: ReadinessBand,
    val calc: ReadinessCalculation,
    /** Valid sleep nights behind the personal baselines (for "learning your patterns"). */
    val baselineDays: Int,
    val wakeMs: Long,
    val suggestions: List<Suggestion>,
    val computedMs: Long,
) {
    val coverage: Double get() = calc.coverage
    val confidence: ReadinessConfidence get() = calc.confidence
    val dimensions: List<DimensionScore> get() = calc.dimensions
    fun dimension(d: Dimension): DimensionScore = calc.dimensions.first { it.dimension == d }
}

/** Today's evaluation: a published [readiness], or the reasons there isn't one yet (INSUFFICIENT_DATA). */
@Serializable
data class Evaluation(
    val dayKey: String,
    val calc: ReadinessCalculation,
    val readiness: Readiness?,
    val wakeMs: Long,
    val computedMs: Long,
)

// ------------------------------------------------------------------ moments

@Serializable
enum class EventType(val label: String) {
    INTERVIEW("Interview"),
    NEGOTIATION("Negotiation / deal"),
    PRESENTATION("Presentation"),
    EXAM("Exam"),
    DECISION("Big decision"),
    OTHER("Other");

    companion object {
        private val KEYWORDS = listOf(
            INTERVIEW to listOf("interview", "hiring", "recruit", "screening"),
            NEGOTIATION to listOf("negotiat", "deal", "contract", "salary", "offer", "closing", "vendor"),
            PRESENTATION to listOf("present", "pitch", "demo", "talk", "keynote", "speech", "webinar"),
            EXAM to listOf("exam", "test", "quiz", "certification", "assessment", "thesis", "defense"),
            DECISION to listOf("decision", "decide", "choose", "board", "invest"),
        )

        /** Infers the kind of moment from its title (only for the icon), so the user never has to pick one. */
        fun fromTitle(title: String): EventType {
            val t = title.lowercase()
            return KEYWORDS.firstOrNull { (_, words) -> words.any { it in t } }?.first ?: OTHER
        }
    }
}

/** A Moment (spec §8.2). The class keeps its earlier name; the UI always says "moment". */
@Serializable
data class PlanEvent(
    val id: String,
    val title: String,
    val type: EventType,
    val startMs: Long,
    val urgency: Urgency,
    val notes: String = "",
    val createdMs: Long,
    val timezoneId: String = "",
    val status: MomentStatus = MomentStatus.ACTIVE,
    val updatedMs: Long = createdMs,
    val completedMs: Long? = null,
    val cancelledMs: Long? = null,
)

@Serializable
data class DayScore(val dayKey: String, val score: Int?)

@Serializable
data class EventStatus(
    val event: PlanEvent,
    val daysUntil: Int,
    val phase: PrepPhase,
    val todayTasks: List<PrepTask>,
    val doneTaskIds: Set<String>,
    val preparation: Preparation,
    val history: List<DayScore>,
)

/** Compact state the phone pushes to the watch (Wearable DataClient). */
@Serializable
data class WatchState(
    val readiness: Readiness?,
    val nextEvent: EventStatus?,
    val updatedMs: Long,
    /** Upcoming Moments, soonest first. */
    val upcoming: List<EventStatus> = emptyList(),
    val sleepMinutes: Int? = null,
    /** The phone's message and next step, so both screens always say the same thing. */
    val brief: DailyBrief? = null,
    /** Moments already rated on the phone, so the watch doesn't ask again. */
    val reflectedIds: Set<String> = emptySet(),
    /** Which reminders the user turned on (set on the phone), so the watch follows the same choices. */
    val reminders: NudgePrefs = NudgePrefs(),
    /** Today's evaluation, including why there's no score yet. */
    val evaluation: Evaluation? = null,
    val peak: PeakWindowPrediction? = null,
    /** Beat the Clock runs played today on either device. */
    val playsToday: Int = 0,
    /** Wake time the phone uses today, so both devices file runs under the same part of the day. */
    val wakeMs: Long? = null,
)

/** How a Moment felt afterwards, 1 (rough) to 5 (great). Shown next to readiness, never claimed to be caused by it. */
@Serializable
data class EventReflection(val eventId: String, val rating: Int, val createdMs: Long)

/** "6h 40m" */
fun formatDuration(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

internal const val MIN_MS = 60_000L
internal const val HOUR_MS = 60 * MIN_MS
