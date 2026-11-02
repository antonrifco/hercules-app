package com.hercules.shared.model

/** Metric wire names and units are part of the server contract (docs/contracts). */
enum class MetricType(val wire: String, val unit: String) {
    HEART_RATE("heart_rate", "bpm"),
    STEPS("steps", "count"),
    CALORIES("calories", "kcal"),
    DISTANCE("distance", "m"),
    FLOORS("floors", "count"),
    SPEED("speed", "m/s"),
    IBI("ibi", "ms"),
    SPO2("spo2", "%"),
    SKIN_TEMPERATURE("skin_temp", "degC"),
    HRV_SDNN("hrv_sdnn", "ms"),
    VO2_MAX("vo2_max", "ml/kg/min"),

    /** User activity state change (Health Services UserActivityInfo). Value: see [ActivityState]. */
    ACTIVITY_STATE("activity_state", "state");

    companion object {
        fun fromWire(s: String): MetricType =
            entries.firstOrNull { it.wire == s } ?: throw IllegalArgumentException("Unknown metric type: $s")
    }
}

enum class SampleSource(val wire: String) {
    PASSIVE("passive"),
    EXERCISE("exercise"),
    MEASURE("measure"),
    SAMSUNG_SDK("samsung_sdk"),
    HEALTHKIT("healthkit");

    /** Daily totals only count these, exercise data would double count. */
    val countsTowardDailyTotals: Boolean get() = this == PASSIVE || this == HEALTHKIT

    companion object {
        fun fromWire(s: String): SampleSource =
            entries.firstOrNull { it.wire == s } ?: throw IllegalArgumentException("Unknown sample source: $s")
    }
}

/** A single raw reading. Times are epoch milliseconds (UTC). */
data class Sample(
    val id: String,
    val type: MetricType,
    val value: Double,
    val startMs: Long,
    val endMs: Long,
    val source: SampleSource,
    val deviceId: String,
    val sessionId: String? = null,
) {
    init {
        require(endMs >= startMs) { "Sample $id: end ($endMs) before start ($startMs)" }
    }

    companion object {
        /**
         * Deterministic id: the same reading delivered twice (sensor re-delivery,
         * watch→phone retry, phone→server retry) always collapses to one row.
         */
        fun idFor(type: MetricType, source: SampleSource, startMs: Long, endMs: Long, sessionId: String?): String =
            "${type.wire}|${source.wire}|$startMs|$endMs|${sessionId.orEmpty()}"

        fun create(
            type: MetricType,
            value: Double,
            startMs: Long,
            endMs: Long = startMs,
            source: SampleSource,
            deviceId: String,
            sessionId: String? = null,
        ) = Sample(idFor(type, source, startMs, endMs, sessionId), type, value, startMs, endMs, source, deviceId, sessionId)
    }
}

/** Encoded as the value of [MetricType.ACTIVITY_STATE] samples. */
object ActivityState {
    const val UNKNOWN = 0.0
    const val AWAKE = 1.0
    const val ASLEEP = 2.0
    const val EXERCISE = 3.0
}

data class DailySummary(
    val dayKey: String,
    val steps: Long,
    val calories: Double,
    val distanceMeters: Double,
    val floors: Long,
    val restingHr: Long? = null,
    val maxHr: Long? = null,
    val avgHr: Double? = null,
    val hrSampleCount: Long = 0,
    val hrvRmssd: Double? = null,
)

data class SessionSummary(
    val id: String,
    val kind: String,
    val deviceId: String,
    val dayKey: String,
    val startMs: Long,
    val endMs: Long,
    val distanceMeters: Double,
    val avgHr: Double? = null,
    val maxHr: Long? = null,
    val hrRecovery60s: Long? = null,
)

enum class RecordKind(val wire: String, val higherIsBetter: Boolean) {
    MAX_DAILY_STEPS("max_daily_steps", true),
    LOWEST_RESTING_HR("lowest_resting_hr", false),
    MAX_SESSION_DISTANCE("max_session_distance", true),
    BEST_HR_RECOVERY("best_hr_recovery", true),
    LONGEST_STEP_STREAK("longest_step_streak", true),
    HIGHEST_HRV_RMSSD("highest_hrv_rmssd", true);

    /** Strictly better: ties keep the earlier record. */
    fun isBetter(candidate: Double, current: Double): Boolean =
        if (higherIsBetter) candidate > current else candidate < current

    companion object {
        fun fromWire(s: String): RecordKind =
            entries.firstOrNull { it.wire == s } ?: throw IllegalArgumentException("Unknown record kind: $s")
    }
}

data class PersonalRecord(
    val kind: RecordKind,
    val value: Double,
    val dayKey: String,
    val refId: String? = null,
)
