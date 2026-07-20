# PR #28 review findings — resume notes

Code review of `foureight84/PulseLoopAndroid#28` (branch `iOS_sync_2026-07-16`, 65 commits,
141 files, +13062/-742), the iOS→Android sync batch from 2026-07-16 through 2026-07-19.
Reviewed with 5 parallel agents (BLE/ring protocol vs decompiled vendor apps, coach
subsystem, activity/workout math, dashboard UI/UX vs iOS, data/migrations/units/sleep/
battery), cross-referenced against the iOS repo (`PulseLoop/`) and the decompiled vendor
apps (`decompiled-*/`) at repo root. Completed 2026-07-20.

Status legend: `[ ]` open · `[x]` fixed · `[~]` triaged, deferred (reason noted)

> **2026-07-20 fix pass:** all 7 first-pass blockers (#1–#5 minus none; #1–#5) and the
> second-pass Highs + mechanical Mediums landed as 8 commits on `iOS_sync_2026-07-16`
> (`c93c687`..`eb803f4`), build + 613 unit tests green. Fixed: #1, #2, #3, #4, #5, #22–#30,
> #32–#35, #37, #44; partial: #39, #46. Still open: first-pass #6–#19 and second-pass #31,
> #36, #38, #40–#43, #45, #47–#50. One deliberate iOS divergence introduced: finish-from-paused
> banks the trailing pause span (iOS counts it as active — added to upstream candidates).
>
> **2026-07-20 second pass:** a continuation review (5 more agents, every new High claim
> independently re-verified against source) found **31 additional findings (#22–#52)** in areas the
> first pass didn't cover — see "Second pass" at the bottom. Headlines: Colmi rings never emit
> `SyncProgress("done")` so half of #61c/#65f is dead for the flagship family (#22); the
> coach-notification worker leaks a self-reconnecting BLE client (#23) and can drive the ring
> concurrently with the foreground app (#24); the finished-workout dismiss worker can kill a new
> workout's notification (#25); pause accounting is corrupt and now leaks into #57a/c/g surfaces
> (#26). Also one citation correction to finding #2 (see the Second-pass preamble).

---

## TL;DR — action list, most severe first

| # | Area | Finding | Severity | Status |
|---|------|---------|----------|--------|
| 1 | Coach | Full-precision GPS sent to third-party weather API (privacy regression vs iOS) | 🔴 High | [[x] `81f92aa` |
| 2 | Ring | Workout HR self-kill fix (#57f) only half-applied — spot HR/SpO2 during a workout still kills the live stream | 🔴 High | [[x] `ef9e9b8` |
| 3 | Activity | `ActivityAggregates.recompute` nulls manually-entered distance on every recompute | 🔴 High | [[x] `2d78f2f` |
| 4 | Coach | Weather retry cooldown defeated in background/notification path | 🟠 Medium | [[x] `81f92aa` |
| 5 | Ring | SpO2 spot-poll during workout is ungated, no HR-restart | 🟠 Medium | [[x] `ef9e9b8` |
| 6 | Ring | LuckRing/TK18 first-pair byte hardcoded to `{0,0}` instead of persisted flag | 🟠 Medium | [ ] |
| 7 | Activity | Pace floor far weaker than iOS (10-16m vs 50m) — fabricates pace on jittery GPS | 🟠 Medium | [ ] |
| 8 | UI/UX | Today coach-card tap doesn't seed conversation with the summary (loses continuity) | 🟠 Medium | [ ] |
| 9 | UI/UX | Sleep carousel has no accessibility semantics (TalkBack can't page it) | 🟠 Medium | [ ] |
| 10 | Coach | OpenAI parser drops `text`-typed content parts (only accepts `output_text`) | 🟡 Low | [ ] |
| 11 | Coach | Coach turn persistence not transactional — usage-sheet undercount on crash mid-turn | 🟡 Low | [ ] |
| 12 | Coach | All 4 HTTP clients read body outside error-mapping try — truncated response leaks raw `IOException` | 🟡 Low | [ ] |
| 13 | Activity | GPS point writes are fire-and-forget; race with `finish()` recompute (self-heals) | 🟡 Low | [ ] |
| 14 | Activity | Accept/reject gate and distance total use two different earth-distance formulas (inert, <1%) | 🟡 Low | [ ] |
| 15 | Ring | jring background measurement cadence hardcoded — ignores ported #74 frequency setting | 🟡 Low | [ ] |
| 16 | UI/UX | Dashboard edit mode missing "move to top/bottom" shortcut (more taps, still reachable) | 🟡 Low | [ ] |
| 17 | UI/UX | Onboarding reduce-motion uses Android animator scale, not an app-level a11y check | 🟡 Low | [ ] |
| 18 | UI/UX | Today chat-prompt card doesn't show calibration-in-progress copy | 🟡 Low | [ ] |
| 19 | UI/UX | Battery notif toggle copy drift; no animation/haptics on card move/hide | 🟡 Low | [ ] |
| 20 | Ring | jring 0x24 combined packet drops HRV; BP gating asymmetry (pre-existing, not new) | ⚪ Info | [~] not a regression from this PR |
| 21 | Activity | No activity-type alias canonicalization (currently unreachable — schemas constrain type) | ⚪ Info | [~] defensive-only, not urgent |

Everything not listed above (Room migrations v7→v12, coach pricing table/usage-tally,
FNV-1a variety rotation, sleep-sync gate, glucose/temp conversion math, `BatteryAlertEngine`,
sleep segmentation/reconciliation, Colmi R08 catalog pattern, measurement-frequency settings
move, `CoachUsageSheet`/`CoachToolTraceDisclosure` UI, Physiology settings screen, sleep
day-key/DST handling, rollup credit/reverse on both delete paths, GPS distance-engine
internal consistency, Android 14+ permission fix, jring/YCBT/LuckRing protocol byte
layouts/CRC/opcodes, R09-bonding fix) was **verified clean** against iOS and/or the
decompiled vendor apps.

---

## 🔴 High severity

### 1. Coach weather feature sends full-precision GPS to a third-party API

**File:** `app/src/main/java/com/pulseloop/coach/context/WeatherContextService.kt:117,152-153`

Requests a fix at `Priority.PRIORITY_BALANCED_POWER_ACCURACY` (~100m) and interpolates the
**unrounded** coordinate into the Open-Meteo URL:
`"?latitude=${coordinate.first}&longitude=${coordinate.second}"`.

iOS coarsens at the request level regardless of granted precision —
`manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers`
(`PulseLoop/Coach/Context/CoachEnvironmentContextService.swift:48`) — and sends only to
Apple's first-party WeatherKit (`.swift:86`), never a third party.

This app typically already holds `ACCESS_FINE_LOCATION` for BLE ring scanning, so a paired
user who enables the weather toggle leaks near-exact home/work coordinates to
`api.open-meteo.com` — an order of magnitude more precise than iOS, to a different trust
boundary. The consent prompt only requests `ACCESS_COARSE_LOCATION`, but that's moot: the
code uses the already-granted fine fix regardless.

**Fix:** round to ~2 decimals (~1km, consistent with `WeatherContextStore.kt:38`'s existing
`isNear` 0.01 threshold) before building the URL, or switch to `PRIORITY_LOW_POWER`.

**Recommend blocking merge on this one.**

### 2. Workout HR stream self-kill fix (#57f) only half-applied

**Files:** `app/src/main/java/com/pulseloop/service/WorkoutSensorPollingService.kt:50`,
`app/src/main/java/com/pulseloop/ui/components/MeasurementModal.kt:78`,
`app/src/main/java/com/pulseloop/coach/tools/ToolImplementations.kt:640`

#57f added one guard so the background ~60s poll loop no longer calls `measureHR()`
mid-workout. But iOS's actual mechanism is `restartWorkoutHeartRateIfActive()`
(`PulseLoop/Services/RingSyncCoordinator.swift:418`), called after **every** spot
read — `measureHR` (511), `measureSpO2` (548), `measureHRV` (574) — and in the poll
service itself (204). **Android has no equivalent method** (grep for
`restartWorkoutHeartRate` in `app/src/main` returns nothing).

`measureHR()`'s `finally` unconditionally calls `engine?.stopHeartRate()`
(`RingSyncCoordinator.kt:374`), which tears down the workout's realtime HR stream. Two of
three `measureHR()` callers are unguarded: the Vitals "Measure" HR button
(`MeasurementModal.kt:78`) and the coach "hr" tool (`ToolImplementations.kt:640`).

**Failure scenario:** user in an active workout taps Measure-HR (or asks the coach for a
spot HR reading) → workout HR graph flatlines for the rest of the session with no recovery
— the exact bug class #57f set out to close, left open on every non-poll path.

**Fix:** port `restartWorkoutHeartRateIfActive()` and call it from every spot-measurement
`finally` block (HR, SpO2, HRV), same as iOS.

### 3. `ActivityAggregates.recompute` silently wipes manually-entered distance

**File:** `app/src/main/java/com/pulseloop/service/ActivityAggregates.kt:28-34`

```kotlin
val distanceMeters = if (session.useGps) {
    RouteDistanceEngine.distanceMeters(gpsPoints, ActivityTrackingProfile.profile(session.type))
} else null
```

iOS's equivalent (`PulseLoop/Services/... PulseServices.swift:838`) is
`gpsDistance(session, context) ?? session.distanceMeters` — `gpsDistance` returns `nil`
whenever there are fewer than 2 accepted GPS points **regardless of `useGps`**, and falls
back to whatever distance is already on the session. The Android port dropped that
fallback, so `recompute()` nulls `distanceMeters` every time it runs on a non-GPS session,
no matter what was there before. 100% reproducible, not timing-dependent.

Reachable via:
- `ManualActivityService.create()` (`:43-54`) — builds the session with the caller-supplied
  distance (e.g. from the coach's `create_activity_session_from_description` tool), then
  immediately calls `recompute()`, nulling it before the first save.
- `PendingActionExecutor.applyUpdates` (`:86-108`) and `WorkoutSummaryScreen`'s
  `EditWorkoutSheet.onSave` (`:159-171`) — editing type/time of any indoor/manual workout
  wipes its distance unless the same edit also restates `distance_km`.
- `EventPersistenceSubscriber`'s post-sync reconcile (`:279-281`) — can zero a fresh
  manual/coach-logged distance shortly after creation.

**User-visible failure:** "log a 5km walk I did yesterday" via the coach → distance chip is
blank in Activity history and stays blank. Editing the time window of any indoor workout
that had a distance makes the number vanish. Note: `ActivityRollup.credit/reverse` already
correctly gates on `useGps`, so the *daily total* isn't corrupted — only the individual
session's own stored distance is lost.

**Fix:** restore the `?? session.distanceMeters` fallback — only null distance when
`useGps` is true *and* fewer than 2 accepted points exist.

---

## 🟠 Medium severity

### 4. Weather retry cooldown defeated in background/notification path

**Files:** `WeatherContextService.kt:48` (state), `notifications/CoachNotifications.kt:203`
(call site)

`lastWeatherFailureAtMs` / `WEATHER_RETRY_COOLDOWN_MS` (5 min) is an **instance field**, but
the notification worker builds a fresh `WeatherContextService(applicationContext)` every
call — cooldown state starts null every time and never fires in the background path. iOS
keeps this on a process-wide singleton (`CoachEnvironmentContextService.shared`) used by
both foreground and background paths; the offending comment (`.swift:35-38`) names the
background-refresh path as the specific reason the cooldown exists. Bounded frequency, so
medium not high. Foreground path (`PulseLoopApp.kt:120`, stable instance) is fine.

**Fix:** hoist the failure timestamp to a companion/singleton, or persist it in
`WeatherContextStore`.

### 5. SpO2 spot-poll during workout is ungated, no HR-restart

**File:** `WorkoutSensorPollingService.kt:55-58`

The SpO2 poll in the same loop has no `workoutHRActive` gate (HR's does), and
`measureSpO2()` has no restart call. iOS restarts HR after `measureSpO2`
(`RingSyncCoordinator.swift:548`) because the ring's realtime SpO2 mode preempts HR on
hardware. `stopSpO2()` on Android leaves `realtimeHRActive=true` in software state, but the
ring itself may already have switched modes and won't resume HR without a re-enable. Fires
every ~5 min during a workout — should be checked against the decompiled Colmi app if not
already covered by finding #2's fix.

### 6. LuckRing/TK18 first-pair byte hardcoded

**File:** `app/src/main/java/com/pulseloop/ring/LuckRingEncoder.kt:113`

`PAIR_FINISH (120) = {0, 0}` is hardcoded; iOS sends `{firstPair ? 1 : 0, 0}` and latches a
`luckring.pairFinished` flag so the leading `1` goes out exactly once
(`LuckRingEncoder.swift:92`, `LuckRingSyncEngine.swift:51-53`). The Coolwear vendor app
(`decompiled-coolring`, `K6SendDataManager.sendAynInfoDetail()`) does the same and names the
byte `appPairFinish`, using it for persisted bind state — contradicting the Android code
comment (`LuckRingEncoder.kt:90-97`) that calls the leading `1` "cosmetic."

**Risk:** a brand-new, never-paired TK18 may not finish binding / arm background logging on
first connect. **Fix:** restore a persisted first-pair flag, mirroring iOS.

### 7. Pace floor far weaker than iOS

**File:** `app/src/main/java/com/pulseloop/ui/components/ActivityMeta.kt:79-86`

```kotlin
if (unitsCovered <= 0.01) return null   // ≈10m metric, ≈16m imperial
```

iOS gates on an absolute, unit-independent `distanceMeters >= 50`
(`PulseLoop/DesignSystem/Components.swift:615-616`). The commit that introduced this
(rounding-before-split, per iOS #43) silently also weakened this floor — an unreviewed side
effect not mentioned in the commit message.

**Failure scenario:** during the first ~10-45m of a GPS-tracked run (the noisiest phase of a
GPS fix), Android computes and displays a real pace number where iOS would show "—". Reaches
both `RecordScreen.kt:42` (live tile) and `WorkoutSummaryScreen.kt:414` (finished-workout
summary) — a workout with only 10-49m total distance gets a fabricated pace baked into
permanent history.

**Fix:** `if (distanceMeters < 50.0) return null`, computed before unit conversion.

### 8. Today coach-card tap loses conversational continuity

**File:** `app/src/main/java/com/pulseloop/ui/screens/TodayScreen.kt:151-162`

Both the coach-summary card and the "no summary yet" chat-prompt card navigate to the bare
`"coach"` route (`PulseLoopApp.kt:502-509`, no args, no seeding). iOS's summary branch calls
`summaryService.openInChat(coachSummary)` (`TodayView.swift:66-76`) →
`CoachSummaryService.swift:171-187` creates/reuses a conversation **seeded with the summary
as the first assistant message** before deep-linking in; the prompt branch calls a distinct
`openRoot()`. Android has no seeding concept anywhere in `CoachSummaryService.kt`.

**Failure scenario:** user reads "Building momentum — steps up 24% today," taps expecting to
discuss it, lands on whatever conversation the Coach tab last had open — nothing to follow up
on. Defeats the stated purpose of making the card tappable.

**Fix:** port `openInChat` — create/reuse a conversation, inject the summary as the first
message, navigate with a conversation id argument.

### 9. Sleep carousel has no accessibility semantics

**File:** `app/src/main/java/com/pulseloop/ui/screens/SleepScreen.kt` (`SleepCarousel`)

Plain `HorizontalPager` + dot `Row`, zero `Modifier.semantics`/content-description/custom
actions. iOS explicitly solved this (`SleepView.swift:190-219`):
`.accessibilityElement(children: .contain)`, `.accessibilityValue("2 of 3, 1:20 PM to 2:05
PM")`, `.accessibilityAdjustableAction` (documented reason: "VoiceOver can't reach
off-screen carousel pages via horizontal swipe"), dot row `.accessibilityHidden(true)`.
Sighted-user parity (dot highlighting, "N of M" header) is otherwise faithful.

**Fix:** add `Modifier.semantics { ... }` with a custom adjustable/paging action on the
pager, hide the dot row from the accessibility tree.

---

## 🟡 Low / cosmetic

10. **OpenAI parser drops `text`-typed content parts** — `coach/openai/OpenAIResponsesClient.kt:256`
    only accepts `output_text`; iOS accepts `output_text` OR `text`
    (`ResponsesTypes.swift:87`). Degrades to empty output / fallback JSON-repair loop if a
    response uses the `text` variant. Not covered by the new `OpenAIResponseParseTest`.
11. **Coach turn persistence isn't transactional** — `ui/viewmodels/ViewModels.kt:821-845`
    (message insert → tool-call inserts → conversation upsert) has no
    `db.withTransaction {}`; iOS commits atomically. A crash mid-turn undercounts the usage
    sheet (no orphaned rows, no FK constraints — cosmetic only).
12. **HTTP body read outside error-mapping try** — `coach/openai/OpenAIResponsesClient.kt:60-67`
    (shared transport, affects all 4 clients) — truncated/premature-EOF response throws a
    raw `IOException` instead of classifying as `ResponsesError.Transport` → "Network".
    Orchestrator catches broadly so no crash, just wrong error label.
13. **GPS point writes are fire-and-forget** — `GpsRouteRecorder.kt:81-109` inserts via
    detached `scope.launch {}`; `finish()` can call `recompute()` before the last fix
    commits, causing distance to visibly tick up seconds/minutes after finishing (self-heals
    via the later backfill reconcile).
14. **Two earth-distance formulas** — `GpsRouteRecorder.rejectionReason()` uses
    `Location.distanceTo()` (Vincenty-ish); `RouteDistanceEngine.haversineMeters()` uses
    spherical haversine (r=6,371,000). <1% divergence, inert, but breaks the file's own
    "one source of truth" doc comment.
15. **jring background measurement cadence hardcoded** — `JringDriver.kt:96` always sends
    `cadenceMinutes=30`; iOS derives enable+cadence from `MeasurementSettings`. The #74-ported
    "Measurement Frequency" setting never reaches jring's background logging.
16. **Dashboard edit mode missing "move to top/bottom"** — `ui/dashboard/DashboardEdit.kt:128-172`
    only has up/down step buttons; iOS's VoiceOver fallback
    (`CardReorder.swift:401-407`) also has a one-tap "Move to top". Every ordering is still
    reachable on Android, just up to n-1 taps for a long move.
17. **Onboarding reduce-motion mechanism differs** — `ui/screens/OnboardingScreen.kt:479-487`
    relies on Android's global animator-duration-scale; iOS checks
    `@Environment(\.accessibilityReduceMotion)` directly (`OnboardingFlowView.swift:411,446-451,538`).
    Different OS toggles — a user with iOS-style reduce-motion prefs but untouched Android
    animator scale gets the full spring animation. Also: iOS staggers 3 springs with opacity
    fades, Android's `SuccessMedallion` (`:566-586`) is one spring, no fade — reads as one
    movement instead of a layered reveal. Copy itself verified verbatim-identical.
18. **Today chat-prompt card missing calibration copy** — `TodayScreen.kt:157-161` always
    shows "Want a recap?" copy; iOS conditions on `summary.calibration.isCalibrating` to
    show "Baseline in progress" copy during onboarding. Android's `TodayViewModel` has no
    calibration-state concept (grep confirmed zero hits). Branch *selection* logic itself
    (coach-on/off × summary present/absent) matches iOS exactly — this is copy-only.
19. **Minor cosmetic drift** — battery notif toggle label is Title Case vs iOS sentence
    case, extra footer clause not in iOS; dashboard card move/hide has no animation spec or
    haptics (iOS has springs + `ReorderHaptics.selection` throughout) — unconfirmed at
    render level, likely but unverified regression in perceived polish.

## ⚪ Informational (not regressions from this PR)

20. jring 0x24 combined packet drops HRV (function untouched by this PR, likely intentional
    per `JringCoordinator.capabilities`); BP row gating asymmetry (Android emits lone
    SYSTOLIC when diastolic==0, iOS requires both) — pre-existing, low impact.
21. No activity-type alias canonicalization (`"outdoor_run"` etc.) in
    `ActivityMeta`/`WorkoutMetricsEngine`/`ActivityTrackingProfile` — iOS has one. Currently
    unreachable since both coach tool schemas constrain `type` to the canonical enum, but a
    dangling `activityLabels` map entry in `PendingActionExecutor.kt` suggests aliases were
    expected. Worth defensive canonicalization if any future path loosens the schema.

## ✅ Verified clean (no action needed)

Room migrations v7→v12 (read end-to-end against every entity — no column/type/nullability
mismatch, no version gap); coach pricing table (all 20 models, byte-for-byte vs
`CoachModelPricing.swift`); usage-tally summation; FNV-1a variety-hint rotation (64-bit,
correct unsigned arithmetic); `CoachSleepSyncGate` (branch-for-branch match, cannot get
permanently stuck); `WeatherContextService.snapshot()` never-throws guarantee (one minor gap:
`requestOneShotLocation` only catches `SecurityException`, iOS-equivalent catches broader);
glucose mg/dL↔mmol/L conversion (`18.0182` factor matches iOS exactly, calibration offset
applied before conversion, no double-conversion risk); temperature °C↔°F conversion;
`BatteryAlertEngine` (hysteresis, day-boundary reset, threshold boundaries all tested and
correct); `SleepSegmentation.segment()` / `reconcileWakingDay()` (faithful port including the
shared greedy-matching limitation); Colmi R08 catalog pattern (no collision with 13 other
patterns); Measurement Frequency settings move (no storage-key change, no reset risk);
`CoachUsageSheet` / `CoachToolTraceDisclosure` UI (exact match); Physiology settings screen
(same 5 inputs, same validation, same threshold-engine math); sleep day-key UTC↔local
conversion (explicit DST awareness); sleep `lastNight` anchoring (fully decoupled from
carousel state, matching iOS's intent); `ActivityRollup.credit/reverse` on both UI-delete and
coach-tool-delete paths (Android is actually *more* correct than iOS here — iOS's own
`PendingActionExecutor` delete case doesn't reverse the rollup, a latent iOS bug not present
on Android); `ActivityAggregates.applyEdit` reverse-then-recompute-then-credit sequencing;
`RouteDistanceEngine`/`Accumulator` internal consistency; Android 14+
`ACTIVITY_RECOGNITION` permission gating (no bypass path); jring/YCBT/LuckRing protocol byte
layouts, CRC, opcodes (cross-checked against decompiled vendor SDKs — QRing
`com.app.cq.ring`, YCBT `com.zhuoting.healthyucheng`/`com.yucheng.ycbtsdk`, Coolwear K6
`decompiled-coolring`); the "bond every `supportBlePair` ring" fix (#29, matches QRing vendor
exactly, jring correctly excluded); diagnostics one-packet-per-notification fix;
`DeviceEntity.lastSyncAt` stamped-on-CONNECT fix.

---

## Notes for resuming

- Repo state at review time: `android` repo on branch `iOS_sync_2026-07-16`, HEAD
  `2e8412c`, exactly matching PR #28. Diff base `main` @ `4ca52fc`.
- iOS cross-reference source: root repo `PulseLoop/` (Swift), main @ `4dae095` at review
  time.
- Decompiled vendor apps used: `decompiled-qring-official` (Colmi, `com.app.cq.ring`),
  `decompiled-smarthealth` (YCBT, `com.zhuoting.healthyucheng`), `decompiled-coolring`
  (LuckRing/K6, `com.kewo.coolring`), `decompiled-jring-offical` (jring, dex-only, no
  extracted Java sources — corroborated via dex method-name strings only).
- No GitHub PR review comments have been posted yet — this file is the only record so far.
  Ask before posting to the PR (visible/hard-to-reverse action).

---

# Second pass (2026-07-20, continuation)

Scope: areas the first pass's 5 agents did not cover, identified by mapping all 45 code commits
and 141 changed files against the first pass's coverage. Five new agents reviewed: (a) the #66
robust-measurement port (`HRSampleWindow`, never examined), (b) #57g finished-workout
notification + `MainActivity`/`DebugScreen`/`MetricsService`/`Screens` diffs, (c) #61c
`ensureFreshData` worker + #61d history grouping + #54 MiniMax client, (d) BLE spot-vs-continuous
interplay against the decompiled vendor apps (QRing + SmartHealth) to validate the finding-#2/#5
fix shape per standing instructions, (e) a dedicated workout-screens UI/UX parity pass
(Record/Summary/Edit/Log-Past/Activity-entry) — the first pass verified workout *math* but never
the screens. **Every High-severity claim below was independently re-verified against source
(including `git show main:` provenance checks) before inclusion.**

**Preamble — citation correction to finding #2.** `ui/components/MeasurementModal.kt` does not
exist; the file is `ui/screens/MeasurementModal.kt` and it is **dead code** (zero call sites
tree-wide). The live spot-measurement caller is the Vitals "Measure" button
(`ui/screens/Screens.kt:260` → `RingSyncCoordinator.measureSpot()` at
`RingSyncCoordinator.kt:336-341`). Finding #2's substance is unchanged — `measureSpot()` calls
`measureHR()`/`measureSpO2()` unguarded, and the missing `restartWorkoutHeartRateIfActive()` is
the bug — but the fix's call sites are `measureHR`/`measureSpO2`/`measureCombined` in
`RingSyncCoordinator.kt`, reached via `measureSpot()`, not the dead modal.

## TL;DR — second-pass action list, most severe first

| # | Area | Finding | Severity | Status |
|---|------|---------|----------|--------|
| 22 | Ring | `ColmiSyncEngine.finishSync()` never publishes `SyncProgress("done")` — `lastFullSyncAt` never stamps for Colmi; #61c skip-gate/done-wait/staleness warning, #65f sleep gate, and summary-sync recheck all dead for the flagship family | 🔴 High | [[x] `c93c687` |
| 23 | BLE | `CoachNotificationWorker.ensureFreshData` leaks its `RingBLEClient` — watchdog reconnects the ring ~15s after the worker exits (and leaks instantly on the permission early-return) | 🔴 High | [[x] `09e6ac7` |
| 24 | BLE | `ensureFreshData` has no foreground coordination — second client drives the same ring: CONNECTED event wipes sleep tables under the user, duplicated decodes, interleaved sync commands | 🔴 High | [[x] `09e6ac7` |
| 25 | Workout | Stale `WorkoutCompleteDismissWorker` cancels the NEW workout's ongoing notification (shared ID 1001, no tag, nothing cancels on start) | 🔴 High | [[x] `dc1513b` |
| 26 | Workout | Pause accounting corrupts `totalPauseSeconds` (pre-existing on `main`, amplified: #57a/c/g now consume it — wrong duration/calories, possible negative duration with 2+ pauses) | 🔴 High | [[x] `dc1513b` |
| 27 | Workout | Notification Pause/Resume/Stop actions never reach `LiveWorkoutManager` (pre-existing) — Stop hides the card while recording continues with no UI path back | 🔴 High | [[x] `dc1513b` |
| 28 | Ring | SpO2 spot-measure window 40s vs iOS 60s (iOS `c8969a4` measured 40s as a coin toss) | 🟠 Medium | [[x] `ef9e9b8` |
| 29 | Ring | `SpotMeasurementGate` (refusal abort) not ported — `MeasurementRejected` decoded then dropped; Android spins the full window; two comments falsely claim the behavior exists | 🟠 Medium | [[x] `ef9e9b8` |
| 30 | UI/UX | `measureSpot()` discards leg outcomes — measurement failure is silent, no error/retry UX (iOS has error stage + per-kind copy + Try again) | 🟠 Medium | [[x] `eb803f4` |
| 31 | UI/UX | #66 countdown redesign absent: shipped UX is a 75s determinate wall-clock countdown promising an SpO2 finish time (what #66 designed away); dead modal; no cancel; live-reading signal unwired | 🟠 Medium | [ ] |
| 32 | Coach | `ensureFreshData` missing iOS's `hasRecentData` (3h live-measurement) fallback — forces connect + full sync every check-in for streaming devices with seconds-fresh data | 🟠 Medium | [[x] `09e6ac7` |
| 33 | Workout | Workout notification elapsed (tick + finished card) includes paused time; iOS and Android's own summary exclude it | 🟠 Medium | [[x] `dc1513b` |
| 34 | Workout | Workout notifications hardcode metric (`%.1f km`); iOS threads `useImperial` everywhere with `%.2f` and sub-50m "—" | 🟠 Medium | [[x] `dc1513b` |
| 35 | Workout | No content intent on workout notifications — tap does nothing, `autoCancel` neutered; iOS deep-links to summary/live | 🟠 Medium | [[x] `dc1513b` |
| 36 | UI/UX | No way back to an active recording + no orphan-cancel on `start()` + no stale-session recovery card (iOS has all three) | 🟠 Medium | [ ] |
| 37 | Activity | `"sport"` missing from `gpsCapable` (iOS: true); `ActivityMetricSet` not ported — cycle shows pace not speed, sport gets pace/splits instead of distance-only | 🟠 Medium | [[x] `f99c3cc` |
| 38 | UI/UX | Post-finish flow gaps: no effort/notes capture, no WORKOUT SAVED badge, no "Updating from ring…"; coach-writable notes/effort columns display nowhere | 🟠 Medium | [ ] |
| 39 | UI/UX | Live-screen polish cluster: raw type key in header/notification titles; no status pills/live map/split strip/REC/freshness subtitles; grid doesn't adapt to indoor; dead calories tile; `--` vs `—` | 🟡 Low | [[~] partially fixed `f99c3cc` — label/indoor-grid/dead-tile done; status pills, live map, split strip, REC, freshness subtitles open |
| 40 | UI/UX | Record picker: GPS toggle hidden vs disabled-with-helper; not seeded from persisted default; no start haptic | 🟡 Low | [ ] |
| 41 | UI/UX | No finish/discard confirmation recap sheets; `LiveWorkoutManager.cancel()` has no caller | 🟡 Low | [ ] |
| 42 | UI/UX | Summary missing HR chart/zone card/SpO2 chart/elevation/recording-quality sections — iOS features predate sync range → backlog, not regression; ported sections verified faithful | 🟡 Low | [~] backlog |
| 43 | UI/UX | Summary gating keyed to `useGps` alone (edited-type edge cases diverge); missing "GPS route unavailable" placeholder | 🟡 Low | [ ] |
| 44 | UI/UX | Edit sheet: future times selectable (iOS picker clamps); `is24Hour` forced off; footnote understates real behavior | 🟡 Low | [[x] `f99c3cc` |
| 45 | UI/UX | Delete confirmation copy drift + missing recap stats | 🟡 Low | [ ] |
| 46 | UI/UX | History sheet: 200-row DAO cap (iOS unbounded); today/zone remembered across midnight; locale-sensitive `uppercase()` | 🟡 Low | [[~] partially fixed `f99c3cc` — locale-safe date headers done; 200-row cap and midnight-stale remember open |
| 47 | Coach | Staleness warning semantics diverge (iOS `DataQualityAnalyzer`: `lastSyncAt` + dynamic hours + never-synced branch); unreachable for Colmi until #22 fixed | 🟡 Low | [ ] |
| 48 | Coach | No empty-store guard — never-synced fresh install gets a content-free AI check-in (iOS skips `.skippedNoData`) | 🟡 Low | [ ] |
| 49 | UI/UX | Activity entry: ACTIVITY_RECOGNITION denial silent dead-end; Daily Distance 1dp vs 2dp; goal editor missing Distance/Calories fields; no pull-to-refresh; no trends tap-through | 🟡 Low | [ ] |
| 50 | Activity | Label/helper copy drift ("Walking" vs "Walk" etc.); no legacy-alias table (extends finding #21) | 🟡 Low | [ ] |
| 51 | Coach | MiniMax client: near-perfect port — Info only (transport has no overall call timeout; per-provider model field is Android-more-correct) | ⚪ Info | [~] no action |
| 52 | Ring | Colmi spot-SpO2 stop byte sends 0 instead of echoing the final reading (vendor fidelity nicety, optional) | ⚪ Info | [~] optional |

---

## 🔴 High severity

### 22. Colmi `finishSync()` never publishes `SyncProgress("done")` — `lastFullSyncAt` never stamps for Colmi

**Files:** `app/src/main/java/com/pulseloop/ring/ColmiSyncEngine.kt:525-529`,
`app/src/main/java/com/pulseloop/service/EventPersistenceSubscriber.kt:221-224`

```kotlin
private fun finishSync() {
    stage = Stage.DONE
    watchdogJob?.cancel()
    watchdogJob = null
}
```

iOS's same function ends with `Task { await PulseEventBus.shared.publish(.syncProgress(stage:
"done")) }` (`PulseLoop/RingProtocol/ColmiSyncEngine.swift:325-331`). On Android the only "done"
producers are `RingEventBridge.kt:66-67` (from `RingDecodedEvent.HistorySyncFinished`, produced
only by the **jring** decoder `RingDecoder.kt:171` and `YCBTHistoryTransfer.kt:97`) and
`LuckRingHistorySync.kt:104`. Nothing in the Colmi path emits it (tree-wide grep confirmed;
`ColmiDecoder` has no terminal-history event).

`EventPersistenceSubscriber.kt:221-224` is the **single writer** of `DeviceEntity.lastFullSyncAt`,
keyed on that event — so for Colmi rings (the flagship family) the stamp stays null forever and
every consumer silently degrades:

- `CoachNotifications.kt:244` — `<10 min` skip gate is dead: every check-in connects and syncs.
- `CoachNotifications.kt:255-256` — the done-wait always burns the full 15s timeout.
- `NotificationContextBuilder.kt:76-77` — the ">12h stale" warning never fires.
- `CoachSleepSyncGate.kt:22` — `lastFullSyncAt == null → true`, so the #65f morning
  "sleep not synced yet" gate never blocks; the +45min sleep-retry path
  (`CoachNotifications.kt:186-190`) never triggers.
- `CoachSummaryCoordinator.kt:64` — the completed-sync re-check never fires.
- `EventPersistenceSubscriber.reconcileRecentlyFinishedWorkouts` (#57e) — triggered off the same
  event, so the post-workout vitals reconcile never runs for Colmi either. **This silently
  dead-letters #57e for the flagship family**, worse than the first pass realized (it verified
  the reconcile logic but not its trigger).

**Fix:** publish `PulseEvent.SyncProgress("done")` from `ColmiSyncEngine.finishSync()`, mirroring
iOS, and audit each family's terminal path so every sync engine emits it. Recommend blocking
merge on this — it undercuts three shipped features of this PR at once.

### 23. `ensureFreshData` leaks its `RingBLEClient` — the watchdog re-attaches the ring after the worker exits

**Files:** `app/src/main/java/com/pulseloop/notifications/CoachNotifications.kt:247-268`,
`app/src/main/java/com/pulseloop/ring/RingBLEClient.kt:199,361-419,465,1376-1387`

The worker builds a private `RingBLEClient` and tears it down with `disconnect()` in a `finally`.
But `RingBLEClient.init` starts a 15s connection watchdog on an owned
`CoroutineScope(Dispatchers.Main + SupervisorJob())`; only `destroy()` cancels the watchdog and
the scope. `disconnect()` touches neither. Post-disconnect the state is DISCONNECTED, so the next
watchdog tick runs `connectLastKnown()` (`RingBLEClient.kt:417-419`) — gated only by
`reconnectAttempts >= 10` and the **user**-disconnect flag, neither of which applies — re-attaching
the ring ~15s after the worker finished, re-firing `onConnected` → another full `runStartup()`
sync, then holding the ring with no UI, rooted forever by its own scope. Worse, the permission
check sits *after* construction (`CoachNotifications.kt:247-248`): `if
(!bleClient.hasPermissions()) return` leaks a constructed client whose watchdog immediately spins
`connectLastKnown()` into permission-less failures.

**Fix:** `finally { bleClient.destroy() }` (scope death tears the GATT down synchronously), and
move construction below the permission check. Note `RingSyncWorker.kt` has the same
disconnect-without-`destroy()` pattern — pre-existing, copied by this port; fix both.

### 24. `ensureFreshData` has no coordination with the foreground app's own client

**Files:** `CoachNotifications.kt:243-264`, `app/src/main/java/com/pulseloop/ui/PulseLoopApp.kt:66`,
`app/src/main/java/com/pulseloop/service/EventPersistenceSubscriber.kt:94-95`

iOS never creates a second client: the scheduler injects the app's shared coordinator
(`PulseLoop/PulseLoopApp.swift:117-119`) and `ensureFreshData` branches — `isSyncInFlight →
await`, `isRingConnected && !fresh → beginSync + await`, else `connectAndSync`
(`CoachNotificationService.swift:250-266`). The Android worker unconditionally creates a fresh
client and `connectLastKnown()` whenever the stamp is stale, ignoring the foreground client held
at `PulseLoopApp.kt:66`. If the app is open and connected but the last *completed* sync is >10 min
old, two clients drive the same peripheral: the worker's CONNECTED publish makes
`EventPersistenceSubscriber.kt:94-95` wipe `sleepStageBlockDao`/`sleepSessionDao` (the Sleep
screen's data vanishes under the user and rebuilds); both clients enable the same CCCDs and decode
every notification into the shared bus; two sync engines interleave history commands on one link;
and the worker's disconnect races the foreground link's lifecycle on stacks where one client's
disconnect drops the shared ACL.

**Fix:** mirror iOS's three-way branch — when the persisted device state is CONNECTED (or a sync
is in flight), just await the bus "done" (≤15s) without connecting; only create the private
client when no live client exists.

### 25. Stale `WorkoutCompleteDismissWorker` cancels the NEW workout's ongoing notification

**File:** `app/src/main/java/com/pulseloop/service/WorkoutForegroundService.kt:25-26,52-53,84,90-97,116,188-191`

The dismiss worker and the live tracker share `NOTIFICATION_ID = 1001` with no tag and no
staleness check; the only scheduling is `enqueueUniqueWork(..., REPLACE, ...)`; a tree-wide grep
finds no `cancelUniqueWork` anywhere — `LiveWorkoutManager.start()` never touches WorkManager.
Sequence: finish workout A at t=0 (worker fires t=10:00), start B at t=2:00 — B re-posts ID 1001
as the ongoing card; at t=10:00 the stale worker cancels **B's** card. While B is recording it
recovers at the next 5s tick; while B is **paused** there is no tick and no recovery — the
tracking card is gone until resume. iOS has no equivalent hazard (dismissal is per-Activity,
system-managed).

**Fix:** (a) `WorkManager.cancelUniqueWork(DISMISS_WORK_NAME)` in `LiveWorkoutManager.start()`;
(b) belt-and-braces — post the summary under a distinct tag and have the worker cancel that tag.

### 26. Pause accounting corrupts `totalPauseSeconds` (pre-existing, amplified by this PR)

**File:** `app/src/main/java/com/pulseloop/service/LiveWorkoutManager.kt:66-87,120-121`

```kotlin
// pause():
totalPauseSeconds = session.totalPauseSeconds + ((now - session.startedAt) / 1000.0)
// resume(): adds nothing
// tick: val elapsed = ((now - session.startedAt) / 1000).toInt()   // no pause subtraction
```

`pause()` adds the **entire elapsed-since-start** to the pause total instead of the pause span.
iOS records a `paused` event and adds `now − lastPause.timestamp` at resume
(`PulseLoop/Services/PulseServices.swift:1056-1073`); every iOS display subtracts pauses.
Identical code exists on `main` (`git show main:` confirmed) — pre-existing — but this PR built
new consumers on top of it: `ActivityAggregates.recompute` subtracts `totalPauseSeconds` for
duration (so #57a finish aggregates and #57c edits compute wrong duration → wrong calories), the
#57g finished card's elapsed comes from the same math (finding #33), and
`WorkoutSummaryScreen.kt:103`'s `durationSec` under-counts. With two pauses the inflated total
can exceed wall time — no clamp in the screen or in `ActivityMeta.duration` (iOS clamps at 0) —
rendering a negative hero duration. **Fix:** port the iOS event-based scheme (store `pausedAt` on
pause, add `now − pausedAt` on resume), subtract in the tick, clamp at 0.

### 27. Workout notification Pause/Resume/Stop actions never reach `LiveWorkoutManager` (pre-existing)

**Files:** `WorkoutForegroundService.kt:44-48` (PR branch), `git show main:` lines 37-39

`ACTION_PAUSE`/`ACTION_RESUME` only mutate the service's local `status` string and redraw the
card — recording, polling, and the tick all continue, and the next `refreshNotification()`
overwrites the fake status. `ACTION_STOP` calls `stopSelf()`: the card vanishes while the workout
silently keeps recording with no UI affordance to return (compounded by finding #36). iOS routes
its Live Activity buttons through the App Group into real pause/resume/finish
(`WorkoutActivityAttributes.swift:104-148` → `LiveWorkoutManager.swift:415-431`). Pre-existing on
`main`, but on a surface this PR rebuilt (#57g) — the Stop case is a genuine data-integrity trap.
**Fix:** route actions to the manager (shared command store/broadcast, mirroring the App Group
channel), labels matching iOS's pause/resume/finish set.

---

## 🟠 Medium severity

### 28. SpO2 spot-measure window is 40s; iOS raised it to 60s because 40s was a coin toss

**Files:** `RingSyncCoordinator.kt:87` vs `PulseLoop/Services/RingSyncCoordinator.swift:265-268`

`SPO2_MEASURE_SECONDS = 40` vs iOS `spo2MeasureSeconds: UInt64 = 60`, raised in `c8969a4` with
the measured rationale: "The R99's successful SpO₂ sweep took 38s… an earlier attempt ran past
41s with no result. At 40s the outcome was a coin toss: the user watched the ring's red LED work
and got an error anyway." Android kept exactly the pre-raise value. Fix: `= 60` (and re-derive
the `SPOT_MEASURE_SECONDS` budget comment — see finding #31's stale-constant note).

### 29. Measurement-refusal abort not ported; decoded event dropped; two comments claim it exists

**Files:** `app/src/main/java/com/pulseloop/ring/RingEventBridge.kt:109`,
`app/src/main/java/com/pulseloop/ring/YCBTDecoder.kt:60`, `RingSyncCoordinator.kt:362-363`

iOS arms a per-measurement token and fails fast on refusal
(`RingSyncCoordinator.swift:488,499,507` — `spot.begin(mode:)`, `spot.isRejected(token)`,
`spot.end(token)`; from `c8969a4`, in the #82 sync scope). Android's decoder produces
`RingDecodedEvent.MeasurementRejected` (`YCBTDecoder.kt:60`) but `RingEventBridge.kt:109` maps it
to `emptyList()` with the comment "RingSyncCoordinator reads this off the raw-packet feed to
abort a spot measurement" — no raw-packet feed exists, and `RingSyncCoordinator.kt:362`'s loop
comment ("or refused the measurement outright") claims a check that isn't there. On a refusing
ring (the documented R99 HRV case; any HR/SpO2 refusal) Android spins the full 30s/40s window
where iOS fails in one round-trip. **Fix:** port `SpotMeasurementGate` (token-keyed per-mode)
plus a coordinator handler for `MeasurementRejected`, or correct the comments.

### 30. `measureSpot()` discards leg outcomes — measurement failure is silent

**Files:** `RingSyncCoordinator.kt:336-341`, `app/src/main/java/com/pulseloop/ui/screens/Screens.kt:251-275`

`measureSpot()` returns `Unit`; both legs' `Int?` results and `FAILED` states are dropped. The
button just re-enables and the "Measuring…" hint disappears — no error, no retry. iOS drives the
result to an explicit outcome with per-kind failure copy ("Couldn't get a steady heart-rate
reading. Keep the ring snug and your hand still, then try again.",
`MeasurementKindPresentation.swift:71-73`) and a Try again button (`MeasurementModal.swift:294`).
The honest-refusal gate (#66's core) landed; the honest-*reporting* half didn't. **Fix:** return
collectable per-leg outcomes and surface failure + retry.

### 31. The #66 countdown-redesign shipped as a wall-clock progress bar that promises an SpO2 finish time

**Files:** `ui/screens/Screens.kt:251-281`, dead `ui/screens/MeasurementModal.kt`,
`RingSyncCoordinator.kt:70,88-91`

iOS's rule (#66): determinate countdown **only** for HR (fixed 30s window, read from the
coordinator), indeterminate sweep + count-UP for everything else — "an honest 'still working',
not a promise we can't keep" (`MeasurementRingView.swift:93-102,146`). Android ships a 1s
wall-clock ticker counting down `SPOT_MEASURE_SECONDS` (75s) with a determinate
`LinearProgressIndicator` covering the unpredictable SpO2 leg — the exact dishonesty #66 designed
away. Also dropped along with the deferred chrome: cancel affordance (iOS Cancel tears the
measurement down; Android's button just disables), the live-bpm reading (`measurementReceivedReading`
exists at `RingSyncCoordinator.kt:70` and nothing reads it — dead port), per-kind working copy,
disconnected-vs-failure error split, haptics, and VoiceOver announcements. And stale constants:
`HR_SETTLE_SECONDS = 4` and the "each leg returns early" doc are false post-#66 — the HR leg now
samples the full 30s by design. **Fix:** finish and wire the sheet (or delete the dead file and
track it as its own ledger item); correct the constants/comments.

### 32. `ensureFreshData` missing iOS's `hasRecentData` fallback

**Files:** `CoachNotifications.kt:243-245` vs `CoachNotificationService.swift:204-213,261-264`

iOS only reconnects when the store is genuinely stale: `hasRecentData` (3h) accepts a fresh full
sync **or a fresh live measurement** — explicitly "covers jring, which streams samples
continuously rather than running a paged history sync". Android's gate is `lastFullSyncAt`-only,
so streaming devices get a forced connect + full `runStartup()` at every check-in even when live
data landed seconds ago — battery and BLE churn iOS avoids. (For Colmi this is moot until #22 is
fixed — every check-in always connects.) **Fix:** add the 3h full-sync-or-live-measurement check
before deciding to connect.

### 33. Workout notification elapsed includes paused time

**Files:** `LiveWorkoutManager.kt:124,175` vs `LiveWorkoutManager.swift:275,300`

Both the tick extras and the finished card compute `(endedAt|now − startedAt)/1000` with no pause
subtraction; iOS's `finishedState` does `max(0, ... - totalPauseSeconds)`, and Android's own
`ActivityAggregates.recompute` and history row subtract too — the card disagrees with the app's
own summary screen for any paused workout. (Fix lands free with #26's pause-scheme port.)

### 34. Workout notifications hardcode metric; granularity and sub-50m conventions diverge

**Files:** `WorkoutForegroundService.kt:121,156` vs `LiveWorkoutManager.swift:178,291,314`,
`WorkoutActivityAttributes.swift:184-189`

`"%.1f km"/"%.0f m"` always; the app has `ApiKeyStore.resolvedUnitSystem`/`UnitConverter.distance`
(used by RecordScreen/ActivityScreen/widgets) which the service never reads. iOS threads
`useImperial` into every ContentState, renders `%.2f`, and hides sub-50m as "—". Imperial users
see km on the notification and mi everywhere else. **Fix:** pass `useImperial` in the extras,
format via `UnitConverter` with iOS thresholds.

### 35. No content intent on workout notifications — tap does nothing

**Files:** `WorkoutForegroundService.kt:130-146,161-168`, `LiveWorkoutManager.kt:176-183`

Neither builder calls `setContentIntent`; on the finished card this also neuters
`setAutoCancel(true)` (fires only on content-intent tap). iOS attaches
`pulseloop://workout/<id>` to both Live Activity surfaces, routing finished→summary /
recording→live (`PulseLoopLiveActivityLiveActivity.swift:18,79`, `RootViews.swift:128-135`).
Android already has the destinations (`activity_detail/{id}`, `record`). **Fix:** add content
intents (finished card also needs the session id added to the finish extras).

### 36. No way back to an active recording; no orphan-cancel; no stale-session recovery

**Files:** `ui/PulseLoopApp.kt:390-397`, `LiveWorkoutManager.kt:44-61`,
`app/src/main/java/com/pulseloop/ui/screens/ActivityScreen.kt`

The "record" route sits above the tab start destination; switching tabs pops it, no `BackHandler`,
and `WorkoutState.isRecording` is read by no UI — once you leave, there is no banner and no return
path (the notification can't substitute: no content intent per #35, dead actions per #27).
`start()` lacks iOS's orphan-cancel loop (`LiveWorkoutManager.swift:148-153` — "cancel any
orphaned recording/paused sessions"), and there is no equivalent of iOS's
`StaleSessionRecoveryCard` ("Unfinished workout — Finish it to keep its time and distance, or
discard it.", `RecordViews.swift:76-125`). iOS also pins the live screen
(`navigationBarBackButtonHidden(true)`). **Fix:** keep the route reachable or block back while
recording; active-workout card on the Activity tab; cancel orphans in `start()`.

### 37. `"sport"` not GPS-capable; `ActivityMetricSet` not ported — cycle shows pace, not speed

**Files:** `ui/components/ActivityMeta.kt:64-65` vs `PulseLoop/DesignSystem/Components.swift:576,581`,
`PulseLoop/Services/WorkoutMetricsEngine.swift:115-137`

Android `gpsCapable = type in setOf("walk","run","cycle","hike")`; iOS includes `"sport": true` —
sport routes can't be recorded on Android, and a sport session with a route is mis-gated in the
summary. Separately, iOS varies metrics by type: walk/run/hike → pace+splits, **cycle → speed**
("MPH"/"KM/H" live tile and hero; "min/km reads oddly on a bike"), sport → distance-only (no
pace/speed/splits). Android shows pace for every GPS workout live and in the hero, and splits for
every GPS workout. **Fix:** add `"sport"` to the set; port `ActivityMetricSet` and key the live
tile label/format, hero third metric, and splits gating on it.

### 38. Post-finish flow: no effort/notes capture, no saved badge, no sync indicator

**Files:** `ui/PulseLoopApp.kt:594-598`, `WorkoutSummaryScreen.kt`,
`data/entity/CoreEntities.kt:160,162`

After finish Android navigates straight to the one summary screen (doubling as history detail).
iOS has a dedicated post-record view: "Updating from ring…" indicator, "WORKOUT SAVED" badge,
"How did this feel?" effort chips (Easy/Moderate/Hard/Very hard), "Add a note…" field, and a Done
button persisting both (`RecordSummaryViews.swift:68-175`). Android's entity already has `notes`
and `perceivedEffort` columns and the coach tools write them — so coach-set notes/effort exist in
the DB but no screen ever shows them. **Fix:** post-finish variant with badge + effort + note;
render notes/effort read-only on the detail view.

---

## 🟡 Low / cosmetic

39. **Live-screen polish cluster** — header and notification titles show the raw type key ("run ·
    recording") instead of `ActivityMeta.label` (`PulseLoopApp.kt:579`, `LiveWorkoutManager.kt:142`,
    `RecordScreen.kt:48` vs `RecordViews.swift:520`); no GPS/ring/HR/SpO2 status pills, live map,
    split strip, REC pill, or HR/SpO2 freshness subtitles (`RecordSummaryViews.swift:181-343`);
    grid doesn't adapt — indoor workouts get "0 m" and "--" tiles iOS hides; the Calories tile is
    hardcoded `--` (`WorkoutState` has no calories field); `--` used where iOS and Android's own
    summary use `—`; distance shows "0 m"/"850 m" where iOS shows "—" then 2-dp km/mi.
40. **Record picker** — GPS toggle hidden for indoor types vs iOS's always-visible
    disabled-with-helper ("Not available for this activity"); `useGps` resets to true on every
    open instead of seeding from a persisted default (`WorkoutPrefsStore` on iOS); no start
    haptic. (`ActivityScreen.kt:510,535-541` vs `RecordViews.swift:456-479`.)
41. **No finish/discard confirmation** — Android's Finish calls `onFinish` directly; iOS gates both
    exits behind a recap sheet ("Finish workout?" with Duration/Distance/HR stats; "Discard
    workout? This recording will be deleted and won't count toward your activity."). Android's
    `LiveWorkoutManager.cancel()` exists but has zero callers. (`RecordScreen.kt:106-113` vs
    `RecordViews.swift:591-618`.)
42. **Summary analytics sections missing** — HR zone-colored chart with min/avg/max footnote, HR
    ZONES time-in-zone card (`hrZoneDurations`, 5 zones %HRmax), SpO2 chart with 95-100 band,
    ELEVATION area chart + "Elev gain" tile (altitude is already stored on GPS points), RECORDING
    QUALITY card (GPS coverage %, dropped points, sample counts vs expected, sensor read failures
    — `hrPollFailureCount`/`spo2PollFailureCount`/`gpsPointCount`/`rejectedGpsPointCount` are all
    already persisted). Provenance: iOS `e8f0a0e` (2026-06-02) / `abd2ad2` — **predate the sync
    range, so this is backlog, not a port regression**; the sections the PR did port (hero, stat
    grid, map, splits) are verified faithful line-for-line. Triaged [~] backlog — call it out in
    the PR description as known-deferred scope.
43. **Summary gating keyed to `useGps` alone** — a GPS run edited to "Gym" still shows the
    distance/pace hero + map + splits (iOS: indoor hero, no map slot, via `showsGpsHeadline =
    useGps && gpsCapable(type)` with an explicit comment); an indoor workout edited to "Run" shows
    no map slot at all (iOS: "GPS route unavailable / Distance uses the ring/app estimate where
    possible." placeholder). (`WorkoutSummaryScreen.kt:133,408,449` vs
    `RecordSummaryComponents.swift:213-236,83-88`, `WorkoutMapView.swift:123-140`.)
44. **Edit sheet** — time step has no now-clamp, so a future time today is selectable and only
    caught by validation; iOS's ranged picker refuses future times outright. `is24Hour = false`
    always (iOS honors locale). Validation strings themselves verbatim parity; footnote is terser
    but *understates* real behavior (Android's recompute does pull in all-day ring data, as iOS's
    copy says). (`WorkoutSummaryScreen.kt:239,299-330` vs `RecordViews.swift:281-326`.)
45. **Delete confirmation** — copy drift ("Delete workout?" / "This removes the session and its
    recorded route." vs iOS "Delete this workout?" / "This permanently removes the workout and its
    recorded heart-rate, GPS, and sensor data." / "Delete workout" / "Keep workout") and iOS shows
    a mini recap (Duration/Distance/Avg HR). (`WorkoutSummaryScreen.kt:143-144` vs
    `RecordViews.swift:215-243`.)
46. **History sheet** — capped at the 200 most recent sessions (`ViewModels.kt:400` →
    `Daos.kt:175-176` LIMIT; iOS unbounded; in-progress/cancelled rows eat into the 200 before the
    finished filter); `today`/`zone` remembered once per dialog composition (stale TODAY/YESTERDAY
    across midnight; iOS evaluates per render); `headerFmt.format(day).uppercase()` is
    locale-sensitive (Turkish dotted-İ; use `Locale.ROOT`). Grouping key, ordering, header format,
    filtering, and sheet title all verified exact parity.
47. **Staleness warning semantics** — Android warns off `lastFullSyncAt` with static ">12h" text
    and stays silent when null (`NotificationContextBuilder.kt:74-77`); iOS's `DataQualityAnalyzer`
    reads `lastSyncAt`, renders dynamic "~Nh", and appends "No recent ring sync recorded — data
    may be incomplete." when never synced (`DataQualityAnalyzer.swift:42-49`). Unreachable for
    Colmi until #22 lands either way.
48. **No empty-store guard** — iOS skips the check-in entirely when the store has no measurements
    at all (`.skippedNoData`, `CoachNotificationService.swift:105-109`); Android proceeds
    unconditionally ("a stale check-in beats a missed one" — deliberate for stale, unconsidered
    for *empty*), so a fresh install with a never-synced ring gets a content-free AI check-in.
49. **Activity entry screen** — ACTIVITY_RECOGNITION denial dead-ends silently (no rationale, no
    Settings deep-link; after "Don't ask again", Start becomes a permanent silent no-op;
    `ActivityScreen.kt:85-108`); Daily Distance 1dp vs iOS 2dp; goal editor has 4 sliders while
    iOS has 6 steppers — Android's rings consume `distanceGoalMeters`/`caloriesGoal` that no UI
    can edit; no pull-to-refresh; daily summary card not tappable (iOS taps through to
    `ActivityTrendsView`, which has no Android counterpart — consistent with the #79 blocked note).
50. **Activity metadata copy** — labels differ ("Walking/Running/Cycling/Hiking" vs
    "Walk/Run/Cycle/Hike"; visible in picker, Log Past grid, summary headers, edit dropdown),
    helper copy differs throughout, and iOS's legacy-alias map (`outdoor_run`→run, `ride`→cycle,
    `strength`→gym — `Components.swift:586-589`) has no Android counterpart: a legacy/coach-created
    type falls through to `"Outdoor_run"` with a star icon and loses GPS capability. Extends
    first-pass finding #21 from "unreachable" to "user-visible via any non-canonical stored type".

---

## ⚪ Informational

51. **MiniMax client — verified near-perfect.** Endpoint, auth header, request schema (model +
    messages + tools-when-non-empty; stream/temperature/max_tokens/response_format/reasoning
    provably never sent), developer→system fold, continuation replay, image parts, tool
    re-nesting, `<think>` stripping, `mm_call_` fallback, both error surfaces, usage both-or-null
    mapping, all 8 preset slugs, and all 8 pricing rows match iOS line-for-line; key in
    EncryptedSharedPreferences, never logged. Only notes: shared OkHttp transport has no overall
    *call* timeout (iOS has a single 60s request timeout); Android's dedicated per-provider
    `minimaxModel` field is strictly more correct than iOS's shared `model` string (an OpenRouter
    slug can leak into MiniMax requests on iOS — another upstream-iOS bug candidate, like the
    rollup-reverse one the first pass found).
52. **Colmi spot-SpO2 stop byte** — Android always sends `[0x6A, 3, 0, 0]`; QRing echoes the final
    reading in byte 2 on the success path (`BloodOxygenActivity.java:318`) "so the ring's own log
    records it" — Android already does exactly this for HR (`ColmiSyncEngine.kt:551-553`).
    Optional one-byte fidelity improvement on success.

## Second-pass vendor validation (for findings #2/#5 fix shape)

Cross-checked the decompiled official apps per standing instructions. **QRing (Colmi official):**
opcodes confirmed (`CMD_REAL_TIME_HEART_RATE`=0x1E, `CMD_START_HEART_RATE`=0x69,
`CMD_STOP_HEART_RATE`=0x6A; `StartHeartRateReq.getSimpleReq` BCD(25)=0x25 — byte-for-byte what
Android's encoder sends). The app **never runs a continuous HR stream** — the only 0x1E
construction is an unstarted 20s self-repost runnable (dead code); every measurement is
screen-scoped `IDLE → [0x69 type sub] MEASURING → (30s auto-stop|user stop) → [0x6A type value|0
0] → IDLE`, torn down in `onStop()`. So the "spot preempts continuous" state structurally cannot
exist in the vendor app. **SmartHealth (YCBT official):** continuous mode is ring-side sport
reporting, and the app **re-issues the identical enable command immediately after every
interruption** — on reconnect mid-sport (`SportRunningActivity.java:972`), after history sync
(`HomeFragment.java:250,1983`), on resume — no delay, no special opcode, gated on an app-side
"should be active" flag. **That is exactly the shape of iOS's `restartWorkoutHeartRateIfActive()`
and exactly what finding #2 proposes** — no vendor uses a different re-enable opcode or a settling
delay. Two Android-specific notes: (a) `ColmiSyncEngine.stopSpO2()` leaves `realtimeHRActive=true`,
so the 20s keepalive keeps firing — but it sends only the *continue* frame `[0x1E,0x03]`, never
the *enable* `[0x1E,0x01]`, so only the restart revives a preempted stream; (b) Android-Colmi
drives the live `0x69/3` SpO2 sweep whereas iOS-Colmi fetches history instead — so the restart is
**more** load-bearing on Android than on iOS. Also updates finding #5: once the restart exists,
the ungated SpO2 poll needs no separate gate — iOS polls SpO2 during workouts precisely because
the restart makes it safe.

## ✅ Second-pass verified CLEAN

- **`HRSampleWindow` gate — line-for-line perfect port** (the actual subject of `36da8f2`):
  5000ms warm-up with `>=` boundary on both sides; echo = every sample inside 5s regardless of
  value; `begin()` before the engine start command; 3000ms strictly-greater contact-gap, never
  true before the first post-warm-up sample; `minSamples=6, band=8, majority=0.6`; upper median;
  cluster filter; Double-comparison majority test including the exact-equality edge; cluster's own
  upper median as result; abort (never settle-partial) on no-reading/disconnect/contact-loss;
  settle-via-`stableValue` (may still be null → FAILED); `hrNoReadingReported` fast-fail only
  before a real reading; deliberate non-clearing of `latestHRValue`; 30s/60×500ms HR loop with
  checks-before-sleep ordering. Android's 7 tests are net-new (iOS has none — the struct is
  private) and were hand-verified against the iOS algorithm, boundary directions included.
- **#57g finish-swap mechanics** — summary posted, `stopForeground(STOP_FOREGROUND_DETACH)`,
  `scheduleDismiss()`, `stopSelf()` ordering; `ACTION_FINISH` returns `START_NOT_STICKY` before
  the trailing `startForeground()` so the ongoing card can never clobber the summary; "`<type>`
  complete" title parity; dismiss-on-re-finish via REPLACE; WorkManager persistence across process
  death matches iOS's system-managed dismissal; cancel/discard path posts nothing and schedules
  nothing (`.immediate` parity); the bundled extras fix is send/receive-exact (0-as-null HR
  sentinel, "--" placeholder, `hasExtra` guard); `workout_live` channel at IMPORTANCE_LOW — the
  complete card is not alertable; POST_NOTIFICATIONS declared + requested at startup.
- **#61d grouping semantics** — device-local day bucketing, descending groups, newest-first items,
  `"EEEE, MMM d"` header format, TODAY/YESTERDAY special cases, "Workout history" title, and the
  finished-only filter are all exact parity (only the Low items in finding #46 diverge).
- **#61c worker mechanics** — the 15s bounded wait is correctly constructed (collector subscribed
  before `connectLastKnown()`, structured-concurrency cancellation on timeout, no leak of the
  *wait* itself); the <10min skip threshold is exact parity; `lastFullSyncAt` stamped exactly on
  "done" and nothing else (for the families that emit it); no bond manipulation on the worker
  path; worker cancellation still disconnects the GATT. Android gating/stamping on
  `currentReal()` (demo row excluded) is strictly safer than iOS's unfiltered `current`.
- **MiniMax provider** — see finding #51.
- **Log Past Activity screen** — type grid contents/order, defaults (run / now−1h / 60min),
  stepper ±5 with 5-min floor and disabled-minus, chips 15/30/45/60/90, duration text formats,
  "The workout must finish before now." + all three service errors verbatim, CTA disabled-state,
  and post-save navigation are exact parity.
- **Workout summary ported sections** — header, hero band (GPS vs indoor variants, "—"
  fallbacks), secondary grid contents/order, splits table (per-km/mi by unit pref, relative pace
  bars, fastest-only accent, ≥1 gating), map placement, and the [startedAt, endedAt] GPS windowing
  are line-for-line faithful; edit-sheet field limits, type list, and validation strings verbatim.
- **Activity entry structure** — card order, TODAY empty-state copy, workout-row contents, weekly
  goal widget copy, and the inline ACTIVITY_RECOGNITION *request* pattern itself (platform
  requirement; only the denial UX is gapped — finding #49).

## Notes for resuming (second pass)

- All second-pass High claims were re-verified against source after the agents reported them
  (Colmi done-signal absence, watchdog lifecycle, notification-ID sharing, pause math on both
  branches, MeasurementModal dead-code, 40-vs-60 constants, `measureSpot` return type,
  RingEventBridge:109, sleep-table wipe on CONNECTED, reconnect gating). Provenance checks used
  `git show main:` (pause accounting, notification actions — both pre-existing) and iOS-side
  `git merge-base --is-ancestor` against `b3697c0` (summary analytics sections and
  `c8969a4` predate the range; #82's port was a protocol-layer subset so the refusal gate and
  60s window fell through as subset gaps).
- Suggested merge-blocker list is now: first-pass #1, #2, #3 + second-pass #22, #23, #24, #25,
  #26, #27.
- Still unreviewed after both passes (small): `MainActivity.kt`/`DebugScreen.kt` non-notification
  hunks, `MetricsService.kt` diff, `DataRepairs.kt` diff, `HeartRateZones.kt`/`VitalsZoneModel.kt`
  internals (settings-screen math was verified, engine internals only via tests), widget publisher
  diffs if any. Nothing in the file-to-commit mapping suggests further High-severity surface, but
  these are the remaining blind spots if a third pass is wanted.
- The graphify knowledge graph (`graphify-out/graph.json`, 1.25GB) needs
  `GRAPHIFY_MAX_GRAPH_BYTES=3GB` to query; it's symbol-oriented — `graphify explain "<Symbol>"`
  and `graphify path A B` work well; natural-language queries hit literal token matches.
- Still true: no GitHub PR review comments posted. Ask before posting.
