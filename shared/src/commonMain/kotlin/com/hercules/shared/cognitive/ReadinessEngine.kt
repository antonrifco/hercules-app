package com.hercules.shared.cognitive

import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.MetricKey
import com.hercules.shared.readiness.NightInput
import com.hercules.shared.readiness.ReadinessCalculation

/**
 * Derives the main sleep from the Hercules watch's activity-state transitions and the heart rate during it.
 * The "night of dayKey" is sleep inside [dayKey-1 18:00, dayKey 14:00]. Gaps between asleep intervals are time awake.
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
        fun metric(k: MetricKey) = calc.dimensions.flatMap { it.metrics }.firstOrNull { it.key == k }
        fun dim(d: Dimension) = calc.dimensions.first { it.dimension == d }

        val duration = metric(MetricKey.SLEEP_DURATION)?.score
        if (lastNight != null && duration != null && duration < 70) {
            out += Suggestion(
                "Short on sleep (${formatDuration(lastNight.asleepMinutes)})",
                "A 10–20 minute nap before 15:00 can help; skip caffeine after 14:00." + (bedtime?.let { " Tonight, aim to be in bed by $it." } ?: ""),
                Tone.ACTION,
            )
        }
        metric(MetricKey.SLEEP_CONSISTENCY)?.score?.takeIf { it < 60 }?.let {
            out += Suggestion("Irregular sleep times", "Going to bed and getting up at similar times steadies your rhythm.", Tone.ACTION)
        }
        dim(Dimension.CARDIO).score?.takeIf { it < 55 }?.let {
            out += Suggestion(
                "Recovery is below your usual",
                "Your heart signals suggest strain. Possible causes: stress, poor sleep, alcohol, illness. Keep effort moderate today.",
                Tone.ACTION,
            )
        }
        dim(Dimension.ACTIVITY).score?.takeIf { it < 60 }?.let {
            out += Suggestion("Activity was off your usual", "A brisk 10-minute walk before demanding work lifts alertness.", Tone.ACTION)
        }
        dim(Dimension.CIRCADIAN).score?.takeIf { it < 60 }?.let {
            out += Suggestion("Brighter mornings, dimmer evenings", "Daylight soon after waking and dim light before bed support your body clock.", Tone.ACTION)
        }
        return out
    }
}
