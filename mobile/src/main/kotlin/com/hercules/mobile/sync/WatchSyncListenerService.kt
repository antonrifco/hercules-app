package com.hercules.mobile.sync

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.WearableListenerService
import com.hercules.mobile.graph
import com.hercules.shared.WearProtocol
import com.hercules.shared.sync.WireCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives a sync batch from the watch, stores it durably, recomputes insights, and replies
 * with the ids it stored. The watch only marks those ids as synced, so nothing is lost if the
 * phone dies mid-transfer; deterministic ids make retries harmless.
 */
class WatchSyncListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != WearProtocol.SYNC_PATH) return null
        val result = TaskCompletionSource<ByteArray>()
        scope.launch {
            try {
                val g = applicationContext.graph
                val (batch, ack) = WireCodec.decodeBatchWithAck(request)
                g.repo.insertSamples(batch.samples)
                g.repo.upsertSummarizedSessions(batch.sessions)
                g.insights.refresh(g.insights.daysOf(batch.samples) + batch.sessions.map { it.dayKey })
                g.markWatchSync(System.currentTimeMillis())
                Log.i(TAG, "stored ${batch.samples.size} samples, ${batch.sessions.size} sessions from ${batch.deviceId}")
                result.setResult(WireCodec.encodeAck(ack))
                // New sleep/HR data can change today's score: recompute and send it back to the watch.
                g.refreshAndPushToWatch()
            } catch (e: Exception) {
                Log.e(TAG, "failed to store watch batch", e)
                result.setException(e)
            }
        }
        return result.task
    }

    private companion object {
        const val TAG = "HerculesPhoneSync"
    }
}
