package com.hercules.shared.model

import kotlin.math.roundToLong

/** Human-readable record labels/values, shared by every UI (Wear, phone, later iOS/watchOS). */
object RecordText {
    fun label(kind: RecordKind): String = when (kind) {
        RecordKind.MAX_DAILY_STEPS -> "Most steps"
        RecordKind.LOWEST_RESTING_HR -> "Lowest resting HR"
        RecordKind.MAX_SESSION_DISTANCE -> "Longest workout"
        RecordKind.BEST_HR_RECOVERY -> "Best HR recovery"
        RecordKind.LONGEST_STEP_STREAK -> "Step goal streak"
        RecordKind.HIGHEST_HRV_RMSSD -> "Highest HRV"
    }

    fun value(r: PersonalRecord): String = when (r.kind) {
        RecordKind.MAX_DAILY_STEPS -> "${r.value.roundToLong()} steps"
        RecordKind.LOWEST_RESTING_HR -> "${r.value.roundToLong()} bpm"
        RecordKind.MAX_SESSION_DISTANCE -> "${(r.value / 10).roundToLong() / 100.0} km"
        RecordKind.BEST_HR_RECOVERY -> "-${r.value.roundToLong()} bpm in 60s"
        RecordKind.LONGEST_STEP_STREAK -> "${r.value.roundToLong()} days"
        RecordKind.HIGHEST_HRV_RMSSD -> "${r.value.roundToLong()} ms"
    }
}
