package com.pulseloop.ring

/**
 * One deframed legacy (`0x7E`) event, as surfaced to [RWfitDriver].
 *
 * The vendor ACKs **before** it parses (`x5/d.java h()` calls `b(...)` on the way through), so
 * [AckNeeded] is emitted ahead of the [Frame] it belongs to and the driver must preserve that order.
 */
sealed interface RWfitLegacyInbound {
    /** A complete data frame — single-packet, or a fully reassembled multi-packet payload. */
    data class Frame(val cmd: Byte, val payload: ByteArray) : RWfitLegacyInbound {
        override fun equals(other: Any?): Boolean =
            other is Frame && cmd == other.cmd && payload.contentEquals(other.payload)
        override fun hashCode(): Int = 31 * cmd.toInt() + payload.contentHashCode()
    }

    /** The device ACKed one of our commands: `0xFE` with `[serHi, serLo, cmd, status]`. */
    data class DeviceAck(val cmd: Byte, val serial: Int, val status: Byte) : RWfitLegacyInbound

    /** This inbound frame must be app-ACKed with the given serial and status `0x00`. */
    data class AckNeeded(val cmd: Byte, val serial: Int) : RWfitLegacyInbound

    /** The frame failed its XOR check; NACK it with status `0x02` so the device retransmits. */
    data class ChecksumFailed(val cmd: Byte, val serial: Int) : RWfitLegacyInbound
}

/**
 * Legacy (`0x7E`, "Realtek") wire codec — framing, serials, XOR checksums, the ACK handshake and
 * inbound multi-packet reassembly. Ported from `x5/d.java` (`CmdHandlerUtils`) in
 * `decompiled-rwfit-official/sources/`.
 *
 * **Frame layout** (`x5/d.java j()` encodes, `h()` decodes):
 * ```
 * single packet (8-byte header):
 *   [0] 0x7E   [1] 0x01 version   [2] cmd   [3] flags   [4] dataLen
 *   [5..6] serial BE u16          [7] XOR of payload (0x00 when empty)
 *   [8..]  payload
 *
 * multi-packet (12-byte header, flags bit 3 set):
 *   [0..7] as above, with [4]/[7] describing *this chunk*
 *   [8..9]  total chunk count BE u16
 *   [10..11] this chunk index BE u16, 1-based
 *   [12..]  chunk payload
 * ```
 * Serials run 1…65535 and wrap (`x5/d.java a()`). They are an echo token, not a sequence check, so
 * [reset] deliberately keeps counting across links — exactly as the vendor does.
 *
 * Not ported: outbound multi-packet splitting. The vendor only chunks file transfers
 * (`x5/d.java j()`'s `length > 1` branch), which PulseLoop never sends — our largest payload is the
 * bind userId, well under one packet. [encode] rejects anything that wouldn't fit rather than
 * silently truncating.
 */
class RWfitLegacyCodec {

    /** Outbound serial counter (`x5/d.java` field `e`, starts at 1). */
    private var serial: Int = 0

    /** In-flight inbound reassembly, keyed by cmd id (`x5/d.java` field `f19809c`). */
    private val partials = mutableMapOf<Byte, MutableList<Pair<Int, ByteArray>>>()

    /**
     * Drop cross-frame state. Call on connect and disconnect — a chunk left over from a dropped
     * link must never complete a frame on the next one (`x5/d.java v()` clears the same map).
     */
    fun reset() {
        partials.clear()
    }

    private fun nextSerial(): Int {
        serial = if (serial >= MAX_SERIAL) 1 else serial + 1
        return serial
    }

    // ── Encode ───────────────────────────────────────────────────────────────────

    /** A framed command plus the serial it was stamped with, so the gate can match its `0xFE`. */
    data class Outbound(val frame: ByteArray, val serial: Int) {
        override fun equals(other: Any?): Boolean =
            other is Outbound && serial == other.serial && frame.contentEquals(other.frame)
        override fun hashCode(): Int = 31 * serial + frame.contentHashCode()
    }

    fun encode(cmd: Byte, payload: ByteArray = ByteArray(0)): Outbound {
        require(payload.size <= MAX_SINGLE_PACKET_PAYLOAD) {
            "legacy payload ${payload.size} exceeds single-frame capacity $MAX_SINGLE_PACKET_PAYLOAD"
        }
        val serial = nextSerial()
        val serialBytes = RWfitProtocol.u16BE(serial)
        val frame = ByteArray(HEADER_SIZE + payload.size)
        frame[0] = FRAME_HEADER
        frame[1] = PROTOCOL_VERSION
        frame[2] = cmd
        frame[3] = 0                                   // single packet: no flags
        frame[4] = (payload.size and 0xFF).toByte()
        frame[5] = serialBytes[0]
        frame[6] = serialBytes[1]
        frame[7] = if (payload.isEmpty()) 0 else RWfitProtocol.xorChecksum(payload)
        payload.copyInto(frame, HEADER_SIZE)
        return Outbound(frame, serial)
    }

    /**
     * The app→device ACK for an inbound frame: cmd `0xFF`, payload `[serHi, serLo, cmd, status]`
     * carrying the *inbound* frame's serial, while the ACK frame's own header serial is freshly
     * assigned (`x5/d.java b()` → `j((byte) -1, …)`). Status `0` = accepted, `2` = checksum failure.
     */
    fun ack(cmd: Byte, inboundSerial: Int, status: Byte): ByteArray {
        val ser = RWfitProtocol.u16BE(inboundSerial)
        return encode(RWfitProtocol.Legacy.APP_ACK, byteArrayOf(ser[0], ser[1], cmd, status)).frame
    }

    // ── Decode ───────────────────────────────────────────────────────────────────

    /**
     * Deframe one notification. Returns an empty list for anything that isn't a `0x7E` frame — the
     * vendor logs and drops those too ("接收到其他指令，不处理").
     *
     * Unlike the reverted implementation, this does not keep a rolling byte buffer: the vendor
     * treats each BLE notification as one whole frame (`r5/b.java onCharacteristicChanged` hands
     * `value` straight to `h()`), and *fragmentation is expressed in the protocol* via the
     * multi-packet header rather than by splitting frames across notifications.
     */
    fun decode(data: ByteArray): List<RWfitLegacyInbound> {
        if (data.size < HEADER_SIZE || data[0] != FRAME_HEADER) return emptyList()

        val cmd = data[2]
        val flags = data[3].toInt()
        val isMultiPacket = (flags shr 3) and 1 == 1
        val dataLen = data[4].toInt() and 0xFF
        val serial = RWfitProtocol.readU16BE(data, 5)
        val checksum = data[7]

        // `x5/d.java h()` takes the single-packet path when the multi-packet flag is clear *or* the
        // frame is too short to hold the 12-byte header.
        val bodyOffset = if (isMultiPacket && data.size > MULTI_HEADER_SIZE - 3) MULTI_HEADER_SIZE else HEADER_SIZE
        if (data.size < bodyOffset + dataLen) return emptyList()
        val chunk = data.copyOfRange(bodyOffset, bodyOffset + dataLen)

        if (dataLen > 0 && checksum != RWfitProtocol.xorChecksum(chunk)) {
            return listOf(RWfitLegacyInbound.ChecksumFailed(cmd, serial))
        }

        // A device ACK is never itself ACKed (`x5/d.java h()`: `if (b10 != -2) b(...)`).
        if (cmd == RWfitProtocol.Legacy.DEVICE_ACK) {
            if (chunk.size < 4) return emptyList()
            val ackedSerial = RWfitProtocol.readU16BE(chunk, 0)
            return listOf(RWfitLegacyInbound.DeviceAck(cmd = chunk[2], serial = ackedSerial, status = chunk[3]))
        }

        val events = mutableListOf<RWfitLegacyInbound>(RWfitLegacyInbound.AckNeeded(cmd, serial))

        if (bodyOffset == HEADER_SIZE) {
            events.add(RWfitLegacyInbound.Frame(cmd, chunk))
            return events
        }

        // Multi-packet: accumulate by cmd id, emit once the last (1-based) index has landed.
        val total = RWfitProtocol.readU16BE(data, 8)
        val index = RWfitProtocol.readU16BE(data, 10)
        val bucket = partials.getOrPut(cmd) { mutableListOf() }
        bucket.add(index to chunk)
        if (index == total && bucket.size == total) {
            val assembled = bucket.sortedBy { it.first }.fold(ByteArray(0)) { acc, p -> acc + p.second }
            partials.remove(cmd)
            events.add(RWfitLegacyInbound.Frame(cmd, assembled))
        }
        return events
    }

    private companion object {
        const val FRAME_HEADER: Byte = 0x7E
        const val PROTOCOL_VERSION: Byte = 0x01
        const val HEADER_SIZE = 8
        const val MULTI_HEADER_SIZE = 12
        const val MAX_SERIAL = 65535
        /** `dataLen` is a single byte (`x5/d.java j()`: `bArr2[4] = (byte) (bArr.length & 255)`). */
        const val MAX_SINGLE_PACKET_PAYLOAD = 0xFF
    }
}
