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

    // ── JieLi Sleep Paging (Issue #135, SR16 multi-page sleep) ─────────────────

    private fun recSleep(unixSeconds: Long, model: Int): ByteArray {
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000L
        val v = unixSeconds - RWfitJLHistory.JIELI_EPOCH_SECONDS + tz
        val be = byteArrayOf(
            ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte(),
        )
        return be + byteArrayOf(model.toByte(), 0, 0)
    }

    @Test
    fun `JieLi sleep paging stages pages, sends delete and get next, and decodes joined session on terminal frame`() {
        // Issue #135: On JieLi 0xAB framing, sleep history is paginated. The ring sends page 1
        // (start marker 0x11, stage changes, no 0x22 yet). Without sending {05,05,30} to delete/ack,
        // the ring never advances to page 2. Staged pages are held in RWfitSleepJournal, and when the
        // terminal empty frame arrives, the joined pages decode into the full night.
        val writer = RecordingWriter()
        val journal = RWfitSleepJournal()
        val driver = RWfitDriver(writer, sleepJournal = journal)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        val t0 = 1_700_000_000L
        // Page 1: 0x11 start marker (2:11 AM) + deep + light (no 0x22 wakeup marker)
        val page1Records = recSleep(t0, 0x11) +
            recSleep(t0 + 600, 1) +    // deep
            recSleep(t0 + 1200, 2)     // light
        val page1Frame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x05, 0x10), page1Records)

        // Page 2: REM + 0x22 wakeup marker
        val page2Records = recSleep(t0 + 1800, 4) +   // REM
            recSleep(t0 + 2400, 0x22)                  // wakeup
        val page2Frame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x05, 0x10), page2Records)

        // Page 3: terminal empty frame (bare triple {05, 05, 10}, 0 data bytes)
        val terminalFrame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x05, 0x10), ByteArray(0))

        // Ingest Page 1:
        val eventsPage1 = driver.ingest(page1Frame, "n")
        assertTrue("Page 1 has no 0x22, should not emit session yet", eventsPage1.isEmpty())
        assertEquals(1, journal.pageCount())

        // Verify writer sent: 1) ACK for page 1 frame, 2) delete {05,05,30}, 3) next request {05,05,10}
        val triples1 = writer.frames.map { Triple(it[6].toInt() and 0xFF, it[7].toInt() and 0xFF, it[8].toInt() and 0xFF) }
        assertEquals(listOf(Triple(5, 5, 0x10), Triple(5, 5, 0x30), Triple(5, 5, 0x10)), triples1)
        assertEquals(0x11, writer.frames[0][1].toInt() and 0xFF) // First frame is app ACK
        writer.clear()

        // Ingest Page 2:
        val eventsPage2 = driver.ingest(page2Frame, "n")
        assertTrue("Page 2 is not terminal, session decodes on terminal frame", eventsPage2.isEmpty())
        assertEquals(2, journal.pageCount())

        val triples2 = writer.frames.map { Triple(it[6].toInt() and 0xFF, it[7].toInt() and 0xFF, it[8].toInt() and 0xFF) }
        assertEquals(listOf(Triple(5, 5, 0x10), Triple(5, 5, 0x30), Triple(5, 5, 0x10)), triples2)
        writer.clear()

        // Ingest Terminal Page:
        val eventsTerminal = driver.ingest(terminalFrame, "n")
        val timelines = eventsTerminal.filterIsInstance<RingDecodedEvent.SleepTimeline>()
        assertEquals(1, timelines.size)
        val timeline = timelines.single()
        assertEquals(40, timeline.stages.size)
        assertEquals(SleepStage.LIGHT, timeline.stages[0])   // 0x11 start counts as light
        assertEquals(SleepStage.DEEP, timeline.stages[10])   // 10 min in
        assertEquals(SleepStage.LIGHT, timeline.stages[20])  // 20 min in
        assertEquals(SleepStage.REM, timeline.stages[30])    // 30 min in
        assertEquals(t0, timeline._timestamp.epochSecond)
        assertTrue(timeline.completeSession)

        // Journal must be cleared after successful decode of complete session
        assertFalse(journal.hasPages())
    }

    @Test
    fun `JieLi sleep paging preserves partial night across link disconnection`() {
        val writer = RecordingWriter()
        val journal = RWfitSleepJournal()
        val driver1 = RWfitDriver(writer, sleepJournal = journal)
        driver1.connectionDidStart()
        driver1.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        val t0 = (System.currentTimeMillis() / 1000L) - 7200L
        val page1Records = recSleep(t0, 0x11) + recSleep(t0 + 600, 1)
        val page1Frame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x05, 0x10), page1Records)

        // Link 1 receives Page 1
        driver1.ingest(page1Frame, "n")
        assertTrue(journal.hasPages())
        driver1.connectionDidEnd()

        // Link 2 connects later (e.g. morning sync)
        writer.clear()
        val driver2 = RWfitDriver(writer, sleepJournal = journal)
        driver2.connectionDidStart()
        driver2.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        // Page 1 is still preserved in journal
        assertTrue(journal.hasPages())

        // Page 2 arrives with wakeup marker 0x22
        val page2Records = recSleep(t0 + 1200, 2) + recSleep(t0 + 1800, 0x22)
        val page2Frame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x05, 0x10), page2Records)
        driver2.ingest(page2Frame, "n")

        // Terminal frame arrives
        val terminalFrame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x05, 0x10), ByteArray(0))
        val events = driver2.ingest(terminalFrame, "n")
        val timelines = events.filterIsInstance<RingDecodedEvent.SleepTimeline>()

        assertEquals(1, timelines.size)
        assertEquals(30, timelines.single().stages.size)
        assertEquals(t0, timelines.single().stages.size.let { timelines.single()._timestamp.epochSecond })
        assertFalse(journal.hasPages())
    }

    @Test
    fun `JieLi sleep with empty history returns empty immediately without sending delete`() {
        val writer = RecordingWriter()
        val journal = RWfitSleepJournal()
        val driver = RWfitDriver(writer, sleepJournal = journal)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        val terminalFrame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x05, 0x10), ByteArray(0))
        val events = driver.ingest(terminalFrame, "n")

        assertTrue(events.isEmpty())
        assertFalse(journal.hasPages())
        // Only the app ACK for the inbound frame is sent, no delete {5,5,30} or retry {5,5,10}
        assertEquals(1, writer.frames.size)
        assertEquals(0x11, writer.frames[0][1].toInt() and 0xFF)
    }

    @Test
    fun `JieLi sleep pagination breaks loop when identical page is sent repeatedly`() {
        val writer = RecordingWriter()
        val journal = RWfitSleepJournal()
        val driver = RWfitDriver(writer, sleepJournal = journal)
        driver.connectionDidStart()
        driver.servicesDiscovered(listOf(RWfitProtocol.SERVICE_UUID, RWfitProtocol.JIELI_SERVICE_UUID))

        val t0 = (System.currentTimeMillis() / 1000L) - 3600L
        val page1Records = recSleep(t0, 0x11) + recSleep(t0 + 600, 1) + recSleep(t0 + 1200, 0x22)
        val page1Frame = RWfitJLCodec().encode(RWfitProtocol.JLTriple(0x05, 0x05, 0x10), page1Records)

        // First delivery: normal staging, enqueues delete + next
        driver.ingest(page1Frame, "n")
        val initialWrites = writer.frames.size

        // Second delivery (duplicate): enqueues delete + next
        driver.ingest(page1Frame, "n")

        // Third delivery (duplicate): loop detected, aborts pagination and decodes current journal
        val events = driver.ingest(page1Frame, "n")
        val timelines = events.filterIsInstance<RingDecodedEvent.SleepTimeline>()

        assertEquals(1, timelines.size)
        // No further delete or get requests enqueued on 3rd duplicate
        val endWrites = writer.frames.size
        // 3rd ingest only emits BLE frame ACK (1 frame), not delete or sync requests
        val addedWrites = endWrites - initialWrites
        assertTrue(addedWrites <= 4)
    }
}
