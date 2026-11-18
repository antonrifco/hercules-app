package com.hercules.shared

import com.hercules.shared.cognitive.SleepAnalyzer
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SleepAnalyzerTest {
    private val dk = DayKeys(TimeZone.UTC)
    private val analyzer = SleepAnalyzer(dk)
    private val day = "2026-11-10"
    private val midnight = dk.startOf(day)
    private val h = 3_600_000L

    private fun state(v: Double, t: Long) = Sample.create(MetricType.ACTIVITY_STATE, v, t, t, SampleSource.PASSIVE, "w")
    private fun hr(t: Long) = Sample.create(MetricType.HEART_RATE, 55.0, t, t, SampleSource.PASSIVE, "w")

    @Test fun anEveningNapIsNotGluedOntoTheNight() {
        val samples = listOf(
            state(ActivityState.ASLEEP, midnight - 5 * h), state(ActivityState.AWAKE, midnight - 5 * h + 40 * 60_000L), // 19:00 nap, 40 min
            state(ActivityState.ASLEEP, midnight - h), state(ActivityState.AWAKE, midnight + 3 * h),                   // 23:00–03:00
            state(ActivityState.ASLEEP, midnight + 3 * h + 20 * 60_000L), state(ActivityState.AWAKE, midnight + 7 * h), // 03:20–07:00
        )
        val night = assertNotNull(analyzer.analyze(day, samples, midnight + 9 * h))
        assertEquals(midnight - h, night.startMs)        // starts at 23:00, not at the nap
        assertEquals(midnight + 7 * h, night.endMs)
        assertEquals(460, night.asleepMinutes)           // 4h + 3h40
        assertEquals(20, night.awakeMinutes)             // only the short awakening inside the night
    }

    @Test fun aNightInProgressIsNotScoredYet() {
        val samples = listOf(state(ActivityState.ASLEEP, midnight - h))
        assertNull(analyzer.analyze(day, samples, midnight + 2 * h)) // 02:00, still asleep
    }

    @Test fun aNightTheWatchNeverSawEndStopsAtItsLastPulse() {
        // Asleep at 23:00, watch taken off at 06:00 (last pulse), never reported awake.
        val samples = listOf(state(ActivityState.ASLEEP, midnight - h)) + (0..7).map { hr(midnight - h + it * h) }
        val night = assertNotNull(analyzer.analyze(day, samples, midnight + 15 * h))
        assertEquals(midnight + 6 * h, night.endMs)
        assertEquals(420, night.asleepMinutes)
    }
}
