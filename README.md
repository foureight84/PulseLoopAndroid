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

## BLE Fixes (56ff / Jring)

Based on testing and protocol analysis:

- **Stale-state guard** — on app restart, any persisted "CONNECTED" or "CONNECTING" state is reset to "DISCONNECTED" since the live GATT is gone. Prevents false "Connected" display after process restart.
- **Keepalive ping (0x3A)** — sends every 15s to prevent the ring's ~20s idle timeout from dropping the link
- **Write ACK timeout** — a 3-second timeout unblocks the command queue if the ring never acknowledges a write (prevents permanent deadlock of all subsequent commands)
- **Connection watchdog** — monitors GATT activity; if no activity for 10-20s, forces a fresh reconnect
- **Foreground reconnection** — on every `ON_START` lifecycle event, reconnects if the GATT silently dropped during Doze/idle
- **No OS-level bonding** — intentionally skipped to avoid the Bluetooth status-bar icon and OS-level pairing instability
- **High-priority connection interval** — requests `CONNECTION_PRIORITY_HIGH` on connect, matching the official app's behavior
- **Firmware version read** — scans all services for 0x2A26/0x2A28 firmware characteristics, not just DIS
- **Force-close stale GATT** — on reconnect, explicitly disconnects and closes any orphaned GATT handle before opening a new one

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
