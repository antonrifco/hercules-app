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
./gradlew -q :wear:assembleRelease && adb -s <watch> install -r wear/build/outputs/apk/release/wear-release.apk  # real watch: ~2x faster than debug
adb shell am start -n com.hercules.app/com.hercules.mobile.ui.MainActivity --ez demo true   # demo data (debug)
```


## Where things are

- `shared/.../readiness/Scoring.kt` (engine 5, SPEC §2): `SleepModel` (McCauley 2013, Table 2 values; lapses → P = 100 − 4·(p − 2)),
  `Recovery` (z vs own 10 days, unfavourable only, M floor 0.7), `Calibrator` (from 20 tests), `ScoreSettings`
  (sliders, stored in kv), `ReadinessCalculator.calculate` (score = g·G + (1−g)·P·M with a test from the last 2 h,
  else P·M; missing-sleep fallbacks; `confidenceParts` → %), `RETENTION_DAYS` (10), `SCORING_ENGINE_VERSION`.
- `shared/.../readiness/Assessment.kt`: Reaction Test (`GAME_VERSION` 4: PVT-B style reaction test, 45 trials (~3 min), Herc
  appears every 1–4 s; speed vs the device's usual, lapses ≥ 500 ms, false starts; scored from the first test against a
  reference, a percentile of the device's last 10 days from 5 tests), `AssessmentWindow`, peak window (by speed). The test loop is `ReactionRun.kt`, shared by phone and watch.
  Migration 3 dropped the old time-estimation game's data.
- `shared/.../cognitive/CognitiveService.kt`: orchestration. `inputs()` builds `DayInputs` (sleep episodes, one
  source per signal, the 2-hour game); `refresh()` purges > 10 days, calibrates, recalculates now + 2 past days,
  baselines, peak, Moments; `recordAssessment`, `rescoreUnscored`.
- `shared/.../cognitive/CognitiveStore.kt`: SQLDelight access; settings and calibration in `kv`; `purgeBefore`.
- `shared/.../cognitive/Coach.kt`: the one next step, shared by phone and watch.
- `mobile/.../health/HealthConnect.kt`: Health Connect reads and permissions (incl. background read);
  `HealthWorker.kt` background sync. `MainActivity` re-reads every 5 min while open.
- `mobile/.../widget/Widgets.kt`: Glance home-screen widgets (Score 2×2, Today 2×2, Last 10 days 4×2), redrawn from
  `pushToWatch()`; added from Setup › Home screen widgets (Xiaomi needs "Home screen shortcuts" allowed).
- `mobile/.../ui/`: `TodayScreen` (Home), `CoachCards` (score hero, `ConfidenceLine`), `PlanScreen` (Moments),
  `DataScreen` (Setup; each signal shows its last reading and opens `SignalHistory`: 7 days × 4 parts of the day), `BeatTheClockScreen`. Watch UI: `wear/.../ui/MainActivity.kt`, `BeatTheClock.kt`.

## Invariants (see SPEC)

- Missing data is never zero: a missing recovery signal drops its term; missing sleep falls back to a recent test
  alone, then the usual sleep window, else no score.
- The score is "now": it falls through the day as hours awake add up. Past days are stored as at wake + 6 h.
- Confidence is a percentage (SPEC §2.8): High ≥ 75, Medium 50–74, Low < 50; without watch sleep at most 74, on a
  test or usual sleep alone at most 49; custom settings −5.
- Recovery (incl. optional extras: glucose, SpO₂, breathing) only ever lowers the score; M ≥ 0.7.
- Stored scores keep their settings and engine version (`ConfigStamp`). `refresh()` recalculates now plus the previous
  `RECALC_DAYS` (2) days with each day's own settings; older days are never touched.
- 10-day retention for raw data, nights, tests and scores; only calibration, settings and Moments are kept longer.
- A day's score starts at wake-up: between midnight and waking (no night recorded yet, before the usual wake time)
  no score is published (`REASON_BEFORE_WAKE`). Watch sleep is the longest episode in the window (awake gaps over
  60 min split episodes); a night in progress is not reported. A night must end at 03:00 or later (`EARLIEST_WAKE_H`);
  earlier sleep (evening nap, phone down for the evening) is a nap of the day before, for every source.
- New data recalculates: watch batch on the phone, Health Connect every 15 min in the background, 5 min while open;
  the watch syncs on new readings at most every 10 min and keeps one background pulse reading per 20 s. The phone also asks the watch to sync (`SYNC_REQUEST_PATH`) at most every 15 min, only when a watch is connected and not in airplane mode.
- Changing the formula: bump `SCORING_ENGINE_VERSION`.
- Phone and watch Reaction Test rules are identical; baselines are per device.
- One source per signal per day, each with its own baseline (SPEC §2.11, §14). Sleep: staged Health Connect night →
  Hercules watch → unstaged HC → screen-use estimate (`ScreenSleep`, Usage Access; phone tier). Naps (other sleep
  ≥ 15 min) also feed the model. HRV/resting HR: HC from a wearable → Hercules watch → HC recorded on a
  phone (half weight). Steps and light are shown, not scored. Without a watch, everything must still work from
  Health Connect and phone sensors.
- The score is a wellness estimate: never present it as medical or a fitness-to-drive judgement.
