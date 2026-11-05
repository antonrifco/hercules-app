package com.hercules.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.CollectionMethod
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.HealthMetric
import com.hercules.shared.readiness.HealthMetricType
import com.hercules.shared.readiness.SleepSessionRecord
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.ConfigurationType
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.MomentRules
import com.hercules.shared.readiness.MomentStatus
import com.hercules.shared.readiness.PeakResult
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.PrepStatus
import com.hercules.shared.readiness.RoundValidity
import com.hercules.shared.readiness.Urgency
import com.hercules.shared.readiness.WeightValidation
import kotlin.test.assertIs
import kotlin.test.assertNull
import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.db.HerculesDatabase
import com.hercules.shared.demo.DemoData
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.insights.InsightsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CognitiveServiceTest {
    private val dk = DayKeys(TimeZone.UTC)
    private val now = dk.startOf("2026-11-10") + 9 * 3_600_000L

    private fun services(): Triple<HerculesRepository, InsightsService, CognitiveService> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HerculesDatabase.Schema.create(driver)
        val db = HerculesDatabase(driver)
        val repo = HerculesRepository(db, Dispatchers.Unconfined)
        val insights = InsightsService(repo, dk, clock = { now })
        val cognitive = CognitiveService(repo, CognitiveStore(db, Dispatchers.Unconfined), dk, clock = { now })
        return Triple(repo, insights, cognitive)
    }

    @Test fun demoDataProducesScoreHistoryPeakAndMoments() = runTest {
        val (repo, insights, cognitive) = services()
        DemoData.load(repo, insights, cognitive, now)

        // A wearable's staged night in Health Connect (01:10 → 06:20) beats the Hercules watch's detection (spec §14).
        val sleep = assertNotNull(cognitive.store.sleep("2026-11-10"))
        assertEquals("demo", sleep.provider)
        assertNotNull(sleep.deepMinutes)
        val ev = cognitive.refresh()
        val r = assertNotNull(ev.readiness, ev.calc.insufficientReason)
        assertTrue(r.score in 30..75, "score=${r.score}")
        assertTrue(ev.calc.coverage >= 0.7)
        assertEquals("default-v2", ev.calc.stamp.configId)
        assertTrue(r.suggestions.any { it.title.startsWith("Short on sleep") })
        assertTrue(cognitive.store.readinessRange("2026-10-27", "2026-11-10").size >= 10)

        val peak = assertNotNull(cognitive.store.latestPeak())
        assertEquals(PeakResult.PREDICTED, peak.result)
        assertEquals(AssessmentWindow.MORNING, peak.window)
        assertTrue(cognitive.store.baselines().any { it.device == DeviceKind.PHONE && it.window == null && it.metric == "BTC_ACCURACY" })

        val events = cognitive.upcoming()
        val pitch = events.first { it.event.id == "demo-pitch" }
        assertEquals(PrepPhase.DAY, pitch.phase)
        val interview = events.first { it.event.id == "demo-interview" }
        assertEquals(6, interview.daysUntil)
        assertEquals(Urgency.CRITICAL, interview.event.urgency)
        assertEquals(PrepPhase.FOUNDATION, interview.phase)
        assertTrue(interview.preparation.status != PrepStatus.INSUFFICIENT_DATA)
        assertTrue(cognitive.store.snapshots("demo-interview").isNotEmpty())

        val state = cognitive.watchState()
        assertEquals(state, cognitive.decodeWatchState(cognitive.encodeWatchState(state)))
        assertEquals("demo-pitch", state.nextEvent?.event?.id)
    }

    @Test fun customWeightsAreValidatedAndHistoryKeepsItsConfig() = runTest {
        val (repo, insights, cognitive) = services()
        DemoData.load(repo, insights, cognitive, now)
        val yesterday = cognitive.store.readiness("2026-11-09")!!
        assertEquals("default-v2", yesterday.calc.stamp.configId)

        val bad = cognitive.saveWeights(Dimension.entries.associateWith { 0.3 })
        assertIs<WeightValidation.Invalid>(bad)
        assertEquals(ConfigurationType.DEFAULT, cognitive.config().type)

        val w = mapOf(Dimension.SLEEP to 0.60, Dimension.CARDIO to 0.20, Dimension.ACTIVITY to 0.0, Dimension.CIRCADIAN to 0.0, Dimension.COGNITIVE to 0.20)
        assertIs<WeightValidation.Valid>(cognitive.saveWeights(w))
        val today = cognitive.evaluation()!!
        assertEquals(ConfigurationType.CUSTOM, today.calc.stamp.configType)
        // Yesterday's stored score is untouched.
        assertEquals(yesterday, cognitive.store.readiness("2026-11-09"))

        cognitive.restoreDefaultWeights()
        assertEquals(ConfigurationType.DEFAULT, cognitive.config().type)
        assertEquals(2, cognitive.store.configHistory().size) // custom, then restored default
    }

    @Test fun beatTheClockScoresFromTheFirstGameAnyTimeAnyNumber() = runTest {
        val (repo, insights, cognitive) = services()
        val wake = cognitive.wakeMs("2026-11-10")
        fun rounds(id: String) = listOf(15_000L, 22_000L, 28_000L).mapIndexed { i, t -> CognitiveRound("$id:$i", id, i, t, t + 300, RoundValidity.VALID) }
        val at = AssessmentWindow.MORNING.startMs(wake) + 60_000
        val first = cognitive.recordAssessment("a", DeviceKind.PHONE, "phone", at, at + 60_000, rounds("a"))
        assertEquals(AssessmentWindow.MORNING, first.window)
        assertFalse(first.practice)
        assertNotNull(first.cognitiveScore) // scored from the very first game
        val second = cognitive.recordAssessment("b", DeviceKind.PHONE, "phone", at + 120_000, at + 180_000, rounds("b"))
        assertEquals(AssessmentWindow.MORNING, second.window) // any time counts, as often as you like
        val late = wake + 13 * 3_600_000L
        val third = cognitive.recordAssessment("c", DeviceKind.PHONE, "phone", late, late + 60_000, rounds("c"))
        assertEquals(AssessmentWindow.EVENING, third.window)
        assertEquals(3, cognitive.playsToday())

        // Watch sessions arrive twice; stored once.
        val w = BeatTheClock.finish("w1", DeviceKind.WATCH, "watch", null, "2026-11-10", at, at + 1, rounds("w1"), emptyList())
        cognitive.applyRemoteSessions(listOf(w))
        cognitive.applyRemoteSessions(listOf(w))
        assertEquals(1, cognitive.store.completedOnDevice(DeviceKind.WATCH))
    }

    @Test fun todaysUnscoredGamesAreScoredOnRefresh() = runTest {
        val (_, _, cognitive) = services()
        fun rounds(id: String) = listOf(15_000L, 22_000L, 28_000L).mapIndexed { i, t -> CognitiveRound("$id:$i", id, i, t, t + 900, RoundValidity.VALID) }
        val today = cognitive.today()
        // A game saved under the old rules: valid, but no score ("locked").
        val old = BeatTheClock.finish("old", DeviceKind.PHONE, "phone", null, today, now - 60_000, now, rounds("old"), emptyList())
            .copy(accuracyScore = null, consistencyScore = null, biasScore = null, cognitiveScore = null, gameVersion = 2)
        cognitive.store.saveSession(old)
        cognitive.refresh()
        val again = cognitive.store.session("old")!!
        assertNotNull(again.cognitiveScore)
        assertEquals(2, again.gameVersion)
    }

    @Test fun energyAndStressAreStoredButNeverScored() = runTest {
        val (_, _, cognitive) = services()
        fun rounds(id: String) = listOf(15_000L, 22_000L, 28_000L).mapIndexed { i, t -> CognitiveRound("$id:$i", id, i, t, t + 300, RoundValidity.VALID) }
        val plain = cognitive.recordAssessment("x", DeviceKind.PHONE, "phone", now, now + 60_000, rounds("x"))
        val rated = cognitive.recordAssessment("y", DeviceKind.WATCH, "watch", now, now + 60_000, rounds("y"), energy = 9, stress = 2)
        assertNull(plain.energy)
        assertEquals(5, rated.energy) // clamped to 1..5
        assertEquals(2, cognitive.store.session("y")?.stress)
        assertEquals(5, cognitive.store.session("y")?.energy)
        assertEquals(plain.accuracyScore, rated.accuracyScore)
        assertEquals(plain.status, rated.status)
    }

    @Test fun momentConstraintsAndLifecycle() = runTest {
        val (_, _, cognitive) = services()
        for (i in 1..5) assertIs<MomentRules.Check.Ok>(cognitive.saveMoment("m$i", "Moment $i", now + i * 3_600_000L, Urgency.NORMAL))
        assertIs<MomentRules.Check.Rejected>(cognitive.saveMoment(null, "Sixth", now + 10 * 3_600_000L, Urgency.NORMAL))
        assertIs<MomentRules.Check.Rejected>(cognitive.saveMoment(null, "Too far", now + 8 * 86_400_000L, Urgency.NORMAL))
        // Editing one of the five is still allowed.
        assertIs<MomentRules.Check.Ok>(cognitive.saveMoment("m1", "Moment one", now + 2 * 3_600_000L, Urgency.CRITICAL))
        assertEquals(Urgency.CRITICAL, cognitive.store.event("m1")!!.urgency)
        cognitive.cancelMoment("m2")
        assertEquals(MomentStatus.CANCELLED, cognitive.store.event("m2")!!.status)
        assertIs<MomentRules.Check.Ok>(cognitive.saveMoment(null, "Now there's room", now + 10 * 3_600_000L, Urgency.NORMAL))
        assertEquals(5, cognitive.upcoming().size)
    }

    @Test fun passedMomentsCompleteAutomatically() = runTest {
        var t = now
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HerculesDatabase.Schema.create(driver)
        val db = HerculesDatabase(driver)
        val cognitive = CognitiveService(HerculesRepository(db, Dispatchers.Unconfined), CognitiveStore(db, Dispatchers.Unconfined), dk, clock = { t })
        cognitive.saveMoment("x", "Exam", now + 3_600_000L, Urgency.NORMAL)
        t = now + 2 * 3_600_000L
        assertEquals(MomentStatus.COMPLETED, cognitive.moments().first().status)
        assertTrue(cognitive.upcoming().isEmpty())
    }

    @Test fun lateDataRecalculatesTheLastDaysButNotOlderOnes() = runTest {
        val (_, _, cognitive) = services()
        fun metric(type: HealthMetricType, day: String, v: Double) = HealthMetric(
            "t:$type:$day", type, v, dk.startOf(day) + 7 * 3_600_000L, dk.startOf(day) + 7 * 3_600_000L, now, day,
            "test", null, DataQuality.VALID, CollectionMethod.HEALTH_CONNECT,
        )
        // Resting heart rate for yesterday arrives today: yesterday gets a score it never had.
        cognitive.store.upsertMetrics(listOf(metric(HealthMetricType.RESTING_HR, "2026-11-09", 58.0)))
        cognitive.refresh()
        val first = assertNotNull(cognitive.store.evaluation("2026-11-09"))
        assertNotNull(first.calc.score)
        // Then yesterday's HRV arrives: yesterday is recalculated with more coverage.
        cognitive.store.upsertMetrics(listOf(metric(HealthMetricType.HRV_RMSSD, "2026-11-09", 45.0)))
        cognitive.refresh()
        val second = assertNotNull(cognitive.store.evaluation("2026-11-09"))
        assertTrue(second.calc.dimensions.first { it.dimension == Dimension.CARDIO }.metrics.any { it.key.name == "HRV" && it.score != null })
        assertEquals(first.calc.stamp.configId, second.calc.stamp.configId)
        // Older than the recalculation window: left alone.
        cognitive.store.upsertMetrics(listOf(metric(HealthMetricType.RESTING_HR, "2026-11-05", 58.0)))
        cognitive.refresh()
        assertNull(cognitive.store.evaluation("2026-11-05"))
    }

    @Test fun withoutTheWatchThePhonesHealthConnectIsUsed() = runTest {
        val (_, _, cognitive) = services()
        val day = "2026-11-10"
        val start = dk.startOf(day) - 2 * 3_600_000L
        cognitive.store.upsertSleepSessions(listOf(
            SleepSessionRecord("hc:1", day, start, start + 8 * 3_600_000L, 450, 30, null, null, null, "phone.app", null, DataQuality.VALID),
        ))
        cognitive.store.upsertMetrics(listOf(
            HealthMetric("hc:rhr", HealthMetricType.RESTING_HR, 57.0, now - 3_600_000L, now - 3_600_000L, now, day, "phone.app", null, DataQuality.VALID, CollectionMethod.HEALTH_CONNECT),
        ))
        val ev = cognitive.refresh()
        assertEquals(com.hercules.shared.cognitive.SleepNight.SOURCE_HEALTH_CONNECT, cognitive.store.sleep(day)?.source)
        assertNotNull(ev.calc.dimensions.first { it.dimension == Dimension.SLEEP }.score)
        assertNotNull(ev.calc.dimensions.first { it.dimension == Dimension.CARDIO }.score)
    }

    @Test fun noDataMeansInsufficientNotZero() = runTest {
        val (_, _, cognitive) = services()
        val ev = cognitive.refresh()
        assertNull(ev.readiness)
        assertNull(ev.calc.score)
        assertNotNull(ev.calc.insufficientReason)
    }

    @Test fun correctedSleepWinsOverAutoDetection() = runTest {
        val (repo, insights, cognitive) = services()
        DemoData.load(repo, insights, cognitive, now)
        val today = cognitive.today()
        val auto = cognitive.store.sleep(today)!!
        val before = cognitive.refresh().readiness!!
        val start = auto.endMs - (8.5 * 3_600_000).toLong()
        val after = cognitive.correctSleep(today, start, auto.endMs).readiness!!
        val fixed = cognitive.store.sleep(today)!!
        assertTrue(fixed.corrected)
        assertTrue(after.score > before.score)
        cognitive.refresh()
        assertEquals(fixed, cognitive.store.sleep(today))
    }
}
