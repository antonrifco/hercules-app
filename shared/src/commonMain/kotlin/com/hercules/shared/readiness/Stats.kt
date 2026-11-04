package com.hercules.shared.readiness

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** Robust statistics used by every baseline and scorer. Pure functions, no platform code. */
object Stats {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    /** Median absolute deviation (unscaled). */
    fun mad(values: List<Double>): Double? {
        val m = median(values) ?: return null
        return median(values.map { abs(it - m) })
    }

    /** Robust z score, `(value − median) / (1.4826 · MAD)`, with [minScale] guarding a tiny or zero MAD. */
    fun robustZ(value: Double, median: Double, mad: Double, minScale: Double): Double {
        val scale = maxOf(1.4826 * mad, minScale)
        return (value - median) / scale
    }

    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    fun log2(x: Double): Double = ln(x) / ln(2.0)

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    fun sd(values: List<Double>): Double? {
        val m = mean(values) ?: return null
        if (values.size < 2) return 0.0
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }

    /**
     * Circular standard deviation, in minutes, of clock times given as minutes since midnight. Sleep midpoints
     * either side of midnight (23:50 and 00:10) are 20 minutes apart, not 23 hours.
     */
    fun circularSdMinutes(minutesOfDay: List<Double>): Double? {
        if (minutesOfDay.size < 2) return null
        val angles = minutesOfDay.map { it / (24 * 60.0) * 2 * PI }
        val c = angles.sumOf { cos(it) } / angles.size
        val s = angles.sumOf { sin(it) } / angles.size
        val r = sqrt(c * c + s * s).coerceIn(1e-12, 1.0)
        val sdRad = sqrt(-2 * ln(r))
        return sdRad / (2 * PI) * 24 * 60
    }

    /** Circular mean of clock times (minutes since midnight). */
    fun circularMeanMinutes(minutesOfDay: List<Double>): Double? {
        if (minutesOfDay.isEmpty()) return null
        val angles = minutesOfDay.map { it / (24 * 60.0) * 2 * PI }
        val a = atan2(angles.sumOf { sin(it) }, angles.sumOf { cos(it) })
        val m = a / (2 * PI) * 24 * 60
        return (m + 24 * 60) % (24 * 60)
    }

    /** Piecewise-linear interpolation over points sorted by x; clamps outside the range. */
    fun curve(x: Double, points: List<Pair<Double, Double>>): Double {
        if (x <= points.first().first) return points.first().second
        for (i in 1 until points.size) {
            val (x1, y1) = points[i]
            if (x <= x1) {
                val (x0, y0) = points[i - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return points.last().second
    }
}
