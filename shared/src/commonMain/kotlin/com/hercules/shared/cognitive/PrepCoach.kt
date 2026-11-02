package com.hercules.shared.cognitive

import com.hercules.shared.insights.DayKeys
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Coaches the run-up to an important event: what phase we're in, today's checklist,
 * a preparation score, a projected readiness for the event, and on the day itself a
 * go / reschedule recommendation.
 */
class PrepCoach(private val dayKeys: DayKeys, private val engine: ReadinessEngine) {

    fun phase(daysUntil: Int): PrepPhase = when {
        daysUntil < 0 -> PrepPhase.DONE
        daysUntil == 0 -> PrepPhase.DAY
        daysUntil == 1 -> PrepPhase.EVE
        daysUntil == 2 -> PrepPhase.SHARPEN
        else -> PrepPhase.FOUNDATION
    }

    fun tasks(event: PlanEvent, phase: PrepPhase, typicalWakeMinute: Int?): List<PrepTask> {
        val clockAt = { ms: Long -> dayKeys.clock(ms) }
        // Needs ~8h sleep and ~2.5h awake before the event; never later than usual bedtime.
        val wakeForEvent = event.startMs - (2.5 * HOUR_MS).toLong()
        val bedtime = clockAt(wakeForEvent - 8 * HOUR_MS - 15 * MIN_MS)
        return when (phase) {
            PrepPhase.FOUNDATION -> listOf(
                PrepTask("sleep_window", "Sleep and wake at consistent times", "Keep it within ±30 minutes. Consistent sleep in the days before matters more than one long night."),
                PrepTask("move", "20–30 minutes of light to moderate exercise", "Improves sleep quality and executive function. Avoid workouts that are completely new to you."),
                PrepTask("alcohol", "Limit alcohol", "Alcohol disrupts REM sleep and raises your overnight heart rate."),
            ) + typeFoundation(event.type)
            PrepPhase.SHARPEN -> listOf(
                typeRehearsal(event.type),
                PrepTask("no_alcohol", "No alcohol from today", "Protect the quality of the last two nights of sleep."),
                PrepTask("no_hard_training", "Skip hard training", "A hard session can raise your resting heart rate for 1–2 days."),
            )
            PrepPhase.EVE -> listOf(
                PrepTask("final_prep", "Finish preparing by 20:00", "Cramming late into the night lowers tomorrow's performance."),
                PrepTask("logistics", "Lay out clothes, documents and route", "Fewer small decisions tomorrow morning saves mental energy."),
                PrepTask("caffeine_cut", "No caffeine after 14:00", "Caffeine lingers 5–6 hours and delays sleep."),
                PrepTask("screens", "No screens for the last hour before bed", "Helps you fall asleep faster."),
                PrepTask("bedtime", "In bed by $bedtime", "Gets you ~8 hours of sleep and up 2.5 hours before the event."),
            )
            PrepPhase.DAY -> listOf(
                PrepTask("light", "10 minutes of morning light", "Morning daylight boosts alertness and anchors your body clock."),
                PrepTask("breakfast", "Breakfast with protein", "Steady energy without a blood-sugar spike."),
                PrepTask("caffeine", "Caffeine around ${clockAt(event.startMs - 45 * MIN_MS)}", "If you drink coffee: 30–60 minutes before the start is when it peaks."),
                PrepTask("walk", "Brisk 10-minute walk before you start", "Increases blood flow to the brain and lowers anxiety."),
                PrepTask("breathing", "2 minutes of box breathing before you go in", "In 4 seconds, hold 4, out 4, hold 4. Calms the nervous system."),
                PrepTask("arrive", "Be ready 15 minutes early", "Avoid the stress of rushing."),
            )
            PrepPhase.DONE -> emptyList()
        }
    }

    private fun typeFoundation(type: EventType): List<PrepTask> = when (type) {
        EventType.INTERVIEW -> listOf(
            PrepTask("research", "Research the company and role", "Understand their product, challenges and culture."),
            PrepTask("star", "Prepare 5 STAR stories", "Situation, Task, Action, Result for behavioural questions."),
        )
        EventType.NEGOTIATION -> listOf(
            PrepTask("batna", "Define your BATNA and walk-away point", "Set your limits now, with a cool head, not at the table."),
            PrepTask("counterpart", "Study the other side's interests", "What do they need, and what is cheap for you but valuable to them?"),
        )
        EventType.PRESENTATION -> listOf(
            PrepTask("outline", "Outline the story and 3 key messages", "Audiences remember little, so make sure it's the right things."),
            PrepTask("deck", "Prepare your slides", "Finish early so there is time to rehearse."),
        )
        EventType.EXAM -> listOf(
            PrepTask("spaced", "Spaced practice", "Short daily sessions beat an all-nighter."),
        )
        EventType.DECISION -> listOf(
            PrepTask("options", "Write down options and criteria", "Set criteria before judging options to avoid bias."),
            PrepTask("premortem", "Run a pre-mortem", "Imagine this decision failed a year from now: what caused it?"),
        )
        EventType.OTHER -> listOf(PrepTask("materials", "Prepare the core material", "Get the important parts done early."))
    }

    private fun typeRehearsal(type: EventType): PrepTask = when (type) {
        EventType.INTERVIEW -> PrepTask("rehearse", "Mock interview", "Practise answers out loud, ideally with another person.")
        EventType.NEGOTIATION -> PrepTask("rehearse", "Role-play the negotiation", "Rehearse responses for 3 scenarios: easy, tough, and deadlock.")
        EventType.PRESENTATION -> PrepTask("rehearse", "Timed dress rehearsal", "At least twice, including Q&A.")
        EventType.EXAM -> PrepTask("rehearse", "Practice exam", "Work through questions under the real time limit.")
        EventType.DECISION -> PrepTask("rehearse", "Talk it through with someone you trust", "An outside view helps catch blind spots.")
        EventType.OTHER -> PrepTask("rehearse", "Final practice / review", "Go over the most important parts again.")
    }

    /** Average sleep score of recent nights, penalized for irregular wake times. */
    fun sleepConsistency(nights: List<SleepNight>): Int? {
        if (nights.isEmpty()) return null
        val base = nights.map { engine.sleepScore(it.asleepMinutes / 60.0) }.average()
        val wakes = nights.map { dayKeys.minuteOfDay(it.endMs).toDouble() }
        val mean = wakes.average()
        val sd = sqrt(wakes.sumOf { (it - mean) * (it - mean) } / wakes.size)
        val penalty = when {
            sd > 90 -> 15
            sd > 60 -> 10
            sd > 30 -> 4
            else -> 0
        }
        return (base - penalty).roundToInt().coerceIn(1, 100)
    }

    /**
     * Preparation 0..100 = recent readiness (45%) + sleep consistency (35%) + checklist completion (20%).
     * Missing parts are left out and the rest re-weighted.
     */
    fun prepScore(recentScores: List<Int>, sleepConsistency: Int?, completion: Double?): Int? {
        val parts = buildList {
            if (recentScores.isNotEmpty()) add(recentScores.average() to 0.45)
            sleepConsistency?.let { add(it.toDouble() to 0.35) }
            completion?.let { add(it * 100 to 0.20) }
        }
        if (parts.isEmpty()) return null
        return (parts.sumOf { it.first * it.second } / parts.sumOf { it.second }).roundToInt().coerceIn(1, 100)
    }

    /** Rough forecast for the event moment, assuming current habits continue. */
    fun projected(recentScores: List<Int>, lastNightHours: Double?, completion: Double?, event: PlanEvent, typicalWakeMinute: Int?): Int? {
        if (recentScores.isEmpty()) return null
        var p = recentScores.takeLast(3).average()
        if (completion != null && completion >= 0.7) p += 4
        if (lastNightHours != null && lastNightHours < 6.5) p -= 6
        val wake = dayKeys.startOf(dayKeys.of(event.startMs)) + (typicalWakeMinute ?: 7 * 60) * MIN_MS
        val window = engine.windows(wake).firstOrNull { event.startMs >= it.startMs && event.startMs < it.endMs }?.kind
            ?: WindowKind.LATE
        return (p + window.adjustment).roundToInt().coerceIn(1, 100)
    }

    /** Go / reschedule advice using today's readiness at the event's time. */
    fun advice(event: PlanEvent, r: Readiness): DecisionAdvice {
        val expected = engine.scoreAt(r, event.startMs)
        val high = event.importance != Importance.NORMAL
        val verdict = when {
            expected >= 75 -> Verdict.GO
            expected >= 60 -> Verdict.GO_WITH_PREP
            expected >= 45 -> if (high) Verdict.CONSIDER_RESCHEDULE else Verdict.GO_WITH_PREP
            else -> if (high) Verdict.RESCHEDULE else Verdict.CONSIDER_RESCHEDULE
        }
        val what = if (event.type == EventType.OTHER) "moment" else event.type.label.lowercase()
        val headline = when (verdict) {
            Verdict.GO -> "You're in good shape for this $what. Go ahead with confidence."
            Verdict.GO_WITH_PREP -> "Fine to proceed. Have a game plan to keep performance high."
            Verdict.CONSIDER_RESCHEDULE -> "Not ideal today. If it can move without a big cost, consider rescheduling."
            Verdict.RESCHEDULE -> "You're running on empty. If at all possible, reschedule to another day."
        }

        val reasons = mutableListOf<String>()
        r.components.filter { it.score < 70 }.forEach { reasons += "${it.kind.label}: ${it.detail}" }
        val window = engine.windowAt(r, event.startMs)
        if (window.adjustment < 0) reasons += "${dayKeys.clock(event.startMs)} falls in your \"${window.label}\" phase"
        if (reasons.isEmpty()) reasons += "All indicators look good"

        val actions = mutableListOf<String>()
        val peak = engine.peak(r)
        if (window.adjustment < 0 && abs(event.startMs - peak.startMs) < 12 * HOUR_MS) {
            actions += "If you can, move it to ${dayKeys.clock(peak.startMs)}–${dayKeys.clock(peak.endMs)} (your peak focus)."
        }
        actions += "Caffeine around ${dayKeys.clock(event.startMs - 45 * MIN_MS)} (if you drink coffee)."
        actions += "A brisk 10-minute walk, then 2 minutes of box breathing before you start."
        if (expected < 70) {
            actions += when (event.type) {
                EventType.NEGOTIATION, EventType.DECISION -> "Don't decide on the spot. Ask for 24 hours before agreeing to anything big."
                EventType.INTERVIEW -> "Bring notes on your 3 main STAR stories; answer with structure, don't improvise."
                EventType.PRESENTATION -> "Keep your outline handy; focus on the 3 key messages only."
                EventType.EXAM -> "Do the easiest questions first to build momentum."
                EventType.OTHER -> "Use a written checklist so nothing slips."
            }
        }
        return DecisionAdvice(verdict, expected, headline, reasons, actions)
    }
}
