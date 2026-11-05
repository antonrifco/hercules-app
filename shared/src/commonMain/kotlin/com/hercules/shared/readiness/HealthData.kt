package com.hercules.shared.readiness

import kotlinx.serialization.Serializable

/** Normalized metric types stored in `health_metric` (spec §18.1). New sensors add a type, not a table. */
@Serializable
enum class HealthMetricType(val label: String, val unit: String, val scored: Boolean) {
    HRV_RMSSD("HRV (RMSSD)", "ms", true),
    RESTING_HR("Resting heart rate", "bpm", true),
    STEPS_DAILY("Steps", "count", true),
    RESPIRATORY_RATE("Respiratory rate", "breaths/min", false),
    SKIN_TEMPERATURE_DELTA("Skin temperature deviation", "°C", false),
    SPO2("Blood oxygen", "%", false),
    HEART_RATE("Heart rate", "bpm", false),
    BLOOD_GLUCOSE("Blood glucose", "mmol/L", false),
}

@Serializable
enum class DataQuality { VALID, INVALID }

@Serializable
enum class CollectionMethod { HEALTH_CONNECT, WATCH, PHONE_SENSOR, DERIVED }

@Serializable
data class HealthMetric(
    val id: String,
    val type: HealthMetricType,
    val value: Double,
    val measuredMs: Long,
    val endMs: Long,
    val recordedMs: Long,
    /** Day the value belongs to. Night-time metrics (HRV, resting HR) belong to the morning they lead into. */
    val dayKey: String,
    val sourcePackage: String,
    val sourceDevice: String?,
    val quality: DataQuality,
    val collection: CollectionMethod,
    val metadata: String? = null,
)

/** A sleep session as a provider reported it. */
data class SleepSessionRecord(
    val id: String,
    val dayKey: String,
    val startMs: Long,
    val endMs: Long,
    val asleepMinutes: Int,
    val awakeMinutes: Int?,
    val deepMinutes: Int?,
    val remMinutes: Int?,
    val lightMinutes: Int?,
    val sourcePackage: String,
    val sourceDevice: String?,
    val quality: DataQuality,
)

data class ExerciseRecord(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val exerciseType: Int,
    val sourcePackage: String,
    val sourceDevice: String?,
)

@Serializable
enum class LightContext { FOREGROUND, BACKGROUND }

data class LightSample(val id: String, val lux: Double, val measuredMs: Long, val sensor: String, val context: LightContext)

// ------------------------------------------------------------------ capabilities (spec §12)

@Serializable
enum class CapabilityStatus(val label: String) {
    AVAILABLE("Available"),
    PERMISSION_REQUIRED("Permission needed"),
    HARDWARE_UNAVAILABLE("Not on this device"),
    PROVIDER_UNAVAILABLE("No provider"),
    NO_RECENT_DATA("No recent data"),
    INVALID_DATA("Data invalid"),
    TEMPORARILY_UNAVAILABLE("Temporarily unavailable"),
}

@Serializable
enum class CapabilityCategory { PHONE_SENSOR, WATCH_SENSOR, HEALTH_CONNECT_RECORD, HEALTH_SERVICES, PAIRED_DEVICE }

@Serializable
enum class SensorCapability(val label: String, val category: CapabilityCategory, val required: Boolean) {
    HC_SLEEP("Sleep sessions", CapabilityCategory.HEALTH_CONNECT_RECORD, true),
    HC_HRV("Heart rate variability", CapabilityCategory.HEALTH_CONNECT_RECORD, true),
    HC_RESTING_HR("Resting heart rate", CapabilityCategory.HEALTH_CONNECT_RECORD, true),
    HC_STEPS("Steps", CapabilityCategory.HEALTH_CONNECT_RECORD, true),
    HC_EXERCISE("Exercise sessions", CapabilityCategory.HEALTH_CONNECT_RECORD, true),
    HC_RESPIRATORY_RATE("Respiratory rate", CapabilityCategory.HEALTH_CONNECT_RECORD, false),
    HC_SKIN_TEMPERATURE("Skin temperature", CapabilityCategory.HEALTH_CONNECT_RECORD, false),
    HC_SPO2("Blood oxygen", CapabilityCategory.HEALTH_CONNECT_RECORD, false),
    HC_HEART_RATE("Heart rate", CapabilityCategory.HEALTH_CONNECT_RECORD, false),
    HC_BLOOD_GLUCOSE("Continuous glucose", CapabilityCategory.HEALTH_CONNECT_RECORD, false),
    PHONE_LIGHT("Ambient light sensor", CapabilityCategory.PHONE_SENSOR, false),
    PAIRED_WATCH("Hercules on your watch", CapabilityCategory.PAIRED_DEVICE, false),
}

@Serializable
data class CapabilityState(val capability: SensorCapability, val status: CapabilityStatus, val latestMs: Long? = null, val detail: String? = null)

/** Unified capability lookup (spec §12.2). Platform code implements it; the scoring engine never sees it. */
interface SensorCapabilityRepository {
    suspend fun getAvailableCapabilities(): Set<SensorCapability>
    suspend fun isAvailable(capability: SensorCapability): Boolean
    suspend fun states(): List<CapabilityState>
}
