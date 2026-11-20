package com.hercules.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hercules.mobile.MobileGraph
import com.hercules.shared.cognitive.grouped
import com.hercules.shared.model.MetricType
import com.hercules.shared.readiness.DataQuality
import com.hercules.shared.readiness.HealthMetricType
import com.hercules.shared.readiness.LightContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The signals Setup lists. Each one can show its latest reading and the last 7 days in four parts of the day. */
enum class SignalKind(val title: String, val cellHint: String) {
    SLEEP("Sleep", "Minutes asleep"),
    HRV("Heart rate variability", "Average, ms"),
    RESTING_HR("Resting heart rate", "Lowest 10-minute average, bpm"),
    STEPS("Steps", "Steps taken"),
    WORKOUTS("Workouts", "Minutes of exercise"),
    LIGHT("Ambient light", "Typical brightness, lux"),
    RESPIRATORY("Respiratory rate", "Average, breaths/min"),
    SKIN_TEMP("Skin temperature", "Average deviation, °C"),
    SPO2("Blood oxygen", "Average, %"),
    GLUCOSE("Glucose", "Average, mmol/L"),
}

/** Night 00–06, morning 06–12, afternoon 12–18, evening 18–24. */
val DayParts = listOf("Night", "Morning", "Afternoon", "Evening")

data class Latest(val text: String, val atMs: Long)
data class DayRow(val dayKey: String, val startMs: Long, val cells: List<String?>)

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val PART = 6 * HOUR

/**
 * Reads one signal from everything Hercules has stored (Health Connect, the watch, this phone) for the last [days]
 * days. Each day is split into four 6-hour parts; a part with no reading stays empty, never zero.
 */
suspend fun signalHistory(g: MobileGraph, kind: SignalKind, nowMs: Long = System.currentTimeMillis(), days: Int = 7): List<DayRow> {
    val dk = g.cognitive.dayKeys
    val store = g.cognitive.store
    val today = dk.of(nowMs)
    val keys = (0 until days).map { dk.plusDays(today, -it) }
    val from = dk.startOf(keys.last())
    val to = dk.nextDayStart(today)

    suspend fun metric(t: HealthMetricType) =
        store.metrics(t, dk.plusDays(keys.last(), -1), today).filter { it.quality == DataQuality.VALID }

    // One value (or null) per 6-hour part, from a function of the part's bounds.
    val cell: suspend (Long, Long) -> String? = when (kind) {
        SignalKind.SLEEP -> {
            val nights = store.sleepRange(dk.plusDays(keys.last(), -1), dk.plusDays(today, 1));
            { a, b ->
                // Time asleep inside the part, spread evenly over the night (stages aren't stored by time).
                val m = nights.sumOf { n ->
                    val overlap = (minOf(b, n.endMs) - maxOf(a, n.startMs)).coerceAtLeast(0)
                    val span = (n.endMs - n.startMs).coerceAtLeast(1)
                    overlap.toDouble() / span * n.asleepMinutes
                }.roundToInt()
                if (m > 0) hm(m) else null
            }
        }
        SignalKind.HRV, SignalKind.RESPIRATORY, SignalKind.SKIN_TEMP, SignalKind.SPO2, SignalKind.GLUCOSE -> {
            val type = metricType(kind)!!
            val list = metric(type);
            { a, b ->
                val v = list.filter { it.measuredMs in a until b }.map { it.value }
                if (v.isEmpty()) null else format(kind, v.average())
            }
        }
        SignalKind.RESTING_HR -> {
            val hc = metric(HealthMetricType.RESTING_HR)
            val pulse = g.repo.samplesBetween(from, to).filter { it.type == MetricType.HEART_RATE }.sortedBy { it.startMs };
            { a, b ->
                val v = listOfNotNull(
                    hc.filter { it.measuredMs in a until b }.minOfOrNull { it.value },
                    lowestWindow(pulse.filter { it.startMs in a until b }.map { it.startMs to it.value }),
                )
                v.minOrNull()?.let { "${it.roundToInt()}" }
            }
        }
        SignalKind.STEPS -> {
            val hc = g.healthConnect.stepsByBucket(from, to, PART)
            val watch = g.repo.samplesBetween(from, to).filter { it.type == MetricType.STEPS && it.source.countsTowardDailyTotals };
            { a, b ->
                // Same rule as the score: the fuller reading wins, never summed.
                val w = watch.filter { it.startMs in a until b }.sumOf { it.value }.roundToLong()
                val h = hc[a]
                val best = maxOf(w, h ?: 0L)
                if (best > 0 || h != null) best.grouped() else null
            }
        }
        SignalKind.WORKOUTS -> {
            val spans = store.exerciseBetween(from, to).map { it.startMs to it.endMs } +
                g.repo.summarizedSessions().filter { it.endMs > from && it.startMs < to }.map { it.startMs to it.endMs };
            { a, b ->
                val m = spans.sumOf { (s, e) -> (minOf(b, e) - maxOf(a, s)).coerceAtLeast(0) } / MIN
                if (m > 0) "${m} min" else null
            }
        }
        SignalKind.LIGHT -> {
            val lux = store.lightBetween(from, to).filterNot { it.context == LightContext.BACKGROUND && it.lux < 1.0 };
            { a, b ->
                val v = lux.filter { it.measuredMs in a until b }.map { it.lux }.sorted()
                if (v.isEmpty()) null else "${v[v.size / 2].roundToInt()}"
            }
        }
    }

    return keys.map { key ->
        val start = dk.startOf(key)
        DayRow(key, start, (0 until 4).map { i ->
            val a = start + i * PART
            if (a > nowMs) null else cell(a, a + PART)
        })
    }
}

/** The newest reading of a signal and when it was taken, for the Setup list. */
suspend fun latestReading(g: MobileGraph, kind: SignalKind, nowMs: Long = System.currentTimeMillis()): Latest? {
    val dk = g.cognitive.dayKeys
    val store = g.cognitive.store
    val today = dk.of(nowMs)
    val weekAgo = dk.plusDays(today, -7)
    fun pick(vararg l: Latest?) = l.filterNotNull().maxByOrNull { it.atMs }
    return when (kind) {
        SignalKind.SLEEP -> store.sleepRange(weekAgo, today).maxByOrNull { it.endMs }?.let { Latest("${hm(it.asleepMinutes)} asleep", it.endMs) }
        SignalKind.HRV, SignalKind.RESPIRATORY, SignalKind.SKIN_TEMP, SignalKind.SPO2, SignalKind.GLUCOSE ->
            store.metrics(metricType(kind)!!, weekAgo, today).filter { it.quality == DataQuality.VALID }.maxByOrNull { it.measuredMs }
                ?.let { Latest(format(kind, it.value) + " " + unit(kind), it.measuredMs) }
        SignalKind.RESTING_HR -> {
            val hc = store.metrics(HealthMetricType.RESTING_HR, weekAgo, today).filter { it.quality == DataQuality.VALID }.maxByOrNull { it.measuredMs }
            val daily = g.repo.allDaily().filter { it.restingHr != null && it.dayKey >= weekAgo }.maxByOrNull { it.dayKey }
            val lastPulse = daily?.let { g.repo.samplesBetween(dk.startOf(it.dayKey), dk.nextDayStart(it.dayKey)).filter { s -> s.type == MetricType.HEART_RATE }.maxOfOrNull { s -> s.startMs } }
            pick(hc?.let { Latest("${it.value.roundToInt()} bpm", it.measuredMs) }, if (daily != null && lastPulse != null) Latest("${daily.restingHr} bpm", lastPulse) else null)
        }
        SignalKind.STEPS -> {
            val hc = store.metrics(HealthMetricType.STEPS_DAILY, weekAgo, today).filter { it.quality == DataQuality.VALID }.maxByOrNull { it.endMs }
            val watch = g.repo.samplesBetween(dk.startOf(weekAgo), nowMs).filter { it.type == MetricType.STEPS && it.source.countsTowardDailyTotals }.maxByOrNull { it.endMs }
            val watchDay = watch?.let { dk.of(it.startMs) }
            val watchTotal = watchDay?.let { d -> g.repo.allDaily().firstOrNull { it.dayKey == d }?.steps }
            pick(
                hc?.let { Latest("%,d steps".format(UiLocale, it.value.roundToLong()) + if (it.dayKey == today) " today" else "", it.endMs) },
                if (watch != null && watchTotal != null) Latest("%,d steps".format(UiLocale, watchTotal) + if (watchDay == today) " today" else "", watch.endMs) else null,
            )
        }
        SignalKind.WORKOUTS -> {
            val from = dk.startOf(dk.plusDays(today, -14))
            val last = (store.exerciseBetween(from, nowMs).map { it.startMs to it.endMs } +
                g.repo.summarizedSessions().filter { it.endMs > from }.map { it.startMs to it.endMs }).maxByOrNull { it.second }
            last?.let { Latest("${(it.second - it.first) / MIN} min", it.second) }
        }
        SignalKind.LIGHT -> store.latestLight()?.let { Latest("${it.lux.grouped()} lx", it.measuredMs) }
    }
}

private fun metricType(k: SignalKind) = when (k) {
    SignalKind.HRV -> HealthMetricType.HRV_RMSSD
    SignalKind.RESPIRATORY -> HealthMetricType.RESPIRATORY_RATE
    SignalKind.SKIN_TEMP -> HealthMetricType.SKIN_TEMPERATURE_DELTA
    SignalKind.SPO2 -> HealthMetricType.SPO2
    SignalKind.GLUCOSE -> HealthMetricType.BLOOD_GLUCOSE
    else -> null
}

private fun unit(k: SignalKind) = when (k) {
    SignalKind.HRV -> "ms"
    SignalKind.RESPIRATORY -> "br/min"
    SignalKind.SKIN_TEMP -> "°C"
    SignalKind.SPO2 -> "%"
    SignalKind.GLUCOSE -> "mmol/L"
    else -> ""
}

private fun format(k: SignalKind, v: Double) = when (k) {
    SignalKind.SKIN_TEMP -> "%+.1f".format(v)
    SignalKind.GLUCOSE, SignalKind.RESPIRATORY -> "%.1f".format(v)
    else -> v.grouped()
}

private fun hm(minutes: Int) = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"

/** Lowest mean over any 10-minute window holding at least 3 readings: the same resting rule the watch data uses. */
private fun lowestWindow(points: List<Pair<Long, Double>>): Double? {
    var best: Double? = null
    var j = 0
    var sum = 0.0
    for (i in points.indices) {
        sum += points[i].second
        while (points[i].first - points[j].first > 10 * MIN) { sum -= points[j].second; j++ }
        val n = i - j + 1
        if (n >= 3) { val m = sum / n; if (best == null || m < best) best = m }
    }
    return best
}

/** Last 7 days of one signal: a row per day, four columns for the parts of the day. */
@Composable
fun SignalHistoryTable(g: MobileGraph, kind: SignalKind) {
    val rows by produceState<List<DayRow>?>(null, kind) { value = runCatching { signalHistory(g, kind) }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxWidth()) {
        Muted("${kind.cellHint}, per part of the day. Night 00–06, morning 06–12, afternoon 12–18, evening 18–24. Empty means no reading.")
        Box(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(76.dp))
            DayParts.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, color = Text2, textAlign = TextAlign.Center, maxLines = 1) }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Hairline))
        val r = rows
        if (r == null) {
            Muted("Loading…")
        } else {
            r.forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                    Text(shortDate(row.startMs), Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = Text1, maxLines = 1)
                    row.cells.forEach { c ->
                        Text(c ?: "–", Modifier.weight(1f), fontFamily = Mono, fontSize = 13.sp, color = if (c == null) Text2 else Cyan, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
            if (r.all { row -> row.cells.all { it == null } }) Muted("No readings in the last 7 days.")
        }
    }
}
