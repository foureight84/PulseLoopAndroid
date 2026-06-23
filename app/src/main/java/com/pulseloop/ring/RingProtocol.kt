package com.pulseloop.ring

/**
 * BLE UUIDs for the 56ff ring service.
 * Ported from [RingUUIDs] in RingProtocol.swift.
 */
object RingUUIDs {
    const val SERVICE = "000056ff-0000-1000-8000-00805f9b34fb"
    const val WRITE = "000033f3-0000-1000-8000-00805f9b34fb"
    const val NOTIFY = "000033f4-0000-1000-8000-00805f9b34fb"
    const val BATTERY = "00002a19-0000-1000-8000-00805f9b34fb"
}

/**
 * Ring command identifiers for the 56ff protocol.
 * Ported from [RingCommandID] in RingProtocol.swift.
 */
enum class RingCommandID(val code: UByte) {
    TIME_SYNC(0x01u),
    USER_INFO(0x02u),
    CURRENT_ACTIVITY(0x03u),
    FIND_RING(0x04u),
    ANTI_LOST(0x05u),
    DEVICE_COMMAND(0x06u),
    CAMERA_MODE(0x07u),
    PERCENT_STATUS(0x0Bu),
    STATUS(0x0Cu),
    ALARM(0x0Du),
    DEVICE_MODE(0x0Eu),
    HISTORY_SUMMARY(0x10u),
    SLEEP_TIMELINE(0x11u),
    ALERT_NOTIFICATION(0x12u),
    ACTIVITY_SUMMARY(0x13u),
    HEART_RATE_SAMPLE_OR_START(0x14u),
    HEART_RATE_STOP(0x15u),
    HISTORY_MEASUREMENT_STREAM(0x16u),
    AUTO_HR_MODE(0x19u),
    STEP_GOAL(0x1Au),
    DEVICE_SETTINGS(0x1Bu),
    HOUR_FORMAT(0x1Du),
    DEVICE_CAPABILITIES(0x20u),
    LOCALE(0x21u),
    WEATHER(0x22u),
    COMBINED_MEASUREMENT(0x23u),
    COMBINED_RESULT(0x24u),
    SPORT_REPORT(0x25u),
    HR_ALERT_AREA(0x26u),
    SENSOR_COMPLETE(0x27u),
    BLOOD_DATA_NOTIFY(0x28u),
    REMINDER(0x31u),
    WALLPAPER(0x34u),
    KEEPALIVE_PING(0x3Au),
    SPO2_TOGGLE(0x3Eu),
    SPO2_RESULT(0x3Fu),
    MENSTRUAL_CYCLE(0x44u),
    APP_IDENTIFIER(0x48u);
    // NOTE: 0x52 is CMD_SET_APP_STATE in the SXR SDK (setAppState0), NOT temperature.
    // The Jring ring has no temperature sensor, so no temperature opcode is defined here.

    companion object {
        fun fromCode(code: UByte): RingCommandID? = entries.find { it.code == code }
    }
}

/**
 * Ported from [RingPacket] in RingProtocol.swift.
 * Fixed 20-byte packet: command ID byte + 19 payload bytes.
 */
data class RingPacket(
    val commandId: UByte,
    val payload: ByteArray,
    val raw: ByteArray
) {
    companion object {
        const val PACKET_SIZE = 20

        fun fromData(data: ByteArray): Result<RingPacket> {
            if (data.size != PACKET_SIZE) {
                return Result.failure(
                    RingProtocolError.InvalidLength(data.size)
                )
            }
            val cmdId = data[0].toUByte()
            val payload = data.copyOfRange(1, data.size)
            return Result.success(
                RingPacket(commandId = cmdId, payload = payload, raw = data)
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RingPacket) return false
        return raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int = raw.contentHashCode()
}

/**
 * Ported from [RingProtocolError] in RingProtocol.swift.
 */
sealed class RingProtocolError(message: String) : Exception(message) {
    class InvalidLength(length: Int) :
        RingProtocolError("Invalid packet length: $length (expected ${RingPacket.PACKET_SIZE})")
    class InvalidHex(hex: String) :
        RingProtocolError("Invalid hex string: $hex")
}
