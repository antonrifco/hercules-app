package com.hercules.shared.insights

import com.hercules.shared.model.DailySummary
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.PersonalRecord
import com.hercules.shared.model.RecordKind
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SessionSummary
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.time.Instant

/** Local calendar-day helpers. Day keys are `yyyy-MM-dd` in the device time zone. */
class DayKeys(private val timeZone: TimeZone = TimeZone.currentSystemDefault()) {
    fun of(epochMs: Long): String =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).date.toString()

    fun startOf(dayKey: String): Long =
        LocalDate.parse(dayKey).atStartOfDayIn(timeZone).toEpochMilliseconds()

    fun nextDayStart(dayKey: String): Long =
        LocalDate.parse(dayKey).plus(1, DateTimeUnit.DAY).atStartOfDayIn(timeZone).toEpochMilliseconds()

    /** Minutes since local midnight. */
    fun minuteOfDay(epochMs: Long): Int {
        val t = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(timeZone).time
        return t.hour * 60 + t.minute
    }

    /** Local `HH:mm`. */
    fun clock(epochMs: Long): String {
        val m = minuteOfDay(epochMs)
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** Days from [fromKey] to [toKey] (negative if [toKey] is earlier). */
    fun daysBetween(fromKey: String, toKey: String): Int =
        LocalDate.parse(fromKey).daysUntil(LocalDate.parse(toKey))

    fun plusDays(dayKey: String, days: Int): String =
        LocalDate.parse(dayKey).plus(days, DateTimeUnit.DAY).toString()

    companion object {
        fun isNextDay(a: String, b: String): Boolean =
            LocalDate.parse(a).plus(1, DateTimeUnit.DAY) == LocalDate.parse(b)
    }
}

class DailyAggregator(
    private val dayKeys: DayKeys,
    private val restingWindowMs: Long = 10 * 60_000L,
    private val restingMinSamples: Int = 3,
) {
    fun aggregate(dayKey: String, samples: List<Sample>): DailySummary {
        val start = dayKeys.startOf(dayKey)
        val end = dayKeys.nextDayStart(dayKey)
        var steps = 0.0
        var calories = 0.0
        var distance = 0.0
        var floors = 0.0
        val hr = mutableListOf<Sample>()
        val ibi = mutableListOf<Sample>()

        for (s in samples) {
            if (s.startMs < start || s.startMs >= end) continue
            val counts = s.source.countsTowardDailyTotals
            when (s.type) {
                MetricType.HEART_RATE -> hr += s
                MetricType.IBI -> ibi += s
                MetricType.STEPS -> if (counts) steps += s.value
                MetricType.CALORIES -> if (counts) calories += s.value
                MetricType.DISTANCE -> if (counts) distance += s.value
                MetricType.FLOORS -> if (counts) floors += s.value
                else -> Unit
            }
        }
        hr.sortBy { it.startMs }
        ibi.sortBy { it.startMs }
        val values = hr.map { it.value }

        return DailySummary(
            dayKey = dayKey,
            steps = steps.roundToLong(),
            calories = calories,
            distanceMeters = distance,
            floors = floors.roundToLong(),
            restingHr = restingHr(hr),
            maxHr = values.maxOrNull()?.roundToLong(),
            avgHr = if (values.isEmpty()) null else values.average(),
            hrSampleCount = values.size.toLong(),
            hrvRmssd = Hrv.rmssd(ibi.map { it.value }),
        )
    }

    /** Lowest mean over any sliding window of [restingWindowMs] holding >= [restingMinSamples] samples. */
    private fun restingHr(hr: List<Sample>): Long? {
        var best: Double? = null
        var j = 0
        var sum = 0.0
        for (i in hr.indices) {
            sum += hr[i].value
            while (hr[i].startMs - hr[j].startMs > restingWindowMs) {
                sum -= hr[j].value
                j++
            }
            val n = i - j + 1
            if (n >= restingMinSamples) {
                val mean = sum / n
                if (best == null || mean < best) best = mean
            }
        }
        return best?.roundToLong()
    }
}

class SessionSummarizer(private val dayKeys: DayKeys) {
    companion object {
        /** How long HR keeps being measured after a workout ends, and how long to wait before summarizing. */
        const val RECOVERY_WINDOW_MS = 75_000L
        private const val RECOVERY_TARGET_MS = 60_000L
        private const val RECOVERY_TOLERANCE_MS = 10_000L
    }

    fun summarize(id: String, kind: String, deviceId: String, startMs: Long, endMs: Long, samples: List<Sample>): SessionSummary {
        val inSession = mutableListOf<Sample>()
        val after = mutableListOf<Sample>()
        var distance = 0.0
        val recoveryEnd = endMs + RECOVERY_WINDOW_MS

        for (s in samples) {
            when {
                s.type == MetricType.HEART_RATE && s.startMs in startMs..endMs -> inSession += s
                s.type == MetricType.HEART_RATE && s.startMs > endMs && s.startMs <= recoveryEnd -> after += s
                s.type == MetricType.DISTANCE && s.sessionId == id -> distance += s.value
            }
        }
        inSession.sortBy { it.startMs }
        val hr = inSession.map { it.value }

        var recovery: Long? = null
        if (inSession.isNotEmpty() && after.isNotEmpty()) {
            val target = endMs + RECOVERY_TARGET_MS
            val nearest = after.minBy { abs(it.startMs - target) }
            if (abs(nearest.startMs - target) <= RECOVERY_TOLERANCE_MS) {
                recovery = (inSession.last().value - nearest.value).roundToLong()
            }
        }

        return SessionSummary(
            id = id,
            kind = kind,
            deviceId = deviceId,
            dayKey = dayKeys.of(startMs),
            startMs = startMs,
            endMs = endMs,
            distanceMeters = distance,
            avgHr = if (hr.isEmpty()) null else hr.average(),
            maxHr = hr.maxOrNull()?.roundToLong(),
            hrRecovery60s = recovery,
        )
    }
}

class PersonalRecordEngine(private val stepGoal: Long = 10_000) {

    fun evaluate(days: List<DailySummary>, sessions: List<SessionSummary>): Map<RecordKind, PersonalRecord> {
        val out = mutableMapOf<RecordKind, PersonalRecord>()

        fun offer(kind: RecordKind, value: Double?, dayKey: String, refId: String? = null) {
            if (value == null) return
            val cur = out[kind]
            if (cur == null || kind.isBetter(value, cur.value)) {
                out[kind] = PersonalRecord(kind, value, dayKey, refId)
            }
        }

        val sortedDays = days.sortedBy { it.dayKey }
        for (d in sortedDays) {
            if (d.steps > 0) offer(RecordKind.MAX_DAILY_STEPS, d.steps.toDouble(), d.dayKey)
            offer(RecordKind.LOWEST_RESTING_HR, d.restingHr?.toDouble(), d.dayKey)
            offer(RecordKind.HIGHEST_HRV_RMSSD, d.hrvRmssd, d.dayKey)
        }
        for (s in sessions.sortedBy { it.startMs }) {
            if (s.distanceMeters > 0) offer(RecordKind.MAX_SESSION_DISTANCE, s.distanceMeters, s.dayKey, s.id)
            offer(RecordKind.BEST_HR_RECOVERY, s.hrRecovery60s?.toDouble(), s.dayKey, s.id)
        }

        var streak = 0
        var prevGoalDay: String? = null
        for (d in sortedDays) {
            if (d.steps >= stepGoal) {
                streak = if (prevGoalDay != null && DayKeys.isNextDay(prevGoalDay, d.dayKey)) streak + 1 else 1
                prevGoalDay = d.dayKey
                offer(RecordKind.LONGEST_STEP_STREAK, streak.toDouble(), d.dayKey)
            } else {
                streak = 0
                prevGoalDay = null
            }
        }
        return out
    }

    fun newlyBroken(before: Map<RecordKind, PersonalRecord>, after: Map<RecordKind, PersonalRecord>): List<PersonalRecord> =
        after.values.filter { r ->
            val old = before[r.kind]
            old == null || r.kind.isBetter(r.value, old.value)
        }
}

object Hrv {
    /** RMSSD over beat-to-beat intervals (ms). Physiologically impossible beats are dropped. */
    fun rmssd(ibiMs: List<Double>): Double? {
        val v = ibiMs.filter { it in 300.0..2000.0 }
        if (v.size < 2) return null
        var sum = 0.0
        for (i in 1 until v.size) {
            val d = v[i] - v[i - 1]
            sum += d * d
        }
        return sqrt(sum / (v.size - 1))
    }
}
