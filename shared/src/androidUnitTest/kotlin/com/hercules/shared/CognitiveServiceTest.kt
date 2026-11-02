package com.hercules.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.cognitive.PrepPhase
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

    @Test fun demoDataProducesExplainedReadinessAndEventCoaching() = runTest {
        val (repo, insights, cognitive) = services()
        DemoData.load(repo, insights, cognitive, now)

        // Last night: 01:10 → 06:20 with elevated HR → low-ish score with concrete advice.
        val sleep = assertNotNull(cognitive.store.sleep("2026-11-10"))
        assertEquals(310, sleep.asleepMinutes)
        val r = assertNotNull(cognitive.refresh())
        assertTrue(r.score in 30..65, "score=${r.score}")
        assertTrue(r.baselineDays >= 7)
        assertTrue(r.suggestions.any { it.title.startsWith("Short on sleep") })

        // 14 days of history for trends.
        assertTrue(cognitive.store.readinessRange("2026-10-27", "2026-11-10").size >= 10)

        val events = cognitive.upcoming()
        val pitch = events.first { it.event.id == "demo-pitch" }
        assertEquals(PrepPhase.DAY, pitch.phase)
        assertNotNull(pitch.advice) // today → go/reschedule advice
        val interview = events.first { it.event.id == "demo-interview" }
        assertEquals(6, interview.daysUntil)
        assertEquals(PrepPhase.FOUNDATION, interview.phase)
        assertNotNull(interview.prepScore)
        assertNotNull(interview.projectedScore)
        assertEquals(8, interview.history.size)

        val state = cognitive.watchState()
        assertEquals(state, cognitive.decodeWatchState(cognitive.encodeWatchState(state)))
        assertEquals("demo-pitch", state.nextEvent?.event?.id)
    }

    @Test fun checkInRaisesConfidenceAndChangesScore() = runTest {
        val (repo, insights, cognitive) = services()
        DemoData.load(repo, insights, cognitive, now)
        val before = cognitive.refresh()!!
        val after = cognitive.saveCheckIn(null, energy = 5, stress = 1)!!
        assertTrue(after.score > before.score)
        assertTrue(after.suggestions.none { it.title == "Do your morning check-in" })
    }

    @Test fun taskCompletionIsTracked() = runTest {
        val (repo, insights, cognitive) = services()
        DemoData.load(repo, insights, cognitive, now)
        val ev = cognitive.store.event("demo-interview")!!
        val before = cognitive.eventStatus(ev)
        before.todayTasks.forEach { cognitive.store.setTaskDone(ev.id, "2026-11-10", it.id, true, now) }
        val after = cognitive.eventStatus(ev)
        assertEquals(after.todayTasks.map { it.id }.toSet(), after.doneTaskIds)
        assertTrue(after.prepScore!! > before.prepScore!!)
    }
}
