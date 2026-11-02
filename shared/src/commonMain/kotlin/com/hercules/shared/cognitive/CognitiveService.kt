package com.hercules.shared.cognitive

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.db.HerculesDatabase
import com.hercules.shared.insights.DayKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import com.hercules.shared.db.Checkin as CheckInRow
import com.hercules.shared.db.Plan_event as EventRow
import com.hercules.shared.db.Prep_task_done as TaskRow
import com.hercules.shared.db.Readiness_day as ReadinessRow
import com.hercules.shared.db.Sleep_night as SleepRow

internal val cognitiveJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Persistence for sleep, check-ins, readiness history, planned events and checklist progress. */
class CognitiveStore(db: HerculesDatabase, private val io: CoroutineDispatcher = Dispatchers.Default) {
    private val q = db.herculesQueries

    suspend fun upsertSleep(s: SleepNight) = withContext(io) {
        q.upsertSleep(SleepRow(s.dayKey, s.startMs, s.endMs, s.asleepMinutes.toLong(), s.sleepingHrAvg, s.sleepingHrLow, s.source))
    }

    suspend fun sleep(dayKey: String): SleepNight? = withContext(io) { q.sleepByKey(dayKey).executeAsOneOrNull()?.toModel() }
    suspend fun sleepRange(fromKey: String, toKey: String): List<SleepNight> =
        withContext(io) { q.sleepRange(fromKey, toKey).executeAsList().map { it.toModel() } }
    fun observeSleep(dayKey: String): Flow<SleepNight?> = q.sleepByKey(dayKey).asFlow().mapToOneOrNull(io).map { it?.toModel() }
    fun observeSleepRange(fromKey: String, toKey: String): Flow<List<SleepNight>> =
        q.sleepRange(fromKey, toKey).asFlow().mapToList(io).map { rows -> rows.map { it.toModel() } }

    suspend fun upsertCheckIn(c: CheckIn) = withContext(io) {
        q.upsertCheckIn(CheckInRow(c.dayKey, c.sleepHours, c.energy.toLong(), c.stress.toLong(), c.createdMs))
    }
    suspend fun checkIn(dayKey: String): CheckIn? = withContext(io) { q.checkInByKey(dayKey).executeAsOneOrNull()?.toModel() }
    fun observeCheckIn(dayKey: String): Flow<CheckIn?> = q.checkInByKey(dayKey).asFlow().mapToOneOrNull(io).map { it?.toModel() }

    suspend fun upsertReadiness(r: Readiness) = withContext(io) {
        q.upsertReadiness(ReadinessRow(r.dayKey, r.score.toLong(), cognitiveJson.encodeToString(Readiness.serializer(), r)))
    }
    suspend fun readinessRange(fromKey: String, toKey: String): List<Readiness> =
        withContext(io) { q.readinessRange(fromKey, toKey).executeAsList().mapNotNull { it.toModel() } }
    fun observeReadiness(dayKey: String): Flow<Readiness?> = q.readinessByKey(dayKey).asFlow().mapToOneOrNull(io).map { it?.toModel() }
    fun observeReadinessRange(fromKey: String, toKey: String): Flow<List<Readiness>> =
        q.readinessRange(fromKey, toKey).asFlow().mapToList(io).map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun upsertEvent(e: PlanEvent) = withContext(io) {
        q.upsertEvent(EventRow(e.id, e.title, e.type.name, e.startMs, e.importance.name, e.notes, e.createdMs))
    }
    suspend fun deleteEvent(id: String) = withContext(io) {
        q.transaction {
            q.deleteEventTasks(id)
            q.deleteEvent(id)
        }
    }
    suspend fun event(id: String): PlanEvent? = withContext(io) { q.eventById(id).executeAsOneOrNull()?.toModel() }
    suspend fun eventsFrom(fromMs: Long): List<PlanEvent> = withContext(io) { q.eventsFrom(fromMs).executeAsList().mapNotNull { it.toModel() } }
    fun observeEventsFrom(fromMs: Long): Flow<List<PlanEvent>> =
        q.eventsFrom(fromMs).asFlow().mapToList(io).map { rows -> rows.mapNotNull { it.toModel() } }

    suspend fun setTaskDone(eventId: String, dayKey: String, taskId: String, done: Boolean, nowMs: Long) = withContext(io) {
        if (done) q.setTaskDone(TaskRow(eventId, dayKey, taskId, nowMs)) else q.setTaskUndone(eventId, dayKey, taskId)
    }
    suspend fun tasksDone(eventId: String): List<Pair<String, String>> =
        withContext(io) { q.tasksDone(eventId).executeAsList().map { it.day_key to it.task_id } }
    fun observeTasksDone(eventId: String): Flow<List<Pair<String, String>>> =
        q.tasksDone(eventId).asFlow().mapToList(io).map { rows -> rows.map { it.day_key to it.task_id } }

    private fun SleepRow.toModel() = SleepNight(day_key, start_ms, end_ms, asleep_min.toInt(), hr_avg, hr_low, source)
    private fun CheckInRow.toModel() = CheckIn(day_key, sleep_hours, energy.toInt(), stress.toInt(), created_ms)
    private fun ReadinessRow.toModel(): Readiness? =
        runCatching { cognitiveJson.decodeFromString(Readiness.serializer(), json) }.getOrNull()
    private fun EventRow.toModel(): PlanEvent? = runCatching {
        PlanEvent(id, title, EventType.valueOf(type), start_ms, Importance.valueOf(importance), notes, created_ms)
    }.getOrNull()
}

/**
 * Orchestrates sleep detection → personal baseline → readiness score, and the event-preparation coach.
 * Runs identically on the phone, the watch, and (later) iOS/watchOS.
 */
class CognitiveService(
    private val repo: HerculesRepository,
    val store: CognitiveStore,
    val dayKeys: DayKeys = DayKeys(),
    private val clock: () -> Long,
) {
    val engine = ReadinessEngine(dayKeys)
    val analyzer = SleepAnalyzer(dayKeys)
    val coach = PrepCoach(dayKeys, engine)
    private val mutex = Mutex()

    fun today(): String = dayKeys.of(clock())

    suspend fun baseline(today: String): Baseline {
        val from = dayKeys.plusDays(today, -14)
        val to = dayKeys.plusDays(today, -1)
        val nights = store.sleepRange(from, to)
        val days = repo.allDaily().filter { it.dayKey in from..to }
        return Baseline.from(nights, days, dayKeys)
    }

    /**
     * Re-detect sleep for last night (and the one before), then recompute readiness for the day
     * containing [atMs] (default: now). Passing a past moment rebuilds history (used by demo data).
     */
    suspend fun refresh(atMs: Long = clock()): Readiness? = mutex.withLock {
        val now = atMs
        val today = dayKeys.of(now)
        val yesterday = dayKeys.plusDays(today, -1)
        for (key in listOf(yesterday, today)) {
            val (from, to) = analyzer.window(key)
            val samples = repo.samplesBetween(from - 12 * HOUR_MS, to)
            if (store.sleep(key)?.corrected == true) continue // the user's correction wins
            analyzer.analyze(key, samples, now)?.let { store.upsertSleep(it) }
        }
        val input = ReadinessInput(
            dayKey = today,
            nowMs = now,
            sleep = store.sleep(today),
            checkIn = store.checkIn(today),
            todayRestingHr = repo.daily(today)?.restingHr,
            yesterday = repo.daily(yesterday),
            baseline = baseline(today),
        )
        engine.compute(input)?.also { store.upsertReadiness(it) }
    }

    suspend fun saveCheckIn(energy: Int, stress: Int): Readiness? {
        store.upsertCheckIn(CheckIn(today(), null, energy, stress, clock()))
        return refresh()
    }

    /**
     * The user fixes the automatically detected night ending on the morning of [dayKey] (or adds one the
     * watch missed). Time awake during the night is kept from the detection; HR stats are kept too.
     */
    suspend fun correctSleep(dayKey: String, startMs: Long, endMs: Long): Readiness? {
        require(endMs > startMs) { "wake time must be after bedtime" }
        val auto = store.sleep(dayKey)
        val awakeMin = auto?.let { ((it.endMs - it.startMs) / 60_000L).toInt() - it.asleepMinutes }?.coerceAtLeast(0) ?: 0
        val inBedMin = ((endMs - startMs) / 60_000L).toInt()
        store.upsertSleep(
            SleepNight(
                dayKey = dayKey,
                startMs = startMs,
                endMs = endMs,
                asleepMinutes = (inBedMin - awakeMin).coerceIn(inBedMin / 2, inBedMin),
                sleepingHrAvg = auto?.sleepingHrAvg,
                sleepingHrLow = auto?.sleepingHrLow,
                source = SleepNight.SOURCE_USER,
            ),
        )
        return refresh()
    }

    suspend fun eventStatus(event: PlanEvent): EventStatus {
        val now = clock()
        val today = dayKeys.of(now)
        val eventDay = dayKeys.of(event.startMs)
        val daysUntil = dayKeys.daysBetween(today, eventDay)
        val phase = coach.phase(daysUntil)
        val wake = baseline(today).wakeMinuteOfDay
        val done = store.tasksDone(event.id)

        // Checklist completion over the run-up so far (max 7 days before the event).
        val firstDay = maxOf(dayKeys.of(event.createdMs), dayKeys.plusDays(eventDay, -7))
        var offered = 0
        var completed = 0
        var d = firstDay
        while (d <= today && d <= eventDay) {
            val ids = coach.tasks(event, coach.phase(dayKeys.daysBetween(d, eventDay)), wake).map { it.id }.toSet()
            offered += ids.size
            completed += done.count { it.first == d && it.second in ids }
            d = dayKeys.plusDays(d, 1)
        }
        val completion = if (offered > 0) completed.toDouble() / offered else null

        val recent = store.readinessRange(dayKeys.plusDays(today, -2), today).map { it.score }
        val nights = store.sleepRange(dayKeys.plusDays(today, -2), today)
        val historyScores = store.readinessRange(dayKeys.plusDays(eventDay, -7), eventDay).associate { it.dayKey to it.score }
        val history = (-7..0).map { dayKeys.plusDays(eventDay, it) }.map { DayScore(it, historyScores[it]) }
        val readinessToday = store.readinessRange(today, today).firstOrNull()

        return EventStatus(
            event = event,
            daysUntil = daysUntil,
            phase = phase,
            todayTasks = coach.tasks(event, phase, wake),
            doneTaskIds = done.filter { it.first == today }.map { it.second }.toSet(),
            prepScore = if (phase == PrepPhase.DONE) null else coach.prepScore(recent, coach.sleepConsistency(nights), completion),
            projectedScore = if (daysUntil >= 1) {
                coach.projected(recent, nights.lastOrNull { it.dayKey == today }?.let { it.asleepMinutes / 60.0 }, completion, event, wake)
            } else null,
            history = history,
            advice = if (daysUntil == 0 && readinessToday != null) coach.advice(event, readinessToday) else null,
        )
    }

    /** Events from today on (including ones earlier today). */
    suspend fun upcoming(): List<EventStatus> =
        store.eventsFrom(dayKeys.startOf(today())).map { eventStatus(it) }

    suspend fun watchState(): WatchState {
        val today = today()
        return WatchState(
            readiness = store.readinessRange(today, today).firstOrNull(),
            nextEvent = upcoming().firstOrNull { it.phase != PrepPhase.DONE },
            upcoming = upcoming().filter { it.phase != PrepPhase.DONE }.take(5),
            updatedMs = clock(),
        )
    }

    fun encodeWatchState(s: WatchState): String = cognitiveJson.encodeToString(WatchState.serializer(), s)
    fun decodeWatchState(json: String): WatchState? =
        runCatching { cognitiveJson.decodeFromString(WatchState.serializer(), json) }.getOrNull()
}
