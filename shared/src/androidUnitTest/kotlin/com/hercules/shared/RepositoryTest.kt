package com.hercules.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.db.HerculesDatabase
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.insights.InsightsService
import com.hercules.shared.insights.RetentionPolicy
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.RecordKind
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.sync.SyncResult
import com.hercules.shared.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryTest {
    private val utc = DayKeys(TimeZone.UTC)
    private val dayStart = utc.startOf("2026-10-02")
    private val min = 60_000L
    private lateinit var repo: HerculesRepository

    @BeforeTest fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HerculesDatabase.Schema.create(driver)
        repo = HerculesRepository(HerculesDatabase(driver), Dispatchers.Unconfined)
    }

    private fun steps(v: Double, t: Long, src: SampleSource = SampleSource.PASSIVE, session: String? = null) =
        Sample.create(MetricType.STEPS, v, t, t, src, "watch", session)

    private fun hr(v: Double, t: Long, src: SampleSource = SampleSource.PASSIVE, session: String? = null) =
        Sample.create(MetricType.HEART_RATE, v, t, t, src, "watch", session)

    @Test fun insertIsIdempotentAndRangeIsHalfOpen() = runTest {
        repo.insertSamples(listOf(hr(70.0, dayStart + min), hr(71.0, dayStart)))
        repo.insertSamples(listOf(hr(70.0, dayStart + min)))
        assertEquals(2, repo.countSamples())
        assertEquals(listOf(dayStart), repo.samplesBetween(dayStart, dayStart + min).map { it.startMs })
    }

    @Test fun insightsBuildSummaryRecordsAndFinalizeSessions() = runTest {
        val now = dayStart + 20 * 60 * min
        val insights = InsightsService(repo, utc, stepGoal = 10_000, clock = { now })
        val start = dayStart + 6 * 60 * min
        val end = start + 30 * min
        repo.startSession("w1", "run", "watch", start)
        repo.endSession("w1", end)
        repo.insertSamples(
            listOf(
                steps(6000.0, dayStart + 8 * 60 * min),
                steps(6000.0, dayStart + 9 * 60 * min),
                hr(160.0, end - 1000, SampleSource.EXERCISE, "w1"),
                hr(130.0, end + 60_000, SampleSource.MEASURE, "w1"),
                Sample.create(MetricType.DISTANCE, 5000.0, start + min, start + 20 * min, SampleSource.EXERCISE, "watch", "w1"),
            ),
        )

        val broken = insights.refresh()

        assertEquals(12000, repo.observeDaily("2026-10-02").first()!!.steps)
        val session = repo.summarizedSessions().single()
        assertEquals(5000.0, session.distanceMeters)
        assertEquals(30, session.hrRecovery60s)
        val kinds = broken.map { it.kind }.toSet()
        assertTrue(RecordKind.MAX_DAILY_STEPS in kinds)
        assertTrue(RecordKind.MAX_SESSION_DISTANCE in kinds)
        assertTrue(RecordKind.BEST_HR_RECOVERY in kinds)
        assertTrue(insights.refresh().isEmpty(), "second run breaks nothing new")
    }

    @Test fun syncMarksOnlyAcceptedAndRetentionRespectsSynced() = runTest {
        val old = dayStart - 40L * 24 * 60 * min
        val ancient = dayStart - 100L * 24 * 60 * min
        val oldSynced = steps(1.0, old)
        repo.insertSamples(listOf(oldSynced, steps(2.0, old + 1), steps(3.0, ancient), steps(4.0, dayStart)))
        val acceptOne = SyncService(repo, { _ -> SyncResult.Accepted(listOf(oldSynced.id), emptyList()) },
            "watch", sampleBatchSize = 10, clock = { 0L })
        acceptOne.syncOnce(maxBatches = 1)
        assertEquals(3, repo.observePendingSampleCount().first())

        // ancient (unsynced, >90d) and the synced 40d-old one are deleted; unsynced 40d-old + recent kept
        val deleted = RetentionPolicy(repo).apply(dayStart)
        assertEquals(2, deleted)
        assertEquals(2, repo.countSamples())
    }

    @Test fun receivedSessionsAreStoredAsSummarized() = runTest {
        val s = com.hercules.shared.model.SessionSummary("w9", "run", "watch", "2026-10-02", dayStart, dayStart + min, 1234.0)
        repo.upsertSummarizedSessions(listOf(s))
        repo.upsertSummarizedSessions(listOf(s))
        assertEquals(listOf(s), repo.summarizedSessions())
        assertEquals(listOf("w9"), repo.pendingSessions(10).map { it.id })
    }
}
