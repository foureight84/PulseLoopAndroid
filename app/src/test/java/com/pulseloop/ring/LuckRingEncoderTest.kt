package com.pulseloop.ring

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/**
 * Ported from LuckRingEncoderTests.swift (iOS #90).
 * The encoder's byte layouts, each pinned against its `K6_*` vendor struct: the MixInfo binding
 * bundle (property set + order + framing), the clock, the real-time toggles, and the requests. A
 * wrong offset here is a command the ring silently ignores, so the golden assertions are the contract.
 */
class LuckRingEncoderTest {
    private val fixedProfile = UserProfileValues(metric = true, gender = 1u, age = 30u, heightCm = 175u, weightKg = 70u)
    private val fixedDate: Instant = Instant.ofEpochSecond(1_700_000_000)

    // MARK: Struct layouts

    @Test
    fun `user info bytes match K6_SendUserInfo`() {
        // [userId u32 LE][sex][age][height][weight][reserved]; sex is inverted (male -> 0).
        val bytes = LuckRingEncoder.userInfoBytes(fixedProfile)
        assertEquals(listOf<UByte>(0u, 0u, 0u, 0u, 0u, 30u, 175u, 70u, 0u), bytes)
    }

    @Test
    fun `user info sex inversion and age floor`() {
        val female = UserProfileValues(metric = true, gender = 0u, age = 0u, heightCm = 160u, weightKg = 55u)
        val bytes = LuckRingEncoder.userInfoBytes(female)
        assertEquals("female -> sex byte 1", 1u.toUByte(), bytes[4])
        assertEquals("age 0 floors to the vendor default of 20", 20u.toUByte(), bytes[5])
    }

    @Test
    fun `time bytes are true utc seconds`() {
        val bytes = LuckRingEncoder.timeBytes(fixedDate, ZoneId.of("UTC"))
        assertEquals(9, bytes.size)
        assertEquals(1_700_000_000L, LuckRingBytes.u32(bytes, 0))   // abs seconds (UTC, no wall-clock shift)
        assertEquals(0L, LuckRingBytes.u32(bytes, 4))               // UTC offset
        assertEquals(0u.toUByte(), bytes[8])                        // format byte
    }

    @Test
    fun `goal bytes match K6_SendGoal`() {
        val bytes = LuckRingEncoder.goalBytes(8000)
        assertEquals(16, bytes.size)
        assertEquals(8000L, LuckRingBytes.u32(bytes, 0))            // step goal, LE
        assertEquals(List(12) { 0u.toUByte() }, bytes.subList(4, 16))   // distance/cal/sleep/duration = 0
    }

    // MARK: MixInfo bundle

    @Test
    fun `startup bundle has vendor property order and data`() {
        val encoder = LuckRingEncoder()
        val frame = encoder.startupBundle(profile = fixedProfile, goalSteps = 8000, date = fixedDate)
        assertEquals(LuckRingCmdType.SEND, frame.cmdType)
        assertEquals(110u.toUByte(), frame.dataType)

        val props = LuckRingMixInfoTLV.decode(frame.payload)
        // Exact order of `sendAynInfoDetail()`: 102, 104, 124, 103, 109, 111, 120.
        assertEquals(listOf<UByte>(102u, 104u, 124u, 103u, 109u, 111u, 120u), props.map { it.type })
        assertEquals(LuckRingEncoder.userInfoBytes(fixedProfile), props[0].data)
        assertEquals(9, props[1].data.size)                          // time
        assertEquals(listOf<UByte>(1u, 0xFFu, 0xFFu, 0u, 0u), props[2].data)   // call-alarm constant
        assertEquals(listOf<UByte>(0u), props[3].data)               // language
        assertEquals(listOf<UByte>(1u), props[4].data)               // data-switch enables real-time pushes
        assertEquals(LuckRingEncoder.goalBytes(8000), props[5].data)
        // Android always sends the non-first-bind token (see LuckRingEncoder.startupBundle doc) --
        // the ring/driver layer has no persisted-flag seam, so the pairing animation is deliberately
        // never retriggered.
        assertEquals("Android never retriggers the ring's pairing animation", listOf<UByte>(0u, 0u), props[6].data)
    }

    // MARK: Toggles / requests

    @Test
    fun `real time toggle layouts`() {
        val encoder = LuckRingEncoder()
        assertFrame(encoder.realHeartRate(true), LuckRingCmdType.SEND, 24u, listOf<UByte>(1u))
        assertFrame(encoder.realSpO2(true), LuckRingCmdType.SEND, 20u, listOf<UByte>(1u, 0u, 0u, 0u, 0u))
        assertFrame(encoder.realHRV(true), LuckRingCmdType.SEND, 45u, listOf<UByte>(1u))
        assertFrame(encoder.realBloodPressure(true), LuckRingCmdType.SEND, 18u, listOf<UByte>(1u, 0u, 0u, 0u, 0u, 0u))
        assertFrame(encoder.realTemperature(true), LuckRingCmdType.SEND, 46u, listOf<UByte>(1u))
        assertFrame(encoder.realHeartRate(false), LuckRingCmdType.SEND, 24u, listOf<UByte>(0u))
    }

    @Test
    fun `request is an empty request frame`() {
        val encoder = LuckRingEncoder()
        assertFrame(encoder.request(LuckRingDataType.BATTERY), LuckRingCmdType.REQUEST, 3u, emptyList())
    }

    @Test
    fun `auto monitoring matches K6_HEART_AUTO_SWITCH`() {
        val encoder = LuckRingEncoder()
        val settings = MeasurementSettings(
            hrEnabled = true, hrIntervalMinutes = 30,
            spo2Enabled = true, stressEnabled = false, hrvEnabled = false, temperatureEnabled = false,
        )
        assertFrame(encoder.autoMonitoring(settings), LuckRingCmdType.SEND, 128u, listOf<UByte>(1u, 0u, 30u, 1u, 0u, 0u, 0u, 0u))
        val off = MeasurementSettings(
            hrEnabled = false, hrIntervalMinutes = 5,
            spo2Enabled = false, stressEnabled = false, hrvEnabled = false, temperatureEnabled = false,
        )
        assertFrame(encoder.autoMonitoring(off), LuckRingCmdType.SEND, 128u, listOf<UByte>(0u, 0u, 5u, 0u, 0u, 0u, 0u, 0u))
    }

    @Test
    fun `find device and unbind`() {
        val encoder = LuckRingEncoder()
        assertFrame(encoder.findDevice(), LuckRingCmdType.SEND, 11u, listOf<UByte>(1u))
        assertFrame(encoder.unbind(), LuckRingCmdType.SEND, 159u, listOf<UByte>(1u))
    }

    @Test
    fun `sequence counter increments per frame`() {
        val encoder = LuckRingEncoder()
        assertEquals(0u.toUByte(), encoder.realHeartRate(true).seq)
        assertEquals(1u.toUByte(), encoder.realHeartRate(false).seq)
        assertEquals(2u.toUByte(), encoder.request(LuckRingDataType.BATTERY).seq)
    }

    private fun assertFrame(frame: LuckRingFrame, cmd: LuckRingCmdType, dataType: UByte, payload: List<UByte>) {
        assertEquals(cmd, frame.cmdType)
        assertEquals(dataType, frame.dataType)
        assertEquals(payload, frame.payload)
    }
}
