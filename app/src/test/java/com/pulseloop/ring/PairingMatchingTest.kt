package com.pulseloop.ring

import com.pulseloop.ring.WearableCapability.Companion.toCsv
import com.pulseloop.wearables.WearableModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests coordinator name matching, exact-model identification (iOS #49), device type
 * detection, and wearable capability CSV serialization. Pure logic — no BLE/hardware
 * dependency.
 */
class PairingMatchingTest {

    private val noAdv = AdvertisementInfo(emptyList(), null)

    private fun colmiMatches(name: String): Boolean =
        ColmiCoordinator.matches(name, noAdv)

    @Test
    fun `device type enum has expected entries`() {
        val types = RingDeviceType.entries.map { it.name }
        assertTrue(types.contains("JRING"))
        assertTrue(types.contains("COLMI_R02"))
        assertTrue(types.contains("YCBT"))
    }

    @Test
    fun `device type display names are meaningful`() {
        assertEquals("SMART_RING", RingDeviceType.JRING.displayName)
        assertEquals("Colmi / Yawell ring", RingDeviceType.COLMI_R02.displayName)
        assertEquals("YCBT / SmartHealth ring", RingDeviceType.YCBT.displayName)
    }

    // ── Coordinator name matching (delegated to the WearableModel catalog, iOS #49) ────

    @Test
    fun `colmi family names match`() {
        val names = listOf(
            "R02_A1B2", "R03_1234", "R06_FFFF", "COLMI R07_9", "R09_00AA",
            "COLMI R10_xyz", "COLMI R12_x", "R05_1A2B", "R10_DEAD", "R11_BEEF",
            "R11C_BEEF", "H59_anything",
        )
        for (name in names) {
            assertTrue("expected Colmi match for $name", colmiMatches(name))
        }
    }

    @Test
    fun `non-colmi names do not match`() {
        for (name in listOf("SMART_RING", "R10M FCF4", "Mi Band 5", "Galaxy Watch", "R0X_NOPE", "Random")) {
            assertFalse("did not expect Colmi match for $name", colmiMatches(name))
        }
    }

    @Test
    fun `colmi matches by service uuid for generic names`() {
        val adv = AdvertisementInfo(listOf(ColmiUUIDs.SERVICE_V1), null)
        assertTrue(ColmiCoordinator.matches("Unlabeled", adv))
    }

    @Test
    fun `jring does not claim colmi names`() {
        assertFalse(JringCoordinator.matches("R02_A1B2", noAdv))
        assertTrue(JringCoordinator.matches("SMART_RING", noAdv))
    }

    @Test
    fun `R10M is claimed only by YCBT`() {
        for (name in listOf("R10M FCF4", "R10M_FCF4")) {
            assertTrue(YCBTCoordinator.matches(name, noAdv))
            assertFalse(ColmiCoordinator.matches(name, noAdv))
            assertFalse(JringCoordinator.matches(name, noAdv))
        }
    }

    @Test
    fun `R10M catalog entry uses the discoverable retail name`() {
        assertTrue(WearableModel.R10M in WearableModel.CATALOG)
        assertEquals("LittleMeatball", WearableModel.R10M.brand)
        assertEquals("LittleMeatball R10M", WearableModel.R10M.displayName)
    }

    @Test
    fun `YCBT find device is enabled only by the support bitmap`() {
        assertFalse(YCBTCoordinator.capabilities.contains(WearableCapability.FIND_DEVICE))
        assertTrue(YCBTCoordinator.bitmapGatedCapabilities.contains(WearableCapability.FIND_DEVICE))
        assertFalse(YCBTCoordinator.capabilities.contains(WearableCapability.SPO2_HISTORY))
    }

    @Test
    fun `YCBT manufacturer marker does not override QRing service`() {
        val manufacturer = byteArrayOf(0x10, 0x78)
        assertFalse(YCBTCoordinator.matches("Unlabeled", AdvertisementInfo(emptyList(), manufacturer)))
        assertFalse(YCBTCoordinator.matches("Unlabeled", AdvertisementInfo(listOf(ColmiUUIDs.SERVICE_V2), manufacturer)))
    }

    @Test
    fun `catalog families all have a registered coordinator`() {
        val registeredTypes = setOf(JringCoordinator.deviceType, ColmiCoordinator.deviceType, YCBTCoordinator.deviceType)
        for (model in WearableModel.CATALOG) {
            assertTrue("no coordinator for ${model.displayName}", registeredTypes.contains(model.family))
        }
    }

    // ── Exact-model identification (iOS #49) ────────────────────────────

    @Test
    fun `advertised names resolve to exact models`() {
        val expected = mapOf(
            "SMART_RING" to "jring",
            "R02_A1B2" to "colmi-r02",
            "R03_1234" to "colmi-r03",
            "R06_FFFF" to "colmi-r06",
            "COLMI R07_9" to "colmi-r07",
            "R09_00AA" to "colmi-r09",
            "COLMI R10_xyz" to "colmi-r10",
            "R11C_BEEF" to "colmi-r11",
            "COLMI R12_x" to "colmi-r12",
            "R05_1A2B" to "yawell-r05",
            "R10_DEAD" to "yawell-r10",
            "R11_BEEF" to "yawell-r11",
            "H59_anything" to "h59",
            "R10M FCF4" to "r10m",
            "R10M_FCF4" to "r10m",
        )
        for ((name, modelID) in expected) {
            assertEquals(name, modelID, WearableModel.modelForAdvertisedName(name)?.id)
        }
    }

    // ── OS-bond gating (only the R09 needs a bond on Android) ───────────

    @Test
    fun `only the R09 requires an OS bond`() {
        assertTrue("R09 must require an OS bond", WearableModel.COLMI_R09.requiresOsBond)
        // Every other catalog model works GATT-only like iOS — no bond, no pairing prompt.
        val bonded = WearableModel.CATALOG.filter { it.requiresOsBond }.map { it.id }
        assertEquals(listOf("colmi-r09"), bonded)
    }

    @Test
    fun `bond decision resolves from the advertised name`() {
        // The R09 advertises as R09_xxxx → gate bonds it; the R10 advertises as COLMI R10_xxxx
        // → gate leaves it unbonded. This is exactly the input RingBLEClient.bondActiveDevice uses.
        assertTrue(WearableModel.modelForAdvertisedName("R09_00AA")?.requiresOsBond == true)
        assertFalse(WearableModel.modelForAdvertisedName("COLMI R10_xyz")?.requiresOsBond == true)
        assertFalse(WearableModel.modelForAdvertisedName("R02_A1B2")?.requiresOsBond == true)
    }

    @Test
    fun `detected model overrides carousel selection`() {
        val model = WearableModel.resolve(
            advertisedName = "COLMI R10_xyz",
            selectedModelID = WearableModel.COLMI_R02.id,
            family = RingDeviceType.COLMI_R02,
        )
        assertEquals(WearableModel.COLMI_R10.id, model?.id)
    }

    @Test
    fun `carousel selection is fallback for generic advertisement`() {
        val model = WearableModel.resolve(
            advertisedName = "Unlabeled",
            selectedModelID = WearableModel.COLMI_R12.id,
            family = RingDeviceType.COLMI_R02,
        )
        assertEquals(WearableModel.COLMI_R12.id, model?.id)
    }

    @Test
    fun `unknown legacy colmi has no exact model`() {
        assertNull(WearableModel.resolve(advertisedName = null, selectedModelID = null, family = RingDeviceType.COLMI_R02))
        assertEquals("Colmi / Yawell ring", RingDeviceType.COLMI_R02.displayName)
    }

    @Test
    fun `colmi r11 reuses yawell r11 image`() {
        assertEquals(WearableModel.YAWELL_R11.imageRes, WearableModel.COLMI_R11.imageRes)
    }

    @Test
    fun `device type valueOf roundtrip`() {
        assertEquals(RingDeviceType.JRING, RingDeviceType.valueOf("JRING"))
        assertEquals(RingDeviceType.COLMI_R02, RingDeviceType.valueOf("COLMI_R02"))
    }

    @Test
    fun `connection state enum has all expected states`() {
        val states = RingConnectionState.entries.map { it.name }
        assertTrue(states.contains("IDLE"))
        assertTrue(states.contains("SCANNING"))
        assertTrue(states.contains("CONNECTING"))
        assertTrue(states.contains("CONNECTED"))
        assertTrue(states.contains("DISCONNECTED"))
    }

    // ── WearableCapability CSV ──────────────────────────────────────────

    @Test
    fun `wearable capability csv roundtrip`() {
        val caps = setOf(WearableCapability.HEART_RATE, WearableCapability.SPO2)
        val csv = caps.toCsv()
        val back = WearableCapability.fromCsv(csv)
        assertEquals(caps, back)
    }

    @Test
    fun `wearable capability csv handles empty`() {
        assertEquals(emptySet<WearableCapability>(), WearableCapability.fromCsv(""))
        assertEquals("", emptySet<WearableCapability>().toCsv())
    }

    @Test
    fun `wearable capability csv handles single entry`() {
        val caps = setOf(WearableCapability.BATTERY)
        val csv = caps.toCsv()
        assertEquals("battery", csv)
        assertEquals(caps, WearableCapability.fromCsv(csv))
    }

    @Test
    fun `wearable capability csv handles all entries`() {
        val all = WearableCapability.entries.toSet()
        val csv = all.toCsv()
        val back = WearableCapability.fromCsv(csv)
        assertEquals(all, back)
    }

    @Test
    fun `capability keys are stable`() {
        assertEquals("heartRate", WearableCapability.HEART_RATE.key)
        assertEquals("spo2", WearableCapability.SPO2.key)
        assertEquals("steps", WearableCapability.STEPS.key)
        assertEquals("sleep", WearableCapability.SLEEP.key)
        assertEquals("battery", WearableCapability.BATTERY.key)
        assertEquals("stress", WearableCapability.STRESS.key)
        assertEquals("hrv", WearableCapability.HRV.key)
        assertEquals("temperature", WearableCapability.TEMPERATURE.key)
    }

    @Test
    fun `default capability set is Jring base`() {
        val defaultCaps = WearableCapability.fromCsv("")
        assertTrue(defaultCaps.isEmpty())
    }
}
