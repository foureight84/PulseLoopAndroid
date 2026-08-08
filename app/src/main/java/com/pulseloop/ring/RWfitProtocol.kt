package com.pulseloop.ring

object RWfitProtocol {
    const val SERVICE_UUID = "0000a00a-0000-1000-8000-00805f9b34fb"
    const val WRITE_UUID = "0000a002-0000-1000-8000-00805f9b34fb"
    const val NOTIFY_UUID = "0000a003-0000-1000-8000-00805f9b34fb"

    // 0x7E legacy framing
    const val FRAME_7E_HEADER = 0x7E.toByte()
    const val FRAME_7E_ACK = 0x01.toByte()

    // 0xAB JieLi framing
    const val FRAME_AB_HEADER = 0xAB.toByte()

    // Commands
    const val CMD_DEVICE_INFO = 0x01
    const val CMD_BATTERY = 0x02
    const val CMD_TIME_SYNC = 0x03
    const val CMD_HEART_RATE = 0x04
    const val CMD_SPO2 = 0x05
    const val CMD_BLOOD_PRESSURE = 0x06
    const val CMD_SLEEP = 0x07
    const val CMD_STEPS = 0x08
    const val CMD_HRV = 0x09
    const val CMD_TEMPERATURE = 0x0A
    const val CMD_STRESS = 0x0B
    const val CMD_BLOOD_SUGAR = 0x0C
    const val CMD_HISTORY_SYNC = 0x10
    const val CMD_HISTORY_DATA = 0x11

    /** XOR checksum for 0x7E framing. */
    fun xorChecksum(data: ByteArray, offset: Int, length: Int): Byte {
        var cs = 0
        for (i in offset until offset + length) cs = cs xor (data[i].toInt() and 0xFF)
        return cs.toByte()
    }

    /** CRC-16/ARC for 0xAB framing. */
    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0x0000
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) (crc shr 1) xor 0xA001 else crc shr 1
            }
        }
        return crc
    }

    /** Assemble a 0x7E frame: [0x7E] [len] [cmd] [payload…] [xor] */
    fun pack7e(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = 2 + payload.size
        val data = ByteArray(1 + len + 1)
        data[0] = FRAME_7E_HEADER
        data[1] = len.toByte()
        data[2] = cmd.toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, data, 3, payload.size)
        data[data.size - 1] = xorChecksum(data, 0, data.size - 1)
        return data
    }

    /** Assemble a 0xAB frame: [0xAB] [cmd] [key] [keyFlag] [payload…] [crc_lo] [crc_hi] */
    fun packAb(cmd: Int, key: Int = 0, keyFlag: Int = 0, payload: ByteArray = ByteArray(0)): ByteArray {
        val header = byteArrayOf(FRAME_AB_HEADER, cmd.toByte(), key.toByte(), keyFlag.toByte())
        val data = header + payload
        val crc = crc16(data, 0, data.size)
        return data + byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }
}
