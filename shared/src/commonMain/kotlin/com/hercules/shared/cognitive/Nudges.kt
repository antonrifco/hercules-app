package com.hercules.shared.cognitive

import com.hercules.shared.insights.DayKeys
import com.hercules.shared.readiness.PeakWindowPrediction
import com.hercules.shared.readiness.PrepPhase
import kotlinx.serialization.Serializable

/** Where a notification should land when tapped. Phone and watch map these to their own screens. */
enum class NudgeTarget { TODAY, ASSESS, EVENT, REFLECT, SLEEP }

enum class NudgeKind { MORNING, ASSESS, EVENT_EVE, EVENT_SOON, FOCUS, EVENING, REFLECT }

data class Nudge(
    val id: String,
    val kind: NudgeKind,
    val atMs: Long,
    val title: String,
    val text: String,
    val target: NudgeTarget,
    val eventId: String? = null,
)

/** Which reminders the user wants. Focus reminders are off by default to avoid noise. */
@Serializable
data class NudgePrefs(
    val morning: Boolean = true,
    val event: Boolean = true,
    val evening: Boolean = true,
    val reflect: Boolean = true,
    val focus: Boolean = false,
    /** A reminder when a Beat the Clock window opens. */
    val assess: Boolean = true,
)

/** Who delivers a reminder so the wrist and the pocket never both buzz. */
object NudgeRouting {
    const val WATCH_WORN_MS = 60 * 60_000L
    const val PHONE_SEES_WORN_MS = 75 * 60_000L

    /** The watch posts only while it is on your wrist (recent heart-rate reading of its own). */
    fun watchShouldPost(lastHrMs: Long?, nowMs: Long) = lastHrMs != null && nowMs - lastHrMs < WATCH_WORN_MS

    /** The phone posts when the watch has been quiet for longer (its data arrives up to ~15 min late). */
    fun phoneShouldPost(lastWatchHrMs: Long?, nowMs: Long) = lastWatchHrMs == null || nowMs - lastWatchHrMs >= PHONE_SEES_WORN_MS
}

/**
 * Decides which reminders are due right now. Pure: the same inputs give the same answer on phone and
 * watch. Every reminder is tied to something real (your score, an event, tonight's bedtime), never generic.
 */
class NudgePlanner(private val dayKeys: DayKeys, private val coach: Coach) {

    companion object {
        /** A reminder stays valid this long after its time, so a late phone wake-up still delivers it. */
        const val VALID_MS = 90 * 60_000L
        const val ASSESS_AFTER_WAKE_MS = 2 * 60 * 60_000L
    }

    fun due(
        nowMs: Long,
        readiness: Readiness?,
        sleep: SleepNight?,
        events: List<EventStatus>,
        reflectedIds: Set<String>,
        baselineWakeMinute: Int?,
        prefs: NudgePrefs,
        alreadySent: Set<String>,
        wakeMs: Long?,
        playsToday: Int,
        peak: PeakWindowPrediction?,
    ): List<Nudge> {
        val today = dayKeys.of(nowMs)
        val out = mutableListOf<Nudge>()
        val todayPeak = coach.peakToday(peak, wakeMs, nowMs)?.first

        if (prefs.morning && readiness != null) {
            out += Nudge(
                "morning:$today", NudgeKind.MORNING, readiness.wakeMs + 45 * MIN_MS,
                "Your cognitive state: ${readiness.score} · ${readiness.band.label}",
                todayPeak?.let { "Peak window ${dayKeys.clock(it.startMs)}–${dayKeys.clock(it.endMs)}." } ?: coach.headline(readiness.band),
                NudgeTarget.TODAY,
            )
        }

        // Optional and any time, so at most one gentle nudge a day, and none once you've played.
        if (prefs.assess && wakeMs != null && playsToday == 0) {
            out += Nudge(
                "assess:$today", NudgeKind.ASSESS, wakeMs + ASSESS_AFTER_WAKE_MS,
                "Beat the Clock?",
                "A 2-minute game that shows how sharp you are today.",
                NudgeTarget.ASSESS,
            )
        }

        if (prefs.event) {
            for (e in events.filter { it.phase != PrepPhase.DONE }) {
                val dayBefore = dayKeys.plusDays(dayKeys.of(e.event.startMs), -1)
                if (dayBefore == today) {
                    val left = e.todayTasks.count { it.id !in e.doneTaskIds }
                    out += Nudge(
                        "eve:${e.event.id}", NudgeKind.EVENT_EVE, dayKeys.startOf(today) + 18 * HOUR_MS,
                        "Tomorrow: ${e.event.title}",
                        if (left > 0) "$left prep steps to go. Then get a strong night's sleep." else "Prep done. Now win the night: sleep well.",
                        NudgeTarget.EVENT, e.event.id,
                    )
                }
                if (e.daysUntil == 0) {
                    out += Nudge(
                        "soon:${e.event.id}", NudgeKind.EVENT_SOON, e.event.startMs - 2 * HOUR_MS,
                        "${e.event.title} at ${dayKeys.clock(e.event.startMs)}",
                        "${e.preparation.status.label} · ${e.preparation.peak.label}",
                        NudgeTarget.EVENT, e.event.id,
                    )
                }
            }
        }

        if (prefs.focus && todayPeak != null) {
            out += Nudge("focus:$today", NudgeKind.FOCUS, todayPeak.startMs - 10 * MIN_MS, "Peak window opens at ${dayKeys.clock(todayPeak.startMs)}", "Line up your hardest task.", NudgeTarget.TODAY)
        }

        if (prefs.evening) {
            val advice = coach.sleepAdvice(nowMs, readiness, sleep, baselineWakeMinute, events)
            out += Nudge(
                "evening:$today", NudgeKind.EVENING, advice.bedtimeMs - 60 * MIN_MS,
                "Lights out by ${dayKeys.clock(advice.bedtimeMs)}",
                listOfNotNull(advice.tomorrowNote, advice.shiftEarlierMinutes?.let { "Aim $it min earlier than last night." }).joinToString(" ").ifEmpty { "A strong night powers tomorrow's score." },
                NudgeTarget.SLEEP,
            )
        }

        if (prefs.reflect) {
            for (e in events) {
                if (e.event.id !in reflectedIds) {
                    out += Nudge("reflect:${e.event.id}", NudgeKind.REFLECT, e.event.startMs + 90 * MIN_MS, "How did ${e.event.title} go?", "Tell Herc in 5 seconds.", NudgeTarget.REFLECT, e.event.id)
                }
            }
        }

        return out.filter { it.id !in alreadySent && nowMs >= it.atMs && nowMs - it.atMs <= VALID_MS }.sortedBy { it.atMs }
    }
}
