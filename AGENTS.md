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

See `docs/qring-ble-adoption.md` §5a for the full history and the decompiled source references.

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
- The single-channel contention theory is **plausible but unproven** — no capture has shown a spot
  measure starved by an active history dump. Don't treat it as established; if you suspect it, prove
  it from a capture where the channel is actually saturated during a failed measure.
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
