package com.hercules.shared

import com.hercules.shared.cognitive.ActionKind
import com.hercules.shared.cognitive.Coach
import com.hercules.shared.cognitive.Level
import com.hercules.shared.cognitive.NudgeKind
import com.hercules.shared.cognitive.NudgePlanner
import com.hercules.shared.cognitive.NudgePrefs
import com.hercules.shared.cognitive.NudgeRouting
import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.learningNote
import com.hercules.shared.cognitive.level
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.CognitiveInput
import com.hercules.shared.readiness.DayInputs
import com.hercules.shared.readiness.DefaultScoring
import com.hercules.shared.readiness.NightInput
import com.hercules.shared.readiness.PeakResult
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.ReadinessCalculator
import com.hercules.shared.readiness.ReadinessConfidence
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoachTest {
    private val dk = DayKeys(TimeZone.UTC)
    private val coach = Coach(dk)
    private val day = "2026-11-10"
    private val midnight = dk.startOf(day)
    private val wake = midnight + 7 * 3_600_000L

    private fun readiness(baselineDays: Int = 20): Readiness {
        val calc = ReadinessCalculator().calculate(
            DayInputs(day, NightInput(day, 0, 1, 470, 20, 180.0), emptyList(), 45.0, List(20) { 45.0 }, 58.0, List(20) { 58.0 }, emptyList(), null, null, CognitiveInput(60, 60, 60, "Phone")),
            DefaultScoring.DEFAULT,
        )
        val s = calc.score!!
        return Readiness(day, s, ReadinessBand.of(s), calc, baselineDays, wake, emptyList(), wake)
    }

    @Test fun levels() {
        assertEquals(Level.GOOD, ReadinessBand.PRIMA.level)
        assertEquals(Level.MODERATE, ReadinessBand.CUKUP.level)
        assertEquals(Level.LOW, ReadinessBand.TERKURAS.level)
    }

    @Test fun learningNoteDuringFirstWeek() {
        assertNotNull(readiness(3).learningNote)
        assertNull(readiness(7).learningNote)
    }

    @Test fun beatTheClockIsOfferedOnceUntilPlayed() {
        val now = wake + 75 * 60_000L
        val b = coach.brief(now, readiness(), null, emptyList(), emptySet(), null, wake, null, playsToday = 0)
        assertEquals(ActionKind.ASSESS, b.action.kind)
        assertEquals(0, b.assess?.playsToday)
        val played = coach.brief(now, readiness(), null, emptyList(), emptySet(), null, wake, null, playsToday = 1)
        assertTrue(played.action.kind != ActionKind.ASSESS) // optional: never pushed again once played
    }

    @Test fun peakShownOnlyWhenPredicted() {
        val now = wake + 3 * 3_600_000L
        val peak = PeakWindowPrediction(PeakResult.PREDICTED, AssessmentWindow.AFTERNOON, emptyList(), ReadinessConfidence.MEDIUM, 60, 20, 0)
        val b = coach.brief(now, readiness(), null, emptyList(), emptySet(), null, wake, peak, 3)
        assertEquals(AssessmentWindow.AFTERNOON, b.peak?.window)
        val none = coach.brief(now, readiness(), null, emptyList(), emptySet(), null, wake, peak.copy(result = PeakResult.NO_CLEAR_PEAK, window = null), 0)
        assertNull(none.peak)
    }

    @Test fun insufficientDataHeadline() {
        val b = coach.brief(wake, null, null, emptyList(), emptySet(), null, wake, null, 0, insufficientReason = "Needs sleep.")
        assertEquals("Needs sleep.", b.headline)
        assertNull(b.level)
    }

    @Test fun assessReminderOncePerDayUntilPlayed() {
        val planner = NudgePlanner(dk, coach)
        val at = wake + NudgePlanner.ASSESS_AFTER_WAKE_MS + 60_000
        val prefs = NudgePrefs(morning = false, evening = false)
        val due = planner.due(at, readiness(), null, emptyList(), emptySet(), null, prefs, emptySet(), wake, 0, null)
        assertEquals(1, due.count { it.kind == NudgeKind.ASSESS })
        val done = planner.due(at, readiness(), null, emptyList(), emptySet(), null, prefs, emptySet(), wake, 1, null)
        assertTrue(done.none { it.kind == NudgeKind.ASSESS })
    }

    @Test fun routing() {
        assertTrue(NudgeRouting.watchShouldPost(1_000, 1_000 + 30 * 60_000))
        assertTrue(!NudgeRouting.watchShouldPost(null, 1_000))
        assertTrue(NudgeRouting.phoneShouldPost(null, 1_000))
    }
}
