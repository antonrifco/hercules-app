package com.hercules.shared.sensors

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Background sensors the user can switch on/off. [feeds] names what the readiness score loses
 * when the sensor is off, so the UI can show the trade-off.
 */
@Serializable
enum class SensorKind(val label: String, val feeds: String) {
    HEART_RATE("Heart rate", "Recovery"),
    SLEEP("Sleep", "Readiness"),
    STEPS("Steps", "Activity"),
    CALORIES("Calories", "Stats"),
    DISTANCE("Distance", "Stats"),
    FLOORS("Floors", "Stats"),
}

/** The user's choice. Either device may change it; the newest [updatedMs] wins. */
@Serializable
data class SensorConfig(val enabled: Set<SensorKind>, val updatedMs: Long) {
    fun with(kind: SensorKind, on: Boolean, nowMs: Long) =
        SensorConfig(if (on) enabled + kind else enabled - kind, nowMs)

    companion object {
        val DEFAULT = SensorConfig(SensorKind.entries.toSet(), 0L)
    }
}

/** What the watch reports back: what its hardware supports and what is actually being recorded. */
@Serializable
data class SensorReport(val supported: Set<SensorKind>, val active: Set<SensorKind>, val updatedMs: Long)

object SensorCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(c: SensorConfig): String = json.encodeToString(SensorConfig.serializer(), c)
    fun decodeConfig(s: String): SensorConfig? = runCatching { json.decodeFromString(SensorConfig.serializer(), s) }.getOrNull()
    fun encode(r: SensorReport): String = json.encodeToString(SensorReport.serializer(), r)
    fun decodeReport(s: String): SensorReport? = runCatching { json.decodeFromString(SensorReport.serializer(), s) }.getOrNull()

    /** Picks the newer of two configs (ties keep [local]). */
    fun newest(local: SensorConfig, remote: SensorConfig?): SensorConfig =
        if (remote != null && remote.updatedMs > local.updatedMs) remote else local
}
