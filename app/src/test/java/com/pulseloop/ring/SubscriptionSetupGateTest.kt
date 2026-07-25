package com.pulseloop.ring

import org.junit.Assert.*
import org.junit.Test

class SubscriptionSetupGateTest {
    private val command = YCBTUUIDs.COMMAND
    private val stream = YCBTUUIDs.STREAM
    private val required = listOf(
        RequiredSubscription(command, SubscriptionMode.INDICATION),
        RequiredSubscription(stream, SubscriptionMode.INDICATION),
    )

    @Test
    fun `YCBT is ready only after both declared CCCDs succeed`() {
        val gate = SubscriptionSetupGate(listOf(command, stream), required)
        gate.observeCharacteristic(command, localEnabled = true, hasCccd = true)
        gate.observeCharacteristic(stream, localEnabled = true, hasCccd = true)
        assertNull(gate.topologyFailure())

        gate.descriptorWritten(command, successful = true)
        assertFalse(gate.isReady)
        gate.descriptorWritten(stream, successful = true)
        assertTrue(gate.isReady)
    }

    @Test
    fun `missing required channel gives a useful topology failure`() {
        val gate = SubscriptionSetupGate(listOf(command, stream), required)
        gate.observeCharacteristic(command, localEnabled = true, hasCccd = true)

        val failure = gate.topologyFailure()
        assertNotNull(failure)
        assertTrue(failure!!.contains("BE940003"))
    }

    @Test
    fun `missing CCCD or failed local enable cannot satisfy topology`() {
        val missingCccd = SubscriptionSetupGate(listOf(command, stream), required)
        missingCccd.observeCharacteristic(command, localEnabled = true, hasCccd = true)
        missingCccd.observeCharacteristic(stream, localEnabled = true, hasCccd = false)
        assertNotNull(missingCccd.topologyFailure())

        val failedEnable = SubscriptionSetupGate(listOf(command, stream), required)
        failedEnable.observeCharacteristic(command, localEnabled = true, hasCccd = true)
        failedEnable.observeCharacteristic(stream, localEnabled = false, hasCccd = true)
        assertNotNull(failedEnable.topologyFailure())
    }

    @Test
    fun `legacy optional channels connect after first successful notify`() {
        val first = "00000001-0000-1000-8000-00805f9b34fb"
        val optional = "00000002-0000-1000-8000-00805f9b34fb"
        val gate = SubscriptionSetupGate(listOf(first, optional), emptyList())
        gate.observeCharacteristic(first, localEnabled = true, hasCccd = true)
        gate.descriptorWritten(first, successful = true)

        assertTrue(gate.isReady)
        assertNull(gate.topologyFailure())
        assertEquals(SubscriptionMode.NOTIFICATION, gate.modeFor(optional))
    }
}
