package com.hercules.shared

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.hercules.shared.app.Catalog
import com.hercules.shared.app.HealthBatch
import com.hercules.shared.app.HerculesCore
import com.hercules.shared.app.SensorSync
import com.hercules.shared.app.TransportSink
import com.hercules.shared.app.WatchTransport
import com.hercules.shared.app.WatchText
import com.hercules.shared.cognitive.EventType
import com.hercules.shared.cognitive.Importance
import com.hercules.shared.cognitive.WindowKind
import com.hercules.shared.db.HerculesDatabase
import com.hercules.shared.insights.DayKeys
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.sensors.SensorKind
import com.hercules.shared.sync.SyncBatch
import com.hercules.shared.sync.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The API the iOS and watchOS apps call, exercised end to end on the JVM. */
class HerculesCoreTest {
    private val dk = DayKeys(TimeZone.UTC)
    private val now = dk.startOf("2026-11-10") + 9 * H // Tuesday 09:00

    private fun core(deviceId: String, clock: () -> Long = { now }): HerculesCore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HerculesDatabase.Schema.create(driver)
        return HerculesCore(HerculesDatabase(driver), deviceId, clock, dk, Dispatchers.Unconfined)
    }

    /** Routes watch batches straight into a phone core, like WatchConnectivity would. */
    private class Loopback(private val phone: HerculesCore) : WatchTransport {
        var batches = 0
        override fun send(batchJson: String, onReply: (String) -> Unit, onFailure: (String) -> Unit) {
            batches++
            onReply(runBlocking { phone.receiveFromWatch(batchJson) })
        }
    }

    /** Ten nights of HealthKit-style data as the watch would collect it: sleep stages, heart rate, steps. */
    private fun healthKitHistory(deviceId: String): HealthBatch {
        val b = HealthBatch(deviceId)
        for (offset in -10..0) {
            val midnight = dk.startOf(dk.plusDays("2026-11-10", offset))
            val bed = midnight - 60 * M
            val wake = midnight + 7 * H
            b.asleep(bed, midnight + 3 * H)                // core + deep
            b.asleep(midnight + 3 * H + 10 * M, wake)      // after a short wake-up
            var t = bed
            while (t < wake) { b.quantity("HEART_RATE", 55.0, t, t); t += 20 * M }
            if (offset < 0) for (h in 8..20) b.quantity("STEPS", 600.0, midnight + h * H, midnight + h * H + 30 * M)
        }
        return b
    }

    @Test fun phoneSnapshotAfterDemoHasEverythingTodayShows() = runTest {
        val phone = core("phone")
        phone.loadDemo()
        val s = phone.phoneSnapshot()
        assertEquals("2026-11-10", s.today)
        assertNotNull(s.readiness)
        assertEquals(WindowKind.PEAK, s.peak?.kind)
        assertEquals(310, s.sleep?.asleepMinutes)
        assertEquals(7, s.sleepWeek.size)
        assertEquals("Tue", s.sleepWeek.last().label)
        assertTrue(s.sleepWeek.all { it.present })
        assertEquals(14, s.trend.size)
        assertTrue(s.trendDays >= 10)
        assertEquals("", s.trend[0].label)
        assertEquals("29", s.trend[1].label) // 2026-10-29
        assertEquals(setOf("demo-interview", "demo-pitch"), s.events.map { it.event.id }.toSet())
        assertEquals("demo-pitch", s.nextEvent?.event?.id) // later today
        assertEquals(listOf("demo-pitch"), s.todayEvents.map { it.event.id })

        val data = phone.dataSnapshot()
        assertTrue((data.today?.steps ?: 0) > 0)
        assertTrue(data.records.any { it.label == "Most steps" })
        assertTrue(data.pending > 0) // no server yet: everything stays queued
    }

    @Test fun healthKitBatchReachesThePhoneWithAckAndBuildsHistory() = runTest {
        val watch = core("apple-watch")
        val phone = core("iphone")
        watch.ingest(healthKitHistory("apple-watch"))

        // The watch alone already has nights and a score from its own data.
        val local = watch.watchHome(null, 0, null)
        assertNotNull(local.readiness)
        assertEquals("7h 50m", local.sleepText)
        assertEquals(55, local.heartRate)

        val link = Loopback(phone)
        val report = watch.syncToPhone(link)
        assertNull(report.problem)
        assertTrue(report.samples > 100, "samples=${report.samples}")
        assertTrue(link.batches >= 2) // 150 per batch
        assertEquals(0L, watch.repo.pendingSampleCount())

        // History arrived in one go; the phone rebuilt every night, so the baseline is complete.
        assertEquals(11, phone.cognitive.store.sleepRange("2026-10-31", "2026-11-10").size)
        val r = assertNotNull(phone.phoneSnapshot().readiness)
        assertTrue(r.baselineDays >= 7, "baselineDays=${r.baselineDays}")

        // Retrying the same data is harmless (deterministic ids).
        assertEquals(0, watch.syncToPhone(link).samples)
    }

    @Test fun failedTransportKeepsDataQueuedAndExplainsWhy() = runTest {
        val watch = core("apple-watch")
        watch.ingest(healthKitHistory("apple-watch"))
        val pending = watch.repo.pendingSampleCount()
        val down = object : WatchTransport {
            override fun send(batchJson: String, onReply: (String) -> Unit, onFailure: (String) -> Unit) = onFailure("not reachable")
        }
        val report = watch.syncToPhone(down)
        assertEquals("phone transfer failed: not reachable", report.problem)
        assertEquals(pending, watch.repo.pendingSampleCount())

        val home = watch.watchHome(null, 0, report.problem)
        assertEquals("Phone not reachable · $pending waiting", home.sync.text)
        assertTrue(home.sync.warning)
    }

    @Test fun silentTransportTimesOutInsteadOfHanging() = runTest {
        val silent = object : WatchTransport {
            override fun send(batchJson: String, onReply: (String) -> Unit, onFailure: (String) -> Unit) = Unit
        }
        val batch = SyncBatch("b", "w", now, listOf(Sample.create(MetricType.STEPS, 5.0, now, now + M, SampleSource.HEALTHKIT, "w")), emptyList())
        val r = TransportSink(silent, timeoutMs = 50).push(batch)
        assertEquals(SyncResult.Rejected(retryable = true, reason = "phone did not answer"), r)
    }

    @Test fun watchUsesFreshPhoneStateElseFallsBackToItsOwn() = runTest {
        val phone = core("iphone")
        phone.loadDemo()
        val json = phone.watchStateJson()

        val fresh = core("apple-watch").watchState(json)
        assertEquals(phone.phoneSnapshot().readiness?.score, fresh.readiness?.score)
        assertEquals(setOf("demo-interview", "demo-pitch"), fresh.upcoming.map { it.event.id }.toSet())

        // Seven hours later (16:00) the phone's state is stale: the watch keeps today's score but drops the
        // pitch that started at 11:00.
        val later = core("apple-watch") { now + 7 * H }
        val stale = later.watchState(json)
        assertNotNull(stale.readiness)
        assertNull(stale.nextEvent)
        assertEquals(listOf("demo-interview"), stale.upcoming.map { it.event.id })
    }

    @Test fun watchHomeShowsScoreTrackingAndMoments() = runTest {
        val watch = core("apple-watch")
        watch.loadDemo()
        val home = watch.watchHome(null, 0, null)
        assertNotNull(home.readiness)
        assertNotNull(home.peak)
        assertEquals("5h 10m", home.sleepText)
        assertTrue(home.heartRate > 0)
        assertTrue(home.steps > 0)
        val pitch = home.moments.first { it.id == "demo-pitch" }
        assertEquals("NOW", pitch.tag)
        assertTrue(pitch.isToday)
        assertNotNull(pitch.verdict)
        assertEquals(home.readiness?.score, pitch.forecast)
        val interview = home.moments.first { it.id == "demo-interview" }
        assertEquals("D-6", interview.tag)
        assertTrue(interview.forecast in 1..100)
        assertEquals("${home.pending} readings to send", home.sync.text)
        assertEquals("Sent to phone · 5m ago", watch.watchHome(null, now - 5 * M, null).sync.text)
    }

    @Test fun sleepCorrectionDraftsAndSaves() = runTest {
        val phone = core("iphone")
        val t = phone.sleepTimes(null)
        assertEquals(listOf(23, 0, 7, 0), listOf(t.bedHour, t.bedMinute, t.wakeHour, t.wakeMinute))

        val ok = phone.sleepDraft(23, 30, 7, 0)
        assertTrue(ok.valid)
        assertEquals(450, ok.minutes)
        assertEquals("7h 30m in bed", ok.message)
        assertEquals(dk.startOf("2026-11-09") + 23 * H + 30 * M, ok.bedMs) // the evening before

        val future = phone.sleepDraft(23, 0, 10, 0) // it's only 09:00
        assertFalse(future.valid)
        assertEquals("Wake time is still ahead", future.message)
        assertEquals("Check the times", phone.sleepDraft(3, 0, 3, 30).message)

        phone.correctSleep(ok)
        val night = assertNotNull(phone.cognitive.store.sleep("2026-11-10"))
        assertTrue(night.corrected)
        assertEquals(450, night.asleepMinutes)
        assertEquals(listOf(23, 30, 7, 0), phone.sleepTimes(night).let { listOf(it.bedHour, it.bedMinute, it.wakeHour, it.wakeMinute) })
    }

    @Test fun eventDraftAdviceAndLifecycle() = runTest {
        val phone = core("iphone")
        assertEquals(8, phone.dayChoices().size)
        assertFalse(phone.eventDraft("", 1, 10, 0).valid)
        assertTrue(phone.eventDraft("Pitch", 0, 8, 0).passed)

        val draft = phone.eventDraft("Interview at Acme", 2, 10, 0)
        assertTrue(draft.valid)
        val hint = assertNotNull(draft.hint)
        assertEquals(WindowKind.PEAK, hint.window)
        assertTrue(hint.good)
        assertTrue("10:00" in hint.text)
        assertFalse(assertNotNull(phone.eventDraft("Board call", 2, 14, 30).hint).good) // afternoon dip

        phone.addEvent("Interview at Acme", Importance.CRITICAL, 2, 10, 0)
        phone.addEvent("", Importance.HIGH, 2, 10, 0) // invalid: ignored
        val ev = phone.phoneSnapshot().events.single()
        assertEquals(EventType.INTERVIEW, ev.event.type)
        assertEquals(2, ev.daysUntil)

        val task = ev.todayTasks.first()
        phone.setTaskDone(ev.event.id, task.id, true)
        assertEquals(setOf(task.id), phone.phoneSnapshot().events.single().doneTaskIds)

        phone.deleteEvent(ev.event.id)
        assertTrue(phone.phoneSnapshot().events.isEmpty())
    }

    @Test fun healthBatchKeepsOnlyUsableReadings() {
        val b = HealthBatch("w")
        b.quantity("HEART_RATE", 61.0, now, now + 5_000)     // stored as a point reading
        b.quantity("STEPS", 0.0, now, now + M)                // empty interval
        b.quantity("SPEED", 3.0, now, now)                    // not collected from HealthKit
        b.quantity("DISTANCE", 120.0, now + M, now)           // end before start
        b.asleep(now, now + 30_000)                           // too short to be sleep
        b.asleep(now, now + 2 * H)
        assertEquals(2, b.count)
    }

    @Test fun sensorChoiceAndPanel() {
        val all = SensorSync.defaultConfig()
        assertEquals("6/6 on", SensorSync.panel(all, null).summary)

        val noFloors = SensorSync.toggle(all, "FLOORS", false, 10)
        assertFalse(SensorSync.isEnabled(noFloors, "FLOORS"))
        assertEquals(SensorKind.entries.map { it.name } - "FLOORS", SensorSync.enabled(noFloors))
        assertEquals(10L, SensorSync.updatedMs(noFloors))

        val names = SensorKind.entries.map { it.name }
        val live = SensorSync.report(names, names - "FLOORS", 11)
        assertEquals("5/6 live", SensorSync.panel(noFloors, live).summary)
        assertEquals("syncing", SensorSync.panel(all, live).summary)

        val noSleepHardware = SensorSync.report(names - "SLEEP", names - "SLEEP", 12)
        val sleepTile = SensorSync.panel(all, noSleepHardware).tiles.first { it.kind == SensorKind.SLEEP }
        assertEquals("n/a", sleepTile.caption)
        assertFalse(sleepTile.on)
        val hrTile = SensorSync.panel(all, noSleepHardware).tiles.first { it.kind == SensorKind.HEART_RATE }
        assertEquals("live · Recovery", hrTile.caption)

        // Newest choice wins, whichever device made it.
        assertEquals(noFloors, SensorSync.latest(all, noFloors))
        assertEquals(noFloors, SensorSync.latest(noFloors, all))
        assertEquals(noFloors, SensorSync.latest(noFloors, null))
    }

    @Test fun wordingAndCatalog() {
        assertEquals("just now", WatchText.ago(30_000))
        assertEquals("3h ago", WatchText.ago(3 * H + 5 * M))
        assertEquals("Nothing to send yet", WatchText.syncLine(0, null, 0, now).text)
        assertEquals(listOf("85–100", "70–84", "55–69", "40–54", "0–39"), Catalog.bandRows.map { it.range })
        assertEquals("Fair", Catalog.bandOf(60).label)
        assertEquals("7h 5m", Catalog.duration(425))
        assertTrue(Catalog.sleepScore(8.0) > Catalog.sleepScore(5.0))
    }

    private companion object {
        const val M = 60_000L
        const val H = 60 * M
    }
}
