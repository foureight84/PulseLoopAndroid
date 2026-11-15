package com.pulseloop.mqtt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A single outbound MQTT message.
 */
data class MqttMessage(
    val topic: String,
    val payload: ByteArray,
    val qos: Int = 1,
    val retain: Boolean = false,
) {
    constructor(
        topic: String,
        payload: String,
        qos: Int = 1,
        retain: Boolean = false,
    ) : this(topic, payload.toByteArray(Charsets.UTF_8), qos, retain)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MqttMessage) return false
        return topic == other.topic &&
            payload.contentEquals(other.payload) &&
            qos == other.qos &&
            retain == other.retain
    }

    override fun hashCode(): Int {
        var result = topic.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + qos
        result = 31 * result + retain.hashCode()
        return result
    }
}

/**
 * Connection parameters for an MQTT broker endpoint.
 */
data class MqttEndpointConfig(
    val host: String,
    val port: Int,
    val useTls: Boolean = false,
    val username: String = "",
    val password: String = "",
    val clientId: String,
    val keepAliveSeconds: Int = 60,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 10_000,
)

/**
 * Raised for any MQTT protocol or transport failure.
 */
class MqttException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A live MQTT connection over a (possibly TLS-wrapped) socket.
 * Not thread-safe on its own; callers must serialize access.
 */
internal class MqttConnection(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    private var nextPacketId: Int = 1,
) : Closeable {

    companion object {
        /**
         * Opens a new connection to [config], performing the MQTT CONNECT/CONNACK handshake.
         * On any failure the underlying socket is closed before the exception propagates.
         */
        fun open(config: MqttEndpointConfig): MqttConnection {
            val host = config.host.trim()
            if (host.isBlank()) {
                throw MqttException("MQTT host must not be blank")
            }
            if (config.port !in 1..65535) {
                throw MqttException("MQTT port must be in 1..65535, was: ${config.port}")
            }

            var socket: Socket? = null
            try {
                val plain = Socket()
                socket = plain
                plain.connect(InetSocketAddress(host, config.port), config.connectTimeoutMs)
                plain.soTimeout = config.readTimeoutMs

                var liveSocket: Socket = plain
                if (config.useTls) {
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val ssl = factory.createSocket(plain, host, config.port, true) as SSLSocket
                    ssl.sslParameters = ssl.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    ssl.startHandshake()
                    liveSocket = ssl
                }

                val input = BufferedInputStream(liveSocket.getInputStream())
                val output = BufferedOutputStream(liveSocket.getOutputStream())
                val connection = MqttConnection(liveSocket, input, output)

                output.write(
                    MqttProtocol.encodeConnect(
                        clientId = config.clientId,
                        username = config.username.ifEmpty { null },
                        password = if (config.username.isNotEmpty() && config.password.isNotEmpty()) {
                            config.password
                        } else {
                            null
                        },
                        keepAliveSeconds = config.keepAliveSeconds,
                        cleanSession = true,
                    )
                )
                output.flush()

                val packet = connection.readPacket()
                val ack = MqttProtocol.decodeConnAck(packet)
                if (ack.returnCode != MqttProtocol.CONNACK_ACCEPTED) {
                    throw MqttException(
                        "MQTT connect rejected: ${MqttProtocol.connAckDescription(ack.returnCode)}"
                    )
                }
                return connection
            } catch (e: IOException) {
                runCatching { socket?.close() }
                if (e is MqttException) throw e
                throw MqttException("MQTT connect failed: ${e.message}", e)
            } catch (e: RuntimeException) {
                runCatching { socket?.close() }
                throw e
            }
        }
    }

    val isClosed: Boolean
        get() = socket.isClosed || !socket.isConnected

    /**
     * Publishes all messages. QoS 0 messages are fire-and-forget; QoS 1 messages
     * wait for a matching PUBACK before proceeding.
     */
    fun publishAll(messages: List<MqttMessage>) {
        for (msg in messages) {
            when (msg.qos) {
                0 -> output.write(
                    MqttProtocol.encodePublish(
                        msg.topic,
                        msg.payload,
                        qos = 0,
                        retain = msg.retain,
                    )
                )
                1 -> {
                    val packetId = allocatePacketId()
                    output.write(
                        MqttProtocol.encodePublish(
                            msg.topic,
                            msg.payload,
                            qos = 1,
                            retain = msg.retain,
                            packetId = packetId,
                        )
                    )
                    output.flush()
                    awaitPubAck(packetId)
                }
                else -> throw MqttException("Unsupported MQTT QoS level: ${msg.qos}")
            }
        }
        output.flush()
    }

    /**
     * Sends a PINGREQ and waits for the PINGRESP.
     */
    fun ping() {
        output.write(MqttProtocol.encodePingReq())
        output.flush()
        val packet = readPacket()
        if (packet.packetType != MqttProtocol.PACKET_PINGRESP) {
            throw MqttException(
                "Unexpected MQTT packet while awaiting PINGRESP: type=${packet.packetType}"
            )
        }
    }

    /**
     * Best-effort graceful disconnect followed by close. Never throws.
     */
    fun disconnectAndClose() {
        runCatching {
            output.write(MqttProtocol.encodeDisconnect())
            output.flush()
        }
        runCatching { close() }
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { socket.close() }
    }

    private fun readPacket(): MqttProtocol.MqttPacket =
        MqttProtocol.readPacket(input)

    private fun allocatePacketId(): Int {
        val id = nextPacketId
        nextPacketId = if (nextPacketId >= 65535) 1 else nextPacketId + 1
        return id
    }

    private fun awaitPubAck(packetId: Int) {
        while (true) {
            val packet = readPacket()
            when (packet.packetType) {
                MqttProtocol.PACKET_PUBACK -> {
                    val ackId = MqttProtocol.decodePubAckPacketId(packet)
                    if (ackId == packetId) return
                    throw MqttException(
                        "MQTT PUBACK packetId mismatch: expected $packetId, got $ackId"
                    )
                }
                MqttProtocol.PACKET_PINGRESP -> continue
                else -> throw MqttException(
                    "Unexpected MQTT packet while awaiting PUBACK: type=${packet.packetType}"
                )
            }
        }
    }
}

/**
 * One-shot MQTT publishing API.
 */
object MqttClient {

    /**
     * Publishes [messages] to [config] on a fresh connection, then disconnects cleanly.
     */
    suspend fun publishAll(config: MqttEndpointConfig, messages: List<MqttMessage>) {
        withContext(Dispatchers.IO) {
            if (messages.isEmpty()) return@withContext
            var connection: MqttConnection? = null
            try {
                connection = MqttConnection.open(config)
                connection.publishAll(messages)
            } catch (e: MqttException) {
                throw e
            } catch (e: IOException) {
                throw MqttException("MQTT publish failed: ${e.message}", e)
            } finally {
                connection?.disconnectAndClose()
            }
        }
    }

    /**
     * Verifies the broker is reachable by connecting and performing a PINGREQ/PINGRESP.
     */
    suspend fun testConnection(config: MqttEndpointConfig) {
        withContext(Dispatchers.IO) {
            var connection: MqttConnection? = null
            try {
                connection = MqttConnection.open(config)
                connection.ping()
                connection.disconnectAndClose()
                connection = null
            } catch (e: MqttException) {
                throw e
            } catch (e: IOException) {
                throw MqttException("MQTT connection test failed: ${e.message}", e)
            } finally {
                connection?.disconnectAndClose()
            }
        }
    }
}

/**
 * Keeps at most one idle MQTT connection alive for [idleTtlMs] to amortize
 * connect/handshake cost across publishes targeting the same endpoint.
 *
 * Mutual exclusion is provided by a [ReentrantLock] acquired on the IO
 * dispatcher; the critical section performs only blocking socket I/O and
 * contains no suspension points, so holding the lock across it is safe.
 */
class MqttSessionPool(
    private val idleTtlMs: Long = 30_000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = ReentrantLock()
    private var connection: MqttConnection? = null
    private var activeConfig: MqttEndpointConfig? = null
    private var lastUsedMs: Long = 0L

    /**
     * Publishes [messages], reusing the cached connection when it is still open,
     * configured for the same endpoint, and within the idle TTL. A reused
     * connection that fails with a plain IOException (stale socket) triggers
     * exactly one reconnect-and-retry. On any failure the cached connection
     * is discarded so the next call starts fresh.
     */
    suspend fun publish(config: MqttEndpointConfig, messages: List<MqttMessage>) {
        if (messages.isEmpty()) return
        withContext(Dispatchers.IO) {
            lock.lock()
            try {
                try {
                    var reused = false
                    val existing = connection
                    val conn: MqttConnection
                    if (existing != null &&
                        !existing.isClosed &&
                        activeConfig == config &&
                        (clockMs() - lastUsedMs) <= idleTtlMs
                    ) {
                        conn = existing
                        reused = true
                    } else {
                        conn = openFreshLocked(config)
                    }

                    try {
                        conn.publishAll(messages)
                    } catch (e: IOException) {
                        if (reused && e !is MqttException) {
                            // Stale reused socket: reconnect once and retry.
                            val fresh = openFreshLocked(config)
                            fresh.publishAll(messages)
                        } else {
                            throw e
                        }
                    }

                    lastUsedMs = clockMs()
                } catch (e: MqttException) {
                    closeAndClearLocked()
                    throw e
                } catch (e: IOException) {
                    closeAndClearLocked()
                    throw MqttException("MQTT publish failed: ${e.message}", e)
                }
            } finally {
                lock.unlock()
            }
        }
    }

    /**
     * Closes any cached connection and resets pool state.
     */
    suspend fun close() {
        withContext(Dispatchers.IO) {
            lock.lock()
            try {
                closeAndClearLocked()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun openFreshLocked(config: MqttEndpointConfig): MqttConnection {
        closeAndClearLocked()
        val fresh = MqttConnection.open(config)
        connection = fresh
        activeConfig = config
        return fresh
    }

    private fun closeAndClearLocked() {
        connection?.disconnectAndClose()
        connection = null
        activeConfig = null
        lastUsedMs = 0L
    }

    companion object {
        val shared: MqttSessionPool = MqttSessionPool()
    }
}
