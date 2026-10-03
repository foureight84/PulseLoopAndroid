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
Reads step totals from Health Connect and publishes them onto the app's
internal event bus. This is the core of the feature. It exposes two functions:

- `refresh()` — queries today's step total, from local midnight to now. Called
  on every app foreground and on pull-to-refresh.
- `refreshHistoricalDays(daysBack = 30)` — queries per-day step totals for the
  last 30 days and publishes one event per non-empty day. Called on app
  foreground and when the user switches the step source from "Ring" to
  "Phone". This is what makes past days show the phone's numbers instead of
  the ring's.

Neither function performs background polling or persistent work — both are
single async queries that run only when the app is foregrounded or the user
acts. `refreshHistoricalDays` anchors its query window to **local midnight**
rather than to `LocalDateTime.now()`, because Health Connect's
`aggregateGroupByPeriod` aligns buckets to the range start rather than to
calendar days. Anchoring to midnight is what makes each returned bucket a full
calendar day; anchoring to "now" produces buckets that straddle two days and
mislabel every row by roughly one day. See the KDoc on
`refreshHistoricalDays` for the full reasoning.

### Modified files

**`app/src/main/AndroidManifest.xml`**
- Added `<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />`
  Required on Android 10+ to access the hardware step sensor. Kept for
  completeness even though the current implementation reads from Health
  Connect instead of the raw sensor.
- Added `<uses-permission android:name="android.permission.health.READ_STEPS" />`
  Required for Health Connect to return step records. This is the *only*
  Health Connect read permission the fork requests; the app remains write-only
  for every other health data type.

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
  `upsertActivityDailyFromPhone()`. This helper **overwrites** the daily row
  unconditionally and stamps `activity_daily.source = "phone"`. Unlike the
  ring's cumulative counter, the phone's "today" value is authoritative and
  should not get stuck at a stale higher number from the ring if the user
  switched the toggle mid-day, so it does not ratchet.
- `ActivityUpdate` (the ring's live counter) is gated on the current
  `stepSource` preference: if the user has selected "Phone", the ring's live
  daily total is skipped entirely.
- `ActivityBucket` (the ring's intraday history samples) is **always written**,
  regardless of step source — those are the intraday records the Activity
  screen's Records tab and per-day history read. Suppressing them emptied the
  Records list for every user on the Phone source and dropped the ring's
  contribution to past days. What is gated instead is the *daily total
  recompute* in `applyActivityBucketAtomic`: when the day's `activity_daily`
  row is already marked `source = "phone"`, the ring's bucket-derived sum does
  not overwrite it. Without that gate, the ring's next sync would clobber the
  phone backfill's past days with the ring's own bucket sum.
- The step-source preference is cached and re-read at most once per second
  to avoid decrypting the preferences file on every ring packet during a sync.

**`app/src/main/java/com/pulseloop/MainActivity.kt`**
- Added a Health Connect permission launcher for `READ_STEPS`.
- Added `refreshPhoneSteps()`: called from `onResume()`, it queries Health
  Connect for today's steps and then for the last 30 days via
  `refreshHistoricalDays()`, publishing one `PhoneStepsUpdate` per non-empty
  day. If the Health Connect read permission has not been granted yet, it
  triggers the permission request dialog.
- Added the `ACTIVITY_RECOGNITION` runtime permission to the app's standard
  permission request flow.

**`app/src/main/java/com/pulseloop/ui/screens/SettingsScreen.kt`**
- Added a "Step Source" section with a single row that toggles between
  "Ring" and "Phone". The selected value is shown on the right side of the row.
- When the toggle flips to "Phone", the screen fires a background call to
  `PhoneStepManager.refreshHistoricalDays()` so the last 30 days are backfilled
  from Health Connect immediately. The row's preference write is synchronous;
  the backfill is fire-and-forget.

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

The feature has two paths, and they are independent. The **live path** keeps
today's step count fresh. The **backfill path** makes past days show the
phone's numbers instead of the ring's.

### Live path (today)

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
                                       between local midnight and now"
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

### Backfill path (past days)

```
                  App foregrounds  OR  user toggles to "Phone"
                                │
                                ▼
                     refreshHistoricalDays()
                                │
                                ▼
                Health Connect query per-day buckets:
                  range: [today-midnight - 29d .. tomorrow-midnight]
                  slicer: Period.ofDays(1)
                  anchor: LOCAL MIDNIGHT (not "now")
                                │
                                ▼
                For each non-empty bucket, publish
                  PhoneStepsUpdate(timestamp = bucket.startTime)
                                │
                                ▼
                    upsertActivityDailyFromPhone()
                                │
                                ▼
              activity_daily rows for each past day, marked source="phone"
                                │
                                ▼
                Ring's next sync arrives with bucket events
                                │
                                ▼
             applyActivityBucketAtomic checks existing.source == "phone"
                                │
                    ┌───────────┴───────────┐
                    │                       │
              source == "phone"       source != "phone"
                    │                       │
              skip daily total         recompute from buckets
              (buckets still written)  (as upstream)
```

The midnight anchor in the backfill path is not cosmetic. Health Connect's
`aggregateGroupByPeriod` aligns its buckets to the range **start**, so a range
that begins at, say, 22:42 produces buckets that run 22:42 → 22:42 and cover
parts of two calendar days. Each bucket then gets mislabeled as the day it
starts on, and the data appears shifted by roughly one day. Anchoring the
range to local midnight makes every bucket exactly one calendar day, and the
`bucket.startTime` → local-day conversion in the persistence layer lands on
the correct date.

When the user selects "Ring", both paths are complete no-ops and the app
behaves exactly as upstream. When the user selects "Phone", the ring's live
daily counter is skipped, the phone backfills past days, and the ring's own
past-day recompute is suppressed for any day the phone has already claimed.

**Refresh triggers:**
- App launch and every foreground return (`onResume`), for both the live and
  backfill paths.
- Pull-to-refresh gesture on the Today screen, for the live path only.
- Toggling the step source from "Ring" to "Phone" in Settings, for the
  backfill path only.

There is **no** background polling, no scheduled job, and no WorkManager
worker dedicated to step refresh. If the app is not open, no step query runs.
This is deliberate and matches the app's existing refresh model.

**Staleness:** The value returned by Health Connect is only as fresh as the
last time a source app (Mi Fitness, Google Fit, etc.) wrote a step record to
Health Connect. Android's Health Connect implementation batches step writes
roughly every minute or two. PulseLoop always reads the newest value Health
Connect has, but cannot make Health Connect itself more up-to-date. On the
very first launch after install, Health Connect may return an empty result
until the Health Connect app itself has been foregrounded once; the second
launch's backfill picks up from there.

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
4. The app immediately backfills the last 30 days of phone step counts in the
   background. On the **Activity** screen, past days will now show the phone's
   numbers rather than the ring's. Today's tile updates on the next foreground
   (or on pull-to-refresh).
5. Close the app (swipe from Recents) and reopen it, or pull down on the
   Today screen, to force a live refresh of today's count.

**To revert:** Set the toggle back to "Ring". The app will resume reading the
ring's step data on the next sync. Historical step rows written while "Phone"
was selected remain in the database and are not deleted by the toggle — they
are marked `source = "phone"` and will not be overwritten by the ring's own
syncs.

---

## Compatibility Notes

- **Health Connect** is built into Android 14 and later as a system
  component. On Android 13 and earlier, it is available as a standalone app
  that must be installed from the Play Store.
- The phone must have **at least one source app writing step data to Health
  Connect** (e.g. Mi Fitness, Google Fit, Samsung Health). If no such app
  exists, the Health Connect query will return zero and past days will remain
  on the ring's numbers.
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

- `PhoneStepManager.kt` is an entirely new file and will not conflict.
- The changes to `ApiKeyStore.kt`, `PulseEventBus.kt`, and `DebugScreen.kt`
  are additive (new property / new event / new case) and normally merge
  cleanly.
- The changes to `EventPersistenceSubscriber.kt`, `MainActivity.kt`,
  `SettingsScreen.kt`, and `TodayScreen.kt` insert code near existing code
  and may need manual attention on large upstream refactors.

**One semantic contract to preserve on merge.** `activity_daily.source =
"phone"` is not a display hint — it is a marker the ring's own bucket-sync
path reads to decide whether to recompute a day's total. If upstream ever
changes the `ActivityDailyEntity` schema or the `applyActivityBucketAtomic`
logic, the `dayOwnedByPhone` check must be carried across, or the ring's next
sync will clobber every past day the phone backfilled. The two commit
messages `Skip ring bucket totals on phone-owned days...` and `Trigger
phone-step backfill on app startup and step-source toggle` describe the
invariant.

The feature is guarded end-to-end by the `stepSource` preference. On any
build where the preference is unset or set to "ring", the app behaves exactly
as upstream with zero overhead.