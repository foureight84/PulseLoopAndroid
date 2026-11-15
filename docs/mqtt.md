# MQTT Integration

PulseLoop Android can publish your synced ring health metrics directly to any local or remote MQTT broker using a consolidated JSON state topic paired with standard MQTT auto-discovery config payloads.

> **Real ring data only:** Demo / seeded rows (`source` in `demo` / `mock`) are excluded from MQTT export. Only measurements, activity, sleep, and workout sessions recorded from a paired real ring are published.

---

## Configuration (`Settings → MQTT`)

| Setting | Default | Notes |
|---|---|---|
| **Enable MQTT export** | Off | Turns background and live publishing on or off. |
| **Broker Host** | _(empty)_ | Hostname or IP of your MQTT broker (for example `192.168.1.50` or `mqtt.local`). Plain TCP is intended for private/local addresses; enable TLS for public hosts. |
| **Port** | `1883` (`8883` with TLS) | TCP port (`1`–`65535`). |
| **Use TLS (MQTTS)** | Off | Uses Android's default TLS socket factory with `HTTPS` hostname verification. |
| **Username / Password** | _(optional)_ | Sent on `CONNECT`; password is stored separately in `EncryptedSharedPreferences` (`pulseloop_secure`). |
| **Discovery Prefix** | `homeassistant` | Root topic used when publishing MQTT auto-discovery entity definitions. |
| **Base Topic** | `pulseloop` | Root topic used for consolidated state payloads. |
| **Device Name Override** | _(optional)_ | Replaces the ring's default display name and topic slug (`<base_topic>/<device_slug>/state`). |
| **Background Ring Sync** | `30 min` | Controls how often `RingSyncWorker` wakes in the background (`15 min`, `30 min`, `1 hr`, `2 hr`, clamped to `15`–`720 min`) and queues an MQTT export. |
| **Live workout streaming** | On | Streams throttled updates (~`5 s`) over a pooled MQTT connection (`MqttSessionPool`) during active workouts and live spot measurements. |

---

## Topic Layout

Every message is published with **QoS 1** and **retain = true**.

### 1. Discovery Config Topics

```text
<discovery_prefix>/<component>/<unique_prefix>/<sensor_key>/config
```

- `<discovery_prefix>`: normalized discovery prefix (default `homeassistant`, compatible with MQTT auto-discovery consumers).
- `<component>`: `binary_sensor` for `workout_active`, `sensor` for all other metrics.
- `<unique_prefix>`: `pulseloop_<mac_or_id_hex>` (for example `pulseloop_aabbccdde1f2`).
- `<sensor_key>`: metric key listed in the table below (for example `heart_rate`, `steps`, `sleep_score`).

### 2. Consolidated State Topic

```text
<base_topic>/<device_slug>/state
```

- `<base_topic>`: normalized base topic (default `pulseloop`).
- `<device_slug>`: `<sanitized_name>_<last4>` (for example `colmi_r11_e1f2`) or `<sanitized_device_name_override>` when an override is set.
- Payload is a single flat JSON object read by subscribers or via auto-discovery `value_template` (for example `{{ value_json.get('heart_rate') }}`).

---

## Exported Data & Sensor Selection

Exported sensors are automatically selected based on the paired ring's reported capabilities and recorded measurements (unsupported metrics are not advertised or emitted). Sleep uses the current reference waking night; daily activity uses today's local day; vitals use the latest real reading.

### Vitals

| Sensor Key | Component | JSON Field | Unit | Device / State Class | Selection Requirement |
|---|---|---|---|---|---|
| `battery` | `sensor` | `battery` | `%` | `battery` / `measurement` | `BATTERY` capability |
| `heart_rate` | `sensor` | `heart_rate` | `bpm` | — / `measurement` | `HEART_RATE` or `MANUAL_HEART_RATE` (uses live HR when workout streaming is active) |
| `resting_heart_rate` | `sensor` | `resting_heart_rate` | `bpm` | — / `measurement` | `HEART_RATE` or `MANUAL_HEART_RATE` **and** computed resting baseline present |
| `spo2` | `sensor` | `spo2` | `%` | — / `measurement` | `SPO2`, `SPO2_HISTORY`, or `MANUAL_SPO2` |
| `hrv` | `sensor` | `hrv` | `ms` | — / `measurement` | `HRV` or `MANUAL_HRV` |
| `stress` | `sensor` | `stress` | — | — / `measurement` | `STRESS` or `MANUAL_STRESS` **and** real hardware `STRESS` rows present (derived stress is never exported) |
| `temperature` | `sensor` | `temperature` | `°C` | `temperature` / `measurement` | `TEMPERATURE` or `MANUAL_TEMPERATURE` |
| `blood_pressure_systolic` | `sensor` | `blood_pressure_systolic` | `mmHg` | — / `measurement` | `BLOOD_PRESSURE` or `MANUAL_BLOOD_PRESSURE` |
| `blood_pressure_diastolic` | `sensor` | `blood_pressure_diastolic` | `mmHg` | — / `measurement` | `BLOOD_PRESSURE` or `MANUAL_BLOOD_PRESSURE` |
| `blood_sugar` | `sensor` | `blood_sugar` | `mg/dL` | `blood_glucose_concentration` / `measurement` | `BLOOD_SUGAR` |
| `respiratory_rate` | `sensor` | `respiratory_rate` | `breaths/min` | — / `measurement` | Real `RESPIRATORY_RATE` measurement rows present |
| `vo2_max` | `sensor` | `vo2_max` | `mL/kg/min` | — / `measurement` | `VO2MAX` |

### Daily Activity & Sleep

| Sensor Key | Component | JSON Field | Unit | Device / State Class | Selection Requirement |
|---|---|---|---|---|---|
| `steps` | `sensor` | `steps` | `steps` | — / `total_increasing` | `STEPS` capability |
| `calories` | `sensor` | `calories` | `kcal` | — / `total_increasing` | `STEPS` capability |
| `distance` | `sensor` | `distance` | `m` | `distance` / `total_increasing` | `STEPS` capability |
| `active_minutes` | `sensor` | `active_minutes` | `min` | `duration` / `total_increasing` | `STEPS` capability |
| `sleep_duration` | `sensor` | `sleep_duration` | `min` | `duration` / `measurement` | `SLEEP` capability |
| `sleep_score` | `sensor` | `sleep_score` | — | — / `measurement` | `SLEEP` capability |
| `sleep_deep` | `sensor` | `sleep_deep` | `min` | `duration` / `measurement` | `SLEEP` capability |
| `sleep_light` | `sensor` | `sleep_light` | `min` | `duration` / `measurement` | `SLEEP` capability |
| `sleep_rem` | `sensor` | `sleep_rem` | `min` | `duration` / `measurement` | `SLEEP` and `REM_SLEEP` capabilities |
| `sleep_awake` | `sensor` | `sleep_awake` | `min` | `duration` / `measurement` | `SLEEP` capability |

### Live Workout & Diagnostics

| Sensor Key | Component | JSON Field | Unit | Device / State Class / Category | Selection Requirement |
|---|---|---|---|---|---|
| `workout_active` | `binary_sensor` | `workout_active` | `ON`/`OFF` | `running` (binary sensor) | `HEART_RATE` capability or active/recorded workout (`payload_on: "ON"`, `payload_off: "OFF"`) |
| `workout_type` | `sensor` | `workout_type` | — | — | Same as `workout_active` (omitted from state JSON when no workout is active) |
| `workout_duration` | `sensor` | `workout_duration` | `s` | `duration` / `measurement` | Same as `workout_active` (omitted from state JSON when no workout is active) |
| `last_sync` | `sensor` | `last_sync` | ISO-8601 UTC | `timestamp` / `diagnostic` | Always included |

---

## Example Payloads

### Discovery Config (`homeassistant/sensor/pulseloop_aabbccdde1f2/heart_rate/config`)

```json
{
  "name": "Heart Rate",
  "unique_id": "pulseloop_aabbccdde1f2_heart_rate",
  "object_id": "colmi_r11_e1f2_heart_rate",
  "state_topic": "pulseloop/colmi_r11_e1f2/state",
  "value_template": "{{ value_json.get('heart_rate') }}",
  "unit_of_measurement": "bpm",
  "state_class": "measurement",
  "icon": "mdi:heart-pulse",
  "device": {
    "identifiers": ["pulseloop_aabbccdde1f2"],
    "name": "Colmi R11",
    "manufacturer": "Colmi",
    "model": "Colmi R11",
    "sw_version": "1.00.38",
    "connections": [["mac", "aa:bb:cc:dd:e1:f2"]]
  }
}
```

### State Topic (`pulseloop/colmi_r11_e1f2/state`)

```json
{
  "battery": 84,
  "heart_rate": 72,
  "resting_heart_rate": 58,
  "spo2": 98,
  "hrv": 46.5,
  "stress": 31,
  "temperature": 36.4,
  "steps": 6840,
  "calories": 428.0,
  "distance": 4925,
  "active_minutes": 34,
  "sleep_duration": 450,
  "sleep_score": 70,
  "sleep_deep": 115,
  "sleep_light": 245,
  "sleep_rem": 90,
  "sleep_awake": 25,
  "blood_pressure_systolic": 118,
  "blood_pressure_diastolic": 76,
  "blood_sugar": 94.0,
  "workout_active": false,
  "last_sync": "2026-11-15T08:06:12Z"
}
```

> Null metrics and inactive workout fields (`workout_type`, `workout_duration`) are omitted from the JSON object rather than emitted as `null`.

---

## Clearing Retained Topics

Tapping **Clear Retained Topics** on `Settings → MQTT` publishes zero-byte retained payloads (QoS 1) to all previously published `<discovery_prefix>/<component>/<unique_prefix>/<sensor_key>/config` topics and `<base_topic>/<device_slug>/state`, then resets local discovery bookkeeping.
