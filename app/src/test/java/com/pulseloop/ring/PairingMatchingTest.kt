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
        assertTrue(types.contains("TK5"))
        assertTrue(types.contains("COLMI_SMART_HEALTH"))
        assertTrue(types.contains("LUCK_RING"))
        assertTrue(types.contains("CRP"))
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
            "R02_A1B2", "R03_1234", "R06_FFFF", "COLMI R07_9", "R08_1234", "R09_00AA",
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
    fun `jring does not claim SMART_RING when the device also advertises a colmi service (issue 29)`() {
        // Some Colmi/Yawell R11 units advertise the generic factory name "SMART_RING" while
        // still exposing Colmi's own GATT service UUIDs. JringCoordinator must defer to
        // ColmiCoordinator for those instead of claiming the device by name alone.
        val colmiV1 = AdvertisementInfo(listOf(ColmiUUIDs.SERVICE_V1), null)
        val colmiV2 = AdvertisementInfo(listOf(ColmiUUIDs.SERVICE_V2), null)
        assertFalse(JringCoordinator.matches("SMART_RING", colmiV1))
        assertFalse(JringCoordinator.matches("SMART_RING", colmiV2))
        assertTrue(ColmiCoordinator.matches("SMART_RING", colmiV1))
        assertTrue(ColmiCoordinator.matches("SMART_RING", colmiV2))
        // A genuine Jring device (no Colmi service present) is unaffected.
        assertTrue(JringCoordinator.matches("SMART_RING", noAdv))
    }

    @Test
    fun `catalog families all have a registered coordinator`() {
        val registeredTypes = setOf(
            JringCoordinator.deviceType, ColmiCoordinator.deviceType, YCBTCoordinator.deviceType,
            TK5Coordinator.deviceType, ColmiSmartHealthCoordinator.deviceType,
            LuckRingCoordinator.deviceType, CRPCoordinator.deviceType,
        )
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
            "R08_1234" to "colmi-r08",
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

    // ── OS-bond gating (only the R09 and R11/Yawell R11 need a bond on Android) ───────────

    @Test
    fun `only the R09 and R11 require an OS bond`() {
        assertTrue("R09 must require an OS bond", WearableModel.COLMI_R09.requiresOsBond)
        assertTrue("R11 must require an OS bond", WearableModel.COLMI_R11.requiresOsBond)
        assertTrue("Yawell R11 must require an OS bond", WearableModel.YAWELL_R11.requiresOsBond)
        // Every other catalog model works GATT-only like iOS — no bond, no pairing prompt.
        val bonded = WearableModel.CATALOG.filter { it.requiresOsBond }.map { it.id }.sorted()
        assertEquals(listOf("colmi-r09", "colmi-r11", "yawell-r11"), bonded)
    }

    @Test
    fun `bond decision resolves from the advertised name`() {
        // The R09/R11 advertise as R09_xxxx/R11C_xxxx → gate bonds them; the R10 advertises as
        // COLMI R10_xxxx → gate leaves it unbonded. This is exactly the input
        // RingBLEClient.bondActiveDevice uses.
        assertTrue(WearableModel.modelForAdvertisedName("R09_00AA")?.requiresOsBond == true)
        assertTrue(WearableModel.modelForAdvertisedName("R11C_BEEF")?.requiresOsBond == true)
        assertTrue(WearableModel.modelForAdvertisedName("R11_BEEF")?.requiresOsBond == true)
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

    // ── YCBT family matching (iOS #82: TK5 + SmartHealth-Colmi) ─────────

    @Test
    fun `tk5 matches by name prefix`() {
        assertTrue(TK5Coordinator.matches("TK5 24AA", noAdv))
        assertTrue(TK5Coordinator.matches("tk5 24aa", noAdv))   // case-insensitive prefix
        assertFalse(TK5Coordinator.matches("R99 54DC", noAdv))
        assertFalse(TK5Coordinator.matches("R02_A1B2", noAdv))
    }

    @Test
    fun `tk5 matches by manufacturer data prefix when unnamed`() {
        val adv = AdvertisementInfo(emptyList(), byteArrayOf(0x10, 0x78, 0x65, 0x01, 0xAA.toByte()))
        assertTrue(TK5Coordinator.matches(null, adv))
    }

    @Test
    fun `smarthealth colmi matches space-separated names, not underscore ones`() {
        assertTrue(ColmiSmartHealthCoordinator.matches("R99 54DC", noAdv))
        assertFalse(ColmiSmartHealthCoordinator.matches("R02_A1B2", noAdv))
        // TK5 resolves to its own family via the catalog, so this coordinator defers to TK5Coordinator.
        assertFalse(ColmiSmartHealthCoordinator.matches("TK5 24AA", noAdv))
    }

    @Test
    fun `smarthealth colmi never claims a name advertising the qring service`() {
        val adv = AdvertisementInfo(listOf(ColmiUUIDs.SERVICE_V1), null)
        assertFalse(ColmiSmartHealthCoordinator.matches("R99 54DC", adv))
    }

    @Test
    fun `ycbt names resolve to exact catalog models`() {
        assertEquals("tk5", WearableModel.modelForAdvertisedName("TK5 24AA")?.id)
        assertEquals("colmi-smarthealth", WearableModel.modelForAdvertisedName("R99 54DC")?.id)
        // The broad SmartHealth pattern is registered last, so every QRing-Colmi pattern above it
        // in the catalog still wins for an underscore name.
        assertEquals("colmi-r02", WearableModel.modelForAdvertisedName("R02_A1B2")?.id)
    }

    // ── LuckRing / TK18 (iOS #90) ────────────────────────────────────

    @Test
    fun `luckring matches by advertised f618 service`() {
        val adv = AdvertisementInfo(listOf(LuckRingUUIDs.SERVICE), null)
        assertTrue(LuckRingCoordinator.matches("anything", adv))
        assertTrue(LuckRingCoordinator.matches(null, adv))
    }

    @Test
    fun `luckring matches by manufacturer company id prefix`() {
        // Company ID 0xFF64 in the little-endian slot => 64 FF.
        val adv = AdvertisementInfo(emptyList(), byteArrayOf(0x64, 0xFF.toByte(), 0x01, 0x02))
        assertTrue(LuckRingCoordinator.matches("anything", adv))
    }

    @Test
    fun `luckring matches by catalog name pattern`() {
        assertTrue(LuckRingCoordinator.matches("TK18", noAdv))
        assertTrue(LuckRingCoordinator.matches("TK18_AA11", noAdv))
        assertFalse(LuckRingCoordinator.matches("TK5 24AA", noAdv))
        assertFalse(LuckRingCoordinator.matches("R02_A1B2", noAdv))
    }

    @Test
    fun `luckring name resolves to the exact catalog model`() {
        assertEquals("luckring-tk18", WearableModel.modelForAdvertisedName("TK18")?.id)
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
