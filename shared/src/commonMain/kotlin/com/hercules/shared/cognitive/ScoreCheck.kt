package com.hercules.shared.cognitive

import com.hercules.shared.readiness.BeatTheClock
import com.hercules.shared.readiness.CognitiveSession
import com.hercules.shared.readiness.Dimension
import com.hercules.shared.readiness.Stats
import kotlin.math.abs

/**
 * Does the body part of the score (sleep, heart, activity, light) track how the user actually did in Beat the
 * Clock? A measurement only: nothing here changes a score. The game's own dimension is left out of the body score,
 * otherwise the score would be compared with itself.
 */
object ScoreCheck {
    /** Fewer days than this and the correlation is mostly noise, so no verdict is given. */
    const val MIN_DAYS = 30
    const val WINDOW_DAYS = 90

    enum class Link { NOT_ENOUGH_DATA, NONE, WEAK, CLEAR }

    /** [correlation] is Spearman's rank correlation over [days] days that have both a body score and a game. */
    data class Result(val days: Int, val correlation: Double?) {
        val link: Link
            get() = when {
                days < MIN_DAYS || correlation == null -> Link.NOT_ENOUGH_DATA
                abs(correlation) >= 0.3 -> Link.CLEAR
                abs(correlation) >= 0.15 -> Link.WEAK
                else -> Link.NONE
            }
    }

    /** The score from every available dimension except the game, re-weighted the same way the real score is. */
    fun bodyScore(r: Readiness): Double? {
        val dims = r.dimensions.filter { it.dimension != Dimension.COGNITIVE && it.available }
        val w = dims.sumOf { it.weight }
        return if (w > 0) dims.sumOf { it.score!! * it.weight } / w else null
    }

    fun run(days: List<Readiness>, sessions: List<CognitiveSession>): Result {
        val games = sessions
            .filter { it.gameVersion == BeatTheClock.GAME_VERSION && it.valid && it.cognitiveScore != null }
            .groupBy { it.dayKey }
            .mapValues { (_, l) -> Stats.median(l.map { it.cognitiveScore!!.toDouble() })!! }
        val pairs = days.mapNotNull { d -> val b = bodyScore(d); val g = games[d.dayKey]; if (b != null && g != null) b to g else null }
        return Result(pairs.size, spearman(pairs.map { it.first }, pairs.map { it.second }))
    }

    /** Rank correlation (ties share their average rank). Null when there are too few points or no spread. */
    fun spearman(x: List<Double>, y: List<Double>): Double? {
        if (x.size < 5 || x.size != y.size) return null
        val rx = ranks(x)
        val ry = ranks(y)
        val mx = rx.average()
        val my = ry.average()
        val sxy = rx.indices.sumOf { (rx[it] - mx) * (ry[it] - my) }
        val sx = rx.sumOf { (it - mx) * (it - mx) }
        val sy = ry.sumOf { (it - my) * (it - my) }
        return if (sx > 0 && sy > 0) sxy / kotlin.math.sqrt(sx * sy) else null
    }

    private fun ranks(v: List<Double>): List<Double> {
        val order = v.indices.sortedBy { v[it] }
        val out = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
            val rank = (i + j) / 2.0 + 1
            for (k in i..j) out[order[k]] = rank
            i = j + 1
        }
        return out.toList()
    }
}
