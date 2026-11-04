# Hercules: Product & Technical Specification (V1)

**Product:** Hercules, a cognitive readiness app for Android with a Wear OS companion.
**Platforms:** Android phone + Wear OS watch · **Language:** Kotlin (Kotlin Multiplatform `shared` module for all domain logic)
**Architecture:** local-first, no backend · **Health data:** Health Connect (phone) and Wear OS Health Services (watch)
**Phone ↔ watch:** Wearable Data Layer API · **Primary menus:** Home, Moments, Setup

This document merges the *Cognitive Readiness App, Master Product & Technical Specification V1* with the existing
Hercules app. Where the two disagree, the master specification wins. The few places where Hercules deliberately
adapts it are listed in [§0.2](#02-adaptations-to-the-master-specification), each with its reason.

---

## 0. Hercules specifics

### 0.1 Identity and design (kept from the existing app)

- **Name:** Hercules. Tagline: "Know when your mind is ready".
- **Mascot:** Herc, the head of an adult male lion with a golden mane. Each readiness classification has its own
  expression and eye colour, and the mascot is the illustration for that level everywhere: launcher icon, animated
  splash, Home, watch home, onboarding.

  | Classification | Herc | Eye colour |
  |---|---|---|
  | Prime | fierce, focused | green `#2EE6A0` |
  | Good | calm, assured | lime `#9BE35A` |
  | Fair | neutral | amber `#FFC857` |
  | Low | weary | orange `#FF8C4C` |
  | Drained | worn down | red `#FF4D6D` |
  | Insufficient data | Fair face, narrowed grey eyes | grey `#8A7A66` |

  Source art: `tools/mascot/lion/*.svg` → `tools/mascot/mascot.py` generates `HercArt.kt` and the drawables.
- **Look:** dark ink background, cyan accents, thin numerals, eyebrow labels. The score label reads
  "Your cognitive state". This specification changes behaviour, not the visual language.
- **Wording:** important events are always called **Moments** (never "events" in the UI).
- **Legal:** Privacy Policy and Terms & Conditions pages live under Setup → About.

### 0.2 Adaptations to the master specification

| Topic | Master spec | Hercules | Why |
|---|---|---|---|
| Local database | Room | SQLDelight (already in the project, shared by phone, watch and future iOS) with the same entities as tables and versioned migrations | §13.1 and §26.2 ask to reuse the existing architecture and dependencies. SQLDelight offers the same guarantees (typed queries, migrations, Flow) and is multiplatform. |
| Health data on the watch | Health Services | Health Services passive monitoring (as before). The phone treats the Hercules watch as one more provider, with its own provenance, next to Health Connect | Users without a Health Connect provider still get a score from their watch. |
| Morning check-in (energy/stress) | Not part of the CRS | Removed. The daily task on Home is now **Beat the Clock** | The five CRS dimensions are fixed by the spec; self-reports are not one of them. |
| Generic "focus windows" | Peak window only from data | Removed. The day timeline shows the three assessment windows and highlights the Peak Cognitive Window only once it is predicted | §5.3: predict the peak only with enough history. |
| Moment go/reschedule verdict and 0–100 "prep" score | Preparation status, no extra score, no rescheduling of fixed events | Replaced by On Track / Needs Attention / Insufficient Data and peak alignment | §8.6 and §8.9. |
| Moment urgency | Normal / Critical | Normal / Critical. Older "Important" moments are read as Critical | §8.2. |

---

# 1. Product overview

## 1.1 Objective

Help users understand their cognitive readiness, find when they tend to perform best, and prepare for important
upcoming Moments. Hercules combines physiological, behavioural, environmental and cognitive-performance signals to
estimate daily readiness, and answers three questions:

1. How ready am I to perform cognitively today?
2. When do I tend to perform best?
3. What can I do to prepare for an important upcoming Moment?

## 1.2 Principles

- Local-first: no backend, no account, no sign-in. All scoring and recommendations run on the device.
- Health and assessment data stay on the user's devices.
- Missing data is never interpreted as zero.
- Scoring is deterministic and reproducible; personalization improves with more observations.
- Phone and watch assessments follow the same rules.
- Users can customize the five CRS dimension weights and restore the defaults with explicit confirmation.
- Optional sensors never become mandatory.
- Confidence and data coverage are shown separately from readiness.
- No medical or clinical claims.

## 1.3 Structure

Exactly three primary menus.

- **Home:** daily Cognitive Readiness Score (CRS), classification, confidence and coverage, dimensions and
  contributing metrics, Peak Cognitive Window, history of readiness and assessments, today's Beat the Clock.
- **Moments:** up to five active future Moments (title, urgency, date, hour); readiness tracking as each approaches;
  personalized preparation; event-day review; history of completed and cancelled Moments.
- **Setup:** assessment settings, health data sources and Health Connect permissions, phone and watch connection,
  sensor capabilities, scoring weights, personal baselines, data availability, reminders, About.

## 1.4 Scope

Included in V1: daily CRS; five classifications; five dimensions; confidence and coverage; Peak Cognitive Window;
Beat the Clock on phone and watch; Health Connect; Wear OS Health Services; ambient light; optional sensor
detection; personal baselines; customizable weights with confirmed reset; up to five Moments with preparation;
local persistence; phone-watch sync; history.

Excluded: cloud backend, remote database, accounts, cross-phone sync, cloud AI, medical diagnosis, clinical
cognitive assessment, EEG, mandatory access to any wearable brand.

---

# 2. Home: daily Cognitive Readiness

## 2.1 Output

CRS from 0 to 100, estimated from the available signals.

## 2.2 Classifications

| Score | Classification |
|---:|---|
| 85–100 | Prime |
| 70–84 | Good |
| 55–69 | Fair |
| 40–54 | Low |
| 0–39 | Drained |

Product categories, not clinically validated thresholds.

## 2.3 Dimensions and default weights

| Dimension | Default |
|---|---:|
| Sleep & Recovery (S) | 40% |
| Cardiovascular Readiness (H) | 20% |
| Physical Activity (A) | 10% |
| Circadian Alignment (C) | 10% |
| Cognitive Performance (T) | 20% |

`CRS = 0.40·S + 0.20·H + 0.10·A + 0.10·C + 0.20·T − extras`, each dimension normalized to 0–100 (default
configuration v2). Sleep leads because it is the only signal with causal evidence for next-day cognition (Van Dongen
2003, Belenky 2003, Lim & Dinges 2010); heart signals are associations (Forte 2019, Buchheit 2014). The weights
remain product hypotheses, not validated values. A stored v1 default follows v2; custom weights are kept. The engine never hardcodes them: it
reads the active `ScoringConfiguration`.

## 2.4 Sleep & Recovery

`S = 0.55·D + 0.25·K + 0.20·Q` (sleep load, regularity, continuity). Within a dimension, if a component is missing
the remaining components are re-weighted; the dimension is available when duration (D) is available.

- **Sleep load (D):** from `SleepSessionRecord` (Health Connect) or the Hercules watch. Weighted hours of the last
  3 consecutive nights (0.60 / 0.25 / 0.15), since short sleep accumulates (Van Dongen 2003). 7–9 h scores 100
  (Watson 2015); 6 h ≈ 60, 5 h ≈ 35; long sleep declines gently (Li 2022).
- **Consistency (K):** circular standard deviation of the sleep midpoint over the previous 14 valid nights (circular
  maths handles sessions that cross midnight). Needs at least 5 nights.
- **Continuity (Q):** sleep efficiency (asleep ÷ time in bed) from awake time or stages when the provider reports
  them. Stages are never scored; they only mark a Health Connect night as coming from a wearable's own algorithm,
  which then wins over the Hercules watch's detection (§14).

## 2.5 Cardiovascular Readiness

`H = 0.70·V + 0.30·R` (HRV RMSSD, resting heart rate), both scored as deviations from the personal baseline
(21–28 days), never against universal absolutes. HRV is ln(RMSSD) averaged over the last 3 nights against the
baseline (Plews 2013/2014), which smooths single-night noise. If only one is available it carries the dimension; the two
together can never exceed the dimension weight. Ordinary heart rate is stored as supporting information and not
scored; respiratory rate is an optional extra that can only deduct (§6.1).

## 2.6 Physical Activity

`A = 0.20·P + 0.50·I + 0.30·E` (steps, activity intensity, low-movement time), scored on the previous complete day
with target ranges (more is not always better).

- **Steps:** the Hercules watch total on days it was worn; otherwise the Health Connect aggregate (which
  de-duplicates providers). Never both.
- **Intensity:** minutes in exercise sessions, overlapping sessions merged so no minute counts twice.
- **Low-movement time:** waking hours with fewer than 250 steps. Scored only when hourly step data is reliable.
  Called "low-movement time", never "sedentary".

Overlap is limited by construction: steps measure volume, intensity counts only exercise minutes, low-movement
counts only idle hours.

## 2.7 Circadian Alignment

`C = L`, light-exposure alignment from the phone's `Sensor.TYPE_LIGHT` on the previous complete day: bright
mornings, daytime exposure, and dim evenings score well (Brown 2022: ≥ 250 lux by day, ≤ 10 lux in the 3 hours
before bed; full marks at those levels, falling off away from them). Requires samples in at least two of the three periods.
Missing samples never mean darkness. Time of day is not a CRS bonus, and sleep consistency is not counted again here.

## 2.8 Cognitive Performance

`T = 0.70·Accuracy + 0.20·Consistency + 0.10·Bias` from the latest valid, scored Beat the Clock session of the
day. Without one, the dimension is unavailable.

---

# 3. Customizable scoring weights

- All five dimension weights are editable in Setup, in whole percent. Weights cannot be negative; a dimension may
  be 0%; the total must be exactly 100% (validated with a tolerance of 1e-6 on the stored fractions); all-zero is
  invalid. Invalid input is rejected, never silently normalized.
- The UI shows when weights differ from the defaults ("Custom").
- Saving creates a new `CUSTOM` configuration (new id, `updatedAt`); history is kept, never overwritten.
- **Reset to default weights:** asks for explicit confirmation, explains that the recommended weights return and
  that later scores may differ, applies only after confirmation, then recalculates today's score. Cancel changes
  nothing. Reset never deletes health records, assessments, baselines, Moments or score history.
- Two configuration types: `DEFAULT` (versioned app configuration) and `CUSTOM`.
- System-controlled (not user-editable in V1, versioned by the team): normalization, baselines, outliers,
  data-quality rules, missing-data rules, assessment validity, classification boundaries, internal component
  weights.
- **History integrity:** every stored score keeps the configuration id and version, the actual weights, the engine
  version, the dimension and component scores, the coverage and the calculation time. Past scores are never
  recomputed when weights change; only today's latest result is.
- **Late data:** every refresh (each sync) recalculates today and the previous 2 days with the data known now,
  each with the weights it was first scored with, because sleep, steps and watch data often arrive hours late.
  Older days are never touched.
- A future dimension means a new configuration version and an explicit migration, explained to the user.
  Customization is personalization, not validation; the app never claims custom weights are more accurate.

---

# 4. Beat the Clock

## 4.1 Purpose

The user sees a target duration, taps START, and taps STOP when they believe it has elapsed. The screen counts the
first three seconds ("1… 2… 3…") so the pace is clear, then the count disappears. It measures time perception, not reaction
time or intelligence.

## 4.2 When to play

Optional, any time, as often as the user likes. There is no schedule. The day's Beat the Clock result is the median of
that day's scored games (accuracy, consistency and bias each taken as the median). Each game is filed under the part of
the waking day it was played in, only to find the peak window:

| Part of day | Hours after waking |
|---|---|
| Morning | 0–4 h (and anything before waking) |
| Afternoon | 4–8 h |
| Evening | 8 h onward |

Wake time comes from last night's sleep, otherwise the median of the last 14 recorded wake-ups (07:00 until there is
any history). There is no wake-time setting.

## 4.3 Session

Three rounds that get longer: 15–19 s, then 20–24 s, then 25–30 s, each a random whole second in its band
(game version 3). Each round opens with a visible "1… 2… 3…" count, then the count disappears. An optional
energy/stress check comes before the last round. There are no practice games: every valid game is scored from the
first one. The baseline is per device, across all times of day, from the same game version only.

## 4.4 Timing

`SystemClock.elapsedRealtimeNanos()` only (monotonic). Per round: target, start, stop, actual, signed error,
absolute error, relative error `(actual − target) / target`, device, validity.

## 4.5 Validation

- A round is invalid if the app lost focus or was interrupted while it ran, or if the tap is implausible
  (outside 0.2×–4× the target).
- A session is **partially valid** with at least 2 of the 3 rounds valid; **fully valid** with all 3.
- Sessions that fail are stored as invalid and never scored.

## 4.6 Scoring

- `accuracyError = median(|relativeError|)`, `bias = median(relativeError)`,
  `consistencyError = MAD(relativeError)`.
- Accuracy and consistency: `ratio = current / baseline`,
  `score = clamp(50 − 20·log2(max(ratio, 0.125)), 0, 100)`.
- Bias: `100 · (1 − clamp(|bias| / max(accuracyError, 0.01), 0, 1))`.
- The baseline is a blend: a typical-player reference (accuracy error 0.12, consistency error 0.08) mixed with the
  median of the same device's earlier valid sessions, personal share = min(sessions, 3) / 3. The first game is
  scored against the reference alone; from the third game on, the baseline is fully personal.
- Valid games of today that have no score (for example from an older rule set) are re-scored on the next refresh.

## 4.7 Device-specific baselines

Two baselines: one for the phone, one for the watch, each across all times of day. Phone and watch results are
never mixed without normalization.

---

# 5. Peak Cognitive Window

- Uses the last 28 days of valid sessions (older practice-flagged games excluded). Eligible only with ≥ 14 observation days and, in every
  window, ≥ 7 valid sessions on ≥ 7 distinct days.
- Performance per session is normalized per device (robust z of the accuracy error against that device's own
  28-day median and MAD, sign flipped so higher is better), then averaged per window.
- `peak = argmax(mean performance)`. If the gap to the runner-up is smaller than its standard error (minimum 0.2),
  the result is `NO_CLEAR_PEAK`. Only broad windows are predicted.
- Stored: predicted window, per-window performance, confidence, valid session count, last update, model version.

---

# 6. Missing data and confidence

- Missing is never zero: no HRV is not zero HRV, no steps record is not zero steps, no light is not darkness, no
  assessment is not poor performance.
- `coverage = Σ weights of available dimensions` (active configuration; 0% dimensions count for nothing).
- `CRS = Σ(weight · score) / Σ(available weights)`. A number is published as soon as any one dimension is
  available (one data point is enough). Only with nothing at all is the result `INSUFFICIENT_DATA`.
- Confidence combines how many signals are in today (coverage) with how many full earlier days of data back them:

| Coverage | Full days of data | Confidence |
|---:|---:|---|
| ≥ 90% | ≥ 7 | High |
| ≥ 70% | ≥ 3 | Medium |
| otherwise | | Low |

- Next to the score: the level, "N of M signals · D days of data", and below High what would raise it
  ("To raise it: add a Beat the Clock game; 2 more days of data."). Phone (Home and Why this score) and watch.

Confidence and classification are independent.

## 6.1 Optional extras

Optional extras never add points; they can only subtract, capped at −10 in total, and need 7 days of history:
respiratory rate ≥ 2 robust SD above usual (−5), skin temperature ≥ 2 SD above usual (−5), overnight SpO₂ < 90%
or ≥ 4 points below usual (−5), and glucose in the last 24 hours under 3.9 mmol/L (−5) or under 3.0 (−10)
(IHSG/ADA 2017). From a CGM it must last about 15 minutes (3 readings; Battelino 2019), since single CGM dips are
often pressure artefacts; one meter reading is enough. Deductions are listed in Why this score. Glucose is not a weighted dimension (it changes by
the hour and only lows have clear cognitive evidence); a reading under 3.9 from the last 3 hours also shows a live
banner on Home, and under 3.0 it says to treat first.

---

# 7. Personalization and baselines

| Valid days | Baseline |
|---|---|
| 1–6 | Reference calibration |
| 7–20 | Gradual personalization |
| 21+ | 80% personal, 20% reference |

Per metric: sleep consistency 14 days; HRV, resting HR, activity and light 21–28 days (28-day window); cognitive
per device and window. Robust normalization `z = (value − median) / (1.4826 · MAD)` with a configured minimum
scale when MAD is too small. Today's score always uses the baseline built from earlier days, and changing weights
never resets baselines.

---

# 8. Moments

- Fields: title (required), urgency (Normal or Critical), date and hour (required), time zone (captured
  automatically), status (managed by the app).
- Constraints: at most five active future Moments; at most seven days ahead; never in the past. Moments can be
  edited or cancelled; they complete automatically when their time passes; completed and cancelled Moments stay as
  history.
- Lifecycle: `ACTIVE`, `COMPLETED`, `CANCELLED`.
- Urgency changes recommendation priority, never the CRS.
- **Preparation status** from the latest CRS, classification, confidence and coverage, sleep and recovery trends,
  recent activity, assessments, peak window and time remaining: **On Track**, **Needs Attention** or
  **Insufficient Data**. No separate 0–100 Moment score.
- **Recommendations** (sleep, recovery, activity, light, cognitive preparation, event day), only when supported by
  evidence, and only what is still practical:

  | Time left | Focus |
  |---|---|
  | 5–7 days | sleep consistency, recovery patterns, sustainable activity |
  | 3–4 days | keep routines, watch readiness |
  | 1–2 days | sleep opportunity, avoid unnecessary exertion |
  | Event day | current readiness, peak alignment, immediate preparation |

- **Event day:** latest CRS with confidence and coverage, peak alignment (`WITHIN_PEAK`, `NEAR_PEAK`,
  `OUTSIDE_PEAK`, `UNKNOWN`), recent sleep and recovery, relevant suggestions. The time is treated as fixed:
  advice is about preparing, not rescheduling.
- **Several Moments:** each evaluated on its own. Combined views put Critical and sooner Moments first, merge
  shared recommendations and never show duplicates or contradictions.
- A readiness snapshot is stored for each active Moment every day.

---

# 9. Setup

- **Sensors & data (one page):** Health Connect availability, permissions (request and re-request), background reading and Sync now; then each signal with its sources and per-type status, the watch, and optional extras.
  Required: `SleepSessionRecord`, `HeartRateVariabilityRmssdRecord`, `RestingHeartRateRecord`, `StepsRecord`,
  `ExerciseSessionRecord`. Optional: `RespiratoryRateRecord`, `SkinTemperatureRecord`, `OxygenSaturationRecord`,
  `HeartRateRecord`, `BloodGlucoseRecord`. Display only (Home's Today tiles, never stored or scored): `DistanceRecord`,
  `ActiveCaloriesBurnedRecord`, `FloorsClimbedRecord`. Background read (`READ_HEALTH_DATA_IN_BACKGROUND`) is requested where
  supported.
- **Sync is automatic:** Health Connect is read when the app opens, every 5 minutes while it is open, and about hourly by
  WorkManager in the background. "Sync now" is optional.
- **Phone sensors:** ambient light availability, sampling status and coverage.
- **Devices:** paired watch, sync status, watch sensors (existing on/off grid with confirmation before turning one
  off).
- **Beat the Clock:** nothing to set. Game rules are identical on phone and watch and not editable.
- **Scoring weights:** active configuration, five editable weights, validation, defaults, confirmed reset, version
  and update time.
- **Personal baselines:** per metric median, MAD, sample count, personal share, calibration version.
- **Availability states, never conflated:** hardware unavailable, permission not granted, provider unavailable, no
  recent measurement, measurement invalid, measurement valid.
- **Data:** clear all local data (with confirmation). About: Privacy Policy, Terms & Conditions, version.

---

# 10. Metrics inventory

## 10.1 Core (scored)

| Metric | Source | Dimension |
|---|---|---|
| Sleep duration | Health Connect / Hercules watch | Sleep & Recovery |
| Sleep consistency | Derived | Sleep & Recovery |
| Sleep continuity | Sleep records | Sleep & Recovery |
| HRV RMSSD | Health Connect | Cardiovascular |
| Resting heart rate | Health Connect / Hercules watch | Cardiovascular |
| Steps | Health Connect / Hercules watch | Activity |
| Activity intensity | Exercise sessions | Activity |
| Low-movement time | Hourly steps | Activity, if reliable |
| Ambient light | Phone `TYPE_LIGHT` | Circadian, if enough samples |
| Beat the Clock | Phone and watch | Cognitive, if valid |

## 10.2 Optional extras (deduction only, §6.1)

Respiratory rate, skin temperature deviation, SpO₂, continuous glucose (Health Connect `BloodGlucoseRecord`,
mmol/L). They never add points and never count toward coverage.

## 10.3 Supporting (stored, never scored)

Ordinary heart rate, sleep stages, raw beat-to-beat intervals.

## 10.4 Future (not V1)

Electrodermal activity, barometric pressure, advanced skin temperature, raw beat-to-beat
analysis. None is assumed to exist on every device.

---

# 11. Optional sensors

Every optional integration detects availability, asks for permission only when needed, collects only when
supported and permitted, validates freshness, timestamps, units and quality, stores locally with source device and
provider, never substitutes zero, never blocks the app, and never changes the CRS just because it appears.
Promoting a metric to scoring needs a new versioned configuration.

- **Ambient light:** lux, timestamp, sensor identity and sampling context (`foreground` or `background`). Sampled
  while the app is open and opportunistically by WorkManager; continuous background monitoring is not promised,
  and the phone is not assumed to be near the eyes. "No sensor" and "no recent sample" are different states.
- **Respiratory rate, skin temperature (deviation, never core temperature), SpO₂:** stored with provenance; used only
  as deductions when clearly off the personal usual (§6.1). Ordinary heart rate and sleep stages are supporting data.
- **Continuous glucose (optional):** read from Health Connect when a CGM or meter app writes it; stored in mmol/L
  with provenance, plausibility 1–35 mmol/L, shown under Setup → Sensors & data → Optional extras. Lows deduct
  (§6.1) and show a live banner on Home.
- **Beat-to-beat intervals:** only from compatible devices; not interchangeable with Health Connect RMSSD.

---

# 12. Sensor capability management

`SensorCapabilityRepository` (`getAvailableCapabilities()`, `isAvailable()`) covers phone hardware sensors, Wear
OS sensors, Health Connect record types and permissions, Health Services capabilities and the paired watch. Each
capability has a `CapabilityStatus`: `AVAILABLE`, `PERMISSION_REQUIRED`, `HARDWARE_UNAVAILABLE`,
`PROVIDER_UNAVAILABLE`, `NO_RECENT_DATA`, `INVALID_DATA`, `TEMPORARILY_UNAVAILABLE`. Available does not mean
recent valid data exists. New sensors are added as repositories and metric types; the scoring engine only consumes
normalized domain metrics.

---

# 13. Architecture

- **Domain (`shared/commonMain`, no Android APIs):** metric models, validation, normalization, baseline engine,
  scoring engine, scoring configuration manager, Beat the Clock engine, peak-window predictor, Moment preparation
  engine, capability models.
- **Data:** SQLDelight database (`HerculesDatabase`); repositories for health metrics, sleep, light, assessments,
  Moments, scoring configuration, baselines.
- **Platform:** Health Connect SDK, `SensorManager`, WorkManager, Health Services, Wearable Data Layer.
- **Presentation:** existing Compose UI (phone) and Wear Compose UI (watch).

| Responsibility | Technology |
|---|---|
| Language | Kotlin (+ KMP shared module) |
| Local database | SQLDelight (see §0.2) |
| Async | Coroutines / Flow |
| Health data | Health Connect |
| Phone sensors | SensorManager |
| Deferred jobs | WorkManager |
| Wearable health data | Health Services |
| Phone-watch | Wearable Data Layer |
| Keys | Android Keystore (planned: database encryption is not in V1 yet) |

The scoring engine never touches `Context`, `SensorManager`, Health Connect, Wear OS, UI or database classes.

---

# 14. Health Connect

Check availability, request permissions, read with appropriate ranges (initial import 30 days, then the last 3 days
on every sync), keep provenance (`dataOrigin` package and device), normalize units, de-duplicate by record id,
validate quality, store locally, handle revoked permissions and missing record types. Steps use the aggregation
API; overlapping providers are never summed blindly. WorkManager runs deferred refreshes; opening the app always
refreshes.


**Which source wins (per signal, per day):**

| Signal | Most reliable first | Why |
|---|---|---|
| Sleep | a wearable's staged night in Health Connect → Hercules watch → other Health Connect session | Vendor sleep algorithms are validated; Hercules' detection is simpler; unstaged sessions are often phone estimates |
| HRV | Health Connect → Hercules watch | Vendors measure overnight at rest; the Hercules watch only catches random beat-to-beat readings |
| Resting heart rate, steps | Hercules watch (when worn that day) → Health Connect | On the body all day; a phone isn't always carried |
| Ambient light | phone | Only the phone has the sensor |

Sources are never summed. Health Connect has no heart or HRV sensor of its own; those values come from another wearable's app.

---

# 15. Wear OS companion

The watch runs Beat the Clock independently (same rules, monotonic timing, interruption handling), collects
supported data with `PassiveMonitoringClient` (batched, unpredictable delivery), stores everything locally first,
and keeps working without the phone. `MeasureClient` is only for short explicit measurements and `ExerciseClient`
only for explicit workouts. Missing capabilities are detected and handled without crashing.

---

# 16. Phone-watch sync

Wearable Data Layer only; no custom Bluetooth.

- **MessageClient:** watch → phone batches (samples, sessions, assessments) with an acknowledgement listing the
  stored ids. Retries are idempotent (UUIDs / deterministic ids), original timestamps and device identity are
  kept, nothing is marked synced before the phone acknowledges it.
- **DataClient:** phone → watch state (latest readiness, Moments, brief, settings) and sensor configuration.
- **CapabilityClient:** finds the companion app.
- The phone validates incoming records, stores them, and recalculates (today and the previous 2 days, §3).

---

# 17. Local-only data

No remote API, cloud database, cloud auth, server scoring, cloud sync or remote inference. Each device has its own
database; they sync when connected. Works offline. No account. The database lives in app-private storage; encrypting it with a Keystore-managed key (SQLCipher) is
planned but not yet built. Only
necessary permissions; revocation handled; unnecessary raw streams avoided (raw watch samples are kept 30 days at
most). Setup can clear all local data. Android backups are disabled.

---

# 18. Data model

Timestamps are UTC epoch milliseconds; local day keys (`yyyy-MM-dd`) and time zone ids are kept where they matter.
Raw observations are stored separately from derived scores.

| Entity | Table | Notes |
|---|---|---|
| HealthMetric | `health_metric` | Extensible for every metric type, with unit, source package and device, quality, collection method |
| SleepSession | `sleep_session` (all provider sessions) → `sleep_night` (the resolved night) | Stages and efficiency when present |
| CognitiveSession / CognitiveRound | `cognitive_session`, `cognitive_round` | Device, window, practice flag, scores, game version |
| UserBaseline | `user_baseline` | Metric, device, window, median, MAD, samples, calibration version |
| ScoringConfiguration | `scoring_config` | Full history, one active |
| ReadinessScore | `readiness_day` | Score or insufficient, classification, confidence, coverage, dimensions, config id and version, weights, engine version |
| PeakWindow | `peak_window` | Prediction history |
| Moment | `plan_event` (+ urgency, status, time zone, timestamps) | |
| MomentReadinessSnapshot | `moment_snapshot` | One per Moment per day |
| LightExposureSample | `light_sample` | Lux, sensor, context |
| ExerciseSession | `exercise_session` | Provider sessions; watch workouts stay in `workout_session` |

---

# 19–21. Interfaces, scheduling, security

Conceptual interfaces (`HealthDataRepository`, `ScoringEngine`, `ScoringConfigurationRepository`,
`BaselineEngine`, `PeakWindowPredictor`, `MomentRepository`, `MomentPreparationEngine`,
`SensorCapabilityRepository`) are implemented by the shared domain classes and platform repositories. The UI
always confirms before restoring defaults.

WorkManager handles deferred Health Connect refresh, recalculation, light sampling, sync retries and reminders.
Readiness and Moments are re-evaluated when data arrives, an assessment completes, weights change, a Moment is
created or edited, and whenever the app opens. WorkManager is never relied on for exact timing.

All data stays local in app-private storage (database encryption is planned, not yet built), and the app never transmits health
records anywhere except to the user's own paired watch.

---

# 22. Testing

Unit tests in `shared` cover: classification boundaries, every formula, custom weights and validation, zero
weights, reset, configuration versioning, reproducible history, missing data, coverage, confidence, baselines
(initialization, blending, MAD = 0, outliers), cognitive scoring (early and late errors, interruptions, practice
exclusion, device-specific baselines), peak-window eligibility and no-clear-peak, Moment constraints (five active,
seven days, past), lifecycle, recommendation priority and de-duplication, optional sensor absence, stale and
invalid data. Integration and device-compatibility checks follow §22 of the master specification.

---

# 23–24. Roadmap and scientific validation

Weights and formulas are hypotheses. Collect 4–8 weeks of data before tuning; evaluate each signal's added value;
avoid circular validation (Beat the Clock is part of the CRS, so the CRS predicting it proves nothing); no machine
learning in V1; promote optional metrics only after their value and overlap are evaluated.

# 25. References

Formula v2 (scoring engine 3.0.0) rests on: Van Dongen et al. 2003 (*Sleep*, chronic restriction); Belenky et al.
2003 (*J Sleep Res*); Lim & Dinges 2010 (*Psychol Bull*, meta-analysis); Watson et al. 2015 (AASM/SRS 7 h
consensus); Li et al. 2022 (*Nature Aging*, 7 h optimum); Phillips et al. 2017 and Windred et al. 2024 (sleep
regularity); Plews et al. 2013/2014 and Buchheit 2014 (ln RMSSD rolling averages); Forte et al. 2019 (HRV and
cognition review); Natarajan et al. 2020 and Smarr et al. 2020 (wearable respiratory rate and skin temperature
deviation); International Hypoglycaemia Study Group / ADA 2017 (glucose < 3.9 / < 3.0 mmol/L); Brown et al. 2022
(*PLoS Biol*, light recommendations); Chang et al. 2012; Wheeler et al. 2020 (exercise and cognition); Hilditch &
McHill 2019 (sleep inertia); Basner & Dinges 2011 (PVT); Schmidt et al. 2007 (time-of-day effects).

Health Connect data types, Wear OS Health Services, Android Sensor API, Wearable Data Layer, WorkManager
(developer.android.com).
