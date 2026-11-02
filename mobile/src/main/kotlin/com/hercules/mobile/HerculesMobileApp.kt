package com.hercules.mobile

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.hercules.shared.sensors.SensorCodec
import com.hercules.shared.sensors.SensorConfig
import com.hercules.shared.sensors.SensorReport
import com.google.android.gms.wearable.Wearable
import com.hercules.shared.DeviceId
import com.hercules.shared.WearProtocol
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.createHerculesDatabase
import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.insights.InsightsService
import com.hercules.shared.insights.RetentionPolicy
import com.hercules.shared.sync.NoopRemoteSink
import com.hercules.shared.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

/**
 * Flip to true and replace [NoopRemoteSink] with an HttpRemoteSink (Ktor) once the backend exists.
 * Everything received from the watch is already queued (synced = 0) for that moment.
 */
const val SERVER_SYNC_ENABLED = false

class MobileGraph(private val context: Context) {
    val deviceId: String = DeviceId.get(context)
    private val db = createHerculesDatabase(context)
    val repo = HerculesRepository(db, Dispatchers.IO)
    val insights = InsightsService(repo, clock = System::currentTimeMillis)
    val retention = RetentionPolicy(repo)
    val cognitive = CognitiveService(repo, CognitiveStore(db, Dispatchers.IO), clock = System::currentTimeMillis)
    val serverSync = SyncService(repo, NoopRemoteSink, deviceId, clock = System::currentTimeMillis)

    private val prefs = context.getSharedPreferences("hercules", Context.MODE_PRIVATE)
    private val _lastWatchSync = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 })
    val lastWatchSync: StateFlow<Long?> = _lastWatchSync

    fun markWatchSync(atMs: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, atMs) }
        _lastWatchSync.value = atMs
    }

    /** Recompute readiness and give the watch the latest score + next event. Safe to call often. */
    suspend fun refreshAndPushToWatch() {
        runCatching { cognitive.refresh() }.onFailure { Log.e(TAG, "readiness refresh failed", it) }
        pushToWatch()
    }

    /** Sends the current readiness + next event to the watch (DataClient, delivered when connected). */
    suspend fun pushToWatch() {
        runCatching {
            val json = cognitive.encodeWatchState(cognitive.watchState())
            val req = PutDataMapRequest.create(WearProtocol.STATE_PATH).apply {
                dataMap.putString(WearProtocol.STATE_KEY, json)
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(req).await()
        }.onFailure { Log.w(TAG, "could not push state to watch", it) }
    }

    /** Current sensor choice (newest written by either device) and the watch's last report. */
    suspend fun sensorState(): Pair<SensorConfig, SensorReport?> {
        val items = Wearable.getDataClient(context).dataItems.await()
        try {
            fun jsonAt(path: String) = items.filter { it.uri.path == path }
                .mapNotNull { DataMapItem.fromDataItem(it).dataMap.getString(WearProtocol.STATE_KEY) }
            val config = jsonAt(WearProtocol.SENSOR_CONFIG_PATH).mapNotNull { SensorCodec.decodeConfig(it) }
                .maxByOrNull { it.updatedMs } ?: SensorConfig.DEFAULT
            val report = jsonAt(WearProtocol.SENSOR_REPORT_PATH).mapNotNull { SensorCodec.decodeReport(it) }
                .maxByOrNull { it.updatedMs }
            return config to report
        } finally {
            items.release()
        }
    }

    /** Sends a new sensor choice to the watch; it re-registers and reports back. */
    suspend fun setSensors(config: SensorConfig) {
        val req = PutDataMapRequest.create(WearProtocol.SENSOR_CONFIG_PATH).apply {
            dataMap.putString(WearProtocol.STATE_KEY, SensorCodec.encode(config))
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(req).await()
    }

    private companion object {
        const val TAG = "Hercules"
        const val KEY_LAST_SYNC = "last_watch_sync"
    }
}

class HerculesMobileApp : Application() {
    lateinit var graph: MobileGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = MobileGraph(this)
        com.hercules.mobile.notify.Notifier.ensureChannel(this)
        com.hercules.mobile.notify.ReminderWorker.schedule(this)
    }
}

val Context.graph: MobileGraph get() = (applicationContext as HerculesMobileApp).graph
