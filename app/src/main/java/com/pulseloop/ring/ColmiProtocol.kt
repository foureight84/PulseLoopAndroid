package com.pulseloop.ring

/**
 * Ported from [ColmiUUIDs] and [ColmiCommandID] in ColmiProtocol.swift.
 * Colmi R02 (Yawell) protocol primitives: UUIDs, opcodes, 16-byte checksummed framing.
 */
object ColmiUUIDs {
    const val SERVICE_V1 = "6e40fff0-b5a3-f393-e0a9-e50e24dcca9e"
    const val SERVICE_V2 = "de5bf728-d711-4e47-af26-65e3012a5dc7"
    const val WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val COMMAND = "de5bf72a-d711-4e47-af26-65e3012a5dc7"
    const val NOTIFY_V1 = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    const val NOTIFY_V2 = "de5bf729-d711-4e47-af26-65e3012a5dc7"
}

object ColmiCommandID {
    const val SET_DATE_TIME: UByte = 0x01u
    const val BATTERY: UByte = 0x03u
    const val PHONE_NAME: UByte = 0x04u
    const val DISPLAY_PREF: UByte = 0x05u
    const val POWER_OFF: UByte = 0x08u
    const val PREFERENCES: UByte = 0x0Au
    const val SYNC_HEART_RATE: UByte = 0x15u
    const val AUTO_HR_PREF: UByte = 0x16u
    const val REALTIME_HEART_RATE: UByte = 0x1Eu
    const val REALTIME_HEART_RATE_ERROR: UByte = 0x9Eu
    const val GOALS: UByte = 0x21u
    const val AUTO_SPO2_PREF: UByte = 0x2Cu
    const val PACKET_SIZE: UByte = 0x2Fu
    const val AUTO_STRESS_PREF: UByte = 0x36u
    const val SYNC_STRESS: UByte = 0x37u
    const val AUTO_HRV_PREF: UByte = 0x38u
    const val SYNC_HRV: UByte = 0x39u
    const val AUTO_TEMP_PREF: UByte = 0x3Au
    const val SYNC_ACTIVITY: UByte = 0x43u
    const val BP_READ: UByte = 0x14u
    const val BP_CONFIRM: UByte = 0x0Eu
    const val FIND_DEVICE: UByte = 0x50u
    const val MANUAL_HEART_RATE: UByte = 0x69u   // CMD_START_REAL_TIME (105)
    const val REALTIME_STOP: UByte = 0x6Au       // CMD_STOP_REAL_TIME (106)
    const val NOTIFICATION: UByte = 0x73u
    const val BIG_DATA_V2: UByte = 0xBCu
    const val FACTORY_RESET: UByte = 0xFFu

    const val PREF_READ: UByte = 0x01u
    const val PREF_WRITE: UByte = 0x02u
    const val PREF_DELETE: UByte = 0x03u

    // Real-time measurement reading types (0x69/0x6A payload byte 0).
    // From the colmi_r02_client RealTimeReading enum.
    const val RT_HEART_RATE: UByte = 0x01u
    const val RT_SPO2: UByte = 0x03u
    // Real-time actions (0x69/0x6A payload byte 1).
    const val RT_ACTION_START: UByte = 0x01u
    const val RT_ACTION_STOP: UByte = 0x02u

    // 0x73 notification subtypes
    const val NOTIF_NEW_HR: UByte = 0x01u
    const val NOTIF_NEW_SPO2: UByte = 0x03u
    const val NOTIF_NEW_STEPS: UByte = 0x04u
    const val NOTIF_BATTERY: UByte = 0x0Cu
    const val NOTIF_LIVE_ACTIVITY: UByte = 0x12u

    // Big-data types
    const val BIG_DATA_BLOOD_SUGAR: UByte = 0x47u
    const val BIG_DATA_TEMPERATURE: UByte = 0x25u
    const val BIG_DATA_SLEEP: UByte = 0x27u
    const val BIG_DATA_SPO2: UByte = 0x2Au

    // Sleep stage types
    const val SLEEP_LIGHT: UByte = 0x02u
    const val SLEEP_DEEP: UByte = 0x03u
    const val SLEEP_REM: UByte = 0x04u
    const val SLEEP_AWAKE: UByte = 0x05u
}

/**
 * Ported from [ColmiPacket] in ColmiProtocol.swift.
 * A 16-byte Colmi frame: 15 content bytes + trailing checksum.
 */
data class ColmiPacket(val bytes: ByteArray) {
    companion object {
        /** Build a framed 16-byte packet from ≤15 content bytes + checksum. */
        fun frame(content: ByteArray): ByteArray {
            val buffer = ByteArray(16)
            val count = minOf(content.size, 15)
            content.copyInto(buffer, 0, 0, count)
            val checksum = (0 until 15).sumOf { buffer[it].toInt() and 0xFF } and 0xFF
            buffer[15] = checksum.toByte()
            return buffer
        }

        /** Validate + decode a 16-byte frame. Returns null on bad length or checksum. */
        fun validating(data: ByteArray): ColmiPacket? {
            if (data.size != 16) return null
            val checksum = (0 until 15).sumOf { data[it].toInt() and 0xFF } and 0xFF
            if (checksum != (data[15].toInt() and 0xFF)) return null
            return ColmiPacket(data)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColmiPacket) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * Ported from [ColmiBytes] in ColmiProtocol.swift.
 */
object ColmiBytes {
    fun u16(a: UByte, b: UByte): Int = a.toInt() or (b.toInt() shl 8)
    fun u32(a: UByte, b: UByte, c: UByte, d: UByte): Int =
        a.toInt() or (b.toInt() shl 8) or (c.toInt() shl 16) or (d.toInt() shl 24)
    fun u24(a: UByte, b: UByte, c: UByte): Int =
        a.toInt() or (b.toInt() shl 8) or (c.toInt() shl 16)
    fun u24be(a: UByte, b: UByte, c: UByte): Int =
        (a.toInt() shl 16) or (b.toInt() shl 8) or c.toInt()
}
