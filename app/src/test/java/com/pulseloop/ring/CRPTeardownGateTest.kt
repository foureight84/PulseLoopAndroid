package com.pulseloop.ring

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CRPTeardownGateTest {
    private val ring = "ring"

    @Test
    fun `quick reconnect waits for the physical teardown captured at 1472 ms`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        advanceTimeBy(500)
        gate.gattClosed(old)
        advanceTimeBy(822) // User taps reconnect 1.322 s after Disconnect.
        val connect = async { gate.await(ring) }
        runCurrent()
        assertFalse("GATT close is not proof of physical disconnect", connect.isCompleted)
        advanceTimeBy(150)
        gate.aclDisconnected(ring)
        runCurrent()
        assertTrue(connect.await())
        assertEquals(1_472L, testScheduler.currentTime)
        assertFalse(gate.isPending(ring))
    }

    @Test
    fun `an early physical disconnect still waits for the old GATT close`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        val connect = async { gate.await(ring) }
        runCurrent()
        gate.aclDisconnected(ring)
        runCurrent()
        assertFalse(connect.isCompleted)
        advanceTimeBy(500)
        gate.gattClosed(old)
        runCurrent()
        assertTrue(connect.await())
    }

    @Test
    fun `a link already down waits only for the GATT close, not a broadcast`() = runTest {
        // Watchdog / hung-CONNECTING reconnect: the OS reports no GATT link, so no
        // ACL_DISCONNECTED will arrive. Must not fall through to the 5 s timeout and scan.
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        gate.linkAlreadyDown(old)
        val connect = async { gate.await(ring) }
        runCurrent()
        assertFalse("the old handle must still close first", connect.isCompleted)
        advanceTimeBy(500)
        gate.gattClosed(old)
        runCurrent()
        assertTrue(connect.await())
        assertEquals(500L, testScheduler.currentTime)
        assertFalse(gate.isPending(ring))
    }

    @Test
    fun `a stale link-down probe cannot release a newer teardown`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val stale = gate.begin(ring)
        val current = gate.begin(ring)
        gate.linkAlreadyDown(stale)
        gate.gattClosed(current)
        assertTrue(gate.isPending(ring))
    }

    @Test
    fun `reconnecting after completed teardown adds no delay`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        gate.gattClosed(old)
        gate.aclDisconnected(ring)
        assertTrue(gate.await(ring))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a different device disconnect cannot release this ring`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        gate.gattClosed(old)
        val connect = async { gate.await(ring) }
        runCurrent()
        gate.aclDisconnected("headphones")
        runCurrent()
        assertFalse(connect.isCompleted)
        gate.aclDisconnected(ring)
        runCurrent()
        assertTrue(connect.await())
    }

    @Test
    fun `a stale GATT close cannot release a newer teardown`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val stale = gate.begin(ring)
        val current = gate.begin(ring)
        val connect = async { gate.await(ring) }
        runCurrent()
        gate.gattClosed(stale)
        gate.aclDisconnected(ring)
        runCurrent()
        assertFalse(connect.isCompleted)
        gate.gattClosed(current)
        runCurrent()
        assertTrue(connect.await())
    }

    @Test
    fun `cancelling one client's wait preserves teardown for the next client`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        gate.gattClosed(old)
        val worker = async { gate.await(ring) }
        runCurrent()
        worker.cancel()
        val foreground = async { gate.await(ring) }
        runCurrent()
        assertTrue(gate.isPending(ring))
        assertFalse(foreground.isCompleted)
        gate.aclDisconnected(ring)
        runCurrent()
        assertTrue(foreground.await())
        assertTrue(worker.isCancelled)
    }

    @Test
    fun `a missing broadcast requires scanning at timeout rather than blind reconnect`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        gate.gattClosed(old)
        val connect = async { gate.await(ring) }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(connect.await())
        assertTrue("other clients must not bypass the missing confirmation", gate.isPending(ring))
        assertFalse(gate.await(ring))
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun `cached advertisement cannot substitute for missing disconnect confirmation`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        gate.gattClosed(old)
        assertFalse("advertising alone cannot skip the normal physical wait",
            gate.freshAdvertisement(ring, 100))
        val connect = async { gate.await(ring) }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(connect.await())
        assertFalse(gate.freshAdvertisement(ring, 4_999_000_000))
        assertFalse(gate.freshAdvertisement(ring, 5_000_000_000))
        assertTrue(gate.freshAdvertisement(ring, 5_001_000_000))
        assertTrue(gate.await(ring))
    }

    @Test
    fun `fresh advertisement must also be newer than the actual GATT close`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        val connect = async { gate.await(ring) }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(connect.await())
        assertFalse(gate.freshAdvertisement(ring, 5_001_000_000))
        advanceTimeBy(100)
        gate.gattClosed(old)
        assertFalse(gate.freshAdvertisement(ring, 5_001_000_000))
        assertTrue(gate.freshAdvertisement(ring, 5_101_000_000))
    }

    @Test
    fun `a late disconnect broadcast releases the scan fallback normally`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        gate.gattClosed(old)
        val connect = async { gate.await(ring) }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(connect.await())
        gate.aclDisconnected(ring)
        assertFalse(gate.isPending(ring))
        assertTrue(gate.await(ring))
    }

    @Test
    fun `bluetooth off clears physical waits after their handles close`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        val old = gate.begin(ring)
        val connect = async { gate.await(ring) }
        runCurrent()
        gate.bluetoothOff()
        runCurrent()
        assertFalse(connect.isCompleted)
        gate.gattClosed(old)
        runCurrent()
        assertTrue(connect.await()) // The caller still checks Bluetooth and user intent before connecting.
    }

    @Test
    fun `an unrelated ring is not delayed by pending CRP teardown`() = runTest {
        val gate = CRPTeardownGate({ testScheduler.currentTime * 1_000_000 })
        gate.begin(ring)
        assertTrue(gate.await("another-ring"))
        assertTrue(gate.isPending(ring))
    }
}
