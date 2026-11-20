package com.hercules.shared

import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.Availability
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveInput
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.DayInputs
import com.hercules.shared.readiness.confidenceBasis
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.MomentPreparationEngine
import com.hercules.shared.readiness.MomentRules
import com.hercules.shared.readiness.MomentStatus
import com.hercules.shared.readiness.NightInput
import com.hercules.shared.readiness.PeakAlignment
import com.hercules.shared.readiness.PeakResult
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.PeakWindowPredictor
import com.hercules.shared.readiness.PrepContext
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.PrepStatus
import com.hercules.shared.readiness.PrepTask
import com.hercules.shared.readiness.ReadinessCalculator
import com.hercules.shared.readiness.ReadinessConfidence
import com.hercules.shared.readiness.RoundValidity
import com.hercules.shared.readiness.SessionStatus
import com.hercules.shared.readiness.Stats
import com.hercules.shared.readiness.Calibration
import com.hercules.shared.readiness.CalibrationPoint
import com.hercules.shared.readiness.Calibrator
import com.hercules.shared.readiness.MetricKey
import com.hercules.shared.readiness.Recovery
import com.hercules.shared.readiness.ScoreSettings
import com.hercules.shared.readiness.Signal
import com.hercules.shared.readiness.SignalSource
import com.hercules.shared.readiness.SleepEpisode
import com.hercules.shared.readiness.SleepModel
import com.hercules.shared.readiness.ScreenSleep
import com.hercules.shared.readiness.Urgency
import com.hercules.shared.readiness.confidenceFor
import kotlin.math.abs
import kotlin.random.Random
import com.hercules.shared.readiness.ReactionRun
import com.hercules.shared.readiness.TrialPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val H = 3_600_000L
private const val D = 24 * H

class ScoringTest {
    private val calc = ReadinessCalculator()
    private val day = "2026-11-10"
    /** Midnight of [day] in the tests' clock (UTC, hours from a fixed origin). */
    private val t0 = 1_000 * D

    /** [nights] nights of [hours] each, waking at 07:00, the last one ending on the morning of day 0. */
    private fun nights(n: Int, hours: Double): List<SleepEpisode> = (n - 1 downTo 0).map { back ->
        val wake = t0 - back * D + 7 * H
        SleepEpisode(wake - (hours * H).toLong(), wake)
    }

    private fun input(
        episodes: List<SleepEpisode> = nights(10, 8.0),
        hoursAwake: Double = 2.0,
        source: SignalSource? = SignalSource.WATCH,
        hrv: Signal? = null,
        rhr: Signal? = null,
        temp: Signal? = null,
        game: CognitiveInput? = null,
        historyDays: Int = 9,
        settings: ScoreSettings = ScoreSettings.DEFAULT,
    ): DayInputs {
        val at = (episodes.lastOrNull()?.endMs ?: (t0 + 7 * H)) + (hoursAwake * H).toLong()
        return DayInputs(
            dayKey = day, atMs = at, hourOfDay = ((at - t0) / H.toDouble()) % 24, lastNight = null, sleepSource = source,
            episodes = episodes, usualWakeHour = 7.0, hrv = hrv, restingHr = rhr, skinTemp = temp, cognitive = game,
            historyDays = historyDays, settings = settings,
        )
    }

    private fun p(i: DayInputs) = calc.calculate(i).alertness!!

    @Test fun lapsesBuildAwakeAndClearAsleep() {
        val s = SleepModel.State(8.0, 40.0, 1.0)
        assertTrue(SleepModel.step(s, 12.0, asleep = false, h = 1.0, phi = SleepModel.PHI, amp = 1.0).p > s.p - 0.5)
        assertTrue(SleepModel.step(s, 2.0, asleep = true, h = 1.0, phi = SleepModel.PHI, amp = 1.0).p < s.p)
        assertTrue(SleepModel.step(s, 2.0, asleep = true, h = 1.0, phi = SleepModel.PHI, amp = 1.0).k < s.k, "κ decays in sleep")
        assertNull(SleepModel.at(emptyList(), 1000, 0.0, SleepModel.PHI, 1.0, 7.0))
        assertNull(SleepModel.at(nights(1, 8.0), t0 - 2 * D, 0.0, SleepModel.PHI, 1.0, 7.0), "nothing before the first night")
    }

    @Test fun reproducesThePapersRanges() {
        // Rested: a few lapses. A night without sleep: well into the teens (McCauley 2013, Fig. 3-4 scale).
        val rested = SleepModel.at(nights(10, 8.0), t0 + 9 * H, 0.0, SleepModel.PHI, 1.0, 7.0)!!.p
        val noSleep = SleepModel.at(nights(10, 8.0), t0 + 31 * H, 0.0, SleepModel.PHI, 1.0, 7.0)!!.p
        assertTrue(rested in 2.0..6.0, "rested $rested")
        assertTrue(noSleep in 12.0..22.0, "24 h awake $noSleep")
    }

    @Test fun restedAndShortlyAwakeIsHighAndADayAwakeIsLow() {
        assertTrue(p(input(hoursAwake = 2.0)) >= 85, "after 8 h sleep and 2 h awake")
        assertTrue(p(input(hoursAwake = 24.0)) < 45, "after 24 h awake")
        assertTrue(p(input(hoursAwake = 48.0)) < p(input(hoursAwake = 24.0)))
    }

    @Test fun sleepDebtCarriesOver() {
        val full = nights(7, 8.0)
        val p0 = p(input(full))
        val p1 = p(input(full.dropLast(1) + nights(1, 5.0)))
        val p3 = p(input(full.dropLast(3) + nights(3, 5.0)))
        assertTrue(p1 < p0)
        assertTrue(p3 < p1, "three short nights lower P more than one")
    }

    @Test fun chronicSixHourSleepKeepsPredictedAlertnessBelowEightHours() {
        val eight = p(input(nights(10, 8.0)))
        val six = (3..10).map { p(input(nights(it, 6.0))) }
        assertTrue(six.zipWithNext().all { (a, b) -> b <= a }, "keeps going down or holds, never rises: $six")
        assertTrue(six.last() < eight - 5, "6 h for 10 nights stays well under the 8 h level")
    }

    @Test fun withoutAGameTheScoreIsPTimesM() {
        val i = input(hrv = Signal(30.0, List(9) { 50.0 + it }, SignalSource.WATCH))
        val c = calc.calculate(i)
        assertTrue(abs(c.alertness!! * c.recovery!! - c.score!!) <= 1.0, "P×M = ${c.alertness} × ${c.recovery}, score ${c.score}")
    }

    @Test fun recoveryOnlySubtractsAndHasAFloor() {
        val good = calc.calculate(input(hrv = Signal(90.0, List(9) { 50.0 + it }, SignalSource.WATCH)))
        assertEquals(1.0, good.recovery!!, 1e-9)
        val terrible = Recovery.modifier(listOf(Recovery.Term(MetricKey.HRV, -4.0, 4.0, 1.0, 4.0, SignalSource.WATCH)))
        assertEquals(0.7, terrible, 1e-9)
    }

    @Test fun missingOrShortHistorySignalsChangeNothing() {
        val base = calc.calculate(input())
        val short = calc.calculate(input(hrv = Signal(20.0, List(4) { 50.0 }, SignalSource.WATCH)))
        assertEquals(1.0, base.recovery)
        assertEquals(1.0, short.recovery)
        assertEquals(base.score, short.score)
    }

    @Test fun phoneHrvCountsHalf() {
        val hist = List(9) { 50.0 + it }
        val watch = Recovery.terms(input(hrv = Signal(30.0, hist, SignalSource.WATCH))).single()
        val phone = Recovery.terms(input(hrv = Signal(30.0, hist, SignalSource.PHONE))).single()
        assertEquals(watch.penalty / 2, phone.penalty, 1e-9)
    }

    @Test fun aRecentGameIsBlendedSixtyForty() {
        val c = calc.calculate(input(game = CognitiveInput(90, "Phone")))
        assertEquals(0.6, c.gameShare, 1e-9)
        assertTrue(abs(0.6 * 90 + 0.4 * c.alertness!! * c.recovery!! - c.score!!) <= 1.0, "within rounding")
    }

    @Test fun gameOnlyWhenThereIsNoSleep() {
        val c = calc.calculate(input(episodes = emptyList(), source = null, game = CognitiveInput(64, "Phone")))
        assertEquals(64, c.score)
        assertEquals(ReadinessConfidence.LOW, c.confidence)
    }

    @Test fun nothingUsableGivesNoScore() {
        val c = calc.calculate(input(episodes = emptyList(), source = null))
        assertNull(c.score)
        assertEquals(ReadinessConfidence.INSUFFICIENT, c.confidence)
    }

    @Test fun confidencePoints() {
        val full = calc.calculate(
            input(
                hrv = Signal(55.0, List(9) { 55.0 }, SignalSource.WATCH), rhr = Signal(58.0, List(9) { 58.0 }, SignalSource.WATCH),
                temp = Signal(0.0, List(9) { 0.0 }, SignalSource.WATCH), game = CognitiveInput(60, "Phone"),
            ),
        )
        assertEquals(93, full.confidencePct)
        assertEquals(ReadinessConfidence.HIGH, full.confidence)
        val phoneSleep = calc.calculate(input(source = SignalSource.PHONE, hrv = Signal(55.0, List(9) { 55.0 }, SignalSource.PHONE), game = CognitiveInput(60, "Phone")))
        assertEquals(15 + 15 + 4 + 19, phoneSleep.confidencePct)
        val assumed = calc.calculate(input(source = SignalSource.ASSUMED, historyDays = 2))
        assertEquals(5, assumed.confidencePct)
        assertEquals(ReadinessConfidence.LOW, assumed.confidence)
        val custom = calc.calculate(input(settings = ScoreSettings(gameWeight = 0.4)))
        assertEquals(35 + 15 - 5, custom.confidencePct)
    }

    @Test fun withoutWatchSleepConfidenceIsNeverHigh() {
        val all = input(
            source = SignalSource.PHONE, hrv = Signal(55.0, List(9) { 55.0 }, SignalSource.WATCH), rhr = Signal(58.0, List(9) { 58.0 }, SignalSource.WATCH),
            temp = Signal(0.0, List(9) { 0.0 }, SignalSource.WATCH), game = CognitiveInput(60, "Phone"),
        ).copy(spo2 = Signal(97.0, List(9) { 97.0 }, SignalSource.WATCH), glucose = Signal(5.5, List(9) { 5.5 }, SignalSource.WATCH))
        assertEquals(74, calc.calculate(all).confidencePct)
        assertEquals(ReadinessConfidence.MEDIUM, confidenceFor(74))
        assertEquals(ReadinessConfidence.HIGH, confidenceFor(75))
        assertEquals(ReadinessConfidence.LOW, confidenceFor(49))
    }

    @Test fun slidersScaleTheModel() {
        val base = p(input(hoursAwake = 12.0))
        val strong = p(input(hoursAwake = 12.0, settings = ScoreSettings(sleepInfluence = 1.5)))
        assertTrue(strong < base)
        assertEquals(ScoreSettings(gameWeight = 0.8), ScoreSettings(gameWeight = 2.0).clamped())
        assertTrue(ScoreSettings.DEFAULT.isDefault)
    }

    @Test fun scoreStaysInOneToHundred() {
        val c = calc.calculate(input(hoursAwake = 40.0))
        assertTrue(c.score!! in 1..100)
    }

    @Test fun calibrationWaitsForTwentySessionsAndStaysBounded() {
        val eps = nights(10, 8.0)
        val pts = List(20) { i -> CalibrationPoint(game = 60.0 + i, atMs = t0 - (i % 9) * D + (9 + i % 8) * H, hrvBad = (i % 3).toDouble()) }
        assertFalse(Calibrator.fit(pts, eps, 0.0, 7.0, Calibration(sessions = 19), 0).fitted)
        val fit = Calibrator.fit(pts, eps, 0.0, 7.0, Calibration(sessions = 20), 0)
        assertTrue(fit.fitted)
        assertTrue(fit.hrvWeight in 0.0..Calibrator.MAX_WEIGHT)
        assertTrue(fit.amplitude in Calibrator.AMPLITUDES)
    }

    @Test fun bandsAndBasis() {
        assertEquals(ReadinessBand.PRIMA, ReadinessBand.of(85))
        assertEquals(ReadinessBand.TERKURAS, ReadinessBand.of(39))
        assertTrue("%" !in calc.calculate(input()).confidenceBasis)
    }
}

class BeatTheClockTest {
    @Test fun fromFiveTestsTheScoreIsAPercentileOfTheLastTenDays() {
        assertNull(BeatTheClock.percentile(3.0, List(4) { 2.5 }))
        assertEquals(100, BeatTheClock.percentile(3.0, listOf(2.0, 2.1, 2.2, 2.3, 2.4)))
        assertEquals(0, BeatTheClock.percentile(1.0, listOf(2.0, 2.1, 2.2, 2.3, 2.4)))
        assertEquals(50, BeatTheClock.percentile(2.2, listOf(2.0, 2.1, 2.2, 2.3, 2.4)))
    }

    /** A test of [n] reactions at [ms] each, plus [early] false starts. */
    private fun rounds(ms: Long, n: Int = 40, early: Int = 0): List<CognitiveRound> =
        (0 until n).map { BeatTheClock.reaction("r$it", "s", it, 2_000, ms) } +
            (0 until early).map { BeatTheClock.falseStart("f$it", "s", n + it, 2_000) }

    private fun session(id: String, device: DeviceKind, window: AssessmentWindow, startMs: Long, ms: Long, history: List<CognitiveSession>, early: Int = 0) =
        BeatTheClock.finish(id, device, "dev", window, "d", startMs, startMs + 180_000, rounds(ms, early = early), history)

    @Test fun delaysAreRandomBetweenOneAndFourSeconds() {
        val r = Random(1)
        val d = (0 until 500).map { BeatTheClock.nextDelay(r) }
        assertTrue(d.all { it in 1_000L..4_000L })
        assertTrue(d.distinct().size > 100, "delays must not be predictable")
    }

    @Test fun trialValidity() {
        assertEquals(RoundValidity.VALID, BeatTheClock.reaction("a", "s", 0, 2_000, 320).validity)
        assertEquals(RoundValidity.FALSE_START, BeatTheClock.reaction("a", "s", 0, 2_000, 60).validity) // anticipation
        assertEquals(RoundValidity.FALSE_START, BeatTheClock.falseStart("a", "s", 0, 2_000).validity)
        // No tap: recorded at the timeout, and it is a lapse.
        val none = BeatTheClock.reaction("a", "s", 0, 2_000, 10_000)
        assertEquals(BeatTheClock.TIMEOUT_MS, none.reactionMs)
        assertTrue(none.lapse)
        assertFalse(BeatTheClock.reaction("a", "s", 0, 2_000, 499).lapse)
        assertTrue(BeatTheClock.reaction("a", "s", 0, 2_000, 500).lapse)
    }

    @Test fun sessionValidity() {
        assertEquals(SessionStatus.FULLY_VALID, BeatTheClock.status(rounds(320, n = 30)))
        assertEquals(SessionStatus.PARTIALLY_VALID, BeatTheClock.status(rounds(320, n = 15)))
        assertEquals(SessionStatus.INVALID, BeatTheClock.status(rounds(320, n = 14)))
        // Tapping at random (more than a quarter false starts) doesn't count.
        assertEquals(SessionStatus.INVALID, BeatTheClock.status(rounds(320, n = 30, early = 11)))
    }

    @Test fun speedLapsesAndFalseStarts() {
        val rs = (0 until 30).map { BeatTheClock.reaction("r$it", "s", it, 2_000, if (it < 3) 800 else 250) } +
            BeatTheClock.falseStart("f", "s", 30, 2_000)
        val s = BeatTheClock.finish("e", DeviceKind.PHONE, "p", null, "d", 0, 1, rs, emptyList())
        assertEquals(3, s.lapses)
        assertEquals(1, s.falseStarts)
        assertEquals(250.0, s.medianMs!!, 1e-9)
        assertEquals((27 * 4.0 + 3 * 1.25) / 30, s.speed!!, 1e-9)
    }

    @Test fun speedScoreFormula() {
        assertEquals(50, BeatTheClock.speedScore(3.0, 3.0))
        assertTrue(BeatTheClock.speedScore(2.7, 3.0) in 33..36) // 10% slower
        assertTrue(BeatTheClock.speedScore(3.3, 3.0) in 63..66) // 10% faster
        assertEquals(0, BeatTheClock.speedScore(1.0, 3.0))
        assertEquals(100, BeatTheClock.speedScore(9.0, 3.0))
    }

    @Test fun theFirstGameIsScoredAgainstTheDeviceReference() {
        val s = session("p", DeviceKind.PHONE, AssessmentWindow.MORNING, 10 * D, 333, emptyList())
        assertNotNull(s.cognitiveScore)
        assertEquals(BeatTheClock.speedScore(s.speed!!, BeatTheClock.referenceSpeed(DeviceKind.PHONE)), s.speedScore)
    }

    @Test fun usualTurnsPersonalAfterThreeSameDeviceGames() {
        val hist = (1..3).map { session("h$it", DeviceKind.WATCH, AssessmentWindow.MORNING, (10 + it) * D, 400, emptyList()) }
        // The phone never borrows the watch's usual: it still uses the phone reference.
        val phone = session("x", DeviceKind.PHONE, AssessmentWindow.MORNING, 20 * D, 400, hist)
        assertEquals(BeatTheClock.speedScore(phone.speed!!, BeatTheClock.referenceSpeed(DeviceKind.PHONE)), phone.speedScore)
        // Same speed as the watch's own usual scores 50, any time of day.
        assertEquals(50, session("z", DeviceKind.WATCH, AssessmentWindow.EVENING, 20 * D, 400, hist).speedScore)
        assertEquals(400.0, BeatTheClock.usualMs(session("z", DeviceKind.WATCH, AssessmentWindow.EVENING, 20 * D, 440, hist))!!, 1.0)
    }

    @Test fun falseStartsBeyondTwoCostPoints() {
        val clean = session("a", DeviceKind.PHONE, AssessmentWindow.MORNING, 10 * D, 333, emptyList())
        val two = session("b", DeviceKind.PHONE, AssessmentWindow.MORNING, 10 * D, 333, emptyList(), early = 2)
        val five = session("c", DeviceKind.PHONE, AssessmentWindow.MORNING, 10 * D, 333, emptyList(), early = 5)
        assertEquals(clean.cognitiveScore, two.cognitiveScore)
        assertEquals(clean.cognitiveScore!! - 15, five.cognitiveScore)
    }

    @Test fun invalidSessionsAreStoredButNotScored() {
        val s = BeatTheClock.finish("i", DeviceKind.PHONE, "p", AssessmentWindow.MORNING, "d", 0, 1, rounds(320, n = 5), emptyList())
        assertEquals(SessionStatus.INVALID, s.status)
        assertNull(s.speed)
        assertNull(s.cognitiveScore)
    }
}

class PeakWindowTest {
    private val now = 100 * D

    private fun s(day: Int, w: AssessmentWindow, speed: Double, device: DeviceKind = DeviceKind.PHONE) = CognitiveSession(
        "$day-$w-$device", device, "dev", w, "d$day", now - day * D, now - day * D + 60_000, SessionStatus.FULLY_VALID,
        speed, 1000 / speed, 0, 0, 50, 50, BeatTheClock.GAME_VERSION, emptyList(),
    )

    @Test fun notEnoughDataUntilEligible() {
        val fewDays = (1..4).flatMap { d -> AssessmentWindow.entries.map { s(d, it, 3.0) } }
        assertEquals(PeakResult.NOT_ENOUGH_DATA, PeakWindowPredictor.predict(fewDays, now).result, "4 days")
        val noEvening = (1..8).flatMap { d -> listOf(s(d, AssessmentWindow.MORNING, 3.4), s(d, AssessmentWindow.AFTERNOON, 3.0)) }
        assertEquals(PeakResult.NOT_ENOUGH_DATA, PeakWindowPredictor.predict(noEvening, now).result, "an untested part of the day could be the best")
    }

    @Test fun predictsClearMorningPeak() {
        val rnd = Random(3)
        val list = (1..20).flatMap { d ->
            AssessmentWindow.entries.map { w ->
                val base = when (w) { AssessmentWindow.MORNING -> 3.4; AssessmentWindow.AFTERNOON -> 3.0; AssessmentWindow.EVENING -> 2.9 }
                s(d, w, base + rnd.nextDouble(-0.05, 0.05))
            }
        }
        val p = PeakWindowPredictor.predict(list, now)
        assertEquals(PeakResult.PREDICTED, p.result)
        assertEquals(AssessmentWindow.MORNING, p.window)
        assertEquals(PeakWindowPrediction.MODEL_VERSION, p.modelVersion)
    }

    @Test fun noClearPeakWhenWindowsAreAlike() {
        val rnd = Random(5)
        val list = (1..20).flatMap { d -> AssessmentWindow.entries.map { w -> s(d, w, 3.0 + rnd.nextDouble(-0.2, 0.2)) } }
        assertEquals(PeakResult.NO_CLEAR_PEAK, PeakWindowPredictor.predict(list, now).result)
    }

    @Test fun oldSessionsAreIgnored() {
        val old = (40..60).flatMap { d -> AssessmentWindow.entries.map { s(d, it, 3.0) } }
        assertEquals(0, PeakWindowPredictor.predict(old, now).validSessionCount)
        val olderGame = (1..20).flatMap { d -> AssessmentWindow.entries.map { s(d, it, 3.0).copy(gameVersion = BeatTheClock.GAME_VERSION - 1) } }
        assertEquals(0, PeakWindowPredictor.predict(olderGame, now).validSessionCount)
    }

    @Test fun eligibleWithThreeTestsOnTwoDaysPerWindowOverFiveDays() {
        // 6 days, each part of the day tested on 3 of them: enough, and a clear morning peak shows.
        val list = (1..6).flatMap { d ->
            AssessmentWindow.entries.filter { (d + it.ordinal) % 2 == 0 }.map { w ->
                s(d, w, if (w == AssessmentWindow.MORNING) 3.4 else 2.9)
            }
        }
        val p = PeakWindowPredictor.predict(list, now)
        assertEquals(PeakResult.PREDICTED, p.result)
        assertEquals(AssessmentWindow.MORNING, p.window)
    }

    @Test fun lateNightBeforeTheUsualWakeIsEveningNotMorning() {
        val wake = 7 * H
        assertEquals(AssessmentWindow.EVENING, AssessmentWindow.at(wake, 1 * H), "01:00, still up from yesterday")
        assertEquals(AssessmentWindow.MORNING, AssessmentWindow.at(wake, 6 * H), "06:00, up early")
        assertEquals(AssessmentWindow.EVENING, AssessmentWindow.at(wake, 22 * H))
    }
}

class MomentsTest {
    private val now = 50 * D

    @Test fun constraints() {
        assertIs<MomentRules.Check.Ok>(MomentRules.check("Pitch", now + H, now, 4))
        assertIs<MomentRules.Check.Rejected>(MomentRules.check("Pitch", now + H, now, 5)) // sixth active
        assertIs<MomentRules.Check.Rejected>(MomentRules.check("Pitch", now - 1, now, 0)) // past
        assertIs<MomentRules.Check.Rejected>(MomentRules.check("Pitch", now + 7 * D + 1, now, 0)) // > 7 days
        assertIs<MomentRules.Check.Ok>(MomentRules.check("Pitch", now + 7 * D, now, 0))
        assertIs<MomentRules.Check.Rejected>(MomentRules.check("  ", now + H, now, 0))
    }

    @Test fun lifecycle() {
        assertEquals(MomentStatus.ACTIVE, MomentRules.statusAt(MomentStatus.ACTIVE, now + 1, now))
        assertEquals(MomentStatus.COMPLETED, MomentRules.statusAt(MomentStatus.ACTIVE, now, now))
        assertEquals(MomentStatus.CANCELLED, MomentRules.statusAt(MomentStatus.CANCELLED, now - D, now))
    }

    @Test fun urgencyFromOlderImportance() {
        assertEquals(Urgency.NORMAL, Urgency.fromStored("NORMAL"))
        assertEquals(Urgency.CRITICAL, Urgency.fromStored("HIGH"))
        assertEquals(Urgency.CRITICAL, Urgency.fromStored("CRITICAL"))
    }

    @Test fun phases() {
        assertEquals(PrepPhase.FOUNDATION, PrepPhase.of(6))
        assertEquals(PrepPhase.MAINTAIN, PrepPhase.of(3))
        assertEquals(PrepPhase.FINAL, PrepPhase.of(1))
        assertEquals(PrepPhase.DAY, PrepPhase.of(0))
        assertEquals(PrepPhase.DONE, PrepPhase.of(-1))
    }

    private fun ctx(days: Int, score: Int? = 75, urgency: Urgency = Urgency.NORMAL, sleep: Double? = 470.0, peak: PeakWindowPrediction? = null) = PrepContext(
        nowMs = now, daysUntil = days, eventStartMs = now + days * D + 3 * H, urgency = urgency,
        latestScore = score, latestConfidence = score?.let { ReadinessConfidence.HIGH }, latestCoverage = score?.let { 1.0 },
        sleepScore = 80, consistencyScore = 80, cardioScore = 70, activityScore = 70, lightScore = 70,
        recentSleepMinutes = sleep, sleepTargetMinutes = 480, peak = peak, eventDayWakeMs = now + days * D,
        clock = { ms -> "${(ms / H % 24).toString().padStart(2, '0')}:00" },
    )

    @Test fun preparationStatus() {
        val e = MomentPreparationEngine()
        assertEquals(PrepStatus.ON_TRACK, e.evaluate(ctx(3)).status)
        assertEquals(PrepStatus.NEEDS_ATTENTION, e.evaluate(ctx(3, score = 50)).status)
        assertEquals(PrepStatus.NEEDS_ATTENTION, e.evaluate(ctx(3, sleep = 330.0)).status)
        assertEquals(PrepStatus.INSUFFICIENT_DATA, e.evaluate(ctx(3, score = null)).status)
    }

    @Test fun recommendationsFollowTimeLeft() {
        val e = MomentPreparationEngine()
        assertTrue(e.recommendations(ctx(6)).any { it.id == "sleep_schedule" })
        assertTrue(e.recommendations(ctx(1)).any { it.id == "sleep_opportunity" })
        assertTrue(e.recommendations(ctx(1)).none { it.id == "sleep_schedule" })
        assertTrue(e.recommendations(ctx(0)).any { it.id == "walk" })
        // Nothing about the content of the Moment: only sleep, recovery, movement, breathing, light and the peak window.
        val allowed = setOf("sleep_schedule", "recovery", "move_sustainably", "morning_light", "keep_routine", "check_readiness", "sleep_opportunity", "no_exertion", "dim_evening", "no_alcohol", "caffeine_cut", "walk", "breathing", "peak_within", "peak_near", "peak_outside")
        (0..7).forEach { d -> assertTrue(e.recommendations(ctx(d)).all { it.id in allowed }, "day $d") }
        assertTrue(e.recommendations(ctx(-1)).isEmpty())
    }

    @Test fun bedtimeFollowsUsualWakeForLaterMoments() {
        // Moment at 14:00 tomorrow, usual wake 07:00, 8 h target: in bed by 22:45, not 04:15.
        val c = ctx(1).copy(eventStartMs = now + D + 14 * H, eventDayWakeMs = now + D + 7 * H)
        val t = MomentPreparationEngine().recommendations(c).first { it.id == "sleep_opportunity" }
        assertTrue(t.title.endsWith("22:00"), t.title)
    }

    @Test fun criticalRaisesPriority() {
        val e = MomentPreparationEngine()
        val n = e.recommendations(ctx(1)).first { it.id == "sleep_opportunity" }.priority
        val c = e.recommendations(ctx(1, urgency = Urgency.CRITICAL)).first { it.id == "sleep_opportunity" }.priority
        assertTrue(c < n)
    }

    @Test fun peakAlignment() {
        val e = MomentPreparationEngine()
        val peak = PeakWindowPrediction(PeakResult.PREDICTED, AssessmentWindow.MORNING, emptyList(), ReadinessConfidence.MEDIUM, 60, 20, 0)
        // Event 3 h after waking: inside the morning (0–4 h), within 90 min of the afternoon (4–8 h).
        assertEquals(PeakAlignment.WITHIN_PEAK, e.peakAlignment(ctx(0, peak = peak)))
        assertEquals(PeakAlignment.NEAR_PEAK, e.peakAlignment(ctx(0, peak = peak.copy(window = AssessmentWindow.AFTERNOON))))
        assertEquals(PeakAlignment.OUTSIDE_PEAK, e.peakAlignment(ctx(0, peak = peak.copy(window = AssessmentWindow.EVENING))))
        assertEquals(PeakAlignment.UNKNOWN, e.peakAlignment(ctx(0)))
        assertEquals(PeakAlignment.UNKNOWN, e.peakAlignment(ctx(0, peak = peak.copy(result = PeakResult.NO_CLEAR_PEAK))))
    }

    @Test fun consolidationMergesAndOrders() {
        val a = PrepTask("sleep_schedule", "A", "", priority = 20)
        val b = PrepTask("sleep_schedule", "A", "", priority = 10)
        val c = PrepTask("dim_evening", "C", "", priority = 45)
        val out = MomentPreparationEngine.consolidate(
            listOf(
                Triple("Normal later", Urgency.NORMAL, 10L to listOf(a, c)),
                Triple("Critical", Urgency.CRITICAL, 20L to listOf(b)),
            ),
        )
        assertEquals(2, out.size)
        val merged = out.first { it.first.id == "sleep_schedule" }
        assertEquals(10, merged.first.priority)
        assertEquals(listOf("Critical", "Normal later"), merged.second)
        assertTrue(out.map { it.first.priority }.zipWithNext().all { (x, y) -> x <= y })
    }
}

class ReactionRunTest {
    @Test fun aTestIsFortyFiveTrials() = kotlinx.coroutines.test.runTest {
        val taps = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val clock = { testScheduler.currentTime }
        var early = 0
        val rounds = ReactionRun.play("s", taps, clock, onPhase = { p, _, _ ->
            when (p) {
                // React in 300 ms; once, tap before Herc appears.
                TrialPhase.SHOW -> launch { delay(300); taps.trySend(clock()) }
                TrialPhase.WAIT -> if (early++ == 5) launch { delay(200); taps.trySend(clock()) }
                else -> Unit
            }
        }, random = Random(2))
        assertEquals(BeatTheClock.TRIALS, rounds.size)
        assertTrue(testScheduler.currentTime in 150_000L..210_000L, "about 3 minutes: ${testScheduler.currentTime}")
        assertEquals(1, rounds.count { it.validity == RoundValidity.FALSE_START })
        assertTrue(rounds.filter { it.validity == RoundValidity.VALID }.all { it.reactionMs == 300L })
        assertEquals(SessionStatus.FULLY_VALID, BeatTheClock.status(rounds))
    }
}

class ScreenSleepTest {
    private val h = 3_600_000L
    private fun off(t: Double) = ScreenSleep.Event((t * h).toLong(), ScreenSleep.Kind.OFF)
    private fun unlock(t: Double) = ScreenSleep.Event((t * h).toLong(), ScreenSleep.Kind.UNLOCK)

    @Test fun theNightRunsFromScreenOffToTheFirstUnlock() {
        val e = ScreenSleep.estimate(listOf(unlock(20.0), off(23.5), off(23.7), unlock(31.0), off(31.2)), 18 * h, 38 * h, 40 * h)!!
        assertEquals((23.5 * h).toLong(), e.startMs)
        assertEquals(31 * h, e.endMs)
        assertEquals(0, e.awakeMinutes)
    }

    @Test fun aBriefCheckAtNightDoesntSplitIt() {
        val e = ScreenSleep.estimate(listOf(off(23.0), unlock(27.0), off(27.1), unlock(31.0)), 18 * h, 38 * h, 40 * h)!!
        assertEquals(23 * h, e.startMs)
        assertEquals(31 * h, e.endMs)
        assertEquals(6, e.awakeMinutes)
    }

    @Test fun shortGapsAndUnfinishedNightsAreIgnored() {
        assertNull(ScreenSleep.estimate(listOf(off(23.0), unlock(25.0)), 18 * h, 38 * h, 40 * h), "2 h is not a night")
        assertNull(ScreenSleep.estimate(listOf(off(23.0), unlock(31.0)), 18 * h, 38 * h, 30 * h), "no unlock yet")
    }
}
