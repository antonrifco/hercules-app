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
- **Wording:** important events are **Moments** (never "events" in UI). Plain, short copy. App name Hercules,
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

- `shared/.../readiness/Scoring.kt`: dimensions, `DefaultScoring` (config v2: 40/20/10/10/20),
  `DimensionScorer` (sleep load, ln-HRV, light curves, `modifiers` for optional extras),
  `ReadinessCalculator.combine` (CRS, coverage, `confidenceFor(coverage, historyDays)`, confidence hint),
  `SCORING_ENGINE_VERSION`.
- `shared/.../readiness/Assessment.kt`: Beat the Clock (`GAME_VERSION` 3: 3 rounds 15–19/20–24/25–30 s, count shown
  for 3 s, scored from the first game against a reference baseline), `AssessmentWindow`, peak window.
- `shared/.../cognitive/CognitiveService.kt`: orchestration. `inputs()` builds `DayInputs` from Health Connect +
  watch data; `refresh()` recalculates today, baselines, peak, Moments; `recordAssessment`, `rescoreUnscored`.
- `shared/.../cognitive/CognitiveStore.kt`: SQLDelight access; `activeConfig()` maps an outdated stored DEFAULT to
  the current one (custom weights kept).
- `shared/.../cognitive/Coach.kt`: the one next step, shared by phone and watch.
- `mobile/.../health/HealthConnect.kt`: Health Connect reads and permissions (incl. background read);
  `HealthWorker.kt` background sync. `MainActivity` re-reads every 5 min while open.
- `mobile/.../ui/`: `TodayScreen` (Home), `CoachCards` (score hero, `ConfidenceLine`), `PlanScreen` (Moments),
  `DataScreen` (Setup), `BeatTheClockScreen`. Watch UI: `wear/.../ui/MainActivity.kt`, `BeatTheClock.kt`.

## Invariants (see SPEC)

- Missing data is never zero; a missing dimension is dropped and the rest re-weighted.
- One data point is enough for a score; confidence: High ≥ 90% coverage & ≥ 7 days, Medium ≥ 70% & ≥ 3 days, else Low.
- Optional extras (respiratory rate, skin temp, SpO₂, glucose lows; CGM lows must last ~3 readings) only subtract, capped at −10 total.
- Stored scores keep their config id/version and engine version. `refresh()` recalculates today plus the previous
  `RECALC_DAYS` (2) days with each day's own stamped weights (late data); older days are never touched.
- Changing the formula: bump `SCORING_ENGINE_VERSION`; changing default weights: bump `DefaultScoring.CONFIG_VERSION`.
- Phone and watch Beat the Clock rules are identical; baselines are per device.
- Same signal from several sources: the more reliable wins, never summed (SPEC §14). Sleep: staged Health Connect
  night → Hercules watch → unstaged HC. HRV: HC → watch. Resting HR, steps: watch (if worn that day) → HC.
  Light: phone. Without a watch, everything must still work from Health Connect and phone sensors.
