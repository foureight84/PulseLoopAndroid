# iOS → Android Sync Ledger

Tracks which upstream iOS changes (github.com/saksham2001/PulseLoopiOS) have been evaluated
and/or ported to this Android app. The goal is feature and visual parity, minus the
intentional platform differences listed at the bottom.

## How to use this file

1. Find new upstream changes since the last triage:
   `git -C <ios-repo> log --first-parent --oneline <last-triaged-commit>..main`
   (PR merges — not individual commits — are the unit of triage.)
2. For each PR: read the diff (`git diff <merge-sha>^1 <merge-sha>`), decide a verdict,
   and add a row. Judge **behavior**, not code — a Swift fix ports as a Kotlin rule.
3. When a PORT/ADAPT item ships, fill in its **Android commit** column.
4. Update **Last triaged iOS commit** below.

**Verdicts:** `PORT` (Android needs it) · `ADAPT` (concept ports, implementation differs) ·
`PARTIAL` (some of it applies) · `ALREADY-HAVE` (Android already does this) ·
`SKIP` (iOS-only / docs / CI) · `BLOCKED` (depends on something Android lacks)

## Sync state

| | |
|---|---|
| **Canonical iOS repo** | `github.com/saksham2001/PulseLoopiOS` (always `main`) |
| **Fork baseline (iOS)** | `600c7a8` — Merge PR #6, 2026-06-20 |
| **Last triaged iOS commit** | `4dae095` — Merge PR #66 measure-HR redesign, 2026-07-16 |
| **Last triage date** | 2026-07-16 |
| **Range covered** | 48 commits / 9 first-parent items since `b3697c0` (2026-07-12) |

---

## Port queue (needs Android work)

Ordered roughly by value-for-effort. Status: ☐ open · ☑ done.

| # | iOS PR | Merged | Title | Verdict | Effort | Android commit |
|---|--------|--------|-------|---------|--------|----------------|
| ☑ | [#17](https://github.com/saksham2001/PulseLoopiOS/pull/17) `26a6075` | 06-24 | Colmi HR enable + activity sample idempotency | **PORT** | M | `1a4f007` |
| ☑ | [#15](https://github.com/saksham2001/PulseLoopiOS/pull/15) `eb5a288` | 07-02 | Sleep sessions splitting at midnight | **ADAPT** | M | `1a4f007` |
| ☑ | [#11](https://github.com/saksham2001/PulseLoopiOS/pull/11) `a582f7a` | 07-01 | Dance activity type | **PORT** | S | `4aad39a` |
| ☑ | [#43](https://github.com/saksham2001/PulseLoopiOS/pull/43) `a280388` | 07-04 | Units consistency (temp/glucose/distance/pace) | **PORT** | M | `4075752` (pace/distance) + `d760c24` (§3 glucose dashboard, via #35) + `09e9122` (§2 temp + §3 glucose detail chart) |
| ☑ | [#41](https://github.com/saksham2001/PulseLoopiOS/pull/41) `102aa35` | 07-04 | Status pill: "Disconnected" not endless "Searching…" | **PARTIAL** | S | already-have (`AppChrome.ConnectionStatusPill`) |
| ☑ | [#35](https://github.com/saksham2001/PulseLoopiOS/pull/35) `f0a4aee` | 07-01 | Vitals dashboard redesign (zones, cards, rings, detail screens) | **PORT** | XL | `19aac67`+`c978b32`+`f756010` |
| ☑ | [#19](https://github.com/saksham2001/PulseLoopiOS/pull/19) `445be25` | 06-25 | Settings redesign + measurement frequency control | **ADAPT** | L | `f4bcd47` |
| ☑ | [#9](https://github.com/saksham2001/PulseLoopiOS/pull/9) `cd62903` | 06-26 | Coach: multi-provider (Gemini) | **PORT** | L | `049058d`+`4d81a07` |
| ☑ | [#22](https://github.com/saksham2001/PulseLoopiOS/pull/22) `909c5cd` | 06-26 | Coach: OpenRouter provider (fold in #40 slug fix) | **PORT** | L | `049058d`+`4d81a07` |
| ☑ | [#31](https://github.com/saksham2001/PulseLoopiOS/pull/31) `cbb2487` | 06-29 | Coach: image attachments (multimodal) | **PORT** | M | `049058d`+`4d81a07` |
| ☑ | [#24](https://github.com/saksham2001/PulseLoopiOS/pull/24) `be6274f` | 06-28 | Coach scheduler thread-safety crash | **ADAPT** | S | no-op (no analogous bug — `CoachNotificationWorker` is a CoroutineWorker, no MainActor assert) |
| ☑ | — | 07-06 | **Design-parity sweep**: iOS dashboard design across all tabs (see notes) | **PORT** | XL | `4aad39a` |
| ☑ | [#48](https://github.com/saksham2001/PulseLoopiOS/pull/48) `aff8574` | 07-05 | Pairing redesign: brand tabs, ring product images, onboarding flow, shared profile/goal editors | **PORT** | L | `a38ddf5` |
| ☑ | [#49](https://github.com/saksham2001/PulseLoopiOS/pull/49) `779740b` | 07-06 | Settings rehaul: device hero card, grouped sections, exact-model matching | **PORT** | L | `de60096` |
| ☑ | [#44](https://github.com/saksham2001/PulseLoopiOS/pull/44) `80195a6` | 07-06 | Home-screen widgets (3 widgets + snapshot pipeline) | **ADAPT** | XL | `c8efccd` |
| ☑ | [#71](https://github.com/saksham2001/PulseLoopiOS/pull/71) `4fd008a` | 07-10 | Colmi R08 ring support (catalog entry) | **PORT** | S | `be74a19` |
| ☐ | [#77](https://github.com/saksham2001/PulseLoopiOS/pull/77) `4241d54` | 07-10 | jring protocol-parity fixes (RingBLEClient + JringSyncEngine + JringClock) | **ADAPT** | L | |
| ☐ | [#57](https://github.com/saksham2001/PulseLoopiOS/pull/57) `8182d8d` | 07-08 | Activity-recording redesign + post-workout vitals backfill + realtime-HR keepalive rework | **ADAPT** | L | |
| ☑ | [#54](https://github.com/saksham2001/PulseLoopiOS/pull/54) `cda2e9c` | 07-07 | Coach: MiniMax provider | **PORT** | M | `22d1ecc` |
| ☑ | [#64](https://github.com/saksham2001/PulseLoopiOS/pull/64) `338226a` | 07-09 | Long-press to reorder & hide cards (Today/Vitals) | **PORT** | M | `8f51349` (stage 1) + `1075586` (stages 2–3). Android uses a discrete edit-mode (Customize button → hide badge + move up/down + Hidden tray) instead of free-form drag |
| ☐ | [#65](https://github.com/saksham2001/PulseLoopiOS/pull/65) `4a60cfe` | 07-09 | Coach transparency/context rehaul | **ADAPT** | M | |
| ⊘ | [#56](https://github.com/saksham2001/PulseLoopiOS/pull/56) `440aaf4` | 07-10 | TK5 ring support (SmartHealth protocol; own sleep decode + multi-record periodic history) | **SUPERSEDED by #82** | — | — |
| — | [#61](https://github.com/saksham2001/PulseLoopiOS/pull/61) `39b611f` | 07-08 | ~~Activity UI sync-alerts bugfix~~ **RE-TRIAGED** into #61a–f below (was mis-billed "S"; PR is ~1188 ins/34 files) | — | — | see sub-rows |
| ☑ | #61a | 07-08 | **Battery low/critical alerts** (`BatteryAlertMonitor`: pure latched per-day crossing engine @20%/10% + re-arm bands + notification) | **PORT** | M | `e5b68f6` — `BatteryAlertEngine`+`BatteryAlertMonitor`+`BatteryNotifications` (own channel) + `ApiKeyStore.batteryAlertsEnabled` (default ON) + Check-Ins toggle; 8 iOS oracle tests ported green |
| ☑ | #61b | 07-08 | **Battery history + drainage graph** (persist battery samples + chart in Wearable settings) | **PORT** | M | `b5fcbf4` — `BatterySampleEntity`+DAO (`MIGRATION_7_8`, v7→v8) + throttled write in `EventPersistenceSubscriber` + `BatteryHistorySection` (24h/7d `ZoneLineChart`, fixed 0–100, critical/low/good zones) in Wearable settings; migration + chart runtime-verified on a real v7 upgrade |
| ☐ | #61c | 07-08 | **Coach notification improvements** (`CoachNotificationService` +73, `NotificationsSettingsView` +39, prompt/orchestrator/settings) | **ADAPT** | M | diff vs Android `CoachNotifications` — verify what changed |
| ☑ | #61d | 07-08 | **Workout-history day grouping** (TODAY/YESTERDAY/date headers in the history sheet) | **PORT** | S | `9c7fecb` — grouped `WorkoutHistoryDialog` by `LocalDate`. (Card style polish is iOS-specific `activityValueStyle`; N/A) |
| ☑ | #61e | 07-08 | **Sleep-page coach card → bottom** | **ALREADY-HAVE** | S | Android `SleepScreen` already renders the coach card at the bottom of both Day and aggregate views (hero opens, coach closes) — no change needed |
| ☐ | #61f | 07-08 | **Sync-event plumbing + misc bugfixes** (`PulseEventBus`/`RingEventBridge`/`RingSyncCoordinator`/`ActivityMigrations` + `SleepInsights`/`TodayTiles`/`ActivityRings`/`RingProtocol` fixes) | **ADAPT** | M | the actual "sync alerts" + a grab-bag "fixed multiple bugs" commit — assess each vs Android (Room Flows may already cover some) |
| ☑ | [#63](https://github.com/saksham2001/PulseLoopiOS/pull/63) `748e79f` | 07-08 | Label jring HR capability as "HR" | **PORT** | S | `be74a19` |
| ☑ | [#42](https://github.com/saksham2001/PulseLoopiOS/pull/42) `9633fe3` | 07-08 | Coach summary owns top card, no Today duplicate | **PARTIAL** | S | `3e14fef` |
| ☐ | [#74](https://github.com/saksham2001/PulseLoopiOS/pull/74) `ea3e22d` | 07-10 | Move Measurement Frequency into General → Physiology | **ADAPT** | S | deferred — cosmetic; doesn't map (Android has no Physiology route; row already Device-gated, empty section already hidden) |
| ☐ | [#82](https://github.com/saksham2001/PulseLoopiOS/pull/82) `902c449` | 07-11 | YCBT (Yucheng) protocol rebuild: TK5 + **SmartHealth-app Colmi rings** + pairing app-variant picker (**supersedes #56**) | **PORT** | XL | |
| ☐ | [#79](https://github.com/saksham2001/PulseLoopiOS/pull/79) `952cf4f` | 07-12 | Activity Year trends: divide in-progress current month by elapsed days, not full 30/31 | **PORT-when-built** | S | |
| ☑ | [#70](https://github.com/saksham2001/PulseLoopiOS/pull/70) `ac2b81a` | 07-12 | Today/Vitals apply Settings visibility + chart-detail changes immediately | **BLOCKED (behind #64)** | S | subsumed by #64 — Today/Vitals read the prefs StateFlow directly, so a visibility/order change recomposes immediately (no signature needed). Chart-detail resolution N/A on Android |
| ☑ | [#66](https://github.com/saksham2001/PulseLoopiOS/pull/66) `4dae095` | 07-16 | Measure HR/SpO₂ countdown redesign + **robust measurement** (warm-up echo discard, contact-gap, median/majority gate) | **PORT** | L | `36da8f2` (robustness; modal chrome deferred) |
| ☑ | [#83](https://github.com/saksham2001/PulseLoopiOS/pull/83) `2367d23` | 07-15 | Split same-day sleep into sessions (night + naps, 60-min gap) + Day carousel | **ADAPT** | L | `0d7212c` — `SleepSegmentation` + `EventPersistenceSubscriber.reconcileWakingDay` (overlap-matched stable ids) + `SleepInsights.collapseByDay` + `byDay` longest-session + `HorizontalPager` carousel. iOS's `DerivedUpdateRow` no-op signal + `migrateSleepSessionSegmentsIfNeeded` migration both dropped — Room Flows already drive reactivity and sleep rebuilds from the ring on every connect (see DataRepairs note) |
| ☑ | [#84](https://github.com/saksham2001/PulseLoopiOS/pull/84) `8b86e5c` | 07-15 | Sleep › Day navigation (page between days) | **PORT** | M | `0d7212c` — `SleepViewModel` dayOffset + `stepDay`/`jumpToDay`/`resetToToday`, chevron header + bounded Material3 `DatePicker` (UTC↔local day-key conversion). Day view isolated from the Today tile (lastNight stays on the true reference night) |
| ☑ | [#85](https://github.com/saksham2001/PulseLoopiOS/pull/85) `9093e9b` | 07-15 | Multi-session sleep days in demo seed | **PORT-with-#83** | S | `0d7212c` — `DemoDataSeeder.napsForDay`/`napStageBlocks` — demo nap sessions keyed `demo-sleep-<day>-<napStart>` (sourceRaw demo); days 0/1 get 2 naps, day 2 one, day 5 one |
| ☐ | [#90](https://github.com/saksham2001/PulseLoopiOS/pull/90) `9d05481` | 07-15 | LuckRing/TK18 ring support (Coolwear "K6" / 0xFF64 `f618` protocol) | **PORT** | XL | |
| ☑ | [#88](https://github.com/saksham2001/PulseLoopiOS/pull/88) `e937a39` | 07-15 | Refresh stale screens after data changes (coach edit → aggregates, goal-edit rings, metric-detail on-sync) | **ADAPT** | S | already-have (Room Flows cover b/c/d; a is an architecture-specific non-gap) |
| ☑ | [#75](https://github.com/saksham2001/PulseLoopiOS/pull/75) `5390a95` | 07-16 | Onboarding fit-to-viewport + copy polish + celebratory finale | **PORT** (fit-to-viewport N/A) | S | `ad2cc5b` — finale medallion + "Setup complete" eyebrow + refreshed copy (You're all set / Today·First sync·Days 3–7 / Start using PulseLoop) + welcome subtitle. Fit-to-viewport N/A (Compose sizes natively) |
| ☑ | [#35](https://github.com/saksham2001/PulseLoopiOS/pull/35) `78ca593` | 07-01 | **Physiology settings screen** (athlete mode, altitude, beta-blockers, lung condition, glucose unit → tune `VitalsThresholdEngine`) — sub-surface of #35 that the XL dashboard port dropped | **PORT** | S–M | `d760c24` |

## Port priority — open items (as of 2026-07-17)

Single source of truth for what to port next, ranked value-for-effort. Small correctness/feature
wins first, XL ring rebuilds last on their own branches, blocked/deferred at the bottom.

> **▶ RESUME HERE (next session):** Tier 1 clear; **#61a battery alerts DONE** (`e5b68f6`) and **#61b
> battery history + graph DONE** (`b5fcbf4`), both runtime-verified on API-35 (the #61b install exercised
> a real v7→v8 upgrade over existing data, not just a fresh create). Both landed as sequential commits on
> `iOS_sync_2026-07-16` — no separate branches, matching how every prior Tier-1 item shipped.
> Next up is **#61c coach notification freshness** (M, ADAPT): active sync-before-notify + a new
> `lastFullSyncAt` field (stamped on `SyncProgress("done")`) replacing the freshness gate's `lastSyncAt`
> (which Android also re-stamps on bare CONNECT — same latent bug as iOS had). This folds in the only
> worthwhile #61f bits (`lastFullSyncAt` + an `awaitSyncCompletion` primitive on `RingSyncCoordinator`);
> #61f has no standalone work otherwise. ⚠ **Risk:** driving a fresh connect-and-sync from a background
> WorkManager worker is unreliable on Android (BLE background limits) — the "await in-flight sync" and
> "send last-known + warning" paths port cleanly; the "connect from background" path likely must degrade
> to last-known+warning. Read the #61c/#61f port-queue rows + the 2026-07-17 recon note below before
> starting. After #61c: **#65** coach transparency, re-scoped **L→XL** (in-memory chat vs SwiftData; no
> WeatherKit) — needs a scope decision (session-scoped usage/trace vs a Room migration) before starting.

**Tier 1 — small, high-value (land on the current sync branch):**

1. ~~**#35 Physiology settings screen**~~ ✅ **DONE** (2026-07-17, uncommitted). Screen + ApiKeyStore
   persistence + `fromProfile` wiring + widget refresh. Glucose-unit picker also lands **#43 §3**.
2. ~~**#43 §2 temp detail-chart unit conversion**~~ ✅ **DONE** (2026-07-17, uncommitted). Plus §3
   glucose end-to-end on both dashboard card and detail chart.
3. ~~**#75 Onboarding copy + finale**~~ ✅ **DONE** `ad2cc5b` — finale medallion + refreshed copy;
   fit-to-viewport N/A.
4. ~~**#61d Workout-history day grouping**~~ ✅ **DONE** `9c7fecb`. ~~**#61e sleep coach-card
   position**~~ ✅ **ALREADY-HAVE** (Android renders it at the bottom already).

**Tier 1 is now clear.** Next up is Tier 2 (below).

**~~#61~~ RE-TRIAGED** (was a mis-scoped "S"): split into #61a–f (see port-queue rows). Battery
alerts (#61a) + battery graph (#61b) + coach-notif (#61c) + sync-event/bugfix bundle (#61f) are each
their own M-sized item and drop to Tier 2/3; only #61d/#61e are Tier-1-sized.

**Tier 2 — medium features:**

5. **#65 Coach transparency/context rehaul** (M, ADAPT). **⚠️ recon 2026-07-17: really L→XL** — Android
   coach chat is in-memory (iOS persists to SwiftData); WeatherKit has no Android SDK. Bigger than "M".
6. ~~**#61a Battery low/critical alerts**~~ ✅ **DONE** `e5b68f6`. `BatteryAlertEngine`
   (pure, 8 iOS oracle tests ported green) + `BatteryAlertMonitor` (bus collector, per-day latch in
   SharedPreferences) + `BatteryNotifications` (own "Ring Battery" channel, one-shot, POST_NOTIFICATIONS
   guarded) + `ApiKeyStore.batteryAlertsEnabled` (default ON, coach-independent) + a Check-Ins toggle.
7. ~~**#61b Battery history + drainage graph**~~ ✅ **DONE** `b5fcbf4`. `BatterySampleEntity`+DAO
   (`MIGRATION_7_8`), throttled write in `EventPersistenceSubscriber`, 24h/7d `ZoneLineChart` in
   `WearableSettingsScreen`. Migration + chart runtime-verified on a real v7→v8 upgrade.
8. **#61c Coach notification improvements** (M, ADAPT). ◀ **RESUME HERE.** **Recon 2026-07-17:** real gap — iOS actively
   syncs (or awaits an in-flight sync) before notifying + fixes the freshness gate to use a new
   `lastFullSyncAt` (stamped on `SyncProgress("done")`) instead of `lastSyncAt` (re-stamped on bare
   CONNECT). **Android has the same latent `lastSyncAt` bug** in `NotificationContextBuilder`'s stale
   warning. ⚠️ Driving a fresh connect-and-sync from a background WorkManager worker is unreliable on
   Android (BLE background limits) — the "await in-flight" + "send last-known" paths port cleanly; the
   "connect from background" path likely degrades to last-known+warning. Folds in the worthwhile #61f bits.
9. **#61f Sync-event plumbing + misc bugfixes** — **Recon 2026-07-17: mostly ALREADY-HAVE (net S).**
   Android's Room-Flow + bucket-sum recompute + in-place self-heal already cover 7 of 10 items (done
   signal, inflated/garbage migrations, formatter caching, tile autosize, future-ts). Only worth-porting
   pieces are **`lastFullSyncAt`** + an **`awaitSyncCompletion`** primitive on `RingSyncCoordinator` —
   **both fold into #61c**, so #61f has no standalone work. (Optional nits: source-side activity ceilings
   for indep. distance/calorie guards; Compose value autosize — not active bugs.)

**Tier 3 — large, focused work (own commits):**

10. **#57 Activity-recording redesign** + post-workout vitals backfill + realtime-HR keepalive rework (L, ADAPT).
11. **#77 jring protocol-parity** (RingBLEClient + JringSyncEngine + JringClock) (L, ADAPT).

**Tier 4 — XL, dedicated branch each:**

12. **#82 YCBT (Yucheng) protocol rebuild** — TK5 + SmartHealth-app Colmi rings + pairing app-variant picker (XL, PORT).
13. **#90 LuckRing/TK18** — Coolwear "K6" / `0xFF64` protocol (XL, PORT).

**Blocked / deferred:**

- **#79 Activity Year-trends** (S) — blocked: no Activity-trends screen on Android yet (not created by #57's redesign either).
- **#74 Measurement-Frequency relocation** — deferred, but **revisit after #35**: it was deferred for "no Physiology route," and #35 creates exactly that route, giving the "move Measurement Frequency under Physiology" idea a home.

### 2026-07-17 Tier 2 session — recon + #61a (branch `iOS_sync_2026-07-16`)

Recon'd all five Tier 2 items against the real iOS diffs, then ported the cleanest (#61a). Re-scoped
verdicts recorded in the tier list above: **#61f is mostly already-have** (Room Flows cover it; only
`lastFullSyncAt`/`awaitSyncCompletion` survive, and both fold into #61c); **#61c** carries a
background-BLE-sync risk; **#65 is L→XL**, not M (in-memory chat vs SwiftData; no WeatherKit on Android).

**#61a Battery low/critical alerts — DONE `e5b68f6`.** Pure `BatteryAlertEngine` (thresholds 20/10,
re-arm 25/15, most-severe latch, per-day `yyyy-MM-dd` reset, `1..100` guard) ported 1:1 from the Swift
engine; all **8 iOS oracle tests** ported to `BatteryAlertEngineTest` and green. `BatteryAlertMonitor`
collects `PulseEvent.BatteryLevel` off `PulseEventBus`, threads per-day latch state through
SharedPreferences (`pulseloop.batteryalerts`), gated on `ApiKeyStore.batteryAlertsEnabled` (default ON,
coach-independent — matches iOS "absent = enabled"). `BatteryNotifications` is a one-shot local
notification on its own "Ring Battery" channel (id 2002, shared across severities so critical replaces a
pending low), POST_NOTIFICATIONS-guarded (no-ops silently if ungranted — a background monitor can't
prompt). Started from `PulseLoopApp` next to `persistence.start()`; channel created in `MainActivity`.
Settings toggle added to `CheckInsSettingsScreen` as an independent card. `:app:assembleDebug` +
`:app:testDebugUnitTest` green. **Runtime-verified on API-35 emulator:** app starts clean with the
monitor wired (no crash/exception), the `ring_battery` notification channel is registered, and the
"Ring battery alerts" card renders in Settings → Coach Check-Ins with its toggle **ON by default**
(independent of the Daily-Check-in toggle, which was Off). Only unverified surface is the actual
notification *delivery*, which needs a live ring `BatteryLevel` event. Files: `service/BatteryAlertEngine.kt`,
`service/BatteryAlertMonitor.kt`, `notifications/BatteryNotifications.kt`, `settings/ApiKeyStore.kt`,
`MainActivity.kt`, `ui/PulseLoopApp.kt`, `ui/screens/SettingsSubScreens.kt`,
`test/.../service/BatteryAlertEngineTest.kt`.

**#61b Battery history + drainage graph — DONE `b5fcbf4`.** `BatterySampleEntity` (id/percent/timestamp/
createdAt, indexed on timestamp) + `BatterySampleDao.samplesBetween(start, end, limit)`; Room bumped
v7→v8 with `MIGRATION_7_8` creating the table + index. `EventPersistenceSubscriber.recordBatterySample`
throttles off the same `BatteryLevel` event that already updates `DeviceEntity` — in-memory
`lastBatteryPercent`/`lastBatteryLogAt`, logs on change or a 30-min floor (first reading after each
launch always logs, matching iOS). `BatteryHistorySection` composable in `WearableSettingsScreen`: a
`SingleChoiceSegmentedButtonRow` 24h/7d picker over a fixed-`0.0..100.0` `ZoneLineChart`, zone-colored
≤20 critical (red) / ≤50 low (amber) / else good (mint) — mirrors iOS `colorForValue` via `MetricZone`
boundaries (`ZoneLineChart` matches `lower <= value < upper`, so cuts sit at 21/51). "Not enough data
yet" text under 2 samples, matching `ZoneLineChart`'s own <2-sample no-op.
**Runtime-verified on API-35, exercising the real migration path** (not just a fresh v8 create):
installed the new build directly over an already-running v7 app/database (no uninstall), confirmed no
crash and `PRAGMA user_version` = 8 with `battery_samples` present at the exact expected schema, and
that pre-existing rows survived (`devices`=1, `measurements`=772 both intact). Seeded 8 synthetic
`battery_samples` rows via on-device `sqlite3` (`run-as` + stdin, since `adb shell` re-parses quoted
argv on the remote shell — piping the whole `run-as ... sqlite3` invocation as one `adb shell` string
argument was what worked) spanning 82%→9%; the Wearable-settings chart rendered with correct zone
coloring (mint dots >50, amber 21–50, a connected red segment for the last two <21 points within the
90-min gap window) and the 24h/7d toggle switched without error. Files: `data/entity/CoreEntities.kt`,
`data/dao/Daos.kt`, `data/PulseLoopDatabase.kt`, `service/EventPersistenceSubscriber.kt`,
`ui/screens/SettingsSubScreens.kt`.

### 2026-07-17 port session (branch `iOS_sync_2026-07-16`)

Worked Tier 1 high-value-first. Both landed items build clean (`:app:compileDebugKotlin`) and the
full unit suite is green; 3 new tests added. Committed as `d760c24` (#35) and `09e9122` (#43).

- **#35 Physiology settings screen — DONE** (`d760c24`). New `PhysiologySettingsScreen` (Settings → General →
  Physiology): athlete-mode toggle, unit-aware altitude field, beta-blocker + lung-condition
  tri-state segmented rows, glucose-unit picker. Persisted to **`ApiKeyStore`** (not Room) — matches
  where `unitSystem` + calibration already live, so no migration. `UserPhysiologyProfile.fromProfile`
  extended with the five inputs (defaulted → existing callers unaffected); a private
  `ApiKeyStore?.physiologyProfile(age, sex)` extension wires the three build sites (Vitals/widget
  `buildState`, `VitalDetailViewModel` init + refresh). Save republishes the widget snapshot.
  Files: `ApiKeyStore.kt`, `service/VitalsZoneModel.kt`, `ui/viewmodels/ViewModels.kt`,
  `ui/screens/SettingsSubScreens.kt`, `ui/screens/SettingsScreen.kt`, `ui/PulseLoopApp.kt`.
- **#43 §2 + §3 — DONE** (`09e9122`). §3 dashboard glucose card was already unit-aware; #35's glucose-unit
  wiring makes it live. Detail chart: `VitalDetailViewModel.convert()` now applies the mg/dL→mmol/L
  unit (was offset-only); new `displayThresholds()` in Screens.kt converts the chart's zone
  bands + y-axis for **temp (°F) and glucose (mmol/L)** so they match the plotted line (was the §2
  bug: °F points on a °C axis); `zoneRangeText`/`formatStat` extended to glucose units. Shared
  `GlucoseUnit.fromMgdl()` added. Tests: `VitalsZoneModelTest` (fromProfile passthrough + defaults,
  glucose conversion).

**⚠️ Triage correction — #61 is not a small item.** Pulling the diff for the "Activity UI
sync-alerts bugfix" row revealed the PR (`39b611f`) is ~1188 insertions across 34 files bundling
several unrelated features: a new **BatteryAlertMonitor** (+172 + 3 test files), a **battery-drainage
history + graph**, a **sleep-page coach-card reposition**, coach-notification changes, and assorted
bugfixes — the activity sync-alert fix is one slice. The ledger's "S / activity sync-alerts bugfix"
under-describes it badly. **Re-triaged into #61a–f** (see port-queue rows): #61a battery alerts (M),
#61b battery history + graph (M), #61c coach-notification improvements (M), #61d workout-history day
grouping (S), #61e sleep coach-card position (S), #61f sync-event plumbing + misc bugfixes (M).
Android has **no battery alerts/history at all** (#61a/#61b are net-new), its workout history isn't
day-grouped yet (#61d), and its `SleepScreen` already has a coach card (#61e is a position check).
Nothing from #61 ported yet — the M-sized pieces moved to Tier 2, the two S slices stay Tier 1.

### 2026-07-16 triage (since `b3697c0` → `4dae095`, 48 commits / 9 first-parent)

Nine PR merges. One new ring family (#90, XL), two sleep features (#83/#84 + #85 seed), a
measurement robustness rework (#66), a reactivity bundle (#88), an onboarding polish (#75),
plus iOS-26 glass (#89, skip) and one fix Android already shipped (#87). None depend on the
sleep-sync reliability work; sequence them behind it as before.

**Relevant → port:**

- **#66 Measure HR/SpO₂ robust measurement** (`4dae095`, L) — the headline behavioral fix. iOS added
  `HRSampleWindow`, which owns two rules a spot HR reading must pass: **(1) warm-up echo discard** —
  the ring replies with its *last stored* bpm the instant the manual-HR command is sent, so everything
  in the first **5 s** is dropped (else a measurement "succeeds" in two seconds on an hours-old number);
  **(2) a consistency gate** — need ≥6 samples, and ≥60% must sit within ±8 bpm of the median, else it
  reports **nothing** and asks for a retry (a plausible-but-untrustworthy HR is worse than an honest
  retry). Plus a **contact-gap** abort (>3 s between samples = ring slipped) and `measurementReceivedReading`
  distinguishing a fresh reading from the stale on-screen value. Android's "Measure Vitals" sweep
  (recent `56ff` single-sweep work) almost certainly accepts the cached echo and doesn't gate on scatter
  — **check `RingSyncCoordinator`/measurement path in the android/ repo and port these rules as Kotlin.**
  The countdown-modal UI redesign (`MeasurementModal`/`MeasurementRingView`/`VitalsResultsView`) is the
  ADAPT half — port the robustness first, the modal chrome can follow.
- **#83 Multi-session sleep + Day carousel** (`2367d23`, L, ADAPT) — iOS now splits a *waking day's*
  sleep into distinct sessions (main night vs. daytime naps) wherever there's a **≥60-min gap** between
  stage blocks, then reconciles SwiftData rows with **stable identity** (match each segment to the
  existing row whose prior range overlaps, so a nap syncing before its night doesn't reshuffle ids),
  and pages them in a **Day carousel**. Pure logic lives in `SleepSegmentation.segment()`; the
  SwiftData reconcile (`SleepService.reconcileWakingDay`, idempotent, no-op on unchanged days) is the
  adapt target. **This changes Android's one-session-per-waking-day model** (the #15 port keys a single
  session per waking day) — ADAPT the segmentation as a pure Kotlin function + a Room reconcile that
  preserves session ids, and add the carousel to `SleepScreen` Day view.
- **#84 Sleep Day navigation** (`8b86e5c`, M) — page between days on the Sleep › Day view (prev/next
  day arrows). Android's Day view anchors on the 4 AM reference night but (verify) has no day paging.
  Straightforward once #83's per-day session model is in place; can land independently for the single-
  session case too.
- **#85 Multi-session sleep seed** (`9093e9b`, S) — adds multi-session (night + nap) days to the demo
  seed so the #83 carousel has something to show. Android's `DemoDataSeeder` is 1:1 with `SeedData.swift`,
  so mirror it — but **only after #83** lands the segmentation/reconcile (the seeded blocks must split
  the same way). Watch the demo-id hazard from the 2026-07-06 review (demo nights use `demo-sleep-<day>`
  ids, must not collide with real synced sessions).
- **#90 LuckRing/TK18 ring support** (`9d05481`, XL) — a whole new ring family on the **Coolwear "K6"
  protocol** (company ID `0xFF64`, GATT service `f618`, notify `b001`/write `b002`, fixed **20-byte**
  packets, CRC disabled, no crypto; binding via a MixInfo TLV bundle, dataType 110). New Swift surface:
  `LuckRingProtocol/Decoder/Encoder/Driver/SyncEngine/HistorySync/Coordinator` + `WearableModel`
  `luckring-tk18` (advertises `^TK18([ _-].*)?$`, family `.luckRing`, HR·SpO₂·HRV·Temp·BP·Sleep·Steps)
  + product image + a big test suite. **Only TK18 is hardware-tested** in the whole `0xFF64` family, so
  iOS marks it `.limited` (untested siblings still pair via strong-signal match with generic art) —
  carry that caveat. PORT into Android's `WearableModel` catalog + a new sync engine if we want LuckRing
  on Android; large, lower priority than the sleep/measure work. **Side note relevant to queued #82:**
  this PR also promotes `colmiSmartHealth` from `.limited` → **`.full`** (the YCBT SmartHealth-Colmi is
  now proven on real hardware), so #82's "unconfirmed on hardware" caveat is partly retired.
- **#88 Data reactivity** (`e937a39`, S, ADAPT) — a bundle of "stale screen" fixes: **(a)** coach
  activity edits (`ActionTools.applyUpdatesNow`) now route type/time changes through
  `ActivityService.applyEdit` so duration/distance/calories aggregates + the sample window stay
  consistent instead of the view keeping old values (was setting fields directly); **(b)** `TodayStore`
  folds the **goal targets** into its summary signature so a goal edit refills the rings immediately;
  **(c)** `MetricDetailView` observes `PulseDataChange` to re-fetch when a background sync lands while
  open; **(d)** `GoalsSettingsView` fires `PulseDataChange.notify()` on save. **Verify each against the
  android/ Kotlin** — Android's reactive `TodayViewModel`/Flow model may already cover some (e.g. Room
  Flows auto-refresh the detail screen); the coach-edit-aggregates one (a) is the most likely real gap
  (check Android's `PendingActionExecutor`/coach edit path recomputes aggregates).
- **#75 Onboarding polish** (`5390a95`, S, PARTIAL) — **fit-to-viewport** (`OnboardingFittedBand`
  measures height, scales content 0.80–1.06 so steps 1/2/5 never scroll or clip on SE/mini, falls back
  to a ScrollView at accessibility Dynamic Type sizes) + **copy polish** + a **celebratory finale**.
  The fit-to-viewport is iOS `GeometryReader` layout mechanics — Compose handles responsive sizing
  natively, so that part is largely N/A. Port the **copy tweaks + the finale animation** into Android's
  `OnboardingFlow` (from #48) if we want the polish; low priority.

**Already have / no Android bug (verified):**

- **#87 Colmi quarter-hour activity buckets** (`cfa8109`) — iOS bug: `ColmiDecoder` mapped the
  quarter-of-day slot to `hour = q/4, minute = 0`, collapsing all four quarters of an hour onto `HH:00`
  so three of every four buckets overwrote each other (up to ~75% step undercount). **Android already
  fixed exactly this** — it's the "Activity history slices collapsed to hour granularity" entry in the
  Android-originated fixes below (`hour = idx/4, minute = (idx%4)*15`). iOS has now caught up; nothing to
  port. Validation that Android led here.

**Skip (iOS-only / already-covered):**

- **#89 iOS-26 Liquid Glass rendering** (`0a8ab4e`, +227) — Liquid Glass correctness (glass containers,
  tile-flash-on-re-render fixes) + Dynamic Type accessibility. **SKIP as a visual language** (standing
  policy — Android keeps its own surfaces). Two portable ideas already covered elsewhere: caching heavy
  `buildTodaySummary` off the render path is an iOS SwiftUI `@Query` re-render problem (N/A to Compose,
  which doesn't re-run the whole body on data change), and `ActivityView` now observing `PulseDataChange`
  is the same reactivity theme as **#88** — fold any Activity-refresh gap into the #88 ADAPT.
- **Local `0d1b965`** "seed: month of demo workouts + 30-day vitals series" — an *Android-side* / local
  demo commit sitting on top of the pulled `4dae095`, not an upstream item. Not triaged.

### 2026-07-16 port session (branch `iOS_sync_2026-07-16`)

Worked the queue high-value-first, small/verified units. All commits build; full unit
suite green (402 tests, 0 failures).

- **#66** measure robustness → `36da8f2` (HRSampleWindow: warm-up echo discard, contact-gap
  abort, median/majority gate; pure class + unit tests). Modal chrome redesign deferred.
- **#54** MiniMax coach provider → `22d1ecc` (full provider: client, presets, store, resolver,
  Settings row + tests).
- **#42** Today coach-card dedup → `3e14fef`.
- **#71** Colmi R08 + **#63** jring "HR" label → `be74a19`.
- **#41** status pill, **#24** coach scheduler, **#88** reactivity → verified **no-op / already-have**
  (see rows). **#74** deferred (cosmetic, doesn't map to Android's settings structure).

Value-first second pass:
- **#64 complete** (highest value): `8f51349` (stage 1 — prefs store + pure logic + 24 tests) +
  `1075586` (stages 2–3 — reactive wiring into Today/Vitals + discrete edit-mode UI: a Customize
  button enters edit mode, cards get a hide badge + up/down move controls over a tap-scrim, a
  Hidden tray restores, a Done bar exits). Chose discrete move/hide over free-form drag (Compose
  has no reorderable grid + no drag lib). **#70 subsumed** — the screens read the prefs StateFlow,
  so a visibility/order change recomposes immediately with no summary-signature machinery.
- **#43 partial**: live record pace/distance shipped `4075752`; **§2** temp detail-chart zone/axis
  conversion + **§3** glucose mmol/L end-to-end remain (both narrow; mostly-already-done per triage).

Still open: **#83/#84/#85** sleep now shipped (`0d7212c`). For the current ranked open queue see
**[Port priority — open items](#port-priority--open-items-as-of-2026-07-16)** above (the single
source of truth); this paragraph is retained as the historical end-of-session state.

Note: #64's edit-mode UI was **runtime-verified on an API-35 arm64 emulator** (com.pulseloop.debug):
Today + Vitals render through the new card-dispatch, and the full flow works — Customize enters edit
mode, the "–" badge hides a card into the Hidden tray, "+" restores it to its saved position,
up/down reorder (with correct first/last chevron gating), and Done persists the new order. No crashes.
Also confirmed live: #42 (single top coach card) and #41 ("Disconnected" pill).

### 2026-07-16 gap found: Physiology settings screen (retro-add to #35)

Reviewing the #74 defer, found the **Physiology settings screen was never ported** — it's a
distinct gap, not the cosmetic #74 relocation. Origin: iOS commit `78ca593`, a sub-surface of
the **XL #35 vitals-dashboard redesign** (merged `f0a4aee`, 07-01). Android's #35 port took the
*engine* — `service/VitalsZoneModel.kt#UserPhysiologyProfile` already carries all five inputs
(`athleteMode`, `altitudeMeters`, `usesBetaBlockers`, `hasKnownLungCondition`, `preferredGlucoseUnit`)
and `VitalsThresholdEngine` reads athlete-mode + altitude — but **not the Settings screen that
feeds them**. Result: the fields are wired but permanently defaulted — every construction site is
`UserPhysiologyProfile.fromProfile(age, sex)` (ViewModels.kt:597/792/950), which fills only age+sex,
and `UserProfileEntity` has no columns for the rest. So athlete mode / altitude / beta-blockers /
lung condition are unreachable, and glucose-unit selection is the still-open **#43 §3**.

Why it hid: the ledger triages at PR granularity, and this screen arrived *inside* an XL PR
rather than as its own row — "#35 dashboard = done" was true, but nothing tracked its Physiology
sub-screen. #74's note ("Android has no Physiology route") then mistook the missing route for an
intentional structural difference and deferred, instead of flagging the route itself as unported.
**To port (S–M):** add the physiology columns to `UserProfileEntity` (+ migration), extend
`fromProfile` to read them, and build a `PhysiologyScreen` under Settings → General. Folds in
#43 §3 (glucose unit). Added as its own queue row above. **✅ Resolved 2026-07-17** — the screen
persists to `ApiKeyStore` rather than Room `UserProfileEntity` (matching Android's existing
units/calibration convention), so no migration was needed; see the 2026-07-17 port session.

### 2026-07-14 triage (since `e00c24b` → `b3697c0`, 29 commits / 9 first-parent)

Nine first-parent items: 7 PR merges + 2 direct doc commits. Two are substantial
(#82 YCBT rings, #80 Apple Health); the rest are small fixes, docs, or CI.

**Relevant → port:**

- **#82 YCBT protocol rebuild** (`902c449`, XL) — the headline item. iOS rebuilt TK5 on the
  **Yucheng YCBT** protocol (`be940` GATT service) and, on the same driver, added
  **SmartHealth-app Colmi rings** (new model `colmi-r99`, advertises `R99 <4hex>`,
  family `.colmiSmartHealth`). The design insight to port: **a Colmi ring ships with *either*
  the QRing firmware (the Yawell/GadgetBridge protocol Android already speaks) or the
  SmartHealth firmware (YCBT) — and the BLE name doesn't reliably say which.** So pairing now
  asks the user which app the ring came with (`RingAppVariant` .qring/.smartHealth picker on
  the model card), and a wrong pick surfaces a one-tap "try the other app" retry
  (`RingAppVariant.other` + a targeted error message). New Swift surface: `YCBTProtocol`,
  `YCBTDecoder/Encoder/Driver`, `YCBTHealthRecords`, `YCBTHistoryTransfer`, `YCBTSyncEngine`,
  `YCBTSettingsEncoder`, plus `RingSyncCoordinator` (+244) and `WearableModel` (+213) app-variant
  wiring, and a big test suite (YCBT*Tests replace the deleted `TK5DecoderTests`). **This
  obsoletes queued #56** (the old SmartHealth-only TK5 approach) — port #82's YCBT architecture
  instead of #56. ADAPT to Android's `WearableModel` catalog + `RingSyncCoordinator`; the
  app-variant picker maps to the pairing carousel. Gauge value scales are still unconfirmed on
  real TK5 hardware (iOS labels it "Limited support") — carry that caveat over.
- **#79 Activity Year trends avg** (`952cf4f`, S) — for the in-progress current month, divide the
  month's total by *elapsed* days (`day-of-month`), not the full 30/31, else the current month's
  per-day bar + the headline average read low. Android has **no `ActivityTrends`/Year activity
  view yet** (only Sleep has Day/Week/Month/Year), so there's nothing to fix today — **apply this
  rule when the activity trends screen is built.** Its sibling, `dailyAverage`, is the same
  elapsed-days idea.
- **#70 Settings visibility reactivity** (`ac2b81a`, S) — Today/Vitals refresh signatures must
  fold in the per-metric visibility + chart-resolution prefs so a Settings toggle rebuilds the
  tab immediately instead of waiting for an unrelated sync to bump the signature. **BLOCKED:**
  Android has no `hiddenMetrics`/`MetricPrefs`/per-metric visibility at all (confirmed — the
  symbols don't exist), so this is a no-op until **#64 (long-press reorder/hide)** lands the
  visibility store. Port #70's signature change *as part of* #64.

**Already have / no Android bug (verified against the Kotlin source):**

- **#68 coach weekly avg steps** (`8353227`) — iOS bug: `steps7d` is a zero-filled 7-slot
  scaffold, so averaging over `.count` (always 7) understates; fix divides by `daysAvailable`.
  Android's `CoachContextBuilder.build()` uses `activityDailyDao().recent(7)` — **only real rows,
  not zero-filled** — and passes the raw `steps7d` list plus `daysAvailable` (days with steps>0)
  to the packet rather than pre-averaging. The specific bug is absent. Nit to keep in mind: if a
  downstream consumer ever averages `steps7d`, divide by `daysAvailable`, not `size`.
- **#69 coach history recency** (`8837f8a`) — iOS bug: a `fetchLimit=40` ascending SwiftData
  query capped replayed context at the *oldest* 40 messages, freezing the coach past message 40;
  fix fetches newest-40 descending then re-sorts. Android's live `CoachViewModel.sendMessage`
  builds `prior` from the **in-memory** `_state.value.messages` (full thread, no limit, already
  chronological), so the bug can't occur on the send path. Note: Android *has since added*
  persisted `coach_conversations`/`coach_messages` tables (`CoachMessageDao`, memory
  "threads in-memory only" is now stale) — **if** a persisted-history browser ever replays via
  `forConversation` with a `LIMIT`, sort **DESC then reverse**, per #69.

**Skip (iOS-only / docs / CI):**

- **#80 Apple Health sync** (`c1275ad`, +1,887) — HealthKit read/write with per-type toggles,
  workout export, and profile import. **Intentional iOS-only divergence** (HealthKit-adjacent).
  The Android analogue would be **Health Connect** — not queued, but if Android ever wants
  wearable→platform sync, this PR is the reference design (per-type prefs store, workout
  exporter, profile importer, sync publisher). SKIP for now.
- **#81 contributors automation** (`32dfbe3`) — GitHub Action + `update_contributors.py` +
  README/docs. Repo governance; the Android repo has its own. SKIP.
- **`b3697c0`** (move YCBT spec out of docs site, add Discord to About) and **`0f500fc`**
  (jring firmware URLs, drop stale TK5 protocol notes) — docs. SKIP.

### 2026-07-10 triage (since #44 / `80195a6` → `e00c24b`, 99 commits / 15 first-parent)

> **Port order: do these AFTER the Colmi sleep-sync fix lands.** The sleep issue
> (see `colmi-sleep-sync-diagnosis.md`) is the priority; these ports queue behind it.
> None of them depend on sleep, but we're sequencing deliberately so the sleep fix
> isn't destabilised by unrelated BLE/activity churn landing at the same time.

**Sleep status check (the reason this triage was run):** iOS has **NOT** addressed the
Colmi sleep-sync reliability problem. The only Colmi `ColmiSyncEngine` change in this range
(PR #57's `isVitalsBackfill`) adds a post-workout fast-path that *skips* sleep — sleep is
still stage 5 of the chained pipeline, still watchdog-force-skipped when a write is dropped.
No merged or open iOS PR decouples sleep into an independent request or fixes the
write-queue drop. So there is nothing to port for sleep; Android leads here. See
`colmi-sleep-sync-diagnosis.md` for the fix plan.

**Relevant → port (queued behind the sleep fix), highest value first:**

- **#71 Colmi R08 support** (`4fd008a`) — add R08 to the wearables catalog. Small, safe,
  our catalog is the direct analogue of iOS's. Verify advertised-name pattern.
- **#77 jring protocol-parity fixes** (`4241d54`) — RingBLEClient + JringSyncEngine +
  new JringClock (+120 lines on the engine). Behavioural BLE fixes for jring; read the diff
  carefully and port as Kotlin rules. **Do not** let this touch the Colmi path mid-sleep-fix.
- **#57 activity-recording redesign** (`8182d8d`) — activity tracking rehaul + post-workout
  vitals backfill (HR+SpO2 only, skips sleep) + wall-clock realtime-HR keepalive (replaces
  the packet-count keepalive that starved when frames failed to parse — Android has the same
  packet-count keepalive in `ColmiSyncEngine.observedRealtimeHeartRate`, worth adopting).
- **#54 MiniMax coach provider** (`cda2e9c`) — another provider in the multi-provider coach;
  mechanical PORT.
- **#64 long-press reorder/hide cards** (`338226a`) — Today/Vitals card management. PORT.
- **#65 coach transparency/context rehaul** (`4a60cfe`) — ADAPT into the Android coach.
- **#56 TK5 ring support** (`440aaf4`) — whole new ring family (SmartHealth protocol) with
  its own decoder/encoder/sync engine + multi-record periodic history. Large; only if we
  want TK5 on Android. Its "periodic multi-record history" is a different sync shape worth
  studying regardless.
- **#61 activity UI sync-alerts** (`39b611f`), **#63 jring "HR" label** (`748e79f`),
  **#42 coach card de-dupe** (`9633fe3`), **#74 measurement-frequency relocation** (`ea3e22d`)
  — small UI/label ADAPTs.

**Skip (iOS-only or non-portable):**

- **#62 Liquid Glass UI** (`1c3808e`) + **`e00c24b` glass fixups** — iOS 26 material; Android
  keeps its own surfaces. SKIP as a visual language (cherry-pick spacing tweaks only if a
  specific screen regressed).
- **#76 default coach to Apple on-device model** (`5c452c8`) — Apple Intelligence, iOS-only
  (a standing intentional divergence). SKIP.
- `6fdfb99` "Remove tasks folder" — repo chore. SKIP.

### 2026-07-06 design-parity sweep (screen audit → port)

A full-screen audit against the iOS `screenshots/` folder found the Android tabs far behind the
iOS #35-era design language. Ported from Swift source (not screenshots) in one sweep:

- **Design system**: `PulseColors.kt` (full AppTheme.swift token set), neutral near-black
  card/background palette in `Theme.kt` (was purple-washed), shared `AppHeader` (PULSELOOP
  eyebrow + greeting + connection/battery status pill on every tab, RootViews.swift), tab order
  Today/Vitals/**Activity/Sleep**/Coach with iOS icons (target, waveform), `CardEyebrow`.
- **Today** (`TodayScreen.kt`, `TodayTiles.kt`): hero insight card, 2-col tile grid (activity
  rings tile, sleep tile w/ stage bar + score, HR/SpO₂/HRV/temp zone-chart tiles, stress/
  fatigue/glucose gauge tiles, dual BP gauge tile — all capability-gated, reusing
  VitalsCardFactory like iOS TodayStore), coach message card. Old redundant stat tiles removed.
- **Sleep** (`SleepScreen.kt`, SleepViewModel range rebuild): Day/Week/Month/Year selector,
  LAST SLEEP hero (duration + big score + rating word), stage-architecture **hypnogram**
  (lanes, glow segments, dashed transitions, time ticks), stage-duration cards, duration
  histogram w/ goal line for aggregate ranges, coach card w/ chips. Day view anchors on the
  4 AM reference night (PulseServices.swift). The old "Recent Nights" list is superseded by
  the Week/Month views.
- **Activity** (`ActivityScreen.kt`, `ActivityMeta.kt`): daily summary card (colored stats +
  rings), "+ Record Activity" pill + history button, TODAY workout rows, weekly-goal widget
  (progress ring + M–S day pills + goal editor dialog). **Record flow fixed**: a type picker
  now actually calls `LiveWorkoutManager.start()` (previously nothing did).
- **Workout summary** (`WorkoutSummaryScreen.kt`, new `activity_detail/{id}` route, wired from
  rows + record-finish): hero header, 3-col hero band (distance/duration/pace or indoor
  variant), stat tile grid, GPS route via the Canvas `WorkoutMapView` (**divergence: no map
  SDK — polyline sketch, no tiles**), per-km splits with relative pace bars (iOS #43 pace
  rounding honored). Not ported yet: HR/SpO₂ charts + zones, elevation, recording-quality card.
- **Coach** (`CoachScreen.kt`): avatar sub-header + new-chat button, suggestion chips,
  iOS bubble styling, markdown-lite (bold/bullets/headings), pill composer with circular
  attach ("+") and send ("↑") buttons. Not ported yet: chat history browser (Android threads
  are in-memory only), inline chart attachments in assistant replies.
- **Vitals cards**: `ZoneLineChart.kt` — zone-split line (via ZoneLineSplitter), reference
  bands, dashed rules, quiet right/bottom axes; replaces the per-card ThresholdBar +
  green SimpleLineChart. **PR #5 interactive detail screens untouched** per the standing
  constraint. BP kept the dual sys/dia line at this point (divergence retired later the same
  day — see the vitals parity pass below).
- **iOS #11 dance** folded in: `ActivityMeta` type order/label/icon + coach tool enums +
  PendingActionExecutor label.

Follow-ups: workout summary HR/SpO₂/elevation/quality sections; coach history + inline
charts; Today per-metric visibility settings (iOS `MetricsService.isVisible` scopes);
goal entity lacks distance/calories columns (rings use iOS defaults).

### 2026-07-06 (later) vitals parity pass — detail screens, BP gauges, demo-data 1:1

Owner request: metric detail pages, the Vitals BP card, and Vitals card order must match iOS;
Today panels must render identically to the iPhone Simulator on the same demo data.

- **Demo data 1:1** (`DemoDataSeeder.kt`): full rewrite to the exact SeedData.swift formulas —
  deterministic sinusoids + fixed extreme days, no RNG (HR 2-hourly/30d + dense hourly 24h with
  152/138 spikes; SpO₂ 6-hourly + 2-hourly 24h with 88/91 dips; daily stress×3 / fatigue / HRV /
  BP / glucose / temperature at iOS clock hours, **including later-today rows** which iOS also
  seeds). Activity dailies use the iOS 90-day formula; sleep nights use the iOS duration formula
  + reference hypnogram pattern, scored through `SleepScore`. Demo device is now CONNECTED at
  82% like iOS (data-display caps only, no BLE ids). Profile matches iOS demo physiology
  (age 25, "not set").
- **Demo full-history windows** (`VitalsViewModel`, `MeasurementDao.hasDemo`): mirrors iOS
  `rangeSamples` — when a kind has demo rows, cards chart the whole seeded history instead of
  24h. With the card charts' fixed 90-min gap rule (`ZoneLineChart.maxGapMs`, iOS
  `maxGap(for: .twentyFourHours)`), sparse daily series (HRV/temp) render as the month-long
  scatter of dots the Simulator shows. Line width 3dp + dot sizes matched to iOS; x labels
  switch to day/month past 48h spans. BP/glucose "latest" now reads the series tail (demo
  seeds future-today rows a `<= now` probe would skip).
- **Vitals screen** (`Screens.kt`): card order now iOS's (HR, SpO₂, **BP, HRV**, Stress,
  Fatigue, Glucose, Temp). BP card replaced the dual sys/dia line (previous divergence,
  retired) with iOS's two 130dp `VitalRingGauge`s + SYSTOLIC/DIASTOLIC captions, zone-colored
  value arcs. Stress/fatigue/glucose cards are now 190dp ring gauges with the
  "subtitle · trend" footer (`VitalGaugeCardItem`), not line charts — `VitalRingGauge` is now
  placed on screens (closes the "not yet placed" note above).
- **Metric detail screens** (`Screens.kt` `VitalDetailScreen`, `VitalDetailViewModel`):
  restyled to iOS MetricDetailView — centered inline title (sentence case), iOS-style
  Today/Week/Month segmented control, chart card (24dp, unit label), LATEST/AVERAGE/MIN/MAX
  single-row stats strip with dividers, REFERENCE ZONES card (engine zones w/ ≥/< range text,
  new `DetailState.engineZones`, HRV baseline-aware), WHAT-THIS-MEANS card (iOS copy verbatim),
  amber warning disclaimer for BP/glucose only. **PR #5 interactivity preserved**: the
  interactive `TrendChart` (scrub/zoom/pan) and the day-paging arrows stay, tucked into the
  chart card header. "Latest" stat is now the window's last reading (iOS semantics).
  Removed: 2×2 stat tiles, trend-read row, ThresholdBar legend, generic disclaimer.
- Verified on-device (`com.pulseloop.debug`) against a fresh iOS Simulator build: Today grid,
  Vitals cards, HR + SpO₂ details show the same values/zones/scatter. Remaining nit: iOS's
  seed fires HR workout spikes only when seeded at an even local hour (2-hour grid hits 18:00)
  — same formula on both platforms, so this matches by construction.

### Porting notes per item

**#17 — Colmi HR enable + activity samples (highest-value bug fix).**
iOS added `ColmiEncoder.autoHeartRate(enabled:intervalMinutes:)` (`0x16 0x02 [0x01|0x02] <interval>`)
and enqueues it at sync startup — without it the ring never records all-day HR history.
Android's `ColmiEncoder.kt` had no such command and `ColmiSyncEngine.runStartup()` never enabled
all-day HR: **same bug confirmed**. Second half: iOS now upserts quarter-hour activity
buckets by unique `startEpoch` and recomputes daily totals from distinct buckets, so re-syncs
can't inflate step counts; includes a one-time migration deleting inflated rows. Android's
`EventPersistenceSubscriber` routed buckets through the live `max()` ratchet with no per-bucket
storage — a *worse* variant: a history day's total collapsed to its single largest bucket.
Ported in `1a4f007`: `autoHeartRate` enqueued at startup, `activity_buckets` table (Room v2→v3
additive migration), sum-of-buckets recompute, and a one-time prefs-gated repair (`DataRepairs`).
Post-review the repair was made non-destructive: it recomputes past days that have synced
buckets instead of deleting all past ring rows (the ring only re-serves ~7 days, so a delete
would permanently destroy older history; an undercounted total beats no total), and the bucket
path now max()-ratchets TODAY's row so a history re-sync can't visibly drop the live count.

**#15 — Sleep midnight splitting.**
iOS keys sleep sessions to the *waking day* with a 7 PM boundary (start ≥ 19:00 → next
calendar day) instead of `startOfDay`, plus a one-time migration merging/deduping already-split
rows (tie-break: totalMinutes, then blockCount, then updatedAt). Android's
`EventPersistenceSubscriber.upsertSleepSession()` used `TimeUtil.startOfDayLocal(ts)` —
**same bug**. Ported as `TimeUtil.wakingDayLocal()` + upsert-key switch (`1a4f007`). The iOS
one-time merge migration was intentionally NOT ported: Android clears `sleep_sessions` on every
connect and rebuilds from the ring, so old split rows disappear on the first sync.

**#11 — Dance activity.**
Add `dance` between `yoga` and `hike` in the activity order; label "Dance", helper
"Studio, cardio, or freestyle", not GPS-capable. iOS icon is SF Symbol `figure.dance` —
pick a Material equivalent. Also add to coach tool activity enums if present.

**#43 — Units consistency.**
iOS made temperature, glucose, distance, and pace honor the imperial/metric preference
everywhere including charts and zone bands (converted to display units before render), and
fixed pace rounding (round seconds *before* min/sec split — 299.85 s must show 5:00, not 4:00).
Android's `UnitSystem.kt` handles distance/temperature basics but glucose is hardcoded mg/dL and
vitals charts don't convert samples or zones to display units. Live Activity portion is iOS-only.

**#41 — Status pill.**
When idle-scanning with no ring linked, show "Disconnected" in danger red (no pulse animation)
instead of a perpetual "Searching…". Applies only if Android's connection state exposes a
scanning/idle-reconnect state distinct from connecting — verify against `RingConnectionState`
before porting; may be a no-op.

**#35 — Vitals dashboard redesign (largest UI item).**
Zone-based color system (8-color palette), `VitalsThresholdEngine` + `VitalsZoneModel` services,
reusable `VitalCard`, concentric `ActivityRingsView`, `VitalRingGauge`, zone-split line charts,
new metric-detail and activity-trends screens, Today screen re-laid-out as tiles.
Android's `VitalsScreen` has simple cards and a 6-color palette; none of the zone engine exists.
This is the main *visual parity* item. Consider splitting into: (a) zone engine + palette,
(b) VitalCard + Today tiles, (c) detail screens.
**Constraint (owner, 2026-07-05): preserve the chart *interactivity* from
[PulseLoopAndroid #5](https://github.com/foureight84/PulseLoopAndroid/pull/5) (merged contributor
work) while adopting the new iOS visuals.** The interaction machinery stays functionally intact —
raw-sample plotting, rolling-24h + day paging, X/Y axis labels, drag-to-scrub hover tooltip,
pinch-zoom + pan-when-zoomed — but its rendering is restyled to the iOS #35 look (zone-split line
colors, reference bands, palette, typography, tooltip/axis skins). Reskin the interaction layer,
don't replace it.
*Ported 2026-07-05 in three commits: `19aac67` (zone engine w/ exact iOS thresholds, 8-color
palette, VitalCard/ActivityRings/VitalRingGauge components, 38 ported tests), `c978b32` (chart
reskin per the constraint above — zone-split gap-broken line via ZoneLineSplitter, 8% reference
bands, quiet trailing axes; PR #5 gesture code untouched), `f756010` (VitalsScreen cards →
VitalCard chrome + iOS metric accents, Today activity rings). Deliberately NOT ported: iOS's
metric-detail/activity-trends screen rebuilds (Android's interactive detail screen is the
divergence we keep, now restyled), the Today tile re-layout beyond the rings, and the
settings screen split. `VitalRingGauge` was not yet placed on a screen at this point (it is
now — Today gauge/BP tiles and the Vitals gauge/BP cards use it). Distance/
calorie ring goals are fixed at iOS defaults until `UserGoalEntity` grows those columns.*

**#19 — Settings redesign + measurement frequency.**
iOS split the monolithic settings view into detail screens and added a per-device
`DeviceMeasurementConfig` (HR interval 5–60 min in 5-min steps; SpO2/stress/HRV/temperature
toggles) that syncs to the ring via `applyMeasurementSettings()`. Android's `SettingsScreen.kt`
is a single ~1,300-line screen with no measurement-frequency UI, though `WearableDriver.kt`
already declares the `.measurementInterval` capability. Port the config entity + ring sync +
measurement screen first; the cosmetic screen-split can ride along with #35. Gate on device
capability. Note: Android's existing personal-info settings (blood sugar calibration) stay —
see intentional divergences.
*Ported 2026-07-05 (`f4bcd47`): config entity + ring sync + Measurement settings card, gated on
the new MEASUREMENT_INTERVAL capability. Deliberately NOT ported: the cosmetic split of Settings
into detail screens (Android keeps its single scroll screen), and profile UI stays hidden for
Colmi (Android divergence — the connect handshake still sends the stored profile when present).*

**#9 / #22 / #31 — Coach provider expansion + images.**
Android's coach is hardcoded to `OpenAIResponsesClient` (`CoachOrchestrator.kt`). iOS now has a
`CoachClientResolver` with OpenAI / Gemini / OpenRouter / Apple-on-device. Port order:
(1) provider abstraction + resolver, (2) GeminiClient (`generateContent`, `functionDeclarations`,
`inlineData` images, `google_search` tool), (3) OpenRouterClient (Chat Completions, `:online`
suffix for web search, `cache_control` prompt caching, privacy routing + provider sort, custom
slug field; DeepSeek preset slug is `deepseek/deepseek-v4-flash` per iOS #40), (4) image
attachments (file-based store, ≤1024 px JPEG @ 70%, `attachmentsJson` on the message entity,
camera/gallery pickers, per-provider payload converters, replay images only on latest user turn).
*Ported 2026-07-05 (`049058d` backend, `4d81a07` UI). Adaptations: no Apple-on-device mode;
per-provider model prefs instead of iOS's shared string; orchestrator takes a per-turn client
factory. Follow-ups: `notifications/CoachNotifications.kt` still hardcodes the OpenAI client
(should adopt `CoachClientResolver`); chat attach is gallery-only (camera + EXIF rotation TBD).*

**#48 — Pairing redesign + onboarding flow.**
iOS replaced the pairing carousel's procedural ring art with real product photos (h59, jring,
yawell-r05/r10/r11 + existing colmi-r02…r12 assets), added brand filter tabs ("All", "Colmi",
"H59", "jring", "Yawell"), capability chips (blurb split on " · "), signal-strength dots
(≥-65 dBm strong/success, ≥-80 medium/warning, else weak/danger) replacing raw dBm, and a
connected-state success animation (ring halo + checkmark spring). It also introduced a 5-step
onboarding flow (welcome → ring → profile → goals → baseline) with persisted progress
(`onboarding.route.v2`, path must start at welcome else reset), skippable ring/profile steps,
and extracted reusable `ProfileDraft`/`GoalDraft` + `ProfileEditorView`/`GoalEditorView` shared
between onboarding and Settings. New model: Colmi R11 (pattern `^R11C_[0-9A-F]{4}$`, reuses the
yawell-r11 image). Android baseline: 2-step OnboardingScreen, flat scan-list PairingScreen,
procedural RingArtView, profile/goals edited inline in Settings. Port: copy PNGs to
drawable-nodpi, add brand/imageRes to `WearableModel`, rebuild PairingScreen (tabs + carousel +
dots + chips + signal dots), OnboardingFlow with DataStore-persisted step path, ProfileDraft/
GoalDraft with unit conversions (in↔cm ×2.54, lb↔kg ÷2.2046226, locale-default units) and the
iOS recommended goal defaults (10k steps / 8 km / 500 kcal / 45 min / 8 h / 4 workouts).

**#49 — Settings rehaul + exact-model identification.**
UI: hero device card on top (ring image 72pt, name, status line + tint, battery pill, relative
"Synced X ago", context action: Connect / Set up a ring / Connecting…, none when connected)
above five grouped sections (DEVICE, AI COACH, GENERAL, METRICS, RESOURCES) of icon+title+
trailing-value+chevron rows with hairline dividers; "Notifications" renamed "Coach Check-Ins"
and gated on coach enabled; developer row unlocked by 7 taps on the version row (2 s window,
escalating haptics, toast). Logic: `advertisedNamePatterns` regexes per catalog model,
`model(advertisedName:)` + `resolve(advertised, selected, family)` priority (BLE name > carousel
selection > family default, jring-only default), `wearableModelID`/`advertisedName` persisted on
Device and carried on `deviceIdentified`; new `deviceForgotten` event clears them. Weight input
became decimal with comma/period-agnostic parsing (`LocalizedDecimalInput`). Family display name
became "Colmi / Yawell ring". Android adaptation: keep content on sub-screens reachable from
grouped rows where iOS does (Android previously kept one long scroll — that divergence is
retired by this port); `DeviceHeroStatus` pure logic + tests port 1:1; UNUserNotificationCenter
prompt maps to POST_NOTIFICATIONS runtime permission.

**#44 — Home-screen widgets.**
Three WidgetKit widgets fed by a JSON snapshot (`widget-snapshot.json` in the app group):
(1) medium fixed Daily Activity (labels/values left, concentric rings right), (2) small
configurable single-metric tile (10 metrics; tile styles rings/sleep/chart/gauge/BP), (3) medium
configurable dual-metric tile. Snapshot: `WidgetSnapshotPublisher` rebuilds on data change
(2 s debounce) + scene-phase edges, ≤48 downsampled chart points, colors as hex + token strings,
hash-skip on unchanged content, background reloads throttled to 1/20 min. Staleness stamp after
45 min; day-rollover shows "No data yet today". ADAPT for Android: Glance widgets + WorkManager/
sync-triggered refresh, snapshot JSON in app files dir (no app-group needed — same process),
config via Glance state/preferences instead of AppIntents. Reuse TodayViewModel/VitalCardFactory
equivalents to build payloads.

**#24 — Coach scheduler crash.**
The iOS bug was `MainActor.assumeIsolated` on a background BGTask queue. No direct equivalent,
but audit `CoachNotificationWorker` / WorkManager paths for the same pattern: UI-state or
main-thread access from a background worker, and Room calls on the right dispatcher.

---

## Already have (Android led or matches — no action)

| iOS PR | Title | Note |
|--------|-------|------|
| [#20](https://github.com/saksham2001/PulseLoopiOS/pull/20) `6fe428f` | Perf re-architecture (event bus, decoupled persistence, memoized today-summary) | Android already built this way: `PulseEventBus` (SharedFlow), `EventPersistenceSubscriber`, `RingSyncCoordinator`, reactive `TodayViewModel` |
| [#32](https://github.com/saksham2001/PulseLoopiOS/pull/32) `3e855ac` | 56ff low-level protocol (user profile, BP calibration, bind handshake, combined measurement, keepalive/watchdog) | This was iOS porting **Android's** work upstream — Android is the origin |
| [#87](https://github.com/saksham2001/PulseLoopiOS/pull/87) `cfa8109` | Colmi quarter-hour activity-bucket offset (up to ~75% step undercount) | iOS adopting **Android's** fix — Android decoded true 15-min slice starts first (see Android-originated fixes) |

## Skipped (iOS-only / docs / CI / release)

| iOS PR / commit | Title | Reason |
|--------|-------|--------|
| [#30](https://github.com/saksham2001/PulseLoopiOS/pull/30) `c42ec85` | Apple on-device coach (FoundationModels) | iOS 26 Apple Intelligence only. Revisit if Android adopts on-device LLM (AI Edge / ML Kit). The anomaly-detection notifications inside this PR could port separately if ever wanted |
| [#38](https://github.com/saksham2001/PulseLoopiOS/pull/38) `cc9a058` | TestFlight release config | iOS release metadata |
| [#47](https://github.com/saksham2001/PulseLoopiOS/pull/47) [#46](https://github.com/saksham2001/PulseLoopiOS/pull/46) [#39](https://github.com/saksham2001/PulseLoopiOS/pull/39) | Release-IPA CI workflow + fixes | iOS CI |
| [#45](https://github.com/saksham2001/PulseLoopiOS/pull/45) [#37](https://github.com/saksham2001/PulseLoopiOS/pull/37) [#28](https://github.com/saksham2001/PulseLoopiOS/pull/28) [#23](https://github.com/saksham2001/PulseLoopiOS/pull/23) | Sideloading guide, iOS-vs-Android refresh, MkDocs site, README updates | Docs |
| [#7](https://github.com/saksham2001/PulseLoopiOS/pull/7) `c9897c9` | OSS setup (templates, SwiftLint, CI) | Repo governance; Android repo has its own |
| [#80](https://github.com/saksham2001/PulseLoopiOS/pull/80) `c1275ad` | Apple Health sync (per-type toggles, workout export, profile import) | HealthKit — intentional iOS-only divergence. Android analogue is **Health Connect**; use this as the reference design if ever wanted |
| [#81](https://github.com/saksham2001/PulseLoopiOS/pull/81) `32dfbe3` | Automated contributor recognition (Action + script + README) | Repo governance; Android repo has its own |
| [#89](https://github.com/saksham2001/PulseLoopiOS/pull/89) `0a8ab4e` | iOS-26 Liquid Glass rendering correctness + Dynamic Type a11y | Glass is an iOS visual language (standing SKIP); portable reactivity bit folds into #88 |
| `25e49fd` `577c5f3` `35d1aa7` `ee42b10` `b3697c0` `0f500fc` | Direct commits: docs/screenshots/tagline/YCBT-spec/Discord/jring-URLs | Docs |

---

## Android-originated fixes (2026-07-06 review pass) — upstream candidates for iOS

A post-port code review of the sync-triage branch found and fixed the issues below on
Android. Several trace back to logic ported 1:1 from iOS, so the same bugs likely exist
upstream — each entry says what changed here and whether iOS should look at it.

**Likely applies to iOS — please check:**

- **Demo seed can destroy real data.** Seeded sleep nights reused the sync engine's
  `"sleep-<wakingDay>"` session ids (ported from SeedData.swift), so a reseed silently
  overwrote up to 30 days of real synced sleep, and the demo clear wiped the sleep tables
  wholesale. Android now keys demo nights under their own `"demo-sleep-<day>"` ids with a new
  `sourceRaw` column on sleep sessions ("ring"/"demo", Room v6→v7), skips days that hold a
  synced session, and seeds/clears only demo-tagged rows. If SeedData.swift shares session
  ids with SleepService's, the Simulator/demo path has the same hazard.
- **Demo device row absorbed a real ring's identity.** All device writes went through
  `current()` (newest `updatedAt` wins), so after a seed the demo device row was the write
  target for a real ring's connect/battery/identity events — and "Clear Demo Data" then
  deleted the paired ring's only row. Android now ranks a real ring above the demo row in
  `current()` and routes ring event handlers through a `currentReal()` that excludes the demo
  row. Worth checking how the iOS demo device interacts with SwiftData's device fetch.
- **Stale sleep stage blocks corrupt re-keyed sessions.** The waking-day re-keying (iOS #15)
  collides with blocks stored under the old start-of-day ids: a rebuilt session's id can match
  a *different* night's legacy blocks, and the stitch-by-startAt merge then produces ~25-hour
  sessions with garbage scores. Android clears `sleep_stage_blocks` together with
  `sleep_sessions` on every connect. If iOS's #15 migration didn't also migrate/purge stage
  blocks keyed by the old grouping, the same corruption can appear there.
- **Activity history slices were collapsed to hour granularity.** The 0x43 decoder mapped the
  quarter-of-day slot index to `hour = idx/4, minute 0`, so up to four 15-minute slices shared
  one bucket key and vanished from day totals (permanent undercount for past days, which have
  no live-total ratchet). Android now decodes true 15-minute slice starts
  (`hour = idx/4, minute = (idx%4)*15`). Check whether ActivityService's bucket keying (iOS
  #17) does the same collapse.
- **Connect handshake overrode ring-side measurement settings.** With no saved measurement
  config (every fresh install/upgrade), the startup wrote ALL-ON defaults — 5-minute all-day
  HR and temperature ON — over whatever the user had configured in the official Colmi app.
  Android now *reads* the ring's auto-HR (`0x16`) and temperature prefs on first connect,
  seeds the persisted config from the replies, and only writes when the ring reports all-day
  HR disabled (keeping the #17 fix: HR history needs it on) — using the ring's own interval.
  iOS #19 pushes its defaults the same way and could adopt the read-then-seed handshake.
- **`reasoning.effort` was sent unconditionally → OpenAI 400s.** The provider store's
  null-means-omit contract was lost at the settings boundary (null coalesced to "medium"),
  so non-reasoning models (gpt-4o, gpt-4o-mini — both in the preset list) rejected every
  coach turn. Android made the setting nullable end to end, added a "Model decides (default)"
  option, and omits the `reasoning` key when unset; summaries now send the user's effort too
  (they silently dropped it, diverging from CoachSummaryGenerator.swift). Check what iOS's
  default effort is for the OpenAI provider with non-reasoning presets.
- **A failed model resolve erased the stored exact model.** `DeviceIdentified` with a null
  `wearableModelID` (re-pair with the carousel on the wrong family) overwrote a previously
  stamped exact model, and the last-model pref was cleared with it — Settings reverted to the
  generic family label. Android now falls back to the stored value on null (DB side) and
  retains the pref when the previous model's family matches the connected device (BLE side).
  iOS #49's resolve has the same null path; check its persistence behavior.

**Android-only (platform-specific, no iOS action expected):**

- `referenceNightLocal` computed "last night" as a fixed −24 h instead of calendar-aware
  `minusDays(1)`, missing the stored day key for up to 4 hours after each DST transition
  (iOS uses `Calendar`, which is already DST-correct).
- Gauge renderers recovered readings by parsing the locale-formatted display string
  (`toDoubleOrNull()` fails on comma-decimal locales the moment mmol/L glucose becomes
  settable); the raw value now travels as `latestValue` on the card state and widget payload.
  iOS passes numerics natively, but the widget-snapshot payload gained a field — mirror it if
  the snapshot schema is ever shared.
- Cleanups: orphaned `SimpleDualLineChart`/`ThresholdBar`/`MetricTile` composables removed;
  zone/accent hexes single-sourced into `PulseColors` (they were triplicated across
  `ZonePalette`/`MetricColors`/`PulseColors`, mirroring their AppTheme.swift home).

---

## Intentional divergences (do not "fix")

**Android-only — keep:**
- Debug/diagnostics export (DiagnosticsExporter, logcat capture, redaction)
- Personal info settings for blood-sugar calculation
- BLE handling differences (Android BLE stack requires different connection/retry strategy)

**iOS-only — not expected on Android:**
- Live Activities (lock-screen workout UI)
- Apple on-device coach (Apple Intelligence)
- HealthKit-adjacent integrations
