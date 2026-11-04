package com.hercules.shared.readiness

import kotlinx.serialization.Serializable

/** Urgency changes how recommendations are prioritized, never the CRS (spec §8.4). */
@Serializable
enum class Urgency(val label: String) {
    NORMAL("Normal"),
    CRITICAL("Critical");

    companion object {
        /** Older builds stored NORMAL / HIGH / CRITICAL importance. "Important" is read as Critical. */
        fun fromStored(s: String): Urgency = when (s) {
            "NORMAL" -> NORMAL
            else -> CRITICAL
        }
    }
}

@Serializable
enum class MomentStatus { ACTIVE, COMPLETED, CANCELLED }

object MomentRules {
    const val MAX_ACTIVE = 5
    const val MAX_DAYS_AHEAD = 7
    private const val DAY_MS = 86_400_000L

    sealed interface Check {
        data object Ok : Check
        data class Rejected(val reason: String) : Check
    }

    /**
     * A new or edited Moment must be in the future, at most seven days ahead, and fit within five active Moments.
     * [activeOthers] are the other active future Moments (the edited one excluded).
     */
    fun check(title: String, startMs: Long, nowMs: Long, activeOthers: Int): Check = when {
        title.isBlank() -> Check.Rejected("Give your Moment a name.")
        startMs <= nowMs -> Check.Rejected("Pick a time that's still ahead.")
        startMs - nowMs > MAX_DAYS_AHEAD * DAY_MS -> Check.Rejected("Moments can be up to 7 days out.")
        activeOthers >= MAX_ACTIVE -> Check.Rejected("You've got $MAX_ACTIVE upcoming Moments. Finish or cancel one first.")
        else -> Check.Ok
    }

    /** Lifecycle (spec §8.5): cancelled stays cancelled, a passed time completes it. */
    fun statusAt(stored: MomentStatus, startMs: Long, nowMs: Long): MomentStatus = when {
        stored == MomentStatus.CANCELLED -> MomentStatus.CANCELLED
        startMs <= nowMs -> MomentStatus.COMPLETED
        else -> MomentStatus.ACTIVE
    }
}

@Serializable
enum class PrepStatus(val label: String) {
    ON_TRACK("On Track"),
    NEEDS_ATTENTION("Needs Attention"),
    INSUFFICIENT_DATA("Insufficient Data"),
}

@Serializable
enum class PeakAlignment(val label: String) {
    WITHIN_PEAK("In your peak window"),
    NEAR_PEAK("Close to your peak window"),
    OUTSIDE_PEAK("Outside your peak window"),
    UNKNOWN("Peak window not known yet"),
}

@Serializable
enum class RecCategory(val label: String) {
    SLEEP("Sleep"),
    RECOVERY("Recovery"),
    ACTIVITY("Activity"),
    LIGHT("Light"),
    COGNITIVE("Cognitive preparation"),
    EVENT_DAY("On the day"),
}

/** Run-up phases by time remaining (spec §8.8). */
@Serializable
enum class PrepPhase(val label: String, val description: String) {
    FOUNDATION("Build the base", "Lock in a steady sleep schedule, recover well and keep moving."),
    MAINTAIN("Hold the line", "Stick to the routine and keep an eye on your score."),
    FINAL("Final stretch", "Guard your sleep and skip anything that drains you."),
    DAY("Game day", "Stay calm and play to your best hours."),
    DONE("Done", "This Moment is behind you.");

    companion object {
        fun of(daysUntil: Int): PrepPhase = when {
            daysUntil < 0 -> DONE
            daysUntil == 0 -> DAY
            daysUntil <= 2 -> FINAL
            daysUntil <= 4 -> MAINTAIN
            else -> FOUNDATION
        }
    }
}

/** One recommendation. [id] is stable for the same advice, so ticking it off survives regeneration. */
@Serializable
data class PrepTask(
    val id: String,
    val title: String,
    val detail: String,
    val category: RecCategory = RecCategory.SLEEP,
    /** Lower = more important. */
    val priority: Int = 50,
    /** The evidence behind it, in plain words; null for general guidance of the phase. */
    val evidence: String? = null,
)

@Serializable
data class Preparation(
    val status: PrepStatus,
    val reasons: List<String>,
    val peak: PeakAlignment,
    val latestScore: Int?,
    val confidence: ReadinessConfidence?,
    val coverage: Double?,
)

/** What the preparation engine looks at (spec §8.6). Everything nullable is simply unknown. */
data class PrepContext(
    val nowMs: Long,
    val daysUntil: Int,
    val eventStartMs: Long,
    val urgency: Urgency,
    val latestScore: Int?,
    val latestConfidence: ReadinessConfidence?,
    val latestCoverage: Double?,
    val sleepScore: Int?,
    val consistencyScore: Int?,
    val cardioScore: Int?,
    val activityScore: Int?,
    val lightScore: Int?,
    /** Average sleep of the last three nights, minutes. */
    val recentSleepMinutes: Double?,
    val sleepTargetMinutes: Int,
    val peak: PeakWindowPrediction?,
    /** Usual wake time on the event day (epoch ms), to place the assessment windows. */
    val eventDayWakeMs: Long?,
    /** Formats epoch ms as local HH:mm. */
    val clock: (Long) -> String,
)

class MomentPreparationEngine {

    fun peakAlignment(c: PrepContext): PeakAlignment {
        val w = c.peak?.window
        val wake = c.eventDayWakeMs
        if (c.peak?.result != PeakResult.PREDICTED || w == null || wake == null) return PeakAlignment.UNKNOWN
        val from = w.startMs(wake)
        val to = w.endMs(wake)
        return when {
            c.eventStartMs in from until to -> PeakAlignment.WITHIN_PEAK
            c.eventStartMs in (from - NEAR_MS) until (to + NEAR_MS) -> PeakAlignment.NEAR_PEAK
            else -> PeakAlignment.OUTSIDE_PEAK
        }
    }

    fun evaluate(c: PrepContext): Preparation {
        val peak = peakAlignment(c)
        if (c.latestScore == null || c.latestConfidence == null || c.latestConfidence == ReadinessConfidence.INSUFFICIENT) {
            return Preparation(PrepStatus.INSUFFICIENT_DATA, listOf("No recent score to judge your prep by yet."), peak, null, c.latestConfidence, c.latestCoverage)
        }
        val reasons = mutableListOf<String>()
        if (c.latestScore < 55) reasons += "Your latest score is ${c.latestScore}, below Fair."
        if (c.recentSleepMinutes != null && c.recentSleepMinutes < c.sleepTargetMinutes - 60) {
            reasons += "You've averaged ${hm(c.recentSleepMinutes.toInt())} of sleep over the last nights."
        } else if (c.sleepScore != null && c.sleepScore < 60) {
            reasons += "Sleep & Recovery is low (${c.sleepScore})."
        }
        if (c.consistencyScore != null && c.consistencyScore < 60) reasons += "Your sleep times have been irregular."
        if (c.cardioScore != null && c.cardioScore < 55) reasons += "Recovery signals are below your usual."
        if (c.activityScore != null && c.activityScore < 50) reasons += "Yesterday's activity was far from your usual range."
        val status = if (reasons.isEmpty()) PrepStatus.ON_TRACK else PrepStatus.NEEDS_ATTENTION
        if (reasons.isEmpty()) reasons += "Sleep, recovery and activity are right where they should be."
        return Preparation(status, reasons, peak, c.latestScore, c.latestConfidence, c.latestCoverage)
    }

    /**
     * Recommendations for today, only what is still practical for the time left and supported by evidence
     * (spec §8.7–8.9). Critical moments raise priority.
     */
    fun recommendations(c: PrepContext): List<PrepTask> {
        val phase = PrepPhase.of(c.daysUntil)
        if (phase == PrepPhase.DONE) return emptyList()
        val out = mutableListOf<PrepTask>()
        val boost = if (c.urgency == Urgency.CRITICAL) -10 else 0
        val bedtime = c.clock(c.eventStartMs - (2.5 * HOUR).toLong() - c.sleepTargetMinutes * 60_000L - 15 * 60_000L)
        val lowSleep = c.recentSleepMinutes != null && c.recentSleepMinutes < c.sleepTargetMinutes - 30
        val irregular = c.consistencyScore != null && c.consistencyScore < 75
        val strained = c.cardioScore != null && c.cardioScore < 60

        when (phase) {
            PrepPhase.FOUNDATION -> {
                out += PrepTask(
                    "sleep_schedule", "Lock in your sleep schedule",
                    "Go to bed and get up within about 30 minutes of the same time each day.", RecCategory.SLEEP, 20 + boost,
                    when {
                        irregular -> "Your sleep times have varied recently."
                        c.consistencyScore == null -> "Not enough sleep history yet to judge consistency."
                        else -> null
                    },
                )
                if (strained) out += recovery(boost)
                out += PrepTask(
                    "move_sustainably", "Keep moving, don't overdo it",
                    "Daily walks or your usual training. Avoid starting intense new routines this week.", RecCategory.ACTIVITY, 40 + boost,
                    c.activityScore?.takeIf { it < 70 }?.let { "Activity has been outside your usual range." },
                )
                out += morningLight(boost, c.lightScore)
            }
            PrepPhase.MAINTAIN -> {
                out += PrepTask("keep_routine", "Stick to your routine", "Same bed and wake times, same rhythm of work and rest.", RecCategory.SLEEP, 25 + boost, if (irregular) "Your sleep times have varied recently." else null)
                out += PrepTask("check_readiness", "Check your score each morning", "If it drops, ease off and protect sleep.", RecCategory.RECOVERY, 45 + boost, c.latestScore?.let { "Today's score: $it." })
                if (strained) out += recovery(boost)
                if (c.lightScore != null && c.lightScore < 60) out += morningLight(boost, c.lightScore)
            }
            PrepPhase.FINAL -> {
                out += PrepTask(
                    "sleep_opportunity", "Full night tonight: in bed by $bedtime",
                    "Allows about ${c.sleepTargetMinutes / 60} hours of sleep and time to wake up before the moment.", RecCategory.SLEEP, 10 + boost,
                    if (lowSleep) "You've been sleeping less than your target." else null,
                )
                out += PrepTask("no_exertion", "Save your energy", "Keep training light so you peak on the day.", RecCategory.RECOVERY, 30 + boost, if (strained) "Recovery signals are below your usual." else null)
                out += PrepTask("dim_evening", "Dim the lights in the evening", "Avoid bright light in the last two hours before bed.", RecCategory.LIGHT, 45 + boost, null)
            }
            PrepPhase.DAY -> {
                val before = c.eventStartMs > c.nowMs
                if (before) {
                    out += PrepTask("no_rush", "Build in time. No rushing.", "Plan to be ready well before ${c.clock(c.eventStartMs)}.", RecCategory.EVENT_DAY, 10 + boost, null)
                    out += PrepTask("distractions", "Kill distractions", "Silence notifications and clear your space before you start.", RecCategory.EVENT_DAY, 20 + boost, null)
                    peakTask(c)?.let { out += it.copy(priority = it.priority + boost) }
                    if (c.nowMs < c.eventStartMs - HOUR && c.clockHour(c.nowMs) < 12) out += morningLight(boost, c.lightScore)
                }
            }
            PrepPhase.DONE -> Unit
        }
        return out.sortedBy { it.priority }
    }

    private fun recovery(boost: Int) = PrepTask(
        "recovery", "Go easy on hard efforts",
        "Your body is recovering less well than usual. Keep effort moderate and rest well.", RecCategory.RECOVERY, 30 + boost,
        "Recovery signals are below your usual.",
    )

    private fun morningLight(boost: Int, lightScore: Int?) = PrepTask(
        "morning_light", "Catch morning daylight",
        "10–20 minutes outside soon after waking helps set your body clock.", RecCategory.LIGHT, 50 + boost,
        lightScore?.takeIf { it < 60 }?.let { "Your light pattern has been off (score $it)." },
    )

    private fun peakTask(c: PrepContext): PrepTask? = when (peakAlignment(c)) {
        PeakAlignment.WITHIN_PEAK -> PrepTask("peak_within", "Your moment falls in your peak window", "You tend to perform best at this time of day.", RecCategory.COGNITIVE, 40, "Based on your Beat the Clock history.")
        PeakAlignment.NEAR_PEAK -> PrepTask("peak_near", "Do your final preparation in your peak window", "It's close to the moment: use it for a last focused run-through.", RecCategory.COGNITIVE, 40, "Based on your Beat the Clock history.")
        PeakAlignment.OUTSIDE_PEAK -> c.peak?.window?.let { w ->
            val wake = c.eventDayWakeMs ?: return null
            PrepTask("peak_outside", "Use ${c.clock(w.startMs(wake))}–${c.clock(w.endMs(wake))} for your hardest preparation", "That's when you tend to perform best. The moment itself stays where it is.", RecCategory.COGNITIVE, 40, "Based on your Beat the Clock history.")
        }
        PeakAlignment.UNKNOWN -> null
    }

    private fun PrepContext.clockHour(ms: Long): Int = clock(ms).substringBefore(":").toIntOrNull() ?: 0

    companion object {
        private const val HOUR = 3_600_000.0
        private const val NEAR_MS = 90 * 60_000L

        private fun hm(minutes: Int) = "${minutes / 60}h ${minutes % 60}m"

        /**
         * Several Moments at once (spec §8.10): Critical and sooner first, shared advice merged (highest priority
         * kept), no duplicates. Returns each task with the titles of the Moments it serves.
         */
        fun consolidate(items: List<Triple<String, Urgency, Pair<Long, List<PrepTask>>>>): List<Pair<PrepTask, List<String>>> {
            val ordered = items.sortedWith(compareBy({ it.second != Urgency.CRITICAL }, { it.third.first }))
            val merged = LinkedHashMap<String, Pair<PrepTask, MutableList<String>>>()
            for ((title, _, pair) in ordered) {
                for (t in pair.second) {
                    val cur = merged[t.id]
                    if (cur == null) merged[t.id] = t to mutableListOf(title)
                    else merged[t.id] = (if (t.priority < cur.first.priority) t else cur.first) to cur.second.apply { add(title) }
                }
            }
            return merged.values.map { it.first to it.second.toList() }.sortedBy { it.first.priority }
        }
    }
}
