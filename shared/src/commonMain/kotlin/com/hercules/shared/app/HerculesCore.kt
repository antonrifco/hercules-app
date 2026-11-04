package com.hercules.shared.app

import com.hercules.shared.cognitive.CognitiveService
import com.hercules.shared.cognitive.CognitiveStore
import com.hercules.shared.cognitive.EventStatus
import com.hercules.shared.cognitive.EventType
import com.hercules.shared.cognitive.Importance
import com.hercules.shared.cognitive.PlanEvent
import com.hercules.shared.cognitive.PrepPhase
import com.hercules.shared.cognitive.SleepNight
import com.hercules.shared.cognitive.WatchState
import com.hercules.shared.cognitive.WindowKind
import com.hercules.shared.cognitive.formatDuration
import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.db.HerculesDatabase
import com.hercules.shared.demo.DemoData
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.insights.InsightsService
import com.hercules.shared.insights.RetentionPolicy
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.RecordText
import com.hercules.shared.model.Sample
import com.hercules.shared.sync.SyncService
import com.hercules.shared.sync.WireCodec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val MIN = 60_000L
private const val HOUR = 60 * MIN

/**
 * Everything one device needs, behind an API that is easy to call from Swift (iOS and watchOS):
 * plain values and snapshot objects in, `suspend` functions (Swift `async throws`) out, no Flows,
 * no dispatchers, no default arguments. The Android apps build the same graph by hand
 * (MobileGraph / WearGraph); this class mirrors what their screens and services do.
 *
 * Swift creates it with `AppleFactory.shared.core(deviceId:)`. Call it from the main actor.
 */
class HerculesCore(
    db: HerculesDatabase,
    val deviceId: String,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val dayKeys: DayKeys = DayKeys(),
    io: CoroutineDispatcher = Dispatchers.Default,
) {
    val repo = HerculesRepository(db, io)
    val insights = InsightsService(repo, dayKeys, clock = clock)
    val retention = RetentionPolicy(repo)
    val cognitive = CognitiveService(repo, CognitiveStore(db, io), dayKeys, clock)

    fun nowMs(): Long = clock()

    // ================================================================== shared

    /** Re-detects sleep and recomputes today's readiness. */
    @Throws(Exception::class)
    suspend fun refresh() {
        runCatching { cognitive.refresh() }
    }

    @Throws(Exception::class)
    suspend fun applyRetention() {
        retention.apply(clock())
    }

    /** 14 days of synthetic data plus two sample events (debug builds only). */
    @Throws(Exception::class)
    suspend fun loadDemo() {
        DemoData.load(repo, insights, cognitive, clock())
    }

    // ================================================================== phone

    /** What the Today and Plan tabs show. */
    @Throws(Exception::class)
    suspend fun phoneSnapshot(): PhoneSnapshot {
        val now = clock()
        val today = dayKeys.of(now)
        val store = cognitive.store
        val readiness = store.readinessRange(today, today).firstOrNull()

        val weekKeys = (-6..0).map { dayKeys.plusDays(today, it) }
        val nights = store.sleepRange(weekKeys.first(), today).associateBy { it.dayKey }
        val sleepWeek = weekKeys.map { k ->
            val n = nights[k]
            val hours = (n?.asleepMinutes ?: 0) / 60.0
            Bar(k, weekdayShort(k), hours, n != null, if (n != null) cognitive.engine.sleepScore(hours) else 0)
        }

        val trendKeys = (-13..0).map { dayKeys.plusDays(today, it) }
        val trendRows = store.readinessRange(trendKeys.first(), today)
        val scores = trendRows.associate { it.dayKey to it.score }
        val trend = trendKeys.mapIndexed { i, k ->
            val s = scores[k]
            Bar(k, if (i % 2 == 1) k.takeLast(2) else "", (s ?: 0).toDouble(), s != null, s ?: 0)
        }

        val events = cognitive.upcoming()
        return PhoneSnapshot(
            nowMs = now,
            today = today,
            dayStartMs = dayKeys.startOf(today),
            readiness = readiness,
            peak = readiness?.windows?.firstOrNull { it.kind == WindowKind.PEAK },
            sleep = store.sleep(today),
            sleepWeek = sleepWeek,
            trend = trend,
            trendDays = trendRows.size,
            trendAverage = if (trendRows.isEmpty()) 0 else trendRows.map { it.score }.average().roundToInt(),
            checkIn = store.checkIn(today),
            events = events,
            nextEvent = events.nextRelevant(now),
            todayEvents = events.filter { it.daysUntil == 0 },
        )
    }

    /** What the Data tab shows. */
    @Throws(Exception::class)
    suspend fun dataSnapshot(): DataSnapshot {
        val today = dayKeys.of(clock())
        val records = repo.records().values.sortedBy { it.kind.ordinal }.map { RecordLine(RecordText.label(it.kind), RecordText.value(it)) }
        return DataSnapshot(repo.daily(today), records, repo.pendingSampleCount())
    }

    @Throws(Exception::class)
    suspend fun saveCheckIn(energy: Int, stress: Int) {
        cognitive.saveCheckIn(energy.coerceIn(1, 5), stress.coerceIn(1, 5))
    }

    /** Initial picker values for correcting last night: the detected times, else 23:00 → 07:00. */
    fun sleepTimes(sleep: SleepNight?): ClockTimes {
        val midnight = dayKeys.startOf(dayKeys.of(clock()))
        fun hm(ms: Long?, fallbackHour: Int): Pair<Int, Int> =
            ms?.let { val m = (it - midnight).mod(24 * HOUR) / MIN; (m / 60).toInt() to (m % 60).toInt() } ?: (fallbackHour to 0)
        val (bh, bm) = hm(sleep?.startMs, 23)
        val (wh, wm) = hm(sleep?.endMs, 7)
        return ClockTimes(bh, bm, wh, wm)
    }

    /**
     * Last night as the user describes it. A bedtime from noon on belongs to the previous evening;
     * the wake time is on this morning.
     */
    fun sleepDraft(bedHour: Int, bedMinute: Int, wakeHour: Int, wakeMinute: Int): SleepDraft {
        val now = clock()
        val midnight = dayKeys.startOf(dayKeys.of(now))
        val bedMs = midnight + bedHour * HOUR + bedMinute * MIN - if (bedHour >= 12) 24 * HOUR else 0
        val wakeMs = midnight + wakeHour * HOUR + wakeMinute * MIN
        val minutes = ((wakeMs - bedMs) / MIN).toInt()
        val future = wakeMs > now
        val valid = minutes in 60..16 * 60 && !future
        val message = when {
            future -> "Wake time is still ahead"
            valid -> "${formatDuration(minutes)} in bed"
            else -> "Check the times"
        }
        return SleepDraft(bedMs, wakeMs, minutes, future, valid, message)
    }

    /** Saves the corrected night (marked "Corrected by you") and recomputes readiness. No-op if invalid. */
    @Throws(Exception::class)
    suspend fun correctSleep(draft: SleepDraft) {
        if (!draft.valid) return
        cognitive.correctSleep(dayKeys.of(clock()), draft.bedMs, draft.wakeMs)
    }

    /** Today, tomorrow, … up to [Catalog.MAX_DAYS_AHEAD] days out. */
    fun dayChoices(): List<DayChoice> {
        val today = dayKeys.of(clock())
        return (0..Catalog.MAX_DAYS_AHEAD).map { DayChoice(it, dayKeys.startOf(dayKeys.plusDays(today, it))) }
    }

    /** Live validation and timing advice while the user picks a day and time. */
    fun eventDraft(title: String, dayOffset: Int, hour: Int, minute: Int): EventDraft {
        val now = clock()
        val day = dayKeys.startOf(dayKeys.plusDays(dayKeys.of(now), dayOffset.coerceIn(0, Catalog.MAX_DAYS_AHEAD)))
        val startMs = day + hour * HOUR + minute * MIN
        val passed = startMs <= now
        val kind = cognitive.engine.windows(day + 7 * HOUR).firstOrNull { startMs >= it.startMs && startMs < it.endMs }?.kind
        return EventDraft(
            startMs = startMs,
            valid = title.isNotBlank() && !passed,
            passed = passed,
            hint = if (!passed && kind != null) TimingHint.of(kind, dayKeys.clock(startMs)) else null,
        )
    }

    /** Adds a big moment; its kind (interview, pitch, …) is inferred from the title. No-op if invalid. */
    @Throws(Exception::class)
    suspend fun addEvent(title: String, importance: Importance, dayOffset: Int, hour: Int, minute: Int) {
        val d = eventDraft(title, dayOffset, hour, minute)
        if (!d.valid) return
        cognitive.store.upsertEvent(
            PlanEvent(Uuid.random().toString(), title.trim(), EventType.fromTitle(title), d.startMs, importance, "", clock()),
        )
    }

    @Throws(Exception::class)
    suspend fun deleteEvent(id: String) {
        cognitive.store.deleteEvent(id)
    }

    /** Ticks a checklist item for today. */
    @Throws(Exception::class)
    suspend fun setTaskDone(eventId: String, taskId: String, done: Boolean) {
        val now = clock()
        cognitive.store.setTaskDone(eventId, dayKeys.of(now), taskId, done, now)
    }

    /**
     * Stores a sync batch from the watch and returns the ack (JSON) to send back: every id in the
     * batch, so the watch never retries something this phone can't read. Same contract as
     * Android's WatchSyncListenerService. Nights the batch touches are re-analyzed too, so a
     * HealthKit backfill gives a full personal baseline from day one.
     */
    @Throws(Exception::class)
    suspend fun receiveFromWatch(batchJson: String): String {
        val (batch, ack) = WireCodec.decodeBatchWithAck(batchJson.encodeToByteArray())
        repo.insertSamples(batch.samples)
        repo.upsertSummarizedSessions(batch.sessions)
        insights.refresh(insights.daysOf(batch.samples) + batch.sessions.map { it.dayKey })
        rebuildNights(batch.samples)
        return WireCodec.encodeAck(ack).decodeToString()
    }

    /** Latest readiness + upcoming moments for the watch (JSON of [WatchState]). Recomputes first. */
    @Throws(Exception::class)
    suspend fun watchStateJson(): String {
        runCatching { cognitive.refresh() }
        return cognitive.encodeWatchState(cognitive.watchState())
    }

    // ================================================================== watch

    /** Stores readings collected from HealthKit and updates summaries, sleep and readiness. */
    @Throws(Exception::class)
    suspend fun ingest(batch: HealthBatch) {
        val samples = batch.samples()
        if (samples.isEmpty()) return
        repo.insertSamples(samples)
        insights.refresh(insights.daysOf(samples))
        rebuildNights(samples)
    }

    /**
     * Hands pending readings to the phone in batches; only ids the phone acknowledged are marked
     * synced (same semantics as the Wear OS SyncWorker).
     */
    @Throws(Exception::class)
    suspend fun syncToPhone(transport: WatchTransport): SyncReport {
        runCatching { insights.refresh() }
        retention.apply(clock())
        val out = SyncService(repo, TransportSink(transport), deviceId, clock = clock).syncOnce()
        return SyncReport(out.samplesSynced, out.sessionsSynced, out.stoppedReason)
    }

    /**
     * What the watch shows: the phone's view when it is fresh (it has the planner and full history),
     * otherwise a score computed on the watch from its own data. Mirrors Wear OS WatchStateSource.
     */
    @Throws(Exception::class)
    suspend fun watchState(phoneStateJson: String?): WatchState {
        val now = clock()
        val fromPhone = phoneStateJson?.let { cognitive.decodeWatchState(it) }
        val today = dayKeys.of(now)
        if (fromPhone != null && now - fromPhone.updatedMs < STALE_MS && fromPhone.readiness?.dayKey == today) {
            return fromPhone
        }
        val local = runCatching { cognitive.refresh() }.getOrNull()
        val onWatch = runCatching { cognitive.watchState() }.getOrNull() // events stored on the watch (demo)
        val phoneUpcoming = fromPhone?.upcoming.orEmpty().filter { it.event.startMs > now - 3 * HOUR }
        return WatchState(
            readiness = local ?: fromPhone?.readiness?.takeIf { it.dayKey == today },
            nextEvent = fromPhone?.nextEvent?.takeIf { it.event.startMs > now - 3 * HOUR } ?: onWatch?.nextEvent,
            updatedMs = now,
            upcoming = phoneUpcoming.ifEmpty { onWatch?.upcoming.orEmpty() },
        )
    }

    /**
     * The watch home screen: today's score and peak window, what is being tracked, whether it
     * reached the phone ([lastSyncOkMs] 0 = never, [syncProblem] from the last [SyncReport]),
     * and a forecast for each big moment ahead.
     */
    @Throws(Exception::class)
    suspend fun watchHome(phoneStateJson: String?, lastSyncOkMs: Long, syncProblem: String?): WatchHome {
        val now = clock()
        val today = dayKeys.of(now)
        val state = watchState(phoneStateJson)
        val r = state.readiness
        val sleep = cognitive.store.sleep(today)
        val pending = repo.pendingSampleCount()
        val schedule = state.upcoming.ifEmpty { listOfNotNull(state.nextEvent) }
        return WatchHome(
            readiness = r,
            peak = r?.windows?.firstOrNull { it.kind == WindowKind.PEAK },
            sleepText = sleep?.let { formatDuration(it.asleepMinutes) } ?: "--",
            heartRate = repo.latest(MetricType.HEART_RATE)?.value?.roundToInt() ?: 0,
            steps = repo.daily(today)?.steps ?: 0,
            pending = pending,
            sync = WatchText.syncLine(lastSyncOkMs, syncProblem, pending, now),
            moments = schedule.map { WatchMoment.of(it, r?.score) },
        )
    }

    // ================================================================== internals

    /**
     * Re-detects sleep and readiness for each recent day the samples touch, oldest first, so that
     * history arriving late (HealthKit backfill, a watch that was offline) still builds the baseline.
     */
    private suspend fun rebuildNights(samples: List<Sample>) {
        if (samples.isEmpty()) return
        val today = dayKeys.of(clock())
        val oldest = dayKeys.plusDays(today, -HISTORY_DAYS)
        val keys = mutableSetOf<String>()
        for (s in samples) {
            keys += dayKeys.of(s.startMs)
            keys += dayKeys.of(s.endMs)
            keys += dayKeys.of(s.startMs + 6 * HOUR) // an evening belongs to the next morning's night
        }
        for (k in keys.filter { it in oldest..today }.sorted()) {
            runCatching { if (k == today) cognitive.refresh() else cognitive.refresh(dayKeys.startOf(k) + 14 * HOUR) }
        }
    }

    private companion object {
        const val STALE_MS = 6 * HOUR
        const val HISTORY_DAYS = 14
    }
}

/** The nearest event that isn't over yet (same rule as the Android Today tab). */
internal fun List<EventStatus>.nextRelevant(nowMs: Long): EventStatus? =
    firstOrNull { it.phase != PrepPhase.DONE && it.event.startMs > nowMs - 2 * HOUR }

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "Mon" for a `yyyy-MM-dd` day key. */
internal fun weekdayShort(dayKey: String): String = WEEKDAYS[LocalDate.parse(dayKey).dayOfWeek.ordinal]
