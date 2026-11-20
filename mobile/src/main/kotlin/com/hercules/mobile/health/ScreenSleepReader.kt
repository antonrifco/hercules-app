package com.hercules.mobile.health

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.ScreenSleep
import com.hercules.shared.readiness.SleepSessionRecord

/**
 * Estimates last night from when the screen went off and was first unlocked (Usage Access). Reads only screen
 * on/off and lock-screen events, never which apps were used. Off until the user allows Usage Access in Setup.
 */
class ScreenSleepReader(private val context: Context, private val store: CognitiveStore, private val dayKeys: DayKeys) {

    fun allowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Writes an estimate for today's and yesterday's night when there is one. Safe to call often. */
    suspend fun update(nowMs: Long = System.currentTimeMillis()) {
        if (!allowed()) return
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return
        val today = dayKeys.of(nowMs)
        val out = mutableListOf<SleepSessionRecord>()
        for (day in listOf(dayKeys.plusDays(today, -1), today)) {
            val midnight = dayKeys.startOf(day)
            val from = midnight - 6 * HOUR
            val to = midnight + 14 * HOUR
            if (from >= nowMs) continue
            val events = read(usage, from - 6 * HOUR, minOf(nowMs, to + HOUR))
            val est = ScreenSleep.estimate(events, from, to, nowMs) ?: continue
            val inBed = ((est.endMs - est.startMs) / 60_000L).toInt()
            out += SleepSessionRecord(
                "screen:$day", day, est.startMs, est.endMs, inBed - est.awakeMinutes, est.awakeMinutes, null, null, null,
                ScreenSleep.SOURCE, "phone", DataQuality.VALID,
            )
        }
        if (out.isNotEmpty()) store.upsertSleepSessions(out)
    }

    private fun read(usage: UsageStatsManager, from: Long, to: Long): List<ScreenSleep.Event> {
        val events = usage.queryEvents(from, to) ?: return emptyList()
        val e = UsageEvents.Event()
        val raw = mutableListOf<Pair<Long, Int>>()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_HIDDEN, UsageEvents.Event.SCREEN_INTERACTIVE -> raw += e.timeStamp to e.eventType
            }
        }
        // Without a lock screen there are no unlock events: then the screen turning on counts as the first use.
        val unlock = if (raw.any { it.second == UsageEvents.Event.KEYGUARD_HIDDEN }) UsageEvents.Event.KEYGUARD_HIDDEN else UsageEvents.Event.SCREEN_INTERACTIVE
        return raw.mapNotNull { (t, type) ->
            when (type) {
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ScreenSleep.Event(t, ScreenSleep.Kind.OFF)
                unlock -> ScreenSleep.Event(t, ScreenSleep.Kind.UNLOCK)
                else -> null
            }
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
