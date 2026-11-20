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
| Morning check-in (energy/stress) | Not part of the CRS | Removed. The daily task on Home is now **Reaction Test** | The five CRS dimensions are fixed by the spec; self-reports are not one of them. |
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
  contributing metrics, Peak Cognitive Window, history of readiness and assessments, today's Reaction Test.
- **Moments:** up to five active future Moments (title, urgency, date, hour); readiness tracking as each approaches;
  personalized preparation; event-day review; history of completed and cancelled Moments.
- **Setup:** assessment settings, health data sources and Health Connect permissions, phone and watch connection,
  sensor capabilities, scoring weights, personal baselines, data availability, reminders, About.

## 1.4 Scope

Included in V1: daily CRS; five classifications; five dimensions; confidence and coverage; Peak Cognitive Window;
Reaction Test on phone and watch; Health Connect; Wear OS Health Services; ambient light; optional sensor
detection; personal baselines; customizable weights with confirmed reset; up to five Moments with preparation;
local persistence; phone-watch sync; history.

Excluded: cloud backend, remote database, accounts, cross-phone sync, cloud AI, medical diagnosis, clinical
cognitive assessment, EEG, mandatory access to any wearable brand.

---

# 2. Home: cognitive score (engine 5.0.0)

The score is a research-based estimate from 1 to 100 of how alert you are right now. Sleep and time awake are the
causal drivers (sleep-restriction experiments: Van Dongen et al. 2003 found lapses grow near-linearly with
wakefulness beyond a critical amount, however the sleep loss happened); HRV, resting heart rate and skin
temperature only mark recovery state, so they scale the prediction instead of adding to it; the Reaction Test is the
one direct measurement and is blended in when recent. Source of the design: the "Cognitive Score Formula" handoff
(20 Nov 2026). Labels below: **[Verified]** checked against a source, **[Verify]** widely cited, not yet checked,
**[Design]** our own tunable choice.

Pipeline: sleep model → predicted alertness P → × recovery modifier M → blended with the game G when one is recent →
tuned to the user over time.

## 2.1 Output and bands

Score 1–100, rounded. Bands (cut-offs [Design], re-check once real score distributions exist):

| Score | Band |
|---:|---|
| 85–100 | Prime |
| 70–84 | Good |
| 55–69 | Fair |
| 40–54 | Low |
| 1–39 | Drained |

The score follows the day: it is highest soon after a good night and falls as hours awake add up. Present it as a
wellness estimate, never a medical or safety assessment; never tell users they are fit or unfit to drive or operate
machinery. Caffeine, alcohol, medication and illness are not modelled; they show up only through M and the game.

## 2.2 Sleep model (McCauley et al. 2013)

McCauley P, Kalachev LV, Mollicone DJ, Banks S, Dinges DF, Van Dongen HPA. *Sleep* 2013;36(12):1987–1997
(PMC3825450) [Verified: equations from its Table 1, parameters from its Table 2]. It predicts PVT lapses (reaction
times ≥ 500 ms) under normal sleep, total and chronic sleep loss, night shifts and naps. State: p (predicted lapses),
u (slow homeostat, days to weeks), κ (circadian amplitude). t is local clock time in hours, τ = 24 h.

```
Wake:  dp/dt = α_w·(p + β·u) + κ·(c(t) + μ_w)        du/dt = η_w·u          dκ/dt = λ·κ·(1 − κ/ξ)
Sleep: dp/dt = α_s·(p + β·u) + κ·(c(t) + μ_s) + α_s·β/η_s
                                                     du/dt = η_s·u + 1      dκ/dt = −λ·κ
c(t) = sin(2π·(t − φ)/24),   η_s = A_c/(A_c − 1)·η_w,   A_c = 20.2/24
α_w = −0.028/h   α_s = −0.26/h   β_w = β_s = −0.26   η_w = 0.0074/h   μ_w = 0.33   μ_s = −1.5
φ = 21.2 h   λ_w = −λ_s = 0.49/h   ξ = 1.09
```

- Implementation (`SleepModel`): Euler steps of 6 minutes through the window's sleep episodes.
- Initial state: the periodic steady state of 8-hour nights ending at the usual wake time, reached by simulating 20
  days before the first recorded night (the paper starts from its baseline schedule's steady state; its closed form,
  supplement Eqs. S13–S17, is not reproduced) [Design].
- φ is moved by (usual wake time − 07:30), since the paper's subjects woke around then [Design]; calibration then fits
  it (§2.7). κ's effect can be scaled (calibration × the body-clock slider; 1 = the paper).
- Sleep episodes: the resolved main nights (§14 precedence), plus naps: any other Health Connect session or watch
  sleep episode of 15 minutes or more that doesn't overlap a night. A missing night after the first recorded one is
  filled with the usual window (median wake time and time in bed) [Design]. Sleep debt carries over automatically.

## 2.3 From lapses to predicted alertness

`P = 100 − 4·max(0, p − 2)`, clamped to 0–100 [Design]; with the sleep-influence slider `P_adj = 100 − k_s·(100 − P)`.
Two lapses or fewer is a normal rested result. Reference points with 8-hour nights and a 07:00 wake-up: about 92 in
the morning and about 85 by bedtime; after 24 h awake about 40; after 48 h lower still; 14 nights of 4 h about 30.
Recheck the scale against real Reaction Test data.

## 2.4 Body clock

Part of the model above: c(t) is lowest (best performance) around φ − 6 h, 15:12 at the paper's φ, and highest
around 03:12. Its amplitude κ builds over about 20 h awake and fades during sleep, so a night awake hits hardest in
the early morning.

## 2.5 Recovery modifier (M)

Each signal is z-scored against the user's own trailing 10 days from the same source:
`z = (today − mean) / sd` (sd floored per signal so a flat history can't explode it; z clamped to ±4). A signal with
fewer than 5 earlier days is dropped, not imputed [Design].

```
penalty = w_h·max(0, −z_lnHRV) + w_r·max(0, z_RHR) + w_t·max(0, z_temp) + optional terms (§2.14)
M = clamp(1 − penalty, 0.7, 1.0)
```

- HRV as ln(RMSSD) overnight [Verify]; resting HR overnight or morning; skin temperature as the overnight deviation.
- Only unfavourable deviations count: M never exceeds 1, a better-than-usual reading doesn't raise the score.
- Starting weights w_h = w_r = w_t = 0.05, fitted later (§2.7) [Design]. Don't raise defaults without game data:
  evidence on wearables predicting performance is mixed (MHSRS abstract; Personal and Ubiquitous Computing 2025).

## 2.6 Reaction Test (G) and the final score

- G is scored against the user's own history, per device: from 5 earlier valid tests in the last 10 days,
  `G = 100 × share of those tests that were slower` (ties count half); before that, the 50-anchored speed score of
  §4.6. Then − 5 per false start beyond 2. Lapse threshold 500 ms [Verify].
- With a test that ended in the last 2 hours (median of them if several, either device):
  `Score = g·G + (1 − g)·(P_adj·M)`, g = 0.6 [Design]. Otherwise `Score = P_adj·M`. Clamped to 1–100.

## 2.7 Calibration

Every valid test is counted (the counter survives the 10-day window). From 20 tests [Design], each new test refits on
the window's tests (model state recomputed at the time each test started, game excluded):

- the body-clock peak (0–23.5 h, half-hour grid; φ = peak + 6 h) and κ's scale (0.5, 0.75, 1, 1.25, 1.5×) by grid
  search on the squared error of G − P·M, one model run over the window per candidate;
- w_h, w_r, w_t by least squares of `1 − G/P` on each signal's unfavourable z, bounded 0–0.2;
- the paper's rate constants stay fixed. At least 8 tests in the window, otherwise the last fit is kept.
- The phone sends its fit to the watch (it sees both devices' tests); the watch keeps its own only if it has counted
  more tests.

Only the fitted values (`Calibration`: weights, φ, a, counter) are kept beyond 10 days; no raw data. Precedent:
2B-Alert personalises a group fatigue model from individual PVT data [Verify]. Setup → Score settings shows the
progress ("Personal tuning").

## 2.8 Confidence

Every score carries 0–100%, from the data behind it (`ReadinessCalculator.confidenceParts`):

| Part | Full | Partial |
|---|---|---|
| Sleep | Watch-detected 35 | Phone estimate or entered 15; usual sleep assumed 5 |
| Days of data in the window | 8–10 days: 15 | 5–7 days: 8 |
| HRV | Watch 8 | Phone 4 |
| Resting heart rate | Watch 8 | Phone 4 |
| Skin temperature | Watch 8 | — |
| Reaction Test | In the last 2 h: 19 | 20+ tests tuned: 12 |

Optional sensors add up to 7 (glucose 3, each other Health Connect sensor 1; each needs 5 days of baseline; ECG
would add 3 but there is no ECG source on Android, §2.14). Custom settings subtract 5. `confidence = min(100, core + optional − adjustments)`.
Without watch sleep it is capped at 74% (never High); on usual sleep or a test alone at 49% (Low).
Labels: High 75–100%, Medium 50–74%, Low under 50%. Home and Why this score list each part with its points and say
what would raise it; the watch shows "72% · sleep: watch · 9 days of data".

## 2.9 Score settings (Setup → Score settings)

Sliders scale the model's own parameters, so the structure stays intact [Design]:

| Slider | Changes | Range | Default |
|---|---|---|---|
| Sleep influence k_s | `P_adj = 100 − k_s·(100 − P)` | 0.5–1.5 | 1.0 |
| HRV / resting HR / temperature sensitivity | × w_h / w_r / w_t | 0–2× | 1× |
| Body clock strength | × a | 0–2× | 1× |
| Reaction Test weight g | replaces 0.6 | 0–0.8 | 0.6 |
| Glucose / blood oxygen / breathing sensitivity | × their 0.05 weights | 0–2× | 1× |

The time constants and the 0.7 floor are not adjustable. Multipliers apply on top of calibrated values. "Reset to
defaults" asks first. Any non-default setting costs 5 confidence points. Settings are stored in `kv`
(`score_settings`) and stamped on every score (`ConfigStamp.settings`); recalculated past days use their own stamp.
Migration: the engine-4 percentage weights are discarded; users start at defaults, and anyone who had custom
weights sees a one-time notice on the settings page.

## 2.10 Missing sleep

The first case that fits [Design]:

1. Entered or phone-estimated sleep: an unstaged Health Connect session, or Hercules' own screen-use estimate
   (optional Usage Access, Setup › Sensors & data). The estimate is the longest stretch from the screen going off to
   the first unlock between 18:00 and 14:00, 3–14 h long; checks under 10 minutes in the night don't split it and
   count as awake (`ScreenSleep`). Only screen on/off and lock-screen events are read, never app use. It ranks below
   every Health Connect session and the watch. The sleep model runs as normal; confidence at most Medium.
2. No sleep, but a test in the last 2 hours: `Score = G`, confidence Low.
3. The usual sleep window from stored nights, once its wake time has passed: model as normal, confidence Low.
4. Nothing usable: no score; Home asks for a night of sleep or a test.

Between midnight and the usual wake time with no night recorded, no score is published ("Today's score starts when
you wake up").

## 2.11 Sources

One source per signal per day, never mixed, each with its own baseline; the source is stored and shown in Why this
score.

| Signal | Primary (watch) | Fallback (phone) | Effect of fallback |
|---|---|---|---|
| Sleep | Hercules watch, or a staged Health Connect night (stages need a wearable) | Unstaged Health Connect session; times entered by the user | Confidence at most Medium |
| HRV, resting HR | Health Connect from a wearable, else the Hercules watch | Health Connect recorded on a phone (`Device.TYPE_PHONE`, stored as `metadata = device:phone`) | weight × 0.5 |
| Skin temperature | Health Connect from a wearable | — | term dropped |

Which night is used still follows §14 (staged Health Connect → Hercules watch → unstaged Health Connect).

## 2.12 Retention

All data stays on the device; there is no server. Raw sensor values, nights, Health Connect copies, light, tests,
daily summaries, evaluations and scores older than 10 days are deleted once a day (`CognitiveService.purgeOld`,
`RetentionPolicy` for watch samples). The first Health Connect import reads 10 days. Baselines, the game percentile
and calibration use the same window. Kept beyond it: calibration values, score settings, Moments with their tasks,
ratings and snapshots, personal records.

## 2.13 Engine status and evidence

Engine 5 runs McCauley et al. 2013 (§2.2), replacing the interim two-process fallback of the handoff. Still to verify:
the lapse-to-P scale (§2.3) against real data, the 500 ms lapse threshold for our 3-minute test, log-RMSSD, the
70 mg/dL glucose threshold. Expect weaker predictions right after recovery sleep; the game residual corrects for it.

## 2.14 Optional sensors

Used only when present; never block a score. Same pattern as §2.5, weight 0.05 each, own slider:

- **Glucose (CGM or meter):** the last 24 hours' mean against earlier daily means, penalising readings below the
  user's baseline; a low under 3.9 mmol/L (70 mg/dL [Verify]; a CGM must stay low for 3 readings, Battelino 2019)
  counts as at least z = −2. A reading under 3.9 in the last 3 hours also shows a live banner on Home.
- **SpO₂** (lower is worse) and **respiratory rate** (higher is worse).
- **ECG:** would replace HRV with an ECG-derived value. Not available: Health Connect and Wear OS Health Services
  have no ECG data type, and Samsung's watch ECG needs a partner licence. Its 3 confidence points stay unearned.

The 0.7 floor applies however many terms are present; optional sensors never make up for missing core data.

## 2.15 Other rules

**Past days.** A past day is stored as its score at wake time + 6 hours, so the week shows the middle of each day, not
its last minutes awake. Every refresh recalculates today (at the current time) and the previous 2 days with the data
known now and each day's own settings; older days are never touched.

**Screen stays on.** During the 3-minute test, phone and watch keep the screen on; leaving the app stops the test
without saving it.

**Moment preparation stays in scope.** Prep tasks cover only sleep, recovery, light, stress and sensors, never the content of the Moment: final stretch adds skipping alcohol and no caffeine after 14:00 the day before; the day itself suggests a 10-minute walk and 2 minutes of box breathing. Reminders: the bedtime reminder is keyed to the wake-up day, so it fires once per night even past midnight; "how did it go?" reads Moments that started in the last 12 hours.

**Watch sleep episodes.** The night is the longest sleep episode in the window that ends at 03:00 or later; sleep
that ends earlier (an evening nap, the phone down for the evening) is a nap of the day before, for every source. Awakenings up to 60 minutes stay in
the episode as time awake; a longer gap starts a new episode, so a nap is never merged into the night. A night still
in progress is not reported. If the watch never reports waking, the night ends at its last pulse reading.

**Recalculation.** Every new batch of data recalculates: a watch sync on the phone, Health Connect every 15 minutes
in the background and every 5 minutes while open, a game, a sleep correction. The watch hands new readings to the
phone at most every 10 minutes and keeps one background pulse reading per 20 seconds. The phone app also asks the watch for its data at most every 15 minutes, only while a watch is connected (never in airplane mode), because phones that block background starts (Xiaomi Autostart) can drop the watch's own deliveries.

**Sleep history.** `refresh()` also resolves any night in the window that has a valid Health Connect session but no
resolved night yet, so the first import feeds the sleep model at once, and re-resolves every phone-tier night
(unstaged Health Connect, screen-use estimate) so watch sleep replaces it whenever it arrives. Nights you edited are
never replaced.

---

# 3. Customizable scoring weights (until engine 4)

Replaced by the score settings of §2.9. Steps, activity and light were dimensions up to engine 4; they are still
collected and shown, but are not part of the score. Scores stored by engine 4 keep their weights in their stamp.

---

# 4. Reaction Test

## 4.1 Purpose

A 3-minute reaction test modelled on the brief Psychomotor Vigilance Test (PVT-B, Basner & Dinges 2011), the
standard measure of how sleep loss and fatigue slow attention. Herc appears at random moments; the user taps anywhere
as fast as they can. It measures reaction speed and attention lapses, not intelligence. (Game versions 1–3 were a
time-estimation game; it was removed with all its sessions, rounds and baselines in database migration 3.)

## 4.2 When to play

Optional, any time, as often as the user likes. There is no schedule. The day's Reaction Test result is the median of
that day's scored tests. Each game is filed under the part of
the waking day it was played in, only to find the peak window:

| Part of day | Hours after waking |
|---|---|
| Morning | 0–4 h (and anything before waking) |
| Afternoon | 4–8 h |
| Evening | 8 h onward |

Wake time comes from last night's sleep, otherwise the median of the last 14 recorded wake-ups (07:00 until there is
any history). There is no wake-time setting.

## 4.3 Session

Game version 4, run by the shared `ReactionRun` loop so phone and watch are identical. 45 trials (false starts
included), about 3 minutes: wait a random 1–4 s (whole milliseconds), show Herc, wait for a tap (at most 3 s), show
the reaction time (or "Too soon") for 1 s, repeat. The screen shows "N of 45" and a progress bar (a ring on the
watch). A millisecond timer starts when Herc is on screen and freezes at the tap, as on the PVT. A tap during the wait is a false start and starts the next trial. The whole screen
is the tap target. An optional energy/stress check comes after the test (skippable; never scored). There are no
practice games: every valid test is scored from the first one.

## 4.4 Timing

`SystemClock.uptimeMillis()` (monotonic): the touch-down time of the tap, against the first frame that shows Herc.
Per trial: wait, reaction time, validity. Touchscreen and display delay (about 50–100 ms) are the same from test to
test on one device, which is why baselines are per device.

## 4.5 Validation

- A trial is a **false start** if the tap came before Herc appeared or within 100 ms of it (anticipation).
  No tap within 3 s is recorded as a 3 000 ms reaction (a lapse).
- A test is **fully valid** with ≥ 30 reactions, **partially valid** with ≥ 15, and **invalid** with fewer or when
  more than a quarter of taps are false starts. Leaving the app stops the test; it is not saved.
- Invalid tests are stored and never scored.

## 4.6 Scoring

- `speed = mean(1000 / reaction ms)` over the reactions (the main PVT outcome; it reflects both slowing and lapses).
  Also stored: median reaction time, **lapses** (reactions ≥ 500 ms, the classic PVT threshold, chosen over PVT-B's
  355 ms because a touchscreen adds delay) and false starts.
- `speedScore = clamp(50 + 150·ln(speed / usual), 0, 100)`: 10% slower than usual ≈ 34, 10% faster ≈ 64.
- `game score = G − 5 × (false starts beyond 2)`, clamped to 0–100, where G is the percentile of §2.6 from 5 earlier
  tests in 10 days, else `speedScore`.
- `usual` is a blend: a device reference (phone 3.0/s ≈ 330 ms, watch 2.7/s ≈ 370 ms) mixed with the median speed
  of the same device's earlier valid tests in the last 10 days, personal share = min(tests, 3) / 3. The first test
  is scored against the reference alone; from the fourth on, against the player's own usual.
- Valid tests of today that have no score are scored on the next refresh.

## 4.7 Device-specific baselines

Two baselines: one for the phone, one for the watch, each across all times of day. Phone and watch results are
never mixed without normalization.

---

# 5. Peak Cognitive Window

- Windows: morning 0–4 h after waking, afternoon 4–8 h, evening 8–16 h. A test more than 2 h before the wake time is
  evening (still up from the day before, no night recorded yet), not morning.
- Uses the last 10 days of valid tests of the current game version. Eligible with ≥ 5 observation days and, in every
  window, ≥ 3 valid tests on ≥ 2 distinct days (model v4). The minimums only rule out tiny samples; the gap test below
  decides whether a difference is real. All three windows are needed: an untested part of the day could be the best.
- Performance per test is normalized per device (robust z of the speed against that device's own 10-day median and
  MAD, higher is faster), then averaged per window.
- `peak = argmax(mean performance)`. If the gap to the runner-up is smaller than its standard error (minimum 0.2),
  the result is `NO_CLEAR_PEAK`. Only broad windows are predicted.
- Stored: predicted window, per-window performance, confidence, valid session count, last update, model version.

---

# 6. Missing data and confidence

- Missing is never zero: no HRV is not zero HRV, no light is not darkness, no test is not poor performance.
- A missing recovery signal drops its term (M unchanged); missing sleep follows §2.10.
- Confidence: §2.8. Confidence and band are independent.

---

# 7. Personalization and baselines

Baselines are the user's own mean and SD over the trailing 10 days, per signal and per source (watch and phone
never mixed), compared from 5 days on; today is always compared with earlier days only. Reaction Test baselines are
per device. From 20 tests the model itself is tuned to the user (§2.7). Changing settings never resets baselines.

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
- **Reaction Test:** nothing to set. Game rules are identical on phone and watch and not editable.
- **Scoring weights:** active configuration, five editable weights, validation, defaults, confirmed reset, version
  and update time.
- **Personal baselines:** per metric median, MAD, sample count, personal share, calibration version.
- **Availability states, never conflated:** hardware unavailable, permission not granted, provider unavailable, no
  recent measurement, measurement invalid, measurement valid.
- **Data:** clear all local data (with confirmation). About: About Hercules (what the app does and the research behind it), Privacy Policy, Terms & Conditions, version. No developer menu; demo data loads only through the debug launch extra.

---

# 10. Metrics inventory

## 10.1 Core (scored)

| Metric | Source | Role |
|---|---|---|
| Sleep episodes | Health Connect / Hercules watch | Sleep pressure (§2.2) |
| Usual wake time | Derived from nights | Body-clock peak (§2.3) |
| HRV RMSSD | Hercules watch / Health Connect | Recovery (§2.5) |
| Resting heart rate | Hercules watch / Health Connect | Recovery |
| Skin temperature deviation | Health Connect | Recovery |
| Reaction Test | Phone and watch | G (§2.6) |

## 10.2 Optional extras (§2.14)

Respiratory rate, SpO₂, glucose (Health Connect `BloodGlucoseRecord`, mmol/L). Each adds a small recovery term and up
to 7 confidence points in total.

## 10.3 Supporting (stored, never scored)

Ordinary heart rate, sleep stages (used only to tell a wearable's night from a phone's), raw beat-to-beat intervals,
steps, exercise, floors, calories and ambient light (shown, not scored since engine 5).

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
- **Respiratory rate, skin temperature (deviation, never core temperature), SpO₂:** stored with provenance and the
  recording device type; recovery terms when off the personal usual (§2.5, §2.14).
- **Continuous glucose (optional):** read from Health Connect when a CGM or meter app writes it; stored in mmol/L
  with provenance, plausibility 1–35 mmol/L, shown under Setup → Sensors & data → Optional extras. A recovery term
  (§2.14); lows also show a live banner on Home.
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
  scoring engine, scoring configuration manager, Reaction Test engine, peak-window predictor, Moment preparation
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

Check availability, request permissions, read with appropriate ranges (initial import 10 days, then the last 3 days
on every sync), keep provenance (`dataOrigin` package and device), normalize units, de-duplicate by record id,
validate quality, store locally, handle revoked permissions and missing record types. Steps use the aggregation
API; overlapping providers are never summed blindly. WorkManager runs deferred refreshes; opening the app always
refreshes.


**Which source wins (per signal, per day):**

| Signal | Most reliable first | Why |
|---|---|---|
| Sleep | a wearable's staged night in Health Connect → Hercules watch → other Health Connect session | Vendor sleep algorithms are validated; Hercules' detection is simpler; unstaged sessions are often phone estimates |
| HRV, resting heart rate | Health Connect from a wearable → Hercules watch → Health Connect recorded on a phone (half weight) | Vendors measure overnight at rest; one source per day, each with its own baseline (§2.11) |
| Steps (shown, not scored) | Hercules watch (when worn that day) and Health Connect compared: the higher wins | A partial day reads low |
| Ambient light | phone | Only the phone has the sensor |

Sources are never summed. Health Connect has no heart or HRV sensor of its own; those values come from another wearable's app.

---

# 15. Wear OS companion

The watch runs Reaction Test independently (same shared test loop, monotonic timing; leaving the app stops the test), collects
supported data with `PassiveMonitoringClient` (batched, unpredictable delivery), stores everything locally first,
and keeps working without the phone. `MeasureClient` is only for short explicit measurements and `ExerciseClient`
only for explicit workouts. Missing capabilities are detected and handled without crashing.

---

# 16. Phone-watch sync

Wearable Data Layer only; no custom Bluetooth.

- **MessageClient:** watch → phone batches (samples, sessions, assessments) with an acknowledgement listing the
  stored ids. Retries are idempotent (UUIDs / deterministic ids), original timestamps and device identity are
  kept, nothing is marked synced before the phone acknowledges it.
- **DataClient:** phone → watch state (latest readiness, Moments, brief, reminder and score settings) and sensor configuration. The watch saves the phone's score settings, so a score it works out itself uses the same sliders.
- **CapabilityClient:** finds the companion app.
- The phone validates incoming records, stores them, and recalculates (now and the previous 2 days, §2.15).

---

# 17. Local-only data

No remote API, cloud database, cloud auth, server scoring, cloud sync or remote inference. Each device has its own
database; they sync when connected. Works offline. No account. The database lives in app-private storage; encrypting it with a Keystore-managed key (SQLCipher) is
planned but not yet built. Only
necessary permissions; revocation handled; unnecessary raw streams avoided. Everything older than 10 days is deleted
(§2.12). Setup can clear all local data. Android backups are disabled.

---

# 18. Data model

Timestamps are UTC epoch milliseconds; local day keys (`yyyy-MM-dd`) and time zone ids are kept where they matter.
Raw observations are stored separately from derived scores.

| Entity | Table | Notes |
|---|---|---|
| HealthMetric | `health_metric` | Extensible for every metric type, with unit, source package and device, quality, collection method |
| SleepSession | `sleep_session` (all provider sessions) → `sleep_night` (the resolved night) | Stages and efficiency when present |
| CognitiveSession / CognitiveRound | `cognitive_session`, `cognitive_round` | Device, window, speed, median ms, lapses, false starts, scores, game version; one round row per trial |
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

Unit tests in `shared` cover: the sleep model (the paper's ranges: a few lapses rested, the teens after a night awake; 8 h sleep and 2 h awake → high
P; 24 h awake → low; a nap raises the evening score; the screen-use estimate; three short nights
lower P progressively; 6 h for 10 nights stays well under the 8 h level), M never above 1 and floored at 0.7,
short-history and missing signals leave M unchanged, phone HRV at half weight, no game → score = P·M, the 60/40
blend, the missing-sleep fallbacks, confidence points and caps, settings clamping, calibration bounds, the game
percentile, 10-day retention, bands, Reaction Test validity and scoring, peak-window eligibility, Moment constraints,
lifecycle, recommendation priority and de-duplication.

---

# 23–24. Roadmap and scientific validation

Settings and the lapse scale are hypotheses; calibration against each user's tests is the validation loop. Next:
re-checking the lapse scale and band cut-offs
against real score distributions. Keep the game out of any claim that "the score predicts the game": it is part of
the score.

# 25. References

Engine 5: Borbély 1982 (*Hum Neurobiol*) and Borbély et al. 2016 (*J Sleep Res*, two-process model); Van Dongen et
al. 2003 (*Sleep* 26(2), cumulative cost of wakefulness) and 2004 (*Sleep*, trait-like vulnerability); McCauley et
al. 2009 (*J Theor Biol*) and 2013 (*Sleep* 36(12), the engine); the 2024 recalibration in *Frontiers in Environmental Health*;
bioRxiv 2025.01.22.634299 (time constants of the earlier interim engine); MHSRS "Physiologic Predictors of Cognitive Performance";
*Personal and Ubiquitous Computing* 2025 (wearable sensors and vigilance); Battelino et al. 2019 (CGM events).

Engine 4 and earlier: Formula v2 (scoring engine 3.0.0) rests on: Van Dongen et al. 2003 (*Sleep*, chronic restriction); Belenky et al.
2003 (*J Sleep Res*); Lim & Dinges 2010 (*Psychol Bull*, meta-analysis); Watson et al. 2015 (AASM/SRS 7 h
consensus); Li et al. 2022 (*Nature Aging*, 7 h optimum); Phillips et al. 2017 and Windred et al. 2024 (sleep
regularity); Plews et al. 2013/2014 and Buchheit 2014 (ln RMSSD rolling averages); Forte et al. 2019 (HRV and
cognition review); Natarajan et al. 2020 and Smarr et al. 2020 (wearable respiratory rate and skin temperature
deviation); International Hypoglycaemia Study Group / ADA 2017 (glucose < 3.9 / < 3.0 mmol/L); Brown et al. 2022
(*PLoS Biol*, light recommendations); Chang et al. 2012; Wheeler et al. 2020 (exercise and cognition); Hilditch &
McHill 2019 (sleep inertia); Basner & Dinges 2011 (PVT); Schmidt et al. 2007 (time-of-day effects).

Health Connect data types, Wear OS Health Services, Android Sensor API, Wearable Data Layer, WorkManager
(developer.android.com).
