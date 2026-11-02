package com.hercules.shared

import com.hercules.shared.cognitive.ActionKind
import com.hercules.shared.cognitive.Baseline
import com.hercules.shared.cognitive.CheckIn
import com.hercules.shared.cognitive.Coach
import com.hercules.shared.cognitive.DecisionAdvice
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.EventType
import com.hercules.shared.cognitive.Importance
import com.hercules.shared.cognitive.Level
import com.hercules.shared.cognitive.NudgeKind
import com.hercules.shared.cognitive.NudgePlanner
import com.hercules.shared.cognitive.NudgePrefs
import com.hercules.shared.cognitive.NudgeRouting
import com.hercules.shared.cognitive.PlanEvent
import com.hercules.shared.cognitive.PrepPhase
import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.ReadinessEngine
import com.hercules.shared.cognitive.ReadinessInput
import com.hercules.shared.cognitive.SleepNight
import com.hercules.shared.cognitive.Verdict
import com.hercules.shared.cognitive.learningNote
import com.hercules.shared.cognitive.level
import com.hercules.shared.insights.DayKeys
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoachTest {
    private val dk = DayKeys(TimeZone.UTC)
    private val engine = ReadinessEngine(dk)
    private val coach = Coach(dk, engine)
    private val H = 3_600_000L
    private val day = dk.startOf("2026-11-10")

    private fun night(hours: Double, hrAvg: Double = 55.0): SleepNight {
        val wake = day + 7 * H
        return SleepNight("2026-11-10", wake - (hours * H).toLong(), wake, (hours * 60).toInt(), hrAvg, hrAvg)
    }

    private fun readiness(hours: Double = 8.0, hr: Double = 55.0, checkIn: CheckIn? = null, days: Int = 10): Readiness =
        engine.compute(
            ReadinessInput("2026-11-10", day + 8 * H, night(hours, hr), checkIn, null, null, Baseline(days, 450.0, 55.0, 58.0, 8000.0, 420)),
        )!!

    private fun event(id: String, startH: Double, daysUntil: Int = 0, tasks: Int = 3, done: Int = 0, dayOffset: Int = 0): EventStatus {
        val ev = PlanEvent(id, "Interview", EventType.INTERVIEW, day + dayOffset * 24 * H + (startH * H).toLong(), Importance.HIGH, "", day)
        val advice = DecisionAdvice(Verdict.GO, 80, "h", emptyList(), emptyList())
        return EventStatus(
            ev, daysUntil, if (daysUntil == 0) PrepPhase.DAY else PrepPhase.EVE,
            (1..tasks).map { com.hercules.shared.cognitive.PrepTask("t$it", "task $it", "") }, (1..done).map { "t$it" }.toSet(),
            70, 75, emptyList(), advice.takeIf { daysUntil == 0 },
        )
    }

    @Test fun bandsMapToThreeLevels() {
        assertEquals(Level.GOOD, readiness(8.0).band.level)
        assertEquals(Level.LOW, readiness(4.0, hr = 66.0).band.level)
    }

    @Test fun learningNoteCountsDownInPlainWords() {
        assertEquals("Learning your patterns. 3 more days.", readiness(days = 4).learningNote)
        assertEquals("Learning your patterns. 1 more day.", readiness(days = 6).learningNote)
        assertNull(readiness(days = 9).learningNote)
    }

    @Test fun reasonNamesTheWeakestPartInEverydayWords() {
        val r = readiness(5.0)
        val reason = assertNotNull(coach.reason(r, null))
        assertTrue(!reason.good && reason.text.startsWith("Short sleep"), reason.text)
        val hot = assertNotNull(coach.reason(readiness(8.0, hr = 64.0), null))
        assertEquals("Sleeping heart rate is higher than usual.", hot.text)
    }

    @Test fun morningWithoutCheckInAsksForOne() {
        val a = coach.action(day + 8 * H, readiness(), null, emptyList(), emptySet(), null).also { assertEquals(ActionKind.CHECK_IN, it.kind) }
        assertEquals("Check in", a.title)
    }

    @Test fun eventSoonBeatsEverythingElseOnceCheckedIn() {
        val ci = CheckIn("2026-11-10", null, 4, 2, day)
        val e = event("e1", 10.0, tasks = 4, done = 1)
        val a = coach.action(day + 8 * H, readiness(), ci, listOf(e), emptySet(), null)
        assertEquals(ActionKind.PREPARE, a.kind)
        assertEquals("e1", a.eventId)
        assertTrue(a.detail!!.contains("3 steps left"), a.detail)
    }

    @Test fun afterAnEventItAsksHowItWentOnlyOnce() {
        val ci = CheckIn("2026-11-10", null, 4, 2, day)
        val e = event("e1", 9.0)
        val now = day + 11 * H
        assertEquals(ActionKind.REFLECT, coach.action(now, readiness(), ci, listOf(e), emptySet(), null).kind)
        assertTrue(coach.action(now, readiness(), ci, listOf(e), setOf("e1"), null).kind != ActionKind.REFLECT)
    }

    @Test fun eveningRecommendsBedtimeBeforeTomorrowsEvent() {
        val r = readiness(6.0)
        val tomorrow = event("e2", 9.0, daysUntil = 1, dayOffset = 1)
        val advice = coach.sleepAdvice(day + 20 * H, r, night(6.0), 420, listOf(tomorrow))
        // wake = 9:00 - 2.5h = 6:30; need 8h (short last night) + 15 min to fall asleep → 22:15
        assertEquals(day + 22 * H + 15 * 60_000L, advice.bedtimeMs)
        assertEquals("Tomorrow: Interview at 09:00.", advice.tomorrowNote)
        assertTrue(advice.ifTarget!! > advice.ifShort!!, "more sleep should project a higher score")
        val a = coach.action(day + 20 * H, r, null, listOf(tomorrow), emptySet(), advice)
        assertEquals(ActionKind.SLEEP, a.kind)
    }

    @Test fun nudgesFireOnceInTheirWindowAndRespectPreferences() {
        val planner = NudgePlanner(dk, coach)
        val r = readiness()
        val now = r.wakeMs + 50 * 60_000L
        val due = planner.due(now, r, null, night(8.0), emptyList(), emptySet(), 420, NudgePrefs(), emptySet())
        assertEquals(listOf(NudgeKind.MORNING), due.map { it.kind })
        assertTrue(due.single().text.contains("Quick check-in?"))
        assertTrue(planner.due(now, r, null, night(8.0), emptyList(), emptySet(), 420, NudgePrefs(), setOf("morning:2026-11-10")).isEmpty())
        assertTrue(planner.due(now, r, null, night(8.0), emptyList(), emptySet(), 420, NudgePrefs(morning = false), emptySet()).isEmpty())
        assertTrue(planner.due(r.wakeMs + 6 * H, r, null, night(8.0), emptyList(), emptySet(), 420, NudgePrefs(), emptySet()).none { it.kind == NudgeKind.MORNING }, "expired")
    }

    @Test fun onlyOneDeviceDelivers() {
        val now = 10 * H
        for (lastHrAgoMin in listOf(5, 30, 59, 61, 74, 76, 200)) {
            val last = now - lastHrAgoMin * 60_000L
            val watch = NudgeRouting.watchShouldPost(last, now)
            val phone = NudgeRouting.phoneShouldPost(last, now)
            assertTrue(!(watch && phone), "both would post at ${lastHrAgoMin}m")
        }
        assertTrue(NudgeRouting.phoneShouldPost(null, now))
        assertTrue(!NudgeRouting.watchShouldPost(null, now))
    }
}
