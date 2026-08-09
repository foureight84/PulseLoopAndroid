package com.pulseloop.ring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driver-level behaviour: framing selection from the service table, the mandatory ACK handshake,
 * and the manifest-gated history cascade.
 */
class RWfitDriverTest {

    /** Captures everything the driver would write to the ring. */
    private class RecordingWriter : RingCommandWriter {
        val frames = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { frames.add(command) }
        fun cmds(): List<Int> = frames.mapNotNull { if (it.size > 2 && it[0] == 0x7E.toByte()) it[2].toInt() and 0xFF else null }
        fun clear() = frames.clear()
    }

    private fun legacyFrame(cmd: Byte, payload: ByteArray, serial: Int = 1): ByteArray {
        val ser = RWfitProtocol.u16BE(serial)
        val xor = if (payload.isEmpty()) 0 else RWfitProtocol.xorChecksum(payload)
        return byteArrayOf(0x7E, 0x01, cmd, 0x00, payload.size.toByte(), ser[0], ser[1], xor) + payload
    }

    private fun advert(services: List<String> = emptyList(), mfg: ByteArray? = null) =
        AdvertisementInfo(serviceUUIDs = services, manufacturerData = mfg)

    // ── Framing selection (r5/b.java onServicesDiscovered) ──────────────────────

    @Test
    fun `a bare A00A ring speaks legacy`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))

        driver.makeSyncEngine().runStartup()

        assertTrue("expected 0x7E frames", writer.frames.all { it[0] == 0x7E.toByte() })
    }

    @Test
    fun `the JieLi AE00 service switches framing`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        driver.makeSyncEngine().runStartup()

        assertTrue("expected 0xAB frames", writer.frames.all { it[0] == 0xAB.toByte() })
    }

    @Test
    fun `Telink and PixArt OTA services also mean JieLi`() {
        for (discriminator in listOf(RWfitProtocol.TELINK_OTA_SERVICE_UUID, RWfitProtocol.PIXART_OTA_SERVICE_UUID)) {
            val writer = RecordingWriter()
            val driver = RWfitDriver(writer)
            driver.connectionDidStart()
            driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, discriminator))
            driver.makeSyncEngine().runStartup()
            assertTrue("$discriminator should select JieLi", writer.frames.first()[0] == 0xAB.toByte())
        }
    }

    @Test
    fun `service matching is case-insensitive`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.JIELI_SERVICE_UUID.uppercase()))
        driver.makeSyncEngine().runStartup()
        assertTrue(writer.frames.first()[0] == 0xAB.toByte())
    }

    // ── ACK handshake (x5/d.java) ───────────────────────────────────────────────

    @Test
    fun `every inbound legacy frame is app-ACKed`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))

        driver.ingest(legacyFrame(RWfitProtocol.Legacy.BATTERY, byteArrayOf(0, 0, 77), serial = 9), "n")

        val ack = writer.frames.single()
        assertEquals(0xFF.toByte(), ack[2])                                   // app-ACK command
        assertArrayEquals(byteArrayOf(0x00, 0x09, 0x01, 0x00), ack.copyOfRange(8, 12))
    }

    @Test
    fun `a bad checksum is NACKed with status 2`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))

        val corrupt = legacyFrame(RWfitProtocol.Legacy.BATTERY, byteArrayOf(0, 0, 77), serial = 3)
        corrupt[7] = (corrupt[7] + 1).toByte()
        val events = driver.ingest(corrupt, "n")

        assertTrue("a corrupt frame must not decode", events.isEmpty())
        assertEquals(0x02.toByte(), writer.frames.single()[11])   // status byte of the ACK payload
    }

    @Test
    fun `a device ACK is not itself ACKed`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))

        driver.ingest(
            legacyFrame(RWfitProtocol.Legacy.DEVICE_ACK, byteArrayOf(0x00, 0x05, 0x21, 0x00)),
            "n",
        )

        assertTrue("0xFE must not be echoed back", writer.frames.isEmpty())
    }

    @Test
    fun `battery decodes through the driver`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))

        val events = driver.ingest(legacyFrame(RWfitProtocol.Legacy.BATTERY, byteArrayOf(0, 0, 77)), "n")

        assertEquals(RingDecodedEvent.Battery(percent = 77), events.single())
    }

    // ── Manifest-gated cascade (blesdk/service/l.java) ──────────────────────────

    @Test
    fun `startup asks for device info, time, battery and the manifest`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))

        driver.makeSyncEngine().runStartup()

        assertEquals(listOf(0x00, 0x21, 0x01, 0xA0), writer.cmds())
    }

    @Test
    fun `only the streams the manifest claims are requested`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))
        driver.makeSyncEngine().runStartup()
        writer.clear()

        // steps (bit 0) + HR (bit 2) only.
        driver.ingest(
            legacyFrame(RWfitProtocol.Legacy.SYNC_MANIFEST, byteArrayOf(0x00, 0x02, 0b0000_0101, 0x00)),
            "n",
        )

        // First stream requested immediately; the app-ACK for the manifest is also in the queue.
        assertEquals(listOf(0xFF, 0xA1), writer.cmds())
        assertFalse("must not ask for sleep", writer.cmds().contains(0xA2))
    }

    @Test
    fun `each history reply advances the cascade one stream at a time`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))
        driver.makeSyncEngine().runStartup()
        driver.ingest(
            legacyFrame(RWfitProtocol.Legacy.SYNC_MANIFEST, byteArrayOf(0x00, 0x02, 0b0000_0101, 0x00)),
            "n",
        )
        writer.clear()

        // Steps reply → HR is next, and nothing else.
        driver.ingest(legacyFrame(RWfitProtocol.Legacy.STEPS_HISTORY, ByteArray(0)), "n")
        assertEquals(listOf(0xFF, 0xA3), writer.cmds())
        writer.clear()

        // HR reply → the queue is empty, so no further history requests.
        driver.ingest(legacyFrame(RWfitProtocol.Legacy.HEART_RATE_HISTORY, ByteArray(0)), "n")
        assertEquals(listOf(0xFF), writer.cmds())
    }

    @Test
    fun `a JieLi link does not request legacy history`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        driver.makeSyncEngine().runStartup()

        // Device info, time and battery only — the 05-group history bodies aren't decodable yet, so
        // requesting them would spend the link on frames we could only log.
        assertEquals(3, writer.frames.size)
    }

    @Test
    fun `reconnect clears the cascade`() {
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID))
        driver.makeSyncEngine().runStartup()
        driver.ingest(
            legacyFrame(RWfitProtocol.Legacy.SYNC_MANIFEST, byteArrayOf(0x00, 0x02, 0b0000_0101, 0x00)),
            "n",
        )

        driver.connectionDidEnd()
        driver.connectionDidStart()
        writer.clear()

        // A history reply left over from the dead link must not resume anything.
        driver.ingest(legacyFrame(RWfitProtocol.Legacy.STEPS_HISTORY, ByteArray(0)), "n")
        assertEquals(listOf(0xFF), writer.cmds())
    }

    // ── Coordinator matching (r5/d.java) ────────────────────────────────────────

    @Test
    fun `matches the A00A advertisement in either form`() {
        assertTrue(RWfitCoordinator.matches(null, advert(services = listOf("a00a"))))
        assertTrue(RWfitCoordinator.matches(null, advert(services = listOf(RWfitProtocol.SERVICE_UUID))))
        assertTrue(RWfitCoordinator.matches(null, advert(services = listOf(RWfitProtocol.SERVICE_UUID.uppercase()))))
    }

    @Test
    fun `matches the vendor manufacturer prefixes`() {
        assertTrue(RWfitCoordinator.matches(null, advert(mfg = byteArrayOf(0xD6.toByte(), 0x05, 0x02, 0x00))))
        assertTrue(RWfitCoordinator.matches(null, advert(mfg = byteArrayOf(0xD6.toByte(), 0x05, 0x41, 0x54))))
        assertTrue(RWfitCoordinator.matches(null, advert(mfg = byteArrayOf(0xD6.toByte(), 0x06, 0x02, 0x00))))
    }

    @Test
    fun `never matches on the device name`() {
        // The vendor requires a non-empty name but never reads it — these rings are rebranded
        // constantly, and the first Android attempt's `startsWith("RW")` both missed rebrands and
        // would hijack unrelated devices.
        assertFalse(RWfitCoordinator.matches("RWfit Ring", advert()))
        assertFalse(RWfitCoordinator.matches("RW-01", advert()))
        assertFalse(RWfitCoordinator.matches("RWXYZ", advert()))
        assertFalse(RWfitCoordinator.matches(null, advert(mfg = byteArrayOf(0x01, 0x02))))
    }

    @Test
    fun `manual measurement is gated behind the feature bitmap, not granted to the family`() {
        // The vendor app has no legacy on-demand measurement command, so a Measure button on a
        // legacy link could only ever time out.
        assertFalse(WearableCapability.MANUAL_HEART_RATE in RWfitCoordinator.capabilities)
        assertTrue(WearableCapability.MANUAL_HEART_RATE in RWfitCoordinator.bitmapGatedCapabilities)
        assertFalse(WearableCapability.BLOOD_PRESSURE in RWfitCoordinator.capabilities)
        assertTrue(WearableCapability.BLOOD_PRESSURE in RWfitCoordinator.bitmapGatedCapabilities)
    }
}
