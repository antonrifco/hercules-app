package com.hercules.shared.cognitive

import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.NightInput
import com.hercules.shared.readiness.ReadinessCalculation

/**
 * Derives the main sleep from the Hercules watch's activity-state transitions and the heart rate during it.
 * The "night of dayKey" is the longest sleep episode inside [dayKey-1 18:00, dayKey 14:00]. Awakenings of up to
 * [MAX_AWAKE_GAP_MS] stay inside one episode and count as time awake; a longer gap starts a new episode, so an
 * evening nap is not glued onto the night. A night still in progress is not reported until the watch sees you awake.
 */
/** See [SleepAnalyzer.earliestWake]. */
const val EARLIEST_WAKE_H = 3L

class SleepAnalyzer(private val dayKeys: DayKeys) {

    fun window(dayKey: String): Pair<Long, Long> {
        val midnight = dayKeys.startOf(dayKey)
        return (midnight - 6 * HOUR_MS) to (midnight + 14 * HOUR_MS)
    }

    /**
     * A night must end at or after this time on the morning of [dayKey]. Sleep that ends earlier (an evening nap, the
     * phone down while watching TV) is the day before's nap, not last night: otherwise just after midnight it would
     * pass for a finished night and publish a score with a wake-up in the evening.
     */
    fun earliestWake(dayKey: String): Long = dayKeys.startOf(dayKey) + EARLIEST_WAKE_H * HOUR_MS

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
        if (intervals.isEmpty()) return null
        // Still asleep (no later state) and the window is still open: wait for the wake-up rather than score half a night.
        val open = states.lastOrNull()?.value == ActivityState.ASLEEP
        if (open && nowMs < to) return null
        if (open) {
            // The watch never reported waking (taken off, flat battery): the night ends at its last pulse reading.
            val lastHr = samples.filter { it.type == MetricType.HEART_RATE && it.startMs in intervals.last().first..to }.maxOfOrNull { it.startMs }
            val (a, b) = intervals.removeAt(intervals.lastIndex)
            val capped = minOf(b, lastHr ?: a)
            if (capped > a) intervals += a to capped
            if (intervals.isEmpty()) return null
        }
        // Group into episodes, then keep the one with the most sleep.
        val episodes = mutableListOf<MutableList<Pair<Long, Long>>>()
        for (iv in intervals) {
            if (episodes.isEmpty() || iv.first - episodes.last().last().second > MAX_AWAKE_GAP_MS) episodes += mutableListOf(iv)
            else episodes.last() += iv
        }
        val main = episodes.filter { it.last().second >= earliestWake(dayKey) }.maxByOrNull { e -> e.sumOf { it.second - it.first } } ?: return null
        intervals.clear()
        intervals += main
        val asleepMs = intervals.sumOf { it.second - it.first }
        if (asleepMs < 60 * MIN_MS) return null

        val hr = samples
            .filter { s -> s.type == MetricType.HEART_RATE && intervals.any { s.startMs in it.first until it.second } }
            .map { it.value }
            .sorted()
        val start = intervals.first().first
        val stop = intervals.last().second
        val asleepMin = (asleepMs / MIN_MS).toInt()
        return SleepNight(
            dayKey = dayKey,
            startMs = start,
            endMs = stop,
            asleepMinutes = asleepMin,
            sleepingHrAvg = if (hr.isEmpty()) null else hr.average(),
            sleepingHrLow = if (hr.isEmpty()) null else hr[(hr.size * 0.1).toInt().coerceAtMost(hr.lastIndex)],
            awakeMinutes = ((stop - start) / MIN_MS).toInt() - asleepMin,
            provider = "Hercules watch",
        )
    }

    /**
     * Every finished sleep episode the watch saw in [[fromMs], [toMs]) (same grouping as the night), for naps.
     * An episode still open at the end is left out.
     */
    fun episodes(states: List<Sample>, fromMs: Long, toMs: Long): List<Pair<Long, Long>> {
        val sorted = states.filter { it.type == MetricType.ACTIVITY_STATE }.sortedBy { it.startMs }
        val out = mutableListOf<Pair<Long, Long>>()
        for ((i, s) in sorted.withIndex()) {
            if (s.value != ActivityState.ASLEEP) continue
            val next = sorted.getOrNull(i + 1)?.startMs ?: continue
            val a = maxOf(s.startMs, fromMs)
            val b = minOf(next, toMs)
            if (b <= a) continue
            if (out.isNotEmpty() && a - out.last().second <= MAX_AWAKE_GAP_MS) out[out.lastIndex] = out.last().first to b else out += a to b
        }
        return out
    }

    companion object {
        /** Longer than this awake and the sleep before it is a separate episode (a nap, or a split night). */
        const val MAX_AWAKE_GAP_MS = 60 * MIN_MS
    }
}

/** Converts a resolved night to the scorer's input (midpoint as local minutes since midnight). */
fun SleepNight.toInput(dayKeys: DayKeys): NightInput {
    val mid = startMs + (endMs - startMs) / 2
    return NightInput(dayKey, startMs, endMs, asleepMinutes, awakeMinutes, dayKeys.minuteOfDay(mid).toDouble())
}

/** Plain-language tips from a calculation. General guidance, labelled as such in the UI. */
object Tips {
    fun build(calc: ReadinessCalculation, band: ReadinessBand?, lastNight: SleepNight?, bedtime: String?): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        band?.let {
            out += Suggestion(it.headline, it.meaning, if (it == ReadinessBand.PRIMA || it == ReadinessBand.BAIK) Tone.POSITIVE else Tone.CAUTION)
        }
        val dim = { d: Dimension -> calc.dimensions.firstOrNull { it.dimension == d } }
        if (lastNight != null && lastNight.asleepMinutes < 6 * 60 + 30) {
            out += Suggestion(
                "Short on sleep (${formatDuration(lastNight.asleepMinutes)})",
                "A 10–20 minute nap before 15:00 can help; skip caffeine after 14:00." + (bedtime?.let { " Tonight, aim to be in bed by $it." } ?: ""),
                Tone.ACTION,
            )
        }
        dim(Dimension.CARDIO)?.score?.takeIf { it < 55 }?.let {
            out += Suggestion(
                "Recovery is below your usual",
                "Your body signals are off your usual. Possible causes: stress, poor sleep, alcohol, illness. Keep effort moderate today.",
                Tone.ACTION,
            )
        }
        return out
    }
}
