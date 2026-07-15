package com.pulseloop.ring

/**
 * Ported from [ColmiDriver] in ColmiDriver.swift.
 * Colmi R02 driver: 16-byte checksum framing, two notify channels, big-data reassembly.
 */
class ColmiDriver(private val writer: RingCommandWriter) : WearableDriver {
    private val decoder = ColmiDecoder
    private val engine: ColmiSyncEngine = ColmiSyncEngine(writer, decoder)

    // Big-data reassembly state: one in-flight transfer, QRing LargeDataParser semantics.
    // While a transfer is incomplete EVERY notify chunk is appended — including one that
    // happens to start with 0xBC, since ATT chunk boundaries are arbitrary and a payload
    // byte can collide with the header magic. A new header is only accepted when idle.
    // (Rings stream one big-data reply at a time; QRing has never handled interleaving.)
    private var bigDataBuffer: ByteArray? = null

    /** Samples already decoded from earlier packets of the current interval-temperature day —
     *  slot position is cumulative across a day's packets (see decodeIntervalTemperature). */
    private var intervalTempSampleOffset = 0

    // BLE topology
    override val serviceUUIDs = listOf(ColmiUUIDs.SERVICE_V1, ColmiUUIDs.SERVICE_V2)
    override val writeUUID = ColmiUUIDs.WRITE
    override val commandUUID = ColmiUUIDs.COMMAND
    override val notifyUUIDs = listOf(ColmiUUIDs.NOTIFY_V1, ColmiUUIDs.NOTIFY_V2)
    override val batteryServiceUUID: String? = null
    override val batteryCharUUID: String? = null

    override fun frame(command: ByteArray): ByteArray {
        if (command.isNotEmpty() && command[0].toUByte() == ColmiCommandID.BIG_DATA_V2) return command
        return ColmiPacket.frame(command)
    }

    override fun usesCommandChannel(frame: ByteArray): Boolean =
        frame.isNotEmpty() && frame[0].toUByte() == ColmiCommandID.BIG_DATA_V2

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> {
        return if (from == ColmiUUIDs.NOTIFY_V2) {
            ingestBigData(data)
        } else {
            ingestNormal(data)
        }
    }

    private fun ingestNormal(data: ByteArray): List<RingDecodedEvent> {
        val op = if (data.isNotEmpty()) data[0].toUByte() else 0u
        if (ColmiSyncEngine.isHistoryOpcode(op)) {
            return engine.handleHistoryFrame(data)
        }
        return decoder.decodeNormal(data)
    }

    private fun ingestBigData(data: ByteArray): List<RingDecodedEvent> {
        val pending = bigDataBuffer
        if (pending == null) {
            if (data.size < 6 || data[0].toUByte() != ColmiCommandID.BIG_DATA_V2) {
                return listOf(RingDecodedEvent.Unknown(
                    commandId = if (data.isNotEmpty()) data[0].toUByte() else 0u, raw = data
                ))
            }
            return accumulate(data)
        }
        // Mid-transfer: append EVERY chunk, including one that starts with 0xBC. ATT chunk
        // boundaries are arbitrary, so a payload byte can collide with the header magic;
        // treating such a chunk as a new frame corrupted the transfer. This matches QRing's
        // LargeDataParser, which appends unconditionally while `intact` is false. Rings stream
        // one big-data reply at a time (QRing can't handle interleaving either), so there is no
        // interleaved-frame case to special-case.
        return accumulate(pending + data)
    }

    private fun accumulate(buffer: ByteArray): List<RingDecodedEvent> {
        val expectedLength = ColmiBytes.u16(buffer[2].toUByte(), buffer[3].toUByte())
        if (buffer.size < expectedLength + 6) {
            bigDataBuffer = buffer
            return emptyList()
        }
        bigDataBuffer = null
        return completeAndDecode(buffer)
    }

    private fun completeAndDecode(buffer: ByteArray): List<RingDecodedEvent> {
        val type = buffer[1].toUByte()
        val events = if (type == ColmiCommandID.BIG_DATA_INTERVAL_TEMPERATURE) {
            val packetIndex = if (buffer.size > 9) buffer[9].toInt() and 0xFF else 0
            if (packetIndex == 0) intervalTempSampleOffset = 0
            val decoded = decoder.decodeIntervalTemperature(buffer, sampleOffset = intervalTempSampleOffset)
            intervalTempSampleOffset += ((buffer.size - 10).coerceAtLeast(0)) / 2
            decoded
        } else {
            decoder.decodeBigData(buffer)
        }
        engine.handleBigDataComplete(type = type, data = buffer)
        return events
    }

    override fun makeSyncEngine(): RingSyncEngine = engine
}
