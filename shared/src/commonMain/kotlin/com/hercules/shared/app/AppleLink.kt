package com.hercules.shared.app

import com.hercules.shared.model.ActivityState
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.sensors.SensorCodec
import com.hercules.shared.sensors.SensorConfig
import com.hercules.shared.sensors.SensorKind
import com.hercules.shared.sensors.SensorReport
import com.hercules.shared.sync.RemoteSink
import com.hercules.shared.sync.SyncBatch
import com.hercules.shared.sync.SyncResult
import com.hercules.shared.sync.WireCodec
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ------------------------------------------------------------------ HealthKit → samples

/**
 * Readings collected from HealthKit on the watch, in the shared sample format (source `healthkit`,
 * which counts toward daily totals). Sensor names are [SensorKind] names: "HEART_RATE", "STEPS",
 * "CALORIES" (kcal), "DISTANCE" (m), "FLOORS"; unknown names are ignored.
 */
class HealthBatch(private val deviceId: String) {
    private val samples = mutableListOf<Sample>()

    val count: Int get() = samples.size

    /** A quantity sample. Heart rate (bpm) is a point reading at [startMs]; the others are totals over the interval. */
    fun quantity(sensor: String, value: Double, startMs: Long, endMs: Long) {
        val type = when (sensor) {
            SensorKind.HEART_RATE.name -> MetricType.HEART_RATE
            SensorKind.STEPS.name -> MetricType.STEPS
            SensorKind.CALORIES.name -> MetricType.CALORIES
            SensorKind.DISTANCE.name -> MetricType.DISTANCE
            SensorKind.FLOORS.name -> MetricType.FLOORS
            else -> return
        }
        if (value <= 0.0 || endMs < startMs) return
        val end = if (type == MetricType.HEART_RATE) startMs else endMs
        samples += Sample.create(type, value, startMs, end, SampleSource.HEALTHKIT, deviceId)
    }

    /**
     * Time asleep (any HealthKit asleep stage: core, deep, REM, unspecified). Stored as an asleep
     * span; overlapping stages and sources are merged by the sleep analyzer.
     */
    fun asleep(startMs: Long, endMs: Long) {
        if (endMs - startMs < 60_000L) return
        samples += Sample.create(MetricType.ACTIVITY_STATE, ActivityState.ASLEEP, startMs, endMs, SampleSource.HEALTHKIT, deviceId)
    }

    internal fun samples(): List<Sample> = samples.toList()
}

// ------------------------------------------------------------------ watch → phone transport

/**
 * Implemented in Swift over WatchConnectivity (`WCSession.sendMessageData` with a reply handler).
 * Must call exactly one of the callbacks, from any thread.
 */
interface WatchTransport {
    fun send(batchJson: String, onReply: (String) -> Unit, onFailure: (String) -> Unit)
}

/** [RemoteSink] over a [WatchTransport]: the phone's reply is the ack (ids it stored). */
internal class TransportSink(private val transport: WatchTransport, private val timeoutMs: Long = 30_000) : RemoteSink {
    override suspend fun push(batch: SyncBatch): SyncResult {
        val reply = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String> { cont ->
                    transport.send(
                        WireCodec.encodeBatch(batch).decodeToString(),
                        { r -> if (cont.isActive) cont.resume(r) },
                        { e -> if (cont.isActive) cont.resumeWithException(TransportFailure(e)) },
                    )
                }
            } ?: return SyncResult.Rejected(retryable = true, reason = "phone did not answer")
        } catch (e: TransportFailure) {
            return SyncResult.Rejected(retryable = true, reason = "phone transfer failed: ${e.message}")
        }
        val ack = runCatching { WireCodec.decodeAck(reply.encodeToByteArray()) }.getOrNull()
            ?: return SyncResult.Rejected(retryable = true, reason = "unreadable reply from phone")
        return SyncResult.Accepted(ack.sampleIds, ack.sessionIds)
    }
}

private class TransportFailure(message: String) : Exception(message)

// ------------------------------------------------------------------ sensor choice

/** One tile of the phone's sensor grid. */
class SensorTile(val kind: SensorKind, val supported: Boolean, val on: Boolean, val live: Boolean, val caption: String)

class SensorPanel(val tiles: List<SensorTile>, val summary: String)

/**
 * Sensor on/off choices as JSON strings ([SensorCodec]), so Swift can keep them in UserDefaults and
 * WatchConnectivity contexts as-is. Either device may change the choice; the newest wins.
 */
object SensorSync {
    private fun config(json: String?): SensorConfig = json?.let { SensorCodec.decodeConfig(it) } ?: SensorConfig.DEFAULT
    private fun kinds(names: List<String>): Set<SensorKind> = names.mapNotNull { n -> SensorKind.entries.firstOrNull { it.name == n } }.toSet()

    fun defaultConfig(): String = SensorCodec.encode(SensorConfig.DEFAULT)

    fun isEnabled(configJson: String?, sensor: String): Boolean = config(configJson).enabled.any { it.name == sensor }

    /** Names of the enabled sensors, in display order. */
    fun enabled(configJson: String?): List<String> = config(configJson).enabled.sortedBy { it.ordinal }.map { it.name }

    fun updatedMs(configJson: String?): Long = config(configJson).updatedMs

    /** The choice after switching [sensor] on or off now. */
    fun toggle(configJson: String?, sensor: String, on: Boolean, nowMs: Long): String {
        val kind = SensorKind.entries.firstOrNull { it.name == sensor } ?: return SensorCodec.encode(config(configJson))
        return SensorCodec.encode(config(configJson).with(kind, on, nowMs))
    }

    /** The newer of two choices (ties keep [localJson]). */
    fun latest(localJson: String?, remoteJson: String?): String =
        SensorCodec.encode(SensorCodec.newest(config(localJson), remoteJson?.let { SensorCodec.decodeConfig(it) }))

    /** What the watch reports back: supported by the hardware, and actually being recorded. */
    fun report(supported: List<String>, active: List<String>, nowMs: Long): String =
        SensorCodec.encode(SensorReport(kinds(supported), kinds(active), nowMs))

    /** The phone's sensor grid, from its choice and the watch's last report. */
    fun panel(configJson: String?, reportJson: String?): SensorPanel {
        val c = config(configJson)
        val report = reportJson?.let { SensorCodec.decodeReport(it) }
        val tiles = SensorKind.entries.map { k ->
            val supported = report?.supported?.contains(k) ?: true
            val on = k in c.enabled && supported
            val live = report?.active?.contains(k) == true
            val caption = when {
                !supported -> "n/a"
                live -> "live · ${k.feeds}"
                on -> "on · ${k.feeds}"
                else -> "off"
            }
            SensorTile(k, supported, on, live, caption)
        }
        val total = SensorKind.entries.size
        val summary = when {
            report == null -> "${c.enabled.size}/$total on"
            report.active != (c.enabled intersect report.supported) -> "syncing"
            else -> "${report.active.size}/$total live"
        }
        return SensorPanel(tiles, summary)
    }
}
