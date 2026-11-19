package com.pulseloop.mqtt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [MqttProtocol], [MqttClient], and [MqttSessionPool].
 * Uses a real in-process TCP broker on 127.0.0.1 for end-to-end coverage.
 */
class MqttProtocolTest {

    private val timeoutMs = 2000

    // ------------------------------------------------------------------
    // 1. Remaining length round-trip boundaries
    // ------------------------------------------------------------------

    @Test
    fun remainingLength_oneByteBoundaries() {
        for (value in intArrayOf(0, 127)) {
            val encoded = encodeRemainingLengthBytes(value)
            assertEquals("value=$value", 1, encoded.size)
            assertEquals(value, MqttProtocol.decodeRemainingLength(ByteArrayInputStream(encoded)))
        }
    }

    @Test
    fun remainingLength_twoByteBoundaries() {
        for (value in intArrayOf(128, 16383)) {
            val encoded = encodeRemainingLengthBytes(value)
            assertEquals("value=$value", 2, encoded.size)
            assertEquals(value, MqttProtocol.decodeRemainingLength(ByteArrayInputStream(encoded)))
        }
    }

    @Test
    fun remainingLength_threeByteBoundaries() {
        for (value in intArrayOf(16384, 2097151)) {
            val encoded = encodeRemainingLengthBytes(value)
            assertEquals("value=$value", 3, encoded.size)
            assertEquals(value, MqttProtocol.decodeRemainingLength(ByteArrayInputStream(encoded)))
        }
    }

    @Test
    fun remainingLength_fourByteBoundaries() {
        for (value in intArrayOf(2097152, 268435455)) {
            val encoded = encodeRemainingLengthBytes(value)
            assertEquals("value=$value", 4, encoded.size)
            assertEquals(value, MqttProtocol.decodeRemainingLength(ByteArrayInputStream(encoded)))
        }
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F),
            encodeRemainingLengthBytes(268435455),
        )
    }

    @Test
    fun remainingLength_overLimit_throws() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x80.toByte())
        try {
            MqttProtocol.decodeRemainingLength(ByteArrayInputStream(bytes))
            fail("Expected IOException for over-limit remaining length")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun remainingLength_prematureEof_throwsEofException() {
        val truncated = byteArrayOf(0x80.toByte(), 0x81.toByte())
        try {
            MqttProtocol.decodeRemainingLength(ByteArrayInputStream(truncated))
            fail("Expected EOFException for truncated remaining length")
        } catch (_: EOFException) {
            // expected
        }
    }

    private fun encodeRemainingLengthBytes(length: Int): ByteArray =
        ByteArrayOutputStream().also { MqttProtocol.encodeRemainingLength(length, it) }.toByteArray()

    // ------------------------------------------------------------------
    // 2. CONNECT encoding
    // ------------------------------------------------------------------

    @Test
    fun connectEncoding_withUsernameAndPassword() {
        val packet = MqttProtocol.encodeConnect(
            clientId = "pulse-loop-client",
            username = "alice",
            password = "secret123",
            keepAliveSeconds = 60,
            cleanSession = true,
        )

        assertEquals(0x10, packet[0].toInt() and 0xFF)
        var offset = 1 + decodeVarintWidth(packet, 1)

        offset = assertUtf8String(packet, offset, "MQTT")
        assertEquals(0x04, packet[offset].toInt())
        val flags = packet[offset + 1].toInt() and 0xFF
        assertEquals(0x02, flags and 0x02) // clean session
        assertEquals(0x80, flags and 0x80) // username flag
        assertEquals(0x40, flags and 0x40) // password flag
        val keepAlive = ((packet[offset + 2].toInt() and 0xFF) shl 8) or (packet[offset + 3].toInt() and 0xFF)
        assertEquals(60, keepAlive)

        var payloadOffset = offset + 4
        payloadOffset = assertUtf8String(packet, payloadOffset, "pulse-loop-client")
        payloadOffset = assertUtf8String(packet, payloadOffset, "alice")
        payloadOffset = assertUtf8String(packet, payloadOffset, "secret123")
        assertEquals(packet.size, payloadOffset)
    }

    @Test
    fun connectEncoding_usernameNullPasswordNonNull_throws() {
        try {
            MqttProtocol.encodeConnect(
                clientId = "client",
                username = null,
                password = "orphan",
                keepAliveSeconds = 30,
                cleanSession = true,
            )
            fail("Expected IllegalArgumentException when password set without username")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun decodeVarintWidth(packet: ByteArray, start: Int): Int {
        var i = start
        while (true) {
            val byte = packet[i].toInt() and 0xFF
            i++
            if (byte and 0x80 == 0) break
        }
        return i - start
    }

    private fun assertUtf8String(packet: ByteArray, offset: Int, expected: String): Int {
        val len = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        assertEquals(expected, String(packet, offset + 2, len, Charsets.UTF_8))
        return offset + 2 + len
    }

    // ------------------------------------------------------------------
    // 3. PUBLISH / PUBACK
    // ------------------------------------------------------------------

    @Test
    fun publishQos0_noPacketId_retainFlag() {
        val packet = MqttProtocol.encodePublish(
            topic = "sensors/heart-rate",
            payload = "72".toByteArray(Charsets.UTF_8),
            qos = 0,
            retain = true,
            dup = false,
            packetId = 1,
        )
        val fixedHeader = packet[0].toInt() and 0xFF
        assertEquals(0x30, fixedHeader and 0xF0) // PUBLISH type
        assertEquals(0x01, fixedHeader and 0x01) // retain = true
        assertEquals(0x00, fixedHeader and 0x08) // dup = false (QoS 0)
        assertEquals(0x00, fixedHeader and 0x06) // QoS 0

        var offset = 1 + decodeVarintWidth(packet, 1)
        offset = assertUtf8String(packet, offset, "sensors/heart-rate")
        assertEquals("72", String(packet, offset, packet.size - offset, Charsets.UTF_8))
    }

    @Test
    fun publishQos1_hasPacketId_dupFlag() {
        val packet = MqttProtocol.encodePublish(
            topic = "sensors/spo2",
            payload = "98".toByteArray(Charsets.UTF_8),
            qos = 1,
            retain = false,
            dup = true,
            packetId = 0x1234,
        )
        val fixedHeader = packet[0].toInt() and 0xFF
        assertEquals(0x08, fixedHeader and 0x08) // dup = true
        assertEquals(0x02, fixedHeader and 0x06) // QoS 1
        assertEquals(0x00, fixedHeader and 0x01) // retain = false

        var offset = 1 + decodeVarintWidth(packet, 1)
        offset = assertUtf8String(packet, offset, "sensors/spo2")
        val packetId = ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
        assertEquals(0x1234, packetId)
        assertEquals("98", String(packet, offset + 2, packet.size - offset - 2, Charsets.UTF_8))
    }

    @Test
    fun pubAck_roundTrip() {
        val encoded = MqttProtocol.encodePubAck(0xBEEF)
        assertEquals(4, encoded.size)
        assertEquals(0x40, encoded[0].toInt() and 0xFF)
        assertEquals(2, encoded[1].toInt())
        val packet = MqttProtocol.readPacket(ByteArrayInputStream(encoded))
        assertEquals(MqttProtocol.PACKET_PUBACK, packet.packetType)
        assertEquals(0xBEEF, MqttProtocol.decodePubAckPacketId(packet))
    }

    // ------------------------------------------------------------------
    // 4. CONNACK decoding
    // ------------------------------------------------------------------

    @Test
    fun connAckDecoding_allReturnCodes() {
        for (code in 0..5) {
            val raw = byteArrayOf(
                0x20,
                0x02,
                if (code == 0) 0x01 else 0x00,
                code.toByte(),
            )
            val packet = MqttProtocol.readPacket(ByteArrayInputStream(raw))
            val connAck = MqttProtocol.decodeConnAck(packet)
            assertEquals(code == 0, connAck.sessionPresent)
            assertEquals(code, connAck.returnCode)
            assertTrue(MqttProtocol.connAckDescription(code).isNotBlank())
        }
    }

    // ------------------------------------------------------------------
    // 5. End-to-end loopback broker tests
    // ------------------------------------------------------------------

    private class BrokerMessage(
        val topic: String,
        val payload: ByteArray,
        val retain: Boolean,
        val packetId: Int?,
    )

    private class MockBroker(private val serverSocket: ServerSocket) {
        val acceptCount = AtomicInteger(0)
        val connectUsernames = CopyOnWriteArrayList<String?>()
        val messages = CopyOnWriteArrayList<BrokerMessage>()
        val sawDisconnect = CountDownLatch(1)
        @Volatile var connAckReturnCode: Int = 0
        @Volatile var closeSocketAfterFirstPublish = false

        fun serve() {
            val executor = Executors.newCachedThreadPool()
            try {
                while (!serverSocket.isClosed) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    acceptCount.incrementAndGet()
                    executor.submit { handle(socket) }
                }
            } finally {
                executor.shutdownNow()
            }
        }

        private fun handle(socket: Socket) {
            socket.soTimeout = 3000
            try {
                socket.use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val output = s.getOutputStream()
                    if (!readConnectAndReply(input, output)) return
                    while (true) {
                        val first = input.read()
                        if (first == -1) return
                        val remaining = MqttProtocol.decodeRemainingLength(input)
                        val body = ByteArray(remaining)
                        input.readFully(body)
                        when ((first ushr 4) and 0x0F) {
                            MqttProtocol.PACKET_PUBLISH -> handlePublish(first, body, output)
                            MqttProtocol.PACKET_PINGREQ -> output.write(byteArrayOf(0xD0.toByte(), 0x00))
                            MqttProtocol.PACKET_DISCONNECT -> {
                                sawDisconnect.countDown()
                                return
                            }
                        }
                        output.flush()
                    }
                }
            } catch (_: Exception) {
                // Socket closed cleanly or simulated disconnect.
            }
        }

        private fun readConnectAndReply(input: DataInputStream, output: OutputStream): Boolean {
            val first = input.read()
            if (first == -1) return false
            val remaining = MqttProtocol.decodeRemainingLength(input)
            val body = ByteArray(remaining)
            input.readFully(body)

            // Variable header: protocolName(2+len) + level(1) + flags(1) + keepAlive(2)
            val protoLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
            var offset = 2 + protoLen + 1
            val connectFlags = body[offset].toInt() and 0xFF
            offset += 1 + 2 // skip flags + keepAlive

            val clientIdLen = ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
            offset += 2 + clientIdLen

            var username: String? = null
            if (connectFlags and 0x80 != 0) {
                val uLen = ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
                username = String(body, offset + 2, uLen, Charsets.UTF_8)
                offset += 2 + uLen
            }
            connectUsernames.add(username)

            output.write(byteArrayOf(0x20, 0x02, 0x00, connAckReturnCode.toByte()))
            output.flush()
            return connAckReturnCode == 0
        }

        private fun handlePublish(firstByte: Int, body: ByteArray, output: OutputStream) {
            var offset = 0
            val topicLen = ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
            offset += 2
            val topic = String(body, offset, topicLen, Charsets.UTF_8)
            offset += topicLen
            val qos = (firstByte and 0x06) ushr 1
            val retain = (firstByte and 0x01) != 0
            var packetId: Int? = null
            if (qos >= 1) {
                packetId = ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
                offset += 2
            }
            val payload = body.copyOfRange(offset, body.size)
            messages.add(BrokerMessage(topic, payload, retain, packetId))
            if (qos == 1 && packetId != null) {
                output.write(MqttProtocol.encodePubAck(packetId))
                output.flush()
            }
            if (closeSocketAfterFirstPublish && messages.size == 1) {
                throw IOException("simulated idle disconnect after first publish")
            }
        }
    }

    private fun startBroker(configure: (MockBroker) -> Unit = {}): Triple<ServerSocket, MockBroker, Thread> {
        val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val broker = MockBroker(serverSocket).apply(configure)
        val thread = Thread { broker.serve() }.apply {
            isDaemon = true
            start()
        }
        return Triple(serverSocket, broker, thread)
    }

    private fun stopBroker(serverSocket: ServerSocket, thread: Thread) {
        runCatching { serverSocket.close() }
        thread.join(1000)
    }

    private fun configFor(port: Int, clientId: String, username: String = "", password: String = "") =
        MqttEndpointConfig(
            host = "127.0.0.1",
            port = port,
            useTls = false,
            username = username,
            password = password,
            clientId = clientId,
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs,
        )

    @Test
    fun publishAll_sendsConnectPublishesAndDisconnect() = runBlocking {
        val (serverSocket, broker, thread) = startBroker()
        try {
            MqttClient.publishAll(
                configFor(serverSocket.localPort, "e2e-publisher", "user", "pass"),
                listOf(
                    MqttMessage("pulse/hr", "72", qos = 1, retain = true),
                    MqttMessage("pulse/spo2", "98", qos = 1, retain = true),
                    MqttMessage("pulse/steps", "10453", qos = 1, retain = false),
                ),
            )

            assertTrue(broker.sawDisconnect.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("user"), broker.connectUsernames.toList())
            assertEquals(3, broker.messages.size)
            assertEquals("pulse/hr", broker.messages[0].topic)
            assertEquals("72", String(broker.messages[0].payload, Charsets.UTF_8))
            assertTrue(broker.messages[0].retain)
            assertEquals("pulse/spo2", broker.messages[1].topic)
            assertEquals("98", String(broker.messages[1].payload, Charsets.UTF_8))
            assertEquals("pulse/steps", broker.messages[2].topic)
            assertEquals("10453", String(broker.messages[2].payload, Charsets.UTF_8))
            assertTrue(broker.messages.all { it.packetId != null })
        } finally {
            stopBroker(serverSocket, thread)
        }
    }

    @Test
    fun publishAll_throwsMqttException_whenConnAckRejects() = runBlocking {
        val (serverSocket, broker, thread) = startBroker { it.connAckReturnCode = 4 }
        try {
            try {
                MqttClient.publishAll(
                    configFor(serverSocket.localPort, "bad-creds", "wrong", "creds"),
                    listOf(MqttMessage("pulse/hr", "72", qos = 1, retain = false)),
                )
                fail("Expected MqttException for CONNACK return code 4")
            } catch (_: MqttException) {
                // expected
            }
            assertEquals(0, broker.messages.size)
        } finally {
            stopBroker(serverSocket, thread)
        }
    }

    @Test
    fun testConnection_pingReqPingRespDisconnect() = runBlocking {
        val (serverSocket, broker, thread) = startBroker()
        try {
            MqttClient.testConnection(configFor(serverSocket.localPort, "ping-test"))
            assertTrue(broker.sawDisconnect.await(2, TimeUnit.SECONDS))
            assertEquals(1, broker.acceptCount.get())
        } finally {
            stopBroker(serverSocket, thread)
        }
    }

    @Test
    fun sessionPool_reusesConnectionWithinIdleTtl() = runBlocking {
        val (serverSocket, broker, thread) = startBroker()
        try {
            var nowMs = 1_000L
            val pool = MqttSessionPool(idleTtlMs = 10_000L, clockMs = { nowMs })
            val cfg = configFor(serverSocket.localPort, "pool-test", "user", "pass")
            try {
                pool.publish(cfg, listOf(MqttMessage("pulse/hr", "72", qos = 1, retain = false)))
                nowMs += 4_000L
                pool.publish(cfg, listOf(MqttMessage("pulse/hr", "75", qos = 1, retain = false)))
            } finally {
                pool.close()
            }
            assertEquals(1, broker.acceptCount.get())
            assertEquals(2, broker.messages.size)
            assertEquals("72", String(broker.messages[0].payload, Charsets.UTF_8))
            assertEquals("75", String(broker.messages[1].payload, Charsets.UTF_8))
        } finally {
            stopBroker(serverSocket, thread)
        }
    }

    @Test
    fun sessionPool_reconnectsWhenBrokerClosesSocket() = runBlocking {
        val (serverSocket, broker, thread) = startBroker { it.closeSocketAfterFirstPublish = true }
        try {
            val pool = MqttSessionPool(idleTtlMs = 10_000L)
            val cfg = configFor(serverSocket.localPort, "reconnect-test", "user", "pass")
            try {
                pool.publish(cfg, listOf(MqttMessage("pulse/hr", "72", qos = 1, retain = false)))
                pool.publish(cfg, listOf(MqttMessage("pulse/hr", "75", qos = 1, retain = false)))
            } finally {
                pool.close()
            }
            assertEquals(2, broker.acceptCount.get())
            assertEquals(2, broker.messages.size)
        } finally {
            stopBroker(serverSocket, thread)
        }
    }

    @Test
    fun sessionPool_reconnectsAfterIdleTtlElapses() = runBlocking {
        val (serverSocket, broker, thread) = startBroker()
        try {
            var nowMs = 1_000L
            val pool = MqttSessionPool(idleTtlMs = 5_000L, clockMs = { nowMs })
            val cfg = configFor(serverSocket.localPort, "ttl-test", "user", "pass")
            try {
                pool.publish(cfg, listOf(MqttMessage("pulse/hr", "72", qos = 1, retain = false)))
                nowMs += 6_000L
                pool.publish(cfg, listOf(MqttMessage("pulse/hr", "75", qos = 1, retain = false)))
            } finally {
                pool.close()
            }
            assertEquals(2, broker.acceptCount.get())
            assertEquals(2, broker.messages.size)
        } finally {
            stopBroker(serverSocket, thread)
        }
    }
}
