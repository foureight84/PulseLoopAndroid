package com.pulseloop.ring

/** One deframed JieLi (`0xAB`) event. */
sealed interface RWfitJLInbound {
    /**
     * A complete frame. [body] still carries its `{cmd, key, keyFlag}` triple in bytes 0..2 —
     * the length field and the CRC both cover the triple, so stripping it here would desync them.
     */
    data class Frame(val flag: Byte, val triple: RWfitProtocol.JLTriple, val body: ByteArray) : RWfitJLInbound {
        /** The payload after the addressing triple. */
        val payload: ByteArray get() = if (body.size > 3) body.copyOfRange(3, body.size) else ByteArray(0)

        /** Flag `0x11` marks this as the device ACKing one of our commands. */
        val isAck: Boolean get() = flag == RWfitJLCodec.FLAG_ACK

        override fun equals(other: Any?): Boolean =
            other is Frame && flag == other.flag && triple == other.triple && body.contentEquals(other.body)
        override fun hashCode(): Int = 31 * (31 * flag.toInt() + triple.hashCode()) + body.contentHashCode()
    }

    /** The body failed its CRC-16/ARC check. The vendor drops these silently and waits. */
    data class ChecksumFailed(val triple: RWfitProtocol.JLTriple) : RWfitJLInbound
}

/**
 * JieLi (`0xAB`) wire codec. Ported from `x5/c.java g()` (encode) and `r5/b.java:386-476` (decode,
 * which the vendor inlines into its GATT callback) in `decompiled-rwfit-official/sources/`.
 *
 * **Frame layout:**
 * ```
 *   [0] 0xAB   [1] flag (0x01 normal, 0x11 ACK)
 *   [2..3] bodyLen BE u16     [4..5] CRC-16/ARC of body, BE
 *   [6] cmd    [7] key    [8] keyFlag       <- the triple is the first 3 bytes of the body
 *   [9..] payload
 * ```
 * `bodyLen` counts from index 6, i.e. it **includes** the triple, and the CRC covers the same span
 * (`r5/b.java`: `arraycopy(value, 6, body, 0, dataLen)` then `crc.equals(y5.d.a(body))`).
 *
 * Long bodies arrive as a header packet followed by raw continuation packets with no header of
 * their own; [decode] reassembles them using [chunkSize], which the driver sets from the negotiated
 * MTU (`r5/b.java j()` returns `mtu - 3 - 3` on JieLi links).
 */
class RWfitJLCodec(private var chunkSize: Int = DEFAULT_CHUNK_SIZE) {

    private var pending: Pending? = null

    private class Pending(val flag: Byte, val triple: RWfitProtocol.JLTriple, val crc: Int, val expected: Int) {
        val body = ByteArray(expected)
        var filled = 0
    }

    fun setChunkSize(size: Int) {
        if (size > 0) chunkSize = size
    }

    /** Drop cross-frame reassembly state. Call on connect and disconnect. */
    fun reset() {
        pending = null
    }

    // ── Encode ───────────────────────────────────────────────────────────────────

    fun encode(triple: RWfitProtocol.JLTriple, payload: ByteArray = ByteArray(0), isAck: Boolean = false): ByteArray {
        val body = triple.bytes + payload
        val len = RWfitProtocol.u16BE(body.size)
        val crc = RWfitProtocol.u16BE(RWfitProtocol.crc16Arc(body))
        return byteArrayOf(FRAME_HEADER, if (isAck) FLAG_ACK else FLAG_NORMAL, len[0], len[1], crc[0], crc[1]) + body
    }

    /**
     * The app→device ACK the vendor sends for every non-ACK inbound frame: flag `0x11`, body = the
     * inbound triple (`r5/b.java:429-444`). The realtime-measure triple `06 09 xx` is the one
     * exception — its ACK body carries a trailing `0x00`.
     */
    fun ack(triple: RWfitProtocol.JLTriple): ByteArray {
        val isRealtimeMeasure = triple.cmd == RWfitProtocol.JieLi.REALTIME_MEASURE.cmd &&
            triple.key == RWfitProtocol.JieLi.REALTIME_MEASURE.key
        val payload = if (isRealtimeMeasure) byteArrayOf(0x00) else ByteArray(0)
        return encode(triple, payload, isAck = true)
    }

    // ── Decode ───────────────────────────────────────────────────────────────────

    fun decode(data: ByteArray): List<RWfitJLInbound> {
        val inFlight = pending
        if (inFlight != null && (data.isEmpty() || data[0] != FRAME_HEADER)) {
            return appendContinuation(inFlight, data)
        }
        if (data.size < HEADER_SIZE || data[0] != FRAME_HEADER) return emptyList()

        val flag = data[1]
        val bodyLen = RWfitProtocol.readU16BE(data, 2)
        val crc = RWfitProtocol.readU16BE(data, 4)
        if (data.size < HEADER_SIZE + 3) return emptyList()
        val triple = RWfitProtocol.JLTriple(data[6], data[7], data[8])

        val available = data.size - HEADER_SIZE
        if (bodyLen > available) {
            // Header packet of a multi-packet body — keep what arrived and wait for continuations.
            val p = Pending(flag, triple, crc, bodyLen)
            data.copyInto(p.body, 0, HEADER_SIZE, data.size)
            p.filled = available
            pending = p
            return emptyList()
        }

        val body = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + bodyLen)
        return finish(flag, triple, crc, body)
    }

    private fun appendContinuation(p: Pending, data: ByteArray): List<RWfitJLInbound> {
        val room = p.expected - p.filled
        val take = minOf(room, data.size)
        data.copyInto(p.body, p.filled, 0, take)
        p.filled += take
        if (p.filled < p.expected) return emptyList()
        pending = null
        return finish(p.flag, p.triple, p.crc, p.body)
    }

    private fun finish(flag: Byte, triple: RWfitProtocol.JLTriple, crc: Int, body: ByteArray): List<RWfitJLInbound> {
        if (RWfitProtocol.crc16Arc(body) != crc) return listOf(RWfitJLInbound.ChecksumFailed(triple))
        return listOf(RWfitJLInbound.Frame(flag, triple, body))
    }

    companion object {
        const val FRAME_HEADER: Byte = 0xAB.toByte()
        const val FLAG_NORMAL: Byte = 0x01
        const val FLAG_ACK: Byte = 0x11
        const val HEADER_SIZE = 6
        /** Conservative pre-MTU-negotiation default (23-byte ATT MTU → 20-byte payload, minus 3). */
        const val DEFAULT_CHUNK_SIZE = 17
    }
}
