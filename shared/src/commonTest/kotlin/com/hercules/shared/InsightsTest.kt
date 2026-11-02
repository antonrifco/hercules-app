package com.hercules.shared

import com.hercules.shared.insights.DailyAggregator
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.insights.Hrv
import com.hercules.shared.insights.PersonalRecordEngine
import com.hercules.shared.insights.SessionSummarizer
import com.hercules.shared.model.DailySummary
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.PersonalRecord
import com.hercules.shared.model.RecordKind
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.model.SessionSummary
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val utc = DayKeys(TimeZone.UTC)
private const val MIN = 60_000L
private val DAY_START = utc.startOf("2026-10-02")
private fun at(h: Int, m: Int) = DAY_START + h * 60 * MIN + m * MIN

private fun s(
    type: MetricType,
    v: Double,
    t: Long,
    src: SampleSource = SampleSource.PASSIVE,
    session: String? = null,
) = Sample.create(type, v, t, t, src, "dev", session)

class DayKeysTest {
    @Test fun formatsAndParses() {
        assertEquals("2026-10-02", utc.of(at(23, 59)))
        assertEquals(at(0, 0) + 24 * 60 * MIN, utc.nextDayStart("2026-10-02"))
        assertTrue(DayKeys.isNextDay("2026-02-28", "2026-03-01"))
        assertTrue(!DayKeys.isNextDay("2026-02-28", "2026-03-02"))
    }
}

class SampleTest {
    @Test fun deterministicIds() {
        val a = s(MetricType.HEART_RATE, 70.0, 1000)
        assertEquals(a.id, s(MetricType.HEART_RATE, 99.0, 1000).id) // value is not part of identity
        assertNotEquals(a.id, s(MetricType.HEART_RATE, 70.0, 1000, SampleSource.EXERCISE).id)
        assertNotEquals(a.id, s(MetricType.HEART_RATE, 70.0, 1001).id)
    }

    @Test fun rejectsEndBeforeStart() {
        assertFailsWith<IllegalArgumentException> {
            Sample.create(MetricType.STEPS, 1.0, 2000, 1000, SampleSource.PASSIVE, "dev")
        }
    }

    @Test fun unknownWireNamesThrow() {
        assertFailsWith<IllegalArgumentException> { MetricType.fromWire("mood") }
        assertEquals(MetricType.entries.size, MetricType.entries.map { it.wire }.toSet().size)
    }
}

class DailyAggregatorTest {
    private val agg = DailyAggregator(utc)

    @Test fun sumsPassiveTotalsIgnoresExercise() {
        val r = agg.aggregate(
            "2026-10-02",
            listOf(
                s(MetricType.STEPS, 1000.0, at(8, 0)),
                s(MetricType.STEPS, 500.0, at(9, 0)),
                s(MetricType.STEPS, 4000.0, at(7, 0), SampleSource.EXERCISE),
                s(MetricType.CALORIES, 120.5, at(8, 0)),
                s(MetricType.DISTANCE, 800.0, at(8, 0)),
                s(MetricType.FLOORS, 3.0, at(8, 0)),
                s(MetricType.STEPS, 999.0, at(0, 0) - 1), // previous day
            ),
        )
        assertEquals(1500, r.steps)
        assertEquals(120.5, r.calories)
        assertEquals(800.0, r.distanceMeters)
        assertEquals(3, r.floors)
    }

    @Test fun restingHrIsLowestWindowMean() {
        val r = agg.aggregate(
            "2026-10-02",
            listOf(
                s(MetricType.HEART_RATE, 80.0, at(8, 0)),
                s(MetricType.HEART_RATE, 80.0, at(8, 3)),
                s(MetricType.HEART_RATE, 80.0, at(8, 6)),
                s(MetricType.HEART_RATE, 60.0, at(8, 20)),
                s(MetricType.HEART_RATE, 62.0, at(8, 23)),
                s(MetricType.HEART_RATE, 64.0, at(8, 26)),
                s(MetricType.HEART_RATE, 50.0, at(15, 0)), // lone sample, not a window
            ),
        )
        assertEquals(62, r.restingHr)
        assertEquals(80, r.maxHr)
        assertEquals(68.0, r.avgHr!!, 0.001)
        assertEquals(7, r.hrSampleCount)
    }

    @Test fun noHeartRateMeansNulls() {
        val r = agg.aggregate("2026-10-02", emptyList())
        assertNull(r.restingHr)
        assertNull(r.avgHr)
        assertEquals(0, r.steps)
    }

    @Test fun hrvFromIbi() {
        val ibis = listOf(800.0, 820.0, 810.0, 840.0).mapIndexed { i, v ->
            s(MetricType.IBI, v, at(8, 0) + i * 1000L, SampleSource.SAMSUNG_SDK)
        }
        assertEquals(21.602, agg.aggregate("2026-10-02", ibis).hrvRmssd!!, 0.001)
    }
}

class HrvTest {
    @Test fun rmssd() {
        assertEquals(21.602, Hrv.rmssd(listOf(800.0, 820.0, 810.0, 840.0))!!, 0.001)
        assertEquals(
            Hrv.rmssd(listOf(800.0, 820.0, 810.0, 840.0)),
            Hrv.rmssd(listOf(800.0, 100.0, 820.0, 810.0, 840.0, 2500.0)),
        )
        assertNull(Hrv.rmssd(listOf(800.0)))
    }
}

class SessionSummarizerTest {
    private val summarizer = SessionSummarizer(utc)
    private val start = at(6, 0)
    private val end = at(6, 30)

    @Test fun avgMaxDistanceAndRecovery() {
        val r = summarizer.summarize(
            "w1", "run", "dev", start, end,
            listOf(
                s(MetricType.HEART_RATE, 120.0, start + MIN, SampleSource.EXERCISE, "w1"),
                s(MetricType.HEART_RATE, 165.0, end - 2000, SampleSource.EXERCISE, "w1"),
                s(MetricType.HEART_RATE, 150.0, end + 30_000, SampleSource.MEASURE, "w1"),
                s(MetricType.HEART_RATE, 135.0, end + 61_000, SampleSource.MEASURE, "w1"),
                s(MetricType.DISTANCE, 1000.0, start + 5 * MIN, SampleSource.EXERCISE, "w1"),
                s(MetricType.DISTANCE, 2500.0, start + 25 * MIN, SampleSource.EXERCISE, "w1"),
                s(MetricType.DISTANCE, 9999.0, start + 25 * MIN, SampleSource.EXERCISE, "other"),
            ),
        )
        assertEquals(142.5, r.avgHr!!, 0.001)
        assertEquals(165, r.maxHr)
        assertEquals(3500.0, r.distanceMeters)
        assertEquals(30, r.hrRecovery60s)
        assertEquals("2026-10-02", r.dayKey)
    }

    @Test fun noRecoveryWithoutSampleNearPlus60s() {
        val r = summarizer.summarize(
            "w1", "run", "dev", start, end,
            listOf(
                s(MetricType.HEART_RATE, 165.0, end, SampleSource.EXERCISE),
                s(MetricType.HEART_RATE, 150.0, end + 30_000, SampleSource.MEASURE),
            ),
        )
        assertNull(r.hrRecovery60s)
    }
}

class PersonalRecordEngineTest {
    private fun day(key: String, steps: Long, resting: Long?) =
        DailySummary(key, steps, 0.0, 0.0, 0, restingHr = resting)

    private fun session(id: String, key: String, startMs: Long, distance: Double, recovery: Long?) =
        SessionSummary(id, "run", "dev", key, startMs, startMs + 30 * MIN, distance, hrRecovery60s = recovery)

    // Same scenario as the original shared fixture: ties keep the earliest, missing day breaks a streak.
    private val days = listOf(
        day("2026-10-01", 9000, 58),
        day("2026-10-02", 12000, 55),
        day("2026-10-03", 4000, 61),
        day("2026-10-04", 8500, 55),
        day("2026-10-06", 8100, null),
    )
    private val sessions = listOf(
        session("s1", "2026-10-02", utc.startOf("2026-10-02"), 7000.0, 28),
        session("s2", "2026-10-04", utc.startOf("2026-10-04"), 5000.0, 32),
        session("s3", "2026-10-06", utc.startOf("2026-10-06"), 7000.0, null),
    )
    private val engine = PersonalRecordEngine(stepGoal = 8000)

    @Test fun evaluatesFixture() {
        val r = engine.evaluate(days, sessions)
        assertEquals(
            setOf(
                PersonalRecord(RecordKind.MAX_DAILY_STEPS, 12000.0, "2026-10-02"),
                PersonalRecord(RecordKind.LOWEST_RESTING_HR, 55.0, "2026-10-02"),
                PersonalRecord(RecordKind.MAX_SESSION_DISTANCE, 7000.0, "2026-10-02", "s1"),
                PersonalRecord(RecordKind.BEST_HR_RECOVERY, 32.0, "2026-10-04", "s2"),
                PersonalRecord(RecordKind.LONGEST_STEP_STREAK, 2.0, "2026-10-02"),
            ),
            r.values.toSet(),
        )
    }

    @Test fun orderIndependent() {
        assertEquals(engine.evaluate(days, sessions), engine.evaluate(days.reversed(), sessions.reversed()))
    }

    @Test fun newlyBroken() {
        val before = engine.evaluate(days.take(1), emptyList())
        val after = engine.evaluate(days, sessions)
        assertEquals(
            setOf(
                RecordKind.MAX_DAILY_STEPS, RecordKind.LOWEST_RESTING_HR, RecordKind.MAX_SESSION_DISTANCE,
                RecordKind.BEST_HR_RECOVERY, RecordKind.LONGEST_STEP_STREAK,
            ),
            engine.newlyBroken(before, after).map { it.kind }.toSet(),
        )
        assertTrue(engine.newlyBroken(after, after).isEmpty())
    }
}
