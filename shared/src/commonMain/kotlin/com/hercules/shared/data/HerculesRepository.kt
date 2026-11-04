package com.hercules.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.hercules.shared.db.HerculesDatabase
import com.hercules.shared.model.DailySummary
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.PersonalRecord
import com.hercules.shared.model.RecordKind
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.model.SessionSummary
import com.hercules.shared.sync.SyncStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.hercules.shared.db.Daily_summary as DailyRow
import com.hercules.shared.db.Personal_record as RecordRow
import com.hercules.shared.db.Sample as SampleRow
import com.hercules.shared.db.Workout_session as SessionRow

/** Single source of truth on every device (watch, phone; later iOS/watchOS via the same code). */
class HerculesRepository(
    db: HerculesDatabase,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : SyncStore {
    private val q = db.herculesQueries

    // ---------------------------------------------------------------- samples
    suspend fun insertSamples(samples: List<Sample>) {
        if (samples.isEmpty()) return
        withContext(io) {
            q.transaction {
                for (s in samples) {
                    q.insertSample(s.id, s.type.wire, s.value, s.startMs, s.endMs, s.source.wire, s.deviceId, s.sessionId)
                }
            }
        }
    }

    suspend fun samplesBetween(fromMs: Long, toMs: Long): List<Sample> = withContext(io) {
        q.samplesBetween(fromMs, toMs).executeAsList().mapNotNull { it.toModel() }
    }

    fun observeLatest(type: MetricType): Flow<Sample?> =
        q.latestOfType(type.wire).asFlow().mapToOneOrNull(io).map { it?.toModel() }

    suspend fun latest(type: MetricType): Sample? =
        withContext(io) { q.latestOfType(type.wire).executeAsOneOrNull()?.toModel() }

    suspend fun countSamples(): Long = withContext(io) { q.countSamples().executeAsOne() }

    fun observePendingSampleCount(): Flow<Long> = q.countPendingSamples().asFlow().mapToOne(io)

    suspend fun pendingSampleCount(): Long = withContext(io) { q.countPendingSamples().executeAsOne() }

    /** Raw-sample retention. Summaries and records are kept forever. */
    suspend fun deleteSamplesBefore(cutoffMs: Long, onlySynced: Boolean): Long = withContext(io) {
        q.transactionWithResult {
            if (onlySynced) q.deleteSyncedBefore(cutoffMs) else q.deleteAllBefore(cutoffMs)
            q.changes().executeAsOne()
        }
    }

    // --------------------------------------------------------------- sessions
    suspend fun startSession(id: String, kind: String, deviceId: String, startMs: Long) = withContext(io) {
        q.startSession(id, kind, deviceId, startMs)
    }

    suspend fun endSession(id: String, endMs: Long) = withContext(io) { q.endSession(endMs, id) }

    suspend fun endedUnsummarizedSessions(beforeMs: Long): List<OpenSession> = withContext(io) {
        q.endedUnsummarized(beforeMs).executeAsList().map {
            OpenSession(it.id, it.kind, it.device_id, it.start_ms, it.end_ms!!)
        }
    }

    suspend fun saveSessionSummary(s: SessionSummary) = withContext(io) {
        q.saveSummary(s.dayKey, s.distanceMeters, s.avgHr, s.maxHr, s.hrRecovery60s, s.id)
    }

    /** Used by a receiver (phone/server) to store sessions summarized elsewhere. */
    suspend fun upsertSummarizedSessions(sessions: List<SessionSummary>) {
        if (sessions.isEmpty()) return
        withContext(io) {
            q.transaction {
                for (s in sessions) {
                    q.upsertSummarizedSession(
                        s.id, s.kind, s.deviceId, s.startMs, s.endMs, s.dayKey,
                        s.distanceMeters, s.avgHr, s.maxHr, s.hrRecovery60s,
                    )
                }
            }
        }
    }

    suspend fun summarizedSessions(): List<SessionSummary> = withContext(io) {
        q.summarizedSessions().executeAsList().map { it.toSummary() }
    }

    fun observeRecentSessions(limit: Long = 10): Flow<List<SessionSummary>> =
        q.recentSessions(limit).asFlow().mapToList(io).map { rows -> rows.map { it.toSummary() } }

    // ------------------------------------------------------- daily summaries
    suspend fun upsertDaily(d: DailySummary) = withContext(io) {
        q.upsertDaily(
            DailyRow(
                d.dayKey, d.steps, d.calories, d.distanceMeters, d.floors,
                d.restingHr, d.maxHr, d.avgHr, d.hrSampleCount, d.hrvRmssd,
            )
        )
    }

    suspend fun daily(dayKey: String): DailySummary? =
        withContext(io) { q.dailyByKey(dayKey).executeAsOneOrNull()?.toModel() }

    suspend fun allDaily(): List<DailySummary> = withContext(io) { q.allDaily().executeAsList().map { it.toModel() } }

    fun observeDaily(dayKey: String): Flow<DailySummary?> =
        q.dailyByKey(dayKey).asFlow().mapToOneOrNull(io).map { it?.toModel() }

    fun observeRecentDays(limit: Long = 7): Flow<List<DailySummary>> =
        q.recentDaily(limit).asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    // ---------------------------------------------------------------- records
    suspend fun records(): Map<RecordKind, PersonalRecord> = withContext(io) {
        q.allRecords().executeAsList().mapNotNull { it.toModel() }.associateBy { it.kind }
    }

    suspend fun saveRecords(records: Collection<PersonalRecord>) = withContext(io) {
        q.transaction {
            records.forEach { q.upsertRecord(RecordRow(it.kind.wire, it.value, it.dayKey, it.refId)) }
        }
    }

    fun observeRecords(): Flow<List<PersonalRecord>> =
        q.allRecords().asFlow().mapToList(io).map { rows -> rows.mapNotNull { it.toModel() }.sortedBy { it.kind.ordinal } }

    // ------------------------------------------------------------- SyncStore
    override suspend fun pendingSamples(limit: Long): List<Sample> = withContext(io) {
        q.pendingSamples(limit).executeAsList().mapNotNull { it.toModel() }
    }

    override suspend fun pendingSessions(limit: Long): List<SessionSummary> = withContext(io) {
        q.pendingSessions(limit).executeAsList().map { it.toSummary() }
    }

    override suspend fun markSynced(sampleIds: List<String>, sessionIds: List<String>) = withContext(io) {
        q.transaction {
            sampleIds.chunked(500).forEach { q.markSamplesSynced(it) }
            sessionIds.chunked(500).forEach { q.markSessionsSynced(it) }
        }
    }

    // ---------------------------------------------------------------- mapping
    private fun SampleRow.toModel(): Sample? = runCatching {
        Sample(
            id = id,
            type = MetricType.fromWire(type),
            value = value_,
            startMs = start_ms,
            endMs = end_ms,
            source = SampleSource.fromWire(source),
            deviceId = device_id,
            sessionId = session_id,
        )
    }.getOrNull() // rows written by a newer app version with unknown types are skipped, not fatal

    private fun SessionRow.toSummary() = SessionSummary(
        id = id,
        kind = kind,
        deviceId = device_id,
        dayKey = day_key.orEmpty(),
        startMs = start_ms,
        endMs = end_ms ?: start_ms,
        distanceMeters = distance_m ?: 0.0,
        avgHr = avg_hr,
        maxHr = max_hr,
        hrRecovery60s = hr_recovery_60s,
    )

    private fun DailyRow.toModel() = DailySummary(
        dayKey = day_key,
        steps = steps,
        calories = calories,
        distanceMeters = distance_m,
        floors = floors,
        restingHr = resting_hr,
        maxHr = max_hr,
        avgHr = avg_hr,
        hrSampleCount = hr_sample_count,
        hrvRmssd = hrv_rmssd,
    )

    private fun RecordRow.toModel(): PersonalRecord? =
        runCatching { PersonalRecord(RecordKind.fromWire(kind), value_, day_key, ref_id) }.getOrNull()
}

data class OpenSession(val id: String, val kind: String, val deviceId: String, val startMs: Long, val endMs: Long)
