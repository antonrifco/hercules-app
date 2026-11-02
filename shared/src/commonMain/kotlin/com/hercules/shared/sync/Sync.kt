package com.hercules.shared.sync

import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.model.SessionSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Anything that holds data waiting to be delivered upstream. */
interface SyncStore {
    suspend fun pendingSamples(limit: Long): List<Sample>
    suspend fun pendingSessions(limit: Long): List<SessionSummary>
    suspend fun markSynced(sampleIds: List<String>, sessionIds: List<String>)
}

data class SyncBatch(
    val batchId: String,
    val deviceId: String,
    val sentAtMs: Long,
    val samples: List<Sample>,
    val sessions: List<SessionSummary>,
)

sealed interface SyncResult {
    data class Accepted(val sampleIds: List<String>, val sessionIds: List<String>) : SyncResult
    data class Rejected(val retryable: Boolean, val reason: String) : SyncResult
}

/**
 * Where a batch goes next. Implementations:
 *  - watch: PhoneRemoteSink (Wearable Data Layer → phone app)
 *  - phone: NoopRemoteSink today, HttpRemoteSink once the backend exists
 *  - later: watch direct-to-server fallback
 */
fun interface RemoteSink {
    suspend fun push(batch: SyncBatch): SyncResult
}

object NoopRemoteSink : RemoteSink {
    override suspend fun push(batch: SyncBatch): SyncResult =
        SyncResult.Rejected(retryable = true, reason = "remote sync disabled")
}

data class SyncOutcome(val samplesSynced: Int, val sessionsSynced: Int, val stoppedReason: String?)

class SyncService(
    private val store: SyncStore,
    private val sink: RemoteSink,
    private val deviceId: String,
    private val sampleBatchSize: Long = 150,
    private val sessionBatchSize: Long = 20,
    private val clock: () -> Long,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    suspend fun syncOnce(maxBatches: Int = 200): SyncOutcome {
        var samples = 0
        var sessions = 0
        repeat(maxBatches) {
            val pendingSamples = store.pendingSamples(sampleBatchSize)
            val pendingSessions = store.pendingSessions(sessionBatchSize)
            if (pendingSamples.isEmpty() && pendingSessions.isEmpty()) {
                return SyncOutcome(samples, sessions, null)
            }
            val batch = SyncBatch(newId(), deviceId, clock(), pendingSamples, pendingSessions)
            when (val r = sink.push(batch)) {
                is SyncResult.Accepted -> {
                    if (r.sampleIds.isEmpty() && r.sessionIds.isEmpty()) {
                        return SyncOutcome(samples, sessions, "receiver accepted nothing")
                    }
                    store.markSynced(r.sampleIds, r.sessionIds)
                    samples += r.sampleIds.size
                    sessions += r.sessionIds.size
                }
                is SyncResult.Rejected -> return SyncOutcome(samples, sessions, r.reason)
            }
        }
        return SyncOutcome(samples, sessions, "batch limit reached")
    }
}

// ------------------------------------------------------------------ wire format
// Same JSON is used watch→phone today and phone→server later (docs/contracts/sample-batch.v1.schema.json).

@Serializable
data class SampleDto(
    val id: String,
    val type: String,
    val value: Double,
    val unit: String,
    val start: String,
    val end: String,
    val source: String,
    val deviceId: String,
    val sessionId: String? = null,
)

@Serializable
data class SessionDto(
    val id: String,
    val kind: String,
    val deviceId: String,
    val dayKey: String,
    val start: String,
    val end: String,
    val distanceMeters: Double,
    val avgHr: Double? = null,
    val maxHr: Long? = null,
    val hrRecovery60s: Long? = null,
)

@Serializable
data class SyncBatchDto(
    val schemaVersion: Int = SCHEMA_VERSION,
    val batchId: String,
    val deviceId: String,
    val sentAt: String,
    val samples: List<SampleDto>,
    val sessions: List<SessionDto> = emptyList(),
)

@Serializable
data class SyncAckDto(val sampleIds: List<String>, val sessionIds: List<String>)

const val SCHEMA_VERSION = 1

object WireCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private fun iso(ms: Long) = Instant.fromEpochMilliseconds(ms).toString()
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    fun toDto(b: SyncBatch) = SyncBatchDto(
        batchId = b.batchId,
        deviceId = b.deviceId,
        sentAt = iso(b.sentAtMs),
        samples = b.samples.map {
            SampleDto(it.id, it.type.wire, it.value, it.type.unit, iso(it.startMs), iso(it.endMs), it.source.wire, it.deviceId, it.sessionId)
        },
        sessions = b.sessions.map {
            SessionDto(it.id, it.kind, it.deviceId, it.dayKey, iso(it.startMs), iso(it.endMs), it.distanceMeters, it.avgHr, it.maxHr, it.hrRecovery60s)
        },
    )

    /** Unknown metric types/sources (newer sender) are dropped, not fatal. */
    fun fromDto(d: SyncBatchDto) = SyncBatch(
        batchId = d.batchId,
        deviceId = d.deviceId,
        sentAtMs = ms(d.sentAt),
        samples = d.samples.mapNotNull {
            runCatching {
                Sample(it.id, MetricType.fromWire(it.type), it.value, ms(it.start), ms(it.end), SampleSource.fromWire(it.source), it.deviceId, it.sessionId)
            }.getOrNull()
        },
        sessions = d.sessions.map {
            SessionSummary(it.id, it.kind, it.deviceId, it.dayKey, ms(it.start), ms(it.end), it.distanceMeters, it.avgHr, it.maxHr, it.hrRecovery60s)
        },
    )

    fun encodeBatch(b: SyncBatch): ByteArray = json.encodeToString(SyncBatchDto.serializer(), toDto(b)).encodeToByteArray()
    fun decodeBatch(bytes: ByteArray): SyncBatch = fromDto(json.decodeFromString(SyncBatchDto.serializer(), bytes.decodeToString()))
    /**
     * Decodes a batch and builds the ack for EVERY id in it, including samples this
     * (older) receiver can't understand, so the sender never retries them forever.
     */
    fun decodeBatchWithAck(bytes: ByteArray): Pair<SyncBatch, SyncAckDto> {
        val dto = json.decodeFromString(SyncBatchDto.serializer(), bytes.decodeToString())
        return fromDto(dto) to SyncAckDto(dto.samples.map { it.id }, dto.sessions.map { it.id })
    }

    fun encodeAck(a: SyncAckDto): ByteArray = json.encodeToString(SyncAckDto.serializer(), a).encodeToByteArray()
    fun decodeAck(bytes: ByteArray): SyncAckDto = json.decodeFromString(SyncAckDto.serializer(), bytes.decodeToString())
}
