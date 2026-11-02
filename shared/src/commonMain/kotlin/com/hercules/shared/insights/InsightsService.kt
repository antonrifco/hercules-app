package com.hercules.shared.insights

import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.model.PersonalRecord
import com.hercules.shared.model.Sample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns raw samples into daily summaries, session summaries and personal records.
 * Safe to call often; runs are serialized.
 */
class InsightsService(
    private val repo: HerculesRepository,
    private val dayKeys: DayKeys = DayKeys(),
    stepGoal: Long = 10_000,
    private val clock: () -> Long,
) {
    private val aggregator = DailyAggregator(dayKeys)
    private val summarizer = SessionSummarizer(dayKeys)
    private val engine = PersonalRecordEngine(stepGoal)
    private val mutex = Mutex()

    fun daysOf(samples: List<Sample>): Set<String> = samples.mapTo(mutableSetOf()) { dayKeys.of(it.startMs) }

    /**
     * Recomputes today, yesterday and [extraDays], finalizes ended workouts,
     * re-evaluates records. Returns the records that were newly broken.
     */
    suspend fun refresh(extraDays: Set<String> = emptySet()): List<PersonalRecord> = mutex.withLock {
        val now = clock()
        val days = mutableSetOf(dayKeys.of(now), dayKeys.of(now - DAY_MS))
        days += extraDays

        val cutoff = now - SessionSummarizer.RECOVERY_WINDOW_MS
        for (s in repo.endedUnsummarizedSessions(cutoff)) {
            val samples = repo.samplesBetween(s.startMs, s.endMs + SessionSummarizer.RECOVERY_WINDOW_MS + 1)
            val summary = summarizer.summarize(s.id, s.kind, s.deviceId, s.startMs, s.endMs, samples)
            repo.saveSessionSummary(summary)
            days += summary.dayKey
        }

        for (key in days) {
            val samples = repo.samplesBetween(dayKeys.startOf(key), dayKeys.nextDayStart(key))
            if (samples.isNotEmpty()) repo.upsertDaily(aggregator.aggregate(key, samples))
        }

        val before = repo.records()
        val after = engine.evaluate(repo.allDaily(), repo.summarizedSessions())
        repo.saveRecords(after.values)
        engine.newlyBroken(before, after)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

/** Raw samples: synced ones kept 30 days, unsynced ones force-deleted after 90 days. */
class RetentionPolicy(
    private val repo: HerculesRepository,
    private val syncedRetentionMs: Long = 30L * 24 * 3600 * 1000,
    private val hardLimitMs: Long = 90L * 24 * 3600 * 1000,
) {
    suspend fun apply(nowMs: Long): Long =
        repo.deleteSamplesBefore(nowMs - syncedRetentionMs, onlySynced = true) +
            repo.deleteSamplesBefore(nowMs - hardLimitMs, onlySynced = false)
}
