# Hercules: Smartwatch Body-Sensor Tracker, Design Spec

**Date:** 2026-11-02
**Status:** Phase 1 (Android: Wear OS + phone) implemented with Kotlin Multiplatform. Phase 5 (iPhone + Apple Watch on the same `:shared`) implemented in `apple/`, not yet built in Xcode. Other phases planned in `docs/superpowers/plans/2026-11-02-hercules-kmp.md`.

## 1. Goal

A smartwatch app with a companion phone app. It records body-sensor data in the foreground and the background, stores everything locally, and works out the user's **personal bests** (step records, lowest resting heart rate, workout distance, heart-rate recovery, streaks, HRV). There's no backend yet. Every record is still shaped, tagged and queued so that adding sync to a server is a drop-in change, not a migration.

## 2. Technology decision: Kotlin Multiplatform (KMP)

| Layer | Android (now) | iOS / watchOS (later) |
|---|---|---|
| Domain logic, DB, sync | `:shared` (KMP, `androidTarget`) | Same `:shared`, compiled for `iosArm64`, `iosSimulatorArm64`, `watchosArm64`, `watchosSimulatorArm64` |
| Watch UI + sensors | Kotlin, Compose for Wear OS, Health Services | SwiftUI + HealthKit (Compose Multiplatform doesn't target watchOS) |
| Phone UI | Kotlin, Jetpack Compose | SwiftUI |
| Watch ↔ phone | Wearable Data Layer (`MessageClient.sendRequest`) | WatchConnectivity |

Why KMP over Flutter:
- Flutter can't target watchOS. With KMP, the business logic is **shared** with the Apple Watch instead of ported by hand.
- On Wear OS, Health Services callbacks run while the UI is closed. With native Kotlin they write **directly** into the shared database. Flutter would need a native buffer plus a bridge.
- The watch app is lighter, with no Flutter engine.

## 3. Target devices and OS floors

- **Wear OS:** Wear OS 3+ (API 30+). Primary: Samsung Galaxy Watch 4 or newer. Wear OS 6 (API 36) granular health permissions are handled.
- **Android phone:** API 28+.
- **watchOS:** watchOS 10+. **iOS:** iOS 17+.

## 4. Sensor matrix (✅ implemented, ◐ planned)

| Metric (wire name) | Unit | Wear OS source | watchOS source (later) | Background |
|---|---|---|---|---|
| `heart_rate` | bpm | ✅ Health Services `HEART_RATE_BPM` (passive, exercise, measure) | HealthKit `.heartRate` | ✅ |
| `steps` | count | ✅ HS `STEPS` | HK `.stepCount` | ✅ |
| `calories` | kcal | ✅ HS `CALORIES` | HK `.activeEnergyBurned` | ✅ |
| `distance` | m | ✅ HS `DISTANCE` | HK `.distanceWalkingRunning` | ✅ |
| `floors` | count | ✅ HS `FLOORS` | HK `.flightsClimbed` | ✅ |
| `speed` | m/s | ✅ HS `SPEED` (workout) | workout builder | foreground |
| `ibi` (beat-to-beat interval) | ms | ◐ Samsung Health Sensor SDK | — | foreground |
| `hrv_sdnn` | ms | — | HK `.heartRateVariabilitySDNN` | ✅ |
| `vo2_max` | ml/kg/min | — | HK `.vo2Max` | ✅ |
| `spo2` | % | ◐ Samsung SDK | HK `.oxygenSaturation` | on demand |
| `skin_temp` | °C | ◐ Samsung SDK | HK wrist temperature | on demand |

The Samsung Health Sensor SDK needs Samsung **partner approval to ship**, though developer mode lets us test locally.

## 5. Architecture

```
Wear OS watch (:wear)                                Android phone (:mobile)
┌─────────────────────────────────────┐              ┌──────────────────────────────────┐
│ PassiveDataService (background)     │              │ WatchSyncListenerService         │
│ ExerciseService (FGS type=health)   │   Data Layer │   store → recompute → ack ids    │
│ SyncWorker (15 min)                 │ ───batch───► │ Dashboard (Compose)              │
│   insights → retention → PhoneSink  │ ◄───ack───── │ SyncService → NoopRemoteSink     │
│ Compose for Wear OS UI              │              │   (→ HttpRemoteSink later)       │
└──────────────┬──────────────────────┘              └───────────────┬──────────────────┘
               └──────────────── :shared (KMP) ──────────────────────┘
   Models · SQLDelight DB · DailyAggregator · SessionSummarizer · PersonalRecordEngine
   · Hrv · InsightsService · RetentionPolicy · SyncService/RemoteSink · WireCodec
```

**Sync path:** phone-first. The watch hands data to the phone over Bluetooth, which is cheap on battery, and the phone uploads to the server. A watch→server fallback (for runs without the phone) can be added later as another `RemoteSink`.

## 6. Data model (wire contract v1)

`Sample`: `{id, type, value, unit, start, end, source, deviceId, sessionId?}`
- `id` is **deterministic**: `type|source|startMs|endMs|sessionId`. Re-delivery from sensors and retries on watch→phone or phone→server all collapse to one row. The server must upsert by `id`.
- Timestamps are UTC ISO-8601 on the wire and epoch ms in the database. Day bucketing uses the local `dayKey` (`yyyy-MM-dd`).
- Derived: `DailySummary`, `SessionSummary`, `PersonalRecord`.
- Envelope: `SyncBatch {schemaVersion: 1, batchId, deviceId, sentAt, samples[], sessions[]}`. Schema: `docs/contracts/sample-batch.v1.schema.json`.
- `synced` flag means "delivered upstream": watch → phone, and phone → server.

**Double-count rule:** daily totals only count `passive`/`healthkit` sources. `exercise` samples feed only into session summaries.

## 7. Background strategy

**Wear OS (implemented)**
- *Passive:* `PassiveMonitoringClient.setPassiveListenerServiceAsync` (HR, steps, calories, distance, floors). The system binds `PassiveDataService` even when the process is dead. `StartupReceiver` re-registers after reboot.
- *Workout:* `ExerciseClient` in a foreground service (`health` type on API 34+) with an `OngoingActivity`. `MeasureClient` HR runs for 75 s after Stop to measure HR recovery.
- *Permissions:* API 30–35 uses `BODY_SENSORS`, `BODY_SENSORS_BACKGROUND` (33+), `ACTIVITY_RECOGNITION`, `POST_NOTIFICATIONS`. API 36+ uses `health.READ_HEART_RATE` and `health.READ_HEALTH_DATA_IN_BACKGROUND`. Background permission is requested separately, after foreground.
- *Sync:* WorkManager every 15 minutes, every 60 s while the app is open, and right after a workout.

**watchOS (later)**: `HKObserverQuery` with `enableBackgroundDelivery(.hourly)` and anchored queries. Workouts use `HKWorkoutSession` + `HKLiveWorkoutBuilder`.

## 8. Personal records

| Record (`kind`) | Better = | Definition |
|---|---|---|
| `max_daily_steps` | higher | Sum of passive/healthkit steps in a local day |
| `lowest_resting_hr` | lower | Lowest mean HR over any 10-minute window with ≥ 3 samples |
| `max_session_distance` | higher | Sum of `distance` samples tagged with the session |
| `best_hr_recovery` | higher | HR at workout end minus the HR sample closest to end+60 s (±10 s) |
| `longest_step_streak` | higher | Consecutive calendar days with steps ≥ goal (10,000) |
| `highest_hrv_rmssd` | higher | Daily RMSSD over beat-to-beat intervals (needs Samsung SDK) |

Ties: the earliest achievement keeps the record.

## 9. Sync-readiness and retention

- `SyncService` pulls pending samples and sessions in batches, pushes them to a `RemoteSink`, and marks only the **acknowledged ids** as synced.
- The watch uses `PhoneRemoteSink`. The phone uses `NoopRemoteSink` until the backend exists (`SERVER_SYNC_ENABLED = false`).
- A receiver acks every id in a batch, including metric types it doesn't understand, so a newer sender never retries them forever.
- Retention: raw samples are deleted after 30 days once synced, with a 90-day hard limit. Summaries and records are kept forever.

## 10. Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | `:shared` + Wear OS app + Android phone app | ✅ Built, unit-tested, linted. Not yet verified on a device. |
| 2 | Device verification on a Galaxy Watch | Next |
| 3 | Samsung Health Sensor SDK (IBI → HRV, SpO2, skin temp) | Planned |
| 4 | Backend + `HttpRemoteSink` (Ktor) + login (device-code flow) | Planned |
| 5 | iOS app + watchOS app on `:shared` | ✅ Written (SwiftUI, HealthKit, WatchConnectivity), Kotlin side unit-tested and cross-compiled for Apple targets. First Xcode build and device run pending. |

## 11. Out of scope for now
Watch faces, complications and tiles, ECG, sleep staging, GPS routes, social features.

## 12. Open questions
1. Apply for Samsung partnership now? Approval takes weeks.
2. Should the step goal be user-configurable?
3. ~~iOS phone UI: SwiftUI or Compose Multiplatform?~~ SwiftUI, through the Swift-friendly `HerculesCore` facade in `:shared`.
