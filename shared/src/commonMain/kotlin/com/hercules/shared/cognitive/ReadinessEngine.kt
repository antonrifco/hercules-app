package com.hercules.shared.cognitive

import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.DailySummary
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import kotlin.math.roundToInt

internal const val MIN_MS = 60_000L
internal const val HOUR_MS = 60 * MIN_MS

/** "6h 40m" */
fun formatDuration(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

/** Piecewise-linear interpolation over sorted (x, y) points; clamps outside the range. */
internal fun curve(x: Double, points: List<Pair<Double, Double>>): Double {
    if (x <= points.first().first) return points.first().second
    for (i in 1 until points.size) {
        val (x1, y1) = points[i]
        if (x <= x1) {
            val (x0, y0) = points[i - 1]
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
    }
    return points.last().second
}

/**
 * Derives the main sleep from activity-state transitions (watch) and the heart rate during it.
 * The "night of dayKey" is sleep inside [dayKey-1 18:00, dayKey 14:00].
 */
class SleepAnalyzer(private val dayKeys: DayKeys) {

    fun window(dayKey: String): Pair<Long, Long> {
        val midnight = dayKeys.startOf(dayKey)
        return (midnight - 6 * HOUR_MS) to (midnight + 14 * HOUR_MS)
    }

    /** [samples] should start ~12 h before the window so the state at window start is known. */
    fun analyze(dayKey: String, samples: List<Sample>, nowMs: Long): SleepNight? {
        val (from, to) = window(dayKey)
        val end = minOf(to, nowMs)
        if (end <= from) return null
        val states = samples.filter { it.type == MetricType.ACTIVITY_STATE }.sortedBy { it.startMs }
        val intervals = mutableListOf<Pair<Long, Long>>()
        for ((i, s) in states.withIndex()) {
            if (s.value != ActivityState.ASLEEP) continue
            val next = states.getOrNull(i + 1)?.startMs ?: end
            val a = maxOf(s.startMs, from)
            val b = minOf(next, end)
            if (b > a) intervals += a to b
        }
        val asleepMs = intervals.sumOf { it.second - it.first }
        if (asleepMs < 60 * MIN_MS) return null

        val hr = samples
            .filter { s -> s.type == MetricType.HEART_RATE && intervals.any { s.startMs in it.first until it.second } }
            .map { it.value }
            .sorted()
        return SleepNight(
            dayKey = dayKey,
            startMs = intervals.first().first,
            endMs = intervals.last().second,
            asleepMinutes = (asleepMs / MIN_MS).toInt(),
            sleepingHrAvg = if (hr.isEmpty()) null else hr.average(),
            sleepingHrLow = if (hr.isEmpty()) null else hr[(hr.size * 0.1).toInt().coerceAtMost(hr.lastIndex)],
        )
    }
}

/** Personal norms from the last 14 days (today excluded). */
data class Baseline(
    val days: Int,
    val sleepMinutes: Double?,
    val sleepingHr: Double?,
    val restingHr: Double?,
    val steps: Double?,
    val wakeMinuteOfDay: Int?,
) {
    companion object {
        val EMPTY = Baseline(0, null, null, null, null, null)

        fun from(nights: List<SleepNight>, days: List<DailySummary>, dayKeys: DayKeys): Baseline {
            fun <T> List<T>.avgOf(min: Int, f: (T) -> Double?): Double? =
                mapNotNull(f).takeIf { it.size >= min }?.average()
            val wakes = nights.map { dayKeys.minuteOfDay(it.endMs) }.sorted()
            return Baseline(
                days = maxOf(nights.size, days.count { it.restingHr != null }),
                sleepMinutes = nights.avgOf(3) { it.asleepMinutes.toDouble() },
                sleepingHr = nights.avgOf(3) { it.sleepingHrAvg },
                restingHr = days.avgOf(3) { it.restingHr?.toDouble() },
                steps = days.filter { it.steps > 0 }.avgOf(3) { it.steps.toDouble() },
                wakeMinuteOfDay = wakes.getOrNull(wakes.size / 2).takeIf { wakes.size >= 3 },
            )
        }
    }
}

data class ReadinessInput(
    val dayKey: String,
    val nowMs: Long,
    val sleep: SleepNight?,
    val checkIn: CheckIn?,
    val todayRestingHr: Long?,
    val yesterday: DailySummary?,
    val baseline: Baseline,
)

/**
 * Cognitive readiness 0..100. A heuristic built on well-established effects:
 * sleep duration (strongest), physiological load (resting/sleeping HR vs personal baseline),
 * self-reported energy/stress, prior-day activity balance, and time since waking.
 * Not a medical device.
 */
class ReadinessEngine(private val dayKeys: DayKeys) {

    fun sleepScore(hours: Double): Int = curve(
        hours,
        listOf(0.0 to 5.0, 3.0 to 15.0, 4.0 to 30.0, 5.0 to 48.0, 6.0 to 68.0, 7.0 to 88.0, 7.5 to 97.0, 8.0 to 100.0, 9.0 to 100.0, 10.0 to 92.0, 11.0 to 85.0),
    ).roundToInt()

    fun recoveryScore(deltaBpm: Double): Int = curve(
        deltaBpm,
        listOf(0.0 to 100.0, 3.0 to 88.0, 6.0 to 70.0, 10.0 to 45.0, 15.0 to 20.0, 25.0 to 10.0),
    ).roundToInt()

    fun selfScore(energy: Int, stress: Int): Int {
        val e = (energy.coerceIn(1, 5) - 1) / 4.0
        val s = (5 - stress.coerceIn(1, 5)) / 4.0
        return ((e * 0.6 + s * 0.4) * 100).roundToInt().coerceIn(10, 100)
    }

    fun activityScore(steps: Long, baselineSteps: Double?): Int =
        if (baselineSteps != null && baselineSteps > 0) {
            curve(steps / baselineSteps, listOf(0.0 to 62.0, 0.3 to 68.0, 0.6 to 92.0, 0.8 to 100.0, 1.5 to 100.0, 2.0 to 88.0, 3.0 to 72.0, 10.0 to 65.0))
        } else {
            curve(steps.toDouble(), listOf(0.0 to 65.0, 2000.0 to 72.0, 5000.0 to 95.0, 8000.0 to 100.0, 15000.0 to 100.0, 25000.0 to 82.0, 60000.0 to 70.0))
        }.roundToInt()

    fun compute(input: ReadinessInput): Readiness? {
        val b = input.baseline
        val components = mutableListOf<ReadinessComponent>()

        // Sleep
        // Sleep only comes from the watch (optionally corrected by the user), never from a questionnaire.
        val sleepHours = input.sleep?.let { it.asleepMinutes / 60.0 }
        if (sleepHours != null) {
            val avg = b.sleepMinutes?.let { " · usual ${formatDuration(it.roundToInt())}" }.orEmpty()
            val src = if (input.sleep.corrected) " (corrected)" else ""
            components += ReadinessComponent(ComponentKind.SLEEP, sleepScore(sleepHours), "${formatDuration((sleepHours * 60).roundToInt())} asleep$src$avg")
        }

        // Recovery: sleeping HR is the cleanest signal, else resting HR
        val recovery: Pair<Double, String>? = when {
            input.sleep?.sleepingHrAvg != null && b.sleepingHr != null ->
                (input.sleep.sleepingHrAvg - b.sleepingHr) to "Sleeping HR"
            b.restingHr != null && (input.todayRestingHr ?: input.yesterday?.restingHr) != null ->
                ((input.todayRestingHr ?: input.yesterday!!.restingHr!!).toDouble() - b.restingHr) to "Resting HR"
            else -> null
        }
        if (recovery != null) {
            val (delta, label) = recovery
            val d = delta.roundToInt()
            val what = if (label == "Sleeping HR") "Sleeping heart rate" else "Resting heart rate"
            val text = when {
                d >= 2 -> "$what is $d bpm higher than usual."
                d <= -2 -> "$what is ${-d} bpm lower than usual."
                else -> "$what is about usual."
            }
            components += ReadinessComponent(ComponentKind.RECOVERY, recoveryScore(delta), text)
        }

        // Self report
        input.checkIn?.let {
            components += ReadinessComponent(ComponentKind.SELF_REPORT, selfScore(it.energy, it.stress), "Energy ${it.energy}/5 · Stress ${it.stress}/5")
        }

        if (components.isEmpty()) return null // activity alone says too little

        // Activity (yesterday)
        input.yesterday?.takeIf { it.steps > 0 }?.let { y ->
            val ratio = b.steps?.let { " (${(y.steps / it * 100).roundToInt()}% of usual)" }.orEmpty()
            components += ReadinessComponent(ComponentKind.ACTIVITY, activityScore(y.steps, b.steps), "${y.steps} steps yesterday$ratio")
        }

        val weight = components.sumOf { it.kind.weight }
        val score = (components.sumOf { it.score * it.kind.weight } / weight).roundToInt().coerceIn(1, 100)
        val has = components.map { it.kind }.toSet()
        val confidence = when {
            input.sleep != null && ComponentKind.RECOVERY in has && b.days >= 7 -> Confidence.HIGH
            (ComponentKind.SLEEP in has || ComponentKind.RECOVERY in has) && (b.days >= 3 || input.checkIn != null) -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        val wake = input.sleep?.endMs ?: (dayKeys.startOf(input.dayKey) + (b.wakeMinuteOfDay ?: 7 * 60) * MIN_MS)
        val windows = windows(wake)
        val band = ReadinessBand.of(score)

        return Readiness(
            dayKey = input.dayKey,
            score = score,
            band = band,
            components = components,
            confidence = confidence,
            baselineDays = b.days,
            wakeMs = wake,
            windows = windows,
            suggestions = suggestions(band, components, input, sleepHours, windows, wake),
            computedMs = input.nowMs,
        )
    }

    /** Typical circadian performance curve, anchored to wake time. */
    fun windows(wakeMs: Long): List<TimeWindow> {
        fun w(kind: WindowKind, fromH: Double, toH: Double) =
            TimeWindow(kind, wakeMs + (fromH * HOUR_MS).toLong(), wakeMs + (toH * HOUR_MS).toLong())
        return listOf(
            w(WindowKind.WARMUP, 0.0, 1.0),
            w(WindowKind.STEADY, 1.0, 2.0),
            w(WindowKind.PEAK, 2.0, 5.0),
            w(WindowKind.STEADY, 5.0, 6.5),
            w(WindowKind.DIP, 6.5, 8.5),
            w(WindowKind.SECOND_PEAK, 8.5, 11.5),
            w(WindowKind.STEADY, 11.5, 13.0),
            w(WindowKind.EVENING, 13.0, 16.0),
            w(WindowKind.LATE, 16.0, 20.0),
        )
    }

    fun windowAt(r: Readiness, t: Long): WindowKind =
        r.windows.firstOrNull { t >= it.startMs && t < it.endMs }?.kind
            ?: if (t < r.wakeMs) WindowKind.WARMUP else WindowKind.LATE

    /** Expected readiness at a specific moment of that day (e.g. the interview time). */
    fun scoreAt(r: Readiness, t: Long): Int = (r.score + windowAt(r, t).adjustment).coerceIn(1, 100)

    fun peak(r: Readiness): TimeWindow = r.windows.first { it.kind == WindowKind.PEAK }

    private fun range(w: TimeWindow) = "${dayKeys.clock(w.startMs)}–${dayKeys.clock(w.endMs)}"

    private fun suggestions(
        band: ReadinessBand,
        components: List<ReadinessComponent>,
        input: ReadinessInput,
        sleepHours: Double?,
        windows: List<TimeWindow>,
        wake: Long,
    ): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val peak = windows.first { it.kind == WindowKind.PEAK }
        val second = windows.first { it.kind == WindowKind.SECOND_PEAK }
        val dip = windows.first { it.kind == WindowKind.DIP }
        out += when (band) {
            ReadinessBand.PRIMA -> Suggestion(band.headline, "Schedule negotiations, interviews or big decisions for ${range(peak)}. Today is a day for hard problems.", Tone.POSITIVE)
            ReadinessBand.BAIK -> Suggestion(band.headline, "Put the most important thing at ${range(peak)} or ${range(second)}. Do a short warm-up beforehand.", Tone.POSITIVE)
            ReadinessBand.CUKUP -> Suggestion(band.headline, "Important work is still fine at ${range(peak)}. Avoid ${range(dip)}.", Tone.CAUTION)
            ReadinessBand.RENDAH -> Suggestion(band.headline, "If you can, move important decisions to another day. If it has to be today, do it at ${range(peak)}.", Tone.CAUTION)
            ReadinessBand.TERKURAS -> Suggestion(band.headline, "Reschedule interviews or important deals if possible. Stick to routine tasks and go to bed early tonight.", Tone.CAUTION)
        }

        val score = components.associate { it.kind to it.score }
        if (sleepHours != null && sleepHours < 6.5) {
            val bedtime = dayKeys.clock(wake + 24 * HOUR_MS - 8 * HOUR_MS - 30 * MIN_MS)
            out += Suggestion(
                "Short on sleep (${formatDuration((sleepHours * 60).roundToInt())})",
                "Take a 10–20 minute nap before 15:00 and skip caffeine after 14:00. Tonight, be in bed by $bedtime.",
                Tone.ACTION,
            )
        }
        if ((score[ComponentKind.RECOVERY] ?: 100) < 70) {
            out += Suggestion(
                "Your body is under strain",
                "Your resting heart rate is higher than usual. Possible causes: stress, poor recovery, alcohol, or getting sick. Drink more water, skip hard training, and try 5 minutes of box breathing (4-4-4-4).",
                Tone.ACTION,
            )
        }
        input.checkIn?.takeIf { it.stress >= 4 }?.let {
            out += Suggestion(
                "High stress",
                "Try 5 minutes of box breathing (4-4-4-4) and a short walk. Slow breathing lowers your heart rate and calms the body.",
                Tone.ACTION,
            )
        }
        val activity = score[ComponentKind.ACTIVITY]
        val ySteps = input.yesterday?.steps ?: 0
        val bSteps = input.baseline.steps
        if (activity != null && activity < 85) {
            out += if (bSteps != null && ySteps > bSteps * 1.6) {
                Suggestion("Very active yesterday", "Avoid intense training today so recovery isn't disrupted.", Tone.CAUTION)
            } else {
                Suggestion("Move a little", "A brisk 10-minute walk before something important lifts alertness and mood.", Tone.ACTION)
            }
        }
        if (input.checkIn == null) {
            out += Suggestion("Do your morning check-in", "Takes 30 seconds. How energetic and stressed you feel makes the score more accurate.", Tone.ACTION)
        }
        return out
    }
}
