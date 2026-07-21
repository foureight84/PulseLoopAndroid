package com.pulseloop.ring

/**
 * CRP ("crrepa"/CRPsmart) driver — the `fdda`-profile family behind the Moyoung "Da Rings" app,
 * the official app for the CRP-firmware Colmi R11 (see [CRPProtocol] and `decompiled-moyoung-official/`).
 *
 * **BLE topology.** Proprietary service `fdda`; write to `fdd2`; notify on `fdd1` (current-steps
 * push), `fdd3` (framed command replies) and `fdd6` (recording/OTA, ignored in v1). Heart rate
 * rides the standard `180d`/`2a37` characteristic and battery the standard `180f`/`2a19` — both
 * declared so [RingBLEClient] binds them.
 *
 * **Framing is identity.** [CRPProtocol] and [CRPSyncEngine] emit fully-framed `FD DA …` packets
 * (all v1 commands fit one ≤20-byte packet, so no chunking is needed), so [frame] returns its input.
 *
 * **Inbound.** `fdd3` replies may span several notifications and are reassembled by
 * [CRPFrameAssembler]; `fdd1`/`2a37` pushes are self-contained. A fresh driver is built per connect
 * ([RingBLEClient.installDriver] calls `coordinator.makeDriver` every time), so the assembler starts
 * clean without an explicit reset hook (matches [LuckRingDriver]/[YCBTDriver]).
 */
class CRPDriver(private val writer: RingCommandWriter?) : WearableDriver {
    private val assembler = CRPFrameAssembler()

    // MARK: BLE topology
    override val serviceUUIDs: List<String> = listOf(CRPUUIDs.SERVICE, CRPUUIDs.SERVICE_HEART_RATE)
    override val writeUUID: String = CRPUUIDs.CHAR_WRITE
    override val notifyUUIDs: List<String> = listOf(
        CRPUUIDs.CHAR_STEPS_NOTIFY,
        CRPUUIDs.CHAR_CMD_NOTIFY,
        CRPUUIDs.CHAR_RECORDING_NOTIFY,
        CRPUUIDs.CHAR_HEART_RATE_MEASURE,
    )
    override val batteryServiceUUID: String = CRPUUIDs.SERVICE_BATTERY
    override val batteryCharUUID: String = CRPUUIDs.CHAR_BATTERY_LEVEL

    // MARK: Framing — the encoder/engine already build full CRP frames.
    override fun frame(command: ByteArray): ByteArray = command

    // MARK: Inbound decode
    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> {
        // Framed command replies (fdd3) reassemble across notifications; everything else is a
        // self-contained push routed by source characteristic inside CRPDecoder.
        if (from.lowercase().contains("fdd3")) {
            val frame = assembler.append(data) ?: return emptyList()
            return CRPDecoder.decode(frame, from)
        }
        return CRPDecoder.decode(data, from)
    }

    override fun makeSyncEngine(): RingSyncEngine = CRPSyncEngine(writer)
}
