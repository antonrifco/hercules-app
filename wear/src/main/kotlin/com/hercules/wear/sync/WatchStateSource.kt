package com.hercules.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.hercules.shared.WearProtocol
import com.hercules.shared.cognitive.CheckIn
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.WatchState
import kotlinx.coroutines.tasks.await

/**
 * What the watch shows: the phone's view (it has the planned events and the full history) when it is
 * fresh, otherwise a score computed on the watch from its own data. The watch therefore keeps working
 * without the phone, just without the planner.
 *
 * A check-in made on the watch is applied here at once, so the score and the next step update before the
 * phone has had a chance to reply.
 */
class WatchStateSource(context: Context, private val cognitive: CognitiveService) {
    private val ctx = context.applicationContext

    suspend fun current(nowMs: Long = System.currentTimeMillis()): WatchState {
        val fromPhone = runCatching { fromPhone() }
            .onFailure { Log.w(TAG, "no phone state", it) }
            .getOrNull()
        val today = cognitive.today()
        val localCheckIn = runCatching { cognitive.store.checkIn(today) }.getOrNull()
        val phoneFresh = fromPhone != null && nowMs - fromPhone.updatedMs < STALE_MS && fromPhone.readiness?.dayKey == today
        val localNewer = localCheckIn != null && localCheckIn.createdMs > (fromPhone?.updatedMs ?: 0L)
        if (phoneFresh && !localNewer) return fromPhone!!

        val local = runCatching { cognitive.refresh() }.getOrNull()
        val onWatch = runCatching { cognitive.watchState() }.getOrNull() // events stored on the watch (demo)
        val phoneUpcoming = fromPhone?.upcoming.orEmpty().filter { it.event.startMs > nowMs - 3 * HOUR_MS }
        val upcoming = phoneUpcoming.ifEmpty { onWatch?.upcoming.orEmpty() }
        val readiness = local ?: fromPhone?.readiness?.takeIf { it.dayKey == today }
        val checkIn = localCheckIn ?: fromPhone?.checkIn
        val sleep = runCatching { cognitive.store.sleep(today) }.getOrNull()
        val reflected = fromPhone?.reflectedIds.orEmpty()
        val brief = runCatching {
            cognitive.daily.brief(nowMs, readiness, checkIn, sleep, upcoming, reflected, cognitive.baseline(today).wakeMinuteOfDay)
        }.getOrNull()
        return WatchState(
            readiness = readiness,
            nextEvent = fromPhone?.nextEvent?.takeIf { it.event.startMs > nowMs - 3 * HOUR_MS } ?: onWatch?.nextEvent,
            updatedMs = nowMs,
            upcoming = upcoming,
            checkIn = checkIn,
            sleepMinutes = sleep?.asleepMinutes ?: fromPhone?.sleepMinutes,
            brief = brief,
            reflectedIds = reflected,
            reminders = fromPhone?.reminders ?: com.hercules.shared.cognitive.NudgePrefs(),
        )
    }

    /** Saves a check-in on the watch and hands it to the phone (newest wins on both sides). */
    suspend fun saveCheckIn(energy: Int, stress: Int) {
        cognitive.saveCheckIn(energy, stress)
        val c: CheckIn = cognitive.store.checkIn(cognitive.today()) ?: return
        runCatching {
            val req = PutDataMapRequest.create(WearProtocol.CHECKIN_PATH).apply {
                dataMap.putString(WearProtocol.STATE_KEY, cognitive.encodeCheckIn(c))
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(ctx).putDataItem(req).await()
        }.onFailure { Log.w(TAG, "check-in not sent to phone yet", it) }
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
