package com.hercules.shared.cognitive

import com.hercules.shared.insights.DayKeys
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

/** "Learning your patterns. 3 more days." while the personal baseline is still forming, else null. */
val Readiness.learningNote: String?
    get() = if (baselineDays >= 7) null else {
        val left = 7 - baselineDays
        "Learning your patterns. $left more ${if (left == 1) "day" else "days"}."
    }

/** Where each part of the screen's information comes from, so measurements are never confused with guesses. */
enum class Provenance(val label: String) {
    MEASURED("Measured by your watch"),
    REPORTED("You reported this"),
    ESTIMATE("Estimate"),
    TIP("General advice"),
}

val ComponentKind.provenance: Provenance
    get() = if (this == ComponentKind.SELF_REPORT) Provenance.REPORTED else Provenance.MEASURED

enum class Moment { MORNING, DAYTIME, EVENING, NIGHT }

enum class ActionKind { CHECK_IN, PREPARE, REFLECT, SLEEP, PLAN_EVENT, FOCUS }

/** The one thing to do next. [eventId] opens that event; null kinds open their own sheet or tab. */
@Serializable
data class RecommendedAction(val kind: ActionKind, val title: String, val detail: String? = null, val eventId: String? = null)

/** Short, plain reason behind the score. [kind] picks the icon. */
@Serializable
data class Reason(val kind: ComponentKind, val text: String, val good: Boolean)

/** Tonight's sleep target and what it is likely to mean for tomorrow. All of it is an estimate. */
@Serializable
data class SleepAdvice(
    val bedtimeMs: Long,
    val targetMinutes: Int,
    val shiftEarlierMinutes: Int?,
    val tomorrowNote: String?,
    /** Estimated readiness tomorrow if you sleep 6h / 7.5h / 8h+ tonight. */
    val ifShort: Int?,
    val ifTarget: Int?,
)

@Serializable
data class DailyBrief(
    val level: Level?,
    val headline: String,
    val reason: Reason?,
    val focus: TimeWindow?,
    val focusNow: Boolean,
    val action: RecommendedAction,
    val sleep: SleepAdvice?,
)

/**
 * Turns the numbers into one message and one next step. Pure and deterministic, shared by phone, watch
 * and the notification planner so they never disagree.
 */
class Coach(private val dayKeys: DayKeys, private val engine: ReadinessEngine) {

    fun moment(nowMs: Long): Moment = when (dayKeys.minuteOfDay(nowMs) / 60) {
        in 4..10 -> Moment.MORNING
        in 11..17 -> Moment.DAYTIME
        in 18..23 -> Moment.EVENING
        else -> Moment.NIGHT
    }

    fun headline(band: ReadinessBand): String = when (band) {
        ReadinessBand.PRIMA -> "Great day for deep work."
        ReadinessBand.BAIK -> "Good day for deep work."
        ReadinessBand.CUKUP -> "Okay day. Use your best hours."
        ReadinessBand.RENDAH -> "Go easy. Delay big decisions."
        ReadinessBand.TERKURAS -> "Recovery day. Keep it light."
    }

    /** The weakest part when something is dragging the score down, otherwise the strongest. */
    fun reason(r: Readiness, checkIn: CheckIn?): Reason? {
        val weakest = r.components.minByOrNull { it.score }
        if (weakest != null && weakest.score < 70) return Reason(weakest.kind, lowText(weakest, checkIn), good = false)
        val best = r.components.filter { it.kind != ComponentKind.ACTIVITY }.maxByOrNull { it.score } ?: return null
        return Reason(best.kind, goodText(best), good = true)
    }

    private fun lowText(c: ReadinessComponent, checkIn: CheckIn?): String = when (c.kind) {
        ComponentKind.SLEEP -> "Short sleep: ${c.detail.substringBefore(" asleep")}."
        ComponentKind.RECOVERY -> "Sleeping heart rate is higher than usual."
        ComponentKind.SELF_REPORT -> if ((checkIn?.stress ?: 0) >= 4) "You feel stressed." else "Your energy is low."
        ComponentKind.ACTIVITY -> "Yesterday's activity was off your usual."
    }

    private fun goodText(c: ReadinessComponent): String = when (c.kind) {
        ComponentKind.SLEEP -> "Slept well: ${c.detail.substringBefore(" asleep")}."
        ComponentKind.RECOVERY -> "Your body is well recovered."
        ComponentKind.SELF_REPORT -> "You feel good."
        ComponentKind.ACTIVITY -> "Yesterday's activity was balanced."
    }

    /** Current focus window if you're in one, else the next one today. */
    fun focus(r: Readiness, nowMs: Long): Pair<TimeWindow, Boolean>? {
        val good = r.windows.filter { it.kind == WindowKind.PEAK || it.kind == WindowKind.SECOND_PEAK }
        good.firstOrNull { nowMs >= it.startMs && nowMs < it.endMs }?.let { return it to true }
        return good.firstOrNull { it.startMs > nowMs }?.let { it to false }
    }

    fun sleepAdvice(
        nowMs: Long,
        r: Readiness?,
        lastNight: SleepNight?,
        baselineWakeMinute: Int?,
        events: List<EventStatus>,
    ): SleepAdvice {
        val today = dayKeys.of(nowMs)
        val tomorrow = dayKeys.plusDays(today, 1)
        val tomorrowStart = dayKeys.startOf(tomorrow)
        val tomorrowEvent = events.firstOrNull { dayKeys.of(it.event.startMs) == tomorrow && it.phase != PrepPhase.DONE }
        val usualWake = tomorrowStart + (baselineWakeMinute ?: r?.let { dayKeys.minuteOfDay(it.wakeMs) } ?: (7 * 60)) * MIN_MS
        val wake = tomorrowEvent?.let { minOf(usualWake, it.event.startMs - (2.5 * HOUR_MS).toLong()) } ?: usualWake
        val need = if (lastNight != null && lastNight.asleepMinutes < 6.5 * 60) 8 * 60 else 7 * 60 + 30
        val bed = wake - need * MIN_MS - 15 * MIN_MS

        val shift = lastNight?.let {
            val usualBed = dayKeys.minuteOfDay(it.startMs)
            val plan = dayKeys.minuteOfDay(bed)
            val diff = ((usualBed - plan) + 24 * 60) % (24 * 60)
            if (diff in 10..120 && it.asleepMinutes < 7 * 60 + 15) (diff / 10) * 10 else null
        }
        val note = tomorrowEvent?.let { "Tomorrow: ${it.event.title} at ${dayKeys.clock(it.event.startMs)}." }
        return SleepAdvice(
            bedtimeMs = bed,
            targetMinutes = need,
            shiftEarlierMinutes = shift,
            tomorrowNote = note,
            ifShort = r?.let { projectTomorrow(it, 6.0) },
            ifTarget = r?.let { projectTomorrow(it, need / 60.0) },
        )
    }

    /** Rough readiness if sleep were [hours] and everything else stayed as it is today. An estimate, nothing more. */
    fun projectTomorrow(r: Readiness, hours: Double): Int {
        val others = r.components.filter { it.kind != ComponentKind.SLEEP }
        val w = others.sumOf { it.kind.weight } + ComponentKind.SLEEP.weight
        val sum = others.sumOf { it.score * it.kind.weight } + engine.sleepScore(hours) * ComponentKind.SLEEP.weight
        return (sum / w).roundToInt().coerceIn(1, 100)
    }

    /**
     * One recommendation, in priority order: finish today's check-in, get ready for what is about to happen,
     * reflect on what just happened, wind down at night, keep preparing, plan something, or use your best hours.
     */
    fun action(
        nowMs: Long,
        r: Readiness?,
        checkIn: CheckIn?,
        events: List<EventStatus>,
        reflectedIds: Set<String>,
        sleep: SleepAdvice?,
    ): RecommendedAction {
        val moment = moment(nowMs)
        if (checkIn == null && moment != Moment.NIGHT && moment != Moment.EVENING) {
            return RecommendedAction(
                ActionKind.CHECK_IN,
                "Check in",
                if (r == null) "Gives you a first score today." else "Takes 10 seconds. Sharpens your score.",
            )
        }
        val soon = events.firstOrNull { it.event.startMs > nowMs && it.event.startMs - nowMs <= 3 * HOUR_MS }
        if (soon != null) {
            val left = soon.todayTasks.size - soon.doneTaskIds.size
            return RecommendedAction(
                ActionKind.PREPARE, "Get ready: ${soon.event.title}",
                "In ${untilText(soon.event.startMs - nowMs)}" + if (left > 0) " · $left steps left" else "",
                soon.event.id,
            )
        }
        val ended = events.firstOrNull {
            it.event.id !in reflectedIds && nowMs - it.event.startMs in (45 * MIN_MS)..(10 * HOUR_MS)
        }
        if (ended != null) return RecommendedAction(ActionKind.REFLECT, "How did it go?", ended.event.title, ended.event.id)
        if (moment == Moment.NIGHT) return RecommendedAction(ActionKind.SLEEP, "Get some sleep", "Rest protects tomorrow's score.")
        if (moment == Moment.EVENING && sleep != null) {
            return RecommendedAction(ActionKind.SLEEP, "Be in bed by ${dayKeys.clock(sleep.bedtimeMs)}", sleep.tomorrowNote ?: sleep.shiftEarlierMinutes?.let { "Try sleeping $it min earlier." })
        }
        val prep = events.firstOrNull { it.phase != PrepPhase.DONE && it.todayTasks.isNotEmpty() && it.doneTaskIds.size < it.todayTasks.size }
        if (prep != null) {
            return RecommendedAction(
                ActionKind.PREPARE, "Continue preparation", "${prep.event.title} · ${prep.doneTaskIds.size}/${prep.todayTasks.size} done today", prep.event.id,
            )
        }
        if (events.none { it.phase != PrepPhase.DONE } && r != null && r.band.level != Level.LOW) {
            return RecommendedAction(ActionKind.PLAN_EVENT, "Plan a big moment", "Get a daily run-up and a go / wait call.")
        }
        val f = r?.let { focus(it, nowMs) }
        return if (f != null) {
            RecommendedAction(ActionKind.FOCUS, if (f.second) "Do your hardest task now" else "Save your hardest task for ${dayKeys.clock(f.first.startMs)}")
        } else {
            RecommendedAction(ActionKind.FOCUS, "Keep it light and recover")
        }
    }

    fun brief(
        nowMs: Long,
        r: Readiness?,
        checkIn: CheckIn?,
        lastNight: SleepNight?,
        events: List<EventStatus>,
        reflectedIds: Set<String>,
        baselineWakeMinute: Int? = null,
    ): DailyBrief {
        val sleep = sleepAdvice(nowMs, r, lastNight, baselineWakeMinute, events)
        val f = r?.let { focus(it, nowMs) }
        return DailyBrief(
            level = r?.band?.level,
            headline = r?.let { headline(it.band) } ?: "No score yet.",
            reason = r?.let { reason(it, checkIn) },
            focus = f?.first,
            focusNow = f?.second == true,
            action = action(nowMs, r, checkIn, events, reflectedIds, sleep),
            sleep = sleep,
        )
    }

    private fun untilText(ms: Long): String {
        val m = (ms / MIN_MS).toInt().coerceAtLeast(1)
        return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m"
    }
}

/** How an event felt afterwards, 1 (rough) to 5 (great). Shown next to readiness, never claimed to be caused by it. */
@Serializable
data class EventReflection(val eventId: String, val rating: Int, val createdMs: Long)
