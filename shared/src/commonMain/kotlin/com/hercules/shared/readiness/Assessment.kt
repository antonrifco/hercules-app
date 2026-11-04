package com.hercules.shared.readiness

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Which device ran an assessment. Phone and watch never share a baseline (spec §4.7). */
@Serializable
enum class DeviceKind(val label: String) { PHONE("Phone"), WATCH("Watch") }

/**
 * Part of the waking day a Beat the Clock run fell in, in hours after waking. Not a schedule: the game can be played
 * any time, as often as you like. The label only groups runs so the peak window can be found.
 */
@Serializable
enum class AssessmentWindow(val label: String, val fromHours: Double, val toHours: Double) {
    MORNING("Morning", 0.0, 4.0),
    AFTERNOON("Afternoon", 4.0, 8.0),
    EVENING("Evening", 8.0, 12.0);

    fun startMs(wakeMs: Long): Long = wakeMs + (fromHours * HOUR).toLong()
    fun endMs(wakeMs: Long): Long = wakeMs + (toHours * HOUR).toLong()
    fun contains(wakeMs: Long, t: Long): Boolean = at(wakeMs, t) == this

    companion object {
        private const val HOUR = 3_600_000.0

        /** Always a part of the day: before waking counts as morning, late at night as evening. */
        fun at(wakeMs: Long, t: Long): AssessmentWindow {
            val h = (t - wakeMs) / HOUR
            return when {
                h < AFTERNOON.fromHours -> MORNING
                h < EVENING.fromHours -> AFTERNOON
                else -> EVENING
            }
        }
    }
}

@Serializable
enum class RoundValidity(val label: String) {
    VALID("Valid"),
    INTERRUPTED("Interrupted"),
    IMPLAUSIBLE("Implausible tap"),
}

@Serializable
enum class SessionStatus(val label: String) {
    FULLY_VALID("Valid"),
    PARTIALLY_VALID("Partly valid"),
    INVALID("Invalid"),
}

@Serializable
data class CognitiveRound(
    val id: String,
    val sessionId: String,
    val order: Int,
    val targetMs: Long,
    val actualMs: Long,
    val validity: RoundValidity,
) {
    val signedErrorMs: Long get() = actualMs - targetMs
    val absoluteErrorMs: Long get() = abs(signedErrorMs)
    val relativeError: Double get() = (actualMs - targetMs).toDouble() / targetMs
}

@Serializable
data class CognitiveSession(
    val id: String,
    val deviceKind: DeviceKind,
    val deviceId: String,
    /** Part of the day it was played in (null only for runs stored before the game went schedule-free). */
    val window: AssessmentWindow?,
    val dayKey: String,
    val startMs: Long,
    val endMs: Long,
    val status: SessionStatus,
    val practice: Boolean,
    /** median(|relative error|) */
    val accuracyError: Double?,
    /** MAD(relative error) */
    val consistencyError: Double?,
    /** median(relative error); negative = taps too early. */
    val bias: Double?,
    val accuracyScore: Int?,
    val consistencyScore: Int?,
    val biasScore: Int?,
    val cognitiveScore: Int?,
    val gameVersion: Int,
    val rounds: List<CognitiveRound>,
    /** Self-reported energy, 1 (drained) to 5 (charged), asked during the game. Optional; never part of the score. */
    val energy: Int? = null,
    /** Self-reported stress, 1 (calm) to 5 (tense), asked during the game. Optional; never part of the score. */
    val stress: Int? = null,
) {
    val valid: Boolean get() = status != SessionStatus.INVALID
    val scored: Boolean get() = cognitiveScore != null
}

/** The rules of Beat the Clock, shared by phone and watch. Changing any of them means a new [GAME_VERSION]. */
object BeatTheClock {
    /**
     * v3: three rounds that get longer (15–19 s, 20–24 s, 25–30 s, whole seconds picked at random each game) and a
     * visible "1… 2… 3…" lead-in. Only same-version games form a baseline.
     */
    const val GAME_VERSION = 3
    /** One band per round, in order: each round's goal is a random whole second inside its band. */
    val ROUND_BANDS_S: List<IntRange> = listOf(15..19, 20..24, 25..30)
    val ROUNDS: Int get() = ROUND_BANDS_S.size
    /** Seconds counted on screen at the start of each round, then the count disappears. */
    const val COUNT_SHOWN_SECONDS = 3
    const val MIN_VALID_ROUNDS = 2
    /** Games on a device before its baseline is fully personal; until then it leans on the reference below. */
    const val MIN_BASELINE_SESSIONS = 3
    /** Typical relative errors for a 15–30 s estimate, used until the player has their own history. */
    const val REFERENCE_ACCURACY_ERROR = 0.12
    const val REFERENCE_CONSISTENCY_ERROR = 0.08
    const val BASELINE_DAYS = 28
    /** A tap faster than 0.2× or slower than 4× the target is treated as a slip, not an estimate. */
    const val MIN_FACTOR = 0.2
    const val MAX_FACTOR = 4.0
    const val RATIO_FLOOR = 0.125
    const val ERROR_FLOOR = 0.01

    /** Three goals, shortest first, each a random whole second in its round's band (spec §4.3). */
    fun plan(random: Random = Random.Default): List<Long> =
        ROUND_BANDS_S.map { band -> band.random(random) * 1_000L }

    /** Validity of one round from monotonic timestamps ([startNanos], [stopNanos]) and whether it was interrupted. */
    fun round(id: String, sessionId: String, order: Int, targetMs: Long, startNanos: Long, stopNanos: Long, interrupted: Boolean): CognitiveRound {
        val actual = ((stopNanos - startNanos) / 1_000_000L).coerceAtLeast(0)
        val validity = when {
            interrupted -> RoundValidity.INTERRUPTED
            actual < targetMs * MIN_FACTOR || actual > targetMs * MAX_FACTOR -> RoundValidity.IMPLAUSIBLE
            else -> RoundValidity.VALID
        }
        return CognitiveRound(id, sessionId, order, targetMs, actual, validity)
    }

    /** Partially valid: at least 2 of the 3 rounds valid. Fully valid: all 3 (spec §4.5). */
    fun status(rounds: List<CognitiveRound>): SessionStatus {
        val valid = rounds.filter { it.validity == RoundValidity.VALID }
        return when {
            valid.size >= ROUNDS -> SessionStatus.FULLY_VALID
            valid.size >= MIN_VALID_ROUNDS -> SessionStatus.PARTIALLY_VALID
            else -> SessionStatus.INVALID
        }
    }

    /** `score = clamp(50 − 20·log2(max(ratio, floor)), 0, 100)` (spec §4.6). Lower error than usual scores above 50. */
    fun ratioScore(current: Double, baseline: Double): Int {
        val ratio = current / baseline.coerceAtLeast(ERROR_FLOOR)
        return Stats.clamp(50 - 20 * Stats.log2(maxOf(ratio, RATIO_FLOOR)), 0.0, 100.0).roundToInt()
    }

    fun biasScore(medianSigned: Double, medianAbs: Double): Int =
        (100 * (1 - Stats.clamp(abs(medianSigned) / maxOf(medianAbs, ERROR_FLOOR), 0.0, 1.0))).roundToInt()

    /**
     * Completes a session: validity, practice flag, error statistics and, when a baseline exists, scores.
     * [history] holds this user's earlier sessions (any device); only same-device, valid, non-practice sessions from
     * the last [BASELINE_DAYS] days form the baseline, whatever time of day they were played.
     */
    fun finish(
        id: String,
        deviceKind: DeviceKind,
        deviceId: String,
        window: AssessmentWindow?,
        dayKey: String,
        startMs: Long,
        endMs: Long,
        rounds: List<CognitiveRound>,
        history: List<CognitiveSession>,
    ): CognitiveSession {
        val status = status(rounds)
        // No practice games any more: every valid game is scored. Older games may still carry the flag.
        val practice = false
        val rel = rounds.filter { it.validity == RoundValidity.VALID }.map { it.relativeError }
        val accErr = if (status != SessionStatus.INVALID) Stats.median(rel.map { abs(it) }) else null
        val conErr = if (status != SessionStatus.INVALID) Stats.mad(rel) else null
        val bias = if (status != SessionStatus.INVALID) Stats.median(rel) else null

        var acc: Int? = null
        var con: Int? = null
        var bs: Int? = null
        if (!practice && accErr != null && conErr != null && bias != null) {
            // Scored from the first game: a typical-player reference at first, fully personal after 3 games.
            val base = baselineSessions(history, deviceKind, startMs)
            val share = minOf(base.size, MIN_BASELINE_SESSIONS).toDouble() / MIN_BASELINE_SESSIONS
            val pAcc = Stats.median(base.mapNotNull { it.accuracyError }) ?: REFERENCE_ACCURACY_ERROR
            val pCon = Stats.median(base.mapNotNull { it.consistencyError }) ?: REFERENCE_CONSISTENCY_ERROR
            val bAcc = share * pAcc + (1 - share) * REFERENCE_ACCURACY_ERROR
            val bCon = share * pCon + (1 - share) * REFERENCE_CONSISTENCY_ERROR
            acc = ratioScore(accErr, bAcc)
            con = ratioScore(maxOf(conErr, ERROR_FLOOR), maxOf(bCon, ERROR_FLOOR))
            bs = biasScore(bias, accErr)
        }
        val total = if (acc != null && con != null && bs != null) (0.70 * acc + 0.20 * con + 0.10 * bs).roundToInt() else null
        return CognitiveSession(
            id, deviceKind, deviceId, window, dayKey, startMs, endMs, status, practice,
            accErr, conErr, bias, acc, con, bs, total, GAME_VERSION, rounds,
        )
    }

    fun baselineSessions(history: List<CognitiveSession>, device: DeviceKind, beforeMs: Long): List<CognitiveSession> =
        history.filter {
            it.deviceKind == device && it.gameVersion == GAME_VERSION && it.valid && !it.practice && it.accuracyError != null &&
                it.startMs < beforeMs && beforeMs - it.startMs <= BASELINE_DAYS * 86_400_000L
        }

}

// ------------------------------------------------------------------ peak cognitive window (spec §5)

@Serializable
enum class PeakResult { PREDICTED, NO_CLEAR_PEAK, NOT_ENOUGH_DATA }

@Serializable
data class WindowPerformance(val window: AssessmentWindow, val mean: Double?, val sessions: Int, val days: Int)

@Serializable
data class PeakWindowPrediction(
    val result: PeakResult,
    val window: AssessmentWindow?,
    val performance: List<WindowPerformance>,
    val confidence: ReadinessConfidence,
    val validSessionCount: Int,
    val observationDays: Int,
    val updatedMs: Long,
    val modelVersion: Int = MODEL_VERSION,
) {
    companion object {
        const val MODEL_VERSION = 1
        const val WINDOW_DAYS = 28
        const val MIN_OBSERVATION_DAYS = 14
        const val MIN_SESSIONS_PER_WINDOW = 7
        const val MIN_DAYS_PER_WINDOW = 7
        const val MIN_GAP = 0.2
    }
}

object PeakWindowPredictor {

    fun predict(sessions: List<CognitiveSession>, nowMs: Long): PeakWindowPrediction {
        val from = nowMs - PeakWindowPrediction.WINDOW_DAYS * 86_400_000L
        val usable = sessions.filter { it.valid && !it.practice && it.window != null && it.accuracyError != null && it.startMs in from..nowMs }

        // Normalize per device: robust z of the accuracy error against that device's own 28-day distribution.
        val perf = usable.groupBy { it.deviceKind }.flatMap { (_, list) ->
            val errs = list.map { it.accuracyError!! }
            val m = Stats.median(errs)!!
            val mad = Stats.mad(errs)!!
            list.map { it to -Stats.robustZ(it.accuracyError!!, m, mad, minScale = 0.01) }
        }
        val byWindow = AssessmentWindow.entries.associateWith { w -> perf.filter { it.first.window == w } }
        val performance = AssessmentWindow.entries.map { w ->
            val list = byWindow.getValue(w)
            WindowPerformance(w, Stats.mean(list.map { it.second }), list.size, list.map { it.first.dayKey }.distinct().size)
        }
        val days = usable.map { it.dayKey }.distinct().size
        val eligible = days >= PeakWindowPrediction.MIN_OBSERVATION_DAYS &&
            performance.all { it.sessions >= PeakWindowPrediction.MIN_SESSIONS_PER_WINDOW && it.days >= PeakWindowPrediction.MIN_DAYS_PER_WINDOW }
        if (!eligible) {
            return PeakWindowPrediction(PeakResult.NOT_ENOUGH_DATA, null, performance, ReadinessConfidence.INSUFFICIENT, usable.size, days, nowMs)
        }
        val ranked = performance.sortedByDescending { it.mean!! }
        val best = ranked[0]
        val second = ranked[1]
        fun se(w: AssessmentWindow): Double {
            val v = byWindow.getValue(w).map { it.second }
            return (Stats.sd(v) ?: 0.0) / sqrt(v.size.toDouble())
        }
        val gapSe = sqrt(se(best.window).let { it * it } + se(second.window).let { it * it })
        val gap = best.mean!! - second.mean!!
        val threshold = maxOf(gapSe, PeakWindowPrediction.MIN_GAP)
        if (gap < threshold) {
            return PeakWindowPrediction(PeakResult.NO_CLEAR_PEAK, null, performance, ReadinessConfidence.LOW, usable.size, days, nowMs)
        }
        val confidence = when {
            gap >= 3 * threshold -> ReadinessConfidence.HIGH
            gap >= 2 * threshold -> ReadinessConfidence.MEDIUM
            else -> ReadinessConfidence.LOW
        }
        return PeakWindowPrediction(PeakResult.PREDICTED, best.window, performance, confidence, usable.size, days, nowMs)
    }
}
