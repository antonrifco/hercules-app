package com.hercules.shared

import com.hercules.shared.cognitive.Readiness
import com.hercules.shared.cognitive.ReadinessBand
import com.hercules.shared.cognitive.ScoreCheck
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveInput
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.DayInputs
import com.hercules.shared.readiness.DefaultScoring
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.NightInput
import com.hercules.shared.readiness.ReadinessCalculator
import com.hercules.shared.readiness.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreCheckTest {
    private fun day(i: Int) = "2026-10-%02d".format(i + 1)

    private fun readiness(dayKey: String, asleepMinutes: Int, game: Int): Readiness {
        val calc = ReadinessCalculator().calculate(
            DayInputs(dayKey, NightInput(dayKey, 0, 1, asleepMinutes, 20, 180.0), emptyList(), 45.0, List(20) { 45.0 }, 58.0, List(20) { 58.0 }, emptyList(), null, null, CognitiveInput(game, game, game, "Phone")),
            DefaultScoring.DEFAULT,
        )
        val s = calc.score!!
        return Readiness(dayKey, s, ReadinessBand.of(s), calc, 20, 0, emptyList(), 0)
    }

    private fun game(dayKey: String, score: Int, n: Int = 0) = CognitiveSession(
        "$dayKey-$n", DeviceKind.PHONE, "p", null, dayKey, 0, 1, SessionStatus.FULLY_VALID, false,
        0.1, 0.05, 0.0, score, score, score, score, BeatTheClock.GAME_VERSION, emptyList(),
    )

    @Test fun spearmanFollowsOrderNotSize() {
        assertEquals(1.0, ScoreCheck.spearman(listOf(1.0, 2.0, 3.0, 4.0, 50.0), listOf(10.0, 11.0, 12.0, 13.0, 14.0))!!, 1e-9)
        assertEquals(-1.0, ScoreCheck.spearman(listOf(1.0, 2.0, 3.0, 4.0, 5.0), listOf(9.0, 7.0, 5.0, 3.0, 1.0))!!, 1e-9)
        assertNull(ScoreCheck.spearman(listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0)))
        assertNull(ScoreCheck.spearman(List(6) { 5.0 }, listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)))
    }

    @Test fun bodyScoreIgnoresTheGame() {
        assertEquals(ScoreCheck.bodyScore(readiness(day(0), 420, 10)), ScoreCheck.bodyScore(readiness(day(0), 420, 95)))
        assertTrue(ScoreCheck.bodyScore(readiness(day(0), 300, 50))!! < ScoreCheck.bodyScore(readiness(day(0), 450, 50))!!)
    }

    @Test fun noVerdictBeforeEnoughDays() {
        val few = (0 until 10).map { readiness(day(it), 300 + it * 15, 50) }
        val r = ScoreCheck.run(few, few.mapIndexed { i, d -> game(d.dayKey, 30 + i * 5) })
        assertEquals(10, r.days)
        assertEquals(ScoreCheck.Link.NOT_ENOUGH_DATA, r.link)
    }

    @Test fun clearLinkWhenGamesFollowSleep() {
        // Sleep varies between 5h and 7h; days without a game don't count; two games on a day use their median.
        val days = (0 until 31).map { readiness(day(it), 300 + (it * 37 % 120), 50) }
        val games = days.dropLast(1).flatMap { d -> val g = ScoreCheck.bodyScore(d)!!.toInt(); listOf(game(d.dayKey, g, 0), game(d.dayKey, g, 1)) }
        val r = ScoreCheck.run(days, games)
        assertEquals(30, r.days)
        assertEquals(ScoreCheck.Link.CLEAR, r.link)
        assertTrue(r.correlation!! > 0.9)
    }
}
