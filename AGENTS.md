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

## Only the client's own connect may rebuild stored data

**Read this before touching `EventPersistenceSubscriber`'s `DeviceStateChanged` branch, or before
adding a family to `preservesSleepOnConnect`.**

`RingConnectionState.CONNECTED` arrives from two unrelated places, and only one of them means a
connection was established:

- `RingBLEClient`'s own connect event — always carries `deviceType` (`activeCoordinator` is set by
  `installDriver`, which runs before the CCCD write that gates CONNECTED).
- `RingEventBridge`, which maps **every** decoder's `RingDecodedEvent.Status` to CONNECTED and never
  sets `deviceType`. These are ordinary device-info replies: jring `0x0C`, LuckRing dev-info, YCBT
  status packets. `runStartup` re-sends them, and `runStartup` is also the ~30-minute background
  sync — so they recur for the whole life of a connection.

The CONNECTED branch clears and rebuilds (unscoped `DELETE FROM sleep_sessions` /
`sleep_stage_blocks` for families outside `preservesSleepOnConnect`). Ungated, every background sync
pass on jring or LuckRing wiped all stored sleep and depended on that same pass re-pulling it —
losing anything past the ring's retention when the pass was interrupted or came back empty.
`isConnectTransition(event.deviceType)` is the gate. **Don't remove it, and don't try to fix this
family-by-family** — `preservesSleepOnConnect` was an attempt at that, and the set of families that
re-assert CONNECTED turned out to be most of them.

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
  how you tell "the monitor is switched off" apart from "this ring lacks the sensor" — the open
  question for stress (`2/47`) and temperature, both 23-sent/0-answered. Send them
  **once per connection**, not per poll pass: `runStartup` is also the ~30-minute background sync.
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
- **Temperature history is `2/22`, not `2/48`.** `q.b(2,48)` is the vendor's `querySleepState`
  (`d1/b.java` line 650); real temp history is `i0.b(day, frameIndex)` = `q.c(2,22,[day,idx])`, the
  same shape as the other timing histories. Its sample layout is still unconfirmed — no non-empty
  capture yet — so the reply stays an ack.
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
- Whenever you touch CRP measure/sync/all-day behavior, hardware-validate with the ring owner
  (zaggash) — and for a "measure broken" report, first get a capture of **several** Measure presses
  with the ring snug and still, to separate a contact failure from a real code bug.
