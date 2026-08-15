# Health Connect integration — design and implementation plan

Status: **not started.** This document exists so a future session can pick the work up cold.
Implementation begins at Phase 0.

## Context

The iOS app ships a complete one-way Apple HealthKit export, added in iOS PR #80.
`docs/ios-sync.md` triaged that PR as **SKIP** with the note *"HealthKit — intentional iOS-only
divergence. Android analogue is **Health Connect**; use this as the reference design if ever
wanted"*, and the "Intentional divergences" section lists "HealthKit-adjacent integrations" as
iOS-only. This document revisits that decision.

The Android app currently has **zero** Health Connect code — no dependency, no manifest entry, no
`<queries>` element. Ring data is therefore trapped inside PulseLoop and can't reach any Health
Connect consumer (Fitbit-style dashboards, Home Assistant, other fitness apps).

The outcome we want: a **write-only** Health Connect export mirroring what HealthKit gets on iOS,
delivered as mergeable slices, followed by a phase covering the metrics Health Connect supports but
HealthKit made awkward.

Paths below are relative to this repo (`android/`) unless prefixed `<ios>`, which means the iOS
repo — this repo's parent directory. `Gadgetbridge/` is also at the parent root.

---

## 1. What iOS writes to HealthKit (the baseline to match)

Source: `<ios>/PulseLoop/Health/HealthSyncService.swift` (598 L), `+Workouts.swift`,
`+Nutrition.swift`, `HealthKitTypeMappings.swift`, `HealthSyncPublisher.swift`,
`<ios>/PulseLoop/Settings/AppleHealthPrefsStore.swift`,
`<ios>/PulseLoop/Views/Settings/AppleHealthSettingsView.swift`.

### Writes (the `toShare` set)

| Category | HealthKit type | Source | Notes |
|---|---|---|---|
| Vitals | `.heartRate` | `Measurement` rows, `kind == .heartRate` | count/min, instantaneous |
| | `.oxygenSaturation` | `.spo2` | percent → 0…1 fraction |
| | `.heartRateVariabilitySDNN` | `.hrv` | ms |
| | `.bodyTemperature` | `.temperature` | °C — Apple's wrist-temp type is read-only to third parties |
| Daily activity | `.stepCount`, `.activeEnergyBurned`, `.distanceWalkingRunning` | `ActivityDaily` | one day-spanning sample each; workout kcal/distance **netted out** so the Move ring doesn't double count |
| Sleep | `.sleepAnalysis` category | `SleepBlock` per stage | deep / core (light) / REM / awake / unspecified; carries `HKMetadataKeyTimeZone` |
| Workouts | `HKWorkout` + `HKWorkoutRoute` | `ActivitySession` | `HKWorkoutBuilder`, child energy + distance samples, GPS route from accepted `ActivityGpsPoint`s |
| Nutrition | 7 dietary types (energy, protein, carbs, fat, fiber, sugar, sodium) | `MealEntry` | only when the nutrition feature's own master toggle is on |

**Deliberately not written** (`HealthKitTypeMappings.swift:48-56`): stress and fatigue (no HealthKit
equivalent), blood pressure (needs `HKCorrelation` pairing), blood sugar, respiratory rate, VO₂max —
all documented as follow-ups that never happened.

### Reads

Profile characteristics only — date of birth, biological sex, latest height, latest weight —
consumed solely by the "Import from Apple Health" button on `ProfileSettingsView`. No
`HKAnchoredObjectQuery`, no observers, no importing of other apps' samples.

### Mechanics worth copying verbatim

- **Upsert, not delete-and-rewrite.** Every sample carries a deterministic
  `HKMetadataKeySyncIdentifier` (`pl-m-<kind>-<epochMs>`, `pl-act-<metric>-<dayEpoch>`,
  `pl-wk-<sessionUUID>`, …) plus an `HKMetadataKeySyncVersion`. Re-exporting the same logical row
  replaces it.
- **Watermarks in UserDefaults**, not the database (`AppleHealthSyncState`). Vitals watermark on
  `Measurement.createdAt` — deliberately *not* the sample timestamp, so late-arriving ring history is
  still picked up. Aggregates watermark on `updatedAt`. Advanced per chunk so an interrupted backfill
  resumes.
- **Debounced trigger.** `HealthSyncPublisher` observes the app-wide `PulseDataChange` token and
  exports 15 s after the last change. No background task, no timer.
- **First-enable backfill dialog**: "Sync all history" / "Only new data from now on". Exports are
  blocked entirely while `backfillChoice == .notAsked`.
- **Per-datatype toggles** (HR, SpO₂, HRV, temperature, sleep, steps & activity, workouts,
  nutrition), all default on under a master toggle that defaults **off**.
- **"Remove PulseLoop data from Apple Health"** deletes everything scoped to our own `HKSource`, then
  clears watermarks. Individual workout/meal deletion hooks fire when the local row is deleted.
- Batching: 1 000 vitals samples per save, with a per-object fallback on batch failure, and the
  watermark only advances past rows that actually landed.

---

## 2. Does not shipping on Google Play matter?

**No, not for what we're building.** The one real consequence is a per-user prerequisite on
Android 13 and below, not a restriction on us.

**Why it's fine:**

- Health Connect permissions are ordinary Android runtime permissions in the
  `android.permission.health.*` group. They're declared in the manifest and granted by the user in
  the Health Connect permission sheet. There is no server-side allowlist keyed to Play approval for
  the standard (non-medical) data types.
- The **Play Console "Health apps declaration" is a publishing-time review gate.** Google's own
  wording ties the failure mode to Play-published apps: *"If your health app is published in the Play
  store and released to the public, but you didn't request for data type accesses, your end users
  receive [an error] dialog."* Not publishing means not being reviewed.
- **Direct proof by existence:** Gadgetbridge is F-Droid / sideload only, has never been on Play, and
  ships a full production Health Connect integration writing 22 permissions' worth of data types. Its
  source is checked out at the parent repo root (`Gadgetbridge/`) and contains zero Play-Store,
  declaration-form, or sideloading workarounds. **It is the reference implementation for this plan.**

**The real caveats, in order of importance:**

1. **Android 13 and below need the Health Connect app from Play.** Health Connect is part of AOSP
   from Android 14 onward. Below that it's a separate APK (`com.google.android.apps.healthdata`)
   distributed through the Play Store. `minSdk = 26`, so a meaningful slice of users hit this. Handle
   it at runtime: `HealthConnectClient.getSdkStatus()` returns `SDK_UNAVAILABLE` or
   `SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`, and the settings screen shows an explanatory row with
   a Play deep-link instead of a broken toggle. This is a user prerequisite, not a build constraint.
2. **A privacy-rationale Activity is mandatory regardless of distribution.** Health Connect's
   permission sheet shows a "privacy policy" link that fires
   `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (pre-14) or
   `android.intent.action.VIEW_PERMISSION_USAGE` + `category.HEALTH_PERMISSIONS` (14+). Gadgetbridge
   satisfies this with a two-`TextView` in-app screen and **no hosted URL**. Do the same.
3. **Medical records (FHIR) data types are a different regime** with genuine extra approval. We don't
   touch them.
4. **Restricted read permissions we're avoiding anyway.** `READ_HEALTH_DATA_HISTORY` (reading other
   apps' data older than 30 days) and `READ_HEALTH_DATA_IN_BACKGROUND` get heightened Play scrutiny.
   Write-only sidesteps both. Note that *writing* historically-dated records has no such restriction
   — full backfill is fine.
5. **Unrelated but worth knowing:** Google's developer-verification requirement for sideloaded apps
   begins rolling out September 2026 (Brazil, Indonesia, Singapore, Thailand first; global 2027+).
   That affects how users install PulseLoop at all, not Health Connect specifically, and is out of
   scope here.

**Bottom line:** build it exactly as if we were shipping on Play (manifest permissions, rationale
activity, `<queries>` block) and simply never file the declaration form. If PulseLoop is ever
submitted to Play, the declaration becomes a form-filling exercise, not a rework.

---

## 3. Target design

New package `app/src/main/java/com/pulseloop/health/`, a sibling of the existing `strava/`
integration package.

### Scope decisions (settled)

- **Write-only.** No `READ_*` permissions at all. iOS's profile import can't fully port regardless —
  Health Connect has no date-of-birth or biological-sex data type.
- **iOS parity first** (Phases 1–4), then the extras Health Connect supports and HealthKit didn't
  (Phase 5).
- **Mergeable slices**, one PR per phase.

### Architectural decisions

**`clientRecordId` on every record.** This is Health Connect's native upsert: `insertRecords` with a
matching `clientRecordId` and a `clientRecordVersion` ≥ the stored one **replaces** the record. It
maps one-to-one onto iOS's `HKMetadataKeySyncIdentifier` scheme, so reuse the same id shapes.
Gadgetbridge only uses this for three of its record types and eats duplicate risk elsewhere — we
should use it everywhere.

> **`clientRecordVersion` must never be the metric value.** Gadgetbridge's comment in
> `syncers/HealthConnectSyncer.kt` explains why: a downward correction would carry a lower version
> and be silently ignored, freezing the record at its stale maximum. Use `1` for immutable vitals and
> the row's `updatedAt` (or the run's wall-clock) for mutable aggregates.

**Watermarks in SharedPreferences, not Room.** Mirrors `AppleHealthPrefsStore` and avoids a DB
migration entirely. Follow the `MetricPrefsStore` pattern (`ui/dashboard/MetricPrefsStore.kt`) — a
JSON blob plus a `StateFlow`. Two separate keys so frequent watermark writes don't rewrite the
preference blob.

**Export is a pure DB → Health Connect pass**, driven by watermarks, never by events. It doesn't
matter when or how the data landed. This is deliberate:

> While mapping the sync pipeline for this plan I noticed `EventPersistenceSubscriber` is only
> constructed inside the `PulseLoopApp` **composable** (`ui/PulseLoopApp.kt:73`), while
> `RingSyncWorker` runs BLE with the app backgrounded. If WorkManager cold-starts the process, no
> subscriber exists to persist what the worker fetches. That is a pre-existing question outside this
> plan's scope — but it is the reason the exporter must read the DB rather than hang off the event
> bus.

**Trigger** — a `HealthConnectExportWorker` (`CoroutineWorker`) enqueued as unique one-time work with
`setInitialDelay(15s)` and `ExistingWorkPolicy.REPLACE`. The REPLACE-on-delay pattern *is* the
debounce (exactly what Gadgetbridge's `NewDataReceiver` does with a 10 s window, and the Android
equivalent of iOS's 15 s `HealthSyncPublisher`). Enqueued from three places:

- `EventPersistenceSubscriber`'s `SyncProgress("done")` branch (`service/EventPersistenceSubscriber.kt:277`)
- the end of `RingSyncWorker.doWork()`, so background-only syncs still export
- the manual "Export now" button

No foreground service. A plain worker gets ~10 minutes; watermarks advance per chunk, so a long
backfill just resumes on the next run. This avoids adding `FOREGROUND_SERVICE_DATA_SYNC` and the
`androidx.work.impl.foreground.SystemForegroundService` manifest override that Gadgetbridge needs.

### Data-type mapping

**Phases 1–4 (iOS parity).** Ranges marked ⚠️ are Health Connect platform limits, not our choice —
violating them throws.

| PulseLoop source | Health Connect record | Permission | Units / conversion | Guard |
|---|---|---|---|---|
| `MeasurementKind.HEART_RATE` | `HeartRateRecord` (**series**) | `WRITE_HEART_RATE` | bpm `Long` | 20…300, drop 0 |
| `SPO2` | `OxygenSaturationRecord` | `WRITE_OXYGEN_SATURATION` | `Percentage(0…100)` — **not** the 0…1 fraction HealthKit wants | 50…100 |
| `HRV` | `HeartRateVariabilityRmssdRecord` | `WRITE_HEART_RATE_VARIABILITY` | ms `Double` | ⚠️ **1…200** — narrower than iOS's 0…1000; a 211 crashed Gadgetbridge (their issue #6190) |
| `TEMPERATURE` | `BodyTemperatureRecord` | `WRITE_BODY_TEMPERATURE` | `Temperature.celsius` | 25…45 |
| `STRESS`, `FATIGUE` | — | — | — | no Health Connect record type exists (same gap as HealthKit) |
| `SleepSessionEntity` + `SleepStageBlockEntity` | `SleepSessionRecord` with `stages` | `WRITE_SLEEP` | — | stages sorted, non-overlapping, inside session bounds |
| `ActivityDailyEntity.steps` | `StepsRecord` | `WRITE_STEPS` | `Long` | > 0 |
| `.calories` (net of workouts) | `ActiveCaloriesBurnedRecord` | `WRITE_ACTIVE_CALORIES_BURNED` | `Energy.kilocalories` | > 0 |
| `.distanceMeters` (net of workouts) | `DistanceRecord` | `WRITE_DISTANCE` | `Length.meters` | > 0 |
| `ActivitySessionEntity` | `ExerciseSessionRecord` | `WRITE_EXERCISE` | type map below | `endedAt > startedAt`, not future |
| `ActivityGpsPointEntity` | `ExerciseRoute` (embedded) | `WRITE_EXERCISE_ROUTE` | lat/lon/alt/accuracy | `accepted` only, ≥ 2 points, **no duplicate timestamps** (HC rejects), decimate on 1 MB overflow |
| session `calories` / `distanceMeters` | sibling `ActiveCaloriesBurnedRecord` / `DistanceRecord` over the session window | as above | kcal / m | > 0 |

**Phase 5 (beyond iOS — Health Connect has types HealthKit lacked or made awkward):**

| PulseLoop source | Health Connect record | Notes |
|---|---|---|
| `BLOOD_PRESSURE_SYSTOLIC` + `BLOOD_PRESSURE_DIASTOLIC` | `BloodPressureRecord` | One record carries both — much simpler than HealthKit's `HKCorrelation`, which is why iOS skipped it. Requires **pairing the two `MeasurementEntity` rows by timestamp**. |
| `BLOOD_SUGAR` | `BloodGlucoseRecord` | `BloodGlucose.milligramsPerDeciliter`, ⚠️ ≤ 900.91 mg/dL (= 50 mmol/L) |
| `RESPIRATORY_RATE` | `RespiratoryRateRecord` | breaths/min, 0…1000 |
| `VO2MAX` | `Vo2MaxRecord` | 0…100, `MEASUREMENT_METHOD_OTHER` |
| `RestingHRBaselineService` / `UserProfileEntity.hrRestingBaseline` | `RestingHeartRateRecord` | ⚠️ 1…300 |
| `MealEntryEntity` | `NutritionRecord` | One record carries energy + all macros — simpler than HealthKit's 7 separate types |

**Exercise type map** — `ActivityMeta.ORDER` (`ui/components/ActivityMeta.kt:23`) → Health Connect
constants, following the shape of the existing `strava/StravaSportMapping.kt`:

```
walk   -> EXERCISE_TYPE_WALKING          gym    -> EXERCISE_TYPE_STRENGTH_TRAINING
run    -> EXERCISE_TYPE_RUNNING          squash -> EXERCISE_TYPE_SQUASH
cycle  -> EXERCISE_TYPE_BIKING           yoga   -> EXERCISE_TYPE_YOGA
hike   -> EXERCISE_TYPE_HIKING           dance  -> EXERCISE_TYPE_DANCING
sport  -> EXERCISE_TYPE_OTHER_WORKOUT    else   -> EXERCISE_TYPE_OTHER_WORKOUT
```

### `clientRecordId` scheme

Ported directly from `<ios>/PulseLoop/Health/HealthKitTypeMappings.swift:100-139`:

```
pl-hr-<hourStartEpochMs>                HeartRateRecord series bucket  version = max(createdAt) in bucket
pl-m-<kind>-<epochMs>                   instantaneous vitals           version = 1
pl-sleep-<dayEpochMs>                   SleepSessionRecord             version = session.updatedAt
pl-act-<steps|energy|dist>-<dayEpochMs> daily aggregates               version = row.updatedAt
pl-wk-<sessionId>                       ExerciseSessionRecord          version = session.updatedAt
pl-wk-<sessionId>-<energy|dist>         workout child records          version = session.updatedAt
```

Two identity traps specific to Android, both of which would silently create duplicates:

1. **`SleepStageBlockEntity.id` is a fresh random UUID on every re-sync** — `upsertSleepSessionAtomic`
   replaces the blocks. Never key on it (iOS can, because its block ids are stable). Health Connect's
   model helps here: one `SleepSessionRecord` holds all stages, so key on the session's `date`, which
   is uniquely indexed and stable.
2. **`MeasurementEntity.id` is a random UUID for live rows** and a stable `history:<key>:<ts>` for
   history rows. Derive the id from `kindRaw` + `timestamp` instead, so a reading that arrives once
   live and once via history collapses onto one record.

### Heart rate needs bucketing

`HeartRateRecord` is a **series** record — Google's guidance is explicitly *"avoid creating single,
long-duration records; structure data into smaller records"*. Do not write one record per sample.

Bucket by local hour, `clientRecordId = pl-hr-<hourStartEpochMs>`. The subtlety: an hour that gains
samples on a later sync must re-upsert the *whole* hour, so after selecting new rows by watermark,
re-query every touched hour in full by `timestamp` and rebuild the record. `clientRecordVersion` =
the max `createdAt` in the bucket, so a later, fuller version always wins.

Gadgetbridge additionally splits a series on a local-date change, a > 15 min gap, and at 1 000
samples — worth copying — and bumps `endTime` by 1 s when start == end, because Health Connect
requires a positive duration.

### Robustness constants (measured from Gadgetbridge's production code)

- `CHUNK_SIZE = 200` records per `insertRecords` call.
- 5 retries, exponential backoff from 1 s (1 / 2 / 4 / 8 / 16 s). `SecurityException` aborts
  immediately — never retry a permission failure.
- **1 MB per-record platform limit**, not exposed by any API. The only variable-size record we build
  is a GPS route inside `ExerciseSessionRecord`. Parse the limit out of the exception message
  (`"single record size limit: 1000000, was: 1700644"`) and uniformly decimate the route to ~90 % of
  the limit, preserving first and last points, never duplicating a timestamp.
- Day-sliced backfill so a large history never blows the memory limit.
- Advance the watermark **only** to a timestamp that actually reached Health Connect, and **never**
  rewind.

---

## 4. Phases

Each phase is a self-contained PR: builds, tests pass, runtime-verified on `emulator-5554` before the
next starts. Branch `feat/health-connect-<slice>`; commits `feat(health): …`; no `Co-Authored-By`
trailer.

### Phase 0 — Foundation (no data written yet)

- `app/build.gradle.kts`: `implementation("androidx.health.connect:connect-client:1.1.0")`. No version
  catalog in this repo — add the coordinate as a literal alongside the others. **Toolchain note
  (2026-08-15):** 1.1.0 is the only stable and its AAR requires compileSdk 36 + AGP 8.9.1 (Gradle
  8.11.1+), which forced a toolchain bump — see §8. Add
  `<uses-sdk tools:overrideLibrary="androidx.health.connect.client"/>` **only if** the manifest merger
  complains (`minSdk = 26` should already satisfy it; Gadgetbridge needs it because it's on 23).
- `AndroidManifest.xml`: the `WRITE_*` permissions for Phases 1–4 only (add Phase 5's when Phase 5
  lands — a narrower first permission sheet is better UX); the `<queries>` block for
  `com.google.android.apps.healthdata` plus the rationale intent; a new `HealthConnectRationaleActivity`
  with the `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` intent-filter; and the
  `ViewPermissionUsageActivity` alias guarded by `android.permission.START_VIEW_PERMISSION_USAGE` for
  API 34+.
- `health/HealthConnectAvailability.kt` — wraps `HealthConnectClient.getSdkStatus()`, distinguishing
  `SDK_AVAILABLE` / `PROVIDER_UPDATE_REQUIRED` / `UNAVAILABLE` so the UI can say something useful.
- `health/HealthConnectPermissions.kt` — permission sets grouped by logical data type, derived from
  record classes via `HealthPermission.getWritePermission(X::class)`. Never hardcode the strings.
- `health/HealthConnectPrefsStore.kt` — `enabled` (default **false**), per-type toggles (default true),
  `backfillChoice`, watermarks, `lastSyncAt`, `lastSyncSummary`, and the last-granted permission set.
  Tolerant JSON decode so a new key never wipes an existing blob.
- `ui/screens/SettingsSubScreens.kt` — a `HealthConnectSettingsScreen` modeled on the existing
  `StravaSettingsScreen` (line 2252); a row in `SettingsScreen.kt`; a route in `PulseLoopApp.kt`.
  The master toggle launches
  `rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract())` —
  `MainActivity` is a `ComponentActivity`, so no `FragmentActivity` is needed.
- Partial grants are first-class: any granted permission counts as connected; each pass re-checks its
  own record class against the granted set.

**Verify:** settings screen renders on API 35 (built-in provider) and on an API 30 image without the
Health Connect app (shows the install prompt, no crash); granting permissions shows PulseLoop in the
Health Connect app's connected-apps list; tapping the privacy-policy link in the permission sheet
opens the rationale screen.

### Phase 1 — Vitals + the export engine

The big one; every later phase only adds a mapper.

- `health/HealthConnectTypeMappings.kt` — **pure**: id builders, plausibility guards, stage/exercise
  constant maps. Keep it free of `HealthConnectClient` so it's trivially unit-testable.
- `health/HealthConnectExporter.kt` — watermark loop, chunking, retry/backoff, per-pass `try/catch` so
  one failing type doesn't sink the others, run summary.
- `health/exporters/VitalsExporter.kt` — HR (bucketed series) + SpO₂ / HRV / temperature (instantaneous).
- `health/HealthConnectExportWorker.kt` + enqueue helper.
- New DAO queries in `data/dao/Daos.kt`: `MeasurementDao.createdSince(kind, watermark)` and an
  hour-window re-read. Note there is currently **no** `createdAt`-based query — only
  `range(kind, start, end)` by `timestamp`.
- Wire the trigger into `EventPersistenceSubscriber.kt:277` and `RingSyncWorker.doWork()`.
- Exclude `sourceRaw == "demo"` / `"mock"` rows, mirroring iOS.
- First-enable backfill dialog ("Sync all history" / "Only new data from now on") and a hard gate: no
  export runs while the choice is unanswered.

**Verify:** inject known measurements via on-device `sqlite3`, run the worker
(`adb shell cmd jobscheduler run -f com.pulseloop <id>`), and confirm the exact values, units, and
timestamps in the Health Connect app's data browser. Then re-run the export and confirm the record
count is **unchanged** — that's the `clientRecordId` upsert working.

### Phase 2 — Sleep

`health/exporters/SleepExporter.kt` — one `SleepSessionRecord` per `SleepSessionEntity`, stages from
`SleepStageBlockEntity` mapped `DEEP→STAGE_TYPE_DEEP`, `LIGHT→STAGE_TYPE_LIGHT`, `REM→STAGE_TYPE_REM`,
`AWAKE→STAGE_TYPE_AWAKE`, `UNKNOWN→STAGE_TYPE_UNKNOWN`. Sort, clamp to session bounds, drop overlaps.
`clientRecordId = pl-sleep-<date>` — **not** the block id (see the identity traps above).

**Verify:** a re-synced night updates in place rather than producing a second session; a night that
grows (later blocks arrive) shows the longer span with no orphan.

### Phase 3 — Daily activity

`health/exporters/ActivityExporter.kt` — `StepsRecord`, `ActiveCaloriesBurnedRecord`, `DistanceRecord`
per day, spanning `startOfDay … min(endOfDay, now)`. Port iOS's `workoutNetting`
(`<ios>/PulseLoop/Health/HealthSyncService.swift:315-331`): subtract finished-workout kcal, and
distance only for walk/run-type sessions, so Health Connect consumers don't double-count against
Phase 4's records.

**Verify:** a day with a recorded workout shows daily totals *plus* the workout, without the workout's
calories appearing twice.

### Phase 4 — Workouts + GPS route

`health/exporters/WorkoutExporter.kt` — `ExerciseSessionRecord` with the type map, `title` from
`ActivityMeta.label`, embedded `ExerciseRoute` from accepted `ActivityGpsPointEntity` rows, plus
sibling energy/distance records. Route sanitisation: drop points outside the session window,
non-finite or out-of-range coordinates, and duplicate timestamps; require ≥ 2 points; skip the route
entirely if `WRITE_EXERCISE_ROUTE` wasn't granted (the session still writes). Implement the 1 MB
decimation fallback.

Deletion hooks: when a session is deleted locally (UI trash icon, coach `delete_activity_session`),
call `deleteRecords(ExerciseSessionRecord::class, clientRecordIdsList = listOf("pl-wk-<id>"))`.

**Verify:** record a real GPS walk on the emulator (`adb emu geo fix`), finish it, confirm the route
renders in Health Connect; delete it locally and confirm it disappears from Health Connect.

### Phase 5 — Beyond iOS

Blood pressure (pair systolic/diastolic rows by timestamp into one `BloodPressureRecord`), glucose,
respiratory rate, VO₂max, resting HR, and `NutritionRecord` from `MealEntryEntity`. Add the matching
manifest permissions in this phase, not earlier.

### Phase 6 — Lifecycle, removal, docs

- **"Remove PulseLoop data from Health Connect"** — `deleteRecords` by record type over our own
  records, then clear all watermarks. Mirrors iOS's `removeAllExportedData`.
- **Revocation detection** — store the last-granted permission set; on app start and on
  settings-screen open, diff against `permissionController.getGrantedPermissions()`. If everything was
  revoked, offer to reset the watermarks so a later re-grant re-exports (Gadgetbridge's
  `HealthConnectResetDialogFragment` pattern). A `SecurityException` from `insertRecords` should also
  trigger a re-check.
- **`DataArchiveService` restore** should stamp watermarks to now, so an imported archive doesn't
  re-export the whole history (iOS does this at `DataArchiveService.swift:458-460`). No `ALL_TABLES`
  change is needed — state lives in SharedPreferences, not Room.
- Update `docs/ios-sync.md`: fill in the Android commit for the port-queue row, and move
  "HealthKit-adjacent integrations" out of the "iOS-only intentional divergences" list once Phase 1
  ships.

---

## 5. Verification

Repo convention is **"Runtime-verified on `emulator-5554`"** with specifics — what was tapped, what
the DB showed, `sqlite3` / `dumpsys` / `adb` output. Per phase:

1. `./gradlew testDebugUnitTest` — pure mappers, id determinism, plausibility bounds, HR bucketing,
   sleep-stage clamping, route sanitisation, watermark monotonicity.
2. `./gradlew assembleDebug`, install on an API 35 emulator (built-in Health Connect).
3. Grant permissions, run the export, inspect the actual records in the Health Connect app.
4. **Re-run the export and confirm no duplicates** — the single most important check.
5. Check an API 30 emulator without the Health Connect APK degrades gracefully.
6. Before the final merge: `assembleRelease` and smoke-test on device. Release builds have
   `isMinifyEnabled = true`; confirm R8 hasn't stripped anything the Health Connect client reflects on.

### Testing approach

This repo has JUnit 4 + `kotlinx-coroutines-test` and *no* MockK, Mockito, or Robolectric, with
`isReturnDefaultValues = true`. Gadgetbridge works within exactly the same constraints, and its two
techniques port directly:

- **Declare the pure `convertSample`-equivalent functions `internal`, not `private`**, so same-module
  tests can call them without touching a client. This is an API-shaping decision to make in Phase 1,
  not retrofit later.
- **Hand-roll a `CapturingClient : HealthConnectClient`** that collects `insertRecords` calls and
  throws `NotImplementedError()` on the other ~12 interface methods. ~15 lines, no mocking framework,
  and it lets `runBlocking` drive a whole exporter pass. See
  `Gadgetbridge/app/src/test/java/nodomain/freeyourgadget/gadgetbridge/util/healthconnect/syncers/HeartRateSyncerTest.kt`.

Confirm early in Phase 1 that constructing `androidx.health.connect.client` record classes works under
plain JVM unit tests. Gadgetbridge does it, so it should — but verify before building the test suite
around it.

---

## 6. Reference files

| Concern | Read this |
|---|---|
| iOS behaviour to match | `<ios>/PulseLoop/Health/HealthSyncService.swift`, `HealthKitTypeMappings.swift`, `<ios>/PulseLoop/Settings/AppleHealthPrefsStore.swift` |
| Client availability, permissions | `Gadgetbridge/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/healthconnect/HealthConnectClientProvider.kt`, `HealthConnectPermissionManager.kt` |
| Orchestrator: slicing, cursors, insert + retry, route shrink | `Gadgetbridge/.../util/healthconnect/HealthConnectUtils.kt` (979 L) |
| Syncer abstraction + `clientRecordMetadata` | `Gadgetbridge/.../util/healthconnect/syncers/HealthConnectSyncer.kt`, `AbstractTimeSampleSyncer.kt` |
| Sleep identity across re-syncs | `Gadgetbridge/.../util/healthconnect/syncers/SleepSyncer.kt` |
| Workouts + route + companion records | `Gadgetbridge/.../util/healthconnect/syncers/RecordedWorkoutSyncer.kt` |
| Manifest requirements | `Gadgetbridge/app/src/main/AndroidManifest.xml:116-139`, `:1153-1175`, `:1313-1324` |
| Android integration precedent | `app/src/main/java/com/pulseloop/strava/` |
| Trigger point | `app/src/main/java/com/pulseloop/service/EventPersistenceSubscriber.kt:277` |
| Settings-screen precedent | `app/src/main/java/com/pulseloop/ui/screens/SettingsSubScreens.kt:2252` (`StravaSettingsScreen`) |

---

## 7. Open items

- **HRV semantics.** Health Connect only has `HeartRateVariabilityRmssdRecord` (RMSSD); iOS writes
  SDNN. The rings' reported HRV metric is vendor-specific and undocumented, so we'd be labelling it
  RMSSD without proof. Worth a one-line caveat in the settings screen footer, and worth checking the
  CRP/Colmi decompiles before Phase 1 ships. The ⚠️ 1–200 ms clamp will also silently drop readings
  iOS accepts.
- **Skin vs body temperature.** `SkinTemperatureRecord` is semantically right for a ring but is a
  feature-gated (`FEATURE_SKIN_TEMPERATURE`) baseline-plus-deltas series requiring a rolling baseline
  (Gadgetbridge maintains a 3-day rolling average defaulting to 33 °C). `BodyTemperatureRecord` matches
  iOS and is far simpler. Start with body temperature; skin temperature is a candidate for a later
  phase.
- **Background persistence gap** (pre-existing, noted above): `EventPersistenceSubscriber` lives in the
  composable while `RingSyncWorker` runs backgrounded. Not this plan's problem, but it bounds how much
  data a background-only user actually accumulates to export.

---

## 8. Session log

Append a dated entry here as work progresses (persistent memory via `mcp_memory` is the parallel
record). Status: **Phase 0 implemented** on `feat/health-connect-foundation` (pre-merge); Phases 1–6 pending.

- **2026-08-14 — prep, no code.** Plan read and re-verified against the live official docs.
  - Official Google Health Connect guide indexed into the `mcp_docs` server as library
    `android-health-connect` (scrape job `09c779f9-e338-44ef-90f5-bed6bca31bd2`, from
    `developer.android.com/health-and-fitness/health-connect?hl=en`). Gotcha: URLs **without**
    `?hl=en` fall into a redirect loop for the crawler.
  - Version pin confirmed current: `androidx.health.connect:connect-client:1.1.0` is the latest
    **stable** (1.2.0-alpha05 is the newest alpha). The official write-data guide targets the
    1.1.0 series, so the guide is a faithful reference for the pinned version.
  - Task context and the working agreement (verify against official docs via the MCP servers;
    log progress here *and* in `mcp_memory`) saved to persistent memory.
- **2026-08-15 — Phase 0 implemented** (branch `feat/health-connect-foundation`).
  - **Toolchain bump (user-approved "bump the toolchain"):** `connect-client:1.1.0` is the only
    stable, and its AAR metadata requires compileSdk 36 + AGP 8.9.1 (verified for 1.1.0, rc01–rc03,
    beta02; only 1.1.0-beta01 and 1.1.0-alpha12 fit SDK 35 / AGP 8.7). Bumped: AGP 8.7.0 → 8.9.1
    (`android/build.gradle.kts`), Gradle wrapper 8.9 → 8.11.1, compileSdk 35 → 36 (**targetSdk stays
    35**), `platforms;android-36` installed locally. **CI implication:** the PulseLoopAndroid release
    pipeline now needs platform-36 (AGP auto-download requires accepted SDK licenses in CI).
  - Shipped: the 1.1.0 dependency; manifest (exactly the ten Phase 1–4 `WRITE_*` permissions — no
    `READ_*`, no Phase 5 types; `<queries>` for `com.google.android.apps.healthdata` + the rationale
    action; `HealthConnectRationaleActivity` and the API 34+ `ViewPermissionUsageActivity` alias
    guarded by `START_VIEW_PERMISSION_USAGE`); the `health/` package (`HealthConnectSdk` three-state
    `getSdkStatus` wrapper, `HealthConnectPermissions` derived via `HealthPermission.getWritePermission`
    — the route uses `PERMISSION_WRITE_EXERCISE_ROUTE` since `ExerciseRoute` is an embedded type,
    `HealthConnectPrefsStore` in the MetricPrefsStore pattern with a **separate watermark key** and
    monotonic `setWatermark`, `HealthConnectRationaleActivity` — in-app rationale, no hosted URL);
    `HealthConnectSettingsScreen` (master toggle → permission sheet, partial grants first-class,
    per-type toggles, availability install/update rows with Play deep link, first-enable backfill
    dialog, last-sync row, HRV RMSSD caveat), the Settings row, and the `settings/health-connect`
    route.
  - API gotchas found by inspecting the 1.1.0 jar (`javap`), beyond the docs: `PermissionController`
    is top-level `androidx.health.connect.client.PermissionController` (NOT in `.permission`), and
    `createRequestPermissionResultContract()` is `ActivityResultContract<Set<String>, Set<String>>` —
    the launcher input is a **`Set`**, so `launch(HealthConnectPermissions.all)` (no `.toTypedArray()`).
  - Verified: `compileDebugKotlin`, `testDebugUnitTest` (15 new tests; full suite **890 green**),
    `assembleDebug`, and the **merged debug manifest** (exactly ten WRITE permissions, zero READ,
    queries + both activities present, no `overrideLibrary` needed at minSdk 26).
  - **Runtime verification (API 35) DONE on the user's `pulseloop_test` AVD** (Pixel 7, arm64-v8a,
    `android-35/google_apis`, headless; UI driven with `uiautomator dump` + `input tap`). On
    Android 15 Health Connect ships as the `com.android.healthfitness` **APEX** with
    `com.google.android.healthconnect.controller` as the provider/permission-flow package — there is
    **no** `com.google.android.apps.healthdata` app on this image (so "connected apps list" was
    verified at the OS level instead), and the 1.1.0 client's `getSdkStatus` correctly reports the
    provider **available**. Verified end-to-end:
      1. Settings → Health Connect row and screen render in the AVAILABLE state (no install prompt).
      2. Master toggle launches the official HC permission sheet ("Allow PulseLoop Debug to access
         Health Connect?"), privacy-policy link present.
      3. Tapping the privacy link fired the system `VIEW_PERMISSION_USAGE` intent, which routed to
         **our `ViewPermissionUsageActivity` alias** (the alias also shows up in the package
         manager's resolution for that action); the rationale screen rendered with title + body.
      4. "Allow" returned to our screen and showed the first-enable backfill dialog (chose
         "Sync all history"); screen then read **"Connected. 10 of 10 permission types granted."**
      5. All eight per-type rows + switches present (default ON), HRV RMSSD caveat visible,
         "No export has run yet — the export engine lands in the next phase."
      6. State survived force-stop + relaunch; the persisted prefs blob was exactly
         `pulseloop.healthconnect.v1` with `enabled=true`, all toggles true, `backfillChoice=
         EXPORT_ALL`, and all ten `lastGrantedPermissions`.
      7. `dumpsys package` shows all ten `android.permission.health.WRITE_*` as
         `granted=true, USER_SET` (plus the library's own `FOREGROUND_SERVICE_HEALTH` from the AAR).
  - **Still pending:** the API 30 graceful-degradation check (no-Health-Connect-APK image) — needs a
    second AVD/image; and the CI platform-36 confirmation on first push.
  - Note: the subagent runtime (worker/observer pattern) was broken this session — even a trivial
    "reply ok" prompt failed with `subagent run failed` — so Phase 0 ran in the main session with a
    self-review pass against the plan, the official docs, and Gadgetbridge instead.

