package com.pulseloop.ring

class RWfitEncoder {
    private var useAbProtocol = false

    fun setProtocol(ab: Boolean) { useAbProtocol = ab }

    fun connectHandshake(): ByteArray = if (useAbProtocol)
        RWfitProtocol.packAb(RWfitProtocol.CMD_DEVICE_INFO)
    else
        RWfitProtocol.pack7e(RWfitProtocol.CMD_DEVICE_INFO)

    fun requestBattery(): ByteArray = pack(RWfitProtocol.CMD_BATTERY)
    fun requestHR(): ByteArray = pack(RWfitProtocol.CMD_HEART_RATE, byteArrayOf(0x01))
    fun stopHR(): ByteArray = pack(RWfitProtocol.CMD_HEART_RATE, byteArrayOf(0x00))
    fun requestSpO2(): ByteArray = pack(RWfitProtocol.CMD_SPO2, byteArrayOf(0x01))
    fun requestHistory(daysAgo: Int = 0): ByteArray = pack(RWfitProtocol.CMD_HISTORY_SYNC, byteArrayOf(daysAgo.toByte()))
    fun timeSync(ts: Long): ByteArray {
        val sec = (ts / 1000).toInt()
        return pack(RWfitProtocol.CMD_TIME_SYNC, byteArrayOf(
            (sec and 0xFF).toByte(), ((sec shr 8) and 0xFF).toByte(),
            ((sec shr 16) and 0xFF).toByte(), ((sec shr 24) and 0xFF).toByte(),
        ))
    }

    fun makeUnbindCommand(): ByteArray = pack(0xFF.toByte().toInt(), byteArrayOf(0x00))

    private fun pack(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        if (useAbProtocol) RWfitProtocol.packAb(cmd, payload = payload)
        else RWfitProtocol.pack7e(cmd, payload)
}
