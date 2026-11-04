package com.hercules.shared

import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.readiness.ActivityInput
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.Availability
import com.hercules.shared.readiness.Baselines
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveInput
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.ConfigurationType
import com.hercules.shared.readiness.DayInputs
import com.hercules.shared.readiness.DefaultScoring
import com.hercules.shared.readiness.StrainInput
import com.hercules.shared.readiness.confidenceBasis
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.DimensionScorer
import com.hercules.shared.readiness.LightInput
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
import com.hercules.shared.readiness.ScoringConfiguration
import com.hercules.shared.readiness.SessionStatus
import com.hercules.shared.readiness.Stats
import com.hercules.shared.readiness.Urgency
import com.hercules.shared.readiness.WeightValidation
import com.hercules.shared.readiness.confidenceFor
import com.hercules.shared.readiness.validateWeights
import kotlin.math.abs
import kotlin.random.Random
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
    private val scorer = DimensionScorer()

    private fun night(day: Int, asleep: Int = 480, awake: Int? = 30, mid: Double = 180.0) =
        NightInput("d$day", 0, 1, asleep, awake, mid)

    private fun full(
        night: NightInput? = night(0),
        hrv: Double? = 45.0,
        rhr: Double? = 58.0,
        activity: ActivityInput? = ActivityInput(8000, 30, 3, 14),
        light: LightInput? = LightInput(listOf(3000.0, 2500.0), listOf(500.0, 400.0), listOf(20.0, 15.0)),
        cognitive: CognitiveInput? = CognitiveInput(60, 55, 80, "Phone · Morning"),
    ) = DayInputs(
        "d0", night, (1..13).map { night(-it, mid = 180.0 + (it % 3) * 10) },
        hrv, List(25) { 44.0 + it % 5 }, rhr, List(25) { 57.0 + it % 3 }, List(25) { 8000.0 + it * 10 }, activity, light, cognitive,
    )

    @Test fun classificationBoundaries() {
        assertEquals(ReadinessBand.PRIMA, ReadinessBand.of(100))
        assertEquals(ReadinessBand.PRIMA, ReadinessBand.of(85))
        assertEquals(ReadinessBand.BAIK, ReadinessBand.of(84))
        assertEquals(ReadinessBand.BAIK, ReadinessBand.of(70))
        assertEquals(ReadinessBand.CUKUP, ReadinessBand.of(69))
        assertEquals(ReadinessBand.CUKUP, ReadinessBand.of(55))
        assertEquals(ReadinessBand.RENDAH, ReadinessBand.of(54))
        assertEquals(ReadinessBand.RENDAH, ReadinessBand.of(40))
        assertEquals(ReadinessBand.TERKURAS, ReadinessBand.of(39))
        assertEquals(ReadinessBand.TERKURAS, ReadinessBand.of(0))
    }

    @Test fun allDimensionsAvailableGivesFullCoverageAndHighConfidence() {
        val c = calc.calculate(full(), DefaultScoring.DEFAULT)
        assertNotNull(c.score)
        assertEquals(1.0, c.coverage, 1e-9)
        assertEquals(ReadinessConfidence.HIGH, c.confidence)
        assertEquals("default-v2", c.stamp.configId)
        assertTrue(c.dimensions.all { it.availability == Availability.AVAILABLE })
    }

    @Test fun weightedMeanOverAvailableDimensions() {
        val c = calc.calculate(full(), DefaultScoring.DEFAULT)
        val expected = c.dimensions.sumOf { it.weight * it.score!! } / c.dimensions.sumOf { it.weight }
        assertEquals(kotlin.math.round(expected).toInt(), c.score)
    }

    @Test fun missingCognitiveRenormalizesAndLowersCoverage() {
        val c = calc.calculate(full(cognitive = null), DefaultScoring.DEFAULT)
        assertEquals(0.80, c.coverage, 1e-9)
        assertEquals(ReadinessConfidence.MEDIUM, c.confidence)
        val avail = c.dimensions.filter { it.available }
        val expected = avail.sumOf { it.weight * it.score!! } / avail.sumOf { it.weight }
        assertEquals(kotlin.math.round(expected).toInt(), c.score)
        assertEquals(Availability.NO_DATA, c.dimensions.first { it.dimension == Dimension.COGNITIVE }.availability)
    }

    @Test fun noSleepStillScoresFromOtherSignals() {
        val c = calc.calculate(full(night = null), DefaultScoring.DEFAULT)
        assertNotNull(c.score)
        assertNull(c.insufficientReason)
    }

    @Test fun lowCoverageScoresWithLowConfidenceAndANote() {
        // Sleep 40 + activity 10 + light 10 = 60%.
        val c = calc.calculate(full(hrv = null, rhr = null, cognitive = null), DefaultScoring.DEFAULT)
        assertEquals(0.60, c.coverage, 1e-9)
        assertNotNull(c.score)
        assertEquals(ReadinessConfidence.LOW, c.confidence)
        assertEquals(3, c.signalsIn)
        assertEquals(5, c.signalsTotal)
        assertEquals("To raise it: add overnight heart data and a Beat the Clock game.", c.confidenceHint)
    }

    @Test fun oneReadingIsEnoughForAScore() {
        val only = DayInputs("d0", null, emptyList(), null, emptyList(), null, emptyList(), emptyList(), null, null, CognitiveInput(60, 55, 80, "Phone"))
        val c = calc.calculate(only, DefaultScoring.DEFAULT)
        assertNotNull(c.score)
        assertEquals(ReadinessConfidence.LOW, c.confidence)
        assertEquals(0, c.historyDays)
        assertEquals("1 of 5 signals · first day of data", c.confidenceBasis)
        assertTrue(c.confidenceHint!!.contains("3 more days"))
    }

    @Test fun confidenceGrowsWithSignalsAndDays() {
        fun withDays(n: Int) = full().let { f ->
            f.copy(previousNights = f.previousNights.take(n), hrvHistory = f.hrvHistory.take(n), restingHrHistory = f.restingHrHistory.take(n), stepsHistory = f.stepsHistory.take(maxOf(n - 1, 0)))
        }
        // Steps only: two earlier days plus yesterday's activity make three full days.
        val stepsOnly = DayInputs("d0", null, emptyList(), null, emptyList(), null, emptyList(), listOf(8000.0, 9000.0), ActivityInput(8000, 30, 3, 14), null, null)
        assertEquals(3, calc.calculate(stepsOnly, DefaultScoring.DEFAULT).historyDays)
        val two = calc.calculate(withDays(2), DefaultScoring.DEFAULT)
        assertEquals(ReadinessConfidence.LOW, two.confidence)
        assertEquals("To raise it: 1 more day of data.", two.confidenceHint)
        val three = calc.calculate(withDays(3), DefaultScoring.DEFAULT)
        assertEquals(ReadinessConfidence.MEDIUM, three.confidence)
        assertEquals("To raise it: 4 more days of data.", three.confidenceHint)
        val week = calc.calculate(withDays(7), DefaultScoring.DEFAULT)
        assertEquals(ReadinessConfidence.HIGH, week.confidence)
        assertNull(week.confidenceHint)
        assertEquals("5 of 5 signals · 7 days of data", week.confidenceBasis)
    }

    @Test fun extrasOnlySubtractAndAreCapped() {
        val base = calc.calculate(full(), DefaultScoring.DEFAULT)
        val strain = StrainInput(
            respiratoryRate = 19.0, respiratoryRateHistory = List(14) { 14.0 + it % 2 * 0.4 },
            skinTempDelta = 1.2, skinTempHistory = List(14) { 0.0 + it % 2 * 0.1 },
            spo2 = 88.0, spo2History = List(14) { 96.0 },
        )
        val hit = calc.calculate(full().copy(strain = strain), DefaultScoring.DEFAULT)
        assertEquals(-10, hit.modifiers.sumOf { it.points })
        assertEquals(base.score!! - 10, hit.score)
        val calm = StrainInput(14.2, List(14) { 14.0 + it % 2 * 0.4 }, 0.05, List(14) { it % 2 * 0.1 }, 96.0, List(14) { 96.0 })
        assertEquals(base.score, calc.calculate(full().copy(strain = calm), DefaultScoring.DEFAULT).score)
    }

    @Test fun glucoseLowsSubtract() {
        val base = calc.calculate(full(), DefaultScoring.DEFAULT).score!!
        fun with(readings: List<Double>) = calc.calculate(full().copy(strain = StrainInput(glucose24h = readings)), DefaultScoring.DEFAULT).score!!
        // Meter: one finger-prick low counts.
        assertEquals(base, with(listOf(5.2)))
        assertEquals(base - 5, with(listOf(3.6)))
        assertEquals(base - 10, with(listOf(2.8)))
        // CGM (288 readings a day): one dip is ignored, ~15 minutes low counts.
        val normal = List(285) { 5.5 }
        assertEquals(base, with(normal + listOf(2.8, 5.4, 5.6)))
        assertEquals(base - 10, with(normal + listOf(2.9, 2.8, 2.9)))
        assertEquals(base - 5, with(normal + listOf(3.5, 3.6, 3.4)))
    }

    @Test fun nothingAtAllMeansNoScore() {
        val none = DayInputs("d0", null, emptyList(), null, emptyList(), null, emptyList(), emptyList(), null, null, null)
        val c = calc.calculate(none, DefaultScoring.DEFAULT)
        assertNull(c.score)
        assertEquals(ReadinessConfidence.INSUFFICIENT, c.confidence)
        assertNotNull(c.insufficientReason)
    }

    @Test fun confidenceThresholds() {
        assertEquals(ReadinessConfidence.HIGH, confidenceFor(0.90, 7))
        assertEquals(ReadinessConfidence.MEDIUM, confidenceFor(0.90, 6))
        assertEquals(ReadinessConfidence.MEDIUM, confidenceFor(0.89, 30))
        assertEquals(ReadinessConfidence.MEDIUM, confidenceFor(0.70, 3))
        assertEquals(ReadinessConfidence.LOW, confidenceFor(0.69, 30))
        assertEquals(ReadinessConfidence.LOW, confidenceFor(0.95, 2))
        assertEquals(ReadinessConfidence.LOW, confidenceFor(0.20, 0))
    }

    @Test fun weightValidationRejectsInvalidAndNeverNormalizes() {
        val d = DefaultScoring.DEFAULT.weights
        assertIs<WeightValidation.Valid>(validateWeights(d))
        assertIs<WeightValidation.Invalid>(validateWeights(d + (Dimension.SLEEP to 0.45))) // 105%
        assertIs<WeightValidation.Invalid>(validateWeights(d + (Dimension.SLEEP to -0.05)))
        assertIs<WeightValidation.Invalid>(validateWeights(Dimension.entries.associateWith { 0.0 }))
        assertIs<WeightValidation.Invalid>(validateWeights(d - Dimension.CARDIO))
        assertIs<WeightValidation.Valid>(validateWeights(mapOf(Dimension.SLEEP to 0.5, Dimension.CARDIO to 0.5, Dimension.ACTIVITY to 0.0, Dimension.CIRCADIAN to 0.0, Dimension.COGNITIVE to 0.0)))
    }

    @Test fun zeroWeightDimensionIsExcludedAndMarked() {
        val w = mapOf(Dimension.SLEEP to 0.45, Dimension.CARDIO to 0.25, Dimension.ACTIVITY to 0.10, Dimension.CIRCADIAN to 0.0, Dimension.COGNITIVE to 0.20)
        val config = ScoringConfiguration.custom("c1", w, 5)
        val c = calc.calculate(full(), config)
        assertEquals(Availability.ZERO_WEIGHT, c.dimensions.first { it.dimension == Dimension.CIRCADIAN }.availability)
        assertEquals(1.0, c.coverage, 1e-9)
        assertEquals(ConfigurationType.CUSTOM, c.stamp.configType)
        assertTrue(config.differsFromDefault)
        assertTrue(!DefaultScoring.DEFAULT.differsFromDefault)
    }

    @Test fun reproducibleWithSameInputsAndConfig() {
        assertEquals(calc.calculate(full(), DefaultScoring.DEFAULT), calc.calculate(full(), DefaultScoring.DEFAULT))
    }

    @Test fun sleepDurationCurve() {
        assertEquals(100, scorer.sleepDurationScore(8.0))
        assertTrue(scorer.sleepDurationScore(6.0) < scorer.sleepDurationScore(7.0))
        assertTrue(scorer.sleepDurationScore(4.0) < 40)
    }

    @Test fun continuityNeedsAwakeTime() {
        val (_, metrics) = scorer.sleep(full(night = night(0, awake = null)))
        assertEquals(Availability.NO_DATA, metrics.first { it.key.name == "SLEEP_CONTINUITY" }.availability)
    }

    @Test fun lightNeedsTwoPeriods() {
        assertNull(scorer.circadian(full(light = LightInput(listOf(3000.0, 2000.0), emptyList(), emptyList()))).first)
        assertNotNull(scorer.circadian(full(light = LightInput(listOf(3000.0, 2000.0), emptyList(), listOf(10.0, 12.0)))).first)
    }

    @Test fun lowMovementNeedsReliableWear() {
        val (_, m) = scorer.activity(full(activity = ActivityInput(8000, 20, 2, 4)))
        assertEquals(Availability.NOT_RELIABLE, m.first { it.key.name == "LOW_MOVEMENT" }.availability)
    }

    @Test fun outOfRangeHrvIsNotReliable() {
        val (score, m) = scorer.cardio(full(hrv = 900.0))
        assertEquals(Availability.NOT_RELIABLE, m.first { it.key.name == "HRV" }.availability)
        assertNotNull(score) // resting HR still scores
    }
}

class BaselineTest {
    @Test fun personalShareGrowsFromSevenDays() {
        assertEquals(0.0, Baselines.personalShare(6))
        assertTrue(Baselines.personalShare(7) > 0.0)
        assertTrue(Baselines.personalShare(14) < Baselines.personalShare(20))
        assertEquals(0.8, Baselines.personalShare(21))
        assertEquals(0.8, Baselines.personalShare(60))
    }

    @Test fun referenceOnlyBeforeSevenDays() {
        val b = Baselines.blend(listOf(80.0, 82.0), 42.0 to 12.0)
        assertEquals(42.0, b.median)
        assertEquals(0.0, b.personalShare)
    }

    @Test fun madZeroUsesMinimumScale() {
        assertEquals(0.0, Stats.mad(List(10) { 50.0 }))
        val z = Stats.robustZ(54.0, 50.0, 0.0, minScale = 4.0)
        assertEquals(1.0, z, 1e-9)
    }

    @Test fun medianAndMadResistOutliers() {
        val v = listOf(50.0, 51.0, 49.0, 50.0, 52.0, 48.0, 500.0)
        assertEquals(50.0, Stats.median(v))
        assertTrue(Stats.mad(v)!! <= 2.0)
    }
}

class BeatTheClockTest {
    private fun r(i: Int, target: Long, actual: Long, v: RoundValidity = RoundValidity.VALID) = CognitiveRound("r$i", "s", i, target, actual, v)

    private val targets = listOf(15_000L, 22_000L, 28_000L)
    private fun rounds(err: Double) = targets
        .mapIndexed { i, t -> r(i, t, (t * (1 + if (i % 2 == 0) err else -err * 0.5)).toLong()) }

    private fun session(id: String, device: DeviceKind, window: AssessmentWindow, startMs: Long, err: Double, history: List<CognitiveSession>) =
        BeatTheClock.finish(id, device, "dev", window, "d", startMs, startMs + 60_000, rounds(err), history)

    @Test fun planIsThreeRoundsGettingLongerBetween15And30Seconds() {
        repeat(200) { seed ->
            val p = BeatTheClock.plan(Random(seed))
            assertEquals(3, p.size)
            assertTrue(p.all { it in 15_000L..30_000L && it % 1_000L == 0L })
            assertTrue(p[0] < p[1] && p[1] < p[2], "rounds must get longer: $p")
        }
        // Goals vary between games so they can't be memorised.
        assertTrue((0 until 20).map { BeatTheClock.plan(Random(it)) }.distinct().size > 1)
    }

    @Test fun roundValidity() {
        assertEquals(RoundValidity.VALID, BeatTheClock.round("a", "s", 0, 5_000, 0, 5_200_000_000, false).validity)
        assertEquals(RoundValidity.INTERRUPTED, BeatTheClock.round("a", "s", 0, 5_000, 0, 5_200_000_000, true).validity)
        assertEquals(RoundValidity.IMPLAUSIBLE, BeatTheClock.round("a", "s", 0, 5_000, 0, 900_000_000, false).validity) // < 0.2×
        assertEquals(RoundValidity.IMPLAUSIBLE, BeatTheClock.round("a", "s", 0, 5_000, 0, 21_000_000_000, false).validity) // > 4×
    }

    @Test fun sessionValidity() {
        val all = rounds(0.05)
        assertEquals(SessionStatus.FULLY_VALID, BeatTheClock.status(all))
        val two = all.mapIndexed { i, x -> if (i == 0) x.copy(validity = RoundValidity.INTERRUPTED) else x }
        assertEquals(SessionStatus.PARTIALLY_VALID, BeatTheClock.status(two))
        // Only one round counted → invalid.
        val one = all.mapIndexed { i, x -> if (i < 2) x.copy(validity = RoundValidity.INTERRUPTED) else x }
        assertEquals(SessionStatus.INVALID, BeatTheClock.status(one))
    }

    @Test fun olderGameVersionsNeverFormTheBaseline() {
        val old = (1..3).map { session("o$it", DeviceKind.PHONE, AssessmentWindow.MORNING, (10 + it) * D, 0.03, emptyList()).copy(gameVersion = 1) }
        // Old games are ignored: the result equals a first game scored against the reference alone.
        val withOld = session("x", DeviceKind.PHONE, AssessmentWindow.MORNING, 20 * D, 0.08, old)
        val fresh = session("x", DeviceKind.PHONE, AssessmentWindow.MORNING, 20 * D, 0.08, emptyList())
        assertEquals(fresh.cognitiveScore, withOld.cognitiveScore)
    }

    @Test fun earlyAndLateErrorsGiveSignedBias() {
        val early = targets.mapIndexed { i, t -> r(i, t, (t * 0.9).toLong()) }
        val s = BeatTheClock.finish("e", DeviceKind.PHONE, "p", null, "d", 0, 1, early, emptyList())
        assertTrue(s.bias!! < 0)
        assertEquals(0.1, s.accuracyError!!, 1e-3)
        val late = early.map { it.copy(actualMs = (it.targetMs * 1.1).toLong()) }
        assertTrue(BeatTheClock.finish("l", DeviceKind.PHONE, "p", null, "d", 0, 1, late, emptyList()).bias!! > 0)
    }

    @Test fun ratioAndBiasFormulas() {
        assertEquals(50, BeatTheClock.ratioScore(0.1, 0.1))
        assertEquals(70, BeatTheClock.ratioScore(0.05, 0.1)) // half the usual error
        assertEquals(30, BeatTheClock.ratioScore(0.2, 0.1))
        assertEquals(100, BeatTheClock.ratioScore(0.0, 0.1)) // floor 0.125 → 50 + 60, clamped
        assertEquals(100, BeatTheClock.biasScore(0.0, 0.1))
        assertEquals(0, BeatTheClock.biasScore(0.1, 0.1))
    }

    @Test fun theVeryFirstGameIsScoredAgainstTheReference() {
        val s = session("p", DeviceKind.PHONE, AssessmentWindow.MORNING, 10 * D, 0.05, emptyList())
        assertFalse(s.practice)
        assertNotNull(s.cognitiveScore)
        assertEquals(BeatTheClock.ratioScore(s.accuracyError!!, BeatTheClock.REFERENCE_ACCURACY_ERROR), s.accuracyScore)
    }

    @Test fun baselineTurnsPersonalAfterThreeSameDeviceGames() {
        val hist = (1..3).map { session("h$it", DeviceKind.WATCH, AssessmentWindow.MORNING, (10 + it) * D, 0.08, emptyList()) }
        // Phone has no history of its own: watch sessions are never mixed in, so it uses the reference.
        val phone = session("x", DeviceKind.PHONE, AssessmentWindow.MORNING, 20 * D, 0.08, hist)
        assertEquals(BeatTheClock.ratioScore(phone.accuracyError!!, BeatTheClock.REFERENCE_ACCURACY_ERROR), phone.accuracyScore)
        // Same device, any time of day: the baseline covers every run, so an evening run scores too.
        assertNotNull(session("y", DeviceKind.WATCH, AssessmentWindow.EVENING, 20 * D, 0.08, hist).cognitiveScore)
        val watch = session("z", DeviceKind.WATCH, AssessmentWindow.MORNING, 20 * D, 0.08, hist)
        assertNotNull(watch.cognitiveScore)
        assertEquals(50, watch.accuracyScore)
    }

    @Test fun invalidSessionsAreStoredButNotScored() {
        val bad = rounds(0.05).map { it.copy(validity = RoundValidity.INTERRUPTED) }
        val s = BeatTheClock.finish("i", DeviceKind.PHONE, "p", AssessmentWindow.MORNING, "d", 0, 1, bad, emptyList())
        assertEquals(SessionStatus.INVALID, s.status)
        assertNull(s.accuracyError)
        assertNull(s.cognitiveScore)
    }
}

class PeakWindowTest {
    private val now = 100 * D

    private fun s(day: Int, w: AssessmentWindow, err: Double, device: DeviceKind = DeviceKind.PHONE) = CognitiveSession(
        "$day-$w-$device", device, "dev", w, "d$day", now - day * D, now - day * D + 60_000, SessionStatus.FULLY_VALID, false,
        err, 0.02, 0.0, 50, 50, 50, 50, 1, emptyList(),
    )

    @Test fun notEnoughDataUntilEligible() {
        val few = (1..10).flatMap { d -> AssessmentWindow.entries.map { s(d, it, 0.1) } }
        assertEquals(PeakResult.NOT_ENOUGH_DATA, PeakWindowPredictor.predict(few, now).result)
    }

    @Test fun predictsClearMorningPeak() {
        val rnd = Random(3)
        val list = (1..20).flatMap { d ->
            AssessmentWindow.entries.map { w ->
                val base = when (w) { AssessmentWindow.MORNING -> 0.05; AssessmentWindow.AFTERNOON -> 0.10; AssessmentWindow.EVENING -> 0.11 }
                s(d, w, base + rnd.nextDouble(-0.01, 0.01))
            }
        }
        val p = PeakWindowPredictor.predict(list, now)
        assertEquals(PeakResult.PREDICTED, p.result)
        assertEquals(AssessmentWindow.MORNING, p.window)
        assertEquals(PeakWindowPrediction.MODEL_VERSION, p.modelVersion)
    }

    @Test fun noClearPeakWhenWindowsAreAlike() {
        val rnd = Random(5)
        val list = (1..20).flatMap { d -> AssessmentWindow.entries.map { w -> s(d, w, 0.08 + rnd.nextDouble(-0.02, 0.02)) } }
        assertEquals(PeakResult.NO_CLEAR_PEAK, PeakWindowPredictor.predict(list, now).result)
    }

    @Test fun practiceAndOldSessionsAreIgnored() {
        val list = (1..20).flatMap { d -> AssessmentWindow.entries.map { s(d, it, 0.1).copy(practice = true) } }
        assertEquals(0, PeakWindowPredictor.predict(list, now).validSessionCount)
        val old = (40..60).flatMap { d -> AssessmentWindow.entries.map { s(d, it, 0.1) } }
        assertEquals(0, PeakWindowPredictor.predict(old, now).validSessionCount)
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
        assertTrue(e.recommendations(ctx(0)).any { it.id == "no_rush" })
        assertTrue(e.recommendations(ctx(-1)).isEmpty())
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

    @Test fun roundingGuard() {
        assertTrue(abs(DefaultScoring.DEFAULT.weights.values.sum() - 1.0) < 1e-9)
    }
}
