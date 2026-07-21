package com.pulseloop.ring

/**
 * Per-connection orchestration for a CRP ("crrepa") ring. Ported in spirit from the Moyoung
 * "Da Rings" connect flow (`d1/b.java` + `b1` package builders): after the link is up the app sets the
 * clock and pushes user anthropometrics, then the ring streams current steps (`fdd1`) on its own
 * and answers measurement commands. There is no bulk history state machine in v1, so most of the
 * [RingSyncEngine] surface is left as the interface's no-op defaults.
 *
 * v1 scope: clock + user-info handshake, live/manual heart rate, find-device, factory reset.
 * Steps and battery arrive as autonomous pushes/reads (see [CRPDriver]) and need no command here.
 * Sleep / SpO2 / HRV / stress / temperature and history sync are deliberately deferred — their
 * reply layouts aren't yet confirmed against the decompile, and [CRPCoordinator] doesn't advertise
 * those capabilities, so nothing calls the corresponding methods.
 */
class CRPSyncEngine(private val writer: RingCommandWriter?) : RingSyncEngine {

    private var profile: UserProfileValues? = null

    override fun runStartup() {
        // Set the device clock first (matches the vendor's connect handshake), then user info so
        // the ring's step/calorie algorithm has real inputs.
        send(CRPProtocol.setTime())
        profile?.let { send(userInfoFrame(it)) }
    }

    override fun handle(event: RingDecodedEvent) {
        // Steps/HR/battery are persisted by RingBLEClient via RingEventBridge; v1 keeps no
        // engine-side state (no staged history pipeline to advance).
    }

    // ---- Heart rate (standard 2a37 stream, started/stopped via the fdda command channel) ----
    override fun startHeartRate() { send(CRPProtocol.measureHeartRate(true)) }
    override fun stopHeartRate() { send(CRPProtocol.measureHeartRate(false)) }

    // ---- SpO2 (command verified; result parsing deferred, so capability isn't advertised) ----
    override fun startSpO2() { send(CRPProtocol.measureSpO2(true)) }
    override fun stopSpO2() { send(CRPProtocol.measureSpO2(false)) }

    override fun findDevice() { send(CRPProtocol.findDevice(true)) }

    override fun factoryReset() { send(CRPProtocol.factoryReset()) }

    override fun powerOff() {
        // No confirmed shut-down opcode in the v1 subset (the vendor uses a callback-gated
        // notification write). Left unsupported rather than sending a guessed command.
    }

    override fun setGoal(steps: Int) {
        // Step-goal command layout not yet confirmed from the decompile; no-op for now.
    }

    override fun setUserProfile(profile: UserProfileValues) { this.profile = profile }

    override fun applyUserProfile(profile: UserProfileValues) {
        this.profile = profile
        send(userInfoFrame(profile))
    }

    override fun resyncTime() { send(CRPProtocol.setTime()) }

    /** Map the app's [UserProfileValues] onto the CRP user-info payload. Stride length isn't
     *  carried by the profile, so estimate it from height (~0.43·height, a common default). */
    private fun userInfoFrame(p: UserProfileValues): ByteArray {
        val heightCm = p.heightCm.toInt()
        val strideCm = (heightCm * 0.43).toInt().coerceIn(0, 255)
        return CRPProtocol.setUserInfo(
            heightCm = heightCm,
            weightKg = p.weightKg.toInt(),
            ageYears = p.age.toInt(),
            gender = p.gender.toInt(),
            strideCm = strideCm,
        )
    }

    private fun send(frame: ByteArray?) {
        if (frame != null) writer?.enqueue(frame)
    }
}
