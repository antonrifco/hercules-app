package com.hercules.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.hercules.shared.WearProtocol
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.WatchState
import kotlinx.coroutines.tasks.await

/**
 * What the watch shows: the phone's view (it has the planned events and the full history) when it is
 * fresh, otherwise a score computed on the watch from its own data. The watch therefore keeps working
 * without the phone, just without the planner.
 */
class WatchStateSource(context: Context, private val cognitive: CognitiveService) {
    private val ctx = context.applicationContext

    suspend fun current(nowMs: Long = System.currentTimeMillis()): WatchState {
        val fromPhone = runCatching { fromPhone() }
            .onFailure { Log.w(TAG, "no phone state", it) }
            .getOrNull()
        val today = cognitive.today()
        if (fromPhone != null && nowMs - fromPhone.updatedMs < STALE_MS && fromPhone.readiness?.dayKey == today) {
            return fromPhone
        }
        val local = runCatching { cognitive.refresh() }.getOrNull()
        return WatchState(
            readiness = local ?: fromPhone?.readiness?.takeIf { it.dayKey == today },
            nextEvent = fromPhone?.nextEvent?.takeIf { it.event.startMs > nowMs - 3 * HOUR_MS },
            updatedMs = nowMs,
        )
    }

    private suspend fun fromPhone(): WatchState? {
        val items = Wearable.getDataClient(ctx).dataItems.await()
        try {
            return items
                .filter { it.uri.path == WearProtocol.STATE_PATH }
                .mapNotNull { DataMapItem.fromDataItem(it).dataMap.getString(WearProtocol.STATE_KEY) }
                .mapNotNull { cognitive.decodeWatchState(it) }
                .maxByOrNull { it.updatedMs }
        } finally {
            items.release()
        }
    }

    private companion object {
        const val TAG = "HerculesState"
        const val HOUR_MS = 3_600_000L
        const val STALE_MS = 6 * HOUR_MS
    }
}
