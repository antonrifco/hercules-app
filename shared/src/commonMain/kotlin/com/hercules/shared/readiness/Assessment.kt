package com.hercules.shared.readiness

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/** Which device ran an assessment. Phone and watch never share a baseline (spec §4.7). */
@Serializable
enum class DeviceKind(val label: String) { PHONE("Phone"), WATCH("Watch") }

/**
 * Part of the waking day a Reaction Test fell in, in hours after waking. Not a schedule: the game can be played
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
    /** Tapped before Herc appeared, or within [BeatTheClock.FALSE_START_MS] of it: a guess, not a reaction. */
    FALSE_START("Too soon"),
}

@Serializable
enum class SessionStatus(val label: String) {
    FULLY_VALID("Valid"),
    PARTIALLY_VALID("Partly valid"),
    INVALID("Invalid"),
}

/** One trial: Herc appeared [delayMs] after the previous trial; [reactionMs] from appearing to the tap (0 if too soon). */
@Serializable
data class CognitiveRound(
    val id: String,
    val sessionId: String,
    val order: Int,
    val delayMs: Long,
    val reactionMs: Long,
    val validity: RoundValidity,
) {
    val lapse: Boolean get() = validity == RoundValidity.VALID && reactionMs >= BeatTheClock.LAPSE_MS
}

@Serializable
data class CognitiveSession(
    val id: String,
    val deviceKind: DeviceKind,
    val deviceId: String,
    /** Part of the day it was played in. */
    val window: AssessmentWindow?,
    val dayKey: String,
    val startMs: Long,
    val endMs: Long,
    val status: SessionStatus,
    /** Mean response speed: the average of 1000 / reaction ms over the reactions (taps per second). Higher is faster. */
    val speed: Double?,
    /** Median reaction time, ms. */
    val medianMs: Double?,
    /** Reactions of [BeatTheClock.LAPSE_MS] or slower: moments attention dropped. */
    val lapses: Int?,
    /** Taps before Herc appeared. */
    val falseStarts: Int?,
    /** Speed against this device's usual, 50 = usual. */
    val speedScore: Int?,
    /** The game's score: speed, less a little for false starts beyond a couple of slips. */
    val cognitiveScore: Int?,
    val gameVersion: Int,
    val rounds: List<CognitiveRound>,
    /** Self-reported energy, 1 (drained) to 5 (charged), asked after the game. Optional; never part of the score. */
    val energy: Int? = null,
    /** Self-reported stress, 1 (calm) to 5 (tense), asked after the game. Optional; never part of the score. */
    val stress: Int? = null,
) {
    val valid: Boolean get() = status != SessionStatus.INVALID
    val scored: Boolean get() = cognitiveScore != null
}

/**
 * Reaction Test: a 3-minute reaction test modelled on the brief Psychomotor Vigilance Test (PVT-B, Basner & Dinges
 * 2011), the standard measure of how sleep loss slows attention. Herc appears after a random 1–4 s wait; tap as fast as
 * you can. Phone and watch use the same rules. Changing any of them means a new [GAME_VERSION].
 */
object BeatTheClock {
    /** v4: reaction test. (v1–v3 were a time-estimation game; their data was removed.) */
    const val GAME_VERSION = 4
    /** Trials per test, false starts included: about 3 minutes, like PVT-B, and a fixed count the screen can show. */
    const val TRIALS = 45
    /** Random wait before Herc appears, as in PVT-B. */
    const val MIN_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 4_000L
    /** Faster than this after Herc appears is anticipation, not a reaction (standard PVT rule). */
    const val FALSE_START_MS = 100L
    /**
     * A lapse: a reaction this slow or slower. PVT-B uses 355 ms with a hardware button; a touchscreen adds about
     * 50–100 ms, so the classic 500 ms PVT threshold is used.
     */
    const val LAPSE_MS = 500L
    /** No tap this long after Herc appears: recorded as a lapse at this time, and the next trial starts. */
    const val TIMEOUT_MS = 3_000L
    /** Reactions needed for a fully valid test, and for one that still counts. */
    const val FULL_TRIALS = 30
    const val MIN_TRIALS = 15
    /** More false starts than this share of all taps means the test was guessed, not played. */
    const val MAX_FALSE_START_SHARE = 0.25
    /** False starts allowed before they cost points. */
    const val FREE_FALSE_STARTS = 2
    const val FALSE_START_PENALTY = 5
    /** Games on a device before its usual is fully personal; until then it leans on the reference below. */
    const val MIN_BASELINE_SESSIONS = 3
    const val BASELINE_DAYS = 28
    /** Typical touchscreen speed until there is a personal usual: about 330 ms on a phone, 370 ms on a watch. */
    fun referenceSpeed(device: DeviceKind): Double = when (device) { DeviceKind.PHONE -> 3.0; DeviceKind.WATCH -> 2.7 }
    /** Points per unit of ln(speed / usual): 10% slower than usual ≈ 34, 10% faster ≈ 64. */
    const val SPEED_GAIN = 150.0

    /** The wait before the next appearance, a random whole number of milliseconds in 1–4 s. */
    fun nextDelay(random: Random = Random.Default): Long = random.nextLong(MIN_DELAY_MS, MAX_DELAY_MS + 1)

    /** A tap after Herc appeared: [reactionMs] from appearing to the tap (capped at [TIMEOUT_MS] when there was none). */
    fun reaction(id: String, sessionId: String, order: Int, delayMs: Long, reactionMs: Long): CognitiveRound {
        val rt = reactionMs.coerceIn(0, TIMEOUT_MS)
        return CognitiveRound(id, sessionId, order, delayMs, rt, if (rt < FALSE_START_MS) RoundValidity.FALSE_START else RoundValidity.VALID)
    }

    /** A tap while waiting, before Herc appeared. */
    fun falseStart(id: String, sessionId: String, order: Int, delayMs: Long): CognitiveRound =
        CognitiveRound(id, sessionId, order, delayMs, 0, RoundValidity.FALSE_START)

    fun status(rounds: List<CognitiveRound>): SessionStatus {
        val reactions = rounds.count { it.validity == RoundValidity.VALID }
        val falseStarts = rounds.size - reactions
        return when {
            rounds.isNotEmpty() && falseStarts.toDouble() / rounds.size > MAX_FALSE_START_SHARE -> SessionStatus.INVALID
            reactions >= FULL_TRIALS -> SessionStatus.FULLY_VALID
            reactions >= MIN_TRIALS -> SessionStatus.PARTIALLY_VALID
            else -> SessionStatus.INVALID
        }
    }

    /** `clamp(50 + 150·ln(speed / usual), 0, 100)`: 50 is the usual, higher is faster. */
    fun speedScore(speed: Double, usual: Double): Int =
        Stats.clamp(50 + SPEED_GAIN * ln(speed / usual.coerceAtLeast(0.1)), 0.0, 100.0).roundToInt()

    /**
     * Completes a test: validity, speed, lapses, false starts and the score. [history] holds earlier sessions (any
     * device); only same-device valid sessions of this version from the last [BASELINE_DAYS] days form the usual.
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
        val reactions = rounds.filter { it.validity == RoundValidity.VALID }
        val falseStarts = rounds.size - reactions.size
        val ok = status != SessionStatus.INVALID
        val speed = if (ok) reactions.map { 1000.0 / it.reactionMs }.average() else null
        val median = if (ok) Stats.median(reactions.map { it.reactionMs.toDouble() }) else null
        val lapses = if (ok) reactions.count { it.lapse } else null
        var speedScore: Int? = null
        var total: Int? = null
        if (speed != null) {
            speedScore = speedScore(speed, usualSpeed(history, deviceKind, startMs))
            total = (speedScore - FALSE_START_PENALTY * maxOf(0, falseStarts - FREE_FALSE_STARTS)).coerceIn(0, 100)
        }
        return CognitiveSession(
            id, deviceKind, deviceId, window, dayKey, startMs, endMs, status,
            speed, median, lapses, if (rounds.isEmpty()) null else falseStarts, speedScore, total, GAME_VERSION, rounds,
        )
    }

    /** This device's usual speed: the reference at first, fully personal after [MIN_BASELINE_SESSIONS] games. */
    fun usualSpeed(history: List<CognitiveSession>, device: DeviceKind, beforeMs: Long): Double {
        val base = baselineSessions(history, device, beforeMs)
        val share = minOf(base.size, MIN_BASELINE_SESSIONS).toDouble() / MIN_BASELINE_SESSIONS
        val personal = Stats.median(base.mapNotNull { it.speed }) ?: referenceSpeed(device)
        return share * personal + (1 - share) * referenceSpeed(device)
    }

    /** The quick check's five stress choices, 1 (calm) to 5 (tense). Energy uses Herc's five faces instead. */
    val STRESS_FACES = listOf("😌", "🙂", "😐", "😟", "😰")

    /** A game score in plain words. 50 is the player's usual, so the words compare against that. */
    fun verdict(score: Int): String = when {
        score >= 65 -> "Faster than usual"
        score >= 40 -> "About your usual"
        score >= 20 -> "Slower than usual"
        else -> "Much slower than usual"
    }

    /** The usual reaction time a game was scored against, in ms, recovered from its speed score (null at the clamped ends). */
    fun usualMs(s: CognitiveSession): Double? {
        val speed = s.speed ?: return null
        val score = s.speedScore?.takeIf { it in 1..99 } ?: return null
        return 1000.0 / (speed / exp((score - 50) / SPEED_GAIN))
    }

    fun baselineSessions(history: List<CognitiveSession>, device: DeviceKind, beforeMs: Long): List<CognitiveSession> =
        history.filter {
            it.deviceKind == device && it.gameVersion == GAME_VERSION && it.valid && it.speed != null &&
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
        /** v3: reaction speed (game v4). v2: only games of the current version; 7 days, 4 games on 3 days. */
        const val MODEL_VERSION = 3
        const val WINDOW_DAYS = 28
        const val MIN_OBSERVATION_DAYS = 7
        const val MIN_SESSIONS_PER_WINDOW = 4
        const val MIN_DAYS_PER_WINDOW = 3
        const val MIN_GAP = 0.2
    }
}

object PeakWindowPredictor {

    fun predict(sessions: List<CognitiveSession>, nowMs: Long): PeakWindowPrediction {
        val from = nowMs - PeakWindowPrediction.WINDOW_DAYS * 86_400_000L
        // Only same-version games: a different game gives numbers that can't be compared.
        val usable = sessions.filter {
            it.gameVersion == BeatTheClock.GAME_VERSION && it.valid && it.window != null && it.speed != null && it.startMs in from..nowMs
        }

        // Normalize per device: robust z of the speed against that device's own 28-day distribution (higher = faster).
        val perf = usable.groupBy { it.deviceKind }.flatMap { (_, list) ->
            val speeds = list.map { it.speed!! }
            val m = Stats.median(speeds)!!
            val mad = Stats.mad(speeds)!!
            list.map { it to Stats.robustZ(it.speed!!, m, mad, minScale = 0.05) }
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
