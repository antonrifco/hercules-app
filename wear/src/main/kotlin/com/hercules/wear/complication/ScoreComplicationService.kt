package com.hercules.wear.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.hercules.wear.R
import com.hercules.wear.graph
import com.hercules.wear.ui.MainActivity

/**
 * Today's score on the watch face. Herc is drawn cropped tight (ic_herc_mark / ic_herc_color) so he fills the slot;
 * the watch face decides the slot's size., in whichever shape the slot offers: the number with Herc's mark, a longer line
 * ("67 · Fair"), or just Herc as a launcher icon. No ring form: one watch face printed the scale's top (100) for it. Tapping any of them opens Hercules. Without a score it
 * shows "-", never a made-up number (never 0).
 */
class ScoreComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, 72, "Good")

    /**
     * Answers from the last saved score first: in battery saver the system gives a data source very little time, and
     * working out the score from scratch can run out of it, which leaves the slot blank. A fresh score is worked
     * out only when it fits in [COMPUTE_BUDGET_MS]; otherwise the saved one (from today) is shown.
     */
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val fresh = kotlinx.coroutines.withTimeoutOrNull(COMPUTE_BUDGET_MS) {
            runCatching { applicationContext.graph.watchState.current().readiness }.getOrNull()
        }
        if (fresh != null) save(applicationContext, fresh.score, fresh.band.label)
        val (score, band) = fresh?.let { it.score to it.band.label } ?: cached(applicationContext)
        return build(request.complicationType, score, band) ?: NoDataComplicationData()
    }

    private fun build(type: ComplicationType, score: Int?, band: String?): ComplicationData? {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mark = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_herc_mark)).build()
        val text = PlainComplicationText.Builder(score?.toString() ?: NO_SCORE).build()
        val said = PlainComplicationText.Builder(
            if (score != null) "Hercules score $score" + (band?.let { ", $it" } ?: "") else "Hercules, no score yet",
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, said).setMonochromaticImage(mark).setTapAction(open).build()
            // A ring needs a number, and with none some faces print 0 as if it were the score. Without a score the
            // slot gets "no data" with a placeholder ring instead, so it shows "-", never 0.
            ComplicationType.RANGED_VALUE ->
                if (score == null) NoDataComplicationData(
                    RangedValueComplicationData.Builder(RangedValueComplicationData.PLACEHOLDER, 0f, 100f, said)
                        .setText(text).setMonochromaticImage(mark).setTapAction(open).build(),
                )
                else RangedValueComplicationData.Builder(score.toFloat(), 0f, 100f, said)
                    .setText(text).setMonochromaticImage(mark).setTapAction(open).build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    PlainComplicationText.Builder(if (score != null) "$score · ${band ?: "Hercules"}" else "$NO_SCORE · no score yet").build(), said,
                ).setTitle(PlainComplicationText.Builder("Hercules").build()).setMonochromaticImage(mark).setTapAction(open).build()
            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(Icon.createWithResource(this, R.drawable.ic_herc_color), SmallImageType.ICON).build(), said,
                ).setTapAction(open).build()
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(mark, said).setTapAction(open).build()
            else -> null
        }
    }

    /** The phone recalculated and sent a new state: put the new score on the watch face straight away. */
    class StateListener : com.google.android.gms.wearable.WearableListenerService() {
        override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
            val item = events.firstOrNull { it.dataItem.uri.path == com.hercules.shared.WearProtocol.STATE_PATH }?.dataItem ?: return
            // Save the phone's score straight away: cheap, and ready even if the watch face asks during battery saver.
            runCatching {
                val json = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).dataMap.getString(com.hercules.shared.WearProtocol.STATE_KEY)
                val r = json?.let { applicationContext.graph.cognitive.decodeWatchState(it) }?.readiness
                if (r != null) save(applicationContext, r.score, r.band.label)
            }
            refresh(applicationContext)
        }
    }

    companion object {
        private const val COMPUTE_BUDGET_MS = 2_000L
        /** What the slot shows before today has a score. */
        private const val NO_SCORE = "-"
        private const val PREFS = "complication"

        /** Remembers today's score for the watch face, so it never has to wait for a calculation. */
        fun save(ctx: Context, score: Int?, band: String?) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("score", score ?: -1).putString("band", band)
                .putString("day", java.time.LocalDate.now().toString()).apply()
        }

        /** The saved score, only if it is from today (yesterday's number would be wrong by morning). */
        fun cached(ctx: Context): Pair<Int?, String?> {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getString("day", null) != java.time.LocalDate.now().toString()) return null to null
            return p.getInt("score", -1).takeIf { it >= 0 } to p.getString("band", null)
        }

        /** Ask the watch face to redraw the score now (after a sync, a game, or a new score from the phone). */
        fun refresh(ctx: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester
                    .create(ctx, ComponentName(ctx, ScoreComplicationService::class.java))
                    .requestUpdateAll()
            }
        }
    }
}
