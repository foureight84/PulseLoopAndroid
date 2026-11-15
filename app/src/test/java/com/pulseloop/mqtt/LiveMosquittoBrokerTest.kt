package com.pulseloop.mqtt

import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.ring.RingDeviceType
import com.pulseloop.ring.WearableCapability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Integration tests exercised against a real local Mosquitto broker (OrbStack / localhost:1883).
 *
 * Skipped during offline CI runs unless either `-Dpulseloop.test.liveBroker=true` or
 * `PULSELOOP_LIVE_BROKER=true` is supplied.
 */
class LiveMosquittoBrokerTest {

    private fun isLiveEnabled(): Boolean =
        System.getProperty("pulseloop.test.liveBroker") == "true" ||
            System.getenv("PULSELOOP_LIVE_BROKER") == "true"

    private fun phase(): String = System.getenv("PULSELOOP_LIVE_PHASE") ?: "all"

    private fun sampleRing() = DeviceEntity(
        id = "live-ring-01",
        name = "Colmi R11",
        advertisedName = "R11_A1B2",
        peripheralIdentifier = "AA:BB:CC:DD:A1:B2",
        bleAddressHint = null,
        batteryPercent = 87,
        stateRaw = "CONNECTED",
        deviceTypeRaw = RingDeviceType.COLMI_R02.name,
        wearableModelID = "colmi-r11",
        capabilitiesRaw = listOf(
            WearableCapability.BATTERY,
            WearableCapability.HEART_RATE,
            WearableCapability.SPO2,
            WearableCapability.STEPS,
            WearableCapability.SLEEP,
            WearableCapability.STRESS,
            WearableCapability.HRV,
            WearableCapability.TEMPERATURE,
        ).joinToString(",") { it.key },
        lastConnectedAt = 1_763_190_000_000L,
        lastDisconnectedAt = null,
        lastSyncAt = 1_763_190_100_000L,
        lastFullSyncAt = 1_763_190_100_000L,
        firmwareVersion = "2.1.4",
        createdAt = 1_763_190_000_000L,
        updatedAt = 1_763_190_100_000L,
    )

    private fun anonConfig(clientId: String = "pl_live_test") = MqttEndpointConfig(
        host = System.getenv("PULSELOOP_MQTT_HOST") ?: "127.0.0.1",
        port = System.getenv("PULSELOOP_MQTT_PORT")?.toIntOrNull() ?: 1883,
        useTls = false,
        username = "",
        password = "",
        clientId = clientId,
    )

    @Test
    fun liveBroker_handshakePublishAndSessionPool() = runBlocking {
        assumeTrue("Live Mosquitto test skipped (set PULSELOOP_LIVE_BROKER=true)", isLiveEnabled())
        val mode = phase()
        assumeTrue("Auth-only phase", mode == "all" || mode == "publish" || mode == "remove")

        val ring = sampleRing()
        val prefs = MqttPrefs(
            enabled = true,
            host = "127.0.0.1",
            port = 1883,
            discoveryPrefix = "homeassistant",
            baseTopic = "pulseloop",
        )
        val identity = MqttDiscovery.resolveDeviceIdentity(ring, prefs)

        if (mode == "all" || mode == "publish") {
            // 1. Handshake & PINGREQ/PINGRESP test
            MqttClient.testConnection(anonConfig("pl_ping_test"))

            // 2. Full HA discovery + state publish
            val initialSnapshot = MqttDataSnapshot(
                batteryPercent = 87,
                heartRateBpm = 72,
                spo2Percent = 98,
                hrvMs = 46.5,
                stressScore = 31,
                temperatureCelsius = 36.4,
                steps = 4320,
                caloriesKcal = 1840.0,
                distanceMeters = 3150,
                activeMinutes = 42,
                sleepTotalMinutes = 435,
                sleepScore = 84,
                sleepDeepMinutes = 110,
                sleepLightMinutes = 290,
                sleepAwakeMinutes = 35,
                workoutActive = false,
                lastSyncIso = "2026-11-15T07:50:00Z",
                availableKinds = setOf(MeasurementKind.STRESS.name),
            )
            val (discoveryMsgs, keys) = MqttDiscovery.buildMqttMessages(
                device = ring,
                snapshot = initialSnapshot,
                prefs = prefs,
                forceDiscovery = true,
            )
            assertTrue(keys.size >= 15)
            MqttClient.publishAll(anonConfig("pl_pub_test"), discoveryMsgs)

            // 3. Consecutive pooled live workout updates over a single warm TCP session
            val pool = MqttSessionPool()
            try {
                val sensors = MqttDiscovery.sensorsFor(ring, initialSnapshot)
            assertTrue(sensors.any { it.key == "stress" })
                val updatedPrefs = prefs.copy(
                    discoveredSensorKeys = keys,
                    lastDiscoveredDeviceSlug = identity.slug,
                )
                val liveTick1 = initialSnapshot.copy(
                    heartRateBpm = 142,
                    workoutActive = true,
                    workoutType = "Indoor Cross Trainer",
                    workoutDurationSeconds = 120,
                )
                val liveTick2 = liveTick1.copy(
                    heartRateBpm = 148,
                    workoutDurationSeconds = 125,
                )
                val (msgs1, _) = MqttDiscovery.buildMqttMessages(ring, liveTick1, updatedPrefs)
                val (msgs2, _) = MqttDiscovery.buildMqttMessages(ring, liveTick2, updatedPrefs)
                assertEquals(1, msgs1.size)
                assertEquals(1, msgs2.size)

                val poolCfg = anonConfig("pl_pool_live")
                pool.publish(poolCfg, msgs1)
                pool.publish(poolCfg, msgs2)
            } finally {
                pool.close()
            }
        }

        if (mode == "all" || mode == "remove") {
            val allKeys = MqttDiscovery.sensorsFor(
                ring,
                MqttDataSnapshot(availableKinds = setOf(MeasurementKind.STRESS.name)),
            ).map { it.key }
            val removalMsgs = MqttDiscovery.buildRemovalMessages(
                prefs.normalizedDiscoveryPrefix,
                identity.uniquePrefix,
                allKeys,
            ) + MqttMessage(
                topic = MqttDiscovery.stateTopic(prefs.normalizedBaseTopic, identity),
                payload = ByteArray(0),
                qos = 1,
                retain = true,
            )
            MqttClient.publishAll(anonConfig("pl_remove_test"), removalMsgs)
        }
    }

    @Test
    fun liveBroker_usernamePasswordAuthAcceptedAndBadPasswordRejected() = runBlocking {
        assumeTrue("Live Mosquitto auth test skipped", isLiveEnabled() && phase() == "auth")

        val validCfg = MqttEndpointConfig(
            host = "127.0.0.1",
            port = 1883,
            useTls = false,
            username = "pulseloop",
            password = "secret_password_123",
            clientId = "pl_auth_ok",
        )
        MqttClient.testConnection(validCfg)
        MqttClient.publishAll(
            validCfg,
            listOf(MqttMessage("pulseloop/auth_test", """{"ok":true}""", qos = 1, retain = false)),
        )

        val badCfg = validCfg.copy(password = "wrong_password", clientId = "pl_auth_bad")
        try {
            MqttClient.testConnection(badCfg)
            fail("Expected MqttException when Mosquitto rejects wrong password")
        } catch (e: MqttException) {
            assertTrue(
                "Expected CONNACK rejection message, got: ${e.message}",
                e.message?.contains("rejected", ignoreCase = true) == true ||
                    e.message?.contains("username or password", ignoreCase = true) == true ||
                    e.message?.contains("authorized", ignoreCase = true) == true,
            )
        }
    }
}
