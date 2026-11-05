package com.hercules.shared.cognitive

import com.hercules.shared.insights.DayKeys
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.DimensionScorer
import com.hercules.shared.readiness.MetricKey
import com.hercules.shared.readiness.PeakResult
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.ScoringParams
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Three-word reading of the score, the same on phone and watch. */
enum class Level(val label: String) { GOOD("Good"), MODERATE("Moderate"), LOW("Low") }

val ReadinessBand.level: Level
    get() = when (this) {
        ReadinessBand.PRIMA, ReadinessBand.BAIK -> Level.GOOD
        ReadinessBand.CUKUP -> Level.MODERATE
        ReadinessBand.RENDAH, ReadinessBand.TERKURAS -> Level.LOW
    }

/** "Learning your patterns. 3 more days." while personal baselines are still forming (spec §7), else null. */
val Readiness.learningNote: String?
    get() = if (baselineDays >= 7) null else {
        val left = 7 - baselineDays
        "Herc is learning your rhythm. $left more ${if (left == 1) "day" else "days"}."
    }

/** Where each part of the information comes from, so measurements are never confused with guesses. */
enum class Provenance(val label: String) {
    MEASURED("Measured by your devices"),
    PHONE_SENSOR("Measured by your phone's light sensor"),
    ASSESSED("From your Beat the Clock"),
    ESTIMATE("Estimate"),
    TIP("General advice"),
}

val Dimension.provenance: Provenance
    get() = when (this) {
        Dimension.CIRCADIAN -> Provenance.PHONE_SENSOR
        Dimension.COGNITIVE -> Provenance.ASSESSED
        else -> Provenance.MEASURED
    }

/** Part of the day, for timing the advice. */
enum class DayPart { MORNING, DAYTIME, EVENING, NIGHT }

enum class ActionKind { ASSESS, PREPARE, REFLECT, SLEEP, PEAK }

/** The one thing to do next. [eventId] opens that Moment; other kinds open their own screen. */
@Serializable
data class RecommendedAction(val kind: ActionKind, val title: String, val detail: String? = null, val eventId: String? = null)

/** Short, plain reason behind the score. [dimension] picks the icon. */
@Serializable
data class Reason(val dimension: Dimension, val text: String, val good: Boolean)

/** Tonight's sleep target and what it is likely to mean for tomorrow. All of it is an estimate. */
@Serializable
data class SleepAdvice(
    val bedtimeMs: Long,
    val targetMinutes: Int,
    val shiftEarlierMinutes: Int?,
    val tomorrowNote: String?,
    /** Estimated score tomorrow if you sleep 6 h / the target tonight, everything else unchanged. */
    val ifShort: Int?,
    val ifTarget: Int?,
)

/** Beat the Clock today. Optional and any time: play once or several times; the day uses the median. */
@Serializable
data class AssessInfo(val playsToday: Int)

@Serializable
data class DailyBrief(
    val level: Level?,
    val headline: String,
    val reason: Reason?,
    /** Today's predicted peak window, when there is one. */
    val peak: TimeWindow?,
    val peakNow: Boolean,
    val action: RecommendedAction,
    val sleep: SleepAdvice?,
    val assess: AssessInfo? = null,
)

/**
 * Turns the numbers into one message and one next step. Pure and deterministic, shared by phone, watch and the
 * notification planner so they never disagree.
 */
class Coach(private val dayKeys: DayKeys) {
    private val scorer = DimensionScorer()

    fun dayPart(nowMs: Long): DayPart = when (dayKeys.minuteOfDay(nowMs) / 60) {
        in 4..10 -> DayPart.MORNING
        in 11..17 -> DayPart.DAYTIME
        in 18..23 -> DayPart.EVENING
        else -> DayPart.NIGHT
    }

    fun headline(band: ReadinessBand): String = when (band) {
        ReadinessBand.PRIMA -> "Prime time. Go after your hardest work."
        ReadinessBand.BAIK -> "Strong day. Put it to work on what matters."
        ReadinessBand.CUKUP -> "Solid enough. Choose your battles and strike."
        ReadinessBand.RENDAH -> "Low tank. Keep it short, sharp and simple."
        ReadinessBand.TERKURAS -> "Recharge day. Rest now, roar tomorrow."
    }

    /** The weakest available dimension when it drags the score down, otherwise the strongest. */
    fun reason(r: Readiness): Reason? {
        val dims = r.dimensions.filter { it.available }
        val weakest = dims.minByOrNull { it.score!! }
        if (weakest != null && weakest.score!! < 65) return Reason(weakest.dimension, lowText(r, weakest.dimension), good = false)
        val best = dims.filter { it.dimension != Dimension.ACTIVITY }.maxByOrNull { it.score!! } ?: return null
        return Reason(best.dimension, goodText(r, best.dimension), good = true)
    }

    private fun sleepValue(r: Readiness) = r.dimension(Dimension.SLEEP).metrics.firstOrNull { it.key == MetricKey.SLEEP_DURATION }?.value

    private fun lowText(r: Readiness, d: Dimension): String = when (d) {
        Dimension.SLEEP -> sleepValue(r)?.let { "Short night: $it." } ?: "Sleep fell short of what you need."
        Dimension.CARDIO -> "Your body is still recovering."
        Dimension.ACTIVITY -> "Yesterday's movement was off your usual."
        Dimension.CIRCADIAN -> "Your daylight rhythm slipped yesterday."
        Dimension.COGNITIVE -> "Your timing was off your usual today."
    }

    private fun goodText(r: Readiness, d: Dimension): String = when (d) {
        Dimension.SLEEP -> sleepValue(r)?.let { "Slept strong: $it." } ?: "Slept strong."
        Dimension.CARDIO -> "Fully recharged."
        Dimension.ACTIVITY -> "Yesterday's movement hit the mark."
        Dimension.CIRCADIAN -> "Daylight rhythm on point."
        Dimension.COGNITIVE -> "Razor-sharp timing today."
    }

    /** Today's predicted peak window and whether it is happening now. */
    fun peakToday(peak: PeakWindowPrediction?, wakeMs: Long?, nowMs: Long): Pair<TimeWindow, Boolean>? {
        val w = peak?.window
        if (peak?.result != PeakResult.PREDICTED || w == null || wakeMs == null) return null
        val tw = TimeWindow(w, w.startMs(wakeMs), w.endMs(wakeMs))
        if (nowMs >= tw.endMs) return null
        return tw to (nowMs >= tw.startMs)
    }


    fun sleepAdvice(
        nowMs: Long,
        r: Readiness?,
        lastNight: SleepNight?,
        baselineWakeMinute: Int?,
        events: List<EventStatus>,
        sleepTargetMinutes: Int = (ScoringParams.V1.sleepTargetHours * 60).toInt(),
    ): SleepAdvice {
        val today = dayKeys.of(nowMs)
        val tomorrow = dayKeys.plusDays(today, 1)
        val tomorrowStart = dayKeys.startOf(tomorrow)
        val tomorrowEvent = events.firstOrNull { dayKeys.of(it.event.startMs) == tomorrow && it.phase != PrepPhase.DONE }
        val usualWake = tomorrowStart + (baselineWakeMinute ?: r?.let { dayKeys.minuteOfDay(it.wakeMs) } ?: (7 * 60)) * MIN_MS
        val wake = tomorrowEvent?.let { minOf(usualWake, it.event.startMs - (2.5 * HOUR_MS).toLong()) } ?: usualWake
        val bed = wake - sleepTargetMinutes * MIN_MS - 15 * MIN_MS
        val shift = lastNight?.let {
            val usualBed = dayKeys.minuteOfDay(it.startMs)
            val plan = dayKeys.minuteOfDay(bed)
            val diff = ((usualBed - plan) + 24 * 60) % (24 * 60)
            if (diff in 10..120 && it.asleepMinutes < sleepTargetMinutes - 30) (diff / 10) * 10 else null
        }
        val note = tomorrowEvent?.let { "Tomorrow: ${it.event.title} at ${dayKeys.clock(it.event.startMs)}." }
        return SleepAdvice(
            bedtimeMs = bed,
            targetMinutes = sleepTargetMinutes,
            shiftEarlierMinutes = shift,
            tomorrowNote = note,
            ifShort = r?.let { projectTomorrow(it, 6.0) },
            ifTarget = r?.let { projectTomorrow(it, sleepTargetMinutes / 60.0) },
        )
    }

    /** Today's score with only sleep duration replaced by [hours]. A rough estimate, labelled as such. */
    fun projectTomorrow(r: Readiness, hours: Double): Int {
        val dims = r.dimensions.map { d ->
            if (d.dimension != Dimension.SLEEP) return@map d
            val metrics = d.metrics.map { m ->
                if (m.key == MetricKey.SLEEP_DURATION) m.copy(score = scorer.sleepDurationScore(hours)) else m
            }
            val avail = metrics.filter { it.score != null }
            val s = if (avail.isEmpty()) null else (avail.sumOf { it.score!! * it.key.internalWeight } / avail.sumOf { it.key.internalWeight }).roundToInt()
            d.copy(score = s, metrics = metrics)
        }
        val avail = dims.filter { it.available }
        if (avail.isEmpty()) return r.score
        return (avail.sumOf { it.weight * it.score!! } / avail.sumOf { it.weight }).roundToInt().coerceIn(0, 100)
    }

    /**
     * One recommendation, in priority order: get ready for a Moment that is about to happen, reflect on one that just happened, wind down at night, keep preparing, use the peak, or (optional) play
     * today's first Beat the Clock.
     */
    fun action(
        nowMs: Long,
        r: Readiness?,
        assess: AssessInfo?,
        events: List<EventStatus>,
        reflectedIds: Set<String>,
        sleep: SleepAdvice?,
        peak: Pair<TimeWindow, Boolean>?,
    ): RecommendedAction {
        val part = dayPart(nowMs)
        val soon = events.firstOrNull { it.event.startMs > nowMs && it.event.startMs - nowMs <= 3 * HOUR_MS }
        if (soon != null) {
            val left = soon.todayTasks.count { it.id !in soon.doneTaskIds }
            return RecommendedAction(
                ActionKind.PREPARE, "Gear up: ${soon.event.title}",
                "In ${untilText(soon.event.startMs - nowMs)}" + if (left > 0) " · $left steps to go" else "",
                soon.event.id,
            )
        }
        val ended = events.firstOrNull {
            it.event.id !in reflectedIds && nowMs - it.event.startMs in (45 * MIN_MS)..(10 * HOUR_MS)
        }
        if (ended != null) return RecommendedAction(ActionKind.REFLECT, "How did you do?", ended.event.title, ended.event.id)
        if (part == DayPart.NIGHT) return RecommendedAction(ActionKind.SLEEP, "Time to rest", "Tonight's sleep powers tomorrow.")
        if (part == DayPart.EVENING && sleep != null) {
            return RecommendedAction(ActionKind.SLEEP, "Lights out by ${dayKeys.clock(sleep.bedtimeMs)}", sleep.tomorrowNote ?: sleep.shiftEarlierMinutes?.let { "Aim $it min earlier than last night." })
        }
        val prep = events.firstOrNull { it.phase != PrepPhase.DONE && it.todayTasks.any { t -> t.id !in it.doneTaskIds } }
        if (prep != null) {
            val done = prep.todayTasks.count { it.id in prep.doneTaskIds }
            return RecommendedAction(ActionKind.PREPARE, "Keep the momentum", "${prep.event.title} · $done/${prep.todayTasks.size} done today", prep.event.id)
        }
        if (peak != null) {
            return RecommendedAction(ActionKind.PEAK, if (peak.second) "Hit your hardest task now" else "Save your hardest task for ${dayKeys.clock(peak.first.startMs)}", "Your ${peak.first.window.label.lowercase()} peak window")
        }
        if (assess != null && assess.playsToday == 0 && part != DayPart.NIGHT) {
            return RecommendedAction(ActionKind.ASSESS, "Play Beat the Clock", "A 2-minute check on how sharp you are")
        }
        return RecommendedAction(ActionKind.PEAK, if (r != null && r.score >= 70) "Go take on the big stuff" else "Steady pace. Recover and reload")
    }

    fun brief(
        nowMs: Long,
        r: Readiness?,
        lastNight: SleepNight?,
        events: List<EventStatus>,
        reflectedIds: Set<String>,
        baselineWakeMinute: Int?,
        wakeMs: Long?,
        peak: PeakWindowPrediction?,
        playsToday: Int,
        insufficientReason: String? = null,
    ): DailyBrief {
        val sleep = sleepAdvice(nowMs, r, lastNight, baselineWakeMinute, events)
        val p = peakToday(peak, wakeMs, nowMs)
        val assess = AssessInfo(playsToday)
        return DailyBrief(
            level = r?.band?.level,
            headline = r?.let { headline(it.band) } ?: (insufficientReason ?: "No score yet."),
            reason = r?.let { reason(it) },
            peak = p?.first,
            peakNow = p?.second == true,
            action = action(nowMs, r, assess, events, reflectedIds, sleep, p),
            sleep = sleep,
            assess = assess,
        )
    }

    private fun untilText(ms: Long): String {
        val m = (ms / MIN_MS).toInt().coerceAtLeast(1)
        return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"
    }
}
