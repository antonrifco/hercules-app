package com.hercules.wear.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hercules.shared.cognitive.Nudge
import com.hercules.shared.cognitive.NudgePlanner
import com.hercules.shared.cognitive.NudgeRouting
import com.hercules.shared.model.MetricType
import com.hercules.wear.R
import com.hercules.wear.graph
import com.hercules.wear.ui.MainActivity
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Reminders on the wrist. They follow the choices made on the phone, and are only posted while the watch is
 * actually worn (it has a fresh heart-rate reading); otherwise the phone delivers them. Tapping one opens the
 * matching watch screen.
 */
class WatchReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val g = ctx.graph
        val now = System.currentTimeMillis()
        val lastHr = g.repo.observeLatest(MetricType.HEART_RATE).first()?.startMs
        if (!NudgeRouting.watchShouldPost(lastHr, now)) return Result.success()
        val state = runCatching { g.watchState.current(now) }.getOrNull() ?: return Result.success()
        val today = g.cognitive.today()
        val due = NudgePlanner(g.cognitive.dayKeys, g.cognitive.daily).due(
            nowMs = now,
            readiness = state.readiness,
            sleep = g.cognitive.store.sleep(today),
            events = state.upcoming,
            reflectedIds = state.reflectedIds,
            baselineWakeMinute = g.cognitive.typicalWakeMinute(today),
            prefs = state.reminders,
            alreadySent = sent(ctx),
            wakeMs = state.wakeMs ?: g.cognitive.wakeMs(today),
            playsToday = state.playsToday,
            peak = state.peak,
        )
        due.forEach { if (post(ctx, it)) markSent(ctx, it.id) }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "reminders"
        private const val PREFS = "hercules_reminders"
        const val EXTRA_TARGET = "open"
        const val EXTRA_EVENT = "event_id"

        fun schedule(ctx: Context) {
            ensureChannel(ctx)
            val req = PeriodicWorkRequestBuilder<WatchReminderWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hercules-watch-reminders", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        private fun ensureChannel(ctx: Context) {
            val m = ctx.getSystemService(NotificationManager::class.java)
            if (m.getNotificationChannel(CHANNEL) == null) {
                m.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        /** Entries are "sentMs|id": a stored set has no order, so the time decides which ones are the oldest. */
        private fun raw(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("sent", emptySet()).orEmpty()

        private fun sent(ctx: Context): Set<String> = raw(ctx).map { it.substringAfter('|') }.toSet()

        private fun markSent(ctx: Context, id: String) {
            val next = (raw(ctx) + "${System.currentTimeMillis()}|$id").sortedBy { it.substringBefore('|', "0").toLongOrNull() ?: 0L }.takeLast(120).toSet()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("sent", next) }
        }

        private fun post(ctx: Context, n: Nudge): Boolean {
            val allowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!allowed || !NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
            ensureChannel(ctx)
            val open = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_TARGET, n.target.name)
                n.eventId?.let { putExtra(EXTRA_EVENT, it) }
            }
            val pi = PendingIntent.getActivity(ctx, n.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            return runCatching { NotificationManagerCompat.from(ctx).notify(n.id.hashCode(), notification) }.isSuccess
        }
    }
}
