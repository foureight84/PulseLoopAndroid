package com.pulseloop.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure-Kotlin RFC 6455 WebSocket client framing (`ws://` / `wss://`) for MQTT 3.1.1.
 *
 * Performs the HTTP/1.1 `Upgrade: websocket` handshake (`Sec-WebSocket-Protocol: mqtt`)
 * on an already-connected [InputStream]/[OutputStream] pair (plain or TLS [SSLSocket])
 * and exposes masked binary frame streams compatible with [MqttProtocol].
 */
internal object MqttWebSocket {

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_HEADER_LINES = 128
    private const val MAX_LINE_BYTES = 8192
    private const val MAX_FRAME_PAYLOAD_BYTES = 16 * 1024 * 1024 // 16 MiB safety cap

    private const val OPCODE_CONTINUATION = 0x0
    private const val OPCODE_TEXT = 0x1
    private const val OPCODE_BINARY = 0x2
    private const val OPCODE_CLOSE = 0x8
    private const val OPCODE_PING = 0x9
    private const val OPCODE_PONG = 0xA

    /**
     * Normalizes a WebSocket endpoint path to always start with `/` (defaulting to `/mqtt`).
     */
    fun normalizePath(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return "/mqtt"
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    /**
     * Performs the RFC 6455 HTTP/1.1 Upgrade handshake and returns wrapped `(input, output)` streams.
     */
    fun upgrade(
        host: String,
        port: Int,
        useTls: Boolean,
        path: String,
        rawInput: InputStream,
        rawOutput: OutputStream,
        random: SecureRandom = SecureRandom(),
    ): Pair<InputStream, OutputStream> {
        val normalizedPath = normalizePath(path)
        val hostHeader = if ((useTls && port == 443) || (!useTls && port == 80)) {
            host
        } else {
            "$host:$port"
        }

        val nonce = ByteArray(16).also { random.nextBytes(it) }
        val wsKey = base64Encode(nonce)
        val expectedAccept = computeAcceptKey(wsKey)

        val request = buildString {
            append("GET ").append(normalizedPath).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("Sec-WebSocket-Protocol: mqtt\r\n")
            append("\r\n")
        }
        rawOutput.write(request.toByteArray(Charsets.US_ASCII))
        rawOutput.flush()

        val statusLine = readHttpLine(rawInput)
            ?: throw MqttException("WebSocket handshake failed: unexpected EOF reading HTTP status")
        if (!statusLine.startsWith("HTTP/1.1 101") && !statusLine.startsWith("HTTP/1.0 101")) {
            throw MqttException("WebSocket handshake rejected: $statusLine")
        }

        val headers = LinkedHashMap<String, String>()
        var lineCount = 0
        while (true) {
            val line = readHttpLine(rawInput)
                ?: throw MqttException("WebSocket handshake failed: unexpected EOF reading headers")
            if (line.isEmpty()) break
            if (++lineCount > MAX_HEADER_LINES) {
                throw MqttException("WebSocket handshake failed: too many HTTP response headers")
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                headers[name] = value
            }
        }

        val upgradeHeader = headers["upgrade"]?.lowercase()
        if (upgradeHeader != "websocket") {
            throw MqttException("WebSocket handshake failed: missing/invalid Upgrade header ($upgradeHeader)")
        }

        val connectionTokens = headers["connection"]
            ?.lowercase()
            ?.split(',')
            ?.map { it.trim() }
            .orEmpty()
        if ("upgrade" !in connectionTokens) {
            throw MqttException("WebSocket handshake failed: missing Connection: Upgrade header")
        }

        val acceptHeader = headers["sec-websocket-accept"]
        if (acceptHeader != expectedAccept) {
            throw MqttException("WebSocket handshake failed: invalid Sec-WebSocket-Accept")
        }

        val subprotocol = headers["sec-websocket-protocol"]?.trim()?.lowercase()
        if (subprotocol != null && subprotocol != "mqtt") {
            throw MqttException("WebSocket handshake failed: unsupported subprotocol '$subprotocol'")
        }

        val writeLock = Any()
        val wsInput = WebSocketInputStream(rawInput, rawOutput, random, writeLock)
        val wsOutput = WebSocketOutputStream(rawOutput, random, writeLock)
        return wsInput to wsOutput
    }

    internal fun computeAcceptKey(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((clientKey + WS_GUID).toByteArray(Charsets.US_ASCII))
        return base64Encode(digest)
    }

    private fun readHttpLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
            }
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                val len = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, len, Charsets.US_ASCII)
            }
            if (buf.size() >= MAX_LINE_BYTES) {
                throw MqttException("WebSocket HTTP header line exceeded $MAX_LINE_BYTES bytes")
            }
            buf.write(b)
        }
    }

    private fun writeClientFrame(
        rawOutput: OutputStream,
        opcode: Int,
        payload: ByteArray,
        offset: Int,
        length: Int,
        random: SecureRandom,
        writeLock: Any,
    ) {
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val header = ByteArrayOutputStream(14)
        header.write(0x80 or (opcode and 0x0F)) // FIN = 1
        when {
            length <= 125 -> header.write(0x80 or length)
            length <= 0xFFFF -> {
                header.write(0x80 or 126)
                header.write((length ushr 8) and 0xFF)
                header.write(length and 0xFF)
            }
            else -> {
                header.write(0x80 or 127)
                val len64 = length.toLong()
                for (shift in 56 downTo 0 step 8) {
                    header.write(((len64 ushr shift) and 0xFFL).toInt())
                }
            }
        }
        header.write(mask)

        val maskedPayload = ByteArray(length)
        for (i in 0 until length) {
            maskedPayload[i] = (payload[offset + i].toInt() xor mask[i and 3].toInt()).toByte()
        }

        synchronized(writeLock) {
            rawOutput.write(header.toByteArray())
            if (length > 0) {
                rawOutput.write(maskedPayload)
            }
            rawOutput.flush()
        }
    }

    private class WebSocketInputStream(
        private val rawInput: InputStream,
        private val rawOutput: OutputStream,
        private val random: SecureRandom,
        private val writeLock: Any,
    ) : InputStream() {

        private var currentFrame: ByteArray = ByteArray(0)
        private var framePos: Int = 0
        private var closedByPeer: Boolean = false

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (framePos >= currentFrame.size) {
                if (closedByPeer) return -1
                if (!readNextDataFrame()) return -1
            }
            val available = currentFrame.size - framePos
            val toCopy = minOf(len, available)
            System.arraycopy(currentFrame, framePos, b, off, toCopy)
            framePos += toCopy
            return toCopy
        }

        private fun readNextDataFrame(): Boolean {
            while (true) {
                val b0 = rawInput.read()
                if (b0 == -1) {
                    closedByPeer = true
                    return false
                }
                val b1 = rawInput.read()
                if (b1 == -1) throw EOFException("Unexpected EOF in WebSocket frame header")

                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                val len7 = b1 and 0x7F

                val payloadLenLong: Long = when (len7) {
                    126 -> {
                        val hi = readByteOrEof()
                        val lo = readByteOrEof()
                        ((hi shl 8) or lo).toLong()
                    }
                    127 -> {
                        var acc = 0L
                        repeat(8) {
                            acc = (acc shl 8) or readByteOrEof().toLong()
                        }
                        acc
                    }
                    else -> len7.toLong()
                }
                if (payloadLenLong < 0 || payloadLenLong > MAX_FRAME_PAYLOAD_BYTES) {
                    throw MqttException("WebSocket frame too large: $payloadLenLong bytes")
                }
                val payloadLen = payloadLenLong.toInt()

                val mask = if (masked) ByteArray(4).also { readFully(it) } else null
                val payload = ByteArray(payloadLen)
                if (payloadLen > 0) {
                    readFully(payload)
                    if (mask != null) {
                        for (i in 0 until payloadLen) {
                            payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                        }
                    }
                }

                when (opcode) {
                    OPCODE_BINARY, OPCODE_CONTINUATION -> {
                        if (payloadLen > 0) {
                            currentFrame = payload
                            framePos = 0
                            return true
                        }
                        // Empty binary/continuation frame: read next frame.
                    }
                    OPCODE_PING -> {
                        // RFC 6455 §5.5.3: reply with masked Pong containing identical application data.
                        runCatching {
                            writeClientFrame(
                                rawOutput = rawOutput,
                                opcode = OPCODE_PONG,
                                payload = payload,
                                offset = 0,
                                length = payload.size.coerceAtMost(125),
                                random = random,
                                writeLock = writeLock,
                            )
                        }
                    }
                    OPCODE_PONG -> {
                        // Unsolicited or keepalive Pong; ignore.
                    }
                    OPCODE_CLOSE -> {
                        closedByPeer = true
                        runCatching {
                            writeClientFrame(
                                rawOutput = rawOutput,
                                opcode = OPCODE_CLOSE,
                                payload = ByteArray(0),
                                offset = 0,
                                length = 0,
                                random = random,
                                writeLock = writeLock,
                            )
                        }
                        return false
                    }
                    OPCODE_TEXT -> throw MqttException("Unexpected WebSocket text frame (expected binary)")
                    else -> throw MqttException("Unsupported WebSocket opcode: 0x${opcode.toString(16)}")
                }
            }
        }

        private fun readByteOrEof(): Int {
            val b = rawInput.read()
            if (b == -1) throw EOFException("Unexpected EOF reading WebSocket frame")
            return b
        }

        private fun readFully(dest: ByteArray) {
            var off = 0
            while (off < dest.size) {
                val n = rawInput.read(dest, off, dest.size - off)
                if (n == -1) throw EOFException("Unexpected EOF reading WebSocket frame payload")
                off += n
            }
        }
    }

    private class WebSocketOutputStream(
        private val rawOutput: OutputStream,
        private val random: SecureRandom,
        private val writeLock: Any,
    ) : OutputStream() {

        private val buffer = ByteArrayOutputStream(256)
        private var closed = false

        override fun write(b: Int) {
            if (closed) throw IOException("WebSocket output stream is closed")
            buffer.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw IOException("WebSocket output stream is closed")
            buffer.write(b, off, len)
        }

        override fun flush() {
            if (buffer.size() > 0) {
                val bytes = buffer.toByteArray()
                buffer.reset()
                writeClientFrame(
                    rawOutput = rawOutput,
                    opcode = OPCODE_BINARY,
                    payload = bytes,
                    offset = 0,
                    length = bytes.size,
                    random = random,
                    writeLock = writeLock,
                )
            } else {
                synchronized(writeLock) { rawOutput.flush() }
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { flush() }
            runCatching {
                writeClientFrame(
                    rawOutput = rawOutput,
                    opcode = OPCODE_CLOSE,
                    payload = ByteArray(0),
                    offset = 0,
                    length = 0,
                    random = random,
                    writeLock = writeLock,
                )
            }
        }
    }

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    internal fun base64Encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val hasB1 = i + 1 < data.size
            val hasB2 = i + 2 < data.size
            val b1 = if (hasB1) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (hasB2) data[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2

            out.append(BASE64_ALPHABET[(triple ushr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(triple ushr 12) and 0x3F])
            out.append(if (hasB1) BASE64_ALPHABET[(triple ushr 6) and 0x3F] else '=')
            out.append(if (hasB2) BASE64_ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return out.toString()
    }
}
