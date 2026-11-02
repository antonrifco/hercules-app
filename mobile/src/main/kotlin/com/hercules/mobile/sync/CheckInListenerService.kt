package com.hercules.mobile.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.hercules.mobile.graph
import com.hercules.shared.WearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** A check-in made on the watch: store it (newest wins), recompute the score, send the result back. */
class CheckInListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(events: DataEventBuffer) {
        val jsons = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearProtocol.CHECKIN_PATH }
            .mapNotNull { DataMapItem.fromDataItem(it.dataItem.freeze()).dataMap.getString(WearProtocol.STATE_KEY) }
        if (jsons.isEmpty()) return
        scope.launch {
            val g = applicationContext.graph
            val changed = jsons.map { runCatching { g.cognitive.applyRemoteCheckIn(it) }.getOrDefault(false) }.any { it }
            if (changed) g.pushToWatch()
            Log.i("HerculesCheckIn", "watch check-in applied=$changed")
        }
    }
}
