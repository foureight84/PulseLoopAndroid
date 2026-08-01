package com.pulseloop.ring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRerouteTest {

    /** Exactly what itspuia's R09 reported in `onSearchComplete` (issue #29 diagnostics). */
    private val r09Services = listOf(
        "0000fef5-0000-1000-8000-00805f9b34fb",
        "000056ff-0000-1000-8000-00805f9b34fb",
        "0000ff12-0000-1000-8000-00805f9b34fb",
        "00001800-0000-1000-8000-00805f9b34fb",
        "00001801-0000-1000-8000-00805f9b34fb",
        "0000180f-0000-1000-8000-00805f9b34fb",
        "0000180a-0000-1000-8000-00805f9b34fb",
        "00001812-0000-1000-8000-00805f9b34fb",
    )

    @Test
    fun `re-routes a 56ff ring stranded on the YCBT driver by the carousel pick`() {
        assertTrue(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = listOf(YCBTUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
                scanDetectedType = RingDeviceType.JRING,
            )
        )
    }

    @Test
    fun `leaves a ring that genuinely speaks its selected protocol alone`() {
        // Both services present: the YCBT pick is correct, so 56ff must not steal it.
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services + YCBTUUIDs.SERVICE,
                activeDeclaredServices = listOf(YCBTUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
                scanDetectedType = RingDeviceType.JRING,
            )
        )
    }

    @Test
    fun `no-ops when the jring driver is already installed`() {
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = listOf(RingUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.JRING,
                scanDetectedType = RingDeviceType.JRING,
            )
        )
    }

    @Test
    fun `does not fire for a ring with no 56ff service`() {
        // zaggash's CRP R11: the CRP block re-routes it; this one must stay out of the way.
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = listOf(CRPUUIDs.SERVICE, "0000180a-0000-1000-8000-00805f9b34fb"),
                activeDeclaredServices = listOf(CRPUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.CRP,
                scanDetectedType = RingDeviceType.JRING,
            )
        )
    }

    @Test
    fun `matches service UUIDs case-insensitively`() {
        assertTrue(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services.map { it.uppercase() },
                activeDeclaredServices = listOf(YCBTUUIDs.SERVICE.uppercase()),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
                scanDetectedType = RingDeviceType.JRING,
            )
        )
    }

    @Test
    fun `a driver declaring no service of its own is left alone`() {
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = emptyList(),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
                scanDetectedType = RingDeviceType.JRING,
            )
        )
    }

    @Test
    fun `never steals a ring the scanner confidently matched to its own family`() {
        // A ring whose advertisement carried a real Colmi signature (name pattern or service UUID)
        // is not the ambiguous "SMART_RING" case this re-route exists for — `connectTo` never
        // overrode anything here, so there is no wrong guess to undo.
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = listOf(YCBTUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
                scanDetectedType = RingDeviceType.COLMI_SMART_HEALTH,
            )
        )
    }

    @Test
    fun `never strands a Colmi ring whose UART profile is missing from the table`() {
        // The regression this guard exists to prevent. Root AGENTS.md records one hedged suspicion,
        // for the R11, that the Colmi UART (6e40fff0/de5bf728) is gated behind the OS bond — so an
        // unbonded first connect could show a table without it. Unproven and single-model, but the
        // failure would be self-sealing: the model re-resolves to generic JRING, requiresOsBond goes
        // false, the bond that would reveal the Colmi profile never fires, and the jring family is
        // persisted for every later reconnect.
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = listOf(ColmiUUIDs.SERVICE_V1, ColmiUUIDs.SERVICE_V2),
                activeDeviceType = RingDeviceType.COLMI_R02,
                scanDetectedType = RingDeviceType.COLMI_R02,
            )
        )
    }

    @Test
    fun `stays out of a direct reconnect that had no scan classification`() {
        assertFalse(
            DriverReroute.shouldRerouteToJring(
                discoveredServices = r09Services,
                activeDeclaredServices = listOf(YCBTUUIDs.SERVICE),
                activeDeviceType = RingDeviceType.COLMI_SMART_HEALTH,
                scanDetectedType = null,
            )
        )
    }
}
