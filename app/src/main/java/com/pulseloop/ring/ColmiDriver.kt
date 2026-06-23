package com.pulseloop.ring

/**
 * Ported from [ColmiDriver] in ColmiDriver.swift.
 * Colmi R02 driver: 16-byte checksum framing, two notify channels, big-data reassembly.
 */
class ColmiDriver(private val writer: RingCommandWriter) : WearableDriver {
    private val decoder = ColmiDecoder
    private val engine: ColmiSyncEngine = ColmiSyncEngine(writer, decoder)

    // Big-data reassembly state
    private val bigDataBuffers = mutableMapOf<UByte, ByteArray>()
    private var activeBigDataType: UByte? = null

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
        val events = decoder.decodeNormal(data)
        if (data.isNotEmpty() && data[0].toUByte() == ColmiCommandID.REALTIME_HEART_RATE) {
            engine.observedRealtimeHeartRate()
        }
        return events
    }

    private fun ingestBigData(data: ByteArray): List<RingDecodedEvent> {
        val type: UByte
        if (data.isNotEmpty() && data[0].toUByte() == ColmiCommandID.BIG_DATA_V2) {
            val v = data.map { it.toUByte() }
            if (v.size < 4) return emptyList()
            type = v[1]
            bigDataBuffers[type] = data
        } else if (activeBigDataType != null && bigDataBuffers.containsKey(activeBigDataType)) {
            type = activeBigDataType!!
            bigDataBuffers[type] = bigDataBuffers[type]!! + data
        } else {
            return listOf(RingDecodedEvent.Unknown(
                commandId = if (data.isNotEmpty()) data[0].toUByte() else 0u, raw = data
            ))
        }

        val buffer = bigDataBuffers[type] ?: return emptyList()
        val bytes = buffer.map { it.toUByte() }
        if (bytes.size < 4) return emptyList()
        val expectedLength = ColmiBytes.u16(bytes[2], bytes[3])
        if (buffer.size < expectedLength + 6) {
            activeBigDataType = type
            return emptyList()
        }

        bigDataBuffers.remove(type)
        activeBigDataType = bigDataBuffers.keys.firstOrNull()
        val events = decoder.decodeBigData(buffer)
        engine.handleBigDataComplete(type = type)
        return events
    }

    override fun makeSyncEngine(): RingSyncEngine = engine
}
