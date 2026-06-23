# PulseLoop Android

Android port of [PulseLoop](https://github.com/foureight84/PulseLoop) — a smart ring companion app for the 56ff/Jring and Colmi/Yawell QRing platforms.

## Visual & UX Differences from iOS

### Vitals Dashboard
- **Threshold bars** on every metric panel — color-coded horizontal bar (Good → Normal → Borderline → High) showing where the current value falls within reference ranges
- **Tap-through detail screens** for every metric — tap any panel to open a full trend view with Today/Week/Month period selector, zone-colored line chart, current-value marker, stat tiles (Latest/Avg/Min/Max), and non-medical explainer + disclaimer
- **Zone-colored trend charts** — detail screen charts color data points by threshold zone (green/amber/red) with a vertical gradient line at zone boundaries. A white-ringed marker highlights the most recent reading
- **Range/avg summaries** — all panels display `Range: min – max · Avg: avg` below the value
- **Combined measurement button** — one-tap spot measurement for BP, SpO₂, stress, fatigue, and blood sugar with countdown progress bar

### Sleep Score
- **Sleep stage breakdown** — Deep/Light/Awake percentage pills color-coded per stage
- **Sleep coach insights** — contextual chip-based recommendations from the onboard sleep analysis engine

### Activity
- **Live workout recording** with GPS tracking, HR zone display, and session history

### Data Architecture
- Reactive Room database with `Flow`-based queries — data appears live as the ring syncs
- Local-midnight-aligned day bucketing for consistent daily stats across timezones
- Calibrated display pipeline: `bpAdjustSystolic`/`bpAdjustDiastolic` offsets applied in ViewModels, `glucoseOffsetMgdl` applied to blood sugar readings
- Pull-to-refresh on Today dashboard triggers immediate ring sync

## BLE Protocol Implementation (56ff / Jring)

The Android port extends the iOS protocol implementation with additional sensor decodes, connection stability improvements, and on-ring calibration support.

### Extended Sensor Decoding

**Combined measurement (0x24)** — 9-byte payload decoded vs iOS's 6 bytes:

| Byte | Metric | iOS | Android |
|---|---|---|---|
| 0 | Command echo (0x24) | ✅ | ✅ |
| 1 | Heart Rate (bpm) | ✅ | ✅ |
| 2 | Systolic (mmHg) | ✅ | ✅ |
| 3 | Diastolic (mmHg) | ✅ | ✅ |
| 4 | SpO₂ (%) | ✅ | ✅ |
| 5 | Fatigue (0–100) | — | ✅ |
| 6 | Stress (0–100) | — | ✅ |
| 7 | Blood Sugar (mmol/L ×10 → mg/dL) | — | ✅ |
| 8 | HRV (ms) | — | ✅ |

**Blood sugar conversion** — ring reports mmol/L × 10; Android converts to mg/dL for display.

**Heart rate history (0x16)** — multi-packet protocol with sub-type routing:
- `0xF0` header block: total packet count
- `0xAA` index block: current position tracking
- `0xA0` data block: 12 raw 1-min samples → 2 averaged readings (6 samples each)
- `0xFF` sync complete marker

**Device info (0x0C)** — builds firmware version from payload bytes in `CID + DID + V + version` format.

**Activity history (0x10)** — 15× 1-min step buckets per packet, local-time boundary detection for day stream completion.

### Ring Configuration

**App identity (0x48)** — persistent app ID sent on connect so the ring routes data to this app.

**User profile (0x02)** — age, gender, height, and weight pushed to the ring in metric units to feed on-device algorithms for BP, blood sugar, and calorie estimation.

**BP calibration (0x33)** — systolic/diastolic reference values pushed to the ring for on-device offset correction, in addition to app-side display calibration.

**Bind protocol (0x4B)** — ring-driven handshake (INIT → APP\_START → ACK → SUCCESS) for stable pairing, plus unbind (UNBOND → UNBOND\_ACK) on device forget.

### Connection Reliability

- **Keepalive ping (0x3A)** — every 15s to prevent the ring's ~20s idle timeout from dropping the link
- **Write ACK timeout** — 3-second timeout unblocks the command queue on missed acknowledgements
- **Connection watchdog** — monitors GATT activity; forces reconnect after 10–20s of silence
- **Foreground reconnection** — reconnects on app resume if the GATT dropped during device sleep
- **Stale-state guard** — resets persisted connection state on app restart since the live GATT is gone
- **Force-close stale GATT** — explicitly disconnects and closes orphaned handles before opening a new connection
- **No OS-level bonding** — avoids Bluetooth status-bar icon and OS-level pairing instability
- **High-priority connection interval** — requests priority connection parameters on connect
- **Firmware discovery** — scans all services for firmware characteristics, not just standard DIS

## Ring Support

| Capability | 56ff / Jring | Colmi QRing |
|---|---|---|
| Heart Rate (spot + live + history) | ✅ | ✅ |
| SpO₂ (spot + history) | ✅ | ✅ |
| Steps / Distance / Calories | ✅ | ✅ |
| Sleep (Light + Deep + Awake) | ✅ | ✅ |
| Sleep (REM) | ❌ | ✅ |
| Blood Pressure | ✅ | ✅ |
| Blood Sugar | ✅ | ✅ |
| HRV | ❌ | ✅ |
| Stress | ✅ | ✅ |
| Fatigue | ✅ | ✅ |
| Skin Temperature | ❌ | ✅ |
| Battery | ✅ | ✅ |
| Find Device | ✅ | ✅ |

## Build

```bash
./gradlew assembleDebug
```

Min SDK: 26 (Android 8.0+). Target SDK: 34.

## License

This project is part of the PulseLoop ecosystem. See the parent repository for license information.
