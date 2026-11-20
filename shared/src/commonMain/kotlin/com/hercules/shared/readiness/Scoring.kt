package com.hercules.shared.readiness

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Version of the scoring engine (formulas, normalization, missing-data rules). Stored with every score.
 * 5.0.0: sleep model × recovery modifier, blended with a recent Reaction Test (docs/SPEC.md §2). Replaces the
 * weighted five-part score of engine 4.
 */
const val SCORING_ENGINE_VERSION = "5.0.0"

/** How many days of raw data, sessions and scores the device keeps; baselines and calibration use the same window. */
const val RETENTION_DAYS = 10

private const val HOUR_MS = 3_600_000.0

/** The parts of the score. ACTIVITY and CIRCADIAN were scored up to engine 4 and stay only so older rows still load. */
@Serializable
enum class Dimension(val label: String, val short: String) {
    SLEEP("Sleep and body clock", "Sleep"),
    CARDIO("Recovery", "Recovery"),
    ACTIVITY("Physical activity", "Activity"),
    CIRCADIAN("Light", "Light"),
    COGNITIVE("Reaction Test", "Reaction"),
}

/** What a part of the score is made of. Entries marked "engine 4" are never produced any more; kept for stored rows. */
@Serializable
enum class MetricKey(val label: String, val dimension: Dimension) {
    SLEEP_DURATION("Last night", Dimension.SLEEP),
    SLEEP_PRESSURE("Sleep pressure", Dimension.SLEEP),
    BODY_CLOCK("Body clock", Dimension.SLEEP),
    HRV("Heart rate variability", Dimension.CARDIO),
    RESTING_HR("Resting heart rate", Dimension.CARDIO),
    SKIN_TEMP("Skin temperature", Dimension.CARDIO),
    SPO2("Blood oxygen", Dimension.CARDIO),
    RESPIRATORY_RATE("Breathing rate", Dimension.CARDIO),
    GLUCOSE("Glucose", Dimension.CARDIO),
    BTC_SPEED("Reaction speed", Dimension.COGNITIVE),
    // engine 4
    SLEEP_CONSISTENCY("Sleep consistency", Dimension.SLEEP),
    SLEEP_CONTINUITY("Sleep continuity", Dimension.SLEEP),
    STEPS("Steps", Dimension.ACTIVITY),
    INTENSITY("Activity intensity", Dimension.ACTIVITY),
    LOW_MOVEMENT("Low-movement time", Dimension.ACTIVITY),
    LIGHT("Light exposure", Dimension.CIRCADIAN),
    BTC_ACCURACY("Timing accuracy", Dimension.COGNITIVE),
    BTC_CONSISTENCY("Timing consistency", Dimension.COGNITIVE),
    BTC_BIAS("Timing bias", Dimension.COGNITIVE),
}

/** Why a metric or part has no score. Never treated as zero. */
@Serializable
enum class Availability(val label: String) {
    AVAILABLE("Available"),
    NO_DATA("No data"),
    NOT_ENOUGH_HISTORY("Not enough history yet"),
    NOT_RELIABLE("Not reliable enough"),
    ZERO_WEIGHT("Weight set to 0%"),
}

@Serializable
data class MetricScore(
    val key: MetricKey,
    val score: Int?,
    /** The measured value in plain words, e.g. "7h 10m" or "52 ms". */
    val value: String?,
    /** How it compares, e.g. "usual 48 ms". */
    val detail: String,
    val availability: Availability,
)

@Serializable
data class DimensionScore(
    val dimension: Dimension,
    val score: Int?,
    /** Share of the final score this part carries (sleep and recovery share the body part). */
    val weight: Double,
    val metrics: List<MetricScore>,
    val availability: Availability,
) {
    val available: Boolean get() = score != null && weight > 0.0
}

@Serializable
enum class ConfigurationType { DEFAULT, CUSTOM }

// ------------------------------------------------------------------ user settings (spec §2.9)

/**
 * The score's sliders. They scale the model's own parameters, so its structure stays intact. The time constants
 * and the recovery floor are not adjustable. Any non-default setting costs 5 confidence points.
 */
@Serializable
data class ScoreSettings(
    /** k_s, 0.5–1.5: how strongly predicted alertness moves the score. */
    val sleepInfluence: Double = 1.0,
    /** Multipliers 0–2 on each recovery signal's weight. */
    val hrv: Double = 1.0,
    val restingHr: Double = 1.0,
    val temperature: Double = 1.0,
    /** Multiplier 0–2 on the body-clock amplitude. */
    val bodyClock: Double = 1.0,
    /** g, 0–0.8: the Reaction Test's share when one was played in the last 2 hours. */
    val gameWeight: Double = 0.6,
    /** Optional extras, multipliers 0–2. */
    val glucose: Double = 1.0,
    val oxygen: Double = 1.0,
    val breathing: Double = 1.0,
) {
    val isDefault: Boolean get() = differences(DEFAULT) == 0

    private fun differences(o: ScoreSettings) = listOf(
        sleepInfluence - o.sleepInfluence, hrv - o.hrv, restingHr - o.restingHr, temperature - o.temperature,
        bodyClock - o.bodyClock, gameWeight - o.gameWeight, glucose - o.glucose, oxygen - o.oxygen, breathing - o.breathing,
    ).count { abs(it) > 1e-6 }

    /** Every value inside its allowed range. */
    fun clamped() = ScoreSettings(
        sleepInfluence.coerceIn(0.5, 1.5), hrv.coerceIn(0.0, 2.0), restingHr.coerceIn(0.0, 2.0), temperature.coerceIn(0.0, 2.0),
        bodyClock.coerceIn(0.0, 2.0), gameWeight.coerceIn(0.0, 0.8), glucose.coerceIn(0.0, 2.0), oxygen.coerceIn(0.0, 2.0),
        breathing.coerceIn(0.0, 2.0),
    )

    companion object {
        val DEFAULT = ScoreSettings()
    }
}

/**
 * Per-user values fitted against the user's own Reaction Tests (spec §2.7). Kept beyond the 10-day window (only these
 * numbers and the session counter, no raw data). Until [fitted], the starting values apply.
 */
@Serializable
data class Calibration(
    val hrvWeight: Double = Recovery.BASE_WEIGHT,
    val restingHrWeight: Double = Recovery.BASE_WEIGHT,
    val temperatureWeight: Double = Recovery.BASE_WEIGHT,
    /** Local hour of the alertness peak; null = usual wake time + 10 h. */
    val peakHour: Double? = null,
    val amplitude: Double = SleepModel.DEFAULT_AMPLITUDE,
    /** Reaction Tests counted so far, ever. */
    val sessions: Int = 0,
    /** End time of the newest test counted, so a test is never counted twice. */
    val lastSessionMs: Long = 0,
    val fitted: Boolean = false,
    val updatedMs: Long = 0,
)

// ------------------------------------------------------------------ inputs

/** Where a value came from. Each signal uses one source per day, with its own baseline (spec §2.11). */
@Serializable
enum class SignalSource(val label: String) {
    WATCH("Watch"),
    PHONE("Phone"),
    ESTIMATED("Entered by you"),
    ASSUMED("Your usual sleep"),
}

/** One night, as resolved from the available providers. Durations in minutes. */
data class NightInput(
    val dayKey: String,
    val startMs: Long,
    val endMs: Long,
    val asleepMinutes: Int,
    /** Null when the provider reports neither awake time nor stages. */
    val awakeMinutes: Int?,
    /** Local minutes since midnight of the sleep midpoint. */
    val midpointMinuteOfDay: Double,
)

data class SleepEpisode(val startMs: Long, val endMs: Long)

/** Today's value of one signal and its earlier daily values from the same source (oldest first). */
data class Signal(val value: Double, val history: List<Double>, val source: SignalSource)

/** The Reaction Test result that counts: the median game score of the tests in the last 2 hours. */
data class CognitiveInput(val score: Int, val sessionLabel: String, val runs: Int = 1)

data class DayInputs(
    val dayKey: String,
    /** The moment the score is for. */
    val atMs: Long,
    /** Local clock hour at [atMs], 0–24. */
    val hourOfDay: Double,
    val lastNight: NightInput?,
    /** Where last night came from; [SignalSource.ASSUMED] when only the usual window is known; null = no sleep info. */
    val sleepSource: SignalSource?,
    /** Sleep episodes of the window, oldest first, including assumed ones for missing nights. */
    val episodes: List<SleepEpisode>,
    /** Local hour of the usual wake-up; null before any night is recorded. */
    val usualWakeHour: Double? = null,
    val hrv: Signal? = null,
    val restingHr: Signal? = null,
    val skinTemp: Signal? = null,
    val spo2: Signal? = null,
    val respiratoryRate: Signal? = null,
    /** Mean glucose of the last 24 hours (mmol/L) against earlier daily means. */
    val glucose: Signal? = null,
    /** A low under 3.9 mmol/L (70 mg/dL) in the last 24 hours (a CGM must stay low for 3 readings). */
    val glucoseLow: Boolean = false,
    val cognitive: CognitiveInput? = null,
    /** Earlier days in the window with any data. */
    val historyDays: Int = 0,
    val settings: ScoreSettings = ScoreSettings.DEFAULT,
    val calibration: Calibration = Calibration(),
)

// ------------------------------------------------------------------ sleep model (spec §2.2–2.4)

/**
 * McCauley et al. 2013 (Sleep 36(12):1987–1997), the biomathematical model of PVT performance under sleep loss,
 * with every parameter from its Table 2. State: p = predicted PVT lapses, u = slow homeostatic state (days to weeks,
 * so chronic short sleep keeps adding up), κ = circadian amplitude, which grows during wake and decays in sleep.
 *
 * Wake:  dp/dt = α_w(p + β·u) + κ(c + μ_w),   du/dt = η_w·u,       dκ/dt = λ·κ(1 − κ/ξ)
 * Sleep: dp/dt = α_s(p + β·u) + κ(c + μ_s) + α_s·β/η_s,   du/dt = η_s·u + 1,   dκ/dt = −λ·κ
 * c(t) = sin(2π(t − φ)/24), t = local clock hours; η_s = A_c/(A_c − 1)·η_w, A_c = 20.2/24.
 *
 * Initial state: the periodic steady state of 8-hour nights ending at the usual wake time (the paper starts from
 * the steady state of its baseline schedule), reached by 20 simulated days. Euler steps of 6 minutes.
 * Lapses map to predicted alertness P = 100 − 4·max(0, p − 2) [Design]: about 92 after a good night, about 40
 * after 24 h awake.
 */
object SleepModel {
    const val ALPHA_W = -0.028
    const val ALPHA_S = -0.26
    const val BETA = -0.26
    const val ETA_W = 0.0074
    const val MU_W = 0.33
    const val MU_S = -1.5
    /** φ in clock hours: highest predicted impairment around φ + 6 h (03:12), lowest around φ − 6 h (15:12). */
    const val PHI = 21.2
    const val LAMBDA = 0.49
    const val XI = 1.09
    const val A_C = 20.2 / 24.0
    val ETA_S = A_C / (A_C - 1) * ETA_W

    /** The paper's subjects woke around 07:30; a different usual wake time shifts φ by the same amount [Design]. */
    const val REFERENCE_WAKE_HOUR = 7.5
    const val DEFAULT_WAKE_HOUR = 7.5
    /** Calibrated multiplier on κ; 1 = the paper. */
    const val DEFAULT_AMPLITUDE = 1.0
    const val LAPSES_FLOOR = 2.0
    const val POINTS_PER_LAPSE = 4.0
    private const val STEP_H = 0.1
    private const val SPIN_UP_DAYS = 20
    private const val BASELINE_SLEEP_H = 8.0

    data class State(val p: Double, val u: Double, val k: Double)

    /** Clock hour of the best predicted alertness for a given φ. */
    fun peakOf(phi: Double): Double = ((phi - 6) % 24 + 24) % 24
    fun phiOf(peakHour: Double): Double = peakHour + 6

    /** The default peak: the paper's, moved with the user's usual wake time. */
    fun peakHour(usualWakeHour: Double?, calibration: Calibration): Double =
        calibration.peakHour ?: peakOf(PHI + ((usualWakeHour ?: DEFAULT_WAKE_HOUR) - REFERENCE_WAKE_HOUR))

    fun alertness(lapses: Double): Double = (100 - POINTS_PER_LAPSE * max(0.0, lapses - LAPSES_FLOOR)).coerceIn(0.0, 100.0)

    /** One Euler step of [h] hours at local clock hour [clock]. [amp] scales κ's effect (calibration × slider). */
    fun step(s: State, clock: Double, asleep: Boolean, h: Double, phi: Double, amp: Double): State {
        val c = sin(2 * PI * (clock - phi) / 24.0)
        val k = s.k * amp
        return if (asleep) State(
            s.p + h * (ALPHA_S * (s.p + BETA * s.u) + k * (c + MU_S) + ALPHA_S * BETA / ETA_S),
            s.u + h * (ETA_S * s.u + 1),
            s.k + h * (-LAMBDA * s.k),
        ) else State(
            s.p + h * (ALPHA_W * (s.p + BETA * s.u) + k * (c + MU_W)),
            s.u + h * (ETA_W * s.u),
            s.k + h * (LAMBDA * s.k * (1 - s.k / XI)),
        )
    }

    /**
     * Runs the model through [episodes] and reports the state at each of [samples] (epoch ms, ascending). Before
     * the first episode: [SPIN_UP_DAYS] of 8-hour nights ending at [wakeHour]. [offsetH] = local clock − UTC hours.
     */
    fun run(
        episodes: List<SleepEpisode>, samples: List<Long>, offsetH: Double, phi: Double, amp: Double, wakeHour: Double,
    ): List<State?> {
        val eps = episodes.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (eps.isEmpty() || samples.isEmpty()) return samples.map { null }
        val stepMs = (STEP_H * HOUR_MS).toLong()
        val first = eps.first().startMs
        val startMs = first - SPIN_UP_DAYS * 24 * HOUR_MS.toLong()
        fun clock(t: Long) = (((t / HOUR_MS + offsetH) % 24) + 24) % 24
        fun spinAsleep(t: Long): Boolean {
            val h = clock(t)
            val bed = wakeHour - BASELINE_SLEEP_H
            return ((h - bed) % 24 + 24) % 24 < BASELINE_SLEEP_H
        }
        val out = arrayOfNulls<State>(samples.size)
        var st = State(0.0, -1 / ETA_S, XI)
        var t = startMs
        var ei = 0
        var si = 0
        while (si < samples.size && samples[si] < first) si++ // no score before the first recorded sleep
        val end = samples.last()
        while (t < end) {
            while (ei < eps.size && eps[ei].endMs <= t) ei++
            val asleep = if (t < first) spinAsleep(t) else ei < eps.size && t >= eps[ei].startMs
            val h = min(stepMs, end - t)
            st = step(st, clock(t), asleep, h / HOUR_MS, phi, amp)
            t += h
            while (si < samples.size && samples[si] <= t) { if (samples[si] >= first) out[si] = st; si++ }
        }
        return out.toList()
    }

    /** Runs on from [start] at [fromMs] through [segments] (duration ms, asleep), e.g. tonight's sleep and the morning after. */
    fun project(start: State, fromMs: Long, segments: List<Pair<Long, Boolean>>, offsetH: Double, phi: Double, amp: Double): State {
        val stepMs = (STEP_H * HOUR_MS).toLong()
        var st = start
        var t = fromMs
        for ((dur, asleep) in segments) {
            val end = t + max(0L, dur)
            while (t < end) {
                val h = min(stepMs, end - t)
                st = step(st, (((t / HOUR_MS + offsetH) % 24) + 24) % 24, asleep, h / HOUR_MS, phi, amp)
                t += h
            }
        }
        return st
    }

    fun at(episodes: List<SleepEpisode>, atMs: Long, offsetH: Double, phi: Double, amp: Double, wakeHour: Double): State? =
        run(episodes, listOf(atMs), offsetH, phi, amp, wakeHour).single()

    /** Local clock hours minus UTC hours at [atMs], from the local [hourOfDay] then. */
    fun offset(atMs: Long, hourOfDay: Double): Double {
        val utc = ((atMs / HOUR_MS) % 24 + 24) % 24
        var d = hourOfDay - utc
        while (d > 14) d -= 24
        while (d < -12) d += 24
        return d
    }
}

// ------------------------------------------------------------------ recovery modifier (spec §2.5, §2.14)

/** Recovery signals scale the predicted alertness down when they deviate unfavourably from the user's own baseline. */
object Recovery {
    const val FLOOR = 0.7
    const val MIN_DAYS = 5
    const val BASE_WEIGHT = 0.05
    /** A phone-sourced HRV or resting heart rate counts half. */
    const val PHONE_FACTOR = 0.5

    /** One signal's contribution: [bad] is the unfavourable part of its z-score; [penalty] = weight × bad. */
    @Serializable
    data class Term(val key: MetricKey, val z: Double, val bad: Double, val weight: Double, val penalty: Double, val source: SignalSource)

    /** z of [s] against its own history, or null with fewer than [MIN_DAYS] earlier days. */
    fun z(s: Signal, minSd: Double, transform: (Double) -> Double = { it }): Double? {
        if (s.history.size < MIN_DAYS) return null
        val h = s.history.map(transform)
        val mean = h.average()
        val sd = sqrt(h.sumOf { (it - mean) * (it - mean) } / (h.size - 1)).coerceAtLeast(minSd)
        return ((transform(s.value) - mean) / sd).coerceIn(-4.0, 4.0)
    }

    fun terms(input: DayInputs, calibration: Calibration = input.calibration, settings: ScoreSettings = input.settings): List<Term> {
        val out = mutableListOf<Term>()
        fun add(key: MetricKey, s: Signal?, minSd: Double, higherIsWorse: Boolean, weight: Double, transform: (Double) -> Double = { it }, floorBad: Double = 0.0) {
            if (s == null) return
            val z = z(s, minSd, transform) ?: return
            val bad = maxOf(if (higherIsWorse) maxOf(0.0, z) else maxOf(0.0, -z), floorBad)
            val w = weight * if (s.source == SignalSource.PHONE) PHONE_FACTOR else 1.0
            out += Term(key, z, bad, w, w * bad, s.source)
        }
        add(MetricKey.HRV, input.hrv?.takeIf { it.value in 5.0..250.0 }, 0.05, higherIsWorse = false, calibration.hrvWeight * settings.hrv, transform = { ln(it) })
        add(MetricKey.RESTING_HR, input.restingHr?.takeIf { it.value in 30.0..120.0 }, 1.0, higherIsWorse = true, calibration.restingHrWeight * settings.restingHr)
        // Skin temperature has no phone fallback: the term is dropped.
        add(MetricKey.SKIN_TEMP, input.skinTemp?.takeIf { it.source != SignalSource.PHONE }, 0.1, higherIsWorse = true, calibration.temperatureWeight * settings.temperature)
        add(MetricKey.SPO2, input.spo2, 0.5, higherIsWorse = false, BASE_WEIGHT * settings.oxygen)
        add(MetricKey.RESPIRATORY_RATE, input.respiratoryRate, 0.3, higherIsWorse = true, BASE_WEIGHT * settings.breathing)
        add(MetricKey.GLUCOSE, input.glucose, 0.3, higherIsWorse = false, BASE_WEIGHT * settings.glucose, floorBad = if (input.glucoseLow) 2.0 else 0.0)
        return out
    }

    /** M = clamp(1 − Σ penalty, 0.7, 1). Never above 1: a better-than-usual reading doesn't raise the score. */
    fun modifier(terms: List<Term>): Double = (1 - terms.sumOf { it.penalty }).coerceIn(FLOOR, 1.0)
}

// ------------------------------------------------------------------ calibration (spec §2.7)

/** One Reaction Test: when it was played, its score and the recovery signals then (game excluded). */
data class CalibrationPoint(
    val game: Double,
    val atMs: Long,
    val hrvBad: Double = 0.0,
    val restingHrBad: Double = 0.0,
    val temperatureBad: Double = 0.0,
    /** Penalty from optional extras, which are not fitted. */
    val otherPenalty: Double = 0.0,
)

object Calibrator {
    const val MIN_SESSIONS = 20
    /** Fewer points than this inside the window and the last fit is kept. */
    const val MIN_POINTS = 8
    const val MAX_WEIGHT = 0.2
    /** Multipliers on the paper's circadian amplitude κ. */
    val AMPLITUDES = listOf(0.5, 0.75, 1.0, 1.25, 1.5)

    /**
     * Fits the body-clock phase φ and amplitude (grid search, one model run over the window per candidate) and each
     * recovery weight (least squares on the game residual) to this user's tests. The paper's rate constants stay
     * fixed. [episodes], [offsetH] and [wakeHour] describe the window the tests were played in.
     */
    fun fit(
        points: List<CalibrationPoint>, episodes: List<SleepEpisode>, offsetH: Double, wakeHour: Double,
        current: Calibration, nowMs: Long,
    ): Calibration {
        if (current.sessions < MIN_SESSIONS || points.size < MIN_POINTS) return current
        val pts = points.sortedBy { it.atMs }
        var best: Calibration? = null
        var bestErr = Double.MAX_VALUE
        for (step in 0 until 48) {
            val peak = step * 0.5
            for (a in AMPLITUDES) {
                val states = SleepModel.run(episodes, pts.map { it.atMs }, offsetH, SleepModel.phiOf(peak), a, wakeHour)
                val idx = pts.indices.filter { states[it] != null }
                if (idx.size < MIN_POINTS) return current
                val p = pts.indices.map { i -> states[i]?.let { SleepModel.alertness(it.p) } ?: 0.0 }
                val y = pts.indices.map { i -> if (p[i] > 5) 1 - pts[i].game / p[i] - pts[i].otherPenalty else 0.0 }
                fun w(x: (CalibrationPoint) -> Double, fallback: Double): Double {
                    val sxx = idx.sumOf { x(pts[it]) * x(pts[it]) }
                    if (sxx < 1e-6) return fallback
                    return (idx.sumOf { x(pts[it]) * y[it] } / sxx).coerceIn(0.0, MAX_WEIGHT)
                }
                val wh = w({ it.hrvBad }, current.hrvWeight)
                val wr = w({ it.restingHrBad }, current.restingHrWeight)
                val wt = w({ it.temperatureBad }, current.temperatureWeight)
                val err = idx.sumOf { i ->
                    val pt = pts[i]
                    val m = (1 - wh * pt.hrvBad - wr * pt.restingHrBad - wt * pt.temperatureBad - pt.otherPenalty).coerceIn(Recovery.FLOOR, 1.0)
                    val d = pt.game - p[i] * m
                    d * d
                }
                if (err < bestErr) {
                    bestErr = err
                    best = current.copy(hrvWeight = wh, restingHrWeight = wr, temperatureWeight = wt, peakHour = peak, amplitude = a, fitted = true, updatedMs = nowMs)
                }
            }
        }
        return best ?: current
    }
}

// ------------------------------------------------------------------ readiness (spec §2)

@Serializable
enum class ReadinessConfidence(val label: String) {
    HIGH("High confidence"),
    MEDIUM("Medium confidence"),
    LOW("Low confidence"),
    INSUFFICIENT("Not enough data"),
}

/** Why there is no score between midnight and waking: the day's score starts with the night behind it. */
const val REASON_BEFORE_WAKE = "Today's score starts when you wake up"

/** Days of data that earn baseline confidence points (spec §2.8). */
const val MIN_CONFIDENT_DAYS = 5
const val HIGH_CONFIDENCE_DAYS = 8

/** A Reaction Test counts in the score for this long after it ends. */
const val GAME_FRESH_MS = 2 * 3_600_000L

/** Display label for a confidence percentage: High 75–100, Medium 50–74, Low under 50. */
fun confidenceFor(pct: Int): ReadinessConfidence = when {
    pct >= 75 -> ReadinessConfidence.HIGH
    pct >= 50 -> ReadinessConfidence.MEDIUM
    else -> ReadinessConfidence.LOW
}

/** The settings and engine a score was calculated with, kept with the score. */
@Serializable
data class ConfigStamp(
    val configId: String,
    val configVersion: Int,
    val configType: ConfigurationType,
    /** Engine 4 weights; empty from engine 5. */
    val weights: Map<Dimension, Double> = emptyMap(),
    val engineVersion: String,
    val settings: ScoreSettings = ScoreSettings.DEFAULT,
) {
    companion object {
        const val SETTINGS_VERSION = 5
        fun of(settings: ScoreSettings) = ConfigStamp(
            if (settings.isDefault) "default-v$SETTINGS_VERSION" else "custom-v$SETTINGS_VERSION", SETTINGS_VERSION,
            if (settings.isDefault) ConfigurationType.DEFAULT else ConfigurationType.CUSTOM, emptyMap(), SCORING_ENGINE_VERSION, settings,
        )
    }
}

/** One line of the confidence breakdown: what earned points, or what is missing and could. */
@Serializable
data class ConfidencePart(val label: String, val points: Int, val max: Int)

@Serializable
data class ReadinessCalculation(
    val dayKey: String,
    /** Null means INSUFFICIENT_DATA. */
    val score: Int?,
    /** Confidence as a fraction (0–1); [confidencePct] / 100. */
    val coverage: Double,
    val confidence: ReadinessConfidence,
    val dimensions: List<DimensionScore>,
    val stamp: ConfigStamp,
    /** Why no number was published, in plain words. */
    val insufficientReason: String?,
    /** Earlier days in the window with data behind this score. */
    val historyDays: Int = 0,
    val signalsIn: Int = 0,
    val signalsTotal: Int = 0,
    /** What would raise confidence, in plain words; null at High. */
    val confidenceHint: String? = null,
    /** Recovery deductions in score points (already inside [score], via the recovery modifier). */
    val modifiers: List<ScoreModifier> = emptyList(),
    val confidencePct: Int = (coverage * 100).roundToInt(),
    val confidenceParts: List<ConfidencePart> = emptyList(),
    /** P after the sleep-influence slider, 0–100. */
    val alertness: Int? = null,
    /** M, 0.7–1. */
    val recovery: Double? = null,
    /** G when a test from the last 2 hours is in the score. */
    val gameScore: Int? = null,
    /** Share of the score the game carries now (0 without a recent test, 1 when the game is the only input). */
    val gameShare: Double = 0.0,
    val sleepSource: SignalSource? = null,
    /** Model state at [atMs], for "if you sleep N hours" projections. */
    /** Predicted PVT lapses p (McCauley 2013) at [atMs]. */
    val pressure: Double? = null,
    /** The model's slow state u and circadian amplitude κ at [atMs], for projections. */
    val homeostat: Double? = null,
    val bodyClock: Double? = null,
    val peakHour: Double? = null,
    val amplitude: Double? = null,
    /** Local clock minus UTC, hours, at [atMs]. */
    val offsetH: Double? = null,
    val atMs: Long = 0,
    val recoveryTerms: List<Recovery.Term> = emptyList(),
) {
    /** The score without the game: P × M. */
    val bodyScore: Double? get() = if (alertness != null) alertness * (recovery ?: 1.0) else null
}

/** A deduction shown in the breakdown. Never positive. */
@Serializable
data class ScoreModifier(val label: String, val points: Int)

/** "sleep: watch · 6 days of data", shown next to the confidence level. */
val ReadinessCalculation.confidenceBasis: String
    get() = listOfNotNull(
        sleepSource?.let { "sleep: ${it.label.lowercase()}" },
        if (historyDays == 0) "first day of data" else "$historyDays ${if (historyDays == 1) "day" else "days"} of data",
    ).joinToString(" · ")

/** What each part means in everyday words. */
val Dimension.plain: String
    get() = when (this) {
        Dimension.SLEEP -> "sleep"
        Dimension.CARDIO -> "heart rate and HRV"
        Dimension.ACTIVITY -> "activity"
        Dimension.CIRCADIAN -> "daylight"
        Dimension.COGNITIVE -> "Reaction Test"
    }

/**
 * Why there is no score, in one or two plain sentences. Null when there is a score.
 */
val ReadinessCalculation.insufficientDetail: String?
    get() {
        if (score != null) return null
        if (insufficientReason == REASON_BEFORE_WAKE) {
            return "It's still night. Today's score appears after you wake up, from last night's sleep and recovery."
        }
        return "There's no sleep on record yet and no recent Reaction Test. Wear the watch to bed, connect Health " +
            "Connect, or take a Reaction Test for a score now."
    }

private fun hm(minutes: Int) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

private fun clock(hour: Double): String {
    val m = ((hour * 60).roundToInt() % (24 * 60) + 24 * 60) % (24 * 60)
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

/**
 * Score = g·G + (1 − g)·(P_adj × M) with a Reaction Test from the last 2 hours, otherwise P_adj × M (spec §2.6).
 * Missing sleep falls back in order: entered or phone sleep (as usual), a recent test alone, your usual sleep window,
 * nothing (spec §2.10).
 */
class ReadinessCalculator {

    fun calculate(input: DayInputs): ReadinessCalculation {
        val st = input.settings.clamped()
        val cal = input.calibration
        val stamp = ConfigStamp.of(st)
        val game = input.cognitive
        val hasSleep = input.sleepSource != null && input.episodes.isNotEmpty()
        val gameOnly = game != null && (!hasSleep || input.sleepSource == SignalSource.ASSUMED)

        var alertness: Double? = null
        var state: SleepModel.State? = null
        val peak = SleepModel.peakHour(input.usualWakeHour, cal)
        val amp = cal.amplitude * st.bodyClock
        val offset = SleepModel.offset(input.atMs, input.hourOfDay)
        val wakeHour = input.usualWakeHour ?: SleepModel.DEFAULT_WAKE_HOUR
        if (hasSleep && !gameOnly) {
            state = SleepModel.at(input.episodes, input.atMs, offset, SleepModel.phiOf(peak), amp, wakeHour)
            if (state != null) {
                val p = SleepModel.alertness(state.p)
                alertness = 100 - st.sleepInfluence * (100 - p)
            }
        }
        val terms = if (alertness != null) Recovery.terms(input, cal, st) else emptyList()
        val m = Recovery.modifier(terms)
        val g = if (game != null && alertness != null) st.gameWeight else if (game != null) 1.0 else 0.0
        val raw = when {
            alertness != null && game != null -> g * game.score + (1 - g) * alertness * m
            alertness != null -> alertness * m
            game != null -> game.score.toDouble()
            else -> null
        }
        val score = raw?.roundToInt()?.coerceIn(1, 100)

        val parts = confidenceParts(input, st, cal, gameOnly, terms)
        var pct = parts.sumOf { it.points }.coerceIn(0, 100)
        if (score != null) {
            // Without measured sleep a score can't reach High; on a test or usual sleep alone it stays Low.
            if (input.sleepSource != SignalSource.WATCH) pct = minOf(pct, 74)
            if (gameOnly || input.sleepSource == SignalSource.ASSUMED) pct = minOf(pct, 49)
        } else pct = 0
        val confidence = if (score == null) ReadinessConfidence.INSUFFICIENT else confidenceFor(pct)

        val bodyShare = if (alertness == null) 0.0 else 1 - g
        val dims = dimensions(input, alertness, state, peak, terms, m, bodyShare, game, g)
        val modifiers = if (alertness == null) emptyList() else terms.filter { it.penalty > 0 }.map { t ->
            ScoreModifier("${t.key.label} off your usual", -(alertness * bodyShare * t.penalty).roundToInt())
        }.filter { it.points < 0 }
        val hint = if (score == null || confidence == ReadinessConfidence.HIGH) null else hint(parts, input.historyDays)
        return ReadinessCalculation(
            dayKey = input.dayKey,
            score = score,
            coverage = pct / 100.0,
            confidence = confidence,
            dimensions = dims,
            stamp = stamp,
            insufficientReason = if (score == null) "Waiting on sleep or a Reaction Test" else null,
            historyDays = input.historyDays,
            signalsIn = dims.count { it.available },
            signalsTotal = dims.size,
            confidenceHint = hint,
            modifiers = modifiers,
            confidencePct = pct,
            confidenceParts = parts,
            alertness = alertness?.roundToInt(),
            recovery = if (alertness != null) m else null,
            gameScore = game?.score,
            gameShare = g,
            sleepSource = if (gameOnly) null else input.sleepSource,
            pressure = state?.p,
            homeostat = state?.u,
            bodyClock = state?.k,
            peakHour = peak,
            amplitude = amp,
            offsetH = offset,
            atMs = input.atMs,
            recoveryTerms = terms,
        )
    }

    /** Confidence points (spec §2.8): core data up to 93, optional extras up to 7, minus 5 for custom settings. */
    fun confidenceParts(input: DayInputs, st: ScoreSettings, cal: Calibration, gameOnly: Boolean, terms: List<Recovery.Term>): List<ConfidencePart> {
        val out = mutableListOf<ConfidencePart>()
        val sleep = when (if (gameOnly) null else input.sleepSource) {
            SignalSource.WATCH -> 35
            SignalSource.PHONE, SignalSource.ESTIMATED -> 15
            SignalSource.ASSUMED -> 5
            null -> 0
        }
        out += ConfidencePart("Sleep" + (input.sleepSource?.takeIf { !gameOnly }?.let { " (${it.label.lowercase()})" } ?: ""), sleep, 35)
        val days = input.historyDays
        out += ConfidencePart("Days of data ($days)", if (days >= HIGH_CONFIDENCE_DAYS) 15 else if (days >= MIN_CONFIDENT_DAYS) 8 else 0, 15)
        fun tiered(label: String, s: Signal?, watchOnly: Boolean = false) {
            val pts = when (s?.source) {
                null -> 0
                SignalSource.PHONE -> if (watchOnly) 0 else 4
                else -> 8
            }
            out += ConfidencePart(label, pts, 8)
        }
        tiered("HRV", input.hrv)
        tiered("Resting heart rate", input.restingHr)
        tiered("Skin temperature", input.skinTemp, watchOnly = true)
        val gamePts = when {
            input.cognitive != null -> 19
            cal.fitted -> 12
            else -> 0
        }
        out += ConfidencePart(if (input.cognitive == null && cal.fitted) "Reaction Test (tuned to you)" else "Reaction Test in the last 2 hours", gamePts, 19)
        val optional = minOf(
            7,
            (if (terms.any { it.key == MetricKey.GLUCOSE }) 3 else 0) +
                terms.count { it.key == MetricKey.SPO2 || it.key == MetricKey.RESPIRATORY_RATE },
        )
        if (optional > 0) out += ConfidencePart("Extra sensors", optional, 7)
        if (!st.isDefault) out += ConfidencePart("Custom score settings", -5, 0)
        return out
    }

    private fun hint(parts: List<ConfidencePart>, historyDays: Int): String? {
        val asks = parts.filter { it.max > 0 && it.points < it.max }.sortedByDescending { it.max - it.points }.mapNotNull { p ->
            when {
                p.label.startsWith("Sleep") -> "wear the watch to bed"
                p.label.startsWith("Days of data") -> (HIGH_CONFIDENCE_DAYS - historyDays).takeIf { it > 0 }?.let { "$it more ${if (it == 1) "day" else "days"} of data" }
                p.label.startsWith("Reaction Test") -> "take a Reaction Test"
                p.label == "HRV" || p.label == "Resting heart rate" -> "overnight heart data"
                else -> null
            }
        }.distinct().take(3)
        return asks.takeIf { it.isNotEmpty() }?.let { "To raise it: ${it.joinToString("; ")}." }
    }

    private fun dimensions(
        input: DayInputs, alertness: Double?, state: SleepModel.State?, peak: Double, terms: List<Recovery.Term>, m: Double,
        bodyShare: Double, game: CognitiveInput?, g: Double,
    ): List<DimensionScore> {
        val night = input.lastNight
        val sleepMetrics = listOf(
            if (night != null) MetricScore(MetricKey.SLEEP_DURATION, null, hm(night.asleepMinutes), input.sleepSource?.label ?: "", Availability.AVAILABLE)
            else if (input.sleepSource == SignalSource.ASSUMED) MetricScore(MetricKey.SLEEP_DURATION, null, null, "Not recorded; using your usual sleep window", Availability.NO_DATA)
            else MetricScore(MetricKey.SLEEP_DURATION, null, null, "No sleep recorded for last night.", Availability.NO_DATA),
            if (state != null) MetricScore(
                MetricKey.SLEEP_PRESSURE, alertness?.roundToInt(), "${(max(0.0, state.p) * 10).roundToInt() / 10.0} lapses",
                "Predicted slow reactions on a 10-minute vigilance test; builds while you're awake, clears while you sleep", Availability.AVAILABLE,
            )
            else MetricScore(MetricKey.SLEEP_PRESSURE, null, null, "Needs a night of sleep.", Availability.NO_DATA),
            MetricScore(MetricKey.BODY_CLOCK, null, clock(peak), "Your best time of day, predicted", if (state != null) Availability.AVAILABLE else Availability.NO_DATA),
        )
        val recoveryMetrics = listOf(MetricKey.HRV, MetricKey.RESTING_HR, MetricKey.SKIN_TEMP, MetricKey.SPO2, MetricKey.RESPIRATORY_RATE, MetricKey.GLUCOSE).mapNotNull { k ->
            val sig = when (k) {
                MetricKey.HRV -> input.hrv
                MetricKey.RESTING_HR -> input.restingHr
                MetricKey.SKIN_TEMP -> input.skinTemp
                MetricKey.SPO2 -> input.spo2
                MetricKey.RESPIRATORY_RATE -> input.respiratoryRate
                else -> input.glucose
            }
            val t = terms.firstOrNull { it.key == k }
            val optional = k == MetricKey.SPO2 || k == MetricKey.RESPIRATORY_RATE || k == MetricKey.GLUCOSE
            when {
                sig == null && optional -> null
                sig == null -> MetricScore(k, null, null, "No reading.", Availability.NO_DATA)
                t == null -> MetricScore(k, null, valueText(k, sig.value), "${sig.source.label} · needs ${Recovery.MIN_DAYS} days to compare", Availability.NOT_ENOUGH_HISTORY)
                else -> MetricScore(
                    k, (100 - t.bad * 25).roundToInt().coerceIn(0, 100), valueText(k, sig.value),
                    "${sig.source.label} · " + (if (t.bad > 0) "off your usual" else "within your usual"), Availability.AVAILABLE,
                )
            }
        }
        val gameMetrics = listOf(
            if (game != null) MetricScore(MetricKey.BTC_SPEED, game.score, "${game.score}", game.sessionLabel, Availability.AVAILABLE)
            else MetricScore(MetricKey.BTC_SPEED, null, null, "No Reaction Test in the last 2 hours.", Availability.NO_DATA),
        )
        val recScore = if (terms.isNotEmpty()) ((m - Recovery.FLOOR) / (1 - Recovery.FLOOR) * 100).roundToInt() else null
        return listOf(
            DimensionScore(Dimension.SLEEP, alertness?.roundToInt(), bodyShare, sleepMetrics, if (alertness != null) Availability.AVAILABLE else Availability.NO_DATA),
            DimensionScore(
                Dimension.CARDIO, recScore, if (recScore != null) bodyShare else 0.0, recoveryMetrics,
                when {
                    recScore != null -> Availability.AVAILABLE
                    recoveryMetrics.any { it.availability == Availability.NOT_ENOUGH_HISTORY } -> Availability.NOT_ENOUGH_HISTORY
                    else -> Availability.NO_DATA
                },
            ),
            DimensionScore(Dimension.COGNITIVE, game?.score, g, gameMetrics, if (game != null) Availability.AVAILABLE else Availability.NO_DATA),
        )
    }

    private fun valueText(k: MetricKey, v: Double): String = when (k) {
        MetricKey.HRV -> "${v.roundToInt()} ms"
        MetricKey.RESTING_HR -> "${v.roundToInt()} bpm"
        MetricKey.SKIN_TEMP -> "${if (v >= 0) "+" else "−"}${((abs(v) * 10).roundToInt() / 10.0)} °C"
        MetricKey.SPO2 -> "${v.roundToInt()}%"
        MetricKey.RESPIRATORY_RATE -> "${(v * 10).roundToInt() / 10.0}/min"
        MetricKey.GLUCOSE -> "${(v * 10).roundToInt() / 10.0} mmol/L"
        else -> v.roundToInt().toString()
    }
}

/** Mean and sample SD of [values]; null when empty. */
fun meanSd(values: List<Double>): Pair<Double, Double>? {
    if (values.isEmpty()) return null
    val m = values.average()
    val sd = if (values.size < 2) 0.0 else sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    return m to sd
}

/** A baseline as stored for display: the mean and spread of the last [RETENTION_DAYS] days. */
@Serializable
data class BaselineValue(
    val median: Double,
    val mad: Double,
    val sampleCount: Int,
    val personalShare: Double,
)

/** System-controlled values that are not part of the formula itself. */
data class ScoringParams(
    /** Adults need 7 h or more (AASM/SRS consensus). Used for bedtime advice. */
    val sleepTargetHours: Double = 7.0,
) {
    companion object {
        val V1 = ScoringParams()
    }
}
