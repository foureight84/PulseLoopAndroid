package com.pulseloop.ring

import com.pulseloop.wearables.WearableModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression cover for issue #56: the manufacturer-data fallback was unreachable on Android.
 *
 * Android's `ScanRecord.getManufacturerSpecificData()` is a `SparseArray` keyed by company ID,
 * with the ID stripped from the value; the coordinators were ported from Swift, where
 * CoreBluetooth leaves the ID in the bytes, so all of them match a little-endian company-ID
 * prefix. `RingBLEClient` used to hand `valueAt(0)` straight through, so no coordinator's
 * manufacturer branch could ever fire — and only the first entry was ever looked at.
 */
class AdvertisementMatcherTest {

    /** The registry, in the order `RingBLEClient` walks it. Order is load-bearing. */
    private val registry = listOf(
        JringCoordinator,
        YCBTCoordinator,
        ColmiCoordinator,
        ColmiSmartHealthCoordinator,
        LuckRingCoordinator,
        TK5Coordinator,
        RWfitCoordinator,
        CRPCoordinator,
    )

    private fun bytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ── manufacturerBlocks: the company ID goes back in front, little-endian ──────────

    @Test
    fun `company id is restored little-endian ahead of the value`() {
        val blocks = AdvertisementMatcher.manufacturerBlocks(listOf(0x7810 to bytes("d40877")))
        assertEquals(1, blocks.size)
        assertEquals("1078d40877", blocks[0]!!.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `every manufacturer entry is offered, not just the first`() {
        val blocks = AdvertisementMatcher.manufacturerBlocks(
            listOf(0x004C to bytes("0215"), 0xFF64 to bytes("aabb"))
        )
        assertEquals(2, blocks.size)
        assertEquals("4c000215", blocks[0]!!.joinToString("") { "%02x".format(it) })
        assertEquals("64ffaabb", blocks[1]!!.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `no manufacturer data still yields one null block so service and name matching runs`() {
        assertEquals(listOf<ByteArray?>(null), AdvertisementMatcher.manufacturerBlocks(emptyList()))
    }

    // ── The reported ring: Ale-Hop2211 E1C7 (issue #56) ──────────────────────────────

    /** Company 0x7810 (Yucheng) + the exact value bytes from the issue's nRF capture. */
    private val aleHopManufacturer = listOf(0x7810 to bytes("d408770058ddeb05e1c70000bf0c4362b6005c000058ddeb05e1c7"))

    @Test
    fun `the reported YCBT ring is claimed by the SmartHealth coordinator`() {
        val matched = AdvertisementMatcher.match(
            registry,
            name = "Ale-Hop2211 E1C7",
            serviceUUIDs = listOf("0000180d", "0000fee7"),
            manufacturerEntries = aleHopManufacturer,
        )
        assertEquals(RingDeviceType.COLMI_SMART_HEALTH, matched)
    }

    @Test
    fun `the manufacturer branch alone recognizes the ring, and only with the company id`() {
        // Isolate the manufacturer path from the name path: a name the catalog does not claim.
        // With the company ID restored the Yucheng marker matches; with it stripped — the old
        // valueAt(0) behaviour — nothing in the registry claims the device at all.
        val serviceUUIDs = listOf("0000180d", "0000fee7")
        assertEquals(
            RingDeviceType.COLMI_SMART_HEALTH,
            AdvertisementMatcher.match(registry, "Unlabeled", serviceUUIDs, aleHopManufacturer),
        )
        val stripped = AdvertisementInfo(
            serviceUUIDs,
            bytes("d408770058ddeb05e1c70000bf0c4362b6005c000058ddeb05e1c7"),
        )
        assertNull(registry.firstOrNull { it.matches("Unlabeled", stripped) }?.deviceType)
    }

    @Test
    fun `hyphenated SmartHealth names resolve to the SmartHealth catalog card`() {
        assertEquals(
            WearableModel.COLMI_SMARTHEALTH.id,
            WearableModel.modelForAdvertisedName("Ale-Hop2211 E1C7")?.id,
        )
        // The coordinator and the catalog gate the same decision — they must agree.
        assertTrue(ColmiSmartHealthCoordinator.matches("Ale-Hop2211 E1C7", AdvertisementInfo(emptyList(), null)))
    }

    @Test
    fun `widening the name class does not pull in QRing-Colmi names`() {
        // No space before the hex — the split that keeps the QRing rings on ColmiCoordinator.
        for (name in listOf("R02_A1B2", "COLMI R10_9C3F", "R09_9D07", "R11C_BEEF")) {
            assertFalse(
                "SmartHealth must not claim $name",
                ColmiSmartHealthCoordinator.matches(name, AdvertisementInfo(emptyList(), null)),
            )
        }
    }

    // ── The other coordinators whose manufacturer branch was equally dead ────────────

    @Test
    fun `TK5 matches its own longer manufacturer prefix`() {
        val matched = AdvertisementMatcher.match(
            registry, name = "Unlabeled", serviceUUIDs = emptyList(),
            manufacturerEntries = listOf(0x7810 to bytes("6501aabb")),
        )
        assertEquals(RingDeviceType.TK5, matched)
    }

    @Test
    fun `LuckRing matches on company 0xFF64 alone`() {
        val matched = AdvertisementMatcher.match(
            registry, name = "Unlabeled", serviceUUIDs = emptyList(),
            manufacturerEntries = listOf(0xFF64 to bytes("0102030405")),
        )
        assertEquals(RingDeviceType.LUCK_RING, matched)
    }

    @Test
    fun `RWfit matches its JieLi company prefix`() {
        val matched = AdvertisementMatcher.match(
            registry, name = "Whatever", serviceUUIDs = emptyList(),
            manufacturerEntries = listOf(0x05D6 to bytes("0200aabb")),
        )
        assertEquals(RingDeviceType.RWFIT, matched)
    }

    @Test
    fun `a family marker in a later entry is still found`() {
        // Entry 0 is an unrelated iBeacon block; the ring's own block is second.
        val matched = AdvertisementMatcher.match(
            registry, name = "Unlabeled", serviceUUIDs = emptyList(),
            manufacturerEntries = listOf(0x004C to bytes("021500"), 0xFF64 to bytes("0102")),
        )
        assertEquals(RingDeviceType.LUCK_RING, matched)
    }

    // ── Ordering and non-regression ─────────────────────────────────────────────────

    @Test
    fun `registry order still wins over a later coordinators manufacturer match`() {
        // A QRing service (ColmiCoordinator, 3rd) plus a LuckRing company ID (5th): Colmi wins.
        val matched = AdvertisementMatcher.match(
            registry, name = "Unlabeled", serviceUUIDs = listOf(ColmiUUIDs.SERVICE_V1),
            manufacturerEntries = listOf(0xFF64 to bytes("0102")),
        )
        assertEquals(RingDeviceType.COLMI_R02, matched)
    }

    @Test
    fun `an unrelated device with manufacturer data still matches nothing`() {
        val matched = AdvertisementMatcher.match(
            registry, name = "Galaxy Watch", serviceUUIDs = listOf("0000180f"),
            manufacturerEntries = listOf(0x0075 to bytes("0102030405")),
        )
        assertNull(matched)
    }
}
