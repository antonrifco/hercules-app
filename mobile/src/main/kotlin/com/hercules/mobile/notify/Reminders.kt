package com.hercules.mobile.notify

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
import com.hercules.mobile.R
import com.hercules.mobile.graph
import com.hercules.mobile.ui.MainActivity
import com.hercules.shared.cognitive.Nudge
import com.hercules.shared.cognitive.NudgePlanner
import com.hercules.shared.cognitive.NudgePrefs
import com.hercules.shared.cognitive.NudgeRouting
import com.hercules.shared.model.MetricType
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Which reminders the user wants. Stored on the phone, editable in Setup. */
object ReminderSettings {
    private const val PREFS = "hercules_reminders"

    fun load(ctx: Context): NudgePrefs {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = NudgePrefs()
        return NudgePrefs(
            morning = p.getBoolean("morning", d.morning),
            event = p.getBoolean("event", d.event),
            evening = p.getBoolean("evening", d.evening),
            reflect = p.getBoolean("reflect", d.reflect),
            focus = p.getBoolean("focus", d.focus),
        )
    }

    fun save(ctx: Context, prefs: NudgePrefs) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean("morning", prefs.morning); putBoolean("event", prefs.event); putBoolean("evening", prefs.evening)
            putBoolean("reflect", prefs.reflect); putBoolean("focus", prefs.focus)
        }
    }

    fun sent(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("sent", emptySet()).orEmpty()

    fun markSent(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Keep the list short: only recent ones matter, since reminders expire after 90 minutes.
        val next = (sent(ctx) + id).toList().takeLast(120).toSet()
        p.edit { putStringSet("sent", next) }
    }
}

/** Posts at most the reminders the planner says are due, when the watch is not already on your wrist. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val g = ctx.graph
        val now = System.currentTimeMillis()
        val prefs = ReminderSettings.load(ctx)
        if (!(prefs.morning || prefs.event || prefs.evening || prefs.reflect || prefs.focus)) return Result.success()
        val lastWatchHr = g.repo.observeLatest(MetricType.HEART_RATE).first()?.startMs
        // The watch delivers its own reminders while it is worn, so the wrist and the pocket never both buzz.
        if (!NudgeRouting.phoneShouldPost(lastWatchHr, now)) return Result.success()

        val today = g.cognitive.dayKeys.of(now)
        val store = g.cognitive.store
        val due = NudgePlanner(g.cognitive.dayKeys, g.cognitive.daily).due(
            now,
            store.readinessRange(today, today).firstOrNull(),
            store.checkIn(today),
            store.sleep(today),
            g.cognitive.upcoming(),
            store.reflections().map { it.eventId }.toSet(),
            g.cognitive.baseline(today).wakeMinuteOfDay,
            prefs,
            ReminderSettings.sent(ctx),
        )
        due.forEach { n ->
            if (Notifier.post(ctx, n)) ReminderSettings.markSent(ctx, n.id)
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hercules-reminders", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object Notifier {
    const val CHANNEL = "reminders"
    const val EXTRA_TARGET = "open"
    const val EXTRA_EVENT = "event_id"

    fun ensureChannel(ctx: Context) {
        val m = ctx.getSystemService(NotificationManager::class.java)
        if (m.getNotificationChannel(CHANNEL) == null) {
            m.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Morning score, event prep, bedtime"
            })
        }
    }

    fun canPost(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    /** @return true if it was shown (so it is not retried). Permission is checked by [canPost]; a late revoke is caught. */
    @android.annotation.SuppressLint("MissingPermission")
    fun post(ctx: Context, n: Nudge): Boolean {
        if (!canPost(ctx)) return false
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        return runCatching { NotificationManagerCompat.from(ctx).notify(n.id.hashCode(), notification) }.isSuccess
    }
}
