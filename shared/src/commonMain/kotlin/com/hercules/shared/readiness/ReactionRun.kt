package com.hercules.shared.readiness

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** What the screen shows during a Reaction Test. */
enum class TrialPhase { WAIT, SHOW, TOO_SOON, REACTED }

/**
 * The Reaction Test loop (code name BeatTheClock), shared by phone and watch so both run exactly the same rules:
 * [BeatTheClock.TRIALS] trials. The screen sends every tap's touch-down time to [taps]; all times use one monotonic
 * clock, [nowMs]. [onPhase] gets the phase, the reaction time once there is one, and the trial number (1-based).
 */
object ReactionRun {
    /** How long the reaction time (or "too soon") stays on screen before the next wait, as in PVT-B. */
    const val FEEDBACK_MS = 1_000L

    suspend fun play(
        sessionId: String,
        taps: Channel<Long>,
        nowMs: () -> Long,
        onPhase: (TrialPhase, Long?, Int) -> Unit,
        /** Returns once Herc is actually on screen, as [nowMs]. */
        onScreen: suspend () -> Long = { nowMs() },
        random: Random = Random.Default,
    ): List<CognitiveRound> {
        val out = mutableListOf<CognitiveRound>()
        while (out.size < BeatTheClock.TRIALS) {
            val i = out.size
            val n = i + 1
            val id = "$sessionId:$i"
            val wait = BeatTheClock.nextDelay(random)
            while (taps.tryReceive().isSuccess) Unit // taps during the feedback don't count either way
            onPhase(TrialPhase.WAIT, null, n)
            if (withTimeoutOrNull(wait) { taps.receive() } != null) {
                out += BeatTheClock.falseStart(id, sessionId, i, wait)
                onPhase(TrialPhase.TOO_SOON, null, n)
                delay(FEEDBACK_MS)
                continue
            }
            onPhase(TrialPhase.SHOW, null, n)
            val shownAt = onScreen()
            val tap = withTimeoutOrNull(BeatTheClock.TIMEOUT_MS) { taps.receive() }
            val r = BeatTheClock.reaction(id, sessionId, i, wait, (tap ?: (shownAt + BeatTheClock.TIMEOUT_MS)) - shownAt)
            out += r
            onPhase(if (r.validity == RoundValidity.VALID) TrialPhase.REACTED else TrialPhase.TOO_SOON, r.reactionMs, n)
            delay(FEEDBACK_MS)
        }
        return out
    }
}
