package com.hercules.shared.demo

import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.insights.InsightsService
import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.CollectionMethod
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.ExerciseRecord
import com.hercules.shared.readiness.HealthMetric
import com.hercules.shared.readiness.HealthMetricType
import com.hercules.shared.readiness.LightContext
import com.hercules.shared.readiness.LightSample
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.RoundValidity
import com.hercules.shared.readiness.SleepSessionRecord
import com.hercules.shared.readiness.Urgency
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Realistic synthetic data for demos and tests: three weeks of Health Connect-style sleep, HRV, resting heart rate,
 * steps and exercise, Hercules watch heart rate, phone light readings, Beat the Clock sessions with a clear morning
 * peak, and two Moments. The last night is deliberately short. Device and package "demo" make it easy to recognise.
 */
object DemoData {
    const val DEVICE_ID = "demo"
    const val PACKAGE = "demo"
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    /** Watch samples: activity states for sleep, heart rate all day, hourly steps. */
    fun samples(nowMs: Long, dayKeys: DayKeys, days: Int = 21, seed: Int = 7): List<Sample> {
        val rnd = Random(seed)
        val out = mutableListOf<Sample>()
        val today = dayKeys.of(nowMs)
        fun add(type: MetricType, v: Double, start: Long, end: Long = start) {
            if (start <= nowMs) out += Sample.create(type, v, start, minOf(end, nowMs), SampleSource.PASSIVE, DEVICE_ID)
        }
        for (offset in -days..0) {
            val key = dayKeys.plusDays(today, offset)
            val midnight = dayKeys.startOf(key)
            val (bed, wake) = night(dayKeys, today, offset, Random(seed * 31 + offset))
            add(MetricType.ACTIVITY_STATE, ActivityState.ASLEEP, bed)
            add(MetricType.ACTIVITY_STATE, ActivityState.AWAKE, wake)
            val sleepHr = if (offset == 0) 61.0 else 54.0 + rnd.nextDouble(-1.5, 4.0)
            var t = bed
            while (t < wake) {
                add(MetricType.HEART_RATE, sleepHr + rnd.nextDouble(-3.0, 3.0), t)
                t += 10 * MIN
            }
            val dayEnd = midnight + 22 * HOUR
            t = wake + 10 * MIN
            while (t < dayEnd) {
                val walking = t in (midnight + 18 * HOUR)..(midnight + 18 * HOUR + 40 * MIN)
                add(MetricType.HEART_RATE, (if (walking) 104.0 else 70.0) + rnd.nextDouble(-6.0, 8.0), t)
                t += 10 * MIN
            }
            val lowActivityDay = offset == -1
            for (h in 7..21) {
                val start = midnight + h * HOUR
                val steps = if (lowActivityDay) rnd.nextInt(80, 260) else rnd.nextInt(350, 900) + if (h == 18) 3500 else 0
                add(MetricType.STEPS, steps.toDouble(), start, start + HOUR - 1)
                add(MetricType.CALORIES, 60.0 + steps * 0.04, start, start + HOUR - 1)
                add(MetricType.DISTANCE, steps * 0.75, start, start + HOUR - 1)
            }
        }
        return out
    }

    /** Bed and wake time of the night ending on the morning of `today + offset`. The last one is short. */
    private fun night(dayKeys: DayKeys, today: String, offset: Int, rnd: Random): Pair<Long, Long> {
        val midnight = dayKeys.startOf(dayKeys.plusDays(today, offset))
        return if (offset == 0) (midnight + 70 * MIN) to (midnight + 6 * HOUR + 20 * MIN)
        else (midnight - 60 * MIN + rnd.nextLong(-40, 50) * MIN) to (midnight + 6 * HOUR + 45 * MIN + rnd.nextLong(-30, 25) * MIN)
    }

    private fun metric(type: HealthMetricType, v: Double, at: Long, dayKey: String) = HealthMetric(
        "demo:${type.name}:$dayKey", type, v, at, at, at, dayKey, PACKAGE, DEVICE_ID, DataQuality.VALID, CollectionMethod.HEALTH_CONNECT,
    )

    /** Synthetic rounds: [skill] is the typical relative error (lower is sharper). */
    private fun rounds(sessionId: String, skill: Double, rnd: Random): List<CognitiveRound> =
        BeatTheClock.plan(rnd).mapIndexed { i, target ->
            val err = (rnd.nextDouble(-1.0, 1.0) * skill * 1.6 - skill * 0.2)
            val actual = (target * (1 + err)).roundToLong()
            CognitiveRound("$sessionId:$i", sessionId, i, target, actual, RoundValidity.VALID)
        }

    /** Fills the database and rebuilds three weeks of history, then adds two Moments. */
    suspend fun load(
        repo: HerculesRepository,
        insights: InsightsService,
        cognitive: CognitiveService,
        nowMs: Long,
        days: Int = 21,
    ) {
        val dk = cognitive.dayKeys
        val store = cognitive.store
        val samples = samples(nowMs, dk, days)
        repo.insertSamples(samples)
        insights.refresh(insights.daysOf(samples))
        val today = dk.of(nowMs)
        val rnd = Random(11)

        val metrics = mutableListOf<HealthMetric>()
        val sleeps = mutableListOf<SleepSessionRecord>()
        val exercise = mutableListOf<ExerciseRecord>()
        val light = mutableListOf<LightSample>()
        for (offset in -days..0) {
            val key = dk.plusDays(today, offset)
            val midnight = dk.startOf(key)
            val (bed, wake) = night(dk, today, offset, Random(7 * 31 + offset))
            val awake = 12 + rnd.nextInt(0, 18)
            val inBed = ((wake - bed) / MIN).toInt()
            sleeps += SleepSessionRecord(
                "demo:sleep:$key", key, bed, wake, inBed - awake, awake, (inBed * 0.18).toInt(), (inBed * 0.22).toInt(),
                inBed - awake - (inBed * 0.40).toInt(), PACKAGE, DEVICE_ID, DataQuality.VALID,
            )
            val short = offset == 0
            metrics += metric(HealthMetricType.HRV_RMSSD, if (short) 34.0 else 46.0 + rnd.nextDouble(-6.0, 6.0), wake - 30 * MIN, key)
            metrics += metric(HealthMetricType.RESTING_HR, if (short) 61.0 else 55.0 + rnd.nextDouble(-2.0, 2.5), wake - 30 * MIN, key)
            metrics += metric(HealthMetricType.RESPIRATORY_RATE, 14.0 + rnd.nextDouble(-1.0, 1.0), wake - 30 * MIN, key)
            if (offset < 0) {
                val steps = if (offset == -1) 3100.0 else 7500.0 + rnd.nextDouble(-1500.0, 2500.0)
                metrics += metric(HealthMetricType.STEPS_DAILY, steps.toInt().toDouble(), midnight + 23 * HOUR, key)
                if (offset % 2 == 0) exercise += ExerciseRecord("demo:ex:$key", midnight + 18 * HOUR, midnight + 18 * HOUR + 35 * MIN, 79, PACKAGE, DEVICE_ID)
                // Phone light: bright mornings, moderate daytime, dim evenings (except yesterday evening).
                fun lux(at: Long, v: Double) { light += LightSample("demo:lux:$at", v, at, "demo-light", LightContext.FOREGROUND) }
                for (m in listOf(40, 95, 150)) lux(wake + m * MIN, 1500.0 + rnd.nextDouble(0.0, 4000.0))
                for (h in listOf(5, 7, 9, 11)) lux(wake + h * HOUR, 250.0 + rnd.nextDouble(0.0, 400.0))
                for (m in listOf(150, 90, 30)) lux(midnight + 24 * HOUR - 60 * MIN - m * MIN, if (offset == -1) 260.0 else 15.0 + rnd.nextDouble(0.0, 40.0))
            }
        }
        store.upsertMetrics(metrics)
        store.upsertSleepSessions(sleeps)
        store.upsertExercise(exercise)
        store.insertLight(light.filter { it.measuredMs <= nowMs })

        // Beat the Clock on the phone: most windows of most days. Mornings are sharpest.
        val history = mutableListOf<CognitiveSession>()
        fun play(id: String, window: AssessmentWindow?, dayKey: String, at: Long, skill: Double) {
            history += BeatTheClock.finish(id, DeviceKind.PHONE, DEVICE_ID, window, dayKey, at, at + 70_000, rounds(id, skill, rnd), history)
        }
        for (offset in -days..0) {
            val key = dk.plusDays(today, offset)
            val (_, wake) = night(dk, today, offset, Random(7 * 31 + offset))
            for (w in AssessmentWindow.entries) {
                val at = w.startMs(wake) + 20 * MIN
                if (at > nowMs || (offset < 0 && rnd.nextInt(10) < 2)) continue
                val skill = when (w) {
                    AssessmentWindow.MORNING -> 0.06
                    AssessmentWindow.AFTERNOON -> 0.10
                    AssessmentWindow.EVENING -> 0.12
                } * if (offset == 0) 1.3 else 1.0
                play("demo:btc:$key:${w.name}", w, key, at, skill)
            }
        }
        for (s in history) store.saveSession(s, synced = true)

        for (offset in -days..0) {
            val key = dk.plusDays(today, offset)
            cognitive.refresh(if (offset == 0) nowMs else dk.startOf(key) + 21 * HOUR)
        }

        val interview = dk.startOf(dk.plusDays(today, 6)) + 10 * HOUR
        cognitive.saveMoment("demo-interview", "Job Interview with Google", interview, Urgency.CRITICAL)
        // A pitch later today (or tomorrow morning when it's already late) to show the event-day view.
        val soon = (nowMs + 2 * HOUR).let { it - it % (30 * MIN) }
        val pitch = if (dk.of(soon) == today) soon else dk.startOf(dk.plusDays(today, 1)) + 9 * HOUR
        cognitive.saveMoment("demo-pitch", "Pitching to Y Combinator", pitch, Urgency.NORMAL)
    }

    /** Takes the demo back out: deletes what [load] wrote and rebuilds history from your own data only. */
    suspend fun remove(insights: InsightsService, cognitive: CognitiveService, nowMs: Long, days: Int = 21) {
        val dk = cognitive.dayKeys
        val today = dk.of(nowMs)
        cognitive.store.removeDemo()
        insights.refresh((-days..0).mapTo(mutableSetOf()) { dk.plusDays(today, it) })
        for (offset in -days..0) {
            val key = dk.plusDays(today, offset)
            cognitive.refresh(if (offset == 0) nowMs else dk.startOf(key) + 21 * HOUR)
        }
    }
}
