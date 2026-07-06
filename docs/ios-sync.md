# iOS → Android Sync Ledger

Tracks which upstream iOS changes (github.com/foureight84/PulseLoop) have been evaluated
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
| **Fork baseline (iOS)** | `600c7a8` — Merge PR #6, 2026-06-20 |
| **Last triaged iOS commit** | `80195a6` — Merge PR #44, 2026-07-06 |
| **Last triage date** | 2026-07-06 |
| **Range covered** | 138 commits / 28 first-parent items, plus #48/#49/#44 (2026-07-06) |

---

## Port queue (needs Android work)

Ordered roughly by value-for-effort. Status: ☐ open · ☑ done.

| # | iOS PR | Merged | Title | Verdict | Effort | Android commit |
|---|--------|--------|-------|---------|--------|----------------|
| ☑ | [#17](https://github.com/foureight84/PulseLoop/pull/17) `26a6075` | 06-24 | Colmi HR enable + activity sample idempotency | **PORT** | M | `1a4f007` |
| ☑ | [#15](https://github.com/foureight84/PulseLoop/pull/15) `eb5a288` | 07-02 | Sleep sessions splitting at midnight | **ADAPT** | M | `1a4f007` |
| ☑ | [#11](https://github.com/foureight84/PulseLoop/pull/11) `a582f7a` | 07-01 | Dance activity type | **PORT** | S | `4aad39a` |
| ☐ | [#43](https://github.com/foureight84/PulseLoop/pull/43) `a280388` | 07-04 | Units consistency (temp/glucose/distance/pace) | **PARTIAL** | M | |
| ☐ | [#41](https://github.com/foureight84/PulseLoop/pull/41) `102aa35` | 07-04 | Status pill: "Disconnected" not endless "Searching…" | **PARTIAL** | S | |
| ☑ | [#35](https://github.com/foureight84/PulseLoop/pull/35) `f0a4aee` | 07-01 | Vitals dashboard redesign (zones, cards, rings, detail screens) | **PORT** | XL | `19aac67`+`c978b32`+`f756010` |
| ☑ | [#19](https://github.com/foureight84/PulseLoop/pull/19) `445be25` | 06-25 | Settings redesign + measurement frequency control | **ADAPT** | L | `f4bcd47` |
| ☑ | [#9](https://github.com/foureight84/PulseLoop/pull/9) `cd62903` | 06-26 | Coach: multi-provider (Gemini) | **PORT** | L | `049058d`+`4d81a07` |
| ☑ | [#22](https://github.com/foureight84/PulseLoop/pull/22) `909c5cd` | 06-26 | Coach: OpenRouter provider (fold in #40 slug fix) | **PORT** | L | `049058d`+`4d81a07` |
| ☑ | [#31](https://github.com/foureight84/PulseLoop/pull/31) `cbb2487` | 06-29 | Coach: image attachments (multimodal) | **PORT** | M | `049058d`+`4d81a07` |
| ☐ | [#24](https://github.com/foureight84/PulseLoop/pull/24) `be6274f` | 06-28 | Coach scheduler thread-safety crash | **ADAPT** | S | |
| ☑ | — | 07-06 | **Design-parity sweep**: iOS dashboard design across all tabs (see notes) | **PORT** | XL | `4aad39a` |
| ☐ | [#48](https://github.com/foureight84/PulseLoop/pull/48) `aff8574` | 07-05 | Pairing redesign: brand tabs, ring product images, onboarding flow, shared profile/goal editors | **PORT** | L | |
| ☐ | [#49](https://github.com/foureight84/PulseLoop/pull/49) `779740b` | 07-06 | Settings rehaul: device hero card, grouped sections, exact-model matching | **PORT** | L | |
| ☐ | [#44](https://github.com/foureight84/PulseLoop/pull/44) `80195a6` | 07-06 | Home-screen widgets (3 widgets + snapshot pipeline) | **ADAPT** | XL | |

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
additive migration), sum-of-buckets recompute, and a one-time prefs-gated repair (`DataRepairs`)
dropping past ring-written daily rows so they regenerate on the next sync.

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
| [#20](https://github.com/foureight84/PulseLoop/pull/20) `6fe428f` | Perf re-architecture (event bus, decoupled persistence, memoized today-summary) | Android already built this way: `PulseEventBus` (SharedFlow), `EventPersistenceSubscriber`, `RingSyncCoordinator`, reactive `TodayViewModel` |
| [#32](https://github.com/foureight84/PulseLoop/pull/32) `3e855ac` | 56ff low-level protocol (user profile, BP calibration, bind handshake, combined measurement, keepalive/watchdog) | This was iOS porting **Android's** work upstream — Android is the origin |

## Skipped (iOS-only / docs / CI / release)

| iOS PR / commit | Title | Reason |
|--------|-------|--------|
| [#30](https://github.com/foureight84/PulseLoop/pull/30) `c42ec85` | Apple on-device coach (FoundationModels) | iOS 26 Apple Intelligence only. Revisit if Android adopts on-device LLM (AI Edge / ML Kit). The anomaly-detection notifications inside this PR could port separately if ever wanted |
| [#38](https://github.com/foureight84/PulseLoop/pull/38) `cc9a058` | TestFlight release config | iOS release metadata |
| [#47](https://github.com/foureight84/PulseLoop/pull/47) [#46](https://github.com/foureight84/PulseLoop/pull/46) [#39](https://github.com/foureight84/PulseLoop/pull/39) | Release-IPA CI workflow + fixes | iOS CI |
| [#45](https://github.com/foureight84/PulseLoop/pull/45) [#37](https://github.com/foureight84/PulseLoop/pull/37) [#28](https://github.com/foureight84/PulseLoop/pull/28) [#23](https://github.com/foureight84/PulseLoop/pull/23) | Sideloading guide, iOS-vs-Android refresh, MkDocs site, README updates | Docs |
| [#7](https://github.com/foureight84/PulseLoop/pull/7) `c9897c9` | OSS setup (templates, SwiftLint, CI) | Repo governance; Android repo has its own |
| `25e49fd` `577c5f3` `35d1aa7` `ee42b10` | Direct commits: docs/screenshots/tagline | Docs |

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
