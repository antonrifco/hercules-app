package com.hercules.shared

import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.model.SessionSummary
import com.hercules.shared.sync.NoopRemoteSink
import com.hercules.shared.sync.RemoteSink
import com.hercules.shared.sync.SyncBatch
import com.hercules.shared.sync.SyncResult
import com.hercules.shared.sync.SyncService
import com.hercules.shared.sync.SyncStore
import com.hercules.shared.sync.WireCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeStore(val samples: List<Sample>, val sessions: List<SessionSummary> = emptyList()) : SyncStore {
    val syncedSamples = mutableSetOf<String>()
    val syncedSessions = mutableSetOf<String>()
    override suspend fun pendingSamples(limit: Long) = samples.filter { it.id !in syncedSamples }.take(limit.toInt())
    override suspend fun pendingSessions(limit: Long) = sessions.filter { it.id !in syncedSessions }.take(limit.toInt())
    override suspend fun markSynced(sampleIds: List<String>, sessionIds: List<String>) {
        syncedSamples += sampleIds
        syncedSessions += sessionIds
    }
}

private class AcceptAll : RemoteSink {
    val batches = mutableListOf<SyncBatch>()
    override suspend fun push(batch: SyncBatch): SyncResult {
        batches += batch
        return SyncResult.Accepted(batch.samples.map { it.id }, batch.sessions.map { it.id })
    }
}

private fun samples(n: Int) = (0 until n).map {
    Sample.create(MetricType.STEPS, 10.0, 1_790_000_000_000 + it * 1000L, source = SampleSource.PASSIVE, deviceId = "dev")
}

private val session = SessionSummary("w1", "run", "dev", "2026-10-02", 1_790_000_000_000, 1_790_001_800_000, 5000.0, 140.0, 170, 30)

class SyncServiceTest {
    @Test fun syncsEverythingInBatches() = runTest {
        val store = FakeStore(samples(5), listOf(session))
        val sink = AcceptAll()
        val out = SyncService(store, sink, "dev", sampleBatchSize = 2, clock = { 0L }).syncOnce()
        assertEquals(5, out.samplesSynced)
        assertEquals(1, out.sessionsSynced)
        assertEquals(null, out.stoppedReason)
        assertEquals(listOf(2, 2, 1), sink.batches.map { it.samples.size })
    }

    @Test fun noopKeepsEverythingPending() = runTest {
        val store = FakeStore(samples(3))
        val out = SyncService(store, NoopRemoteSink, "dev", clock = { 0L }).syncOnce()
        assertEquals(0, out.samplesSynced)
        assertEquals("remote sync disabled", out.stoppedReason)
        assertTrue(store.syncedSamples.isEmpty())
    }
}

class WireCodecTest {
    @Test fun batchRoundTripsAndUsesContractFields() {
        val batch = SyncBatch("b1", "dev", 1_790_000_000_000, samples(2), listOf(session))
        val bytes = WireCodec.encodeBatch(batch)
        val json = bytes.decodeToString()
        assertTrue("\"schemaVersion\":1" in json)
        assertTrue("\"type\":\"steps\"" in json)
        assertTrue("\"unit\":\"count\"" in json)
        assertTrue("\"start\":\"2026-09-21T" in json) // ISO-8601 UTC
        assertEquals(batch, WireCodec.decodeBatch(bytes))
    }

    @Test fun unknownMetricFromNewerSenderIsDropped() {
        val json = """
            {"schemaVersion":1,"batchId":"b","deviceId":"d","sentAt":"2026-10-02T00:00:00Z",
             "samples":[
               {"id":"x","type":"mood","value":1,"unit":"?","start":"2026-10-02T00:00:00Z","end":"2026-10-02T00:00:00Z","source":"passive","deviceId":"d"},
               {"id":"y","type":"steps","value":5,"unit":"count","start":"2026-10-02T00:00:00Z","end":"2026-10-02T00:01:00Z","source":"passive","deviceId":"d","extra":true}
             ]}
        """.trimIndent()
        assertEquals(listOf("y"), WireCodec.decodeBatch(json.encodeToByteArray()).samples.map { it.id })
    }
}
