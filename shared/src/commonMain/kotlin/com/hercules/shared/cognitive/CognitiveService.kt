package com.hercules.shared.cognitive

import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.MetricType
import com.hercules.shared.readiness.ActivityInput
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.BaselineValue
import com.hercules.shared.readiness.Baselines
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveInput
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.DayInputs
import com.hercules.shared.readiness.DefaultScoring
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.HealthMetricType
import com.hercules.shared.readiness.LightContext
import com.hercules.shared.readiness.LightInput
import com.hercules.shared.readiness.MetricKey
import com.hercules.shared.readiness.MomentPreparationEngine
import com.hercules.shared.readiness.MomentRules
import com.hercules.shared.readiness.MomentStatus
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.PeakWindowPredictor
import com.hercules.shared.readiness.ReadinessCalculation
import com.hercules.shared.readiness.StrainInput
import com.hercules.shared.readiness.PrepContext
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.PrepTask
import com.hercules.shared.readiness.ReadinessCalculator
import com.hercules.shared.readiness.SCORING_ENGINE_VERSION
import com.hercules.shared.readiness.ScoringConfiguration
import com.hercules.shared.readiness.ScoringParams
import com.hercules.shared.readiness.Stats
import com.hercules.shared.readiness.Urgency
import com.hercules.shared.readiness.WeightValidation
import com.hercules.shared.readiness.validateWeights
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** A recommendation shared by several Moments (spec §8.10). */
@kotlinx.serialization.Serializable
data class CombinedTask(val task: PrepTask, val moments: List<String>)

/**
 * Orchestrates sleep resolution → inputs → CRS, Beat the Clock, the peak window and Moment preparation.
 * Pure domain rules live in `readiness`; this class only reads and writes the store. Same code on phone and watch.
 */
/** Earlier days recalculated on every refresh, matching the Health Connect re-import window. */
const val RECALC_DAYS = 2

class CognitiveService(
    private val repo: HerculesRepository,
    val store: CognitiveStore,
    val dayKeys: DayKeys = DayKeys(),
    private val clock: () -> Long,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    val analyzer = SleepAnalyzer(dayKeys)
    val daily = Coach(dayKeys)
    private val calculator = ReadinessCalculator()
    private val prep = MomentPreparationEngine()
    private val params = ScoringParams.V1
    private val mutex = Mutex()

    fun today(): String = dayKeys.of(clock())
    fun now(): Long = clock()

    // ---------------------------------------------------------------- settings

    /**
     * Your typical wake time (minutes after midnight): the median of the last [WAKE_HISTORY_NIGHTS] recorded wake-ups.
     * Null until a night has been recorded. Nothing to set; it follows your history.
     */
    suspend fun typicalWakeMinute(beforeKey: String = today()): Int? {
        val nights = store.sleepRange(dayKeys.plusDays(beforeKey, -WAKE_HISTORY_NIGHTS), beforeKey)
        return Stats.median(nights.map { dayKeys.minuteOfDay(it.endMs).toDouble() })?.roundToInt()
    }

    /** Wake time for [dayKey]: last night's sleep, otherwise your typical wake time (07:00 until there is history). */
    suspend fun wakeMs(dayKey: String): Long =
        store.sleep(dayKey)?.endMs ?: (dayKeys.startOf(dayKey) + (typicalWakeMinute(dayKey) ?: DEFAULT_WAKE_MINUTE) * MIN_MS)

    // ---------------------------------------------------------------- scoring configuration

    suspend fun config(): ScoringConfiguration = store.activeConfig()

    /** Validates and activates custom weights. Past scores keep the configuration they were calculated with. */
    suspend fun saveWeights(weights: Map<Dimension, Double>): WeightValidation {
        val v = validateWeights(weights)
        if (v !is WeightValidation.Valid) return v
        val now = clock()
        store.activateConfig(ScoringConfiguration.custom("custom-$now", weights, now))
        refresh()
        return v
    }

    /** Restores the recommended defaults as a new history entry (the UI confirms first). */
    suspend fun restoreDefaultWeights() {
        val now = clock()
        store.activateConfig(DefaultScoring.DEFAULT.copy(id = "default-v${DefaultScoring.CONFIG_VERSION}-$now", updatedAtMs = now))
        refresh()
    }

    // ---------------------------------------------------------------- sleep

    /**
     * Resolves the night that ended on the morning of [dayKey]: the user's correction, else Health Connect's main
     * sleep session, else what the Hercules watch detected.
     */
    private suspend fun resolveSleep(dayKey: String, nowMs: Long) {
        val existing = store.sleep(dayKey)
        if (existing?.corrected == true) return
        val (from, to) = analyzer.window(dayKey)
        val watch = analyzer.analyze(dayKey, repo.samplesBetween(from - 12 * HOUR_MS, to), nowMs)
        val hc = store.sleepSessions(dayKey).filter { it.quality == DataQuality.VALID && it.endMs <= nowMs }.maxByOrNull { it.asleepMinutes }
        // Most reliable night first: a wearable's own staged sleep in Health Connect (its validated algorithm), then
        // the Hercules watch's detection, then any other Health Connect session (often a phone-only estimate).
        val staged = hc != null && listOf(hc.deepMinutes, hc.remMinutes, hc.lightMinutes).any { it != null && it > 0 }
        val resolved = when {
            watch != null && !staged -> watch
            hc != null -> SleepNight(
                dayKey = dayKey,
                startMs = hc.startMs,
                endMs = hc.endMs,
                asleepMinutes = hc.asleepMinutes,
                sleepingHrAvg = watch?.sleepingHrAvg,
                sleepingHrLow = watch?.sleepingHrLow,
                source = SleepNight.SOURCE_HEALTH_CONNECT,
                awakeMinutes = hc.awakeMinutes,
                deepMinutes = hc.deepMinutes,
                remMinutes = hc.remMinutes,
                lightMinutes = hc.lightMinutes,
                provider = hc.sourcePackage,
            )
            else -> watch
        }
        if (resolved != null && resolved != existing) store.upsertSleep(resolved)
    }

    /**
     * The user fixes the night ending on the morning of [dayKey] (or adds one that wasn't recorded). Time awake is
     * kept from the automatic record when there is one.
     */
    suspend fun correctSleep(dayKey: String, startMs: Long, endMs: Long): Evaluation {
        require(endMs > startMs) { "wake time must be after bedtime" }
        val auto = store.sleep(dayKey)
        val inBed = ((endMs - startMs) / MIN_MS).toInt()
        val awake = auto?.awakeMinutes?.coerceIn(0, inBed / 2)
        store.upsertSleep(
            SleepNight(
                dayKey = dayKey,
                startMs = startMs,
                endMs = endMs,
                asleepMinutes = inBed - (awake ?: 0),
                sleepingHrAvg = auto?.sleepingHrAvg,
                sleepingHrLow = auto?.sleepingHrLow,
                source = SleepNight.SOURCE_USER,
                awakeMinutes = awake,
                provider = "You",
            ),
        )
        return refresh()
    }

    // ---------------------------------------------------------------- daily values

    /**
     * One value per day for [type], from the more reliable source (spec §14); never summed:
     * - Steps and resting heart rate: the Hercules watch when it was worn that day (on the body all day beats a
     *   phone that is often not carried), otherwise Health Connect.
     * - HRV: Health Connect first. A wearable's own app measures it overnight at rest, the standard for recovery;
     *   the Hercules watch only has beat-to-beat readings it happens to catch, so it is the fallback.
     */
    private suspend fun dailyValues(type: HealthMetricType, fromKey: String, toKey: String): Map<String, Double> {
        val hc = store.metrics(type, fromKey, toKey).filter { it.quality == DataQuality.VALID }.groupBy { it.dayKey }.mapValues { (_, list) ->
            when (type) {
                HealthMetricType.STEPS_DAILY -> list.maxOf { it.value }
                HealthMetricType.RESTING_HR -> list.minOf { it.value }
                else -> Stats.median(list.map { it.value })!!
            }
        }
        val watch = repo.allDaily().filter { it.dayKey in fromKey..toKey }.mapNotNull { d ->
            val v = when (type) {
                HealthMetricType.HRV_RMSSD -> d.hrvRmssd
                HealthMetricType.RESTING_HR -> d.restingHr?.toDouble()
                HealthMetricType.STEPS_DAILY -> d.steps.takeIf { d.hrSampleCount > 0 }?.toDouble()
                else -> null
            }
            v?.let { d.dayKey to it }
        }.toMap()
        return if (type == HealthMetricType.HRV_RMSSD) watch + hc else hc + watch
    }

    private suspend fun nightInputs(fromKey: String, toKey: String) = store.sleepRange(fromKey, toKey).map { it.toInput(dayKeys) }

    /** Everything the calculator needs for [dayKey], using only data available at [nowMs]. */
    suspend fun inputs(dayKey: String, nowMs: Long): DayInputs {
        val yesterday = dayKeys.plusDays(dayKey, -1)
        val historyFrom = dayKeys.plusDays(dayKey, -params.baselineDays)

        val lastNight = store.sleep(dayKey)?.toInput(dayKeys)
        val previous = nightInputs(dayKeys.plusDays(dayKey, -(params.consistencyNights - 1)), yesterday)

        val hrv = dailyValues(HealthMetricType.HRV_RMSSD, historyFrom, dayKey)
        val rhr = dailyValues(HealthMetricType.RESTING_HR, historyFrom, dayKey)
        val steps = dailyValues(HealthMetricType.STEPS_DAILY, historyFrom, yesterday)
        val weekAgo = dayKeys.plusDays(dayKey, -7)

        suspend fun extra(type: HealthMetricType): Pair<Double?, List<Double>> =
            dailyValues(type, historyFrom, dayKey).let { m -> m[dayKey] to m.filterKeys { it < dayKey }.values.toList() }
        val (resp, respHist) = extra(HealthMetricType.RESPIRATORY_RATE)
        val (skin, skinHist) = extra(HealthMetricType.SKIN_TEMPERATURE_DELTA)
        val (spo2, spo2Hist) = extra(HealthMetricType.SPO2)
        val glucose = store.metrics(HealthMetricType.BLOOD_GLUCOSE, yesterday, dayKey)
            .filter { it.quality == DataQuality.VALID && it.measuredMs in (nowMs - DAY_MS)..nowMs }
            .map { it.value }
        val strain = StrainInput(resp, respHist, skin, skinHist, spo2, spo2Hist, glucose)
            .takeIf { resp != null || skin != null || spo2 != null || glucose.isNotEmpty() }

        return DayInputs(
            dayKey = dayKey,
            lastNight = lastNight,
            previousNights = previous,
            hrv = hrv[dayKey],
            hrvHistory = hrv.filterKeys { it < dayKey }.values.toList(),
            hrvRecent = hrv.entries.filter { it.key >= weekAgo && it.key < dayKey }.sortedBy { it.key }.map { it.value },
            restingHr = rhr[dayKey],
            restingHrHistory = rhr.filterKeys { it < dayKey }.values.toList(),
            stepsHistory = steps.filterKeys { it < yesterday }.values.toList(),
            activity = activity(yesterday, steps[yesterday]),
            light = light(yesterday, dayKey),
            cognitive = cognitive(dayKey, nowMs),
            strain = strain,
        )
    }

    /** Yesterday's activity: steps, exercise minutes (overlaps merged across providers) and low-movement hours. */
    private suspend fun activity(dayKey: String, steps: Double?): ActivityInput? {
        if (steps == null) return null
        val start = dayKeys.startOf(dayKey)
        val end = dayKeys.nextDayStart(dayKey)
        val intervals = store.exerciseBetween(start, end).map { it.startMs to it.endMs } +
            repo.summarizedSessions().filter { it.endMs > start && it.startMs < end }.map { it.startMs to it.endMs }
        val exercise = mergedMinutes(intervals.map { maxOf(it.first, start) to minOf(it.second, end) })

        // Low-movement time needs proof of wear: an hour counts only when the watch recorded heart rate in it.
        val wake = wakeMs(dayKey)
        val samples = repo.samplesBetween(wake, start + 22 * HOUR_MS)
        var observed = 0
        var low = 0
        var t = wake
        while (t + HOUR_MS <= start + 22 * HOUR_MS) {
            val hour = samples.filter { it.startMs >= t && it.startMs < t + HOUR_MS }
            if (hour.any { it.type == MetricType.HEART_RATE }) {
                observed++
                if (hour.filter { it.type == MetricType.STEPS && it.source.countsTowardDailyTotals }.sumOf { it.value } < params.lowMovementStepsPerHour) low++
            }
            t += HOUR_MS
        }
        return ActivityInput(steps.toLong(), exercise, if (observed > 0) low else null, observed)
    }

    private fun mergedMinutes(intervals: List<Pair<Long, Long>>): Int {
        var total = 0L
        var curStart = -1L
        var curEnd = -1L
        for ((a, b) in intervals.filter { it.second > it.first }.sortedBy { it.first }) {
            if (a > curEnd) {
                if (curEnd > curStart) total += curEnd - curStart
                curStart = a
                curEnd = b
            } else if (b > curEnd) curEnd = b
        }
        if (curEnd > curStart) total += curEnd - curStart
        return (total / MIN_MS).toInt()
    }

    /**
     * Light on [dayKey] split into morning (3 h after waking), evening (3 h before bed) and the daytime between.
     * Background readings under 1 lux are dropped: the phone was probably in a pocket.
     */
    private suspend fun light(dayKey: String, nextDayKey: String): LightInput? {
        val wake = wakeMs(dayKey)
        val bed = store.sleep(nextDayKey)?.startMs ?: (dayKeys.startOf(nextDayKey) - HOUR_MS)
        if (bed <= wake + 6 * HOUR_MS) return null
        val samples = store.lightBetween(wake, bed)
            .filterNot { it.context == LightContext.BACKGROUND && it.lux < params.lightBackgroundMinLux }
            .filter { it.lux >= 0 && !it.lux.isNaN() }
        if (samples.isEmpty()) return null
        val morningEnd = wake + 3 * HOUR_MS
        val eveningStart = bed - 3 * HOUR_MS
        return LightInput(
            morning = samples.filter { it.measuredMs < morningEnd }.map { it.lux },
            daytime = samples.filter { it.measuredMs in morningEnd until eveningStart }.map { it.lux },
            evening = samples.filter { it.measuredMs >= eveningStart }.map { it.lux },
        )
    }

    /**
     * The day's Beat the Clock result from either device. Play once or many times: each part is the median of the
     * day's scored runs, so one lucky or sloppy run doesn't swing it.
     */
    private suspend fun cognitive(dayKey: String, nowMs: Long): CognitiveInput? {
        val runs = store.sessionsFrom(dayKeys.startOf(dayKey)).filter { it.dayKey == dayKey && it.scored && it.endMs <= nowMs }
        if (runs.isEmpty()) return null
        fun med(f: (CognitiveSession) -> Int?) = Stats.median(runs.mapNotNull { f(it)?.toDouble() })!!.roundToInt()
        val label = if (runs.size == 1) "1 run · ${runs[0].deviceKind.label}" else "Median of ${runs.size} runs"
        return CognitiveInput(med { it.accuracyScore }, med { it.consistencyScore }, med { it.biasScore }, label)
    }

    // ---------------------------------------------------------------- refresh

    /**
     * Re-resolves the last two nights and recalculates the day containing [atMs] (default: now) with the active
     * configuration. Also refreshes baselines, the peak window, Moment lifecycles and today's Moment snapshots.
     */
    suspend fun refresh(atMs: Long = clock()): Evaluation = withContext(Dispatchers.Default) { mutex.withLock {
        val today = dayKeys.of(atMs)
        for (back in RECALC_DAYS downTo 0) resolveSleep(dayKeys.plusDays(today, -back), atMs)

        rescoreUnscored(today, atMs)
        recalcRecent(today, atMs)
        val config = store.activeConfig()
        val input = inputs(today, atMs)
        val calc = calculator.calculate(input, config)
        val evaluation = save(today, calc, atMs)

        saveBaselines(input, atMs)
        val peak = PeakWindowPredictor.predict(store.sessionsFrom(atMs - PeakWindowPrediction.WINDOW_DAYS * DAY_MS), atMs)
        store.savePeak(peak, dayKeys.plusDays(today, -PeakWindowPrediction.WINDOW_DAYS), today)

        updateLifecycles(atMs)
        snapshotMoments(atMs, evaluation, peak)
        evaluation
    } }

    /** Stores [calc] for [dayKey] as its evaluation and, when it has a number, its readiness. */
    private suspend fun save(dayKey: String, calc: ReadinessCalculation, atMs: Long): Evaluation {
        val wake = wakeMs(dayKey)
        val baselineDays = store.sleepRange(dayKeys.plusDays(dayKey, -params.baselineDays), dayKeys.plusDays(dayKey, -1)).size
        val readiness = calc.score?.let { score ->
            val band = ReadinessBand.of(score)
            val bedtime = dayKeys.clock(wake + 16 * HOUR_MS - 15 * MIN_MS)
            Readiness(dayKey, score, band, calc, baselineDays, wake, Tips.build(calc, band, store.sleep(dayKey), bedtime), atMs)
        }
        readiness?.let { store.upsertReadiness(it) }
        return Evaluation(dayKey, calc, readiness, wake, atMs).also { store.saveEvaluation(it) }
    }

    /**
     * Data often arrives late: last night's sleep after midnight, the rest of yesterday's steps, a watch that synced
     * hours later. The previous [RECALC_DAYS] days are recalculated with what is known now, using the weights each day
     * was first scored with (so a later weight change never rewrites history), and saved only when they change.
     * Older days stay as they were.
     */
    private suspend fun recalcRecent(today: String, atMs: Long) {
        for (back in 1..RECALC_DAYS) {
            val day = dayKeys.plusDays(today, -back)
            val old = store.evaluation(day)
            val config = old?.calc?.stamp?.toConfig() ?: store.activeConfig()
            val endOfDay = dayKeys.startOf(dayKeys.plusDays(day, 1)) - 1
            val calc = calculator.calculate(inputs(day, endOfDay), config)
            if (old == null && calc.score == null) continue
            if (old != null && old.calc.score == calc.score && old.calc.coverage == calc.coverage && old.calc.historyDays == calc.historyDays) continue
            save(day, calc, atMs)
        }
    }

    private suspend fun saveBaselines(input: DayInputs, nowMs: Long) {
        val v = SCORING_ENGINE_VERSION
        val list = mutableListOf(
            StoredBaseline(MetricKey.HRV.name, null, null, Baselines.blend(input.hrvHistory, params.hrvReference), v, nowMs),
            StoredBaseline(MetricKey.RESTING_HR.name, null, null, Baselines.blend(input.restingHrHistory, params.restingHrReference), v, nowMs),
            StoredBaseline(MetricKey.STEPS.name, null, null, Baselines.blend(input.stepsHistory.filter { it > 0 }, params.stepsReference to 2500.0), v, nowMs),
        )
        val history = store.sessionsFrom(nowMs - BeatTheClock.BASELINE_DAYS * DAY_MS)
        for (d in DeviceKind.entries) {
            val base = BeatTheClock.baselineSessions(history, d, nowMs + 1)
            val errs = base.mapNotNull { it.accuracyError }
            if (errs.isEmpty()) continue
            val share = if (errs.size >= BeatTheClock.MIN_BASELINE_SESSIONS) 1.0 else 0.0
            list += StoredBaseline(MetricKey.BTC_ACCURACY.name, d, null, BaselineValue(Stats.median(errs)!!, Stats.mad(errs)!!, errs.size, share), "btc-${BeatTheClock.GAME_VERSION}", nowMs)
        }
        store.saveBaselines(list)
    }

    suspend fun evaluation(dayKey: String = today()): Evaluation? = store.evaluation(dayKey)

    // ---------------------------------------------------------------- Beat the Clock

    /** Valid, non-practice runs played today, on either device. */
    suspend fun playsToday(dayKey: String = today()): Int =
        store.sessionsFrom(dayKeys.startOf(dayKey)).count { it.dayKey == dayKey && it.valid && !it.practice }


    /**
     * Completes a session played on this device and stores it. Any time counts; [startMs] only decides which part of
     * the day it is filed under (from [wakeMs], defaulting to what this database knows).
     */
    suspend fun recordAssessment(
        sessionId: String,
        device: DeviceKind,
        deviceId: String,
        startMs: Long,
        endMs: Long,
        rounds: List<CognitiveRound>,
        wakeMs: Long? = null,
        energy: Int? = null,
        stress: Int? = null,
    ): CognitiveSession {
        val day = dayKeys.of(startMs)
        val wake = wakeMs ?: wakeMs(day)
        val window = AssessmentWindow.at(wake, startMs)
        val history = store.sessionsFrom(startMs - (BeatTheClock.BASELINE_DAYS + 1) * DAY_MS)
        val session = BeatTheClock.finish(sessionId, device, deviceId, window, day, startMs, endMs, rounds, history)
            .copy(energy = energy?.coerceIn(1, 5), stress = stress?.coerceIn(1, 5))
        store.saveSession(session)
        refresh()
        return session
    }

    /**
     * Valid games from today that never got a score (played while a score still needed practice or a full
     * baseline) are scored now, so nobody sees "locked" after playing.
     */
    private suspend fun rescoreUnscored(dayKey: String, nowMs: Long) {
        val history = store.sessionsFrom(nowMs - (BeatTheClock.BASELINE_DAYS + 1) * DAY_MS)
        val stale = history.filter { it.dayKey == dayKey && it.valid && it.cognitiveScore == null }
        for (s in stale) {
            val again = BeatTheClock.finish(s.id, s.deviceKind, s.deviceId, s.window, s.dayKey, s.startMs, s.endMs, s.rounds, history)
            if (again.cognitiveScore != null) store.saveSession(again.copy(gameVersion = s.gameVersion, energy = s.energy, stress = s.stress))
        }
    }

    /** Sessions played on the other device. Idempotent: the same id twice is stored once. */
    suspend fun applyRemoteSessions(sessions: List<CognitiveSession>): List<String> {
        if (sessions.isEmpty()) return emptyList()
        for (s in sessions) store.saveSession(s, synced = true)
        refresh()
        return sessions.map { it.id }
    }

    // ---------------------------------------------------------------- moments

    /** Applies the lifecycle: a passed Moment completes automatically; cancelled ones stay cancelled. */
    private suspend fun updateLifecycles(nowMs: Long) {
        for (e in store.events()) {
            val next = MomentRules.statusAt(e.status, e.startMs, nowMs)
            if (next != e.status) store.upsertEvent(e.copy(status = next, completedMs = if (next == MomentStatus.COMPLETED) e.startMs else e.completedMs, updatedMs = nowMs))
        }
    }

    suspend fun moments(): List<PlanEvent> {
        updateLifecycles(clock())
        return store.events()
    }

    /**
     * Creates ([id] null) or edits a Moment. Rejected when it is in the past, more than seven days ahead, or would
     * be the sixth active one. The time zone is captured automatically.
     */
    suspend fun saveMoment(id: String?, title: String, startMs: Long, urgency: Urgency, notes: String = "", timezoneId: String = ""): MomentRules.Check {
        val now = clock()
        val existing = id?.let { store.event(it) }
        if (existing != null && existing.status != MomentStatus.ACTIVE) return MomentRules.Check.Rejected("Only upcoming moments can be edited.")
        val others = moments().count { it.status == MomentStatus.ACTIVE && it.id != id }
        val check = MomentRules.check(title, startMs, now, others)
        if (check !is MomentRules.Check.Ok) return check
        val t = title.trim()
        store.upsertEvent(
            existing?.copy(title = t, type = EventType.fromTitle(t), startMs = startMs, urgency = urgency, notes = notes, timezoneId = timezoneId, updatedMs = now)
                ?: PlanEvent(id ?: newId(), t, EventType.fromTitle(t), startMs, urgency, notes, now, timezoneId, MomentStatus.ACTIVE, now),
        )
        evaluation()?.let { snapshotMoments(now, it, store.latestPeak()) }
        return check
    }

    suspend fun cancelMoment(id: String) {
        val e = store.event(id) ?: return
        if (e.status != MomentStatus.ACTIVE) return
        val now = clock()
        store.upsertEvent(e.copy(status = MomentStatus.CANCELLED, cancelledMs = now, updatedMs = now))
    }

    private suspend fun context(e: PlanEvent, nowMs: Long, evaluation: Evaluation?, peak: PeakWindowPrediction?): PrepContext {
        val today = dayKeys.of(nowMs)
        val eventDay = dayKeys.of(e.startMs)
        val calc = evaluation?.calc
        fun dim(d: Dimension) = calc?.dimensions?.firstOrNull { it.dimension == d }?.score
        val consistency = calc?.dimensions?.flatMap { it.metrics }?.firstOrNull { it.key == MetricKey.SLEEP_CONSISTENCY }?.score
        val recent = store.sleepRange(dayKeys.plusDays(today, -2), today)
        val wakeMinute = store.sleep(today)?.let { dayKeys.minuteOfDay(it.endMs) } ?: typicalWakeMinute(today) ?: DEFAULT_WAKE_MINUTE
        return PrepContext(
            nowMs = nowMs,
            daysUntil = dayKeys.daysBetween(today, eventDay),
            eventStartMs = e.startMs,
            urgency = e.urgency,
            latestScore = calc?.score,
            latestConfidence = calc?.confidence,
            latestCoverage = calc?.coverage,
            sleepScore = dim(Dimension.SLEEP),
            consistencyScore = consistency,
            cardioScore = dim(Dimension.CARDIO),
            activityScore = dim(Dimension.ACTIVITY),
            lightScore = dim(Dimension.CIRCADIAN),
            recentSleepMinutes = recent.takeIf { it.isNotEmpty() }?.map { it.asleepMinutes.toDouble() }?.average(),
            sleepTargetMinutes = (params.sleepTargetHours * 60).toInt(),
            peak = peak,
            eventDayWakeMs = if (eventDay == today) wakeMs(today) else dayKeys.startOf(eventDay) + wakeMinute * MIN_MS,
            clock = dayKeys::clock,
        )
    }

    suspend fun eventStatus(e: PlanEvent, nowMs: Long = clock(), evaluation: Evaluation? = null, peak: PeakWindowPrediction? = null): EventStatus {
        val ev = evaluation ?: evaluation(dayKeys.of(nowMs))
        val pk = peak ?: store.latestPeak()
        val c = context(e, nowMs, ev, pk)
        val today = dayKeys.of(nowMs)
        val eventDay = dayKeys.of(e.startMs)
        val active = e.status == MomentStatus.ACTIVE && e.startMs > nowMs
        val phase = if (active) PrepPhase.of(c.daysUntil) else PrepPhase.DONE
        val snaps = store.snapshots(e.id).associateBy { it.dayKey }
        val scores = store.readinessRange(dayKeys.plusDays(eventDay, -7), eventDay).associate { it.dayKey to it.score }
        val history = (-7..0).map { dayKeys.plusDays(eventDay, it) }.filter { it <= today }.map { DayScore(it, snaps[it]?.score ?: scores[it]) }
        return EventStatus(
            event = e,
            daysUntil = c.daysUntil,
            phase = phase,
            todayTasks = if (active) prep.recommendations(c) else emptyList(),
            doneTaskIds = store.tasksDone(e.id).filter { it.first == today }.map { it.second }.toSet(),
            preparation = prep.evaluate(c),
            history = history,
        )
    }

    /** Active Moments, soonest first. */
    suspend fun upcoming(nowMs: Long = clock()): List<EventStatus> {
        val ev = evaluation(dayKeys.of(nowMs))
        val pk = store.latestPeak()
        return moments().filter { it.status == MomentStatus.ACTIVE }.sortedBy { it.startMs }.map { eventStatus(it, nowMs, ev, pk) }
    }

    /** Today's recommendations across all active Moments: Critical and sooner first, duplicates merged. */
    suspend fun combinedTasks(events: List<EventStatus>? = null): List<CombinedTask> {
        val list = events ?: upcoming()
        return MomentPreparationEngine.consolidate(list.map { Triple(it.event.title, it.event.urgency, it.event.startMs to it.todayTasks) })
            .map { CombinedTask(it.first, it.second) }
    }

    private suspend fun snapshotMoments(nowMs: Long, evaluation: Evaluation, peak: PeakWindowPrediction?) {
        val today = dayKeys.of(nowMs)
        for (e in store.events().filter { it.status == MomentStatus.ACTIVE }) {
            val c = context(e, nowMs, evaluation, peak)
            val p = prep.evaluate(c)
            store.saveSnapshot(
                MomentSnapshot(
                    e.id, today, nowMs, evaluation.calc.score, evaluation.readiness?.band, evaluation.calc.confidence.name, evaluation.calc.coverage,
                    p.status.name, p.peak.name, evaluation.calc.stamp.configId, evaluation.calc.stamp.configVersion,
                ),
            )
        }
    }

    // ---------------------------------------------------------------- brief and watch

    /** Today's message and next step from what is stored right now. Moments stay on their own page, never Home. */
    suspend fun brief(nowMs: Long = clock()): DailyBrief = withContext(Dispatchers.Default) {
        val today = dayKeys.of(nowMs)
        val ev = evaluation(today)
        daily.brief(
            nowMs = nowMs,
            r = ev?.readiness,
            lastNight = store.sleep(today),
            events = emptyList(),
            reflectedIds = emptySet(),
            baselineWakeMinute = typicalWakeMinute(today),
            wakeMs = wakeMs(today),
            peak = store.latestPeak(),
            playsToday = playsToday(today),
            insufficientReason = ev?.calc?.insufficientReason,
        )
    }

    suspend fun watchState(reminders: NudgePrefs = NudgePrefs()): WatchState {
        val today = today()
        val events = upcoming()
        val ev = evaluation(today)
        return WatchState(
            readiness = ev?.readiness,
            nextEvent = events.firstOrNull(),
            upcoming = events.take(MomentRules.MAX_ACTIVE),
            updatedMs = clock(),
            sleepMinutes = store.sleep(today)?.asleepMinutes,
            brief = brief(),
            reflectedIds = store.reflections().map { it.eventId }.toSet(),
            reminders = reminders,
            evaluation = ev,
            peak = store.latestPeak(),
            playsToday = playsToday(today),
            wakeMs = wakeMs(today),
        )
    }

    fun encodeWatchState(s: WatchState): String = cognitiveJson.encodeToString(WatchState.serializer(), s)
    fun decodeWatchState(json: String): WatchState? =
        runCatching { cognitiveJson.decodeFromString(WatchState.serializer(), json) }.getOrNull()

    suspend fun clearAll() = store.clearAll()

    /**
     * The latest glucose reading from the last 3 hours when it is low (under 3.9 mmol/L, IHSG/ADA 2017), else null.
     * Shown live on Home rather than folded into the score: a low is a now-problem, not a day trend.
     */
    suspend fun lowGlucose(nowMs: Long = clock()): Double? =
        store.latestMetric(HealthMetricType.BLOOD_GLUCOSE)
            ?.takeIf { it.quality == DataQuality.VALID && nowMs - it.measuredMs in 0..3 * 3_600_000L && it.value < 3.9 }
            ?.value

    companion object {
        const val WAKE_HISTORY_NIGHTS = 14
        const val DEFAULT_WAKE_MINUTE = 7 * 60
        private const val DAY_MS = 24 * HOUR_MS
    }
}
