# Hercules (Kotlin Multiplatform) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Wear OS + Android phone app that tracks body sensors in the foreground and background and finds personal bests. The code is structured so a backend and iOS/watchOS apps can be added without reworking the core.

**Architecture:** `:shared` is a KMP library holding the models, SQLDelight DB, insights (aggregation, sessions, personal records, HRV), sync engine and wire format. `:wear` (Compose for Wear OS + Health Services) writes sensor data straight into the shared DB and syncs batches to `:mobile` over the Wearable Data Layer. `:mobile` (Jetpack Compose) stores the batches, recomputes insights and queues data for the future server.

**Tech Stack:** Kotlin 2.2.21, AGP 8.13.2, Gradle 8.14.3, SQLDelight 2.1.0, kotlinx-coroutines 1.10.2, kotlinx-serialization 1.9.0, kotlinx-datetime 0.7.1, Health Services 1.1.0, Wear Compose Material3 1.5.0, Compose BOM 2025.10.00, play-services-wearable 19.0.0, WorkManager 2.10.5.

**Spec:** `docs/superpowers/specs/2026-11-02-hercules-watch-design.md`

## Global Constraints

- Wear and phone apps share applicationId `com.hercules.app` and must be signed with the same key.
- Wear `minSdk 30`, phone `minSdk 28`, `compileSdk`/`targetSdk 36`, JVM target 17.
- All domain logic lives in `:shared/commonMain`. No Android or JVM APIs there (they would break the iOS/watchOS targets).
- Metric wire names: `heart_rate, steps, calories, distance, floors, speed, ibi, spo2, skin_temp, hrv_sdnn, vo2_max`. Source wire names: `passive, exercise, measure, samsung_sdk, healthkit`.
- Sample id = `type|source|startMs|endMs|sessionId` (`Sample.idFor`). Never change this format, because the server de-duplicates on it.
- Daily totals count only `passive`/`healthkit` sources.
- Sync marks only acknowledged ids as synced. A receiver acks every id it received (`WireCodec.decodeBatchWithAck`).
- Wire format changes must stay backward compatible within `schemaVersion = 1` (add optional fields only).
- Verification commands: `./gradlew :shared:testDebugUnitTest`, `./gradlew :wear:assembleDebug :mobile:assembleDebug`, `./gradlew :wear:lintDebug :mobile:lintDebug` (0 issues expected).
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## File Structure (current)

```
shared/src/commonMain/kotlin/com/hercules/shared/
  model/Models.kt            MetricType, SampleSource, Sample, DailySummary, SessionSummary, RecordKind, PersonalRecord
  model/RecordText.kt        record labels/formatting for every UI
  insights/Insights.kt       DayKeys, DailyAggregator, SessionSummarizer, PersonalRecordEngine, Hrv
  insights/InsightsService.kt InsightsService (refresh), RetentionPolicy
  data/HerculesRepository.kt repository over SQLDelight; implements SyncStore
  sync/Sync.kt               SyncStore, RemoteSink, NoopRemoteSink, SyncService, DTOs, WireCodec
shared/src/commonMain/sqldelight/com/hercules/shared/db/Hercules.sq
shared/src/androidMain/…     createHerculesDatabase(context), WearProtocol, DeviceId
shared/src/appleMain/…       createHerculesDatabase(name)   (compiled only with -Phercules.apple=true)
shared/src/commonTest/…      InsightsTest, SyncTest       shared/src/androidUnitTest/… RepositoryTest
wear/src/main/kotlin/com/hercules/wear/
  HerculesWearApp.kt         WearGraph (DI), schedules SyncWorker
  health/Health.kt           HealthMapper, Permissions, PassiveDataService, PassiveRegistrar, StartupReceiver
  health/ExerciseService.kt  WorkoutKind, LiveWorkout, ExerciseService
  sync/Sync.kt               PhoneRemoteSink, SyncWorker
  ui/MainActivity.kt         permission, home, workout, records screens
mobile/src/main/kotlin/com/hercules/mobile/
  HerculesMobileApp.kt       MobileGraph (DI), SERVER_SYNC_ENABLED
  sync/WatchSyncListenerService.kt
  ui/MainActivity.kt         dashboard
docs/contracts/sample-batch.v1.schema.json
```

---

## Phase 1: Android foundation ✅ DONE

These tasks are done. Each one has been compiled and the shared module is unit-tested (22 tests passing), and lint reports 0 issues. They're listed so the plan reflects reality.

- [x] **Task 1: Gradle/KMP scaffold.** Version catalog, wrapper, `:shared` with Apple targets behind `hercules.apple`.
- [x] **Task 2: Shared models + insights.** Deterministic sample ids, daily aggregation with a resting-HR sliding window, session summary with 60 s HR recovery, personal-record engine (ties keep the earliest, missing days break streaks), RMSSD.
- [x] **Task 3: SQLDelight schema + `HerculesRepository`.** Idempotent inserts, flows for the UI, pending/synced tracking, retention deletes.
- [x] **Task 4: `InsightsService` + `RetentionPolicy`.** Finalizes ended workouts after the 75 s recovery window, recomputes today, yesterday and touched days, returns newly broken records.
- [x] **Task 5: Sync engine + wire format.** `SyncService` with ack semantics. `WireCodec` (ISO timestamps, drops unknown types, acks all ids).
- [x] **Task 6: Wear sensors.** Passive monitoring with boot re-registration, workout foreground service with OngoingActivity and recovery measurement, API 30–36 permissions.
- [x] **Task 7: Wear sync + UI.** `PhoneRemoteSink` (capability `hercules_phone`, `MessageClient.sendRequest`), `SyncWorker` (15 min, plus while open, plus after workouts), Compose for Wear OS screens.
- [x] **Task 8: Phone app.** `WatchSyncListenerService` (store, recompute, ack), dashboard, server sync stubbed with `NoopRemoteSink`.

---

## Phase 2: Device verification (next)

### Task 9: Verify on a Galaxy Watch + Android phone

**Files:**
- Create: `docs/device-notes.md`

- [ ] **Step 1: Install both apps**

```bash
./gradlew :wear:assembleDebug :mobile:assembleDebug
adb -s <phone> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb connect <watch-ip>:<port>            # watch: Developer options → Wireless debugging
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

- [ ] **Step 2: Permission flow.** Open Hercules on the watch and tap Allow. Expected: a foreground prompt, then a separate "Allow all the time" prompt. Denying leaves the app on the permission screen without crashing.

- [ ] **Step 3: Passive types supported**

Run: `adb -s <watch> logcat -d -s Hercules | grep "Passive monitoring types"`
Expected: a list containing at least `HeartRate` and `Steps`. Record the list in `docs/device-notes.md`.

- [ ] **Step 4: Background collection.** Force-stop the app (`adb -s <watch> shell am force-stop com.hercules.app`), wear the watch for 2 hours, then reopen it. Expected: the step count is within ±5% of Samsung Health.

- [ ] **Step 5: Reboot survival.** Reboot the watch, wait 30 minutes, then open the app. Expected: there are new heart-rate samples after the reboot time.

- [ ] **Step 6: Workout.** Do a 20-minute walk with the screen off. Expected: the ongoing-activity icon shows on the watch face and live bpm resumes when you wake the screen. After Stop, there's a "Cool down" screen for about 75 s, and "Longest workout" plus "Best HR recovery" appear in Personal bests within about 2 minutes.

- [ ] **Step 7: Watch → phone sync**

Run: `adb -s <phone> logcat -s HerculesPhoneSync`
Expected: `stored N samples, M sessions from <deviceId>` within 60 s while the watch app is open. The phone dashboard shows today's steps, and the watch shows "Synced to phone".

- [ ] **Step 8: Offline phone.** Turn off the phone's Bluetooth for 1 hour, then turn it back on. Expected: the watch shows "N readings waiting for phone", the count drains after reconnecting, and the phone has no duplicates (today's steps match the watch).

- [ ] **Step 9: Battery.** Run passive-only for 24 hours. Expected: less than 5% extra drain (Settings → Battery). Record the result in `docs/device-notes.md`.

- [ ] **Step 10: Commit**

```bash
git add docs/device-notes.md
git commit -m "docs: record Galaxy Watch verification results"
```

Fix any failures found here with superpowers:systematic-debugging before moving to Phase 3.

---

## Phase 3: Samsung Health Sensor SDK (IBI → HRV)

Prerequisites: download the Samsung Health Sensor SDK AAR from developer.samsung.com, and enable developer mode on the watch (Settings → Apps → Health Platform → tap the title 10× → Dev mode ON). Publishing requires Samsung partner approval.

### Task 10: Continuous HR + IBI capture during workouts

**Files:**
- Create: `wear/libs/samsung-health-sensor-api-<ver>.aar`, `wear/src/main/kotlin/com/hercules/wear/health/SamsungHrTracker.kt`
- Modify: `wear/build.gradle.kts`, `wear/src/main/kotlin/com/hercules/wear/health/ExerciseService.kt`
- Test: `shared/src/commonTest/kotlin/com/hercules/shared/InsightsTest.kt` (HRV is already covered by `hrvFromIbi`; add a record test)

**Interfaces:**
- Consumes: `Sample.create`, `HerculesRepository.insertSamples`, `MetricType.IBI`, `SampleSource.SAMSUNG_SDK`
- Produces: `class SamsungHrTracker(context: Context, deviceId: String, onSamples: (List<Sample>) -> Unit) { fun start(sessionId: String?); fun stop() }`

- [ ] **Step 1: Failing test.** In `InsightsTest.kt`, inside `PersonalRecordEngineTest`, add:
```kotlin
@Test fun hrvRecord() {
    val r = PersonalRecordEngine().evaluate(
        listOf(
            DailySummary("2026-10-01", 0, 0.0, 0.0, 0, hrvRmssd = 40.0),
            DailySummary("2026-10-02", 0, 0.0, 0.0, 0, hrvRmssd = 55.5),
        ),
        emptyList(),
    )
    assertEquals(PersonalRecord(RecordKind.HIGHEST_HRV_RMSSD, 55.5, "2026-10-02"), r[RecordKind.HIGHEST_HRV_RMSSD])
}
```
Run: `./gradlew :shared:testDebugUnitTest`. Expected: PASS. The engine already supports this, so the test pins the behavior.

- [ ] **Step 2: Gradle.** In `wear/build.gradle.kts` dependencies, add:
```kotlin
implementation(files("libs/samsung-health-sensor-api-<ver>.aar"))
implementation("com.google.code.gson:gson:2.11.0")
```

- [ ] **Step 3: Tracker.** Check the class names against the sample app shipped with the SDK version before writing this:
```kotlin
package com.hercules.wear.health

import android.content.Context
import android.util.Log
import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.android.service.health.tracking.data.DataPoint as SDataPoint

class SamsungHrTracker(
    private val context: Context,
    private val deviceId: String,
    private val onSamples: (List<Sample>) -> Unit,
) {
    private var service: HealthTrackingService? = null
    private var tracker: HealthTracker? = null

    fun start(sessionId: String?) {
        service = HealthTrackingService(object : ConnectionListener {
            override fun onConnectionSuccess() {
                tracker = service!!.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS).apply {
                    setEventListener(object : HealthTracker.TrackerEventListener {
                        override fun onDataReceived(list: List<SDataPoint>) {
                            val out = mutableListOf<Sample>()
                            for (dp in list) {
                                val ibis = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                                val statuses = dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST)
                                ibis.forEachIndexed { i, ibi ->
                                    if (statuses.getOrNull(i) == 0 && ibi > 0) {
                                        val t = dp.timestamp + i // keep ids unique within one data point
                                        out += Sample.create(MetricType.IBI, ibi.toDouble(), t, t, SampleSource.SAMSUNG_SDK, deviceId, sessionId)
                                    }
                                }
                            }
                            if (out.isNotEmpty()) onSamples(out)
                        }
                        override fun onFlushCompleted() = Unit
                        override fun onError(error: HealthTracker.TrackerError) { Log.w("HerculesSamsung", "tracker error $error") }
                    })
                }
            }
            override fun onConnectionEnded() = Unit
            override fun onConnectionFailed(e: HealthTrackerException) { Log.w("HerculesSamsung", "connect failed", e) }
        }, context).also { it.connectService() }
    }

    fun stop() {
        tracker?.unsetEventListener()
        service?.disconnectService()
        tracker = null
        service = null
    }
}
```

- [ ] **Step 4: Wire into `ExerciseService`.** Add a field `private var samsung: SamsungHrTracker? = null`. At the end of `begin()`, after the phase becomes ACTIVE:
```kotlin
samsung = runCatching {
    SamsungHrTracker(applicationContext, g.deviceId) { s -> lifecycleScope.launch { g.repo.insertSamples(s) } }
        .also { it.start(sessionId) }
}.getOrNull() // non-Samsung watches: silently unavailable
```
At the start of `end()`, add `samsung?.stop(); samsung = null`.

- [ ] **Step 5: Build + device check**

Run: `./gradlew :wear:assembleDebug :wear:lintDebug`. Expected: builds, 0 lint issues.
On the watch, do a 5-minute workout. Expected: Personal bests shows "Highest HRV" after the next sync.

- [ ] **Step 6: Commit**
```bash
git add wear shared
git commit -m "feat(wear): capture IBI via Samsung Health Sensor SDK for HRV"
```

---

## Phase 4: Backend sync (phone → server)

Depends on a backend that accepts `POST /v1/batches` with the `SyncBatch` JSON (`docs/contracts/sample-batch.v1.schema.json`). It upserts by sample `id` and session `id`, and responds `200 {"sampleIds":[…],"sessionIds":[…]}`.

### Task 11: `HttpRemoteSink` in `:shared` (Ktor)

**Files:**
- Modify: `gradle/libs.versions.toml`, `shared/build.gradle.kts`
- Create: `shared/src/commonMain/kotlin/com/hercules/shared/sync/HttpRemoteSink.kt`
- Test: `shared/src/commonTest/kotlin/com/hercules/shared/HttpRemoteSinkTest.kt`

**Interfaces:**
- Consumes: `RemoteSink`, `SyncBatch`, `SyncResult`, `WireCodec.encodeBatch`, `WireCodec.decodeAck`
- Produces: `class HttpRemoteSink(client: HttpClient, baseUrl: String, token: suspend () -> String?) : RemoteSink`

- [ ] **Step 1: Dependencies.** Add Ktor 3.x, using the newest release built for Kotlin 2.2 (check the Ktor release notes): `ktor-client-core` and `ktor-client-mock` (test) in commonMain/commonTest, `ktor-client-okhttp` in androidMain, `ktor-client-darwin` in appleMain.

- [ ] **Step 2: Failing test**
```kotlin
package com.hercules.shared

import com.hercules.shared.model.MetricType
import com.hercules.shared.model.Sample
import com.hercules.shared.model.SampleSource
import com.hercules.shared.sync.HttpRemoteSink
import com.hercules.shared.sync.SyncBatch
import com.hercules.shared.sync.SyncResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HttpRemoteSinkTest {
    private val batch = SyncBatch("b1", "dev", 0, listOf(
        Sample.create(MetricType.STEPS, 1.0, 1000, source = SampleSource.PASSIVE, deviceId = "dev")), emptyList())

    private fun sink(status: HttpStatusCode, body: String) = HttpRemoteSink(
        HttpClient(MockEngine { req ->
            assertEquals("Bearer t0k", req.headers[HttpHeaders.Authorization])
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }),
        baseUrl = "https://api.example.test",
        token = { "t0k" },
    )

    @Test fun acceptedIdsComeFromServer() = runTest {
        val r = sink(HttpStatusCode.OK, """{"sampleIds":["${batch.samples[0].id}"],"sessionIds":[]}""").push(batch)
        assertEquals(SyncResult.Accepted(listOf(batch.samples[0].id), emptyList()), r)
    }

    @Test fun serverErrorIsRetryable() = runTest {
        val r = sink(HttpStatusCode.ServiceUnavailable, "").push(batch)
        assertTrue(r is SyncResult.Rejected && r.retryable)
    }

    @Test fun clientErrorIsNotRetryable() = runTest {
        val r = sink(HttpStatusCode.BadRequest, "").push(batch)
        assertTrue(r is SyncResult.Rejected && !r.retryable)
    }
}
```
Run: `./gradlew :shared:testDebugUnitTest`. Expected: FAIL, `Unresolved reference: HttpRemoteSink`.

- [ ] **Step 3: Implement** `HttpRemoteSink.kt`:
```kotlin
package com.hercules.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess

class HttpRemoteSink(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: suspend () -> String?,
) : RemoteSink {
    override suspend fun push(batch: SyncBatch): SyncResult = try {
        val res = client.post("$baseUrl/v1/batches") {
            token()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(WireCodec.encodeBatch(batch))
        }
        when {
            res.status.isSuccess() -> WireCodec.decodeAck(res.bodyAsBytes()).let { SyncResult.Accepted(it.sampleIds, it.sessionIds) }
            res.status.value in 400..499 && res.status.value != 401 && res.status.value != 429 ->
                SyncResult.Rejected(retryable = false, reason = "server rejected batch: ${res.status}")
            else -> SyncResult.Rejected(retryable = true, reason = "server unavailable: ${res.status}")
        }
    } catch (e: Exception) {
        SyncResult.Rejected(retryable = true, reason = "network: ${e.message}")
    }
}
```
Run the tests. Expected: PASS.

- [ ] **Step 4: Wire into the phone.** In `MobileGraph`, replace `NoopRemoteSink` with `HttpRemoteSink(HttpClient(OkHttp), BuildConfig.API_URL, token = { auth.accessToken() })` and set `SERVER_SYNC_ENABLED = true`. Add a `ServerSyncWorker` (same shape as the watch's `SyncWorker`: periodic 30 min, `NetworkType.CONNECTED` constraint) that calls `serverSync.syncOnce()`.

- [ ] **Step 5: Gzip.** Add `ContentEncoding { gzip() }` (Ktor `ktor-client-encoding`) on the client. Heart-rate JSON compresses about 10×.

- [ ] **Step 6: Commit**
```bash
git add gradle shared mobile
git commit -m "feat: HttpRemoteSink and phone→server sync"
```

### Task 12: Login (device-code flow)

- [ ] Implement the OAuth 2.0 Device Authorization Grant in `:shared` (`AuthClient` with `startDeviceCode()`, `pollToken()` and `refresh()`), store tokens in Android Keystore-backed `EncryptedSharedPreferences` (later the iOS Keychain), and add a "Sign in" card on the phone dashboard. Write tests with Ktor `MockEngine` for: pending → authorized, expired code, and refresh on 401. The exact endpoints depend on the chosen identity provider, so finalize the steps once the backend is defined.

---

## Phase 5: iOS + watchOS on `:shared`

Prerequisite: Xcode installed (`xcode-select -s /Applications/Xcode.app`).

### Task 13: Build `:shared` for Apple targets

- [ ] **Step 1:** Run `./gradlew -Phercules.apple=true :shared:compileKotlinWatchosSimulatorArm64 :shared:compileKotlinIosSimulatorArm64`. Expected: BUILD SUCCESSFUL. Fix any JVM-only API that slipped into `commonMain`.
- [ ] **Step 2:** Run `./gradlew -Phercules.apple=true :shared:iosSimulatorArm64Test`. Expected: all `commonTest` tests pass on Kotlin/Native.
- [ ] **Step 3:** Add SKIE (`co.touchlab.skie`, choose the version that supports Kotlin 2.2.21) so `suspend`/`Flow` become Swift `async`/`AsyncSequence`. Then run `./gradlew -Phercules.apple=true :shared:linkDebugFrameworkWatchosSimulatorArm64`.
- [ ] **Step 4:** Set `hercules.apple=true` in `gradle.properties` and commit.

### Task 14: watchOS app (SwiftUI + HealthKit)

- [ ] Create `apple/` with an XcodeGen `project.yml` containing a watch-only app `HerculesWatch` (watchOS 10, HealthKit + background-delivery entitlements, `WKBackgroundModes: workout-processing`). A build phase runs `./gradlew :shared:embedAndSignAppleFrameworkForXcode`.
- [ ] `HealthKitCollector.swift`: `HKObserverQuery` + `enableBackgroundDelivery(.hourly)` + `HKAnchoredObjectQueryDescriptor` for heart rate, steps, active energy, distance, flights, HRV SDNN, SpO2 and VO2max. It maps each HealthKit sample to `Sample.companion.create(…, source: .healthkit, …)` and calls `repo.insertSamples`. Cumulative types only count samples whose `sourceRevision.productType` starts with `Watch`.
- [ ] `WorkoutManager.swift`: `HKWorkoutSession` + `HKLiveWorkoutBuilder` → `repo.startSession` / `endSession`. Live HR comes from `workoutBuilder(_:didCollectDataOf:)`.
- [ ] UI: Home, Workout and Records views using `RecordText` from `:shared`.
- [ ] Sync: a `PhoneRemoteSink` equivalent over WatchConnectivity (`sendMessageData` with a reply handler carrying the ack).

### Task 15: iOS app

- [ ] Receive WatchConnectivity batches → `WireCodec.decodeBatchWithAck` → store → `insights.refresh` → reply with the ack (the same flow as `WatchSyncListenerService`).
- [ ] Dashboard (SwiftUI, or Compose Multiplatform if chosen) and `HttpRemoteSink` with the Darwin engine.
- [ ] Optional: backfill from the iPhone's HealthKit history, which already contains Apple Watch data.

---

## Self-Review Notes

- Spec §4 sensors: implemented ones are covered by Phase 1. IBI/HRV by Task 10. SpO2 and skin temperature on Wear OS follow the Task 10 pattern with `HealthTrackerType.SPO2_ON_DEMAND` / `SKIN_TEMPERATURE_ON_DEMAND`. watchOS sensors by Task 14.
- Spec §7 background: Phase 1 (Wear), Task 14 (watchOS). Spec §9 sync: Phase 1 + Task 11. Login (from the sync discussion): Task 12.
- Phase 1 has no device verification yet, so Task 9 is a hard gate before shipping.

---

## Update 2026-11-02: product pivot to cognitive readiness (implemented)

The app is no longer workout-centric. Built and unit-tested in this iteration:

- **Sleep tracking**: watch `UserActivityInfo` → `activity_state` samples → `SleepAnalyzer` (`shared/cognitive`).
- **Readiness score 1–100%**: `ReadinessEngine` (sleep 40, recovery 30, check-in 15, activity 15; 14-day personal baseline; circadian windows).
- **Cognitive planning**: `PrepCoach` + `CognitiveService` (events ≤7 days ahead, phase checklists, prep score, forecast, go/reschedule advice).
- **Sensor management**: `SensorConfig`/`SensorReport` synced via DataClient; toggles on watch and phone.
- **UI**: phone tabs Today / Plan / Data (English, visual-first); watch home = score ring + peak time + next event.

Still open, in addition to Phases 2–5 above: on-device verification of sleep detection and of state/sensor sync; reminders for checklist tasks; calendar integration.

---

## Update 2026-11-04: iPhone + Apple Watch apps (Phase 5, implemented)

Same specification as the Android apps, on the same `:shared` core:

- **Shared:** `com.hercules.shared.app.HerculesCore` wraps the graph (repository, insights, retention, cognitive) behind a Swift-friendly API (plain values, snapshot objects, `suspend` → Swift `async throws`, no Flows). It mirrors MobileGraph/WearGraph, `WatchSyncListenerService` (`receiveFromWatch`), `WatchStateSource` (`watchState`/`watchHome`) and the phone dialogs (`sleepDraft`, `eventDraft`). `HealthBatch` turns HealthKit readings into samples; `WatchTransport` + `TransportSink` carry batches with ack semantics; `SensorSync` handles the sensor choice as JSON. `appleMain/AppleFactory` creates it. Tested in `HerculesCoreTest` (11 tests).
- **Sleep from HealthKit:** stored as `activity_state` *spans* (value asleep, start..end). `SleepAnalyzer` now merges spans with the Wear OS state-change points, so overlapping stages, several sources and out-of-order batches give the same night. Points-only behaviour is unchanged.
- **History:** nights touched by a batch (HealthKit's 14-day backfill, a watch that was away) are re-analyzed on both devices, so the baseline is complete from day one.
- **Wire contract:** `activity_state` added to the schema's type enum (it was already sent by Wear OS).
- **Sync on Apple:** `sendMessage` only reaches the phone while the watch app is in front, so the iPhone also reads the Apple Watch's data from its own Health store (Apple syncs it); identical sample ids keep it to one copy. The watch sends whenever it is open.
- **Sleep:** only the main sleep block counts (wake-ups up to 2 h stay in the night), so a nap no longer moves the wake time, on Wear OS too.
- **Gradle:** `watchosDeviceArm64` target added. SKIE was not needed.
- **apple/:** XcodeGen `project.yml`, SwiftUI iOS app (Today, Plan, event detail, add moment, sleep correction, check-in, Data, guide, onboarding, launch art), SwiftUI watchOS app (HealthKit observer + anchored queries with background delivery, WatchConnectivity sync, home with dial/tracking/moments, sensors, permission), asset catalogs from the existing artwork.

Task 13: done. CI on a macOS runner (`.github/workflows/apple.yml`) runs `iosSimulatorArm64Test` (passes) and builds both apps in Xcode 16.4 (passes); klibs also cross-compile on Linux (`-Pkotlin.native.enableKlibsCrossCompilation=true`). `hercules.apple` stays false by default so Android-only machines don't need Xcode. Tasks 14 and 15: implemented as above, except workouts (`HKWorkoutSession`), which the Android app no longer has either since the pivot to readiness.

Still open: run on a simulator pair and on devices (signed build, HealthKit background delivery, WatchConnectivity wake-ups); HealthKit `restingHeartRate` and sleep deletions; iPhone-side HealthKit backfill.
