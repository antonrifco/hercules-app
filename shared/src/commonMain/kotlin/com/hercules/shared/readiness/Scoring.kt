package com.hercules.shared.readiness

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Version of the scoring engine (formulas, normalization, missing-data rules). Stored with every score. */
private const val DAY_MS = 86_400_000L

const val SCORING_ENGINE_VERSION = "3.0.0"

/** The five readiness dimensions of the CRS (spec §2.3). */
@Serializable
enum class Dimension(val label: String, val short: String) {
    SLEEP("Sleep & Recovery", "Sleep"),
    CARDIO("Cardiovascular Readiness", "Heart"),
    ACTIVITY("Physical Activity", "Activity"),
    CIRCADIAN("Circadian Alignment", "Light"),
    COGNITIVE("Cognitive Performance", "Mind"),
}

/** Contributing metrics with their fixed, system-controlled share inside their dimension (spec §2.4–2.8, §3.6). */
@Serializable
enum class MetricKey(val label: String, val dimension: Dimension, val internalWeight: Double) {
    // v2 (evidence review, docs/SPEC.md §2): cumulative sleep load leads; HRV outweighs resting HR.
    SLEEP_DURATION("Sleep duration", Dimension.SLEEP, 0.55),
    SLEEP_CONSISTENCY("Sleep consistency", Dimension.SLEEP, 0.25),
    SLEEP_CONTINUITY("Sleep continuity", Dimension.SLEEP, 0.20),
    HRV("Heart rate variability", Dimension.CARDIO, 0.70),
    RESTING_HR("Resting heart rate", Dimension.CARDIO, 0.30),
    STEPS("Steps", Dimension.ACTIVITY, 0.20),
    INTENSITY("Activity intensity", Dimension.ACTIVITY, 0.50),
    LOW_MOVEMENT("Low-movement time", Dimension.ACTIVITY, 0.30),
    LIGHT("Light exposure", Dimension.CIRCADIAN, 1.00),
    BTC_ACCURACY("Timing accuracy", Dimension.COGNITIVE, 0.70),
    BTC_CONSISTENCY("Timing consistency", Dimension.COGNITIVE, 0.20),
    BTC_BIAS("Timing bias", Dimension.COGNITIVE, 0.10),
}

/** Why a metric or dimension has no score. Never treated as zero. */
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
    val weight: Double,
    val metrics: List<MetricScore>,
    val availability: Availability,
) {
    val available: Boolean get() = score != null && weight > 0.0
}

// ------------------------------------------------------------------ configuration (spec §3)

@Serializable
enum class ConfigurationType { DEFAULT, CUSTOM }

@Serializable
data class ScoringConfiguration(
    val id: String,
    /** Version of the dimension set this configuration was written for. A new dimension means a new version. */
    val version: Int,
    val type: ConfigurationType,
    val sleepWeight: Double,
    val cardiovascularWeight: Double,
    val activityWeight: Double,
    val circadianWeight: Double,
    val cognitiveWeight: Double,
    val updatedAtMs: Long,
) {
    fun weight(d: Dimension): Double = when (d) {
        Dimension.SLEEP -> sleepWeight
        Dimension.CARDIO -> cardiovascularWeight
        Dimension.ACTIVITY -> activityWeight
        Dimension.CIRCADIAN -> circadianWeight
        Dimension.COGNITIVE -> cognitiveWeight
    }

    val weights: Map<Dimension, Double> get() = Dimension.entries.associateWith { weight(it) }

    /** True when any weight differs from the recommended defaults. */
    val differsFromDefault: Boolean
        get() = Dimension.entries.any { abs(weight(it) - DefaultScoring.DEFAULT.weight(it)) > WEIGHT_TOLERANCE }

    companion object {
        const val WEIGHT_TOLERANCE = 1e-6

        fun custom(id: String, weights: Map<Dimension, Double>, nowMs: Long) = ScoringConfiguration(
            id = id,
            version = DefaultScoring.CONFIG_VERSION,
            type = ConfigurationType.CUSTOM,
            sleepWeight = weights[Dimension.SLEEP] ?: 0.0,
            cardiovascularWeight = weights[Dimension.CARDIO] ?: 0.0,
            activityWeight = weights[Dimension.ACTIVITY] ?: 0.0,
            circadianWeight = weights[Dimension.CIRCADIAN] ?: 0.0,
            cognitiveWeight = weights[Dimension.COGNITIVE] ?: 0.0,
            updatedAtMs = nowMs,
        )
    }
}

object DefaultScoring {
    /**
     * v2 (evidence review): sleep is the only signal with causal evidence for next-day cognition (Van Dongen 2003,
     * Lim & Dinges 2010), so it leads; heart signals are associations (Forte 2019, Buchheit 2014).
     */
    const val CONFIG_VERSION = 2

    val DEFAULT = ScoringConfiguration(
        id = "default-v2",
        version = CONFIG_VERSION,
        type = ConfigurationType.DEFAULT,
        sleepWeight = 0.40,
        cardiovascularWeight = 0.20,
        activityWeight = 0.10,
        circadianWeight = 0.10,
        cognitiveWeight = 0.20,
        updatedAtMs = 0L,
    )
}

sealed interface WeightValidation {
    data object Valid : WeightValidation
    data class Invalid(val reason: String) : WeightValidation
}

/** Rejects invalid weights; never normalizes them silently (spec §3.3). Weights are fractions (0.35 = 35%). */
fun validateWeights(weights: Map<Dimension, Double>): WeightValidation {
    if (Dimension.entries.any { it !in weights }) return WeightValidation.Invalid("Every dimension needs a weight.")
    val values = Dimension.entries.map { weights.getValue(it) }
    if (values.any { it.isNaN() || it.isInfinite() }) return WeightValidation.Invalid("Weights must be numbers.")
    if (values.any { it < 0.0 }) return WeightValidation.Invalid("Weights can't be negative.")
    if (values.all { it == 0.0 }) return WeightValidation.Invalid("At least one dimension needs a weight.")
    val total = values.sum()
    if (total < 1.0 - ScoringConfiguration.WEIGHT_TOLERANCE) return WeightValidation.Invalid("Weights add up to ${pct(total)}. They must add up to 100%.")
    if (total > 1.0 + ScoringConfiguration.WEIGHT_TOLERANCE) return WeightValidation.Invalid("Weights add up to ${pct(total)}. They must add up to 100%.")
    return WeightValidation.Valid
}

private fun pct(v: Double) = "${(v * 100).roundToInt()}%"

// ------------------------------------------------------------------ system-controlled parameters (spec §3.6)

/** Normalization and baseline parameters, versioned with [SCORING_ENGINE_VERSION]. Not user-editable. */
data class ScoringParams(
    /** Adults need 7 h or more (AASM/SRS consensus, Watson 2015); cognition peaks around 7 h (Li 2022). */
    val sleepTargetHours: Double = 7.0,
    /** Last night, the night before, the one before that: sleep loss accumulates (Van Dongen 2003, Belenky 2003). */
    val sleepLoadWeights: List<Double> = listOf(0.60, 0.25, 0.15),
    /** HRV is read as the mean of ln(RMSSD) over this many recent nights (Plews 2013/2014). */
    val hrvRecentNights: Int = 3,
    val consistencyNights: Int = 14,
    val consistencyMinNights: Int = 5,
    val baselineDays: Int = 28,
    val personalMaxShare: Double = 0.8,
    /** Reference calibration used before enough personal history exists (spec §7). */
    val hrvReference: Pair<Double, Double> = 42.0 to 12.0,
    val restingHrReference: Pair<Double, Double> = 62.0 to 5.0,
    val stepsReference: Double = 8000.0,
    val hrvMinScale: Double = 4.0,
    val restingHrMinScale: Double = 1.5,
    val lowMovementStepsPerHour: Int = 250,
    val lowMovementMinObservedHours: Int = 10,
    val lightMinSamplesPerPeriod: Int = 2,
    /** Background light readings below this are discarded: the phone may be in a pocket or bag. */
    val lightBackgroundMinLux: Double = 1.0,
    /** Earlier days an optional extra needs before "above your usual" can be judged. */
    val strainMinHistory: Int = 7,
) {
    companion object {
        val V1 = ScoringParams()
    }
}

// ------------------------------------------------------------------ baselines (spec §7)

/** A baseline as used for scoring: the blend of personal history and reference calibration. */
@Serializable
data class BaselineValue(
    val median: Double,
    val mad: Double,
    /** Valid observations behind the personal part. */
    val sampleCount: Int,
    /** 0.0 = reference only, up to 0.8 = mostly personal. */
    val personalShare: Double,
)

object Baselines {
    /** Days 1–6 reference only, 7–20 a growing personal share, 21+ the personal maximum (80%). */
    fun personalShare(validDays: Int, maxShare: Double = ScoringParams.V1.personalMaxShare): Double = when {
        validDays < 7 -> 0.0
        validDays >= 21 -> maxShare
        else -> maxShare * (validDays - 6) / 15.0
    }

    /**
     * Blends personal history with the reference. [history] holds earlier valid observations only, so today's value
     * is always scored against yesterday's baseline (spec §7).
     */
    fun blend(history: List<Double>, reference: Pair<Double, Double>, maxShare: Double = ScoringParams.V1.personalMaxShare): BaselineValue {
        val share = personalShare(history.size, maxShare)
        val pm = Stats.median(history)
        val pmad = Stats.mad(history)
        if (pm == null || pmad == null || share == 0.0) return BaselineValue(reference.first, reference.second, history.size, 0.0)
        return BaselineValue(
            median = share * pm + (1 - share) * reference.first,
            mad = share * pmad + (1 - share) * reference.second,
            sampleCount = history.size,
            personalShare = share,
        )
    }
}

// ------------------------------------------------------------------ inputs

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

/** Previous complete day of activity. Null fields are missing, not zero. */
data class ActivityInput(
    val steps: Long?,
    /** Minutes inside exercise sessions, overlaps merged. Null when the day was not tracked at all. */
    val exerciseMinutes: Int?,
    /** Waking hours with fewer than [ScoringParams.lowMovementStepsPerHour] steps, among [observedHours]. */
    val lowMovementHours: Int?,
    val observedHours: Int,
)

/** Light on the previous complete day, split by period. Each list holds lux readings that passed quality checks. */
data class LightInput(val morning: List<Double>, val daytime: List<Double>, val evening: List<Double>)

/** Today's cognitive result (latest valid, scored Beat the Clock session). */
data class CognitiveInput(val accuracy: Int, val consistency: Int, val bias: Int, val sessionLabel: String)

data class DayInputs(
    val dayKey: String,
    val lastNight: NightInput?,
    /** Earlier nights, most recent last, at most [ScoringParams.consistencyNights] − 1 of them. */
    val previousNights: List<NightInput>,
    val hrv: Double?,
    val hrvHistory: List<Double>,
    val restingHr: Double?,
    val restingHrHistory: List<Double>,
    val stepsHistory: List<Double>,
    val activity: ActivityInput?,
    val light: LightInput?,
    val cognitive: CognitiveInput?,
    /** Up to the last [ScoringParams.hrvRecentNights] − 1 HRV values before today, within the last week, oldest first. */
    val hrvRecent: List<Double> = emptyList(),
    /** Optional extras that can only lower the score (illness or low-oxygen signs). Null when none are recorded. */
    val strain: StrainInput? = null,
)

/** Optional extras, each today's value and earlier days (oldest first). Missing is null or empty, never zero. */
data class StrainInput(
    val respiratoryRate: Double? = null,
    val respiratoryRateHistory: List<Double> = emptyList(),
    val skinTempDelta: Double? = null,
    val skinTempHistory: List<Double> = emptyList(),
    val spo2: Double? = null,
    val spo2History: List<Double> = emptyList(),
    /** Valid glucose readings (mmol/L) from the 24 hours before scoring, CGM or meter. */
    val glucose24h: List<Double> = emptyList(),
)

/** A deduction from the score because an optional extra shows strain. Never positive. */
@Serializable
data class ScoreModifier(val label: String, val points: Int)

// ------------------------------------------------------------------ dimension scorers

/** Pure scoring of the five dimensions. Every function returns null scores for missing data, never zero. */
class DimensionScorer(private val p: ScoringParams = ScoringParams.V1) {

    /**
     * Full marks for 7–9 h; steeper below (6 h → 60, 5 h → 35, 4 h → 15: Van Dongen 2003, Lim & Dinges 2010),
     * gentle above (10 h → 85: Li 2022 found both short and long sleep worse).
     */
    fun sleepDurationScore(hours: Double): Int =
        Stats.curve(hours, listOf(0.0 to 0.0, 3.0 to 5.0, 4.0 to 15.0, 5.0 to 35.0, 6.0 to 60.0, 7.0 to 100.0, 9.0 to 100.0, 10.0 to 85.0, 11.0 to 65.0, 14.0 to 40.0)).roundToInt()

    /** Weighted hours of the last three nights; a gap of more than a couple of days breaks the chain. */
    fun sleepLoadHours(last: NightInput, previous: List<NightInput>): Double {
        val chain = buildList {
            add(last)
            for (n in previous.reversed()) {
                if (size >= p.sleepLoadWeights.size || last.endMs - n.endMs > (size + 0.5) * DAY_MS) break
                add(n)
            }
        }
        val w = p.sleepLoadWeights.take(chain.size)
        return chain.zip(w).sumOf { (n, k) -> n.asleepMinutes / 60.0 * k } / w.sum()
    }

    /** Circular SD of the sleep midpoint, in minutes. */
    fun sleepConsistencyScore(sdMinutes: Double): Int =
        Stats.curve(sdMinutes, listOf(0.0 to 100.0, 20.0 to 100.0, 30.0 to 90.0, 45.0 to 75.0, 60.0 to 60.0, 90.0 to 35.0, 120.0 to 20.0, 180.0 to 10.0)).roundToInt()

    /** Sleep efficiency 0..1. */
    fun sleepContinuityScore(efficiency: Double): Int =
        Stats.curve(efficiency, listOf(0.5 to 10.0, 0.65 to 25.0, 0.75 to 50.0, 0.80 to 65.0, 0.85 to 80.0, 0.88 to 90.0, 0.92 to 100.0)).roundToInt()

    /** Higher than baseline is better (HRV). z is clamped to ±4 so a single outlier can't dominate. */
    fun higherIsBetter(z: Double): Int = (75 + 12.5 * Stats.clamp(z, -4.0, 4.0)).roundToInt().coerceIn(0, 100)

    fun lowerIsBetter(z: Double): Int = higherIsBetter(-z)

    /** Steps relative to the (blended) usual: a target range, not "more is better". */
    fun stepsScore(steps: Long, usual: Double): Int {
        val ratio = steps / usual.coerceAtLeast(1000.0)
        return Stats.curve(ratio, listOf(0.0 to 30.0, 0.3 to 50.0, 0.6 to 80.0, 0.8 to 100.0, 1.5 to 100.0, 2.0 to 90.0, 3.0 to 75.0, 5.0 to 60.0)).roundToInt()
    }

    fun intensityScore(minutes: Int): Int =
        Stats.curve(minutes.toDouble(), listOf(0.0 to 60.0, 15.0 to 80.0, 30.0 to 100.0, 90.0 to 100.0, 150.0 to 85.0, 240.0 to 65.0, 360.0 to 50.0)).roundToInt()

    fun lowMovementScore(hours: Int): Int =
        Stats.curve(hours.toDouble(), listOf(0.0 to 100.0, 6.0 to 100.0, 8.0 to 80.0, 10.0 to 60.0, 12.0 to 40.0, 16.0 to 25.0)).roundToInt()

    /** Brown 2022: at least 250 lux by day. */
    fun morningLightScore(lux: Double): Int =
        Stats.curve(lux, listOf(0.0 to 10.0, 10.0 to 20.0, 50.0 to 40.0, 100.0 to 60.0, 250.0 to 100.0)).roundToInt()

    fun daytimeLightScore(lux: Double): Int =
        Stats.curve(lux, listOf(0.0 to 20.0, 50.0 to 45.0, 100.0 to 65.0, 250.0 to 100.0)).roundToInt()

    /** Brown 2022: at most 10 lux in the 3 hours before bed. */
    fun eveningLightScore(lux: Double): Int =
        Stats.curve(lux, listOf(0.0 to 100.0, 10.0 to 100.0, 30.0 to 75.0, 100.0 to 40.0, 300.0 to 15.0, 1000.0 to 5.0)).roundToInt()

    /**
     * Optional extras only ever subtract (spec §11): breathing rate or skin temperature well above the person's
     * usual (illness signs: Natarajan 2020, Smarr 2020), or low overnight oxygen (Bucks 2013). At most −10.
     */
    fun modifiers(s: StrainInput?): List<ScoreModifier> {
        if (s == null) return emptyList()
        fun high(v: Double?, history: List<Double>, minScale: Double): Boolean {
            if (v == null || history.size < p.strainMinHistory) return false
            return Stats.robustZ(v, Stats.median(history)!!, Stats.mad(history)!!, minScale) >= 2.0
        }
        val out = mutableListOf<ScoreModifier>()
        // Hypoglycaemia impairs cognition, and overnight lows also disturb sleep and next-day thinking (IHSG/ADA 2017).
        // A CGM (many readings a day) must stay low for ~15 minutes (3 readings), the consensus definition of a
        // hypoglycaemia event (Battelino 2019); a single CGM dip is often a pressure artefact. A meter reading is a
        // deliberate finger-prick, so one low counts.
        val cgm = s.glucose24h.size >= 24
        fun low(limit: Double) = s.glucose24h.count { it < limit } >= (if (cgm) 3 else 1)
        when {
            low(3.0) -> out += ScoreModifier("Glucose under 3.0 in the last 24 hours", -10)
            low(3.9) -> out += ScoreModifier("Glucose under 3.9 in the last 24 hours", -5)
        }
        val resp = high(s.respiratoryRate, s.respiratoryRateHistory, 0.5)
        val temp = high(s.skinTempDelta, s.skinTempHistory, 0.2)
        if (resp) out += ScoreModifier("Breathing rate above your usual", -5)
        if (temp) out += ScoreModifier("Skin temperature above your usual", -5)
        val usualSpo2 = Stats.median(s.spo2History)
        if (s.spo2 != null && (s.spo2 < 90.0 || (usualSpo2 != null && s.spo2History.size >= p.strainMinHistory && s.spo2 <= usualSpo2 - 4))) {
            out += ScoreModifier("Low overnight oxygen", -5)
        }
        var left = -10
        return out.map { m -> val pts = maxOf(m.points, left); left -= pts; m.copy(points = pts) }.filter { it.points < 0 }
    }

    fun sleep(input: DayInputs): Pair<Int?, List<MetricScore>> {
        val night = input.lastNight
        val metrics = mutableListOf<MetricScore>()
        if (night == null) {
            metrics += MetricScore(MetricKey.SLEEP_DURATION, null, null, "No sleep recorded for last night.", Availability.NO_DATA)
            metrics += MetricScore(MetricKey.SLEEP_CONSISTENCY, null, null, "Needs last night's sleep.", Availability.NO_DATA)
            metrics += MetricScore(MetricKey.SLEEP_CONTINUITY, null, null, "Needs last night's sleep.", Availability.NO_DATA)
            return null to metrics
        }
        val load = sleepLoadHours(night, input.previousNights)
        metrics += MetricScore(
            MetricKey.SLEEP_DURATION, sleepDurationScore(load), hm(night.asleepMinutes),
            "3-night load ${hm((load * 60).roundToInt())} · target ${hm((p.sleepTargetHours * 60).roundToInt())}", Availability.AVAILABLE,
        )

        val nights = (input.previousNights + night).takeLast(p.consistencyNights)
        val sd = if (nights.size >= p.consistencyMinNights) Stats.circularSdMinutes(nights.map { it.midpointMinuteOfDay }) else null
        metrics += if (sd != null) {
            MetricScore(MetricKey.SLEEP_CONSISTENCY, sleepConsistencyScore(sd), "±${sd.roundToInt()} min", "Midpoint spread over ${nights.size} nights", Availability.AVAILABLE)
        } else {
            MetricScore(MetricKey.SLEEP_CONSISTENCY, null, null, "Needs ${p.consistencyMinNights} nights; ${nights.size} so far.", Availability.NOT_ENOUGH_HISTORY)
        }

        val awake = night.awakeMinutes
        metrics += if (awake != null && night.asleepMinutes + awake > 0) {
            val eff = night.asleepMinutes.toDouble() / (night.asleepMinutes + awake)
            MetricScore(MetricKey.SLEEP_CONTINUITY, sleepContinuityScore(eff), "${(eff * 100).roundToInt()}%", "Awake ${awake} min in bed", Availability.AVAILABLE)
        } else {
            MetricScore(MetricKey.SLEEP_CONTINUITY, null, null, "Your sleep source doesn't report time awake.", Availability.NO_DATA)
        }
        return combine(metrics) to metrics
    }

    fun cardio(input: DayInputs): Pair<Int?, List<MetricScore>> {
        val metrics = mutableListOf<MetricScore>()
        val hrv = input.hrv?.takeIf { it in 5.0..250.0 }
        metrics += if (hrv != null) {
            // ln(RMSSD), averaged over the last few nights: steadier than one night (Plews 2013/2014).
            val recent = (input.hrvRecent.filter { it in 5.0..250.0 }.takeLast(p.hrvRecentNights - 1) + hrv).map { ln(it) }
            val history = input.hrvHistory.filter { it in 5.0..250.0 }.map { ln(it) }
            val ref = ln(p.hrvReference.first) to p.hrvReference.second / p.hrvReference.first
            val b = Baselines.blend(history, ref)
            val z = Stats.robustZ(recent.average(), b.median, b.mad, p.hrvMinScale / p.hrvReference.first)
            val usual = kotlin.math.exp(b.median).roundToInt()
            MetricScore(MetricKey.HRV, higherIsBetter(z), "${hrv.roundToInt()} ms", "Usual $usual ms · ${recent.size}-night average", Availability.AVAILABLE)
        } else {
            MetricScore(MetricKey.HRV, null, null, if (input.hrv != null) "Reading out of range." else "No HRV reading from last night.", if (input.hrv != null) Availability.NOT_RELIABLE else Availability.NO_DATA)
        }
        val rhr = input.restingHr?.takeIf { it in 30.0..120.0 }
        metrics += if (rhr != null) {
            val b = Baselines.blend(input.restingHrHistory.filter { it in 30.0..120.0 }, p.restingHrReference)
            val z = Stats.robustZ(rhr, b.median, b.mad, p.restingHrMinScale)
            MetricScore(MetricKey.RESTING_HR, lowerIsBetter(z), "${rhr.roundToInt()} bpm", "Usual ${b.median.roundToInt()} bpm", Availability.AVAILABLE)
        } else {
            MetricScore(MetricKey.RESTING_HR, null, null, if (input.restingHr != null) "Reading out of range." else "No resting heart rate yet.", if (input.restingHr != null) Availability.NOT_RELIABLE else Availability.NO_DATA)
        }
        return combine(metrics) to metrics
    }

    fun activity(input: DayInputs): Pair<Int?, List<MetricScore>> {
        val a = input.activity
        val metrics = mutableListOf<MetricScore>()
        val usual = Baselines.blend(input.stepsHistory.filter { it > 0 }, p.stepsReference to 2500.0).median
        metrics += if (a?.steps != null) {
            MetricScore(MetricKey.STEPS, stepsScore(a.steps, usual), "${a.steps}", "Usual ${usual.roundToInt()}", Availability.AVAILABLE)
        } else {
            MetricScore(MetricKey.STEPS, null, null, "No steps recorded yesterday.", Availability.NO_DATA)
        }
        metrics += if (a?.exerciseMinutes != null) {
            MetricScore(MetricKey.INTENSITY, intensityScore(a.exerciseMinutes), "${a.exerciseMinutes} min", "Exercise yesterday", Availability.AVAILABLE)
        } else {
            MetricScore(MetricKey.INTENSITY, null, null, "Yesterday wasn't tracked.", Availability.NO_DATA)
        }
        metrics += if (a?.lowMovementHours != null && a.observedHours >= p.lowMovementMinObservedHours) {
            MetricScore(MetricKey.LOW_MOVEMENT, lowMovementScore(a.lowMovementHours), "${a.lowMovementHours} h", "Of ${a.observedHours} tracked waking hours", Availability.AVAILABLE)
        } else {
            MetricScore(MetricKey.LOW_MOVEMENT, null, null, "Needs ${p.lowMovementMinObservedHours}+ hours of watch wear.", Availability.NOT_RELIABLE)
        }
        // Steps anchor the dimension: without them, the day wasn't tracked.
        if (metrics.first().score == null) return null to metrics
        return combine(metrics) to metrics
    }

    fun circadian(input: DayInputs): Pair<Int?, List<MetricScore>> {
        val l = input.light
        val parts = buildList {
            if (l != null && l.morning.size >= p.lightMinSamplesPerPeriod) add(Triple("morning", morningLightScore(Stats.median(l.morning)!!), 0.4))
            if (l != null && l.daytime.size >= p.lightMinSamplesPerPeriod) add(Triple("daytime", daytimeLightScore(Stats.median(l.daytime)!!), 0.3))
            if (l != null && l.evening.size >= p.lightMinSamplesPerPeriod) add(Triple("evening", eveningLightScore(Stats.median(l.evening)!!), 0.3))
        }
        if (parts.size < 2) {
            val n = (l?.morning?.size ?: 0) + (l?.daytime?.size ?: 0) + (l?.evening?.size ?: 0)
            return null to listOf(
                MetricScore(MetricKey.LIGHT, null, null, if (n == 0) "No light readings yesterday." else "Too few light readings yesterday ($n).", if (n == 0) Availability.NO_DATA else Availability.NOT_RELIABLE),
            )
        }
        val score = (parts.sumOf { it.second * it.third } / parts.sumOf { it.third }).roundToInt()
        val detail = parts.joinToString(" · ") { "${it.first} ${it.second}" }
        return score to listOf(MetricScore(MetricKey.LIGHT, score, "${parts.size}/3 periods", detail, Availability.AVAILABLE))
    }

    fun cognitive(input: DayInputs): Pair<Int?, List<MetricScore>> {
        val c = input.cognitive
            ?: return null to listOf(
                MetricScore(MetricKey.BTC_ACCURACY, null, null, "No scored Beat the Clock today.", Availability.NO_DATA),
                MetricScore(MetricKey.BTC_CONSISTENCY, null, null, "No scored Beat the Clock today.", Availability.NO_DATA),
                MetricScore(MetricKey.BTC_BIAS, null, null, "No scored Beat the Clock today.", Availability.NO_DATA),
            )
        val metrics = listOf(
            MetricScore(MetricKey.BTC_ACCURACY, c.accuracy, "${c.accuracy}", c.sessionLabel, Availability.AVAILABLE),
            MetricScore(MetricKey.BTC_CONSISTENCY, c.consistency, "${c.consistency}", c.sessionLabel, Availability.AVAILABLE),
            MetricScore(MetricKey.BTC_BIAS, c.bias, "${c.bias}", c.sessionLabel, Availability.AVAILABLE),
        )
        return combine(metrics) to metrics
    }

    /** Internal weights re-normalized over the metrics that have a score. */
    private fun combine(metrics: List<MetricScore>): Int? {
        val available = metrics.filter { it.score != null }
        if (available.isEmpty()) return null
        val w = available.sumOf { it.key.internalWeight }
        return (available.sumOf { it.score!! * it.key.internalWeight } / w).roundToInt().coerceIn(0, 100)
    }

    private fun hm(minutes: Int) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

// ------------------------------------------------------------------ readiness (spec §2, §6)

@Serializable
enum class ReadinessConfidence(val label: String) {
    HIGH("High confidence"),
    MEDIUM("Medium confidence"),
    LOW("Low confidence"),
    INSUFFICIENT("Not enough data"),
}

/** Full earlier days of data for Medium confidence. */
const val MIN_CONFIDENT_DAYS = 3
/** Full earlier days of data for High confidence. */
const val HIGH_CONFIDENCE_DAYS = 7

/**
 * Confidence from both what is in today and how much history is behind it (spec §6): High needs ≥ 90% of the
 * weighted signals and 7 full days; Medium ≥ 70% and 3 days; anything less is Low. INSUFFICIENT means no score.
 */
fun confidenceFor(coverage: Double, historyDays: Int): ReadinessConfidence = when {
    coverage >= 0.90 - 1e-9 && historyDays >= HIGH_CONFIDENCE_DAYS -> ReadinessConfidence.HIGH
    coverage >= 0.70 - 1e-9 && historyDays >= MIN_CONFIDENT_DAYS -> ReadinessConfidence.MEDIUM
    else -> ReadinessConfidence.LOW
}

/** The configuration and engine a score was calculated with, kept forever with the score (spec §3.7). */
@Serializable
data class ConfigStamp(
    val configId: String,
    val configVersion: Int,
    val configType: ConfigurationType,
    val weights: Map<Dimension, Double>,
    val engineVersion: String,
) {
    /** The configuration this score was calculated with, to recalculate the same day with the same weights. */
    fun toConfig(): ScoringConfiguration = ScoringConfiguration(
        configId, configVersion, configType,
        weights[Dimension.SLEEP] ?: 0.0, weights[Dimension.CARDIO] ?: 0.0, weights[Dimension.ACTIVITY] ?: 0.0,
        weights[Dimension.CIRCADIAN] ?: 0.0, weights[Dimension.COGNITIVE] ?: 0.0, 0L,
    )
}

@Serializable
data class ReadinessCalculation(
    val dayKey: String,
    /** Null means INSUFFICIENT_DATA. */
    val score: Int?,
    val coverage: Double,
    val confidence: ReadinessConfidence,
    val dimensions: List<DimensionScore>,
    val stamp: ConfigStamp,
    /** Why no number was published, in plain words. */
    val insufficientReason: String?,
    /** Full earlier days with any sensor data behind this score. */
    val historyDays: Int = MIN_CONFIDENT_DAYS,
    /** Signals (dimensions with weight) that are in today, out of [signalsTotal]. */
    val signalsIn: Int = 0,
    val signalsTotal: Int = 0,
    /** What would raise confidence, in plain words; null at High. */
    val confidenceHint: String? = null,
    /** Deductions from optional extras (already applied to [score]). */
    val modifiers: List<ScoreModifier> = emptyList(),
)

/** "4 of 5 signals · 6 days of data", shown next to the confidence level. */
val ReadinessCalculation.confidenceBasis: String
    get() = "$signalsIn of $signalsTotal signals · " + if (historyDays == 0) "first day of data" else "$historyDays ${if (historyDays == 1) "day" else "days"} of data"

/** What adding each missing signal takes, in words that point at an action. */
private val Dimension.toAdd: String
    get() = when (this) {
        Dimension.SLEEP -> "sleep tracking"
        Dimension.CARDIO -> "overnight heart data"
        Dimension.ACTIVITY -> "step tracking"
        Dimension.CIRCADIAN -> "daylight readings"
        Dimension.COGNITIVE -> "a Beat the Clock game"
    }

/** What each dimension means in everyday words, for sentences like "Hercules has your sleep and activity". */
val Dimension.plain: String
    get() = when (this) {
        Dimension.SLEEP -> "sleep"
        Dimension.CARDIO -> "heart rate and HRV"
        Dimension.ACTIVITY -> "activity"
        Dimension.CIRCADIAN -> "daylight"
        Dimension.COGNITIVE -> "Beat the Clock"
    }

private fun List<String>.inWords(): String = when (size) {
    0 -> ""
    1 -> first()
    else -> dropLast(1).joinToString(", ") + " and " + last()
}

/**
 * Why there is no score, in one or two plain sentences: what is in, what is missing, and what a score needs.
 * Null when there is a score.
 */
val ReadinessCalculation.insufficientDetail: String?
    get() {
        if (score != null) return null
        return "Nothing has come in for today yet. One reading is enough to start: last night's sleep, heart rate, " +
            "steps, daylight or a Beat the Clock game. Connect Health Connect, wear the watch, or play a game."
    }

class ReadinessCalculator(private val scorer: DimensionScorer = DimensionScorer()) {

    fun calculate(input: DayInputs, config: ScoringConfiguration): ReadinessCalculation {
        val raw = mapOf(
            Dimension.SLEEP to scorer.sleep(input),
            Dimension.CARDIO to scorer.cardio(input),
            Dimension.ACTIVITY to scorer.activity(input),
            Dimension.CIRCADIAN to scorer.circadian(input),
            Dimension.COGNITIVE to scorer.cognitive(input),
        )
        val dims = Dimension.entries.map { d ->
            val (score, metrics) = raw.getValue(d)
            val w = config.weight(d)
            val availability = when {
                w == 0.0 -> Availability.ZERO_WEIGHT
                score != null -> Availability.AVAILABLE
                metrics.any { it.availability == Availability.NOT_ENOUGH_HISTORY } -> Availability.NOT_ENOUGH_HISTORY
                metrics.any { it.availability == Availability.NOT_RELIABLE } -> Availability.NOT_RELIABLE
                else -> Availability.NO_DATA
            }
            DimensionScore(d, score, w, metrics, availability)
        }
        // Full earlier days with any data: the longest per-signal history. Steps history stops before yesterday
        // (yesterday's steps are the activity input), so yesterday is added back when it has steps.
        val stepDays = input.stepsHistory.size + if (input.activity != null) 1 else 0
        val historyDays = maxOf(input.previousNights.size, input.hrvHistory.size, input.restingHrHistory.size, stepDays)
        return combine(input.dayKey, dims, config, historyDays, scorer.modifiers(input.strain))
    }

    /**
     * Weighted mean over available dimensions (spec §6). One available signal is enough for a score; optional extras
     * can only subtract. Confidence follows coverage and days of history; the hint says what would raise it.
     */
    fun combine(
        dayKey: String,
        dims: List<DimensionScore>,
        config: ScoringConfiguration,
        historyDays: Int = MIN_CONFIDENT_DAYS,
        modifiers: List<ScoreModifier> = emptyList(),
    ): ReadinessCalculation {
        val stamp = ConfigStamp(config.id, config.version, config.type, config.weights, SCORING_ENGINE_VERSION)
        val available = dims.filter { it.available }
        val coverage = available.sumOf { it.weight }.let { if (it > 1.0) 1.0 else it }
        val weight = available.sumOf { it.weight }
        val applied = if (available.isNotEmpty() && weight > 0.0) modifiers else emptyList()
        val score = if (available.isNotEmpty() && weight > 0.0) {
            ((available.sumOf { it.weight * it.score!! } / weight).roundToInt() + applied.sumOf { it.points }).coerceIn(0, 100)
        } else null
        val reason = if (score == null) "Waiting on the first reading" else null
        val confidence = if (score == null) ReadinessConfidence.INSUFFICIENT else confidenceFor(coverage, historyDays)
        val counted = dims.filter { it.weight > 0.0 }
        val hint = if (score == null || confidence == ReadinessConfidence.HIGH) null else {
            val missing = counted.filterNot { it.available }.sortedByDescending { it.weight }.map { it.dimension.toAdd }
            val target = if (confidence == ReadinessConfidence.LOW && historyDays < MIN_CONFIDENT_DAYS) MIN_CONFIDENT_DAYS else HIGH_CONFIDENCE_DAYS
            val days = (target - historyDays).takeIf { it > 0 }?.let { "$it more ${if (it == 1) "day" else "days"} of data" }
            listOfNotNull(missing.takeIf { it.isNotEmpty() }?.let { "add ${it.inWords()}" }, days)
                .joinToString("; ").takeIf { it.isNotEmpty() }?.let { "To raise it: $it." }
        }
        return ReadinessCalculation(
            dayKey, score, coverage, confidence, dims, stamp, reason, historyDays,
            signalsIn = counted.count { it.available }, signalsTotal = counted.size, confidenceHint = hint, modifiers = applied,
        )
    }
}
