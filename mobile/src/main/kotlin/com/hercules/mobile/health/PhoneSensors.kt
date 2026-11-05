package com.hercules.mobile.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.android.gms.wearable.Wearable
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.readiness.CapabilityState
import com.hercules.shared.readiness.CapabilityStatus
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.HealthMetricType
import com.hercules.shared.readiness.LightContext
import com.hercules.shared.readiness.LightSample
import com.hercules.shared.readiness.SensorCapability
import com.hercules.shared.readiness.SensorCapabilityRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's ambient light sensor (`Sensor.TYPE_LIGHT`). Samples a single reading at a time: while the app is open
 * ([LightContext.FOREGROUND]) and opportunistically from WorkManager ([LightContext.BACKGROUND]). Never continuous.
 */
class LightSampler(context: Context, private val store: CognitiveStore) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)

    val available: Boolean get() = sensor != null
    val sensorName: String? get() = sensor?.let { "${it.vendor} ${it.name}".trim() }

    /** One reading, stored; null when there is no sensor or it didn't answer within [timeoutMs]. */
    suspend fun sampleOnce(context: LightContext, timeoutMs: Long = 3_000): LightSample? {
        val s = sensor ?: return null
        val lux = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Double?> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(e.values.firstOrNull()?.toDouble())
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        } ?: return null
        if (lux.isNaN() || lux < 0.0 || lux > 200_000.0) return null
        val now = System.currentTimeMillis()
        val sample = LightSample("lux:$now", lux, now, sensorName ?: "light", context)
        store.insertLight(listOf(sample))
        return sample
    }
}

/**
 * Availability of everything Hercules can read on the phone (spec §12): Health Connect record types, the light
 * sensor and the paired watch. "Available" never implies that recent valid data exists; that's NO_RECENT_DATA.
 */
class PhoneCapabilities(
    private val context: Context,
    private val hc: HealthConnectImporter,
    private val light: LightSampler,
    private val store: CognitiveStore,
    private val dayKeys: com.hercules.shared.insights.DayKeys,
    private val lastWatchSync: () -> Long?,
) : SensorCapabilityRepository {

    private val metricOf = mapOf(
        SensorCapability.HC_HRV to HealthMetricType.HRV_RMSSD,
        SensorCapability.HC_RESTING_HR to HealthMetricType.RESTING_HR,
        SensorCapability.HC_STEPS to HealthMetricType.STEPS_DAILY,
        SensorCapability.HC_RESPIRATORY_RATE to HealthMetricType.RESPIRATORY_RATE,
        SensorCapability.HC_SKIN_TEMPERATURE to HealthMetricType.SKIN_TEMPERATURE_DELTA,
        SensorCapability.HC_SPO2 to HealthMetricType.SPO2,
        SensorCapability.HC_HEART_RATE to HealthMetricType.HEART_RATE,
        SensorCapability.HC_BLOOD_GLUCOSE to HealthMetricType.BLOOD_GLUCOSE,
    )

    override suspend fun states(): List<CapabilityState> {
        val now = System.currentTimeMillis()
        val hcState = hc.state()
        val granted = if (hcState == HealthConnectState.AVAILABLE) hc.granted() else emptySet()
        val stats = store.metricStats().associateBy { it.type }
        val out = mutableListOf<CapabilityState>()
        for (cap in HealthPermissions.byCapability.keys) {
            val latest: Long? = when (cap) {
                SensorCapability.HC_SLEEP -> store.sleep(dayKeys.of(now))?.endMs
                    ?: store.sleepRange(dayKeys.of(now - 3 * DAY), dayKeys.of(now)).lastOrNull()?.endMs
                SensorCapability.HC_EXERCISE -> store.exerciseBetween(now - 14 * DAY, now).maxOfOrNull { it.endMs }
                else -> metricOf[cap]?.let { stats[it]?.latestMs }
            }
            val status = when {
                hcState != HealthConnectState.AVAILABLE -> CapabilityStatus.PROVIDER_UNAVAILABLE
                cap == SensorCapability.HC_SKIN_TEMPERATURE && !hc.skinTemperatureSupported() -> CapabilityStatus.PROVIDER_UNAVAILABLE
                HealthPermissions.of(cap) !in granted -> CapabilityStatus.PERMISSION_REQUIRED
                latest == null || now - latest > (if (cap == SensorCapability.HC_EXERCISE) 14 * DAY else 2 * DAY) -> CapabilityStatus.NO_RECENT_DATA
                cap == SensorCapability.HC_SLEEP && store.sleepSessions(dayKeys.of(now)).any { it.quality == DataQuality.INVALID } ->
                    CapabilityStatus.INVALID_DATA
                else -> CapabilityStatus.AVAILABLE
            }
            out += CapabilityState(cap, status, latest)
        }
        val lastLux = store.latestLight()?.measuredMs
        out += CapabilityState(
            SensorCapability.PHONE_LIGHT,
            when {
                !light.available -> CapabilityStatus.HARDWARE_UNAVAILABLE
                lastLux == null || now - lastLux > 2 * DAY -> CapabilityStatus.NO_RECENT_DATA
                else -> CapabilityStatus.AVAILABLE
            },
            lastLux,
            light.sensorName,
        )
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrNull()
        val sync = lastWatchSync()
        out += CapabilityState(
            SensorCapability.PAIRED_WATCH,
            when {
                // No Wear OS support on this phone (or never linked): there is no watch, not a hiccup.
                (nodes == null || nodes.isEmpty()) && sync == null -> CapabilityStatus.HARDWARE_UNAVAILABLE
                nodes == null -> CapabilityStatus.TEMPORARILY_UNAVAILABLE
                nodes.isEmpty() -> CapabilityStatus.TEMPORARILY_UNAVAILABLE
                sync == null || now - sync > DAY -> CapabilityStatus.NO_RECENT_DATA
                else -> CapabilityStatus.AVAILABLE
            },
            sync,
            nodes?.joinToString { it.displayName },
        )
        return out
    }

    override suspend fun getAvailableCapabilities(): Set<SensorCapability> =
        states().filter { it.status == CapabilityStatus.AVAILABLE || it.status == CapabilityStatus.NO_RECENT_DATA }.map { it.capability }.toSet()

    override suspend fun isAvailable(capability: SensorCapability): Boolean = capability in getAvailableCapabilities()

    private companion object { const val DAY = 86_400_000L }
}
