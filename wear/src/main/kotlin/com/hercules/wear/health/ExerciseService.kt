package com.hercules.wear.health

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.lifecycle.LifecycleService
import androidx.work.ExistingWorkPolicy
import androidx.lifecycle.lifecycleScope
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.hercules.shared.insights.SessionSummarizer
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.SampleSource
import com.hercules.wear.R
import com.hercules.wear.graph
import com.hercules.wear.sync.SyncWorker
import com.hercules.wear.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.UUID

enum class WorkoutKind(val wire: String, val label: String, val hsType: ExerciseType) {
    RUN("run", "Lari", ExerciseType.RUNNING),
    WALK("walk", "Jalan", ExerciseType.WALKING),
    BIKE("bike", "Sepeda", ExerciseType.BIKING),
    OTHER("workout", "Latihan lain", ExerciseType.WORKOUT),
}

/** Live workout state observed by the UI (same process as the service). */
object LiveWorkout {
    enum class Phase { IDLE, STARTING, ACTIVE, RECOVERY }

    internal val phase = MutableStateFlow(Phase.IDLE)
    internal val heartRate = MutableStateFlow<Double?>(null)
    internal val startedAtMs = MutableStateFlow<Long?>(null)
    internal val kind = MutableStateFlow<WorkoutKind?>(null)

    val phaseFlow: StateFlow<Phase> get() = phase
    val heartRateFlow: StateFlow<Double?> get() = heartRate
    val startedAtFlow: StateFlow<Long?> get() = startedAtMs
    val kindFlow: StateFlow<WorkoutKind?> get() = kind
}

/**
 * High-frequency tracking during a workout. Runs as a `health` foreground service with a
 * Wear OngoingActivity so it keeps going with the screen off. After the user stops, HR keeps
 * being measured for ~75 s to compute 1-minute heart-rate recovery.
 */
class ExerciseService : LifecycleService() {

    companion object {
        private const val ACTION_START = "com.hercules.wear.START_WORKOUT"
        private const val ACTION_STOP = "com.hercules.wear.STOP_WORKOUT"
        private const val EXTRA_KIND = "kind"
        private const val CHANNEL = "workout"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "HerculesWorkout"

        private val WANTED: Set<DataType<*, *>> = setOf(
            DataType.HEART_RATE_BPM, DataType.SPEED, DataType.STEPS, DataType.CALORIES, DataType.DISTANCE,
        )

        fun start(ctx: Context, kind: WorkoutKind) {
            LiveWorkout.phase.value = LiveWorkout.Phase.STARTING
            ctx.startForegroundService(
                Intent(ctx, ExerciseService::class.java).setAction(ACTION_START).putExtra(EXTRA_KIND, kind.name)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ExerciseService::class.java).setAction(ACTION_STOP))
        }
    }

    private val hs by lazy { HealthServices.getClient(this) }
    private var sessionId: String? = null

    private val updateCallback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            record(update.latestMetrics, SampleSource.EXERCISE)
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Exercise callback registration failed", throwable)
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
    }

    private val recoveryCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit
        override fun onDataReceived(data: DataPointContainer) = record(data, SampleSource.MEASURE)
    }

    private fun record(container: DataPointContainer, source: SampleSource) {
        val g = applicationContext.graph
        val samples = HealthMapper.toSamples(container, source, g.deviceId, sessionId)
        if (samples.isEmpty()) return
        samples.lastOrNull { it.type == MetricType.HEART_RATE }?.let { LiveWorkout.heartRate.value = it.value }
        lifecycleScope.launch { g.repo.insertSamples(samples) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val kind = runCatching { WorkoutKind.valueOf(intent.getStringExtra(EXTRA_KIND)!!) }.getOrDefault(WorkoutKind.OTHER)
                goForeground(kind)
                lifecycleScope.launch { begin(kind) }
            }
            ACTION_STOP -> lifecycleScope.launch { end() }
        }
        return START_NOT_STICKY
    }

    private suspend fun begin(kind: WorkoutKind) {
        if (sessionId != null) return
        val g = applicationContext.graph
        try {
            val caps = hs.exerciseClient.getCapabilitiesAsync().await()
                .getExerciseTypeCapabilities(kind.hsType).supportedDataTypes
            val config = ExerciseConfig.builder(kind.hsType)
                .setDataTypes(WANTED.filter { it in caps }.toSet())
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(false)
                .build()
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            g.repo.startSession(id, kind.wire, g.deviceId, now)
            sessionId = id
            hs.exerciseClient.setUpdateCallback(updateCallback)
            hs.exerciseClient.startExerciseAsync(config).await()
            LiveWorkout.kind.value = kind
            LiveWorkout.startedAtMs.value = now
            LiveWorkout.heartRate.value = null
            LiveWorkout.phase.value = LiveWorkout.Phase.ACTIVE
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start workout", e)
            sessionId?.let { g.repo.endSession(it, System.currentTimeMillis()) }
            sessionId = null
            finish()
        }
    }

    private suspend fun end() {
        val id = sessionId ?: return finish()
        val g = applicationContext.graph
        g.repo.endSession(id, System.currentTimeMillis())
        runCatching { hs.exerciseClient.endExerciseAsync().await() }
        runCatching { hs.exerciseClient.clearUpdateCallbackAsync(updateCallback).await() }

        LiveWorkout.phase.value = LiveWorkout.Phase.RECOVERY
        runCatching {
            hs.measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, recoveryCallback)
            delay(SessionSummarizer.RECOVERY_WINDOW_MS)
            hs.measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, recoveryCallback).await()
        }.onFailure { Log.w(TAG, "Recovery measurement failed", it) }

        sessionId = null
        // Summarize the session, update records and hand everything to the phone.
        SyncWorker.runNow(applicationContext, delayMs = 2_000, policy = ExistingWorkPolicy.APPEND_OR_REPLACE)
        finish()
    }

    private fun finish() {
        LiveWorkout.phase.value = LiveWorkout.Phase.IDLE
        LiveWorkout.startedAtMs.value = null
        LiveWorkout.kind.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(kind: WorkoutKind) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Latihan", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Hercules · ${kind.label}")
            .setContentText("Merekam detak jantung")
            .setSmallIcon(R.drawable.ic_hercules)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_hercules)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate("${kind.label} berlangsung").build())
            .build()
            .apply(applicationContext)
        // The `health` FGS type exists from API 34 (Wear OS 5); older watches start without a type.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), type)
    }
}
