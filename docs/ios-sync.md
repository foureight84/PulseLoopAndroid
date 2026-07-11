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
| **Last triaged iOS commit** | `e00c24b` — glass-UI fixup, 2026-07-10 |
| **Last triage date** | 2026-07-10 |
| **Range covered** | 99 commits / 15 first-parent items since `80195a6` (2026-07-10) |

---

## Port queue (needs Android work)

Ordered roughly by value-for-effort. Status: ☐ open · ☑ done.

| # | iOS PR | Merged | Title | Verdict | Effort | Android commit |
|---|--------|--------|-------|---------|--------|----------------|
| ☑ | [#17](https://github.com/saksham2001/PulseLoopiOS/pull/17) `26a6075` | 06-24 | Colmi HR enable + activity sample idempotency | **PORT** | M | `1a4f007` |
| ☑ | [#15](https://github.com/saksham2001/PulseLoopiOS/pull/15) `eb5a288` | 07-02 | Sleep sessions splitting at midnight | **ADAPT** | M | `1a4f007` |
| ☑ | [#11](https://github.com/saksham2001/PulseLoopiOS/pull/11) `a582f7a` | 07-01 | Dance activity type | **PORT** | S | `4aad39a` |
| ☐ | [#43](https://github.com/saksham2001/PulseLoopiOS/pull/43) `a280388` | 07-04 | Units consistency (temp/glucose/distance/pace) | **PARTIAL** | M | |
| ☐ | [#41](https://github.com/saksham2001/PulseLoopiOS/pull/41) `102aa35` | 07-04 | Status pill: "Disconnected" not endless "Searching…" | **PARTIAL** | S | |
| ☑ | [#35](https://github.com/saksham2001/PulseLoopiOS/pull/35) `f0a4aee` | 07-01 | Vitals dashboard redesign (zones, cards, rings, detail screens) | **PORT** | XL | `19aac67`+`c978b32`+`f756010` |
| ☑ | [#19](https://github.com/saksham2001/PulseLoopiOS/pull/19) `445be25` | 06-25 | Settings redesign + measurement frequency control | **ADAPT** | L | `f4bcd47` |
| ☑ | [#9](https://github.com/saksham2001/PulseLoopiOS/pull/9) `cd62903` | 06-26 | Coach: multi-provider (Gemini) | **PORT** | L | `049058d`+`4d81a07` |
| ☑ | [#22](https://github.com/saksham2001/PulseLoopiOS/pull/22) `909c5cd` | 06-26 | Coach: OpenRouter provider (fold in #40 slug fix) | **PORT** | L | `049058d`+`4d81a07` |
| ☑ | [#31](https://github.com/saksham2001/PulseLoopiOS/pull/31) `cbb2487` | 06-29 | Coach: image attachments (multimodal) | **PORT** | M | `049058d`+`4d81a07` |
| ☐ | [#24](https://github.com/saksham2001/PulseLoopiOS/pull/24) `be6274f` | 06-28 | Coach scheduler thread-safety crash | **ADAPT** | S | |
| ☑ | — | 07-06 | **Design-parity sweep**: iOS dashboard design across all tabs (see notes) | **PORT** | XL | `4aad39a` |
| ☑ | [#48](https://github.com/saksham2001/PulseLoopiOS/pull/48) `aff8574` | 07-05 | Pairing redesign: brand tabs, ring product images, onboarding flow, shared profile/goal editors | **PORT** | L | `a38ddf5` |
| ☑ | [#49](https://github.com/saksham2001/PulseLoopiOS/pull/49) `779740b` | 07-06 | Settings rehaul: device hero card, grouped sections, exact-model matching | **PORT** | L | `de60096` |
| ☑ | [#44](https://github.com/saksham2001/PulseLoopiOS/pull/44) `80195a6` | 07-06 | Home-screen widgets (3 widgets + snapshot pipeline) | **ADAPT** | XL | `c8efccd` |
| ☐ | [#71](https://github.com/saksham2001/PulseLoopiOS/pull/71) `4fd008a` | 07-10 | Colmi R08 ring support (catalog entry) | **PORT** | S | |
| ☐ | [#77](https://github.com/saksham2001/PulseLoopiOS/pull/77) `4241d54` | 07-10 | jring protocol-parity fixes (RingBLEClient + JringSyncEngine + JringClock) | **ADAPT** | L | |
| ☐ | [#57](https://github.com/saksham2001/PulseLoopiOS/pull/57) `8182d8d` | 07-08 | Activity-recording redesign + post-workout vitals backfill + realtime-HR keepalive rework | **ADAPT** | L | |
| ☐ | [#54](https://github.com/saksham2001/PulseLoopiOS/pull/54) `cda2e9c` | 07-07 | Coach: MiniMax provider | **PORT** | M | |
| ☐ | [#64](https://github.com/saksham2001/PulseLoopiOS/pull/64) `338226a` | 07-09 | Long-press to reorder & hide cards (Today/Vitals) | **PORT** | M | |
| ☐ | [#65](https://github.com/saksham2001/PulseLoopiOS/pull/65) `4a60cfe` | 07-09 | Coach transparency/context rehaul | **ADAPT** | M | |
| ☐ | [#56](https://github.com/saksham2001/PulseLoopiOS/pull/56) `440aaf4` | 07-10 | TK5 ring support (SmartHealth protocol; own sleep decode + multi-record periodic history) | **PORT** | L | |
| ☐ | [#61](https://github.com/saksham2001/PulseLoopiOS/pull/61) `39b611f` | 07-08 | Activity UI sync-alerts bugfix | **ADAPT** | S | |
| ☐ | [#63](https://github.com/saksham2001/PulseLoopiOS/pull/63) `748e79f` | 07-08 | Label jring HR capability as "HR" | **PORT** | S | |
| ☐ | [#42](https://github.com/saksham2001/PulseLoopiOS/pull/42) `9633fe3` | 07-08 | Coach summary owns top card, no Today duplicate | **PARTIAL** | S | |
| ☐ | [#74](https://github.com/saksham2001/PulseLoopiOS/pull/74) `ea3e22d` | 07-10 | Move Measurement Frequency into General → Physiology | **ADAPT** | S | |

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

## Skipped (iOS-only / docs / CI / release)

| iOS PR / commit | Title | Reason |
|--------|-------|--------|
| [#30](https://github.com/saksham2001/PulseLoopiOS/pull/30) `c42ec85` | Apple on-device coach (FoundationModels) | iOS 26 Apple Intelligence only. Revisit if Android adopts on-device LLM (AI Edge / ML Kit). The anomaly-detection notifications inside this PR could port separately if ever wanted |
| [#38](https://github.com/saksham2001/PulseLoopiOS/pull/38) `cc9a058` | TestFlight release config | iOS release metadata |
| [#47](https://github.com/saksham2001/PulseLoopiOS/pull/47) [#46](https://github.com/saksham2001/PulseLoopiOS/pull/46) [#39](https://github.com/saksham2001/PulseLoopiOS/pull/39) | Release-IPA CI workflow + fixes | iOS CI |
| [#45](https://github.com/saksham2001/PulseLoopiOS/pull/45) [#37](https://github.com/saksham2001/PulseLoopiOS/pull/37) [#28](https://github.com/saksham2001/PulseLoopiOS/pull/28) [#23](https://github.com/saksham2001/PulseLoopiOS/pull/23) | Sideloading guide, iOS-vs-Android refresh, MkDocs site, README updates | Docs |
| [#7](https://github.com/saksham2001/PulseLoopiOS/pull/7) `c9897c9` | OSS setup (templates, SwiftLint, CI) | Repo governance; Android repo has its own |
| `25e49fd` `577c5f3` `35d1aa7` `ee42b10` | Direct commits: docs/screenshots/tagline | Docs |

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
