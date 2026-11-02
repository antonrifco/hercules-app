package com.hercules.shared.cognitive

import com.hercules.shared.insights.DayKeys

/** Where a notification should land when tapped. Phone and watch map these to their own screens. */
enum class NudgeTarget { TODAY, CHECK_IN, EVENT, REFLECT, SLEEP }

enum class NudgeKind { MORNING, EVENT_EVE, EVENT_SOON, FOCUS, EVENING, REFLECT }

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
data class NudgePrefs(
    val morning: Boolean = true,
    val event: Boolean = true,
    val evening: Boolean = true,
    val reflect: Boolean = true,
    val focus: Boolean = false,
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
    }

    fun due(
        nowMs: Long,
        readiness: Readiness?,
        checkIn: CheckIn?,
        sleep: SleepNight?,
        events: List<EventStatus>,
        reflectedIds: Set<String>,
        baselineWakeMinute: Int?,
        prefs: NudgePrefs,
        alreadySent: Set<String>,
    ): List<Nudge> {
        val today = dayKeys.of(nowMs)
        val out = mutableListOf<Nudge>()

        if (prefs.morning && readiness != null) {
            val f = coach.focus(readiness, readiness.wakeMs)
            val level = readiness.band.level.label
            out += Nudge(
                "morning:$today", NudgeKind.MORNING, readiness.wakeMs + 45 * MIN_MS,
                "Readiness ${readiness.score} · $level",
                listOfNotNull(f?.first?.let { "Best focus ${dayKeys.clock(it.startMs)}–${dayKeys.clock(it.endMs)}." }, if (checkIn == null) "Quick check-in?" else null).joinToString(" "),
                if (checkIn == null) NudgeTarget.CHECK_IN else NudgeTarget.TODAY,
            )
        }

        if (prefs.event) {
            for (e in events.filter { it.phase != PrepPhase.DONE }) {
                val dayBefore = dayKeys.plusDays(dayKeys.of(e.event.startMs), -1)
                if (dayBefore == today || e.daysUntil == 1) {
                    val left = e.todayTasks.size - e.doneTaskIds.size
                    out += Nudge(
                        "eve:${e.event.id}", NudgeKind.EVENT_EVE, dayKeys.startOf(today) + 18 * HOUR_MS,
                        "Tomorrow: ${e.event.title}",
                        if (left > 0) "$left prep steps left. Sleep well tonight." else "Prep done. Sleep well tonight.",
                        NudgeTarget.EVENT, e.event.id,
                    )
                }
                if (e.daysUntil == 0) {
                    val expected = e.advice?.expectedScore
                    out += Nudge(
                        "soon:${e.event.id}", NudgeKind.EVENT_SOON, e.event.startMs - 2 * HOUR_MS,
                        "${e.event.title} at ${dayKeys.clock(e.event.startMs)}",
                        listOfNotNull(e.advice?.verdict?.label, expected?.let { "Expected readiness $it." }).joinToString(" · ").ifEmpty { "Time to get ready." },
                        NudgeTarget.EVENT, e.event.id,
                    )
                }
            }
        }

        if (prefs.focus && readiness != null) {
            readiness.windows.firstOrNull { it.kind == WindowKind.PEAK }?.let {
                out += Nudge("focus:$today", NudgeKind.FOCUS, it.startMs - 10 * MIN_MS, "Peak focus starts at ${dayKeys.clock(it.startMs)}", "Save your hardest task for now.", NudgeTarget.TODAY)
            }
        }

        if (prefs.evening) {
            val advice = coach.sleepAdvice(nowMs, readiness, sleep, baselineWakeMinute, events)
            // Bedtime is tonight's: if it already passed after midnight math, it belongs to the next calendar day.
            out += Nudge(
                "evening:$today", NudgeKind.EVENING, advice.bedtimeMs - 60 * MIN_MS,
                "Be in bed by ${dayKeys.clock(advice.bedtimeMs)}",
                listOfNotNull(advice.tomorrowNote, advice.shiftEarlierMinutes?.let { "Try sleeping $it min earlier." }).joinToString(" ").ifEmpty { "A good night lifts tomorrow's score." },
                NudgeTarget.SLEEP,
            )
        }

        if (prefs.reflect) {
            for (e in events) {
                if (e.event.id !in reflectedIds) {
                    out += Nudge("reflect:${e.event.id}", NudgeKind.REFLECT, e.event.startMs + 90 * MIN_MS, "How did ${e.event.title} go?", "Takes 5 seconds.", NudgeTarget.REFLECT, e.event.id)
                }
            }
        }

        return out.filter { it.id !in alreadySent && nowMs >= it.atMs && nowMs - it.atMs <= VALID_MS }.sortedBy { it.atMs }
    }
}
