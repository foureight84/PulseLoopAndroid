package com.pulseloop.ring

/**
 * Ported from LuckRingDriver.swift (iOS #90).
 *
 * LuckRing / TK18 driver. Topology is a single service `F618` with a write char `B002` and a notify
 * char `B001`; the standard `180D` Heart Rate characteristic is deliberately left unsubscribed (mirror
 * the YCBT rationale -- the proprietary `07` stream reflects real finger contact).
 *
 * **Framing is identity.** The encoder / sync engine / history pager already emit fully-framed
 * 20-byte packets (a logical frame is split into head + continuation packets and enqueued
 * individually -- the jring pattern), so [frame] returns its input untouched. `RingBLEClient`'s
 * serialized write queue handles pacing between packets.
 *
 * **Inbound: ACK-before-decode.** The ring retransmits a device-initiated **SEND** until the app
 * answers with a matching ACK (`queue/b.java`'s app-ACK rule), so [ingest] enqueues the protocol ACK
 * *before* it decodes -- a slow decode must never be able to stall the ring. A device ACK or a
 * SEND_NO_ACK is never ACKed (the former is not data; the latter, by definition, expects no reply).
 *
 * A fresh driver is built per connection ([RingBLEClient.installDriver] calls `coordinator.makeDriver`
 * on every connect), so -- unlike iOS's `connectionDidStart`/`connectionDidEnd` -- no explicit
 * reconnect-reset hook is needed here: the assembler/history-pager state below all start clean
 * (matches [YCBTDriver]'s identical note).
 */
class LuckRingDriver(private val writer: RingCommandWriter?) : WearableDriver {
    private val decoder = LuckRingDecoder
    private val assembler = LuckRingFrameAssembler()
    /** The history pager. Owned here because only the driver sees frames ([ingest]); handed to the
     *  engine so `runStartup` can seed the catalog pass. */
    private val historySync = LuckRingHistorySync(writer)

    // MARK: BLE topology

    override val serviceUUIDs: List<String> = listOf(LuckRingUUIDs.SERVICE)
    override val writeUUID: String = LuckRingUUIDs.WRITE
    override val notifyUUIDs: List<String> = listOf(LuckRingUUIDs.NOTIFY)
    override val batteryServiceUUID: String? = null   // battery is in-band (dataType 3)
    override val batteryCharUUID: String? = null

    // MARK: Framing

    override fun frame(command: ByteArray): ByteArray = command

    // MARK: Inbound decode

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> {
        val frame = assembler.append(data) ?: return emptyList()

        // ACK a device-initiated SEND before decoding -- the ring retransmits until we do.
        if (frame.cmdType == LuckRingCmdType.SEND) {
            writer?.enqueue(LuckRingPacketizer.ack(frame.dataType, frame.seq, frame.devType))
        }

        // Let the history pager settle/advance on this type's data frames.
        if (frame.cmdType == LuckRingCmdType.SEND || frame.cmdType == LuckRingCmdType.SEND_NO_ACK) {
            historySync.noteReceived(frame.dataType)
        }

        return decoder.decode(frame)
    }

    override fun makeSyncEngine(): RingSyncEngine = LuckRingSyncEngine(writer, historySync)
}
