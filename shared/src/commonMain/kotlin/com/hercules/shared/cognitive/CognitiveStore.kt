package com.hercules.shared.cognitive

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.hercules.shared.db.HerculesDatabase
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.BaselineValue
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.CollectionMethod
import com.hercules.shared.readiness.ConfigurationType
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.DefaultScoring
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.ExerciseRecord
import com.hercules.shared.readiness.HealthMetric
import com.hercules.shared.readiness.HealthMetricType
import com.hercules.shared.readiness.LightContext
import com.hercules.shared.readiness.LightSample
import com.hercules.shared.readiness.MomentStatus
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.RoundValidity
import com.hercules.shared.readiness.ScoringConfiguration
import com.hercules.shared.readiness.SessionStatus
import com.hercules.shared.readiness.SleepSessionRecord
import com.hercules.shared.readiness.Urgency
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.hercules.shared.db.Cognitive_round as RoundRow
import com.hercules.shared.db.Cognitive_session as CogRow
import com.hercules.shared.db.Exercise_session as ExerciseRow
import com.hercules.shared.db.Health_metric as MetricRow
import com.hercules.shared.db.Light_sample as LightRow
import com.hercules.shared.db.Moment_snapshot as SnapshotRow
import com.hercules.shared.db.Peak_window as PeakRow
import com.hercules.shared.db.Plan_event as EventRow
import com.hercules.shared.db.Prep_task_done as TaskRow
import com.hercules.shared.db.Readiness_day as ReadinessRow
import com.hercules.shared.db.Scoring_config as ConfigRow
import com.hercules.shared.db.Sleep_night as SleepRow
import com.hercules.shared.db.Sleep_session as SleepSessionRow
import com.hercules.shared.db.User_baseline as BaselineRow

internal val cognitiveJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** A stored baseline, as shown in Setup → Personal baselines. */
@Serializable
data class StoredBaseline(
    val metric: String,
    val device: DeviceKind?,
    val window: AssessmentWindow?,
    val value: BaselineValue,
    val calibrationVersion: String,
    val updatedMs: Long,
)

/** Readiness snapshot of one Moment on one day (spec §8). */
@Serializable
data class MomentSnapshot(
    val momentId: String,
    val dayKey: String,
    val capturedMs: Long,
    val score: Int?,
    val band: ReadinessBand?,
    val confidence: String?,
    val coverage: Double?,
    val prepStatus: String,
    val peakAlignment: String,
    val configId: String?,
    val configVersion: Int?,
)

data class MetricTypeStat(val type: HealthMetricType, val count: Long, val latestMs: Long?)

/** Local persistence for everything the readiness engine reads and writes. One instance per database. */
class CognitiveStore(db: HerculesDatabase, private val io: CoroutineDispatcher = Dispatchers.Default) {
    private val q = db.herculesQueries

    // ---------------------------------------------------------------- resolved nights
    suspend fun upsertSleep(s: SleepNight) = withContext(io) {
        q.upsertSleep(
            SleepRow(
                s.dayKey, s.startMs, s.endMs, s.asleepMinutes.toLong(), s.sleepingHrAvg, s.sleepingHrLow, s.source,
                s.awakeMinutes?.toLong(), s.deepMinutes?.toLong(), s.remMinutes?.toLong(), s.lightMinutes?.toLong(), s.provider,
            ),
        )
    }

    suspend fun sleep(dayKey: String): SleepNight? = withContext(io) { q.sleepByKey(dayKey).executeAsOneOrNull()?.toModel() }
    suspend fun sleepRange(fromKey: String, toKey: String): List<SleepNight> =
        withContext(io) { q.sleepRange(fromKey, toKey).executeAsList().map { it.toModel() } }
    fun observeSleep(dayKey: String): Flow<SleepNight?> = q.sleepByKey(dayKey).asFlow().mapToOneOrNull(io).map { it?.toModel() }
    fun observeSleepRange(fromKey: String, toKey: String): Flow<List<SleepNight>> =
        q.sleepRange(fromKey, toKey).asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    // ---------------------------------------------------------------- provider sleep sessions
    suspend fun upsertSleepSessions(list: List<SleepSessionRecord>) = withContext(io) {
        q.transaction {
            for (s in list) {
                q.upsertSleepSession(
                    SleepSessionRow(
                        s.id, s.dayKey, s.startMs, s.endMs, s.asleepMinutes.toLong(), s.awakeMinutes?.toLong(), s.deepMinutes?.toLong(),
                        s.remMinutes?.toLong(), s.lightMinutes?.toLong(), s.sourcePackage, s.sourceDevice, s.quality.name,
                    ),
                )
            }
        }
    }

    suspend fun sleepSessions(dayKey: String): List<SleepSessionRecord> = withContext(io) {
        q.sleepSessionsForDay(dayKey).executeAsList().map {
            SleepSessionRecord(
                it.id, it.day_key, it.start_ms, it.end_ms, it.asleep_min.toInt(), it.awake_min?.toInt(), it.deep_min?.toInt(),
                it.rem_min?.toInt(), it.light_min?.toInt(), it.source_package, it.source_device, enumOr(it.quality, DataQuality.INVALID),
            )
        }
    }

    // ---------------------------------------------------------------- health metrics
    suspend fun upsertMetrics(list: List<HealthMetric>) = withContext(io) {
        q.transaction {
            for (m in list) {
                q.upsertMetric(
                    MetricRow(
                        m.id, m.type.name, m.value, m.type.unit, m.measuredMs, m.endMs, m.recordedMs, m.dayKey, m.sourcePackage,
                        m.sourceDevice, m.quality.name, m.collection.name, m.metadata,
                    ),
                )
            }
        }
    }

    suspend fun metrics(type: HealthMetricType, fromKey: String, toKey: String): List<HealthMetric> = withContext(io) {
        q.metricsOfType(type.name, fromKey, toKey).executeAsList().mapNotNull { it.toModel() }
    }

    suspend fun latestMetric(type: HealthMetricType): HealthMetric? = withContext(io) { q.latestMetric(type.name).executeAsOneOrNull()?.toModel() }

    suspend fun metricStats(): List<MetricTypeStat> = withContext(io) {
        q.metricTypeCounts().executeAsList().mapNotNull { r ->
            runCatching { MetricTypeStat(HealthMetricType.valueOf(r.metric_type), r.n, r.latest) }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- exercise
    suspend fun upsertExercise(list: List<ExerciseRecord>) = withContext(io) {
        q.transaction { for (e in list) q.upsertExercise(ExerciseRow(e.id, e.startMs, e.endMs, e.exerciseType.toLong(), e.sourcePackage, e.sourceDevice)) }
    }

    suspend fun exerciseBetween(fromMs: Long, toMs: Long): List<ExerciseRecord> = withContext(io) {
        q.exerciseBetween(fromMs, toMs).executeAsList().map { ExerciseRecord(it.id, it.start_ms, it.end_ms, it.exercise_type.toInt(), it.source_package, it.source_device) }
    }

    // ---------------------------------------------------------------- light
    suspend fun insertLight(list: List<LightSample>) = withContext(io) {
        q.transaction { for (l in list) q.insertLight(LightRow(l.id, l.lux, l.measuredMs, l.sensor, l.context.name)) }
    }

    suspend fun lightBetween(fromMs: Long, toMs: Long): List<LightSample> = withContext(io) {
        q.lightBetween(fromMs, toMs).executeAsList().map { it.toModel() }
    }

    suspend fun latestLight(): LightSample? = withContext(io) { q.latestLight().executeAsOneOrNull()?.toModel() }
    suspend fun countLightSince(ms: Long): Long = withContext(io) { q.countLightSince(ms).executeAsOne() }
    suspend fun deleteLightBefore(ms: Long) = withContext(io) { q.deleteLightBefore(ms) }

    // ---------------------------------------------------------------- Beat the Clock
    suspend fun saveSession(s: CognitiveSession, synced: Boolean = false) = withContext(io) {
        q.transaction {
            q.upsertCognitiveSession(
                CogRow(
                    s.id, s.deviceKind.name, s.deviceId, s.window?.name, s.dayKey, s.startMs, s.endMs, s.status.name, if (s.practice) 1 else 0,
                    s.accuracyError, s.consistencyError, s.bias, s.accuracyScore?.toLong(), s.consistencyScore?.toLong(),
                    s.biasScore?.toLong(), s.cognitiveScore?.toLong(), s.gameVersion.toLong(), if (synced) 1 else 0,
                    s.energy?.toLong(), s.stress?.toLong(),
                ),
            )
            for (r in s.rounds) {
                q.upsertCognitiveRound(
                    RoundRow(r.id, r.sessionId, r.order.toLong(), r.targetMs, r.actualMs, r.signedErrorMs, r.absoluteErrorMs, r.relativeError, r.validity.name),
                )
            }
        }
    }

    suspend fun session(id: String): CognitiveSession? = withContext(io) { q.cognitiveSessionById(id).executeAsOneOrNull()?.let { toSession(it) } }

    suspend fun sessionsFrom(fromMs: Long): List<CognitiveSession> = withContext(io) {
        q.cognitiveSessionsFrom(fromMs).executeAsList().mapNotNull { toSession(it) }
    }

    fun observeSessionsFrom(fromMs: Long): Flow<List<CognitiveSession>> =
        q.cognitiveSessionsFrom(fromMs).asFlow().mapToList(io).map { rows -> rows.mapNotNull { toSession(it) } }

    suspend fun pendingSessions(limit: Long): List<CognitiveSession> = withContext(io) {
        q.pendingCognitiveSessions(limit).executeAsList().mapNotNull { toSession(it) }
    }

    suspend fun markSessionsSynced(ids: List<String>) = withContext(io) { if (ids.isNotEmpty()) q.markCognitiveSynced(ids) }

    suspend fun completedOnDevice(kind: DeviceKind): Int = withContext(io) { q.countCognitiveByDevice(kind.name).executeAsOne().toInt() }

    private fun toSession(r: CogRow): CognitiveSession? = runCatching {
        val rounds = q.roundsForSession(r.id).executeAsList().map {
            CognitiveRound(it.id, it.session_id, it.round_order.toInt(), it.target_ms, it.actual_ms, RoundValidity.valueOf(it.validity))
        }
        CognitiveSession(
            r.id, DeviceKind.valueOf(r.device_kind), r.device_id, r.assessment_window?.let { AssessmentWindow.valueOf(it) }, r.day_key,
            r.start_ms, r.end_ms, SessionStatus.valueOf(r.status), r.practice == 1L, r.accuracy_error, r.consistency_error, r.bias,
            r.accuracy_score?.toInt(), r.consistency_score?.toInt(), r.bias_score?.toInt(), r.cognitive_score?.toInt(), r.game_version.toInt(), rounds,
            energy = r.energy?.toInt(), stress = r.stress?.toInt(),
        )
    }.getOrNull()

    // ---------------------------------------------------------------- baselines
    suspend fun saveBaselines(list: List<StoredBaseline>) = withContext(io) {
        q.transaction {
            for (b in list) {
                val id = listOfNotNull(b.metric, b.device?.name, b.window?.name).joinToString(":")
                q.upsertBaseline(
                    BaselineRow(
                        id, b.metric, b.device?.name, b.window?.name, b.value.median, b.value.mad, b.value.sampleCount.toLong(),
                        b.value.personalShare, b.calibrationVersion, b.updatedMs,
                    ),
                )
            }
        }
    }

    suspend fun baselines(): List<StoredBaseline> = withContext(io) {
        q.allBaselines().executeAsList().map {
            StoredBaseline(
                it.metric_type, it.device_source?.let { d -> enumOrNull<DeviceKind>(d) }, it.assessment_window?.let { w -> enumOrNull<AssessmentWindow>(w) },
                BaselineValue(it.median, it.mad, it.sample_count.toInt(), it.personal_share), it.calibration_version, it.updated_ms,
            )
        }
    }

    fun observeBaselines(): Flow<List<StoredBaseline>> = q.allBaselines().asFlow().mapToList(io).map { rows ->
        rows.map {
            StoredBaseline(
                it.metric_type, it.device_source?.let { d -> enumOrNull<DeviceKind>(d) }, it.assessment_window?.let { w -> enumOrNull<AssessmentWindow>(w) },
                BaselineValue(it.median, it.mad, it.sample_count.toInt(), it.personal_share), it.calibration_version, it.updated_ms,
            )
        }
    }

    // ---------------------------------------------------------------- scoring configuration (history kept)
    suspend fun activeConfig(): ScoringConfiguration = withContext(io) { q.activeConfig().executeAsOneOrNull()?.toModel().current() }
    fun observeActiveConfig(): Flow<ScoringConfiguration> = q.activeConfig().asFlow().mapToOneOrNull(io).map { it?.toModel().current() }

    /** An older stored default follows the current default; custom weights are kept as they are. */
    private fun ScoringConfiguration?.current(): ScoringConfiguration =
        if (this == null || (type == ConfigurationType.DEFAULT && version < DefaultScoring.CONFIG_VERSION)) DefaultScoring.DEFAULT else this

    /** Stores [c] as a new history entry and makes it the only active one. Older entries are never changed. */
    suspend fun activateConfig(c: ScoringConfiguration) = withContext(io) {
        q.transaction {
            q.deactivateConfigs()
            q.insertConfig(
                ConfigRow(c.id, c.version.toLong(), c.type.name, c.sleepWeight, c.cardiovascularWeight, c.activityWeight, c.circadianWeight, c.cognitiveWeight, c.updatedAtMs, 1),
            )
        }
    }

    suspend fun configHistory(): List<ScoringConfiguration> = withContext(io) { q.configHistory().executeAsList().map { it.toModel() } }

    // ---------------------------------------------------------------- peak window
    suspend fun savePeak(p: PeakWindowPrediction, startDay: String, endDay: String) = withContext(io) {
        q.insertPeak(PeakRow("peak:$endDay", startDay, endDay, p.result.name, p.window?.name, cognitiveJson.encodeToString(PeakWindowPrediction.serializer(), p), p.modelVersion.toLong(), p.updatedMs))
    }

    suspend fun latestPeak(): PeakWindowPrediction? = withContext(io) {
        q.latestPeak().executeAsOneOrNull()?.let { runCatching { cognitiveJson.decodeFromString(PeakWindowPrediction.serializer(), it.json) }.getOrNull() }
    }

    // ---------------------------------------------------------------- readiness (published scores only)
    suspend fun upsertReadiness(r: Readiness) = withContext(io) {
        q.upsertReadiness(
            ReadinessRow(
                r.dayKey, r.score.toLong(), cognitiveJson.encodeToString(Readiness.serializer(), r), r.calc.stamp.configId,
                r.calc.stamp.configVersion.toLong(), r.calc.stamp.engineVersion, r.coverage, r.confidence.name, r.computedMs,
            ),
        )
    }

    suspend fun readiness(dayKey: String): Readiness? = withContext(io) { q.readinessByKey(dayKey).executeAsOneOrNull()?.toModel() }
    suspend fun readinessRange(fromKey: String, toKey: String): List<Readiness> =
        withContext(io) { q.readinessRange(fromKey, toKey).executeAsList().mapNotNull { it.toModel() } }
    fun observeReadiness(dayKey: String): Flow<Readiness?> = q.readinessByKey(dayKey).asFlow().mapToOneOrNull(io).map { it?.toModel() }
    fun observeReadinessRange(fromKey: String, toKey: String): Flow<List<Readiness>> =
        q.readinessRange(fromKey, toKey).asFlow().mapToList(io).map { rows -> rows.mapNotNull { it.toModel() } }

    /** Rows calculated by an older engine stay in the table for integrity but can't be shown as V1 scores. */
    private fun ReadinessRow.toModel(): Readiness? =
        if (engine_version == null) null else runCatching { cognitiveJson.decodeFromString(Readiness.serializer(), json) }.getOrNull()

    // ---------------------------------------------------------------- evaluations and small state
    suspend fun saveEvaluation(e: Evaluation) = kvPut("evaluation:${e.dayKey}", cognitiveJson.encodeToString(Evaluation.serializer(), e))
    suspend fun evaluation(dayKey: String): Evaluation? =
        kvGet("evaluation:$dayKey")?.let { runCatching { cognitiveJson.decodeFromString(Evaluation.serializer(), it) }.getOrNull() }
    fun observeEvaluation(dayKey: String): Flow<Evaluation?> = q.kvGet("evaluation:$dayKey").asFlow().mapToOneOrNull(io).map { s ->
        s?.let { runCatching { cognitiveJson.decodeFromString(Evaluation.serializer(), it) }.getOrNull() }
    }

    suspend fun kvGet(key: String): String? = withContext(io) { q.kvGet(key).executeAsOneOrNull() }
    suspend fun kvPut(key: String, value: String) = withContext(io) { q.kvPut(key, value) }
    fun observeKv(key: String): Flow<String?> = q.kvGet(key).asFlow().mapToOneOrNull(io)

    // ---------------------------------------------------------------- moments
    suspend fun upsertEvent(e: PlanEvent) = withContext(io) {
        q.upsertEvent(
            EventRow(e.id, e.title, e.type.name, e.startMs, e.urgency.name, e.notes, e.createdMs, e.timezoneId, e.status.name, e.updatedMs, e.completedMs, e.cancelledMs),
        )
    }

    suspend fun event(id: String): PlanEvent? = withContext(io) { q.eventById(id).executeAsOneOrNull()?.toModel() }
    suspend fun events(): List<PlanEvent> = withContext(io) { q.eventsAll().executeAsList().mapNotNull { it.toModel() } }
    fun observeEvents(): Flow<List<PlanEvent>> = q.eventsAll().asFlow().mapToList(io).map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun setTaskDone(eventId: String, dayKey: String, taskId: String, done: Boolean, nowMs: Long) = withContext(io) {
        if (done) q.setTaskDone(TaskRow(eventId, dayKey, taskId, nowMs)) else q.setTaskUndone(eventId, dayKey, taskId)
    }

    suspend fun tasksDone(eventId: String): List<Pair<String, String>> =
        withContext(io) { q.tasksDone(eventId).executeAsList().map { it.day_key to it.task_id } }

    fun observeTasksDone(eventId: String): Flow<List<Pair<String, String>>> =
        q.tasksDone(eventId).asFlow().mapToList(io).map { rows -> rows.map { it.day_key to it.task_id } }

    suspend fun saveSnapshot(s: MomentSnapshot) = withContext(io) {
        q.upsertSnapshot(
            SnapshotRow(s.momentId, s.dayKey, s.capturedMs, s.score?.toLong(), s.band?.name, s.confidence, s.coverage, s.prepStatus, s.peakAlignment, s.configId, s.configVersion?.toLong()),
        )
    }

    suspend fun snapshots(momentId: String): List<MomentSnapshot> = withContext(io) {
        q.snapshotsFor(momentId).executeAsList().map {
            MomentSnapshot(
                it.moment_id, it.day_key, it.captured_ms, it.score?.toInt(), it.classification?.let { c -> enumOrNull<ReadinessBand>(c) }, it.confidence,
                it.coverage, it.prep_status, it.peak_alignment, it.config_id, it.config_version?.toInt(),
            )
        }
    }

    suspend fun saveReflection(r: EventReflection) = withContext(io) {
        q.upsertReflection(com.hercules.shared.db.Event_reflection(r.eventId, r.rating.toLong(), r.createdMs))
    }

    suspend fun reflection(eventId: String): EventReflection? = withContext(io) {
        q.reflectionById(eventId).executeAsOneOrNull()?.let { EventReflection(it.event_id, it.rating.toInt(), it.created_ms) }
    }

    suspend fun reflections(): List<EventReflection> = withContext(io) {
        q.reflectionsAll().executeAsList().map { EventReflection(it.event_id, it.rating.toInt(), it.created_ms) }
    }

    fun observeReflections(): Flow<List<EventReflection>> =
        q.reflectionsAll().asFlow().mapToList(io).map { rows -> rows.map { EventReflection(it.event_id, it.rating.toInt(), it.created_ms) } }

    // ---------------------------------------------------------------- clear all (Setup → Data)
    suspend fun clearAll() = withContext(io) {
        q.transaction {
            q.clearSamples(); q.clearSessions(); q.clearDaily(); q.clearRecords(); q.clearSleep(); q.clearReadiness()
            q.clearEvents(); q.clearTasks(); q.clearReflections(); q.clearMetrics(); q.clearSleepSessions(); q.clearExercise(); q.clearLight()
            q.clearCognitive(); q.clearRounds(); q.clearBaselines(); q.clearConfigs(); q.clearPeaks(); q.clearSnapshots(); q.clearKv()
        }
    }

    /**
     * Deletes everything the demo loader wrote (ids "demo:…"/"demo-…", device "demo"), plus the derived tables, which
     * mix demo and real input and are rebuilt by the caller. Your own records stay.
     */
    suspend fun removeDemo() = withContext(io) {
        q.transaction {
            q.deleteDemoSamples(); q.deleteDemoMetrics(); q.deleteDemoSleepSessions(); q.deleteDemoExercise(); q.deleteDemoLight()
            q.deleteDemoRounds(); q.deleteDemoCognitive(); q.deleteDemoEvents(); q.deleteDemoTasks(); q.deleteDemoReflections(); q.deleteDemoSnapshots()
            q.clearSleep(); q.clearReadiness(); q.clearDaily(); q.clearRecords(); q.clearBaselines(); q.clearPeaks()
        }
    }

    // ---------------------------------------------------------------- mapping
    private fun SleepRow.toModel() = SleepNight(
        day_key, start_ms, end_ms, asleep_min.toInt(), hr_avg, hr_low, source, awake_min?.toInt(), deep_min?.toInt(), rem_min?.toInt(), light_min?.toInt(), provider,
    )

    private fun MetricRow.toModel(): HealthMetric? = runCatching {
        HealthMetric(
            id, HealthMetricType.valueOf(metric_type), this.value_, measured_ms, end_ms, recorded_ms, day_key, source_package, source_device,
            DataQuality.valueOf(quality), CollectionMethod.valueOf(collection), metadata,
        )
    }.getOrNull()

    private fun LightRow.toModel() = LightSample(id, lux, measured_ms, sensor, enumOr(context, LightContext.FOREGROUND))

    private fun ConfigRow.toModel() = ScoringConfiguration(
        id, version.toInt(), enumOr(type, ConfigurationType.CUSTOM), sleep_weight, cardio_weight, activity_weight, circadian_weight, cognitive_weight, updated_ms,
    )

    private fun EventRow.toModel(): PlanEvent? = runCatching {
        PlanEvent(
            id = id,
            title = title,
            type = enumOr(type, EventType.OTHER),
            startMs = start_ms,
            urgency = Urgency.fromStored(importance),
            notes = notes,
            createdMs = created_ms,
            timezoneId = timezone_id,
            status = enumOr(status, MomentStatus.ACTIVE),
            updatedMs = updated_ms ?: created_ms,
            completedMs = completed_ms,
            cancelledMs = cancelled_ms,
        )
    }.getOrNull()
}

internal inline fun <reified E : Enum<E>> enumOrNull(s: String): E? = enumValues<E>().firstOrNull { it.name == s }
internal inline fun <reified E : Enum<E>> enumOr(s: String, default: E): E = enumOrNull<E>(s) ?: default
