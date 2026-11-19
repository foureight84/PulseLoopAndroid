package com.pulseloop.mqtt

import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import com.pulseloop.ring.ringModelLabel
import com.pulseloop.wearables.WearableModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure-Kotlin MQTT state and auto-discovery payload generation.
 *
 * No Android dependencies: safe to run directly in JVM unit tests.
 */
data class DeviceIdentity(
    val uniquePrefix: String,
    val slug: String,
    val displayName: String,
    val manufacturer: String,
    val model: String,
    val swVersion: String?,
    val macAddress: String?,
)

data class SensorDescriptor(
    val key: String,
    val component: String = "sensor",
    val name: String,
    val jsonField: String,
    val unit: String? = null,
    val deviceClass: String? = null,
    val stateClass: String? = null,
    val icon: String? = null,
    val entityCategory: String? = null,
    val suggestedDisplayPrecision: Int? = null,
    val payloadOn: String? = null,
    val payloadOff: String? = null,
    val valueTemplate: String? = null,
)

data class MqttDataSnapshot(
    val batteryPercent: Int? = null,
    val heartRateBpm: Int? = null,
    val restingHeartRateBpm: Int? = null,
    val spo2Percent: Int? = null,
    val hrvMs: Double? = null,
    val stressScore: Int? = null,
    val temperatureCelsius: Double? = null,
    val steps: Int? = null,
    val caloriesKcal: Double? = null,
    val distanceMeters: Int? = null,
    val activeMinutes: Int? = null,
    val sleepTotalMinutes: Int? = null,
    val sleepScore: Int? = null,
    val sleepDeepMinutes: Int? = null,
    val sleepLightMinutes: Int? = null,
    val sleepRemMinutes: Int? = null,
    val sleepAwakeMinutes: Int? = null,
    val bpSystolicMmhg: Int? = null,
    val bpDiastolicMmhg: Int? = null,
    val bloodSugarMmol: Double? = null,
    val respiratoryRateBrpm: Double? = null,
    val vo2Max: Double? = null,
    val workoutActive: Boolean = false,
    val workoutType: String? = null,
    val workoutDurationSeconds: Int? = null,
    val lastSyncIso: String? = null,
    val availableKinds: Set<String> = emptySet(),
)

object MqttDiscovery {

    private val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val NON_SLUG_CHARS = Regex("[^a-z0-9]+")

    // ------------------------------------------------------------------
    // Slug / identity helpers
    // ------------------------------------------------------------------

    fun sanitizeSlug(raw: String): String {
        val cleaned = raw.lowercase()
            .replace(NON_SLUG_CHARS, "_")
            .trim('_')
        return cleaned.ifEmpty { "ring" }
    }

    fun resolveDeviceIdentity(device: DeviceEntity, prefs: MqttPrefs): DeviceIdentity {
        val model = WearableModel.model(device.wearableModelID)?.displayName
            ?: ringModelLabel(device.name, device.deviceType)

        val manufacturer = when (device.deviceType) {
            RingDeviceType.COLMI_R02,
            RingDeviceType.COLMI_SMART_HEALTH,
            RingDeviceType.CRP -> "Colmi"
            RingDeviceType.YCBT -> "YCBT"
            RingDeviceType.RWFIT -> "RWfit"
            else -> "Smart Ring"
        }

        val rawIdentifier = (device.peripheralIdentifier ?: device.bleAddressHint ?: device.id).trim()
        val idDigits = rawIdentifier.lowercase().replace(Regex("[^a-z0-9]"), "")
            .ifEmpty { device.id.lowercase().replace(Regex("[^a-z0-9]"), "") }

        val uniquePrefix = "pulseloop_$idDigits"
        val shortSuffix = idDigits.takeLast(4).ifEmpty { "ring" }

        val overrideName = prefs.deviceNameOverride.trim()
        val displayName = overrideName.ifEmpty {
            device.name.trim().ifEmpty {
                device.advertisedName?.trim().orEmpty().ifEmpty { model }
            }
        }

        val slug = if (overrideName.isNotEmpty()) {
            sanitizeSlug(overrideName)
        } else {
            sanitizeSlug("${displayName}_$shortSuffix")
        }

        val macAddress = (device.peripheralIdentifier ?: device.bleAddressHint)
            ?.trim()
            ?.takeIf { MAC_REGEX.matches(it) }

        val swVersion = device.firmwareVersion?.trim()?.takeIf { it.isNotEmpty() }

        return DeviceIdentity(
            uniquePrefix = uniquePrefix,
            slug = slug,
            displayName = displayName,
            manufacturer = manufacturer,
            model = model,
            swVersion = swVersion,
            macAddress = macAddress,
        )
    }

    // ------------------------------------------------------------------
    // Topic helpers
    // ------------------------------------------------------------------

    fun stateTopic(baseTopic: String, identity: DeviceIdentity): String =
        "${baseTopic.trim().trim('/').ifEmpty { "pulseloop" }}/${identity.slug}/state"

    fun discoveryTopic(discoveryPrefix: String, descriptor: SensorDescriptor, identity: DeviceIdentity): String =
        "${discoveryPrefix.trim().trim('/').ifEmpty { "homeassistant" }}/" +
            "${descriptor.component}/${identity.uniquePrefix}/${descriptor.key}/config"

    /** Maps a stored sensor key back to its discovery component prefix. */
    fun componentForKey(key: String): String =
        if (key == "workout_active") "binary_sensor" else "sensor"

    // ------------------------------------------------------------------
    // Sensor descriptors (strict capability + data gating)
    // ------------------------------------------------------------------

    fun sensorsFor(device: DeviceEntity, snapshot: MqttDataSnapshot): List<SensorDescriptor> {
        val caps = device.capabilities
        val sensors = mutableListOf<SensorDescriptor>()

        val workoutRelevant = WearableCapability.HEART_RATE in caps ||
            snapshot.workoutActive ||
            snapshot.workoutType != null

        if (WearableCapability.BATTERY in caps) {
            sensors += SensorDescriptor(
                key = "battery",
                name = "Battery",
                jsonField = "battery",
                unit = "%",
                deviceClass = "battery",
                stateClass = "measurement",
                icon = "mdi:battery",
            )
        }

        if (WearableCapability.HEART_RATE in caps || WearableCapability.MANUAL_HEART_RATE in caps) {
            sensors += SensorDescriptor(
                key = "heart_rate",
                name = "Heart Rate",
                jsonField = "heart_rate",
                unit = "bpm",
                stateClass = "measurement",
                icon = "mdi:heart-pulse",
            )
        }

        if ((WearableCapability.HEART_RATE in caps || WearableCapability.MANUAL_HEART_RATE in caps) &&
            snapshot.restingHeartRateBpm != null
        ) {
            sensors += SensorDescriptor(
                key = "resting_heart_rate",
                name = "Resting Heart Rate",
                jsonField = "resting_heart_rate",
                unit = "bpm",
                stateClass = "measurement",
                icon = "mdi:heart-outline",
            )
        }

        if (WearableCapability.SPO2 in caps ||
            WearableCapability.SPO2_HISTORY in caps ||
            WearableCapability.MANUAL_SPO2 in caps
        ) {
            sensors += SensorDescriptor(
                key = "spo2",
                name = "Blood Oxygen",
                jsonField = "spo2",
                unit = "%",
                stateClass = "measurement",
                icon = "mdi:water-percent",
            )
        }

        if (WearableCapability.HRV in caps || WearableCapability.MANUAL_HRV in caps) {
            sensors += SensorDescriptor(
                key = "hrv",
                name = "HRV",
                jsonField = "hrv",
                unit = "ms",
                stateClass = "measurement",
                icon = "mdi:heart-flash",
                suggestedDisplayPrecision = 1,
            )
        }

        // CRITICAL (#67): only expose stress when real hardware stress readings exist.
        if ((WearableCapability.STRESS in caps || WearableCapability.MANUAL_STRESS in caps) &&
            MeasurementKind.STRESS.name in snapshot.availableKinds
        ) {
            sensors += SensorDescriptor(
                key = "stress",
                name = "Stress",
                jsonField = "stress",
                stateClass = "measurement",
                icon = "mdi:emoticon-neutral-outline",
            )
        }

        if (WearableCapability.TEMPERATURE in caps || WearableCapability.MANUAL_TEMPERATURE in caps) {
            sensors += SensorDescriptor(
                key = "temperature",
                name = "Skin Temperature",
                jsonField = "temperature",
                unit = "°C",
                deviceClass = "temperature",
                stateClass = "measurement",
                suggestedDisplayPrecision = 1,
            )
        }

        if (WearableCapability.STEPS in caps) {
            sensors += SensorDescriptor(
                key = "steps",
                name = "Steps",
                jsonField = "steps",
                unit = "steps",
                stateClass = "total_increasing",
                icon = "mdi:walk",
            )
            sensors += SensorDescriptor(
                key = "calories",
                name = "Calories",
                jsonField = "calories",
                unit = "kcal",
                stateClass = "total_increasing",
                icon = "mdi:fire",
                suggestedDisplayPrecision = 0,
            )
            sensors += SensorDescriptor(
                key = "distance",
                name = "Distance",
                jsonField = "distance",
                unit = "m",
                deviceClass = "distance",
                stateClass = "total_increasing",
                icon = "mdi:map-marker-distance",
            )
            sensors += SensorDescriptor(
                key = "active_minutes",
                name = "Active Minutes",
                jsonField = "active_minutes",
                unit = "min",
                deviceClass = "duration",
                stateClass = "total_increasing",
                icon = "mdi:run",
            )
        }

        if (WearableCapability.SLEEP in caps) {
            sensors += SensorDescriptor(
                key = "sleep_duration",
                name = "Sleep Duration",
                jsonField = "sleep_duration",
                unit = "min",
                deviceClass = "duration",
                stateClass = "measurement",
                icon = "mdi:sleep",
            )
            sensors += SensorDescriptor(
                key = "sleep_score",
                name = "Sleep Score",
                jsonField = "sleep_score",
                stateClass = "measurement",
                icon = "mdi:star-circle-outline",
            )
            sensors += SensorDescriptor(
                key = "sleep_deep",
                name = "Deep Sleep",
                jsonField = "sleep_deep",
                unit = "min",
                deviceClass = "duration",
                stateClass = "measurement",
                icon = "mdi:power-sleep",
            )
            sensors += SensorDescriptor(
                key = "sleep_light",
                name = "Light Sleep",
                jsonField = "sleep_light",
                unit = "min",
                deviceClass = "duration",
                stateClass = "measurement",
                icon = "mdi:weather-night",
            )
            if (WearableCapability.REM_SLEEP in caps) {
                sensors += SensorDescriptor(
                    key = "sleep_rem",
                    name = "REM Sleep",
                    jsonField = "sleep_rem",
                    unit = "min",
                    deviceClass = "duration",
                    stateClass = "measurement",
                    icon = "mdi:eye-outline",
                )
            }
            sensors += SensorDescriptor(
                key = "sleep_awake",
                name = "Awake Sleep",
                jsonField = "sleep_awake",
                unit = "min",
                deviceClass = "duration",
                stateClass = "measurement",
                icon = "mdi:sleep-off",
            )
        }

        if (WearableCapability.BLOOD_PRESSURE in caps || WearableCapability.MANUAL_BLOOD_PRESSURE in caps) {
            sensors += SensorDescriptor(
                key = "blood_pressure_systolic",
                name = "Blood Pressure Systolic",
                jsonField = "blood_pressure_systolic",
                unit = "mmHg",
                stateClass = "measurement",
                icon = "mdi:blood-bag",
            )
            sensors += SensorDescriptor(
                key = "blood_pressure_diastolic",
                name = "Blood Pressure Diastolic",
                jsonField = "blood_pressure_diastolic",
                unit = "mmHg",
                stateClass = "measurement",
                icon = "mdi:blood-bag",
            )
        }

        if (WearableCapability.BLOOD_SUGAR in caps) {
            sensors += SensorDescriptor(
                key = "blood_sugar",
                name = "Blood Glucose",
                jsonField = "blood_sugar",
                unit = MeasurementKind.BLOOD_SUGAR.unit,
                deviceClass = "blood_glucose_concentration",
                stateClass = "measurement",
                suggestedDisplayPrecision = 1,
            )
        }

        // Only if real respiratory-rate readings exist (e.g. YCBT).
        if (MeasurementKind.RESPIRATORY_RATE.name in snapshot.availableKinds) {
            sensors += SensorDescriptor(
                key = "respiratory_rate",
                name = "Respiratory Rate",
                jsonField = "respiratory_rate",
                unit = "breaths/min",
                stateClass = "measurement",
                icon = "mdi:lungs",
                suggestedDisplayPrecision = 1,
            )
        }

        if (WearableCapability.VO2MAX in caps) {
            sensors += SensorDescriptor(
                key = "vo2_max",
                name = "VO2 Max",
                jsonField = "vo2_max",
                unit = "mL/kg/min",
                stateClass = "measurement",
                icon = "mdi:lungs",
                suggestedDisplayPrecision = 1,
            )
        }

        if (workoutRelevant) {
            sensors += SensorDescriptor(
                component = "binary_sensor",
                key = "workout_active",
                name = "Workout Active",
                jsonField = "workout_active",
                deviceClass = "running",
                icon = "mdi:run-fast",
                payloadOn = "ON",
                payloadOff = "OFF",
                valueTemplate = "{{ 'ON' if value_json.get('workout_active') else 'OFF' }}",
            )
            sensors += SensorDescriptor(
                key = "workout_type",
                name = "Workout Type",
                jsonField = "workout_type",
                icon = "mdi:dumbbell",
            )
            sensors += SensorDescriptor(
                key = "workout_duration",
                name = "Workout Duration",
                jsonField = "workout_duration",
                unit = "s",
                deviceClass = "duration",
                stateClass = "measurement",
                icon = "mdi:timer-outline",
            )
        }

        // Always included: diagnostic sync timestamp.
        sensors += SensorDescriptor(
            key = "last_sync",
            name = "Last Sync",
            jsonField = "last_sync",
            deviceClass = "timestamp",
            entityCategory = "diagnostic",
            icon = "mdi:sync",
        )

        return sensors
    }

    // ------------------------------------------------------------------
    // Discovery payload construction
    // ------------------------------------------------------------------

    private fun discoveryJson(
        descriptor: SensorDescriptor,
        identity: DeviceIdentity,
        baseTopic: String,
    ): JsonObject = buildJsonObject {
        put("name", descriptor.name)
        put("unique_id", "${identity.uniquePrefix}_${descriptor.key}")
        put("object_id", "${identity.slug}_${descriptor.key}")
        put("state_topic", stateTopic(baseTopic, identity))
        put(
            "value_template",
            descriptor.valueTemplate ?: "{{ value_json.get('${descriptor.jsonField}') }}",
        )
        descriptor.unit?.let { put("unit_of_measurement", it) }
        descriptor.deviceClass?.let { put("device_class", it) }
        descriptor.stateClass?.let { put("state_class", it) }
        descriptor.icon?.let { put("icon", it) }
        descriptor.entityCategory?.let { put("entity_category", it) }
        descriptor.suggestedDisplayPrecision?.let { put("suggested_display_precision", it) }
        descriptor.payloadOn?.let { put("payload_on", it) }
        descriptor.payloadOff?.let { put("payload_off", it) }
        put("device", buildJsonObject {
            put("identifiers", JsonArray(listOf(JsonPrimitive(identity.uniquePrefix))))
            put("name", identity.displayName)
            put("manufacturer", identity.manufacturer)
            put("model", identity.model)
            identity.swVersion?.let { put("sw_version", it) }
            identity.macAddress?.let { mac ->
                put(
                    "connections",
                    JsonArray(
                        listOf(
                            JsonArray(
                                listOf(JsonPrimitive("mac"), JsonPrimitive(mac.lowercase()))
                            )
                        )
                    ),
                )
            }
        })
    }

    fun buildDiscoveryMessages(
        identity: DeviceIdentity,
        sensors: List<SensorDescriptor>,
        prefs: MqttPrefs,
    ): List<MqttMessage> {
        val baseTopic = prefs.normalizedBaseTopic
        val discoveryPrefix = prefs.normalizedDiscoveryPrefix
        return sensors.map { descriptor ->
            MqttMessage(
                topic = discoveryTopic(discoveryPrefix, descriptor, identity),
                payload = discoveryJson(descriptor, identity, baseTopic).toString(),
                qos = 1,
                retain = true,
            )
        }
    }

    fun buildRemovalMessages(
        discoveryPrefix: String,
        uniquePrefix: String,
        sensorDescriptorsOrKeys: Iterable<String>,
    ): List<MqttMessage> {
        val prefix = discoveryPrefix.trim().trim('/').ifEmpty { "homeassistant" }
        return sensorDescriptorsOrKeys.map { key ->
            MqttMessage(
                topic = "$prefix/${componentForKey(key)}/$uniquePrefix/$key/config",
                payload = ByteArray(0),
                qos = 1,
                retain = true,
            )
        }
    }

    // ------------------------------------------------------------------
    // State payload construction
    // ------------------------------------------------------------------

    fun buildStateJson(snapshot: MqttDataSnapshot, activeSensors: List<SensorDescriptor>): String {
        val activeFields = activeSensors.map { it.jsonField }.toSet()
        return buildJsonObject {
            if ("battery" in activeFields) {
                snapshot.batteryPercent?.let { put("battery", it) }
            }
            if ("heart_rate" in activeFields) {
                snapshot.heartRateBpm?.let { put("heart_rate", it) }
            }
            if ("resting_heart_rate" in activeFields) {
                snapshot.restingHeartRateBpm?.let { put("resting_heart_rate", it) }
            }
            if ("spo2" in activeFields) {
                snapshot.spo2Percent?.let { put("spo2", it) }
            }
            if ("hrv" in activeFields) {
                snapshot.hrvMs?.let { put("hrv", it) }
            }
            if ("stress" in activeFields) {
                snapshot.stressScore?.let { put("stress", it) }
            }
            if ("temperature" in activeFields) {
                snapshot.temperatureCelsius?.let { put("temperature", it) }
            }
            if ("steps" in activeFields) {
                snapshot.steps?.let { put("steps", it) }
            }
            if ("calories" in activeFields) {
                snapshot.caloriesKcal?.let { put("calories", it) }
            }
            if ("distance" in activeFields) {
                snapshot.distanceMeters?.let { put("distance", it) }
            }
            if ("active_minutes" in activeFields) {
                snapshot.activeMinutes?.let { put("active_minutes", it) }
            }
            if ("sleep_duration" in activeFields) {
                snapshot.sleepTotalMinutes?.let { put("sleep_duration", it) }
            }
            if ("sleep_score" in activeFields) {
                snapshot.sleepScore?.let { put("sleep_score", it) }
            }
            if ("sleep_deep" in activeFields) {
                snapshot.sleepDeepMinutes?.let { put("sleep_deep", it) }
            }
            if ("sleep_light" in activeFields) {
                snapshot.sleepLightMinutes?.let { put("sleep_light", it) }
            }
            if ("sleep_rem" in activeFields) {
                snapshot.sleepRemMinutes?.let { put("sleep_rem", it) }
            }
            if ("sleep_awake" in activeFields) {
                snapshot.sleepAwakeMinutes?.let { put("sleep_awake", it) }
            }
            if ("blood_pressure_systolic" in activeFields) {
                snapshot.bpSystolicMmhg?.let { put("blood_pressure_systolic", it) }
            }
            if ("blood_pressure_diastolic" in activeFields) {
                snapshot.bpDiastolicMmhg?.let { put("blood_pressure_diastolic", it) }
            }
            if ("blood_sugar" in activeFields) {
                snapshot.bloodSugarMmol?.let { put("blood_sugar", it) }
            }
            if ("respiratory_rate" in activeFields) {
                snapshot.respiratoryRateBrpm?.let { put("respiratory_rate", it) }
            }
            if ("vo2_max" in activeFields) {
                snapshot.vo2Max?.let { put("vo2_max", it) }
            }
            if ("workout_active" in activeFields) {
                put("workout_active", snapshot.workoutActive)
            }
            if ("workout_type" in activeFields) {
                snapshot.workoutType?.let { put("workout_type", it) }
            }
            if ("workout_duration" in activeFields) {
                snapshot.workoutDurationSeconds?.let { put("workout_duration", it) }
            }
            if ("last_sync" in activeFields) {
                snapshot.lastSyncIso?.let { put("last_sync", it) }
            }
        }.toString()
    }

    // ------------------------------------------------------------------
    // Combined message builder
    // ------------------------------------------------------------------

    fun buildMqttMessages(
        device: DeviceEntity,
        snapshot: MqttDataSnapshot,
        prefs: MqttPrefs,
        forceDiscovery: Boolean = false,
    ): Pair<List<MqttMessage>, Set<String>> {
        val identity = resolveDeviceIdentity(device, prefs)
        val sensors = sensorsFor(device, snapshot)
        val sensorKeys = sensors.map { it.key }.toSet()

        val messages = mutableListOf<MqttMessage>()

        val needsDiscovery = forceDiscovery ||
            sensorKeys != prefs.discoveredSensorKeys ||
            identity.slug != prefs.lastDiscoveredDeviceSlug

        if (needsDiscovery) {
            val staleKeys = prefs.discoveredSensorKeys - sensorKeys
            if (staleKeys.isNotEmpty()) {
                messages += buildRemovalMessages(
                    prefs.normalizedDiscoveryPrefix,
                    identity.uniquePrefix,
                    staleKeys,
                )
            }
            messages += buildDiscoveryMessages(identity, sensors, prefs)
        }

        messages += MqttMessage(
            topic = stateTopic(prefs.normalizedBaseTopic, identity),
            payload = buildStateJson(snapshot, sensors),
            qos = 1,
            retain = true,
        )

        return messages to sensorKeys
    }
}
