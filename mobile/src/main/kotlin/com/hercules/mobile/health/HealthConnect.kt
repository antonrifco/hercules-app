package com.hercules.mobile.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.readiness.CollectionMethod
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.ExerciseRecord
import com.hercules.shared.readiness.HealthMetric
import com.hercules.shared.readiness.HealthMetricType
import com.hercules.shared.readiness.SensorCapability
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass
import com.hercules.shared.readiness.SleepSessionRecord as SleepRecord

/** Health Connect record types Hercules reads (spec §9). Read-only: Hercules never writes to Health Connect. */
object HealthPermissions {
    val byCapability: Map<SensorCapability, KClass<out Record>> = mapOf(
        SensorCapability.HC_SLEEP to SleepSessionRecord::class,
        SensorCapability.HC_HRV to HeartRateVariabilityRmssdRecord::class,
        SensorCapability.HC_RESTING_HR to RestingHeartRateRecord::class,
        SensorCapability.HC_STEPS to StepsRecord::class,
        SensorCapability.HC_EXERCISE to ExerciseSessionRecord::class,
        SensorCapability.HC_RESPIRATORY_RATE to RespiratoryRateRecord::class,
        SensorCapability.HC_SKIN_TEMPERATURE to SkinTemperatureRecord::class,
        SensorCapability.HC_SPO2 to OxygenSaturationRecord::class,
        SensorCapability.HC_HEART_RATE to HeartRateRecord::class,
        SensorCapability.HC_BLOOD_GLUCOSE to BloodGlucoseRecord::class,
    )

    fun of(c: SensorCapability): String = HealthPermission.getReadPermission(byCapability.getValue(c))

    val required: Set<String> = byCapability.keys.filter { it.required }.map { of(it) }.toSet()
    val optional: Set<String> = byCapability.keys.filter { !it.required }.map { of(it) }.toSet()
    val all: Set<String> = required + optional

    /** Shown on Home's Today tiles only (distance, energy, floors); never stored or scored. */
    val display: Set<String> = setOf(DistanceRecord::class, ActiveCaloriesBurnedRecord::class, FloorsClimbedRecord::class)
        .map { HealthPermission.getReadPermission(it) }.toSet()

    /** Lets the hourly background job read Health Connect while Hercules isn't open. */
    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
}

/** What Health Connect looks like right now on this phone. */
enum class HealthConnectState { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED, UNSUPPORTED }

/**
 * Imports Health Connect records into the local database. Re-reading a window is harmless: every record keeps its
 * Health Connect id, so the same record twice is one row. Only valid, plausible values are marked VALID.
 */
class HealthConnectImporter(private val context: Context, private val store: CognitiveStore, private val dayKeys: DayKeys) {

    fun state(): HealthConnectState = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthConnectState.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthConnectState.UPDATE_REQUIRED
        else -> if (android.os.Build.VERSION.SDK_INT >= 34) HealthConnectState.UNSUPPORTED else HealthConnectState.NOT_INSTALLED
    }

    private fun client(): HealthConnectClient? = if (state() == HealthConnectState.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    suspend fun granted(): Set<String> = runCatching { client()?.permissionController?.getGrantedPermissions() }.getOrNull().orEmpty()

    /** Whether this Health Connect lets Hercules read in the background (the hourly sync). */
    fun backgroundReadSupported(): Boolean = runCatching {
        client()?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    /** Everything to ask for: every record type, plus background reading where supported. */
    fun permissionsToRequest(): Set<String> =
        HealthPermissions.all + HealthPermissions.display + if (backgroundReadSupported()) setOf(HealthPermissions.BACKGROUND) else emptySet()

    /** Background reading is supported but not granted, so only in-app syncs work. */
    suspend fun backgroundMissing(): Boolean = backgroundReadSupported() && HealthPermissions.BACKGROUND !in granted()

    /** Something worth asking for beyond the record types: background reading or the Today-tile types. */
    suspend fun extrasMissing(): Boolean = backgroundMissing() || !granted().containsAll(HealthPermissions.display)

    /** Today's totals for Home's tiles, from Health Connect's de-duplicated aggregates. Only granted types are read. */
    data class DayTotals(val steps: Long?, val distanceM: Double?, val activeKcal: Double?, val floors: Double?, val maxHr: Long?, val restingHr: Long?)

    suspend fun todayTotals(nowMs: Long = System.currentTimeMillis()): DayTotals? {
        val c = client() ?: return null
        val granted = granted()
        val metrics = buildSet {
            fun add(type: KClass<out Record>, m: androidx.health.connect.client.aggregate.AggregateMetric<*>) {
                if (HealthPermission.getReadPermission(type) in granted) add(m)
            }
            add(StepsRecord::class, StepsRecord.COUNT_TOTAL)
            add(DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL)
            add(ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            add(FloorsClimbedRecord::class, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            add(HeartRateRecord::class, HeartRateRecord.BPM_MAX)
            add(RestingHeartRateRecord::class, RestingHeartRateRecord.BPM_MIN)
        }
        if (metrics.isEmpty()) return null
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        return runCatching {
            val r = c.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(start, Instant.ofEpochMilli(nowMs))))
            DayTotals(
                r[StepsRecord.COUNT_TOTAL], r[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories, r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL],
                r[HeartRateRecord.BPM_MAX], r[RestingHeartRateRecord.BPM_MIN],
            )
        }.onFailure { Log.w(TAG, "today totals failed", it) }.getOrNull()
    }

    /** Skin temperature needs a newer Health Connect; older versions simply don't offer it. */
    fun skinTemperatureSupported(): Boolean = runCatching {
        client()?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    data class Result(val records: Int, val error: String?)

    /** Reads the last [days] days of every granted type. */
    suspend fun import(days: Int, nowMs: Long = System.currentTimeMillis()): Result {
        val c = client() ?: return Result(0, "Health Connect isn't available")
        val granted = granted()
        if (granted.isEmpty()) return Result(0, "No Health Connect permissions")
        val end = Instant.ofEpochMilli(nowMs)
        val start = end.minus(Duration.ofDays(days.toLong()))
        val range = TimeRangeFilter.between(start, end)
        var n = 0
        fun has(cap: SensorCapability) = HealthPermissions.of(cap) in granted
        return runCatching {
            if (has(SensorCapability.HC_SLEEP)) n += importSleep(c, range)
            if (has(SensorCapability.HC_HRV)) {
                n += importMetric(c, HeartRateVariabilityRmssdRecord::class, range, HealthMetricType.HRV_RMSSD) { listOf(Triple(it.time, it.heartRateVariabilityMillis, it.time)) }
            }
            if (has(SensorCapability.HC_RESTING_HR)) {
                n += importMetric(c, RestingHeartRateRecord::class, range, HealthMetricType.RESTING_HR) { listOf(Triple(it.time, it.beatsPerMinute.toDouble(), it.time)) }
            }
            if (has(SensorCapability.HC_STEPS)) n += importSteps(c, days, nowMs)
            if (has(SensorCapability.HC_EXERCISE)) n += importExercise(c, range)
            if (has(SensorCapability.HC_RESPIRATORY_RATE)) {
                n += importMetric(c, RespiratoryRateRecord::class, range, HealthMetricType.RESPIRATORY_RATE) { listOf(Triple(it.time, it.rate, it.time)) }
            }
            if (has(SensorCapability.HC_SPO2)) {
                n += importMetric(c, OxygenSaturationRecord::class, range, HealthMetricType.SPO2) { listOf(Triple(it.time, it.percentage.value, it.time)) }
            }
            if (has(SensorCapability.HC_SKIN_TEMPERATURE) && skinTemperatureSupported()) {
                n += importMetric(c, SkinTemperatureRecord::class, range, HealthMetricType.SKIN_TEMPERATURE_DELTA) { r ->
                    r.deltas.map { Triple(it.time, it.delta.inCelsius, it.time) }
                }
            }
            if (has(SensorCapability.HC_HEART_RATE)) {
                // Supporting data only: one value per record (its mean), not every sample.
                n += importMetric(c, HeartRateRecord::class, range, HealthMetricType.HEART_RATE) { r ->
                    if (r.samples.isEmpty()) emptyList() else listOf(Triple(r.startTime, r.samples.map { it.beatsPerMinute }.average(), r.endTime))
                }
            }
            if (has(SensorCapability.HC_BLOOD_GLUCOSE)) {
                // Optional (CGM or finger-stick), stored with provenance. Never a weighted signal: lows only deduct (spec §6.1)
                // and show a live banner on Home.
                n += importMetric(c, BloodGlucoseRecord::class, range, HealthMetricType.BLOOD_GLUCOSE) { listOf(Triple(it.time, it.level.inMillimolesPerLiter, it.time)) }
            }
            store.kvPut(KEY_LAST_IMPORT, nowMs.toString())
            Result(n, null)
        }.getOrElse {
            Log.w(TAG, "Health Connect import failed", it)
            Result(n, it.message ?: it.javaClass.simpleName)
        }
    }

    suspend fun lastImportMs(): Long? = store.kvGet(KEY_LAST_IMPORT)?.toLongOrNull()

    private suspend fun <T : Record> readAll(c: HealthConnectClient, type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val res = c.readRecords(ReadRecordsRequest(type, range, pageSize = 1000, pageToken = token))
            out += res.records
            token = res.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /** Night-time readings belong to the morning they lead into: anything after 18:00 counts for the next day. */
    private fun nightDay(i: Instant): String = dayKeys.of(i.toEpochMilli() + 6 * HOUR)

    private fun origin(m: Metadata) = m.dataOrigin.packageName
    private fun device(m: Metadata) = m.device?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ").ifBlank { null } }

    private suspend fun <T : Record> importMetric(
        c: HealthConnectClient,
        type: KClass<T>,
        range: TimeRangeFilter,
        metric: HealthMetricType,
        values: (T) -> List<Triple<Instant, Double, Instant>>,
    ): Int {
        val now = System.currentTimeMillis()
        val list = readAll(c, type, range).flatMap { r ->
            values(r).mapIndexed { i, (at, v, end) ->
                HealthMetric(
                    id = "hc:${r.metadata.id}:$i",
                    type = metric,
                    value = v,
                    measuredMs = at.toEpochMilli(),
                    endMs = end.toEpochMilli(),
                    recordedMs = now,
                    dayKey = nightDay(at),
                    sourcePackage = origin(r.metadata),
                    sourceDevice = device(r.metadata),
                    quality = if (plausible(metric, v)) DataQuality.VALID else DataQuality.INVALID,
                    collection = CollectionMethod.HEALTH_CONNECT,
                )
            }
        }
        store.upsertMetrics(list)
        return list.size
    }

    private fun plausible(t: HealthMetricType, v: Double) = !v.isNaN() && when (t) {
        HealthMetricType.HRV_RMSSD -> v in 5.0..250.0
        HealthMetricType.RESTING_HR -> v in 30.0..120.0
        HealthMetricType.STEPS_DAILY -> v in 0.0..100_000.0
        HealthMetricType.RESPIRATORY_RATE -> v in 4.0..40.0
        HealthMetricType.SKIN_TEMPERATURE_DELTA -> v in -5.0..5.0
        HealthMetricType.SPO2 -> v in 70.0..100.0
        HealthMetricType.HEART_RATE -> v in 25.0..230.0
        HealthMetricType.BLOOD_GLUCOSE -> v in 1.0..35.0
    }

    private suspend fun importSleep(c: HealthConnectClient, range: TimeRangeFilter): Int {
        val asleepStages = setOf(
            SleepSessionRecord.STAGE_TYPE_SLEEPING, SleepSessionRecord.STAGE_TYPE_LIGHT,
            SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_REM,
        )
        val awakeStages = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)
        fun minutes(list: List<SleepSessionRecord.Stage>) = (list.sumOf { Duration.between(it.startTime, it.endTime).toMillis() } / 60_000L).toInt()
        val list = readAll(c, SleepSessionRecord::class, range).map { r ->
            val total = (Duration.between(r.startTime, r.endTime).toMillis() / 60_000L).toInt()
            val staged = r.stages.isNotEmpty()
            val asleep = if (staged) minutes(r.stages.filter { it.stage in asleepStages }) else total
            SleepRecord(
                id = "hc:${r.metadata.id}",
                dayKey = dayKeys.of(r.endTime.toEpochMilli()),
                startMs = r.startTime.toEpochMilli(),
                endMs = r.endTime.toEpochMilli(),
                asleepMinutes = asleep,
                awakeMinutes = if (staged) minutes(r.stages.filter { it.stage in awakeStages }) else null,
                deepMinutes = if (staged) minutes(r.stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP }) else null,
                remMinutes = if (staged) minutes(r.stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_REM }) else null,
                lightMinutes = if (staged) minutes(r.stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_LIGHT }) else null,
                sourcePackage = origin(r.metadata),
                sourceDevice = device(r.metadata),
                quality = if (asleep in 60..16 * 60) DataQuality.VALID else DataQuality.INVALID,
            )
        }
        store.upsertSleepSessions(list)
        return list.size
    }

    /** Daily step totals from Health Connect's own aggregation, which de-duplicates overlapping apps. */
    private suspend fun importSteps(c: HealthConnectClient, days: Int, nowMs: Long): Int {
        val zone = ZoneId.systemDefault()
        val endDay = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1).atStartOfDay()
        val startDay = endDay.minusDays(days.toLong() + 1)
        val rows = c.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(startDay, endDay), Period.ofDays(1)),
        )
        val now = System.currentTimeMillis()
        val list = rows.mapNotNull { p ->
            val steps = p.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
            val dayStart = p.startTime.atZone(zone).toInstant().toEpochMilli()
            val key = dayKeys.of(dayStart)
            HealthMetric(
                "hc:steps:$key", HealthMetricType.STEPS_DAILY, steps.toDouble(), dayStart, p.endTime.atZone(zone).toInstant().toEpochMilli(), now, key,
                p.result.dataOrigins.joinToString(",") { it.packageName }.ifEmpty { "health_connect" }, null,
                if (plausible(HealthMetricType.STEPS_DAILY, steps.toDouble())) DataQuality.VALID else DataQuality.INVALID, CollectionMethod.HEALTH_CONNECT,
            )
        }
        store.upsertMetrics(list)
        return list.size
    }

    private suspend fun importExercise(c: HealthConnectClient, range: TimeRangeFilter): Int {
        val list = readAll(c, ExerciseSessionRecord::class, range).map {
            ExerciseRecord("hc:${it.metadata.id}", it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), it.exerciseType, origin(it.metadata), device(it.metadata))
        }
        store.upsertExercise(list)
        return list.size
    }

    @Suppress("unused") private fun LocalDateTime.ms(zone: ZoneId) = atZone(zone).toInstant().toEpochMilli()

    companion object {
        private const val TAG = "HerculesHC"
        private const val HOUR = 3_600_000L
        const val KEY_LAST_IMPORT = "hc_last_import"
    }
}
