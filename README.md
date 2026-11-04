# Hercules

<p align="center"><img src="docs/images/hercules-icon.png" width="120" alt="Hercules icon"></p>

![Hercules — Know when your brain is ready.](docs/images/hercules-hero.jpg)

**Know when your brain is ready.** Hercules turns the signals your phone, watch and Health Connect already collect (sleep, heart rate variability, resting heart rate, activity, daylight) plus a 2-minute timing game, **Beat the Clock**, into a daily **Cognitive Readiness Score (0–100)**. It shows how much to trust that number, finds the time of day you tend to think best, and helps you prepare for important **Moments** in the next 7 days.

Android phone and Wear OS watch today (Kotlin Multiplatform), built so iOS and watchOS can follow with the same core logic. The full product and technical specification is [`docs/SPEC.md`](docs/SPEC.md).

---

# Part 1: Business process

## 1. The idea

High-stakes moments like a job interview, a negotiation, a pitch or an exam depend on focus, working memory and emotional control. Those depend heavily on how you slept and how recovered you are. Most people walk into those moments blind to their own condition.

Hercules answers three questions, in three menus:

| Question | Menu |
|---|---|
| *How ready am I to perform cognitively today?* | **Home**: score, level, confidence, why, one next step |
| *When do I tend to perform best?* | **Home**: Peak Cognitive Window, once enough Beat the Clock games exist |
| *What can I do to prepare for an important Moment?* | **Moments**: up to 5 Moments, 7 days ahead, with preparation status |

**Setup** holds Health Connect, sensors and devices, scoring weights, baselines, reminders, data and legal pages.

## 2. Daily flow

1. **Data arrives on its own.** Health Connect is read when the app opens, every 5 minutes while it is open, and about once an hour in the background (the watch records its sensors continuously and syncs every 15 minutes). "Sync now" is optional. The Hercules watch is one more provider.
2. **A score appears from the first data point.** One night of sleep or one Beat the Clock game is enough. The confidence line under the score says how far to trust it.
3. **Beat the Clock (optional, any time, ~2 minutes).** Three rounds: hold a target of 15–19 s, then 20–24 s, then 25–30 s, and tap STOP when you think the time is up. Each round counts "1… 2… 3…" so you get the pace, then the count disappears. The first game is scored against a typical-player reference; from the third game on, against your own baseline. Phone and watch keep separate baselines.
4. **One next step.** The coach (`Coach.kt`) picks the single most useful action: play, prepare for a Moment, reflect on one, sleep earlier, or use your peak window. Phone and watch say the same thing.
5. **Sleep looks wrong?** Tap **Edit times** (or **Add sleep**) in the Sleep section. A corrected night is never overwritten by automatic detection.

**Meet Herc.** The mascot is the head of an adult male lion with a bright golden mane, designed with Higgsfield. **Each band has its own expression and eye colour**: fierce and focused with green eyes for Prime, calm and assured with lime eyes for Good, neutral with amber eyes for Fair, weary with orange eyes for Low, and worn down with red eyes for Drained. With no score yet he shows the Fair face with dim, narrowed eyes. He is the launcher icon on phone and watch, the animated splash, Home, the next-step card and the watch home. The base lion came from Recraft V4.1 (vector). Nano Banana Pro redrew it once per expression, and Ideogram Vector traced each one back to vector (`tools/mascot/lion/*.svg`). `tools/mascot/mascot.py` reads those files and writes `HercArt.kt` for both apps, plus the launcher, notification and animated-splash drawables.

![Herc: Prime, Good, Fair, Low, Drained, no score](docs/images/hercules-mascot.png)

**Reminders** (all optional, in Setup): morning score, Beat the Clock, Moment prep (evening before and shortly ahead), bedtime, after a Moment, and focus start (off by default). **Only one device delivers each reminder:** the watch while it is worn, the phone otherwise.

**Watch:** score with Herc, level, confidence and what it is based on, one next step, Beat the Clock (same rules as the phone), upcoming Moments, and a tracking-status screen.

## 3. Reading the score

| Score | Level | What to do |
|---|---|---|
| 85–100 | **Prime** | Schedule the hardest things today |
| 70–84 | **Good** | Put important work in your peak window |
| 55–69 | **Fair** | Timing matters; use your best hours |
| 40–54 | **Low** | Postpone non-urgent big decisions if you can |
| 0–39 | **Drained** | Light tasks, recover, sleep early |

**Confidence** sits right under the score and is separate from the level:

| Signals in today | Full days of data | Confidence |
|---|---|---|
| ≥ 90% of the weighted signals | ≥ 7 | High |
| ≥ 70% | ≥ 3 | Medium |
| less | | Low |

It reads like *"Medium confidence · 4 of 5 signals · 5 days of data"*, and below High it says what would raise it (*"To raise it: add a Beat the Clock game; 2 more days of data."*). **Why this score** opens from the score and shows every dimension, metric, deduction and the configuration used.

## 4. How the score is built (default configuration v2)

| Dimension | Weight | What is measured | Evidence |
|---|---|---|---|
| Sleep & Recovery | 40% | 3-night sleep load (0.60 / 0.25 / 0.15; 7–9 h = full marks) 55%, sleep regularity 25%, continuity 20% | Van Dongen 2003, Lim & Dinges 2010, Watson 2015, Phillips 2017 |
| Cardiovascular | 20% | HRV (ln RMSSD, 3-night average vs. your 28-day usual) 70%, resting heart rate vs. usual 30% | Plews 2013/2014, Buchheit 2014, Forte 2019 |
| Physical Activity | 10% | Yesterday's steps, exercise minutes, low-movement hours (more is not always better) | Chang 2012, Wheeler 2020 |
| Circadian Alignment | 10% | Phone light sensor: bright mornings and days, dim evenings | Brown 2022 |
| Cognitive Performance | 20% | Today's Beat the Clock: accuracy, consistency, bias | |

- **Missing is never zero.** A missing dimension is left out and the others are re-weighted; it lowers confidence, not the score.
- **Optional extras only subtract** (max −10 in total, after 7 days of history): breathing rate or skin temperature clearly above your usual (−5 each), low overnight oxygen (−5), glucose under 3.9 mmol/L in the last 24 hours (−5) or under 3.0 (−10), for about 15 minutes on a CGM (one dip is ignored) or one meter reading. A low glucose reading from the last 3 hours also shows a live banner on Home.
- **Weights are editable** in Setup (must total 100%, never silently normalized). Reset asks for confirmation. Every stored score keeps the configuration and engine version it was calculated with.
- **New data updates the score.** Every sync recalculates today and the previous 2 days (late sleep or steps land on the right day), each with its original weights. Older days are never rewritten.
- **Today tiles** (steps, distance, resting and peak pulse, energy, floors) come from Health Connect's totals or the Hercules watch, whichever recorded more, and have their own **Sync** button.

## 5. Peak Cognitive Window

Each Beat the Clock game is filed under morning (0–4 h after waking), afternoon (4–8 h) or evening (8 h+). After 14 days, with at least 7 valid games on 7 different days in every part of the day, Hercules names the part of the day you perform best, or says there is no clear peak.

## 6. Moments (up to 7 days ahead)

Add a title, urgency (Normal or Critical), date and hour. Each Moment gets a **preparation status**, *On Track*, *Needs Attention* or *Insufficient Data*, from your recent readiness, sleep and recovery trends, activity, Beat the Clock and whether the hour falls in your peak window. Recommendations depend on time left (5–7 days: build sleep regularity; 1–2 days: protect sleep; event day: current readiness and peak alignment). The time is treated as fixed: advice is about preparing, not rescheduling. Afterwards, one tap records how it went.

## 7. Sensors are your choice

**Setup → Sensors & data** is one page for everything that feeds the score: the Health Connect connection (permissions, background reading, Sync now), each signal with its source and state (permission needed, no recent data, live), the watch, and optional extras. Required Health Connect types: sleep, HRV, resting heart rate, steps, exercise. Optional extras: respiratory rate, skin temperature, SpO₂, heart rate, blood glucose. The watch sensor grid (heart rate, sleep, steps, calories, distance, floors) syncs both ways. Turning a sensor off removes its signal and lowers confidence; it never breaks the score.

## 8. Important limits

- Hercules is a **decision aid, not a medical device**. It doesn't diagnose anything.
- The formula rests on published research, but the weights are product hypotheses, not a clinical validation of this app.
- Data stays on your devices. No backend, no account.

---

# Part 2: Technical

## Architecture

```
┌──────────── Wear OS watch (wear) ───────────┐        ┌────────────── Android phone (mobile) ──────────────┐
│ Health Services passive monitoring          │        │ Health Connect (auto read + background worker)     │
│                                             │        │ WatchSyncListenerService                           │
│  HR, steps, calories, distance, floors      │ batch  │  store samples → insights → readiness refresh      │
│  + user activity state (asleep/awake) ──────┼───────►│  → push WatchState back to the watch               │
│ Sync status: "Sent to phone · 2m ago"       │MessageC│ Compose UI: Today · Plan · Data(+Sensors)          │
│ Home: score, confidence, Beat the Clock     │◄───────┤ DataClient: /hercules/state, /hercules/sensors/*   │
│ Sensors screen ◄──── /hercules/sensors ────►│DataCli.│ NoopRemoteSink → (future) HttpRemoteSink → backend │
└──────────────────────┬──────────────────────┘        └──────────────────────────┬─────────────────────────┘
                       └───────────── shared (Kotlin Multiplatform) ──────────────┘
      readiness/ Scoring · Assessment (Beat the Clock) · Moments · Stats   cognitive/ CognitiveService · Coach
      SQLDelight DB · SensorConfig · SyncService/WireCodec · DemoData           (Android now, iOS/watchOS next)
```

All scoring and coaching logic is in `shared`, so the watch can compute a score on its own and an iOS/watchOS port reuses it unchanged.

## Modules

| Module | Contents |
|---|---|
| `shared/` | `readiness/` `Scoring.kt` (dimensions, CRS, confidence, extras), `Assessment.kt` (Beat the Clock, peak window), `Moments.kt`, `HealthData.kt`, `Stats.kt` · `cognitive/` `CognitiveService` (orchestration) + `CognitiveStore`, `Coach`, `Nudges`, `ReadinessEngine` (sleep analysis) · `model/`, `insights/`, `data/` watch samples and daily summaries · `sensors/` · `sync/` batch sync + wire format · `demo/` synthetic data · `sqldelight/` schema |
| `wear/` | `health/` passive monitoring, sleep, sensor settings · `sync/` phone sink, periodic worker, `WatchStateSource` · `notify/` reminders · `ui/` home, Beat the Clock, Moments (Wear Compose M3) |
| `mobile/` | `health/` Health Connect reader, background worker, phone light sensor · `sync/` receives watch batches · `notify/` reminders · `ui/` Home (`TodayScreen`, `CoachCards`), Moments (`PlanScreen`), Setup (`DataScreen`), `BeatTheClockScreen`, onboarding (`Journey`), legal |

## Sensors and what they're used for

| Sensor | Health Services source | Stored as | Used by |
|---|---|---|---|
| Heart rate | `HEART_RATE_BPM` (passive) | `heart_rate` | Resting HR → Cardiovascular (fallback when Health Connect has none) |
| Sleep | `UserActivityInfo` (`USER_ACTIVITY_ASLEEP / PASSIVE / EXERCISE`) | `activity_state` points (state changes) | `SleepAnalyzer`: main sleep in the window 18:00 the day before → 14:00, at least 60 min, wake-ups merged |
| Steps, calories, distance, floors | passive deltas | interval samples | Activity (on days the watch was worn), daily stats, records |

When the same signal comes from several places, the more reliable one wins (never summed): **sleep** from a wearable's staged night in Health Connect, then the Hercules watch; **HRV** from Health Connect (measured overnight by the wearable's app), then the watch; **resting heart rate and steps** from the Hercules watch on days it was worn, then Health Connect; **light** from the phone. Without the watch everything still works from Health Connect and the phone.

Only sensors that are **enabled** (`SensorConfig`) and **supported** by the watch are registered. The watch publishes a `SensorReport` (supported + active) so the phone shows the real state.

## Data and sync

- **Storage:** SQLDelight (`HerculesDatabase`). Watch data: `sample`, `workout_session`, `daily_summary`, `personal_record`, `sleep_night`. Readiness: `health_metric`, `sleep_session`, `exercise_session`, `light_sample`, `cognitive_session`, `cognitive_round`, `user_baseline`, `scoring_config`, `readiness_day` (JSON, with config and engine version), `peak_window`. Moments: `plan_event`, `prep_task_done`, `event_reflection`, `moment_snapshot`. Settings: `kv`.
- **Watch → phone:** every 15 minutes (WorkManager) and while the app is open. Batches go over `MessageClient` on `/hercules/sync/v1`. The phone acks the stored IDs, and only acked rows are marked synced. Sample IDs are deterministic, so retries are idempotent.
- **Phone → watch:** after each refresh the phone writes `WatchState` (score with confidence, next step, Moments) to DataClient `/hercules/state`. Beat the Clock games sync both ways and are stored once per id. The watch uses it when it is less than 6 hours old and from today; otherwise it computes locally.
- **Sensor choice:** `/hercules/sensors/config`, written by either side; the newest `updatedMs` wins. The watch reports back on `/hercules/sensors/report`.
- **Backend (future):** the phone keeps everything queued (`synced = 0`). Swap `NoopRemoteSink` for an HTTP sink and set `SERVER_SYNC_ENABLED`. The wire contract is in `docs/contracts/sample-batch.v1.schema.json`.

## Build and test

Requirements: JDK 17+ (21 tested) and the Android SDK (compileSdk 36).

```bash
./gradlew :shared:testDebugUnitTest                  # unit tests: scoring, confidence, Beat the Clock, peak, moments, coach, sync, DB
./gradlew :wear:assembleDebug :mobile:assembleDebug
./gradlew :wear:lintDebug :mobile:lintDebug
```

## Run

```bash
# Phone
adb -s <phone> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
# Galaxy Watch: Developer options → ADB + Wireless debugging
adb connect <watch-ip>:<port>
adb -s <watch> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Both apps share the `applicationId` `com.hercules.app`, which the Wearable Data Layer requires.

**Try it without a watch (debug builds only):** load synthetic sleep, HRV, resting HR, steps, light and Beat the Clock games plus sample Moments:

```bash
adb shell am start -n com.hercules.app/com.hercules.mobile.ui.MainActivity --ez demo true
```

You can also use **Setup → Developers → Load demo data**.

The watch has the same switch, so its UI can be checked on an unpaired Wear OS emulator (grant the sensor permissions first, or tap Allow):

```bash
adb -s <watch-serial> shell am start -n com.hercules.app/com.hercules.wear.ui.MainActivity --ez demo true
```

With no data at all the score shows "Waiting on the first reading"; any one reading gives a (low-confidence) score.

**Synthetic sensors on a Wear emulator:**

```bash
adb -s <watch> shell am broadcast -a "whs.USE_SYNTHETIC_PROVIDERS" com.google.android.wearable.healthservices
adb -s <watch> shell am broadcast -a "whs.synthetic.user.START_WALKING" com.google.android.wearable.healthservices
```

Logs: `adb logcat -s Hercules HerculesSync HerculesState HerculesPhoneSync`.

## Porting to iOS / watchOS

1. Install Xcode, then build the framework: `./gradlew -Phercules.apple=true :shared:linkDebugFrameworkWatchosSimulatorArm64` (targets `iosArm64`, `iosSimulatorArm64`, `watchosArm64`, `watchosSimulatorArm64`; framework `HerculesShared`).
2. **watchOS (SwiftUI):** feed HealthKit into `HerculesRepository.insertSamples`. Map `HKCategoryTypeIdentifier.sleepAnalysis` to `activity_state` samples, so `SleepAnalyzer` works as-is. Show `CognitiveService.watchState()`.
3. **iOS (SwiftUI or Compose Multiplatform):** WatchConnectivity replaces the Data Layer, HealthKit replaces Health Connect. Reuse `CognitiveService`, `Coach` and `SyncService`.
4. Consider SKIE so `suspend` and `Flow` look like Swift `async` and `AsyncSequence`.

## Design

The UI is designed as a **precision instrument**, not a wellness toy: matte black (`#05050E`), electric cyan (`#3FE0FF`) and indigo (`#6E6BFF`), hairline rules instead of card stacks, sharp 6 dp corners, small letter-spaced section labels, monospace readouts, and Material *Rounded* line icons (no emoji).

| Element | Where |
|---|---|
| Bezel dial: 100 ticks lit up to the score | Phone Today hero; watch home (follows the round screen edge) |
| Your day: last night's sleep, the three parts of the day, the peak window, *now*, today's Moments on one time axis | Phone Home |
| Countdown rail: Moments as stops on a vertical line | Phone Moments |
| Sensor tiles: lit = recording | Phone Setup |

**User journeys and illustrations** (generated with Higgsfield, GPT Image 2.5, in one blueprint style; `mobile/src/main/res/drawable-nodpi/ill_*.webp`):

| Journey step | Illustration |
|---|---|
| Onboarding 01 · Meet Herc | `ill_score`: head profile inside a precision dial |
| Onboarding 02 · Health Connect | `ill_sleep`: sleep wave flowing into a watch |
| Onboarding 03 · Beat the Clock | `ill_checkin`: rings aligning |
| Onboarding 04 · Moments, and the empty Moments screen | `ill_plan`: seven ascending platforms to a diamond |
| Onboarding 05 · The watch | `ill_no_watch`: broken signal arc |

**Launch.** The system splash (Android 12+ API, backported) shows the mark on the brand black. On a cold start it hands off to an **animated splash**: Kling 3.0 via Higgsfield, converted to an animated WebP in `res/raw/splash_anim.webp` and played with `AnimatedImageDrawable`. Tap to skip.

| Asset | |
|---|---|
| `docs/images/hercules-icon.png` | Icon master (1024 px): **H** monogram, heartbeat crossbar, readiness gauge. Launcher, adaptive, round and monochrome icons in `mobile/` and `wear/` are generated from it |
| `docs/images/hercules-splash.jpg` | Static splash artwork |
| `docs/images/hercules-hero.jpg` | README / store hero (devices are concept art, not exact app screens) |

## Known gaps

- **Not yet verified on a physical Galaxy Watch.** The phone runs on a Xiaomi device; the watch on the Wear OS emulator. Watch sleep detection and phone↔watch sync are untested on watch hardware.
- HRV comes from Health Connect; the Hercules watch alone gives no HRV (needs the Samsung Health Sensor SDK).
- Weights are evidence-informed hypotheses; they need 4–8 weeks of real data before tuning (SPEC §23).
- The peak window counts games from the first hour after waking (sleep inertia) like any other morning game.
- No backend or login. Moments are limited to 7 days ahead and are not linked to the device calendar.
