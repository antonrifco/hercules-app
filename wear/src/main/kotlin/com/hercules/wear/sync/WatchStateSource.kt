package com.hercules.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.hercules.shared.WearProtocol
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.WatchState
import com.hercules.shared.readiness.DeviceKind
import kotlinx.coroutines.tasks.await

/**
 * What the watch shows: the phone's view (it has Health Connect, the moments and the full history) when it is
 * fresh, otherwise a state computed on the watch from its own data. The watch therefore keeps working without the
 * phone, just without the moments.
 *
 * A Beat the Clock session played on the watch counts at once here, so the score and next step update before the
 * phone has had a chance to reply.
 */
class WatchStateSource(context: Context, private val cognitive: CognitiveService) {
    private val ctx = context.applicationContext

    suspend fun current(nowMs: Long = System.currentTimeMillis()): WatchState {
        val fromPhone = runCatching { fromPhone() }
            .onFailure { Log.w(TAG, "no phone state", it) }
            .getOrNull()
        val today = cognitive.today()
        val lastLocal = runCatching { cognitive.store.sessionsFrom(cognitive.dayKeys.startOf(today)).maxOfOrNull { it.endMs } }.getOrNull()
        val phoneFresh = fromPhone != null && nowMs - fromPhone.updatedMs < STALE_MS && fromPhone.evaluation?.dayKey == today
        val localNewer = lastLocal != null && lastLocal > (fromPhone?.updatedMs ?: 0L)
        if (phoneFresh && !localNewer) return fromPhone!!

        val local = runCatching { cognitive.refresh() }.getOrNull()
        val onWatch = runCatching { cognitive.watchState() }.getOrNull() // moments stored on the watch (demo)
        val phoneUpcoming = fromPhone?.upcoming.orEmpty().filter { it.event.startMs > nowMs - 3 * HOUR_MS }
        val upcoming = phoneUpcoming.ifEmpty { onWatch?.upcoming.orEmpty() }
        // The phone sees Health Connect; prefer its evaluation for today unless it has none.
        val phoneEval = fromPhone?.evaluation?.takeIf { it.dayKey == today }
        val evaluation = if (phoneEval?.readiness != null || local == null) phoneEval ?: local else local
        val sleep = runCatching { cognitive.store.sleep(today) }.getOrNull()
        val reflected = fromPhone?.reflectedIds.orEmpty()
        val wakeMs = fromPhone?.wakeMs?.takeIf { phoneFresh } ?: cognitive.wakeMs(today)
        val plays = maxOf(fromPhone?.playsToday?.takeIf { phoneFresh } ?: 0, cognitive.playsToday(today))
        val peak = fromPhone?.peak ?: cognitive.store.latestPeak()
        val brief = runCatching {
            cognitive.daily.brief(
                nowMs = nowMs, r = evaluation?.readiness, lastNight = sleep, events = emptyList(), reflectedIds = reflected,
                baselineWakeMinute = cognitive.typicalWakeMinute(today), wakeMs = wakeMs, peak = peak, playsToday = plays,
                insufficientReason = evaluation?.calc?.insufficientReason,
            )
        }.getOrNull()
        return WatchState(
            readiness = evaluation?.readiness,
            nextEvent = upcoming.firstOrNull() ?: onWatch?.nextEvent,
            updatedMs = nowMs,
            upcoming = upcoming,
            sleepMinutes = sleep?.asleepMinutes ?: fromPhone?.sleepMinutes,
            brief = brief,
            reflectedIds = reflected,
            reminders = fromPhone?.reminders ?: com.hercules.shared.cognitive.NudgePrefs(),
            evaluation = evaluation,
            peak = peak,
            playsToday = plays,
            wakeMs = wakeMs,
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
