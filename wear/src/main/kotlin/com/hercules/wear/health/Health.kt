package com.hercules.wear.health

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.wear.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.hercules.shared.WearProtocol
import com.hercules.shared.sensors.SensorCodec
import com.hercules.shared.sensors.SensorConfig
import com.hercules.shared.sensors.SensorKind
import com.hercules.shared.sensors.SensorReport
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant

private const val TAG = "Hercules"

/** Converts Health Services data points (timestamps relative to boot) into shared [Sample]s. */
object HealthMapper {
    private fun bootInstant(): Instant =
        Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())

    fun toSamples(c: DataPointContainer, source: SampleSource, deviceId: String, sessionId: String? = null): List<Sample> {
        val boot = bootInstant()
        val out = mutableListOf<Sample>()

        fun point(type: MetricType, value: Double, t: Instant) {
            val ms = t.toEpochMilli()
            out += Sample.create(type, value, ms, ms, source, deviceId, sessionId)
        }

        fun interval(type: MetricType, value: Double, start: Instant, end: Instant) {
            if (value <= 0.0) return
            out += Sample.create(type, value, start.toEpochMilli(), end.toEpochMilli(), source, deviceId, sessionId)
        }

        c.getData(DataType.HEART_RATE_BPM).forEach { if (it.value > 0) point(MetricType.HEART_RATE, it.value, it.getTimeInstant(boot)) }
        c.getData(DataType.SPEED).forEach { point(MetricType.SPEED, it.value, it.getTimeInstant(boot)) }
        c.getData(DataType.STEPS).forEach { interval(MetricType.STEPS, it.value.toDouble(), it.getStartInstant(boot), it.getEndInstant(boot)) }
        c.getData(DataType.CALORIES).forEach { interval(MetricType.CALORIES, it.value, it.getStartInstant(boot), it.getEndInstant(boot)) }
        c.getData(DataType.DISTANCE).forEach { interval(MetricType.DISTANCE, it.value, it.getStartInstant(boot), it.getEndInstant(boot)) }
        c.getData(DataType.FLOORS).forEach { interval(MetricType.FLOORS, it.value, it.getStartInstant(boot), it.getEndInstant(boot)) }
        return out
    }

    /**
     * Sleep comes from the watch's own activity-state classifier (awake / asleep / exercise).
     * We store each state change as a point; the shared SleepAnalyzer turns them into nights.
     */
    fun toSample(info: UserActivityInfo, deviceId: String): Sample {
        val value = when (info.userActivityState) {
            UserActivityState.USER_ACTIVITY_ASLEEP -> ActivityState.ASLEEP
            UserActivityState.USER_ACTIVITY_PASSIVE -> ActivityState.AWAKE
            UserActivityState.USER_ACTIVITY_EXERCISE -> ActivityState.EXERCISE
            else -> ActivityState.UNKNOWN
        }
        val ms = info.stateChangeTime.toEpochMilli()
        return Sample.create(MetricType.ACTIVITY_STATE, value, ms, ms, SampleSource.PASSIVE, deviceId)
    }
}

data class PermissionState(
    val heartRate: Boolean,
    val background: Boolean,
    val activity: Boolean,
    val notifications: Boolean,
) {
    val canTrack: Boolean get() = heartRate && activity
}

object Permissions {
    private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    private const val READ_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    private val isApi36 get() = Build.VERSION.SDK_INT >= 36

    private val heartRatePermission get() = if (isApi36) READ_HEART_RATE else Manifest.permission.BODY_SENSORS

    fun foreground(): Array<String> = buildList {
        add(heartRatePermission)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    /** Must be requested on its own, after foreground access is granted. Null below API 33. */
    fun background(): String? = when {
        isApi36 -> READ_IN_BACKGROUND
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.BODY_SENSORS_BACKGROUND
        else -> null
    }

    fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun state(ctx: Context) = PermissionState(
        heartRate = has(ctx, heartRatePermission),
        background = background()?.let { has(ctx, it) } ?: true,
        activity = has(ctx, Manifest.permission.ACTIVITY_RECOGNITION),
        notifications = Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS),
    )
}

/** Bound by the system whenever passive data is ready — even if our process was dead. */
class PassiveDataService : PassiveListenerService() {
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val g = applicationContext.graph
        val samples = HealthMapper.toSamples(dataPoints, SampleSource.PASSIVE, g.deviceId)
        if (samples.isEmpty()) return
        // Small batches; block briefly so the write completes before the system unbinds us.
        runBlocking(Dispatchers.IO) { g.repo.insertSamples(samples) }
    }

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val g = applicationContext.graph
        val sample = HealthMapper.toSample(info, g.deviceId)
        Log.i(TAG, "Activity state -> ${info.userActivityState.name}")
        runBlocking(Dispatchers.IO) {
            g.repo.insertSamples(listOf(sample))
            // Waking up is the moment a new readiness score becomes meaningful.
            if (sample.value == ActivityState.AWAKE) runCatching { g.cognitive.refresh() }
        }
    }
}

object PassiveRegistrar {
    private const val KEY = "passive_enabled"
    private val dataTypes: Map<SensorKind, DataType<*, *>> = mapOf(
        SensorKind.HEART_RATE to DataType.HEART_RATE_BPM,
        SensorKind.STEPS to DataType.STEPS,
        SensorKind.CALORIES to DataType.CALORIES,
        SensorKind.DISTANCE to DataType.DISTANCE,
        SensorKind.FLOORS to DataType.FLOORS,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hercules", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY, false)

    /** Sensors this watch can record in the background. Sleep relies on the activity-state classifier. */
    suspend fun supported(ctx: Context): Set<SensorKind> {
        val caps = HealthServices.getClient(ctx).passiveMonitoringClient.getCapabilitiesAsync().await()
        val types = caps.supportedDataTypesPassiveMonitoring
        return dataTypes.filterValues { it in types }.keys + SensorKind.SLEEP
    }

    /**
     * (Re-)registers passive monitoring for the sensors the user enabled ([SensorSettings]) that the
     * hardware supports, then tells the phone what is active. Returns the active set.
     */
    suspend fun enable(ctx: Context): Set<SensorKind> {
        val client = HealthServices.getClient(ctx).passiveMonitoringClient
        val supported = supported(ctx)
        val active = SensorSettings.load(ctx).enabled intersect supported
        val types = active.mapNotNull { dataTypes[it] }.toSet()
        Log.i(TAG, "Passive monitoring: ${active.map { it.name }}")
        if (active.isEmpty()) {
            client.clearPassiveListenerServiceAsync().await()
        } else {
            val config = PassiveListenerConfig.builder()
                .setDataTypes(types)
                .setShouldUserActivityInfoBeRequested(SensorKind.SLEEP in active) // awake/asleep → sleep tracking
                .build()
            client.setPassiveListenerServiceAsync(PassiveDataService::class.java, config).await()
        }
        prefs(ctx).edit { putBoolean(KEY, true) }
        SensorSettings.report(ctx, SensorReport(supported, active, System.currentTimeMillis()))
        return active
    }

    suspend fun disable(ctx: Context) {
        HealthServices.getClient(ctx).passiveMonitoringClient.clearPassiveListenerServiceAsync().await()
        prefs(ctx).edit { putBoolean(KEY, false) }
    }
}

/** The user's sensor choice on the watch, mirrored to/from the phone over the Data Layer. */
object SensorSettings {
    private const val KEY = "sensor_config"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hercules", Context.MODE_PRIVATE)

    fun load(ctx: Context): SensorConfig =
        prefs(ctx).getString(KEY, null)?.let { SensorCodec.decodeConfig(it) } ?: SensorConfig.DEFAULT

    private fun save(ctx: Context, c: SensorConfig) = prefs(ctx).edit { putString(KEY, SensorCodec.encode(c)) }

    /** Changed on the watch: store, re-register, and tell the phone. */
    suspend fun set(ctx: Context, kind: SensorKind, on: Boolean): Set<SensorKind> {
        val c = load(ctx).with(kind, on, System.currentTimeMillis())
        save(ctx, c)
        runCatching { putItem(ctx, WearProtocol.SENSOR_CONFIG_PATH, SensorCodec.encode(c)) }
            .onFailure { Log.w(TAG, "could not share sensor config", it) }
        return PassiveRegistrar.enable(ctx)
    }

    /** Changed on the phone: apply only if newer than what we have. */
    suspend fun applyRemote(ctx: Context, remote: SensorConfig) {
        val local = load(ctx)
        if (SensorCodec.newest(local, remote) === local) return
        save(ctx, remote)
        if (Permissions.state(ctx).canTrack) PassiveRegistrar.enable(ctx)
    }

    suspend fun report(ctx: Context, r: SensorReport) {
        runCatching { putItem(ctx, WearProtocol.SENSOR_REPORT_PATH, SensorCodec.encode(r)) }
            .onFailure { Log.w(TAG, "could not send sensor report", it) }
    }

    private suspend fun putItem(ctx: Context, path: String, json: String) {
        val req = PutDataMapRequest.create(path).apply { dataMap.putString(WearProtocol.STATE_KEY, json) }
            .asPutDataRequest().setUrgent()
        Wearable.getDataClient(ctx).putDataItem(req).await()
    }
}

/** Receives sensor choices made in the phone app. */
class SensorConfigListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val remote = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearProtocol.SENSOR_CONFIG_PATH }
            .mapNotNull { DataMapItem.fromDataItem(it.dataItem).dataMap.getString(WearProtocol.STATE_KEY) }
            .mapNotNull { SensorCodec.decodeConfig(it) }
            .maxByOrNull { it.updatedMs } ?: return
        runBlocking(Dispatchers.IO) { runCatching { SensorSettings.applyRemote(applicationContext, remote) } }
    }
}

/** Passive registrations don't survive a reboot: re-register if the user had tracking on. */
class StartupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!PassiveRegistrar.isEnabled(context) || !Permissions.state(context).canTrack) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                PassiveRegistrar.enable(context)
            } catch (e: Exception) {
                Log.e(TAG, "Re-register after boot failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
