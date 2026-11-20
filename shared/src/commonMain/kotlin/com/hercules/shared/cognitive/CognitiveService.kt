package com.hercules.shared.cognitive

import com.hercules.shared.data.HerculesRepository
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.readiness.AssessmentWindow
import com.hercules.shared.readiness.BaselineValue
import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.Calibration
import com.hercules.shared.readiness.CalibrationPoint
import com.hercules.shared.readiness.Calibrator
import com.hercules.shared.readiness.CognitiveInput
import com.hercules.shared.readiness.CognitiveRound
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.DayInputs
import com.hercules.shared.readiness.DeviceKind
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.GAME_FRESH_MS
import com.hercules.shared.readiness.HealthMetricType
import com.hercules.shared.readiness.METADATA_PHONE
import com.hercules.shared.readiness.MetricKey
import com.hercules.shared.readiness.MomentPreparationEngine
import com.hercules.shared.readiness.MomentRules
import com.hercules.shared.readiness.MomentStatus
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.PeakWindowPredictor
import com.hercules.shared.readiness.ReadinessCalculation
import com.hercules.shared.readiness.PrepContext
import com.hercules.shared.readiness.PrepPhase
import com.hercules.shared.readiness.PrepTask
import com.hercules.shared.readiness.ReadinessCalculator
import com.hercules.shared.readiness.REASON_BEFORE_WAKE
import com.hercules.shared.readiness.RETENTION_DAYS
import com.hercules.shared.readiness.ReadinessConfidence
import com.hercules.shared.readiness.Recovery
import com.hercules.shared.readiness.SCORING_ENGINE_VERSION
import com.hercules.shared.readiness.ScoreSettings
import com.hercules.shared.readiness.ScoringParams
import com.hercules.shared.readiness.Signal
import com.hercules.shared.readiness.SignalSource
import com.hercules.shared.readiness.SleepEpisode
import com.hercules.shared.readiness.SleepModel
import com.hercules.shared.readiness.ScreenSleep
import com.hercules.shared.model.MetricType
import com.hercules.shared.readiness.Stats
import com.hercules.shared.readiness.Urgency
import com.hercules.shared.readiness.meanSd
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
 * Orchestrates sleep resolution → inputs → CRS, Reaction Test, the peak window and Moment preparation.
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

    // ---------------------------------------------------------------- score settings

    suspend fun settings(): ScoreSettings = store.scoreSettings()

    /** Saves the sliders (clamped to their ranges) and recalculates today. Past scores keep the settings they had. */
    suspend fun saveSettings(s: ScoreSettings) {
        store.saveScoreSettings(s.clamped())
        refresh()
    }

    suspend fun restoreDefaultSettings() = saveSettings(ScoreSettings.DEFAULT)

    suspend fun calibration(): Calibration = store.calibration()

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
        val sessions = store.sleepSessions(dayKey).filter { it.quality == DataQuality.VALID && it.endMs <= nowMs && it.endMs >= analyzer.earliestWake(dayKey) }
        fun isStaged(s: com.hercules.shared.readiness.SleepSessionRecord) = listOf(s.deepMinutes, s.remMinutes, s.lightMinutes).any { it != null && it > 0 }
        // A staged session first, then other Health Connect sessions, then the phone's screen-use estimate.
        val hc = sessions.filter { isStaged(it) }.maxByOrNull { it.asleepMinutes }
            ?: sessions.filter { it.sourcePackage != ScreenSleep.SOURCE }.maxByOrNull { it.asleepMinutes }
            ?: sessions.maxByOrNull { it.asleepMinutes }
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
                provider = if (hc.sourcePackage == ScreenSleep.SOURCE) "Phone screen use" else hc.sourcePackage,
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
     * - Steps and resting heart rate: the Hercules watch (when it was worn that day) and Health Connect are compared
     *   and the fuller reading wins: the higher step count, the lower resting heart rate. Either source can miss
     *   part of the day (a watch worn for an hour, a phone left on the desk), and a partial day always reads low on
     *   steps and high on resting heart rate.
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
        return when (type) {
            HealthMetricType.HRV_RMSSD -> watch + hc
            HealthMetricType.STEPS_DAILY -> (hc.keys + watch.keys).associateWith { k -> maxOf(hc[k] ?: 0.0, watch[k] ?: 0.0) }
            HealthMetricType.RESTING_HR -> (hc.keys + watch.keys).associateWith { k -> minOf(hc[k] ?: Double.MAX_VALUE, watch[k] ?: Double.MAX_VALUE) }
            else -> hc + watch
        }
    }

    /**
     * Today's value of [type] and its earlier days from one source, never mixed (spec §2.11). Most reliable first:
     * Health Connect from a wearable (its own overnight measurement), the Hercules watch, then Health Connect
     * recorded on a phone. Each source keeps its own baseline.
     */
    private suspend fun signal(type: HealthMetricType, dayKey: String, fromKey: String): Signal? {
        val rows = store.metrics(type, fromKey, dayKey).filter { it.quality == DataQuality.VALID }
        fun daily(list: List<Double>) = if (type == HealthMetricType.RESTING_HR) list.min() else Stats.median(list)!!
        fun hc(phone: Boolean): Map<String, Double> = rows.filter { (it.metadata == METADATA_PHONE) == phone }
            .groupBy { it.dayKey }.mapValues { (_, l) -> daily(l.map { it.value }) }
        val watch: Map<String, Double> = when (type) {
            HealthMetricType.HRV_RMSSD -> repo.allDaily().filter { it.dayKey in fromKey..dayKey }.mapNotNull { d -> d.hrvRmssd?.let { d.dayKey to it } }.toMap()
            HealthMetricType.RESTING_HR -> repo.allDaily().filter { it.dayKey in fromKey..dayKey }.mapNotNull { d -> d.restingHr?.let { d.dayKey to it.toDouble() } }.toMap()
            else -> emptyMap()
        }
        for ((values, source) in listOf(hc(false) to SignalSource.WATCH, watch to SignalSource.WATCH, hc(true) to SignalSource.PHONE)) {
            val today = values[dayKey] ?: continue
            return Signal(today, values.filterKeys { it < dayKey }.entries.sortedBy { it.key }.map { it.value }, source)
        }
        return null
    }

    private fun sourceOf(n: SleepNight): SignalSource = when {
        n.source == SleepNight.SOURCE_USER -> SignalSource.ESTIMATED
        n.source == SleepNight.SOURCE_SENSOR -> SignalSource.WATCH
        // Sleep stages need a wearable; an unstaged session is usually a phone estimate.
        listOf(n.deepMinutes, n.remMinutes, n.lightMinutes).any { it != null && it > 0 } -> SignalSource.WATCH
        else -> SignalSource.PHONE
    }

    /**
     * The window's sleep episodes for the sleep model, oldest first. A missing night after the first recorded one is
     * filled with the usual window (median wake time and time in bed), so one forgotten night doesn't read as a night
     * awake. Today's assumed night only counts once its usual wake time has passed.
     */
    private fun episodes(nights: List<SleepNight>, dayKey: String, nowMs: Long): Pair<List<SleepEpisode>, Boolean> {
        if (nights.isEmpty()) return emptyList<SleepEpisode>() to false
        val wakeMinute = Stats.median(nights.map { dayKeys.minuteOfDay(it.endMs).toDouble() })!!
        val inBedMs = Stats.median(nights.map { (it.endMs - it.startMs).toDouble() })!!.toLong()
        val byDay = nights.associateBy { it.dayKey }
        val out = mutableListOf<SleepEpisode>()
        var assumedToday = false
        var d = nights.minOf { it.dayKey }
        while (d <= dayKey) {
            val n = byDay[d]
            if (n != null) out += SleepEpisode(n.startMs, n.endMs)
            else {
                val wake = dayKeys.startOf(d) + (wakeMinute * MIN_MS).toLong()
                if (wake <= nowMs) {
                    out += SleepEpisode(wake - inBedMs, wake)
                    if (d == dayKey) assumedToday = true
                }
            }
            d = dayKeys.plusDays(d, 1)
        }
        return out to assumedToday
    }

    /**
     * Naps and other sleep outside the main nights (spec §2.2): Health Connect sessions and the watch's sleep
     * episodes of at least [MIN_NAP_MIN] minutes that don't overlap a night. Each counts as sleep in the model.
     */
    private suspend fun naps(fromKey: String, dayKey: String, nowMs: Long, nights: List<SleepEpisode>): List<SleepEpisode> {
        val fromMs = dayKeys.startOf(fromKey) - 6 * HOUR_MS
        val candidates = store.sleepSessionsRange(fromKey, dayKeys.plusDays(dayKey, 1))
            .filter { it.quality == DataQuality.VALID && it.endMs <= nowMs && it.sourcePackage != ScreenSleep.SOURCE }.map { SleepEpisode(it.startMs, it.endMs) } +
            analyzer.episodes(repo.samplesOfType(MetricType.ACTIVITY_STATE, fromMs - 12 * HOUR_MS, nowMs), fromMs, nowMs).map { SleepEpisode(it.first, it.second) }
        val out = mutableListOf<SleepEpisode>()
        for (c in candidates.sortedBy { it.startMs }) {
            if (c.endMs - c.startMs < MIN_NAP_MIN * MIN_MS) continue
            if ((nights + out).any { c.startMs < it.endMs && it.startMs < c.endMs }) continue
            out += c
        }
        return out
    }

    /** Everything the calculator needs for the score at [nowMs] on [dayKey], using only data available then. */
    suspend fun inputs(dayKey: String, nowMs: Long): DayInputs {
        val from = dayKeys.plusDays(dayKey, -RETENTION_DAYS)
        val nights = store.sleepRange(from, dayKey).filter { it.endMs <= nowMs }.sortedBy { it.dayKey }
        val last = nights.lastOrNull { it.dayKey == dayKey }
        val (nightEpisodes, assumed) = episodes(nights, dayKey, nowMs)
        val episodes = (nightEpisodes + naps(from, dayKey, nowMs, nightEpisodes)).sortedBy { it.startMs }
        val sleepSource = when {
            last != null -> sourceOf(last)
            assumed -> SignalSource.ASSUMED
            else -> null
        }

        val glucoseRows = store.metrics(HealthMetricType.BLOOD_GLUCOSE, from, dayKey).filter { it.quality == DataQuality.VALID && it.measuredMs <= nowMs }
        val last24 = glucoseRows.filter { it.measuredMs > nowMs - DAY_MS }.map { it.value }
        val glucoseHistory = glucoseRows.filter { it.measuredMs <= nowMs - DAY_MS }.groupBy { it.dayKey }.entries.sortedBy { it.key }.map { (_, l) -> l.map { it.value }.average() }
        // A CGM (many readings a day) must stay low for ~15 minutes (3 readings), the consensus definition of a
        // hypoglycaemia event (Battelino 2019); a meter reading is a deliberate finger-prick, so one low counts.
        val cgm = last24.size >= 24
        val glucoseLow = last24.count { it < 3.9 } >= (if (cgm) 3 else 1)

        val hrv = signal(HealthMetricType.HRV_RMSSD, dayKey, from)
        val rhr = signal(HealthMetricType.RESTING_HR, dayKey, from)
        return DayInputs(
            dayKey = dayKey,
            atMs = nowMs,
            hourOfDay = dayKeys.minuteOfDay(nowMs) / 60.0,
            lastNight = last?.toInput(dayKeys),
            sleepSource = sleepSource,
            episodes = episodes,
            usualWakeHour = typicalWakeMinute(dayKey)?.let { it / 60.0 },
            hrv = hrv,
            restingHr = rhr,
            skinTemp = signal(HealthMetricType.SKIN_TEMPERATURE_DELTA, dayKey, from),
            spo2 = signal(HealthMetricType.SPO2, dayKey, from),
            respiratoryRate = signal(HealthMetricType.RESPIRATORY_RATE, dayKey, from),
            glucose = if (last24.isNotEmpty()) Signal(last24.average(), glucoseHistory, SignalSource.WATCH) else null,
            glucoseLow = glucoseLow,
            cognitive = cognitive(nowMs),
            historyDays = historyDays(dayKey, from, nights),
            settings = store.scoreSettings(),
            calibration = store.calibration(),
        )
    }

    /** Earlier days in the window with any data: a night, a heart reading, steps from a worn watch, or a test. */
    private suspend fun historyDays(dayKey: String, from: String, nights: List<SleepNight>): Int {
        val days = mutableSetOf<String>()
        days += nights.map { it.dayKey }
        days += repo.allDaily().filter { it.hrSampleCount > 0 }.map { it.dayKey }
        for (t in listOf(HealthMetricType.HRV_RMSSD, HealthMetricType.RESTING_HR, HealthMetricType.STEPS_DAILY)) days += store.metrics(t, from, dayKey).map { it.dayKey }
        days += store.sessionsFrom(dayKeys.startOf(from)).filter { it.valid }.map { it.dayKey }
        return days.count { it in from..dayKey && it < dayKey }
    }

    /** The Reaction Test that counts now: the median score of the tests that ended in the last 2 hours, either device. */
    private suspend fun cognitive(nowMs: Long): CognitiveInput? {
        val runs = store.sessionsFrom(nowMs - GAME_FRESH_MS - HOUR_MS)
            .filter { it.scored && it.endMs <= nowMs && nowMs - it.endMs <= GAME_FRESH_MS }
        if (runs.isEmpty()) return null
        val score = Stats.median(runs.mapNotNull { it.cognitiveScore?.toDouble() })!!.roundToInt()
        val ms = Stats.median(runs.mapNotNull { it.medianMs })?.roundToInt()
        val label = (if (runs.size == 1) "1 test · ${runs[0].deviceKind.label}" else "Median of ${runs.size} tests") + (ms?.let { " · $it ms" } ?: "")
        return CognitiveInput(score, label, runs.size)
    }

    // ---------------------------------------------------------------- refresh

    /**
     * Re-resolves the last two nights and recalculates the day containing [atMs] (default: now) with the active
     * configuration. Also refreshes baselines, the peak window, Moment lifecycles and today's Moment snapshots.
     */
    suspend fun refresh(atMs: Long = clock()): Evaluation = withContext(Dispatchers.Default) { mutex.withLock {
        val today = dayKeys.of(atMs)
        for (back in RECALC_DAYS downTo 0) resolveSleep(dayKeys.plusDays(today, -back), atMs)
        // Health Connect hands over its history in one go (10 days on the first import). Give each older night its
        // resolved row once, so the sleep model and the week chart can use it from day one.
        purgeOld(atMs)
        // Older nights in the window: fill missing ones, and upgrade a phone-tier night (unstaged Health Connect or the
        // screen-use estimate) as soon as watch sleep for it arrives, however late. Your own edits are never replaced.
        for (back in RECALC_DAYS + 1..SLEEP_BACKFILL_DAYS) {
            val day = dayKeys.plusDays(today, -back)
            val night = store.sleep(day)
            val phoneTier = night != null && !night.corrected && sourceOf(night) != SignalSource.WATCH
            if ((night == null && store.sleepSessions(day).any { it.quality == DataQuality.VALID }) || phoneTier) resolveSleep(day, atMs)
        }

        rescoreUnscored(today, atMs)
        updateCalibration(atMs)
        recalcRecent(today, atMs)
        val input = inputs(today, atMs)
        // A day's score starts at wake-up. Between midnight and waking there is no night behind it yet, and whatever
        // has trickled in (ten minutes of pulse, yesterday's steps) would pass for a score. Withhold it until then.
        val beforeWake = input.lastNight == null && atMs < wakeMs(today)
        val calc = calculator.calculate(input).let { c ->
            if (!beforeWake) c
            else c.copy(score = null, confidence = ReadinessConfidence.INSUFFICIENT, insufficientReason = REASON_BEFORE_WAKE, confidenceHint = null, modifiers = emptyList())
        }
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
        val baselineDays = store.sleepRange(dayKeys.plusDays(dayKey, -RETENTION_DAYS), dayKeys.plusDays(dayKey, -1)).size
        val readiness = calc.score?.let { score ->
            val band = ReadinessBand.of(score)
            // Same rule as the Coach's "Lights out": the sleep target plus 15 minutes before the next wake-up.
            val bedtime = dayKeys.clock(wake + DAY_MS - (params.sleepTargetHours * 60).toLong() * MIN_MS - 15 * MIN_MS)
            Readiness(dayKey, score, band, calc, baselineDays, wake, Tips.build(calc, band, store.sleep(dayKey), bedtime), atMs)
        }
        readiness?.let { store.upsertReadiness(it) }
        return Evaluation(dayKey, calc, readiness, wake, atMs).also { store.saveEvaluation(it) }
    }

    /**
     * Data often arrives late: last night's sleep after midnight, a watch that synced hours later. The previous
     * [RECALC_DAYS] days are recalculated with what is known now, with the settings each day was first scored with,
     * and saved only when they change. A past day is scored at its wake time + 6 hours, so the history shows the
     * middle of each day, not its last minutes awake. Older days stay as they were.
     */
    private suspend fun recalcRecent(today: String, atMs: Long) {
        for (back in 1..RECALC_DAYS) {
            val day = dayKeys.plusDays(today, -back)
            val old = store.evaluation(day)
            val endOfDay = dayKeys.startOf(dayKeys.plusDays(day, 1)) - 1
            val at = minOf(wakeMs(day) + PAST_DAY_OFFSET_MS, endOfDay)
            val input = inputs(day, at).let { i -> old?.calc?.stamp?.settings?.let { i.copy(settings = it) } ?: i }
            val calc = calculator.calculate(input)
            if (old == null && calc.score == null) continue
            if (old != null && old.calc.score == calc.score && old.calc.confidencePct == calc.confidencePct && old.calc.stamp.engineVersion == calc.stamp.engineVersion) continue
            save(day, calc, atMs)
        }
    }

    /** Once a day: everything older than [RETENTION_DAYS] days is deleted (spec §2.12). */
    private suspend fun purgeOld(nowMs: Long) {
        val today = dayKeys.of(nowMs)
        if (store.kvGet(KEY_PURGED) == today) return
        val cutoffKey = dayKeys.plusDays(today, -RETENTION_DAYS)
        store.purgeBefore(cutoffKey, dayKeys.startOf(cutoffKey))
        store.kvPut(KEY_PURGED, today)
    }

    /**
     * Counts new Reaction Tests and, from [Calibrator.MIN_SESSIONS] tests on, fits the body-clock peak, its amplitude
     * and the recovery weights to the tests in the window (spec §2.7). Only the fitted numbers are kept.
     */
    private suspend fun updateCalibration(nowMs: Long) {
        var cal = store.calibration()
        val window = store.sessionsFrom(nowMs - RETENTION_DAYS * DAY_MS)
            .filter { it.gameVersion == BeatTheClock.GAME_VERSION && it.valid && it.cognitiveScore != null && it.endMs <= nowMs }
        val fresh = window.filter { it.endMs > cal.lastSessionMs }
        if (fresh.isEmpty()) return
        cal = cal.copy(sessions = cal.sessions + fresh.size, lastSessionMs = fresh.maxOf { it.endMs })
        if (cal.sessions >= Calibrator.MIN_SESSIONS) {
            val now = inputs(dayKeys.of(nowMs), nowMs)
            cal = Calibrator.fit(
                window.mapNotNull { point(it) }, now.episodes, SleepModel.offset(nowMs, now.hourOfDay),
                now.usualWakeHour ?: SleepModel.DEFAULT_WAKE_HOUR, cal, nowMs,
            )
        }
        store.saveCalibration(cal)
    }

    /** The model's state when [s] was played, without the game itself. */
    private suspend fun point(s: CognitiveSession): CalibrationPoint? {
        val input = inputs(s.dayKey, s.startMs).copy(cognitive = null)
        if (input.sleepSource == null) return null
        val terms = Recovery.terms(input, Calibration(), ScoreSettings.DEFAULT)
        fun x(k: MetricKey) = terms.firstOrNull { it.key == k }?.let { it.bad * if (it.source == SignalSource.PHONE) Recovery.PHONE_FACTOR else 1.0 } ?: 0.0
        val fitted = setOf(MetricKey.HRV, MetricKey.RESTING_HR, MetricKey.SKIN_TEMP)
        return CalibrationPoint(
            s.cognitiveScore!!.toDouble(), s.startMs, x(MetricKey.HRV), x(MetricKey.RESTING_HR), x(MetricKey.SKIN_TEMP),
            terms.filter { it.key !in fitted }.sumOf { it.penalty },
        )
    }

    private suspend fun saveBaselines(input: DayInputs, nowMs: Long) {
        val v = SCORING_ENGINE_VERSION
        val list = mutableListOf<StoredBaseline>()
        fun add(key: MetricKey, s: Signal?) {
            val (m, sd) = meanSd(s?.history ?: return) ?: return
            list += StoredBaseline(key.name, null, null, BaselineValue(m, sd, s.history.size, 1.0), v, nowMs)
        }
        add(MetricKey.HRV, input.hrv)
        add(MetricKey.RESTING_HR, input.restingHr)
        val history = store.sessionsFrom(nowMs - BeatTheClock.BASELINE_DAYS * DAY_MS)
        for (d in DeviceKind.entries) {
            val base = BeatTheClock.baselineSessions(history, d, nowMs + 1)
            val speeds = base.mapNotNull { it.speed }
            if (speeds.isEmpty()) continue
            val share = if (speeds.size >= BeatTheClock.MIN_BASELINE_SESSIONS) 1.0 else 0.0
            list += StoredBaseline(MetricKey.BTC_SPEED.name, d, null, BaselineValue(Stats.median(speeds)!!, Stats.mad(speeds)!!, speeds.size, share), "btc-${BeatTheClock.GAME_VERSION}", nowMs)
        }
        store.saveBaselines(list)
    }

    suspend fun evaluation(dayKey: String = today()): Evaluation? = store.evaluation(dayKey)

    // ---------------------------------------------------------------- Reaction Test

    suspend fun playsToday(dayKey: String = today()): Int =
        store.sessionsFrom(dayKeys.startOf(dayKey)).count { it.dayKey == dayKey && it.valid }



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

    /** Valid tests from today that never got a score are scored now, so nobody sees "locked" after playing. */
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
        val recent = store.sleepRange(dayKeys.plusDays(today, -2), today)
        val wakeMinute = typicalWakeMinute(today) ?: store.sleep(today)?.let { dayKeys.minuteOfDay(it.endMs) } ?: DEFAULT_WAKE_MINUTE
        return PrepContext(
            nowMs = nowMs,
            daysUntil = dayKeys.daysBetween(today, eventDay),
            eventStartMs = e.startMs,
            urgency = e.urgency,
            latestScore = calc?.score,
            latestConfidence = calc?.confidence,
            latestCoverage = calc?.coverage,
            sleepScore = dim(Dimension.SLEEP),
            consistencyScore = null,
            cardioScore = dim(Dimension.CARDIO),
            activityScore = null,
            lightScore = null,
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

    /**
     * Moments that started in the last [hours] hours and were not cancelled. They are no longer "upcoming", so the
     * "how did it go?" reminder needs them handed over separately.
     */
    suspend fun recentlyEnded(nowMs: Long = clock(), hours: Int = 12): List<EventStatus> {
        val ev = evaluation(dayKeys.of(nowMs))
        val pk = store.latestPeak()
        return moments().filter { it.status != MomentStatus.CANCELLED && it.startMs <= nowMs && nowMs - it.startMs <= hours * HOUR_MS }
            .sortedBy { it.startMs }.map { eventStatus(it, nowMs, ev, pk) }
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
            ended = recentlyEnded(),
            scoreSettings = store.scoreSettings(),
            calibration = store.calibration(),
        )
    }

    fun encodeWatchState(s: WatchState): String = cognitiveJson.encodeToString(WatchState.serializer(), s)
    fun decodeWatchState(json: String): WatchState? =
        runCatching { cognitiveJson.decodeFromString(WatchState.serializer(), json) }.getOrNull()

    suspend fun clearAll() = store.clearAll()

    /**
     * The user's best days in what the device keeps (the last $RETENTION_DAYS days), from whatever source delivered them (Health Connect, the watch, the
     * games): highest score, sharpest game, longest sleep, most steps, lowest resting heart rate, highest HRV.
     */
    suspend fun personalBests(): List<PersonalBest> {
        val to = today()
        val from = dayKeys.plusDays(to, -365)
        val out = mutableListOf<PersonalBest>()
        store.readinessRange(from, to).maxByOrNull { it.score }?.let { out += PersonalBest("Highest score", "${it.score}", it.dayKey) }
        store.sessionsFrom(dayKeys.startOf(from))
            .filter { it.gameVersion == BeatTheClock.GAME_VERSION && it.valid && it.cognitiveScore != null }
            .maxByOrNull { it.cognitiveScore!! }?.let { out += PersonalBest("Fastest Reaction Test", "${it.cognitiveScore} / 100", it.dayKey) }
        store.sleepRange(from, to).maxByOrNull { it.asleepMinutes }?.let { out += PersonalBest("Longest sleep", formatDuration(it.asleepMinutes), it.dayKey) }
        dailyValues(HealthMetricType.STEPS_DAILY, from, to).filterValues { it > 0 }.maxByOrNull { it.value }
            ?.let { out += PersonalBest("Most steps", it.value.grouped(), it.key) }
        dailyValues(HealthMetricType.RESTING_HR, from, to).minByOrNull { it.value }
            ?.let { out += PersonalBest("Lowest resting heart rate", "${it.value.roundToInt()} bpm", it.key) }
        dailyValues(HealthMetricType.HRV_RMSSD, from, to).maxByOrNull { it.value }
            ?.let { out += PersonalBest("Highest HRV", "${it.value.roundToInt()} ms", it.key) }
        return out
    }

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
        /** How far back a Health Connect night without a resolved row is still picked up. Matches the first import. */
        const val SLEEP_BACKFILL_DAYS = RETENTION_DAYS
        private const val KEY_PURGED = "purged_day"
        private const val PAST_DAY_OFFSET_MS = 6 * HOUR_MS
        /** Shorter sleep than this is ignored (dozing, sensor noise). */
        const val MIN_NAP_MIN = 15
        const val DEFAULT_WAKE_MINUTE = 7 * 60
        private const val DAY_MS = 24 * HOUR_MS
    }
}

/** One personal best: what it is, the value in plain words, and the day it happened. */
data class PersonalBest(val label: String, val value: String, val dayKey: String)
