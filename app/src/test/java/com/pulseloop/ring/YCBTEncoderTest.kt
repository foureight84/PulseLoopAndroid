package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class YCBTEncoderTest {
    private val encoder = YCBTEncoder()

    @Test
    fun `setTime weekday byte is correct for every day`() {
        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(2026, java.util.Calendar.JULY, 6, 12, 34, 14)
        for (offset in 0..6) {
            val date = Instant.ofEpochMilli(calendar.timeInMillis + offset * 86_400_000L)
            val command = encoder.setTime(date)
            assertEquals(offset.toByte(), command.last())
        }
    }

    @Test
    fun `setTime matches captured frame`() {
        val calendar = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.set(2026, java.util.Calendar.JULY, 6, 12, 34, 14)
        val date = Instant.ofEpochMilli(calendar.timeInMillis)
        val command = encoder.setTime(date)
        assertArrayEquals(byteArrayOf(0x01, 0x00, 0xea.toByte(), 0x07, 0x07, 0x06, 0x0c, 0x22, 0x0e, 0x00), command)
    }

    @Test
    fun `monitor commands clamp interval to thirty minutes`() {
        val settings = MeasurementSettings(
            hrEnabled = true, hrIntervalMinutes = 5,
            spo2Enabled = true, stressEnabled = true, hrvEnabled = true, temperatureEnabled = true,
        )
        val commands = encoder.monitorCommands(settings)
        assertEquals(5, commands.size)
        assertArrayEquals(byteArrayOf(0x01, 0x0c, 0x01, 30), commands[0])
        assertArrayEquals(byteArrayOf(0x01, 0x1c, 0x01, 30), commands[1])
        assertArrayEquals(byteArrayOf(0x01, 0x20, 0x01, 30), commands[2])
        assertArrayEquals(byteArrayOf(0x01, 0x26, 0x01, 30), commands[3])
        assertArrayEquals(byteArrayOf(0x01, 0x45, 0x01, 30, 0, 0, 0), commands[4])
    }

    @Test
    fun `monitor commands honour interval above floor and disabled flags`() {
        val settings = MeasurementSettings(
            hrEnabled = true, hrIntervalMinutes = 60,
            spo2Enabled = false, stressEnabled = false, hrvEnabled = false, temperatureEnabled = false,
        )
        val commands = encoder.monitorCommands(settings)
        assertArrayEquals(byteArrayOf(0x01, 0x0c, 0x01, 60), commands[0])
        assertArrayEquals(byteArrayOf(0x01, 0x1c, 0x01, 60), commands[1])
        assertArrayEquals(byteArrayOf(0x01, 0x20, 0x00, 60), commands[2])
        assertArrayEquals(byteArrayOf(0x01, 0x26, 0x00, 60), commands[3])
        assertArrayEquals(byteArrayOf(0x01, 0x45, 0x00, 60, 0, 0, 0), commands[4])
    }

    @Test
    fun `userInfo carries real profile`() {
        val profile = UserProfileValues(metric = true, gender = 0x01u, age = 31u, heightCm = 183u, weightKg = 78u)
        assertArrayEquals(byteArrayOf(0x01, 0x03, 183.toByte(), 78.toByte(), 1, 31.toByte()), encoder.userInfo(profile))

        val female = UserProfileValues(metric = false, gender = 0x00u, age = 29u, heightCm = 165u, weightKg = 60u)
        assertArrayEquals(byteArrayOf(0x01, 0x03, 165.toByte(), 60.toByte(), 0, 29.toByte()), encoder.userInfo(female))
    }

    @Test
    fun `startup sends no health delete or retired get opcodes`() {
        val sequence = encoder.startupSequence()
        for (command in sequence) {
            val group = command[0].toInt() and 0xFF
            val cmd = command[1].toInt() and 0xFF
            assertFalse("05 ${String.format("%02x", cmd)} is a Health-DELETE opcode", group == 0x05 && cmd in 0x40..0x4e)
            assertFalse("handshake must not touch Health group", group == 0x05)
            assertFalse("02 ${String.format("%02x", cmd)} is retired Get opcode", group == 0x02 && cmd in listOf(0x24, 0x26, 0x28))
        }
    }

    @Test
    fun `startup order mirrors SmartHealth handshake`() {
        val sequence = encoder.startupSequence().map { it.copyOfRange(0, 2) }
        assertArrayEquals(byteArrayOf(0x01, 0x00), sequence.first())
        assertArrayEquals(byteArrayOf(0x03, 0x09), sequence.last())
        assertEquals(listOf(
            byteArrayOf(0x02, 0x00),
            byteArrayOf(0x02, 0x01),
            byteArrayOf(0x02, 0x1b),
            byteArrayOf(0x02, 0x03),
            byteArrayOf(0x02, 0x07),
        ), sequence.subList(1, 6))
        assertEquals(byteArrayOf(0x03, 0x09, 0x01, 0x00, 0x02).toList(), encoder.startupSequence().last().toList())
        val settings = sequence.subList(6, sequence.size)
        assertEquals(listOf(
            byteArrayOf(0x01, 0x12),
            byteArrayOf(0x01, 0x04),
            byteArrayOf(0x01, 0x0c),
            byteArrayOf(0x01, 0x1c),
            byteArrayOf(0x01, 0x20),
            byteArrayOf(0x01, 0x26),
            byteArrayOf(0x01, 0x45),
            byteArrayOf(0x01, 0x03),
            byteArrayOf(0x03, 0x09),
        ), settings)
    }

    @Test
    fun `history request and block ack bytes`() {
        assertArrayEquals(byteArrayOf(0x05, 0x06), encoder.healthHistoryRequest(YCBTHistoryType.HEART))
        assertArrayEquals(byteArrayOf(0x05, 0x09), encoder.healthHistoryRequest(YCBTHistoryType.ALL))
        assertArrayEquals(byteArrayOf(0x05, 0x04), encoder.healthHistoryRequest(YCBTHistoryType.SLEEP))
        assertArrayEquals(byteArrayOf(0x05, 0x80.toByte(), 0x00), encoder.historyBlockAck(status = 0x00))
        assertArrayEquals(byteArrayOf(0x05, 0x80.toByte(), 0x04), encoder.historyBlockAck(status = 0x04))
    }

    @Test
    fun `live measurement modes use distinct sensors`() {
        assertArrayEquals(byteArrayOf(0x03, 0x2f, 0x01, 0x00), encoder.heartRateStart())
        assertArrayEquals(byteArrayOf(0x03, 0x2f, 0x01, 0x01), encoder.bloodPressureStart())
        assertArrayEquals(byteArrayOf(0x03, 0x2f, 0x01, 0x02), encoder.spo2Start())
        assertArrayEquals(byteArrayOf(0x03, 0x2f, 0x01, 0x0a), encoder.hrvStart())
    }

    @Test
    fun `live measurement stop echoes its own mode`() {
        assertArrayEquals(byteArrayOf(0x03, 0x2f, 0x00, 0x00), encoder.heartRateStop())
        assertArrayEquals(byteArrayOf(0x03, 0x2f, 0x00, 0x01), encoder.bloodPressureStop())
        assertArrayEquals(byteArrayOf(0x03, 0x2f, 0x00, 0x02), encoder.spo2Stop())
        assertArrayEquals(byteArrayOf(0x03, 0x2f, 0x00, 0x0a), encoder.hrvStop())
    }

    @Test
    fun `findDevice uses AppControl not DevControl ack`() {
        assertArrayEquals(byteArrayOf(0x03, 0x00, 0x01, 0x05, 0x02), encoder.findDevice())
        assertNotEquals(0x04.toByte(), encoder.findDevice()[0])
    }
}
