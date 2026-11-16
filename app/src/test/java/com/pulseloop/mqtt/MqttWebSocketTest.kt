package com.pulseloop.mqtt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MqttWebSocketTest {

    @Test
    fun computeAcceptKey_matchesRfc6455Vector() {
        assertEquals(
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            MqttWebSocket.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ=="),
        )
    }

    @Test
    fun normalizePath_addsLeadingSlashOrDefaultsToMqtt() {
        assertEquals("/mqtt", MqttWebSocket.normalizePath(""))
        assertEquals("/mqtt", MqttWebSocket.normalizePath("   "))
        assertEquals("/mqtt", MqttWebSocket.normalizePath("/mqtt"))
        assertEquals("/ws", MqttWebSocket.normalizePath("ws"))
        assertEquals("/mqtt/v1", MqttWebSocket.normalizePath(" /mqtt/v1 "))
    }

    @Test
    fun upgrade_rejectsNon101AndInvalidAcceptKey() {
        val badStatus = "HTTP/1.1 404 Not Found\r\n\r\n".toByteArray(Charsets.US_ASCII)
        try {
            MqttWebSocket.upgrade(
                host = "mqtt.example.com",
                port = 443,
                useTls = true,
                path = "/mqtt",
                rawInput = ByteArrayInputStream(badStatus),
                rawOutput = ByteArrayOutputStream(),
            )
            fail("Expected MqttException on HTTP 404")
        } catch (e: MqttException) {
            assertTrue(e.message!!.contains("404"))
        }

        val badAccept = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: wrongkey==\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII)
        try {
            MqttWebSocket.upgrade(
                host = "mqtt.example.com",
                port = 443,
                useTls = true,
                path = "/mqtt",
                rawInput = ByteArrayInputStream(badAccept),
                rawOutput = ByteArrayOutputStream(),
            )
            fail("Expected MqttException on invalid Sec-WebSocket-Accept")
        } catch (e: MqttException) {
            assertTrue(e.message!!.contains("Sec-WebSocket-Accept"))
        }
    }

    @Test
    fun loopbackMockWsBroker_handlesHandshakeQos1PublishPingAndLargePayload() = runBlocking {
        val capturedRequestLines = CopyOnWriteArrayList<String>()
        val capturedMqttPayloads = CopyOnWriteArrayList<ByteArray>()
        val receivedPong = CopyOnWriteArrayList<ByteArray>()
        val sawClose = java.util.concurrent.atomic.AtomicBoolean(false)

        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val serverFuture = executor.submit {
            server.use { srv ->
                srv.accept().use { client ->
                    val input = client.getInputStream()
                    val output = client.getOutputStream()

                    // Read HTTP Upgrade request
                    var key = ""
                    while (true) {
                        val line = readAsciiLine(input)
                        if (line.isEmpty()) break
                        capturedRequestLines.add(line)
                        if (line.lowercase().startsWith("sec-websocket-key:")) {
                            key = line.substringAfter(':').trim()
                        }
                    }

                    val accept = MqttWebSocket.computeAcceptKey(key)
                    val response = (
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: $accept\r\n" +
                            "Sec-WebSocket-Protocol: mqtt\r\n" +
                            "\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                    output.write(response)
                    output.flush()

                    // Read MQTT packets wrapped in masked client WebSocket frames
                    while (true) {
                        val (opcode, payload) = readWsFrame(input) ?: break
                        when (opcode) {
                            0x8 -> {
                                sawClose.set(true)
                                break
                            }
                            0xA -> {
                                receivedPong.add(payload)
                            }
                            0x2 -> {
                                capturedMqttPayloads.add(payload)
                                val packetType = (payload[0].toInt() ushr 4) and 0x0F
                                when (packetType) {
                                    MqttProtocol.PACKET_CONNECT -> {
                                        // Send WS Ping first to verify client auto-replies with Pong before CONNACK
                                        writeUnmaskedWsFrame(output, 0x9, "hi".toByteArray())
                                        writeUnmaskedWsFrame(
                                            output,
                                            0x2,
                                            byteArrayOf(0x20, 0x02, 0x00, 0x00), // CONNACK 0
                                        )
                                    }
                                    MqttProtocol.PACKET_PUBLISH -> {
                                        val qos = (payload[0].toInt() ushr 1) and 0x03
                                        if (qos == 1) {
                                            // Extract packetId after remaining length + topic
                                            var idx = 1
                                            while ((payload[idx].toInt() and 0x80) != 0) idx++
                                            idx++
                                            val topicLen = ((payload[idx].toInt() and 0xFF) shl 8) or
                                                (payload[idx + 1].toInt() and 0xFF)
                                            val pidIdx = idx + 2 + topicLen
                                            val hi = payload[pidIdx]
                                            val lo = payload[pidIdx + 1]
                                            writeUnmaskedWsFrame(
                                                output,
                                                0x2,
                                                byteArrayOf(0x40, 0x02, hi, lo), // PUBACK
                                            )
                                        }
                                    }
                                    MqttProtocol.PACKET_PINGREQ -> {
                                        writeUnmaskedWsFrame(
                                            output,
                                            0x2,
                                            byteArrayOf(0xD0.toByte(), 0x00), // PINGRESP
                                        )
                                    }
                                    MqttProtocol.PACKET_DISCONNECT -> {
                                        // Client will follow with WS Close
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val largeJson = """{"payload":"${"x".repeat(400)}"}"""
        val config = MqttEndpointConfig(
            host = "127.0.0.1",
            port = server.localPort,
            useTls = false,
            transport = MqttTransport.WEBSOCKET,
            wsPath = "ws/mqtt",
            username = "u",
            password = "p",
            clientId = "pl_ws_test",
        )
        MqttClient.publishAll(
            config,
            listOf(
                MqttMessage("pulseloop/test/state", largeJson, qos = 1, retain = true),
            ),
        )
        serverFuture.get()
        executor.shutdown()

        assertTrue(capturedRequestLines.first().startsWith("GET /ws/mqtt HTTP/1.1"))
        assertTrue(capturedRequestLines.any { it.equals("Host: 127.0.0.1:${server.localPort}", ignoreCase = true) })
        assertTrue(capturedRequestLines.any { it.equals("Sec-WebSocket-Protocol: mqtt", ignoreCase = true) })
        assertEquals(1, receivedPong.size)
        assertEquals("hi", receivedPong.first().toString(Charsets.UTF_8))
        assertTrue(capturedMqttPayloads.size >= 3) // CONNECT, PUBLISH, DISCONNECT
        assertTrue(sawClose.get())
    }

    private fun readAsciiLine(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) throw EOFException()
            if (b == '\n'.code) {
                val arr = buf.toByteArray()
                val len = if (arr.isNotEmpty() && arr.last() == '\r'.code.toByte()) arr.size - 1 else arr.size
                return String(arr, 0, len, Charsets.US_ASCII)
            }
            buf.write(b)
        }
    }

    private fun readWsFrame(input: InputStream): Pair<Int, ByteArray>? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) throw EOFException()
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        assertTrue("Client WebSocket frames must be masked per RFC 6455", masked)
        var len = b1 and 0x7F
        if (len == 126) {
            len = (input.read() shl 8) or input.read()
        } else if (len == 127) {
            var acc = 0L
            repeat(8) { acc = (acc shl 8) or input.read().toLong() }
            len = acc.toInt()
        }
        val mask = ByteArray(4)
        readFully(input, mask)
        val payload = ByteArray(len)
        readFully(input, payload)
        for (i in 0 until len) {
            payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        return opcode to payload
    }

    private fun readFully(input: InputStream, dest: ByteArray) {
        var off = 0
        while (off < dest.size) {
            val n = input.read(dest, off, dest.size - off)
            if (n == -1) throw EOFException()
            off += n
        }
    }

    private fun writeUnmaskedWsFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        if (payload.size <= 125) {
            output.write(payload.size)
        } else {
            output.write(126)
            output.write((payload.size ushr 8) and 0xFF)
            output.write(payload.size and 0xFF)
        }
        output.write(payload)
        output.flush()
    }
}
