package com.pulseloop.ring

/** Days of history pulled on the first pass of a jring connection; every later pass asks only for
 *  today. Each day is its own `0x10` request — byte 1 is a day offset, not a count (issue #73).
 *  See [JringSyncEngine.historyDayOffsetsForThisPass] for why this is shorter than CRP's week. */
private const val JRING_BACKFILL_DAYS = 3

@OptIn(ExperimentalStdlibApi::class)

/**
 * Ported from [JringCoordinator] in JringCoordinator.swift.
 * Coordinator for the original "jring" (SMART_RING, service 000056ff…).
 */
object JringCoordinator : WearableCoordinator {
    override val deviceType = RingDeviceType.JRING
    private const val ADVERTISED_NAME = "SMART_RING"
    private const val MANUFACTURER_HEX_NEEDLE = "41422ec75b6a"

    override fun matches(name: String?, advertisement: AdvertisementInfo): Boolean {
        // "SMART_RING" isn't exclusive to real Jring hardware — some Colmi/Yawell R11 units
        // (issue #29) advertise this same generic factory name while still exposing Colmi's own
        // GATT service UUIDs. Don't let the bare name win over a device that already identifies
        // itself as Colmi/Yawell via service UUID; ColmiCoordinator gets first claim on those.
        val advertisesColmiService = advertisement.serviceUUIDs.contains(ColmiUUIDs.SERVICE_V1) ||
            advertisement.serviceUUIDs.contains(ColmiUUIDs.SERVICE_V2)
        if (name == ADVERTISED_NAME && !advertisesColmiService) return true
        if (advertisement.serviceUUIDs.contains(RingUUIDs.SERVICE)) return true
        advertisement.manufacturerData?.let { mfg ->
            if (mfg.toHexString().contains(MANUFACTURER_HEX_NEEDLE)) return true
        }
        return false
    }

    override val capabilities = setOf(
        WearableCapability.HEART_RATE,
        WearableCapability.SPO2,
        WearableCapability.STEPS,
        WearableCapability.SLEEP,
        WearableCapability.BATTERY,
        WearableCapability.BLOOD_PRESSURE,   // 0x23/0x24 combined measurement
        WearableCapability.BLOOD_SUGAR,      // combined measurement byte[7] (mmol/L ×10)
        WearableCapability.STRESS,           // combined measurement byte[6]
        WearableCapability.FATIGUE,          // combined measurement byte[5]
        WearableCapability.MANUAL_HEART_RATE,
        WearableCapability.MANUAL_SPO2,
        WearableCapability.REALTIME_HEART_RATE,
        WearableCapability.FIND_DEVICE,
        // NOTE: No TEMPERATURE — the Jring (SR08-class PPG ring) has no skin-temperature
        // sensor. In the official app temperature is gated by the 0x20 capability bit
        // FUNCTION_TEMPERATURE (zArr[10]), which these rings do not set; it exists in the
        // shared SDK only for smartwatch variants.
    )

    override val iconSystemName = "circle.hexagongrid.circle.fill"

    override fun makeDriver(writer: RingCommandWriter): WearableDriver = JringDriver(writer)
}

/**
 * Ported from [JringDriver] in JringDriver.swift.
 * Thin wrapper over RingDecoder/RingEncoder for jring devices.
 */
class JringDriver(
    private val writer: RingCommandWriter,
    /** The history pager. Owned here because only the driver sees whole frames ([ingest]); handed
     *  to the engine so `runStartup` can seed the day window. A fresh driver is built per
     *  connection (`RingBLEClient.installDriver` calls `coordinator.makeDriver` on every connect),
     *  so nothing here needs a reconnect *reset* — but its timers do need [connectionDidEnd].
     *  Injectable so a test can run a whole pass on millisecond timers. */
    private val historySync: JringHistorySync = JringHistorySync(writer),
) : WearableDriver {
    /** One clock per connection, shared by the decoder and the sync engine: the engine latches
     *  the UTC offset when it sends 0x01, and the decoder subtracts that same offset off every
     *  ring-stamped history timestamp. See [JringClock]. */
    private val clock = JringClock()
    private val decoder = RingDecoder(clock)

    override val serviceUUIDs = listOf(RingUUIDs.SERVICE)
    override val writeUUID = RingUUIDs.WRITE
    override val notifyUUIDs = listOf(RingUUIDs.NOTIFY)
    override val batteryServiceUUID = "0000180f-0000-1000-8000-00805f9b34fb"
    override val batteryCharUUID = RingUUIDs.BATTERY

    override fun frame(command: ByteArray) = command  // jring: already 20 bytes, no checksum

    override fun ingest(data: ByteArray, from: String): List<RingDecodedEvent> {
        // The pager settles on whole frames, so it is fed here rather than from the engine's
        // decoded-event stream: one 0x10 frame fans out into 15 ActivityBucket events, which says
        // nothing about how many frames are still coming.
        historySync.noteFrame(data)
        return decoder.decode(data)
    }

    override fun makeSyncEngine(): RingSyncEngine = JringSyncEngine(writer, clock, historySync)

    /**
     * Drop any in-flight history pass on disconnect.
     *
     * A fresh driver is built per connection, so no *state* needs resetting here — but the pager
     * holds live timers, and [RingCommandWriter] outlives this driver. A settle firing after the
     * link dropped would enqueue the next day's `0x10` into whatever connection comes next,
     * landing a stray history request on top of that connection's own stream: the truncation of
     * issue #73, arriving from a connection that has already ended.
     */
    override fun connectionDidEnd() {
        historySync.cancel()
    }
}

/**
 * Ported from [JringSyncEngine] in JringSyncEngine.swift.
 * Fire-and-forget sync engine for jring devices — with one exception: history goes through
 * [JringHistorySync], because this ring answers one history request at a time (issue #73).
 */
class JringSyncEngine(
    private val writer: RingCommandWriter?,
    private val clock: JringClock = JringClock(),
    /** Owned by [JringDriver] in production so it can be fed whole frames; defaulted here so a
     *  caller holding only a writer (tests, and any future engine-only path) still gets a pager
     *  rather than an unpaced burst. */
    private val historySync: JringHistorySync = JringHistorySync(writer),
) : RingSyncEngine {
    override val supportsCombinedMeasurement: Boolean = true
    private val encoder = RingEncoder

    /** The ring's self-reported feature bits (0x20 reply), or `null` if it never answered.
     *  Nothing branches on these yet — captured so a future offline-history sync chain can gate
     *  its extra per-day queries once the bit ordering is confirmed against real hardware. */
    private var bandCapabilities: JringBandCapabilities? = null

    override fun runStartup() {
        writer?.enqueue(encoder.makeStatusCommand())
        enqueueTimeSync()
        writer?.enqueue(encoder.makeLocaleCommand())
        writer?.enqueue(encoder.makeDefaultUserInfoCommand())
        // 0x19 arms the ring's continuous background sensor logging. The vendor app sends this on
        // every connect; without it the ring records almost nothing, which is why users previously
        // had to initialise with the vendor app first.
        writer?.enqueue(encoder.makeAutomaticHeartRateCommand(enabled = true, cadenceMinutes = 30))
        writer?.enqueue(encoder.makeBandFunctionCommand())
        // History is paged rather than enqueued: [JringHistorySync] sends one day's 0x10, holds
        // the rest until that day's stream goes quiet, and asks for each day's 0x16 HR itself.
        // The gate is only spent if a pass actually began — `start` declines while one is in
        // flight, and a declined backfill must still be owed.
        if (historySync.start(historyDayOffsetsForThisPass())) historyBackfilled = true
    }

    /** Whether this connection has already pulled the deep history window. A fresh engine is built
     *  per connection ([JringDriver.makeSyncEngine] runs on connect), so instance state gives
     *  "once per connection" for free — the same trick `CRPSyncEngine` uses for its read-backs. */
    private var historyBackfilled = false

    /**
     * Which days to ask for on this pass: **today always**, plus the older days of the backfill
     * window once per connection.
     *
     * `0x10`'s byte 1 is a day offset rather than a count (issue #73 — see
     * [RingEncoder.makeHistoryQueryCommand] for the vendor evidence), so each day needs its own
     * request. Today (`0`) leads every pass because it is the day the user is looking at, and
     * because under the old count reading it was the one day never requested at all: a single
     * `0x10/01` asked for yesterday, so last night's sleep never arrived.
     *
     * **Why the once-per-connection gate matters more here than on CRP.** [runStartup] is also the
     * ~30-minute background sync (and `refresh()`/`querySleep()` route through it), so an
     * unconditional wider window would re-pull the whole span every half hour forever. And `0x10`
     * returns activity *and* sleep — there is no sleep-only request — so each extra day is roughly
     * 96 more packets (activity arrives as 15× 1-minute buckets each), against the nights we
     * actually came for. That volume, not the nights, is why this window is deliberately shorter
     * than the CRP backfill's week.
     *
     * **This is the window, not the schedule.** Which days to ask for is decided here; *when* each
     * request goes out is [JringHistorySync]'s, one day at a time. The two were briefly the same
     * thing and that was the second half of #73: enqueued together, the window's requests
     * truncated each other on an SR08.
     *
     * **One divergence from the vendor remains, deliberate:** it counts *down* to today
     * (`DupMainActivity.onGetMultipleSportData`), we lead with today. It is the day the user opened
     * the app to see, so it is the day that should land first and the one that survives if a pass
     * is cut short. Re-syncing the same days is harmless anyway: activity buckets upsert by
     * timestamp with the day total recomputed from distinct buckets, and sleep reconciles one
     * waking day at a time.
     */
    private fun historyDayOffsetsForThisPass(): List<Int> =
        if (historyBackfilled) listOf(0) else (0 until JRING_BACKFILL_DAYS).toList()

    override fun handle(event: RingDecodedEvent) {
        when (event) {
            // Ring-side bind handshake (0x4B), mirroring the official app's
            // onNotifyBindedInfo: the ring drives binding on connect so it stays paired
            // to us and keeps streaming. Unbind (on forget) is handled in RingBLEClient.
            is RingDecodedEvent.BindNotify -> when (event.action) {
                0 -> if (event.state == 0) writer?.enqueue(encoder.makeBindAppStartCommand()) // INIT → APP_START
                2 -> writer?.enqueue(encoder.makeBindSuccessCommand())                         // ACK → SUCCESS
            }
            is RingDecodedEvent.BandFunction -> bandCapabilities = event.capabilities
            else -> {}
        }
    }

    /** Latch the offset we're about to encode, then send it. The decoder subtracts the same
     *  value back off every ring-stamped timestamp — the two halves must always move together. */
    private fun enqueueTimeSync() {
        clock.capture()
        writer?.enqueue(encoder.makeTimeSyncCommand())
    }

    /** Re-push the clock after the phone's timezone or wall clock changes, so the ring's RTC
     *  keeps tracking local time (its sleep detection and day-indexed history depend on it). */
    override fun resyncTime() {
        enqueueTimeSync()
    }

    override fun startHeartRate() {
        writer?.enqueue(encoder.makeHeartRateStartCommand())
    }

    override fun stopHeartRate() {
        writer?.enqueue(encoder.makeHeartRateStopCommand())
        writer?.enqueue(encoder.makeAutomaticHeartRateCommand(enabled = true, cadenceMinutes = 30))
    }

    /** Spot HR using live HR streaming (0x14), same as workout HR. */
    override fun measureHeartRateSpot() {
        writer?.enqueue(encoder.makeHeartRateStartCommand())
    }

    /** SpO₂-only measurement using 0x3E (not 0x23 — that's combined BP measurement). */
    override fun startSpO2() {
        writer?.enqueue(encoder.makeSpO2StartCommand())
    }

    override fun stopSpO2() {
        writer?.enqueue(encoder.makeSpO2StopCommand())
    }

    /** Combined measurement (0x23 → 0x24): HR + systolic + diastolic + SpO₂ + fatigue + stress + blood sugar + HRV in one 20-byte notification. BP values are direct sensor readings. Blood sugar is a profile-derived estimate. */
    override fun startCombinedMeasurement() {
        writer?.enqueue(encoder.makeCombinedMeasurementStart())
    }

    override fun stopCombinedMeasurement() {
        writer?.enqueue(encoder.makeCombinedMeasurementStop())
    }

    override fun findDevice() {
        writer?.enqueue(encoder.makeFindRingCommand())
    }

    override fun setGoal(steps: Int) {
        writer?.enqueue(encoder.makeGoalCommand(steps))
    }

    override fun setUserInfo(ageYears: Int, isMale: Boolean, heightCm: Int, weightKg: Int) {
        writer?.enqueue(encoder.makeUserInfoCommand(ageYears, isMale, heightCm, weightKg))
    }

    override fun setBloodPressureAdjust(systolic: Int, diastolic: Int) {
        writer?.enqueue(encoder.makeBPAdjustCommand(systolic, diastolic))
    }

    override fun setAppId(appId: String) {
        writer?.enqueue(encoder.makeAppIdCommand(appId))
    }

    // Keepalive ping (0x3A) — prevents ring's ~20s idle disconnect
    fun sendKeepalive() {
        val cmd = ByteArray(20)
        cmd[0] = 0x3A.toByte()
        writer?.enqueue(cmd)
    }

    // Jring has no power-off or factory-reset capabilities — no-ops
    override fun powerOff() {}
    override fun factoryReset() {}
}
