# Colmi sleep sync — why sleep goes missing, and how QRing/iOS differ

Investigation date: 2026-07-10. Trigger: a COLMI R10 (`wearableType=COLMI_R02`,
firmware `RT03CR_1.00.02_260319`) returned **no sleep log** after a night's wear. Two
diagnostics captures taken right after waking (`pulseloop-diagnostics-2026-07-10T17-32`,
`...2026-07-11T01-29`) drove this analysis.

## TL;DR

Sleep is not lost because of anything sleep-specific. It's lost because:

1. **The Android GATT write queue thrashes** — `writeCharacteristic()` returns `false`
   repeatedly ("device busy"), and our ACK-timeout optimistically frees the queue slot
   while the framework op is still pending, so we spin-and-drop commands.
2. **Sleep is buried mid-pipeline.** `ColmiSyncEngine` runs one chained 9-stage history
   sync (`ACTIVITY→HEART_RATE→STRESS→SPO2→SLEEP→HRV→BP→TEMPERATURE→BLOOD_SUGAR`). Each
   stage is one write into that failing queue, and a per-stage watchdog **silently
   force-skips** a stalled stage. When the `bigDataSleep()` request (or its multi-frame
   reply) is dropped, the watchdog skips `SLEEP→HRV` and no sleep is persisted — no error
   surfaces to the user.

The request bytes themselves are correct and identical across platforms.

## Evidence from the diagnostics

Both captures show the same failure, mild in the daytime capture and severe post-wake:

```
W/RingBLEClient: GATT op rejected at issue — retrying: J   (writeCharacteristic() == false)
W/RingBLEClient: GATT op dropped after 3 attempts: J
W/RingBLEClient: GATT op ACK timed out — unblocking queue
```

- Post-wake: hundreds of write rejections + ACK timeouts over ~10 s. Daytime: ~18, then
  recovered.
- `"J"` is the R8-obfuscated `GattOp` subclass name in the release build — the log can't
  even say which op failed. **Diagnostics gap: keep the op label readable in release.**
- Connection landed at `interval=99 latency=4` (a slow/low-power link, ~124 ms interval,
  4 skippable events) despite the code requesting `CONNECTION_PRIORITY_HIGH`.
- `rawPackets` is a 200-entry ring buffer covering only the last ~250 ms; post-wake it was
  flooded with 200 of our own retry `command_ack`s, evicting any real sleep reply.
  **Diagnostics gap: retries shouldn't flood the packet buffer.**

## How the sleep request is built (all three are byte-identical)

| Platform | Call | Bytes |
|---|---|---|
| Android | `ColmiEncoder.bigDataSleep()` | `bc 27 01 00 ff 00 ff` |
| iOS | `ColmiEncoder.bigDataSleep()` | `bc 27 01 00 ff 00 ff` |
| QRing (new protocol) | `LargeDataHandler.syncSleepList(offset)` | big-data cmd 39, `offset` byte |

`bc` = big-data V2, `27` = sleep. The trailing `01 00 ff 00 ff` requests full history.

## Are all Colmi rings handled the same on Android? — Yes, fully uniform

Every Colmi/Yawell model (R02/R03/R06/R07/R09/**R10**/R11/R12, Yawell R05/R10/R11, H59)
collapses to the single `RingDeviceType.COLMI_R02` family and one `ColmiCoordinator`.
Model identity is used **only** for display (name/image/brand tab) via advertised-name
regex. There is:

- No per-model / per-firmware / per-capability branch in `requestSleep()`,
  `bigDataSleep()`, or `decodeSleep()`.
- **No capability-gating of any stage** — the ring's reported `sleep,remSleep` capability
  is never consulted; the device-support bitfield is read only to decide BLE bonding.
- **No new-vs-old sleep-protocol concept at all** (zero matches for `newSleep`/
  `sleepProtocol`). One path only: big-data V2 `0x27`.

So the R10 is treated byte-for-byte like a working R02. The only model/firmware-sensitive
Colmi code anywhere is the auto-HR record shape (`ColmiEncoder.autoHeartRate`), unrelated
to sleep.

## How the official QRing app does it — per-ring, decoupled, two channels

QRing (Oudmon/Realsil `bbpro` SDK, `SleepDetailRepository.java`) differs in three ways
that matter:

1. **Per-ring protocol selection.** A single boolean `UserConfig.newSleepProtocol`
   selects between two uniform sleep protocols. It is set from a **capability bit the ring
   declares about itself** — `SetTimeRsp` byte[8] (returned to `SetTimeReq(1)` at connect),
   persisted once and read by every sleep path. New → big-data `syncSleepList`; old →
   day-indexed `ReadSleepDetailsReq(dayIndex, 0, 95)`. No model lookup table.
2. **Sleep is an independent, on-demand request** — fired directly when the sleep screen
   opens (`syncTodaySleepDetail`, offset `0` = today; `255` = all history). It is **not**
   chained behind activity/HR/stress/SpO2, and never watchdog-skipped as a side effect of
   an earlier stage failing.
3. **Two sleep channels**: cmd 39 = main sleep, cmd 62 = nap ("lunch") sleep. Runs through
   the SDK's own one-op-at-a-time command queue with per-command response callbacks.

QRing also treats "last night" as first-class: the sync explicitly keys sleep to the
previous day (`dateUtil.addDay(-1)`) plus `calcLastSleep()`/`queryLastSleep()`.

## How the official JRing app does it — different ring, but a transferable lesson

JRing is a **different device/protocol** (SXR `com.sxr.sdk.ble.keepfit` "keepfit" SDK, not
Colmi/QRing), so its request format doesn't map to Colmi. But PulseLoop also supports jring,
and JRing's *write reliability* is the model PulseLoop's queue should aspire to.

**Sleep is not a separate request at all.** JRing fetches an entire day — steps + heart rate
+ **sleep** — in one call: `getDataByDay(type=1, day)`, streamed back as repeated
`onGetDataByDay(...)` and closed by `onGetDataByDayEnd(...)`. Sleep is embedded in the
unified per-day record; there is no sleep opcode. The app sync loop (`DupMainActivity`,
lines tagged `[debug: sleep]`) walks **day-by-day, oldest→newest, capped at 7 days**
(`iMax ≤ 6`), firing the next day's `getDataByDay` only after the previous day's end
callback. Sleep *windows* (noon nap + night) are configured separately via `setSleepTime`,
which the ring uses to classify. So JRing chains by **day**, whereas Colmi/PulseLoop chains
by **data type** — and sleep never sits behind four unrelated stages.

**The write serialization is far more robust than PulseLoop's op-queue** — this is the part
worth stealing. The SDK's single-threaded `process_cmd_runnable` (`BluetoothLeService`):

- Pulls one `BleCmdItem` at a time from a FIFO queue, strictly one command outstanding.
- **Blocks the next command until the current one's response arrives** (an `A0` ready-gate) —
  true request/response coupling, not an optimistic ACK-timeout that frees the slot early.
- **On `writeCharacteristic()==false`, retries up to 30× at 300 ms** (~9 s of persistence)
  before giving up — versus PulseLoop's 3 attempts × 150 ms then *drop*.
- On terminal write failure it **resets the connection** (`u(true)`), and on response
  timeout it runs a session-timeout reset (`Q()`) — it never spin-drops into a busy stack.
- **Refuses to interleave non-sync commands during a sync** (only the `a3` opcode passes) —
  avoids exactly the framework-busy collisions we see in the R10 logs.

Directly relevant to the diagnosis: the storm in the R10 capture is `writeCharacteristic()`
returning false, which PulseLoop gives up on after 3 quick tries. JRing's SDK would have
retried that same write ~30 times over ~9 s and reset the link on failure rather than
silently dropping the command. PulseLoop's queue is the weakest of the four apps studied.

## How iOS (root repo) does it — same design as Android, same weaknesses

iOS mirrors Android almost exactly, and as of 2026-07-10 (upstream `e00c24b`) **has not
addressed this**:

- Identical `bc 27 01 00 ff 00 ff` request.
- Same chained pipeline (7 stages — no BP/blood-sugar), sleep is stage 5, same per-stage
  force-skip watchdog. `querySleep()` just re-runs the whole pipeline; no isolated sleep
  request.
- Same manual single-outstanding-op FIFO write queue + 4 s write-ACK timeout (explicitly
  commented as mirroring Android), and **no per-write resend** — drop-and-advance only.
- The one recent Colmi sync change (PR #57 `isVitalsBackfill`) adds a post-workout
  fast-path that **explicitly skips sleep** — the opposite of decoupling it.

The one thing that saves iOS from the *specific* storm in these logs: CoreBluetooth owns
write serialization and never exposes the raw `writeCharacteristic()==false` busy state, so
iOS doesn't hit the spin-and-drop loop as often. It is not a better sleep design.

## Recommended fixes (Android), highest value first

1. **Decouple sleep into an on-demand request** (QRing's model): fire `bigDataSleep()`
   directly when the sleep screen opens / via a dedicated retry, so it doesn't depend on
   four earlier stages surviving. This is the single biggest win. (iOS would benefit too.)
2. **Fix the write-queue thrash**: on repeated `writeCharacteristic()==false`, force a
   reconnect to clear the framework busy flag instead of spin-dropping; don't optimistically
   free the slot on ACK-timeout into a still-busy stack; make `CONNECTION_PRIORITY_HIGH`
   actually stick (it's landing at interval 99).
3. **Consider the today-vs-all offset and the nap channel** — Android always requests full
   history and ignores nap sleep (QRing cmd 62); querying "today only" post-wake may be more
   reliable.
4. **Diagnostics**: un-obfuscate the op label in release and stop letting retries flood the
   200-entry rawPacket buffer, so the next capture is readable.

## Open questions

- Confirm the R10 (`RT03CR` firmware) actually finalizes the night's sleep record before a
  post-wake sync (QRing keys sleep to the previous day). If the record isn't finalized yet,
  no protocol change helps.
- Verify whether the R10 reports a "new sleep protocol" capability bit like QRing keys on —
  Android currently assumes the big-data path unconditionally, which is likely correct here
  but unverified.
