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
| **Last triaged iOS commit** | `dff0363` — Merge PR #47, 2026-07-05 |
| **Last triage date** | 2026-07-05 |
| **Range covered** | 138 commits / 28 first-parent items |

---

## Port queue (needs Android work)

Ordered roughly by value-for-effort. Status: ☐ open · ☑ done.

| # | iOS PR | Merged | Title | Verdict | Effort | Android commit |
|---|--------|--------|-------|---------|--------|----------------|
| ☑ | [#17](https://github.com/foureight84/PulseLoop/pull/17) `26a6075` | 06-24 | Colmi HR enable + activity sample idempotency | **PORT** | M | `1a4f007` |
| ☑ | [#15](https://github.com/foureight84/PulseLoop/pull/15) `eb5a288` | 07-02 | Sleep sessions splitting at midnight | **ADAPT** | M | `1a4f007` |
| ☐ | [#11](https://github.com/foureight84/PulseLoop/pull/11) `a582f7a` | 07-01 | Dance activity type | **PORT** | S | |
| ☐ | [#43](https://github.com/foureight84/PulseLoop/pull/43) `a280388` | 07-04 | Units consistency (temp/glucose/distance/pace) | **PARTIAL** | M | |
| ☐ | [#41](https://github.com/foureight84/PulseLoop/pull/41) `102aa35` | 07-04 | Status pill: "Disconnected" not endless "Searching…" | **PARTIAL** | S | |
| ☑ | [#35](https://github.com/foureight84/PulseLoop/pull/35) `f0a4aee` | 07-01 | Vitals dashboard redesign (zones, cards, rings, detail screens) | **PORT** | XL | `19aac67`+`c978b32`+`f756010` |
| ☑ | [#19](https://github.com/foureight84/PulseLoop/pull/19) `445be25` | 06-25 | Settings redesign + measurement frequency control | **ADAPT** | L | `f4bcd47` |
| ☑ | [#9](https://github.com/foureight84/PulseLoop/pull/9) `cd62903` | 06-26 | Coach: multi-provider (Gemini) | **PORT** | L | `049058d`+`4d81a07` |
| ☑ | [#22](https://github.com/foureight84/PulseLoop/pull/22) `909c5cd` | 06-26 | Coach: OpenRouter provider (fold in #40 slug fix) | **PORT** | L | `049058d`+`4d81a07` |
| ☑ | [#31](https://github.com/foureight84/PulseLoop/pull/31) `cbb2487` | 06-29 | Coach: image attachments (multimodal) | **PORT** | M | `049058d`+`4d81a07` |
| ☐ | [#24](https://github.com/foureight84/PulseLoop/pull/24) `be6274f` | 06-28 | Coach scheduler thread-safety crash | **ADAPT** | S | |

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
settings screen split. `VitalRingGauge` is available but not yet placed on a screen. Distance/
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
