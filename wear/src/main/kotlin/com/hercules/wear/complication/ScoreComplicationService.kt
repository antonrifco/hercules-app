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
 * shows "--", never a made-up number.
 */
class ScoreComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, 72, "Good")

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val r = runCatching { applicationContext.graph.watchState.current().readiness }.getOrNull()
        return build(request.complicationType, r?.score, r?.band?.label)
    }

    private fun build(type: ComplicationType, score: Int?, band: String?): ComplicationData? {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val mark = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_herc_mark)).build()
        val text = PlainComplicationText.Builder(score?.toString() ?: "--").build()
        val said = PlainComplicationText.Builder(
            if (score != null) "Hercules score $score" + (band?.let { ", $it" } ?: "") else "Hercules, no score yet",
        ).build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(text, said).setMonochromaticImage(mark).setTapAction(open).build()
            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder((score ?: 0).toFloat(), 0f, 100f, said)
                    .setText(text).setMonochromaticImage(mark).setTapAction(open).build()
            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    PlainComplicationText.Builder(if (score != null) "$score · ${band ?: "Hercules"}" else "No score yet").build(), said,
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
            if (events.any { it.dataItem.uri.path == com.hercules.shared.WearProtocol.STATE_PATH }) refresh(applicationContext)
        }
    }

    companion object {
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
