package com.pulseloop.ring

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * iOS #19 parity: per-device measurement config + profile-backed user preferences.
 * Pure — no hardware; a recording writer captures the enqueued commands.
 */
class ColmiMeasurementSettingsTest {

    private class RecordingWriter : RingCommandWriter {
        val commands = mutableListOf<ByteArray>()
        override fun enqueue(command: ByteArray) { commands.add(command) }
    }

    // MARK: Encoder

    @Test
    fun `temp pref write mirrors the extra 0x03 framing byte`() {
        assertArrayEquals(byteArrayOf(0x3A, 0x03, 0x02, 0x01), ColmiEncoder.writeTempPref(enabled = true))
        assertArrayEquals(byteArrayOf(0x3A, 0x03, 0x02, 0x00), ColmiEncoder.writeTempPref(enabled = false))
    }

    // MARK: UserProfileValues mapping

    @Test
    fun `profile values map sex to the colmi gender byte`() {
        assertEquals(0x00u.toUByte(), UserProfileValues.from(true, "Female", 30, 165.0, 60.0).gender)
        assertEquals(0x01u.toUByte(), UserProfileValues.from(true, "male", 30, 180.0, 80.0).gender)
        assertEquals(0x02u.toUByte(), UserProfileValues.from(true, null, 30, 180.0, 80.0).gender)
    }

    @Test
    fun `profile values clamp to byte ranges with neutral fallbacks`() {
        val defaults = UserProfileValues.from(metric = true, sex = null, age = null, heightCm = null, weightKg = null)
        assertEquals(25u.toUByte(), defaults.age)
        assertEquals(175u.toUByte(), defaults.heightCm)
        assertEquals(70u.toUByte(), defaults.weightKg)
        val clamped = UserProfileValues.from(metric = false, sex = "male", age = 300, heightCm = 300.0, weightKg = 300.0)
        assertEquals(255u.toUByte(), clamped.age)
        assertEquals(255u.toUByte(), clamped.heightCm)
        assertEquals(255u.toUByte(), clamped.weightKg)
    }

    // MARK: Sync engine

    @Test
    fun `startup emits the configured HR interval and vital toggles`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(MeasurementSettings(
            hrEnabled = true, hrIntervalMinutes = 15,
            spo2Enabled = true, stressEnabled = false, hrvEnabled = true, temperatureEnabled = false,
        ))
        engine.runStartup()
        engine.destroy()

        val autoHr = writer.commands.first { it[0] == 0x16.toByte() && it.size == 8 && it[1] == 0x02.toByte() }
        assertEquals(0x01.toByte(), autoHr[2])       // enabled
        assertEquals(15.toByte(), autoHr[3])         // configured interval
        val stress = writer.commands.first { it[0] == 0x36.toByte() && it[1] == 0x02.toByte() }
        assertEquals(0x00.toByte(), stress[2])       // stress off
        val temp = writer.commands.first { it[0] == 0x3A.toByte() && it.size == 4 && it[2] == 0x02.toByte() }
        assertEquals(0x00.toByte(), temp[3])         // temperature off
    }

    @Test
    fun `startup with no persisted config does not force-write HR or temp prefs`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(null)
        engine.runStartup()
        engine.destroy()

        // No auto-HR write (0x16 0x02) and no temp write (0x3A 0x03 0x02) — the ring's own
        // settings are read (0x16 0x01 / 0x3A 0x03 0x01) and seed the config instead.
        assertTrue(writer.commands.none { it[0] == 0x16.toByte() && it[1] == 0x02.toByte() })
        assertTrue(writer.commands.none { it[0] == 0x3A.toByte() && it[2] == 0x02.toByte() })
        assertTrue(writer.commands.any { it[0] == 0x16.toByte() && it[1] == 0x01.toByte() })
        assertTrue(writer.commands.any { it[0] == 0x3A.toByte() && it[2] == 0x01.toByte() })
        // SpO2/stress/HRV stay force-enabled — the history sync depends on them.
        for (pref in byteArrayOf(0x2C, 0x36, 0x38)) {
            val write = writer.commands.first { it[0] == pref && it[1] == 0x02.toByte() }
            assertEquals(0x01.toByte(), write[2])
        }
    }

    @Test
    fun `ring-disabled all-day HR is re-enabled keeping the ring's interval`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(null)
        engine.runStartup()
        // Ring reports all-day HR off at a 60-minute interval (read reply: 0x16 0x01 0x02 60).
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x02, 60)))
        engine.destroy()

        val autoHr = writer.commands.first { it[0] == 0x16.toByte() && it[1] == 0x02.toByte() }
        assertEquals(0x01.toByte(), autoHr[2])   // forced on — 0x15 HR history needs it
        assertEquals(60.toByte(), autoHr[3])     // but the ring's interval is respected
    }

    @Test
    fun `ring-enabled all-day HR is left untouched`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(null)
        engine.runStartup()
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x01, 30)))
        engine.destroy()

        assertTrue(writer.commands.none { it[0] == 0x16.toByte() && it[1] == 0x02.toByte() })
    }

    @Test
    fun `seeded config surfaces the ring's settings once both prefs reported`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        var seeded: MeasurementSettings? = null
        engine.setMeasurementSettings(null)
        engine.setOnMeasurementConfigSeeded { seeded = it }
        engine.runStartup()

        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x01, 30)))  // HR on, 30 min
        assertEquals(null, seeded)  // waits for the temp reply
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x3A, 0x03, 0x01, 0x00)))  // temp off
        engine.destroy()

        val settings = seeded!!
        assertTrue(settings.hrEnabled)
        assertEquals(30, settings.hrIntervalMinutes)
        assertTrue(settings.spo2Enabled && settings.stressEnabled && settings.hrvEnabled)
        assertTrue(!settings.temperatureEnabled)
    }

    @Test
    fun `seeding ignores replies when a persisted config exists`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        var seeded: MeasurementSettings? = null
        engine.setMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.setOnMeasurementConfigSeeded { seeded = it }
        engine.runStartup()
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x16, 0x01, 0x02, 60)))
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x3A, 0x03, 0x01, 0x00)))
        engine.destroy()

        assertEquals(null, seeded)
        // The persisted config was written exactly once — the reply didn't add a second write.
        assertEquals(1, writer.commands.count { it[0] == 0x16.toByte() && it[1] == 0x02.toByte() })
    }

    @Test
    fun `apply pushes all five measurement commands immediately`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.applyMeasurementSettings(MeasurementSettings.ALL_ON_DEFAULT)
        engine.destroy()

        assertEquals(5, writer.commands.size)
        assertEquals(0x16.toByte(), writer.commands[0][0])  // auto-HR
        assertEquals(0x2C.toByte(), writer.commands[1][0])  // SpO2 pref
        assertEquals(0x36.toByte(), writer.commands[2][0])  // stress pref
        assertEquals(0x38.toByte(), writer.commands[3][0])  // HRV pref
        assertEquals(0x3A.toByte(), writer.commands[4][0])  // temp pref
    }

    @Test
    fun `user profile feeds the preferences command on startup`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setUserProfile(UserProfileValues.from(
            metric = false, sex = "female", age = 42, heightCm = 160.0, weightKg = 55.0,
        ))
        engine.runStartup()
        engine.destroy()

        val prefs = writer.commands.first { it[0] == 0x0A.toByte() }
        assertEquals(0x01.toByte(), prefs[3])  // imperial flag
        assertEquals(0x00.toByte(), prefs[4])  // female
        assertEquals(42.toByte(), prefs[5])
        assertEquals(160.toByte(), prefs[6])
        assertEquals(55.toByte(), prefs[7])
    }

    @Test
    fun `colmi declares the measurement interval capability, jring does not`() {
        assertTrue(ColmiCoordinator.capabilities.contains(WearableCapability.MEASUREMENT_INTERVAL))
        assertTrue(!JringCoordinator.capabilities.contains(WearableCapability.MEASUREMENT_INTERVAL))
    }

    // MARK: Capability-gated bonding (QRing supportBlePair)

    @Test
    fun `startup reads the device-support capability bitfield`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        engine.setMeasurementSettings(null)
        engine.runStartup()
        engine.destroy()

        assertTrue(writer.commands.any { it[0] == 0x3C.toByte() })  // DeviceSupportReq
    }

    @Test
    fun `supportBlePair reply requests a bond, non-support reply does not`() {
        val writer = RecordingWriter()
        val engine = ColmiSyncEngine(writer, ColmiDecoder)
        var bondRequests = 0
        engine.setOnBondRequested { bondRequests++ }

        // Feature byte with bit 3 set → bond wanted.
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x3C, 0x08)))
        assertEquals(1, bondRequests)

        // Feature byte without bit 3 → no bond.
        engine.handleRawNotify(ColmiPacket.frame(byteArrayOf(0x3C, 0x07)))
        assertEquals(1, bondRequests)
        engine.destroy()
    }
}
