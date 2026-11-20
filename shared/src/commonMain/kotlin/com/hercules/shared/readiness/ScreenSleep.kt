package com.hercules.shared.readiness

/**
 * A phone-only sleep estimate from screen use (spec §2.10): the longest stretch overnight from the screen going off
 * to the first unlock. Brief checks in the night (under [BRIEF_USE_MS] of use) don't split it; that time counts as
 * awake. It is a phone-tier source: the model uses it like any unstaged night, and confidence stays at most Medium.
 */
object ScreenSleep {
    /** Stored as a Health Connect-style session with this package, so it's never mistaken for a wearable's night. */
    const val SOURCE = "hercules.screen"
    const val MIN_MS = 3 * 3_600_000L
    const val MAX_MS = 14 * 3_600_000L
    const val BRIEF_USE_MS = 10 * 60_000L

    enum class Kind { OFF, UNLOCK }
    data class Event(val atMs: Long, val kind: Kind)
    data class Estimate(val startMs: Long, val endMs: Long, val awakeMinutes: Int)

    /**
     * The night inside [[fromMs], [toMs]] (the evening before to early afternoon), or null. Only finished nights:
     * the stretch must end with an unlock at or before [nowMs].
     */
    fun estimate(events: List<Event>, fromMs: Long, toMs: Long, nowMs: Long): Estimate? {
        val sorted = events.filter { it.atMs <= nowMs }.sortedBy { it.atMs }
        // Idle stretches: the first screen-off after an unlock, to the next unlock.
        val gaps = mutableListOf<Pair<Long, Long>>()
        var off: Long? = null
        for (e in sorted) when (e.kind) {
            Kind.OFF -> if (off == null) off = e.atMs
            Kind.UNLOCK -> { off?.let { if (e.atMs > it) gaps += it to e.atMs }; off = null }
        }
        // Join stretches split by a brief check, keeping the time used as awake.
        val merged = mutableListOf<Triple<Long, Long, Long>>() // start, end, awake ms
        for ((a, b) in gaps) {
            val last = merged.lastOrNull()
            if (last != null && a - last.second <= BRIEF_USE_MS) merged[merged.lastIndex] = Triple(last.first, b, last.third + (a - last.second))
            else merged += Triple(a, b, 0L)
        }
        val best = merged
            .map { (a, b, awake) -> Triple(maxOf(a, fromMs), minOf(b, toMs), awake) }
            .filter { (a, b, _) -> b - a in MIN_MS..MAX_MS }
            .maxByOrNull { (a, b, awake) -> b - a - awake } ?: return null
        return Estimate(best.first, best.second, (best.third / 60_000L).toInt())
    }
}
