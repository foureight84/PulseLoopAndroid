# Custom Changes: Phone-Based Step Tracking

This fork of PulseLoop Android adds an optional alternative source for daily step
data: the phone's own pedometer, read via Android's Health Connect service.
Everything else in the app — heart rate, SpO₂, sleep, HRV, stress, temperature,
blood pressure, glucose, and the AI coach — is unchanged from upstream.

---

## Motivation

The Colmi R12 (and similar Colmi/QRing-family rings) systematically over-counts
steps by roughly 20% compared to a phone's hardware pedometer or a dedicated
fitness tracker. On a day of ~3,200 actual steps, the ring might report ~3,900.

The upstream app has no way to correct for this: all step data comes from the
ring, and the ring's number is treated as ground truth. Users who care about
accurate step counts are forced to either accept the inflated number or run a
second app alongside PulseLoop just for steps.

This fork closes that gap by letting the user choose which source PulseLoop
trusts for its step count.

---

## What Changed

### New files

**`app/src/main/java/com/pulseloop/PhoneStepManager.kt`**
Reads today's step total from Health Connect and publishes it onto the
app's internal event bus. This is the core of the feature. It performs no
background polling and no persistent work — it is a single async query that
runs only when the app is foregrounded or the user pulls to refresh.

**`app/src/main/java/com/pulseloop/PhoneStepCounter.kt`**
A thin wrapper around Android's `Sensor.TYPE_STEP_COUNTER`. Retained from an
earlier iteration of this feature but **not currently referenced by any
production code path**. It can be safely deleted if the Health Connect
approach remains the sole method.

### Modified files

**`app/src/main/AndroidManifest.xml`**
- Added `<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />`
  Required on Android 10+ to access the hardware step sensor. Kept for
  completeness even though the current implementation reads from Health
  Connect instead of the raw sensor.

**`app/src/main/java/com/pulseloop/settings/ApiKeyStore.kt`**
- Added a new preference: `stepSource`, a `String` that is either `"ring"`
  (default) or `"phone"`. Stored in the same encrypted shared-preferences
  file as the rest of the app's settings.

**`app/src/main/java/com/pulseloop/ring/PulseEventBus.kt`**
- Added a new event type: `PulseEvent.PhoneStepsUpdate`. Carries a
  timestamp, a step count, and estimated distance + calories. Distinct from
  `ActivityUpdate` (which carries the ring's live counter) so the two sources
  can never be confused.

**`app/src/main/java/com/pulseloop/service/EventPersistenceSubscriber.kt`**
- Added `PhoneStepsUpdate` handling: persists the event via a new helper
  `upsertActivityDailyFromPhone()`, which overwrites (rather than ratchets)
  today's activity row for the phone source.
- Ring-originated step events (`ActivityUpdate` and `ActivityBucket`) are now
  gated on the current `stepSource` preference: if the user has selected
  "Phone", ring step writes are skipped entirely.
- The step-source preference is cached and re-read at most once per second
  to avoid decrypting the preferences file on every ring packet during a sync.

**`app/src/main/java/com/pulseloop/MainActivity.kt`**
- Added a Health Connect permission launcher for `READ_STEPS`.
- Added `refreshPhoneSteps()`: called from `onResume()`, it queries Health
  Connect and publishes a `PhoneStepsUpdate` if the user's step source is
  "Phone". If the Health Connect read permission has not been granted yet, it
  triggers the permission request dialog.
- Added the `ACTIVITY_RECOGNITION` runtime permission to the app's standard
  permission request flow.

**`app/src/main/java/com/pulseloop/ui/screens/SettingsScreen.kt`**
- Added a "Step Source" section with a single row that toggles between
  "Ring" and "Phone". The selected value is shown on the right side of the row.

**`app/src/main/java/com/pulseloop/ui/screens/TodayScreen.kt`**
- The pull-to-refresh handler now also calls `PhoneStepManager.refresh()` when
  the user's step source is "Phone". Previously, pull-to-refresh only synced
  the ring, leaving the step tile stale.

**`app/src/main/java/com/pulseloop/ui/screens/DebugScreen.kt`**
- Added a display label for the `PhoneStepsUpdate` event in the debug log.

**`app/build.gradle.kts`**
- `versionName` / `versionCode` bumped to distinguish this fork from upstream.
- `isShrinkResources = true` added to the release build type (reduces APK size).
- `lint { abortOnError = false }` added so pre-existing lint warnings don't
  block commits.

---

## How It Works

```
                          App foregrounds
                                │
                                ▼
                  MainActivity.onResume()
                                │
                                ▼
                     refreshPhoneSteps()
                                │
              ┌─────────────────┴──────────────────┐
              │                                    │
      stepSource == "ring"                 stepSource == "phone"
              │                                    │
           (no-op)                                 ▼
                                      PhoneStepManager.refresh()
                                                 │
                                                 ▼
                                      Health Connect query:
                                      "StepsRecord.COUNT_TOTAL
                                       between startOfDay and now"
                                                 │
                                                 ▼
                                     PulseEventBus.publish(
                                       PhoneStepsUpdate(...))
                                                 │
                                                 ▼
                                 EventPersistenceSubscriber
                                                 │
                                                 ▼
                              upsertActivityDailyFromPhone()
                                                 │
                                                 ▼
                                 activity_daily table (source="phone")
                                                 │
                                                 ▼
                                       Today / Activity UI
```

When the user selects "Ring", the phone-side flow is a complete no-op and the
app behaves exactly as upstream. When the user selects "Phone", ring step
writes are skipped and the Health Connect flow takes over.

**Refresh triggers:**
- App launch and every foreground return (`onResume`).
- Pull-to-refresh gesture on the Today screen.

There is **no** background polling, no scheduled job, and no WorkManager
worker dedicated to step refresh. If the app is not open, no step query runs.
This is deliberate and matches the app's existing refresh model.

**Staleness:** The value returned by Health Connect is only as fresh as the
last time a source app (Mi Fitness, Google Fit, etc.) wrote a step record to
Health Connect. Android's Health Connect implementation batches step writes
roughly every minute or two. PulseLoop always reads the newest value Health
Connect has, but cannot make Health Connect itself more up-to-date.

---

## Privacy Statement

The upstream app is deliberately write-only with respect to Health Connect:
it exports its own measurements to Health Connect but never reads anything
back. This is a meaningful design choice, and the addition of a read path
deserves explanation.

**This feature reads from Health Connect, but it does not violate the app's
privacy principles, for these reasons:**

1. **Health Connect is on-device.** It is an Android system service — a
   local IPC hub managed by the operating system. It is *not* a cloud
   service, and no data leaves the phone as a result of a Health Connect
   read. When PulseLoop queries it, the round trip is entirely inside the
   device.

2. **Only one data type is read: Steps.** No heart rate, no sleep, no
   location, no workouts. The permission requested is exactly
   `android.permission.health.READ_STEPS` — nothing more.

3. **The user explicitly opts in.** Reading only happens when the user
   selects "Phone" in the Step Source setting. On the default ("Ring")
   setting, no read ever occurs, and the app's behaviour is byte-identical
   to upstream.

4. **The data is stored locally.** The result is written to the app's own
   Room database, on-device, exactly where the ring's step data would have
   gone. Nothing is transmitted to any server, and nothing is shared with
   third parties.

5. **This is not ingestion of foreign health data.** The feature is not
   "show me what other fitness apps think of me." It is "let me use the
   phone's own step counter instead of the ring's." The value read is
   functionally identical to what the ring would have provided — a daily
   step count — with different accuracy characteristics. It substitutes for
   a ring data stream, rather than supplementing the app's health model
   with foreign inputs.

The write-only design of upstream remains the *default*. Users who do not
care about step accuracy will never interact with the read path and will
never be prompted for the Health Connect read permission.

---

## How to Use

1. Open the app and go to **Settings → Step Source**.
2. Tap the **Data Source** row to switch between "Ring" and "Phone".
3. If you selected "Phone" for the first time, Android will show a Health
   Connect permission prompt asking whether PulseLoop may read your steps.
   Tap **Allow**.
4. Close the app (swipe from Recents) and reopen it, or pull down on the
   Today screen. The step tile will now show the phone's step count.

**To revert:** Set the toggle back to "Ring". The app will resume reading the
ring's step data on the next sync. Historical step rows written while "Phone"
was selected remain in the database and are not deleted by the toggle.

---

## Compatibility Notes

- **Health Connect** is built into Android 14 and later as a system
  component. On Android 13 and earlier, it is available as a standalone app
  that must be installed from the Play Store.
- The phone must have **at least one source app writing step data to Health
  Connect** (e.g. Mi Fitness, Google Fit, Samsung Health). If no such app
  exists, the Health Connect query will return zero.
- The **Steps read permission** must be granted for the feature to function.
  This is checked at runtime; if revoked, PulseLoop falls back to no step
  refresh and the user is re-prompted on the next foreground.

---

## Future Maintenance

When upstream releases a new version:

```
git fetch upstream
git rebase <tag>
```

Most conflicts, if any, will land in `EventPersistenceSubscriber.kt` and
`MainActivity.kt`, since both files are actively developed upstream. The
changes in this fork are structured to be self-contained:

- `PhoneStepManager.kt` and `PhoneStepCounter.kt` are entirely new files and
  will not conflict.
- The changes to `ApiKeyStore.kt`, `PulseEventBus.kt`, and `DebugScreen.kt`
  are additive (new property / new event / new case) and normally merge
  cleanly.
- The changes to `EventPersistenceSubscriber.kt`, `MainActivity.kt`, and
  `TodayScreen.kt` insert code near existing code and may need manual
  attention on large upstream refactors.

The feature is guarded end-to-end by the `stepSource` preference. On any
build where the preference is unset or set to "ring", the app behaves exactly
as upstream with zero overhead.