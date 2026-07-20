# QRing BLE Stack vs PulseLoop — Comparative Analysis & Safe-Adoption Guide

**Audience:** an AI agent (or engineer) working on either the **Android** app
(`foureight84/PulseLoopAndroid`, this repo) or the **iOS** app
(`foureight84/PulseLoop`, the root repo). The two apps are behavior-parity ports of each
other, so every rule here applies to both — file-name mappings are in
[§9 Porting to iOS](#9-porting-to-ios).

**What this documents:** how the official Colmi **QRing** app (decompiled at
`decompiled-qring-official/` in the iOS repo root) drives its rings over BLE, how PulseLoop
differs, and exactly how to adopt QRing's behavior **without breaking PulseLoop's own
features**. It is the design record behind the fix on branch
`fix/colmi-r09-autohr-and-pairing`.

> **TL;DR** Two user-reported bugs, both traced to PulseLoop sending *less* than QRing:
> 1. **R09 heart rate only measures on button press** → PulseLoop sent a truncated 4-byte
>    `0x16` HR-settings write; newer RT-series firmware needs QRing's full 7-field record.
> 2. **R02/R09 "cannot pair" / stuck on "Connecting"** → PulseLoop never created an OS
>    bond (QRing does, gated on the ring's `supportBlePair` capability bit), and its
>    connect watchdog had no timeout on a *first* pairing.

---

## 1. The rings and who speaks what

Colmi/Yawell rings (R02, R03, R06, R09, R10, …) all speak **one** wire protocol — the
Oudmon/"Jxr35" protocol. QRing talks to them through the `com.oudmon.ble` SDK. PulseLoop
reimplemented the same protocol from scratch (cross-checked against `colmi_r02_client` and
GadgetBridge). PulseLoop *also* supports a second, unrelated family — **jring / "56ff"** —
which is NOT a Colmi ring and must never be affected by Colmi-specific changes.

| | Colmi family (R02/R03/R09/R10) | jring / 56ff |
|---|---|---|
| PulseLoop driver | `ColmiDriver` / `ColmiSyncEngine` / `ColmiEncoder` / `ColmiDecoder` | `JringDriver` |
| Framing | 16 bytes: 15 content + 1 checksum (`ColmiPacket.frame`) | identity / 20-byte |
| Official app | QRing (`com.oudmon.ble`) | (other) |
| Bonding | **Only R09/R11 — see §5a**, not "whenever `supportBlePair`" (that's QRing's rule, not ours) | No (unencrypted) |

**Key insight:** R09 is not a different protocol from R02 — it's the same opcodes on newer
firmware (`RT09_*`) that is *stricter* about payload completeness and *expects* an OS bond.
PulseLoop maps every Colmi model to one internal type `COLMI_R02` and one code path; there
is no per-model command branching, and we did **not** add any. The fixes are firmware-safe
supersets, not model forks.

## 2. Wire framing (identical on both apps — verified)

QRing `BaseReqCmd.getData()` builds `[opcode][subData…]`, zero-pads to
`Constants.CMD_DATA_LENGTH` (16), and writes a 1-byte additive checksum in the last byte.
This is **byte-for-byte** what PulseLoop's `ColmiPacket.frame` does. So any QRing command
translates to PulseLoop as: *return `[opcode] + subData` as the logical payload; the driver
frames it.* No framing work is ever needed when porting a QRing command.

Decompiled refs: `sources/com/oudmon/ble/base/communication/req/BaseReqCmd.java`,
`.../req/MixtureReq.java`.

## 3. Command reference (the opcodes this analysis touched)

| Opcode | Name | QRing class | PulseLoop `ColmiCommandID` | Notes |
|---|---|---|---|---|
| `0x16` (22) | HR all-day settings | `HeartRateSettingReq` | `AUTO_HR_PREF` | **7-field write** — see §4 |
| `0x2C` (44) | SpO₂ auto | `BloodOxygenSettingReq` | `AUTO_SPO2_PREF` | simple on/off pref |
| `0x36` (54) | Stress auto | `PressureSettingReq` | `AUTO_STRESS_PREF` | simple on/off pref |
| `0x38` (56) | HRV auto | `HRVSettingReq` | `AUTO_HRV_PREF` | simple on/off pref |
| `0x3A` (58) | Temp auto | (settings) | `AUTO_TEMP_PREF` | extra `0x03` framing byte |
| `0x3C` (60) | **Device support / capabilities** | `DeviceSupportReq` / `DeviceSupportFunctionRsp` | `DEVICE_SUPPORT` (**added**) | carries `supportBlePair` — see §5 |
| `0xBC` (188) | Big-data channel | `LargeDataHandler` | `BIG_DATA_V2` | **read/sync only** — NOT an enable switch |

**Trap for the next investigator:** `0xBC` has `ACTION_Interval_Heart_Rate = 0x75` etc.
These *download historical* per-interval samples; they do **not** enable monitoring. The
enable switch is `0x16`. Don't chase the big-data path for auto-HR.

## 4. Auto-HR — root cause and fix

### What QRing sends
`HeartRateSettingReq.getWriteInstance(detect, interval, hrStart, tooLow, tooHigh, tooSwitch)`
→ subData `{2, enable(1/2), interval, hrStart, hrTooLow, hrTooHigh, hrTooSwitch}`
(7 fields). Framed: `16 02 <enable> <interval> <hrStart> <tooLow> <tooHigh> <tooSwitch>`.

Semantics, from `HeartRateSettingRsp.readSubData`:
- `enable`: **`0x01` on / `0x02` off** (not `0x01`/`0x00`).
- `interval`: sampling minutes.
- `hrStart` (a.k.a. `startInterval`): defaults to **5** when 0 (a secondary interval, *not*
  a time-of-day window — there is no matching "end" field).
- `tooLow` / `tooHigh`: HR alarm thresholds; **0 = unset**.
- `tooSwitch` (a.k.a. `mainSwitch`): the HR-**alarm** enable. **Not** the monitoring master
  switch — QRing users with alarms off still get all-day HR, so `0` here is fine.

### What PulseLoop sent before (the bug)
Only the first 4 bytes: `16 02 <enable> <interval>`. Old R02 firmware accepts this and
enables all-day HR. **Newer RT-series firmware (R09 `RT09_*`) ACKs it but never arms the
background sampler** — so HR is only captured on a physical button press. This exactly
matches the diagnostics: the app sent `16 02 01 05` on connect, yet no background HR was
recorded.

### The fix (implemented)
`ColmiEncoder.autoHeartRate` now emits the full 7-field record with the four trailing bytes
**zeroed** (= "no HR alarms configured", precisely what QRing sends when the user hasn't set
alerts):
```
16 02 <01|02> <interval> 00 00 00 00
```
- **Zero regression risk on R02:** QRing sends this same fuller record to R02s, which work.
- **`enable`/`interval` bytes and their positions are unchanged**, so the custom-interval
  feature (§7) is preserved — see that section for why.

Files: `ColmiEncoder.kt::autoHeartRate`. Decoder `ColmiDecoder.decodeAutoHRPrefRead` already
reads only `v[2]`/`v[3]`, so it tolerates the longer reply unchanged.

> **Confidence & validation:** this is the highest-confidence fix available without R09
> hardware — it makes PulseLoop send exactly what the *known-working* official app sends.
> If a future test with an R09 shows HR still off, the next hypothesis to check is whether
> the ring's `0x16` **read reply** parses correctly on RT09 (byte offsets), because a
> misparse would make the seeding logic believe HR is already on and skip the enable. See
> `ColmiSyncEngine.handleRawNotify`.

## 5. Pairing — root causes and fixes

PulseLoop's `RingBLEClient` had already adopted most of QRing's connection discipline
(`connectGatt(…, autoConnect=false, TRANSPORT_LE)`, `refresh()`+`close()` teardown, a
reconnect watchdog that alternates direct-connect vs scan-then-connect by `count % 3`). Two
gaps remained.

### 5a. No OS bond (the main pairing defect)
**QRing bonds, gated on a capability bit.** After reading the device-support response it does:
```java
// DeviceCmdInit.java
if (deviceSupportFunctionRsp.supportBlePair) {   // = responseFrame[2] & 0x08 (see note)
    BleOperateManager.getInstance().bleCreateBond();   // BluetoothDevice.createBond()
}
```
`bleCreateBond()` (`BleBaseControl.java`) calls the standard `createBond()` only when
`getBondState() == BOND_NONE`. R09 firmware sets `supportBlePair`; that's why the ring shows
in the phone's paired-devices list under QRing and holds a stable link. PulseLoop connected
GATT-only and **never bonded** — so the ring was absent from the OS paired list and the link
was fragile (connects once, then can't re-sync; recovery needed "forget + toggle Bluetooth").

**Why PulseLoop can't copy QRing's gate verbatim:** PulseLoop derives Colmi capabilities from
a **hardcoded static set** (`ColmiCoordinator.capabilities`), not from the ring's response —
it never parsed `0x3C`. So the fix had to *teach PulseLoop to read `0x3C`* and route the bit.

**The fix (implemented) — the "clean `supportBlePair` gate":**
1. `ColmiCommandID.DEVICE_SUPPORT = 0x3C` + `ColmiEncoder.deviceSupport()` (opcode only,
   no sub-data — matches QRing's `DeviceSupportReq`).
2. `ColmiDecoder.decodeDeviceSupport(frame)` → `ColmiDeviceSupport?`: `supportBlePair` =
   `frame[2] & 0x08` and `supportIntervalTemp` = `frame[9] & 0x80`, or `null` if it isn't a
   `0x3C` frame. **Fail-safe:** a wrong guess returns `null` → no bond → today's behavior,
   never a crash. **OFFSET NOTE (bug fixed post-adoption):** QRing's `QCDataParser` strips
   the opcode (and checksum) before any rsp class runs — `acceptData(copyOfRange(bArr, 1,
   len-1))` — so `DeviceSupportFunctionRsp`'s `bArr[1]` is FULL-frame byte **2**, not 1.
   The first port read `frame[1]`, a byte QRing never parses, so the bond gate keyed off
   undefined data.
3. `ColmiSyncEngine.runStartup` enqueues `deviceSupport()`; `handleRawNotify` decodes the
   reply *before* the config-seeding guard and, if `supportBlePair`, invokes a new
   `onBondRequested` callback.
4. `RingSyncEngine.setOnBondRequested(cb)` (new interface hook, default no-op) is wired in
   `RingBLEClient.installDriver` to `bondActiveDevice()`, which calls
   `device.createBond()` when `bondState == BOND_NONE`.

**Why this is safe and correctly scoped:**
- **Colmi-only:** only `ColmiSyncEngine` ever fires the callback, and only when the ring's
  own reply sets the bit. jring/56ff is untouched.
- **R02-safe:** an older R02 that doesn't advertise `supportBlePair` simply won't bond →
  identical to today. (Corollary: if some R02 *needs* a bond, this alone won't add one for
  it — that ring relies on 5b instead.)
- **No connect-time race:** the bond fires when the `0x3C` reply arrives *during startup*
  (post-CONNECTED, post-discovery), so it does NOT race `requestMtu()/discoverServices()` —
  the original reason bonding was removed. The `0x3C` reply also lands while the rest of the
  startup handshake (battery/pref reads, seeding writes, first history-sync request) is still
  queued or in flight, so `bondActiveDevice()` first `awaitOpsFlushed()`s (bounded) and calls
  `createBond()` only in a quiet gap — `createBond()` on a busy link can force a transient
  re-encrypt/disconnect on some firmware that would drop those in-flight ops.
- **Idempotent:** guarded on `BOND_NONE`, so it bonds at most once per ring.
- **Visible UX change (intended, but ONLY for allowlisted models):** the ring appears in the
  phone's Bluetooth paired-devices list and lights the status-bar BT icon — this is the point
  for the R09/R11, and matches QRing for them. These rings use "just works" pairing (no
  passkey dialog).

> ### ⚠️ Bonding scope is a hand-curated allowlist — NOT "whenever `supportBlePair`"
> **This is the single most re-litigated decision in this doc. Read it before touching
> `bondActiveDevice()`, `WearableModel.requiresOsBond`, or anything with "bond" in the name.**
>
> The real QRing app (`DeviceCmdInit.init`, quoted above) bonds **unconditionally** whenever
> the ring's `0x3C` reply sets `supportBlePair` — no per-model check at all. **PulseLoop
> deliberately does not do this.** `RingBLEClient.bondActiveDevice()` also requires
> `WearableModel.requiresOsBond == true` for the resolved model, currently just
> `COLMI_R09`, `COLMI_R11`, `YAWELL_R11` — the models with a *demonstrated* GATT-only
> fragility. Every other Colmi/Yawell model, including the **R10**, reports `supportBlePair`
> too but stays GATT-only on purpose: bonding is a real UX cost (an OS pairing dialog, the
> ring occupying the phone's paired-devices list) that isn't worth paying for a model that
> already holds a stable link without it.
>
> **This has already regressed once.** 2026-07-15 (`0978636`) introduced the allowlist
> specifically to stop the R10 from showing the pairing dialog. 2026-07-19 (`2e8412c`), a fix
> for issue #29 (Colmi R11 stuck on "Connecting") removed the allowlist entirely in favor of
> matching QRing's blanket rule — which fixed the R11 but immediately reopened the R10
> pairing-dialog regression in the same release. Corrected by restoring the allowlist and
> adding the R11/Yawell R11 to it **by name**, instead of widening the condition back to
> `supportBlePair` alone.
>
> **The rule going forward:** when a new model is confirmed (real hardware, or a credible,
> specific user report) to need an OS bond, add that model to the allowlist by name. Do
> **not** "simplify" or "match QRing exactly" by conditioning on `supportBlePair` alone —
> that generalization is exactly what caused the regression, and it will cause it again for
> the R10 (or any other non-allowlisted model that happens to report the bit).

### 5b. Stuck on "Connecting…" forever (first pairing)
**QRing arms a no-callback timeout on *every* connect** (`mTimeoutRunnable`, 40s in
`BleBaseControl`): if the OS never delivers `onConnectionStateChange`, it force-disconnects
and reports failure. PulseLoop's watchdog had a 30s connect timeout **but gated it behind
`if (lastKnownIdentifier == null) return`** — and on a *first-ever* pairing there is no
stored ring yet, so the timeout never ran and the attempt hung indefinitely.

**The fix (implemented):** `RingBLEClient.connectionWatchdogTick` now checks the `CONNECTING`
timeout **before** the last-known-ring guard. If a stored ring exists it reconnects
(`forceReconnect`, unchanged); on a first pair it calls `failConnectAttempt(...)` — tears
down the half-open GATT, sets state `FAILED` with a user-facing error, so the pairing UI
leaves "Connecting…".

### 5c. Already-present QRing behaviors (do not re-add)
These were adopted in earlier work; noted so a porter doesn't duplicate them:
`autoConnect=false` + `TRANSPORT_LE`; `refresh()`-via-reflection then `close()` on every
teardown; `count % 3` direct-vs-scan reconnect alternation; a GATT-op FIFO so a dropped CCCD
write can't hang the connection; "toggle Bluetooth" hint after 2 sightless reconnect scans.

## 6. Side-by-side summary

| Concern | QRing | PulseLoop before | PulseLoop after |
|---|---|---|---|
| Auto-HR write | 7-field `0x16` | 4-byte `0x16` (truncated) | **7-field `0x16`** |
| Reads capabilities (`0x3C`) | yes | no | **yes** |
| OS bond | if `supportBlePair` (any model) | never | **if `supportBlePair` AND model on the [allowlist](#5a-no-os-bond-the-main-pairing-defect) (R09/R11)** |
| First-pair connect timeout | 40s, every attempt | none (gated out) | **30s, every attempt** |
| Reconnect timeout | 40s | 30s (reconnect only) | 30s (reconnect only) |
| `connectGatt` params | `false`, `TRANSPORT_LE` | same | same |
| Teardown refresh+close | yes | yes | yes |

## 7. PulseLoop custom features — confirmed unaffected

PulseLoop's only material behavioral addition over QRing is the **custom data-sync /
measurement-interval window** (iOS #19): a Settings row (shown only for rings declaring the
`MEASUREMENT_INTERVAL` capability) where the user sets the all-day HR sampling interval and
per-vital on/off toggles. Flow:
`SettingsSubScreens → RingSyncCoordinator.applyMeasurementSettings →
ColmiSyncEngine.applyMeasurementSettings → enqueueMeasurementCommands →
ColmiEncoder.autoHeartRate(enabled, intervalMinutes)`.

- **Auto-HR fix (§4):** the custom interval flows through the *same* `autoHeartRate` builder.
  The change **only appends** trailing bytes; `enabled` and `intervalMinutes` keep their
  positions and the existing `((interval/5)*5).coerceIn(5,60)` rounding. So the feature's
  contract is unchanged — and on R09 it goes from *silently ignored* to *actually honored*.
- **Pairing fix (§5):** lives entirely in the connection/bonding layer (`RingBLEClient`) and
  the startup handshake; it shares no code with the interval/settings path. Adding the `0x3C`
  read does not change the static capability set, so the Settings row's visibility and every
  other capability-gated feature are untouched.

There are no other PulseLoop-only ring features that these changes touch (the seed-from-ring
config logic, realtime-HR keepalive, forget/unbind flow, and history state machine are all
preserved).

## 8. Exactly what changed (Android)

| File | Change |
|---|---|
| `ring/ColmiProtocol.kt` | `+ DEVICE_SUPPORT = 0x3C` |
| `ring/ColmiEncoder.kt` | `autoHeartRate` → 7-field payload; `+ deviceSupport()` |
| `ring/ColmiDecoder.kt` | `+ decodeDeviceSupport()` |
| `ring/WearableDriver.kt` | `+ RingSyncEngine.setOnBondRequested()` (default no-op) |
| `ring/ColmiSyncEngine.kt` | `+ onBondRequested`; startup reads `0x3C`; `handleRawNotify` routes bond signal |
| `ring/RingBLEClient.kt` | `+ bondActiveDevice()`; wire callback in `installDriver`; first-pair connect timeout + `failConnectAttempt()`; updated no-bond comment |
| tests | updated 4→8-byte auto-HR asserts; `+ decodeDeviceSupport` + bond-request tests |

## 9. Porting to iOS

The iOS app mirrors these classes (Swift). Apply the same behavior:

| Android | iOS (root repo) |
|---|---|
| `ColmiEncoder.kt` | `ColmiEncoder.swift` |
| `ColmiDecoder.kt` | `ColmiDecoder.swift` |
| `ColmiProtocol.kt` | `ColmiProtocol.swift` (`ColmiCommandID`) |
| `ColmiSyncEngine.kt` | `ColmiSyncEngine.swift` |
| `RingBLEClient.kt` | `RingBLEClient.swift` |
| `WearableDriver.kt` (`RingSyncEngine`) | `WearableDriver.swift` |

iOS specifics to translate:
- **Auto-HR:** same 8-byte payload. Trivial, no platform caveats.
- **`0x3C` read + `supportBlePair`:** same decode (`frame[2] & 0x08` — full-frame index;
  QRing rsp classes see the opcode-stripped slice, so their `bArr[1]` = full-frame `[2]`),
  same startup enqueue and engine callback.
- **Bonding is different on iOS.** CoreBluetooth has **no `createBond()`** — bonding/pairing
  is triggered *implicitly* by accessing an encrypted characteristic, and the OS shows the
  pairing sheet. So the iOS equivalent of "honor `supportBlePair`" is usually a no-op at the
  API level (iOS pairs on demand), **but** the useful ports are: (a) the **first-connect
  timeout** (CoreBluetooth `connect(_:options:)` has no timeout — add your own timer and call
  `cancelPeripheralConnection` on expiry, exactly like `failConnectAttempt`), and (b) do NOT
  hold the `CBPeripheral`/central in a state that blocks re-discovery after a silent drop.
  Verify whether iOS users even report the pairing bug before porting 5a; the auto-HR (§4)
  and first-pair-timeout (§5b) fixes are the clearly-portable ones.

## 10. Source map (for re-verification)

**QRing (decompiled, in iOS repo root `decompiled-qring-official/sources/`):**
- `com/oudmon/ble/base/communication/req/HeartRateSettingReq.java` — 7-field `0x16` write
- `com/oudmon/ble/base/communication/rsp/HeartRateSettingRsp.java` — field semantics
- `com/oudmon/ble/base/communication/req/DeviceSupportReq.java` — `0x3C` request (opcode 60)
- `com/oudmon/ble/base/communication/rsp/DeviceSupportFunctionRsp.java` — `supportBlePair = byte[1] & 8`
  of the opcode-stripped slice (`QCDataParser.java:34`) = **full-frame byte 2**; `supportIntervalTemp
  = byte[8] & 0x80` = full-frame byte 9
- `com/qcwireless/smart/ui/base/watch/DeviceCmdInit.java` — bond gate on `supportBlePair`
- `com/oudmon/ble/base/bluetooth/BleBaseControl.java` — `bleCreateBond`, `connectGatt`,
  `mTimeoutRunnable` (40s), `mReconnectRunnable`, `refreshDeviceCache`
- `com/oudmon/ble/base/communication/req/BaseReqCmd.java` — 16-byte framing + checksum

**Diagnostics that seeded this analysis:** a user's exported debug log showed the ring
(`R09`, `RT09_3.10.21_251107`) receiving `16 02 01 05` on connect yet recording no background
HR, and issue #9 reports of "not in paired list / stuck Connecting / forget+toggle to
recover."
