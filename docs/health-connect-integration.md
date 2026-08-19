# Health Connect integration — design and implementation plan

Status: **Phases 0–6 complete** (2026-08-17) — vitals, sleep, daily activity, workouts
+ GPS route, and the beyond-iOS set (blood pressure, blood glucose, respiratory rate,
VO₂max, resting HR, nutrition) all export; 16 `WRITE_*` permissions (0 `READ_*`). Phase 6
adds "remove PulseLoop data" (owner-scoped per-type time-range deletion), grant/revocation
watermark resets (uniform grow-reset over the 6 watermark groups), the meal `updatedAt`
migration (v20→v21), and the archive-restore watermark stamp. Runtime-verified on
`emulator-5554` (API 35), including a foreign-canary removal proof (our records deleted, a
foreign app's record untouched). Both final gates passed: the release R8 smoke (the
minified build ran the Health Connect client with no class-stripping crash) and the API 30
no-Health-Connect-image graceful-degradation check (provider-less API 30 AVD: no crash, the
Health Connect screen shows the actionable "update/install" state).
This document's §8 session log is the running record — read the newest entry first when resuming.

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
pl-sleep-<dayEpochMs>[-<i>]             SleepSessionRecord             version = session.updatedAt
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

**Two amendments made when this shipped** (see §8, 2026-08-16):

- **Distance netting must NOT keep iOS's walk/run filter.** iOS needs it because HealthKit splits
  distance across `.distanceWalkingRunning` / `.distanceCycling`. Health Connect has a single
  `DistanceRecord` that every workout's distance lands in, and `ActivityRollup.credit` folds in
  every `useGps` session regardless of type — so the netting set is `useGps` sessions of any type.
  Keeping the filter would double-count a GPS ride.
- **Netting stays switched off until Phase 4 exists.** iOS gates netting on `exportWorkouts` alone
  because its workout exporter already ships; subtracting here before `WorkoutExporter` exists
  would under-report every day containing a workout with nothing writing the difference back, and
  a write-only export cannot repair it. `HealthConnectExporter.WORKOUTS_EXPORTED` is the single
  flag Phase 4 flips, and netting also requires `WRITE_EXERCISE` to be granted (the toggle can be
  on while the permission is denied).

**Verify:** a day with a recorded workout shows daily totals *plus* the workout, without the workout's
calories appearing twice. (Only fully checkable once Phase 4 lands; Phase 3 verified the netting
arithmetic on-device with the flag forced on, then verified the shipped un-netted behavior.)

### Phase 4 — Workouts + GPS route

**Inherited from Phase 3 — do these in this phase:** flip
`HealthConnectExporter.WORKOUTS_EXPORTED` to `true` in the same commit that adds the exporter; and
decide the stale-record question, which only becomes live once netting is on: when a day's netted
leftover falls to ≤ 0 the record is *dropped*, so any previously exported un-netted record for that
day stays in Health Connect un-overwritten (write-only: it cannot be deleted). Writing a floor-value
record would overwrite it, at the cost of asserting a zero the write-data guide says to omit. Two
known imperfections in "netted set == credited set" also want resolving here:
`applyActivityBucketAtomic` overwrites a past day's `distanceMeters` with the ring's bucket sum
(discarding credited GPS metres, so netting would over-subtract after a history re-sync), and
`ActivityRollup.credit` skips sub-minute sessions that netting would still subtract.

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
  records, then clear all watermarks, turn the export **OFF**, and reset the backfill choice to
  `NOT_ASKED` + the `newOnlyStamped` first-enable marker. Mirrors iOS's `removeAllExportedData`
  in spirit but deliberately stricter: leaving it enabled with `backfillChoice=EXPORT_ALL` would let
  the very next background trigger (ring sync, app-start grow, settings open) re-export the whole
  history within ~15 s, silently undoing the destructive removal; re-enabling re-offers the
  backfill dialog so the user picks fresh.
- **Newly-granted permissions need a watermark reset, not just revoked ones.** Each group has one
  watermark but several independently grantable record types (activity covers steps, active
  calories and distance). Grant only `WRITE_STEPS` and the activity watermark advances past every
  historical day; granting `WRITE_ACTIVE_CALORIES_BURNED` later never backfills them, because the
  DAO selects on `updatedAt > watermark`. Diff the granted set in both directions and reset the
  affected group's watermark when it *grows*. (Phase 1's vitals group has the same shape.)
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
record). Status: **Phases 0–6 complete** on `feat/health-connect-foundation` (pre-merge) — see the newest entry below and the top STATUS line.

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
- **2026-08-15 — Phase 1 implemented (vitals export engine + worker)** (branch
  `feat/health-connect-foundation`).
  - Shipped: `health/HealthConnectTypeMappings.kt` (pure, unit-testable: the `pl-hr-<hourEpochMs>`
    / `pl-hr-<hourEpochMs>-<i>` and `pl-m-<kind>-<epochMs>` clientRecordId scheme ported from iOS
    `HealthKitTypeMappings.swift`; local-hour bucketing; Gadgetbridge HR segmentation — split on
    local-date change, >15 min gap, or 1000 samples; plausibility guards matching platform insert
    validation; `demo`/`mock` source exclusion); `health/HealthConnectExporter.kt` (the pass
    orchestrator + `healthConnectInsertChunked`: 200 records/call, 5 retries, 1/2/4/8/16 s backoff,
    SecurityException aborts immediately, watermark = max high-water of the last *successful* chunk);
    `health/exporters/VitalsExporter.kt` (HR as per-hour series of `HeartRateRecord`s with per-segment
    clientRecordId and version = max `createdAt` in the hour; SpO₂, HRV-as-RMSSD, and body
    temperature as one instantaneous record per row, version 1); `health/HealthConnectExportWorker.kt`
    (debounced OneTimeWorkRequest, `REPLACE`; hard gate on `NOT_ASKED`; live per-kind re-check of the
    granted set on every pass; group watermark = min of per-kind highs); DAO `createdSince` /
    `rangeReal` queries; triggers on ring-sync done, background-sync done, **first-enable grant**,
    and **backfill-dialog answer** (the last two so a user who enables the export with history
    already in the DB gets an export without waiting for the next ring sync).
  - 1.1.0 API gotchas found by `javap` (beyond Phase 0's): the client factory is the synchronous
    companion `HealthConnectClient.getOrCreate(Context)` — there is **no suspend `get`** in stable
    1.1.0 (Gadgetbridge's 1.1.0 provider matches); `PermissionController` is an **interface**
    obtained as `client.permissionController` (no `(client)` constructor); `Metadata`'s factories
    require a **non-null** `Device`, so with no real ring paired the export is attributed to an app
    device (`TYPE_PHONE`/"PulseLoop"/"app") — the Android form of iOS' "attributed to the app only";
    the HRV record class is `HeartRateVariabilityRmssdRecord` (not `HeartRateVariabilityRecord` —
    the plan's name is wrong; platform bounds 1.0–200.0 ms match `isPlausibleHrvRmssd`).
  - Verified: `testDebugUnitTest` — 25 new tests (17 type-mappings: id formats, hour bucketing in
    UTC and +05:30, all four plausibility bound pairs, segmentation edge cases including the exact
    15:00 gap, the 1000-sample cap, and unsorted input; 8 chunk/retry: chunk sizes, the 400-boundary,
    backoff timing under virtual time, SecurityException no-retry, retry exhaustion, watermark
    stopping at the last successful chunk) — full suite **915 green**; `installDebug`.
  - **Runtime verification (API 35) DONE on `pulseloop_test`** by injecting known rows through
    `run-as … sqlite3 pulseloop.db` (5 live HR rows in one local hour — 3 samples + a 38-min gap +
    2 samples, so exactly two segments; 1 **demo** HR row; SpO₂ 97 %; HRV 42 ms; temp 36.6 °C), then
    driving the real worker (grant-result trigger; 15 s debounce) and reading `logcat -s
    HealthConnectExport` + the persisted prefs:
    1. Pass 1 (backfill, `EXPORT_ALL`): `pass done: exported hr 2, spo2 1, hrv 1, temp 1` — the two
       HR series records carry the suffixed segment ids, the demo row was excluded, and the
       platform accepted every insert (HC validates value/units on insert).
    2. Pass 2 (immediate re-run): `pass done: nothing new to export` — the vitals watermark
       advanced to the injected rows' `createdAt`, so no rework.
    3. Pass 3 (watermark reset to null in the prefs file, app restarted): the **same five** records
       re-exported — identical deterministic clientRecordIds re-inserted without error, i.e. the
       upsert identity holds for the write-only path.
    4. Group watermark observed as `min(per-kind highs)` in the persisted blob after each pass.
  - Bug found and fixed during verification: the worker wrote `lastSyncSummary` but not
    `lastSyncAt`, so the settings "Last sync" card could never update; now both are stamped and the
    card renders (`Last sync: nothing new to export` observed on-screen).
  - Verification limitation (honest report): this image has no Health Connect data-browser app
    (no Play Store), so "record count unchanged on re-run" was verified as identical-id re-upserts
    accepted by the platform (Pass 3) plus the logcat counts, not by visually inspecting the HC
    store. The API 30 no-HC graceful-degradation check and the CI platform-36 confirmation remain
    pending (see Phase 0 entry).
  - Subagents still broken (`subagent run failed` on the trivial probe) — Phase 1 also ran solo with
    the self-review pass; the `read_image` capability declaration for this model still rejects image
    input, so UI verification stayed on `uiautomator dump` + coordinate taps.
- **2026-08-16 — Phase 2 implemented (sleep export)** (branch `feat/health-connect-foundation`).
  - Shipped: `health/exporters/SleepExporter.kt` (one `SleepSessionRecord` per `SleepSessionEntity`,
    stages from that session's `SleepStageBlockEntity` rows; stage map DEEP/LIGHT/REM/AWAKE/UNKNOWN
    → the client's `SleepSessionRecord.STAGE_TYPE_*` constants — never raw ints; normalization:
    sort by start, clamp to session bounds, drop overlaps keeping the earlier, drop
    zero/negative-length stages; sessions with no valid stages are skipped and counted, with a
    later re-sync re-selecting them via `updatedAt`); `HealthConnectTypeMappings` gains the pure
    sleep helpers (`sleepSessionRecordId`, `mainSleepIndex`/`sleepSessionSuffix`, `sleepStageType`,
    `normalizeSleepStages`); `HealthConnectExporter.run()` gains the sleep group pass (its own
    `SLEEP` watermark, per-kind toggle + live permission gate, same chunk/retry and
    advance-only-to-landed watermark semantics); `SleepSessionDao.updatedSince(watermark)` —
    `updatedAt > :watermark AND sourceRaw NOT IN ('demo','mock')` (mirrors Phase 1's
    `createdSince`). No manifest change: `WRITE_SLEEP` was already declared in Phase 0 and
    `HealthConnectPermissions.sleep` already derived it via
    `HealthPermission.getWritePermission(SleepSessionRecord::class)`.
  - **Identity decision (deviation from the plan's letter, in its spirit):** the plan says key on
    the session's `date` because it is "uniquely indexed and stable" — but `date` is the waking
    day's local midnight and is **not unique**: `reconcileWakingDay`/`SleepSegmentation` can hold a
    main night plus a daytime nap on one waking day (see `SleepSessionDao.byDay`'s "a day can now
    hold several sessions"). Two sessions sharing one `clientRecordId` would silently replace each
    other in Health Connect. Resolution: the day's **main** session (longest, ties to earliest
    start — the same main sleep `byDay()` surfaces) keeps the plain `pl-sleep-<dayEpochMs>`;
    additional sessions take deterministic suffixes `pl-sleep-<dayEpochMs>-<i>` (1-based, startAt
    order among non-main). Single-session days — the common case — are exactly the plan's id.
    Accepted edge (mirrors the HR hour-split edge): if a nap later joins/leaves the day, suffixed
    ids shift and one superseded record remains in Health Connect (write-only: cannot delete it;
    its content is re-exported under the new ids on the same pass).
  - API verified against the 1.1.0 jar (`javap`) + decompiled source: constructor is
    `(Instant startTime, ZoneOffset startZoneOffset, Instant endTime, ZoneOffset endZoneOffset,
    Metadata, String title, String notes, List<Stage>)`, `Stage(Instant, Instant, int)`; the
    constructor enforces sorted, non-overlapping (touching allowed) stages inside the session
    bounds — `normalizeSleepStages` is written to satisfy exactly that. Stage constants pinned in
    tests: UNKNOWN=0, AWAKE=1, LIGHT=4, DEEP=5, REM=6. `clientRecordVersion` =
    `session.updatedAt` (plan identity table) — a re-synced, fuller night always wins the upsert.
    The official-docs MCP index only holds the Health Connect landing page, so upsert semantics
    rest on plan §3 + Phase 1's runtime proof; Gadgetbridge's `SleepSyncer` (frozen-id +
    grow-in-place, `Metadata.autoRecorded`) informed the shape.
  - Verified: `testDebugUnitTest` — 24 new tests (all five stage mappings + pinned constant
    values + unrecognized-raw fallback; plain/suffixed id formats; id stability across re-sync and
    block-UUID churn; main-sleep selection incl. ties and empty day; single-session / nap /
    two-nap suffix determinism; normalization: sort, clamp, overlap-truncate, fully-covered drop,
    reach-session-end drop, touching allowed, zero/negative drop, out-of-bounds drop,
    non-positive session span; sleep watermark = max landed session `updatedAt`, non-monotonic
    input, partial-failure stop; `SLEEP` watermark monotonicity) — full suite **939 green**;
    `assembleDebug`.
  - **Runtime verification (API 35) DONE on `pulseloop_test`** (fresh install this session —
    onboarding completed with "Explore without ring", BT permission allowed, HC permission sheet
    "Allow all", backfill "Sync all history"). This image has **no HC data-browser app**, so
    records were confirmed **directly in the provider's store** (`adb root` →
    `sqlite3 /data/system_ce/0/healthconnect/healthconnect.db` — `sleep_session_record_table` +
    `sleep_stages_table`), a stronger check than Phase 1's acceptance-based one. Injected two
    `sourceRaw='ring'` nights via `run-as … sqlite3`: Night A 23:40→07:10 (450 min, 10 blocks
    covering LIGHT/DEEP/REM/AWAKE) with waking-day `date` = today's local midnight, and older
    Night B 23:40→06:30 (410 min, 5 blocks). Emulator TZ is America/Los_Angeles (PDT, offset
    −25200 s) — the injected `date`/`startAt`/`endAt` are true local-midnight/local instants.
    1. Pass 1: `pass done: exported sleep 2` — store shows exactly
       `pl-sleep-<dayA>` and `pl-sleep-<dayB>` with the correct spans, 15 stages total with the
       right types (4/5/6/1), versions = each session's `updatedAt`, zone offsets −25200.
    2. Pass 2 (toggle off→on re-run): `pass done: nothing new to export` — counts unchanged
       (2 records / 15 stages).
    3. Growth (Night A re-sync: last LIGHT block 25→55 min, session end 07:10→07:40,
       `updatedAt` bumped): `pass done: exported sleep 1` — the SAME `pl-sleep-<dayA>` record
       updated in place (version advanced, end extended to 07:40, last stage extended), record
       count still 2 — no orphan second session. Sleep watermark advanced to exactly the landed
       `updatedAt`.
    4. Block-UUID churn (Night B: 5 blocks deleted, re-inserted with fresh ids, same times,
       `updatedAt` bumped): `pass done: exported sleep 1` — the SAME `pl-sleep-<dayB>` record
       re-upserted in place, stages byte-identical, record count still 2 — no duplicates from the
       churned block UUIDs.
    5. Bonus observation (mid-4, when Night B's blocks were deleted before the re-insert landed):
       the pass reported `skipped: sleep: 1 session(s) without valid stages` and left the
       watermark untouched — the empty-stages guard + re-selection path working as designed.
    6. Final no-op pass: `nothing new to export`. Watermarks persisted monotonically
       (`sleep`: 1786892400000 → 1786896000000 → 1786899000000, each exactly the max landed
       session `updatedAt`); `vitals` watermark stamped to the pass time (empty-kind rule);
       `lastSyncSummary` live on the settings card.
  - Sandbox note (for the next phase): this agent's file sandbox is workspace-write —
    `~/.gradle` and the SDK are read-only, so the build ran with
    `GRADLE_USER_HOME=/tmp/dsh-gradle-home` (one-time 7.8 G copy of the warm cache) and the
    extracted wrapper dist's `bin/gradle` directly. `adb root` works on this AVD (google_apis),
    which is what made direct store inspection possible.
  - Left behind on the AVD: app connected (10/10 permissions, all toggles on, `EXPORT_ALL`),
    the two test nights in the app DB (grown Night A + churned Night B), 2 records in the HC
    store, `adb` running as root.
- **2026-08-16 — Phase 3 implemented (daily activity export)** (branch
  `feat/health-connect-foundation`).
  - Shipped: `health/exporters/ActivityExporter.kt` (one `StepsRecord` +
    `ActiveCaloriesBurnedRecord` + `DistanceRecord` per `ActivityDailyEntity`, spanning the local
    day clamped to `min(endOfDay, now)`); `HealthConnectTypeMappings` gains the pure activity
    helpers (`activityRecordId`, `activityDayEndMs`, the three range guards, `activityLeftover`,
    `workoutNetting` + `NettableSession`/`WorkoutNetting`); `HealthConnectExporter.run()` gains the
    activity group with its own `ACTIVITY` watermark and **per-metric** permission gating (three
    record types, three independently grantable permissions);
    `ActivityDailyDao.updatedSince(watermark)` and
    `ActivitySessionDao.finishedStartedBetween(from, to)`. No manifest change — all three
    `WRITE_*` permissions landed in Phase 0.
  - **Identity is simpler than sleep's:** `activity_daily.date` genuinely *is* uniquely indexed
    (`CoreEntities.kt:74`), so `pl-act-<metric>-<dayEpochMs>` needs no suffix scheme;
    `clientRecordVersion = row.updatedAt`. `date` is re-normalized through
    `TimeUtil.startOfDayLocal` before use (iOS does the same at `HealthSyncService.swift:270`) —
    it is local midnight *in the zone it was written in*, and a stored off-zone midnight would
    otherwise emit a second overlapping record for one calendar day, which Health Connect sums
    rather than de-duplicates for an app's own records.
  - **Workout netting — ported, but deliberately switched off until Phase 4.** The port itself
    diverges from iOS in one way (drop the walk/run distance filter: Health Connect has a single
    `DistanceRecord`, and `ActivityRollup.credit` folds in every `useGps` session regardless of
    type). The gate is the bigger call: iOS nets on `exportWorkouts` alone because its workout
    exporter already ships, whereas here `WorkoutExporter` does not exist yet, so netting on the
    toggle would subtract energy and metres nothing writes back — silent, unrepairable
    under-reporting on every day containing a workout. `HealthConnectExporter.WORKOUTS_EXPORTED`
    (false) is the one flag Phase 4 flips; netting also requires `WRITE_EXERCISE` granted, which
    still matters after Phase 4 because the toggle can be on while the permission is denied.
  - **Observer review found 13 items; 5 were fixed as real defects**, the rest documented:
    1. Netting active with no workout exporter → the gate above (**blocker**).
    2. `ORDER BY date ASC` broke the chunked watermark invariant — `healthConnectInsertChunked`
       advances to the max high water of the last successful chunk, which is only sound when
       records arrive in high-water order, and a history re-sync restamps an *old* day's
       `updatedAt`. Now `ORDER BY updatedAt ASC`. **The same defect was in Phase 2's
       `SleepSessionDao.updatedSince` and is fixed in this commit too.**
    3. Phase 3 is the first group where one source row emits several records sharing one high
       water, so a chunk boundary falling inside a day could strand an unlanded sibling below an
       advanced watermark. `healthConnectInsertChunked` now clamps a failed pass to the largest
       completed high water strictly below everything still pending.
    4. The steps guard now stops at the app's own corruption threshold (200 000,
       `EventPersistenceSubscriber.kt:374`) rather than the platform's 1 000 000 — a write-only
       store cannot be retracted, and the app self-heals such rows only on the next sync.
    5. Comments that overclaimed were corrected: "netted set == credited set" is imperfect in two
       known ways (`applyActivityBucketAtomic` overwrites a past day's distance, discarding the
       GPS credit; `ActivityRollup.credit` skips sub-minute sessions), the raw `calories` column is
       only the ring's device figure when `source != ring_history && calories > 0`, and the
       empty-pass watermark stamp does not retire a zero-metric day as previously claimed.
       Deferred with a written home: the ≤ 0-leftover stale-record window → Phase 4; newly-granted
       permissions needing a watermark reset → Phase 6.
  - API verified against the 1.1.0 jar (`javap`): all three records are `IntervalRecord`s with the
    constructor `(Instant, ZoneOffset, Instant, ZoneOffset, <value>, Metadata)`; steps is a bare
    `Long`, energy is `Energy.kilocalories`, distance is `Length.meters`. Validation is a union of
    two validators — Jetpack's `require`s below Android 14 and the platform's `requireInRange` from
    14 up (`StepsRecord.kt:44-52` branches on SDK level): steps `1..1_000_000` (Jetpack's floor is
    1, the platform's 0), energy and distance `0..1_000_000`, `startTime` strictly before `endTime`
    pre-U, and on U+ `startTime` must not be in the future. Guards drop rather than clamp
    (Gadgetbridge's style, so one bad row cannot sink its 200-record chunk); NaN and ±∞ fall out of
    every comparison for free. Note **Gadgetbridge is not a precedent here** — it writes per-minute
    activity records, not day aggregates, and avoids double counting by *suppressing* the workout
    side (`RecordedWorkoutSyncer.kt:330-334`) rather than netting.
  - Verified: `testDebugUnitTest` — 24 new tests (id tokens and stability; the day-span clamp for a
    past day, today, a future day and exact midnight, plus both DST directions in
    America/Los_Angeles; all three range guards including the 200 000 step ceiling; netting sums,
    the GPS-only distance rule, day separation, null/non-positive inputs; leftover subtraction
    including the negative and exactly-zero cases; the chunk-boundary sibling clamp in both the
    stranding and clean-boundary shapes; and the netting gate) — full suite **963 green**;
    `assembleDebug`, `installDebug`.
  - **Runtime verification (API 35) DONE on `pulseloop_test`** (fresh image this session:
    onboarding via "Explore without ring", location/BT/notification permissions, HC sheet
    "Allow all" → 10/10, backfill "Sync all history"). TZ America/Los_Angeles (−25200). Injected
    five days and three workouts through `run-as … sqlite3`, chosen so each netting rule has a
    discriminating case: day A = today (8 500 steps / 520 kcal / 6 400 m) with a **GPS run**
    (180 kcal / 2 000 m) and a **still-recording** walk (999 kcal / 9 999 m); day B = 3 days ago,
    no workout; day C = `ring_history` with a **non-GPS gym** session (90 kcal / 1 500 m);
    day D = `demo`; day E = all-zero.
    1. First trigger: `nothing new to export` — correct, and a useful confirmation: the earlier
       empty pass had already stamped the activity watermark to *now*, so the backdated injected
       rows fell below it. Re-stamping `updatedAt` (what a real sync does) released them.
    2. With netting forced on: `exported activity 9 · skipped: activity: 1 day(s) with nothing to
       export`. Records confirmed **directly in the provider store** (`adb root` →
       `sqlite3 /data/system_ce/0/healthconnect/healthconnect.db`): day A steps 8 500 (**not**
       netted — no per-workout step record exists to double against), energy 340 kcal
       (520 − 180 ✓), distance 4 400 m (6 400 − 2 000 ✓), `end_time` clamped to *now* rather than
       end-of-day; day C energy 160 kcal (250 − 90 ✓) but distance **1 200 m un-netted** — the
       discriminating case for the `useGps` rule; day B whole; day D absent (demo excluded, and
       not counted as skipped since the source filter drops it before the loop); day E absent and
       counted as the one skipped day. The recording session's 999 kcal / 9 999 m never applied.
       Zone offsets −25200, versions = each row's `updatedAt`.
    3. Re-run: `nothing new to export`, counts unchanged.
    4. Growth (day A → 11 200 steps / 640 kcal / 7 900 m, `updatedAt` bumped):
       `exported activity 3` — the **same three ids** updated in place (11 200 / 460 kcal /
       5 900 m), record counts still 3 / 3 / 3, no orphans.
    5. Shipped configuration re-verified after the observer fixes (netting gated off):
       `exported activity 9`, day A now 640 kcal / 7 900 m and day C 250 kcal / 1 200 m — the full
       totals — still 3 / 3 / 3 records, ids unchanged by the `startOfDayLocal` normalization.
    6. Final pass `nothing new to export`; activity watermark 1786885973764, above the max row
       `updatedAt` 1786885888790 — the empty-pass stamp, monotonic.
  - **Toolchain gotcha (cost ~20 min):** a stale untracked `.kotlin/` directory (Kotlin 2.x
    project-level incremental state, carried over from another machine's build) made
    `compileDebugUnitTestKotlin` fail with **63** bogus errors — `internal` members of `main`
    unresolved from the test source set (`isConnectTransition`, `connectPurge`, `colorToHex`, …)
    plus a phantom opt-in error. It reproduced at HEAD with every Phase 3 change stashed, which is
    what proved it environmental. `rm -rf .kotlin app/build/kotlin` fixed it outright. Separately,
    this session's Bash sandbox blocks the Kotlin compile daemon's writes to
    `~/Library/Application Support/kotlin/daemon`, and the silent in-process fallback loses
    `-Xfriend-paths` (the same internal-visibility symptom) — Gradle needs the sandbox escalation
    here, as the emulator does for `~/.android`.
  - Left behind on the AVD: app connected (10/10, all toggles on, `EXPORT_ALL`), five activity days
    + three sessions in the app DB, 9 activity records in the HC store (un-netted), `adb` as root.

- **2026-08-16 — Phase 4 implemented (workouts + GPS route)** (branch
  `feat/health-connect-foundation`, commit `44d9794`, on top of Phase 3's `1ec0c1e`).
  - Shipped: `health/exporters/WorkoutExporter.kt` (one `ExerciseSessionRecord` per finished
    `ActivitySessionEntity` — type map, `ActivityMeta.label` title, session notes, embedded
    `ExerciseRoute` from the session's `accepted` GPS fixes plus sibling
    `ActiveCaloriesBurnedRecord` / `DistanceRecord` over the session window);
    `health/HealthConnectWorkoutDeletion.kt` (the Phase 4 deletion hooks); the workouts group in
    `HealthConnectExporter.run()`; `insertChunkWithRouteShrink` + `shrinkOversizedRoute` (the
    1 MB single-record fallback); the pure helpers in `HealthConnectTypeMappings`
    (`workoutRecordId`/`workoutChildRecordId`, `exerciseType`, `selectWorkoutSession`,
    `GpsRoutePoint`/`sanitizeRoutePoints`, `parseRecordSizeLimit`, `decimateToSize`,
    `creditedActiveMinutes`); `ActivitySessionDao.finishedUpdatedSince`
    (`ORDER BY updatedAt ASC` — the chunked-watermark invariant) and
    `ActivityGpsPointDao.forSessions` (one query for the whole pending set); deletion hooks in
    `WorkoutSummaryScreen` (UI trash) and `PendingActionExecutor` (coach
    `delete_activity_session` — the Android confirm flow is not wired to a UI yet, so that path is
    dead code today; the hook is in place for when it lands). No manifest change —
    `WRITE_EXERCISE` + `WRITE_EXERCISE_ROUTE` landed in Phase 0, and there are still **no
    READ_* permissions**.
  - **Siblings == netting set, enforced structurally:** energy is written for every finished
    session with plausible calories (the exact set `workoutNetting` subtracts); distance is
    written only for `useGps` sessions (the Phase 3 amendment — the set `ActivityRollup.credit`
    folds into the daily row). Each sibling is also gated on its **own** write permission — the
    first observer-flagged defect of the phase, fixed before the first runtime pass.
  - **WORKOUTS_EXPORTED flipped to true in this commit** (plan: same commit as the exporter), with
    one addition the plan's stale-record question forced: a **one-time netting-flip reset**. The
    ≤ 0-leftover case (decided: **drop, do not floor** — the floor cannot reliably repair the stale
    record because the stale day is not re-selected, and the write-data guide says to omit zero
    values) is one window; the *other* pre-flip staleness is that every daily record exported under
    the Phase 3 build is UN-netted, and with the workout siblings now live, every such day containing
    a workout over-counts by the workout's own energy/distance until the day happens to be
    re-selected. `HealthConnectPrefs.nettingFlipDone` (absent from Phase 3 blobs → false, which is
    exactly the "still needs the flip" state for upgrading users) +
    `HealthConnectPrefsStore.resetWatermarks(setOf(ACTIVITY, WORKOUTS))` (monotonic
    `setWatermark` can never rewind, so the reset is its own method) re-export every day and
    session on the first netting-live pass; the re-upsert is idempotent — same clientRecordIds,
    higher-or-equal versions.
  - **The two Phase 3 netting imperfections, resolved:** (1) `workoutNetting` now skips the same
    sub-minute sessions `ActivityRollup.credit` never credits — the credit-eligibility arithmetic
    is ported verbatim (`creditedActiveMinutes` = `minutesFor`: full active minutes after
    pauses, `minutes <= 0` → skip); (2) `applyActivityBucketAtomic`'s past-day distance
    overwrite is **self-healing, no code change**: it stamps `updatedAt`, so the day is
    re-selected and re-exported with the ring-only leftover while the workout's distance sibling
    restores the credited metres — the consumer sum becomes the ring's own day total, the correct
    reading for a past day (the only residual is the accepted ≤ 0 window).
  - 1.1.0 API facts confirmed by `javap` on the AAR (and by the research subagent):
    `ExerciseSessionRecord(Instant, ZoneOffset, Instant, ZoneOffset, Metadata, int exerciseType,
    String? title, String? notes, List<ExerciseSegment>, List<ExerciseLap>, ExerciseRoute?,
    String? plannedExerciseSessionId)` with defaults from `title` on; the constructor (and
    `ExerciseRoute(List<Location>)`) **client-side reject** a route whose points leave the
    parent's [startTime, endTime] or repeat a timestamp (`IllegalArgumentException`) — which is
    exactly what the sanitiser guarantees before construction; `Record` is a bare interface (no
    child-sample types in 1.1.0 — siblings are the right Android pattern);
    `deleteRecords(KClass, recordIdsList, clientRecordIdsList)` with **both lists non-null and no
    defaults**, and **no client-side permission check** (the granted-set diff in the deletion hook
    is the only guard).
  - Verified: `testDebugUnitTest` — 31 new tests (the full ten-way exercise-type map + fallback;
    title pinning against `ActivityMeta.label`; id scheme; the INVALID/FUTURE guard boundaries
    incl. `endedAt == now` not-future; route sanitisation — window bounds inclusive, NaN/±∞,
    latitude ±90 / longitude ±180 bounds, duplicate-timestamp keep-first, unsorted input, inverted
    window, the 1-point "no route" boundary; `parseRecordSizeLimit` against the platform message
    format; decimation first/last preservation, strictly-increasing indices, the target < 2 clamp,
    and no-index-duplication for every target 2..size−1; the shrink wrapper — offender-only
    decimation by ratio, unrelated errors pass through as null, the 2-point no-op floor that
    prevents retry loops, immediate-retry-on-shrink, no-backoff, SecurityException aborts; the
    credit-eligibility port and its netting skip; the netting gate now asserting the flipped
    constant; the flip marker's tolerant decode and `resetWatermarks` semantics incl. persistence
    across a store reload) — full suite **994 green**; `assembleDebug`, `installDebug`.
  - **Runtime verification (API 35) DONE on `pulseloop_test`**. Deviation from the plan's
    literal "record a real GPS walk via `adb emu geo fix`": **this emulator build's console
    `geo fix` parser only accepts integer coordinates** (`37.8037` → "KO: invalid latitude",
    `10 20 30` → OK) and its NMEA path (`geo nmea`) accepts sentences with valid checksums
    (GP and GN talkers, GLL/RMC/GGA/GSA all tried) but produces no provider fix — verified via
    `dumpsys location` (last fix unchanged after ~10 injected sentences). The walk-profile speed
    cap (5 m/s) also rules out integer-degree jumps (one step = 111 km). So the route itself was
    verified with a fixture session shaped exactly as `GpsRouteRecorder.ingest` persists one
    (accepted/rejected/out-of-window/duplicate rows), while the **recording flow was real**: a
    live UI walk (start via the type picker, GPS on) that ingested the console's integer fixes —
    1 accepted + 3 speed-rejected points, persisted with reasons — then finished through the
    summary's Finish button (28:44, 117.33 kcal, no distance: one accepted point).
    1. First pass after the flip: `exported activity 9, workouts 7` + the flip reset fired
       (`nettingFlipDone` false → true, ACTIVITY/WORKOUTS watermarks nulled and re-advanced).
       The pre-flip staleness was repaired live: day C's stale un-netted energy record
       (250 kcal, exported under Phase 3) was **overwritten in place at the same
       clientRecordId** with its netted value (160 = 250 − 90), and the same-version re-upsert of
       unchanged rows was accepted by the platform (9 activity records, zero insert errors).
    2. Store state (`adb root` → provider DB): 3 sessions — `pl-wk-wkA` (store code 33 =
       EXERCISE_TYPE_RUNNING (library constant 56) normalized provider-side; version =
       session.updatedAt; 7:00–7:30 PDT), `pl-wk-wkC` (store code 45 = EXERCISE_TYPE_STRENGTH_TRAINING
       (70), no distance sibling: useGps=0), the real walk (store code 53 = EXERCISE_TYPE_WALKING
       (79), **has_route=0** — one accepted point < 2, session still written, exactly the plan's
       rule; titles — "Running"/"Gym"/"Walking" — confirm the map landed); today's daily records netted to
       the milli: energy 342.672 kcal = 640 − 180 − 117.328 (sibling records 180 + 117.328),
       distance 5 900 m = 7 900 − 2 000 (sibling 2 000), steps 11 200 un-netted; consumer sums
       342.672 + 180 + 117.328 = 640.000 and 5 900 + 2 000 = 7 900.
    3. Route fixture (24 rows: 20 clean 30 s-interval points at walk speed, 1 duplicate
       timestamp, 1 speed-rejected, 1 before start, 1 after end): **20 route points** landed in
       `exercise_route_table` (accepted-only, window-inclusive, duplicate dropped, rejected and
       out-of-window excluded), `has_route=1`, notes exported; its day (1 200 → 2 000 m stored,
       +800 credited, −800 netted → daily 1 200 + sibling 800 = 2 000) and energy (250 → 100 =
       250 − 90 − 60, siblings 90 + 60) both balance.
    4. **≤ 0-leftover drop verified live:** a day holding 10 kcal with a 50 kcal finished session
       produced **no** daily records at all (energy 10 − 50 = −40 dropped; steps/distance 0
       dropped) while the session + its 50 kcal sibling exported — the documented accepted window.
    5. Re-run: counts unchanged (5 sessions / 8 energy / 5 distance / 3 steps / 20 route points),
       no duplicates; the zero-metric day re-queries once and self-retires on the next
       empty-pass stamp (the documented bounded re-query). Count reconciliation: 5 sessions =
       wkA + wkC + the live walk + wkRoute + wkTiny; 8 energy = those 5's siblings (all have
       plausible kcal) + the 3 daily ActiveCalories records (days 08-13, 08-14, 08-16); 5
       distance = the 2 useGps siblings (wkA 2 000 m, wkRoute 800 m) + the 3 daily Distance
       records (9 000 / 1 200 / 5 900); 3 steps = the 3 daily Steps records; 20 route points
       all under wkRoute.
    6. **Deletion hooks verified through the real UI trash:** deleting the live walk removed
       `pl-wk-504a…` + its energy sibling from the store (local row → `statusRaw='deleted'`,
       no longer re-selected by the finished-only query); deleting the route session removed the
       session, **all 20 route rows** (provider-side cascade on the parent) and both siblings
       (3 / 6 / 4 / 3 counts after). WRITE-only deletion (no READ_* held) works on the provider.
  - Sandbox/toolchain: same as Phase 3 — Gradle runs need the one-shot sandbox escalation
    (`~/.gradle` + the Kotlin daemon dir are read-only under workspace-write); the emulator's
    `adb` plumbing likewise. No stale-incremental-state incident this time.
  - Left behind on the AVD: app connected (10/10, all toggles on, `EXPORT_ALL`,
    `nettingFlipDone=true`), 7 sessions (3 finished + 1 recording) and 7 daily rows in the app
    DB, 3 exercise sessions + 6 energy + 5 distance + 3 steps records and 0 route rows in the HC
    store (the route session was the one deleted), `adb` as root.
  - Accepted residuals left on the record (observer stage B, both match iOS semantics, neither
    a code change): (a) a FUTURE-dated session blocks the workouts pass while its energy and
    distance are still netted out of its day (`finishedStartedBetween` has no now-check) —
    bounded under-report on that day until the day row restamps; future-dated sessions are
    clock-skew pathology only. (b) a corrupt INVALID row carrying calories > 0 is
    watermark-leapfrogged yet still netted — same under-report shape, same low exposure (the
    coach update path recomputes kcal to null/0 for zero-duration sessions).
  - **Observer stage-B review (49-item checklist): 1 BLOCKER + 2 SHOULD-FIX, all fixed in the
    same-day follow-up.** (1) BLOCKER — on a partial chunk failure, the workouts watermark could
    leapfrog unlanded valid sessions via the record-less INVALID rows' `invalidHighWater` (same
    defect class as the Phase 3 chunk-stranding bug, reached through the record-less path):
    the advance decision is now the pure `workoutsWatermarkAdvance`, and `invalidHighWater`
    applies only when the pass fully completed. (2) The deletion hook gated on the master export
    toggle, so a delete made with the export off would have left ghost records — it now gates on
    availability + the per-class permission only (iOS parity: availability only). (3) The
    netting-flip reset now additionally requires `backfillChoice == EXPORT_ALL` — a "Only new
    data from now on" user's consent boundary is not re-opened by a full-history re-export (their
    narrower pre-flip residual is accepted and documented at the flip condition). Also adopted
    from the research report: workout records are `Metadata.activelyRecorded` (user-initiated —
    Gadgetbridge marks its ACTIVITY-type records actively recorded; the Phase 1–3 ring-data
    groups stay `autoRecorded`).

- **2026-08-16 — Phase 5 implemented (beyond iOS: BP, glucose, resp rate, VO₂max, resting HR, nutrition)** (branch `feat/health-connect-foundation`).
  - **Scope (plan §4):** the six beyond-iOS types + their `WRITE_*` manifest permissions (added this phase, not earlier). One research subagent, one observer subagent (stage A pre-, stage B post-implementation), I coded directly — same split as Phases 0–4.
  - **Shipped:** `HealthConnectTypeMappings.kt` pure helpers (record-id builders, plausibility guards, `pairBloodPressure`, `nutritionMealType`); `VitalsExporter` now builds glucose (`BloodGlucoseRecord`), resp rate, VO₂max (`MEASUREMENT_METHOD_OTHER`) + new `buildBloodPressure` (pairs systolic/diastolic `MeasurementEntity` rows by exact timestamp into one `BloodPressureRecord`, id `pl-m-bp-<ts>`, drops unpaired + out-of-range + demo); new `RestingHeartRateExporter.kt` (single constant-id `pl-resting-hr`, version = `hrRestingBaselineUpdatedAt`, `Math.round` bpm, guard 1..300); new `NutritionExporter.kt` (`MealEntryEntity` → `NutritionRecord` interval start + 60 s, id `pl-meal-<id>`, version = createdAt, `Mass.milligrams` sodium, skips empty meal, validates before build); the RESTING_HR + NUTRITION groups in `HealthConnectExporter.run()`; the 6 `WRITE_*` manifest permissions (16 total, 0 `READ_*`); the 6 per-type toggles (default on) + `RESTING_HR` watermark in `HealthConnectPrefsStore`; 5 new settings rows; `MealEntryDao.createdSince`.
  - **Glucose cap is 900.0 mg/dL, not the plan's 900.91** — verified from the 1.1.0 AAR: the `BloodGlucoseRecord` level cap is 50 mmol/L and the mg/dL→mmol/L factor is 1/18, so 900.0 → 50.0 mmol/L (exactly at the cap) but 900.01 → 50.0006 (throws). The plan's 900.91 (and Gadgetbridge's) is *looser* than this client — flagged in review so nobody "corrects" it back. The guard drops out-of-range samples (20..900.0) rather than clamping, consistent with the other types.
  - **Observer stage-B BLOCKER (fixed): the nutrition energy cap was 1000× too loose.** I had read the platform's `Energy.calories(100_000_000)` cap as a *kcal* value, but `Energy.calories` is **small calories**, so the real cap is 1e8 cal = **100,000 kcal**. A 6-digit kcal typo (500,000) would have passed the guard and thrown from the `NutritionRecord` ctor, wedging the whole export work on every retry (the exact "one typo sinks the chunk" failure the guard exists to prevent). Fixed `MAX_NUTRITION_ENERGY_KCAL` 1e8 → 100_000 + boundary tests.
  - **Observer stage-B SHOULD-FIX (fixed): the nutrition export now also gates on the app's nutrition-feature master toggle (`UserGoalEntity.nutritionEnabled`, default false), not just the Health Connect per-type toggle** — iOS gates nutrition on the feature toggle too, and the "Open Nutrition Log" entry is un-gated, so off-feature meals would otherwise leak to Health Connect. Both must be on.
  - **Phase 1 latent-bug fix (committed `054b5d2` before this phase's work):** every write path stores `kindRaw = MeasurementKind.<X>.name`, but `VitalsExporter` queried `createdSince` by `.key` — the two never matched, so the VITALS group silently exported nothing. Replaced with a shared `kindRaw` map (`.name`) for query + write. Runtime-verified this phase: the 4 new VITALS kinds (glucose/resp/vo2/bp) all use `.name` and export correctly, exercising the fixed query path.
  - **Runtime-verified on emulator-5554 (API 35, google_apis, `adb root`):** granted all 16 perms via `pm grant` (the export's live `getGrantedPermissions()` reads the OS grant; the sheet flow is unchanged); merged manifest = 16 `WRITE` / 0 `READ`. Injected fixtures via host sqlite3 pull/modify/push (the device toybox `sqlite3` cannot read the WAL-mode DB): paired BP 120/80 + 110/70, an orphan systolic, an out-of-range 210/80 pair, a `demo` pair (excluded), glucose 100 + 900.0 + 950 + 0, resp 16 + 70, VO₂ 45 + 150, 2 meals, resting-HR baseline 57.5. Pass result: `glucose 2, resp_rate 1, vo2max 1, bp 2, resting_hr 1, nutrition 2` — every guard/pair/exclusion behaved as designed. Provider store inspected (`/data/system_ce/0/healthconnect/healthconnect.db`): correct `clientRecordId`s, versions, values (glucose 100→5.556 mmol/L, 900.0→50.0 mmol/L; BP 120/80 + 110/70; resting `pl-resting-hr` 58 bpm = round(57.5), version=updatedAt; nutrition `pl-meal-<id>` meal_type 1/2, energy in small-cal, `Mass.milligrams` sodium, NULL sodium ≈ 0). **Re-run → no duplicates** (rows = distinct `client_record_id` in all six tables) — the upsert idempotency the design depends on. Resting-HR re-learn (baseline 57.5→60.0 with a newer `hrRestingBaselineUpdatedAt`) upserted the same `pl-resting-hr` in place (value 60, strictly higher version, count still 1). (The release R8 smoke is the plan's *final* gate per the status line, not a Phase 5 block — R8 is already proven on the 1.1.0 client in Phases 0–4 and the six new record classes are the same AAR public API.)
  - **Nutrition watermark uses `createdAt` — the Stage-A acceptable fallback, NOT the recommended `updatedAt` migration:** `MealEntryEntity` has no `updatedAt` and Android has no in-place meal-edit path yet (meals are insert-once today), so `createdAt` is safe now. The first meal-edit PR MUST add `MealEntryEntity.updatedAt` + a Room migration (`ALTER TABLE meal_entries ADD COLUMN updatedAt`, backfill `= createdAt`), then switch the nutrition watermark + version to `updatedAt` — otherwise in-place edits are invisible to the exporter (createdAt unchanged) and the stale `NutritionRecord` never re-selects; write-only means no repair. iOS's twin already carries `updatedAt` for exactly this.
  - **Stage-B NICE-TO-HAVE polish (applied):** BP drops are now counted + reported — `pairBloodPressure` returns a `BpPairingResult` with `unpaired`/`outOfRange`, and the pass summary gets a "blood pressure: N reading(s) dropped (unpaired or out of range)" line, matching the other groups' skipped counters (stage-A #2). Nutrition records are `Metadata.activelyRecorded` (meals are user-initiated — Phase 4 consistency) and clamp a future-dated `meal.timestamp` to now (iOS parity, clock-skew guard). Fixed the stale Phase-1-bug-fingerprint comment in `HealthConnectPermissions.WRITE_PERMISSION_BY_KIND` (keys are kindKey tokens, not kindRaw) and the VitalsExporter KDoc (now lists the Phase 5 kinds); the glucose KDoc now notes the ceiling is the platform cap (900.0), not the app bridge (600).
  - **Phase 6 input (observer-flagged):** the permission→group reset mapping must map the four new kinds (glucose/resp/vo2/**bp**) → **VITALS** (they share the advanced VITALS watermark). On an *upgrading* install the VITALS watermark is already ahead of historical glucose/resp/vo2/bp rows, so those legacy rows won't backfill until Phase 6's newly-granted-permission reset; fresh installs (all watermarks null) backfill fully — consistent with the Phase 3 precedent. That one VITALS reset also backfills the `.name`-fixed legacy kinds.
  - **Gotcha (device tooling):** `adb push` into `/data/data/…` lands the file root-owned, which crashes the app on the next DB open (Room `SQLiteDatabase.openInner`). Push to `/data/local/tmp` then `run-as … cp` into place (app-owned), or `chown u0_a208:u0_a208` + `chmod 660` after a root push.

- **2026-08-17 — Phase 6 implemented (lifecycle, removal, docs)** (branch `feat/health-connect-foundation`).
  - **Scope (plan §4):** "remove PulseLoop data" (iOS `removeAllExportedData` parity), grant/revocation watermark resets (16 perms → 6 groups, the four Phase-5 kinds → VITALS), meal `updatedAt` migration + nutrition watermark/version on `updatedAt`, archive-restore watermark stamp, `ios-sync.md` update. One research subagent, one observer subagent (pass 1), I coded directly.
  - **Shipped:** `health/HealthConnectPermissionReconcile.kt` (new — `PERMISSION_GROUP` 16→6 map, `groupsFor`, `reconcile`, `onAppStart`; **uniform grow-reset**: any group whose granted set grows gets its watermark nulled — NUTRITION/RESTING_HR "no reset" is just the no-op instance, no key is special-cased); `health/HealthConnectRemoval.kt` (new — `RECORD_TYPES`: 15 record classes × their single WRITE perm (exercise route is embedded, not a 16th class); `removeAll`: cancel the unique export work → per-class WRITE-gated `deleteRecords(class, TimeRangeFilter.after(Instant.EPOCH))` with per-class catch/log/continue → `clearWatermarks()` + null `lastSyncAt`/`lastSyncSummary`); meal `updatedAt` (`NutritionEntities` += `updatedAt`, `PulseLoopDatabase` v20→v21 `ADD COLUMN` + `= createdAt` backfill inside the upgrade transaction, `MealEntryDao.updatedSince`, `NutritionExporter` watermark + record version on `updatedAt`, `DataArchive` DTO round-trip with `createdAt` backfill for old archives); archive-restore stamp (`DataArchiveService.importFile`, after the Room transaction and before return, gated on the device's `enabled`, stamps all six watermarks to `now` — iOS `DataArchiveService.swift:458-461` parity); `HealthConnectPrefs` += `revocationOfferDismissed` (one-shot revoke-offer flag, tolerant decode); `HealthConnectExportWorker` companion `cancel(context)` + the SecurityException path now re-reads the live grant set, reconciles, and corrects the stored set; `SettingsSubScreens.kt` — "Remove PulseLoop data" card + confirm dialog (creation `runCatching`-guarded), the state-based one-shot revocation offer, the LaunchedEffect-on-open reconcile, and the launcher reconcile. New `HealthConnectPermissionReconcileTest.kt` (8 tests, hand-rolled `FakeSharedPreferences`, no mocks per repo constraint).
  - **Deletion uses the time-range overload, not `clientRecordIds`** — research pinned both from primary sources: the 1.1.0 KDoc states the range overload is "automatically filtered to [Record] belonging to the calling application", and AOSP `HealthConnectServiceImpl.java:1128-1131` force-overwrites the data-origin filter to `callerPackageName`; **WRITE-only is required** (`DataPermissionEnforcer.enforceRecordIdsWritePermissions`, no read fallback — unlike the read path). The `clientRecordId` path is infeasible: write-only means stored record IDs can't be enumerated, and the ID overload aborts the whole transaction on any unknown ID.
  - **Design locks:** grow-reset is uniform for all 6 groups (revoke→re-grant re-exports; idempotent-safe); on revocation the (possibly empty) live set is stored (grow-from-empty backstop); the revocation offer is a Gadgetbridge-pattern UX courtesy, one-shot via `revocationOfferDismissed` (cleared on grow, set on all three dismissal paths); the archive stamp is `now` gated on `enabled`; deletion is platform owner-scoped, permission-guarded, per-class isolated, work-cancelled-first (the iOS `isSyncing` latch analogue); and removal also turns the export OFF + resets `backfillChoice` to `NOT_ASKED` and the `newOnlyStamped` first-enable marker (a pass-1 MAJOR fix — an `EXPORT_ALL` re-export would otherwise silently undo the removal within ~15 s; re-enabling re-offers the backfill dialog, deliberately stricter than iOS parity).
  - **Runtime-verified on `emulator-5554` (API 35, arm64, adb root):**
    - Migration 20→21: `user_version=21`, `meal_entries.updatedAt` present, seed meals backfilled (`updatedAt == createdAt` exactly).
    - Grow-reset: stored set narrowed to ACTIVITY (3 perms) + sentinel watermarks → relaunch → `onAppStart` detected the grow → vitals/sleep/workouts/nutrition/restingHr reset to null, activity kept its sentinel, `lastGrantedPermissions` updated to the live 16; a subsequent export then advanced the reset groups.
    - **Removal (closes the observer's MAJOR finding):** planted a *foreign* canary (a hand-inserted `application_info_table` row `com.canary.test` + a `steps_record_table` row under its `app_info_id`) → Settings → Health Connect → "Remove PulseLoop data" → confirm. Result: **all our records gone** (`app_info_id=1` = 0 across all 15 record tables), **the canary survived** (owner-scoping proven at runtime, not only statically), all six watermarks null, `lastSyncAt`/`lastSyncSummary` null, and the granted set preserved. (At the time of this check `enabled`/`backfillChoice` were still preserved too; the pass-1 MAJOR fix since then made `removeAll` also turn the export OFF and reset `backfillChoice`→`NOT_ASKED` + `newOnlyStamped`, so a re-enable re-offers the dialog instead of a background trigger silently re-exporting the whole history.) The status line read "Removed PulseLoop data from Health Connect." (0 failed types). The canary was then removed from the provider store.
  - **Observer pass 1: 1 MAJOR + 4 MINOR, all addressed.** MAJOR — the removal KDoc overclaimed WRITE-only as "verified" with no recorded runtime proof: now runtime-proven (above) and the KDoc states what is statically established (1.1.0 KDoc + AOSP force-filter) versus runtime-confirmed. MINOR — (a) the full-revocation reset offer was unreachable (diff-based; `onAppStart` stores the empty live set before the screen opens): now state-based (`enabled && granted empty && hadSync && !dismissed`) + the one-shot flag; (b) `ios-sync.md`'s "Phases 0–6 complete" was premature: it is finalized only now, with this §8 entry + the final gates; (c) unguarded `HealthConnectClient.getOrCreate` in the remove dialog: now `runCatching` + a status message; (d) stale untracked `android/dist/`: added to `.gitignore`.
  - **Final gates (plan §5.6): release R8 smoke — PASS.** `assembleRelease` (`isMinifyEnabled = true`) built clean — `minifyReleaseWithR8` ran and `mapping.txt`/`seeds.txt`/`usage.txt` are present. The R8 release APK (`com.pulseloop`) was installed on the API 35 emulator with its HC prefs seeded (enabled + 16 granted) and launched: `onAppStart` made the real `getGrantedPermissions` IPC to the Health Connect provider and reconciled the stored set 16→0 (the device's actual grants for `com.pulseloop`), rewriting the prefs — with **no** `NoClassDefFoundError`/`ClassNotFound`/FATAL anywhere in logcat. R8 did not strip the Health Connect client. The **API 30 no-Health-Connect-image graceful-degradation check — PASS.** On a fresh API 30 `google_apis` AVD (`pl_api30`, no Health Connect provider present — confirmed via `pm list packages`), the R8 release APK installed and launched cleanly (no FATAL/`NoClassDefFoundError`), and Settings → Health Connect rendered the actionable unavailable state — "The Health Connect app on this device needs an update before it can be used." with an "Update Health Connect" button — instead of a broken toggle or a crash. The availability guard (`getSdkStatus` → not `SDK_AVAILABLE` → no HC client calls) holds on a provider-less device.
  - **Pre-merge observer pass (commit boundary): NO BLOCKERS, NO SHOULD-FIX — ready to merge on correctness.** The observer independently re-verified the AOSP claim rather than trusting the citation (fetched `HealthConnectServiceImpl.java` from googlesource `main`: `deleteUsingFiltersForSelf` force-sets the package filter to the caller at line 1130 and enforces **WRITE-only** at 1139; the non-self `deleteUsingFilters` requires the platform `MANAGE_HEALTH_DATA_PERMISSION` — so a regular app has exactly one delete path, caller-scoped + WRITE, corroborating the KDoc/§8; the API 35 AVD canary remains the authoritative runtime proof). Two new items: **(1) MINOR** — `revocationOfferDismissed` was cleared on grow only by the two UI paths (settings LaunchedEffect + launcher), not by `onAppStart`'s out-of-band grow, so a pure out-of-band dismiss → re-grant → revoke cycle left the offer suppressed (no correctness impact — the grow-from-empty backstop always re-exports). **Fixed:** `onAppStart` now clears the flag in the same `store.update` when a grow is detected, so the "cleared on grow" contract holds on all three paths (the §8 wording is now fully true). **(2) NIT** — the launcher-path offer lacked the `hadSync` guard the state-based path has (a user who never exported could get a "reset" offer for already-empty state; a harmless no-op, wording slightly off). **Fixed:** the launcher path now applies the same `hadSync` guard, aligning the two offer paths.

- **2026-08-19 — PR #50 review pass 2 (1 MAJOR, 2 MINOR, 1 NIT), all addressed** (branch `feat/health-connect-foundation`).
  - **MAJOR (3811754530) — `EXPORT_NEW_ONLY` pre-consent history leak via a watermark reset.** The pass-1 fix replaced the `wm0.vitals == null` sentinel with a dedicated `newOnlyStamped` flag, but `resetWatermarks` (the grow-reset on a re-granted permission, or the re-enable-vitals-toggle reset) still *nulled* the watermark. A null group watermark means "export from epoch" (`createdSince(kind, 0)`), so a NEW_ONLY user who re-granted a permission out of band — or flipped a vitals row off→on — would have their **pre-consent** history re-exported, exactly what the consent was meant to prevent (the Phase 4 netting flip is deliberately gated on `EXPORT_ALL` for this same reason). **Fix:** persist the consent instant — `HealthConnectPrefs.newOnlyConsentAt` is recorded by the first-enable sentinel (the stamp pass), and `resetWatermarks` now clamps a NEW_ONLY group reset to it instead of nulling (`EXPORT_ALL`/`NOT_ASKED` keep the null-and-backfill-from-epoch behaviour; `consent` is null for a NEW_ONLY user whose sentinel has not stamped yet, all watermarks null). `removeAll` clears it so a fresh re-enable re-stamps.
  - **MINOR (3811754542) — activity recreation stranded the hard-gated state.** `showBackfillDialog` was `remember`, so a rotation/process death while it was up destroyed it WITHOUT running `onDismissRequest`, leaving `enabled=true` + `backfillChoice=NOT_ASKED` — "Connected" but nothing ever exports, with no re-offer. **Fix:** derive it from persisted state (`enabled && isConnected && backfillChoice == NOT_ASKED`) the way the revocation offer already does — recreation re-shows it, and the explicit dismiss becomes redundant.
  - **MINOR (3811754548) — the `false` default reproduced the original bug once at the upgrade boundary.** A pre-fix blob decodes `newOnlyStamped` to `false`; an install that already ran a pre-fix build with EXPORT_NEW_ONLY (watermarks already stamped by the old null-inference sentinel) would take the first-enable branch once after updating and re-stamp every group to now, silently dropping the rows pending since the last pass. **Fix:** in `load()`, when the key is absent AND any watermark is non-null, seed `newOnlyStamped=true` + `newOnlyConsentAt=min(watermarks)` (every watermark was stamped to the consent instant and only ever advanced forward, so the min is ≥ the true consent — safe, never a pre-consent leak). A fresh NEW_ONLY choice (all-null watermarks) is never suppressed.
  - **NIT (3811754553) — doc drift after the pass-1 removal fix.** Updated the Phase 6 plan line, the §8 Design-locks line, and the removal live-verification note to capture that `removeAll` also turns the export OFF + resets `backfillChoice`→`NOT_ASKED` + `newOnlyStamped` (the reason: an `EXPORT_ALL` re-export would otherwise silently undo the removal within ~15 s; re-enabling re-offers the dialog); fixed the stale `MainActivity.onResume` comment ("No-op unless … with a stored grant" — the guard is `!enabled` only) and the backfill dialog's false "You can change this later" copy.
  - **Tests:** 7 new in `HealthConnectPrefsStoreTest` — the NEW_ONLY consent clamp (single key, multi-key, the non-NEW_ONLY null regression, and on-disk persistence) and the upgrade-boundary seed (absent-key seed from the earliest watermark, all-null watermarks not seeded, present-key not overridden). **Verified:** `testDebugUnitTest` (health package: 152 tests, 0 failures — 7 new in `HealthConnectPrefsStoreTest`) + `assembleDebug` green.
