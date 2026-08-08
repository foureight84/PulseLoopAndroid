package com.pulseloop.ring

class RWfitDriver(private val writer: RingCommandWriter?) : WearableDriver {
    private val encoder = RWfitEncoder()
    private val decoder = RWfitDecoder()

    override val serviceUUIDs: List<String> = listOf(RWfitProtocol.SERVICE_UUID)
    override val writeUUID: String = RWfitProtocol.WRITE_UUID
    override val notifyUUIDs: List<String> = listOf(RWfitProtocol.NOTIFY_UUID)

    override fun frame(command: ByteArray): ByteArray = command

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> = decoder.feed(data)

    override fun makeSyncEngine(): RingSyncEngine = RWfitSyncEngine(writer)

    override fun connectionDidStart() {
        encoder.setProtocol(false)
        decoder.setProtocol(false)
    }
}
