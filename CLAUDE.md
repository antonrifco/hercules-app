# Hercules: notes for Claude

Cognitive readiness app: Android phone (`mobile/`, Jetpack Compose) + Wear OS watch (`wear/`, Wear Compose M3),
with all domain logic in the Kotlin Multiplatform `shared/` module (SQLDelight). Product and formula details live in
`docs/SPEC.md`; the user-facing overview is `README.md`. Keep all three in step when behaviour changes.

## Working rules

- **Commits:** don't commit per change. One commit + push only when the user says "push".
- **Keep it simple.** The user asks for no over-engineering; prefer the smallest correct change.
- **Physical phone:** a Xiaomi (`TC6DRO5THA9XVCA6`). Don't drive its screen (taps/screenshots that interfere) while
  the user is using it. If it isn't in `adb devices`, say phone changes are compile-verified only.
- **Watch:** the Wear OS emulator (`emulator-5554`). Package `com.hercules.app`, launch
  `com.hercules.app/com.hercules.wear.ui.MainActivity`.
- **Wording:** important events are **Moments** (never "events" in UI). Plain, short copy. The reaction test is called **Reaction Test** in the UI (code name `BeatTheClock`). App name Hercules,
  mascot Herc the lion; keep both.

## Commands

```bash
./gradlew -q :shared:testDebugUnitTest                         # results: shared/build/test-results/testDebugUnitTest/*.xml
./gradlew -q :mobile:compileDebugKotlin :wear:compileDebugKotlin
ANDROID_SERIAL=TC6DRO5THA9XVCA6 ./gradlew -q :mobile:installDebug
ANDROID_SERIAL=emulator-5554 ./gradlew -q :wear:installDebug
adb shell am start -n com.hercules.app/com.hercules.mobile.ui.MainActivity --ez demo true   # demo data (debug)
```


## Where things are

- `shared/.../readiness/Scoring.kt`: dimensions, `DefaultScoring` (config v4: 65/10/10/5/10; one game counts 60%, two 80%, three in full),
  `DimensionScorer` (sleep load, ln-HRV, light curves, `modifiers` for optional extras),
  `ReadinessCalculator.combine` (CRS, coverage, `confidenceFor(coverage, historyDays)`, confidence hint),
  `SCORING_ENGINE_VERSION`.
- `shared/.../readiness/Assessment.kt`: Reaction Test (`GAME_VERSION` 4: PVT-B style reaction test, 45 trials (~3 min), Herc
  appears every 1–4 s; speed vs the device's usual, lapses ≥ 500 ms, false starts; scored from the first test against a
  reference), `AssessmentWindow`, peak window (by speed). The test loop is `ReactionRun.kt`, shared by phone and watch.
  Migration 3 dropped the old time-estimation game's data.
- `shared/.../cognitive/CognitiveService.kt`: orchestration. `inputs()` builds `DayInputs` from Health Connect +
  watch data; `refresh()` recalculates today, baselines, peak, Moments; `recordAssessment`, `rescoreUnscored`.
- `shared/.../cognitive/CognitiveStore.kt`: SQLDelight access; `activeConfig()` maps an outdated stored DEFAULT to
  the current one (custom weights kept).
- `shared/.../cognitive/Coach.kt`: the one next step, shared by phone and watch.
- `mobile/.../health/HealthConnect.kt`: Health Connect reads and permissions (incl. background read);
  `HealthWorker.kt` background sync. `MainActivity` re-reads every 5 min while open.
- `mobile/.../widget/Widgets.kt`: Glance home-screen widgets (Score 2×2, Today 2×2, This week 4×2), redrawn from
  `pushToWatch()`; added from Setup › Home screen widgets (Xiaomi needs "Home screen shortcuts" allowed).
- `mobile/.../ui/`: `TodayScreen` (Home), `CoachCards` (score hero, `ConfidenceLine`), `PlanScreen` (Moments),
  `DataScreen` (Setup; each signal shows its last reading and opens `SignalHistory`: 7 days × 4 parts of the day), `BeatTheClockScreen`. Watch UI: `wear/.../ui/MainActivity.kt`, `BeatTheClock.kt`.

## Invariants (see SPEC)

- Missing data is never zero; a missing dimension is dropped and the rest re-weighted.
- One data point is enough for a score; confidence: High ≥ 90% coverage & ≥ 7 days, Medium ≥ 50% & ≥ 3 days, else Low.
- Optional extras (respiratory rate, skin temp, SpO₂, glucose lows; CGM lows must last ~3 readings) only subtract, capped at −10 total.
- Stored scores keep their config id/version and engine version. `refresh()` recalculates today plus the previous
  `RECALC_DAYS` (2) days with each day's own stamped weights (late data); older days are never touched.
- A day's score starts at wake-up: between midnight and waking (no night recorded yet, before the usual wake time)
  no score is published (`REASON_BEFORE_WAKE`). Watch sleep is the longest episode in the window (awake gaps over
  60 min split episodes); a night in progress is not reported.
- New data recalculates: watch batch on the phone, Health Connect every 15 min in the background, 5 min while open;
  the watch syncs on new readings at most every 10 min and keeps one background pulse reading per 20 s. The phone also asks the watch to sync (`SYNC_REQUEST_PATH`) at most every 15 min, only when a watch is connected and not in airplane mode.
- Changing the formula: bump `SCORING_ENGINE_VERSION`; changing default weights: bump `DefaultScoring.CONFIG_VERSION`.
- Phone and watch Reaction Test rules are identical; baselines are per device.
- Same signal from several sources: the more reliable wins, never summed (SPEC §14). Sleep: staged Health Connect
  night → Hercules watch → unstaged HC. HRV: HC → watch. Resting HR, steps: watch (if worn that day) and HC compared, the fuller reading wins (higher steps, lower resting HR).
  Light: phone. Without a watch, everything must still work from Health Connect and phone sensors.
