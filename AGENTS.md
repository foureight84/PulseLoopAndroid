# Agent instructions — PulseLoop Android

Read this before touching ring/BLE hardware code (`app/src/main/java/com/pulseloop/ring/`,
`app/src/main/java/com/pulseloop/wearables/`). Full detail: `docs/qring-ble-adoption.md`.

## Ring BLE protocol work — match the vendor app, not iOS

When porting or fixing a ring's BLE protocol (connect/pairing sequence, GATT characteristic
writes, command framing, notification handling), reference the **decompiled official vendor
Android app** (`decompiled-qring-official/`, `decompiled-jring-offical/`, etc., at the repo
root) and match its actual behavior. Do not port iOS's CoreBluetooth sequencing — Android's
Bluetooth stack behaves differently (pairing/bonding flow, MTU negotiation, background
restrictions), and a straight iOS port has caused real pairing/data-collection bugs before.

## Manufacturer data: Android splits the field, iOS doesn't — put the company ID back

`ScanRecord.getManufacturerSpecificData()` returns a `SparseArray` **keyed by company ID, with the
ID removed from the value**. CoreBluetooth hands iOS the raw block, company ID and all. Every
coordinator was ported from Swift and therefore matches a little-endian company-ID *prefix*
(`TK5Coordinator` `10786501`, `ColmiSmartHealthCoordinator` `1078`, `LuckRingCoordinator` `64ff`,
`RWfitProtocol.MANUFACTURER_HEX_PREFIXES` `d605…`/`d606…`). `RingBLEClient` used to pass
`valueAt(0)` through untouched, so those prefixes could never appear and **every manufacturer-data
fallback in the registry was dead code on Android** — a ring whose name the catalog didn't
recognise matched nothing at all (issue #56, an `Ale-Hop2211 E1C7` YCBT ring). It also read only
entry 0 despite a comment claiming otherwise.

Build `AdvertisementInfo` through `AdvertisementMatcher` (`WearableDriver.kt`), which restores the
company ID and offers **every** entry to each coordinator, registry order still outermost. If you
add a coordinator that matches manufacturer bytes, write the prefix in on-air layout (company ID
first) and cover it in `AdvertisementMatcherTest`.

Same lesson, different field: a name pattern is one convention, so keep it in one constant.
`WearableModel.SMARTHEALTH_NAME_PATTERN` gates two decisions in series — whether
`modelForAdvertisedName` returns a card at all, and whether `ColmiSmartHealthCoordinator` accepts
the name — and the two copies that used to exist drifted into the same bug.

## Colmi/Yawell OS-bonding is a hand-curated allowlist — not "match QRing exactly"

**This is the one rule in this file most likely to get silently reverted by a future "generalize
to match the vendor app" fix. Read it before changing anything with "bond" in the name.**

The real QRing app (`decompiled-qring-official/.../DeviceCmdInit.java`) bonds **unconditionally**
whenever a ring's `0x3C` device-support reply sets `supportBlePair` — no per-model check.
PulseLoop deliberately does **not** copy this. `RingBLEClient.bondActiveDevice()` also requires
`WearableModel.requiresOsBond == true` for the resolved model — currently only `COLMI_R09`,
`COLMI_R11`, `YAWELL_R11`. Every other Colmi/Yawell model (notably the **R10**, which also
reports `supportBlePair`) stays GATT-only on purpose: bonding triggers a real OS pairing dialog
and puts the ring in the phone's paired-devices list, a UX cost not worth paying for a model that
already holds a stable link without it.

**This has already regressed once in production**, in the same release: a 2026-07-19 fix for
issue #29 (R11 stuck on "Connecting") removed the allowlist in favor of QRing's blanket rule,
which fixed the R11 but reopened the R10 pairing-dialog bug that a 2026-07-15 commit had
deliberately fixed by introducing the allowlist. Corrected by restoring the allowlist and adding
R11/Yawell R11 to it by name.

**The rule:** when a new model is confirmed (real hardware, or a specific credible user report)
to need an OS bond, add it to `WearableModel.requiresOsBond`'s allowlist by name. Never widen
the condition to "whenever `supportBlePair` is set" to match the vendor app — that is exactly
the change that caused the regression, and it will cause it again for the R10.

**A driver re-route can silently revoke a bond, too.** `DriverReroute.shouldRerouteToJring` moves a
ring off its selected driver post-connect, and re-resolving the model against the JRING family lands
on the generic `JRING` entry, whose `requiresOsBond` is `false`. Root `AGENTS.md` records one hedged
suspicion — that the **R11**'s full Colmi UART profile (`6e40fff0`/`de5bf728`) *appears* to be gated
behind an OS bond. Unproven, and about one model, but if it holds anywhere then a re-route fired on
a table missing that profile is self-sealing: it prevents the very bond that would reveal it, and
CONNECTED persists the jring family to `LAST_WEARABLE_MODEL_KEY`, so every later reconnect starts
there and the carousel can't undo it. That is why the re-route is scoped to
`scanDetectedType == JRING`: only connections where a generic-"SMART_RING" guess was actually
overridden. Don't widen it to "any driver whose services are missing."

See `docs/qring-ble-adoption.md` §5a for the full history and the decompiled source references.

## Connecting must never delete stored history

**Read this before touching `EventPersistenceSubscriber`'s `DeviceStateChanged` branch.**

**No ring re-supplies more history than its own buffer holds, so the app's copy is the only durable
one.** A connect may delete **nothing at all** — not stored history, and (since the iOS-parity fix)
not demo rows either. This is not a style preference — it was a data-loss bug three times, in three
different shapes (issue #43, the sync-pass variant before it, and the demo-row purge below).

The original design deleted all sleep on connect and re-pulled it, carving YCBT out via
`preservesSleepOnConnect` because YCBT re-asserts CONNECTED mid-history. That premise was false for
everyone: CRP asks `queryHistorySleep(daysAgo = 0)`, and jring calls `makeHistoryQueryCommand()` with
its default of 1 day (`JringDriver.kt:105`), so "delete
everything and ask again" capped stored sleep at a single night — a new night replaced the previous
one instead of joining it. Both the carve-out and the rebuild are gone. What protects a re-synced
day now is `upsertSleepSessionAtomic`, which reconciles one waking day at a time, idempotently, and
re-points legacy mis-keyed blocks itself — the blanket clear's own stated justification.

**Stopping the deletion only stops further loss; it recovers nothing.** A ring holds days the app
has never asked for, and asking is cheap because every reply is self-describing — CRP's sleep frame
carries its own day index in `payload[0]`, which `CRPDecoder.decodeSleep` accepts up to 14, so a
night is dated from the reply rather than from the request, and a day the ring has no record of
simply produces no reply. `CRPSyncEngine.sendSleepBackfill` therefore pulls the prior week **once
per connection** (not per pass — `runStartup` is also the ~30-minute background sync, and this ring
funnels everything through one `fdd2` channel).

jring has the same gap, for different reasons. Its depth is `makeHistoryQueryCommand()`'s default of
1, called with no argument at `JringDriver.kt:105`, against a command that accepts up to 27.
**`RingSyncCoordinator.syncWindowDays` is not that control** — despite its "must match
makeHistoryQueryCommand's default" comment, it has exactly one use, sizing the sync-progress window
in `beginSyncProgress`, and it applies to every family. Don't cite it as a per-family request depth;
that mistake is what deferred this fix once already. Two things do make jring harder than CRP:
`JringSyncEngine.runStartup` has no once-per-connection gate, so a wider `days` re-pulls the whole
span on every ~30-minute background pass rather than once; and `0x10` returns activity *and* sleep
together — there is no sleep-only request — so each extra day costs ~96 activity packets
(15× 1-minute buckets per packet) on top of the night.

Consequence to keep in mind: nothing bulk-deletes real sleep any more, so a Forget followed by
pairing a different ring carries the previous ring's history over. If that ever needs to change,
it belongs on `DeviceForgotten` as a deliberate choice, not as a side effect of connecting.

`RingConnectionState.CONNECTED` also arrives from two unrelated places, and only one is a real
transition:

- `RingBLEClient`'s own connect event — always carries `deviceType` (`activeCoordinator` is set by
  `installDriver`, which runs before the CCCD write that gates CONNECTED).
- `RingEventBridge`, which maps **every** decoder's `RingDecodedEvent.Status` to CONNECTED and never
  sets `deviceType`. These are ordinary device-info replies: jring `0x0C`, LuckRing dev-info, YCBT
  status packets. `runStartup` re-sends them, and `runStartup` is also the ~30-minute background
  sync — so they recur for the whole life of a connection.

`isConnectTransition(event.deviceType)` is that gate. It used to feed `connectPurge`; now that a
connect deletes nothing, `connectPurge` ignores its argument and returns `ConnectPurge.NOTHING`
unconditionally, so `isConnectTransition` has **no production caller left** — only
`EventPersistenceIdentityTest`. It is kept because the distinction is real and gets re-derived
wrongly each time someone needs it, and any future connect-time action wants exactly it. Be precise
about the gate's scope, because it is narrower than it looks: it decides only what a CONNECTED
event may *delete*. The row write below it — `stateRaw = "CONNECTED"`, `lastConnectedAt`, `lastSyncAt` — is
**outside** the gate and still runs for every decoder `Status`, so a jring `0x0C` reply does still
restamp the device row as freshly connected on each sync pass. That is harmless today; it is not
something the gate prevents, so don't cite it as if it were.

**Demo rows are not purged on connect either — match iOS, don't "clean up" for it.** The demo
purge was the last surviving fragment of the original "connect = clear everything and rebuild"
design, and it was gated only on "this is the BLE client's own connect". A paired ring
re-establishes its link constantly — measured at roughly one reconnect per five minutes on a COLMI
R10 — so every one of those re-ran `clearDemo()` and seeded demo data could never survive alongside
a paired ring. Captured live at 09:00:54 with the phone untouched: 772 measurements / 84 activity
days / 36 sleep sessions to zero. **iOS has no connect-time demo purge anywhere** — its only
deletion of seeded rows is user-initiated inside `SeedData`, and it handles the demo/real mix by
*detecting* it (`isDemo`, `source == "mock"`, `DataFreshness.demo`) and adapting the UI. Demo data
coexisting with a real ring is an expected state. Demo rows are retired only from
Settings → Privacy & Data → Clear Demo Data.

`ConnectPurge` now has exactly **one** member, `NOTHING`, and the single-branch `when`s that switch
on it in `EventPersistenceSubscriber` and `EventPersistenceIdentityTest` read as dead code but are
not: they are what makes *widening* what a connect deletes a compile error instead of a one-line
edit. Don't "simplify" them away. Know the limit, though — it only catches an author who routes the
new deletion through the enum; a bare `clearDemo()` dropped into the CONNECTED arm still compiles.
The two tests named below are the actual enforcement.

**Readers must choose between demo and real rows, because nothing separates them any more.** The
purge was also, accidentally, the thing keeping seeded rows out of every unfiltered query. With it
gone the two coexist indefinitely, so `DemoDataPolicy` (`data/DemoDataPolicy.kt`) states the rule:
**real wins** — a reader surfaces demo rows only while the corresponding real series is empty, and
switches to the `*Real` DAO queries the moment the ring has synced anything. Derived values that
are *persisted or exported* — `hrRestingBaseline`, `estimatedActiveCalories`, Health Connect
records — read `*Real` unconditionally, since a demo-derived number outlives the demo data behind
it. When you add a query over `measurements`, `activity_daily`, or `sleep_sessions`, decide which
of those two it is; "it's just a read" was how a seeded 56 bpm night became a real user's auto HR
zone floor.

Two related things worth knowing before changing this area:

- **Nothing bulk-deletes sleep any more, anywhere in the app.** That connect path was the only
  caller, so there is no retention or pruning mechanism at all now — the tables grow without bound
  and a Forget doesn't reclaim them. Fine at current row sizes; a deliberate retention policy is a
  separate piece of work, not something to bolt back onto connect.
- **One narrow delete path survives**, in `reconcileWakingDay`: `if (groups.isEmpty())` drops that
  day's rows. It should be unreachable — `upsertSleepSession` returns early on empty stages, so the
  replacements reaching it are never empty — but it is the one place a *re-sync* can still remove a
  stored night, so check it first if history goes missing again.

Corollary for new protocol work: a reply that merely reports something about the device (firmware,
serial, capabilities) is not a connection event. Give it its own `RingDecodedEvent` — as
`FirmwareRevision` does — rather than hanging it off `Status`.

## Colmi R11 (CRP "Da Rings") — diagnose from the capture, and decode wear state before blaming code

**Read this before changing anything in `CRP*` startup, sync, all-day-monitoring, or history code —
and before assuming a "Measure button broken" report is a code regression. Issue #29, 2026-07-22:
a reported HR-measure regression after enabling all-day monitoring (`c4d61ca`) turned out, on
capture analysis, to most likely be a wear/contact failure — not the code change.**

**How the mis-diagnosis happened (don't repeat it):** the "HR stopped working after build 25"
report *looked* like the all-day-monitoring commit broke it, and a plausible "single-channel
starvation" story was constructed (the R11 does funnel handshake + timing config + `queryAllHistory`
+ on-demand measures through one `fdd2`-write/`fdd3`-notify path). But the capture contradicted it:
during the failed 30 s measure the channel was **idle ~18 s**, the sleep "dump" was 2 frames, and the
ring simply returned no `g1/cmd9` result. The decisive clue was two **`group 3 / cmd 7`** frames that
appear only in the broken capture — which the vendor (`g1/a.java`) decodes as
**`onWearStateChange(bArr[0] > 0)`**, i.e. on-finger / skin-contact detection. `g3c7 [00]` = **ring not
worn**. An optical sensor with no skin contact cannot read HR/SpO₂. This matched the user's own
"it says keep your hand still" and hedged "I feel like I lost HR." The all-day change had no
supporting evidence as the cause.

**Rules / lessons:**
- **Diagnose R11 issues from the rawPackets capture, not from "what changed."** Decode the actual
  `group/cmd` frames against the vendor `g1/a.java` response dispatch before attributing a symptom to
  a recent commit. A known-good "measure button" capture (worn, HR returns ~19 s after `g1/cmd9 [01]`)
  is the baseline to diff against.
- **Wear state = `group 3 / cmd 7`** (`onWearStateChange`, `payload[0] > 0`). Decoded as of the
  wear-state fix: `CRPDecoder` → `RingDecodedEvent.WearingStatus` → `PulseEvent.WearState`, and
  `RingSyncCoordinator` fast-fails an in-flight CRP spot measure (with a "put the ring on" message)
  when it reports not-worn *before* any reading. Gated to CRP — YCBT's wear polarity is unverified.
  A not-worn measure now fails in ~2 s with guidance instead of spinning the full window silently.
- **SpO2 works on the R11 — do not "fix" it by removing the capability.** zaggash's 2026-07-23
  capture (build 26) contains a real reading: `group 1 / cmd 11` payload `0x61` = **97 %**. It is
  slow and contact-sensitive — the successful measure took **48 s** of silence before answering, and
  only 1 of 3 attempts in that session succeeded. A later session where every attempt failed is a
  contact problem, not absent hardware. COLMI's product page lists only a "Vcare VC30F heart rate"
  sensor; that is a marketing page, not a bill of materials, and reading it as proof of no SpO2
  hardware already produced one PR that had to be closed (#40).
- **HR success does not prove contact is good enough for SpO2.** In that same capture HR returned a
  reading **3 seconds before all three** SpO2 attempts — the two that failed and the one that
  succeeded. HR reads fine at contact quality SpO2 cannot use, so never gate SpO2 messaging on recent
  HR. "Put the ring on snugly" is the *correct* advice for an SpO2 failure even when HR just worked.
- **`group 3 / cmd 7 [00]` predicts measurement failure.** It appeared ~4 s into every failed spot
  measure across both captures and never before the successful one, landing ~2 ms before the
  `group 1 / cmd 11 [FF]` no-reading sentinel. Keeping the fast-fail is right: it turns a 60 s dead
  wait into a ~4.5 s failure. Note the ring never emits `[01]` in either capture, so treat it as a
  failure signal rather than a literal wear flag — but its user-facing advice (improve contact) is
  correct either way.
- **Read-backs exist — ask the ring instead of guessing.** `querySupportSpO2Type` (`2/37`) answers
  NOT_SUPPORT / SLEEP_OXYGEN / TIMING_OXYGEN, and the monitor-state queries `2/6` HR, `2/7` HRV,
  `2/8` SpO2, `2/45` stress, `2/21` temp each report the configured interval (`0` = off). These are
  how you tell "the monitor is switched off" apart from "this ring lacks the sensor". Send them
  **once per connection**, not per poll pass: `runStartup` is also the ~30-minute background sync.
  The R100 capture in issue #58 shows what a *useful* answer looks like and what silence means:
  `2/6` HR, `2/7` HRV, `2/8` SpO2 all replied `05` (5-minute interval, enabled) and `2/21` temp
  replied `06`, while `2/45` stress and `2/37` SpO2-type answered **nothing across 22 sync passes**
  — the same ring that also never answers the stress history query `2/47`. On that ring stress is
  absent, not switched off. Note the app still advertises `STRESS` for it, because
  `CRPCoordinator.capabilities` is a static family set, not something the ring confirmed — a
  capability list is not evidence about an individual ring.
- **Group 7 is Gomore, not device info — an opcode read off a decompiled builder is a guess until
  you check its caller.** Firmware was queried on `7/1` and never answered (23 sends, 0 replies),
  which read like ring firmware ignoring a valid vendor command. It wasn't: every builder in `b1/r`
  resolves to a Gomore call in `d1/b.java` (`7/0` querySupportGomore, `7/1` **querySavedGomoreKey**,
  `7/2` queryGomoreEUID, `7/3` sendGomoreKey, `7/13` queryGomoreVersion). The constants had been
  built by pairing `b1/r`'s methods with opcodes *positionally* (a→0, b→1, c→13) — but jadx
  alphabetises method names, so letter order carries no meaning. The same slip mislabelled `3/1`
  (`shutDown`) as `CMD_RESTART`; restart is `3/14`. **Resolve every opcode through its `d1/b.java`
  caller, never by position in the builder class.**
- **Firmware version is `3/3`**, replying with a bare UTF-8 string (`g1/a.i1`:
  `onVersion(new String(payload, UTF_8))`) — `MOY-R1K3-2.1.6` on zaggash's R11, matching the vendor
  app's Firmware-information screen. Decoded into `RingDecodedEvent.FirmwareRevision`, which exists
  because neither older event fits: `FirmwareVersion` carries an `Int` (the jring `0xF6` build), and
  `Status` bridges to `DeviceStateChanged(CONNECTED, …)` — a connection-state event, which a
  firmware string is not. Sibling group-3 queries confirmed from their callers: `3/0` reset,
  `3/1` shutDown, `3/4` firmware hash, `3/6` real-time battery, `3/7` wear state, `3/14` restart,
  `3/22` binding reminder.
- **Temperature history is `2/22`, not `2/48`, and its layout is now CONFIRMED (issue #58).**
  `q.b(2,48)` is the vendor's `querySleepState` (`d1/b.java` line 650); real temp history is
  `i0.b(day, frameIndex)` = `q.c(2,22,[day,idx])`, the same shape as the other timing histories.
  The R100 capture attached to issue #58 is the first non-empty temperature reply anyone has sent
  us, and it matches the vendor parser `e1/m` byte for byte: `[day][frameIndex]` then
  **little-endian 2-byte tenths of a degree Celsius** per 5-minute slot, 72 slots/frame, terminal
  index **3** (four frames/day, like HRV), clamp **28.0–50.0 °C** with anything outside meaning "no
  reading" (`e1/m.a`). Decoded in `CRPDecoder.decodeTimingHistory`. Note what the missing decode
  cost beyond the samples: with no `TimingHistoryFrame` marker emitted, `CRPSyncEngine` never
  advanced the cursor, so the ring was asked for frame 0 on every pass and **never** for frames
  1-3 — 18:00 onward of every day was unreachable.
- **The multi-frame follow-up is hardware-validated** (was open on rc3): HR asked frames (0,0)+(0,1)
  and got both; HRV asked (0,0)…(0,3) and got all four. HR history decoded 27 readings at 00:10–11:35
  local (46–104 bpm), HRV 11 readings (30–56 ms), sleep 12 records across light/deep/REM — so the
  local-midnight anchoring is right and there is no UTC drift.
- The single-channel contention theory is **plausible but unproven** — no capture has shown a spot
  measure starved by an active history dump. Don't treat it as established; if you suspect it, prove
  it from a capture where the channel is actually saturated during a failed measure. It is still the
  reason to keep per-pass traffic lean (see the read-backs above).
- **All-day "timing" vital history is DECODED (build 27, rc3), confirmed against zaggash's rc2
  capture.** Layout (vendor `e1/{f,d,g,l}.java`, group 2): a query `[day, frameIndex]` returns
  `[day][frameIndex][slots…]`, one **5-minute** slot per sample (`w0.b.a()/5`), `0` = no reading.
  **HR (cmd 15) / SpO2 (17) / stress (47)** are **one byte/slot**, 144 slots/frame, terminal frame
  index **1** (two frames = 288 slots = 24 h). **HRV (cmd 16)** is a **little-endian 2-byte** value
  per slot, 72 slots/frame, terminal index **3** (four frames). Clamps: HR 40..200, SpO2 ≤100, HRV
  any positive, stress 1..100. Each slot's time = `localMidnight(today − day) + globalSlot*5min`
  (ring stamps against **local** midnight — a UTC-vs-local offset makes samples look "in the future"
  in a raw capture; that's expected, not a bug). `CRPDecoder.decodeTimingHistory` emits one
  `HistoryMeasurement` per valid slot (persisted idempotently, `source="history"`) plus a
  `TimingHistoryFrame` marker that drives `CRPSyncEngine.handle` to pull the next frame — the
  vendor's sequential `insertBleMessage(<query>.b(day, index+1))`. **Still to hardware-validate on
  rc3:** that the ring answers a direct `[day, index>0]` request (the multi-frame follow-up). In
  the rc2 capture SpO2 came back all-zero and stress didn't reply at all — confirm those all-day
  monitors are actually enabled on his ring before assuming a decode gap.
- **Vendor divergences still open** (verified from the decompile): the vendor *can* read monitor
  state (`queryTimingHeartRateState`), so the engine's "no read-back → force `ALL_ON_DEFAULT` every
  connect" premise is false — match the vendor (query state / apply saved config). And the vendor
  sends spot measures on a priority path (`insertNotificationMessage`) distinct from config/history
  (`insertBleMessage`).
- **The R100 (issue #58) is a second CRP ring, and a more talkative one than the R11.** White-label,
  ships with the same "Da Rings" app, firmware `MOY-R2E3-*`, catalogued as `WearableModel.R100`. It
  advertises a usable name (`R100` / `R100_<hex>`) so it matches at scan instead of relying on the
  post-connect `fdda` re-route. What its capture settles: temperature history decodes (above),
  all-day HR/HRV return real frames, all-day SpO2 returns almost entirely empty ones (2 frames with
  data against 44 empty — the ring records little, the decode is fine), and stress is unanswered on
  both its history and state opcodes. When a CRP question needs a non-empty capture, this ring is
  now the better source.
- Whenever you touch CRP measure/sync/all-day behavior, hardware-validate with the ring owner
  (zaggash) — and for a "measure broken" report, first get a capture of **several** Measure presses
  with the ring snug and still, to separate a contact failure from a real code bug.

## Spot measurements: the ring's own verdict beats our window (issue #59)

**Read this before touching `HRSampleWindow`, `SpotMeasurementGate`, or `RingSyncCoordinator`'s
measure legs.**

A YCBT ring ends a spot measurement itself with **`04 0e [mode, status]`** — `bArr[0]` is the same
mode byte `03 2f` started with, `bArr[1]` is `1` success / `2` failed / anything else cancelled
(vendor `BaseMeasureActivity.onDataResponse`, `decompiled-smarthealth/.../BaseMeasureActivity.java:259`).
The vendor reads **no value** out of that frame; on success it calls `syncData()` and pulls the
reading from history. We decode it the same way: `RingDecodedEvent.MeasurementComplete` carries the
mode and the verdict and nothing else, and `SpotMeasurementGate` honours it by token so a
completion can only end the measurement it names.

What the `Ale-Hop2211` captures in #59 established about how these rings actually behave. None of
it is safe to assume away:

- **Warm-up is not the same as the cached echo.** `HRSampleWindow` drops the first 5 s because the
  ring answers instantly with its last stored bpm. That ring sent nothing for 14 s, then spent ~12 s
  on a *pre-converged plateau* (47 47 47, 46 46 46) before stepping to the real rate (84 … 81) at
  ~26 s. A whole-window median picks the plateau every time: it is both the majority of the window
  and the most self-consistent thing in it. **Never settle a median over everything collected.**
- **Which settle rule a run gets is decided by whether the ring ended it**
  (`HRSampleWindow.settled(ringChoosesLastSample)`, wired to the ring's `04 0e` success verdict on
  *this run*, not to the family's ability to send one). A run that hits our ceiling without a
  `04 0e` is one the ring never finished, so it falls back to the consistency gate whatever the
  family. A ring that ends its own measurement **logs the last plausible
  sample of the run** (vendor band `HEART_RATE_VISIBLE_MIN..MAX` = 40..220), so the app reports
  that. RC-3 feedback on #59 established it directly: three spot measurements captured with no stop
  command, each read back out of the ring's memory before any app touched it, stored value ==
  last streamed sample **3/3** (65, 58, 72). It is a discriminating test here, unlike SpO2 where
  tail and last coincide — the rate is still **climbing** when the ring stops, so every
  tail-weighted rule lands below the ring's answer (94 against the ring's 93 on rc5, and up to
  18 bpm out on those captures). Disagreeing with the ring is not a better number, it is a second
  number: the ring's copy arrives on the next sync and ours yields to it (issue #60). A ring with
  **no** completion signal keeps the tail rule, because nothing chose its last sample — the leg
  just ran out of window. Don't widen the last-sample rule to every family; that is the same
  over-generalisation rc5 had to correct for the ring-copy rule.
- **The ring's stored sample is not a converged reading, and that is not ours to fix.** It stops
  while the value is still rising (34.2 s, 49.2 s, 34.2 s across those three runs), so "which
  sample did the firmware choose" and "is that sample any good" have different answers and only the
  first is the app's. The reporter raised this himself and argued against correcting for it:
  inventing a better number app-side would disagree with the row the ring re-supplies.
- **These rings stream in bursts.** Three samples about a second apart, then **4-6 s of silence**.
  The contact-lost gap was 3 s, so it fired mid-measurement on a ring that was working perfectly and
  aborted the leg before the sensor had converged at all. It is 8 s now. Size this against the
  burstiest cadence in a capture, never against the average one.
- **SpO2 is not heart rate, and the HR rules must not be copied onto it.** RC-1 feedback on #59
  included an instrumented SpO2 capture from the same ring, and every property differs: samples
  start at t+13 s, there is a **24 s** silence in the middle (against HR's 4-6 s), the run lasts
  **50 s** (against 35), and the values *rise to a peak of 99 then decline to 94* rather than
  converging. So: there is no cached echo to discard here, the HR contact-gap would abort it
  outright if it were ever applied to this leg, and HR's tail rule would report the decline.
  `Spo2SampleWindow` settles on the **last plausible sample** (vendor band 70–100), because that is
  what the ring itself logs. Three sources agree (RC-2 feedback on #59): five captures read back
  against the ring's own history matched the last sample 5/5 (a median matched 4/5); the one run
  that collapsed 98 → 87 is stored by the ring as 87, so it was a bad measurement, not a rule
  failure; and the vendor app (`BloodOxygenMeasureActivity.onEvent`) never settles at all — it
  shows each frame and on `04 0e` re-reads the ring's history. Don't put a median or a tail rule
  back: anything cleverer than the ring disagrees with the row the ring will later re-supply.
  There is still no value-based early exit for this leg — the collapsing run's late burst changed
  the answer, so "a value in hand" is not "done"; only `04 0e` (or the ceiling) is.
- **A window ceiling is per family** (`RingSyncEngine.spotHeartRateSeconds`,
  `spotSpo2Seconds`). YCBT HR is 45 s because that ring self-terminates at ~35 s; YCBT SpO2 is
  75 s because five captures put its `04 0e` at t+63.1 s (the default 60 s timed out just before
  it); everyone else keeps 30 s / 60 s. This is only safe *because* the leg ends on `04 0e` —
  raising the default for families that never send one would make every measurement visibly slower
  for nothing.
- **`04 0e` success is not an abort for BP and HRV.** Those legs read no value out of the push
  (the vendor re-syncs history), and HRV has no live-value frame at all, so treating success as
  "stop polling" turned a measurement the ring called successful into a failure. Only the ring's
  *failure* verdict ends them early (`pollForValue`'s abort predicate tests `== false`).

**Collecting a whole run is gated on the ring saying when it is done**
(`RingSyncEngine.signalsMeasurementCompletion`, true only for YCBT). Don't widen it: a leg that
waits for a completion signal no family sends just idles out its window, and the CRP R11 answers a
spot SpO2 with one value after ~48 s of silence and nothing further — waiting past it would turn a
working measurement into a minute-long stare at a progress bar. Families without the signal keep
"first plausible value wins" for SpO2.

**A spot measurement's output is one reading, not a stream.** While one is settling, the
coordinator closes a gate on that kind's live samples and reopens it before publishing the settled
value once, `spot = true`. The gate is a **bus event** (`PulseEvent.LiveSampleGate`), not a shared
flag: `EventPersistenceSubscriber` collects behind the ring on its own dispatcher, so a flag read at
write time let every sample already queued in the bus through the moment it flipped — the event is
ordered against the samples it governs. Before any of this, every converging PPG estimate was stored
as its own heart-rate row stamped with the moment it arrived — a failed measurement left a whole
train of readings that were never the user's heart rate (this is what prompted issue #60). A live
*workout* is the opposite case: there the stream **is** the data, so a measurement that runs during
one neither closes the gate nor publishes a second row for a reading the stream already stored.

**A spot HR measurement is refused while a workout is running.** The live-sample gate is one switch
per kind, so whichever of the two closed it decides whether the other's samples are stored, and the
leg sampled that decision once at the start. Running both meant either the workout's samples were
dropped for the length of the leg, or — if the workout started inside it — the leg's converging
samples were stored as workout rows and its settled value never published. A workout starting mid-leg
now aborts the leg, and the gate is reopened unconditionally in the `finally` because the leg is what
closed it; leaving it closed would silently drop that workout's samples for the rest of the session.
The workout screen is already showing live bpm, so refusing costs nothing. No unit test:
`RingSyncCoordinator` needs a BLE client and has no harness.

## The terminal block is the authority on a history transfer, not the header (issue #69)

`YCBTHistoryTransfer.handleTerminal` used to require the terminal block's packet count to equal the
one the header declared. **The ring contradicts its own estimate one frame later, so that check threw
away whole record types.** On the reporter's `Ale-Hop2211` (COLMI_SMART_HEALTH, firmware 2.04) the
`05 09` header declared **5** packets for 840 bytes and the ring then sent **6** — it packs whole
20-byte records into each frame (7 × 20 = 140) instead of filling it, so the header's
`ceil(bytes / frame)` estimate is one short. Bytes and CRC were correct on every transfer; we
nacked, retried once, and skipped the type.

What that cost: the composite `05 18` record is the **only** source of SpO₂ history on this family —
the dedicated `05 1a` query was sent ten times in one session and never answered once — so hourly
SpO₂ vanished while HR survived from `05 15`. Respiratory rate, HRV, BP, temperature and blood sugar
ride in the same record. It only bites above one packet, which is why the record imported while the
day was young and stopped once it grew, and why it looked like a regression introduced by a release
that had touched nothing nearby.

The vendor doesn't check the count either: at `Sync_Block_Verify` (128) `DataUnpack` reads the two
count bytes into locals it never compares, sizes its buffer from the **terminal's** length, and
accepts on `crc16_compute(...) == crc` alone. So: size the buffer from the header if you like, but
validate against the terminal's byte count and the CRC. The CRC is the integrity check; a packet
count is an estimate, and this firmware proves it can be wrong.

**The general lesson, because this shape recurs here.** A silent "reject and skip" on a
self-consistency check is much harder to see than a decode bug: the frames arrive, the log shows
them as `unknown` (which is what *every* accumulating history frame looks like — 427 sleep frames in
the same export say `unknown` and sleep imports fine), and nothing fails anywhere. When a whole
metric is missing but its neighbours are not, check what the transfer did with the block before
suspecting the parser.

## A complete sleep record retires only its own run (issue #63)

A YCBT ring closes a sleep session when the wearer gets up and opens a new one when they settle,
so one night can be two `af fa` records minutes apart (00:12–03:18 and 03:21–07:24 in the report).
`SleepSegmentation` merges those into one stored row, which is right. What was wrong: a complete
record used to be treated as authoritative for **every block of every row it overlapped**, so on the
next sync pass the second record landed on the merged row and wiped the first record's three hours
with its own stale copy — the night read 4 h 06. `completeSessionSurvivors` now retires only the
contiguous run of blocks the packet's interval sits in (overlapping blocks, plus anything abutting
end-to-start with no gap, in both directions). A shortened re-send still retires its own stale head
or tail; a neighbouring session across even a one-minute gap is untouched. The vendor
(`DataUnpack` case 4 → one `Sleep` row per `af fa` block, `queryByYearToDay` returns a list) never
lets one record displace another. `YCBTHealthRecords.sleep` also resynchronises on the `af fa` magic
now, so a record with a wrong declared length can't swallow the sessions after it.

**A session's `totalMinutes` is time asleep, not the span from its start to its end.** Merging is
what made the two diverge: one row covering both records also covers the minutes between them, so
the reporter's night read 8 h 10 (23:51–08:02) against the 268 + 140 minutes its two records
declared, 6 h 48. The vendor draws the same distinction and the reporter found where —
`SleepActivity:695` builds each history entry as `deepSleepTotal + lightSleepTotal + remTotal`,
carries `wakeDuration` separately, and takes `startTime` from the first record of the day and
`endTime` from the last, never conflating the two. `asleepMinutes(blocks)` (SleepInsights.kt) is
the single definition; `spanMinutes` is the other number. Three things to keep straight:

- It is **"every stage except AWAKE"**, not "DEEP + LIGHT + REM". Same sum on a ring that labels
  its stages (YCBT's sleep tag 4 *is* AWAKE), but `SleepStage.UNKNOWN` is the `else` branch of
  every decoder here — an unrecognised stage byte *inside* a sleep record. Naming three stages
  would drop minutes that were slept, and could zero a night on a ring we decode only partly.
- **Anything positioning against wall-clock time scales by `spanMinutes`.** The hypnogram's x axis
  did use `totalMinutes` and would silently compress and mislabel every tick otherwise.
- **Stored rows were repaired once** (`DataRepairs.repairSleepDurationsIfNeeded`, prefs key
  `sleepAsleepMinutesRepair.v1`), because ring history only reaches back about a week and a
  re-sync would leave older nights reading the old way forever. It recomputes from each session's
  own blocks and skips a session with none rather than zeroing it — `byDay` and `earliestDay` both
  filter `totalMinutes > 0`, so a zero hides the night.

**A zero-length segment must not shadow a real one.** The vendor de-duplicates segments on
`sleepStartTime` (`DataUnpack` case 4 keeps the first it sees) and the ring emits zero-length
segments, so a zero-length segment sharing a start with a real one used to take its place and drop
it. All four duplicate starts in the reporter's thirteen-record night dump are exactly that — a
zero-length LIGHT ahead of a real 76–105 s segment — and since `placeStages` reads an unclaimed
minute as wake, the dropped minutes surfaced as wake instead. The vendor has the same de-duplication
and does not care, because its headline comes from the header's own totals rather than from the
segment array; ours is counted off the timeline, so it does. Zero-length segments are skipped before
the de-duplication now.

**The record's header declares its own time asleep, and it agrees with the timeline.** When
`deepSleepCount` (+12) reads `0xffff`, the three following `u16`s are **seconds**, in the order
`rapidEyeMovementTotal` (+14), `deepSleepTotal` (+16), `lightSleepTotal` (+18) — REM first, not
deep — and `wakeDuration` is the summed length of the `0xf4` segments (`DataUnpack.java:1736-1805`).
Otherwise +14 is `lightSleepCount`, deep/light are minutes×60, and REM is absent. On the reporter's
dump `deepSleepCount` is `0xffff` on 13/13 records and deep+light+rem agrees with the timeline we
count to within a minute on 12 of 13. So the counted timeline is sound; if a headline ever needs to
be independent of placement and rounding, the declared totals are there. Note the record length at
+2 is **bytes, not minutes** — it reads plausibly as a minute count (244, 164, 268, 140) and was
misread that way for several rounds of this issue.

**A complete record retires only what its interval overlaps — abutting blocks are left alone.** The
rule used to grow its run across any block meeting it end-to-start, so that a shortened re-send could
retire its own stale tail. But a block carries no record identity — `sessionId` is the *merged* row,
shared by every record of the night — so "my stale tail" and "the neighbouring record" are the same
shape from the same side, and two records meeting with no gap read as one run: the second wiped the
first. The reporter's ring closes one record and opens the next 33 seconds later, so whether the two
round to the same minute is a coin toss, and losing it costs a whole session. The trade is a stale
tail surviving a genuine shortening, which is minutes rather than hours and rarer than it was now
that a re-send reproduces the record's declared bounds instead of a drifted end. Carrying the
originating record's start on each block would allow both, and is the fix if the tail ever bites.

Still open, and a fair ask: showing a split night's two records **separately** as well as merged.

## Live workout HR on Colmi is a sport session, not an HR stream (issue #64)

The QRing app never touches the realtime-HR commands during an activity. `SportRunningActivity`
sends `PhoneSportReq.getSportStatus(1, sportType)` = `0x77 01 <type>` on entry and consumes the
ring's own unsolicited `0x78` telemetry (`DeviceNotifyRsp`: `[dataType][status][durMin×2][bpm]
[steps×3][metres×3][cal×3]` after the opcode) until `0x77 04`. No timer, no keepalive — the ring
drives the cadence, which is the near-constant LED and ~10 s readings the reporter sees there.
`ColmiSyncEngine.startWorkoutHeartRate` does the same. Rules that matter:

- **Don't re-send the start mid-workout.** The coordinator restarts the stream after every spot
  measure; on this path that must be a no-op or the ring's own sport record resets.
- **Silence gets one resume (`0x77 03`), then the session is given up** for the plain HR stream
  (`sportWatchdogTick`), as it is when the ring rejects `0x77` outright (the error-flag reply).
  Those two are sticky for the engine's life (`sportRejected`), like the `0x1E` refusal — the ring
  has shown it will not run a sport session at all. **A `0x78` status 3 is not one of them**: it is
  the vendor's own "this session finished" push, which its running screen answers by closing the
  screen. It ends the session for the rest of that workout (`sportEndedByRing`) and the next
  workout starts a fresh one; making it sticky would let one ring-side timeout cost every later
  workout the protocol this issue exists to add. The old `0x1E` → `0x69` path is unchanged
  underneath and is what a fallback lands on.
- `0x77` on the command channel is `PhoneSportReq`; the same number as a big-data *action* is
  interval temperature on the other characteristic. They are unrelated.
- **Every `0x78` frame decodes to a `SportTelemetry` event first** (kind `sport_telemetry`, in the
  redactor's masked set), with the bpm as a separate `HeartRateSample` only when plausible. A
  warm-up frame has bpm 0 but still carries live steps, distance and calories; returning nothing
  for it made it `unknown` and exported it in clear — the decode-gap-becomes-privacy-gap failure
  the diagnostics section below warns about.
- Untested on hardware as of this note: the reporter (issue #64, Colmi R09) has the ring.

## Deleting a reading needs a tombstone, not just a DELETE (issue #60)

History measurements are keyed `history:<kind>:<timestamp>` and written with `upsert`, on purpose:
re-syncing a day the ring still holds must update the same row rather than duplicate it (see the
measurement-duplicate bug). That idempotence is also what would undo a deletion — delete the row and
the next sync writes it straight back, with nothing failing anywhere.

So `measurement_deletions` (v24) remembers the deletion, `EventPersistenceSubscriber.upsertUnlessDeleted`
is the single gate every deterministic-id write goes through, and `MeasurementDeletion` owns the two
rules callers must not have to remember: tombstone anything regenerable, and delete a blood-pressure
reading as both of its rows. A live reading's id is a fresh UUID nothing regenerates, so it is
deleted without a tombstone — `MeasurementDeletionDao.record` applies that split, and
`MeasurementDeletionTest` guards the id-prefix agreement that makes it work.

Tombstones ride in the archive (`PulseArchive.measurementDeletions`) because a restore wipes every
table first; without them a backup round trip would forget the deletions while the ring still holds
the days behind them.

**The ring owns a spot reading it logged itself.** RC-1's doubled rows (two HR rows per
measurement, same minute, 79/82) are the ring **logging the spot reading into its own history** —
confirmed on RC-2 by reading five SpO2 runs back out of the ring's memory at the exact times they
were taken — which a later history sync imports as a `history:<kind>:<ts>` row next to the UUID row
we stored for our settled value. Our row is stored with `sourceRaw = "spot"`, and
`EventPersistenceSubscriber.adoptRingsCopy` deletes it when a history sample of the same kind lands
within 90 s: the ring's row wins because it is the one that regenerates on every sync (so it is the
one a tombstone can hold down).

**Only a reading the ring itself completed may take part, and the gate is at write time.** A row is
marked `"spot"` only when the ring reported *that run's* success (`04 0e`, carried on the event as
`ringWillLogIt`, which `RingSyncCoordinator` sets from the run's own verdict — a ring that ends a
measurement with its own verdict is one whose vendor app reads the value back out of history).
Everything else stores a plain `"live"` row exactly as before, with nothing that could delete it.
This is per run, not per family: a YCBT run that times out at our ceiling with no `04 0e` was
never logged by the ring, and marking it `"spot"` would let the next sync delete it in favour of an
unrelated all-day grid sample. A history sample the user has tombstoned adopts nothing either — it
is re-sent on every sync, and letting it retire spot rows would delete a retaken reading each time. Do not widen this to all families: CRP and Colmi record all-day
HR/SpO2 on a **five-minute grid**, so with a ±90 s match window most spot measurements would have an
unrelated grid sample within reach and the user's own reading would be deleted in favour of it.

**A deleted `spot` row needs a range tombstone, because its id is a UUID.** The split above —
tombstone the regenerable ids, delete the UUIDs outright — is right for a `"live"` row and wrong for
a `"spot"` one: a `"spot"` row exists *precisely because* the ring logged that measurement itself and
will hand it back under a `history:` id on the next sync. Tombstoning by id alone let the ring's copy
walk straight in, so the reading came back and had to be deleted twice. The ring stamps its log to
the minute rather than to our settled instant, so the tombstone cannot name the id it must suppress:
`MeasurementDeletionDao.record` writes a `spot:<kind>:<ts>` row instead and `isSpotDeleted` matches
it the way `adoptRingsCopy` matches the pair it reconciles — same kind, within ±90 s. A measurement
retaken inside that window inherits the suppression: it is stored and displayed (it is our own row,
not a history write) but never adopts the ring's copy. That is the same ±90 s ambiguity
`adoptRingsCopy` already carries, and it fails towards keeping a reading the user asked for.

**Known limit, worth stating when a user asks:** a reading already exported to Health Connect stays
there. The export doesn't retain HC record ids, so there is nothing to delete against.

## Deleting an activity bucket needs the tombstone *and* the day's deficit (issue #70)

`ActivityBucketDeletion` is the tombstone rule applied to intraday step blocks, and it has one more
thing to get right than `MeasurementDeletion` does. **A tombstone guards the history path; today's
number does not come from the history path.** The ring also pushes `PulseEvent.ActivityUpdate`
carrying its own *cumulative* count for the day — seconds apart, and again on every reconnect — and
`EventPersistenceSubscriber.upsertActivityDaily` ratchets the day up against it with `maxOf`. That
counter still includes the deleted block, so a restated 7,000 went back to 8,000 on the next frame:
the delete looked like it worked and then silently undid itself, which is the exact failure the
restate-without-the-ratchet rule exists to prevent.

So the day remembers what it removed. `ActivityDailyEntity.deletedSteps` / `deletedDistanceMeters`
(v27) are subtracted from every later cumulative reading before the ratchet
(`ActivityBucketDeletion.ratchetAgainstRing`, which lives with the deletion rules rather than in the
write path — a reader looking at the ratchet has no reason to suspect a deletion changed what the
ring's counter *means*). They ride the archive for the same reason the measurement tombstones do.
This is also why the feature is only offered on today: it is the only day whose counter is still
moving.

**Calories are dropped, not corrected.** A bucket carries steps and distance and no calorie field,
so there is nothing to subtract from the ring's own daily figure — and that figure demonstrably
counted the block the user removed. The day's `calories` is cleared instead, which makes
`DailyCalorieEstimator.deviceReportedCalories` fall through to the app's own estimate, recomputed
from the surviving buckets at deletion time rather than at the next completed sync. An estimate
consistent with the restated day beats a device figure known to be wrong. `activeMinutes` is not
touched: it is credited by `ActivityRollup` from workouts, not from step buckets.

## A derived metric must say it is derived (issue #67)

`DerivedStress` computes a stress figure from HRV for rings whose hardware never reports one — the
R100 answers neither the stress history query nor its monitor-state read-back (22 sends, 0 replies,
while every other state query on that ring answered), so stress there is absent rather than switched
off. The app advertised it anyway, because a capability list is a static per-family constant and not
something an individual ring confirmed, and the user got a card that could never fill.

**The rule that matters more than the formula: it is labelled wherever it is shown.** The card reads
"Estimated from HRV — your ring doesn't measure stress", and `VitalsState.stressIsDerived` carries
the fact so no future surface can render it as a measurement by accident. A derived figure presented
as measured would be worse than the empty card it replaces — a user comparing it against the vendor
app's number is entitled to know which of the two they are looking at.

It is scored **against that user's own recent HRV**, not a population: HRV varies several-fold
between individuals, so an absolute cutoff labels whole people permanently stressed or permanently
calm. Median and median-absolute-deviation rather than mean and standard deviation, because ring
HRV history is full of obvious outliers and one of them must not redefine the scale. It returns null
below twelve baseline readings rather than a default, for the same reason the battery estimate in
#65 refuses to answer: an unearned number on a health screen is read as a measurement.

Derived stress only fills in where the ring returned **no** stress at all. It never overwrites or
blends with hardware readings.

**"No stress at all" is a question about the ring, so ask it of the whole history.** Gating on the
24 h chart window instead meant an empty window decided it, and windows go empty for ordinary
reasons — the monitor switched off for a day, a ring re-paired this morning, a quiet night. A ring
that does measure stress then showed a derived number captioned "your ring doesn't measure stress",
which is a false statement about that user's hardware. The gate is
`measurementDao().hasReal(STRESS)`.

**Labelled wherever it is shown means the chart too.** The figure reaches three surfaces — the
Vitals card, the Today tile, and the `vitals/stress` detail chart — and the detail screen builds its
own series straight from Room rather than from `VitalsState`, so it needs the derivation wired in
separately (`VitalDetailViewModel.derivedStressIn`) and carries `DetailState.isDerived` into the
same amber disclaimer card BP and glucose use. Two related traps: a derived score has no "0 means
nothing measured" sentinel, so the card's `>= 10` floor must not be applied to it (a genuinely calm
day scores below 10), and the scores skip the first readings for want of a baseline — so they
cannot be zipped positionally onto the HRV series. `DerivedStress.scored` returns each score with
the index of the reading behind it for exactly that reason.

## Diagnostics masking keeps the routing header (issue #58)

`DiagnosticsRedactor.maskPacketHex` masks a health frame's payload but keeps the leading bytes that
say *which* record it is — 6 for CRP (`FD DA 10 len group cmd`), 4 for YCBT, 1 elsewhere. Masking
from byte 1 made every health frame in a report indistinguishable, which is why issue #58's capture
could not answer whether an all-day SpO₂ reply carried samples. Those header bytes are the same ones
the app writes when it *asks* for the record, and outbound queries are exported unmasked, so keeping
them costs no privacy.

The inverse failure is worth remembering too: CRP temperature frames were exported **with their
values intact**, because an undecoded frame fell through to `command_ack`, which isn't in
`HEALTH_KINDS`. A decode gap silently became a privacy gap. When you add a decoder for a frame that
carries physiological values, check that its `decodedKind` is one the redactor masks.

**The header length must come from the packet's own family, and a half-assembled frame has no header
at all.** Two further shapes of the same failure, fixed together:

- Masking used to use the **connected** ring's family for every stored packet, so a report exported
  after switching rings masked the old family's frames with the wrong header length — and CRP's is the
  longest, so a Colmi frame masked as CRP kept five bytes of samples. `raw_packets.deviceTypeRaw`
  (v25) records the family at capture time (`PulseEvent.RawPacket.deviceType`), and a row from before
  it existed masks from byte 1: less useful, never wrong in the direction that leaks.
- A CRP reply spanning several notifications only decodes when its last chunk lands, so every chunk
  before that reached the log as `unknown` — which is deliberately exported **whole**, because control
  and pairing frames decode to nothing and are what most connection reports are taken for. Half an
  all-day health reply left in clear that way. `RingDecodedEvent.FramePending` names a chunk instead,
  and the two cases mask differently: `frame_start` keeps its family's header (it is there, and it is
  what identifies the reply), `frame_chunk` keeps byte 0 and nothing else, because the middle of a
  frame is payload from byte 0 on.
