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
    fun `a JieLi link requests its history streams as bare triples after the handshake`() {
        // JieLi has no manifest: the vendor requests each 05-group stream directly with the bare
        // {5, type, 0x10} triple, no payload (blesdk/service/y.java:345-537, e.g.
        // TRingHeartRateStatisticsActivity.java:545). The burst covers every stream RWfitJLHistory
        // decodes, in HistoryType order (breathe has no JieLi type and drops out).
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        driver.makeSyncEngine().runStartup()

        assertTrue("expected 0xAB frames", writer.frames.all { it[0] == 0xAB.toByte() })
        assertEquals(12, writer.frames.size)   // device info + time + battery + 9 history streams
        val triples = writer.frames.map { Triple(it[6].toInt() and 0xFF, it[7].toInt() and 0xFF, it[8].toInt() and 0xFF) }
        assertEquals(
            listOf(
                Triple(2, 4, 0x10),   // device info
                Triple(2, 1, 0),      // time sync
                Triple(2, 3, 0x10),   // battery
                Triple(5, 2, 0x10),   // steps
                Triple(5, 5, 0x10),   // sleep
                Triple(5, 3, 0x10),   // heart rate
                Triple(5, 4, 0x10),   // blood pressure
                Triple(5, 9, 0x10),   // SpO2
                Triple(5, 8, 0x10),   // temperature
                Triple(5, 10, 0x10),  // HRV
                Triple(5, 13, 0x10),  // stress
                Triple(5, 16, 0x10),  // blood sugar
            ),
            triples,
        )
    }

    @Test
    fun `JieLi history is requested once per connection, not per poll pass`() {
        // runStartup doubles as the ~30-minute background sync: the handshake frames go out again,
        // but the 05-group burst must not — the ring would just re-send buffers we already hold.
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))
        fun historyFrames() = writer.frames.count { it[0] == 0xAB.toByte() && (it[6].toInt() and 0xFF) == 5 }

        driver.makeSyncEngine().runStartup()
        assertEquals(9, historyFrames())
        driver.makeSyncEngine().runStartup()
        assertEquals(9, historyFrames())
    }

    @Test
    fun `a reconnected JieLi link re-requests its history`() {
        // reset() runs on connectionDidEnd/Start, re-arming the once-per-connection gate. A real
        // reconnect re-runs GATT discovery (servicesDiscovered) before runStartup, as in the
        // production connect sequence.
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))
        driver.makeSyncEngine().runStartup()

        driver.connectionDidEnd()
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))
        writer.clear()
        driver.makeSyncEngine().runStartup()

        assertEquals(12, writer.frames.size)
        assertEquals(9, writer.frames.count { (it[6].toInt() and 0xFF) == 5 })
    }

    @Test
    fun `a JieLi history reply decodes through the driver and is acked`() {
        // {5,3,16} heart-rate reply: two 6-byte records, the second a zero-bpm "no reading" slot
        // the vendor drops (x5/b.java V @1318-1320). The frame is app-ACKed (flag 0x11) before the
        // decode result is produced, as on every other JieLi inbound.
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        fun be32(v: Long) = byteArrayOf(
            ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
        )
        val records = be32(0x00_0B_C0_00) + byteArrayOf(72, 0x00) +
            be32(0x00_0B_C0_3C) + byteArrayOf(0x00, 0x00)
        val frame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x03, 0x10), records)

        val events = driver.ingest(frame, "n")

        val measurements = events.filterIsInstance<RingDecodedEvent.HistoryMeasurement>()
        assertEquals(1, measurements.size)
        assertEquals(MeasurementKind.HEART_RATE, measurements[0].kind_field)
        assertEquals(72.0, measurements[0].value, 0.0)
        val ack = writer.frames.single()
        assertEquals(0xAB.toByte(), ack[0])
        assertEquals(0x11, ack[1].toInt() and 0xFF)   // FLAG_ACK
        assertArrayEquals(byteArrayOf(0x05, 0x03, 0x10), ack.copyOfRange(6, 9))
    }

    @Test
    fun `an unported JieLi history key still decodes to nothing and the frame is still acked`() {
        // e.g. sport {5,14,16}: no PulseLoop metric, so the driver logs and drops the records
        // (the frame itself is still ACKed — ACK-before-decode is a link discipline, not a
        // parse verdict).
        val writer = RecordingWriter()
        val driver = RWfitDriver(writer)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        val frame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x14, 0x10), ByteArray(16))

        assertTrue(driver.ingest(frame, "n").isEmpty())
        val ack = writer.frames.single()
        assertEquals(0x11, ack[1].toInt() and 0xFF)
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
