package com.hercules.wear

import android.app.Application
import android.content.Context
import com.hercules.shared.DeviceId
import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.createHerculesDatabase
import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.insights.InsightsService
import com.hercules.shared.insights.RetentionPolicy
import com.hercules.shared.sync.SyncService
import com.hercules.shared.sync.SyncStore
import com.hercules.wear.sync.PhoneRemoteSink
import com.hercules.wear.sync.SyncWorker
import com.hercules.wear.sync.WatchStateSource
import kotlinx.coroutines.Dispatchers

/** Hand-rolled DI container; small enough not to need Hilt/Koin yet. */
class WearGraph(context: Context) {
    val deviceId: String = DeviceId.get(context)
    private val db = createHerculesDatabase(context)
    val repo = HerculesRepository(db, Dispatchers.IO)
    val insights = InsightsService(repo, clock = System::currentTimeMillis)
    val retention = RetentionPolicy(repo)
    private val cognitiveStore = CognitiveStore(db, Dispatchers.IO)
    val cognitive = CognitiveService(repo, cognitiveStore, clock = System::currentTimeMillis)
    val watchState = WatchStateSource(context, cognitive)
    val phoneSync = SyncService(
        // Beat the Clock sessions played on the watch travel in the same batches as samples, acked by id.
        store = object : SyncStore by repo {
            override suspend fun pendingAssessments(limit: Long) = cognitiveStore.pendingSessions(limit)
            override suspend fun markAssessmentsSynced(ids: List<String>) = cognitiveStore.markSessionsSynced(ids)
        },
        sink = PhoneRemoteSink(context),
        deviceId = deviceId,
        clock = System::currentTimeMillis,
    )
}

open class HerculesWearApp : Application() {
    lateinit var graph: WearGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = WearGraph(this)
        SyncWorker.schedulePeriodic(this)
        com.hercules.wear.notify.WatchReminderWorker.schedule(this)
    }
}

val Context.graph: WearGraph get() = (applicationContext as HerculesWearApp).graph
