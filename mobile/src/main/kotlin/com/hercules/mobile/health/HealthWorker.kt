package com.hercules.mobile.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hercules.mobile.graph
import com.hercules.shared.readiness.LightContext
import java.util.concurrent.TimeUnit

/**
 * Hourly background work: one opportunistic light reading, a Health Connect import, recalculation and a push to
 * the watch. Old light readings are pruned after 60 days.
 */
class HealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val g = applicationContext.graph
        runCatching { g.light.sampleOnce(LightContext.BACKGROUND) }
        g.importHealthAndRefresh()
        runCatching { g.cognitive.store.deleteLightBefore(System.currentTimeMillis() - 60 * 86_400_000L) }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<HealthWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hercules-health", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Right after permissions are granted: import now instead of waiting for the hourly run. */
        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<HealthWorker>().build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("hercules-health-now", ExistingWorkPolicy.REPLACE, req)
        }
    }
}
