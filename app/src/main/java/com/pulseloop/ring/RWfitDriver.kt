package com.pulseloop.ring

import android.util.Log

/**
 * RWfit driver. Owns the one thing that makes this family awkward: **the wire framing isn't known
 * until after connect.**
 *
 * The advertisement carries no signal for it, so the vendor decides from the sibling services the
 * ring exposes at discovery (`r5/b.java onServicesDiscovered`, lines 700-727): JieLi `AE00`, Telink
 * OTA or PixArt `FF00` present ⇒ `0xAB` framing; none of them ⇒ legacy `0x7E`. Both framings serve
 * the same `A00A`/`B002`/`B003` GATT, so the driver is shared and only the codec swaps.
 *
 * Legacy is the default until [servicesDiscovered] says otherwise: it's the more common firmware,
 * and a frame in the wrong format is ignored by the ring rather than misinterpreted (the magic
 * byte differs), so a wrong guess is inert rather than dangerous.
 */
class RWfitDriver(private val writer: RingCommandWriter?) : WearableDriver {

    private val legacyCodec = RWfitLegacyCodec()
    private val jieliCodec = RWfitJLCodec()
    private val encoder = RWfitEncoder(legacyCodec, jieliCodec)
    private val syncEngine = RWfitSyncEngine(writer, encoder)

    override val serviceUUIDs: List<String> = listOf(RWfitProtocol.SERVICE_UUID)
    override val writeUUID: String = RWfitProtocol.WRITE_UUID
    override val notifyUUIDs: List<String> = listOf(RWfitProtocol.NOTIFY_UUID)

    /** Commands arrive pre-framed from [RWfitEncoder]; there's no second envelope. */
    override fun frame(command: ByteArray): ByteArray = command

    override fun makeSyncEngine(): RingSyncEngine = syncEngine

    override fun connectionDidStart() {
        legacyCodec.reset()
        jieliCodec.reset()
        syncEngine.reset()
        encoder.framing = RWfitFraming.LEGACY
    }

    override fun connectionDidEnd() {
        legacyCodec.reset()
        jieliCodec.reset()
        syncEngine.reset()
    }

    override fun servicesDiscovered(serviceUUIDs: Collection<String>) {
        val present = serviceUUIDs.map { it.lowercase() }.toSet()
        val isJieLi = RWfitProtocol.FRAMING_DISCRIMINATOR_UUIDS.any { it in present }
        encoder.framing = if (isJieLi) RWfitFraming.JIELI else RWfitFraming.LEGACY
        syncEngine.framing = encoder.framing
        Log.i(TAG, "framing = ${encoder.framing} (discriminators present: " +
            RWfitProtocol.FRAMING_DISCRIMINATOR_UUIDS.filter { it in present } + ")")
    }

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> =
        when (encoder.framing) {
            RWfitFraming.LEGACY -> ingestLegacy(data)
            RWfitFraming.JIELI -> ingestJieLi(data)
        }

    // ── Legacy 0x7E ─────────────────────────────────────────────────────────────

    private fun ingestLegacy(data: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (inbound in legacyCodec.decode(data)) {
            when (inbound) {
                // The vendor ACKs before it parses, and the ring retransmits until it sees one.
                is RWfitLegacyInbound.AckNeeded ->
                    writer?.enqueue(legacyCodec.ack(inbound.cmd, inbound.serial, status = 0))

                // Status 2 asks for a retransmit rather than dropping the frame silently.
                is RWfitLegacyInbound.ChecksumFailed -> {
                    Log.w(TAG, "checksum failed on cmd 0x${"%02X".format(inbound.cmd)} — NACKing")
                    writer?.enqueue(legacyCodec.ack(inbound.cmd, inbound.serial, status = 2))
                }

                is RWfitLegacyInbound.DeviceAck ->
                    syncEngine.onDeviceAck(inbound.cmd, inbound.status)

                is RWfitLegacyInbound.Frame -> events.addAll(decodeLegacyFrame(inbound))
            }
        }
        return events
    }

    private fun decodeLegacyFrame(frame: RWfitLegacyInbound.Frame): List<RingDecodedEvent> {
        val p = frame.payload
        return when (frame.cmd) {
            RWfitProtocol.Legacy.DEVICE_INFO -> {
                syncEngine.onDeviceInfo()
                listOf(RingDecodedEvent.Status(address = null))
            }

            RWfitProtocol.Legacy.BATTERY, RWfitProtocol.Legacy.BATTERY_ALT ->
                RWfitDecoder.decodeBattery(p)

            RWfitProtocol.Legacy.SYNC_MANIFEST -> {
                RWfitDecoder.decodeSyncManifest(p)?.let { syncEngine.onManifest(it) }
                emptyList()
            }

            RWfitProtocol.Legacy.STEPS_HISTORY ->
                RWfitDecoder.decodeStepHistory(p).also { syncEngine.onHistoryReply(RWfitProtocol.HistoryType.STEPS) }
            RWfitProtocol.Legacy.SLEEP_HISTORY ->
                RWfitDecoder.decodeSleepHistory(p).also { syncEngine.onHistoryReply(RWfitProtocol.HistoryType.SLEEP) }
            RWfitProtocol.Legacy.HEART_RATE_HISTORY ->
                RWfitDecoder.decodeHeartRateHistory(p).also { syncEngine.onHistoryReply(RWfitProtocol.HistoryType.HEART_RATE) }
            RWfitProtocol.Legacy.BLOOD_PRESSURE_HISTORY ->
                RWfitDecoder.decodeBloodPressureHistory(p).also { syncEngine.onHistoryReply(RWfitProtocol.HistoryType.BLOOD_PRESSURE) }
            RWfitProtocol.Legacy.SPO2_HISTORY ->
                RWfitDecoder.decodeSpo2History(p).also { syncEngine.onHistoryReply(RWfitProtocol.HistoryType.SPO2) }
            RWfitProtocol.Legacy.TEMPERATURE_HISTORY ->
                RWfitDecoder.decodeTemperatureHistory(p).also { syncEngine.onHistoryReply(RWfitProtocol.HistoryType.TEMPERATURE) }

            // Breathe history decodes to nothing PulseLoop stores, but the reply still has to
            // advance the cascade or the sync stalls on it.
            RWfitProtocol.Legacy.BREATHE_HISTORY -> {
                syncEngine.onHistoryReply(RWfitProtocol.HistoryType.BREATHE)
                emptyList()
            }

            // Feature bitmap (x5/b.java i() → SupportMenuBean). Its layout hasn't been extracted
            // yet, so the capability gating it would drive isn't wired — see RWfitCoordinator.
            RWfitProtocol.Legacy.FEATURES, RWfitProtocol.Legacy.BIND_STATUS -> emptyList()

            else -> {
                Log.d(TAG, "unhandled legacy cmd 0x${"%02X".format(frame.cmd)} (${p.size}B)")
                emptyList()
            }
        }
    }

    // ── JieLi 0xAB ──────────────────────────────────────────────────────────────

    private fun ingestJieLi(data: ByteArray): List<RingDecodedEvent> {
        val events = mutableListOf<RingDecodedEvent>()
        for (inbound in jieliCodec.decode(data)) {
            when (inbound) {
                is RWfitJLInbound.ChecksumFailed ->
                    Log.w(TAG, "JieLi CRC failed for ${inbound.triple}")

                is RWfitJLInbound.Frame -> {
                    // The vendor app→device ACKs every non-ACK frame it receives (r5/b.java:429).
                    if (!inbound.isAck) writer?.enqueue(jieliCodec.ack(inbound.triple))
                    events.addAll(decodeJieLiFrame(inbound))
                }
            }
        }
        return events
    }

    private fun decodeJieLiFrame(frame: RWfitJLInbound.Frame): List<RingDecodedEvent> {
        val t = frame.triple
        return when {
            t.cmd == RWfitProtocol.JieLi.BATTERY.cmd && t.key == RWfitProtocol.JieLi.BATTERY.key -> {
                // The JieLi battery body is a bare percentage after the triple, unlike legacy's
                // three-byte PowerBean.
                frame.payload.firstOrNull()
                    ?.let { listOf(RingDecodedEvent.Battery(percent = (it.toInt() and 0xFF).coerceIn(0, 100))) }
                    ?: emptyList()
            }

            t.cmd == RWfitProtocol.JieLi.DEVICE_INFO.cmd && t.key == RWfitProtocol.JieLi.DEVICE_INFO.key -> {
                syncEngine.onDeviceInfo()
                listOf(RingDecodedEvent.Status(address = null))
            }

            // The 05-group history bodies, decoded per-type from the vendor parsers
            // (`x5/b.java` a0/V/T/Z/U/S/W/Y/R — see RWfitJLHistory for the layouts). Steps come
            // up as ActivityBuckets (the records are per-interval deltas the vendor sums per
            // date), everything else as the same HistoryMeasurement/SleepTimeline events the
            // legacy path emits. Still unported: sport {5,14,16}, Muslim count {5,23,16} and the
            // other non-metric 05 keys, plus the {5,x,0x30} delete variants the vendor sends
            // after sync (no parser for them exists in the vendor either — x5/b.java dispatch).
            t.cmd == 0x05.toByte() -> RWfitJLHistory.decode(t.key, frame.payload) ?: run {
                Log.i(TAG, "JieLi history frame key 0x${"%02X".format(t.key)} (${frame.payload.size}B) — decoder not yet ported")
                emptyList()
            }

            else -> {
                Log.d(TAG, "unhandled JieLi triple $t (${frame.payload.size}B)")
                emptyList()
            }
        }
    }

    private companion object {
        const val TAG = "RWfitDriver"
    }
}
