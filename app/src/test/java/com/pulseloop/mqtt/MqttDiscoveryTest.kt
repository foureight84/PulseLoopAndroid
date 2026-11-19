package com.pulseloop.mqtt

import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttDiscoveryTest {

    private val colmiCaps = listOf(
        WearableCapability.BATTERY,
        WearableCapability.HEART_RATE,
        WearableCapability.SPO2,
        WearableCapability.STEPS,
        WearableCapability.SLEEP,
    )

    private fun testRing(
        mac: String = "AA:BB:CC:DD:EE:FF",
        name: String = "Colmi R02",
        type: RingDeviceType = RingDeviceType.COLMI_R02,
        caps: List<WearableCapability> = colmiCaps,
    ) = DeviceEntity(
        id = "device-${mac.lowercase().replace(":", "")}",
        name = name,
        advertisedName = null,
        peripheralIdentifier = mac,
        bleAddressHint = null,
        batteryPercent = 82,
        stateRaw = "CONNECTED",
        deviceTypeRaw = type.name,
        wearableModelID = null,
        capabilitiesRaw = caps.joinToString(",") { it.key },
        lastConnectedAt = 1_700_000_000_000L,
        lastDisconnectedAt = null,
        lastSyncAt = 1_700_000_100_000L,
        lastFullSyncAt = null,
        firmwareVersion = "1.0.0",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_100_000L,
    )

    private fun keys(device: DeviceEntity, snapshot: MqttDataSnapshot = MqttDataSnapshot()): Set<String> =
        MqttDiscovery.sensorsFor(device, snapshot).map { it.key }.toSet()

    // ------------------------------------------------------------------
    // 1. Capability gating on a Colmi R02-like ring
    // ------------------------------------------------------------------

    @Test
    fun `colmi ring exposes supported sensors`() {
        val device = testRing()
        val sensorKeys = keys(device)

        assertTrue("battery" in sensorKeys)
        assertTrue("heart_rate" in sensorKeys)
        assertTrue("spo2" in sensorKeys)

        assertTrue("steps" in sensorKeys)
        assertTrue("calories" in sensorKeys)
        assertTrue("distance" in sensorKeys)
        assertTrue("active_minutes" in sensorKeys)

        assertTrue("sleep_duration" in sensorKeys)
        assertTrue("sleep_score" in sensorKeys)
        assertTrue("sleep_deep" in sensorKeys)
        assertTrue("sleep_light" in sensorKeys)
        assertTrue("sleep_awake" in sensorKeys)

        assertTrue("workout_active" in sensorKeys)
        assertTrue("workout_type" in sensorKeys)
        assertTrue("workout_duration" in sensorKeys)

        assertTrue("last_sync" in sensorKeys)
    }

    @Test
    fun `colmi ring omits unsupported sensors`() {
        val device = testRing()
        val sensorKeys = keys(device)

        val unsupported = setOf(
            "resting_heart_rate",
            "temperature",
            "hrv",
            "stress",
            "sleep_rem",
            "blood_pressure_systolic",
            "blood_pressure_diastolic",
            "blood_sugar",
            "respiratory_rate",
            "vo2_max",
        )
        unsupported.forEach { key ->
            assertFalse("Expected '$key' to be absent", key in sensorKeys)
        }
    }

    // ------------------------------------------------------------------
    // 2. Derived stress #67 guard
    // ------------------------------------------------------------------

    @Test
    fun `stress capability without stress measurement kind omits stress sensor`() {
        val device = testRing(caps = colmiCaps + WearableCapability.STRESS)
        val sensorKeys = keys(device, MqttDataSnapshot(availableKinds = emptySet()))
        assertFalse("stress" in sensorKeys)
    }

    @Test
    fun `stress capability with stress measurement kind includes stress sensor`() {
        val device = testRing(caps = colmiCaps + WearableCapability.STRESS)
        val sensorKeys = keys(
            device,
            MqttDataSnapshot(availableKinds = setOf(MeasurementKind.STRESS.name), stressScore = 38),
        )
        assertTrue("stress" in sensorKeys)
    }

    // ------------------------------------------------------------------
    // 3. Respiratory rate guard
    // ------------------------------------------------------------------

    @Test
    fun `ycbt ring without respiratory measurement kind omits respiratory_rate`() {
        val device = testRing(
            mac = "11:22:33:44:55:66",
            name = "YCBT Ring",
            type = RingDeviceType.YCBT,
        )
        val sensorKeys = keys(device, MqttDataSnapshot(availableKinds = emptySet()))
        assertFalse("respiratory_rate" in sensorKeys)
    }

    @Test
    fun `ycbt ring with respiratory measurement kind includes respiratory_rate`() {
        val device = testRing(
            mac = "11:22:33:44:55:66",
            name = "YCBT Ring",
            type = RingDeviceType.YCBT,
        )
        val sensorKeys = keys(
            device,
            MqttDataSnapshot(
                availableKinds = setOf(MeasurementKind.RESPIRATORY_RATE.name),
                respiratoryRateBrpm = 15.2,
            ),
        )
        assertTrue("respiratory_rate" in sensorKeys)
    }

    // ------------------------------------------------------------------
    // 4. Multi-device isolation
    // ------------------------------------------------------------------

    @Test
    fun `two rings with same name produce distinct identities and topics`() {
        val prefs = MqttPrefs(enabled = true, host = "192.168.1.50")
        val ringA = testRing(mac = "AA:BB:CC:DD:EE:01", name = "Colmi R11")
        val ringB = testRing(mac = "AA:BB:CC:DD:EE:02", name = "Colmi R11")

        val idA = MqttDiscovery.resolveDeviceIdentity(ringA, prefs)
        val idB = MqttDiscovery.resolveDeviceIdentity(ringB, prefs)

        assertNotEquals(idA.uniquePrefix, idB.uniquePrefix)
        assertNotEquals(idA.slug, idB.slug)

        val stateTopicA = MqttDiscovery.stateTopic(prefs.normalizedBaseTopic, idA)
        val stateTopicB = MqttDiscovery.stateTopic(prefs.normalizedBaseTopic, idB)
        assertNotEquals(stateTopicA, stateTopicB)

        val sample = MqttDiscovery.sensorsFor(ringA, MqttDataSnapshot()).first()
        val configTopicA = MqttDiscovery.discoveryTopic(prefs.normalizedDiscoveryPrefix, sample, idA)
        val configTopicB = MqttDiscovery.discoveryTopic(prefs.normalizedDiscoveryPrefix, sample, idB)
        assertNotEquals(configTopicA, configTopicB)
    }

    @Test
    fun `deviceNameOverride changes displayName and slug but keeps uniquePrefix`() {
        val ring = testRing(mac = "AA:BB:CC:DD:EE:03", name = "Colmi R11")

        val base = MqttDiscovery.resolveDeviceIdentity(ring, MqttPrefs())
        val overridden = MqttDiscovery.resolveDeviceIdentity(
            ring,
            MqttPrefs(deviceNameOverride = "Bedroom Ring"),
        )

        assertEquals(base.uniquePrefix, overridden.uniquePrefix)
        assertNotEquals(base.slug, overridden.slug)
        assertEquals("Bedroom Ring", overridden.displayName)
        assertEquals("bedroom_ring", overridden.slug)
    }

    // ------------------------------------------------------------------
    // 5. Discovery JSON & state JSON structure
    // ------------------------------------------------------------------

    @Test
    fun `discovery payload has required HA fields`() {
        val prefs = MqttPrefs(enabled = true, host = "192.168.1.50")
        val ring = testRing()
        val identity = MqttDiscovery.resolveDeviceIdentity(ring, prefs)
        val sensors = MqttDiscovery.sensorsFor(ring, MqttDataSnapshot())

        val discoveryMessages = MqttDiscovery.buildDiscoveryMessages(identity, sensors, prefs)
        val batteryIdx = sensors.indexOfFirst { it.key == "battery" }
        val batteryMsg = discoveryMessages[batteryIdx]
        assertTrue(batteryMsg.retain)
        assertEquals(1, batteryMsg.qos)

        val payload: JsonObject = Json.parseToJsonElement(batteryMsg.payload.decodeToString()).jsonObject
        assertEquals("${identity.uniquePrefix}_battery", payload["unique_id"]?.jsonPrimitive?.content)
        assertEquals("${identity.slug}_battery", payload["object_id"]?.jsonPrimitive?.content)
        assertEquals(
            MqttDiscovery.stateTopic(prefs.normalizedBaseTopic, identity),
            payload["state_topic"]?.jsonPrimitive?.content,
        )

        val deviceJson = payload["device"]?.jsonObject
        assertNotNull(deviceJson)
        val identifiers = deviceJson?.get("identifiers")?.jsonArray
        assertEquals(listOf(identity.uniquePrefix), identifiers?.map { it.jsonPrimitive.content })

        val connections = deviceJson?.get("connections")?.jsonArray
        val firstConnection = connections?.firstOrNull() as? JsonArray
        assertNotNull(firstConnection)
        assertEquals("mac", (firstConnection?.getOrNull(0) as? JsonPrimitive)?.content)
        assertEquals("aa:bb:cc:dd:ee:ff", (firstConnection?.getOrNull(1) as? JsonPrimitive)?.content)
    }

    @Test
    fun `workout_active uses binary_sensor topic with ON-OFF payloads`() {
        val prefs = MqttPrefs(enabled = true, host = "192.168.1.50")
        val ring = testRing()
        val identity = MqttDiscovery.resolveDeviceIdentity(ring, prefs)
        val sensors = MqttDiscovery.sensorsFor(ring, MqttDataSnapshot())

        val workout = sensors.first { it.key == "workout_active" }
        val topic = MqttDiscovery.discoveryTopic(prefs.normalizedDiscoveryPrefix, workout, identity)
        assertTrue(topic.startsWith("homeassistant/binary_sensor/${identity.uniquePrefix}/workout_active/config"))

        val messages = MqttDiscovery.buildDiscoveryMessages(identity, listOf(workout), prefs)
        val payload = Json.parseToJsonElement(messages.single().payload.decodeToString()).jsonObject
        assertEquals("ON", payload["payload_on"]?.jsonPrimitive?.content)
        assertEquals("OFF", payload["payload_off"]?.jsonPrimitive?.content)
        assertEquals("running", payload["device_class"]?.jsonPrimitive?.content)
    }

    @Test
    fun `state json serializes present fields and omits nulls`() {
        val ring = testRing()
        val snapshot = MqttDataSnapshot(
            batteryPercent = 82,
            heartRateBpm = 71,
            steps = 5432,
            workoutActive = false,
            lastSyncIso = "2026-11-15T07:00:00Z",
        )
        val sensors = MqttDiscovery.sensorsFor(ring, snapshot)

        val json = MqttDiscovery.buildStateJson(snapshot, sensors)
        val obj = Json.parseToJsonElement(json).jsonObject

        assertEquals(82, obj["battery"]?.jsonPrimitive?.int)
        assertEquals(71, obj["heart_rate"]?.jsonPrimitive?.int)
        assertEquals(5432, obj["steps"]?.jsonPrimitive?.int)
        assertEquals(false, obj["workout_active"]?.jsonPrimitive?.boolean)
        assertEquals("2026-11-15T07:00:00Z", obj["last_sync"]?.jsonPrimitive?.content)

        // Null fields must be omitted entirely
        assertFalse(obj.containsKey("spo2"))
        assertFalse(obj.containsKey("temperature"))
        assertFalse(obj.containsKey("workout_type"))
        assertFalse(obj.containsKey("workout_duration"))
    }

    // ------------------------------------------------------------------
    // 6. Incremental discovery & stale sensor cleanup
    // ------------------------------------------------------------------

    @Test
    fun `incremental build with matching discovered keys and slug emits only one state message`() {
        val ring = testRing()
        val snapshot = MqttDataSnapshot(batteryPercent = 82)
        val basePrefs = MqttPrefs(enabled = true, host = "192.168.1.50")
        val identity = MqttDiscovery.resolveDeviceIdentity(ring, basePrefs)
        val sensors = MqttDiscovery.sensorsFor(ring, snapshot)
        val prefs = basePrefs.copy(
            discoveredSensorKeys = sensors.map { it.key }.toSet(),
            lastDiscoveredDeviceSlug = identity.slug,
        )

        val (messages, keys) = MqttDiscovery.buildMqttMessages(
            device = ring,
            snapshot = snapshot,
            prefs = prefs,
            forceDiscovery = false,
        )

        assertEquals(sensors.map { it.key }.toSet(), keys)
        assertEquals(1, messages.size)
        assertTrue(messages[0].retain)
        assertEquals(
            MqttDiscovery.stateTopic(prefs.normalizedBaseTopic, identity),
            messages[0].topic,
        )
        assertTrue(messages[0].payload.isNotEmpty())
    }

    @Test
    fun `stale discovered key triggers removal message plus discovery and state updates`() {
        val ring = testRing()
        val snapshot = MqttDataSnapshot(batteryPercent = 82)
        val basePrefs = MqttPrefs(enabled = true, host = "192.168.1.50")
        val identity = MqttDiscovery.resolveDeviceIdentity(ring, basePrefs)
        val sensors = MqttDiscovery.sensorsFor(ring, snapshot)
        val prefs = basePrefs.copy(
            discoveredSensorKeys = sensors.map { it.key }.toSet() + "removed_sensor",
            lastDiscoveredDeviceSlug = identity.slug,
        )

        val (messages, _) = MqttDiscovery.buildMqttMessages(
            device = ring,
            snapshot = snapshot,
            prefs = prefs,
            forceDiscovery = false,
        )

        val staleConfigTopic = "homeassistant/sensor/${identity.uniquePrefix}/removed_sensor/config"
        val removalMessages = messages.filter { it.topic == staleConfigTopic }
        assertEquals(1, removalMessages.size)
        assertTrue(removalMessages[0].retain)
        assertTrue(removalMessages[0].payload.isEmpty())

        val messageTopics = messages.map { it.topic }.toSet()
        sensors.forEach { sensor ->
            val expectedTopic = MqttDiscovery.discoveryTopic(
                prefs.normalizedDiscoveryPrefix,
                sensor,
                identity,
            )
            assertTrue("Missing discovery message for ${sensor.key}", expectedTopic in messageTopics)
        }

        val stateMessages = messages.filter {
            it.topic == MqttDiscovery.stateTopic(prefs.normalizedBaseTopic, identity)
        }
        assertEquals(1, stateMessages.size)
        assertTrue(stateMessages[0].retain)
        assertTrue(stateMessages[0].payload.isNotEmpty())
    }
}
