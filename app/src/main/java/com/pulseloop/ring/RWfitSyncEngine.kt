package com.pulseloop.ring

class RWfitSyncEngine(private val writer: RingCommandWriter?) : RingSyncEngine {
    private val encoder = RWfitEncoder()

    override fun runStartup() { writer?.enqueue(encoder.connectHandshake()) }
    override fun handle(event: RingDecodedEvent) {}
    override fun startHeartRate() { writer?.enqueue(encoder.requestHR()) }
    override fun stopHeartRate() { writer?.enqueue(encoder.stopHR()) }
    override fun startSpO2() { writer?.enqueue(encoder.requestSpO2()) }
    override fun stopSpO2() {}
    override fun findDevice() {}
    override fun setGoal(steps: Int) {}
    override fun powerOff() {}
    override fun factoryReset() {}
}
