package com.pulseloop.ring

import java.time.Instant
import java.util.TimeZone
import java.util.UUID

/**
 * Ported from YCBTProtocol.swift.
 * Yucheng YCBT protocol primitives — GATT topology, framing, byte/epoch helpers, opcodes,
 * and the health-record type table. This is the wire language the R10M speaks.
 *
 * Wire format (both command channel be940001 and async stream be940003):
 *   [type:1][cmd:1][len:2 LE][payload:N][crc16:2 LE]
 * where len is the *total* frame length (header + payload + crc) and the CRC is
 * CRC16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection) over every byte before it.
 */

object YCBTUUIDs {
    const val SERVICE = "be940000-7333-be46-b7ae-689e71722bd5"
    const val COMMAND = "be940001-7333-be46-b7ae-689e71722bd5"
    const val STREAM = "be940003-7333-be46-b7ae-689e71722bd5"
}

class YCBTFrame(
    val type: Int,
    val cmd: Int,
    val payload: ByteArray,
) {
    companion object {
        /** Parse and CRC-validate one inbound frame. Returns null on a short frame or CRC mismatch. */
        fun validating(data: ByteArray): YCBTFrame? {
            val bytes = data
            if (bytes.size < 6) return null
            val declared = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
            if (declared != bytes.size) return null
            val crcGiven = (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
            if (crc16(bytes, 0, bytes.size - 2) != crcGiven) return null
            return YCBTFrame(
                type = bytes[0].toInt() and 0xFF,
                cmd = bytes[1].toInt() and 0xFF,
                payload = bytes.copyOfRange(4, bytes.size - 2)
            )
        }

        /** Build a framed packet from a logical command [type, cmd, payload…]: insert the total-length
         * field after the two header bytes and append the little-endian CRC16. */
        fun frame(logical: ByteArray): ByteArray {
            if (logical.size < 2) return logical.copyOf()
            val total = logical.size + 4 // + 2-byte length field + 2-byte CRC
            val out = ByteArray(total)
            out[0] = logical[0]
            out[1] = logical[1]
            out[2] = (total and 0xFF).toByte()
            out[3] = ((total shr 8) and 0xFF).toByte()
            System.arraycopy(logical, 2, out, 4, logical.size - 2)
            val crc = crc16(out, 0, out.size - 2)
            out[out.size - 2] = (crc and 0xFF).toByte()
            out[out.size - 1] = ((crc shr 8) and 0xFF).toByte()
            return out
        }

        /** CRC16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no input/output reflection, no final xor). */
        fun crc16(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
            var crc = 0xFFFF
            for (i in offset until offset + length) {
                val b = bytes[i].toInt() and 0xFF
                crc = crc xor (b shl 8)
                repeat(8) {
                    crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF
                }
            }
            return crc and 0xFFFF
        }

        fun crc16(bytes: ByteArray): Int = crc16(bytes, 0, bytes.size)
    }
}

/** Little-endian + epoch helpers. YCBT timestamps are seconds since 2000-01-01 UTC. */
object YCBTBytes {
    const val EPOCH_OFFSET = 946_684_800L

    fun u16(bytes: ByteArray, i: Int): Int {
        if (bytes.size < i + 2) return 0
        return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    }

    fun u24(bytes: ByteArray, i: Int): Int {
        if (bytes.size < i + 3) return 0
        return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or ((bytes[i + 2].toInt() and 0xFF) shl 16)
    }

    fun u32(bytes: ByteArray, i: Int): Int {
        if (bytes.size < i + 4) return 0
        return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8) or
               ((bytes[i + 2].toInt() and 0xFF) shl 16) or ((bytes[i + 3].toInt() and 0xFF) shl 24)
    }

    /** Convert ring seconds (2000-epoch) to an Instant. The ring clock is local wall-clock; decoding
     * must un-apply the device's UTC offset to recover the true absolute instant. */
    fun date(ringSeconds: Int, timeZone: TimeZone = TimeZone.getDefault()): Instant {
        // The ring stores local wall-clock seconds. Match Foundation's
        // secondsFromGMT() approximation and include the currently active DST offset.
        val offset = timeZone.getOffset(System.currentTimeMillis()) / 1000
        return Instant.ofEpochSecond(ringSeconds.toLong() + EPOCH_OFFSET - offset)
    }

    /** Convert an Instant to ring seconds (2000-epoch), the inverse of date(). */
    fun ringSeconds(date: Instant, timeZone: TimeZone = TimeZone.getDefault()): Int {
        val offset = timeZone.getOffset(date.toEpochMilli()) / 1000
        return (date.epochSecond - EPOCH_OFFSET + offset).toInt()
    }
}

/** Frame type byte — the command group. */
object YCBTGroup {
    const val SETTING: Int = 0x01
    const val GET: Int = 0x02
    const val APP_CONTROL: Int = 0x03
    const val DEV_CONTROL: Int = 0x04
    const val HEALTH: Int = 0x05
    const val REAL: Int = 0x06
}

/** The cmd bytes we act on, by group. */
object YCBTCommand {
    // Group 0x02 (Get)
    const val GET_DEVICE_INFO: Int = 0x00
    const val GET_SUPPORT_FUNCTION: Int = 0x01
    const val GET_DEVICE_NAME: Int = 0x03
    const val GET_USER_CONFIG: Int = 0x07
    const val GET_CHIP_SCHEME: Int = 0x1b

    // Group 0x03 (AppControl)
    const val FIND_DEVICE: Int = 0x00
    const val LIVE_MEASUREMENT: Int = 0x2f
    const val LIVE_STATUS_PUSH: Int = 0x09

    // Group 0x06 (Real — async stream)
    const val LIVE_STATUS: Int = 0x00
    const val LIVE_HEART_RATE: Int = 0x01
    const val LIVE_SPO2: Int = 0x02
    const val LIVE_VITALS: Int = 0x03
    const val LIVE_WEARING_STATUS: Int = 0x13
    const val LIVE_BATTERY: Int = 0x15
}

/** One health-history record type: query key, ack key, record stride, and label. */
data class YCBTHistoryType(
    val queryKey: Int,
    val ackKey: Int,
    val recordStride: Int?,
    val label: String,
) {
    companion object {
        val SPORT = YCBTHistoryType(0x02, 0x11, 14, "activity")
        val SLEEP = YCBTHistoryType(0x04, 0x13, null, "sleep")
        val HEART = YCBTHistoryType(0x06, 0x15, 6, "heart rate")
        val BLOOD = YCBTHistoryType(0x08, 0x17, 8, "blood pressure")
        val ALL = YCBTHistoryType(0x09, 0x18, 20, "vitals")
        val SPO2 = YCBTHistoryType(0x1a, 0x22, 6, "blood oxygen")
        val TEMPERATURE = YCBTHistoryType(0x1e, 0x26, 7, "temperature")
        val COMPREHENSIVE = YCBTHistoryType(0x2f, 0x30, 44, "metabolic")
        val BODY_DATA = YCBTHistoryType(0x33, 0x34, 28, "body data")

        val CATALOG: List<YCBTHistoryType> = listOf(
            SPORT, SLEEP, HEART, BLOOD, ALL, SPO2, TEMPERATURE, COMPREHENSIVE, BODY_DATA
        )
    }
}

/** The measurement-mode byte shared by start/stop and status/result push. */
object YCBTMeasurementMode {
    const val HEART_RATE: Int = 0x00
    const val BLOOD_PRESSURE: Int = 0x01
    const val SPO2: Int = 0x02
    const val RESPIRATORY_RATE: Int = 0x03
    const val TEMPERATURE: Int = 0x04
    const val BLOOD_SUGAR: Int = 0x05
    const val URIC_ACID: Int = 0x06
    const val BLOOD_KETONE: Int = 0x07
    const val BLOOD_FAT: Int = 0x09
    const val HRV: Int = 0x0a
    const val STRESS: Int = 0x0c

    fun isAccepted(status: Int): Boolean = status == 0x00
}

/** Group 4 (DevControl) — ring→app push keys. */
object YCBTDevControl {
    const val FIND_PHONE: Int = 0x00
    const val SOS: Int = 0x05
    const val MEASUREMENT_RESULT: Int = 0x0e
    const val MEASUREMENT_STATUS: Int = 0x13
    const val SEDENTARY_REMINDER: Int = 0x16
    const val SOS_CALL: Int = 0x17

    fun ack(key: Int): ByteArray = byteArrayOf(YCBTGroup.DEV_CONTROL.toByte(), key.toByte(), 0x00)

    const val RESULT_SUCCESS: Int = 0x01
}

/** Health-group control keys and ACK status bytes. */
object YCBTHealth {
    const val TERMINAL_BLOCK: Int = 0x80
    const val ACK_ACCEPTED: Int = 0x00
    const val ACK_CRC_FAILURE: Int = 0x04
    const val HEADER_PAYLOAD_LENGTH: Int = 10
    const val TERMINAL_PAYLOAD_LENGTH: Int = 6
}

/** Logical (unframed) Health-group commands. */
object YCBTHealthCommand {
    fun historyRequest(type: YCBTHistoryType): ByteArray =
        byteArrayOf(YCBTGroup.HEALTH.toByte(), type.queryKey.toByte())

    fun historyBlockAck(status: Int): ByteArray =
        byteArrayOf(YCBTGroup.HEALTH.toByte(), YCBTHealth.TERMINAL_BLOCK.toByte(), status.toByte())
}

/** Device-side rejection of a command. */
enum class YCBTFrameError(val code: Int) {
    UNSUPPORTED_COMMAND(0xfb),
    UNSUPPORTED_KEY(0xfc),
    LENGTH(0xfd),
    DATA(0xfe),
    CRC(0xff);

    companion object {
        fun detect(payload: ByteArray): YCBTFrameError? {
            if (payload.size != 1) return null
            return entries.find { it.code == (payload[0].toInt() and 0xFF) }
        }
    }

    val isPermanent: Boolean get() = this == UNSUPPORTED_COMMAND || this == UNSUPPORTED_KEY
}

/** Reassembles GATT notifications into whole logical frames. */
class YCBTFrameAssembler {
    private val minFrameLength = 6
    private val maxFrameLength = 1024
    private val pending = mutableMapOf<String, ByteArray>()

    fun reset() {
        pending.clear()
    }

    /** Feed one notification; returns the complete logical frames it completed (0, 1, or several). */
    fun append(data: ByteArray, fromCharacteristic: String): List<ByteArray> {
        var buffer = pending[fromCharacteristic] ?: ByteArray(0)
        buffer += data

        val frames = mutableListOf<ByteArray>()
        while (buffer.size >= 4) {
            val declared = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
            if (!isPlausibleGroup(buffer[0]) || declared < minFrameLength || declared > maxFrameLength) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            if (buffer.size < declared) break
            frames.add(buffer.copyOfRange(0, declared))
            buffer = buffer.copyOfRange(declared, buffer.size)
        }
        pending[fromCharacteristic] = buffer
        return frames
    }

    private fun isPlausibleGroup(byte: Byte): Boolean {
        val b = byte.toInt() and 0xFF
        return b in YCBTGroup.SETTING..YCBTGroup.REAL
    }
}

/** Parser for the 02 01 SupportFunction reply: variable-length bit array. */
object YCBTSupportFunction {
    private data class Bit(val byte: Int, val bit: Int, val minLength: Int, val capability: WearableCapability)

    private val bits = listOf(
        Bit(0, 7, 14, WearableCapability.STEPS),          // ISHASSTEPCOUNT
        Bit(0, 6, 14, WearableCapability.SLEEP),         // ISHASSLEEP
        Bit(0, 3, 14, WearableCapability.HEART_RATE),      // ISHASHEARTRATE
        Bit(0, 0, 14, WearableCapability.BLOOD_PRESSURE),  // ISHASBLOOD
        Bit(1, 3, 14, WearableCapability.SPO2),            // ISHASBLOODOXYGEN
        Bit(1, 1, 14, WearableCapability.HRV),             // ISHASHRV
        Bit(8, 0, 14, WearableCapability.TEMPERATURE),     // ISHASTEMP
        Bit(17, 3, 18, WearableCapability.BLOOD_SUGAR),   // ISHASBLOODSUGAR
        Bit(22, 6, 23, WearableCapability.STRESS),         // IS_HAS_PRESSURE
        Bit(22, 6, 23, WearableCapability.FATIGUE),        // IS_HAS_PRESSURE (same record)
        Bit(6, 4, 14, WearableCapability.FIND_DEVICE),     // ISHASFINDDEVICE
        Bit(15, 1, 18, WearableCapability.MANUAL_HEART_RATE),      // ISHATESTHEART
        Bit(15, 2, 18, WearableCapability.MANUAL_BLOOD_PRESSURE),  // ISHASTESTBLOOD
        Bit(15, 3, 18, WearableCapability.MANUAL_SPO2),           // ISHASTESTSPO2
        Bit(23, 0, 24, WearableCapability.MANUAL_HRV),             // IS_HAS_HRV_MEASUREMENT
    )

    fun capabilities(payload: ByteArray): Set<WearableCapability> {
        val out = mutableSetOf<WearableCapability>()
        for (b in bits) {
            if (payload.size >= b.minLength && b.byte < payload.size) {
                if (((payload[b.byte].toInt() and 0xFF) shr b.bit) and 1 == 1) {
                    out.add(b.capability)
                }
            }
        }
        return out
    }

    fun rawBits(payload: ByteArray): List<Boolean> {
        return payload.flatMap { byte ->
            (0..7).map { ((byte.toInt() and 0xFF) shr (7 - it)) and 1 == 1 }
        }
    }
}

/** The 02 1b chipScheme reply — diagnostic only. */
object YCBTChipScheme {
    fun value(payload: ByteArray): Int {
        val first = (payload.firstOrNull()?.toInt() ?: return 0) and 0xFF
        return if (first >= 240) 0 else first
    }

    fun isJieLi(value: Int): Boolean = value in 3..5
}
