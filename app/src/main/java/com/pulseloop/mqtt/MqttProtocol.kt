package com.pulseloop.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Pure-Kotlin implementation of the MQTT 3.1.1 wire protocol subset used by PulseLoop.
 *
 * Supports encoding/decoding of CONNECT, CONNACK, PUBLISH, PUBACK, PINGREQ, PINGRESP
 * and DISCONNECT packets. Contains no Android dependencies so it can be unit-tested
 * directly on the JVM.
 */
internal object MqttProtocol {

    // ------------------------------------------------------------------
    // Packet type constants (MQTT 3.1.1 section 2.2.1)
    // ------------------------------------------------------------------
    const val PACKET_CONNECT = 1
    const val PACKET_CONNACK = 2
    const val PACKET_PUBLISH = 3
    const val PACKET_PUBACK = 4
    const val PACKET_PINGREQ = 12
    const val PACKET_PINGRESP = 13
    const val PACKET_DISCONNECT = 14

    /** CONNACK return code: connection accepted. */
    const val CONNACK_ACCEPTED = 0

    /** Maximum value representable by the MQTT variable-length remaining length field. */
    private const val MAX_REMAINING_LENGTH = 268_435_455 // 0xFF,0xFF,0xFF,0x7F

    /** Maximum length of an MQTT UTF-8 string (2-byte unsigned length prefix). */
    private const val MAX_STRING_LENGTH = 65_535

    // ------------------------------------------------------------------
    // Data classes
    // ------------------------------------------------------------------

    /**
     * A decoded MQTT packet: the fixed-header type and flags plus the raw body
     * (everything after the remaining-length field).
     */
    data class MqttPacket(
        val packetType: Int,
        val flags: Int,
        val body: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MqttPacket) return false
            return packetType == other.packetType &&
                flags == other.flags &&
                body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var result = packetType
            result = 31 * result + flags
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    /** Decoded contents of a CONNACK packet. */
    data class ConnAck(
        val sessionPresent: Boolean,
        val returnCode: Int,
    )

    // ------------------------------------------------------------------
    // Remaining length helpers (MQTT 3.1.1 section 2.2.3)
    // ------------------------------------------------------------------

    /**
     * Encodes [length] using the MQTT variable-length encoding and appends the
     * resulting bytes to [out].
     *
     * @throws IllegalArgumentException if [length] is outside 0..268_435_455.
     */
    fun encodeRemainingLength(length: Int, out: ByteArrayOutputStream) {
        require(length in 0..MAX_REMAINING_LENGTH) {
            "Remaining length out of range: $length (must be 0..$MAX_REMAINING_LENGTH)"
        }
        var value = length
        do {
            var byte = value % 128
            value /= 128
            if (value > 0) {
                byte = byte or 0x80
            }
            out.write(byte)
        } while (value > 0)
    }

    /**
     * Decodes an MQTT variable-length remaining length field from [input],
     * consuming 1..4 bytes.
     *
     * @throws EOFException if the stream ends before the field is complete.
     * @throws IOException if the continuation bit is still set on the 4th byte.
     */
    fun decodeRemainingLength(input: InputStream): Int {
        var multiplier = 1
        var value = 0
        var byte: Int
        do {
            byte = input.read()
            if (byte < 0) {
                throw EOFException("Premature EOF while reading MQTT remaining length")
            }
            value += (byte and 0x7F) * multiplier
            if ((byte and 0x80) != 0 && multiplier >= 128 * 128 * 128) {
                // Continuation bit set on the 4th byte -> malformed.
                throw IOException("Malformed MQTT remaining length")
            }
            multiplier *= 128
        } while (byte and 0x80 != 0)
        return value
    }

    // ------------------------------------------------------------------
    // UTF-8 string helper
    // ------------------------------------------------------------------

    /**
     * Writes [value] as an MQTT UTF-8 string: a 2-byte big-endian length prefix
     * followed by the UTF-8 encoded bytes.
     *
     * @throws IllegalArgumentException if the UTF-8 encoding exceeds 65_535 bytes.
     */
    fun writeMqttString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_LENGTH) {
            throw IllegalArgumentException(
                "MQTT string exceeds 65535 bytes: ${bytes.size}"
            )
        }
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    // ------------------------------------------------------------------
    // Packet encoders
    // ------------------------------------------------------------------

    /**
     * Encodes an MQTT CONNECT packet.
     *
     * @throws IllegalArgumentException if a password is supplied without a username,
     *   or if the clientId UTF-8 encoding exceeds 65_535 bytes.
     */
    fun encodeConnect(
        clientId: String,
        username: String? = null,
        password: String? = null,
        keepAliveSeconds: Int = 60,
        cleanSession: Boolean = true,
    ): ByteArray {
        require(!(password != null && username == null)) {
            "Password provided without a username"
        }
        val clientIdBytes = clientId.toByteArray(Charsets.UTF_8)
        require(clientIdBytes.size <= MAX_STRING_LENGTH) {
            "Client id UTF-8 length exceeds 65535 bytes: ${clientIdBytes.size}"
        }

        val body = ByteArrayOutputStream()

        // Variable header: protocol name "MQTT", level 4.
        writeMqttString(body, "MQTT")
        body.write(0x04) // protocol level

        // Connect flags.
        var connectFlags = 0
        if (cleanSession) connectFlags = connectFlags or 0x02
        if (username != null) connectFlags = connectFlags or 0x80
        if (password != null) connectFlags = connectFlags or 0x40
        body.write(connectFlags)

        // Keep alive: big-endian uint16.
        val keepAlive = keepAliveSeconds and 0xFFFF
        body.write((keepAlive ushr 8) and 0xFF)
        body.write(keepAlive and 0xFF)

        // Payload.
        writeMqttString(body, clientId)
        if (username != null) writeMqttString(body, username)
        if (password != null) writeMqttString(body, password)

        val payload = body.toByteArray()
        val header = ByteArrayOutputStream()
        header.write(0x10) // CONNECT fixed header type
        encodeRemainingLength(payload.size, header)

        return header.toByteArray() + payload
    }

    /**
     * Encodes an MQTT PUBLISH packet.
     *
     * @throws IllegalArgumentException if the topic is empty, the QoS is not 0 or 1,
     *   or (for QoS 1) the packet id is outside 1..65535.
     */
    fun encodePublish(
        topic: String,
        payload: ByteArray,
        qos: Int = 1,
        retain: Boolean = false,
        dup: Boolean = false,
        packetId: Int = 1,
    ): ByteArray {
        require(topic.isNotEmpty()) { "Topic must not be empty" }
        require(qos in 0..1) { "QoS must be 0 or 1, got: $qos" }
        if (qos == 1) {
            require(packetId in 1..65_535) {
                "Packet id must be in 1..65535 for QoS 1, got: $packetId"
            }
        }

        val body = ByteArrayOutputStream()
        writeMqttString(body, topic)
        if (qos == 1) {
            body.write((packetId ushr 8) and 0xFF)
            body.write(packetId and 0xFF)
        }
        body.write(payload)

        val fixedHeader: Int =
            0x30 or
                (if (dup && qos > 0) 0x08 else 0) or
                ((qos and 0x03) shl 1) or
                (if (retain) 0x01 else 0)

        val header = ByteArrayOutputStream()
        header.write(fixedHeader)
        encodeRemainingLength(body.size(), header)

        return header.toByteArray() + body.toByteArray()
    }

    /**
     * Encodes an MQTT PUBACK packet (4 bytes).
     *
     * @throws IllegalArgumentException if [packetId] is outside 1..65535.
     */
    fun encodePubAck(packetId: Int): ByteArray {
        require(packetId in 1..65_535) {
            "Packet id must be in 1..65535, got: $packetId"
        }
        return byteArrayOf(
            0x40,
            0x02,
            ((packetId ushr 8) and 0xFF).toByte(),
            (packetId and 0xFF).toByte(),
        )
    }

    /** Encodes an MQTT PINGREQ packet. */
    fun encodePingReq(): ByteArray = byteArrayOf(0xC0.toByte(), 0x00)

    /** Encodes an MQTT DISCONNECT packet. */
    fun encodeDisconnect(): ByteArray = byteArrayOf(0xE0.toByte(), 0x00)

    // ------------------------------------------------------------------
    // Packet decoders
    // ------------------------------------------------------------------

    /**
     * Reads a complete MQTT packet from [input]: the fixed header byte, the
     * variable-length remaining length, and exactly that many body bytes.
     *
     * @throws EOFException if the stream ends before the packet is complete.
     */
    fun readPacket(input: InputStream): MqttPacket {
        val firstByte = input.read()
        if (firstByte < 0) {
            throw EOFException("Premature EOF while reading MQTT fixed header")
        }
        val packetType = (firstByte ushr 4) and 0x0F
        val flags = firstByte and 0x0F
        val remainingLength = decodeRemainingLength(input)

        val body = ByteArray(remainingLength)
        var offset = 0
        while (offset < remainingLength) {
            val read = input.read(body, offset, remainingLength - offset)
            if (read < 0) {
                throw EOFException(
                    "Premature EOF while reading MQTT packet body " +
                        "(expected $remainingLength bytes, got $offset)"
                )
            }
            offset += read
        }
        return MqttPacket(packetType, flags, body)
    }

    /**
     * Decodes a CONNACK packet.
     *
     * @throws IOException if the packet is not a CONNACK or the body is not 2 bytes.
     */
    fun decodeConnAck(packet: MqttPacket): ConnAck {
        if (packet.packetType != PACKET_CONNACK) {
            throw IOException(
                "Expected CONNACK packet (type $PACKET_CONNACK), got type ${packet.packetType}"
            )
        }
        if (packet.body.size != 2) {
            throw IOException(
                "Malformed CONNACK: body must be 2 bytes, got ${packet.body.size}"
            )
        }
        val sessionPresent = (packet.body[0].toInt() and 0x01) != 0
        val returnCode = packet.body[1].toInt() and 0xFF
        return ConnAck(sessionPresent, returnCode)
    }

    /**
     * Extracts the unsigned 16-bit packet id from a PUBACK packet.
     *
     * @throws IOException if the packet is not a PUBACK or the body is not 2 bytes.
     */
    fun decodePubAckPacketId(packet: MqttPacket): Int {
        if (packet.packetType != PACKET_PUBACK) {
            throw IOException(
                "Expected PUBACK packet (type $PACKET_PUBACK), got type ${packet.packetType}"
            )
        }
        if (packet.body.size != 2) {
            throw IOException(
                "Malformed PUBACK: body must be 2 bytes, got ${packet.body.size}"
            )
        }
        val high = packet.body[0].toInt() and 0xFF
        val low = packet.body[1].toInt() and 0xFF
        return (high shl 8) or low
    }

    /**
     * Returns a concise human-readable description of a CONNACK return code.
     */
    fun connAckDescription(returnCode: Int): String = when (returnCode) {
        0 -> "Accepted"
        1 -> "Unacceptable protocol version"
        2 -> "Identifier rejected"
        3 -> "Server unavailable"
        4 -> "Bad username or password"
        5 -> "Not authorized"
        else -> "Unknown error ($returnCode)"
    }
}
