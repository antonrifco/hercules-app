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
    val cognitive = CognitiveService(repo, CognitiveStore(db, Dispatchers.IO), clock = System::currentTimeMillis)
    val watchState = WatchStateSource(context, cognitive)
    val phoneSync = SyncService(
        store = repo,
        sink = PhoneRemoteSink(context),
        deviceId = deviceId,
        clock = System::currentTimeMillis,
    )
}

class HerculesWearApp : Application() {
    lateinit var graph: WearGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = WearGraph(this)
        SyncWorker.schedulePeriodic(this)
    }
}

val Context.graph: WearGraph get() = (applicationContext as HerculesWearApp).graph
