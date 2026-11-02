package com.hercules.shared

import com.hercules.shared.cognitive.Baseline
import com.hercules.shared.cognitive.CheckIn
import com.hercules.shared.cognitive.ComponentKind
import com.hercules.shared.cognitive.Confidence
import com.hercules.shared.cognitive.EventType
import com.hercules.shared.cognitive.Importance
import com.hercules.shared.cognitive.PlanEvent
import com.hercules.shared.cognitive.PrepCoach
import com.hercules.shared.cognitive.PrepPhase
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.ReadinessEngine
import com.hercules.shared.cognitive.ReadinessInput
import com.hercules.shared.cognitive.SleepAnalyzer
import com.hercules.shared.cognitive.SleepNight
import com.hercules.shared.cognitive.Verdict
import com.hercules.shared.cognitive.WindowKind
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.DailySummary
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val dk = DayKeys(TimeZone.UTC)
private const val M = 60_000L
private const val H = 60 * M
private val D = dk.startOf("2026-11-10")

private fun state(v: Double, t: Long) = Sample.create(MetricType.ACTIVITY_STATE, v, t, t, SampleSource.PASSIVE, "w")
private fun hr(v: Double, t: Long) = Sample.create(MetricType.HEART_RATE, v, t, t, SampleSource.PASSIVE, "w")

private fun night(key: String, hours: Double, hr: Double? = 55.0, wakeH: Double = 7.0): SleepNight {
    val wake = dk.startOf(key) + (wakeH * H).toLong()
    return SleepNight(key, wake - (hours * H).toLong(), wake, (hours * 60).toInt(), hr, hr)
}

private val goodBaseline = Baseline(days = 10, sleepMinutes = 450.0, sleepingHr = 55.0, restingHr = 58.0, steps = 8000.0, wakeMinuteOfDay = 420)

class SleepAnalyzerTest {
    private val analyzer = SleepAnalyzer(dk)

    @Test fun detectsMainSleepAndSleepingHr() {
        val samples = listOf(
            state(ActivityState.AWAKE, D - 10 * H),
            state(ActivityState.ASLEEP, D - 60 * M),           // 23:00
            hr(56.0, D), hr(52.0, D + 2 * H), hr(54.0, D + 4 * H),
            state(ActivityState.AWAKE, D + 3 * H),              // woke 03:00 briefly
            state(ActivityState.ASLEEP, D + 3 * H + 20 * M),
            state(ActivityState.AWAKE, D + 7 * H),              // 07:00
            hr(80.0, D + 8 * H),                                // daytime, not sleep
        )
        val s = assertNotNull(analyzer.analyze("2026-11-10", samples, D + 12 * H))
        assertEquals(D - 60 * M, s.startMs)
        assertEquals(D + 7 * H, s.endMs)
        assertEquals(8 * 60 - 20, s.asleepMinutes)
        assertEquals(54.0, s.sleepingHrAvg!!, 0.001)
    }

    @Test fun stillAsleepIsClippedToNow() {
        val s = analyzer.analyze("2026-11-10", listOf(state(ActivityState.ASLEEP, D)), D + 5 * H)
        assertEquals(300, s!!.asleepMinutes)
    }

    @Test fun napsUnderAnHourAreIgnored() {
        assertNull(analyzer.analyze("2026-11-10", listOf(state(ActivityState.ASLEEP, D + 13 * H), state(ActivityState.AWAKE, D + 13 * H + 30 * M)), D + 14 * H))
    }
}

class ReadinessEngineTest {
    private val engine = ReadinessEngine(dk)

    private fun input(sleep: SleepNight?, checkIn: CheckIn? = null, ySteps: Long = 8000, baseline: Baseline = goodBaseline) =
        ReadinessInput(
            dayKey = "2026-11-10",
            nowMs = D + 9 * H,
            sleep = sleep,
            checkIn = checkIn,
            todayRestingHr = null,
            yesterday = DailySummary("2026-11-09", ySteps, 0.0, 0.0, 0, restingHr = 58),
            baseline = baseline,
        )

    @Test fun wellRestedIsPrimaWithHighConfidence() {
        val r = engine.compute(input(night("2026-11-10", 8.0, 54.0)))!!
        assertTrue(r.score >= 85, "score=${r.score}")
        assertEquals(ReadinessBand.PRIMA, r.band)
        assertEquals(Confidence.HIGH, r.confidence)
    }

    @Test fun shortSleepAndElevatedHrIsLowAndExplained() {
        val r = engine.compute(input(night("2026-11-10", 4.5, 63.0), CheckIn("2026-11-10", null, 2, 4, 0)))!!
        assertTrue(r.score < 55, "score=${r.score}")
        assertTrue(r.band == ReadinessBand.RENDAH || r.band == ReadinessBand.TERKURAS)
        val titles = r.suggestions.map { it.title }
        assertTrue(titles.any { it.startsWith("Short on sleep") })
        assertTrue("Your body is under strain" in titles)
        assertTrue("High stress" in titles)
    }

    @Test fun checkInAloneStillProducesAScoreButNeverInventsSleep() {
        // Even a legacy check-in carrying sleep hours must not count as sleep: sleep only comes from the watch.
        val r = engine.compute(input(null, CheckIn("2026-11-10", 7.0, 4, 2, 0), baseline = Baseline.EMPTY))!!
        assertEquals(setOf(ComponentKind.SELF_REPORT, ComponentKind.ACTIVITY), r.components.map { it.kind }.toSet())
        assertEquals(Confidence.LOW, r.confidence)
    }

    @Test fun noSleepNoCheckInNoBaselineMeansNoScore() {
        assertNull(engine.compute(input(null, baseline = Baseline.EMPTY)))
    }

    @Test fun timeOfDayAdjustsEventScore() {
        val r = engine.compute(input(night("2026-11-10", 8.0, 54.0)))!!
        val wake = D + 7 * H
        assertEquals(WindowKind.PEAK, engine.windowAt(r, wake + 3 * H))
        assertEquals(WindowKind.DIP, engine.windowAt(r, wake + 7 * H))
        assertTrue(engine.scoreAt(r, wake + 3 * H) > engine.scoreAt(r, wake + 7 * H))
    }

    @Test fun curvesAreMonotonicWhereExpected() {
        assertTrue(engine.sleepScore(4.0) < engine.sleepScore(6.0))
        assertTrue(engine.sleepScore(6.0) < engine.sleepScore(8.0))
        assertTrue(engine.recoveryScore(0.0) > engine.recoveryScore(8.0))
        assertTrue(engine.selfScore(5, 1) > engine.selfScore(1, 5))
    }
}

class PrepCoachTest {
    private val engine = ReadinessEngine(dk)
    private val coach = PrepCoach(dk, engine)
    private val event = PlanEvent("e1", "Interview", EventType.INTERVIEW, D + 10 * H, Importance.CRITICAL, "", D - 5 * 24 * H)

    @Test fun phasesCountDownToTheDay() {
        assertEquals(PrepPhase.FOUNDATION, coach.phase(5))
        assertEquals(PrepPhase.SHARPEN, coach.phase(2))
        assertEquals(PrepPhase.EVE, coach.phase(1))
        assertEquals(PrepPhase.DAY, coach.phase(0))
        assertEquals(PrepPhase.DONE, coach.phase(-1))
    }

    @Test fun tasksAreTypeSpecificAndTimed() {
        assertTrue(coach.tasks(event, PrepPhase.FOUNDATION, 420).any { it.id == "star" })
        val eve = coach.tasks(event, PrepPhase.EVE, 420)
        assertTrue(eve.first { it.id == "bedtime" }.title.endsWith("23:15")) // 10:00 − 2.5h − 8h15m
        assertTrue(coach.tasks(event, PrepPhase.DAY, 420).first { it.id == "caffeine" }.title.contains("09:15"))
    }

    @Test fun adviceGoesFromGoToReschedule() {
        fun adviceFor(hours: Double, hr: Double): Verdict {
            val r = engine.compute(
                ReadinessInput("2026-11-10", D + 8 * H, night("2026-11-10", hours, hr), null, null, null, goodBaseline),
            )!!
            return coach.advice(event, r).verdict
        }
        assertEquals(Verdict.GO, adviceFor(8.0, 54.0))
        assertEquals(Verdict.RESCHEDULE, adviceFor(3.5, 68.0))
    }

    @Test fun prepScoreReweightsMissingParts() {
        assertNull(coach.prepScore(emptyList(), null, null))
        assertEquals(80, coach.prepScore(listOf(80), null, null))
        assertEquals(76, coach.prepScore(listOf(80, 80), 80, 0.6))
    }
}

class SensorConfigTest {
    @Test fun toggleAndNewestWins() {
        val c = com.hercules.shared.sensors.SensorConfig.DEFAULT.with(com.hercules.shared.sensors.SensorKind.FLOORS, false, 10)
        assertTrue(com.hercules.shared.sensors.SensorKind.FLOORS !in c.enabled)
        val round = com.hercules.shared.sensors.SensorCodec.decodeConfig(com.hercules.shared.sensors.SensorCodec.encode(c))
        assertEquals(c, round)
        val older = c.copy(enabled = emptySet(), updatedMs = 5)
        assertEquals(c, com.hercules.shared.sensors.SensorCodec.newest(c, older))
        val newer = c.copy(enabled = emptySet(), updatedMs = 20)
        assertEquals(newer, com.hercules.shared.sensors.SensorCodec.newest(c, newer))
    }
}

class EventTypeFromTitleTest {
    @Test fun infersKindFromTitle() {
        assertEquals(EventType.INTERVIEW, EventType.fromTitle("Job Interview with Google"))
        assertEquals(EventType.NEGOTIATION, EventType.fromTitle("Vendor contract negotiation"))
        assertEquals(EventType.PRESENTATION, EventType.fromTitle("Pitch to Series A investors"))
        assertEquals(EventType.EXAM, EventType.fromTitle("AWS certification exam"))
        assertEquals(EventType.OTHER, EventType.fromTitle("Marathon"))
    }
}
