package com.hercules.shared.cognitive

import kotlinx.serialization.Serializable

/** Main sleep that ended on the morning of [dayKey]. */
@Serializable
data class SleepNight(
    val dayKey: String,
    val startMs: Long,
    val endMs: Long,
    val asleepMinutes: Int,
    val sleepingHrAvg: Double? = null,
    val sleepingHrLow: Double? = null,
    val source: String = SOURCE_SENSOR,
) {
    val corrected: Boolean get() = source == SOURCE_USER

    companion object {
        const val SOURCE_SENSOR = "sensor"
        /** Times the user corrected; automatic detection never overwrites these. */
        const val SOURCE_USER = "user"
    }
}

/** Morning self-report. Energy and stress are on a 1..5 scale. Sleep is never asked: it comes from the watch. */
@Serializable
data class CheckIn(
    val dayKey: String,
    val sleepHours: Double?,
    val energy: Int,
    val stress: Int,
    val createdMs: Long,
)

@Serializable
enum class ReadinessBand(val label: String, val minScore: Int, val headline: String, val meaning: String) {
    PRIMA(
        "Prime", 85,
        "Prime condition for important decisions",
        "Focus, working memory and emotional control are at their best. Ideal for interviews, negotiations or big decisions.",
    ),
    BAIK(
        "Good", 70,
        "Good condition for important work",
        "Ready for important activities. A short warm-up and picking the right hour will get the most out of today.",
    ),
    CUKUP(
        "Fair", 55,
        "Fair, so choose your timing",
        "You can still perform well if it's scheduled in your peak window. Avoid impulsive decisions and use a checklist.",
    ),
    RENDAH(
        "Low", 40,
        "Postpone big decisions that aren't urgent",
        "Risk judgement and impulse control tend to drop. If you must decide, get a second opinion and pause before committing.",
    ),
    TERKURAS(
        "Drained", 0,
        "Make recovery today's priority",
        "Body and brain need to recover. If possible, reschedule important things and stick to light tasks.",
    );

    companion object {
        fun of(score: Int): ReadinessBand = entries.first { score >= it.minScore }
    }
}

@Serializable
enum class ComponentKind(val label: String, val weight: Double, val explanation: String) {
    SLEEP("Sleep", 0.40, "Last night's sleep duration. The biggest single factor for focus, memory and decision-making."),
    RECOVERY("Recovery", 0.30, "Sleeping/resting heart rate compared with your personal average. Higher = your body is under strain."),
    SELF_REPORT("How you feel", 0.15, "Energy and stress from the morning check-in. Your own perception is valuable data."),
    ACTIVITY("Activity", 0.15, "Yesterday's activity compared with your usual. Too little or too much both lower readiness."),
}

@Serializable
data class ReadinessComponent(val kind: ComponentKind, val score: Int, val detail: String)

@Serializable
enum class Confidence(val label: String) {
    HIGH("High accuracy"),
    MEDIUM("Medium accuracy"),
    LOW("Calibrating"),
}

@Serializable
enum class WindowKind(val label: String, val adjustment: Int) {
    WARMUP("Waking up", -10),
    PEAK("Peak focus", 3),
    STEADY("Steady", -2),
    DIP("Afternoon dip", -8),
    SECOND_PEAK("Second wind", 0),
    EVENING("Winding down", -8),
    LATE("Late", -18),
}

@Serializable
data class TimeWindow(val kind: WindowKind, val startMs: Long, val endMs: Long)

@Serializable
enum class Tone { POSITIVE, CAUTION, ACTION }

@Serializable
data class Suggestion(val title: String, val detail: String, val tone: Tone)

@Serializable
data class Readiness(
    val dayKey: String,
    val score: Int,
    val band: ReadinessBand,
    val components: List<ReadinessComponent>,
    val confidence: Confidence,
    val baselineDays: Int,
    val wakeMs: Long,
    val windows: List<TimeWindow>,
    val suggestions: List<Suggestion>,
    val computedMs: Long,
)

// ------------------------------------------------------------------ planning

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

        /** Infers the kind of moment from its title, so the user never has to pick one. */
        fun fromTitle(title: String): EventType {
            val t = title.lowercase()
            return KEYWORDS.firstOrNull { (_, words) -> words.any { it in t } }?.first ?: OTHER
        }
    }
}

@Serializable
enum class Importance(val label: String) {
    NORMAL("Normal"),
    HIGH("Important"),
    CRITICAL("Critical"),
}

@Serializable
data class PlanEvent(
    val id: String,
    val title: String,
    val type: EventType,
    val startMs: Long,
    val importance: Importance,
    val notes: String = "",
    val createdMs: Long,
)

@Serializable
enum class PrepPhase(val label: String, val description: String) {
    FOUNDATION("Build the foundation", "Lock in a consistent sleep pattern and prepare the core material."),
    SHARPEN("Sharpen", "Rehearse under realistic conditions and cut anything that hurts recovery."),
    EVE("The day before", "Finish preparing early and protect tonight's sleep."),
    DAY("Event day", "Wake up body and brain, then perform at your best hour."),
    DONE("Done", "The event has passed. Hope it went great!"),
}

@Serializable
data class PrepTask(val id: String, val title: String, val detail: String)

@Serializable
enum class Verdict(val label: String) {
    GO("Go ahead"),
    GO_WITH_PREP("Go, with a game plan"),
    CONSIDER_RESCHEDULE("Consider rescheduling"),
    RESCHEDULE("Postpone if you can"),
}

@Serializable
data class DecisionAdvice(
    val verdict: Verdict,
    val expectedScore: Int,
    val headline: String,
    val reasons: List<String>,
    val actions: List<String>,
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
    val prepScore: Int?,
    val projectedScore: Int?,
    val history: List<DayScore>,
    val advice: DecisionAdvice?,
)

/** Compact state the phone pushes to the watch (Wearable DataClient). */
@Serializable
data class WatchState(
    val readiness: Readiness?,
    val nextEvent: EventStatus?,
    val updatedMs: Long,
    /** Upcoming big moments (up to 7 days), soonest first, for the watch's schedule list. */
    val upcoming: List<EventStatus> = emptyList(),
)
