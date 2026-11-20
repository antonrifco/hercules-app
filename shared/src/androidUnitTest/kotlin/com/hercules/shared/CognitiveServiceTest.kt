package com.hercules.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.readiness.ScreenSleep
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
import com.hercules.shared.readiness.ScoreSettings
import com.hercules.shared.readiness.SignalSource
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.PrepStatus
import com.hercules.shared.readiness.RoundValidity
import com.hercules.shared.readiness.Urgency
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
        assertTrue(ev.calc.confidencePct >= 50, "confidence=${ev.calc.confidencePct}")
        assertEquals(ConfigurationType.DEFAULT, ev.calc.stamp.configType)
        assertTrue(r.suggestions.any { it.title.startsWith("Short on sleep") })
        // Only the last 10 days are kept.
        assertTrue(cognitive.store.readinessRange("2026-10-27", "2026-11-10").size in 5..11)
        assertTrue(cognitive.store.readinessRange("2026-10-01", "2026-10-30").isEmpty())

        val peak = assertNotNull(cognitive.store.latestPeak())
        assertEquals(PeakResult.PREDICTED, peak.result)
        assertEquals(AssessmentWindow.MORNING, peak.window)
        assertTrue(cognitive.store.baselines().any { it.device == DeviceKind.PHONE && it.window == null && it.metric == "BTC_SPEED" })

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

    @Test fun customSettingsCostConfidenceAndHistoryKeepsItsSettings() = runTest {
        val (repo, insights, cognitive) = services()
        DemoData.load(repo, insights, cognitive, now)
        val yesterday = cognitive.store.readiness("2026-11-09")!!
        assertEquals(ConfigurationType.DEFAULT, yesterday.calc.stamp.configType)
        val before = cognitive.evaluation()!!.calc.confidencePct

        cognitive.saveSettings(ScoreSettings(gameWeight = 5.0))
        assertEquals(0.8, cognitive.settings().gameWeight)
        val today = cognitive.evaluation()!!
        assertEquals(ConfigurationType.CUSTOM, today.calc.stamp.configType)
        assertEquals(before - 5, today.calc.confidencePct)
        // Yesterday's stored score keeps the settings it was made with.
        assertEquals(ConfigurationType.DEFAULT, cognitive.store.readiness("2026-11-09")!!.calc.stamp.configType)

        cognitive.restoreDefaultSettings()
        assertTrue(cognitive.settings().isDefault)
    }

    @Test fun beatTheClockScoresFromTheFirstGameAnyTimeAnyNumber() = runTest {
        val (repo, insights, cognitive) = services()
        val wake = cognitive.wakeMs("2026-11-10")
        fun rounds(id: String) = (0 until 30).map { BeatTheClock.reaction("$id:$it", id, it, 2_000, 330) }
        val at = AssessmentWindow.MORNING.startMs(wake) + 60_000
        val first = cognitive.recordAssessment("a", DeviceKind.PHONE, "phone", at, at + 60_000, rounds("a"))
        assertEquals(AssessmentWindow.MORNING, first.window)
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
        fun rounds(id: String) = (0 until 30).map { BeatTheClock.reaction("$id:$it", id, it, 2_000, 420) }
        val today = cognitive.today()
        // A valid game saved without a score ("locked").
        val old = BeatTheClock.finish("old", DeviceKind.PHONE, "phone", null, today, now - 60_000, now, rounds("old"), emptyList())
            .copy(speedScore = null, cognitiveScore = null)
        cognitive.store.saveSession(old)
        cognitive.refresh()
        val again = cognitive.store.session("old")!!
        assertNotNull(again.cognitiveScore)
        assertEquals(BeatTheClock.GAME_VERSION, again.gameVersion)
    }

    @Test fun energyAndStressAreStoredButNeverScored() = runTest {
        val (_, _, cognitive) = services()
        fun rounds(id: String) = (0 until 30).map { BeatTheClock.reaction("$id:$it", id, it, 2_000, 330) }
        val plain = cognitive.recordAssessment("x", DeviceKind.PHONE, "phone", now, now + 60_000, rounds("x"))
        val rated = cognitive.recordAssessment("y", DeviceKind.WATCH, "watch", now, now + 60_000, rounds("y"), energy = 9, stress = 2)
        assertNull(plain.energy)
        assertEquals(5, rated.energy) // clamped to 1..5
        assertEquals(2, cognitive.store.session("y")?.stress)
        assertEquals(5, cognitive.store.session("y")?.energy)
        // The self-report never moves the score: same as the same game scored without it.
        assertEquals(BeatTheClock.finish("z", DeviceKind.WATCH, "watch", null, rated.dayKey, now, now + 60_000, rounds("z"), emptyList()).speedScore, rated.speedScore)
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

    private fun hcNight(day: String, id: String = "hc:$day") = SleepSessionRecord(
        id, day, dk.startOf(day) - 2 * 3_600_000L, dk.startOf(day) + 6 * 3_600_000L, 450, 30, null, null, null, "phone.app", null, DataQuality.VALID,
    )

    @Test fun lateDataRecalculatesTheLastDaysButNotOlderOnes() = runTest {
        val (_, _, cognitive) = services()
        // Yesterday's night arrives today: yesterday gets a score it never had.
        cognitive.store.upsertSleepSessions(listOf(hcNight("2026-11-09")))
        cognitive.refresh()
        val first = assertNotNull(cognitive.store.evaluation("2026-11-09"))
        assertNotNull(first.calc.score)
        // The night before it arrives too: yesterday is recalculated (more sleep history behind it).
        cognitive.store.upsertSleepSessions(listOf(hcNight("2026-11-08")))
        cognitive.refresh()
        val second = assertNotNull(cognitive.store.evaluation("2026-11-09"))
        assertNotNull(second.calc.score)
        assertEquals(first.calc.stamp.configId, second.calc.stamp.configId)
        // Older than the recalculation window: left alone.
        cognitive.store.upsertSleepSessions(listOf(hcNight("2026-11-05")))
        cognitive.refresh()
        assertNull(cognitive.store.evaluation("2026-11-05"))
    }

    @Test fun withoutTheWatchThePhonesHealthConnectIsUsed() = runTest {
        val (_, _, cognitive) = services()
        val day = "2026-11-10"
        cognitive.store.upsertSleepSessions(listOf(hcNight(day)))
        val ev = cognitive.refresh()
        assertEquals(com.hercules.shared.cognitive.SleepNight.SOURCE_HEALTH_CONNECT, cognitive.store.sleep(day)?.source)
        // An unstaged night is a phone estimate: it scores, but confidence can't reach High.
        assertEquals(SignalSource.PHONE, ev.calc.sleepSource)
        assertNotNull(ev.calc.dimensions.first { it.dimension == Dimension.SLEEP }.score)
        assertTrue(ev.calc.confidencePct <= 74)
    }

    @Test fun sleepEndingBeforeThreeIsNotLastNight() = runTest {
        val (_, _, cognitive) = services()
        val day = dk.plusDays("2026-11-10", -4)
        // The phone down 20:00–00:05 (TV evening): ends too early to be the night of [day].
        val evening = SleepSessionRecord("screen:$day", day, dk.startOf(day) - 4 * 3_600_000L, dk.startOf(day) + 5 * 60_000L, 245, 0, null, null, null, ScreenSleep.SOURCE, "phone", DataQuality.VALID)
        cognitive.store.upsertSleepSessions(listOf(evening))
        cognitive.refresh()
        assertEquals(null, cognitive.store.sleep(day))
    }

    @Test fun lateWatchSleepReplacesAPhoneNightEvenDaysLater() = runTest {
        val (_, _, cognitive) = services()
        val day = dk.plusDays("2026-11-10", -5)
        // First only the phone's screen-use estimate for that night: it becomes the night.
        val screen = SleepSessionRecord("screen:$day", day, dk.startOf(day) - 1 * 3_600_000L, dk.startOf(day) + 7 * 3_600_000L, 480, 0, null, null, null, ScreenSleep.SOURCE, "phone", DataQuality.VALID)
        cognitive.store.upsertSleepSessions(listOf(screen))
        cognitive.refresh()
        assertEquals("Phone screen use", cognitive.store.sleep(day)?.provider)
        // Days later the watch's staged night for it syncs: it replaces the estimate.
        val watch = SleepSessionRecord("hc:w:$day", day, dk.startOf(day) - 30 * 60_000L, dk.startOf(day) + 6 * 3_600_000L, 360, 20, 80, 90, 190, "com.xiaomi.wearable", "Xiaomi Watch", DataQuality.VALID)
        cognitive.store.upsertSleepSessions(listOf(watch))
        cognitive.refresh()
        assertEquals("com.xiaomi.wearable", cognitive.store.sleep(day)?.provider)
        assertEquals(360, cognitive.store.sleep(day)?.asleepMinutes)
    }

    @Test fun aNapCountsAsSleep() = runTest {
        val (_, _, cognitive) = services()
        val day = "2026-11-10"
        cognitive.store.upsertSleepSessions((0..4).map { hcNight(dk.plusDays(day, -it)) })
        val evening = dk.startOf(day) + 18 * 3_600_000L
        val without = cognitive.refresh(evening).calc
        // A 1-hour nap after lunch, from Health Connect: less sleep pressure by the evening.
        val nap = SleepSessionRecord("hc:nap", day, dk.startOf(day) + 13 * 3_600_000L, dk.startOf(day) + 14 * 3_600_000L, 60, 0, null, null, null, "phone.app", null, DataQuality.VALID)
        cognitive.store.upsertSleepSessions(listOf(nap))
        val with = cognitive.refresh(evening).calc
        assertTrue(with.alertness!! > without.alertness!!, "nap ${with.alertness} vs ${without.alertness}")
        assertEquals(dk.startOf(day) + 6 * 3_600_000L, cognitive.store.sleep(day)?.endMs, "the nap doesn't replace the night")
    }

    @Test fun onlyTheLastTenDaysAreKept() = runTest {
        val (_, _, cognitive) = services()
        // The first import can bring more nights than the window: all within it become nights, older ones go.
        cognitive.store.upsertSleepSessions((0..20).map { back -> hcNight(dk.plusDays("2026-11-10", -back)) })
        val ev = cognitive.refresh()
        assertEquals(11, cognitive.store.sleepRange("2026-10-01", "2026-11-10").size)
        assertTrue(cognitive.store.sleepSessions("2026-10-25").isEmpty())
        assertEquals(10, ev.calc.historyDays)
    }

    @Test fun noScoreBetweenMidnightAndWaking() = runTest {
        // 00:30 with the night before on record: the day has not started.
        val night = dk.startOf("2026-11-10") + 30 * 60_000L
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HerculesDatabase.Schema.create(driver)
        val db = HerculesDatabase(driver)
        val cognitive = CognitiveService(HerculesRepository(db, Dispatchers.Unconfined), CognitiveStore(db, Dispatchers.Unconfined), dk, clock = { night })
        cognitive.store.upsertSleepSessions(listOf(hcNight("2026-11-09")))
        val ev = cognitive.refresh()
        assertNull(ev.calc.score)
        assertEquals(com.hercules.shared.readiness.REASON_BEFORE_WAKE, ev.calc.insufficientReason)
        // After the usual wake-up time, with no night recorded, the usual sleep window stands in (Low confidence).
        val morning = CognitiveService(HerculesRepository(db, Dispatchers.Unconfined), CognitiveStore(db, Dispatchers.Unconfined), dk, clock = { night + 8 * 3_600_000L })
        val later = morning.refresh().calc
        assertNotNull(later.score)
        assertEquals(SignalSource.ASSUMED, later.sleepSource)
        assertEquals(com.hercules.shared.readiness.ReadinessConfidence.LOW, later.confidence)
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
