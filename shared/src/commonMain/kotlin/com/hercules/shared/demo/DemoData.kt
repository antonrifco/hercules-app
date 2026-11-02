package com.hercules.shared.demo

import com.hercules.shared.cognitive.CheckIn
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.EventType
import com.hercules.shared.cognitive.Importance
import com.hercules.shared.cognitive.PlanEvent
import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.insights.InsightsService
import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import kotlin.random.Random

/**
 * Realistic synthetic data for demos and tests: 14 days of sleep, heart rate and steps.
 * The last night is deliberately short with an elevated heart rate, so the app shows
 * meaningful suggestions. Uses deviceId "demo" so it's easy to recognise.
 */
object DemoData {
    const val DEVICE_ID = "demo"
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    fun samples(nowMs: Long, dayKeys: DayKeys, days: Int = 14, seed: Int = 7): List<Sample> {
        val rnd = Random(seed)
        val out = mutableListOf<Sample>()
        val today = dayKeys.of(nowMs)
        fun add(type: MetricType, v: Double, start: Long, end: Long = start) {
            if (start <= nowMs) out += Sample.create(type, v, start, minOf(end, nowMs), SampleSource.PASSIVE, DEVICE_ID)
        }
        for (offset in -days..0) {
            val key = dayKeys.plusDays(today, offset)
            val midnight = dayKeys.startOf(key)
            val lastNight = offset == 0
            // Sleep that ends on this morning.
            val bed = if (lastNight) midnight + 70 * MIN else midnight - 60 * MIN + rnd.nextLong(-50, 80) * MIN
            val wake = if (lastNight) midnight + 6 * HOUR + 20 * MIN else midnight + 6 * HOUR + 45 * MIN + rnd.nextLong(-35, 26) * MIN
            add(MetricType.ACTIVITY_STATE, ActivityState.ASLEEP, bed)
            add(MetricType.ACTIVITY_STATE, ActivityState.AWAKE, wake)
            val sleepHr = if (lastNight) 61.0 else 54.0 + rnd.nextDouble(-1.5, 4.0)
            var t = bed
            while (t < wake) {
                add(MetricType.HEART_RATE, sleepHr + rnd.nextDouble(-3.0, 3.0), t)
                t += 10 * MIN
            }
            // Daytime.
            val dayEnd = midnight + 22 * HOUR
            t = wake + 10 * MIN
            while (t < dayEnd) {
                val walking = t in (midnight + 18 * HOUR)..(midnight + 18 * HOUR + 40 * MIN)
                val restOffset = if (lastNight) 5.0 else 0.0
                add(MetricType.HEART_RATE, (if (walking) 104.0 else 70.0 + restOffset) + rnd.nextDouble(-6.0, 8.0), t)
                t += 10 * MIN
            }
            val lowActivityDay = offset == -1
            for (h in 7..21) {
                val start = midnight + h * HOUR
                val steps = if (lowActivityDay) rnd.nextInt(80, 260) else rnd.nextInt(350, 900) + if (h == 18) 3500 else 0
                add(MetricType.STEPS, steps.toDouble(), start, start + HOUR - 1)
                add(MetricType.CALORIES, 60.0 + steps * 0.04, start, start + HOUR - 1)
                add(MetricType.DISTANCE, steps * 0.75, start, start + HOUR - 1)
                if (h % 4 == 0) add(MetricType.FLOORS, rnd.nextInt(0, 4).toDouble(), start, start + HOUR - 1)
            }
        }
        return out
    }

    /** Fills the database and rebuilds summaries, readiness history, check-ins and two sample events. */
    suspend fun load(
        repo: HerculesRepository,
        insights: InsightsService,
        cognitive: CognitiveService,
        nowMs: Long,
        days: Int = 14,
    ) {
        val dk = cognitive.dayKeys
        val samples = samples(nowMs, dk, days)
        repo.insertSamples(samples)
        insights.refresh(insights.daysOf(samples))
        val today = dk.of(nowMs)
        val rnd = Random(11)
        for (offset in -days..0) {
            val key = dk.plusDays(today, offset)
            val at = if (offset == 0) nowMs else dk.startOf(key) + 12 * HOUR
            if (offset in -6..-1) {
                cognitive.store.upsertCheckIn(CheckIn(key, null, rnd.nextInt(3, 6), rnd.nextInt(1, 4), at))
            }
            cognitive.refresh(at)
        }
        val todayStart = dk.startOf(today)
        cognitive.store.upsertEvent(
            PlanEvent("demo-interview", "Job Interview with Google", EventType.INTERVIEW,
                dk.startOf(dk.plusDays(today, 6)) + 10 * HOUR, Importance.CRITICAL, "", nowMs - 2 * 24 * HOUR),
        )
        // A YC pitch later today (or tomorrow morning if it's already late), to show the go/reschedule advice.
        val pitchStart = (nowMs + 2 * HOUR).let { if (dk.of(it) == today) it - it % (30 * MIN) else todayStart + 24 * HOUR + 9 * HOUR }
        cognitive.store.upsertEvent(
            PlanEvent("demo-pitch", "Pitching to Y Combinator", EventType.PRESENTATION,
                pitchStart, Importance.HIGH, "", nowMs - 4 * 24 * HOUR),
        )
    }
}
