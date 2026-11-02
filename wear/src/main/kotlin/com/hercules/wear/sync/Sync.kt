package com.hercules.wear.sync

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.hercules.shared.WearProtocol
import com.hercules.shared.sync.RemoteSink
import com.hercules.shared.sync.SyncBatch
import com.hercules.shared.sync.SyncResult
import com.hercules.shared.sync.WireCodec
import com.hercules.wear.graph
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/**
 * Sends batches to the Hercules phone app over the Wearable Data Layer (Bluetooth/Wi-Fi,
 * handled by the system). The phone acks the ids it stored; only those are marked synced.
 */
class PhoneRemoteSink(context: Context) : RemoteSink {
    private val ctx = context.applicationContext

    override suspend fun push(batch: SyncBatch): SyncResult {
        return try {
            val nodes = Wearable.getCapabilityClient(ctx)
                .getCapability(WearProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                ?: return SyncResult.Rejected(retryable = true, reason = "phone app not reachable")
            val reply = Wearable.getMessageClient(ctx)
                .sendRequest(node.id, WearProtocol.SYNC_PATH, WireCodec.encodeBatch(batch))
                .await()
            val ack = WireCodec.decodeAck(reply)
            SyncResult.Accepted(ack.sampleIds, ack.sessionIds)
        } catch (e: Exception) {
            SyncResult.Rejected(retryable = true, reason = "phone transfer failed: ${e.message}")
        }
    }
}

/** Periodic housekeeping: summaries/records → retention → hand data to the phone. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        return try {
            val broken = g.insights.refresh()
            g.retention.apply(System.currentTimeMillis())
            val out = g.phoneSync.syncOnce()
            SyncStatus.record(applicationContext, out.stoppedReason, delivered = out.samplesSynced + out.sessionsSynced)
            Log.i(TAG, "sync: newRecords=${broken.size} samples=${out.samplesSynced} sessions=${out.sessionsSynced} stopped=${out.stoppedReason}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "sync worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "HerculesSync"

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hercules-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /**
         * [ExistingWorkPolicy.KEEP] (default) never cancels a sync that is already running;
         * use [ExistingWorkPolicy.APPEND_OR_REPLACE] to guarantee one more run after it (e.g. after a workout).
         */
        fun runNow(ctx: Context, delayMs: Long = 0, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("hercules-sync-now", policy, req)
        }
    }
}

/** Outcome of the last hand-off to the phone, shown on the watch so the user can see data is flowing. */
object SyncStatus {
    data class State(val lastOkMs: Long?, val lastAttemptMs: Long?, val problem: String?)

    private const val PREFS = "hercules_sync"
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<State?>(null)

    fun flow(ctx: Context): kotlinx.coroutines.flow.StateFlow<State?> {
        if (_state.value == null) _state.value = load(ctx)
        return _state
    }

    /** "ok" only moves when the phone actually acknowledged data; an empty run just clears the problem. */
    fun record(ctx: Context, stoppedReason: String?, delivered: Int, nowMs: Long = System.currentTimeMillis()) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit {
            putLong("attempt", nowMs)
            if (delivered > 0) putLong("ok", nowMs)
            if (stoppedReason == null) remove("problem") else putString("problem", stoppedReason)
        }
        _state.value = load(ctx)
    }

    private fun load(ctx: Context): State {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return State(
            lastOkMs = p.getLong("ok", 0L).takeIf { it > 0 },
            lastAttemptMs = p.getLong("attempt", 0L).takeIf { it > 0 },
            problem = p.getString("problem", null),
        )
    }
}
